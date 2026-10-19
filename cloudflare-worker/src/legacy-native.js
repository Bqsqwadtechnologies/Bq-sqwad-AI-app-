import { firestoreGet, firestoreSet, firestoreCreate, firestoreDelete, firestoreQuery, firestoreCommit } from "./firebase.js";
import { runProvider, callTavily, DEFAULT_MODELS } from "./providers.js";

const activeStates=new Set(["active","trialing","grace"]);
const clean=(v,n=12000)=>String(v??"").trim().slice(0,n);

const SCOPE_WORKSPACES = {
  learning: { title: "Learning Studio", job: "learning, lessons, practice, quizzes, homework help, revision and exam preparation" },
  truthguard: { title: "TruthGuard AI", job: "checking claims against evidence and sources" },
  life_mathis: { title: "Life Mathis", job: "life guidance, reflection and personal decisions" },
  companion: { title: "AI Companion", job: "companionship, supportive conversation and check-ins" },
  personal_intelligence: { title: "Personal Intelligence", job: "planning, organizing, goals and priorities" },
  data_analyst: { title: "Data Analyst", job: "analyzing datasets, spreadsheets, statistics and reports" },
  frontier_lab: { title: "Frontier Lab", job: "experiments, hypotheses, prototypes and simulations" },
  video: { title: "Video Generation", job: "generating video clips and animations" }
};

function normalizeScopeWorkspace(value) {
  const key = String(value || "").toLowerCase().replace(/[^a-z]/g, "");
  const aliases = {
    learningstudio: "learning", learning: "learning", explorer: "learning", learningstudiocapability: "learning",
    truthguard: "truthguard", truthguardai: "truthguard", truthguardcheck: "truthguard",
    lifemathis: "life_mathis", lifemathisworkspace: "life_mathis", lifematters: "life_mathis", lifemattersworkspace: "life_mathis",
    companion: "companion", aicompanion: "companion",
    personal: "personal_intelligence", personalintelligence: "personal_intelligence",
    dataanalyst: "data_analyst", dataanalysis: "data_analyst", dataanalystworkspace: "data_analyst",
    frontier: "frontier_lab", frontierlab: "frontier_lab",
    video: "video", videogeneration: "video", videoworkspace: "video"
  };
  return aliases[key] || null;
}

export function classifyWorkspaceScope(workspace, prompt) {
  const key = normalizeScopeWorkspace(workspace);
  if (!key) return { inScope: true };
  const text = clean(prompt, 4000).toLowerCase();
  if (!text) return { inScope: false, redirect: { workspace: "CHAT", message: "Please enter a request. Main Chat can help you choose the right workspace." } };

  const taskRedirect = () => {
    if (/\b(news|latest headlines|current events|today's events|weather forecast|what happened today)\b/.test(text)) return ["WEB_SEARCH", "Web Search"];
    if (/\b(generate|create|make|edit|render)\b.{0,30}\b(video|clip|animation)\b|\b(video|clip|animation)\b.{0,30}\b(generate|create|edit|render)\b/.test(text)) return ["VIDEO_GENERATION", "Video Generation"];
    if (/\b(dataset|spreadsheet|csv|xlsx|statistics|data analysis|analyze data|analyse data|chart|trend analysis)\b/.test(text)) return ["DATA_ANALYST", "Data Analyst"];
    if (/\b(plan my|schedule|organize|organise|prioritize|prioritise|to-do list|task list|routine|calendar plan|set goals)\b/.test(text)) return ["PERSONAL_INTELLIGENCE", "Personal Intelligence"];
    if (/\b(fact.?check|verify this claim|is it true|check this claim|misinformation|source check)\b/.test(text)) return ["TRUTHGUARD", "TruthGuard AI"];
    if (/\b(lesson|homework|exam revision|quiz me|teach me|explain this topic|study plan)\b/.test(text)) return ["LEARNING", "Learning Studio"];
    return ["CHAT", "Main Chat"];
  };

  const clearlyUnrelated = /\b(write me (a )?(love )?poem|love poem|tell me (the )?news|latest news|weather forecast|build (me )?(an? )?app|write (me )?(an? )?email|send (an? )?email|tell me a joke|make me laugh|generate (an? )?image|draw (me )|small talk|code (an? )?app|create (a )?website)\b/.test(text);
  let inScope = false;
  switch (key) {
    case "learning":
      inScope = !clearlyUnrelated && /\b(teach|learn|explain|lesson|practice|quiz|homework|revise|revision|exam|study|solve|understand|formula|equation|definition|notes|school|subject|past paper|flashcards|test me|mathematics|math|algebra|geometry|calculus|physics|chemistry|biology|photosynthesis|mitosis|gravity|newton|fractions|grammar|literature|geography|history|science|essay assignment)\b/.test(text);
      break;
    case "truthguard":
      inScope = !clearlyUnrelated && (/\b(claim|verify|fact.?check|is it true|true or false|check whether|check if|evidence|sources|hoax|rumou?r|misinformation|debunk|reliable source|is .* true|did .* really)\b/.test(text) || (!/^(what|who|where|when|why|how|write|create|generate|make|build|explain|tell me|give me)\b/.test(text) && /\b(is|are|was|were|has|have|causes?|caused|leads? to|proves?|orbits?|contains?|shows?|claims?|reports?)\b/.test(text)));
      break;
    case "life_mathis":
      inScope = !clearlyUnrelated && /\b(life advice|life guidance|personal decision|relationship|career|personal growth|habit|motivation|stress|wellbeing|well-being|difficult decision|family issue|life challenge|confidence|boundaries|coping|what should i do)\b/.test(text);
      break;
    case "companion":
      inScope = !clearlyUnrelated && /\b(hi|hello|hey|how are you|talk to me|check.?in|i feel|i am feeling|i'm feeling|i had a bad day|lonely|sad|worried|anxious|encourage|motivate|celebrate|thank you|thanks|need someone to talk|support me|keep me company)\b/.test(text);
      break;
    case "personal_intelligence":
      inScope = !clearlyUnrelated && /\b(plan|planning|schedule|organize|organise|prioritize|prioritise|to-do|task list|goal|routine|calendar|time management|break down|project plan|weekly plan|daily plan)\b/.test(text);
      break;
    case "data_analyst":
      inScope = !clearlyUnrelated && /\b(analy[sz]e|analysis|dataset|data|spreadsheet|csv|xlsx|statistics|chart|trend|report|sql|metrics|calculate|correlation|average|median|regression|visuali[sz]e)\b/.test(text);
      break;
    case "frontier_lab":
      inScope = !clearlyUnrelated && /\b(experiment|hypothesis|prototype|simulation|benchmark|lab|research design|test an idea|scientific model|proof of concept|feasibility|experimental)\b/.test(text);
      break;
    case "video":
      inScope = !clearlyUnrelated && /\b(generate video|create video|video clip|animation|animated clip|video prompt|scene sequence|text.to.video|video generation)\b/.test(text);
      break;
  }
  if (inScope) return { inScope: true };
  // Learning intentionally redirects unrelated requests to Main Chat, including news,
  // writing and general conversation, rather than acting as a general chatbot.
  const [destination, destinationName] = key === "learning" ? ["CHAT", "Main Chat"] : taskRedirect();
  return {
    inScope: false,
    redirect: {
      workspace: destination,
      message: SCOPE_WORKSPACES[key].title + " is only for " + SCOPE_WORKSPACES[key].job + ". For this request, please open " + destinationName + "."
    }
  };
}

export function workspaceScopeSystemPrompt(workspace) {
  const key = normalizeScopeWorkspace(workspace);
  if (!key) return "";
  const { title, job } = SCOPE_WORKSPACES[key];
  return "STRICT WORKSPACE BOUNDARY: You are " + title + ". Only perform this workspace's job: " + job + ". Do not act as Main Chat or perform unrelated tasks, even if the user insists. If a request is outside scope, politely say this workspace is only for " + job + " and direct the user to Main Chat. Never fabricate a result for an out-of-scope task.";
}

export async function workspaceScopeRedirect(workspace, prompt) {
  const key = normalizeScopeWorkspace(workspace);
  if (!key) return null;
  const stored = await firestoreGet("adminAdvancedOptions/workspace_scope_guards") || {};
  const flags = stored.enabledByWorkspace || stored.config?.enabledByWorkspace || {};
  if (flags[key] === false) return null;
  const result = classifyWorkspaceScope(key, prompt);
  return result.inScope ? null : result.redirect;
}
async function section(name){const x=await firestoreGet("planCatalog/config");return Array.isArray(x?.catalog?.sections?.[name])?x.catalog.sections[name]:[]}
async function adminTest(user,env){const claims=user?.customAttributes||{},email=String(user?.email||"").toLowerCase(),owner=String(env?.SUPER_ADMIN_EMAIL||"").toLowerCase();const root=(Boolean(owner)&&email===owner&&user?.emailVerified===true)||claims.superAdmin===true||claims.role==="super_admin";if(!root)return false;return (await firestoreGet("adminAdvancedOptions/admin_testing_mode"))?.enabled===true}
async function gate(user,env,fallback){const e=await firestoreGet("entitlements/"+user.uid),test=await adminTest(user,env),state=String(e?.state||e?.status||"").toLowerCase(),expiry=e?.expiresAt?Date.parse(String(e.expiresAt)):NaN,notExpired=!e?.expiresAt||(Number.isFinite(expiry)&&expiry>Date.now()),workspace=fallback==="companionfree"?"companion":fallback==="explorer"?"learning":fallback==="truthguardfree"?"truthguard":"",allowed={companion:new Set(["companionfree","companionbasic","companionplus","companionpremium","companionultimate"]),learning:new Set(["explorer","learnscholar","learnfamily","learnschool"]),truthguard:new Set(["truthguardfree","tgplus","tgpro","tgteam"])},entitledPlan=String(e?.planId||"").trim().toLowerCase(),active=activeStates.has(state)&&notExpired&&Boolean(workspace&&allowed[workspace]?.has(entitledPlan));return {planId:test?"admin-unlimited":(active?entitledPlan:fallback),adminTestMode:test}}

export async function companionCatalog(user,env){const g=await gate(user,env,"companionfree"),p=await section("companion");return {ok:true,activePlanId:g.planId,adminTestMode:g.adminTestMode,plans:p.map(x=>({id:x.id,name:x.name,description:x.description||"",price:x.price||{},features:x.features||[],limits:x.limits||{},basePlans:x.basePlans||[],billingPeriods:x.billingPeriods||[],trialDays:x.trialDays||0,playEligible:x.playEligible===true,productId:x.productId||null}))}}
export async function companionProfile(uid){const x=await firestoreGet("companionProfiles/"+uid)||{};return {ok:true,profile:{personality:String(x.personality||"Warm"),name:String(x.name||"BQ Companion"),memoryEnabled:x.memoryEnabled!==false,checkInsEnabled:x.checkInsEnabled!==false,voiceEnabled:x.voiceEnabled===true,cameraEnabled:x.cameraEnabled===true,updatedAt:x.updatedAt||null}}}
export async function saveCompanionProfile(uid,b){const current=await firestoreGet("companionProfiles/"+uid)||{};const p={uid,name:clean(b.name??current.name??"BQ Companion",60),personality:clean(b.personality??current.personality??"Warm",40),memoryEnabled:b.memoryEnabled===undefined?current.memoryEnabled!==false:b.memoryEnabled!==false,checkInsEnabled:b.checkInsEnabled===undefined?current.checkInsEnabled!==false:b.checkInsEnabled!==false,voiceEnabled:b.voiceEnabled===undefined?current.voiceEnabled===true:b.voiceEnabled===true,cameraEnabled:b.cameraEnabled===undefined?current.cameraEnabled===true:b.cameraEnabled===true,updatedAt:new Date()};await firestoreSet("companionProfiles/"+uid,p,true);return {ok:true,profile:p}}
export async function companionHistory(uid){const a=await firestoreQuery("companionMessages",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:uid}}],limit:100});a.sort((x,y)=>String(y.createdAt||"").localeCompare(String(x.createdAt||"")));return {ok:true,items:a.map(x=>({id:String(x.__name||"").split("/").pop(),...x}))}}
export async function companionChat(env,user,b,charge,commit,reverse){
  const m=clean(b.message);
  if(!m)throw new Error("message_required");
  const redirect = await workspaceScopeRedirect("companion", m);
  if (redirect) return { ok: true, redirect };
  const r=await charge(user.uid,Number(b.creditCost||0),"companion",user);
  try{
    const p=await firestoreGet("companionProfiles/"+user.uid)||{};
    const settings=await firestoreGet("companionSettings/"+user.uid)||{};
    const allowedPersonality=new Set(["Warm","Caring","Encouraging","Calm"]);
    const requestedPersonality=clean(b.personality||p.personality||"Warm",40);
    const personality=allowedPersonality.has(requestedPersonality)?requestedPersonality:"Warm";
    const history=settings.saveHistory===false||p.memoryEnabled===false?[]:await firestoreQuery("companionMessages",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:user.uid}}],limit:20});
    history.sort((x,y)=>String(x.createdAt||"").localeCompare(String(y.createdAt||"")));
    const memory=p.memoryEnabled===false?"":history.map(x=>String(x.role||"user")+": "+clean(x.text,1000)).join("\n");
    const z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:m,system:["You are BQ Sqwad AI Companion.",workspaceScopeSystemPrompt("companion"),"Be warm, supportive, respectful and concise.","Do not claim to be human or a real-world person.","Respect the user's boundaries and do not pressure them.","Personality: "+personality,memory?"Conversation memory:\n"+memory:""].filter(Boolean).join("\n")});
    if(settings.saveHistory!==false){
      await firestoreCreate("companionMessages/"+crypto.randomUUID(),{uid:user.uid,role:"user",text:m,createdAt:new Date()});
      await firestoreCreate("companionMessages/"+crypto.randomUUID(),{uid:user.uid,role:"assistant",text:z.text||"",createdAt:new Date(),providerId:z.providerId||null,modelId:z.modelId||null});
    }
    await commit(user.uid,r.reservationId,z.providerId||"companion");
    return {ok:true,text:z.text||"",answer:z.text||"",creditsCharged:r.amount||0};
  }catch(e){await reverse(user.uid,r.reservationId);throw e}
}
export async function companionCheckins(uid){const a=await firestoreQuery("companionCheckins",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:uid}}],limit:60});a.sort((x,y)=>String(y.createdAt||"").localeCompare(String(x.createdAt||"")));return {ok:true,items:a.map(x=>({id:String(x.__name||"").split("/").pop(),...x}))}}
export async function companionCheckin(env,user,b,charge,commit,reverse){
  const p=clean(b.prompt||"How are you doing today?",500);
  const redirect = await workspaceScopeRedirect("companion", p);
  if (redirect) return { ok: true, redirect };
  const allowedPersonality=new Set(["Warm","Caring","Encouraging","Calm"]);
  const requestedPersonality=clean(b.personality||"Warm",40);
  const personality=allowedPersonality.has(requestedPersonality)?requestedPersonality:"Warm";
  const r=await charge(user.uid,Number(b.creditCost||0),"companion_checkin",user);
  try{
    const z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:p,system:"Give a brief reflective check-in prompt. "+workspaceScopeSystemPrompt("companion")+" Personality: "+personality+". Be supportive and respectful. Do not present yourself as a human."});
    const settings=await firestoreGet("companionSettings/"+user.uid)||{};
    if(settings.saveHistory!==false){
      const id=crypto.randomUUID();
      await firestoreCreate("companionCheckins/"+id,{uid:user.uid,prompt:p,response:z.text||"",createdAt:new Date()});
    }
    await commit(user.uid,r.reservationId,z.providerId||"companion_checkin");
    return {ok:true,text:z.text||"",creditsCharged:r.amount||0};
  }catch(e){await reverse(user.uid,r.reservationId);throw e}
}
export async function companionSettings(uid){return {ok:true,settings:await firestoreGet("companionSettings/"+uid)||{notifications:true,saveHistory:true}}}
export async function saveCompanionSettings(uid,b){const p={};for(const k of ["notifications","saveHistory","safeMode","voiceName","avatarStyle"])if(b[k]!==undefined)p[k]=b[k];p.updatedAt=new Date();await firestoreSet("companionSettings/"+uid,p,true);return {ok:true}}

export async function learningCatalog(user,env){const g=await gate(user,env,"explorer"),p=await section("learning"),a=await section("learningExamAddOns");return {ok:true,activePlanId:g.planId,plans:p.map(x=>({id:x.id,name:x.name,description:x.description||"",price:x.price||{},limits:x.limits||{},features:x.features||[],basePlans:x.basePlans||[],trialDays:x.trialDays||0,playEligible:x.playEligible===true})),examAddOns:a.map(x=>({id:x.id,name:x.name,price:x.price||{},playEligible:x.playEligible===true}))}}
export async function learningProgress(uid){const a=await firestoreQuery("learningSessions",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:uid}}],limit:200}),c=a.filter(x=>String(x.status||"completed")==="completed"),subjects={};for(const x of c){const s=String(x.subject||"General");subjects[s]=(subjects[s]||0)+1}return {ok:true,lessonsCompleted:c.length,quizzesCompleted:c.filter(x=>String(x.mode||"").toLowerCase()==="quiz").length,subjects,recent:c.slice(-20).reverse()}}
export async function learningSession(env,user,b,charge,commit,reverse){const goal=clean(b.goal);if(!goal)throw new Error("learning_goal_required");const redirect=await workspaceScopeRedirect("learning",goal);if(redirect)return {ok:true,redirect};const g=await gate(user,env,"explorer"),plan=g.planId,mode=clean(b.mode||"Tutor",32).toLowerCase(),feature=clean(b.feature,40).toLowerCase();const scholar=["learnscholar","learnfamily","learnschool"].includes(plan),family=["learnfamily","learnschool"].includes(plan),school=plan==="learnschool";if(!g.adminTestMode){if(["exam","timed exam","timed_exam","past paper","past_paper"].includes(mode)&&!scholar)throw new Error("learning_feature_locked:exam_mode");const allowedFeatures={homework_help:scholar,live_teacher:scholar,worksheets:scholar,parent_dashboard:family,certificates:family,teacher_dashboard:school,classes:school};if(Object.prototype.hasOwnProperty.call(allowedFeatures,feature)&&!allowedFeatures[feature])throw new Error("learning_feature_locked:"+feature);if(plan==="explorer"){const today=new Date().toISOString().slice(0,10),rows=await firestoreQuery("learningSessions",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:user.uid}}],limit:500}),used=rows.filter(item=>{const stamp=Date.parse(String(item.createdAt||""));return Number.isFinite(stamp)&&new Date(stamp).toISOString().slice(0,10)===today}).length;if(used>=3)throw new Error("daily_capability_limit_reached:learning");}}const r=await charge(user.uid,Number(b.creditCost||0),"learning_studio",user);try{const z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:goal,system:["You are BQ Sqwad AI Learning Studio, a structured, age-appropriate learning tutor.","Teach rather than merely answer. Use clear steps, examples, checks for understanding and concise practice.","Never invent curriculum requirements, exam dates, marking schemes or school policies.",workspaceScopeSystemPrompt("learning"),"Mode: "+mode,"Subject: "+clean(b.subject||"General",80),"Plan: "+plan].join("\n")});const id=crypto.randomUUID();await firestoreCreate("learningSessions/"+id,{uid:user.uid,id,planId:plan,mode,subject:clean(b.subject||"General",80),goal,feature:feature||null,status:"completed",providerId:z.providerId||null,modelId:z.modelId||null,createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,r.reservationId,z.providerId||"learning_studio");return {ok:true,id,planId:plan,mode,subject:clean(b.subject||"General",80),text:z.text||"",answer:z.text||"",creditsCharged:r.amount||0}}catch(e){await reverse(user.uid,r.reservationId);throw e}}

export async function truthguardCatalog(user,env){const g=await gate(user,env,"truthguardfree"),p=await section("truthguard"),s=await firestoreGet("truthGuardSettings/"+user.uid);return {ok:true,activePlanId:g.planId,adminTestMode:g.adminTestMode,settings:s||{showSources:true,showReliability:true,conservativeVerdicts:true,saveHistory:true,defaultDeepResearch:false},plans:p.map(x=>({id:x.id,name:x.name,description:x.description||"",price:x.price||{},features:x.features||[],limits:x.limits||{},basePlans:x.basePlans||[],billingPeriods:x.billingPeriods||[],trialDays:x.trialDays||0,trialEligible:x.trialEligible!==false,playEligible:x.playEligible===true}))}}
export async function truthguardSettings(uid){return {ok:true,settings:await firestoreGet("truthGuardSettings/"+uid)||{showSources:true,showReliability:true,conservativeVerdicts:true,saveHistory:true,defaultDeepResearch:false}}}
export async function saveTruthguardSettings(uid,b){const o=await firestoreGet("truthGuardSettings/"+uid)||{},n={showSources:b.showSources==null?o.showSources!==false:b.showSources===true,showReliability:b.showReliability==null?o.showReliability!==false:b.showReliability===true,conservativeVerdicts:b.conservativeVerdicts==null?o.conservativeVerdicts!==false:b.conservativeVerdicts===true,saveHistory:b.saveHistory==null?o.saveHistory!==false:b.saveHistory===true,defaultDeepResearch:b.defaultDeepResearch===true,updatedAt:new Date()};await firestoreSet("truthGuardSettings/"+uid,n,true);return {ok:true,settings:{...n,updatedAt:null}}}
export async function truthguardHistory(uid){const a=await firestoreQuery("truthGuardChecks",{filters:[{field:{fieldPath:"uid"},op:"EQUAL",value:{stringValue:uid}}],limit:100});a.sort((x,y)=>String(y.createdAt||"").localeCompare(String(x.createdAt||"")));return {items:a.map(x=>({id:String(x.__name||"").split("/").pop(),claim:String(x.claim||""),analysis:String(x.analysis||""),sources:Array.isArray(x.sources)?x.sources:[],depth:String(x.depth||"standard"),planId:String(x.planId||"truthguardfree"),createdAt:x.createdAt||null}))}}
async function reserveTruthGuardMonthlyUsage(uid,monthKey,planId,limit){const path="truthGuardUsageCounters/"+uid+"_"+monthKey;for(let attempt=0;attempt<6;attempt++){const current=await firestoreGet(path);const count=Math.max(0,Number(current?.count||0));if(count>=limit)throw new Error("truthguard_monthly_limit_reached");const data={uid,monthKey,planId,count:count+1,updatedAt:new Date()};try{if(current)await firestoreCommit([{path,data,updateTime:current.__updateTime}]);else await firestoreCommit([{path,data,create:true}]);return path}catch(error){if(attempt===5)throw new Error("truthguard_usage_reservation_failed")}}throw new Error("truthguard_usage_reservation_failed")}
async function releaseTruthGuardMonthlyUsage(path){for(let attempt=0;attempt<5;attempt++){const current=await firestoreGet(path);if(!current||Number(current.count||0)<=0)return;try{await firestoreCommit([{path,data:{uid:current.uid,monthKey:current.monthKey,planId:current.planId,count:Math.max(0,Number(current.count||0)-1),updatedAt:new Date()},updateTime:current.__updateTime}]);return}catch{if(attempt===4)return}}}
export async function truthguardCheck(env,user,b,charge,commit,reverse){const claim=clean(b.claim);if(!claim)throw new Error("truthguard_claim_required");const redirect=await workspaceScopeRedirect("truthguard",claim);if(redirect)return {ok:true,redirect};const g=await gate(user,env,"truthguardfree"),plan=g.planId,deep=Boolean(b.deepResearch&&(g.adminTestMode||["tgpro","tgteam"].includes(plan))),sourceLimit=plan==="truthguardfree"?3:plan==="tgplus"?10:20,monthlyLimit=plan==="truthguardfree"?5:plan==="tgplus"?150:plan==="tgpro"?1000:Infinity,r=await charge(user.uid,Number(b.creditCost||0),"truth_guard",user);let usageCounterPath=null;try{if(!g.adminTestMode&&Number.isFinite(monthlyLimit)){const monthKey=new Date().toISOString().slice(0,7);usageCounterPath=await reserveTruthGuardMonthlyUsage(user.uid,monthKey,plan,monthlyLimit);}const s=await callTavily(env,claim,sourceLimit,deep),sources=(s.sources||[]).slice(0,sourceLimit).map(x=>({title:clean(x.title,500),url:clean(x.url,2000),snippet:clean(x.snippet,4000)})),evidence=sources.map((x,i)=>"["+(i+1)+"] "+x.title+"\nURL: "+x.url+"\n"+x.snippet).join("\n\n"),settings=await firestoreGet("truthGuardSettings/"+user.uid)||{},z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:claim,system:["You are TruthGuard AI, an evidence-first claim verification system.","Evaluate only the supplied claim and retrieved evidence.","Do not invent sources, facts, quotes, or certainty.","Return a clear verdict, confidence level, explanation, and source-grounded reasoning.","Evidence:",evidence||"(No usable sources were returned.)"].join("\n")}),id=crypto.randomUUID();if(settings.saveHistory!==false)await firestoreCreate("truthGuardChecks/"+id,{uid:user.uid,claim,analysis:z.text||"",sources,depth:deep?"deep":"standard",planId:plan,providerId:z.providerId||null,modelId:z.modelId||null,createdAt:new Date(),updatedAt:new Date()});await commit(user.uid,r.reservationId,z.providerId||"truth_guard");return {ok:true,id:settings.saveHistory!==false?id:null,claim,analysis:z.text||"",sources,planId:plan,creditsCharged:r.amount||0}}catch(e){if(usageCounterPath)await releaseTruthGuardMonthlyUsage(usageCounterPath);await reverse(user.uid,r.reservationId);throw e}}

export async function organizationGet(uid){let x=await firestoreGet("organizationWorkspaces/"+uid);if(!x){x={uid,name:"My Organization",description:"",members:[{id:uid,role:"owner",status:"active"}],channels:[],settings:{assistantEnabled:true,sharedMemoryEnabled:false},knowledge:[],createdAt:new Date(),updatedAt:new Date()};await firestoreSet("organizationWorkspaces/"+uid,x,false)}return {exists:true,...x}}
export async function organizationUpdate(uid,b){const name=clean(b.name,160);if(!name)throw new Error("organization_name_required");await firestoreSet("organizationWorkspaces/"+uid,{name,description:clean(b.description,2000),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationSettings(uid,b){const x=await organizationGet(uid),s={...(x.settings||{})};for(const k of ["assistantEnabled","sharedMemoryEnabled","sharedKnowledgeEnabled"])if(b[k]!=null)s[k]=b[k]===true;await firestoreSet("organizationWorkspaces/"+uid,{settings:s,updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationChannel(uid,b){const x=await organizationGet(uid),a=Array.isArray(x.channels)?x.channels:[];a.push({id:crypto.randomUUID(),name:clean(b.name,120),type:clean(b.type||"chat",40),enabled:true,createdAt:new Date().toISOString()});await firestoreSet("organizationWorkspaces/"+uid,{channels:a.slice(-100),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationKnowledge(uid,b){const t=clean(b.text);if(!t)throw new Error("organization_knowledge_required");const x=await organizationGet(uid),a=Array.isArray(x.knowledge)?x.knowledge:[];a.push({id:crypto.randomUUID(),text:t,source:clean(b.source||"manual",80),createdAt:new Date().toISOString()});await firestoreSet("organizationWorkspaces/"+uid,{knowledge:a.slice(-500),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationMember(uid,b){const id=clean(b.memberId,160);if(!id)throw new Error("member_id_required");const roles=new Set(["owner","admin","editor","viewer","member"]),role=roles.has(String(b.role))?String(b.role):"member",x=await organizationGet(uid),a=Array.isArray(x.members)?x.members:[],n=a.some(m=>m.id===id)?a.map(m=>m.id===id?{...m,role,status:"active"}:m):[...a,{id,role,status:"active",addedAt:new Date().toISOString()}];await firestoreSet("organizationWorkspaces/"+uid,{members:n.slice(-100),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationMemberState(uid,b){const x=await organizationGet(uid),a=Array.isArray(x.members)?x.members:[],status=String(b.status||"").toLowerCase()==="inactive"?"inactive":"active";await firestoreSet("organizationWorkspaces/"+uid,{members:a.map(m=>m.id===b.memberId&&m.role!=="owner"?{...m,status}:m),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationChannelState(uid,b){const x=await organizationGet(uid),a=Array.isArray(x.channels)?x.channels:[];await firestoreSet("organizationWorkspaces/"+uid,{channels:a.map(c=>c.id===b.channelId?{...c,enabled:b.enabled===true}:c),updatedAt:new Date()},true);return organizationGet(uid)}
export async function organizationAssistant(env,user,b){const o=await organizationGet(user.uid),m=clean(b.message);if(!m)throw new Error("message_required");const k=(o.knowledge||[]).slice(-50).map(x=>x.text).join("\n"),z=await runProvider(env,{primaryProviderId:"openai",primaryModelId:DEFAULT_MODELS.openai,fallbackProviderIds:["gemini"]},{message:m,system:["You are BQ Sqwad AI Organization Studio assistant.","Keep organization data isolated from unrelated personal workspace data.","Use only the organization profile and knowledge supplied below.","Do not invent organization facts.","Organization: "+o.name,"Description: "+o.description,"Knowledge:",k||"(none)"].join("\n")});return {ok:true,text:z.text||"",answer:z.text||"",providerId:z.providerId||null,modelId:z.modelId||null}}
