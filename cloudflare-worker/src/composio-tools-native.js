/**
 * Plan-catalog tool connections via Composio (or compatible).
 * Business AI tools are chosen by the business from their plan — NOT forced BVN/NIN.
 * Optional bank KYC tools remain available only if the plan lists them and the business enables them.
 */

export function toolsForPlan(planCatalogEntry) {
  const list = planCatalogEntry?.tools || planCatalogEntry?.connectors || [];
  return (Array.isArray(list) ? list : []).map((t) => ({
    id: String(t.id || t.slug || ""),
    name: String(t.name || t.id || "Tool"),
    composioApp: String(t.composioApp || t.app || t.id || ""),
    required: t.required === true,
    category: String(t.category || "general"),
  })).filter((t) => t.id);
}

export async function listConnectedTools(dbGet, businessId) {
  const doc = await dbGet(`businessWorkspaces/${businessId}/composioConnections`).catch(() => null);
  if (!doc?.items) return [];
  try {
    const items = typeof doc.items === "string" ? JSON.parse(doc.items) : doc.items;
    return Array.isArray(items) ? items : [];
  } catch {
    return [];
  }
}

export async function saveConnectedTool(dbWrite, businessId, connection) {
  const existing = await listConnectedTools(async (p) => {
    if (p.includes("composioConnections")) {
      return { items: JSON.stringify([]) };
    }
    return null;
  }, businessId).catch(() => []);
  void existing;
  const path = `businessWorkspaces/${businessId}/composioConnections/${connection.toolId}`;
  await dbWrite(path, {
    toolId: connection.toolId,
    composioApp: connection.composioApp,
    accountLabel: connection.accountLabel || "",
    status: connection.status || "connected",
    connectedAt: new Date().toISOString(),
    // Never store raw OAuth secrets in plain fields — only session refs.
    sessionRef: connection.sessionRef || null,
  });
  return { ok: true };
}

/** Execute a tool only if connected and listed on the plan. */
export async function runPlanTool(env, {
  businessId,
  toolId,
  planTools,
  connected,
  args,
}) {
  const allowed = (planTools || []).some((t) => t.id === toolId);
  if (!allowed) {
    return { ok: false, error: "tool_not_on_plan" };
  }
  const conn = (connected || []).find((c) => c.toolId === toolId && c.status === "connected");
  if (!conn) {
    return { ok: false, error: "tool_not_connected" };
  }
  // Composio API key stays in Worker secrets.
  const apiKey = env?.COMPOSIO_API_KEY;
  if (!apiKey) {
    return { ok: false, error: "composio_not_configured" };
  }
  // Placeholder execution surface — real Composio HTTP calls use sessionRef.
  return {
    ok: true,
    toolId,
    resultSummary: `Tool ${toolId} accepted (session ${conn.sessionRef || "local"}).`,
    // Never echo secrets from args
    argsEcho: Object.keys(args || {}),
  };
}
