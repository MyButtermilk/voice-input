import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY || process.env.EXASEARCH_API_KEY);

const queries = [
  'site:soniox.com "/v1/" transcriptions',
  'site:soniox.com "/v1/" files',
  'site:soniox.com/docs/stt/api-reference/auth',
  'site:soniox.com/docs stt webhook',
];

(async () => {
  for (const q of queries) {
    console.log("\n=== "+q+" ===");
    const res = await exa.searchAndContents(q, { text: { maxCharacters: 12000 }});
    for (const r of res.results.slice(0, 10)) {
      const text = (r.text||'');
      if (text.includes('/v1/transcriptions') || text.includes('/v1/files') || q.includes('auth') || q.includes('webhook')) {
        console.log('-', r.title, '->', r.url);
        console.log(text.replace(/\s+/g,' ').slice(0, 1500)+'...');
      }
    }
  }
})();
