package com.zaverio.wallpapershuttle

import android.app.AlarmManager
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
import android.os.Looper
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
        private const val SERVICE_RECOVERY_REQUEST = 7216
        private const val WAKE_LOCK_TAG = "WallpaperShuttle:Rotation"
        private const val CYCLE_STALL_TIMEOUT_MS = 90_000L
        private const val MAX_RETIRED_ROTATION_THREADS = 2

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
    @Volatile private var cycleStartedAt = 0L
    @Volatile private var nextCycleDueAt = 0L
    @Volatile private var loopGeneration = 0
    private val retiredRotationThreads = mutableListOf<HandlerThread>()
    private var lastStatus: String = "Rotation is active"
    private val watchdogHandler = Handler(Looper.getMainLooper())

    // Screen-on is a recovery point: some Android vendor power managers pause
    // background work while the display is off. Refresh the wallpaper as soon
    // as the user wakes the phone, without requiring the app to be opened.
    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT
            ) {
                if (preferences.getBoolean("rotation_enabled", false)) {
                    scheduleRotationImmediately("Rotation resumed when the screen turned on")
                }
            }
        }
    }

    private val watchdogTask = object : Runnable {
        override fun run() {
            try {
                if (preferences.getBoolean("rotation_enabled", false)) {
                    retiredRotationThreads.removeAll { !it.isAlive }
                    ensureRotationThread()

                    val now = SystemClock.elapsedRealtime()
                    val intervalMillis = TimeUnit.SECONDS.toMillis(
                        preferences.getInt("interval_seconds", 5)
                            .coerceIn(5, 86400).toLong()
                    )

                    // A live foreground service can still have a worker blocked inside
                    // image decoding or WallpaperManager. Do not let cycleInProgress
                    // prevent recovery forever.
                    if (cycleInProgress && cycleStartedAt > 0L &&
                        now - cycleStartedAt >= CYCLE_STALL_TIMEOUT_MS
                    ) {
                        recoverStalledCycle()
                    } else {
                        val deadlineMissed = nextCycleDueAt > 0L &&
                            now > nextCycleDueAt + maxOf(30_000L, intervalMillis * 2L)

                        if (!cycleInProgress && (!loopScheduled || deadlineMissed)) {
                            scheduleRotationImmediately(
                                "Rotation loop recovered automatically"
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                try {
                    preferences.edit()
                        .putString(
                            "last_rotation_status",
                            "Rotation watchdog issue: ${error.message ?: "unknown error"}"
                        )
                        .commit()
                } catch (_: Exception) {
                    // Keep the watchdog alive even if status persistence fails.
                }
            } finally {
                if (::preferences.isInitialized &&
                    preferences.getBoolean("rotation_enabled", false)
                ) {
                    watchdogHandler.postDelayed(this, 15_000L)
                }
            }
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
            cycleStartedAt = SystemClock.elapsedRealtime()
            val intervalSeconds = preferences
                .getInt("interval_seconds", 5)
                .coerceIn(5, 86400)
            maintainWakeLock(intervalSeconds)
            val generationAtStart = loopGeneration
            val startedAt = cycleStartedAt

            preferences.edit()
                .putLong("rotation_cycle_started_at", System.currentTimeMillis())
                .commit()

            val cycleStatus = try {
                rotateToRandomImage()
            } catch (error: Exception) {
                "Rotation issue: ${error.message ?: "Unexpected error"}"
            }

            // A watchdog recovery or interval change may have replaced this
            // worker while WallpaperManager was processing the old request.
            // The retired invocation must not change the new loop's state.
            if (generationAtStart != loopGeneration) return

            lastStatus = cycleStatus
            preferences.edit()
                .putLong("rotation_cycle_completed_at", System.currentTimeMillis())
                .remove("rotation_cycle_started_at")
                .commit()

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
            cycleStartedAt = 0L
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

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenWakeReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
            cycleStartedAt = 0L
            loopScheduled = false
            nextCycleDueAt = 0L
        }
    }

    private fun scheduleRotationImmediately(status: String) {
        ensureRotationThread()
        loopGeneration++
        rotationHandler.removeCallbacks(rotationTask)
        cycleInProgress = false
        cycleStartedAt = 0L
        loopScheduled = true
        nextCycleDueAt = SystemClock.elapsedRealtime()
        preferences.edit()
            .putString("last_rotation_status", status)
            .commit()

        if (!rotationHandler.post(rotationTask)) {
            loopScheduled = false
            nextCycleDueAt = 0L
            preferences.edit()
                .putString("last_rotation_status", "Rotation loop could not be queued; watchdog will retry")
                .commit()
        }
    }

    private fun recoverStalledCycle() {
        retiredRotationThreads.removeAll { !it.isAlive }
        val stillRetired = retiredRotationThreads.count { it.isAlive }
        if (stillRetired >= MAX_RETIRED_ROTATION_THREADS) {
            preferences.edit()
                .putString(
                    "last_rotation_status",
                    "Rotation is stalled. Waiting for a previous wallpaper request to return before another recovery."
                )
                .commit()
            return
        }

        val oldThread = rotationThread
        loopGeneration++
        rotationHandler.removeCallbacksAndMessages(null)
        try {
            oldThread.quitSafely()
        } catch (_: Exception) {
            // The new worker below is independent of the retired thread.
        }
        if (oldThread.isAlive) retiredRotationThreads.add(oldThread)

        rotationThread = HandlerThread("WallpaperRotationLoop-Recovery-$loopGeneration")
            .apply { start() }
        rotationHandler = Handler(rotationThread.looper)

        cycleInProgress = false
        cycleStartedAt = 0L
        loopScheduled = true
        nextCycleDueAt = SystemClock.elapsedRealtime()
        preferences.edit()
            .putString(
                "last_rotation_status",
                "A wallpaper change took too long. Rotation engine restarted automatically."
            )
            .remove("rotation_cycle_started_at")
            .commit()

        if (!rotationHandler.post(rotationTask)) {
            loopScheduled = false
            nextCycleDueAt = 0L
            preferences.edit()
                .putString("last_rotation_status", "Rotation recovery could not be queued; retrying")
                .commit()
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
                        cycleInProgress = false
                        cycleStartedAt = 0L
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
                        // Reopening the app must repair an overdue loop as well as
                        // a missing one, without resetting a healthy countdown.
                        val now = SystemClock.elapsedRealtime()
                        val intervalMillis = TimeUnit.SECONDS.toMillis(seconds.toLong())
                        val overdue = loopScheduled && nextCycleDueAt > 0L &&
                            now > nextCycleDueAt + maxOf(30_000L, intervalMillis * 2L)
                        val stalled = cycleInProgress && cycleStartedAt > 0L &&
                            now - cycleStartedAt >= CYCLE_STALL_TIMEOUT_MS

                        when {
                            stalled -> recoverStalledCycle()
                            !loopScheduled || (!cycleInProgress && overdue) ->
                                scheduleRotationImmediately("Rotation resumed by service health check")
                        }
                    }
                    else -> {
                        loopGeneration++
                        rotationHandler.removeCallbacks(rotationTask)
                        cycleInProgress = false
                        cycleStartedAt = 0L
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
        cancelServiceRecovery()
        loopGeneration++
        loopScheduled = false
        cycleInProgress = false
        cycleStartedAt = 0L
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
            cycleInProgress = false
            cycleStartedAt = 0L
            loopScheduled = true
            nextCycleDueAt = SystemClock.elapsedRealtime()
            rotationHandler.post(rotationTask)
        }
        scheduleServiceRecovery()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scheduleServiceRecovery()
        loopGeneration++
        loopScheduled = false
        cycleInProgress = false
        cycleStartedAt = 0L
        nextCycleDueAt = 0L
        watchdogHandler.removeCallbacks(watchdogTask)
        try { unregisterReceiver(screenWakeReceiver) } catch (_: Exception) { }
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

    private fun scheduleServiceRecovery() {
        if (!preferences.getBoolean("rotation_enabled", false)) return
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, WallpaperRotationRestartReceiver::class.java)
                .setAction(ACTION_ENSURE)
            val restartPendingIntent = PendingIntent.getBroadcast(
                this, SERVICE_RECOVERY_REQUEST, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 3_000L, restartPendingIntent
            )
        } catch (error: Exception) {
            preferences.edit().putString(
                "last_rotation_status",
                "Background recovery could not be scheduled: ${error.message ?: "unknown error"}"
            ).commit()
        }
    }

    private fun cancelServiceRecovery() {
        try {
            val restartIntent = Intent(this, WallpaperRotationRestartReceiver::class.java)
                .setAction(ACTION_ENSURE)
            val pendingIntent = PendingIntent.getBroadcast(
                this, SERVICE_RECOVERY_REQUEST, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (_: Exception) { }
    }
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

/** Restarts rotation after device-specific task/service removal. */
class WallpaperRotationRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences("shuffle_wallpaper", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("rotation_enabled", false)) return
        try {
            WallpaperRotationService.start(context.applicationContext, WallpaperRotationService.ACTION_ENSURE)
        } catch (error: Exception) {
            preferences.edit()
                .putBoolean("foreground_rotation_service", false)
                .putString("last_rotation_status", "Automatic background restart failed: ${error.message ?: "unknown error"}")
                .commit()
        }
    }
}
