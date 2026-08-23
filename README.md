# Conteo de personas con YOLO

Proyecto base para detectar y contar personas usando Python, OpenCV y YOLO.

## Activar entorno

En PowerShell, dentro de esta carpeta:

```powershell
.\.venv\Scripts\Activate.ps1
```

Si PowerShell bloquea la activacion del entorno, ejecuta una vez:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

Luego vuelve a activar el entorno.

## Probar con camara de laptop

```powershell
python people_counter.py
```

Para cerrar la ventana, presiona `q`.

Si tu laptop tiene mas de una camara, prueba otro indice:

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

## Usar ESP32-CAM despues

Cuando tengas la ESP32-CAM transmitiendo por WiFi, usa:

```powershell
python people_counter.py --source esp32 --esp32-url http://192.168.1.45:81/stream
```

Cambia la IP por la que muestre tu ESP32-CAM en el monitor serial de Arduino IDE.

## Ajustar sensibilidad

Si detecta personas falsas, sube la confianza:

```powershell
python people_counter.py --confidence 0.65
```

Si no detecta personas reales, baja un poco la confianza:

```powershell
python people_counter.py --confidence 0.4
```
