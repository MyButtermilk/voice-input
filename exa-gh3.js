import Exa from "exa-js";
const exa = new Exa(process.env.EXA_API_KEY || process.env.EXASEARCH_API_KEY);
const queries = [
  'site:github.com soniox',
  'site:github.com soniox_examples android',
  'site:github.com soniox stt android',
  'site:github.com "soniox" "android" kotlin',
  'site:github.com "api.soniox.com"',
  'site:githubusercontent.com soniox',
];
(async () => {
  for (const q of queries) {
    console.log(`\n===== ${q} =====`);
    const res = await exa.searchAndContents(q, { text: { maxCharacters: 10000 } });
    for (const r of res.results.slice(0, 12)) {
      console.log('-', r.title, '->', r.url);
      if (r.text) console.log(r.text.replace(/\s+/g, ' ').slice(0, 1200) + '...');
    }
  }
})();
