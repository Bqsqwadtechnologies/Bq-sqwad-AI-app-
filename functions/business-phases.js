import { getFirestore, FieldValue } from "firebase-admin/firestore";

const db = () => getFirestore();

const clean = (v, max = 500) => String(v ?? "").trim().slice(0, max);
const arr = (v, max = 100) => Array.isArray(v) ? v.map(x => clean(x, 300)).filter(Boolean).slice(0, max) : [];
const obj = v => v && typeof v === "object" && !Array.isArray(v) ? v : {};

export const BUSINESS_COLLECTIONS = Object.freeze({
  workspaces: "businessWorkspaces",
  members: "businessWorkspaceMembers",
  assistants: "businessAssistants",
  knowledge: "businessKnowledge",
  contacts: "businessContacts",
  leads: "businessLeads",
  pipelines: "businessPipelines",
  appointments: "businessAppointments",
  activities: "businessCrmActivities",
  faqs: "businessFaqs",
  vault: "businessVault",
  inbox: "businessInbox",
  automations: "businessAutomations",
  leadCaptures: "businessLeadCaptures"
});

function workspaceRef(id) { return db().collection(BUSINESS_COLLECTIONS.workspaces).doc(id); }

async function memberRole(uid, workspaceId) {
  const snap = await db().collection(BUSINESS_COLLECTIONS.members).doc(workspaceId + "_" + uid).get();
  if (snap.exists && snap.data()?.status !== "disabled") return String(snap.data()?.role || "member");
  const own = await workspaceRef(workspaceId).get();
  if (own.exists && own.data()?.ownerUid === uid) return "owner";
  return null;
}

export async function requireBusinessAccess(uid, workspaceId, minimum = "member") {
  const id = clean(workspaceId, 120);
  if (!id) throw new Error("business_workspace_required");
  const role = await memberRole(uid, id);
  const ranks = { viewer: 1, member: 2, manager: 3, admin: 4, owner: 5 };
  if (!role || (ranks[role] || 0) < (ranks[minimum] || 1)) throw new Error("business_workspace_access_denied");
  return role;
}

async function ensureWorkspace(uid, input = {}) {
  const requested = clean(input.workspaceId, 120);
  if (requested) {
    await requireBusinessAccess(uid, requested);
    return requested;
  }
  const own = await db().collection(BUSINESS_COLLECTIONS.workspaces).where("ownerUid", "==", uid).limit(1).get();
  if (!own.empty) return own.docs[0].id;
  const ref = db().collection(BUSINESS_COLLECTIONS.workspaces).doc();
  const now = FieldValue.serverTimestamp();
  await ref.set({
    ownerUid: uid,
    name: clean(input.name, 180) || "My Business",
    slug: clean(input.slug, 100) || ref.id,
    planId: clean(input.planId, 100) || "bizstarter",
    status: "active",
    settings: {
      timezone: clean(obj(input.settings).timezone, 80) || "UTC",
      currency: "USD",
      leadStatuses: ["new", "contacted", "qualified", "proposal", "won", "lost"]
    },
    usage: { messages: 0, contacts: 0, leads: 0, knowledgeItems: 0 },
    createdAt: now,
    updatedAt: now
  });
  await db().collection(BUSINESS_COLLECTIONS.members).doc(ref.id + "_" + uid).set({
    workspaceId: ref.id, uid, role: "owner", status: "active", permissions: ["*"],
    createdAt: now, updatedAt: now
  });
  return ref.id;
}

function sanitizeDoc(id, data) {
  const x = { id, ...data };
  if (x.createdAt?.toDate) x.createdAt = x.createdAt.toDate().toISOString();
  if (x.updatedAt?.toDate) x.updatedAt = x.updatedAt.toDate().toISOString();
  return x;
}

async function listOwned(collection, workspaceId, limit = 100) {
  const snap = await db().collection(collection).where("workspaceId", "==", workspaceId).limit(Math.min(200, Math.max(1, limit))).get();
  return snap.docs.map(d => sanitizeDoc(d.id, d.data())).sort((a,b) => String(b.updatedAt || b.createdAt || "").localeCompare(String(a.updatedAt || a.createdAt || "")));
}

export async function businessWorkspace(uid, input = {}) {
  const workspaceId = await ensureWorkspace(uid, input);
  await requireBusinessAccess(uid, workspaceId);
  const [workspace, members, assistants, knowledge, contacts, leads, pipelines, appointments] = await Promise.all([
    workspaceRef(workspaceId).get(),
    listOwned(BUSINESS_COLLECTIONS.members, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.assistants, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.knowledge, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.contacts, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.leads, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.pipelines, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.appointments, workspaceId)
  ]);
  if (!workspace.exists) throw new Error("business_workspace_not_found");
  const workspaceData = sanitizeDoc(workspace.id, workspace.data());
  return {
    workspaceId,
    ...workspaceData,
    workspace: workspaceData,
    assistantName: assistants[0]?.name || "Business AI Assistant",
    members, assistants, knowledge, contacts, leads, pipelines, appointments,
    trainingAssets: [],
    inbox: [],
    automations: [],
    analytics: {
      members: members.length, assistants: assistants.length, knowledge: knowledge.length,
      contacts: contacts.length, leads: leads.length, pipelines: pipelines.length, appointments: appointments.length,
      requests: 0
    }
  };
}

export async function createBusinessRecord(uid, type, input) {
  const map = {
    assistant: BUSINESS_COLLECTIONS.assistants,
    knowledge: BUSINESS_COLLECTIONS.knowledge,
    contact: BUSINESS_COLLECTIONS.contacts,
    lead: BUSINESS_COLLECTIONS.leads,
    pipeline: BUSINESS_COLLECTIONS.pipelines,
    appointment: BUSINESS_COLLECTIONS.appointments,
    activity: BUSINESS_COLLECTIONS.activities
  };
  const collection = map[type];
  if (!collection) throw new Error("unsupported_business_record");
  const workspaceId = await ensureWorkspace(uid, input);
  await requireBusinessAccess(uid, workspaceId, type === "activity" ? "member" : "manager");
  const ref = db().collection(collection).doc();
  const payload = { ...obj(input), workspaceId, ownerUid: uid, createdBy: uid, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() };
  delete payload.workspaceId;
  payload.workspaceId = workspaceId;
  if (type === "assistant") {
    payload.name = clean(input.name, 160) || "Business Assistant";
    payload.instructions = clean(input.instructions, 12000);
    payload.tools = arr(input.tools);
    payload.enabled = input.enabled !== false;
  } else if (type === "knowledge") {
    payload.title = clean(input.title, 200);
    payload.content = clean(input.content, 30000);
    payload.type = clean(input.type, 40) || "note";
    payload.tags = arr(input.tags);
    payload.sourceUrl = clean(input.sourceUrl, 1000);
  } else if (type === "contact") {
    payload.name = clean(input.name, 180);
    payload.email = clean(input.email, 320);
    payload.phone = clean(input.phone, 80);
    payload.company = clean(input.company, 180);
    payload.tags = arr(input.tags);
  } else if (type === "lead") {
    payload.contactId = clean(input.contactId, 120);
    payload.name = clean(input.name, 180);
    payload.email = clean(input.email, 320);
    payload.phone = clean(input.phone, 80);
    payload.status = clean(input.status, 40) || "new";
    payload.value = Math.max(0, Number(input.value || 0));
    payload.notes = clean(input.notes, 6000);
    payload.source = clean(input.source, 120);
    payload.tags = arr(input.tags);
  } else if (type === "pipeline") {
    payload.name = clean(input.name, 160) || "Sales Pipeline";
    payload.stages = Array.isArray(input.stages) && input.stages.length ? input.stages.slice(0, 30).map((s,i) => ({ id: clean(s.id,60) || "stage" + (i+1), name: clean(s.name,120) || "Stage " + (i+1), order: i })) : [
      {id:"new",name:"New",order:0},{id:"qualified",name:"Qualified",order:1},{id:"proposal",name:"Proposal",order:2},{id:"won",name:"Won",order:3},{id:"lost",name:"Lost",order:4}
    ];
  } else if (type === "appointment") {
    payload.title = clean(input.title, 200);
    payload.contactId = clean(input.contactId, 120);
    payload.startAt = clean(input.startAt, 80);
    payload.endAt = clean(input.endAt, 80);
    payload.status = clean(input.status, 40) || "scheduled";
    payload.notes = clean(input.notes, 6000);
  } else {
    payload.entityType = clean(input.entityType, 60);
    payload.entityId = clean(input.entityId, 120);
    payload.action = clean(input.action, 120);
    payload.note = clean(input.note, 3000);
  }
  await ref.set(payload);
  await workspaceRef(workspaceId).set({ updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return sanitizeDoc(ref.id, (await ref.get()).data());
}

export async function updateBusinessRecord(uid, type, id, input) {
  const map = { assistant: BUSINESS_COLLECTIONS.assistants, knowledge: BUSINESS_COLLECTIONS.knowledge, contact: BUSINESS_COLLECTIONS.contacts, lead: BUSINESS_COLLECTIONS.leads, pipeline: BUSINESS_COLLECTIONS.pipelines, appointment: BUSINESS_COLLECTIONS.appointments };
  const collection = map[type];
  if (!collection || !id) throw new Error("unsupported_business_record");
  const ref = db().collection(collection).doc(id);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_record_not_found");
  const workspaceId = clean(snap.data()?.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "manager");
  const allowed = obj(input);
  delete allowed.workspaceId; delete allowed.ownerUid; delete allowed.createdBy; delete allowed.createdAt; delete allowed.updatedAt; delete allowed.id;
  allowed.updatedAt = FieldValue.serverTimestamp();
  await ref.set(allowed, { merge: true });
  return sanitizeDoc(id, (await ref.get()).data());
}

export async function deleteBusinessRecord(uid, type, id) {
  const map = { assistant: BUSINESS_COLLECTIONS.assistants, knowledge: BUSINESS_COLLECTIONS.knowledge, contact: BUSINESS_COLLECTIONS.contacts, lead: BUSINESS_COLLECTIONS.leads, pipeline: BUSINESS_COLLECTIONS.pipelines, appointment: BUSINESS_COLLECTIONS.appointments };
  const collection = map[type];
  if (!collection || !id) throw new Error("unsupported_business_record");
  const ref = db().collection(collection).doc(id);
  const snap = await ref.get();
  if (!snap.exists) throw new Error("business_record_not_found");
  await requireBusinessAccess(uid, clean(snap.data()?.workspaceId, 120), "manager");
  await ref.delete();
  return { ok: true, id };
}

export async function addBusinessMember(uid, input) {
  const workspaceId = clean(input.workspaceId, 120);
  await requireBusinessAccess(uid, workspaceId, "admin");
  const memberUid = clean(input.uid, 160);
  if (!memberUid) throw new Error("member_uid_required");
  const role = ["viewer","member","manager","admin"].includes(clean(input.role,30)) ? clean(input.role,30) : "member";
  const ref = db().collection(BUSINESS_COLLECTIONS.members).doc(workspaceId + "_" + memberUid);
  await ref.set({workspaceId, uid: memberUid, role, status:"active", permissions:arr(input.permissions,100), invitedBy:uid, updatedAt:FieldValue.serverTimestamp(), createdAt:FieldValue.serverTimestamp()},{merge:true});
  return sanitizeDoc(ref.id, (await ref.get()).data());
}


export async function listBusinessExtended(uid, workspaceId) {
  await requireBusinessAccess(uid, workspaceId);
  const [faqs, vault, inbox, automations, leadCaptures, activities] = await Promise.all([
    listOwned(BUSINESS_COLLECTIONS.faqs, workspaceId), listOwned(BUSINESS_COLLECTIONS.vault, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.inbox, workspaceId), listOwned(BUSINESS_COLLECTIONS.automations, workspaceId),
    listOwned(BUSINESS_COLLECTIONS.leadCaptures, workspaceId), listOwned(BUSINESS_COLLECTIONS.activities, workspaceId)
  ]);
  const ws = await workspaceRef(workspaceId).get(); const usage = ws.exists ? (ws.data()?.usage || {}) : {};
  return { faqs, vault, inbox, automations, leadCaptures, activities, usage, forecast: { messagesUsed:Number(usage.messages||0), contactsUsed:Number(usage.contacts||0), leadsUsed:Number(usage.leads||0), knowledgeUsed:Number(usage.knowledgeItems||0), period:'current' } };
}

export async function createBusinessExtended(uid, type, input) {
  const map={faq:BUSINESS_COLLECTIONS.faqs,vault:BUSINESS_COLLECTIONS.vault,inbox:BUSINESS_COLLECTIONS.inbox,automation:BUSINESS_COLLECTIONS.automations,leadCapture:BUSINESS_COLLECTIONS.leadCaptures};
  const collection=map[type]; if(!collection) throw new Error('unsupported_business_extended_record');
  const workspaceId=await ensureWorkspace(uid,input); await requireBusinessAccess(uid,workspaceId,'manager');
  const ref=db().collection(collection).doc(); const now=FieldValue.serverTimestamp(); const p={workspaceId,ownerUid:uid,createdBy:uid,createdAt:now,updatedAt:now};
  if(type==='faq'){p.question=clean(input.question,1000);p.answer=clean(input.answer,12000);p.tags=arr(input.tags);if(!p.question||!p.answer)throw new Error('faq_question_and_answer_required');}
  else if(type==='vault'){p.title=clean(input.title,200);p.content=clean(input.content,30000);p.visibility=clean(input.visibility,30)||'workspace';p.tags=arr(input.tags);if(!p.title||!p.content)throw new Error('vault_title_and_content_required');}
  else if(type==='inbox'){p.contactId=clean(input.contactId,120);p.channel=clean(input.channel,60)||'manual';p.sender=clean(input.sender,320);p.message=clean(input.message,12000);p.status=clean(input.status,30)||'open';if(!p.message)throw new Error('inbox_message_required');}
  else if(type==='automation'){p.name=clean(input.name,180);p.enabled=input.enabled!==false;p.trigger=obj(input.trigger);p.conditions=Array.isArray(input.conditions)?input.conditions.slice(0,30):[];p.actions=Array.isArray(input.actions)?input.actions.slice(0,50):[];if(!p.name)throw new Error('automation_name_required');}
  else {p.name=clean(input.name,180);p.source=clean(input.source,80)||'web';p.fields=Array.isArray(input.fields)?input.fields.slice(0,50):[];p.status='active';p.token=crypto.randomUUID();}
  await ref.set(p); await workspaceRef(workspaceId).set({updatedAt:now},{merge:true}); return sanitizeDoc(ref.id,(await ref.get()).data());
}

export async function updateBusinessExtended(uid,type,id,input){
  const map={faq:BUSINESS_COLLECTIONS.faqs,vault:BUSINESS_COLLECTIONS.vault,inbox:BUSINESS_COLLECTIONS.inbox,automation:BUSINESS_COLLECTIONS.automations,leadCapture:BUSINESS_COLLECTIONS.leadCaptures}; const collection=map[type]; if(!collection||!id)throw new Error('unsupported_business_extended_record');
  const ref=db().collection(collection).doc(id); const snap=await ref.get(); if(!snap.exists)throw new Error('business_record_not_found'); await requireBusinessAccess(uid,clean(snap.data()?.workspaceId,120),'manager');
  const p=obj(input); delete p.workspaceId;delete p.ownerUid;delete p.createdBy;delete p.createdAt;delete p.updatedAt;delete p.id;p.updatedAt=FieldValue.serverTimestamp(); await ref.set(p,{merge:true}); return sanitizeDoc(id,(await ref.get()).data());
}
