import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY || process.env.EXASEARCH_API_KEY);
const queries = [
  'site:github.com android "api.soniox.com"',
  'site:github.com android "soniox" stt',
  'site:github.com kotlin "soniox"',
  'site:github.com android websocket "soniox"',
];
(async () => {
  for (const q of queries) {
    console.log(`\n===== ${q} =====`);
    const res = await exa.searchAndContents(q, { text: { maxCharacters: 8000 } });
    for (const r of res.results.slice(0, 12)) {
      console.log('-', r.title, '->', r.url);
      if (r.text) console.log(r.text.replace(/\s+/g, ' ').slice(0, 1000) + '...');
    }
  }
})();
