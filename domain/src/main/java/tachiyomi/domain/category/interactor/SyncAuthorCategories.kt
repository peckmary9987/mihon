package tachiyomi.domain.category.interactor

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

class SyncAuthorCategories(
    private val categoryRepository: CategoryRepository,
    private val mangaRepository: MangaRepository,
) {
    // Author categories use IDs starting from -10000 to avoid conflicts
    companion object {
        const val AUTHOR_CATEGORY_ID_START = -10000L
        const val UNKNOWN_AUTHOR_ID = -9999L
    }

    suspend fun sync() {
        // Get all distinct authors from favorite manga
        val authors = mangaRepository.getDistinctAuthors()

        // Get all existing categories
        val existingCategories = categoryRepository.getAll()

        // Find existing author categories (negative IDs)
        val existingAuthorCategories = existingCategories.filter { it.id <= UNKNOWN_AUTHOR_ID }

        // Create a map of existing author names (lowercase) to category IDs
        val existingAuthorMap = existingAuthorCategories.associateBy {
            it.name.lowercase().trim()
        }

        // Track which authors we've processed
        val processedAuthors = mutableSetOf<String>()

        // Create or update author categories
        authors.forEachIndexed { index, author ->
            val normalizedAuthor = author.lowercase().trim()
            processedAuthors.add(normalizedAuthor)

            val categoryId = AUTHOR_CATEGORY_ID_START - index
            val displayName = author.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }

            if (normalizedAuthor !in existingAuthorMap) {
                // Create new author category
                categoryRepository.insert(
                    Category(
                        id = categoryId,
                        name = displayName,
                        order = categoryId,
                        flags = 0L,
                    )
                )
            }
        }

        // Remove author categories that no longer have any manga
        existingAuthorCategories.forEach { category ->
            val authorName = category.name.lowercase().trim()
            if (authorName !in processedAuthors) {
                // Delete the category and its associations
                categoryRepository.delete(category.id)
            }
        }

        // Update manga-category associations
        val allCategories = categoryRepository.getAll()
        val authorCategoryMap = allCategories
            .filter { it.id <= UNKNOWN_AUTHOR_ID }
            .associateBy { it.name.lowercase().trim() }

        // For each author, get their manga and update categories
        authors.forEach { author ->
            val normalizedAuthor = author.lowercase().trim()
            val categoryId = authorCategoryMap[normalizedAuthor]?.id ?: return@forEach

            val mangaIds = mangaRepository.getMangaIdsByAuthor(author)

            mangaIds.forEach { mangaId ->
                // Get current categories for this manga
                val currentCategories = categoryRepository.getCategoriesByMangaId(mangaId)
                val currentCategoryIds = currentCategories.map { it.id }.toMutableList()

                // Add author category if not already present
                if (categoryId !in currentCategoryIds) {
                    currentCategoryIds.add(categoryId)
                    mangaRepository.setMangaCategories(mangaId, currentCategoryIds)
                }
            }
        }
    }

    suspend fun getAuthorCategories(): List<Category> {
        return categoryRepository.getAll().filter { it.id <= UNKNOWN_AUTHOR_ID }
    }
}
