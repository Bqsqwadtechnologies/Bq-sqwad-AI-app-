import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getYouTubeLiveStatus, transitionYouTubeLive } from "./youtube-live.js";
import { getVerifiedMediaRights } from "./media-rights.js";

const SESSIONS = "organizationProductionLiveSessions";
const EVENTS = "organizationProductionEvents";

function clean(v, n = 500) { return String(v ?? "").trim().slice(0, n); }

export async function enforceProtectedContentPolicy(uid, input = {}) {
  const sessionId = clean(input.sessionId, 160);
  if (!sessionId) throw new Error("live_session_required");

  const ref = getFirestore().collection(SESSIONS).doc(sessionId);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("live_session_not_found");

  const session = snap.data() || {};
  if (String(session.uid || "") !== String(uid)) throw new Error("live_session_forbidden");

  const protectedDetected = input.protectedBroadcastDetected === true;
  const confidence = Math.max(0, Math.min(1, Number(input.confidence ?? 0)));
  const detectionSource = clean(input.detectionSource, 80) || "runtime_content_analysis";
  const destination = clean(session.platform, 40);

  let rightsVerified = false;
  if (input.rightsId) {
    try {
      await getVerifiedMediaRights(uid, input.rightsId, input.workspaceId || null);
      rightsVerified = true;
    } catch {}
  } else if (session.rightsId) {
    try {
      await getVerifiedMediaRights(uid, session.rightsId, session.workspaceId || null);
      rightsVerified = true;
    } catch {}
  }

  if (!protectedDetected || rightsVerified) {
    await ref.set({
      lastPolicyCheck: {
        protectedBroadcastDetected: protectedDetected,
        confidence,
        detectionSource,
        rightsVerified,
        action: "allow",
        checkedAt: FieldValue.serverTimestamp()
      },
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
    return { allowed: true, stopped: false, rightsVerified };
  }

  if (destination === "youtube" && session.accountId && session.broadcastId) {
    try {
      await transitionYouTubeLive(uid, session.accountId, session.broadcastId, "complete");
    } catch {}
  }

  await ref.set({
    status: "stopped_policy_violation",
    stopReason: "protected_broadcast_without_verified_rights",
    lastPolicyCheck: {
      protectedBroadcastDetected: true,
      confidence,
      detectionSource,
      rightsVerified: false,
      action: "stop",
      checkedAt: FieldValue.serverTimestamp()
    },
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });

  const eventRef = getFirestore().collection(EVENTS).doc();
  await eventRef.set({
    uid,
    eventId: eventRef.id,
    type: "protected_content_auto_stop",
    status: "blocked",
    destinationId: session.destinationId || null,
    message: "Live stream stopped because protected broadcast content was detected without verified authorization.",
    metadata: {
      sessionId,
      platform: destination,
      confidence,
      detectionSource,
      rightsVerified: false
    },
    createdAt: FieldValue.serverTimestamp()
  });

  return {
    allowed: false,
    stopped: true,
    reason: "protected_broadcast_without_verified_rights",
    message: "Live stream stopped because protected broadcast content was detected and no verified authorization was found."
  };
}
