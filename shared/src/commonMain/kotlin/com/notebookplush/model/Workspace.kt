package com.notebookplush.model

data class Document(
    val id: Long,
    val name: String = "untitled.json",
    val text: String = "",
    val sourceUri: String? = null,
    val savedText: String = "",
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
) {
    val modified: Boolean get() = text != savedText
}

data class Workspace(
    val documents: List<Document> = listOf(Document(1)),
    val activeId: Long = documents.first().id,
) {
    init {
        require(documents.isNotEmpty())
        require(documents.map { it.id }.distinct().size == documents.size)
        require(documents.any { it.id == activeId })
    }
    val active: Document get() = documents.first { it.id == activeId }
    fun update(id: Long, change: (Document) -> Document): Workspace =
        copy(documents = documents.map { if (it.id == id) change(it) else it })
    fun select(id: Long): Workspace = if (documents.any { it.id == id }) copy(activeId = id) else this
    fun add(document: Document): Workspace = copy(documents = documents + document, activeId = document.id)
    fun close(id: Long): Workspace = close(setOf(id))
    fun close(ids: Set<Long>, keepId: Long? = null): Workspace {
        val remaining = documents.filterNot { it.id in ids }
        if (remaining.isEmpty()) return Workspace(listOf(Document(nextId())))
        val next = when {
            remaining.any { it.id == keepId } -> requireNotNull(keepId)
            remaining.any { it.id == activeId } -> activeId
            else -> documents.drop(documents.indexOfFirst { it.id == activeId } + 1)
                .firstOrNull { it.id !in ids }?.id ?: remaining.last().id
        }
        return Workspace(remaining, next)
    }
    fun nextId(): Long = (documents.maxOfOrNull { it.id } ?: 0) + 1
}
