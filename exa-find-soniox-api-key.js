import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY);
(async () => {
  const q='site:github.com "SONIOX_API_KEY"';
  const res = await exa.searchAndContents(q, { text: { maxCharacters: 12000 }});
  for (const r of res.results.slice(0, 12)){
    console.log('-', r.title, '->', r.url);
    console.log((r.text||'').replace(/\s+/g, ' ').slice(0, 1000)+'...');
  }
})();
