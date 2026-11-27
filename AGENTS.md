# AGENTS.md

## Project

- Single-module Android app (`:app`): Kotlin + Jetpack Compose + Material 3 (Coroutines/Flow, one ViewModel per screen). Package/applicationId: `io.github.danivi.yanistickers`.
- Current code is only the Compose scaffold (`MainActivity` + `ui/theme`). The full product spec is **`.personal/Instrucciones.md`** (Spanish, gitignored personal notes). Consult it for full details; never commit or delete `.personal/`.
- **Core goal & constraints:** Personal, sideloaded Android app (no Play Store, no iOS, no web) to create, store, export/import, and add sticker packs to WhatsApp. Must work 100% offline with no server, no Room/SQLite, no `ffmpeg-kit`, and no sound stickers. Top priority is fast backup/recovery via explicit `.zip` export/import.
- **Agent workflow:**
  - Work phase by phase (see Phases below); do not start the next phase until the current phase's acceptance criterion passes.
  - Before starting each phase, list the files to be created or modified.
  - Keep code simple: no unnecessary dependencies.
  - Write code comments, UI copy, and commit messages in Spanish (code identifiers/symbols remain in English).
  - The WhatsApp details in the spec were written from memory: verify limits, URIs, columns, and `ENABLE_STICKER_PACK` intent flow against the official `WhatsApp/stickers` repo before implementing, and note any discrepancies.

## Architecture & Data Model

- **Storage layout** under `filesDir/packs/<packId>/`:
  - `pack.json`: `{ schemaVersion: 1, identifier, name, publisher, animated, imageDataVersion, trayImageFile, stickers: [{ file, emojis }], createdAt, updatedAt }`
  - `tray.webp` (or `.png`): 96×96 static tray icon (≤ ~50 KB)
  - `stickers/<stickerId>.webp`: sticker files (filenames without spaces or special characters)
- **Pack rules:**
  - `identifier` is permanent/stable (WhatsApp identifies packs by it).
  - Increment `imageDataVersion` on every pack edit so WhatsApp refreshes its cache.
  - A pack is **either all static or all animated** (`animated` boolean); never mix in the same pack.
  - Validate WhatsApp limits before allowing "Add to WhatsApp" and surface per-sticker errors: 3–30 stickers/pack, 1–3 emojis/sticker, 512×512 WebP, static ≤ ~100 KB, animated ≤ ~500 KB / ≤ 10 s / min frame duration ~8 ms, text fields ≤ ~128 chars with restricted identifier characters (verify exact values against `WhatsApp/stickers`).
- **Backup / Restore (`.zip` via SAF):**
  - Export (single pack or all) with `ACTION_CREATE_DOCUMENT`; import with `ACTION_OPEN_DOCUMENT`.
  - Format: `manifest.json` (`schemaVersion`, `packs`, `exportedAt`) + `<packId>/pack.json`, `<packId>/tray.webp`, `<packId>/stickers/*.webp`.
  - Import must validate `schemaVersion`/structure, guard against **zip-slip**, handle `identifier` conflicts (replace, duplicate with new id, or skip), and offer immediate "Add to WhatsApp".
- **WhatsApp Integration:**
  - `ContentProvider` with authority `${applicationId}.stickercontentprovider`, `android:exported="true"`, `android:readPermission="com.whatsapp.sticker.READ"`.
  - Serve `metadata`, `metadata/<packId>`, `stickers/<packId>`, and `stickers_asset/<packId>/<file>` via `openAssetFile` (`ParcelFileDescriptor`/`AssetFileDescriptor`) backed by `filesDir/packs/` (not `assets/`).
  - Manifest `<queries>` for `com.whatsapp` and `com.whatsapp.w4b`.
  - Launch `com.whatsapp.intent.action.ENABLE_STICKER_PACK` (`sticker_pack_id`, `sticker_pack_authority`, `sticker_pack_name`) via Activity Result API; handle WhatsApp not installed, invalid pack, and user cancellation.
- **Sticker Pipeline:**
  - Static: Photo Picker → crop/fit 512×512 with transparency → WebP encode (`Bitmap.compress`), stepping down quality to fit size.
  - Animated: `libwebp` (`WebPAnimEncoder`) via NDK/CMake + thin custom JNI wrapper (`arm64-v8a` ABI is sufficient for personal use). Inputs: already-valid animated WebP (import & validate **without recompressing**), GIF, or short MP4 (≤ 10 s via `MediaMetadataRetriever`/`MediaCodec`). Decode frame-by-frame to avoid OOM; auto-tune quality/FPS/duration to stay ≤ 500 KB.
- **Screens:** Pack List, Pack Editor, Sticker Creator, Backup/Restore, Validation.

## Phases

1. **Skeleton + Provider:** Project setup, `ContentProvider` reading from `filesDir`, pre-built test packs (1 static + 1 animated). *Acceptance: pack appears in WhatsApp and stickers send.*
2. **Storage & Pack List:** Data model, pack CRUD, validation.
3. **Backup/Restore:** `.zip` export/import with conflict handling. *Acceptance: uninstall, reinstall, import, and re-add to WhatsApp.*
4. **Static Sticker Creator:** Picker, 512×512 crop, WebP encoding, emojis.
5. **Animated Sticker Creator:** NDK `libwebp`, GIF/video → animated WebP, size fitting, validation.
6. **Extras:** ML Kit Subject Segmentation (bg removal), `.wastickers` import, UI polish.

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
