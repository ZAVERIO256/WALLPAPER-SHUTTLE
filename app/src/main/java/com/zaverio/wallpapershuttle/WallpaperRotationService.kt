package com.zaverio.wallpapershuttle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * User-started continuous wallpaper rotation.
 *
 * The foreground service removes WorkManager's deliberate scheduling delay.
 * A partial wake lock keeps its timer running when the display is off; this
 * increases battery use and is held only for as long as rotation is enabled.
 */
class WallpaperRotationService : Service() {

    companion object {
        const val ACTION_START = "com.zaverio.wallpapershuttle.action.START_ROTATION"
        const val ACTION_STOP = "com.zaverio.wallpapershuttle.action.STOP_ROTATION"
        const val ACTION_ENSURE = "com.zaverio.wallpapershuttle.action.ENSURE_ROTATION"
        const val ACTION_UPDATE_INTERVAL =
            "com.zaverio.wallpapershuttle.action.UPDATE_ROTATION_INTERVAL"

        private const val PREFS = "shuffle_wallpaper"
        private const val CHANNEL_ID = "wallpaper_rotation_active"
        private const val NOTIFICATION_ID = 7215
        private const val WAKE_LOCK_TAG = "WallpaperShuttle:Rotation"

        fun start(context: Context, action: String = ACTION_START) {
            val intent = Intent(context, WallpaperRotationService::class.java)
                .setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(WallpaperRotationWorker.WORK_NAME)
            context.stopService(
                Intent(context, WallpaperRotationService::class.java)
            )
        }
    }

    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var rotationThread: HandlerThread
    private lateinit var rotationHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    private var cachedImages: List<Uri> = emptyList()
    private var cachedFolderUri: String? = null
    private var lastImageScanAt = 0L
    @Volatile private var loopScheduled = false
    @Volatile private var cycleInProgress = false
    @Volatile private var nextCycleDueAt = 0L
    @Volatile private var loopGeneration = 0
    private var lastStatus: String = "Rotation is active"
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogTask = object : Runnable {
        override fun run() {
            if (!preferences.getBoolean("rotation_enabled", false)) return

            val intervalMillis = TimeUnit.SECONDS.toMillis(
                preferences.getInt("interval_seconds", 5).coerceIn(5, 86400).toLong()
            )
            ensureRotationThread()

            val now = SystemClock.elapsedRealtime()
            val deadlineMissed = nextCycleDueAt > 0L &&
                now > nextCycleDueAt + maxOf(30_000L, intervalMillis * 2L)

            if (!cycleInProgress && (!loopScheduled || deadlineMissed)) {
                rotationHandler.removeCallbacks(rotationTask)
                loopGeneration++
                loopScheduled = true
                nextCycleDueAt = now
                rotationHandler.post(rotationTask)
                preferences.edit()
                    .putString("last_rotation_status", "Rotation loop recovered automatically")
                    .commit()
            }

            watchdogHandler.postDelayed(this, 15_000L)
        }
    }

    private val rotationTask = object : Runnable {
        override fun run() {
            if (!preferences.getBoolean("rotation_enabled", false)) {
                stopServiceCleanly()
                return
            }

            loopScheduled = false
            cycleInProgress = true
            val intervalSeconds = preferences
                .getInt("interval_seconds", 5)
                .coerceIn(5, 86400)
            maintainWakeLock(intervalSeconds)
            val generationAtStart = loopGeneration
            val startedAt = SystemClock.elapsedRealtime()

            try {
                lastStatus = rotateToRandomImage()
            } catch (error: Exception) {
                lastStatus = "Rotation issue: ${error.message ?: "Unexpected error"}"
            }

            // An interval update or pause may have happened while the image
            // was being prepared. Do not let the old run schedule another loop.
            if (generationAtStart != loopGeneration) {
                cycleInProgress = false
                return
            }

            // Count processing time toward the chosen interval. If processing
            // takes longer than the interval, start the next cycle immediately.
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val remainingDelay = (
                TimeUnit.SECONDS.toMillis(intervalSeconds.toLong()) - elapsed
            ).coerceAtLeast(0L)
            val nextChangeAt = System.currentTimeMillis() + remainingDelay

            preferences.edit()
                .putLong("next_rotation_time", nextChangeAt)
                .putString("last_rotation_status", lastStatus)
                .commit()

            try {
                updateForegroundNotification(intervalSeconds)
            } catch (notificationError: Exception) {
                preferences.edit()
                    .putString(
                        "last_rotation_status",
                        "$lastStatus (notification update issue: ${notificationError.message ?: "unknown"})"
                    )
                    .commit()
            }

            cycleInProgress = false
            loopScheduled = true
            nextCycleDueAt = SystemClock.elapsedRealtime() + remainingDelay
            if (!rotationHandler.postDelayed(this, remainingDelay)) {
                loopScheduled = false
                nextCycleDueAt = 0L
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        createNotificationChannel()

        rotationThread = HandlerThread("WallpaperRotationLoop").apply { start() }
        rotationHandler = Handler(rotationThread.looper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }

        watchdogHandler.postDelayed(watchdogTask, 15_000L)
    }

    private fun ensureRotationThread() {
        if (!::rotationThread.isInitialized || !rotationThread.isAlive) {
            if (::rotationThread.isInitialized) {
                try { rotationThread.quitSafely() } catch (_: Exception) { }
            }
            rotationThread = HandlerThread("WallpaperRotationLoop").apply { start() }
            rotationHandler = Handler(rotationThread.looper)
            cycleInProgress = false
            loopScheduled = false
            nextCycleDueAt = 0L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceCleanly()
                return START_NOT_STICKY
            }
            else -> {
                if (!preferences.getBoolean("rotation_enabled", false)) {
                    stopServiceCleanly()
                    return START_NOT_STICKY
                }

                ensureForeground()
                preferences.edit()
                    .putBoolean("foreground_rotation_service", true)
                    .commit()

                // Cancel any old WorkManager chain left by earlier app versions.
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(WallpaperRotationWorker.WORK_NAME)

                val seconds = preferences.getInt("interval_seconds", 5)
                    .coerceIn(5, 86400)
                maintainWakeLock(seconds)

                when (intent?.action) {
                    ACTION_UPDATE_INTERVAL -> {
                        loopGeneration++
                        rotationHandler.removeCallbacks(rotationTask)
                        val seconds = preferences.getInt("interval_seconds", 5)
                            .coerceIn(5, 86400)
                        maintainWakeLock(seconds)
                        val nextChangeAt = System.currentTimeMillis() +
                            TimeUnit.SECONDS.toMillis(seconds.toLong())
                        preferences.edit().putLong("next_rotation_time", nextChangeAt).commit()
                        updateForegroundNotification(seconds)
                        loopScheduled = true
                        val delayMillis = TimeUnit.SECONDS.toMillis(seconds.toLong())
                        nextCycleDueAt = SystemClock.elapsedRealtime() + delayMillis
                        if (!rotationHandler.postDelayed(rotationTask, delayMillis)) {
                            loopScheduled = false
                            nextCycleDueAt = 0L
                        }
                    }
                    ACTION_ENSURE -> {
                        // Reopening the app should repair a missing service without
                        // restarting an already-running countdown.
                        if (!loopScheduled) {
                            loopGeneration++
                            loopScheduled = true
                            nextCycleDueAt = SystemClock.elapsedRealtime()
                            if (!rotationHandler.post(rotationTask)) {
                                loopScheduled = false
                                nextCycleDueAt = 0L
                            }
                        }
                    }
                    else -> {
                        loopGeneration++
                        rotationHandler.removeCallbacks(rotationTask)
                        loopScheduled = true
                        nextCycleDueAt = SystemClock.elapsedRealtime()
                        if (!rotationHandler.post(rotationTask)) {
                            loopScheduled = false
                            nextCycleDueAt = 0L
                        }
                    }
                }
                return START_STICKY
            }
        }
    }

    private fun ensureForeground() {
        val intervalSeconds = preferences.getInt("interval_seconds", 5)
            .coerceIn(5, 86400)
        val notification = buildNotification(intervalSeconds)

        val foregroundType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
    }

    private fun maintainWakeLock(intervalSeconds: Int) {
        val lock = wakeLock ?: return

        // Keep the CPU awake for short, high-frequency rotation. For long
        // intervals release it so the phone can sleep and conserve battery.
        if (intervalSeconds <= 300) {
            val now = SystemClock.elapsedRealtime()
            val shouldRefresh = !lock.isHeld ||
                now - wakeLockAcquiredAt >= TimeUnit.MINUTES.toMillis(20)
            if (shouldRefresh) {
                if (lock.isHeld) lock.release()
                lock.acquire(TimeUnit.MINUTES.toMillis(30))
                wakeLockAcquiredAt = now
            }
        } else if (lock.isHeld) {
            lock.release()
            wakeLockAcquiredAt = 0L
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wallpaper rotation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent status while automatic wallpaper rotation is active"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(intervalSeconds: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, WallpaperRotationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Wallpaper Shuttle is active")
            .setContentText("Changing wallpapers every $intervalSeconds seconds")
            .setSubText(lastStatus)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pauseIntent
            )
            .build()
    }

    private fun updateForegroundNotification(intervalSeconds: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(intervalSeconds))
    }

    private fun collectImages(root: DocumentFile): List<Uri> {
        val images = mutableListOf<Uri>()
        val folders = ArrayDeque<DocumentFile>()
        folders.add(root)

        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val files = try {
                folder.listFiles()
            } catch (_: Exception) {
                continue
            }

            for (file in files) {
                try {
                    when {
                        file.isDirectory -> folders.add(file)
                        file.isFile && isImageFile(file) -> images.add(file.uri)
                    }
                } catch (_: Exception) {
                    // Ignore inaccessible entries and continue scanning.
                }
            }
        }
        return images
    }

    private fun isImageFile(file: DocumentFile): Boolean {
        if (file.type?.startsWith("image/", ignoreCase = true) == true) return true
        val name = file.name?.lowercase() ?: return false
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
            .any { name.endsWith(it) }
    }

    private fun rotateToRandomImage(): String {
        return try {
            val folderString = preferences.getString("folder_uri", null)
                ?: throw IllegalStateException("No image folder is selected.")

            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderString))
                ?: throw IllegalStateException("The selected image folder is unavailable.")

            val now = SystemClock.elapsedRealtime()
            if (cachedFolderUri != folderString ||
                now - lastImageScanAt >= TimeUnit.MINUTES.toMillis(5)
            ) {
                cachedImages = collectImages(folder)
                cachedFolderUri = folderString
                lastImageScanAt = now
            }

            val excluded = preferences.getStringSet("excluded_uris", emptySet())
                ?.toSet() ?: emptySet()

            val eligibleImages = cachedImages.filterNot {
                it.toString() in excluded
            }

            if (eligibleImages.isEmpty()) {
                throw IllegalStateException("No eligible images. Check your exclusions.")
            }

            val chosenImage = eligibleImages.random()
            val destination = preferences.getInt(
                "destination",
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
            val displayMode = preferences.getString("display_mode", "crop") ?: "crop"

            val wallpaperId = WallpaperImageProcessor.apply(
                this,
                chosenImage,
                displayMode,
                destination
            )

            AppStorage.addHistory(this, chosenImage)
            preferences.edit()
                .putString("last_image_uri", chosenImage.toString())
                .putLong("last_rotation_time", System.currentTimeMillis())
                .commit()

            "Wallpaper changed successfully (ID $wallpaperId)"
        } catch (error: Exception) {
            "Rotation issue: ${error.message ?: "unknown error"}"
        }
    }

    private fun stopServiceCleanly() {
        loopGeneration++
        loopScheduled = false
        cycleInProgress = false
        nextCycleDueAt = 0L
        watchdogHandler.removeCallbacks(watchdogTask)
        rotationHandler.removeCallbacks(rotationTask)
        preferences.edit()
            .putBoolean("rotation_enabled", false)
            .putBoolean("foreground_rotation_service", false)
            .remove("next_rotation_time")
            .commit()

        WorkManager.getInstance(applicationContext)
            .cancelUniqueWork(WallpaperRotationWorker.WORK_NAME)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLockAcquiredAt = 0L
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the app card from Recents is not a Pause command. Keep the
        // foreground service loop alive and repair a missing queued callback.
        if (preferences.getBoolean("rotation_enabled", false) &&
            !cycleInProgress && !loopScheduled
        ) {
            ensureRotationThread()
            loopGeneration++
            loopScheduled = true
            nextCycleDueAt = SystemClock.elapsedRealtime()
            rotationHandler.post(rotationTask)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loopGeneration++
        loopScheduled = false
        cycleInProgress = false
        nextCycleDueAt = 0L
        watchdogHandler.removeCallbacks(watchdogTask)
        rotationHandler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLockAcquiredAt = 0L
        }
        if (::rotationThread.isInitialized) {
            rotationThread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Restarts user-enabled rotation after a reboot or app replacement.
 * Android may still apply device-specific battery and service-start limits.
 */
class WallpaperRotationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val preferences = context.getSharedPreferences(
            "shuffle_wallpaper",
            Context.MODE_PRIVATE
        )
        if (!preferences.getBoolean("rotation_enabled", false)) return

        try {
            WallpaperRotationService.start(context.applicationContext)
        } catch (_: Exception) {
            // Keep a best-effort fallback if a foreground service cannot be started.
            preferences.edit().putBoolean("foreground_rotation_service", false).commit()
            val intervalSeconds = preferences.getInt("interval_seconds", 5)
                .coerceIn(5, 86400)
            val request = OneTimeWorkRequestBuilder<WallpaperRotationWorker>()
                .setInitialDelay(0, TimeUnit.SECONDS)
                .addTag(WallpaperRotationWorker.WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WallpaperRotationWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
