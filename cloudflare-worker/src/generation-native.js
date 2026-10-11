import { firestoreGet, firestoreCreate } from "./firebase.js";
async function openai(env,path,init={}){const r=await fetch("https://api.openai.com/v1"+path,{...init,headers:{Authorization:"Bearer "+String(env.OPENAI_API_KEY||""),...(init.headers||{})}});const raw=await r.text();let b={};try{b=raw?JSON.parse(raw):{}}catch{}if(!r.ok)throw new Error("OpenAI HTTP "+r.status+":"+raw.slice(0,700));return b}
export async function generateImageNative(env,user,b,charge,commit,reverse){const prompt=String(b.prompt||b.message||"").trim().slice(0,12000);if(!prompt)throw new Error("image_prompt_required");const reservation=await charge(user.uid,Number(b.creditCost||0),"image_generation",user);try{const model=String(b.model||"gpt-image-2"),z=await openai(env,"/images/generations",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({model,prompt,size:String(b.size||"1024x1024"),quality:String(b.quality||"auto")})}),x=z.data?.[0];if(!x?.b64_json)throw new Error("image_generation_returned_no_image");const id=crypto.randomUUID();await firestoreCreate("imageGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",modelId:model,status:"completed",prompt,createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,reservation.reservationId,"openai");return {providerId:"openai",modelId:model,imageBase64:x.b64_json,generationId:id,creditsCharged:reservation.amount}}catch(e){await reverse(user.uid,reservation.reservationId);throw e}}
export async function editImageNative(env,user,b,charge,commit,reverse){
  const prompt=String(b.prompt||"").trim().slice(0,12000);
  const encoded=String(b.imageBase64||"").trim();
  if(!prompt)throw new Error("image_edit_prompt_required");
  if(!encoded)throw new Error("image_edit_input_required");
  const reservation=await charge(user.uid,Number(b.creditCost||0),"image_generation",user);
  try {
    const binary=atob(encoded);
    const bytes=new Uint8Array(binary.length);
    for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);
    const form=new FormData();
    form.append("model",String(b.model||"gpt-image-2"));
    form.append("prompt",prompt);
    form.append("size",String(b.size||"1024x1024"));
    form.append("quality",String(b.quality||"auto"));
    form.append("image",new Blob([bytes],{type:"image/png"}),"bq-sqwad-ai-image.png");
    const z=await openai(env,"/images/edits",{method:"POST",body:form});
    const x=z.data?.[0];
    if(!x?.b64_json)throw new Error("image_edit_returned_no_image");
    const id=crypto.randomUUID();
    await firestoreCreate("imageGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",modelId:String(b.model||"gpt-image-2"),status:"completed",prompt,operation:"edit",createdAt:new Date(),updatedAt:new Date()});
    await commit(user.uid,reservation.reservationId,"openai");
    return {providerId:"openai",modelId:String(b.model||"gpt-image-2"),imageBase64:x.b64_json,generationId:id,creditsCharged:reservation.amount};
  } catch(e) { await reverse(user.uid,reservation.reservationId); throw e; }
}
export async function createVideoNative(env,user,b,charge,commit,reverse){const prompt=String(b.prompt||"").trim().slice(0,12000);if(!prompt)throw new Error("video_prompt_required");const reservation=await charge(user.uid,Number(b.creditCost||0),"video_generation",user);try{const form=new FormData();form.append("model",String(b.model||"sora-2"));form.append("prompt",prompt);if(b.seconds)form.append("seconds",String(b.seconds));if(b.size)form.append("size",String(b.size));const z=await openai(env,"/videos",{method:"POST",body:form}),id=crypto.randomUUID();await firestoreCreate("videoGenerationJobs/"+id,{uid:user.uid,jobId:id,provider:"openai",providerVideoId:String(z.id||""),modelId:String(z.model||b.model||"sora-2"),status:String(z.status||"queued"),progress:Number(z.progress||0),prompt,seconds:String(z.seconds||b.seconds||""),size:String(z.size||b.size||""),createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,reservation.reservationId,"openai");return {jobId:id,providerVideoId:z.id,status:z.status,progress:Number(z.progress||0),modelId:z.model||b.model||"sora-2",creditsCharged:reservation.amount}}catch(e){await reverse(user.uid,reservation.reservationId);throw e}}
export async function getVideoNative(env,user,jobId){const x=await firestoreGet("videoGenerationJobs/"+jobId);if(!x||x.uid!==user.uid)throw new Error("video_job_not_found");const z=await openai(env,"/videos/"+encodeURIComponent(String(x.providerVideoId)),{method:"GET"});return {jobId,...x,status:String(z.status||x.status||"queued"),progress:Number(z.progress||0),error:z.error?.message||null}}
export async function getVideoContentNative(env,user,jobId){const x=await firestoreGet("videoGenerationJobs/"+jobId);if(!x||x.uid!==user.uid)throw new Error("video_job_not_found");const r=await fetch("https://api.openai.com/v1/videos/"+encodeURIComponent(String(x.providerVideoId))+"/content",{headers:{Authorization:"Bearer "+String(env.OPENAI_API_KEY||"")}});if(!r.ok)throw new Error("video_content_download_failed");return new Response(r.body,{status:200,headers:{"content-type":r.headers.get("content-type")||"video/mp4","cache-control":"private, no-store"}})}
