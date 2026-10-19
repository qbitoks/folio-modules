# Folio Документы

PDF and DjVu reader module for [Folio](https://github.com/qbitoks/folio): MJ PDF's engine under a Compose UI in Folio's
design, DjVuLibre for DjVu, plus k2pdfopt's reflow for «Скан как книга» (PDF and DjVu page images). The app as a whole is under the GNU AGPL v3 (`reflow/LICENSE`): it links
AGPL k2pdfopt; MJ PDF's parts stay GPL v3 (`LICENSE`), which allows that combination (GPL v3 §13).

## Origin

- `pdfium/` — PdfiumAndroid from [MJ PDF](https://gitlab.com/mudlej_android/mj_pdf_reader) by Mudlej
  (GPL-3.0; originally Apache-2.0, `pdfium/LICENSE`), Java and JNI sources unchanged.
- `viewer/` — AndroidPdfViewer from MJ PDF (GPL-3.0; originally Apache-2.0 by Bartosz Schiller, `viewer/LICENSE`).
  Changes: `PDFView.setPageColorFilter` (page colours of reading themes); material `R` import in `DefaultScrollHandle`.
- `app/.../engine/` — MJ PDF's text mode (`StructuredTextFormatter`, `TextModeTextFormatter`, `PageCharMetrics`) unchanged
  apart from the package; `PdfText` is based on its `PdfExtractor`.
- `app/.../ui/` — new UI. `ui/folio/` is a copy of Folio's design system (same author), so the module looks like Folio.
- `reflow/` — k2pdfopt's page reflow (willus.com) through KOReader's
  [libk2pdfopt](https://github.com/koreader/libk2pdfopt) API, with [Leptonica](http://www.leptonica.org) (BSD-2);
  `reflow/NOTICE`. Only `native/folio_reflow.c` (JNI) and `Reflow.kt` are new. No OCR, no image codecs.
- `djvu/` — [DjVuLibre](https://djvu.sourceforge.net) 3.5.28 (libdjvu, GPL-2.0-or-later, `djvu/LICENSE`, `djvu/NOTICE`)
  through `native/folio_djvu.cpp` (JNI) and `DjvuDocument.kt`, both new. DjVu has no «Как книга» or search (no text
  layer is read); «Оригинал» is `ui/DjvuPagesView.kt`, «Скан как книга» shares `ScanPages`.
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

`reflow/src/main/jniLibs/<abi>/libfolioreflow.so` (k2pdfopt + Leptonica 1.85.0, pinned in `reflow/native/build.sh`)
is built the same way, in Docker on Windows (the script fetches its sources into `reflow/native/.work/`):

```bash
docker run --rm -v "$PWD/reflow:/reflow" -v /path/to/ndk-r28c-linux:/ndk ubuntu:24.04 bash -c   'apt-get update && apt-get install -y cmake ninja-build git curl ca-certificates && ANDROID_NDK=/ndk /reflow/native/build.sh'
```

`djvu/src/main/jniLibs/<abi>/libfoliodjvu.so` comes from `djvu/native/build.sh` the same way (Linux NDK r28c, `make`):

```bash
docker run --rm -v "$PWD/djvu:/djvu" -v /path/to/ndk-r28c-linux:/ndk ubuntu:24.04 bash -c   'apt-get update && apt-get install -y make curl ca-certificates && ANDROID_NDK=/ndk /djvu/native/build.sh'
```

## Protocol with Folio

Folio starts `PdfActivity` with action `app.folio.action.OPEN_PDF`, a read-only `content://` URI typed `application/pdf` or `image/vnd.djvu` (`image/x-djvu` accepted) and extras
`app.folio.extra.BOOK_ID`, `PAGE` (zero-based), `TITLE`, `APPEARANCE`. The module reports the page with
`ContentResolver.call("content://app.folio.reading", "position", bookId, {page, pages})`; Folio accepts it only from
apps signed with its key (`app.folio.permission.READING`). Code: `app/.../FolioLink.kt`.

## Build

```bash
./gradlew :documents:app:assembleRelease
```

Release builds are signed with Folio's key (`ANDROID_DEBUG_KEYSTORE_PATH`, or this machine's debug key).
