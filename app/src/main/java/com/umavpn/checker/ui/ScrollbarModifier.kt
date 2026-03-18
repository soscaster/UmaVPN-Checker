package com.umavpn.checker.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = Color.Gray.copy(alpha = 0.5f),
    width: Dp = 4.dp,
): Modifier = this.drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return@drawWithContent
    val visible = info.visibleItemsInfo.size
    if (visible >= total) return@drawWithContent
    val barH = size.height * visible / total
    val frac = state.firstVisibleItemIndex.toFloat() / (total - visible)
    val offsetY = (size.height - barH) * frac
    drawRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), offsetY),
        size = Size(width.toPx(), barH),
    )
}
