# AGENTS.md

## Project

- Single-module Android app (`:app`): Kotlin + Jetpack Compose + Material 3 (Coroutines/Flow, one ViewModel per screen). Package/applicationId: `io.github.danivi.yanistickers`.
- **Core goal & constraints:** Personal, sideloaded Android app (no Play Store, no iOS, no web) to create, store, export/import, and add static and animated sticker packs to WhatsApp. Core functionality must work 100% offline with no backend server, no Room/SQLite, no `ffmpeg-kit`, and no sound stickers (network is only used optionally for in-app self-updates via GitHub Releases). Never commit or delete `.personal/` (gitignored personal notes/assets).
- **Conventions:**
  - Keep code simple: no unnecessary dependencies.
  - Write code comments, UI copy, and commit messages in Spanish (code identifiers/symbols remain in English).
  - Declare all dependencies/plugins in `gradle/libs.versions.toml` and reference them via `alias(...)`/`libs.*`. Repository declarations are locked in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); do not add `repositories {}` to module build files.

## Current State

- **Status:** App terminada (`v1.0.0`, `versionCode = 10000`) y subida a GitHub (`https://github.com/D4nivi/YaniStickers`). Primera release automática fallida en CI (pendiente de revisión). `.\gradlew.bat assembleDebug test lint` pasa sin errores.
- **Implemented modules:**
  - `data/`: `PackModels` (`StickerItem.name`, `StickerPack.usesDefaultTray`), `PackJsonParser` (parse/serialize JSON, safe IDs/filenames, grapheme emoji splitting & emoji-only validation), `LocalPackStore` (atomic `pack.json` CRUD, `imageDataVersion` bump on every edit, `addSticker` with auto-tray replacement on first sticker when `usesDefaultTray = true`, `replaceSticker`, `updateTrayIcon`, `installImportedPack` for backup conflicts, orphan cleanup, canonical path checks), `SeedPackInstaller`, `DefaultTrayGenerator`, `BackupModels` & `PackBackupManager` (`.zip` export/import with `manifest.json`, zip-slip guard, staging & `REPLACE`/`DUPLICATE_NEW_ID`/`SKIP`), `StaticStickerEncoder` (`ImageDecoder` + `512×512`/`96×96` transparent framing + `WEBP_LOSSLESS`/`WEBP_LOSSY`), `AnimatedFrameExtractor`, `AnimatedStickerEncoder`, `FrameSamplingPlanner`, `GifMetadataParser`, `HardwareVideoFrameExtractor`, and `NativeWebpCodec`.
  - `update/`: `AppVersionComparator`, `GitHubUpdateClient` (queries `D4nivi/YaniStickers` `/releases/latest` via `HttpURLConnection`, streams `.apk` to `cacheDir/updates/` with ZIP header verification), and `ApkInstaller` (`canRequestPackageInstalls()` + `FileProvider` install intent).
  - `cpp/`: `CMakeLists.txt`, `animated_webp_jni.cpp`, and vendored `third_party/` (`libwebp` `WebPAnimEncoder`/`WebPAnimDecoder` compiled for `arm64-v8a`).
  - `whatsapp/`: `WhatsAppContract`, `StickerContentProvider` (serves `metadata`, `metadata/*`, `stickers/*`, `stickers_asset/*/*` from `filesDir/packs/`), and `WhatsAppBridge` (`ENABLE_STICKER_PACK` + `WhitelistCheck` for `com.whatsapp` and `com.whatsapp.w4b`).
  - `validation/`: `ImageContainerInspector` (pure-Kotlin RIFF/WebP + PNG header parser) and `PackValidator` / `ValidationModels` (enforces verified WhatsApp limits).
  - `ui/`:
    - `PackListScreen` / `PackListViewModel` / `AboutUpdateDialog`: pack sorting (name/createdAt asc/desc), 5-sticker preview strip per card, 3-dot menu (Editar, Duplicar, Exportar `.zip`, Borrar), `AddToWhatsAppButton`, empty state & `gatitas.jpg` footer (with `miau.mp3`), header `AboutHeaderButton` with red update badge, «Acerca de» dialog with GitHub repo link and in-app updater.
    - `PackEditorScreen` / `PackEditorViewModel`: metadata editing, custom tray launcher, list / 3-column grid toggle, optimistic scroll-safe reordering, grid detail dialog (`StickerDetailDialog`), «Usar como icono» / «Poner como icono del pack», sticker framing & metadata edit entry points.
    - `StaticStickerCreatorScreen` / `StaticStickerCreatorViewModel`: Photo Picker, `FIT`/`CROP`/pan/zoom/90°-rotate canvas with snap guide lines, collapsible «Avanzado» (in-app name, emoji-only picker, accessibility), edit mode & custom tray mode.
    - `AnimatedStickerCreatorScreen` / `AnimatedStickerCreatorViewModel`: Video Picker & Document Picker (`.webp`/`.gif`/`.mp4`), live framing preview (`TextureView`/`MediaPlayer` for video with trim `RangeSlider` & speed control; Coil for GIF/WebP), passthrough without recompression for already-valid animated WebPs, adaptive quality/FPS/duration fitting (`≤ 500 KB`, `≤ 10 s`), edit mode.
    - `BackupScreen` / `BackupViewModel`: SAF export all/single `.zip` (`ACTION_CREATE_DOCUMENT`), SAF import (`ACTION_OPEN_DOCUMENT`) with conflict resolution UI.
    - `WhatsAppActionHelper`, `StickerCreatorSharedUi`, `AppScreen`, `ui/theme/` (forced light theme on `#FFFFFF`, medium blue `#2B76D2` buttons, WhatsApp green `#20B858` button with left-aligned logo, vibrant red `#E52432` destructive actions, `Vanilla Caramel` font in `res/font/vanilla_caramel.otf` with `•` metadata separator).
  - **Unit tests (`app/src/test/java/...`):** `AnimatedStickerEncoderTest`, `FrameSamplingPlannerTest`, `GifMetadataParserTest`, `LocalPackStoreTest`, `PackBackupManagerTest`, `StaticStickerEncoderTest`, `AppVersionComparatorTest`, `GitHubUpdateClientTest`, `ImageContainerInspectorTest`, `PackValidatorTest`, `WhatsAppContractTest`.
- **UI/UX conventions:**
  - Validation is **transparent**: no "Válido" badges or dedicated Validation screen; only show concise inline error(s) when invalid and keep «Añadir a WhatsApp» disabled.
  - Adding the first sticker to a pack with the default letter icon (`usesDefaultTray = true`) automatically promotes that sticker (frame 0 if animated) to the `96×96` tray icon.

## Architecture & Data Model

- **Storage layout** under `filesDir/packs/<packId>/`:
  - `pack.json`: `{ schemaVersion: 1, identifier, name, publisher, animated, imageDataVersion, trayImageFile, usesDefaultTray, stickers: [{ file, emojis, accessibilityText?, name? }], createdAt, updatedAt, avoidCache? }`
  - `tray.webp` (or `.png`): static tray icon (`96×96` recommended; WhatsApp accepts `24..512` px, `≤ 50 KB`)
  - `stickers/<stickerId>.webp`: sticker files (filenames without spaces or special characters)
- **Pack rules & verified `WhatsApp/stickers` contract (`StickerPackValidator.java`):**
  - `identifier` is permanent/stable (`^[A-Za-z0-9._-]+$`, no `..`, `≤ 128` chars).
  - Increment `imageDataVersion` on every pack edit so WhatsApp refreshes its cache.
  - A pack is **either all static or all animated** (`animated` boolean); never mix in the same pack.
  - Exact validated limits: `3–30` stickers/pack, `1–3` emojis/sticker, `name`/`publisher`/`identifier` `1–128` chars, `accessibilityText` `≤ 125` (static) or `≤ 255` (animated) chars, `512×512` WebP, static `≤ 100 KB` (`1` frame), animated `≤ 500 KB` (`> 1` frame, `≤ 10000 ms`, `≥ 8 ms`/frame).
- **WhatsApp Integration:**
  - `ContentProvider` with authority `${applicationId}.stickercontentprovider`, `android:exported="true"`, `android:readPermission="com.whatsapp.sticker.READ"`.
  - Serves `metadata`, `metadata/<packId>`, `stickers/<packId>`, and `stickers_asset/<packId>/<file>` via `openAssetFile`; Manifest `<queries>` and `ENABLE_STICKER_PACK` target `com.whatsapp` and `com.whatsapp.w4b`.

## Toolchain & Commands

- Windows project. Use the wrapper: `.\gradlew.bat ...` (Gradle 9.6.0). JDK 21 installed; Java source/target 11.
- AGP 9.4.1 with built-in Kotlin 2.2.10 (`kotlin-compose` only; do not add `org.jetbrains.kotlin.android`).
- `compileSdk`/`targetSdk` 37, `minSdk` 30, Compose BOM 2026.02.01, NDK/CMake (`arm64-v8a`), Coil 3 (`coil-compose`, `coil-gif`), `org-json` (`testImplementation` only).
- Build debug APK: `.\gradlew.bat assembleDebug`
- All unit tests: `.\gradlew.bat test`
- Single unit test: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.danivi.yanistickers.validation.PackValidatorTest"`
- Instrumented tests: `.\gradlew.bat connectedAndroidTest`
- Lint: `.\gradlew.bat lint`
- Install on connected device: `.\gradlew.bat installDebug`
- No formatter/linter plugins (ktlint, detekt) configured. CI release workflow lives at `.github/workflows/release.yml` (triggers on `v*` tags or `workflow_dispatch`).

## Release / Signing

- `app/build.gradle.kts` configures release signing from env vars (`SIGNING_KEYSTORE_PATH`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) and allows overriding `versionName`/`versionCode` via `appVersionName`/`appVersionCode` Gradle properties or `APP_VERSION_NAME`/`APP_VERSION_CODE` env vars (defaults: `1.0.0` / `10000`). Without signing env vars, `assembleRelease` produces an unsigned APK.
- GitHub Actions uses secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- R8 optimization is disabled for release in `app/build.gradle.kts`.
- Keep rules belong in `app/src/main/keepRules/*.keep` (AGP 9 layout), not `proguard-rules.pro`.
- Keystores are gitignored; keep one stable signing key forever so in-app updates do not lose local data.

## Layout

- App code: `app/src/main/java/io/github/danivi/yanistickers/` (`data/`, `update/`, `validation/`, `whatsapp/`, `ui/`)
- Native code: `app/src/main/cpp/` (`CMakeLists.txt`, `animated_webp_jni.cpp`, `third_party/`)
- CI workflows: `.github/workflows/release.yml`
- Unit tests: `app/src/test/java/...`; instrumented tests: `app/src/androidTest/java/...`
