# Diseño del módulo Code Guard de JARVIS

**Destino:** aplicación Android existente JARVIS  
**Estado:** especificación inicial; todavía no implementado  
**Separación:** módulo interno de JARVIS, no una segunda aplicación.

## Objetivo

Añadir a JARVIS la capacidad de inspeccionar y explicar código fuente, evaluar riesgos y, en una etapa posterior, permitir pruebas únicamente dentro de un entorno aislado validado.

## Principios

- Importar, leer o analizar nunca ejecuta el archivo.
- El análisis estático se realiza primero y sin ejecutar el código.
- Denegar por defecto cuando el lenguaje o el comportamiento no se pueda evaluar.
- Un resultado sin hallazgos no garantiza que el código sea seguro.
- El analizador y el aislamiento de ejecución son barreras distintas.
- No conceder acceso libre a archivos personales, credenciales, cámara, micrófono, contactos, SMS ni otras aplicaciones.
- No root ni privilegios elevados.
- No descargar ni instalar dependencias automáticamente.
- Pedir aprobación explícita antes de cualquier prueba.
- Usar copias temporales; nunca modificar el original.
- Limitar tiempo, memoria, almacenamiento, procesos y salida; permitir detener la prueba.
- Procesamiento local por defecto; no subir código sin consentimiento.

## Arquitectura prevista dentro de JARVIS

1. **Interfaz Code Guard:** importar código, elegir archivo y ver informe.
2. **Identificación:** reconocer formato y lenguaje sin confiar solo en la extensión.
3. **Analizadores estáticos:** parseo y reglas específicas por lenguaje.
4. **Motor de políticas:** clasificar hallazgos y decidir bloquear o permitir revisión.
5. **Informe explicable:** archivo, línea, evidencia, severidad, confianza y limitaciones.
6. **Entorno de pruebas futuro:** runtime restringido, separado del proceso principal y con capacidades mínimas.
7. **Registro y limpieza:** historial local y eliminación del espacio temporal.

## Estados

- `SAFE_TO_REVIEW`: no se detectaron señales conocidas; no autoriza ejecución automática.
- `WARNING`: comportamiento sensible o evidencia incompleta; bloquear ejecución inicialmente.
- `BLOCKED`: riesgo crítico, formato no soportado o política incumplida.
- `NOT_ANALYZABLE`: cobertura insuficiente.

## Etapas de implementación

1. Inspección estática y reportes, sin ejecución.
2. Primer lenguaje soportado con parser y reglas verificables.
3. Pruebas automatizadas y evaluación de falsos positivos/negativos.
4. Investigación de runtime Android específico y su aislamiento real.
5. Ejecución limitada solo cuando se valide la frontera de seguridad.
6. Integración gradual con el chat de JARVIS.

## Limitación técnica

Android no ofrece una caja de arena universal para ejecutar cualquier lenguaje arbitrario con garantías absolutas. Un proceso secundario, WebView o contenedor improvisado no es por sí solo una frontera suficiente. Cada runtime se debe evaluar individualmente.
