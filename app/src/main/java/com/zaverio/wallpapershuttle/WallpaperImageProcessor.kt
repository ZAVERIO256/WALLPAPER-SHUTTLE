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
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object WallpaperImageProcessor {

    fun apply(
        context: Context,
        uri: Uri,
        mode: String,
        destination: Int
    ): Int {
        val manager = WallpaperManager.getInstance(context)
        val metrics = context.resources.displayMetrics
        val validFlags = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        val requested = destination and validFlags

        if (requested == 0) {
            throw IllegalArgumentException("Choose Home, Lock or Both in Settings.")
        }

        val desiredWidth = manager.desiredMinimumWidth
            .takeIf { it > 0 } ?: metrics.widthPixels

        val desiredHeight = manager.desiredMinimumHeight
            .takeIf { it > 0 } ?: metrics.heightPixels

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

        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open the selected image. Re-select the image folder and grant access.")

        // Bounds-only decoding intentionally returns null. Its purpose is to
        // populate outWidth/outHeight, so do not treat that null as a read error.
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("This image is invalid or has an unsupported format.")
        }

        var sampleSize = 1
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight
        while (sourcePixels / ((sampleSize * 2L) * (sampleSize * 2L)) > 12_000_000L) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("Image decoding failed. Try another picture.")

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

            // Apply each screen individually on Both for clearer device compatibility
            // and report success only when Android returns a non-zero wallpaper ID.
            val homeRequested = requested and WallpaperManager.FLAG_SYSTEM != 0
            val lockRequested = requested and WallpaperManager.FLAG_LOCK != 0

            var homeId = -1
            var lockId = -1
            var homeError: Exception? = null
            var lockError: Exception? = null

            if (homeRequested) {
                try {
                    homeId = manager.setBitmap(
                        output, null, true, WallpaperManager.FLAG_SYSTEM
                    )
                } catch (e: Exception) {
                    homeError = e
                }
            }

            if (lockRequested) {
                try {
                    lockId = manager.setBitmap(
                        output, null, true, WallpaperManager.FLAG_LOCK
                    )
                } catch (e: Exception) {
                    lockError = e
                }
            }

            val homeSucceeded = !homeRequested || homeId > 0
            val lockSucceeded = !lockRequested || lockId > 0

            if (!homeSucceeded || !lockSucceeded) {
                val detail = buildList {
                    if (homeRequested && !homeSucceeded) {
                        add("Home screen: " + (homeError?.message ?: "Android returned wallpaper ID 0"))
                    }
                    if (lockRequested && !lockSucceeded) {
                        add("Lock screen: " + (lockError?.message ?: "Android returned wallpaper ID 0"))
                    }
                }.joinToString("; ")
                val succeeded = buildList {
                    if (homeRequested && homeSucceeded) add("Home")
                    if (lockRequested && lockSucceeded) add("Lock")
                }
                val prefix = if (succeeded.isEmpty()) {
                    "Android did not apply the wallpaper."
                } else {
                    "Wallpaper changed on ${succeeded.joinToString()} only."
                }
                throw IOException("$prefix $detail")
            }

            return if (lockRequested) lockId else homeId
        } finally {
            source.recycle()
            output.recycle()
        }
    }
}
