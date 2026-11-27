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

- **Implemented:** Phases 1 and 2 (`assembleDebug`, `test`, and `lint` pass; Phase 1 verified on real WhatsApp device).
  - `data/`: `PackModels`, `PackJsonParser` (parse/serialize JSON, safe IDs/filenames, grapheme emoji splitting), `LocalPackStore` (atomic `pack.json` CRUD, `imageDataVersion` bump on every edit, orphan sticker cleanup, canonical path checks), `SeedPackInstaller` (pre-built static + animated test packs in `assets/seed_packs/`, installed once via `.seeds_initialized`), `DefaultTrayGenerator` (`96×96` default tray icon).
  - `whatsapp/`: `WhatsAppContract`, `StickerContentProvider` (serves `metadata`, `metadata/*`, `stickers/*`, `stickers_asset/*/*` from `filesDir/packs/`), `WhatsAppBridge` (`ENABLE_STICKER_PACK` + `WhitelistCheck` for `com.whatsapp` and `com.whatsapp.w4b`).
  - `validation/`: `ImageContainerInspector` (pure-Kotlin RIFF/WebP + PNG header parser) and `PackValidator` / `ValidationModels` (enforces verified WhatsApp limits).
  - `ui/`: `PackListScreen` / `PackListViewModel`, `PackEditorScreen` / `PackEditorViewModel`, `WhatsAppActionHelper`, `AppScreen`.
  - **Tests:** `WhatsAppContractTest`, `ImageContainerInspectorTest`, `PackValidatorTest`, `LocalPackStoreTest`.
- **UI/UX conventions settled in Phase 2:**
  - Validation must remain **transparent** to the user: no "Válido" badges, no dedicated Validation screen, and no technical rule/ID/version clutter in the UI.
  - Only when a pack is invalid (e.g. `< 3` stickers), show a concise inline warning with the concrete error(s) in both the pack card and editor, and keep the full-width **"Añadir a WhatsApp"** button disabled.
  - Pack card actions (Editar, Duplicar, Borrar) live inside a 3-dot `DropdownMenu`.
  - "Añadir sticker" button in `PackEditorScreen` currently shows an upcoming-phases notice (to be wired in Phases 4–5).
- **Next up (remaining work):**
  - **Phase 3:** `.zip` backup/restore via SAF (`manifest.json` + pack folders, zip-slip protection, `identifier` conflict resolution, immediate "Add to WhatsApp", unit tests for ZIP import/export).
  - **Phase 4:** Static sticker creator (Photo Picker, `512×512` crop/fit with transparency, WebP encoding `≤ 100 KB`, custom tray icon support, emojis).
  - **Phase 5:** Animated sticker creator (NDK/CMake `libwebp` `WebPAnimEncoder` JNI wrapper, GIF/MP4/`WebP animado` import & size-fitting `≤ 500 KB`, animated preview with Coil).
  - **Phase 6:** Extras (ML Kit Subject Segmentation background removal, `.wastickers` import, UI polish).

## Architecture & Data Model

- **Storage layout** under `filesDir/packs/<packId>/`:
  - `pack.json`: `{ schemaVersion: 1, identifier, name, publisher, animated, imageDataVersion, trayImageFile, stickers: [{ file, emojis, accessibilityText? }], createdAt, updatedAt, avoidCache? }`
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
- **Screens:** Pack List, Pack Editor, Sticker Creator (Phases 4–5), Backup/Restore (Phase 3). (No standalone Validation screen.)

## Phases

1. **[x] Skeleton + Provider:** Project setup, `ContentProvider` reading from `filesDir`, pre-built test packs (1 static + 1 animated). *Acceptance passed on device.*
2. **[x] Storage & Pack List:** Data model, CRUD (`LocalPackStore`), pure-Kotlin WebP/PNG validator, `PackList` & `PackEditor` screens + ViewModels, unit tests.
3. **[ ] Backup/Restore:** `.zip` export/import with conflict handling + ZIP unit tests. *Acceptance: uninstall, reinstall, import, and re-add to WhatsApp.*
4. **[ ] Static Sticker Creator:** Picker, `512×512` crop, WebP encoding, emojis.
5. **[ ] Animated Sticker Creator:** NDK `libwebp`, GIF/video → animated WebP, size fitting, animated preview.
6. **[ ] Extras:** ML Kit Subject Segmentation (bg removal), `.wastickers` import, UI polish.

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
