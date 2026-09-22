import crypto from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const REF = "adminSecurity/global";
const ISSUER = "BQ Sqwad AI";
const ACCOUNT = "BQ Sqwad Admin";

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
    account: ACCOUNT
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
  if (!status.passwordConfigured && !status.totpConfigured) return;
  const user = await getAuth().getUser(uid);
  const verifiedAt = Number(user.customClaims?.admin2faVerifiedAt || 0);
  if (!verifiedAt || Date.now() - verifiedAt > status.verifiedWindowSeconds * 1000) {
    throw new Error("admin_step_up_required");
  }
}

async function audit(uid, action) {
  await getFirestore().collection("adminSecurityAudit").add({
    uid, action, createdAt: FieldValue.serverTimestamp()
  });
}
