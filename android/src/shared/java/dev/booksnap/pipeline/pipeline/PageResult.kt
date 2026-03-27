package dev.booksnap.pipeline

data class BoundingBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class PageResult(
    val text: String,
    val textBounds: BoundingBox = BoundingBox(0, 0, 0, 0),
    val pageNumber: Int? = null,
    val pageNumberBounds: BoundingBox? = null,
)
