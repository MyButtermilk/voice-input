import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY || process.env.EXASEARCH_API_KEY);
(async () => {
  const res = await exa.searchAndContents('site:soniox.com "POST /v1/files"', { text: { maxCharacters: 12000 }});
  for (const r of res.results) {
    console.log('-', r.title, '->', r.url);
    console.log((r.text||'').replace(/\s+/g,' ').slice(0, 1200)+'...');
  }
})();
