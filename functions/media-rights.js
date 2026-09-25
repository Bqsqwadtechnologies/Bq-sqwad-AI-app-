import { getFirestore, FieldValue } from "firebase-admin/firestore";

const COLLECTION = "mediaRights";

function clean(v, n=500){ return String(v ?? "").trim().slice(0,n); }

export async function createMediaRights(uid, input = {}) {
  const db=getFirestore();
  const id=clean(input.id,120) || db.collection(COLLECTION).doc().id;
  const doc={
    id, uid,
    workspaceId: clean(input.workspaceId,120) || null,
    sourceType: clean(input.sourceType,80) || "user_media",
    sourceUrl: clean(input.sourceUrl,4000) || null,
    contentTitle: clean(input.contentTitle,300) || null,
    rightsBasis: clean(input.rightsBasis,120),
    licenseReference: clean(input.licenseReference,1000) || null,
    licenseDocumentUrl: clean(input.licenseDocumentUrl,4000) || null,
    providerVerification: clean(input.providerVerification,120) || null,
    status: "pending_admin_review",
    submittedAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  };
  if (!doc.rightsBasis) throw new Error("media_rights_basis_required");
  if (["licensed","owned","public_domain"].includes(doc.rightsBasis) && !doc.licenseReference && doc.rightsBasis === "licensed") {
    throw new Error("license_reference_required");
  }
  await db.collection(COLLECTION).doc(id).set(doc);
  return { id, status: doc.status };
}

export async function getVerifiedMediaRights(uid, rightsId, workspaceId=null) {
  const snap=await getFirestore().collection(COLLECTION).doc(clean(rightsId,120)).get();
  if(!snap.exists) throw new Error("media_rights_not_found");
  const x=snap.data()||{};
  if(String(x.uid)!==String(uid)) throw new Error("media_rights_forbidden");
  if(workspaceId && String(x.workspaceId||"")!==String(workspaceId)) throw new Error("media_rights_workspace_mismatch");
  if(x.status!=="verified") throw new Error("media_rights_not_verified");
  return { id:snap.id, ...x };
}

export async function reviewMediaRights(adminUid, rightsId, decision, note="") {
  const status=["verified","rejected","needs_more_evidence"].includes(String(decision)) ? String(decision) : null;
  if(!status) throw new Error("media_rights_review_status_invalid");
  const ref=getFirestore().collection(COLLECTION).doc(clean(rightsId,120));
  const snap=await ref.get();
  if(!snap.exists) throw new Error("media_rights_not_found");
  await ref.set({
    status,
    reviewNote: clean(note,2000) || null,
    reviewedBy: adminUid,
    reviewedAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp()
  },{merge:true});
  return { id:ref.id, status };
}

export async function listPendingMediaRights() {
  const snap=await getFirestore().collection(COLLECTION).where("status","==","pending_admin_review").limit(100).get();
  return snap.docs.map(d=>({id:d.id,...d.data()}));
}

export async function assertMediaRights(uid, input={}) {
  const rightsId=clean(input.rightsId,120);
  if(!rightsId) throw new Error("media_rights_required");
  return getVerifiedMediaRights(uid,rightsId,clean(input.workspaceId,120)||null);
}
