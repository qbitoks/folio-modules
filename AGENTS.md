# AGENTS.md

Engine plugins and shared manifests for Folio (`qbitoks/folio`). Product overview: `README.md`.

## Layout
- `tts-common/` — `FolioTtsService` (Android `TextToSpeechService` over a `Synthesizer`), `VoiceStore`
  (pack downloads in `files/voices/<id>/`, HTTPS only, optional SHA-256), `VoicesActivity` (the app's one screen).
- `tts-piper/` — sherpa-onnx Piper voices; `tts-silero/` — Silero via onnxruntime-android (Russian only).
- `modules.json` — read by Folio (`core/data/.../modules/ModuleIndex.kt`); CI rewrites an entry on release.
- `catalogs.json` — Folio's preset catalogs (`CatalogPresets`); Folio keeps a bundled copy as fallback.

## Rules
- Engines must be signed with Folio's certificate (SHA-256 `b3e3703d…358f`); Folio refuses other APKs.
- Keep `modules.json` and `catalogs.json` backward compatible: add fields, never rename; bump `version` on a breaking change.
- Voice names are stable API (`piper-<id>`, `silero-ru_<name>`): Folio saves them in its prefs.
- sherpa-onnx `OfflineTts` and ORT sessions are used only from the service's synthesis thread.
- Release: bump version, tag `<engine>-v<versionName>`; see `README.md`.

## Check
`./gradlew :tts-common:testDebugUnitTest :tts-silero:testDebugUnitTest :tts-piper:assembleRelease :tts-silero:assembleRelease`
