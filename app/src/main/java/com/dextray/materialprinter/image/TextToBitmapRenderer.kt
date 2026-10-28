package com.dextray.materialprinter.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.dextray.materialprinter.protocol.PrinterProtocol

enum class TextAlignOption {
    LEFT,
    CENTER,
    RIGHT
}

object TextToBitmapRenderer {

    /**
     * Renders multiline text into a Bitmap of width 384, exactly fitting the cat printer paper.
     */
    fun renderTextToBitmap(
        text: String,
        textSizePx: Float = 26f,
        align: TextAlignOption = TextAlignOption.LEFT,
        isBold: Boolean = false,
        isMonospace: Boolean = false,
        invertColors: Boolean = false,
        width: Int = PrinterProtocol.PRINTER_WIDTH
    ): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            color = if (invertColors) Color.WHITE else Color.BLACK

            var style = Typeface.NORMAL
            if (isBold) style = style or Typeface.BOLD

            val baseFamily = if (isMonospace) Typeface.MONOSPACE else Typeface.DEFAULT
            typeface = Typeface.create(baseFamily, style)
        }

        val layoutAlignment = when (align) {
            TextAlignOption.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignOption.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignOption.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

        // Keep a small margin on both sides: the text must not touch the edge of the paper
        val sideMargin = 8
        val layoutWidth = (width - sideMargin * 2).coerceAtLeast(16)

        val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
                .setAlignment(layoutAlignment)
                .setIncludePad(false)
                .setLineSpacing(4f, 1.1f)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                layoutWidth,
                layoutAlignment,
                1.1f,
                4f,
                false
            )
        }

        val verticalPadding = 20
        val height = (staticLayout.height + verticalPadding * 2).coerceAtLeast(40)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill background
        canvas.drawColor(if (invertColors) Color.BLACK else Color.WHITE)

        // Draw text
        canvas.save()
        canvas.translate(sideMargin.toFloat(), verticalPadding.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
