# runtimer

Personal Android run timer for a Samsung Galaxy A13 on Android 14. Shamelessly vibe-coded because I can't be asked to learn Android app development.

## Build

This workspace includes a project-local Android SDK and a Gradle wrapper. Build
the debug APK with JDK 17 or 21:

```bash
./gradlew :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install On The Phone

1. Enable Developer options and USB debugging on the phone.
2. Connect the phone over USB and accept the debugging prompt.
3. Run:

```bash
./install-debug.sh
```

If `adb devices` shows the phone as `unauthorized`, unlock the phone and accept the USB debugging dialog, then run the script again.

## Phone Setup

On first launch, grant precise location and notification permission. If the app shows the lock-screen alert warning, tap **Enable lock-screen alerts** and allow full-screen notifications for Run Timer.

For Samsung battery management, set Run Timer to unrestricted battery usage before relying on it during a run:

```text
Settings > Apps > Run Timer > Battery > Unrestricted
```

## Schedule

Enter one phase per row. Each row has minutes, seconds, a label, a drag handle,
and a delete button. Filling the empty row at the bottom automatically creates
another empty row.

## History

Completed and aborted runs are saved in the history log. Tap a run to view its
recorded route with phase-colored segments, start and end markers, total
distance, and phase distances.

## License

Run Timer's own source code is licensed under the GNU General Public License
version 3 only. See [LICENSE](LICENSE).

Bundled build wrapper files and build/test dependencies remain under their own
licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
