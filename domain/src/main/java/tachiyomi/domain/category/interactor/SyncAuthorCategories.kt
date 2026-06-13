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
         * "作者名（别名）" -> "作者名"
         * "作者名(Alias)" -> "作者名"
         * "作者名[Note]" -> "作者名"
         */
        fun normalizeAuthor(author: String): String {
            var result = author.trim()
            // Remove content in various bracket types
            result = result.replace(Regex("[（(\\[【].*[）)\\]】]"), "")
            // Remove trailing comma/semicolon
            result = result.replace(Regex("[,;，；]\\s*$"), "")
            // Trim and capitalize
            result = result.trim()
            return if (result.isEmpty()) author.trim() else result
        }
    }

    suspend fun sync() {
        // Get all favorite manga with author info
        val favorites = mangaRepository.getFavorites()

        // Build author mapping: normalized -> list of (mangaId, originalAuthor)
        val authorMangaMap = mutableMapOf<String, MutableList<Pair<Long, String>>>()

        favorites.forEach { manga ->
            val author = manga.author?.trim()
            if (!author.isNullOrEmpty()) {
                val normalized = normalizeAuthor(author).lowercase()
                authorMangaMap.getOrPut(normalized) { mutableListOf() }
                    .add(manga.id to author)
            } else {
                // No author - add to unknown group
                authorMangaMap.getOrPut("") { mutableListOf() }
                    .add(manga.id to "")
            }
        }

        // Get existing categories
        val existingCategories = categoryRepository.getAll()
        val existingAuthorCategories = existingCategories.filter { it.id <= UNKNOWN_AUTHOR_ID }
        val existingAuthorMap = existingAuthorCategories.associateBy {
            it.name.lowercase().trim()
        }

        // Process each author group
        var categoryId = AUTHOR_CATEGORY_ID_START
        val processedAuthorNames = mutableSetOf<String>()

        authorMangaMap.forEach { (normalizedAuthor, mangaPairs) ->
            if (normalizedAuthor.isEmpty()) {
                // Handle unknown author
                processedAuthorNames.add("")
                val existingId = existingAuthorMap[""]?.id ?: UNKNOWN_AUTHOR_ID

                // Ensure unknown author category exists
                if ("" !in existingAuthorMap) {
                    categoryRepository.insert(
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

            processedAuthorNames.add(normalizedAuthor)

            // Get display name (use the longest original name for better display)
            val displayName = mangaPairs.map { it.second }
                .distinct()
                .maxByOrNull { it.length }
                ?.let { capitalizeName(normalizeAuthor(it)) }
                ?: capitalizeName(normalizedAuthor)

            // Find or create category
            val existingCategoryId = existingAuthorMap[normalizedAuthor]?.id
            val catId = existingCategoryId ?: run {
                val newId = categoryId--
                categoryRepository.insert(
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

    suspend fun getAuthorCategories(): List<Category> {
        return categoryRepository.getAll().filter { it.id <= UNKNOWN_AUTHOR_ID }
    }
}
