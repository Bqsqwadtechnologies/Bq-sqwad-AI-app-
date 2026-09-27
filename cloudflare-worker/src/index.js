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
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      ...corsHeaders(),
      "content-type": "application/json; charset=utf-8"
    }
  });
}

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (url.pathname === "/" || url.pathname === "/health") {
      return json({
        service: "BQ Sqwad AI",
        status: "ok",
        backend: "cloudflare-worker"
      });
    }

    const upstreamUrl = UPSTREAM + url.pathname + url.search;
    const headers = new Headers(request.headers);
    headers.delete("host");

    try {
      const upstream = await fetch(upstreamUrl, {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD"
          ? undefined
          : request.body,
        redirect: "follow"
      });

      const responseHeaders = new Headers(upstream.headers);
      for (const [key, value] of Object.entries(corsHeaders())) {
        responseHeaders.set(key, value);
      }

      return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: responseHeaders
      });
    } catch (error) {
      console.error("backend_proxy_failed", error);
      return json({
        error: "backend_unavailable",
        message: "BQ Sqwad AI's secure backend is temporarily unavailable."
      }, 502);
    }
  }
};
