# JARVIS — sistema de extensiones personales

## Objetivo

Permitir que cada propietario agregue funciones, herramientas y paquetes de conocimiento propios sin modificar ni reemplazar el núcleo original de JARVIS.

## Separación de componentes

- **Núcleo original:** pantallas, memoria base, controles de seguridad y capacidades oficiales. No se modifica al importar una extensión.
- **Extensiones personales:** paquetes independientes, identificados por ID, nombre, versión, autor y fecha de instalación.
- **Datos de cada instalación:** las extensiones y sus datos privados se guardan en el almacenamiento local de esa instalación. No se mezclan entre propietarios.
- **Portabilidad:** exportar e importar paquetes de extensión y, por separado, copias de seguridad de sus datos. La identidad del propietario no se exporta como texto ni se incluye en el APK.

## Primera versión segura (sin ejecutar código arbitrario)

Una extensión inicial será declarativa: metadatos, instrucciones, plantillas, listas de conocimiento y acciones elegidas de un catálogo permitido por JARVIS. No podrá contener ni ejecutar Kotlin, Java, JavaScript, Python, comandos de shell, scripts ni binarios.

Ejemplo de manifiesto:

```json
{
  "format": "jarvis-extension",
  "formatVersion": 1,
  "id": "com.example.study-helper",
  "name": "Ayudante de estudio",
  "version": "1.0.0",
  "description": "Organiza temas y sesiones de estudio",
  "capabilities": ["knowledge.search", "tasks.create"],
  "content": {
    "instructions": "Ayuda a organizar temas de estudio.",
    "knowledge": []
  }
}
```

## Permisos

- Cada extensión declara sus capacidades antes de instalarse.
- JARVIS valida el formato, tamaño, versión y capacidades solicitadas.
- Ninguna extensión recibe permisos automáticamente por estar importada.
- Las acciones sensibles requieren confirmación explícita del usuario y, cuando corresponda, autorización del propietario.
- Una extensión no puede leer secretos, PIN, credenciales, datos de otras extensiones ni memoria privada salvo permiso específico y limitado.
- El núcleo puede desactivar o retirar una extensión sin borrar el resto de JARVIS.

## Administración

La pantalla **Extensiones** deberá permitir:
1. Importar un paquete desde un archivo elegido por el usuario.
2. Revisar nombre, autor, versión, contenido y permisos solicitados antes de instalar.
3. Activar, desactivar, actualizar y desinstalar extensiones.
4. Exportar una extensión personal.
5. Mostrar advertencias si el paquete está incompleto, duplicado o solicita capacidades no admitidas.

## Propiedad y varios dispositivos

La propiedad debe estar separada de las extensiones. No se incluirá un PIN maestro ni una clave privada dentro del APK. Para reconocer al mismo propietario en varios teléfonos, la futura solución debe usar una credencial criptográfica generada y protegida fuera del APK, con una copia de recuperación que el propietario custodie. La app contendrá únicamente los datos públicos necesarios para verificarla. No se debe confundir “primer usuario de una instalación” con “creador original”.

## Límites de la primera entrega

- La importación de extensiones no ejecuta código.
- No se promete sincronización automática entre teléfonos.
- La IA online/offline solo podrá usar el contenido de una extensión cuando el motor correspondiente esté conectado y el usuario autorice el contexto.
- Las funciones que necesiten código nativo o permisos nuevos requerirán una actualización revisada de JARVIS.

## Orden de implementación

1. Modelo de datos y validador de manifiestos.
2. Almacenamiento local aislado para extensiones.
3. Pantalla de revisión e instalación, lista, activación y desinstalación.
4. Exportación/importación de paquetes.
5. Catálogo de capacidades seguras y confirmaciones.
6. Integración con memoria y motores de IA cuando estén conectados.
7. Identidad de propietario portátil con credencial criptográfica protegida.
