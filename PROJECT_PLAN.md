# Plan del proyecto

## Nombre propuesto

Asistencia Estudiantil con IA

## Nuevo enfoque

El proyecto deja de estar basado en lentes inteligentes, Arduino Nano y ESP32-CAM. El nuevo enfoque es una aplicacion Android con inteligencia artificial integrada para apoyar el registro de asistencia estudiantil.

## Problema a resolver

El registro manual de asistencia consume tiempo, puede tener errores y depende completamente del docente. La aplicacion busca automatizar parte del proceso usando la camara del telefono y modelos de IA para detectar presencia en el aula.

## Objetivo general

Desarrollar una aplicacion Android que permita registrar asistencia estudiantil mediante captura de camara, conteo de personas e integracion progresiva de inteligencia artificial.

## Objetivos especificos

- Detectar y contar personas usando IA.
- Registrar asistencia por fecha, hora, grupo y asignatura.
- Guardar los registros localmente.
- Generar evidencia basica del conteo realizado.
- Preparar el sistema para una futura identificacion autorizada de estudiantes.

## Herramientas propuestas

### Prototipo de IA

- Python
- OpenCV
- Ultralytics YOLO
- PyTorch
- NumPy
- InsightFace / ArcFace
- FastAPI
- SQLite

### Aplicacion Android

- Android Studio
- Kotlin
- CameraX
- ML Kit para deteccion local de rostros
- API HTTP local para reconocimiento

## Etapas

### Etapa 1: Prototipo en Python

Validar que el modelo detecta personas correctamente usando la camara de la laptop o videos de prueba.

### Etapa 2: Diseno de la app Android

Crear pantallas basicas:

- Inicio o seleccion de clase.
- Camara.
- Resultado del conteo.
- Historial de asistencia.

Estado actual: la app ya tiene una pantalla inicial con seleccion de clase, vista de camara, conteo facial en tiempo real y registro temporal en historial.

### Etapa 3: IA en Android

Usar la camara de Android y enviar capturas controladas al servicio Python.

Estado actual: ML Kit cuenta rostros localmente y la app se comunica con la API
para registrar embeddings y reconocer estudiantes.

### Etapa 4: Registro de asistencia

Guardar datos como:

- Fecha.
- Hora.
- Clase.
- Conteo detectado.
- Observaciones.

Estado actual: SQLite almacena estudiantes, sesiones y asistencias. Una
restriccion unica impide registrar dos veces al mismo estudiante en una sesion.

### Etapa 5: Identificacion autorizada

Agregar identificacion de estudiantes solo si existe consentimiento, control de privacidad y una forma segura de guardar credenciales.

Estado actual: InsightFace genera embeddings ArcFace y las fotos no se conservan.
Faltan cifrado, autenticacion, HTTPS y deteccion de suplantacion para un uso real.

## Consideraciones de privacidad

La identificacion por rostro debe tratarse como una funcion sensible. Para un sistema estudiantil real se recomienda:

- Solicitar consentimiento informado.
- Evitar guardar imagenes innecesarias.
- Guardar solo datos minimos.
- Proteger la base de datos.
- Permitir correccion manual de asistencia.
- No depender exclusivamente del rostro para decisiones oficiales.
