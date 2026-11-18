# Gestor de capturas

App de Android para gestionar capturas de pantalla:

- Al hacer una captura aparece una notificación con la vista previa y los botones **Borrar** y **Keep**.
- **Borrar** la elimina del móvil y también su copia subida a **pCloud**.
- **Keep** propone una nota para Google Keep con el texto reconocido en la captura.
- **Borrar todas** aparece cuando hay varias capturas pendientes.
- La app muestra todas las capturas, con selección arrastrando y filtros para limpiar (antiguas, grandes…).

Requiere Android 11 o superior (64 bits).

## Instalar y actualizar

La forma más cómoda es con [Obtainium](https://github.com/ImranR98/Obtainium), que instala la app desde este repositorio y avisa de cada versión nueva:

1. Instala Obtainium (descárgalo de su página de *Releases*).
2. En Obtainium pulsa **Añadir app** y pega la dirección de este repositorio.
3. Pulsa **Instalar**. A partir de ahí, Obtainium te avisará de las actualizaciones.

También puedes descargar `GestorCapturas.apk` directamente de la sección **Releases** e instalarlo encima de la versión anterior.

## Si algo falla

En la app: **Ajustes → Diagnóstico y registro → Compartir**. El informe incluye el estado de los permisos, la conexión con pCloud y el registro de lo que ha hecho la app.

## Publicar una versión nueva

Sube `versionCode` y `versionName` en `app/build.gradle.kts` y haz *push* a `main`. GitHub Actions compila el APK, lo firma con la clave guardada en el secreto `SIGNING_KEYSTORE_BASE64` y, si ese `versionName` aún no está publicado, crea la *release* `v<versionName>` con el APK.
