package com.zaverio.wallpapershuttle

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object WallpaperImageProcessor {

    fun apply(
        context: Context,
        uri: Uri,
        mode: String,
        destination: Int
    ) {
        val manager = WallpaperManager.getInstance(context)
        val metrics = context.resources.displayMetrics

        val desiredWidth = manager.desiredMinimumWidth
            .takeIf { it > 0 } ?: metrics.widthPixels

        val desiredHeight = manager.desiredMinimumHeight
            .takeIf { it > 0 } ?: metrics.heightPixels

        // Limit output dimensions to control memory usage.
        val dimensionScale = min(
            1f,
            min(
                4096f / max(desiredWidth, desiredHeight),
                sqrt(8_000_000f / (desiredWidth.toFloat() * desiredHeight))
            )
        )

        val outputWidth = (desiredWidth * dimensionScale)
            .toInt().coerceAtLeast(1)

        val outputHeight = (desiredHeight * dimensionScale)
            .toInt().coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("Cannot read selected image.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("Invalid or unsupported image.")
        }

        // Avoid unnecessarily large source bitmaps.
        var sampleSize = 1
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight

        while (sourcePixels / ((sampleSize * 2L) * (sampleSize * 2L)) >
            12_000_000L
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("Image decoding failed.")

        val output = Bitmap.createBitmap(
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )

        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)

            val sourceWidth = source.width.toFloat()
            val sourceHeight = source.height.toFloat()

            val scaleX = outputWidth / sourceWidth
            val scaleY = outputHeight / sourceHeight

            val scale = when (mode.lowercase()) {
                "fit" -> min(scaleX, scaleY)
                "stretch" -> null
                else -> max(scaleX, scaleY)
            }

            val destinationRect = if (mode.equals("stretch", true)) {
                RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
            } else {
                val width = sourceWidth * scale!!
                val height = sourceHeight * scale

                val left = (outputWidth - width) / 2f
                val top = (outputHeight - height) / 2f

                RectF(left, top, left + width, top + height)
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            canvas.drawBitmap(source, null, destinationRect, paint)

            // Apply to the selected home/lock destination.
            manager.setBitmap(output, null, true, destination)
        } finally {
            source.recycle()
            output.recycle()
        }
    }
}
