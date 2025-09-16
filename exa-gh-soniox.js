import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY || process.env.EXASEARCH_API_KEY);
const queries = [
  'site:github.com soniox android',
  'site:github.com "stt-rt.soniox.com"',
  'site:github.com "api.soniox.com/v1/transcriptions"',
  'site:github.com soniox websocket android',
  'site:github.com SONIOX_API_KEY android',
  'site:github.com soniox kotlin',
];

(async () => {
  for (const q of queries) {
    console.log(`\n===== ${q} =====`);
    const res = await exa.searchAndContents(q, { text: { maxCharacters: 10000 } });
    for (const r of res.results.slice(0, 10)) {
      console.log('-', r.title, '->', r.url);
      if (r.text) console.log(r.text.replace(/\s+/g, ' ').slice(0, 1200) + '...');
    }
  }
})();
