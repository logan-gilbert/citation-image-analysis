// citation-pipeline — run the whole citation-image-analysis pipeline end to end.
//
// Stage 01 (export-cited-urls) -> 02 (extract-pages) -> 03 (score-and-enrich)
// -> 04 (render-dashboard), each via its own command, then auto-opens the
// dashboard. All artifacts are written to a run directory (default
// /workspace/citation-run), which this script CREATES for you — so you can run
// it from anywhere with a single command, no `mkdir`/`cd` first.
//
// This runs TODAY in the SLICC CLI float — no Chrome extension required. Each
// stage is a discovered .jsh command, and this orchestrator just chains them
// with `exec`, stopping on the first failure.
//
// Usage:
//   citation-pipeline [topN] [--dir=/path] [--platform="ChatGPT (Free)"] [--by=prompts] [--scoring=google] [--telemetry] [--lab-cwv] [--resume]
//   defaults: topN = 10, --by = citations, --scoring = google, --dir = /workspace/citation-run
//
//   --dir=/path             where to write artifacts (created if missing)
//   --resume                resume/append to a previous run's output.json instead
//                           of re-extracting. By default every run is FRESH
//                           (re-extracts all pages). --fresh is still accepted
//                           but is now a no-op (the default).
//   --platform="..."        force the Platform label (else auto-detected)
//   --by=citations|prompts  rank the top N by "Times Cited" (default) or
//                           "Prompts Cited In"
//   --scoring=google|original  metadata rubric: Google's image SEO best practices
//                           (default) or the original Phase 1 brief rubric. Run
//                           the pipeline into two --dir folders to compare them.
//   --telemetry             after extraction, collect per-page Core Web Vitals
//                           (LCP/CLS/INP) + page views from the OpTel tool and
//                           show them in the dashboard (display-only). Requires
//                           the optel-telemetry skill and a logged-in OpTel tab
//                           open in the browser; non-fatal if it's missing (the
//                           core run continues). Also captures LAB CWV during
//                           Stage 02 to fill gaps for pages OpTel doesn't cover
//                           (synthetic, marked "lab"; OpTel field data preferred
//                           per page where available).
//   --lab-cwv               capture LAB Core Web Vitals (LCP/CLS + a TBT proxy
//                           for INP) during the Stage 02 visit only — no OpTel.
//                           Synthetic single-load readings, clearly marked "lab".
//                           (Implied by --telemetry.)
//
// Prerequisite: the operator is logged into Adobe LLM Optimizer and on the
// URL Inspector "Your Cited URLs" view in the browser SLICC controls. For
// --telemetry, also have the OpTel tab (aemcs-workspace.adobe.com) open.

const raw = process.argv.slice(2);
let platformValue = '';
let byValue = '';
let dirValue = '';
let scoringValue = '';
let fresh = true; // each pipeline run is FRESH by default (no --fresh needed)
let telemetry = false;
let labCwv = false;
const pos = [];
for (const a of raw) {
  if (a === '--fresh') fresh = true; // accepted for back-compat; now the default
  else if (a === '--resume') fresh = false; // opt into resuming/appending a prior run
  else if (a === '--telemetry') telemetry = true;
  else if (a === '--lab-cwv') labCwv = true;
  else if (/^--platform=/.test(a)) platformValue = a.slice('--platform='.length).replace(/^["']|["']$/g, '');
  else if (/^--by=/.test(a)) byValue = a.slice('--by='.length).replace(/^["']|["']$/g, '');
  else if (/^--dir=/.test(a)) dirValue = a.slice('--dir='.length).replace(/^["']|["']$/g, '');
  else if (/^--scoring=/.test(a)) scoringValue = a.slice('--scoring='.length).replace(/^["']|["']$/g, '');
  else pos.push(a);
}
const TOP_N = parseInt(pos[0], 10) > 0 ? parseInt(pos[0], 10) : 10;
// Re-quote so a value with spaces (e.g. "ChatGPT (Free)") survives the shell.
const platformArg = platformValue ? ` --platform=${JSON.stringify(platformValue)}` : '';
const byArg = byValue ? ` --by=${JSON.stringify(byValue)}` : '';
const scoringArg = scoringValue ? ` --scoring=${JSON.stringify(scoringValue)}` : '';

// Run directory: created up front so the operator never has to mkdir/cd first.
// Artifacts use absolute paths under it, so this command works from any cwd.
const RUN_DIR = (dirValue || '/workspace/citation-run').replace(/\/+$/, '');
const made = await exec(`mkdir -p ${JSON.stringify(RUN_DIR)}`);
if (made.exitCode !== 0) {
  console.error(`citation-pipeline: could not create ${RUN_DIR}: ${made.stderr || made.stdout}`);
  process.exit(made.exitCode || 1);
}

const INPUT = `${RUN_DIR}/input.json`;
const OUTPUT = `${RUN_DIR}/output.json`;
const DATA = `${RUN_DIR}/dashboard-data.json`;
const HTML = `${RUN_DIR}/dashboard.html`;
const TELE = `${RUN_DIR}/telemetry.json`;

async function stage(label, cmd) {
  console.log(`\n=== ${label} ===`);
  console.log(`$ ${cmd}`);
  const r = await exec(cmd);
  if (r.stdout && r.stdout.trim()) console.log(r.stdout.trimEnd());
  if (r.stderr && r.stderr.trim()) console.error(r.stderr.trimEnd());
  if (r.exitCode !== 0) {
    console.error(`\ncitation-pipeline: ${label} failed (exit ${r.exitCode}). Stopping.`);
    process.exit(r.exitCode || 1);
  }
}

// Like stage(), but NON-FATAL: a warning is printed and the pipeline continues.
// Used for optional OpTel telemetry so a missing/locked OpTel tab never blocks
// the core citation run.
async function optionalStage(label, cmd) {
  console.log(`\n=== ${label} ===`);
  console.log(`$ ${cmd}`);
  const r = await exec(cmd);
  if (r.stdout && r.stdout.trim()) console.log(r.stdout.trimEnd());
  if (r.stderr && r.stderr.trim()) console.error(r.stderr.trimEnd());
  if (r.exitCode !== 0) {
    console.warn(`citation-pipeline: ${label} failed (exit ${r.exitCode}) — continuing without it.`);
    return false;
  }
  return true;
}

console.log(`Running full citation-image-analysis pipeline (top ${TOP_N}, scoring=${scoringValue || 'google'})${telemetry ? ' [+telemetry]' : labCwv ? ' [+lab-cwv]' : ''}${fresh ? '' : ' [resume]'}...`);
console.log(`Artifacts -> ${RUN_DIR}`);

await stage('Stage 01 — export cited URLs', `export-cited-urls ${TOP_N} ${INPUT}${platformArg}${byArg}`);
// Capture LAB CWV during the Stage 02 visit when --lab-cwv is set, or with
// --telemetry (to fill gaps for pages OpTel doesn't cover) — synthetic,
// clearly-marked CWV either way.
const labCwvArg = telemetry || labCwv ? ' --lab-cwv' : '';
await stage('Stage 02 — extract pages', `extract-pages ${INPUT} ${OUTPUT}${fresh ? ' --fresh' : ''}${labCwvArg}`);

// Optional: collect OpTel Core Web Vitals + page views and merge them onto the
// extracted rows BEFORE scoring, so Stage 03 carries them straight through into
// the dashboard data. Non-fatal — a missing OpTel tab won't block the run.
if (telemetry) {
  const collected = await optionalStage('Telemetry — collect OpTel CWV', `collect-optel ${OUTPUT} ${TELE}`);
  if (collected) {
    await optionalStage('Telemetry — merge into rows', `enrich-telemetry ${RUN_DIR} --telemetry=${TELE} --merge-only`);
  }
}

await stage('Stage 03 — score and enrich', `score-and-enrich ${OUTPUT} ${DATA}${scoringArg}`);
await stage('Stage 04 — render dashboard', `render-dashboard ${DATA} ${HTML}`);

// Auto-open the finished dashboard in the browser. `open` serves it through the
// preview service worker (file:// is not supported). Non-fatal if it fails —
// the path is printed below so the operator can open it manually.
console.log(`\n=== Opening dashboard ===`);
const opened = await exec(`open ${HTML}`);
if (opened.stdout && opened.stdout.trim()) console.log(opened.stdout.trimEnd());
if (opened.exitCode !== 0) {
  if (opened.stderr && opened.stderr.trim()) console.error(opened.stderr.trimEnd());
  console.error(`(could not auto-open — open it manually with:  open ${HTML})`);
}

console.log(`\n✓ Pipeline complete. Dashboard: ${HTML}`);
