// enrich-telemetry — join OpTel telemetry onto a citation run's rows.
//
// Reads a run dir's output.json (Stage 02/03 rows) + telemetry.json (from
// collect-optel) and attaches page-level Core Web Vitals + page views to every
// row by normalized page_url. score-and-enrich spreads row objects straight
// through, so these fields survive into dashboard-data.json untouched — no
// scoring change. CWV are page-level signals (potential confounders for the
// image-quality vs citations question); page views are display-only (downstream
// of citations ⇒ collider risk), never a quality control.
//
// Usage:
//   enrich-telemetry [runDir] [--telemetry=/path/telemetry.json] [--merge-only]
//   defaults: runDir = /workspace/citation-run, telemetry = <runDir>/telemetry.json
//
//   --merge-only   only attach the fields to output.json (used inside
//                  citation-pipeline, which runs Stage 03/04 itself afterward).
//                  Without it (standalone), this also re-runs score-and-enrich +
//                  render-dashboard so you get a refreshed dashboard in one go.

const args = process.argv.slice(2);
let TELE_OVERRIDE = '';
let MERGE_ONLY = false;
const pos = [];
for (const a of args) {
  if (a === '--merge-only') MERGE_ONLY = true;
  else if (/^--telemetry=/.test(a)) TELE_OVERRIDE = a.slice('--telemetry='.length).replace(/^["']|["']$/g, '');
  else pos.push(a);
}
const RUN_DIR = (pos[0] || '/workspace/citation-run').replace(/\/+$/, '');
const OUTPUT = `${RUN_DIR}/output.json`;
const TELE = TELE_OVERRIDE || `${RUN_DIR}/telemetry.json`;
const DATA = `${RUN_DIR}/dashboard-data.json`;
const HTML = `${RUN_DIR}/dashboard.html`;

// Same shape collect-optel keys by: origin + lowercased host + pathname,
// no trailing slash/query/hash.
function normalizeUrl(u) {
  try {
    const x = new URL(u);
    const path = x.pathname.replace(/\/+$/, '') || '/';
    return `${x.protocol}//${x.host.toLowerCase()}${path}`;
  } catch {
    return String(u || '').split('?')[0].split('#')[0].replace(/\/+$/, '');
  }
}

if (!(await fs.exists(OUTPUT))) {
  console.error(`enrich-telemetry: output not found: ${OUTPUT}`);
  process.exit(1);
}
if (!(await fs.exists(TELE))) {
  console.error(`enrich-telemetry: telemetry not found: ${TELE} (run collect-optel first)`);
  process.exit(1);
}

let rows;
try {
  rows = JSON.parse(await fs.readFile(OUTPUT));
} catch (e) {
  console.error(`enrich-telemetry: cannot parse ${OUTPUT}: ${e.message}`);
  process.exit(1);
}
let tele;
try {
  tele = JSON.parse(await fs.readFile(TELE));
} catch (e) {
  console.error(`enrich-telemetry: cannot parse ${TELE}: ${e.message}`);
  process.exit(1);
}
if (!Array.isArray(rows)) {
  console.error('enrich-telemetry: expected output.json to be an array of rows');
  process.exit(1);
}

let matched = 0;
let pagesMatched = new Set();
let pagesSeen = new Set();
for (const r of rows) {
  if (!r || !r.page_url) continue;
  pagesSeen.add(normalizeUrl(r.page_url));
  const t = tele[normalizeUrl(r.page_url)];
  if (t) {
    r.cwv_lcp_s = t.lcp_s ?? null;
    r.cwv_lcp_rating = t.lcp_rating ?? null;
    r.cwv_lcp_raw = t.lcp_raw ?? null;
    r.cwv_cls = t.cls ?? null;
    r.cwv_cls_rating = t.cls_rating ?? null;
    r.cwv_cls_raw = t.cls_raw ?? null;
    r.cwv_inp_s = t.inp_s ?? null;
    r.cwv_inp_rating = t.inp_rating ?? null;
    r.cwv_inp_raw = t.inp_raw ?? null;
    r.page_views = t.page_views ?? null;
    r.page_views_raw = t.page_views_raw ?? null;
    r.telemetry_matched = true;
    // Only call OpTel the CWV source when it actually has a CWV value — a page
    // with views but placeholder ("- s"/"nan") CWV must fall back to lab data.
    const optelHasCwv = t.lcp_s != null || t.cls != null || t.inp_s != null;
    r.cwv_source = optelHasCwv ? 'optel' : null; // field/RUM; preferred over lab
    matched++;
    pagesMatched.add(normalizeUrl(r.page_url));
  } else {
    r.telemetry_matched = false;
    // Leave cwv_source unset here — the dashboard fills it as 'lab' when the row
    // carries lab_cwv_* from a --lab-cwv Stage 02 run, else treats CWV as absent.
  }
}

await fs.writeFile(OUTPUT, JSON.stringify(rows, null, 2));
console.log(
  `enrich-telemetry: matched telemetry to ${matched}/${rows.length} rows ` +
    `(${pagesMatched.size}/${pagesSeen.size} unique pages) -> ${OUTPUT}`,
);
if (pagesMatched.size === 0) {
  console.warn('enrich-telemetry: no pages matched — check that telemetry.json URLs line up with the run.');
}

if (MERGE_ONLY) {
  process.exit(0);
}

// Standalone: refresh the dashboard so the operator sees the new columns in one
// command. Preserve whatever rubric the run already used (stamped on the rows).
const mode = rows.find((r) => r && r.scoring_mode)?.scoring_mode || 'google';
const scoringArg = ` --scoring=${mode}`;

async function stage(label, cmd) {
  console.log(`\n=== ${label} ===`);
  console.log(`$ ${cmd}`);
  const r = await exec(cmd);
  if (r.stdout && r.stdout.trim()) console.log(r.stdout.trimEnd());
  if (r.stderr && r.stderr.trim()) console.error(r.stderr.trimEnd());
  if (r.exitCode !== 0) {
    console.error(`\nenrich-telemetry: ${label} failed (exit ${r.exitCode}). Stopping.`);
    process.exit(r.exitCode || 1);
  }
}

await stage('Re-score', `score-and-enrich ${OUTPUT} ${DATA}${scoringArg}`);
await stage('Re-render dashboard', `render-dashboard ${DATA} ${HTML}`);

console.log(`\n=== Opening dashboard ===`);
const opened = await exec(`open ${HTML}`);
if (opened.stdout && opened.stdout.trim()) console.log(opened.stdout.trimEnd());
if (opened.exitCode !== 0) {
  if (opened.stderr && opened.stderr.trim()) console.error(opened.stderr.trimEnd());
  console.error(`(could not auto-open — open it manually with:  open ${HTML})`);
}
console.log(`\n✓ Telemetry enriched. Dashboard: ${HTML}`);
