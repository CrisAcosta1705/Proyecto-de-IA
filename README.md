# Asistencia estudiantil con IA

Proyecto enfocado en una aplicacion Android para asistencia estudiantil con inteligencia artificial integrada.

La primera etapa usa Python como prototipo para validar el conteo de personas con la camara de la laptop. La version final del proyecto debe llevar esta logica a Android usando la camara del telefono.

## Objetivo

Crear una aplicacion Android capaz de apoyar el registro de asistencia estudiantil mediante:

- Conteo de personas presentes en el aula.
- Captura de evidencia visual controlada.
- Registro de fecha, hora y grupo/clase.
- Posible identificacion de estudiantes con credenciales autorizadas.
- Almacenamiento local o en base de datos.

## Enfoque tecnico

### Prototipo actual

El prototipo actual esta hecho en Python y permite probar la deteccion de personas antes de construir la app Android.

Herramientas usadas:

- Python 3.12
- OpenCV
- Ultralytics YOLO
- PyTorch
- NumPy

### Aplicacion Android final

Para la aplicacion Android se recomienda usar:

- Kotlin como lenguaje principal.
- Android Studio.
- CameraX para capturar imagen/video.
- TensorFlow Lite para ejecutar modelos de IA en el dispositivo.
- Room o SQLite para guardar asistencia localmente.
- Firebase opcional para sincronizacion en la nube.

## Flujo propuesto

```text
App Android
    |
    v
Camara del telefono
    |
    v
Modelo de IA local
    |
    v
Conteo de personas / validacion de estudiantes
    |
    v
Registro de asistencia
```

## Probar el prototipo con camara de laptop

Activa el entorno virtual:

```powershell
.\.venv\Scripts\Activate.ps1
```

Ejecuta el conteo:

```powershell
python people_counter.py
```

Para cerrar la ventana, presiona `q`.

Si tu laptop tiene mas de una camara:

```powershell
python people_counter.py --camera-index 1
```

## Probar con video local

```powershell
python people_counter.py --source video --video-path .\videos\prueba.mp4
```

## Guardar conteos en CSV

```powershell
python people_counter.py --csv .\data\conteos.csv
```

## Siguiente etapa

La siguiente etapa del proyecto es crear una app Android base con:

- Pantalla de inicio de sesion o seleccion de clase.
- Pantalla de camara.
- Boton para iniciar conteo.
- Resultado del conteo.
- Registro de asistencia por fecha y hora.
- Base de datos local.

Despues se puede integrar IA en Android usando TensorFlow Lite.

## Estructura Android inicial

La app Android inicial esta en:

```text
android-app/
```

Contiene:

- Proyecto Gradle Android.
- Modulo `app`.
- `MainActivity.kt`.
- Pantalla inicial en XML.
- Dependencias preparadas para CameraX y TensorFlow Lite.

Para abrirla:

1. Abre Android Studio.
2. Selecciona `Open`.
3. Abre la carpeta `android-app`.
4. Espera la sincronizacion de Gradle.
5. Ejecuta la app en un emulador o telefono Android.

La app Android ya incluye una primera vista de camara con CameraX y conteo de rostros con ML Kit Face Detection.

Para probarla en un telefono:

1. Conecta el telefono por USB.
2. Activa depuracion USB.
3. Selecciona el dispositivo en Android Studio.
4. Presiona `Run`.
5. Acepta el permiso de camara en el telefono.

Esta etapa detecta rostros y cuenta cuantos aparecen en la camara. Todavia no identifica el nombre de cada estudiante; esa funcion requiere un modulo adicional de credenciales faciales, consentimiento informado y almacenamiento seguro.
