package com.dextray.materialprinter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Loads a picture for printing:
 *  - applies the EXIF orientation (BitmapFactory ignores it, so camera photos came out sideways),
 *  - decodes a down-sampled copy instead of the full-size bitmap (large photos caused OutOfMemoryError).
 *
 * The result always uses a software bitmap config, so getPixels() works on it.
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    /** The shorter side of the decoded image stays at least this big: 3x the 384-dot print width. */
    private const val MIN_SIDE_AFTER_SAMPLING = 1152
    /** Avoid decoding huge panoramas whose short side alone would otherwise prevent down-sampling. */
    private const val MAX_SIDE_AFTER_SAMPLING = 4096

    fun load(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadWithImageDecoder(context, uri)
            } else {
                loadLegacy(context, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Image is too large to load", e)
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (
            minOf(width, height) / (sample * 2) >= MIN_SIDE_AFTER_SAMPLING ||
            maxOf(width, height) / (sample * 2) > MAX_SIDE_AFTER_SAMPLING
        ) {
            sample *= 2
        }
        return sample
    }

    // API 28+: ImageDecoder applies the EXIF orientation by itself.
    @RequiresApi(Build.VERSION_CODES.P)
    private fun loadWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
        }
    }

    // API 26-27
    @Suppress("DEPRECATION")
    private fun loadLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val exifOrientation: Int? = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            null
        }
        return applyOrientation(decoded, exifOrientation ?: ExifInterface.ORIENTATION_NORMAL)
    }

    @Suppress("DEPRECATION")
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }
}
