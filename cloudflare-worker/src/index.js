const DEFAULT_REALTIME_MODEL = "gpt-realtime";
// EMERGENCY: full restore in progress - loading from tool
export default { async fetch() { return new Response(JSON.stringify({ error: "worker_source_restore_in_progress" }), { status: 503, headers: { "content-type": "application/json" } }); } };
