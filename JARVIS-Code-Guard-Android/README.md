# JARVIS Code Guard for Android

**Versión:** 0.1.0  
**Estado:** diseño y especificación inicial  
**Independencia:** proyecto Android separado. No forma parte de `JARVIS-Android-0.4` ni modifica el APK principal.

## Objetivo

Crear una herramienta Android que pueda inspeccionar código fuente, explicar su comportamiento y evaluar riesgos antes de permitir cualquier prueba. Leer código no equivale a ejecutarlo: la inspección inicial será siempre estática y no ejecutará el archivo analizado.

## Principios obligatorios

1. **Analizar antes de ejecutar.** Importar, abrir o analizar un archivo nunca lo ejecuta.
2. **Denegar por defecto.** Si el lenguaje, el comportamiento o el riesgo no se pueden evaluar con suficiente confianza, no se habilita la ejecución.
3. **Dos barreras independientes.** El análisis del código no sustituye al aislamiento del entorno de pruebas.
4. **Sin acceso libre al dispositivo.** El código de prueba no tendrá acceso directo a archivos personales, credenciales, cámara, micrófono, contactos, SMS ni otras aplicaciones.
5. **Sin privilegios elevados.** No root, no ejecución como administrador y no permisos de accesibilidad.
6. **Aprobación explícita.** El usuario debe revisar el informe y autorizar cada ejecución.
7. **Límites estrictos.** Tiempo, memoria, almacenamiento, procesos y salida limitados; botón para detener la prueba.
8. **No tocar originales.** Las pruebas usan una copia temporal; nunca modifican el archivo importado.
9. **Transparencia.** Mostrar lenguaje detectado, hallazgos, limitaciones y motivo de cada bloqueo.
10. **Privacidad.** El análisis debe ser local por defecto. No subir código a servicios externos sin consentimiento explícito.

## Alcance de la primera etapa

- Crear el proyecto Android independiente.
- Definir arquitectura, modelo de hallazgos y políticas de seguridad.
- Implementar importación de archivos como datos inertes.
- Implementar inspección estática y un informe explicable.
- Todavía **no** ejecutar código arbitrario.

## Etapas posteriores

- Añadir analizadores específicos por lenguaje, empezando por un alcance pequeño y verificable.
- Evaluar runtimes Android mantenidos y sus límites reales.
- Diseñar un entorno de ejecución aislado; no confiar en un simple proceso separado como única barrera.
- Incorporar pruebas automatizadas con ejemplos benignos y muestras de prueba no ejecutables.
- Solo habilitar ejecución para lenguajes y configuraciones cuyo aislamiento se haya validado.

## Primera plataforma

Android. La aplicación será independiente y tendrá su propio identificador, configuración Gradle, versiones, compilación y artefactos. No se añadirá como módulo ni dependencia de la aplicación JARVIS principal.

## Criterio de seguridad

Un resultado "sin hallazgos" significa únicamente que los analizadores no encontraron señales conocidas; nunca significa que el código sea garantizadamente seguro. Los casos desconocidos o no soportados quedan bloqueados para ejecución.
