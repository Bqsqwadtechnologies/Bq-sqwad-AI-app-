(function(){
  const wait=ms=>new Promise(r=>setTimeout(r,ms));
  const db={
    "/health":()=>({service:"BQ Sqwad AI",status:"ok",backend:"mock"}),
    "/credits":()=>({total:120,permanent:20,subscription:100,plan:"pro"}),
    "/history":()=>({items:[{id:"1",title:"Business plan outline",pinned:true},{id:"2",title:"Explain quantum computing",pinned:false}]}),
    "/feature-settings":()=>({modelSelector:true,voiceInput:true})
  };
  window.MOCK_MODE="mock";
  const realFetch=window.fetch.bind(window);
  window.fetch=async function(url,opts){
    const path=new URL(url,"https://mock.local").pathname;
    if(window.MOCK_MODE==="offline")throw new TypeError("Failed to fetch");
    if(window.MOCK_MODE==="slow")await wait(1800);
    if(window.MOCK_MODE==="error")return new Response(JSON.stringify({error:"provider_unavailable"}),{status:503});
    const handler=db[path];
    if(!handler)return new Response(JSON.stringify({error:"not_mocked",path}),{status:404});
    await wait(180);return new Response(JSON.stringify(handler(opts)),{status:200,headers:{"content-type":"application/json"}});
  };
  window.mockStream=function(text,onChunk,onDone){let i=0;const id=setInterval(()=>{i+=4;onChunk(text.slice(0,i));if(i>=text.length){clearInterval(id);onDone()}},35);return()=>clearInterval(id)};
  window.pingLiveWorker=async function(){try{const r=await realFetch("https://bq-sqwad-ai-app.isaacoshiomole0.workers.dev/health");return{ok:r.ok,body:await r.text()}}catch(e){return{ok:false,body:String(e)}}};
})();
