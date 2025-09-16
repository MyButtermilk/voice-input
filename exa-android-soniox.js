import Exa from "exa-js";
const exa=new Exa(process.env.EXA_API_KEY);
(async()=>{
  const queries=[
    'site:github.com soniox android sample',
    'site:github.com soniox android app',
    'site:github.com "soniox" "android"',
    'site:github.com "api.soniox.com" android',
    'site:github.com "stt-rt.soniox.com" android',
  ];
  for (const q of queries){
    console.log('\n===== '+q+' =====');
    const res=await exa.searchAndContents(q,{text:{maxCharacters:8000}});
    for (const r of res.results.slice(0,10)){
      console.log('-',r.title,'->',r.url);
      if (r.text){ console.log(r.text.replace(/\s+/g,' ').slice(0,1000)+'...'); }
    }
  }
})();
