import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse
} from "@simplewebauthn/server";
import {
  firestoreGet,
  firestoreSet,
  firestoreCreate,
  firestoreDelete,
  firestoreQuery,
  firestoreCommit
} from "./firebase.js";

const RP_ID = "bqsqwadai.com";
const RP_NAME = "BQ Sqwad AI";
const ORIGIN = "https://bqsqwadai.com";
const TTL = 10 * 60 * 1000;
const b64u = value => {
  const bytes = value instanceof Uint8Array ? value : new TextEncoder().encode(value);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
const dec = value => {
  const clean = String(value).replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(clean + "=".repeat((4 - clean.length % 4) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
};
const pem = value => {
  const binary = atob(String(value).replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, ""));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
};
async function sign(env, header, claim) {
  const raw = header + "." + claim;
  const account = JSON.parse(String(env.BQ_FIREBASE_SERVICE_ACCOUNT_JSON || ""));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pem(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return raw + "." + b64u(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(raw)));
}
async function customToken(env, uid) {
  const now = Math.floor(Date.now() / 1000);
  const account = JSON.parse(String(env.BQ_FIREBASE_SERVICE_ACCOUNT_JSON || ""));
  const header = b64u(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64u(JSON.stringify({
    iss: account.client_email,
    sub: account.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat: now,
    exp: now + 3600,
    uid: String(uid)
  }));
  return sign(env, header, claim);
}
function challengeExpiry(record) {
  return Date.parse(String(record?.expiresAt || ""));
}
function validCredentialId(id) {
  return /^[A-Za-z0-9_-]{16,1024}$/.test(String(id || ""));
}
async function status(uid) {
  const rows = await firestoreQuery("passkeys", {
    filters: [{ field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: uid } }],
    limit: 100
  });
  return {
    count: rows.length,
    items: rows.map(row => ({
      id: String(row.__name || "").split("/").pop(),
      createdAt: row.createdAt || "",
      deviceType: String(row.deviceType || "unknown"),
      backedUp: row.backedUp === true
    }))
  };
}
export async function passkeyStatus(uid) {
  return status(uid);
}
export async function registrationOptionsNative(uid, email) {
  const rows = await firestoreQuery("passkeys", {
    filters: [{ field: { fieldPath: "uid" }, op: "EQUAL", value: { stringValue: uid } }],
    limit: 100
  });
  const options = await generateRegistrationOptions({
    rpName: RP_NAME,
    rpID: RP_ID,
    userName: email || uid,
    userDisplayName: email || "BQ Sqwad AI user",
    userID: new TextEncoder().encode(uid),
    attestationType: "none",
    excludeCredentials: rows.map(row => ({
      id: String(row.__name || "").split("/").pop(),
      transports: row.transports || []
    })),
    authenticatorSelection: {
      residentKey: "required",
      userVerification: "required",
      authenticatorAttachment: "platform"
    },
    supportedAlgorithmIDs: [-7, -257]
  });
  await firestoreSet("passkeyChallenges/" + uid + "_register", {
    uid,
    purpose: "register",
    challenge: options.challenge,
    createdAt: new Date(),
    expiresAt: new Date(Date.now() + TTL)
  }, false);
  return options;
}
export async function verifyRegistrationNative(uid, response) {
  const path = "passkeyChallenges/" + uid + "_register";
  const challenge = await firestoreGet(path);
  if (!challenge) throw new Error("passkey_registration_challenge_missing");
  const expires = challengeExpiry(challenge);
  if (challenge.uid !== uid || challenge.purpose !== "register" ||
      !Number.isFinite(expires) || expires <= Date.now()) {
    throw new Error("passkey_registration_challenge_expired");
  }
  const verification = await verifyRegistrationResponse({
    response,
    expectedChallenge: String(challenge.challenge),
    expectedOrigin: ORIGIN,
    expectedRPID: RP_ID,
    requireUserVerification: true,
    supportedAlgorithmIDs: [-7, -257]
  });
  if (!verification.verified || !verification.registrationInfo) throw new Error("passkey_registration_failed");
  const info = verification.registrationInfo;
  const credential = info.credential || {};
  const id = String(credential.id || "");
  if (!validCredentialId(id) || !challenge.__updateTime) throw new Error("passkey_credential_id_invalid");
  await firestoreCommit([
    {
      path: "passkeys/" + id,
      create: true,
      data: {
        uid,
        publicKey: b64u(credential.publicKey),
        counter: Number(credential.counter || 0),
        transports: credential.transports || [],
        deviceType: info.credentialDeviceType || "unknown",
        backedUp: info.credentialBackedUp === true,
        createdAt: new Date(),
        updatedAt: new Date()
      }
    },
    {
      path,
      updateTime: challenge.__updateTime,
      data: { consumedAt: new Date() }
    }
  ]);
  return { ok: true, id, ...await status(uid) };
}
export async function authenticationOptionsNative() {
  const options = await generateAuthenticationOptions({
    rpID: RP_ID,
    userVerification: "required",
    allowCredentials: []
  });
  const sessionId = crypto.randomUUID();
  await firestoreSet("passkeyChallenges/" + sessionId + "_login", {
    sessionId,
    purpose: "login",
    challenge: options.challenge,
    createdAt: new Date(),
    expiresAt: new Date(Date.now() + TTL)
  }, false);
  return { sessionId, options };
}
export async function verifyAuthenticationNative(env, sessionId, response) {
  const cleanSessionId = String(sessionId || "");
  if (!/^[0-9a-f-]{36}$/i.test(cleanSessionId)) throw new Error("passkey_authentication_session_invalid");
  const id = String(response?.id || "");
  if (!validCredentialId(id)) throw new Error("passkey_credential_id_invalid");
  const challengePath = "passkeyChallenges/" + cleanSessionId + "_login";
  const credentialPath = "passkeys/" + id;
  const [challenge, saved] = await Promise.all([
    firestoreGet(challengePath),
    firestoreGet(credentialPath)
  ]);
  if (!challenge || !saved) throw new Error("passkey_authentication_challenge_missing");
  const expires = challengeExpiry(challenge);
  if (challenge.purpose !== "login" || !Number.isFinite(expires) || expires <= Date.now()) {
    throw new Error("passkey_authentication_challenge_expired");
  }
  if (!saved.uid || !saved.publicKey || !challenge.__updateTime || !saved.__updateTime) {
    throw new Error("passkey_credential_invalid");
  }
  const verification = await verifyAuthenticationResponse({
    response,
    expectedChallenge: String(challenge.challenge),
    expectedOrigin: ORIGIN,
    expectedRPID: RP_ID,
    requireUserVerification: true,
    credential: {
      id,
      publicKey: dec(saved.publicKey),
      counter: Number(saved.counter || 0),
      transports: saved.transports || []
    }
  });
  if (!verification.verified) throw new Error("passkey_authentication_failed");
  const nextCounter = Number(verification.authenticationInfo?.newCounter ?? saved.counter ?? 0);
  const currentCounter = Number(saved.counter || 0);
  if (currentCounter > 0 && nextCounter > 0 && nextCounter <= currentCounter) {
    throw new Error("passkey_counter_replay_detected");
  }
  // Challenge consumption and counter advancement share one conditional commit.
  // A second concurrent/replayed request cannot mint another Firebase custom token.
  await firestoreCommit([
    {
      path: credentialPath,
      updateTime: saved.__updateTime,
      data: { counter: nextCounter, updatedAt: new Date() }
    },
    {
      path: challengePath,
      updateTime: challenge.__updateTime,
      data: { consumedAt: new Date() }
    }
  ]);
  return {
    ok: true,
    customToken: await customToken(env, String(saved.uid)),
    uid: String(saved.uid)
  };
}
export async function removePasskeyNative(uid, id) {
  if (!validCredentialId(id)) throw new Error("passkey_credential_id_invalid");
  const record = await firestoreGet("passkeys/" + id);
  if (!record || record.uid !== uid) throw new Error("passkey_not_found");
  await firestoreDelete("passkeys/" + id);
  return { ok: true, ...await status(uid) };
}
