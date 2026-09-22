import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getProviderSecret, setProviderSecret } from "./secret-store.js";

const CONFIG = "socialIntegrations";
const ACCOUNTS = "socialAccounts";

const PLATFORMS = {
  facebook: {
    label: "Facebook Pages",
    authorizationUrl: "https://www.facebook.com/v24.0/dialog/oauth",
    tokenUrl: "https://graph.facebook.com/v24.0/oauth/access_token",
    defaultScopes: ["pages_show_list", "pages_read_engagement", "pages_manage_posts"]
  },
  instagram: {
    label: "Instagram",
    authorizationUrl: "https://www.facebook.com/v24.0/dialog/oauth",
    tokenUrl: "https://graph.facebook.com/v24.0/oauth/access_token",
    defaultScopes: ["instagram_basic", "instagram_content_publish"]
  },
  youtube: {
    label: "YouTube",
    authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth",
    tokenUrl: "https://oauth2.googleapis.com/token",
    defaultScopes: ["https://www.googleapis.com/auth/youtube.readonly", "https://www.googleapis.com/auth/youtube.upload"]
  },
  tiktok: {
    label: "TikTok",
    authorizationUrl: "https://www.tiktok.com/v2/auth/authorize/",
    tokenUrl: "https://open.tiktokapis.com/v2/oauth/token/",
    defaultScopes: ["user.info.basic", "video.list", "video.publish"]
  },
  x: {
    label: "X",
    authorizationUrl: "https://twitter.com/i/oauth2/authorize",
    tokenUrl: "https://api.x.com/2/oauth2/token",
    defaultScopes: ["tweet.read", "tweet.write", "users.read", "offline.access"]
  }
};

function clean(v, n = 500) { return String(v ?? "").trim().slice(0, n); }

function configRef() { return getFirestore().collection(CONFIG).doc("global"); }
function accountRef(id) { return getFirestore().collection(ACCOUNTS).doc(id); }

export function listSocialPlatforms() {
  return Object.entries(PLATFORMS).map(([id, p]) => ({
    id,
    label: p.label,
    authorizationUrl: p.authorizationUrl,
    tokenUrl: p.tokenUrl,
    defaultScopes: p.defaultScopes
  }));
}

export async function getSocialConfig() {
  const snap = await configRef().get();
  const data = snap.exists ? snap.data() : {};
  return {
    enabled: data.enabled === true,
    testMode: data.testMode !== false,
    callbackBaseUrl: clean(data.callbackBaseUrl, 1000),
    platforms: data.platforms || {}
  };
}

export async function updateSocialConfig(patch, actorUid) {
  const current = await getSocialConfig();
  const platforms = { ...(current.platforms || {}) };
  for (const id of Object.keys(PLATFORMS)) {
    if (patch.platforms?.[id]) {
      const p = patch.platforms[id];
      platforms[id] = {
        enabled: p.enabled === true,
        clientId: clean(p.clientId, 500),
        scopes: Array.isArray(p.scopes) ? p.scopes.map(x => clean(x, 200)).filter(Boolean).slice(0, 40) : PLATFORMS[id].defaultScopes
      };
    }
  }
  await configRef().set({
    enabled: patch.enabled == null ? current.enabled : patch.enabled === true,
    testMode: patch.testMode == null ? current.testMode : patch.testMode === true,
    callbackBaseUrl: patch.callbackBaseUrl == null ? current.callbackBaseUrl : clean(patch.callbackBaseUrl, 1000),
    platforms,
    updatedBy: actorUid,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  return getSocialConfig();
}

export async function saveSocialClientSecret(platform, value, actorUid) {
  if (!PLATFORMS[platform]) throw new Error("unsupported_social_platform");
  const providerId = platform === "facebook" || platform === "instagram"
    ? "meta_app_secret"
    : platform === "youtube"
      ? "youtube_client_secret"
      : platform === "tiktok"
        ? "tiktok_client_secret"
        : "x_client_secret";
  await setProviderSecret(providerId, clean(value, 4096), actorUid);
  return { ok: true, platform };
}

export async function beginSocialOAuth(platform, state, redirectUri, codeChallenge = null) {
  const config = await getSocialConfig();
  const p = PLATFORMS[platform];
  if (!p) throw new Error("unsupported_social_platform");
  const pc = config.platforms?.[platform] || {};
  if (!pc.enabled || !pc.clientId) throw new Error("social_platform_not_configured");
  const scopes = Array.isArray(pc.scopes) && pc.scopes.length ? pc.scopes : p.defaultScopes;
  const params = new URLSearchParams({
    client_id: pc.clientId,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: scopes.join(" "),
    state
  });
  if (platform === "youtube") params.set("access_type", "offline");
  if (platform === "x") { params.set("code_challenge", codeChallenge || ""); params.set("code_challenge_method", "S256"); }
  return p.authorizationUrl + "?" + params.toString();
}

export async function exchangeSocialOAuth(platform, code, redirectUri, codeVerifier = null) {
  const config = await getSocialConfig();
  const p = PLATFORMS[platform];
  const pc = config.platforms?.[platform] || {};
  if (!p || !pc.clientId) throw new Error("social_platform_not_configured");
  const secretId = platform === "facebook" || platform === "instagram" ? "meta_app_secret" : platform === "youtube" ? "youtube_client_secret" : platform === "tiktok" ? "tiktok_client_secret" : "x_client_secret";
  const secret = await getProviderSecret(secretId);
  if (!secret) throw new Error("social_client_secret_missing");
  const body = new URLSearchParams({
    client_id: pc.clientId,
    client_secret: secret,
    code,
    redirect_uri: redirectUri,
    grant_type: "authorization_code"
  });
  if (platform === "x" && codeVerifier) body.set("code_verifier", codeVerifier);
  const response = await fetch(p.tokenUrl, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
  const raw = await response.text();
  if (!response.ok) throw new Error("social_token_exchange_failed:" + raw.slice(0, 500));
  return JSON.parse(raw);
}

export async function saveSocialAccount(platform, tokenData, metadata = {}, actorUid) {
  const id = crypto.randomUUID();
  await accountRef(id).set({
    id,
    platform,
    uid: actorUid,
    token: tokenData.access_token || tokenData.accessToken || null,
    refreshToken: tokenData.refresh_token || null,
    expiresIn: tokenData.expires_in || null,
    metadata,
    status: "connected",
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });
  return { id, platform, status: "connected", metadata };
}

export async function listSocialAccounts(uid) {
  const snap = await getFirestore().collection(ACCOUNTS).where("uid", "==", uid).limit(50).get();
  return snap.docs.map(d => {
    const x = d.data();
    return { id: d.id, platform: x.platform, metadata: x.metadata || {}, status: x.status || "unknown", updatedAt: x.updatedAt?.toDate?.()?.toISOString() || null };
  });
}
