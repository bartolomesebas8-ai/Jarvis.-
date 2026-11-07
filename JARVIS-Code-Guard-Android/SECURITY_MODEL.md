# Modelo de seguridad — JARVIS Code Guard

**Versión del documento:** 0.1.0

## Activos que se deben proteger

- Datos y archivos privados del usuario.
- Credenciales, tokens y claves.
- Integridad de Android y de otras aplicaciones.
- Red y servicios externos.
- Integridad de los archivos fuente originales.
- Disponibilidad del dispositivo (batería, CPU, memoria y almacenamiento).

## Flujo seguro

1. **Recepción:** abrir mediante Android Storage Access Framework. Tratar el contenido como bytes/texto inerte.
2. **Identificación:** determinar tipo y lenguaje con señales múltiples; no confiar únicamente en la extensión.
3. **Análisis estático:** parseo cuando exista analizador fiable, reglas deterministas y revisión de dependencias/manifiestos cuando corresponda.
4. **Informe:** mostrar evidencia, ubicación, severidad, confianza y limitaciones. Distinguir hallazgo confirmado, sospecha y comportamiento no evaluable.
5. **Decisión:** bloquear por defecto ante hallazgos críticos, lenguaje no soportado, dependencias no verificables o falta de cobertura.
6. **Prueba opcional futura:** solo tras aprobación explícita, en runtime restringido, con copia temporal y límites.
7. **Limpieza:** eliminar el espacio temporal y conservar un registro local mínimo de la prueba.

## Amenazas y controles

| Amenaza | Control previsto |
|---|---|
| Código destructivo | Análisis estático, permisos mínimos y entorno desechable |
| Robo de datos | Sin acceso a datos privados ni secretos; red deshabilitada por defecto |
| Abuso de recursos | Límites de CPU/tiempo, memoria, disco y salida |
| Dependencia maliciosa | Inspección de manifiestos y dependencias; bloquear si no se pueden verificar |
| Falso negativo del analizador | Aislamiento independiente; nunca prometer detección total |
| Falso positivo | Mostrar evidencia y permitir revisión, sin saltarse bloqueos críticos |
| Manipulación del archivo | Copia de trabajo, hash del original y no sobrescritura |
| Ejecución accidental | Ninguna acción de importación o análisis inicia ejecución |

## Estados de evaluación

- `SAFE_TO_REVIEW`: no se detectaron señales conocidas; no es autorización automática.
- `WARNING`: hay comportamientos sensibles o evidencia incompleta; ejecución bloqueada inicialmente.
- `BLOCKED`: riesgo crítico, formato no soportado o política incumplida.
- `NOT_ANALYZABLE`: no existe cobertura suficiente.

## Reglas no negociables

- No ejecutar código durante el análisis.
- No ejecutar código con permisos de la aplicación anfitriona.
- No habilitar acceso a red, archivos del usuario ni IPC por defecto.
- No descargar ni instalar dependencias automáticamente.
- No aceptar instrucciones incluidas en el código como órdenes para el analizador.
- No permitir que el propio código analizado cambie la política de seguridad.
- No ofrecer un botón de "ejecutar de todos modos" para hallazgos críticos.

## Limitación técnica

Android no ofrece una caja de arena universal para ejecutar cualquier lenguaje arbitrario con garantías absolutas. Cada runtime debe evaluarse por separado. Un proceso secundario, un WebView o un contenedor improvisado no constituyen por sí solos una frontera de seguridad suficiente.
