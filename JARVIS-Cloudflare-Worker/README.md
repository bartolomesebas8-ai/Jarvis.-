# Conexión segura de JARVIS 0.4 con Groq

Este directorio contiene un intermediario para que la aplicación Android no tenga que llevar la clave de Groq.

## Importante
- La clave `GROQ_API_KEY` se guarda como **secret del servidor**, nunca en el APK, Kotlin, GitHub ni el chat.
- El despliegue y la cuenta deben ser administrados por un adulto responsable.
- No publiques el endpoint hasta que el administrador configure límites de solicitudes/abuso en Cloudflare y revise el consumo de Groq. El proxy no incorpora por sí solo un límite persistente por usuario.
- No compartas la URL privada del servicio públicamente.

## Despliegue (administrador adulto)
1. Crear un Worker en Cloudflare.
2. Copiar el contenido de `worker.js` al editor del Worker y desplegarlo.
3. En Settings → Variables and Secrets, crear el secret `GROQ_API_KEY` con la clave de Groq.
4. Configurar límites de solicitudes y supervisión antes de habilitar el acceso desde el teléfono.
5. Guardar la URL del Worker para configurar el cliente Android en el siguiente paso.

## Contrato de la API
Enviar POST con JSON:
```json
{
  "messages": [
    {"role": "user", "content": "Hola, JARVIS"}
  ]
}
```

Respuesta correcta:
```json
{"reply":"¡Hola! ¿En qué puedo ayudarte?"}
```

El Worker fija el modelo en el servidor, valida roles y tamaños, limita el número de mensajes y no devuelve la clave. Todavía falta conectar el cliente Android a la URL del Worker.
