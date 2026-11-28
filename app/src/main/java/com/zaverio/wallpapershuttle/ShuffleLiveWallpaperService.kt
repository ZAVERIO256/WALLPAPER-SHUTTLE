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
import org.json.JSONArray

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

        private var lastManualRequest = 0L
        private var lastScheduleRefreshRequest = 0L
        private val manualRequestTask = object : Runnable {
            override fun run() {
                if (destroyed) return
                if (isVisible && rotationEnabled()) {
                    val prefs = preferences()
                    val request = prefs.getLong("manual_change_request", 0L)
                    if (request != lastManualRequest) {
                        lastManualRequest = request
                        val target = prefs.getString("manual_target_uri", null)?.let(Uri::parse)
                        showNextImage(target)
                        scheduleNextRotation()
                    }
                    val refreshRequest = prefs.getLong("schedule_refresh_request", 0L)
                    if (refreshRequest != lastScheduleRefreshRequest) {
                        lastScheduleRefreshRequest = refreshRequest
                        scheduleNextRotation()
                        prefs.edit().putString("last_rotation_status", "Rotation schedule updated").apply()
                    }
                    handler.postDelayed(this, 600L)
                }
            }
        }

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
            val prefs = preferences()
            val seconds = if (prefs.getBoolean("smart_schedule_enabled", false)) {
                when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
                    in 5..10 -> 15 * 60
                    in 11..16 -> 30 * 60
                    in 17..21 -> 20 * 60
                    else -> 60 * 60
                }
            } else {
                prefs.getInt("interval_seconds", 5).coerceIn(5, 86400)
            }
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val batteryAdjustedSeconds = if (plugged == 0) when {
                batteryPercent in 0..15 -> maxOf(seconds, 3600)
                batteryPercent in 16..25 -> maxOf(seconds, 1800)
                batteryPercent in 26..35 -> maxOf(seconds, 900)
                else -> seconds
            } else seconds
            return TimeUnit.SECONDS.toMillis(batteryAdjustedSeconds.toLong())
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
            handler.removeCallbacks(manualRequestTask)
            if (visible && rotationEnabled()) {
                if (surfaceReady) showNextImage()
                lastManualRequest = preferences().getLong("manual_change_request", 0L)
                lastScheduleRefreshRequest = preferences().getLong("schedule_refresh_request", 0L)
                handler.post(manualRequestTask)
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
            val found = LinkedHashSet<String>()
            val pending = ArrayDeque<DocumentFile>()
            val visitedFolders = HashSet<String>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val folder = pending.removeFirst()
                if (!visitedFolders.add(folder.uri.toString())) continue
                val children = try { folder.listFiles() } catch (_: Exception) { continue }
                for (file in children) {
                    try {
                        when {
                            file.isDirectory -> pending.addLast(file)
                            file.isFile -> {
                                val name = file.name?.lowercase().orEmpty()
                                val mime = file.type?.lowercase().orEmpty()
                                val imageExtension = listOf(
                                    ".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".webp",
                                    ".bmp", ".gif", ".heic", ".heif", ".avif", ".tif", ".tiff"
                                ).any(name::endsWith)
                                if (mime.startsWith("image/") || imageExtension) found.add(file.uri.toString())
                            }
                        }
                    } catch (_: Exception) {
                        // Skip only the individual entry that the provider cannot expose.
                    }
                }
            }
            return found.map(Uri::parse)
        }
        private fun setStatus(message: String) {
            preferences().edit().putString("last_rotation_status", message).apply()
        }

        /**
         * Persistent shuffle bag: every eligible image is visited once before
         * a new random order is created. The bag survives wallpaper-engine
         * recreation and avoids immediate repeats at cycle boundaries.
         */
        private fun shuffleBagCandidates(eligible: List<Uri>): List<Uri> {
            val prefs = preferences()
            val current = eligible.map { it.toString() }.distinct()
            val signature = current.sorted().joinToString("\n")
            var bag = try {
                val saved = JSONArray(prefs.getString("shuffle_bag_uris", "[]"))
                (0 until saved.length()).map { saved.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
            var cursor = prefs.getInt("shuffle_bag_cursor", 0)
            val savedSignature = prefs.getString("shuffle_bag_signature", null)
            if (savedSignature != signature || bag.size != current.size || bag.toSet() != current.toSet()) {
                bag = current.shuffled().toMutableList()
                if (bag.size > 1 && bag.firstOrNull() == lastUri?.toString()) {
                    val swapIndex = (1 until bag.size).random()
                    val first = bag[0]
                    bag[0] = bag[swapIndex]
                    bag[swapIndex] = first
                }
                cursor = 0
                prefs.edit()
                    .putString("shuffle_bag_signature", signature)
                    .putString("shuffle_bag_uris", JSONArray(bag).toString())
                    .putInt("shuffle_bag_cursor", 0)
                    .apply()
            } else if (cursor >= bag.size) {
                bag = current.shuffled().toMutableList()
                if (bag.size > 1 && bag.firstOrNull() == lastUri?.toString()) {
                    val swapIndex = (1 until bag.size).random()
                    val first = bag[0]
                    bag[0] = bag[swapIndex]
                    bag[swapIndex] = first
                }
                cursor = 0
                prefs.edit()
                    .putString("shuffle_bag_uris", JSONArray(bag).toString())
                    .putInt("shuffle_bag_cursor", 0)
                    .apply()
            }
            return bag.drop(cursor).map(Uri::parse)
        }

        private fun showNextImage(requestedUri: Uri? = null) {
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
            val favourites = prefs.getStringSet("favourite_images", emptySet())?.toSet() ?: emptySet()
            val favouritesOnly = prefs.getBoolean("favourites_only_rotation", false)
            val eligible = imageUris.filterNot { it.toString() in excluded }
                .filter { !favouritesOnly || it.toString() in favourites }
            if (eligible.isEmpty()) {
                val message = when {
                    imageUris.isEmpty() -> "No supported images found in the selected folder"
                    favouritesOnly && favourites.isEmpty() -> "Favourite-only rotation is enabled, but no images are favourited"
                    favouritesOnly -> "No eligible favourites found. Favourite an image or turn off Favourite-only rotation"
                    else -> "All images are excluded"
                }
                setStatus(message)
                return
            }

            if (lastUri == null) {
                lastUri = prefs.getString("last_image_uri", null)?.let(Uri::parse)
            }
            val bagCandidates = shuffleBagCandidates(eligible)
            // If a manual selection is still waiting in this cycle, move it to
            // the front and consume every bag entry up to it. This prevents it
            // from appearing a second time during the same random cycle.
            val requestedInBag = requestedUri?.takeIf { it in bagCandidates }
            val attemptCandidates = when {
                requestedInBag != null -> listOf(requestedInBag) + bagCandidates.filter { it != requestedInBag }
                requestedUri != null && requestedUri in eligible -> listOf(requestedUri) + bagCandidates
                else -> bagCandidates
            }
            var chosen: Uri? = null
            var decoded: Bitmap? = null
            var failedAttempts = 0
            var consumedBagEntries = 0
            for (candidate in attemptCandidates) {
                val attempt = try { decodeForWallpaper(candidate) } catch (_: Exception) { null }
                if (attempt != null) {
                    chosen = candidate
                    decoded = attempt
                    if (requestedUri == null || requestedInBag != null) consumedBagEntries++
                    break
                }
                failedAttempts++
                if (requestedUri == null || requestedInBag != null) consumedBagEntries++
            }
            if (consumedBagEntries > 0) {
                prefs.edit().putInt(
                    "shuffle_bag_cursor",
                    prefs.getInt("shuffle_bag_cursor", 0) + consumedBagEntries
                ).apply()
            }

            var newBitmap = decoded
            var selectedUri = chosen

            // If every newly selected file fails, recover the last known-good
            // wallpaper URI. Android may recreate this engine after memory
            // pressure or while the device is waking; do not leave a blank
            // surface just because the next random file is temporarily unreadable.
            if (newBitmap == null || selectedUri == null) {
                val lastKnownUri = prefs.getString("last_image_uri", null)
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (lastKnownUri != null) {
                    val recovered = runCatching { decodeForWallpaper(lastKnownUri) }.getOrNull()
                    if (recovered != null) {
                        newBitmap = recovered
                        selectedUri = lastKnownUri
                    }
                }
            }

            if (newBitmap == null || selectedUri == null) {
                prefs.edit()
                    .putInt("unreadable_image_count", prefs.getInt("unreadable_image_count", 0) + failedAttempts)
                    .putString(
                        "last_rotation_status",
                        if (bitmap != null) "New images could not be read. Keeping the current wallpaper and retrying"
                        else "Images are temporarily unavailable. Re-select the folder if this continues"
                    )
                    .apply()
                return
            }

            // If the engine has no in-memory frame yet, show the recovered
            // image immediately rather than starting a transition from black.
            if (bitmap == null) {
                bitmap = newBitmap
                lastUri = selectedUri
                prefs.edit()
                    .putString("last_image_uri", selectedUri.toString())
                    .putString("last_rotation_status", "Previous wallpaper restored")
                    .apply()
                drawFrame()
                return
            }

            handler.removeCallbacks(fadeTask)
            previousBitmap?.recycle()
            previousBitmap = bitmap
            bitmap = newBitmap
            transitionStartedAt = SystemClock.uptimeMillis()
            lastUri = selectedUri

            prefs.edit()
                .putString("last_image_uri", selectedUri.toString())
                .putLong("last_rotation_time", System.currentTimeMillis())
                .putInt("total_rotation_count", prefs.getInt("total_rotation_count", 0) + 1)
                .putInt("unreadable_image_count", prefs.getInt("unreadable_image_count", 0) + failedAttempts)
                .putString(
                    "last_rotation_status",
                    if (failedAttempts > 0) "Wallpaper changed; skipped $failedAttempts unreadable image(s)"
                    else "Live wallpaper image changed successfully"
                )
                .apply()
            drawFrame()
            if (previousBitmap != null) handler.post(fadeTask)
        }

        /**
         * Decode only enough pixels for the current wallpaper surface. Large
         * camera images can otherwise consume tens of megabytes per bitmap,
         * especially while two images overlap during the crossfade.
         */
        private fun decodeForWallpaper(uri: Uri): Bitmap? {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                return contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val mode = preferences().getString("display_mode", "crop") ?: "crop"
            val sourceWidth = bounds.outWidth.toFloat()
            val sourceHeight = bounds.outHeight.toFloat()
            val scaleX = surfaceWidth / sourceWidth
            val scaleY = surfaceHeight / sourceHeight
            val renderScale = when (mode) {
                "fit" -> minOf(scaleX, scaleY)
                "stretch" -> 1f
                else -> maxOf(scaleX, scaleY)
            }.coerceAtLeast(0.0001f)

            val requiredWidth = (surfaceWidth / renderScale).toInt().coerceAtLeast(1)
            val requiredHeight = (surfaceHeight / renderScale).toInt().coerceAtLeast(1)
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= requiredWidth &&
                bounds.outHeight / (sampleSize * 2) >= requiredHeight
            ) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (decoded != null) {
                preferences().edit()
                    .putString(
                        "last_image_quality",
                        "${bounds.outWidth}×${bounds.outHeight} source → ${decoded.width}×${decoded.height} loaded"
                    )
                    .apply()
            }
            return decoded
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
