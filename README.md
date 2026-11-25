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
- Run continuous wallpaper rotation in a foreground service with a silent ongoing notification.
- Use a service watchdog to detect and recover missed rotation callbacks or a dead rotation thread while the service process remains active.
- Keep the CPU awake for intervals of five minutes or less while rotation is active; longer intervals release the wake lock to conserve battery.
- Offer an in-app shortcut to request exemption from Android battery optimisation.
- Resume user-enabled rotation after device restart or app replacement when Android permits the service to start.
- Save app settings locally and retain WorkManager as a fallback if a foreground service cannot start.

## Using the app

1. Open Gallery and select the folder containing your wallpapers.
2. Select an image and use Apply Wallpaper for a manual change.
3. Open Settings to select Home, Lock, or Both and choose a display mode.
4. Choose a rotation interval, then tap Start on Home or in Settings.
5. Tap Pause to stop automatic rotation.

## Rotation timing and Android behaviour

The minimum selectable interval is 5 seconds. Once the user starts rotation, Wallpaper Shuttle runs a foreground service with a silent, ongoing notification. For selected intervals of five minutes or less, it also holds a timed partial wake lock and renews it during rotation so the CPU can continue processing changes while the screen is off. This improves short-interval responsiveness but can noticeably increase battery use.

Longer selected intervals release the partial wake lock to let the phone sleep. A service watchdog checks for missed rotation cycles and recreates the rotation thread if it dies while the service remains alive. Android, Doze, battery optimisation, and manufacturer power management can still restrict or stop background activity. Exact five-second timing cannot be guaranteed, and Android's Force stop action disables background work until the user launches the app again.

The app resumes an enabled rotation after a device restart or app update when Android permits the foreground service to start. On Infinix/XOS, also exempt Wallpaper Shuttle from battery optimisation, enable Auto-start in Phone Master/XManager, allow background activity, and lock the app in Recent Apps so the cleaner skips it. Menu names vary by XOS version.

The app uses a silent ongoing foreground-service notification while automatic rotation is active. On Android 13 and later, allow notification permission so that the service notification can appear in the notification drawer.

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
