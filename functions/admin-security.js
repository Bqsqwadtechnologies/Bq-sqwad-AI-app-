import crypto from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { encryptSecret, decryptSecret } from "./secret-store.js";

const REF = "adminSecurity/global";
const ISSUER = "BQ Sqwad AI";
const ACCOUNT = "BQ Sqwad Admin";
const BIOMETRIC_CHALLENGE_TTL_MS = 5 * 60 * 1000;
const ADMIN_UNLOCK_MAX_FAILURES = 5;
const ADMIN_UNLOCK_LOCKOUT_MS = 15 * 60 * 1000;

function unlockAttemptsRef(uid) {
  return getFirestore().collection("adminSecurityAttempts").doc(String(uid));
}

async function assertAdminUnlockAllowed(uid) {
  const document = unlockAttemptsRef(uid);
  const snap = await document.get();
  const lockUntil = snap.data()?.lockUntil?.toDate?.()?.getTime?.() || 0;
  if (lockUntil > Date.now()) throw new Error("admin_unlock_temporarily_locked");
}

async function recordAdminUnlockFailure(uid) {
  const document = unlockAttemptsRef(uid);
  await getFirestore().runTransaction(async (transaction) => {
    const snap = await transaction.get(document);
    const data = snap.data() || {};
    const lockUntil = data.lockUntil?.toDate?.()?.getTime?.() || 0;
    if (lockUntil > Date.now()) return;
    const failures = Math.max(0, Number(data.failures || 0)) + 1;
    transaction.set(document, {
      failures: failures >= ADMIN_UNLOCK_MAX_FAILURES ? 0 : failures,
      lockUntil: failures >= ADMIN_UNLOCK_MAX_FAILURES
        ? new Date(Date.now() + ADMIN_UNLOCK_LOCKOUT_MS)
        : null,
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
  });
}

async function clearAdminUnlockFailures(uid) {
  await unlockAttemptsRef(uid).delete();
}

function base32Encode(buffer) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0, value = 0, out = "";
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += alphabet[(value << (5 - bits)) & 31];
  return out;
}

function base32Decode(input) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0, value = 0;
  const bytes = [];
  for (const ch of String(input).toUpperCase().replace(/=+$/g, "")) {
    const index = alphabet.indexOf(ch);
    if (index < 0) continue;
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

function totp(secret, counter = Math.floor(Date.now() / 1000 / 30)) {
  const key = base32Decode(secret);
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac("sha1", key).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code = (
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff)
  ) % 1000000;
  return String(code).padStart(6, "0");
}

function matchingTotpCounter(secret, code) {
  const normalized = String(code || "").replace(/\D/g, "");
  if (normalized.length !== 6) return null;
  const supplied = Buffer.from(normalized);
  const counter = Math.floor(Date.now() / 1000 / 30);
  for (let delta = -1; delta <= 1; delta++) {
    const candidate = counter + delta;
    if (candidate < 0) continue;
    const expected = Buffer.from(totp(secret, candidate));
    if (crypto.timingSafeEqual(expected, supplied)) return candidate;
  }
  return null;
}

function verifyTotp(secret, code) {
  return matchingTotpCounter(secret, code) !== null;
}

function hashPassword(password, salt = crypto.randomBytes(16)) {
  const hash = crypto.scryptSync(String(password), salt, 32);
  return { salt: salt.toString("base64"), hash: hash.toString("base64") };
}

function verifyPassword(password, record) {
  if (!record?.hash || !record?.salt) return false;
  const actual = crypto.scryptSync(String(password), Buffer.from(record.salt, "base64"), 32);
  const expected = Buffer.from(record.hash, "base64");
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

async function ref() {
  return getFirestore().doc(REF);
}

export async function securityStatus() {
  const snap = await (await ref()).get();
  const data = snap.exists ? snap.data() || {} : {};
  return {
    passwordConfigured: Boolean(data.password?.hash),
    totpConfigured: Boolean(data.totpSecretEncrypted || data.totpSecret),
    verifiedWindowSeconds: 12 * 60 * 60,
    issuer: ISSUER,
    account: ACCOUNT,
    biometricConfigured: Boolean(data.biometric?.publicKey)
  };
}

export async function configurePassword(password, actorUid) {
  if (String(password || "").length < 10) throw new Error("Admin password must be at least 10 characters");
  const record = hashPassword(password);
  const document = await ref();
  await document.set({
    password: record,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: actorUid
  }, { merge: true });
  await audit(actorUid, "password_configured");
  return securityStatus();
}

export async function beginTotpSetup(actorUid) {
  const secret = base32Encode(crypto.randomBytes(20));
  const uri = "otpauth://totp/" + encodeURIComponent(ISSUER + ":" + ACCOUNT) +
    "?secret=" + secret +
    "&issuer=" + encodeURIComponent(ISSUER) +
    "&algorithm=SHA1&digits=6&period=30";
  await (await ref()).set({
    pendingTotpSecretEncrypted: encryptSecret(secret),
    pendingTotpSecret: null,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: actorUid
  }, { merge: true });
  return { secret, otpauthUri: uri };
}

export async function confirmTotpSetup(code, actorUid) {
  const document = await ref();
  const snap = await document.get();
  const data = snap.data() || {};
  const pendingEncrypted = data.pendingTotpSecretEncrypted || null;
  const pending = pendingEncrypted ? decryptSecret(pendingEncrypted) : data.pendingTotpSecret;
  const matchedCounter = pending ? matchingTotpCounter(pending, code) : null;
  if (!pending || matchedCounter === null) throw new Error("Invalid authenticator code");

  await getFirestore().runTransaction(async (transaction) => {
    const latest = await transaction.get(document);
    const latestData = latest.data() || {};
    const latestPending = latestData.pendingTotpSecretEncrypted
      ? decryptSecret(latestData.pendingTotpSecretEncrypted)
      : latestData.pendingTotpSecret;
    if (!latestPending || latestPending !== pending) throw new Error("Authenticator setup expired; start again.");
    if (matchedCounter <= Number(latestData.lastTotpCounter || -1)) {
      throw new Error("This authenticator code has already been used.");
    }
    transaction.set(document, {
      totpSecretEncrypted: encryptSecret(pending),
      totpSecret: null,
      pendingTotpSecretEncrypted: null,
      pendingTotpSecret: null,
      lastTotpCounter: matchedCounter,
      updatedAt: FieldValue.serverTimestamp(),
      updatedBy: actorUid
    }, { merge: true });
  });
  await audit(actorUid, "totp_configured");
  return securityStatus();
}

export async function unlockAdmin(uid, password, code) {
  await assertAdminUnlockAllowed(uid);
  const document = await ref();
  const snap = await document.get();
  const data = snap.exists ? snap.data() || {} : {};
  const totpSecret = data.totpSecretEncrypted ? decryptSecret(data.totpSecretEncrypted) : data.totpSecret;
  let matchedCounter = null;

  if (data.password?.hash) {
    if (!totpSecret) throw new Error("Configure the authenticator before password-based admin login");
    if (!verifyPassword(password, data.password)) {
      await recordAdminUnlockFailure(uid);
      throw new Error("Invalid admin credentials");
    }
    matchedCounter = matchingTotpCounter(totpSecret, code);
    if (matchedCounter === null) {
      await recordAdminUnlockFailure(uid);
      throw new Error("Invalid admin credentials");
    }
  } else if (totpSecret) {
    matchedCounter = matchingTotpCounter(totpSecret, code);
    if (matchedCounter === null) {
      await recordAdminUnlockFailure(uid);
      throw new Error("Invalid admin credentials");
    }
  }

  if (matchedCounter !== null) {
    await getFirestore().runTransaction(async (transaction) => {
      const latest = await transaction.get(document);
      const latestData = latest.data() || {};
      const latestSecret = latestData.totpSecretEncrypted
        ? decryptSecret(latestData.totpSecretEncrypted)
        : latestData.totpSecret;
      if (!latestSecret || latestSecret !== totpSecret) throw new Error("Authenticator configuration changed; try again.");
      if (matchedCounter <= Number(latestData.lastTotpCounter || -1)) {
        throw new Error("This authenticator code has already been used.");
      }
      transaction.set(document, {
        lastTotpCounter: matchedCounter,
        updatedAt: FieldValue.serverTimestamp(),
        updatedBy: uid
      }, { merge: true });
    });
  }

  await clearAdminUnlockFailures(uid);
  const user = await getAuth().getUser(uid);
  await getAuth().setCustomUserClaims(uid, {
    ...(user.customClaims || {}),
    admin2faVerifiedAt: Date.now()
  });
  await audit(uid, "admin_unlocked");
  return { ok: true, verifiedAt: Date.now() };
}

export async function requireStepUp(uid) {
  const status = await securityStatus();
  const user = await getAuth().getUser(uid);
  const passwordTotpVerifiedAt = Number(user.customClaims?.admin2faVerifiedAt || 0);
  const biometricVerifiedAt = Number(user.customClaims?.adminBiometricVerifiedAt || 0);
  const windowMs = status.verifiedWindowSeconds * 1000;
  if (status.passwordConfigured || status.totpConfigured || (await biometricStatus()).configured) {
    if ((passwordTotpVerifiedAt && Date.now() - passwordTotpVerifiedAt <= windowMs) || (biometricVerifiedAt && Date.now() - biometricVerifiedAt <= windowMs)) return;
    throw new Error("admin_step_up_required");
  }
}

async function audit(uid, action) {
  await getFirestore().collection("adminSecurityAudit").add({
    uid, action, createdAt: FieldValue.serverTimestamp()
  });
}


export async function biometricStatus() {
  const snap = await (await ref()).get();
  return { configured: Boolean(snap.data()?.biometric?.publicKey) };
}

export async function registerBiometric(uid, publicKeyBase64) {
  if (!publicKeyBase64 || String(publicKeyBase64).length > 10000) throw new Error("biometric_public_key_required");
  await (await ref()).set({
    biometric: {
      publicKey: String(publicKeyBase64),
      registeredAt: FieldValue.serverTimestamp(),
      registeredBy: uid
    }
  }, { merge: true });
  await audit(uid, "biometric_configured");
  return biometricStatus();
}

export async function beginBiometricChallenge(uid) {
  const challengeId = crypto.randomBytes(24).toString("base64url");
  const challenge = crypto.randomBytes(32);
  await getFirestore().collection("adminBiometricChallenges").doc(challengeId).set({
    uid,
    challenge: challenge.toString("base64"),
    expiresAt: new Date(Date.now() + BIOMETRIC_CHALLENGE_TTL_MS),
    createdAt: FieldValue.serverTimestamp()
  });
  return { challengeId, challenge: challenge.toString("base64") };
}

export async function verifyBiometric(uid, challengeId, signatureBase64) {
  const id = String(challengeId || "");
  const signature = String(signatureBase64 || "");
  if (!/^[A-Za-z0-9_-]{32}$/.test(id) || signature.length > 2048 || !/^[A-Za-z0-9+/]+={0,2}$/.test(signature)) {
    throw new Error("biometric_challenge_invalid");
  }
  const challengeRef = getFirestore().collection("adminBiometricChallenges").doc(id);
  const snap = await challengeRef.get();
  const challengeData = snap.data() || {};
  if (!snap.exists || challengeData.uid !== uid) throw new Error("biometric_challenge_invalid");
  const expires = challengeData.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (!expires || expires < Date.now()) throw new Error("biometric_challenge_expired");
  const challengeText = String(challengeData.challenge || "");
  const challengeBytes = Buffer.from(challengeText, "base64");
  if (challengeBytes.length !== 32) throw new Error("biometric_challenge_invalid");

  const security = await (await ref()).get();
  const publicKey = security.data()?.biometric?.publicKey;
  if (!publicKey || String(publicKey).length > 10000) throw new Error("biometric_not_configured");
  const verify = crypto.createVerify("SHA256");
  verify.update(challengeBytes);
  verify.end();
  const ok = verify.verify(
    { key: Buffer.from(String(publicKey), "base64"), format: "der", type: "spki" },
    Buffer.from(signature, "base64")
  );
  if (!ok) throw new Error("biometric_verification_failed");

  // Consume the challenge transactionally. Two simultaneous requests with the
  // same valid signature cannot both elevate the admin session.
  await getFirestore().runTransaction(async (transaction) => {
    const latest = await transaction.get(challengeRef);
    const latestData = latest.data() || {};
    const latestExpiry = latestData.expiresAt?.toDate?.()?.getTime?.() || 0;
    if (!latest.exists || latestData.uid !== uid || latestData.challenge !== challengeText ||
        !latestExpiry || latestExpiry < Date.now()) {
      throw new Error("biometric_challenge_already_used_or_expired");
    }
    transaction.delete(challengeRef);
  });

  const user = await getAuth().getUser(uid);
  await getAuth().setCustomUserClaims(uid, {
    ...(user.customClaims || {}),
    adminBiometricVerifiedAt: Date.now()
  });
  await audit(uid, "biometric_unlocked");
  return { ok: true, verifiedAt: Date.now() };
}

export async function removeBiometric(uid) {
  await (await ref()).set({ biometric: null, updatedAt: FieldValue.serverTimestamp(), updatedBy: uid }, { merge: true });
  await audit(uid, "biometric_removed");
  return biometricStatus();
}
