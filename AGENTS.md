# AGENTS.md

## Project

- Single-module Android app (`:app`): Kotlin + Jetpack Compose + Material 3 (Coroutines/Flow, one ViewModel per screen). Package/applicationId: `io.github.danivi.yanistickers`.
- The full product spec is **`.personal/Instrucciones.md`** (Spanish, gitignored personal notes). Consult it for full details; never commit or delete `.personal/`.
- **Core goal & constraints:** Personal, sideloaded Android app (no Play Store, no iOS, no web) to create, store, export/import, and add sticker packs to WhatsApp. Must work 100% offline with no server, no Room/SQLite, no `ffmpeg-kit`, and no sound stickers. Top priority is fast backup/recovery via explicit `.zip` export/import.
- **Agent workflow:**
  - Work phase by phase (see Phases below); do not start the next phase until the current phase's acceptance criterion passes.
  - Before starting each phase, list the files to be created or modified.
  - Keep code simple: no unnecessary dependencies.
  - Write code comments, UI copy, and commit messages in Spanish (code identifiers/symbols remain in English).
  - The WhatsApp details in the spec were written from memory: verify limits, URIs, columns, and `ENABLE_STICKER_PACK` intent flow against the official `WhatsApp/stickers` repo before implementing, and note any discrepancies.

## Current State

- **Implemented:** Phases 1, 2, 3, and 4 (`assembleDebug`, `test`, and `lint` pass; Phase 1 verified on real WhatsApp device).
  - `data/`: `PackModels` (`StickerItem.name`, `StickerPack.usesDefaultTray`), `PackJsonParser` (parse/serialize JSON, safe IDs/filenames, grapheme emoji splitting & emoji-only validation `isEmojiGrapheme`/`filterEmojiInput`), `LocalPackStore` (atomic `pack.json` CRUD, `imageDataVersion` bump on every edit, `addSticker` with auto-tray replacement on first sticker when `usesDefaultTray = true`, `updateTrayIcon`, `installImportedPack` for backup conflicts, orphan sticker cleanup, canonical path checks), `SeedPackInstaller`, `DefaultTrayGenerator`, `BackupModels` & `PackBackupManager` (`.zip` export/import with `manifest.json`, zip-slip protection, staging & `REPLACE`/`DUPLICATE_NEW_ID`/`SKIP` conflict resolution), `StaticStickerEncoder` (`ImageDecoder` + `512×512`/`96×96` transparent framing + `WEBP_LOSSLESS`/`WEBP_LOSSY` size-fitting and sticker-to-tray conversion).
  - `whatsapp/`: `WhatsAppContract`, `StickerContentProvider` (serves `metadata`, `metadata/*`, `stickers/*`, `stickers_asset/*/*` from `filesDir/packs/`), `WhatsAppBridge` (`ENABLE_STICKER_PACK` + `WhitelistCheck` for `com.whatsapp` and `com.whatsapp.w4b`).
  - `validation/`: `ImageContainerInspector` (pure-Kotlin RIFF/WebP + PNG header parser) and `PackValidator` / `ValidationModels` (enforces verified WhatsApp limits).
  - `ui/`: `PackListScreen` / `PackListViewModel` (pack sorting by name/createdAt asc/desc, single-pack `.zip` export, access to Backup), `PackEditorScreen` / `PackEditorViewModel` (optimistic scroll-safe reordering, list/3-column grid toggle, grid detail dialog without reorder, "Usar como icono" on stickers, custom tray launcher), `StaticStickerCreatorScreen` / `StaticStickerCreatorViewModel` (Photo Picker, interactive `FIT`/`CROP`/pan/zoom/rotate canvas, live WebP size, collapsible "Avanzado" with in-app sticker name, emoji-only selector with `EmojiPickerDialog`, accessibility), `BackupScreen` / `BackupViewModel` (SAF export all/single `.zip`, SAF import with exclusive conflict-resolution view), `WhatsAppActionHelper` (`rememberWhatsAppAddAction`, `LocalStickerThumbnail`, `StickerMetadataEditorFields`), `AppScreen`.
  - **Tests:** `WhatsAppContractTest`, `ImageContainerInspectorTest`, `PackValidatorTest`, `LocalPackStoreTest`, `PackBackupManagerTest`, `StaticStickerEncoderTest`.
- **UI/UX conventions settled in Phases 2–4:**
  - Validation must remain **transparent** to the user: no "Válido" badges, no dedicated Validation screen, and no technical rule/ID/version clutter in the UI. Only when invalid, show concise inline error(s) and keep **"Añadir a WhatsApp"** disabled.
  - Pack card actions (Editar, Duplicar, Exportar `.zip`, Borrar) live inside a 3-dot `DropdownMenu`; TopAppBar has Sort, Copia de seguridad, and Recargar.
  - Adding the first sticker to a pack with the default letter icon (`usesDefaultTray = true`) automatically promotes that sticker (frame 0 if animated) to the `96×96` pack tray icon.
  - "Añadir sticker" opens `StaticStickerCreator` on static packs; on animated packs it currently shows a Phase 5 notice (custom tray icon works on both).
- **Next up (remaining work):**
  - **Device acceptance check (Phase 3–4):** Verify uninstall → reinstall → import `.zip` → add to WhatsApp, and end-to-end static sticker creation on a physical device.
  - **Phase 5:** Animated sticker creator (NDK/CMake `libwebp` `WebPAnimEncoder` JNI wrapper `arm64-v8a`, GIF/MP4/`WebP animado` import without recompressing already-valid WebPs, frame-by-frame size-fitting `≤ 500 KB`, animated preview with Coil, auto-tray from frame 0).
  - **Phase 6:** Extras (ML Kit Subject Segmentation background removal, `.wastickers` import, 5-sticker preview strip on main pack cards from `.personal/TODO.md`, UI polish).

## Architecture & Data Model

- **Storage layout** under `filesDir/packs/<packId>/`:
  - `pack.json`: `{ schemaVersion: 1, identifier, name, publisher, animated, imageDataVersion, trayImageFile, usesDefaultTray, stickers: [{ file, emojis, accessibilityText?, name? }], createdAt, updatedAt, avoidCache? }`
  - `tray.webp` (or `.png`): static tray icon (`96×96` recommended; WhatsApp accepts `24..512` px, `≤ 50 KB`)
  - `stickers/<stickerId>.webp`: sticker files (filenames without spaces or special characters)
- **Pack rules & verified `WhatsApp/stickers` contract (`StickerPackValidator.java`):**
  - `identifier` is permanent/stable (`^[A-Za-z0-9._-]+$`, no `..`, `≤ 128` chars — URI/filesystem-safe subset of WhatsApp's `[\w-.,'\s]+`).
  - Increment `imageDataVersion` on every pack edit so WhatsApp refreshes its cache.
  - A pack is **either all static or all animated** (`animated` boolean); never mix in the same pack.
  - Exact validated limits: `3–30` stickers/pack, `1–3` emojis/sticker, `name`/`publisher`/`identifier` `1–128` chars, `accessibilityText` `≤ 125` (static) or `≤ 255` (animated) chars, `512×512` WebP, static `≤ 100 KB` (`1` frame), animated `≤ 500 KB` (`> 1` frame, `≤ 10000 ms`, `≥ 8 ms`/frame).
- **Backup / Restore (`.zip` via SAF) — Phase 3:**
  - Export (single pack or all) with `ACTION_CREATE_DOCUMENT`; import with `ACTION_OPEN_DOCUMENT`.
  - Format: `manifest.json` (`schemaVersion`, `packs`, `exportedAt`) + `<packId>/pack.json`, `<packId>/tray.webp` (or `.png`), `<packId>/stickers/*.webp`.
  - Import must validate `schemaVersion`/structure, guard against **zip-slip**, handle `identifier` conflicts (replace, duplicate with new id, or skip), and offer immediate "Add to WhatsApp".
- **WhatsApp Integration:**
  - `ContentProvider` with authority `${applicationId}.stickercontentprovider`, `android:exported="true"`, `android:readPermission="com.whatsapp.sticker.READ"`.
  - Serve `metadata`, `metadata/<packId>`, `stickers/<packId>` (columns: `sticker_file_name`, `sticker_emoji`, `sticker_accessibility_text`), and `stickers_asset/<packId>/<file>` (3 segments; `pack.json` `stickers/` prefix is stripped in cursor) via `openAssetFile`.
  - Manifest `<queries>` for `com.whatsapp` and `com.whatsapp.w4b`.
  - Launch `com.whatsapp.intent.action.ENABLE_STICKER_PACK` (`sticker_pack_id`, `sticker_pack_authority`, `sticker_pack_name`) via Activity Result API.
- **Sticker Pipeline (Phases 4–5):**
  - Static: Photo Picker → crop/fit `512×512` with transparency → WebP encode (`Bitmap.compress`), stepping down quality to fit `≤ 100 KB`.
  - Animated: `libwebp` (`WebPAnimEncoder`) via NDK/CMake + thin custom JNI wrapper (`arm64-v8a` ABI). Inputs: already-valid animated WebP (import & validate **without recompressing**), GIF, or short MP4 (≤ 10 s via `MediaMetadataRetriever`/`MediaCodec`). Decode frame-by-frame to avoid OOM; auto-tune quality/FPS/duration to stay `≤ 500 KB`.
- **Screens:** Pack List, Pack Editor (list & 3-column grid), Static Sticker / Tray Creator, Backup/Restore (Phase 3), Animated Sticker Creator (Phase 5). (No standalone Validation screen.)

## Phases

1. **[x] Skeleton + Provider:** Project setup, `ContentProvider` reading from `filesDir`, pre-built test packs (1 static + 1 animated). *Acceptance passed on device.*
2. **[x] Storage & Pack List:** Data model, CRUD (`LocalPackStore`), pure-Kotlin WebP/PNG validator, `PackList` & `PackEditor` screens + ViewModels, unit tests.
3. **[x] Backup/Restore:** `.zip` export/import via SAF with conflict handling, zip-slip guard + ZIP unit tests. *Pending final uninstall/reinstall device verification.*
4. **[x] Static Sticker Creator:** Photo Picker, `512×512` crop/fit with transparency, WebP encoding, custom & auto tray icon, emoji-only picker, sticker names, list/grid toggle & scroll-safe reordering.
5. **[ ] Animated Sticker Creator:** NDK `libwebp`, GIF/video → animated WebP, size fitting, animated preview.
6. **[ ] Extras:** ML Kit Subject Segmentation (bg removal), `.wastickers` import, 5-sticker preview strip on main screen, UI polish.

## Toolchain

- Windows project. Use the wrapper: `.\gradlew.bat ...` (Gradle 9.6.0). JDK 21 is installed; Java source/target is 11.
- AGP 9.4.1 provides Kotlin 2.2.10 (built-in Kotlin). Do not add the old `org.jetbrains.kotlin.android` plugin; `kotlin-compose` is the only Kotlin plugin applied.
- compileSdk/targetSdk 37, minSdk 30, Compose BOM 2026.02.01.
- Declare all dependencies/plugins in `gradle/libs.versions.toml` and reference them via `alias(...)`/`libs.*`. Repository declarations are locked in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); do not add `repositories {}` to module build files.
- Currently added in `libs.versions.toml`: `androidx-lifecycle-viewmodel-compose`, `androidx-lifecycle-runtime-compose`, `androidx-compose-material-icons-extended`, and `org-json` (`testImplementation` only for JVM tests).
- Animated WebP infrastructure does **not** exist yet: no NDK/CMake, libwebp, Coil, or ML Kit dependencies. Adding them is new work in Phases 5–6.

## Commands

- Build debug APK: `.\gradlew.bat assembleDebug`
- All unit tests: `.\gradlew.bat test`
- Single unit test: `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.danivi.yanistickers.validation.PackValidatorTest"`
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

- App code: `app/src/main/java/io/github/danivi/yanistickers/` (`data/`, `validation/`, `whatsapp/`, `ui/`)
- Unit tests: `app/src/test/java/...`; instrumented tests: `app/src/androidTest/java/...`
- The spec explicitly requires unit tests for pack validation and ZIP import/export.
