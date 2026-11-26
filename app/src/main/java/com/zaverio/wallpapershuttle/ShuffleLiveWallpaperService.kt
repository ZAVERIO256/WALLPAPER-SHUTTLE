package com.zaverio.wallpapershuttle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.TimeUnit

/**
 * System-managed live wallpaper renderer. Android owns this service while it
 * is selected as the active wallpaper, independently of MainActivity.
 */
class ShuffleLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = RotationEngine()

    inner class RotationEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bitmap: Bitmap? = null
        private var previousBitmap: Bitmap? = null
        private var transitionStartedAt = 0L
        private val transitionDurationMs = 650L
        private var imageUris: List<Uri> = emptyList()
        private var lastUri: Uri? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var destroyed = false
        private var surfaceReady = false

        private val fadeTask = object : Runnable {
            override fun run() {
                if (destroyed || !surfaceReady || previousBitmap == null) return
                drawFrame()
                if (previousBitmap != null) handler.postDelayed(this, 16L)
            }
        }

        private val rotateTask = object : Runnable {
            override fun run() {
                if (destroyed || !surfaceReady || !isVisible || !rotationEnabled()) return
                showNextImage()
                scheduleNextRotation()
            }
        }

        private fun preferences() =
            getSharedPreferences("shuffle_wallpaper", MODE_PRIVATE)

        private fun rotationEnabled() =
            preferences().getBoolean("rotation_enabled", false) &&
                preferences().getBoolean("live_wallpaper_mode", false)

        private fun intervalMillis(): Long {
            val seconds = preferences().getInt("interval_seconds", 5).coerceIn(5, 86400)
            return TimeUnit.SECONDS.toMillis(seconds.toLong())
        }

        private fun scheduleNextRotation() {
            handler.removeCallbacks(rotateTask)
            if (!destroyed && surfaceReady && isVisible && rotationEnabled()) {
                val nextAt = System.currentTimeMillis() + intervalMillis()
                preferences().edit()
                    .putLong("next_rotation_time", nextAt)
                    .putString("last_rotation_status", "Live wallpaper is active")
                    .apply()
                handler.postDelayed(rotateTask, intervalMillis())
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            handler.removeCallbacks(rotateTask)
            if (visible && rotationEnabled()) {
                if (surfaceReady) showNextImage()
                scheduleNextRotation()
            } else {
                preferences().edit()
                    .putLong("next_rotation_time", 0L)
                    .putString(
                        "last_rotation_status",
                        if (!rotationEnabled()) "Rotation is paused" else "Wallpaper is waiting until the screen is visible"
                    )
                    .apply()
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            surfaceReady = width > 0 && height > 0 && holder.surface?.isValid == true
            if (surfaceReady) {
                if (bitmap == null && isVisible && rotationEnabled()) showNextImage()
                else drawFrame()
                // Surface recreation must restart the timer; otherwise a
                // destroyed surface could leave rotation permanently stalled.
                if (previousBitmap != null) handler.post(fadeTask)
                scheduleNextRotation()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(rotateTask)
            preferences().edit()
                .putLong("next_rotation_time", 0L)
                .putString("last_rotation_status", "Wallpaper surface is being recreated")
                .apply()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            surfaceReady = false
            handler.removeCallbacksAndMessages(null)
            bitmap?.recycle()
            previousBitmap?.recycle()
            bitmap = null
            previousBitmap = null
            super.onDestroy()
        }

        private fun findImages(root: DocumentFile): List<Uri> {
            val found = mutableListOf<Uri>()
            val pending = ArrayDeque<DocumentFile>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val folder = pending.removeFirst()
                val children = try {
                    folder.listFiles()
                } catch (_: Exception) {
                    emptyArray()
                }
                for (file in children) {
                    try {
                        if (file.isDirectory) pending.add(file)
                        else if (file.isFile && (
                            file.type?.startsWith("image/", true) == true ||
                                file.name?.lowercase()?.let { name ->
                                    listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")
                                        .any(name::endsWith)
                                } == true
                            )
                        ) found.add(file.uri)
                    } catch (_: Exception) {
                        // Ignore individual files that disappear or become inaccessible.
                    }
                }
            }
            return found
        }

        private fun setStatus(message: String) {
            preferences().edit().putString("last_rotation_status", message).apply()
        }

        private fun showNextImage() {
            if (!rotationEnabled()) {
                setStatus("Rotation is paused")
                return
            }

            val prefs = preferences()
            val folderString = prefs.getString("folder_uri", null)
            if (folderString.isNullOrBlank()) {
                setStatus("Choose a wallpaper folder in the app")
                return
            }

            val root = try {
                DocumentFile.fromTreeUri(this@ShuffleLiveWallpaperService, Uri.parse(folderString))
            } catch (_: Exception) {
                null
            }
            if (root == null || !root.canRead()) {
                setStatus("Folder access is unavailable. Re-select the folder in the app")
                return
            }

            // Refresh the folder list each cycle so newly added and removed
            // pictures are reflected without reopening the app.
            imageUris = findImages(root)
            val excluded = prefs.getStringSet("excluded_uris", emptySet())?.toSet() ?: emptySet()
            val eligible = imageUris.filterNot { it.toString() in excluded }
            if (eligible.isEmpty()) {
                setStatus(if (imageUris.isEmpty()) "No supported images found in the selected folder" else "All images are excluded")
                return
            }

            if (lastUri == null) {
                lastUri = prefs.getString("last_image_uri", null)?.let(Uri::parse)
            }
            val candidates = eligible.filter { it != lastUri }.ifEmpty { eligible }
            val chosen = candidates.random()
            val decoded = try {
                contentResolver.openInputStream(chosen)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }

            if (decoded == null) {
                setStatus("Could not read an image. Will try another on the next rotation")
                return
            }

            handler.removeCallbacks(fadeTask)
            previousBitmap?.recycle()
            previousBitmap = bitmap
            bitmap = decoded
            transitionStartedAt = SystemClock.uptimeMillis()
            lastUri = chosen

            prefs.edit()
                .putString("last_image_uri", chosen.toString())
                .putLong("last_rotation_time", System.currentTimeMillis())
                .putString("last_rotation_status", "Live wallpaper image changed successfully")
                .apply()
            drawFrame()
            if (previousBitmap != null) handler.post(fadeTask)
        }

        private fun drawImage(canvas: Canvas, image: Bitmap, alpha: Int) {
            val mode = preferences().getString("display_mode", "crop") ?: "crop"
            val scaleX = surfaceWidth.toFloat() / image.width.coerceAtLeast(1)
            val scaleY = surfaceHeight.toFloat() / image.height.coerceAtLeast(1)
            val scale = when (mode) {
                "fit" -> minOf(scaleX, scaleY)
                "stretch" -> 0f
                else -> maxOf(scaleX, scaleY)
            }
            val destination = if (mode == "stretch") {
                RectF(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            } else {
                val width = image.width * scale
                val height = image.height * scale
                val left = (surfaceWidth - width) / 2f
                val top = (surfaceHeight - height) / 2f
                RectF(left, top, left + width, top + height)
            }
            paint.alpha = alpha.coerceIn(0, 255)
            canvas.drawBitmap(image, null, destination, paint)
            paint.alpha = 255
        }

        private fun drawFrame() {
            val image = bitmap ?: return
            if (!surfaceReady) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val old = previousBitmap
                    val elapsed = SystemClock.uptimeMillis() - transitionStartedAt
                    val progress = if (old == null) 1f
                        else (elapsed.toFloat() / transitionDurationMs).coerceIn(0f, 1f)
                    if (old != null && progress < 1f) {
                        drawImage(canvas, old, 255)
                        drawImage(canvas, image, (progress * 255).toInt())
                    } else {
                        drawImage(canvas, image, 255)
                        if (old != null) {
                            previousBitmap = null
                            old.recycle()
                            handler.removeCallbacks(fadeTask)
                        }
                    }
                }
            } catch (_: Exception) {
                setStatus("Wallpaper display surface is temporarily unavailable")
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                        // Surface may have been destroyed while drawing.
                    }
                }
            }
        }
    }
}
