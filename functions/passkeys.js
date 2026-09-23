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
    challenge: options.challenge,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS)
  });
  return options;
}

export async function verifyRegistration(uid, response) {
  const snap = await challengeRef(uid, "register").get();
  if (!snap.exists) throw new Error("passkey_registration_challenge_missing");
  const data = snap.data() || {};
  const expires = data.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (!expires || expires < Date.now()) throw new Error("passkey_registration_challenge_expired");

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
  await credentialRef(credential.id).set({
    uid,
    publicKey: Buffer.from(credential.publicKey).toString("base64"),
    counter: Number(credential.counter || 0),
    transports: credential.transports || [],
    deviceType: credentialDeviceType || "unknown",
    backedUp: credentialBackedUp === true,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  });
  await challengeRef(uid, "register").delete();
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
    challenge: options.challenge,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS)
  });
  return { sessionId: id, options };
}

export async function verifyAuthentication(sessionId, response) {
  const snap = await challengeRef(sessionId, "login").get();
  if (!snap.exists) throw new Error("passkey_authentication_challenge_missing");
  const data = snap.data() || {};
  const expires = data.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (!expires || expires < Date.now()) throw new Error("passkey_authentication_challenge_expired");

  const credentialId = String(response?.id || "");
  if (!credentialId) throw new Error("passkey_credential_id_missing");
  const credSnap = await credentialRef(credentialId).get();
  if (!credSnap.exists) throw new Error("passkey_not_registered");
  const saved = credSnap.data() || {};

  const verification = await verifyAuthenticationResponse({
    response,
    expectedChallenge: String(data.challenge),
    expectedOrigin: origins(),
    expectedRPID: RP_ID,
    requireUserVerification: true,
    credential: {
      id: credentialId,
      publicKey: new Uint8Array(Buffer.from(String(saved.publicKey || ""), "base64")),
      counter: Number(saved.counter || 0),
      transports: saved.transports || []
    }
  });
  if (!verification.verified) throw new Error("passkey_authentication_failed");

  await credSnap.ref.set({
    counter: Number(verification.authenticationInfo?.newCounter ?? saved.counter ?? 0),
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });
  await challengeRef(sessionId, "login").delete();
  const customToken = await getAuth().createCustomToken(String(saved.uid));
  return { ok: true, customToken, uid: String(saved.uid) };
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
  const ref = credentialRef(String(id || ""));
  const snap = await ref.get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("passkey_not_found");
  await ref.delete();
  return { ok: true, ...(await passkeyStatus(uid)) };
}
