# Datos del proyecto

Esta carpeta separa los dos subconjuntos definidos en el informe:

```text
data/
|-- raw/
|   |-- classroom/              # Imagenes completas de aula
|   `-- registered_students/    # Una carpeta anonima por estudiante
|-- processed/
`-- test/
    |-- classroom_images/
    `-- recognition_images/
```

Ejemplo para un estudiante autorizado:

```text
data/raw/registered_students/estudiante_001/frontal_001.jpg
```

No se deben subir imagenes, nombres reales, embeddings ni metadatos sensibles a
GitHub. Los archivos `.gitkeep` solo conservan la estructura vacia.

