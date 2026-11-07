/**
 * JARVIS 0.4 — Groq proxy (Cloudflare Worker)
 *
 * Keep GROQ_API_KEY as a Worker secret. Never put it in Android, GitHub,
 * source control, logs, or chat.
 *
 * Before exposing this endpoint publicly, the account owner should enable
 * Cloudflare rate limiting / abuse controls and monitor Groq usage.
 */
const MODEL = "llama-3.3-70b-versatile";
const MAX_BODY = 24_000;
const MAX_MESSAGES = 24;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "POST, OPTIONS",
      "access-control-allow-headers": "Content-Type"
    }
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "POST, OPTIONS",
        "access-control-allow-headers": "Content-Type"
      }
    });

    if (request.method !== "POST") return json({ error: "Método no permitido." }, 405);
    if (!env.GROQ_API_KEY) return json({ error: "El servidor aún no está configurado." }, 503);

    const declaredLength = Number(request.headers.get("content-length") || 0);
    if (declaredLength > MAX_BODY) return json({ error: "Solicitud demasiado grande." }, 413);

    let payload;
    try {
      const raw = await request.text();
      if (raw.length > MAX_BODY) return json({ error: "Solicitud demasiado grande." }, 413);
      payload = JSON.parse(raw);
    } catch {
      return json({ error: "JSON no válido." }, 400);
    }

    if (!Array.isArray(payload.messages) || payload.messages.length < 1 ||
        payload.messages.length > MAX_MESSAGES) {
      return json({ error: "Historial de mensajes no válido." }, 400);
    }

    const messages = payload.messages.map((m) => {
      if (!m || !["system", "user", "assistant"].includes(m.role) ||
          typeof m.content !== "string" || m.content.length > 6000) return null;
      return { role: m.role, content: m.content };
    });
    if (messages.some((m) => m === null)) return json({ error: "Formato de mensaje no válido." }, 400);

    try {
      const upstream = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "authorization": `Bearer ${env.GROQ_API_KEY}`,
          "content-type": "application/json"
        },
        body: JSON.stringify({
          model: MODEL,
          messages,
          temperature: 0.7,
          max_tokens: 700
        })
      });

      const result = await upstream.json();
      if (!upstream.ok) {
        return json({ error: "Groq no pudo procesar la solicitud.", status: upstream.status }, 502);
      }
      const answer = result?.choices?.[0]?.message?.content;
      if (typeof answer !== "string" || !answer.trim()) {
        return json({ error: "Groq devolvió una respuesta vacía." }, 502);
      }
      return json({ reply: answer });
    } catch {
      return json({ error: "No se pudo conectar con Groq." }, 502);
    }
  }
};
