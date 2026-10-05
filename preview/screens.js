(function(){
  const screens=["Chat","History","Settings","Learning","Companion","TruthGuard","Video Generation","Business","Organization","Catalog","Legal","Admin","Account Protection","Auth / Consent"];
  const state={screen:"Chat",mode:"success",theme:"dark",stop:null};
  const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
  function render(root){
    document.documentElement.classList.toggle("light",state.theme==="light");
    root.innerHTML='<div class="app"><header class="top"><div class="brand"><div class="logo">BQ</div><div><b>BQ Sqwad AI</b><small>The AI That Grows With You</small></div></div><div class="toolbar"><select class="select" id="screen"></select><button class="toggle" id="theme">Theme</button><button class="toggle" id="live">Live /health</button></div><div class="statebar">'+["loading","empty","error","success"].map(x=>'<button data-state="'+x+'">'+x+'</button>').join("")+'</div></header><main id="main"></main><nav class="nav">'+["Chat","History","Settings","Features"].map(x=>'<button class="'+(state.screen===x?'active':'')+'" data-nav="'+x+'">'+x+'</button>').join("")+'</nav></div>';
    const sel=root.querySelector("#screen");screens.forEach(x=>{const o=document.createElement("option");o.value=x;o.textContent=x;sel.appendChild(o)});sel.value=state.screen;
    sel.onchange=()=>{state.screen=sel.value;render(root)};root.querySelector("#theme").onclick=()=>{state.theme=state.theme==="dark"?"light":"dark";render(root)};
    root.querySelector("#live").onclick=async()=>{const r=await pingLiveWorker();alert((r.ok?"LIVE OK\n":"LIVE CHECK FAILED\n")+r.body)};
    root.querySelectorAll("[data-state]").forEach(b=>b.onclick=()=>{state.mode=b.dataset.state;draw(root.querySelector("#main"))});
    root.querySelectorAll("[data-nav]").forEach(b=>b.onclick=()=>{state.screen=b.dataset.nav==="Features"?"Learning":b.dataset.nav;render(root)});
    draw(root.querySelector("#main"));
  }
  function draw(main){
    const m=state.mode;
    if(m==="loading"){main.innerHTML='<div class="card"><h2>Loading…</h2><p>Loading secure BQ Sqwad AI data.</p></div>';return}
    if(m==="empty"){main.innerHTML='<div class="card"><h2>'+esc(state.screen)+'</h2><p>No content yet. This state is intentionally empty for UI testing.</p></div>';return}
    if(m==="error"){main.innerHTML='<div class="card"><h2>'+esc(state.screen)+'</h2><p class="err">Something went wrong.</p><button class="toggle" onclick="location.reload()">Retry</button></div>';return}
    if(state.screen==="Chat"){main.innerHTML='<div class="chat"><div class="card"><span class="pill">120 credits</span><div class="meter"><i></i></div></div><div class="msg me">Explain how BQ Sqwad AI works.</div><div class="msg ai"><b>Streaming response</b><p>Markdown, code blocks, copy, regenerate, stop and feedback are represented here.</p><pre>fun hello() = "BQ Sqwad AI"</pre><div class="row"><button class="toggle">Copy</button><button class="toggle">Regenerate</button><button class="toggle">Stop</button></div></div><div class="card row"><input class="input grow" placeholder="Message BQ Sqwad AI"><button class="toggle">Send</button></div></div>';return}
    const cards={History:["Search chats","Pin / rename","Delete / export"],Settings:["Theme","Credits","Privacy & data"],Learning:["Courses","Tutor","Progress"],Companion:["Memory","Voice","Routines"],TruthGuard:["Evidence","Sources","Reliability"],"Video Generation":["Prompt","Image / video","Export"],Business:["Leads","Inbox","Agents"],Organization:["Knowledge","Production","Team"],Catalog:["Credits","Learning AI","Organization plans"],Legal:["Terms","Privacy","Data deletion"],Admin:["Capabilities","Providers","Security"],"Account Protection":["Google / email","Passkeys","Biometric"],"Auth / Consent":["Sign in","Consent","Verification"]};
    main.innerHTML='<div class="card"><h2>'+esc(state.screen)+'</h2><p class="ok">Success state</p><div class="grid">'+(cards[state.screen]||["Loading","Empty","Error"]).map(x=>'<div class="card feature"><b>'+esc(x)+'</b><p>Professional native-app flow preview.</p></div>').join("")+'</div></div>';
  }
  window.BQPreview={mount:root=>render(root)};
})();
