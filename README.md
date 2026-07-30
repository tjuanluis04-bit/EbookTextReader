# Lector de Texto (EPUB / PDF)

App Android que abre archivos **EPUB** y **PDF** guardados en el celular y muestra
el texto de cada capítulo **corrido, sin cortes de página**, con un botón para
copiar el capítulo completo al portapapeles de una sola vez.

## Cómo funciona

- **EPUB**: es en realidad un `.zip`. La app lee el índice del libro (`toc.ncx`
  o `nav.xhtml`) para armar la lista de capítulos con sus títulos reales, y
  extrae el texto de cada capítulo uniendo todos los párrafos en un solo bloque.
- **PDF**: los PDF no tienen "capítulos" reales, así que si el documento es
  corto se trata como un único texto; si es largo, se divide en bloques de 25
  páginas (el texto dentro de cada bloque sale corrido, sin las interrupciones
  de página que tienen los lectores comunes).

## Estructura del proyecto

```
app/src/main/java/com/textreader/app/
├── MainActivity.kt            -> Elegir archivo (EPUB o PDF)
├── ChapterListActivity.kt      -> Lista de capítulos del libro
├── ReaderActivity.kt           -> Texto del capítulo + botón "Copiar"
├── ChapterAdapter.kt
├── epub/EpubParser.kt          -> Lectura y extracción de EPUB
└── pdf/PdfParser.kt            -> Lectura y extracción de PDF
```

## Compilar con GitHub Actions

El workflow `.github/workflows/build.yml` compila automáticamente un APK de
depuración en cada push a `main` (o manualmente desde la pestaña *Actions* con
"Run workflow"). El APK queda disponible como artefacto descargable en el
resumen de la ejecución (`app-debug-apk`).

No hace falta subir el Gradle Wrapper: el workflow instala Gradle 8.7
directamente en el runner.

## Compilar localmente (opcional)

Si querés abrirlo en Android Studio, abrí la carpeta del proyecto y dejá que
Android Studio genere el Gradle Wrapper automáticamente, o corré:

```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

## Requisitos

- minSdk 26 (Android 8.0+)
- El ícono es un **ícono adaptativo** (`mipmap-anydpi-v26`), por eso el
  requisito de Android 8.0.
