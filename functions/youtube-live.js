import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { decryptSecret } from "./secret-store.js";
import { assertMediaRights } from "./media-rights.js";

const ACCOUNTS = "socialAccounts";
const YOUTUBE_API = "https://www.googleapis.com/youtube/v3";

function clean(v, n = 500) { return String(v ?? "").trim().slice(0, n); }

async function youtubeToken(uid, accountId) {
  const ref = getFirestore().collection(ACCOUNTS).doc(clean(accountId, 160));
  const snap = await ref.get();
  if (!snap.exists) throw new Error("social_account_not_found");
  const account = snap.data() || {};
  if (String(account.uid || "") !== String(uid) || account.platform !== "youtube") throw new Error("youtube_account_not_found");
  let token = account.accessTokenSecret?.ciphertext ? decryptSecret(account.accessTokenSecret) : clean(account.token, 10000);
  if (!token) throw new Error("youtube_access_token_missing");
  return { ref, account, token };
}

async function youtubeRequest(uid, accountId, path, init = {}) {
  const { ref, account, token } = await youtubeToken(uid, accountId);
  const headers = { ...(init.headers || {}), Authorization: "Bearer " + token };
  let response = await fetch(YOUTUBE_API + path, { ...init, headers });
  if (response.status === 401 && account.refreshTokenSecret?.ciphertext) {
    throw new Error("youtube_token_refresh_required");
  }
  const raw = await response.text();
  let body = {};
  try { body = JSON.parse(raw); } catch {}
  if (!response.ok) throw new Error("youtube_api_error:" + clean(body.error?.message || raw, 700));
  return body;
}

export async function getYouTubeChannel(uid, accountId) {
  return youtubeRequest(uid, accountId, "/channels?part=id,snippet,status,contentDetails&mine=true");
}

export async function createYouTubeLiveSession(uid, accountId, input = {}) {
  await assertMediaRights(uid, { rightsId: input.rightsId, workspaceId: input.workspaceId });
  const title = clean(input.title, 100);
  if (!title) throw new Error("youtube_live_title_required");
  const scheduledStartTime = new Date(input.scheduledStartTime || Date.now() + 60000);
  if (!Number.isFinite(scheduledStartTime.getTime())) throw new Error("youtube_live_start_time_invalid");
  const privacyStatus = ["private", "unlisted", "public"].includes(String(input.privacyStatus)) ? String(input.privacyStatus) : "private";
  const categoryId = clean(input.categoryId || "22", 20);

  const broadcast = await youtubeRequest(uid, accountId, "/liveBroadcasts?part=snippet,status,contentDetails", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      snippet: {
        title,
        description: clean(input.description, 5000),
        scheduledStartTime: scheduledStartTime.toISOString(),
        categoryId
      },
      status: { privacyStatus },
      contentDetails: {
        enableAutoStart: input.enableAutoStart !== false,
        enableAutoStop: input.enableAutoStop !== false,
        enableDvr: input.enableDvr !== false,
        recordFromStart: input.recordFromStart !== false,
        monitorStream: { enableMonitorStream: true }
      }
    })
  });

  const stream = await youtubeRequest(uid, accountId, "/liveStreams?part=snippet,cdn,contentDetails,status", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      snippet: { title: title + " — BQ Sqwad AI" },
      cdn: {
        frameRate: ["30fps", "60fps"].includes(String(input.frameRate)) ? String(input.frameRate) : "30fps",
        ingestionType: "rtmp",
        resolution: ["240p","360p","480p","720p","1080p","1440p","2160p"].includes(String(input.resolution)) ? String(input.resolution) : "1080p"
      },
      contentDetails: { isReusable: input.reusable === true }
    })
  });

  const bound = await youtubeRequest(uid, accountId, "/liveBroadcasts/bind?id=" + encodeURIComponent(broadcast.id) + "&streamId=" + encodeURIComponent(stream.id) + "&part=id,snippet,status,contentDetails", { method: "POST" });

  const db = getFirestore();
  const sessionRef = db.collection("organizationProductionLiveSessions").doc();
  const ingestion = stream.cdn?.ingestionInfo || {};
  await sessionRef.set({
    id: sessionRef.id,
    uid,
    accountId,
    platform: "youtube",
    broadcastId: broadcast.id,
    streamId: stream.id,
    liveChatId: bound.snippet?.liveChatId || broadcast.snippet?.liveChatId || null,
    ingestionAddress: ingestion.ingestionAddress || null,
    streamName: ingestion.streamName || null,
    title,
    privacyStatus,
    status: "created",
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });

  return {
    sessionId: sessionRef.id,
    platform: "youtube",
    broadcastId: broadcast.id,
    streamId: stream.id,
    liveChatId: bound.snippet?.liveChatId || broadcast.snippet?.liveChatId || null,
    ingestionAddress: ingestion.ingestionAddress || null,
    streamName: ingestion.streamName || null,
    status: "created"
  };
}

export async function transitionYouTubeLive(uid, accountId, broadcastId, status) {
  const allowed = new Set(["testing", "live", "complete"]);
  if (!allowed.has(String(status))) throw new Error("youtube_live_transition_invalid");
  const result = await youtubeRequest(uid, accountId,
    "/liveBroadcasts/transition?broadcastStatus=" + encodeURIComponent(status) + "&id=" + encodeURIComponent(broadcastId) + "&part=id,snippet,status,contentDetails",
    { method: "POST" });
  await getFirestore().collection("organizationProductionLiveSessions")
    .where("uid","==",uid).where("accountId","==",accountId).where("broadcastId","==",broadcastId).limit(1).get()
    .then(async snap => { if (!snap.empty) await snap.docs[0].ref.set({ status: String(status), updatedAt: FieldValue.serverTimestamp() }, { merge:true }); });
  return result;
}

export async function getYouTubeLiveStatus(uid, accountId, broadcastId) {
  return youtubeRequest(uid, accountId, "/liveBroadcasts?part=id,snippet,status,contentDetails&id=" + encodeURIComponent(broadcastId));
}
