const TOKEN_URL="https://oauth2.googleapis.com/token";
const b64=v=>{const a=v instanceof Uint8Array?v:new TextEncoder().encode(v);let s="";for(let i=0;i<a.length;i+=0x8000)s+=String.fromCharCode(...a.subarray(i,i+0x8000));return btoa(s).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"")};
const pem=v=>{const b=atob(String(v).replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,"")),a=new Uint8Array(b.length);for(let i=0;i<b.length;i++)a[i]=b.charCodeAt(i);return a};
let cache={t:null,e:0};
async function token(env){const now=Math.floor(Date.now()/1000);if(cache.t&&cache.e>now+60)return cache.t;const a=JSON.parse(env.BQ_FIREBASE_SERVICE_ACCOUNT_JSON),h=b64(JSON.stringify({alg:"RS256",typ:"JWT"})),c=b64(JSON.stringify({iss:a.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",aud:TOKEN_URL,iat:now,exp:now+3600})),raw=h+"."+c,k=await crypto.subtle.importKey("pkcs8",pem(a.private_key),{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]),sig=await crypto.subtle.sign("RSASSA-PKCS1-v1_5",k,new TextEncoder().encode(raw)),r=await fetch(TOKEN_URL,{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:raw+"."+b64(sig)})}),x=await r.json();if(!r.ok||!x.access_token)throw new Error("firebase_messaging_token_failed");cache={t:x.access_token,e:now+Number(x.expires_in||3600)};return cache.t}
export async function broadcast(env, projectId, tokens, title, body, data = {}) {
  let sent = 0, failed = 0;
  for (const t of tokens.slice(0, 10000)) {
    const messageData = Object.fromEntries(
      Object.entries({ ...data, title, body }).map(([key, value]) => [String(key), String(value ?? "")])
    );
    const response = await fetch(
      "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(projectId) + "/messages:send",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + await token(env),
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: t,
            data: messageData,
            android: { priority: "HIGH" },
            webpush: { headers: { Urgency: "high" } },
          },
        }),
      }
    );
    if (response.ok) sent++;
    else failed++;
  }
  return { sent, failed, tokenCount: tokens.length };
}
