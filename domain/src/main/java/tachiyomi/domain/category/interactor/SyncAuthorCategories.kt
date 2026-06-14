package tachiyomi.domain.category.interactor

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

class SyncAuthorCategories(
    private val categoryRepository: CategoryRepository,
    private val mangaRepository: MangaRepository,
) {
    companion object {
        const val AUTHOR_CATEGORY_ID_START = -10000L
        const val UNKNOWN_AUTHOR_ID = -9999L

        /**
         * Extract main author name by removing content in parentheses/brackets
         * "規制当局 (リヒャルト・バフマン)" -> "規制当局"
         * "作者名（别名）" -> "作者名"
         */
        fun extractMainName(author: String): String {
            var result = author.trim()
            // Remove content in various bracket types
            result = result.replace(Regex("[（(\\[【].*[）)\\]】]"), "")
            // Remove trailing comma/semicolon
            result = result.replace(Regex("[,;，；]\\s*$"), "")
            result = result.trim()
            return if (result.isEmpty()) author.trim() else result
        }

        /**
         * Extract alias from parentheses/brackets
         * "規制当局 (リヒャルト・バフマン)" -> "リヒャルト・バフマン"
         * "作者名（别名）" -> "别名"
         * "作者名" -> null
         */
        fun extractAlias(author: String): String? {
            val match = Regex("[（(\\[【](.*?)[）)\\]】]").find(author)
            return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Get all possible names for matching (main name + alias)
         * "規制当局 (リヒャルト・バフマン)" -> ["規制当局", "リヒャルト・バフマン"]
         */
        fun getAllNames(author: String): List<String> {
            val names = mutableListOf<String>()
            val mainName = extractMainName(author)
            names.add(mainName.lowercase())
            extractAlias(author)?.let { names.add(it.lowercase()) }
            return names.distinct()
        }
    }

    suspend fun sync() {
        try {
            syncInternal()
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
        }
    }

    private suspend fun syncInternal() {
        // Get all favorite manga with author info
        val favorites = mangaRepository.getFavorites()

        // Build author groups using alias matching
        // Key: canonical name (lowercase), Value: list of (mangaId, originalAuthor)
        val authorGroups = mutableMapOf<String, MutableList<Pair<Long, String>>>()
        // Map alias to canonical name
        val aliasToCanonical = mutableMapOf<String, String>()

        favorites.forEach { manga ->
            val author = manga.author?.trim()
            if (author.isNullOrEmpty()) {
                authorGroups.getOrPut("") { mutableListOf() }
                    .add(manga.id to "")
                return@forEach
            }

            val allNames = getAllNames(author)

            // Check if any of the names already has a group
            val existingCanonical = allNames.firstNotNullOfOrNull { aliasToCanonical[it] }
                ?: allNames.firstNotNullOfOrNull { authorGroups.keys.find { key -> key == it } }

            if (existingCanonical != null) {
                // Add to existing group
                authorGroups.getOrPut(existingCanonical) { mutableListOf() }
                    .add(manga.id to author)
                // Map all names to this canonical name
                allNames.forEach { aliasToCanonical[it] = existingCanonical }
            } else {
                // Create new group with main name as canonical
                val canonical = extractMainName(author).lowercase()
                authorGroups.getOrPut(canonical) { mutableListOf() }
                    .add(manga.id to author)
                allNames.forEach { aliasToCanonical[it] = canonical }
            }
        }

        // Get existing categories
        val existingCategories = categoryRepository.getAll()
        val existingAuthorCategories = existingCategories.filter { it.id <= UNKNOWN_AUTHOR_ID }
        val existingAuthorMap = existingAuthorCategories.associateBy {
            it.name.lowercase().trim()
        }

        // Find the minimum existing author category ID to avoid conflicts
        val existingIds = existingAuthorCategories.map { it.id }.toSet()
        var nextCategoryId = if (existingIds.isEmpty()) {
            AUTHOR_CATEGORY_ID_START
        } else {
            (existingIds.min() - 1).coerceAtMost(AUTHOR_CATEGORY_ID_START)
        }

        // Process each author group
        val processedAuthorNames = mutableSetOf<String>()

        authorGroups.forEach { (canonicalName, mangaPairs) ->
            if (canonicalName.isEmpty()) {
                // Handle unknown author
                processedAuthorNames.add("")
                val existingId = existingAuthorMap[""]?.id ?: UNKNOWN_AUTHOR_ID

                // Ensure unknown author category exists
                if ("" !in existingAuthorMap) {
                    categoryRepository.insertWithId(
                        Category(
                            id = UNKNOWN_AUTHOR_ID,
                            name = "Unknown author",
                            order = UNKNOWN_AUTHOR_ID,
                            flags = 0L,
                        )
                    )
                }

                // Assign manga to unknown author category
                mangaPairs.forEach { (mangaId, _) ->
                    assignMangaToCategory(mangaId, existingId)
                }
                return@forEach
            }

            processedAuthorNames.add(canonicalName)

            // Get display name (use the longest original name for better display)
            val displayName = mangaPairs.map { it.second }
                .distinct()
                .maxByOrNull { it.length }
                ?.let { extractMainName(it) }
                ?: canonicalName

            // Find or create category
            val existingCategoryId = existingAuthorMap[canonicalName]?.id
            val catId = existingCategoryId ?: run {
                // Find next available ID
                while (nextCategoryId in existingIds) {
                    nextCategoryId--
                }
                val newId = nextCategoryId--
                categoryRepository.insertWithId(
                    Category(
                        id = newId,
                        name = displayName,
                        order = newId,
                        flags = 0L,
                    )
                )
                newId
            }

            // Assign all manga in this group to the category
            mangaPairs.forEach { (mangaId, _) ->
                assignMangaToCategory(mangaId, catId)
            }
        }

        // Remove author categories that no longer have manga
        existingAuthorCategories.forEach { category ->
            val normalizedName = category.name.lowercase().trim()
            if (normalizedName !in processedAuthorNames && category.id != UNKNOWN_AUTHOR_ID) {
                categoryRepository.delete(category.id)
            }
        }
    }

    private suspend fun assignMangaToCategory(mangaId: Long, categoryId: Long) {
        val currentCategories = categoryRepository.getCategoriesByMangaId(mangaId)
        val currentCategoryIds = currentCategories.map { it.id }.toMutableList()

        if (categoryId !in currentCategoryIds) {
            currentCategoryIds.add(categoryId)
            mangaRepository.setMangaCategories(mangaId, currentCategoryIds)
        }
    }

    private fun capitalizeName(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Update author category for a single manga
     * Called when manga info changes or manga is added/removed from library
     */
    suspend fun syncManga(mangaId: Long, author: String?) {
        try {
            if (author.isNullOrBlank()) {
                // No author - assign to unknown
                ensureUnknownCategoryExists()
                assignMangaToCategory(mangaId, UNKNOWN_AUTHOR_ID)
                return
            }

            val existingCategories = categoryRepository.getAll()
            val existingAuthorCategories = existingCategories.filter { it.id <= UNKNOWN_AUTHOR_ID }
            val existingAuthorMap = existingAuthorCategories.associateBy {
                it.name.lowercase().trim()
            }

            // Get all possible names for this author
            val allNames = getAllNames(author)

            // Find existing category by checking all names
            val existingCategoryId = allNames.firstNotNullOfOrNull { name ->
                existingAuthorMap[name]?.id
            }

            val categoryId = existingCategoryId ?: run {
                // Create new category
                val existingIds = existingAuthorCategories.map { it.id }.toSet()
                var newId = if (existingIds.isEmpty()) {
                    AUTHOR_CATEGORY_ID_START
                } else {
                    (existingIds.min() - 1).coerceAtMost(AUTHOR_CATEGORY_ID_START)
                }
                while (newId in existingIds) {
                    newId--
                }

                val displayName = extractMainName(author)
                categoryRepository.insertWithId(
                    Category(
                        id = newId,
                        name = displayName,
                        order = newId,
                        flags = 0L,
                    )
                )
                newId
            }

            // Update manga's categories
            assignMangaToCategory(mangaId, categoryId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun ensureUnknownCategoryExists() {
        val existingCategories = categoryRepository.getAll()
        if (existingCategories.none { it.id == UNKNOWN_AUTHOR_ID }) {
            categoryRepository.insertWithId(
                Category(
                    id = UNKNOWN_AUTHOR_ID,
                    name = "Unknown author",
                    order = UNKNOWN_AUTHOR_ID,
                    flags = 0L,
                )
            )
        }
    }

    /**
     * Remove manga from all author categories
     * Called when manga is removed from library
     */
    suspend fun removeManga(mangaId: Long) {
        try {
            val currentCategories = categoryRepository.getCategoriesByMangaId(mangaId)
            val authorCategoryIds = currentCategories
                .filter { it.id <= UNKNOWN_AUTHOR_ID }
                .map { it.id }

            if (authorCategoryIds.isNotEmpty()) {
                val remainingCategories = currentCategories
                    .filter { it.id > UNKNOWN_AUTHOR_ID }
                    .map { it.id }
                mangaRepository.setMangaCategories(mangaId, remainingCategories)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getAuthorCategories(): List<Category> {
        return categoryRepository.getAll().filter { it.id <= UNKNOWN_AUTHOR_ID }
    }
}
