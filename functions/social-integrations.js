import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getProviderSecret, setProviderSecret, encryptSecret, decryptSecret } from "./secret-store.js";
import { requireBusinessAccess, requireBusinessPlan } from "./business-phases.js";
import { assertMediaRights } from "./media-rights.js";

const CANONICAL_CALLBACK_BASE_URL = "https://europe-west1-bqsqwadai.cloudfunctions.net/bqSqwadApi";
const CANONICAL_CALLBACK_URL = CANONICAL_CALLBACK_BASE_URL + "/social/oauth/callback";
const CANONICAL_WEB_ORIGIN = "https://bqsqwadai.com";

const DEFAULT_CLIENT_IDS = {
  youtube: "848359744340-jeevhbunr9ari8dh7632gi8btli90vd0.apps.googleusercontent.com",
  x: ""
};

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
    defaultScopes: ["https://www.googleapis.com/auth/youtube", "https://www.googleapis.com/auth/youtube.force-ssl", "https://www.googleapis.com/auth/youtube.readonly", "https://www.googleapis.com/auth/youtube.upload"]
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
    callbackBaseUrl: CANONICAL_CALLBACK_URL,
    javascriptOrigin: CANONICAL_WEB_ORIGIN,
    platforms: Object.fromEntries(Object.entries(PLATFORMS).map(([id, p]) => {
      const configured = data.platforms?.[id] || {};
      return [id, {
        enabled: configured.enabled === true,
        clientId: clean(configured.clientId || DEFAULT_CLIENT_IDS[id] || "", 500),
        scopes: Array.isArray(configured.scopes) && configured.scopes.length ? configured.scopes : p.defaultScopes
      }];
    }))
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
  let scopes = Array.isArray(pc.scopes) && pc.scopes.length ? pc.scopes : p.defaultScopes;
  if (platform === "x") {
    const allowed = new Set(["tweet.read", "tweet.write", "users.read", "offline.access", "media.write", "like.read", "like.write"]);
    scopes = scopes.map(x => clean(x, 80)).filter(x => allowed.has(x));
    for (const required of ["tweet.read", "tweet.write", "users.read", "offline.access"]) {
      if (!scopes.includes(required)) scopes.push(required);
    }
  }
  const params = new URLSearchParams({
    client_id: pc.clientId,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: scopes.join(" "),
    state
  });
  if (platform === "youtube") params.set("access_type", "offline");
  if (platform === "x") {
    if (!codeChallenge) throw new Error("x_pkce_challenge_missing");
    params.set("code_challenge", codeChallenge);
    params.set("code_challenge_method", "S256");
  }
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
  const headers = { "Content-Type": "application/x-www-form-urlencoded" };
  if (platform === "x") {
    headers.Authorization = "Basic " + Buffer.from(pc.clientId + ":" + secret).toString("base64");
    body.delete("client_secret");
  }
  const response = await fetch(p.tokenUrl, { method: "POST", headers, body });
  const raw = await response.text();
  if (!response.ok) {
    const detail = raw.replace(/[\r\n]+/g, " ").slice(0, 700);
    throw new Error((platform === "x" ? "x_token_exchange_failed:" : "social_token_exchange_failed:") + detail);
  }
  let parsed;
  try { parsed = JSON.parse(raw); } catch { throw new Error("social_token_exchange_invalid_json"); }
  if (platform === "x") {
    const granted = String(parsed.scope || "").split(/\s+/).filter(Boolean);
    if (!granted.includes("tweet.write")) throw new Error("x_write_scope_missing");
    if (!granted.includes("users.read")) throw new Error("x_users_scope_missing");
    if (!granted.includes("offline.access")) throw new Error("x_offline_access_missing_reconnect_required");
    const account = await validateXAccessToken(String(parsed.access_token || ""));
    parsed.xAccount = {
      id: String(account.id),
      name: String(account.name || ""),
      username: String(account.username || "")
    };
  }
  return parsed;
}

async function refreshXAccessToken(account) {
  const secret = await getProviderSecret("x_client_secret");
  const config = await getSocialConfig();
  const clientId = String(config.platforms?.x?.clientId || "");
  const refreshToken = account.refreshTokenSecret?.ciphertext ? decryptSecret(account.refreshTokenSecret) : "";
  if (!secret) throw new Error("x_client_secret_missing");
  if (!clientId) throw new Error("x_client_id_missing");
  if (!refreshToken) throw new Error("x_refresh_token_missing");

  const refresh = await fetch("https://api.x.com/2/oauth2/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Authorization": "Basic " + Buffer.from(clientId + ":" + secret).toString("base64")
    },
    body: new URLSearchParams({
      refresh_token: refreshToken,
      grant_type: "refresh_token",
      client_id: clientId
    })
  });
  const refreshedRaw = await refresh.text();
  let refreshed = {};
  try { refreshed = JSON.parse(refreshedRaw); } catch {}
  if (!refresh.ok || !refreshed.access_token) {
    throw new Error("x_token_refresh_failed:" + refreshedRaw.replace(/[\r\n]+/g, " ").slice(0, 700));
  }
  const accessToken = String(refreshed.access_token);
  const nextRefreshToken = String(refreshed.refresh_token || refreshToken);
  const expiresAt = new Date(Date.now() + Number(refreshed.expires_in || 7200) * 1000);
  return { accessToken, refreshToken: nextRefreshToken, expiresAt };
}

async function validateXAccessToken(accessToken) {
  const response = await fetch("https://api.x.com/2/users/me?user.fields=id,name,username", {
    headers: { Authorization: "Bearer " + accessToken }
  });
  const raw = await response.text();
  let parsed = {};
  try { parsed = JSON.parse(raw); } catch {}
  if (!response.ok || !parsed?.data?.id) {
    const detail = raw.replace(/[\r\n]+/g, " ").slice(0, 700);
    throw new Error("x_account_validation_failed:" + detail);
  }
  return parsed.data;
}

export async function saveSocialAccount(platform, tokenData, metadata = {}, actorUid) {
  const id = crypto.randomUUID();
  const workspaceId = clean(metadata.workspaceId, 120);
  const organizationId = clean(metadata.organizationId, 120);
  if (organizationId) {
    const orgSnap = await getFirestore().collection("organizationWorkspaces").doc(organizationId).get();
    const member = (orgSnap.data()?.members || []).find(item => String(item.id || "") === String(actorUid) && item.status !== "inactive");
    if (!orgSnap.exists || !member || !["owner", "admin", "editor"].includes(String(member.role || ""))) {
      throw new Error("organization_social_access_denied");
    }
  } else if (workspaceId) {
    await requireBusinessAccess(actorUid, workspaceId, "manager");
    await requireBusinessPlan(actorUid, workspaceId, "channels");
  }
  const expiresIn = Number(tokenData.expires_in || 0);
  const accessToken = String(tokenData.access_token || tokenData.accessToken || "");
  const refreshToken = String(tokenData.refresh_token || "");
  if (!accessToken) throw new Error("social_access_token_missing");
  await accountRef(id).set({
    id,
    platform,
    uid: actorUid,
    workspaceId: workspaceId || null,
    accessTokenSecret: encryptSecret(accessToken),
    refreshTokenSecret: refreshToken ? encryptSecret(refreshToken) : null,
    expiresIn: expiresIn || null,
    expiresAt: expiresIn ? new Date(Date.now() + expiresIn * 1000) : null,
    grantedScopes: String(tokenData.scope || "").split(/\s+/).filter(Boolean).slice(0, 80),
    metadata: {
      ...metadata,
      ...(platform === "x" && tokenData.xAccount ? { xAccount: tokenData.xAccount } : {})
    },
    status: "connected",
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });
  return { id, platform, status: "connected", metadata };
}


async function getValidXToken(account) {
  let token = account.accessTokenSecret?.ciphertext ? decryptSecret(account.accessTokenSecret) : clean(account.token, 10000);
  const expiresAt = account.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (expiresAt && expiresAt <= Date.now() + 60000) {
    if (!account.refreshTokenSecret?.ciphertext) throw new Error("x_refresh_token_missing");
    const refreshed = await refreshXAccessToken(account);
    return { token: refreshed.accessToken, patch: {
      accessTokenSecret: encryptSecret(refreshed.accessToken),
      refreshTokenSecret: encryptSecret(refreshed.refreshToken),
      expiresAt: refreshed.expiresAt,
      updatedAt: FieldValue.serverTimestamp()
    }};
  }
  return { token, patch: null };
}

function requireXScope(account, scope) {
  const scopes = new Set((account.grantedScopes || []).map(String));
  if (scopes.size && !scopes.has(scope)) throw new Error("x_" + scope.replace(".", "_") + "_missing_reconnect_required");
}

export async function manageXAction(uid, accountId, input = {}) {
  const ref = accountRef(clean(accountId, 160));
  const snap = await ref.get();
  if (!snap.exists || clean(snap.data()?.uid, 160) !== uid) throw new Error("social_account_not_found");
  const account = snap.data();
  if (clean(account.platform, 40) !== "x") throw new Error("x_account_required");
  const workspaceId = clean(input.workspaceId || account.workspaceId, 120);
  if (!workspaceId) throw new Error("business_workspace_required");
  await requireBusinessAccess(uid, workspaceId, "manager");
  await requireBusinessPlan(uid, workspaceId, "channels");
  const action = clean(input.action, 40);
  const x = await getValidXToken(account);
  if (x.patch) await ref.set(x.patch, { merge: true });
  const token = x.token;
  const userId = String(account.metadata?.xAccount?.id || "");
  if (!userId) throw new Error("x_user_id_missing");
  let response;
  if (action === "reply") {
    requireXScope(account, "tweet.write");
    const postId = clean(input.postId, 80), text = clean(input.text, 5000);
    if (!postId || !text) throw new Error("x_reply_requires_post_and_text");
    response = await fetch("https://api.x.com/2/tweets", { method:"POST", headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"}, body:JSON.stringify({text,reply:{in_reply_to_tweet_id:postId}}) });
  } else if (action === "delete") {
    requireXScope(account, "tweet.write");
    const postId = clean(input.postId, 80);
    if (!postId) throw new Error("x_delete_requires_post");
    response = await fetch("https://api.x.com/2/tweets/"+encodeURIComponent(postId), {method:"DELETE",headers:{Authorization:"Bearer "+token}});
  } else if (action === "like" || action === "unlike") {
    requireXScope(account, "like.write");
    const postId = clean(input.postId, 80);
    if (!postId) throw new Error("x_"+action+"_requires_post");
    response = action === "like"
      ? await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/likes",{method:"POST",headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({tweet_id:postId})})
      : await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/likes/"+encodeURIComponent(postId),{method:"DELETE",headers:{Authorization:"Bearer "+token}});
  } else if (action === "repost" || action === "unrepost") {
    requireXScope(account, "tweet.write");
    const postId = clean(input.postId, 80);
    if (!postId) throw new Error("x_"+action+"_requires_post");
    response = action === "repost"
      ? await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/retweets",{method:"POST",headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({tweet_id:postId})})
      : await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/retweets/"+encodeURIComponent(postId),{method:"DELETE",headers:{Authorization:"Bearer "+token}});
  } else {
    throw new Error("unsupported_x_action");
  }
  const raw=await response.text(); let parsed={}; try{parsed=JSON.parse(raw);}catch{}
  if(!response.ok) throw new Error("x_"+action+"_failed:"+raw.replace(/[\r\n]+/g," ").slice(0,700));
  return {ok:true,action,data:parsed?.data||parsed};
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
  let token = account.accessTokenSecret?.ciphertext
    ? decryptSecret(account.accessTokenSecret)
    : clean(account.token, 10000);
  const text = clean(input.text, 5000);
  if (!token) throw new Error("social_account_token_missing");
  const xExpiresAt = account.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (platform === "x" && xExpiresAt && xExpiresAt <= Date.now() + 60000 && !account.refreshTokenSecret?.ciphertext) {
    throw new Error("x_refresh_token_missing");
  }
  if (platform === "x" && account.refreshTokenSecret?.ciphertext && xExpiresAt && xExpiresAt <= Date.now() + 60000) {
    const refreshed = await refreshXAccessToken(account);
    token = refreshed.accessToken;
    await ref.set({
      accessTokenSecret: encryptSecret(refreshed.accessToken),
      refreshTokenSecret: encryptSecret(refreshed.refreshToken),
      expiresAt: refreshed.expiresAt,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  }

  if (platform === "youtube" && account.refreshTokenSecret?.ciphertext && account.expiresAt?.toDate?.()?.getTime?.() && account.expiresAt.toDate().getTime() <= Date.now() + 60000) {
    const secret = await getProviderSecret("youtube_client_secret");
    if (secret) {
      const refresh = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: String((await getSocialConfig()).platforms?.youtube?.clientId || ""),
          client_secret: secret,
          refresh_token: account.refreshTokenSecret?.ciphertext ? decryptSecret(account.refreshTokenSecret) : String(account.refreshToken || ""),
          grant_type: "refresh_token"
        })
      });
      const refreshed = await refresh.json();
      if (refresh.ok && refreshed.access_token) {
        token = String(refreshed.access_token);
        await ref.set({ accessTokenSecret: encryptSecret(token), expiresAt: new Date(Date.now() + Number(refreshed.expires_in || 3600) * 1000), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
      }
    }
  }
  if (!text && !input.mediaUrl) throw new Error("social_content_required");
  if (input.mediaUrl) await assertMediaRights(uid, { rightsId: input.rightsId, workspaceId });
  const metadata = account.metadata || {};
  let response;
  if (platform === "x") {
    const scopes = new Set((account.grantedScopes || []).map(String));
    if (scopes.size && !scopes.has("tweet.write")) throw new Error("x_write_scope_missing_reconnect_required");
    response = await fetch("https://api.x.com/2/tweets", {
      method: "POST",
      headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
      body: JSON.stringify({ text: text || "" })
    });
    if (response.status === 401 && account.refreshTokenSecret?.ciphertext) {
      const refreshed = await refreshXAccessToken(account);
      token = refreshed.accessToken;
      await ref.set({
        accessTokenSecret: encryptSecret(refreshed.accessToken),
        refreshTokenSecret: encryptSecret(refreshed.refreshToken),
        expiresAt: refreshed.expiresAt,
        updatedAt: FieldValue.serverTimestamp(),
        status: "connected"
      }, { merge: true });
      response = await fetch("https://api.x.com/2/tweets", {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({ text: text || "" })
      });
    }
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
    const declaredLength = Number(media.headers.get("content-length") || 0);
    if (declaredLength > 512 * 1024 * 1024) throw new Error("youtube_video_too_large_for_business_uploader");
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
  } else {
    throw new Error("social_publish_not_supported_for_platform");
  }
  const raw = await response.text();
  if (!response.ok) {
    const detail = raw.replace(/[\r\n]+/g, " ").slice(0, 900);
    throw new Error((platform === "x" ? "x_publish_failed:" : "social_publish_failed:") + detail);
  }
  let result = {};
  try { result = JSON.parse(raw); } catch {}
  const publishRef = getFirestore().collection("socialPublishJobs").doc();
  await publishRef.set({
    uid, accountId, workspaceId, platform, status: "published", text, result,
    createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
  });
  return { ok: true, jobId: publishRef.id, platform, status: "published", result };
}

export async function disconnectSocialAccount(uid, accountId) {
  const ref = accountRef(clean(accountId, 160));
  const snap = await ref.get();
  if (!snap.exists || clean(snap.data()?.uid, 160) !== uid) throw new Error("social_account_not_found");
  const account = snap.data();
  const platform = clean(account.platform, 40);
  let revokeError = null;
  if (platform === "x" && account.accessTokenSecret?.ciphertext) {
    try {
      const secret = await getProviderSecret("x_client_secret");
      const config = await getSocialConfig();
      const clientId = String(config.platforms?.x?.clientId || "");
      const token = decryptSecret(account.accessTokenSecret);
      if (secret && clientId && token) {
        const revoke = await fetch("https://api.x.com/2/oauth2/revoke", {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "Authorization": "Basic " + Buffer.from(clientId + ":" + secret).toString("base64")
          },
          body: new URLSearchParams({ token, token_type_hint: "access_token", client_id: clientId })
        });
        if (!revoke.ok) revokeError = "x_remote_revoke_failed";
        const refreshToken = account.refreshTokenSecret?.ciphertext ? decryptSecret(account.refreshTokenSecret) : "";
        if (refreshToken) {
          const refreshRevoke = await fetch("https://api.x.com/2/oauth2/revoke", {
            method: "POST",
            headers: {
              "Content-Type": "application/x-www-form-urlencoded",
              "Authorization": "Basic " + Buffer.from(clientId + ":" + secret).toString("base64")
            },
            body: new URLSearchParams({ token: refreshToken, token_type_hint: "refresh_token", client_id: clientId })
          });
          if (!refreshRevoke.ok) revokeError = "x_remote_revoke_failed";
        }
      }
    } catch {
      revokeError = "x_remote_revoke_failed";
    }
  }
  await ref.delete();
  await getFirestore().collection("socialAccountAudit").add({
    uid, accountId, platform, action: "disconnect",
    remoteRevocationAttempted: platform === "x",
    remoteRevocationFailed: Boolean(revokeError),
    createdAt: FieldValue.serverTimestamp()
  });
  return { ok: true, disconnected: true, platform, remoteRevocationFailed: Boolean(revokeError) };
}

export async function listSocialAccounts(uid, workspaceId) {
  const query = getFirestore().collection(ACCOUNTS).where("uid", "==", uid);
  const snap = workspaceId ? await query.where("workspaceId", "==", String(workspaceId)).limit(50).get() : await query.limit(50).get();
  return snap.docs.map(d => {
    const x = d.data();
    return { id: d.id, platform: x.platform, metadata: x.metadata || {}, status: x.status || "unknown", updatedAt: x.updatedAt?.toDate?.()?.toISOString() || null };
  });
}
