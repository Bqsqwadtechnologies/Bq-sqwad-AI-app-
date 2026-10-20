import { firestoreGet, firestoreCreate } from "./firebase.js";
export function validateGeneratedImageBase64(encoded) {
  const value = String(encoded || "");
  if (!value || value.length > 28 * 1024 * 1024 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length % 4 !== 0) {
    throw Object.assign(new Error("image_generation_invalid_image_data"), { safeError: "image_generation_invalid_image_data" });
  }
  let bytes;
  try { bytes = Uint8Array.from(atob(value), (character) => character.charCodeAt(0)); }
  catch { throw Object.assign(new Error("image_generation_invalid_image_data"), { safeError: "image_generation_invalid_image_data" }); }
  const png = bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 &&
    bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a;
  const jpeg = bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  const webp = bytes.length >= 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" &&
    String.fromCharCode(...bytes.slice(8, 12)) === "WEBP";
  if (!png && !jpeg && !webp) {
    throw Object.assign(new Error("image_generation_invalid_image_data"), { safeError: "image_generation_invalid_image_data" });
  }
  return true;
}
async function openai(env,path,init={}){const r=await fetch("https://api.openai.com/v1"+path,{...init,signal:init.signal||AbortSignal.timeout(60000),headers:{Authorization:"Bearer "+String(env.OPENAI_API_KEY||""),...(init.headers||{})}});const raw=await r.text();let b={};try{b=raw?JSON.parse(raw):{}}catch{}if(!r.ok)throw new Error("OpenAI HTTP "+r.status+":"+raw.slice(0,700));return b}
export async function generateImageNative(env,user,b,charge,commit,reverse,trustedCreditCost=null){const prompt=String(b.prompt||b.message||"").trim().slice(0,12000);if(!prompt)throw new Error("image_prompt_required");const reservation=await charge(user.uid,Math.max(0,Number(trustedCreditCost??0)),"image_generation",user);try{const model=String(b.model||"gpt-image-2"),z=await openai(env,"/images/generations",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({model,prompt,size:String(b.size||"1024x1024"),quality:String(b.quality||"auto")})}),x=z.data?.[0];if(!x?.b64_json)throw new Error("image_generation_returned_no_image");validateGeneratedImageBase64(x.b64_json);const id=crypto.randomUUID();await firestoreCreate("imageGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",modelId:model,status:"completed",prompt,createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,reservation.reservationId,"openai");return {providerId:"openai",modelId:model,imageBase64:x.b64_json,generationId:id,creditsCharged:reservation.amount}}catch(e){await reverse(user.uid,reservation.reservationId);throw e}}
export async function editImageNative(env,user,b,charge,commit,reverse,trustedCreditCost=null){
  const prompt=String(b.prompt||"").trim().slice(0,12000);
  const encoded=String(b.imageBase64||"").trim();
  const requestedMime=String(b.mimeType||"image/png").trim().toLowerCase();
  const mimeType=requestedMime==="image/jpg"?"image/jpeg":requestedMime;
  const extensions={"image/png":"png","image/jpeg":"jpg","image/webp":"webp"};
  if(!prompt)throw new Error("image_edit_prompt_required");
  if(!encoded)throw new Error("image_edit_input_required");
  if(!extensions[mimeType]) throw Object.assign(new Error("image_edit_type_unsupported"), { httpStatus: 415 });
  // Bound decoded-image memory use before allocating the binary buffer.
  if(encoded.length > 12000000) throw Object.assign(new Error("image_edit_input_too_large"), { httpStatus: 413 });
  const reservation=await charge(user.uid,Math.max(0,Number(trustedCreditCost??0)),"image_generation",user);
  try {
    const binary=atob(encoded);
    const bytes=new Uint8Array(binary.length);
    for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
    const form=new FormData();
    form.append("model",String(b.model||"gpt-image-2"));
    form.append("prompt",prompt);
    form.append("size",String(b.size||"1024x1024"));
    form.append("quality",String(b.quality||"auto"));
    form.append("image",new Blob([bytes],{type:mimeType}),"bq-sqwad-ai-image."+extensions[mimeType]);
    const z=await openai(env,"/images/edits",{method:"POST",body:form});
    const x=z.data?.[0];
    if(!x?.b64_json)throw new Error("image_edit_returned_no_image");
    const id=crypto.randomUUID();
    await firestoreCreate("imageGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",modelId:String(b.model||"gpt-image-2"),status:"completed",prompt,operation:"edit",createdAt:new Date(),updatedAt:new Date()});
    await commit(user.uid,reservation.reservationId,"openai");
    return {providerId:"openai",modelId:String(b.model||"gpt-image-2"),imageBase64:x.b64_json,generationId:id,creditsCharged:reservation.amount};
  } catch(e) { await reverse(user.uid,reservation.reservationId); throw e; }
}
export async function createVideoNative(env,user,b,charge,commit,reverse,trustedCreditCost=null){const prompt=String(b.prompt||"").trim().slice(0,12000);if(!prompt)throw new Error("video_prompt_required");const reservation=await charge(user.uid,Math.max(0,Number(trustedCreditCost??0)),"video_generation",user);try{const form=new FormData();form.append("model",String(b.model||"sora-2"));form.append("prompt",prompt);if(b.seconds)form.append("seconds",String(b.seconds));if(b.size)form.append("size",String(b.size));const z=await openai(env,"/videos",{method:"POST",body:form}),id=crypto.randomUUID();await firestoreCreate("videoGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",providerVideoId:String(z.id||""),modelId:String(z.model||b.model||"sora-2"),status:String(z.status||"queued"),progress:Number(z.progress||0),prompt,seconds:String(z.seconds||b.seconds||""),size:String(z.size||b.size||""),createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,reservation.reservationId,"openai");return {jobId:id,providerVideoId:z.id,status:z.status,progress:Number(z.progress||0),modelId:z.model||b.model||"sora-2",creditsCharged:reservation.amount}}catch(e){await reverse(user.uid,reservation.reservationId);throw e}}
export async function getVideoNative(env,user,jobId){const x=await firestoreGet("videoGenerationJobs/"+jobId);if(!x||x.uid!==user.uid)throw new Error("video_job_not_found");const z=await openai(env,"/videos/"+encodeURIComponent(String(x.providerVideoId)),{method:"GET"});return {jobId,...x,status:String(z.status||x.status||"queued"),progress:Number(z.progress||0),error:z.error?.message||null}}
export async function getVideoContentNative(env,user,jobId){const x=await firestoreGet("videoGenerationJobs/"+jobId);if(!x||x.uid!==user.uid)throw new Error("video_job_not_found");const r=await fetch("https://api.openai.com/v1/videos/"+encodeURIComponent(String(x.providerVideoId))+"/content",{headers:{Authorization:"Bearer "+String(env.OPENAI_API_KEY||"")},signal:AbortSignal.timeout(30000)});if(!r.ok)throw new Error("video_content_download_failed");return new Response(r.body,{status:200,headers:{"content-type":r.headers.get("content-type")||"video/mp4","cache-control":"private, no-store"}})}
