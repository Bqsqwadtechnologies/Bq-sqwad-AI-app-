const FIRESTORE_BASE = "https://firestore.googleapis.com/v1";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const IDENTITY_LOOKUP = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";

let accessTokenCache = { token: null, exp: 0 };

function b64url(input) {
  const bytes = input instanceof ArrayBuffer ? new Uint8Array(input) : new TextEncoder().encode(input);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemToBytes(pem) {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function configureFirebaseEnv(env) {
  globalThis.__bqEnv = env;
}

function env(name) {
  const value = globalThis.__bqEnv?.[name];
  if (!value) throw new Error(name + " is not configured");
  return value;
}

async function googleAccessToken() {
  const now = Math.floor(Date.now() / 1000);
  if (accessTokenCache.token && accessTokenCache.exp > now + 60) return accessTokenCache.token;
  const account = JSON.parse(env("BQ_FIREBASE_SERVICE_ACCOUNT_JSON"));
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/identitytoolkit",
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned)
  );
  const assertion = unsigned + "." + b64url(signature);
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const body = await response.json();
  if (!response.ok || !body.access_token) throw new Error("Firebase service access token unavailable");
  accessTokenCache = { token: body.access_token, exp: now + Number(body.expires_in || 3600) };
  return body.access_token;
}

export async function deleteFirebaseAuthUser(uid) {
  const token = await googleAccessToken();
  const response = await fetch(
    "https://identitytoolkit.googleapis.com/v1/projects/" + encodeURIComponent(projectId()) + "/accounts:delete",
    {
      method: "POST",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ localId: String(uid || "") })
    }
  );
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = String(body.error?.message || "Firebase Auth account deletion failed");
    if (/USER_NOT_FOUND|user not found/i.test(message)) return { ok: true, alreadyDeleted: true };
    throw new Error(message);
  }
  return { ok: true };
}

export async function verifyFirebaseUser(request) {
  const authorization = request.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) throw new Error("Authentication required");
  const idToken = authorization.slice(7).trim();
  if (!idToken) throw new Error("Authentication required");
  // Firebase Web API keys are client identifiers, not authentication secrets.
  // Keep the configured Worker secret when present, but use the project's verified
  // Android/Web Firebase key as a safe fallback so the Worker never fails with
  // "FIREBASE_WEB_API_KEY is not configured" when the secret was not provisioned.
  const apiKey = globalThis.__bqEnv?.FIREBASE_WEB_API_KEY || "AIzaSyBcbFDXG6IsKatj8DjpCOQjJJ4DHv536eo";
  const response = await fetch(IDENTITY_LOOKUP + "?key=" + encodeURIComponent(apiKey), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ idToken })
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body.users?.[0]?.localId) throw new Error("Invalid or expired authentication token");
  const user = body.users[0];
  let customAttributes = {};
  try { customAttributes = user.customAttributes ? JSON.parse(user.customAttributes) : {}; } catch { customAttributes = {}; }
  return {
    uid: String(user.localId),
    email: user.email ? String(user.email) : null,
    emailVerified: user.emailVerified === true,
    customAttributes
  };
}

function projectId() {
  return JSON.parse(env("BQ_FIREBASE_SERVICE_ACCOUNT_JSON")).project_id;
}

function docUrl(path) {
  return FIRESTORE_BASE + "/projects/" + encodeURIComponent(projectId()) +
    "/databases/(default)/documents/" + path.split("/").map(encodeURIComponent).join("/");
}

function encodeValue(value) {
  if (value === null) return { nullValue: null };
  if (value === true || value === false) return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  }
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeValue) } };
  if (typeof value === "object") {
    const fields = {};
    for (const [k, v] of Object.entries(value)) fields[k] = encodeValue(v);
    return { mapValue: { fields } };
  }
  return { stringValue: String(value) };
}

function decodeValue(v) {
  if (!v) return null;
  if ("stringValue" in v) return v.stringValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return Number(v.doubleValue);
  if ("booleanValue" in v) return v.booleanValue;
  if ("nullValue" in v) return null;
  if ("timestampValue" in v) return v.timestampValue;
  if ("referenceValue" in v) return v.referenceValue;
  if ("arrayValue" in v) return (v.arrayValue.values || []).map(decodeValue);
  if ("mapValue" in v) {
    const out = {};
    for (const [k, value] of Object.entries(v.mapValue.fields || {})) out[k] = decodeValue(value);
    return out;
  }
  return null;
}

export function decodeDocument(doc) {
  if (!doc) return null;
  const out = {};
  for (const [k, v] of Object.entries(doc.fields || {})) out[k] = decodeValue(v);
  out.__name = doc.name;
  out.__updateTime = doc.updateTime || null;
  return out;
}

async function firestoreFetch(url, options = {}) {
  const token = await googleAccessToken();
  const response = await fetch(url, {
    ...options,
    headers: {
      authorization: "Bearer " + token,
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = body.error?.message || "Firestore request failed";
    throw new Error(message);
  }
  return body;
}

export async function firestoreGet(path) {
  try {
    return decodeDocument(await firestoreFetch(docUrl(path)));
  } catch (error) {
    if (/NOT_FOUND|not found/i.test(error.message || "")) return null;
    throw error;
  }
}

export async function firestoreSet(path, data, merge = true) {
  const fields = {};
  for (const [k, v] of Object.entries(data || {})) fields[k] = encodeValue(v);
  const url = docUrl(path) + (merge ? "?updateMask.fieldPaths=" + Object.keys(fields).map(encodeURIComponent).join("&updateMask.fieldPaths=") : "");
  return decodeDocument(await firestoreFetch(url, {
    method: "PATCH",
    body: JSON.stringify({ fields })
  }));
}

export async function firestoreCreate(path, data) {
  const fields = {};
  for (const [k, v] of Object.entries(data || {})) fields[k] = encodeValue(v);
  const parts = path.split("/");
  const id = parts.pop();
  const collection = parts.join("/");
  const url = FIRESTORE_BASE + "/projects/" + encodeURIComponent(projectId()) + "/databases/(default)/documents/" +
    collection.split("/").map(encodeURIComponent).join("/") + "?documentId=" + encodeURIComponent(id);
  return decodeDocument(await firestoreFetch(url, { method: "POST", body: JSON.stringify({ fields }) }));
}

export async function firestoreCommit(writes) {
  const body = { writes: writes.map(write => ({
    ...(write.create
      ? { create: { name: docUrl(write.path).replace(FIRESTORE_BASE + "/", ""), fields: Object.fromEntries(Object.entries(write.data || {}).map(([k,v]) => [k, encodeValue(v)])) } }
      : { update: { name: docUrl(write.path).replace(FIRESTORE_BASE + "/", ""), fields: Object.fromEntries(Object.entries(write.data || {}).map(([k,v]) => [k, encodeValue(v)])) } }),
    ...(!write.create && write.updateTime ? { currentDocument: { updateTime: write.updateTime } } : {}),
    ...(!write.create && write.exists === false ? { currentDocument: { exists: false } } : {})
  })) };
  const token = await googleAccessToken();
  const url = FIRESTORE_BASE + "/projects/" + encodeURIComponent(projectId()) + "/databases/(default)/documents:commit";
  const response = await fetch(url, {
    method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: JSON.stringify(body)
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.error?.message || "Firestore commit failed");
  return result;
}

export async function firestoreQuery(collection, {
  parent = null,
  filters = [],
  orderBy = null,
  limit = 100,
  offset = 0
} = {}) {
  const project = encodeURIComponent(projectId());
  const parentPath = parent ? "/" + parent.split("/").map(encodeURIComponent).join("/") : "";
  const url = FIRESTORE_BASE + "/projects/" + project + "/databases/(default)/documents:runQuery";
  const from = [{ collectionId: collection }];
  const structuredQuery = { from };
  if (filters.length) {
    structuredQuery.where = filters.length === 1
      ? { fieldFilter: filters[0] }
      : { compositeFilter: { op: "AND", filters: filters.map(fieldFilter => ({ fieldFilter })) } };
  }
  if (orderBy) structuredQuery.orderBy = [{ field: { fieldPath: orderBy.field }, direction: orderBy.direction || "DESCENDING" }];
  if (limit) structuredQuery.limit = Math.min(100, Math.max(1, Number(limit) || 100));
  if (Number.isFinite(Number(offset)) && Number(offset) > 0) structuredQuery.offset = Math.max(0, Math.floor(Number(offset)));
  const token = await googleAccessToken();
  const response = await fetch(url, {
    method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: JSON.stringify({ structuredQuery, parent: parent ? "projects/" + projectId() + "/databases/(default)/documents/" + parent.split("/").map(encodeURIComponent).join("/") : "projects/" + projectId() + "/databases/(default)/documents" })
  });
  const result = await response.json().catch(() => []);
  if (!response.ok) throw new Error(result.error?.message || "Firestore query failed");
  return (Array.isArray(result) ? result : []).filter(row => row.document).map(row => decodeDocument(row.document));
}

export async function firestoreQueryCollection(collection, parent = null) {
  const url = FIRESTORE_BASE + "/projects/" + encodeURIComponent(projectId()) + "/databases/(default)/documents/" + collection.split("/").map(encodeURIComponent).join("/");
  return firestoreFetch(url);
}

export async function firestoreDelete(path) {
  const token = await googleAccessToken();
  const response = await fetch(docUrl(path), { method: "DELETE", headers: { authorization: "Bearer " + token } });
  if (!response.ok && response.status !== 404) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error?.message || "Firestore delete failed");
  }
  return { ok: true };
}
