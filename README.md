# Wallpaper Shuttle

Wallpaper Shuttle is an offline-first Android wallpaper manager by David-Tyra.

## Features

- Select an image folder using Android's folder picker.
- Scan images in the selected folder and its subfolders.
- Browse a three-column image gallery and choose a picture.
- Apply a wallpaper to the Home screen, Lock screen, or both.
- Choose Crop (edge-to-edge), Fit (show the whole image), or Stretch.
- Random automatic wallpaper rotation, with repeats allowed.
- Choose rotation intervals from 5 seconds to 24 hours.
- Keep a wallpaper history and exclude or restore pictures.
- Save app settings locally and resume scheduled rotation after device restarts through WorkManager.

## Using the app

1. Open Gallery and select the folder containing your wallpapers.
2. Select an image and use Apply Wallpaper for a manual change.
3. Open Settings to select Home, Lock, or Both and choose a display mode.
4. Choose a rotation interval, then tap Start on Home or in Settings.
5. Tap Pause to stop automatic rotation.

## Rotation timing and Android behaviour

The minimum selectable interval is 5 seconds. Wallpaper Shuttle schedules automatic changes with Android WorkManager so scheduled work can persist across app restarts and device reboots. Android may defer background work because of Doze, battery optimisation, or manufacturer power management. Therefore, short intervals are best-effort and are not exact alarms.

The app intentionally does not request notification permission or show routine notifications.

## Privacy and storage

The app works with the image folder that the user selects. Folder access, interval settings, exclusions, and wallpaper history are stored locally on the device. Normal app operation does not require an account or internet connection.

## Build

The project uses Android Gradle Plugin, Kotlin, Jetpack Compose, and Java 17. To build a debug APK in a configured Android environment:

```bash
./gradlew --no-daemon --max-workers=1 clean assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Developer

David-Tyra  
kdavidtyra@gmail.com  
+256 701 303 855
