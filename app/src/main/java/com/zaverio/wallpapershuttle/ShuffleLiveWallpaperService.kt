package com.zaverio.wallpapershuttle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import java.util.concurrent.TimeUnit

/**
 * System-managed live wallpaper renderer. The OS owns this service while it
 * is the selected wallpaper, so rotation is not tied to MainActivity's life.
 */
class ShuffleLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = RotationEngine()

    inner class RotationEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bitmap: Bitmap? = null
        private var imageUris: List<Uri> = emptyList()
        private var lastUri: Uri? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var destroyed = false

        private val rotateTask = object : Runnable {
            override fun run() {
                if (destroyed || !isVisible || !rotationEnabled()) return
                showNextImage()
                val seconds = preferences().getInt("interval_seconds", 5).coerceIn(5, 86400)
                handler.postDelayed(this, TimeUnit.SECONDS.toMillis(seconds.toLong()))
            }
        }

        private fun preferences() = getSharedPreferences("shuffle_wallpaper", MODE_PRIVATE)

        private fun rotationEnabled() = preferences().getBoolean("rotation_enabled", false)

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            handler.removeCallbacks(rotateTask)
            if (visible && rotationEnabled()) {
                showNextImage()
                val seconds = preferences().getInt("interval_seconds", 5).coerceIn(5, 86400)
                handler.postDelayed(rotateTask, TimeUnit.SECONDS.toMillis(seconds.toLong()))
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(rotateTask)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true
            handler.removeCallbacksAndMessages(null)
            bitmap?.recycle()
            bitmap = null
            super.onDestroy()
        }

        private fun findImages(root: DocumentFile): List<Uri> {
            val found = mutableListOf<Uri>()
            val pending = ArrayDeque<DocumentFile>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val folder = pending.removeFirst()
                val children = try { folder.listFiles() } catch (_: Exception) { emptyArray() }
                for (file in children) {
                    try {
                        if (file.isDirectory) pending.add(file)
                        else if (file.isFile && (
                            file.type?.startsWith("image/", true) == true ||
                            file.name?.lowercase()?.let { name ->
                                listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif").any(name::endsWith)
                            } == true
                        )) found.add(file.uri)
                    } catch (_: Exception) { }
                }
            }
            return found
        }

        private fun showNextImage() {
            if (!rotationEnabled()) return
            val prefs = preferences()
            val folderString = prefs.getString("folder_uri", null) ?: return
            val root = DocumentFile.fromTreeUri(this@ShuffleLiveWallpaperService, Uri.parse(folderString)) ?: return
            if (imageUris.isEmpty()) imageUris = findImages(root)
            val excluded = prefs.getStringSet("excluded_uris", emptySet())?.toSet() ?: emptySet()
            val eligible = imageUris.filterNot { it.toString() in excluded }
            if (eligible.isEmpty()) return
            val candidates = eligible.filter { it != lastUri }.ifEmpty { eligible }
            val chosen = candidates.random()
            val decoded = try {
                contentResolver.openInputStream(chosen)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } ?: return
            val old = bitmap
            bitmap = decoded
            lastUri = chosen
            old?.recycle()
            prefs.edit()
                .putString("last_image_uri", chosen.toString())
                .putLong("last_rotation_time", System.currentTimeMillis())
                .putString("last_rotation_status", "Live wallpaper image changed successfully")
                .commit()
            drawFrame()
        }

        private fun drawFrame() {
            val image = bitmap ?: return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
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
                    canvas.drawBitmap(image, null, destination, paint)
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
                }
            }
        }
    }
}
