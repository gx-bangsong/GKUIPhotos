/* Normalized, gap-free collage layouts shared by preview and export.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

internal data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun collageCells(count: Int, layout: Int, split: Float = 0.5f): List<Cell> {
    require(count in 2..6)
    val divider = split.coerceIn(0.2f, 0.8f)
    // A distinct geometry, rather than just a different fill order, for four photos.
    if (count == 4 && layout == 1) return listOf(
        Cell(0f, 0f, divider, 1f),
        Cell(divider, 0f, 1f, 1f / 3f),
        Cell(divider, 1f / 3f, 1f, 2f / 3f),
        Cell(divider, 2f / 3f, 1f, 1f),
    )
    val rows = (count + 1) / 2
    return (0 until count).map { index ->
        val row = index / 2
        val columns = if (row == rows - 1 && count % 2 == 1) 1 else 2
        val col = index % 2
        val left = if (columns == 1 || col == 0) 0f else divider
        val right = if (columns == 1 || col == 1) 1f else divider
        val cell = Cell(left, row.toFloat() / rows, right, (row + 1f) / rows)
        if (layout == 0) cell else Cell(cell.top, cell.left, cell.bottom, cell.right)
    }
}

/** Destination rectangle for a center-cropped, panned photo in canvas coordinates. */
internal fun collageDestination(cell: Cell, imageWidth: Int, imageHeight: Int, zoom: Float, x: Float, y: Float): Cell {
    require(imageWidth > 0 && imageHeight > 0 && zoom >= 1f)
    val cellWidth = cell.right - cell.left
    val cellHeight = cell.bottom - cell.top
    val scale = maxOf(cellWidth / imageWidth, cellHeight / imageHeight) * zoom
    val width = imageWidth * scale
    val height = imageHeight * scale
    val centerX = (cell.left + cell.right) / 2f + x.coerceIn(-1f, 1f) * (width - cellWidth) / 2f
    val centerY = (cell.top + cell.bottom) / 2f + y.coerceIn(-1f, 1f) * (height - cellHeight) / 2f
    return Cell(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
}

/** Moves a photo while keeping the same photo selected, even for duplicate URIs. */
internal fun <T> moveCollageItem(items: MutableList<T>, selected: Int, from: Int, to: Int): Int {
    require(from in items.indices && to in items.indices && selected in items.indices)
    if (from == to) return selected
    items.add(to, items.removeAt(from))
    return when {
        selected == from -> to
        from < selected && selected <= to -> selected - 1
        to <= selected && selected < from -> selected + 1
        else -> selected
    }
}
