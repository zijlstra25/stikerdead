# StikerDead

Teclado Android enfocado en stickers.

## Estado actual

MVP inicial con:

- IME (Input Method Editor) para Android.
- Pantalla de configuración para activar el teclado.
- Sección visual de stickers.
- Dos stickers de prueba en SVG.
- Envío mediante `InputConnection.commitContent()` cuando la aplicación receptora acepta el MIME enviado.
- `FileProvider` para compartir el contenido desde el almacenamiento de caché.
- Base preparada para categorías, favoritos, búsqueda y paquetes de stickers.

## Requisitos

- Android Studio Quail 4 / 2026.1.4.
- JDK 17 o superior para el build.
- Android SDK Platform 36.
- Gradle 9.6.1 mediante Gradle Wrapper.

## Compilar en Windows

Desde PowerShell, con el repositorio clonado:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat assembleDebug
```

El APK de debug quedará en:

```
app\build\outputs\apk\debug\app-debug.apk
```

## Próximos pasos

1. Reemplazar los stickers de prueba por PNG/WebP/GIF reales.
2. Detectar los MIME types que acepta cada aplicación receptora.
3. Agregar paquetes de stickers.
4. Agregar favoritos y categorías.
5. Mejorar la interfaz del teclado.
6. Probar compatibilidad específicamente con Instagram y otras apps.

Repositorio: https://github.com/zijlstra25/stikerdead
