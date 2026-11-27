package com.zaverio.wallpapershuttle

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val DarkBackground = Color(0xFF090E1A)
private val CardBackground = Color(0xFF151F31)
private val Accent = Color(0xFF9B8CFF)
private val AccentMint = Color(0xFF65E0C1)
private val MutedText = Color(0xFFB4BED0)

private fun formatRotationInterval(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceIn(5, 86400)
    return when {
        seconds < 60 -> "$seconds seconds"
        seconds % 3600 == 0 -> {
            val hours = seconds / 3600
            if (hours == 1) "1 hour" else "$hours hours"
        }
        seconds % 60 == 0 -> {
            val minutes = seconds / 60
            if (minutes == 1) "1 minute" else "$minutes minutes"
        }
        else -> "$seconds seconds"
    }
}

private fun ensureActiveRotationService(context: Context) {
    val preferences = context.getSharedPreferences(
        "shuffle_wallpaper",
        Context.MODE_PRIVATE
    )
    if (!preferences.getBoolean("rotation_enabled", false) || preferences.getBoolean("live_wallpaper_mode", false)) return

    try {
        WallpaperRotationService.start(
            context.applicationContext,
            WallpaperRotationService.ACTION_ENSURE
        )
    } catch (error: Exception) {
        preferences.edit()
            .putBoolean("foreground_rotation_service", false)
            .putString(
                "last_rotation_status",
                "Could not resume continuous rotation: ${error.message ?: "unknown error"}"
            )
            .commit()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ensureActiveRotationService(applicationContext)
        setContent {
            WallpaperShuttleApp(this)
        }
    }
}

private fun isImageFile(file: DocumentFile): Boolean {
    if (file.type?.startsWith("image/", ignoreCase = true) == true) {
        return true
    }

    val name = file.name?.lowercase() ?: return false

    return listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"
    ).any { name.endsWith(it) }
}

private fun findImages(folder: DocumentFile): List<Uri> {
    val results = mutableListOf<Uri>()
    val folders = ArrayDeque<DocumentFile>()
    folders.add(folder)

    while (folders.isNotEmpty()) {
        val current = folders.removeFirst()

        val files = try {
            current.listFiles()
        } catch (_: Exception) {
            continue
        }

        for (file in files) {
            try {
                when {
                    file.isDirectory -> folders.add(file)
                    file.isFile && isImageFile(file) -> results.add(file.uri)
                }
            } catch (_: Exception) {
                // Skip inaccessible entries.
            }
        }
    }

    return results
}

@Composable
fun WallpaperShuttleApp(context: Context) {
    val preferences = remember {
        context.getSharedPreferences(
            "shuffle_wallpaper",
            Context.MODE_PRIVATE
        )
    }

    var selectedFolder by remember {
        mutableStateOf(
            preferences.getString("folder_uri", null)?.let(Uri::parse)
        )
    }

    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }

    var favouriteImages by remember {
        mutableStateOf(preferences.getStringSet("favourite_images", emptySet())?.toSet() ?: emptySet())
    }
    var galleryFilter by remember { mutableStateOf("ALL") }
    var favouritesOnlyRotation by remember { mutableStateOf(preferences.getBoolean("favourites_only_rotation", false)) }
    var showImagePreview by remember { mutableStateOf(false) }

    var excludedImages by remember {
        mutableStateOf(AppStorage.getExcluded(context))
    }

    var historyEntries by remember {
        mutableStateOf(AppStorage.getHistory(context))
    }

    var isScanning by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    var rotationRunning by remember {
        mutableStateOf(preferences.getBoolean("rotation_enabled", false))
    }

    // Move users who were still on the old 10-second default to the new 5-second default.
    if (preferences.getInt("five_second_timer_migration", 0) < 1) {
        if (preferences.getInt("interval_seconds", 10) == 10) {
            preferences.edit().putInt("interval_seconds", 5).commit()
        }
        preferences.edit().putInt("five_second_timer_migration", 1).commit()
    }

    var intervalSeconds by remember {
        mutableIntStateOf(
            preferences.getInt("interval_seconds", 5).coerceIn(5, 86400)
        )
    }
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    var smartScheduleEnabled by remember {
        mutableStateOf(preferences.getBoolean("smart_schedule_enabled", false))
    }

    var destination by remember {
        mutableIntStateOf(
            preferences.getInt(
                "destination",
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
        )
    }

    // One-time migration: default to edge-to-edge Fill Screen on both displays.
    // Fit remains available when the user wants to see every edge of an image.
    if (preferences.getInt("fill_mode_default_migration", 0) < 1) {
        preferences.edit()
            .putString("display_mode", "crop")
            .putInt("fill_mode_default_migration", 1)
            .commit()
    }

    var displayMode by remember {
        mutableStateOf(
            preferences.getString("display_mode", "crop") ?: "crop"
        )
    }

    var backupMessage by remember { mutableStateOf<String?>(null) }

    var statusMessage by remember {
        mutableStateOf(
            preferences.getString("last_rotation_status", null)
        )
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    var clockNow by remember {
        mutableLongStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(Unit) {
        while (true) {
            clockNow = System.currentTimeMillis()
            delay(1000L)
        }
    }


    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            WallpaperRotationService.start(
                context,
                WallpaperRotationService.ACTION_ENSURE
            )
            if (!granted) {
                statusMessage =
                    "Rotation is active. Android may show its service indicator in system task manager instead of the notification drawer."
            }
        } catch (error: Exception) {
            statusMessage = "Notification permission was updated, but rotation could not be checked: ${error.message}"
        }
    }

    LaunchedEffect(rotationRunning) {
        if (rotationRunning &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !preferences.getBoolean("live_wallpaper_mode", false) &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    fun scanFolder(uri: Uri) {
        isScanning = true
        statusMessage = "Scanning selected folder..."

        executor.execute {
            val result = try {
                val folder = DocumentFile.fromTreeUri(context, uri)
                if (folder != null && folder.canRead()) {
                    findImages(folder)
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            mainHandler.post {
                imageUris = result

                val eligible = result.filterNot {
                    it.toString() in excludedImages
                }

                selectedImage = selectedImage?.takeIf {
                    result.contains(it) && it.toString() !in excludedImages
                } ?: eligible.randomOrNull()

                isScanning = false
                statusMessage = "${result.size} supported images found."
            }
        }
    }

    LaunchedEffect(selectedFolder) {
        selectedFolder?.let { scanFolder(it) }
    }

    LaunchedEffect(intervalSeconds) {
        preferences.edit().putInt("interval_seconds", intervalSeconds).commit()
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                preferences.edit()
                    .putString("folder_uri", uri.toString())
                    .commit()

                selectedFolder = uri
                selectedImage = null
                statusMessage = "Folder selected."
            } catch (_: SecurityException) {
                statusMessage = "Could not save folder permission."
            }
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val backup = JSONObject()
                    .put("format", "wallpaper-shuttle-backup")
                    .put("version", 1)
                    .put("created_at", System.currentTimeMillis())
                    .put("favourites", JSONArray(favouriteImages.toList()))
                    .put("excluded", JSONArray(excludedImages.toList()))
                    .put("history", JSONArray(preferences.getString("wallpaper_history", "[]")))
                    .put("interval_seconds", intervalSeconds)
                    .put("smart_schedule_enabled", smartScheduleEnabled)
                    .put("destination", destination)
                    .put("display_mode", displayMode)
                    .put("favourites_only_rotation", favouritesOnlyRotation)
                context.contentResolver.openOutputStream(uri)?.use { it.write(backup.toString(2).toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Could not open backup file")
                backupMessage = "Backup saved successfully. Keep the file somewhere safe."
            } catch (error: Exception) { backupMessage = "Backup failed: ${error.message ?: "unknown error"}" }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Could not read the selected file")
                val backup = JSONObject(raw)
                require(backup.optString("format") == "wallpaper-shuttle-backup") { "Not a Wallpaper Shuttle backup" }
                require(backup.optInt("version", -1) == 1) { "Unsupported backup version" }
                val favArray = backup.optJSONArray("favourites") ?: JSONArray()
                val excArray = backup.optJSONArray("excluded") ?: JSONArray()
                val favs = (0 until favArray.length()).map { favArray.optString(it) }.filter { it.isNotBlank() }.toSet()
                val excluded = (0 until excArray.length()).map { excArray.optString(it) }.filter { it.isNotBlank() }.toSet()
                val restoredInterval = backup.optInt("interval_seconds", 5).coerceIn(5, 86400)
                val restoredDestination = backup.optInt("destination", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    .let { if (it == WallpaperManager.FLAG_SYSTEM || it == WallpaperManager.FLAG_LOCK || it == (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)) it else WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK }
                val restoredMode = backup.optString("display_mode", "crop").takeIf { it == "crop" || it == "fit" || it == "stretch" } ?: "crop"
                preferences.edit().putStringSet("favourite_images", HashSet(favs))
                    .putStringSet("excluded_uris", HashSet(excluded))
                    .putString("wallpaper_history", backup.optJSONArray("history")?.toString() ?: "[]")
                    .putInt("interval_seconds", restoredInterval)
                    .putBoolean("smart_schedule_enabled", backup.optBoolean("smart_schedule_enabled", false))
                    .putInt("destination", restoredDestination).putString("display_mode", restoredMode)
                    .putBoolean("favourites_only_rotation", backup.optBoolean("favourites_only_rotation", false))
                    .putLong("schedule_refresh_request", System.currentTimeMillis()).apply()
                favouriteImages = favs
                excludedImages = excluded
                historyEntries = AppStorage.getHistory(context)
                intervalSeconds = restoredInterval
                smartScheduleEnabled = backup.optBoolean("smart_schedule_enabled", false)
                destination = restoredDestination
                displayMode = restoredMode
                favouritesOnlyRotation = backup.optBoolean("favourites_only_rotation", false)
                selectedImage = null
                backupMessage = "Backup restored. Re-select your wallpaper folder if needed. Rotation remains paused until you start it."
            } catch (error: Exception) { backupMessage = "Restore failed: ${error.message ?: "invalid backup"}" }
        }
    }
    val folderName = selectedFolder?.let {
        DocumentFile.fromTreeUri(context, it)?.name
    } ?: "No folder selected"

    fun startRotation() {
        val eligible = imageUris.filterNot {
            it.toString() in excludedImages
        }

        if (eligible.isEmpty() || selectedFolder == null) {
            statusMessage = if (imageUris.isEmpty()) {
                "Select a folder containing images first."
            } else {
                "All images are excluded. Restore some images first."
            }
            return
        }

        preferences.edit().putBoolean("rotation_enabled", false).putBoolean("live_wallpaper_mode", false).commit()
        WallpaperRotationService.stop(context)
        preferences.edit().putBoolean("rotation_enabled", true).putBoolean("live_wallpaper_mode", true).putBoolean("foreground_rotation_service", false).putLong("next_rotation_time", System.currentTimeMillis()).commit()
        rotationRunning = true
        statusMessage = "Choose Set wallpaper in the Android preview to enable continuous live rotation."
        try {
            val liveWallpaperIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, ShuffleLiveWallpaperService::class.java))
            }
            context.startActivity(liveWallpaperIntent)
        } catch (error: Exception) {
            preferences.edit().putBoolean("rotation_enabled", false).putBoolean("live_wallpaper_mode", false).commit()
            rotationRunning = false
            statusMessage = "Could not open Android live wallpaper preview."
        }
    }

    fun requestManualChange(previous: Boolean) {
        if (!rotationRunning || !preferences.getBoolean("live_wallpaper_mode", false)) {
            statusMessage = "Start live wallpaper rotation first."
            return
        }
        val target = if (previous) AppStorage.getHistory(context).getOrNull(1)?.uri else null
        if (previous && target == null) {
            statusMessage = "No previous wallpaper is available yet."
            return
        }
        preferences.edit()
            .putString("manual_target_uri", target?.toString())
            .putLong("manual_change_request", preferences.getLong("manual_change_request", 0L) + 1L)
            .apply()
        statusMessage = if (previous) "Returning to the previous wallpaper..." else "Choosing your next wallpaper..."
    }

    fun pauseRotation() {
        preferences.edit()
            .putBoolean("rotation_enabled", false)
            .putBoolean("live_wallpaper_mode", false)
            .putBoolean("foreground_rotation_service", false)
            .remove("next_rotation_time")
            .commit()

        WallpaperRotationService.stop(context)

        rotationRunning = false
        statusMessage = "Automatic rotation paused."
    }

    fun applyWallpaper(uri: Uri) {
        isApplying = true
        statusMessage = "Applying wallpaper..."

        executor.execute {
            val result = try {
                val displayMode = preferences.getString(
                    "display_mode",
                    "crop"
                ) ?: "crop"

                val wallpaperId = WallpaperImageProcessor.apply(
                    context,
                    uri,
                    displayMode,
                    destination
                )

                AppStorage.addHistory(context, uri)
                preferences.edit()
                    .putLong("last_rotation_time", System.currentTimeMillis())
                    .commit()
                historyEntries = AppStorage.getHistory(context)
                val appliedTo = when (destination) {
                    WallpaperManager.FLAG_SYSTEM -> "Home screen"
                    WallpaperManager.FLAG_LOCK -> "Lock screen"
                    else -> "Home and Lock screens"
                }
                "Wallpaper applied to $appliedTo (confirmation ID $wallpaperId)."
            } catch (e: Exception) {
                "Could not apply wallpaper: ${e.message}"
            }

            mainHandler.post {
                isApplying = false
                statusMessage = result
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF100D20),
            secondary = AccentMint,
            onSecondary = Color(0xFF06231D),
            background = DarkBackground,
            surface = CardBackground,
            onSurface = Color(0xFFF5F6FC),
            surfaceVariant = Color(0xFF202C42),
            onSurfaceVariant = MutedText
        ),
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF111A2B), DarkBackground, Color(0xFF080C15))))
            ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(Accent, Color(0xFF55C9D8)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = Color(0xFF101522), fontSize = 29.sp, fontWeight = FontWeight.Black)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "WALLPAPER SHUTTLE",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.7.sp
                        )
                        Text("Make every screen feel new.", color = MutedText, fontSize = 12.sp)
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (rotationRunning) AccentMint.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (rotationRunning) AccentMint.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.size(7.dp).clip(RoundedCornerShape(50))
                                    .background(if (rotationRunning) AccentMint else MutedText)
                            )
                            Text(
                                if (rotationRunning) "ACTIVE" else "READY",
                                color = if (rotationRunning) AccentMint else MutedText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                Text(
                    when (selectedTab) {
                        0 -> "Your personal wallpaper studio"
                        1 -> "Your collection, all in one place"
                        2 -> "Fine-tune your experience"
                        else -> "Everything about the app"
                    },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )


                if (selectedTab == 0) {
                    val nextRotationAt = preferences.getLong(
                        "next_rotation_time", 0L
                    )
                    val remainingSeconds =
                        ((nextRotationAt - clockNow).coerceAtLeast(0L)) / 1000L
                    val hoursLeft = remainingSeconds / 3600L
                    val minutesLeft = (remainingSeconds % 3600L) / 60L
                    val secondsLeft = remainingSeconds % 60L

                    val countdownText = if (hoursLeft > 0) {
                        "%02d:%02d:%02d".format(
                            hoursLeft, minutesLeft, secondsLeft
                        )
                    } else {
                        "%02d:%02d".format(minutesLeft, secondsLeft)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = CardBackground
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "WALLPAPER OVERVIEW",
                                color = Accent,
                                fontWeight = FontWeight.Bold
                            )

                            if (selectedImage != null) {
                                AsyncImage(
                                    model = selectedImage,
                                    contentDescription = "Selected wallpaper",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    "Select an image folder in Gallery to get started.",
                                    color = Color.LightGray
                                )
                            }

                            Text(
                                "${imageUris.size} images available",
                                color = Color.White
                            )

                            Text(
                                "Change interval: ${formatRotationInterval(intervalSeconds)}",
                                color = Color.LightGray
                            )

                            Text(
                                "NEXT WALLPAPER CHANGE",
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                when {
                                    !rotationRunning -> "Paused"
                                    nextRotationAt <= 0L -> "Starting first rotation..."
                                    remainingSeconds == 0L ->
                                        "Due — waiting for Android"
                                    else -> countdownText
                                },
                                color = Color.White,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Short-interval mode keeps the CPU awake for faster changes and uses more battery.",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )

                            val lastChangedAt = preferences.getLong(
                                "last_rotation_time", 0L
                            )

                            if (lastChangedAt > 0L) {
                                Text(
                                    "Last wallpaper change: " +
                                        android.text.format.DateFormat
                                            .getTimeFormat(context)
                                            .format(java.util.Date(lastChangedAt)),
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }

                            val imageQualityInfo = preferences.getString("last_image_quality", null)
                            if (!imageQualityInfo.isNullOrBlank()) {
                                Text(
                                    "Image loading: $imageQualityInfo",
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                            val unreadableImages = preferences.getInt("unreadable_image_count", 0)
                            if (unreadableImages > 0) {
                                Text(
                                    "Unreadable images skipped: $unreadableImages",
                                    color = Color(0xFFFFC27A),
                                    fontSize = 11.sp
                                )
                            }

                            Text(
                                if (rotationRunning) "Automatic rotation is active"
                                else "Automatic rotation is paused",
                                color = if (rotationRunning)
                                    Color(0xFF86D6A3)
                                else Color.LightGray
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { requestManualChange(true) },
                                    enabled = rotationRunning,
                                    modifier = Modifier.weight(1f)
                                ) { Text("‹ Previous") }
                                Button(
                                    onClick = { requestManualChange(false) },
                                    enabled = rotationRunning,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Next wallpaper ›") }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { startRotation() },
                                    enabled = !rotationRunning &&
                                        imageUris.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Start")
                                }

                                OutlinedButton(
                                    onClick = { pauseRotation() },
                                    enabled = rotationRunning,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Pause")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedTab = 1
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("OPEN MY GALLERY")
                            }
                        }
                    }
                }


                if (selectedTab == 1) {
                // FOLDER MANAGER
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "IMAGE SOURCE",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        Text(folderName, color = Color.White)

                        Button(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SELECT IMAGE FOLDER")
                        }

                        OutlinedButton(
                            onClick = {
                                selectedFolder?.let { scanFolder(it) }
                            },
                            enabled = selectedFolder != null && !isScanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("RESCAN FOLDER")
                        }

                        if (isScanning) {
                            CircularProgressIndicator(
                                color = Accent,
                                modifier = Modifier
                                    .size(26.dp)
                                    .align(Alignment.CenterHorizontally)
                            )
                        } else {
                            Text(
                                "${imageUris.size} supported images found",
                                color = Color.White
                            )
                        }
                    }
                }

                // GALLERY
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "MY IMAGE GALLERY",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        val visibleImages = when (galleryFilter) {
                            "FAVOURITES" -> imageUris.filter { it.toString() in favouriteImages }
                            "EXCLUDED" -> imageUris.filter { it.toString() in excludedImages }
                            else -> imageUris.filterNot { it.toString() in excludedImages }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("ALL", "FAVOURITES", "EXCLUDED").forEach { filter ->
                                val count = when (filter) {
                                    "FAVOURITES" -> favouriteImages.size
                                    "EXCLUDED" -> excludedImages.size
                                    else -> imageUris.size - excludedImages.size
                                }
                                FilterChip(
                                    selected = galleryFilter == filter,
                                    onClick = { galleryFilter = filter },
                                    label = { Text("$filter  $count", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Accent.copy(alpha = 0.22f),
                                        selectedLabelColor = Accent
                                    )
                                )
                            }
                        }

                        if (imageUris.isEmpty()) {
                            Text("Choose an image folder above to build your collection.", color = MutedText)
                        } else if (visibleImages.isEmpty()) {
                            Text(
                                if (galleryFilter == "FAVOURITES") "No favourites yet. Tap the star on any image to save it."
                                else if (galleryFilter == "EXCLUDED") "No excluded images."
                                else "No images to show.",
                                color = MutedText,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                "${visibleImages.size} images shown • Tap a photo to preview",
                                color = MutedText,
                                fontSize = 12.sp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                visibleImages.chunked(2).forEach { imageRow ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        imageRow.forEach { uri ->
                                            val isSelected = uri == selectedImage
                                            val isFavourite = uri.toString() in favouriteImages
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(174.dp)
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) Accent else Color.White.copy(alpha = 0.10f),
                                                        shape = RoundedCornerShape(18.dp)
                                                    )
                                                    .clickable {
                                                        selectedImage = uri
                                                        showImagePreview = true
                                                        statusMessage = "Image selected."
                                                    }
                                            ) {
                                                AsyncImage(
                                                    model = uri,
                                                    contentDescription = "Wallpaper photo",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Box(
                                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                                                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)))
                                                        .padding(9.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (isSelected) {
                                                            Text("SELECTED", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        } else {
                                                            Spacer(Modifier.width(1.dp))
                                                        }
                                                        Surface(
                                                            onClick = {
                                                                val updated = favouriteImages.toMutableSet()
                                                                if (isFavourite) updated.remove(uri.toString()) else updated.add(uri.toString())
                                                                favouriteImages = updated
                                                                preferences.edit().putStringSet("favourite_images", updated).apply()
                                                            },
                                                            shape = RoundedCornerShape(50),
                                                            color = Color.Black.copy(alpha = 0.48f)
                                                        ) {
                                                            Text(
                                                                if (isFavourite) "★" else "☆",
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                                color = if (isFavourite) Color(0xFFFFD166) else Color.White,
                                                                fontSize = 18.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (imageRow.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // SELECTED IMAGE
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "SELECTED WALLPAPER",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        if (selectedImage != null) {
                            AsyncImage(
                                model = selectedImage,
                                contentDescription = "Selected wallpaper",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("No image selected.", color = Color.LightGray)
                        }

                        OutlinedButton(
                            onClick = {
                                selectedImage = imageUris
                                    .filterNot { it.toString() in excludedImages }
                                    .randomOrNull()

                                statusMessage = if (selectedImage != null) {
                                    "Random image selected."
                                } else {
                                    "All images are excluded."
                                }
                            },
                            enabled = imageUris.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SHUFFLE — RANDOM IMAGE")
                        }

                        Button(
                            onClick = {
                                selectedImage?.let { applyWallpaper(it) }
                            },
                            enabled = selectedImage != null && !isApplying,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isApplying) "APPLYING..."
                                else "APPLY WALLPAPER"
                            )
                        }

                        statusMessage?.let { message ->
                            Text(
                                message,
                                color = if (message.startsWith("Wallpaper applied")) {
                                    Color(0xFF86D6A3)
                                } else {
                                    Color(0xFFFFB4AB)
                                },
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                selectedImage?.let { uri ->
                                    val updated = excludedImages.toMutableSet()

                                    if (uri.toString() in updated) {
                                        updated.remove(uri.toString())
                                        statusMessage = "Image restored to rotation."
                                    } else {
                                        updated.add(uri.toString())
                                        statusMessage = "Image excluded from rotation."
                                    }

                                    excludedImages = updated
                                    AppStorage.saveExcluded(context, updated)

                                    if (uri.toString() in updated) {
                                        selectedImage = null
                                    }
                                }
                            },
                            enabled = selectedImage != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (selectedImage?.toString() in excludedImages)
                                    "RESTORE SELECTED IMAGE"
                                else
                                    "EXCLUDE SELECTED IMAGE"
                            )
                        }

                        Text(
                            "${excludedImages.size} images excluded from automatic rotation",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        OutlinedButton(
                            onClick = {
                                excludedImages = emptySet()
                                AppStorage.saveExcluded(context, emptySet())
                                selectedImage = imageUris.randomOrNull()
                                statusMessage = "All excluded images restored."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("RESTORE ALL EXCLUDED IMAGES")
                        }
                    }
                }

                // WALLPAPER HISTORY
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "WALLPAPER HISTORY",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        if (historyEntries.isEmpty()) {
                            Text(
                                "No wallpaper changes recorded yet.",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                "${historyEntries.size} recent changes saved",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            Row(
                                modifier = Modifier.horizontalScroll(
                                    rememberScrollState()
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                historyEntries.take(10).forEach { entry ->
                                    AsyncImage(
                                        model = entry.uri,
                                        contentDescription = "Previous wallpaper",
                                        modifier = Modifier
                                            .size(90.dp)
                                            .clickable {
                                                selectedImage = entry.uri
                                                statusMessage = "History image selected."
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                historyEntries = AppStorage.getHistory(context)
                                statusMessage = "Wallpaper history refreshed."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("REFRESH HISTORY")
                        }

                        TextButton(
                            onClick = {
                                AppStorage.clearHistory(context)
                                historyEntries = emptyList()
                                statusMessage = "Wallpaper history cleared."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("CLEAR HISTORY")
                        }
                    }
                }

                }
                if (showImagePreview && selectedImage != null) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showImagePreview = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF050811)).padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("WALLPAPER PREVIEW", color = Color.White, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { showImagePreview = false }) { Text("CLOSE", color = Accent) }
                            }
                            AsyncImage(
                                model = selectedImage,
                                contentDescription = "Full screen wallpaper preview",
                                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        selectedImage?.let { uri ->
                                            val updated = favouriteImages.toMutableSet()
                                            if (uri.toString() in updated) updated.remove(uri.toString()) else updated.add(uri.toString())
                                            favouriteImages = updated
                                            preferences.edit().putStringSet("favourite_images", updated).apply()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (selectedImage?.toString() in favouriteImages) "★ SAVED" else "☆ FAVOURITE") }
                                Button(
                                    onClick = { selectedImage?.let { applyWallpaper(it) }; showImagePreview = false },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isApplying
                                ) { Text("APPLY") }
                            }
                        }
                    }
                }

                if (selectedTab == 2) {
                // ROTATION INSIGHTS
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("ROTATION INSIGHTS", color = Accent, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("TOTAL CHANGES", color = Color.LightGray, fontSize = 10.sp)
                                Text("${preferences.getInt("total_rotation_count", 0)}", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("UNREADABLE SKIPPED", color = Color.LightGray, fontSize = 10.sp)
                                Text("${preferences.getInt("unreadable_image_count", 0)}", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider(color = Color(0x334F6688))
                        Text(if (smartScheduleEnabled) "Smart schedule is ON • rotation speed changes through the day" else "Fixed schedule is ON • ${formatRotationInterval(intervalSeconds)} between changes", color = AccentMint, fontSize = 12.sp)
                        Text("Images in selected folder: ${imageUris.size}. Statistics count successful wallpaper changes.", color = Color.LightGray, fontSize = 12.sp)
                    }
                }

                // FAVOURITE-ONLY ROTATION
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBackground), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("FAVOURITE-ONLY ROTATION", color = Accent, fontWeight = FontWeight.Bold)
                                Text("Rotate only pictures you have starred in Gallery.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            Switch(checked = favouritesOnlyRotation, onCheckedChange = { enabled ->
                                favouritesOnlyRotation = enabled
                                preferences.edit().putBoolean("favourites_only_rotation", enabled)
                                    .putLong("schedule_refresh_request", System.currentTimeMillis()).apply()
                            })
                        }
                        Text(if (favouritesOnlyRotation) "ON • ${favouriteImages.size} favourite images saved. Excluded images are still skipped." else "OFF • All non-excluded images are eligible for rotation.", color = AccentMint, fontSize = 11.sp)
                        if (favouritesOnlyRotation && favouriteImages.isEmpty()) {
                            Text("Star some images in Gallery first, otherwise rotation will have no eligible pictures.", color = Color(0xFFFFC078), fontSize = 11.sp)
                        }
                    }
                }

                // BACKUP & RESTORE                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBackground), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("BACKUP & RESTORE", color = Accent, fontWeight = FontWeight.Bold)
                        Text("Save favourites, exclusions, wallpaper history, rotation schedule, destination and display mode in one portable file.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 18.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { backupExportLauncher.launch("Wallpaper-Shuttle-Backup.json") }, modifier = Modifier.weight(1f)) { Text("EXPORT") }
                            OutlinedButton(onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.weight(1f)) { Text("RESTORE") }
                        }
                        Text("Android folder permission is not transferable. After restoring on another phone or installation, select your wallpaper folder again. Rotation will not start automatically.", color = MutedText, fontSize = 11.sp, lineHeight = 16.sp)
                        backupMessage?.let { Text(it, color = AccentMint, fontSize = 12.sp, lineHeight = 17.sp) }
                    }
                }

                // DESTINATION
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "WALLPAPER DESTINATION",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = destination == WallpaperManager.FLAG_SYSTEM,
                                onClick = {
                                    destination = WallpaperManager.FLAG_SYSTEM
                                    preferences.edit()
                                        .putInt("destination", destination)
                                        .commit()
                                },
                                label = { Text("Home") }
                            )

                            FilterChip(
                                selected = destination == WallpaperManager.FLAG_LOCK,
                                onClick = {
                                    destination = WallpaperManager.FLAG_LOCK
                                    preferences.edit()
                                        .putInt("destination", destination)
                                        .commit()
                                },
                                label = { Text("Lock") }
                            )

                            FilterChip(
                                selected = destination ==
                                    (WallpaperManager.FLAG_SYSTEM or
                                     WallpaperManager.FLAG_LOCK),
                                onClick = {
                                    destination =
                                        WallpaperManager.FLAG_SYSTEM or
                                        WallpaperManager.FLAG_LOCK
                                    preferences.edit()
                                        .putInt("destination", destination)
                                        .commit()
                                },
                                label = { Text("Both") }
                            )
                        }
                    }
                }

                // WALLPAPER DISPLAY MODE
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "WALLPAPER DISPLAY MODE",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = displayMode == "crop",
                                onClick = {
                                    displayMode = "crop"
                                    preferences.edit()
                                        .putString("display_mode", displayMode)
                                        .commit()
                                },
                                label = { Text("Crop") }
                            )

                            FilterChip(
                                selected = displayMode == "fit",
                                onClick = {
                                    displayMode = "fit"
                                    preferences.edit()
                                        .putString("display_mode", displayMode)
                                        .commit()
                                },
                                label = { Text("Fit") }
                            )

                            FilterChip(
                                selected = displayMode == "stretch",
                                onClick = {
                                    displayMode = "stretch"
                                    preferences.edit()
                                        .putString("display_mode", displayMode)
                                        .commit()
                                },
                                label = { Text("Stretch") }
                            )
                        }

                        Text(
                            when (displayMode) {
                                "fit" -> "Shows the complete original image. Black space may appear where the image ratio differs from the screen."
                                "stretch" -> "Fills every part of the screen but may distort the image."
                                else -> "Fills the entire screen edge-to-edge, preserving image proportions while trimming excess edges."
                            },
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }

                // BACKGROUND RELIABILITY
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "KEEP WALLPAPER SHUTTLE RUNNING",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val powerManager = context.getSystemService(
                                        Context.POWER_SERVICE
                                    ) as android.os.PowerManager

                                    if (powerManager.isIgnoringBatteryOptimizations(
                                            context.packageName
                                        )
                                    ) {
                                        statusMessage =
                                            "Battery optimisation is already disabled for Wallpaper Shuttle."
                                    } else {
                                        try {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                            statusMessage =
                                                "Approve unrestricted battery use for Wallpaper Shuttle."
                                        } catch (error: Exception) {
                                            statusMessage =
                                                "Open Android battery settings and allow unrestricted background use: ${error.message}"
                                        }
                                    }
                                } else {
                                    statusMessage = "This Android version does not require battery optimisation exemption."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("DISABLE BATTERY OPTIMISATION")
                        }

                        Text(
                            "Infinix XOS also needs Auto-start enabled and Wallpaper Shuttle locked in Recent Apps. In Phone Master or XManager, allow this app to auto-start and exclude it from memory cleaning. Do not use Force stop or Freeze.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                // ROTATION SETTINGS
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CardBackground
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "AUTOMATIC ROTATION",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            formatRotationInterval(intervalSeconds),
                            color = Color.White,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { intervalMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("CHANGE INTERVAL  •  ${formatRotationInterval(intervalSeconds)}")
                            }

                            DropdownMenu(
                                expanded = intervalMenuExpanded,
                                onDismissRequest = { intervalMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 420.dp)
                            ) {
                                listOf(
                                    5, 10, 20, 30, 40, 50,
                                    60, 120, 180, 300, 600, 900, 1800,
                                    3600, 7200, 21600, 43200, 86400
                                ).forEach { seconds ->
                                    DropdownMenuItem(
                                        text = { Text(formatRotationInterval(seconds)) },
                                        onClick = {
                                            intervalSeconds = seconds
                                            intervalMenuExpanded = false

                                            // Restart the active countdown using the newly selected interval.
                                            if (rotationRunning) {
                                                val nextChangeAt =
                                                    System.currentTimeMillis() +
                                                        seconds.toLong() * 1000L
                                                preferences.edit()
                                                    .putInt("interval_seconds", seconds)
                                                    .putLong("next_rotation_time", nextChangeAt)
                                                    .putLong("schedule_refresh_request", System.currentTimeMillis())
                                                    .commit()

                                                try {
                                                    WallpaperRotationService.start(
                                                        context,
                                                        WallpaperRotationService.ACTION_UPDATE_INTERVAL
                                                    )
                                                } catch (error: Exception) {
                                                    statusMessage =
                                                        "Could not update the active rotation timer: ${error.message}"
                                                }
                                            }
                                        },
                                        trailingIcon = {
                                            if (intervalSeconds == seconds) {
                                                Text("✓", color = Accent, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            "Short intervals use a continuous foreground service and keep the CPU awake for more reliable timing. This uses more battery. Longer intervals allow the CPU to sleep.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2940)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("SMART DAILY SCHEDULE", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("Automatically adapt the interval to the time of day", color = MutedText, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = smartScheduleEnabled,
                                        onCheckedChange = { enabled ->
                                            smartScheduleEnabled = enabled
                                            preferences.edit().putBoolean("smart_schedule_enabled", enabled).putLong("schedule_refresh_request", System.currentTimeMillis()).apply()
                                            statusMessage = if (enabled) "Smart daily schedule enabled." else "Using your fixed rotation interval."
                                        }
                                    )
                                }
                                if (smartScheduleEnabled) {
                                    listOf(
                                        "Morning • 5 AM–11 AM" to "Every 15 minutes",
                                        "Afternoon • 11 AM–5 PM" to "Every 30 minutes",
                                        "Evening • 5 PM–10 PM" to "Every 20 minutes",
                                        "Night • 10 PM–5 AM" to "Every 1 hour"
                                    ).forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(item.first, color = Color.White, fontSize = 11.sp)
                                            Text(item.second, color = AccentMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                    Text("Your selected fixed interval remains saved and is used whenever Smart Schedule is off.", color = MutedText, fontSize = 10.sp)
                                }
                            }
                        }

                        Button(
                            onClick = { startRotation() },
                            enabled = !rotationRunning && imageUris.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("START AUTOMATIC ROTATION")
                        }

                        OutlinedButton(
                            onClick = { pauseRotation() },
                            enabled = rotationRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("PAUSE ROTATION")
                        }

                        Text(
                            if (rotationRunning) "Rotation is running"
                            else "Rotation is paused",
                            color = if (rotationRunning) Color(0xFF86D6A3)
                            else Color.LightGray
                        )

                        Text(
                            "An ongoing silent notification stays visible while rotation is active. Press Pause to stop it.",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }

                statusMessage?.let {
                    Text(
                        it,
                        color = Color(0xFFB7C8FF),
                        fontSize = 13.sp
                    )
                }

                HorizontalDivider(color = Color(0xFF344055))

                }
                if (selectedTab == 3) {
                    // ABOUT — app overview, feature guide, developer and support
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(82.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .border(1.dp, Accent.copy(alpha = 0.65f), RoundedCornerShape(24.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("WS", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            }
                            Text(
                                "WALLPAPER SHUTTLE",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Your wallpapers. Your style.",
                                color = Accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "A personal wallpaper manager that helps you organise your favourite images and change the look of your phone automatically.",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("OFFLINE", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("Designed for local use", color = Color.LightGray, fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("5s–24h", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("Rotation interval", color = Color.LightGray, fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("3 MODES", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("Home • Lock • Both", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("WHAT YOU CAN DO", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val aboutFeatures = listOf(
                                "Automatic wallpaper rotation" to "Run continuous rotation using Android’s system-managed live wallpaper engine with smooth crossfades, memory-aware image decoding and recovery from unreadable photos.",
                                "Your own image folders" to "Choose a folder on your device and scan its images, including subfolders.",
                                "Gallery and random shuffle" to "Preview available pictures and select a random image whenever you like.",
                                "Wallpaper history" to "Review recently applied wallpapers and select one again.",
                                "Image exclusions" to "Keep selected pictures out of automatic rotation, and restore them later.",
                                "Home, Lock or Both" to "Choose which phone screen receives the wallpaper.",
                                "Crop (Fill Screen), Fit and Stretch" to "Choose edge-to-edge coverage, full-image viewing or stretched display."
                            )
                            aboutFeatures.forEachIndexed { index, feature ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("${index + 1}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(feature.first, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(feature.second, color = Color.LightGray, fontSize = 12.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("GETTING STARTED", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val steps = listOf(
                                "1. Open Gallery and choose the folder containing your wallpapers.",
                                "2. Wait for the folder scan to finish and browse your images.",
                                "3. Open Settings to choose Home, Lock or Both and your display mode.",
                                "4. Set the rotation interval, then return to Home and tap Start.",
                                "5. Use Pause whenever you want to stop automatic changes."
                            )
                            steps.forEach { step ->
                                Text(step, color = Color.LightGray, fontSize = 12.sp, lineHeight = 19.sp)
                            }
                            HorizontalDivider(color = Color(0xFF344055))
                            Text("DISPLAY MODES", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Crop fills the display edge-to-edge while preserving proportions, trimming excess edges when needed. Fit shows the entire original image but can leave black space. Stretch fills the display but may distort the picture.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 19.sp)
                            Text("Automatic rotation runs through Android’s system-managed live wallpaper engine, so it is not dependent on keeping this app open. Rotation happens while the wallpaper is visible; Android may pause it when the display is off and show a fresh image when the screen becomes visible again. The engine refreshes your selected folder, remembers the last image, recovers its timer after wallpaper surface recreation, and records useful status information. Lock-screen live wallpaper support depends on your Android version and device manufacturer. Force stop can still stop wallpaper activity until you reopen the app.", color = Color(0xFFB7C8FF), fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text("DEVELOPER", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("David-Tyra", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("Created with care for personalisation and simplicity.", color = Color.LightGray, fontSize = 12.sp)
                            HorizontalDivider(color = Color(0xFF344055))
                            Text("CONTACT & SUPPORT", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("kdavidtyra@gmail.com", color = Color.White, fontSize = 13.sp)
                            Text("+256 701 303 855", color = Color.White, fontSize = 13.sp)
                            Text("App: Wallpaper Shuttle", color = Color.LightGray, fontSize = 11.sp)
                            Text("Version 1.22", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }

                    Text(
                        "Thank you for using Wallpaper Shuttle.",
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

            NavigationBar(
                containerColor = Color(0xFF111A2A),
                contentColor = Color.White,
                tonalElevation = 12.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("⌂") },
                    label = { Text("Home", fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("▦") },
                    label = { Text("Gallery", fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙") },
                    label = { Text("Settings", fontWeight = if (selectedTab == 2) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("ⓘ") },
                    label = { Text("About", fontWeight = if (selectedTab == 3) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.16f),
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )
            }
        }
    }
}
}
