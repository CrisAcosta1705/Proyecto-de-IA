# Asistencia estudiantil con IA

Prototipo academico para contar personas en un aula y registrar asistencia de
estudiantes autorizados mediante reconocimiento facial.

## Arquitectura hibrida

```text
Aplicacion Android
    |-- Modo telefono: ML Kit + MobileFaceNet ONNX + SQLite local
    |-- Modo servidor: fotografia JPEG 1080p hacia FastAPI
    `-- Modo automatico: telefono primero y servidor como respaldo

API FastAPI opcional
    |-- InsightFace/ArcFace de mayor capacidad
    `-- SQLite independiente para estudiantes y asistencia
```

El conteo general de personas se mantiene como un componente independiente con
YOLO en `people_counter.py`. Android cuenta rostros con ML Kit y puede registrar
identidades y asistencia sin computadora ni conexion a Internet.

## Funciones implementadas

- Registro de hasta cinco embeddings por estudiante en el telefono y hasta
  veinte en el servidor.
- La fotografia original, el nombre, el carnet y los embeddings se guardan en
  SQLite.
- Deteccion y alineacion facial con InsightFace.
- Embeddings ArcFace y comparacion con umbral configurable.
- Resultado `no identificado` cuando no se supera el umbral.
- Sesiones de asistencia por clase.
- Listas de asistencia identificadas por fecha y materia.
- Confirmacion antes de reemplazar una lista existente para la misma fecha y materia.
- Sexo registrado en el perfil del estudiante y estadisticas de asistencia masculina y femenina.
- Directorio de todos los estudiantes registrados, con edicion de nombre y sexo
  sin modificar sus embeddings ni el historial de asistencia.
- Interfaz separada en configuracion, asistencia, historial, estudiantes y
  registro facial, con navegacion inferior disponible en vertical y horizontal.
- Historial general de listas con total de asistencias y distribucion masculina
  y femenina; cada lista permite abrir su detalle por fecha y materia.
- Registro facial guiado por video: la app valida estabilidad y captura
  automaticamente referencias frontal, de perfil, hacia arriba y hacia abajo.
- Restriccion unica para no registrar dos veces al mismo estudiante por sesion.
- API local para Android y documentacion interactiva en `/docs`.
- Vista Android horizontal para registrar, reconocer y cerrar una sesion.
- Captura CameraX de alta calidad e indicador visible durante el analisis.
- Fotografia analizada con cajas verdes para estudiantes reconocidos, cajas
  rojas para rostros desconocidos y etiqueta de nombre y carnet.
- Cambio entre camara trasera y frontal para registrar selfies.
- Eliminacion de fotografia y embeddings por carnet, conservando el historial
  de asistencia.
- Reconocimiento completamente local con `w600k_mbf.onnx` y ONNX Runtime.
- Base SQLite privada dentro del telefono con un maximo de cinco capturas por
  estudiante.
- Modos `Automatico`, `Solo telefono` y `Servidor`.
- Descubrimiento automatico de la API cuando ambos dispositivos comparten red.
- Recuperacion de registros antiguos del servidor: la app descarga la foto,
  genera un embedding compatible con MobileFaceNet y lo conserva en el telefono.

## Preparar Python

Desde PowerShell, abre el repositorio y ejecuta:

```powershell
cd "C:\Users\nnnnn\OneDrive\Documents\GitHub\Proyecto-de-IA"
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Comprueba la instalacion del modelo facial:

```powershell
python scripts\check_face_model.py
```

La primera ejecucion descarga aproximadamente 275 MB en
`models/insightface/`. Los pesos no se incluyen en Git. El modelo preentrenado
de InsightFace debe utilizarse respetando su licencia para investigacion no
comercial.

Para registrar una credencial o fotografia existente desde la computadora:

```powershell
python scripts\register_student.py `
  --student-id "2023-0000U" `
  --name "Nombre completo" `
  --image "C:\ruta\credencial.jpeg"
```

La imagen debe contener exactamente un rostro detectable. Para mejorar el
reconocimiento con cambios de angulo e iluminacion se pueden agregar capturas
adicionales con `--append`.

## Iniciar la API

```powershell
.\scripts\start_api.ps1
```

La ventana debe permanecer abierta solamente cuando se utiliza el modo
`Servidor` o se desea el respaldo de red del modo `Automatico`.
El script localiza el entorno virtual e inicia la API en todas las interfaces de
red mediante el puerto `8000`.

En la computadora se puede comprobar con:

```text
http://127.0.0.1:8000/health
http://127.0.0.1:8000/docs
```

Para conectar un telefono fisico, ambos equipos deben estar en la misma red.
Ejecuta `ipconfig`, localiza la direccion IPv4 de la computadora y escribela en
la app, por ejemplo:

```text
http://192.168.1.25:8000
```

Windows puede solicitar permiso para que Python acepte conexiones en redes
privadas. El valor `10.0.2.2` se utiliza solamente desde el emulador Android.

## Flujo desde Android

1. Selecciona `Solo telefono` para trabajar sin computadora, `Servidor` para
   usar exclusivamente FastAPI o `Automatico` para utilizar ambos.
2. Si ya existian estudiantes en la computadora, inicia la API una vez y pulsa
   `Recuperar registros del servidor`. No se copian embeddings incompatibles:
   la aplicacion vuelve a procesar cada fotografia con el modelo del telefono.
3. Abre `Registro`, ingresa nombre, carnet y sexo, y pulsa `Iniciar recorrido facial`.
4. Mueve el rostro siguiendo las indicaciones: frontal, perfil, arriba y abajo.
   La app analiza el video, exige varios frames estables y guarda automaticamente
   una referencia clara por posicion.
5. Abre `Asistencia`, selecciona materia y fecha, y pulsa `Reconocer asistencia`.
6. Si ya existe una lista para esa fecha y materia, confirma si deseas reemplazarla.
7. Abre `Historial` para consultar todas las listas y sus estadisticas por sexo.
8. Abre `Estudiantes` para ver la tabla de rostros, editar perfiles o eliminar
   datos biometricos sin borrar asistencias historicas.
9. Pulsa `Cerrar sesion` al terminar una toma de asistencia.

Para borrar un registro facial, escribe el carnet, pulsa
`Eliminar reconocimiento` y confirma el dialogo. La fotografia y los embeddings
se eliminan; el nombre, carnet y las asistencias historicas se conservan.

El identificador admite letras, numeros, guion y guion bajo, por ejemplo
`estudiante_001`.

## Ejecutar Android

Abre `android-app` en Android Studio, conecta el telefono y ejecuta el modulo
`app`. El APK de depuracion se genera en:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

## Generar y publicar el APK desde GitHub

El workflow `.github/workflows/android-apk.yml` compila la aplicacion sin
necesidad de Android Studio:

- Cada cambio de Android enviado a `main` genera un APK descargable durante 30
  dias desde `Actions > Generar APK Android > Artifacts`.
- Una etiqueta cuyo nombre comience con `v`, por ejemplo `v0.1.0`, crea una
  publicacion permanente en la seccion `Releases` y adjunta el APK junto con su
  suma SHA-256.
- El nombre de la etiqueta se usa como `versionName` y el numero de ejecucion
  de GitHub aumenta automaticamente el `versionCode` para permitir futuras
  actualizaciones.
- Si no hay secretos de firma, GitHub genera un APK `debug` instalable para
  pruebas. Si los cuatro secretos estan configurados, genera un APK `release`
  firmado de forma estable.

Para publicar la version actual:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Para una firma estable, genera una clave una sola vez y mantenla fuera del
repositorio:

```powershell
keytool -genkeypair -v `
  -keystore "$HOME\.android\asistencia-ia-release.jks" `
  -alias asistencia-ia `
  -keyalg RSA -keysize 2048 -validity 10000

[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("$HOME\.android\asistencia-ia-release.jks")
) | Set-Clipboard
```

En `Settings > Secrets and variables > Actions` del repositorio se deben crear:

| Secreto | Contenido |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Texto Base64 copiado por PowerShell |
| `ANDROID_KEYSTORE_PASSWORD` | Contrasena del almacen de claves |
| `ANDROID_KEY_ALIAS` | Alias de la clave, por ejemplo `asistencia-ia` |
| `ANDROID_KEY_PASSWORD` | Contrasena de la clave |

La clave y sus contrasenas deben conservarse para poder instalar futuras
actualizaciones sobre una version ya distribuida. Los archivos APK, AAB, JKS y
keystore permanecen excluidos de Git.

## Conteo con YOLO

```powershell
python people_counter.py
python people_counter.py --source video --video-path .\videos\prueba.mp4
python people_counter.py --csv .\data\conteos.csv
```

Presiona `q` para cerrar la ventana del contador.

## Pruebas

```powershell
python -m unittest discover -s tests -v
cd android-app
.\gradlew.bat assembleDebug
```

## Datos y privacidad

FastAPI crea `data/processed/asistencia.db`. Android crea
`asistencia_local.db` en el almacenamiento privado de la aplicacion. Ambas bases
guardan fotografias, embeddings, identificadores, nombres y asistencias. Las
fotografias de registro se pueden recuperar desde el servidor para regenerar
embeddings locales, pero las asistencias historicas no se sincronizan
automaticamente. Android tiene deshabilitado el respaldo del sistema para evitar
copiar biometria a la nube.

El APK incluye el modelo ligero `w600k_mbf.onnx` del paquete oficial
`buffalo_sc`. Los modelos preentrenados de InsightFace son para investigacion no
comercial; un uso comercial requiere una licencia separada.

Este prototipo todavia no incluye cifrado de SQLite, autenticacion de la API,
HTTPS ni deteccion de fotografias impresas o pantallas. Debe usarse solo en una
red privada y con consentimiento. Antes de utilizarlo para decisiones oficiales
es necesario validar el umbral con el dataset del proyecto y agregar controles
de seguridad, cifrado y deteccion de suplantacion. La informacion biometrica y
academica debe eliminarse cuando ya no sea necesaria.
