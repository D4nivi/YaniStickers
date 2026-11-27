# AGENTS.md

## Project

- Single-module Android app (`:app`): Kotlin + Jetpack Compose + Material 3. Package/applicationId: `io.github.danivi.yanistickers`.
- Current code is only the Compose scaffold (`MainActivity` + `ui/theme`). The real product spec is **`.personal/Instrucciones.md`** (Spanish, gitignored personal notes). Read it before implementing anything; it defines the data model, WhatsApp integration, phases, and acceptance criteria. Never commit or delete `.personal/`.
- Hard constraints from the spec: fully offline, no server/database (no Room/SQLite), sticker packs as files under `filesDir/packs/<packId>/` (`pack.json`, tray, WebP stickers), animated stickers supported from the start, no `ffmpeg-kit`, no sound stickers.
- Work phase by phase; do not start the next phase until the current phase's acceptance criterion passes.
- The WhatsApp details in the spec were written from memory: verify limits/URIs/columns against the official `WhatsApp/stickers` repo before implementing them.

## Toolchain

- Windows project. Use the wrapper: `.\gradlew.bat ...` (Gradle 9.6.0). JDK 21 is installed; Java source/target is 11.
- AGP 9.4.1 provides Kotlin 2.2.10 (built-in Kotlin). Do not add the old `org.jetbrains.kotlin.android` plugin; `kotlin-compose` is the only Kotlin plugin applied.
- compileSdk/targetSdk 37, minSdk 30, Compose BOM 2026.02.01.
- Declare all dependencies/plugins in `gradle/libs.versions.toml` and reference them via `alias(...)`/`libs.*`. Repository declarations are locked in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); do not add `repositories {}` to module build files.
- Animated WebP infrastructure does **not** exist yet: no NDK/CMake, libwebp, Coil, or ML Kit dependencies. Adding them is new work, not configuration tweaking.

## Commands

- Build debug APK: `.\gradlew.bat assembleDebug`
- All unit tests: `.\gradlew.bat test`
- Single unit test: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.danivi.yanistickers.ExampleUnitTest"`
- Instrumented tests (device/emulator required): `.\gradlew.bat connectedAndroidTest`
- Lint: `.\gradlew.bat lint`
- Install on a connected device: `.\gradlew.bat installDebug`
- No formatter/linter plugins (ktlint, detekt) or CI workflows are configured.

## Release / signing

- There is no signing config, so `assembleRelease` produces an unsigned APK. Distribution is manual sideloading; there is no Play Store flow.
- R8 optimization is disabled for release in `app/build.gradle.kts`.
- Keep rules belong in `app/src/main/keepRules/*.keep` (AGP 9 layout), not `proguard-rules.pro`.
- Keystores are gitignored. The spec requires keeping one stable signing key forever; changing it blocks updates and loses local data on reinstall.

## Layout

- App code: `app/src/main/java/io/github/danivi/yanistickers/`
- Unit tests: `app/src/test/java/...`; instrumented tests: `app/src/androidTest/java/...`
- The spec explicitly requires unit tests for pack validation and ZIP import/export.
