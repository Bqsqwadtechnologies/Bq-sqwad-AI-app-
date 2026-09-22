import crypto from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const REF = "adminSecurity/global";
const ISSUER = "BQ Sqwad AI";
const ACCOUNT = "BQ Sqwad Admin";
const BIOMETRIC_CHALLENGE_TTL_MS = 5 * 60 * 1000;

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

function verifyTotp(secret, code) {
  const normalized = String(code || "").replace(/\D/g, "");
  if (normalized.length !== 6) return false;
  const counter = Math.floor(Date.now() / 1000 / 30);
  for (let delta = -1; delta <= 1; delta++) {
    if (crypto.timingSafeEqual(Buffer.from(totp(secret, counter + delta)), Buffer.from(normalized))) return true;
  }
  return false;
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
    totpConfigured: Boolean(data.totpSecret),
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
    pendingTotpSecret: secret,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: actorUid
  }, { merge: true });
  return { secret, otpauthUri: uri };
}

export async function confirmTotpSetup(code, actorUid) {
  const snap = await (await ref()).get();
  const pending = snap.data()?.pendingTotpSecret;
  if (!pending || !verifyTotp(pending, code)) throw new Error("Invalid authenticator code");
  await (await ref()).set({
    totpSecret: pending,
    pendingTotpSecret: null,
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: actorUid
  }, { merge: true });
  await audit(actorUid, "totp_configured");
  return securityStatus();
}

export async function unlockAdmin(uid, password, code) {
  const snap = await (await ref()).get();
  const data = snap.exists ? snap.data() || {} : {};
  if (data.password?.hash && !verifyPassword(password, data.password)) throw new Error("Invalid admin password");
  if (data.totpSecret && !verifyTotp(data.totpSecret, code)) throw new Error("Invalid authenticator code");
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
  const snap = await getFirestore().collection("adminBiometricChallenges").doc(String(challengeId || "")).get();
  if (!snap.exists || snap.data()?.uid !== uid) throw new Error("biometric_challenge_invalid");
  const expires = snap.data()?.expiresAt?.toDate?.()?.getTime?.() || 0;
  if (!expires || expires < Date.now()) throw new Error("biometric_challenge_expired");
  const security = await (await ref()).get();
  const publicKey = security.data()?.biometric?.publicKey;
  if (!publicKey) throw new Error("biometric_not_configured");
  const verify = crypto.createVerify("SHA256");
  verify.update(Buffer.from(String(snap.data()?.challenge || ""), "base64"));
  verify.end();
  const ok = verify.verify({ key: Buffer.from(String(publicKey), "base64"), format: "der", type: "spki" }, Buffer.from(String(signatureBase64 || ""), "base64"));
  if (!ok) throw new Error("biometric_verification_failed");
  await getAuth().setCustomUserClaims(uid, {
    ...(await getAuth().getUser(uid)).customClaims,
    adminBiometricVerifiedAt: Date.now()
  });
  await snap.ref.delete();
  await audit(uid, "biometric_unlocked");
  return { ok: true, verifiedAt: Date.now() };
}

export async function removeBiometric(uid) {
  await (await ref()).set({ biometric: null, updatedAt: FieldValue.serverTimestamp(), updatedBy: uid }, { merge: true });
  await audit(uid, "biometric_removed");
  return biometricStatus();
}
