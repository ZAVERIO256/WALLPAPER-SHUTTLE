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

        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)

        val lockWidth = manager.desiredMinimumWidth
            .takeIf { it > 0 } ?: screenWidth
        val lockHeight = manager.desiredMinimumHeight
            .takeIf { it > 0 } ?: screenHeight

        // Read source bounds first. A null bitmap is expected for inJustDecodeBounds.
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(
                "Cannot open the selected image. Re-select the image folder and grant access."
            )

        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("This image is invalid or has an unsupported format.")
        }

        // Decode a memory-conscious source bitmap once, then prepare each screen separately.
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
        } ?: throw IllegalStateException(
            "Image decoding failed. Try selecting another picture."
        )

        if (source.width <= 0 || source.height <= 0) {
            source.recycle()
            throw IllegalStateException("The selected image could not be decoded.")
        }

        fun createScreenBitmap(targetWidth: Int, targetHeight: Int): Bitmap {
            // Cap the output size to avoid excessive memory use on large screens.
            val scaleLimit = min(
                1f,
                min(
                    4096f / max(targetWidth, targetHeight),
                    sqrt(8_000_000f / (targetWidth.toFloat() * targetHeight))
                )
            )
            val outputWidth = (targetWidth * scaleLimit).toInt().coerceAtLeast(1)
            val outputHeight = (targetHeight * scaleLimit).toInt().coerceAtLeast(1)

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
                return output
            } catch (e: Exception) {
                output.recycle()
                throw e
            }
        }

        try {
            val homeRequested = requested and WallpaperManager.FLAG_SYSTEM != 0
            val lockRequested = requested and WallpaperManager.FLAG_LOCK != 0

            var homeId = -1
            var lockId = -1
            var homeError: Exception? = null
            var lockError: Exception? = null

            if (homeRequested) {
                val homeBitmap = createScreenBitmap(screenWidth, screenHeight)
                try {
                    // Use the physical display size for Home so multi-page launchers
                    // have no extra-wide image to pan across their workspace.
                    homeId = manager.setBitmap(
                        homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM
                    )
                } catch (e: Exception) {
                    homeError = e
                } finally {
                    homeBitmap.recycle()
                }
            }

            if (lockRequested) {
                val lockBitmap = createScreenBitmap(lockWidth, lockHeight)
                try {
                    // Preserve the device's preferred wallpaper dimensions for Lock.
                    lockId = manager.setBitmap(
                        lockBitmap, null, true, WallpaperManager.FLAG_LOCK
                    )
                } catch (e: Exception) {
                    lockError = e
                } finally {
                    lockBitmap.recycle()
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
        }
    }
}
