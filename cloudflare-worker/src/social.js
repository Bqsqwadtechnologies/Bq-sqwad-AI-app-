import { firestoreGet, firestoreSet, firestoreCreate, firestoreQueryCollection } from "./firebase.js";

const PLATFORMS = {
  x: {
    auth: "https://twitter.com/i/oauth2/authorize",
    token: "https://api.x.com/2/oauth2/token",
    scopes: ["tweet.read","tweet.write","users.read","offline.access"]
  },
  youtube: {
    auth: "https://accounts.google.com/o/oauth2/v2/auth",
    token: "https://oauth2.googleapis.com/token",
    scopes: ["https://www.googleapis.com/auth/youtube","https://www.googleapis.com/auth/youtube.readonly","https://www.googleapis.com/auth/youtube.force-ssl","https://www.googleapis.com/auth/youtube.upload"]
  },
  tiktok: {
    auth: "https://www.tiktok.com/v2/auth/authorize/",
    token: "https://open.tiktokapis.com/v2/oauth/token/",
    scopes: ["user.info.basic","user.info.profile","user.info.stats","video.list","video.publish"]
  }
};

const enc = new TextEncoder();

function secret(env,name){ const aliases={X_CLIENT_ID:["X_CLIENT_ID","X_API_KEY","X_client_id","X_consumer_key"],X_CLIENT_SECRET:["X_CLIENT_SECRET","X_API_SECRET","X_client_secret","X_secret_key"],YOUTUBE_CLIENT_ID:["YOUTUBE_CLIENT_ID"],YOUTUBE_CLIENT_SECRET:["YOUTUBE_CLIENT_SECRET","YouTube_OAuth_Client_Secret"],TIKTOK_CLIENT_KEY:["TIKTOK_CLIENT_KEY","TIKTOK_CLIENT_ID","TikTok_client_key"],TIKTOK_CLIENT_SECRET:["TIKTOK_CLIENT_SECRET","TIKTOK_SECRET_KEY","TikTok_client_secret"]}; const v=(aliases[name]||[name]).map(k=>env?.[k]).find(Boolean); if(!v) throw new Error(name+"_not_configured"); return String(v); }
function b64u(bytes){ let s=""; const a=new Uint8Array(bytes); for(let i=0;i<a.length;i+=0x8000)s+=String.fromCharCode(...a.subarray(i,i+0x8000)); return btoa(s).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,""); }
function bytes(s){ const p=s.replace(/-/g,"+").replace(/_/g,"/"); const b=atob(p+"=".repeat((4-p.length%4)%4)); const a=new Uint8Array(b.length); for(let i=0;i<b.length;i++)a[i]=b.charCodeAt(i); return a; }
async function key(env){ return crypto.subtle.importKey("raw",await crypto.subtle.digest("SHA-256",enc.encode(secret(env,"BQ_SOCIAL_ENCRYPTION_KEY"))),{name:"AES-GCM"},false,["encrypt","decrypt"]); }
async function seal(env,value){ const iv=crypto.getRandomValues(new Uint8Array(12)); const k=await key(env); const c=await crypto.subtle.encrypt({name:"AES-GCM",iv},k,enc.encode(value)); return {algorithm:"AES-GCM",iv:b64u(iv),ciphertext:b64u(c)}; }
async function open(env,payload){ const k=await key(env); const p=await crypto.subtle.decrypt({name:"AES-GCM",iv:bytes(payload.iv)},k,bytes(payload.ciphertext)); return new TextDecoder().decode(p); }
function clean(v,n=500){return String(v??"").trim().slice(0,n);}
function cfgName(platform){return platform==="x"?"X_CLIENT_ID":platform==="youtube"?"YOUTUBE_CLIENT_ID":"TIKTOK_CLIENT_KEY";}
function secretName(platform){return platform==="x"?"X_CLIENT_SECRET":platform==="youtube"?"YOUTUBE_CLIENT_SECRET":"TIKTOK_CLIENT_SECRET";}

export async function socialStart(env,uid,platform,redirectUri,workspaceId=''){
  const p=PLATFORMS[platform]; if(!p) throw new Error("unsupported_social_platform");
  const clientId=secret(env,cfgName(platform));
  secret(env,secretName(platform));
  const state=crypto.randomUUID();
  const verifier=platform==="x"?b64u(crypto.getRandomValues(new Uint8Array(32))):"";
  const challenge=verifier?b64u(await crypto.subtle.digest("SHA-256",enc.encode(verifier))):"";
  await firestoreCreate("socialOAuthStates/"+state,{uid,platform,redirectUri,workspaceId:String(workspaceId||""),codeVerifier:verifier||null,expiresAt:new Date(Date.now()+600000)});
  const q=new URLSearchParams({client_id:clientId,redirect_uri:redirectUri,response_type:"code",scope:p.scopes.join(" "),state});
  if(platform==="x"){q.set("code_challenge",challenge);q.set("code_challenge_method","S256");}
  if(platform==="youtube"){q.set("access_type","offline");q.set("prompt","consent");}
  if(platform==="tiktok"){q.delete("client_id");q.set("client_key",clientId);}
  return {state,authorizationUrl:p.auth+"?"+q.toString()};
}

export async function socialCallback(env,state,code){
  const st=await firestoreGet("socialOAuthStates/"+state);
  if(!st) throw new Error("oauth_state_invalid");
  if(new Date(st.expiresAt).getTime()<Date.now()) throw new Error("oauth_state_expired");
  const p=PLATFORMS[st.platform]; if(!p) throw new Error("unsupported_social_platform");
  const clientId=secret(env,cfgName(st.platform)), clientSecret=secret(env,secretName(st.platform));
  const form=new URLSearchParams({client_id:clientId,code,redirect_uri:String(st.redirectUri),grant_type:"authorization_code"});
  if(st.platform==="tiktok"){ form.delete("client_id"); form.set("client_key",clientId); }
  if(st.codeVerifier) form.set("code_verifier",st.codeVerifier);
  const headers={"Content-Type":"application/x-www-form-urlencoded"};
  if(st.platform==="x"){headers.Authorization="Basic "+btoa(clientId+":"+clientSecret);}
  else form.set("client_secret",clientSecret);
  const tokenRes=await fetch(p.token,{method:"POST",headers,body:form});
  const raw=await tokenRes.text(); let token={}; try{token=JSON.parse(raw)}catch{}
  if(!tokenRes.ok||!token.access_token) throw new Error("oauth_token_exchange_failed:"+raw.slice(0,500));
  let metadata={};
  if(st.platform==="x"){
    const me=await fetch("https://api.x.com/2/users/me?user.fields=id,name,username",{headers:{Authorization:"Bearer "+token.access_token}});
    const m=await me.json(); if(!me.ok||!m.data?.id) throw new Error("x_account_validation_failed");
    metadata={xAccount:{id:String(m.data.id),name:String(m.data.name||""),username:String(m.data.username||"")}};
  } else if(st.platform==="youtube"){
    const ch=await fetch("https://www.googleapis.com/youtube/v3/channels?part=id,snippet&mine=true",{headers:{Authorization:"Bearer "+token.access_token}});
    const body=await ch.json(); if(!ch.ok||!body.items?.[0]?.id) throw new Error("youtube_account_validation_failed");
    metadata={youtubeChannel:{id:body.items[0].id,title:body.items[0].snippet?.title||""}};
  } else if(st.platform==="tiktok"){
    const fields="open_id,union_id,avatar_url,display_name,bio_description,profile_deep_link,is_verified,username,follower_count,following_count,likes_count,video_count";
    const me=await fetch("https://open.tiktokapis.com/v2/user/info/?fields="+encodeURIComponent(fields),{headers:{Authorization:"Bearer "+token.access_token}});
    const m=await me.json(); if(!me.ok||!m.data?.user?.open_id) throw new Error("tiktok_account_validation_failed");
    metadata={tiktokProfile:m.data.user};
  }
  const id=crypto.randomUUID();
  await firestoreSet("socialAccounts/"+id,{uid:st.uid,platform:st.platform,workspaceId:String(st.workspaceId||"")||null,accessToken:await seal(env,String(token.access_token)),refreshToken:token.refresh_token?await seal(env,String(token.refresh_token)):null,expiresAt:token.expires_in?new Date(Date.now()+Number(token.expires_in)*1000):null,refreshExpiresAt:token.refresh_expires_in?new Date(Date.now()+Number(token.refresh_expires_in)*1000):null,scope:String(token.scope||""),grantedScopes:String(token.scope||"").split(/[ ,]+/).map(x=>x.trim()).filter(Boolean),metadata,status:"connected",createdAt:new Date(),updatedAt:new Date()});
  await firestoreSet("socialOAuthStates/"+state,{uid:st.uid,status:"completed",completedAt:new Date()});
  return {ok:true,id,platform:st.platform,metadata};
}

export async function tiktokProfile(env, uid, accountId, workspaceId="") {
  const accounts = await listSocialAccounts(uid, workspaceId);
  const account = accounts.find(x => x.id === String(accountId) && x.platform === "tiktok");
  if (!account) throw new Error("tiktok_account_not_found");
  const rawSnap = await firestoreGet("socialAccounts/" + account.id);
  const tokenState = await socialAccountToken(env, rawSnap);
  const fields = "open_id,union_id,avatar_url,display_name,bio_description,profile_deep_link,is_verified,username,follower_count,following_count,likes_count,video_count";
  const response = await fetch("https://open.tiktokapis.com/v2/user/info/?fields=" + encodeURIComponent(fields), {
    headers: { Authorization: "Bearer " + tokenState.access }
  });
  const raw = await response.text();
  if (!response.ok) throw new Error("tiktok_profile_failed:" + raw.slice(0, 700));
  const parsed = JSON.parse(raw);
  return parsed.data?.user || {};
}

export async function tiktokVideos(env, uid, accountId, workspaceId="") {
  const accounts = await listSocialAccounts(uid, workspaceId);
  const account = accounts.find(x => x.id === String(accountId) && x.platform === "tiktok");
  if (!account) throw new Error("tiktok_account_not_found");
  const rawSnap = await firestoreGet("socialAccounts/" + account.id);
  const tokenState = await socialAccountToken(env, rawSnap);
  const response = await fetch("https://open.tiktokapis.com/v2/video/list/?fields=id,title,video_description,cover_image_url,share_url,create_time,duration", {
    method: "POST",
    headers: { Authorization: "Bearer " + tokenState.access, "Content-Type": "application/json" },
    body: JSON.stringify({ max_count: 20 })
  });
  const raw = await response.text();
  if (!response.ok) throw new Error("tiktok_videos_failed:" + raw.slice(0, 700));
  const parsed = JSON.parse(raw);
  return { videos: parsed.data?.videos || [], cursor: parsed.data?.cursor || null, hasMore: parsed.data?.has_more === true };
}

export async function publishTikTok(env, uid, accountId, workspaceId, input = {}) {
  const accounts = await listSocialAccounts(uid, workspaceId);
  const account = accounts.find(x => x.id === String(accountId) && x.platform === "tiktok");
  if (!account) throw new Error("tiktok_account_not_found");
  const rawSnap = await firestoreGet("socialAccounts/" + account.id);
  const tokenState = await socialAccountToken(env, rawSnap);
  const granted = new Set(String(rawSnap.grantedScopes || "").split(/[ ,]+/).filter(Boolean));
  if (granted.size && !granted.has("video.publish")) throw new Error("tiktok_video_publish_scope_missing_reconnect_required");
  const mediaUrl = clean(input.mediaUrl, 4000);
  const title = clean(input.title || input.text, 2200);
  if (!mediaUrl || !title) throw new Error("tiktok_video_and_title_required");
  const creatorInfo = await fetch("https://open.tiktokapis.com/v2/post/publish/creator_info/query/", {
    method: "POST",
    headers: { Authorization: "Bearer " + tokenState.access, "Content-Type": "application/json" }
  });
  const creatorRaw = await creatorInfo.text();
  if (!creatorInfo.ok) throw new Error("tiktok_creator_info_failed:" + creatorRaw.slice(0, 700));
  const creator = JSON.parse(creatorRaw)?.data || {};
  const options = Array.isArray(creator.privacy_level_options) ? creator.privacy_level_options : [];
  const requested = clean(input.privacyLevel || "SELF_ONLY", 40);
  const privacyLevel = options.includes(requested) ? requested : (options[0] || "SELF_ONLY");
  const response = await fetch("https://open.tiktokapis.com/v2/post/publish/video/init/", {
    method: "POST",
    headers: { Authorization: "Bearer " + tokenState.access, "Content-Type": "application/json; charset=UTF-8" },
    body: JSON.stringify({
      post_info: {
        title,
        privacy_level: privacyLevel,
        disable_duet: creator.duet_disabled === true,
        disable_comment: creator.comment_disabled === true,
        disable_stitch: creator.stitch_disabled === true,
        is_aigc: input.isAigc !== false
      },
      source_info: { source: "PULL_FROM_URL", video_url: mediaUrl }
    })
  });
  const raw = await response.text();
  if (!response.ok) throw new Error("tiktok_publish_failed:" + raw.slice(0, 900));
  let result = {};
  try { result = JSON.parse(raw); } catch {}
  return { ok: true, platform: "tiktok", status: "accepted", publishId: result.data?.publish_id || null, result };
}

export async function listSocialAccounts(uid, workspaceId=""){
  const result = await firestoreQueryCollection("socialAccounts");
  const docs = Array.isArray(result?.documents) ? result.documents : [];
  return docs.map(doc => {
    const item = {};
    for (const [k,v] of Object.entries(doc.fields || {})) {
      if ("stringValue" in v) item[k]=v.stringValue;
      else if ("booleanValue" in v) item[k]=v.booleanValue;
      else if ("integerValue" in v) item[k]=Number(v.integerValue);
      else if ("timestampValue" in v) item[k]=v.timestampValue;
      else item[k]=null;
    }
    item.id = String(doc.name || "").split("/").pop();
    return item;
  }).filter(x => x.uid === uid && (!workspaceId || String(x.workspaceId || "") === String(workspaceId)))
    .map(x => ({id:x.id, platform:x.platform, workspaceId:x.workspaceId||null, organizationId:x.organizationId||null, status:x.status||"connected", metadata:x.metadata||{}}));
}

export async function socialAccountToken(env,account){
  let access=await open(env,account.accessToken);
  const expiry=new Date(account.expiresAt||0).getTime();
  if(expiry && expiry<=Date.now()+60000 && account.refreshToken){
    const platform=String(account.platform), p=PLATFORMS[platform];
    const clientId=secret(env,cfgName(platform)), clientSecret=secret(env,secretName(platform));
    const refresh=await open(env,account.refreshToken);
    const form=new URLSearchParams({refresh_token:refresh,grant_type:"refresh_token"});
    const headers={"Content-Type":"application/x-www-form-urlencoded"};
    if(platform==="x"){ form.set("client_id",clientId); headers.Authorization="Basic "+btoa(clientId+":"+clientSecret); }
    else if(platform==="tiktok"){ form.set("client_key",clientId); form.set("client_secret",clientSecret); }
    else { form.set("client_id",clientId); form.set("client_secret",clientSecret); }
    const res=await fetch(p.token,{method:"POST",headers,body:form}); const raw=await res.text(); let t={}; try{t=JSON.parse(raw)}catch{}
    if(!res.ok||!t.access_token) throw new Error(platform+"_token_refresh_failed");
    access=String(t.access_token);
    account.accessToken=await seal(env,access);
    if(t.refresh_token) account.refreshToken=await seal(env,String(t.refresh_token));
    account.expiresAt=new Date(Date.now()+Number(t.expires_in||7200)*1000);
  }
  return {access,account};
}

export async function manageXAction(env,uid,accountId,input={}){const account=await firestoreGet("socialAccounts/"+String(accountId||""));if(!account||account.uid!==uid||account.platform!=="x")throw new Error("x_account_required");const state=await socialAccountToken(env,account);const token=state.access,userId=String(account.metadata?.xAccount?.id||"");if(!userId)throw new Error("x_user_id_missing");const action=clean(input.action,40),postId=clean(input.postId,80);if(!postId)throw new Error("x_post_required");let r;if(action==="reply"){const text=clean(input.text,5000);if(!text)throw new Error("x_reply_requires_post_and_text");r=await fetch("https://api.x.com/2/tweets",{method:"POST",headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({text,reply:{in_reply_to_tweet_id:postId}})});}else if(action==="delete"){r=await fetch("https://api.x.com/2/tweets/"+encodeURIComponent(postId),{method:"DELETE",headers:{Authorization:"Bearer "+token}})}else if(action==="like"){r=await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/likes",{method:"POST",headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({tweet_id:postId})})}else if(action==="unlike"){r=await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/likes/"+encodeURIComponent(postId),{method:"DELETE",headers:{Authorization:"Bearer "+token}})}else if(action==="repost"){r=await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/retweets",{method:"POST",headers:{Authorization:"Bearer "+token,"Content-Type":"application/json"},body:JSON.stringify({tweet_id:postId})})}else if(action==="unrepost"){r=await fetch("https://api.x.com/2/users/"+encodeURIComponent(userId)+"/retweets/"+encodeURIComponent(postId),{method:"DELETE",headers:{Authorization:"Bearer "+token}})}else throw new Error("unsupported_x_action");const raw=await r.text();let data={};try{data=JSON.parse(raw)}catch{}if(!r.ok)throw new Error("x_"+action+"_failed:"+raw.slice(0,700));return {ok:true,action,data:data.data||data}}
