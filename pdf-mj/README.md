# Folio MJ PDF

PDF reader module for [Folio](https://github.com/qbitoks/folio): MJ PDF's engine under a Compose UI in Folio's design.
Licensed under the GNU GPL v3 (`LICENSE`).

## Origin

- `pdfium/` — PdfiumAndroid from [MJ PDF](https://gitlab.com/mudlej_android/mj_pdf_reader) by Mudlej
  (GPL-3.0; originally Apache-2.0, `pdfium/LICENSE`), Java and JNI sources unchanged.
- `viewer/` — AndroidPdfViewer from MJ PDF (GPL-3.0; originally Apache-2.0 by Bartosz Schiller, `viewer/LICENSE`).
  Changes: `PDFView.setPageColorFilter` (page colours of reading themes); material `R` import in `DefaultScrollHandle`.
- `app/.../engine/` — MJ PDF's text mode (`StructuredTextFormatter`, `TextModeTextFormatter`, `PageCharMetrics`) unchanged
  apart from the package; `PdfText` is based on its `PdfExtractor`.
- `app/.../ui/` — new UI. `ui/folio/` is a copy of Folio's design system (same author), so the module looks like Folio.
- Fonts: Inter, Literata, PT Serif, Source Serif (SIL OFL 1.1, `app/src/main/assets/licenses/`).
- Upstream commit used: `30cca2d` (2026-08-24).

## Native libraries

`pdfium/src/main/jniLibs/<abi>/` holds prebuilt `.so` files (arm64-v8a, x86_64): Pdfium from
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) (chromium/7920), libpng, FreeType and
MJ PDF's JNI bridge (`pdfium/src/main/jni/`). Rebuild them on Linux with NDK r28c from MJ PDF's checkout layout:

```bash
ANDROID_NDK=/path/to/android-ndk-r28c python3 build_dependencies.py --pdfium prebuilt --abi arm64-v8a
```

The script is in `native/`; it expects MJ PDF's tree (`PdfiumAndroid/src/main/jni`). Run it on a case-sensitive
file system: Pdfium's `VERSION` file shadows the C++ `<version>` header on Windows mounts.

## Protocol with Folio

Folio starts `PdfActivity` with action `app.folio.action.OPEN_PDF`, a read-only `content://` URI and extras
`app.folio.extra.BOOK_ID`, `PAGE` (zero-based), `TITLE`, `APPEARANCE`. The module reports the page with
`ContentResolver.call("content://app.folio.reading", "position", bookId, {page, pages})`; Folio accepts it only from
apps signed with its key (`app.folio.permission.READING`). Code: `app/.../FolioLink.kt`.

## Build

```bash
./gradlew :pdf-mj:app:assembleRelease
```

Release builds are signed with Folio's key (`ANDROID_DEBUG_KEYSTORE_PATH`, or this machine's debug key).
