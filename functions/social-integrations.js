import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getProviderSecret, setProviderSecret } from "./secret-store.js";
import { requireBusinessAccess, requireBusinessPlan } from "./business-phases.js";

const CANONICAL_CALLBACK_BASE_URL = "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi";
const CANONICAL_CALLBACK_URL = CANONICAL_CALLBACK_BASE_URL + "/social/oauth/callback";
const CANONICAL_WEB_ORIGIN = "https://bqsqwadai.com";

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
    callbackBaseUrl: CANONICAL_CALLBACK_URL,\n    javascriptOrigin: CANONICAL_WEB_ORIGIN,
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
    callbackBaseUrl: CANONICAL_CALLBACK_URL,
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
  const workspaceId = clean(metadata.workspaceId, 120);
  if (workspaceId) {
    await requireBusinessAccess(actorUid, workspaceId, "manager");
    await requireBusinessPlan(actorUid, workspaceId, "channels");
  }
  const expiresIn = Number(tokenData.expires_in || 0);
  await accountRef(id).set({
    id,
    platform,
    uid: actorUid,
    workspaceId: workspaceId || null,
    token: tokenData.access_token || tokenData.accessToken || null,
    refreshToken: tokenData.refresh_token || null,
    expiresIn: expiresIn || null,
    expiresAt: expiresIn ? new Date(Date.now() + expiresIn * 1000) : null,
    metadata,

    status: "connected",
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });
  return { id, platform, status: "connected", metadata };
}


export async function publishSocialPost(uid, accountId, input = {}) {
  const ref = accountRef(clean(accountId, 160));
  const snap = await ref.get();
  if (!snap.exists || clean(snap.data()?.uid, 160) !== uid) throw new Error("social_account_not_found");
  const account = snap.data();
  const workspaceId = clean(input.workspaceId || account.workspaceId, 120);
  if (workspaceId) {
    await requireBusinessAccess(uid, workspaceId, "manager");
    await requireBusinessPlan(uid, workspaceId, "channels");
  } else {
    throw new Error("business_workspace_required");
  }
  const platform = clean(account.platform, 40);
  let token = clean(account.token, 10000);
  const text = clean(input.text, 5000);
  if (!token) throw new Error("social_account_token_missing");
  if (platform === "youtube" && account.refreshToken && account.expiresAt?.toDate?.()?.getTime?.() && account.expiresAt.toDate().getTime() <= Date.now()) {
    const secret = await getProviderSecret("youtube_client_secret");
    if (secret) {
      const refresh = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: String((await getSocialConfig()).platforms?.youtube?.clientId || ""),
          client_secret: secret,
          refresh_token: String(account.refreshToken),
          grant_type: "refresh_token"
        })
      });
      const refreshed = await refresh.json();
      if (refresh.ok && refreshed.access_token) {
        token = String(refreshed.access_token);
        await ref.set({ token, expiresAt: new Date(Date.now() + Number(refreshed.expires_in || 3600) * 1000), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      }
    }
  }
  if (!text && !input.mediaUrl) throw new Error("social_content_required");
  const metadata = account.metadata || {};
  let response;
  if (platform === "x") {
    response = await fetch("https://api.x.com/2/tweets", {
      method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify({ text: text || "" })
    });
  } else if (platform === "facebook") {
    const pageId = clean(input.pageId || metadata.pageId, 120);
    if (!pageId) throw new Error("facebook_page_id_required");
    response = await fetch("https://graph.facebook.com/v24.0/" + encodeURIComponent(pageId) + "/feed", {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ message: text, access_token: token })
    });
  } else if (platform === "instagram") {
    const igId = clean(input.instagramAccountId || metadata.instagramAccountId, 120);
    const mediaUrl = clean(input.mediaUrl, 2000);
    if (!igId || !mediaUrl) throw new Error("instagram_account_and_media_required");
    const create = await fetch("https://graph.facebook.com/v24.0/" + encodeURIComponent(igId) + "/media", {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ image_url: mediaUrl, caption: text, access_token: token })
    });
    const createRaw = await create.text();
    if (!create.ok) throw new Error("instagram_media_create_failed:" + createRaw.slice(0, 500));
    const creation = JSON.parse(createRaw);
    response = await fetch("https://graph.facebook.com/v24.0/" + encodeURIComponent(igId) + "/media_publish", {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ creation_id: creation.id, access_token: token })
    });
  } else if (platform === "youtube") {
    const mediaUrl = clean(input.mediaUrl, 4000);
    if (!mediaUrl) throw new Error("youtube_video_required");
    const title = clean(input.title || text.split("\n")[0] || "BQ Sqwad AI Business Video", 100);
    const description = clean(input.description || text, 5000);
    const privacyStatus = ["private","unlisted","public"].includes(String(input.privacyStatus || "")) ? String(input.privacyStatus) : "private";
    const media = await fetch(mediaUrl);
    if (!media.ok) throw new Error("youtube_media_fetch_failed");
    const contentType = media.headers.get("content-type") || "video/*";
    if (!contentType.startsWith("video/") && contentType !== "application/octet-stream") throw new Error("youtube_video_content_type_required");
    const bytes = new Uint8Array(await media.arrayBuffer());
    const metadataBody = JSON.stringify({
      snippet: { title, description, categoryId: clean(input.categoryId || "22", 20) },
      status: { privacyStatus }
    });
    const init = await fetch("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + token,
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Type": contentType,
        "X-Upload-Content-Length": String(bytes.byteLength)
      },
      body: metadataBody
    });
    const location = init.headers.get("location");
    if (!init.ok || !location) {
      const initRaw = await init.text();
      throw new Error("youtube_upload_session_failed:" + initRaw.slice(0, 500));
    }
    response = await fetch(location, {
      method: "PUT",
      headers: { "Content-Type": contentType, "Content-Length": String(bytes.byteLength) },
      body: bytes
    });
  }
  } else {
    throw new Error("social_publish_not_supported_for_platform");
  }
  const raw = await response.text();
  if (!response.ok) throw new Error("social_publish_failed:" + raw.slice(0, 700));
  let result = {};
  try { result = JSON.parse(raw); } catch {}
  const publishRef = getFirestore().collection("socialPublishJobs").doc();
  await publishRef.set({
    uid, accountId, workspaceId, platform, status: "published", text, result,
    createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
  });
  return { ok: true, jobId: publishRef.id, platform, status: "published", result };
}

export async function listSocialAccounts(uid) {
  const snap = await getFirestore().collection(ACCOUNTS).where("uid", "==", uid).limit(50).get();
  return snap.docs.map(d => {
    const x = d.data();
    return { id: d.id, platform: x.platform, metadata: x.metadata || {}, status: x.status || "unknown", updatedAt: x.updatedAt?.toDate?.()?.toISOString() || null };
  });
}
