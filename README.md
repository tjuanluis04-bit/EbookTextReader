# Lector de Texto (EPUB / PDF)

App Android que abre archivos **EPUB** y **PDF** guardados en el celular y muestra
el texto de cada capítulo **corrido, sin cortes de página**, con formato tipo
Markdown (subtítulos, negrita, itálica) y alineación de texto ajustable, con un
botón para copiar el capítulo completo al portapapeles de una sola vez.

## Funciones

- **Detección exacta de capítulos**:
  - **EPUB**: los capítulos salen del índice real del libro (`toc.ncx` /
    `nav.xhtml`), no de la lista cruda de archivos. Esto evita que la tapa,
    la portadilla o la página de copyright aparezcan como "capítulos", y
    corta bien incluso cuando un capítulo abarca varios archivos o cuando
    varios capítulos comparten un mismo archivo (separados por anclas `#id`).
  - **PDF**: si el archivo tiene marcadores/outline (bookmarks) reales, se
    usan esos como capítulos exactos. Si el PDF no los tiene, se avisa y se
    divide en bloques de 25 páginas como respaldo.
- **Navegación entre capítulos sin salir de la pantalla**: en la pantalla de
  lectura hay botones "‹ Anterior" y "Siguiente ›" que cargan el capítulo
  correspondiente en el mismo lugar (sin volver a la lista).
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

## Cómo se arman los capítulos

- **EPUB**: se lee el índice (`toc.ncx` o `nav.xhtml`), pero solo su nivel
  superior (para no confundir subtítulos con capítulos). Cada entrada se
  ubica exactamente en el archivo/ancla donde empieza, y el capítulo termina
  justo donde arranca el siguiente. Si el EPUB no trae índice utilizable, se
  usa como respaldo un capítulo por archivo del libro.
- **PDF**: se intenta usar los marcadores reales del PDF (si los trae). Si no
  los trae, se divide en bloques de 25 páginas (el texto dentro de cada
  bloque sale corrido, sin las interrupciones de página que tienen los
  lectores comunes), y la app te avisa que la división no es exacta.

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
