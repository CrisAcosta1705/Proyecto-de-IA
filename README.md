# Asistencia estudiantil con IA

Prototipo academico para contar personas en un aula y registrar asistencia de
estudiantes autorizados mediante reconocimiento facial.

## Arquitectura hibrida

```text
Aplicacion Android
    |-- Modo telefono: ML Kit + ArcFace w600k_mbf ONNX + SQLite local
    |-- Modo servidor: fotografia JPEG 1080p hacia FastAPI
    `-- Modo automatico: servidor compatible y telefono como respaldo

API FastAPI permanente
    |-- InsightFace para deteccion + el mismo ArcFace w600k_mbf ONNX
    |-- HTTPS y clave de acceso
    `-- SQLite y modelos en disco persistente
```

El conteo general de personas se mantiene como un componente independiente con
YOLO en `people_counter.py`. Android cuenta rostros con ML Kit y puede registrar
identidades y asistencia sin computadora ni conexion a Internet.

## Funciones implementadas

- Registro de hasta cinco embeddings por estudiante en el telefono y hasta
  veinte en el servidor.
- La fotografia original, el nombre, el carnet y los embeddings se guardan en
  SQLite.
- Alineacion ArcFace de cinco puntos tanto en Android como en Railway.
- Embeddings ArcFace y comparacion con umbral configurable.
- Resultado `no identificado` cuando no se supera el umbral.
- Sesiones de asistencia por clase.
- Listas de asistencia identificadas por fecha y materia.
- Acumulacion de varias fotografias para la misma fecha y materia sin duplicar estudiantes.
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
- API desplegable en la nube y documentacion interactiva en `/docs`.
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
- Dominio HTTPS fijo configurado durante la compilacion de Android.
- Recuperacion de registros antiguos del servidor: la app descarga la foto,
  genera un embedding compatible con MobileFaceNet y lo conserva en el telefono.
- Subida manual de estudiantes y referencias faciales locales al servidor, con
  validacion individual y confirmacion antes de reemplazar referencias remotas.
- Acceso administrativo local con contraseña protegida mediante PBKDF2, bloqueo
  temporal por intentos fallidos y cierre de sesion sin borrar datos.

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

## Iniciar la API localmente

```powershell
.\scripts\start_api.ps1
```

Este arranque se conserva para desarrollo. La aplicacion publicada utiliza el
servidor HTTPS permanente configurado durante su compilacion.

En la computadora se puede comprobar con:

```text
http://127.0.0.1:8000/health
http://127.0.0.1:8000/docs
```

## Servidor permanente en Railway

El repositorio incluye `Dockerfile`, `requirements-server.txt` y la definicion
de infraestructura `.railway/railway.ts`. Railway crea el servicio en la region
US East, comprueba `/health` y monta un volumen persistente de 500 MB en
`/var/data` para la base SQLite, las fotografias y los modelos.

1. Instala la CLI con `npm install --global @railway/cli` y ejecuta
   `railway login`.
2. Desde la raiz del repositorio ejecuta `npm install` y
   `railway init --name asistencia-ia`.
3. Ejecuta `railway config apply` para crear `asistencia-api` y su volumen. El
   primer arranque permanecera bloqueado hasta configurar la clave.
4. Configura `ASISTENCIA_API_KEY` con una clave aleatoria de al menos 32
   caracteres mediante `railway variable set ASISTENCIA_API_KEY=CLAVE --service
   asistencia-api`. No agregues la clave al repositorio.
5. Genera una clave AES aleatoria de 32 bytes y configura su valor Base64 en
   `ASISTENCIA_DATA_ENCRYPTION_KEY`. Guardala fuera de Git y del telefono:
   perderla hace irrecuperables las fotos y embeddings del servidor.
6. Ejecuta `railway redeploy --service asistencia-api`.
7. Genera el dominio HTTPS con `railway domain --service asistencia-api` y
   comprueba que `https://DOMINIO/health` responda con `status: ok`.
8. En GitHub, abre `Settings > Secrets and variables > Actions` y configura:

| Tipo | Nombre | Valor |
| --- | --- | --- |
| Variable | `ASISTENCIA_API_URL` | URL HTTPS `*.up.railway.app` asignada por Railway |
| Secreto | `ASISTENCIA_API_KEY` | La misma clave configurada en Railway |

La API usa `FACE_SIMILARITY_THRESHOLD=0.50`,
`FACE_SINGLE_REFERENCE_THRESHOLD=0.58`, `FACE_MINIMUM_SCORE_MARGIN=0.05`,
`FACE_MINIMUM_REFERENCE_SUPPORT=0.38`, `FACE_SECOND_REFERENCE_WEIGHT=0.30` e
`INSIGHTFACE_DETECTION_SIZE=640` como
valores predeterminados. El margen evita aceptar identidades ambiguas; el
soporte y el peso de la segunda referencia exigen corroboracion cuando el
estudiante tiene varias fotos. El
tamaño de detección conserva más detalle en fotografías grupales. Estos valores
forman parte del perfil facial compartido y deben mantenerse iguales en
Railway y Android. La app valida el perfil publicado en `/health` antes de usar
el servidor.

El servicio de este proyecto esta publicado en:

```text
https://asistencia-api-production-f93b.up.railway.app
```

Despues de configurar la variable y el secreto, genera una nueva etiqueta para
que GitHub compile el APK con el dominio y la clave. La clave queda incorporada
en el APK y debe rotarse si el archivo se distribuye fuera del grupo autorizado.

El proyecto debe mantener una sola replica mientras use SQLite. El volumen de
500 MB es adecuado para las pruebas iniciales y puede ampliarse al cambiar de
plan. El volumen evita que los registros desaparezcan al desplegar una nueva
version, pero se recomienda activar copias de seguridad antes de almacenar datos
biometricos reales.

Railway utiliza el detector ligero de `buffalo_sc`, pero la extracción de
identidad siempre se ejecuta con el mismo archivo `w600k_mbf.onnx` incluido en
Android. El servidor valida su SHA-256 al cargarlo para impedir que ambos
motores comparen embeddings de modelos diferentes.

## Flujo desde Android

1. En el primer inicio, crea el usuario y la contraseña de administración del
   dispositivo. En los inicios posteriores utiliza esas credenciales.
2. Selecciona `Solo telefono` para trabajar sin computadora, `Servidor` para
   usar exclusivamente el servidor permanente o `Automatico` para utilizar el
   servidor cuando esté disponible y el teléfono como respaldo.
3. Si ya existian estudiantes en el servidor, pulsa
   `Recuperar registros del servidor`. No se copian embeddings incompatibles:
   la aplicacion vuelve a procesar cada fotografia con el modelo del telefono.
4. Para respaldar en el servidor los rostros guardados solamente en el telefono,
   abre `Config.`, pulsa `Subir registros del telefono` y confirma la operacion.
5. Abre `Registro`, ingresa nombre, carnet y sexo, y pulsa `Iniciar recorrido facial`.
6. Mueve el rostro siguiendo las indicaciones: frontal, perfil, arriba y abajo.
   La cámara muestra flechas para indicar la dirección, analiza el video, exige
   varios frames estables y guarda automáticamente una referencia clara por
   posición. Al finalizar, la prueba frontal muestra una cuenta regresiva de
   tres segundos antes de tomar la fotografía de verificación.
7. Abre `Asistencia`, selecciona materia y fecha, y pulsa `Reconocer asistencia`.
8. Puedes tomar varias fotografias para la misma materia y fecha. Cada captura
   agrega estudiantes nuevos y conserva una sola asistencia por carnet.
   En grupos, la aplicación captura a la máxima resolución compatible y asigna
   cada identidad a un solo rostro. Para aulas grandes se recomienda tomar varias
   fotografías cercanas por sectores.
9. Abre `Historial` para consultar todas las listas y sus estadisticas por sexo.
10. Abre `Estudiantes` para ver la tabla de rostros, editar perfiles o eliminar
   datos biometricos sin borrar asistencias historicas.
11. Pulsa `Finalizar asistencia` al terminar las capturas de una clase. Para salir
    de la cuenta, abre `Config.` y pulsa `Cerrar sesion`.

Para borrar un registro facial, escribe el carnet, pulsa
`Eliminar reconocimiento` y confirma el dialogo. La fotografia y los embeddings
se eliminan; el nombre, carnet y las asistencias historicas se conservan.

El carnet de estudiante admite letras, numeros, guion y guion bajo, por ejemplo
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
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del almacén de claves |
| `ANDROID_KEY_ALIAS` | Alias de la clave, por ejemplo `asistencia-ia` |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave |

El mismo apartado tambien debe contener `ASISTENCIA_API_URL` como variable y
`ASISTENCIA_API_KEY` como secreto para conectar el APK con el servidor permanente.

La clave y sus contraseñas deben conservarse para poder instalar futuras
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

En desarrollo, FastAPI crea `data/processed/asistencia.db`; en Railway utiliza
`/var/data/asistencia.db` dentro del disco persistente. Android crea
`asistencia_local.db` en el almacenamiento privado de la aplicacion. Ambas bases
guardan fotografias, embeddings, identificadores, nombres y asistencias. Las
fotografias de registro se pueden recuperar desde el servidor para regenerar
embeddings locales. La app tambien permite subir manualmente al servidor los
perfiles y referencias guardados en el telefono, pero las asistencias historicas
no se sincronizan automaticamente. Android cifra cada fotografia y embedding
con AES-256-GCM antes de escribirlo en SQLite. La clave se genera y conserva en
Android Keystore, no dentro de la base. Las cuentas locales tambien se guardan
en preferencias cifradas. El respaldo y la transferencia del sistema estan
deshabilitados, y la app bloquea capturas y grabaciones de pantalla.

El login administrativo es local al dispositivo. La contraseña no se guarda en
texto plano: se conserva un hash PBKDF2 con sal aleatoria. Este acceso protege la
interfaz y los datos locales; la API de produccion utiliza ademas su propia clave
tecnica enviada por el APK firmado.

El APK incluye el modelo ligero `w600k_mbf.onnx` del paquete oficial
`buffalo_sc`. Los modelos preentrenados de InsightFace son para investigacion no
comercial; un uso comercial requiere una licencia separada.

La API en Railway utiliza HTTPS y una clave enviada en `X-API-Key`. Las fotos y
embeddings de SQLite se cifran con AES-256-GCM usando
`ASISTENCIA_DATA_ENCRYPTION_KEY`; los datos anteriores se migran y compactan al
arrancar. El contenedor aplica permisos privados, los endpoints biometricos
envian cabeceras `no-store` y la documentacion publica se desactiva en
produccion. La app rechaza servidores cuyo `/health` no confirme
`storage_encrypted=true` o cuyo perfil facial no coincida con el perfil local.

Para rotar la clave, conserva temporalmente la anterior en
`ASISTENCIA_DATA_ENCRYPTION_KEY_PREVIOUS`, configura una nueva clave principal
y despliega. El servidor vuelve a cifrar los BLOB con la nueva clave; despues
de comprobar el servicio puede eliminarse la variable anterior. Nunca se deben
versionar estas claves ni incluirlas en copias del documento.

El prototipo todavia no detecta fotografias impresas o pantallas. Debe usarse
con consentimiento, validar el umbral con el dataset real y eliminar la
informacion biometrica y academica cuando ya no sea necesaria.
