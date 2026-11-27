# Gestor de capturas

App de Android para gestionar capturas de pantalla:

- Al hacer una captura aparece una notificación con la vista previa y los botones **Borrar** y **Keep**.
- **Borrar** la mueve a la **papelera** (30 días, con opción de restaurar) y manda su copia subida a la papelera de **pCloud**.
- **Keep** propone una nota para Google Keep con el texto reconocido en la captura.
- **Borrar todas** aparece cuando hay varias capturas pendientes.
- La app muestra todas las capturas, con selección arrastrando y filtros para limpiar (antiguas, grandes…).

Requiere Android 11 o superior (64 bits).

## Instalar y actualizar

1. Descarga `GestorCapturas.apk` de la última versión en **Releases** e instálalo.
2. Listo: la app comprueba sola si hay versión nueva al abrirla y ofrece **Actualizar** dentro de la propia app. También puedes buscarla en **Ajustes → Buscar actualizaciones**.

La primera actualización pedirá permitir que la app instale apps de esta fuente (una sola vez).

## Si algo falla

En la app: **Ajustes → Diagnóstico y registro → Compartir**. El informe incluye el estado de los permisos, la conexión con pCloud y el registro de lo que ha hecho la app.

## Publicar una versión nueva

Sube `versionCode` y `versionName` en `app/build.gradle.kts` y haz *push* a `main`. GitHub Actions compila el APK, lo firma con la clave guardada en el secreto `SIGNING_KEYSTORE_BASE64` y, si ese `versionName` aún no está publicado, crea la *release* `v<versionName>` con el APK.
