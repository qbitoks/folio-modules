# Folio modules

Хранилище отдельно устанавливаемых модулей для читалки [Folio](https://github.com/qbitoks/folio):

- **Folio Piper** (`tts-piper`) — нейросетевые голоса Piper (sherpa-onnx), несколько языков.
- **Folio Silero** (`tts-silero`) — 29 русских голосов Silero v5 (onnxruntime).
- **Folio Документы** (`documents`) — читалка PDF в оформлении Folio на движке [MJ PDF](https://gitlab.com/mudlej_android/mj_pdf_reader):
  режимы «Как книга» и «Скан как книга» (перекомпоновка сканов и колонок на k2pdfopt), оглавление, поиск, темы,
  прокрутка или листание, обрезка полей. Лицензия AGPL-3.0 (`documents/reflow/LICENSE`).
- **`catalogs.json`** — список OPDS-каталогов, который Folio показывает в «Каталогах».
- **`modules.json`** — индекс модулей: версия, ссылка на APK, SHA-256, размер. Его читает Folio.

Каждый движок — обычный системный движок синтеза речи Android (`TextToSpeechService`): им может читать
Folio, «Настройки → Синтез речи» и любое другое приложение. Голоса скачиваются внутри движка.

Folio скачивает APK по `modules.json`, сверяет SHA-256 и сертификат подписи со своим и устанавливает
через системный установщик. Поэтому модули подписываются тем же ключом, что и Folio.

## Сборка

JDK 17, Android SDK platform 37.

```bash
./gradlew :tts-common:testDebugUnitTest :tts-silero:testDebugUnitTest :tts-piper:assembleRelease :tts-silero:assembleRelease
```

Folio Документы — отдельное приложение без значка: его открывает Folio, когда пользователь открывает PDF,
и получает обратно страницу, на которой остановилось чтение.

## Релиз движка

1. Поднять `versionCode` и `versionName` в `tts-<engine>/build.gradle.kts`, закоммитить в `main`.
2. `git tag <engine>-v<versionName>` и `git push origin <engine>-v<versionName>`.
3. CI соберёт APK с ключом Folio (секрет `ANDROID_DEBUG_KEYSTORE_BASE64`), проверит сертификат,
   опубликует релиз и обновит `modules.json` в `main`.

Модель Silero собирается скриптом `silero/export_onnx.py` и лежит в релизе `silero-model-v5`.
