import crypto from "node:crypto";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const COLLECTION = "adminProviderSecrets";
const ALGORITHM = "aes-256-gcm";

function masterKey() {
  const raw = process.env.BQ_SECRET_ENCRYPTION_KEY;
  if (!raw) throw new Error("BQ_SECRET_ENCRYPTION_KEY is not configured");
  const key = Buffer.from(raw, "hex");
  if (key.length !== 32) throw new Error("BQ_SECRET_ENCRYPTION_KEY must be 64 hex characters");
  return key;
}

export function encryptSecret(value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv(ALGORITHM, masterKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  return {
    algorithm: ALGORITHM,
    iv: iv.toString("base64"),
    ciphertext: ciphertext.toString("base64"),
    authTag: cipher.getAuthTag().toString("base64")
  };
}

export function decryptSecret(payload) {
  const decipher = crypto.createDecipheriv(ALGORITHM, masterKey(), Buffer.from(payload.iv, "base64"));
  decipher.setAuthTag(Buffer.from(payload.authTag, "base64"));
  return Buffer.concat([
    decipher.update(Buffer.from(payload.ciphertext, "base64")),
    decipher.final()
  ]).toString("utf8");
}

const ENV_FALLBACKS = {
  gemini: "GEMINI_API_KEY",
  openai: "OPENAI_API_KEY",
  tavily: "TAVILY_API_KEY",
  paystack: "PAYSTACK_SECRET_KEY",
  paystack_public: "PAYSTACK_PUBLIC_KEY",
  meta_app_secret: null,
  youtube_client_secret: null,
  tiktok_client_secret: null,
  x_client_secret: null
};

export async function getProviderSecret(providerId) {
  const db = getFirestore();
  const doc = await db.collection(COLLECTION).doc(providerId).get();
  if (doc.exists && doc.data()?.ciphertext) return decryptSecret(doc.data());
  const envName = ENV_FALLBACKS[providerId];
  return envName ? (process.env[envName] || null) : null;
}

export async function setProviderSecret(providerId, value, actorUid) {
  if (!value || value.length > 4096) throw new Error("Invalid provider secret");
  await getFirestore().collection(COLLECTION).doc(providerId).set({
    ...encryptSecret(value),
    updatedAt: FieldValue.serverTimestamp(),
    updatedBy: actorUid
  });
}

export async function providerSecretStatus(providerId) {
  const doc = await getFirestore().collection(COLLECTION).doc(providerId).get();
  const envName = ENV_FALLBACKS[providerId];
  return {
    providerId,
    configured: doc.exists || Boolean(envName && process.env[envName]),
    source: doc.exists ? "encrypted_firestore" : (envName && process.env[envName] ? "runtime_secret" : "none"),
    updatedAt: doc.data()?.updatedAt?.toDate?.()?.toISOString() || null
  };
}
