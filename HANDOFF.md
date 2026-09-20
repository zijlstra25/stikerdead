# StikerDead — Estado del proyecto

## Objetivo

StikerDead es un teclado Android centrado en stickers. La idea es que el usuario pueda tener una biblioteca propia de stickers dentro del teclado, similar a la sección de emojis, y enviarlos rápidamente a aplicaciones compatibles.

## Estado actual confirmado en un teléfono real

Dispositivo de prueba:
- Samsung SM-A042M
- ADB funcionando por USB

La APK de debug se compiló correctamente y se instaló con éxito.

Paquete Android:
- `com.stikerdead.keyboard`

Servicio IME:
- `com.stikerdead.keyboard/.StickerKeyboardService`

Comprobación de instalación:
- `adb install -r app-debug.apk` terminó con `Success`
- `adb shell ime list -a` muestra StikerDead como teclado disponible

## Funcionalidad que YA funciona

### Teclado
El teclado StikerDead aparece correctamente como teclado del sistema.

### Stickers de prueba
Actualmente hay dos stickers de prueba:
- ❤️ heart
- 😄 smile

### Toque corto
Un toque de menos de aproximadamente 0,9 segundos intenta enviarlo directamente como sticker usando:
- `InputConnectionCompat.commitContent()`
- MIME `image/webp.wasticker`

En la prueba real con WhatsApp:
- el sticker se envía correctamente
- WhatsApp lo reconoce como sticker
- aparece en la biblioteca de "Usados recientemente"

### Mantener presionado
Mantener presionado aproximadamente 0,9 segundos o más prepara una imagen PNG normal.

En WhatsApp esto abre el menú de contenido que permite acciones como:
- Enviar como sticker
- Editar foto
- Añadir al paquete de stickers
- Editar

Esto cumple el comportamiento buscado:
- toque corto = envío rápido
- pulsación larga = menú de WhatsApp

## Archivos principales

`app/src/main/java/com/stikerdead/keyboard/StickerKeyboardService.java`
- Implementa el IME
- Renderiza la cuadrícula de stickers
- Maneja toque corto / pulsación larga
- Genera PNG o WebP de prueba
- Envía contenido mediante InputConnectionCompat

`app/src/main/java/com/stikerdead/keyboard/MainActivity.java`
- Pantalla inicial de StikerDead
- Botón para abrir los ajustes de teclados Android

`app/src/main/java/com/stikerdead/keyboard/StickerItem.java`
- Modelo básico de sticker

`app/src/main/AndroidManifest.xml`
- Registra la Activity, el IME y FileProvider

`app/src/main/res/xml/method.xml`
- Configuración del teclado IME

`app/src/main/res/xml/file_paths.xml`
- Rutas del FileProvider

## Build actual

Proyecto:
- Android Gradle Plugin 9.4.0
- Gradle Wrapper 9.6.1
- compileSdk 36
- targetSdk 36
- minSdk 25
- Java de Android Studio JBR 25.0.3
- androidx.core 1.17.0

En Windows se está trabajando desde:
`C:\Users\zijlstra25\Desktop\stikerdead`

Variables usadas:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"
```

Compilar:
```powershell
cd C:\Users\zijlstra25\Desktop\stikerdead
git pull
.\gradlew.bat assembleDebug
```

APK:
```
app\build\outputs\apk\debug\app-debug.apk
```

Instalar:
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = ".\app\build\outputs\apk\debug\app-debug.apk"
& $adb devices -l
& $adb install -r $apk
```

## Importante para el próximo chat

NO empezar de cero.

El repositorio GitHub actual es la fuente de verdad:
`https://github.com/zijlstra25/stikerdead`

Antes de modificar código:
1. Leer este archivo.
2. Revisar `README.md`.
3. Revisar `StickerKeyboardService.java`.
4. Mantener el comportamiento que ya está probado:
   - toque corto -> sticker directo
   - pulsación larga -> menú/imagen de WhatsApp

## Lo que todavía NO existe

El botón `+` todavía NO está implementado.

La interfaz actual contiene básicamente:
```
EMOJIS   STICKERS

❤️   😄
```

## Próximo objetivo de desarrollo

Implementar una biblioteca real de stickers dentro del teclado.

Orden sugerido:
1. Agregar botón `+`
2. Pantalla para agregar/importar stickers
3. Guardar stickers localmente
4. Mostrar miniaturas reales
5. Categorías
6. Favoritos
7. Usados recientemente propios
8. Soporte para paquetes
9. Soporte para stickers animados
10. Mejorar compatibilidad por aplicación receptora

## Nota sobre WhatsApp

El envío directo funciona en la prueba real de WhatsApp con el enfoque actual.

No asumir que `image/webp.wasticker` es una API pública estable de WhatsApp. Mantener la implementación encapsulada para poder cambiar el mecanismo si una futura versión de WhatsApp cambia su comportamiento.

## Historial de problemas ya resueltos

1. Java 8 estaba en PATH.
   - Se decidió usar el JBR de Android Studio.

2. Android SDK no estaba configurado.
   - Se instaló/configuró en `%LOCALAPPDATA%\Android\Sdk`.

3. ADB no estaba en PATH.
   - Se verificó ejecutando directamente `platform-tools\adb.exe`.

4. Git no estaba instalado.
   - Git quedó instalado y funciona desde PowerShell.

5. El primer código usaba un import incorrecto para InputMethodService.
   - Corregido a `android.inputmethodservice.InputMethodService`.

6. androidx.core 1.19.0 exigía compileSdk 37.
   - Cambiado a androidx.core 1.17.0 para mantener compileSdk 36.

7. El primer envío con SVG no funcionaba como se esperaba.
   - Se cambió a imágenes reales generadas como PNG/WebP.

8. El teléfono inicialmente no aparecía en ADB.
   - Se habilitó correctamente la depuración USB y luego apareció como `device`.

9. La instalación final de la APK funcionó con `Success`.

## Último estado funcional conocido

La versión instalada y probada funciona con este flujo:

```
StikerDead Keyboard
       |
       +-- toque corto (< 0,9 s) --> WhatsApp --> sticker directo
       |
       +-- pulsación larga (>= 0,9 s) --> WhatsApp --> menú de acciones
```

WhatsApp además incorpora los stickers enviados a "Usados recientemente".

## Regla de desarrollo

Cada cambio importante debe:
- hacerse en el repositorio
- compilarse
- probarse en el Samsung cuando sea relevante
- mantener actualizado este archivo si cambia el comportamiento del MVP
