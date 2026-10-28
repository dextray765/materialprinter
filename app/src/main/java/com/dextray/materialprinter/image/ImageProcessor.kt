package com.dextray.materialprinter.image

import android.graphics.Bitmap
import android.graphics.Color
import com.dextray.materialprinter.protocol.PrinterProtocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class DitherMode {
    FLOYD_STEINBERG,
    THRESHOLD
}

data class ProcessedImageResult(
    val previewBitmap: Bitmap,
    val rasterBytes: ByteArray,
    val widthBytes: Int,
    val height: Int
)

object ImageProcessor {

    /**
     * Resizes, applies contrast/brightness, dithering/binarization,
     * and generates:
     * 1. An UPRIGHT preview Bitmap (for human eyes on the phone screen).
     * 2. An ESC/POS 1-bit raster byte buffer (rotated 180° if needed for printer feed).
     */
    suspend fun processBitmap(
        source: Bitmap,
        targetWidth: Int = PrinterProtocol.PRINTER_WIDTH,
        rotate180: Boolean = true,
        ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
        threshold: Int = 128,
        contrast: Float = 1.0f,
        brightness: Int = 0,
        invertColors: Boolean = false
    ): ProcessedImageResult {
        val coroutineContext = currentCoroutineContext()
        // 1. Calculate scaled dimensions preserving aspect ratio
        val scaledHeight = (source.height * (targetWidth.toFloat() / source.width.toFloat()))
            .toInt()
            .coerceAtLeast(1)

        val scaledBitmap = Bitmap.createScaledBitmap(source, targetWidth, scaledHeight, true)

        val width = scaledBitmap.width
        val height = scaledBitmap.height

        // Memory matters: a long receipt can have millions of dots. One IntArray is reused for everything
        // (source pixels first, then the black/white preview) and only two rows of gray values are kept.
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaledBitmap !== source) {
            scaledBitmap.recycle()
        }

        // isBlack[y * width + x] == true means the dot will be printed (black dot)
        val isBlack = BooleanArray(width * height)

        // 2-3. Grayscale (with contrast/brightness), then binarization / Floyd-Steinberg, row by row (UPRIGHT).
        // Floyd-Steinberg only ever pushes error into the current and the next row.
        var current = FloatArray(width)
        var next = FloatArray(width)
        fillGrayRow(pixels, 0, width, contrast, brightness, invertColors, current)

        for (y in 0 until height) {
            if ((y and 63) == 0) coroutineContext.ensureActive()
            if (y + 1 < height) {
                fillGrayRow(pixels, y + 1, width, contrast, brightness, invertColors, next)
            }
            val rowOffset = y * width
            for (x in 0 until width) {
                val oldVal = current[x]
                val black = oldVal < threshold
                isBlack[rowOffset + x] = black
                // The preview pixel of this row is no longer needed as source data: reuse the array
                pixels[rowOffset + x] = if (black) Color.BLACK else Color.WHITE

                if (ditherMode == DitherMode.FLOYD_STEINBERG) {
                    val newVal = if (black) 0f else 255f
                    val quantError = oldVal - newVal

                    // Diffuse error
                    if (x + 1 < width) {
                        current[x + 1] += quantError * (7f / 16f)
                    }
                    if (y + 1 < height) {
                        if (x > 0) {
                            next[x - 1] += quantError * (3f / 16f)
                        }
                        next[x] += quantError * (5f / 16f)
                        if (x + 1 < width) {
                            next[x + 1] += quantError * (1f / 16f)
                        }
                    }
                }
            }
            val swap = current
            current = next
            next = swap
        }

        // 4. Build packed 1-bit raster data for printer
        // If rotate180 == true, sample (width - 1 - x, height - 1 - y) so paper spewing out is right-side up!
        val widthBytes = (width + 7) / 8 // 48 bytes for 384 dots
        val rasterBytes = ByteArray(widthBytes * height)

        for (y in 0 until height) {
            if ((y and 63) == 0) coroutineContext.ensureActive()
            val rasterRowOffset = y * widthBytes
            val srcY = if (rotate180) height - 1 - y else y

            for (byteIdx in 0 until widthBytes) {
                var byteVal = 0
                val bitOffsetBase = byteIdx * 8

                for (bit in 0 until 8) {
                    val x = bitOffsetBase + bit
                    val srcX = if (rotate180) width - 1 - x else x

                    if (srcX in 0 until width && srcY in 0 until height) {
                        val blackDot = isBlack[srcY * width + srcX]
                        if (blackDot) {
                            // MSB first: 1 bit = thermal element on (black dot)
                            byteVal = byteVal or (1 shl (7 - bit))
                        }
                    }
                }
                rasterBytes[rasterRowOffset + byteIdx] = byteVal.toByte()
            }
        }

        // 5. Build preview only after processing completes, so cancelled work doesn't allocate it.
        val previewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        previewBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        return ProcessedImageResult(
            previewBitmap = previewBitmap,
            rasterBytes = rasterBytes,
            widthBytes = widthBytes,
            height = height
        )
    }

    /** Gray value (0..255) of every dot of one row, after transparency, contrast, brightness and inversion. */
    private fun fillGrayRow(
        pixels: IntArray,
        y: Int,
        width: Int,
        contrast: Float,
        brightness: Int,
        invertColors: Boolean,
        out: FloatArray
    ) {
        val rowOffset = y * width
        for (x in 0 until width) {
            val color = pixels[rowOffset + x]
            val a = (color ushr 24) and 0xFF
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Standard luminance
            var lum = 0.299f * r + 0.587f * g + 0.114f * b

            // Transparent pixels must become paper (white), not black:
            // getPixels() returns fully transparent pixels as 0x00000000.
            if (a < 255) {
                lum = (lum * a + 255f * (255 - a)) / 255f
            }

            // Apply brightness and contrast
            lum = ((lum - 128f) * contrast + 128f + brightness).coerceIn(0f, 255f)

            if (invertColors) {
                lum = 255f - lum
            }

            out[x] = lum
        }
    }
}
