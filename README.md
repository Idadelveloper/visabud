This is a Kotlin Multiplatform project targeting Android, iOS, Web, Server.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

* [/shared](./shared/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./shared/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run Server

To build and run the development version of the server, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :server:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :server:run
  ```

### Build and Run Web Application

To build and run the development version of the web app, use the run configuration from the run widget
in your IDE's toolbar or run it directly from the terminal:

- for the Wasm target (faster, modern browsers):
    - on macOS/Linux
      ```shell
      ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
      ```
    - on Windows
      ```shell
      .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
      ```
- for the JS target (slower, supports older browsers):
    - on macOS/Linux
      ```shell
      ./gradlew :composeApp:jsBrowserDevelopmentRun
      ```
    - on Windows
      ```shell
      .\gradlew.bat :composeApp:jsBrowserDevelopmentRun
      ```

### Build and Run iOS Application

Preferred workflow (avoids IDE CidrBuild toolchain):
- Build iOS frameworks with Gradle
  - Simulator (Apple Silicon):
    ```shell
    ./gradlew :composeApp:buildIosSim
    # or
    ./scripts/build-ios.sh
    ```
  - Device:
    ```shell
    ./gradlew :composeApp:buildIosDevice
    # or
    ./scripts/build-ios.sh device
    ```
  - Universal XCFramework:
    ```shell
    ./gradlew :composeApp:buildIosXCFramework
    # or
    ./scripts/build-ios.sh xcframework
    ```
- Open iosApp/iosApp.xcodeproj in Xcode and run on a simulator or device. Ensure it links the generated ComposeApp framework.

If you prefer running from IDE run configs, ensure you use the Gradle tasks above. The built-in "Build" action that uses CidrBuild may fail with tooling-related exceptions in some setups.

### Troubleshooting: java.lang.IllegalThreadStateException: process hasn't exited
If you see this error when triggering an iOS build from the IDE:
- Cause: This often comes from the IDE’s CidrBuild process trying to manage a native build process that hasn’t terminated yet.
- Fix/Workaround:
  - Use the Gradle tasks provided above (buildIosSim / buildIosDevice / buildIosXCFramework) instead of the IDE’s C/C++ toolchain build.
  - Or use the helper script: `scripts/build-ios.sh`.
  - Then run the app via Xcode using the generated framework, or keep using Gradle.
- Still stuck? Share the exact action you triggered, IDE version, and the full stack trace.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).# visabud
