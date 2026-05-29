# Third-Party Notices

Run Timer's own source code is licensed under GPLv3. The following third-party
components are used by the project and remain under their own licenses.

## Included In This Repository

| Component | Files | License |
| --- | --- | --- |
| Gradle wrapper | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Apache License 2.0 |

A copy of the Apache License 2.0 is included at
`licenses/APACHE-2.0.txt`.

## Build/Test Dependencies Not Included In The App

| Component | How Used | License |
| --- | --- | --- |
| Gradle Build Tool 8.10.2 | Downloaded by the Gradle wrapper to build the project | Apache License 2.0 |
| Android Gradle Plugin 8.7.3 | Build plugin declared in `build.gradle` | Apache License 2.0 |
| Android SDK command-line tools/platform/build-tools | Local build/install tooling; ignored by Git in `.android-sdk/` | Android SDK terms and component licenses |

The app has no third-party runtime library dependencies beyond Android platform
APIs.
