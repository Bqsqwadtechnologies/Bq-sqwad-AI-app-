import crypto from "node:crypto";
import { firestoreGet, firestoreSet, firestoreCreate, firestoreDelete } from "./firebase.js";
const REF="adminSecurity/global",ISSUER="BQ Sqwad AI",ACCOUNT="BQ Sqwad Admin";
function key(env){const b=Buffer.from(String(env.BQ_SECRET_ENCRYPTION_KEY||""),"hex");if(b.length!==32)throw new Error("BQ_SECRET_ENCRYPTION_KEY must be 64 hex characters");return b}
function enc(env,s){const iv=crypto.randomBytes(12),c=crypto.createCipheriv("aes-256-gcm",key(env),iv);const out=Buffer.concat([c.update(String(s),"utf8"),c.final()]);return {algorithm:"aes-256-gcm",iv:iv.toString("base64"),ciphertext:out.toString("base64"),authTag:c.getAuthTag().toString("base64")}}
function dec(env,p){const d=crypto.createDecipheriv("aes-256-gcm",key(env),Buffer.from(p.iv,"base64"));d.setAuthTag(Buffer.from(p.authTag,"base64"));return Buffer.concat([d.update(Buffer.from(p.ciphertext,"base64")),d.final()]).toString("utf8")}
function b32e(buf){const a="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";let bits=0,v=0,o="";for(const x of buf){v=(v<<8)|x;bits+=8;while(bits>=5){o+=a[(v>>(bits-5))&31];bits-=5}}if(bits)o+=a[(v<<(5-bits))&31];return o}
function b32d(s){const a="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";let bits=0,v=0,o=[];for(const ch of String(s).replace(/=+$/,"").toUpperCase()){const i=a.indexOf(ch);if(i<0)continue;v=(v<<5)|i;bits+=5;if(bits>=8){o.push((v>>(bits-8))&255);bits-=8}}return Buffer.from(o)}
function code(secret,c=Math.floor(Date.now()/30000)){const m=Buffer.alloc(8);m.writeBigUInt64BE(BigInt(c));const h=crypto.createHmac("sha1",b32d(secret)).update(m).digest(),o=h[h.length-1]&15,n=(((h[o]&127)<<24)|((h[o+1]&255)<<16)|((h[o+2]&255)<<8)|(h[o+3]&255))%1000000;return String(n).padStart(6,"0")}
function valid(secret,input){const x=String(input||"").replace(/\D/g,"");if(x.length!==6)return false;const c=Math.floor(Date.now()/30000);return [-1,0,1].some(d=>crypto.timingSafeEqual(Buffer.from(code(secret,c+d)),Buffer.from(x)))}
function pwd(password,salt=crypto.randomBytes(16)){return {salt:salt.toString("base64"),hash:crypto.scryptSync(String(password),salt,32).toString("base64")}}
function pwdOk(password,r){if(!r?.hash||!r?.salt)return false;const a=crypto.scryptSync(String(password),Buffer.from(r.salt,"base64"),32),b=Buffer.from(r.hash,"base64");return a.length===b.length&&crypto.timingSafeEqual(a,b)}
function timestampMillis(value) {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value < 1e12 ? value * 1000 : value;
  if (typeof value === "string") return Date.parse(value);
  if (!value || typeof value !== "object") return NaN;
  if (typeof value.toDate === "function") {
    const date = value.toDate();
    return date instanceof Date ? date.getTime() : NaN;
  }
  if (typeof value.timestampValue === "string") return Date.parse(value.timestampValue);
  const seconds = Number(value.seconds ?? value._seconds ?? value.secondsValue);
  if (Number.isFinite(seconds)) {
    const nanos = Number(value.nanoseconds ?? value._nanoseconds ?? value.nanos ?? 0);
    return seconds * 1000 + (Number.isFinite(nanos) ? nanos / 1e6 : 0);
  }
  return NaN;
}
export function isAdminSessionActive(record, now = Date.now()) {
  const expiresAt = timestampMillis(record?.expiresAt);
  return Number.isFinite(expiresAt) && expiresAt > now;
}
export async function adminSessionStatusNative(uid) {
  const record = await firestoreGet("adminStepUp/" + uid);
  const expiresAt = timestampMillis(record?.expiresAt);
  const active = Number.isFinite(expiresAt) && expiresAt > Date.now();
  return {
    active,
    expiresAt: active ? new Date(expiresAt).toISOString() : null,
    expiresInSeconds: active ? Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000)) : 0
  };
}
export async function requireAdminSessionNative(uid) {
  const status = await adminSessionStatusNative(uid);
  if (!status.active) throw Object.assign(new Error("admin_session_required"), { httpStatus: 401 });
  // Sliding step-up: sensitive admin activity renews the 30-minute window.
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 30 * 60 * 1000);
  const current = await firestoreGet("adminStepUp/" + uid);
  await firestoreSet("adminStepUp/" + uid, {
    uid,
    verifiedAt: current?.verifiedAt || now,
    lastActivityAt: now,
    expiresAt
  }, true);
  return { active: true, expiresAt: expiresAt.toISOString(), expiresInSeconds: 1800 };
}
export async function securityStatusNative(){const x=await firestoreGet(REF);return {passwordConfigured:Boolean(x?.password?.hash),totpConfigured:Boolean(x?.totpSecretEncrypted),verifiedWindowSeconds:1800,issuer:ISSUER,account:ACCOUNT,biometricConfigured:Boolean(x?.biometric?.publicKey)}}
export async function configurePasswordNative(env,uid,password){if(String(password||"").length<10)throw new Error("Admin password must be at least 10 characters");const existing=await firestoreGet(REF);if(existing?.password?.hash||existing?.totpSecretEncrypted)await requireAdminSessionNative(uid);await firestoreSet(REF,{password:pwd(password),updatedBy:uid,updatedAt:new Date()},true);return securityStatusNative()}
export async function beginTotpNative(env,uid){const existing=await firestoreGet(REF);if(existing?.password?.hash||existing?.totpSecretEncrypted)await requireAdminSessionNative(uid);const secret=b32e(crypto.randomBytes(20));await firestoreSet(REF,{pendingTotpSecretEncrypted:enc(env,secret),pendingTotpSecret:null,updatedBy:uid,updatedAt:new Date()},true);return {secret,otpauthUri:"otpauth://totp/"+encodeURIComponent(ISSUER+":"+ACCOUNT)+"?secret="+secret+"&issuer="+encodeURIComponent(ISSUER)+"&algorithm=SHA1&digits=6&period=30"}}
export async function confirmTotpNative(env,uid,input){const x=await firestoreGet(REF);if(x?.password?.hash||x?.totpSecretEncrypted)await requireAdminSessionNative(uid);const secret=x?.pendingTotpSecretEncrypted?dec(env,x.pendingTotpSecretEncrypted):x?.pendingTotpSecret;if(!secret||!valid(secret,input))throw new Error("Invalid authenticator code");await firestoreSet(REF,{totpSecretEncrypted:enc(env,secret),pendingTotpSecretEncrypted:null,pendingTotpSecret:null,updatedBy:uid,updatedAt:new Date()},true);return securityStatusNative()}
async function auditUnlock(uid, action, success, details = {}) {
  await firestoreCreate("adminAuditLogs/" + crypto.randomUUID(), {
    uid, action, success, createdAt: new Date(), ...details
  });
}
export async function unlockAdminNative(env, uid, password, otp) {
  const attemptPath = "adminUnlockAttempts/" + uid;
  const attempt = await firestoreGet(attemptPath);
  const now = Date.now();
  const lockedUntil = timestampMillis(attempt?.lockedUntil);
  if (Number.isFinite(lockedUntil) && lockedUntil > now) {
    throw Object.assign(new Error("admin_unlock_locked"), { httpStatus: 429, safeError: "admin_unlock_locked" });
  }
  const config = await firestoreGet(REF);
  const secret = config?.totpSecretEncrypted ? dec(env, config.totpSecretEncrypted) : config?.totpSecret;
  const hasPassword = Boolean(config?.password?.hash);
  const hasTotp = Boolean(secret);
  if (!hasPassword && !hasTotp) throw new Error("Configure an admin password or authenticator before unlocking");
  const passwordValid = !hasPassword || pwdOk(password, config.password);
  const otpValid = !hasTotp || valid(secret, otp);
  if (!passwordValid || !otpValid) {
    const failures = Number(attempt?.failures || 0) + 1;
    const lockUntil = failures >= 5 ? new Date(now + 15 * 60 * 1000) : null;
    await firestoreSet(attemptPath, { uid, failures, lastFailureAt: new Date(now), lockedUntil: lockUntil }, true);
    await auditUnlock(uid, "admin_unlock_failed", false, { failures, lockedUntil: lockUntil });
    throw Object.assign(new Error(lockUntil ? "admin_unlock_locked" : "admin_unlock_invalid"), {
      httpStatus: lockUntil ? 429 : 401,
      safeError: lockUntil ? "admin_unlock_locked" : "admin_unlock_invalid"
    });
  }
  const verifiedAt = new Date(now);
  const expiresAt = new Date(now + 30 * 60 * 1000);
  await firestoreSet(attemptPath, { uid, failures: 0, lockedUntil: null, lastSuccessAt: verifiedAt }, true);
  await auditUnlock(uid, "admin_unlock_succeeded", true, { expiresAt });
  await firestoreSet("adminStepUp/" + uid, { uid, verifiedAt, lastActivityAt: verifiedAt, expiresAt }, true);
  return { ok: true, verifiedAt: verifiedAt.getTime(), expiresAt: expiresAt.toISOString(), expiresInSeconds: 1800 };
}
export async function biometricStatusNative(){const x=await firestoreGet(REF);return {configured:Boolean(x?.biometric?.publicKey)}}
export async function removeBiometricNative(uid){await firestoreSet(REF,{biometric:null,updatedBy:uid,updatedAt:new Date()},true);return {ok:true,configured:false}}
export async function registerBiometricNative(uid,keyValue){if(!keyValue||String(keyValue).length>10000)throw new Error("biometric_public_key_required");await firestoreSet(REF,{biometric:{publicKey:String(keyValue),registeredAt:new Date(),registeredBy:uid}},true);return biometricStatusNative()}
export async function beginBiometricNative(uid){const id=crypto.randomBytes(24).toString("base64url"),challenge=crypto.randomBytes(32).toString("base64");await firestoreSet("adminBiometricChallenges/"+id,{uid,challenge,expiresAt:new Date(Date.now()+300000),createdAt:new Date()},false);return {challengeId:id,challenge}}
export async function verifyBiometricNative(uid,id,sig){const x=await firestoreGet("adminBiometricChallenges/"+id),s=await firestoreGet(REF),pub=s?.biometric?.publicKey;if(!x||x.uid!==uid)throw new Error("biometric_challenge_invalid");if(timestampMillis(x.expiresAt)<Date.now())throw new Error("biometric_challenge_expired");if(!pub)throw new Error("biometric_not_configured");const v=crypto.createVerify("SHA256");v.update(Buffer.from(String(x.challenge),"base64"));v.end();if(!v.verify({key:Buffer.from(String(pub),"base64"),format:"der",type:"spki"},Buffer.from(String(sig||""),"base64")))throw new Error("biometric_verification_failed");await firestoreDelete("adminBiometricChallenges/"+id);const verifiedAt=new Date(),expiresAt=new Date(verifiedAt.getTime()+1800000);await firestoreSet("adminStepUp/"+uid,{uid,verifiedAt,expiresAt},true);return {ok:true,verifiedAt:verifiedAt.getTime(),expiresAt:expiresAt.toISOString(),expiresInSeconds:1800}}
