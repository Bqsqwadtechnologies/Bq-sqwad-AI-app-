import { configureFirebaseEnv, verifyFirebaseUser, firestoreGet, firestoreSet, firestoreCreate } from "./firebase.js";

const UPSTREAM = "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi";

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept",
    "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "Cache-Control": "no-store"
  };
}
function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { ...corsHeaders(), "content-type": "application/json; charset=utf-8" } });
}
async function readBody(request) {
  try { return await request.json(); } catch { return {}; }
}
function env(name) {
  const value = globalThis.__bqEnv?.[name];
  if (!value) throw new Error(name + " is not configured");
  return value;
}
async function openai(body, stream = false) {
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { Authorization: "Bearer " + env("OPENAI_API_KEY"), "Content-Type": "application/json" },
    body: JSON.stringify({ ...body, stream })
  });
  if (!response.ok) throw new Error("OpenAI HTTP " + response.status + ": " + (await response.text()).slice(0, 500));
  return response;
}
function outputText(result) {
  return result.output_text || result.output?.flatMap(x => x.content || []).map(x => x.text || "").join("") || "";
}
async function capability(uid, name) {
  const control = await firestoreGet("adminAdvancedOptions/capability_" + name);
  if (control?.enabled === false || control?.killSwitch === true) throw new Error("capability_disabled:capability_" + name);
  return control || { enabled: true, creditCost: 0, config: {} };
}
async function requireConsent(uid) {
  const consent = await firestoreGet("userConsents/" + uid);
  if (!consent || consent.requiredAccepted !== true) throw new Error("required_consent_missing");
}
async function memoryContext(uid) {
  const memory = await firestoreGet("userMemory/" + uid);
  if (!memory || memory.enabled === false || !Array.isArray(memory.items)) return "";
  return memory.items.slice(-30).map(x => String(x?.text || "").trim()).filter(Boolean).join("\n");
}
async function chargeCredits(uid, amount, capabilityName) {
  const charge = Math.max(0, Number(amount || 0));
  if (!charge) return { reservationId: null, amount: 0 };
  const balance = await firestoreGet("creditBalances/" + uid);
  if (!balance || Number(balance.available || 0) < charge) throw new Error("Insufficient credits");
  const reservationId = crypto.randomUUID();
  await firestoreSet("creditBalances/" + uid, {
    uid, available: Number(balance.available || 0) - charge,
    reserved: Number(balance.reserved || 0) + charge,
    updatedAt: new Date()
  }, true);
  await firestoreCreate("creditReservations/" + reservationId, {
    uid, capability: capabilityName, amount: charge, status: "reserved", createdAt: new Date()
  });
  return { reservationId, amount: charge };
}
async function commitCredits(uid, reservationId, providerId) {
  if (!reservationId) return;
  const reservation = await firestoreGet("creditReservations/" + reservationId);
  if (!reservation || reservation.uid !== uid || reservation.status !== "reserved") return;
  const balance = await firestoreGet("creditBalances/" + uid);
  await firestoreSet("creditBalances/" + uid, {
    available: Number(balance?.available || 0),
    reserved: Math.max(0, Number(balance?.reserved || 0) - Number(reservation.amount || 0)),
    updatedAt: new Date()
  }, true);
  await firestoreSet("creditReservations/" + reservationId, {
    status: "committed", providerId: providerId || null, committedAt: new Date()
  }, true);
  await firestoreCreate("creditLedger/" + crypto.randomUUID(), {
    uid, capability: reservation.capability, amount: Number(reservation.amount || 0),
    kind: "usage", providerId: providerId || null, createdAt: new Date()
  });
}
async function reverseCredits(uid, reservationId) {
  if (!reservationId) return;
  const reservation = await firestoreGet("creditReservations/" + reservationId);
  if (!reservation || reservation.uid !== uid || reservation.status !== "reserved") return;
  const balance = await firestoreGet("creditBalances/" + uid);
  await firestoreSet("creditBalances/" + uid, {
    available: Number(balance?.available || 0) + Number(reservation.amount || 0),
    reserved: Math.max(0, Number(balance?.reserved || 0) - Number(reservation.amount || 0)),
    updatedAt: new Date()
  }, true);
  await firestoreSet("creditReservations/" + reservationId, { status: "reversed", reversedAt: new Date() }, true);
}
async function ai(request, user, body) {
  await requireConsent(user.uid);
  const capabilityName = String(body.capability || "chat").trim();
  if (!/^[a-z0-9_-]{1,64}$/.test(capabilityName)) throw new Error("invalid_capability");
  const control = await capability(user.uid, capabilityName);
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName);
  try {
    const memory = await memoryContext(user.uid);
    const system = [body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
    const model = String(control.modelId || control.config?.modelId || "gpt-5.6-luna");
    const result = await openai({ model, input: String(body.message || ""), ...(system ? { instructions: system } : {}) });
    const payload = await result.json();
    const text = outputText(payload).trim();
    if (!text) throw new Error("The AI service returned an empty response.");
    await commitCredits(user.uid, reservation.reservationId, "openai");
    return json({ text, providerId: "openai", modelId: model, creditsCharged: reservation.amount, incidentId: crypto.randomUUID() });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}
async function vision(request, user, body) {
  await requireConsent(user.uid);
  const imageBase64 = String(body.imageBase64 || "");
  if (!imageBase64) throw new Error("image_required");
  if (imageBase64.length > 8000000) throw new Error("image_too_large");
  const control = await capability(user.uid, "chat");
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, "chat");
  try {
    const model = String(control.modelId || control.config?.modelId || "gpt-5.6-luna");
    const response = await openai({
      model,
      input: [{ role: "user", content: [
        { type: "input_text", text: String(body.message || "Describe what is visible in this camera frame.") },
        { type: "input_image", image_url: "data:" + String(body.mimeType || "image/jpeg") + ";base64," + imageBase64 }
      ]}]
    });
    const payload = await response.json();
    const text = outputText(payload).trim();
    await commitCredits(user.uid, reservation.reservationId, "openai");
    return json({ text, providerId: "openai", modelId: model, creditsCharged: reservation.amount });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}
async function stream(request, user, body) {
  await requireConsent(user.uid);
  const capabilityName = String(body.capability || "chat").trim();
  const control = await capability(user.uid, capabilityName);
  const reservation = await chargeCredits(user.uid, control.creditCost || 0, capabilityName);
  try {
    const memory = await memoryContext(user.uid);
    const system = [body.system ? String(body.system) : "", memory ? "Relevant user memory (use only when helpful and do not invent details):\n" + memory : ""].filter(Boolean).join("\n");
    const model = String(control.modelId || control.config?.modelId || "gpt-5.6-luna");
    const upstream = await openai({ model, input: String(body.message || ""), ...(system ? { instructions: system } : {}) }, true);
    const reader = upstream.body.getReader();
    const decoder = new TextDecoder();
    const encoder = new TextEncoder();
    const output = new ReadableStream({
      async start(controller) {
        let buffer = "";
        try {
          while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split(/\r?\n\r?\n/);
            buffer = events.pop() || "";
            for (const event of events) {
              const data = event.split(/\r?\n/).filter(x => x.startsWith("data:")).map(x => x.slice(5).trim()).join("\n");
              if (!data || data === "[DONE]") continue;
              let parsed; try { parsed = JSON.parse(data); } catch { continue; }
              if (parsed.type === "response.output_text.delta" && parsed.delta) {
                controller.enqueue(encoder.encode("data: " + JSON.stringify({type:"delta", text:parsed.delta}) + "\n\n"));
              } else if (parsed.type === "response.completed") {
                controller.enqueue(encoder.encode("data: " + JSON.stringify({type:"completed"}) + "\n\n"));
              } else if (parsed.type === "error") {
                controller.enqueue(encoder.encode("data: " + JSON.stringify({type:"error", message:parsed.error?.message || "Streaming failed"}) + "\n\n"));
              }
            }
          }
          await commitCredits(user.uid, reservation.reservationId, "openai");
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        } catch (error) {
          await reverseCredits(user.uid, reservation.reservationId);
          controller.enqueue(encoder.encode("data: " + JSON.stringify({type:"error", message:error.message || "stream_failed"}) + "\n\n"));
          controller.close();
        }
      }
    });
    return new Response(output, { status: 200, headers: { ...corsHeaders(), "content-type": "text/event-stream", "cache-control": "no-cache" } });
  } catch (error) {
    await reverseCredits(user.uid, reservation.reservationId);
    throw error;
  }
}

export default {
  async fetch(request, env) {
    configureFirebaseEnv(env);
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
    if (url.pathname === "/" || url.pathname === "/health") return json({service:"BQ Sqwad AI", status:"ok", backend:"cloudflare-worker"});
    try {
      if (url.pathname === "/ai" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await ai(request, user, await readBody(request));
      }
      if (url.pathname === "/ai/vision" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await vision(request, user, await readBody(request));
      }
      if (url.pathname === "/ai/stream" && request.method === "POST") {
        const user = await verifyFirebaseUser(request);
        return await stream(request, user, await readBody(request));
      }
      const upstreamUrl = UPSTREAM + url.pathname + url.search;
      const headers = new Headers(request.headers);
      headers.delete("host");
      const upstream = await fetch(upstreamUrl, {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
        redirect: "follow"
      });
      const responseHeaders = new Headers(upstream.headers);
      for (const [key, value] of Object.entries(corsHeaders())) responseHeaders.set(key, value);
      return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: responseHeaders });
    } catch (error) {
      console.error("bq_backend_error", { message: error?.message || "request_failed" });
      return json({ error: error?.message || "backend_unavailable" }, 400);
    }
  }
};
