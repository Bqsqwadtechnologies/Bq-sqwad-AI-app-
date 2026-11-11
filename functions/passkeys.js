import { generateRegistrationOptions, verifyRegistrationResponse, generateAuthenticationOptions, verifyAuthenticationResponse } from "@simplewebauthn/server";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import crypto from "node:crypto";

const RP_ID = "bqsqwadai.com";
const RP_NAME = "BQ Sqwad AI";
const WEB_ORIGIN = "https://bqsqwadai.com";
const CHALLENGE_TTL_MS = 10 * 60 * 1000;

function origins() {
  const configured = String(process.env.BQ_PASSKEY_ALLOWED_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  return configured.length ? configured : [WEB_ORIGIN];
}

function challengeRef(uid, purpose) {
  return getFirestore().collection("passkeyChallenges").doc(uid + "_" + purpose);
}

function credentialRef(id) {
  return getFirestore().collection("passkeys").doc(id);
}

function validCredentialId(id) {
  return /^[A-Za-z0-9_-]{16,1024}$/.test(String(id || ""));
}

function challengeExpiry(data) {
  return data?.expiresAt?.toDate?.()?.getTime?.() || Date.parse(String(data?.expiresAt || ""));
}

export async function passkeyStatus(uid) {
  const snap = await getFirestore().collection("passkeys").where("uid", "==", uid).limit(100).get();
  return {
    count: snap.size,
    items: snap.docs.map(d => {
      const x = d.data() || {};
      return { id: d.id, createdAt: x.createdAt?.toDate?.()?.toISOString?.() || "", deviceType: String(x.deviceType || "unknown"), backedUp: x.backedUp === true };
    })
  };
}

export async function registrationOptions(uid) {
  const user = await getAuth().getUser(uid);
  const existing = await getFirestore().collection("passkeys").where("uid", "==", uid).limit(100).get();
  const options = await generateRegistrationOptions({
    rpName: RP_NAME,
    rpID: RP_ID,
    userName: user.email || uid,
    userDisplayName: user.displayName || user.email || "BQ Sqwad AI user",
    userID: new TextEncoder().encode(uid),
    attestationType: "none",
    excludeCredentials: existing.docs.map(d => ({ id: d.id, transports: d.data()?.transports || [] })),
    authenticatorSelection: {
      residentKey: "required",
      userVerification: "required",
      authenticatorAttachment: "platform"
    },
    supportedAlgorithmIDs: [-7, -257]
  });
  await challengeRef(uid, "register").set({
    uid,
    purpose: "register",
    challenge: options.challenge,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS)
  });
  return options;
}

export async function verifyRegistration(uid, response) {
  const db = getFirestore();
  const ref = challengeRef(uid, "register");
  const snap = await ref.get();
  if (!snap.exists) throw new Error("passkey_registration_challenge_missing");
  const data = snap.data() || {};
  const expires = challengeExpiry(data);
  if (!Number.isFinite(expires) || expires <= Date.now()) throw new Error("passkey_registration_challenge_expired");

  const verification = await verifyRegistrationResponse({
    response,
    expectedChallenge: String(data.challenge),
    expectedOrigin: origins(),
    expectedRPID: RP_ID,
    requireUserVerification: true,
    supportedAlgorithmIDs: [-7, -257]
  });
  if (!verification.verified || !verification.registrationInfo) throw new Error("passkey_registration_failed");

  const { credential, credentialDeviceType, credentialBackedUp } = verification.registrationInfo;
  if (!validCredentialId(credential.id)) throw new Error("passkey_credential_id_invalid");
  const credentialDocument = credentialRef(credential.id);
  await db.runTransaction(async tx => {
    const [currentChallenge, existingCredential] = await Promise.all([
      tx.get(ref),
      tx.get(credentialDocument)
    ]);
    if (!currentChallenge.exists) throw new Error("passkey_registration_challenge_used");
    const current = currentChallenge.data() || {};
    const currentExpiry = challengeExpiry(current);
    if (current.uid !== uid || current.purpose !== "register" ||
        current.challenge !== data.challenge ||
        !Number.isFinite(currentExpiry) || currentExpiry <= Date.now()) {
      throw new Error("passkey_registration_challenge_invalid");
    }
    if (existingCredential.exists) throw new Error("passkey_already_registered");
    tx.create(credentialDocument, {
      uid,
      publicKey: Buffer.from(credential.publicKey).toString("base64"),
      counter: Number(credential.counter || 0),
      transports: credential.transports || [],
      deviceType: credentialDeviceType || "unknown",
      backedUp: credentialBackedUp === true,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.delete(ref);
  });
  return { ok: true, id: credential.id, ...(await passkeyStatus(uid)) };
}

export async function authenticationOptions() {
  const options = await generateAuthenticationOptions({
    rpID: RP_ID,
    userVerification: "required",
    allowCredentials: []
  });
  const id = cryptoRandomId();
  await challengeRef(id, "login").set({
    purpose: "login",
    challenge: options.challenge,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS)
  });
  return { sessionId: id, options };
}

export async function verifyAuthentication(sessionId, response) {
  const cleanSessionId = String(sessionId || "");
  if (!/^[A-Za-z0-9_-]{32}$/.test(cleanSessionId)) throw new Error("passkey_authentication_session_invalid");
  const credentialId = String(response?.id || "");
  if (!validCredentialId(credentialId)) throw new Error("passkey_credential_id_invalid");

  const db = getFirestore();
  const challengeDocument = challengeRef(cleanSessionId, "login");
  const credentialDocument = credentialRef(credentialId);
  const [challengeSnapshot, credentialSnapshot] = await Promise.all([
    challengeDocument.get(),
    credentialDocument.get()
  ]);
  if (!challengeSnapshot.exists) throw new Error("passkey_authentication_challenge_missing");
  if (!credentialSnapshot.exists) throw new Error("passkey_not_registered");

  const challenge = challengeSnapshot.data() || {};
  const challengeExpiryAt = challengeExpiry(challenge);
  if (challenge.purpose !== "login" ||
      !Number.isFinite(challengeExpiryAt) || challengeExpiryAt <= Date.now()) {
    throw new Error("passkey_authentication_challenge_expired");
  }
  const saved = credentialSnapshot.data() || {};
  const savedUid = String(saved.uid || "");
  if (!savedUid || !saved.publicKey) throw new Error("passkey_credential_invalid");

  const verification = await verifyAuthenticationResponse({
    response,
    expectedChallenge: String(challenge.challenge),
    expectedOrigin: origins(),
    expectedRPID: RP_ID,
    requireUserVerification: true,
    credential: {
      id: credentialId,
      publicKey: new Uint8Array(Buffer.from(String(saved.publicKey), "base64")),
      counter: Number(saved.counter || 0),
      transports: saved.transports || []
    }
  });
  if (!verification.verified) throw new Error("passkey_authentication_failed");

  const nextCounter = Number(verification.authenticationInfo?.newCounter ?? saved.counter ?? 0);
  await db.runTransaction(async tx => {
    const [currentChallenge, currentCredential] = await Promise.all([
      tx.get(challengeDocument),
      tx.get(credentialDocument)
    ]);
    if (!currentChallenge.exists || !currentCredential.exists) {
      throw new Error("passkey_authentication_challenge_used");
    }
    const currentChallengeData = currentChallenge.data() || {};
    const currentCredentialData = currentCredential.data() || {};
    const currentExpiry = challengeExpiry(currentChallengeData);
    if (currentChallengeData.purpose !== "login" ||
        currentChallengeData.challenge !== challenge.challenge ||
        !Number.isFinite(currentExpiry) || currentExpiry <= Date.now()) {
      throw new Error("passkey_authentication_challenge_invalid");
    }
    if (String(currentCredentialData.uid || "") !== savedUid ||
        String(currentCredentialData.publicKey || "") !== String(saved.publicKey) ||
        Number(currentCredentialData.counter || 0) !== Number(saved.counter || 0)) {
      throw new Error("passkey_credential_changed_retry");
    }
    const currentCounter = Number(currentCredentialData.counter || 0);
    // Authenticators that always report zero are valid; non-zero counters must advance.
    if (currentCounter > 0 && nextCounter > 0 && nextCounter <= currentCounter) {
      throw new Error("passkey_counter_replay_detected");
    }
    tx.update(credentialDocument, {
      counter: nextCounter,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.delete(challengeDocument);
  });

  const customToken = await getAuth().createCustomToken(savedUid);
  return { ok: true, customToken, uid: savedUid };
}

function cryptoRandomId() {
  return Buffer.from(cryptoRandomBytes(24)).toString("base64url");
}

function cryptoRandomBytes(size) {
  const bytes = new Uint8Array(size);
  globalThis.crypto.getRandomValues(bytes);
  return bytes;
}

export async function removePasskey(uid, id) {
  if (!validCredentialId(id)) throw new Error("passkey_credential_id_invalid");
  const ref = credentialRef(id);
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("passkey_not_found");
  await ref.delete();
  return { ok: true, ...(await passkeyStatus(uid)) };
}
