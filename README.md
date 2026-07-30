# Lector de Texto (EPUB / PDF)

App Android que abre archivos **EPUB** y **PDF** guardados en el celular y muestra
el texto de cada capítulo **corrido, sin cortes de página**, con formato tipo
Markdown (subtítulos, negrita, itálica) y alineación de texto ajustable, con un
botón para copiar el capítulo completo al portapapeles de una sola vez.

## Funciones

- **Markdown**: los subtítulos dentro de un capítulo se distinguen del resto
  del texto, y los párrafos quedan bien separados (como en el libro original).
  - En **EPUB** se usan las etiquetas HTML reales (`<h1>`-`<h6>`, `<strong>`,
    `<em>`, listas, citas).
  - En **PDF** no existen subtítulos "de verdad", así que se detecta el
    tamaño de letra de cada bloque de texto: si es notablemente más grande
    que el cuerpo del documento, se trata como subtítulo.
- **Detección de PDF escaneados**: si un PDF no tiene texto real (por ejemplo,
  es una imagen escaneada), la app lo detecta y avisa que necesitaría OCR, en
  vez de mostrar una lista de capítulos vacía o texto vacío.
- **Alineación de texto**: 4 modos elegibles en la pantalla de lectura -
  Izquierda, Centro, Derecha y Justificado (el de libros/periódicos, con
  espacios estirados para que ambos bordes queden rectos). La elección se
  recuerda para la próxima vez.
- **Copiar capítulo completo**: copia el texto ya limpio (sin símbolos de
  Markdown) al portapapeles de un solo toque.

## Cómo funciona la extracción

- **EPUB**: es en realidad un `.zip`. La app lee el índice del libro (`toc.ncx`
  o `nav.xhtml`) para armar la lista de capítulos con sus títulos reales.
- **PDF**: los PDF no tienen "capítulos" reales, así que si el documento es
  corto se trata como un único texto; si es largo, se divide en bloques de 25
  páginas (el texto dentro de cada bloque sale corrido, sin las interrupciones
  de página que tienen los lectores comunes).

## Estructura del proyecto

```
app/src/main/java/com/textreader/app/
├── MainActivity.kt            -> Elegir archivo (EPUB o PDF)
├── ChapterListActivity.kt      -> Lista de capítulos del libro (o aviso si no hay texto)
├── ReaderActivity.kt           -> Texto Markdown + alineación + botón "Copiar"
├── ChapterAdapter.kt
├── epub/EpubParser.kt          -> Lectura y extracción de EPUB a Markdown
└── pdf/PdfParser.kt            -> Lectura, extracción a Markdown y detección de PDF sin texto
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

- minSdk 26 (Android 8.0+) — necesario tanto para el ícono adaptativo como
  para el modo de texto justificado nativo de Android.
