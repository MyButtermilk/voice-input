import Exa from "exa-js";

const exa = new Exa(process.env.EXA_API_KEY);

const queries = [
  'Soniox async API speech-to-text',
  'site:soniox.com realtime API speech-to-text',
  'site:docs.soniox.com real-time streaming API',
  'Soniox WebSocket streaming API',
  'Soniox batch transcription API',
  'Soniox API reference async',
];

function logSection(title) {
  console.log(`\n===== ${title} =====`);
}

(async () => {
  for (const q of queries) {
    logSection(q);
    const res = await exa.searchAndContents(q, { text: { maxCharacters: 6000 } });
    for (const r of res.results.slice(0, 5)) {
      const url = r.url || (r.result?.url);
      const title = r.title || (r.result?.title);
      console.log(`- ${title} -> ${url}`);
      if (r.text) {
        const t = r.text.replace(/\s+/g, ' ').slice(0, 1000);
        console.log(`  excerpt: ${t}...`);
      }
    }
  }
})().catch(e => { console.error(e); process.exit(1); });
