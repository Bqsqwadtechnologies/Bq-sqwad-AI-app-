export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/health" || url.pathname === "/") {
      return new Response(JSON.stringify({
        service: "BQ Sqwad AI",
        status: "ok"
      }), {
        status: 200,
        headers: {
          "content-type": "application/json; charset=utf-8"
        }
      });
    }

    return new Response(JSON.stringify({
      service: "BQ Sqwad AI",
      status: "ok",
      message: "Cloudflare Worker is running"
    }), {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8"
      }
    });
  }
};
