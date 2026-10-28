package com.dextray.materialprinter.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dextray.materialprinter.ui.theme.LedAmber
import com.dextray.materialprinter.ui.theme.PrinterSlotBottom
import com.dextray.materialprinter.ui.theme.PrinterSlotTop
import com.dextray.materialprinter.ui.theme.ReceiptPaperHint
import com.dextray.materialprinter.ui.theme.ReceiptPaperWhite

/** A straight top edge and a serrated bottom edge: the tear-off edge of a receipt. */
private class TornEdgeShape(
    private val toothWidth: Dp,
    private val toothHeight: Dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tooth = with(density) { toothWidth.toPx() }.coerceAtLeast(2f)
        val depth = with(density) { toothHeight.toPx() }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - depth)
            var x = size.width
            var down = true
            while (x > 0f) {
                x = (x - tooth / 2f).coerceAtLeast(0f)
                lineTo(x, if (down) size.height else size.height - depth)
                down = !down
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * The receipt coming out of the printer slot - the one expressive element of the app.
 * The slot glows amber while the printer is working.
 */
@Composable
fun ThermalPaperPreview(
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    widthDots: Int = 384,
    emptyMessage: String = "Предпросмотр чека",
    isPrinting: Boolean = false
) {
    val slotGlow by animateColorAsState(
        targetValue = if (isPrinting) LedAmber else Color(0x33FFFFFF),
        label = "slotGlow"
    )
    val paperShape = remember { TornEdgeShape(toothWidth = 12.dp, toothHeight = 6.dp) }
    val shownBitmap = remember(previewBitmap) { previewBitmap?.let { limitForDisplay(it) } }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Printer slot
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.verticalGradient(listOf(PrinterSlotTop, PrinterSlotBottom)))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.94f)
                    .height(2.dp)
                    .background(slotGlow)
            )
        }

        // Paper
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .shadow(elevation = 4.dp, shape = paperShape)
                .clip(paperShape)
                .background(ReceiptPaperWhite)
                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            if (shownBitmap != null) {
                Image(
                    bitmap = shownBitmap.asImageBitmap(),
                    contentDescription = "Предпросмотр чека",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ReceiptPaperHint
                    )
                }
            }
        }

        Text(
            text = "$widthDots точек в ширину",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/** Many GPUs cannot draw a bitmap taller than 4096 px (it silently stays blank), so a very long receipt is scaled down for display only. */
private fun limitForDisplay(bitmap: Bitmap): Bitmap {
    val maxHeight = 4096
    if (bitmap.height <= maxHeight) return bitmap
    val scale = maxHeight.toFloat() / bitmap.height
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), maxHeight, true)
}
