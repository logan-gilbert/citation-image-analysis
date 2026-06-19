// extract-pages — Stage 02 orchestrator of the citation-image-analysis pipeline.
//
// Reads input.json (Stage 01 output), visits each cited URL in the browser SLICC
// controls, renders the full page (scroll to trigger lazy images), extracts page
// + image data with extract-page.js, merges the citation fields, and appends one
// row per image to output.json (the Stage 03 input).
//
// Sequential and resumable: re-running skips pages already captured and retries
// any that previously errored. NOT deterministic (live web), but the per-page DOM
// logic is encapsulated in extract-page.js.
//
// Usage:
//   extract-pages [inputPath] [outputPath] [--fresh] [--lab-cwv]
//   defaults: input = input.json, output = output.json
//   --fresh    ignore any existing output and start clean (no resume/append)
//   --lab-cwv  also capture LAB Core Web Vitals (LCP/CLS + TBT-as-INP proxy)
//              during each page visit, attached as lab_cwv_* on every row. This
//              is synthetic single-load data (not field/RUM like OpTel); the
//              dashboard prefers OpTel field data and only falls back to lab to
//              fill gaps (pages OpTel doesn't cover), marking them as "lab".
//              NOTE: this foregrounds each tab (playwright-cli activate) so the
//              page actually paints — Chrome never computes LCP/layout-shift for
//              hidden/background tabs. Expect the active tab to flip per page.
//
// Tune for slow sites: PAGE_READY_MS (max wait for load), LAZY_SETTLE_MS (wait
// after scrolling to the bottom so lazy images request + lay out), CWV_SETTLE_MS
// (extra settle before the lab-CWV read so late LCP/shifts register).

const rawArgs = process.argv.slice(2);
let FRESH = false;
let LAB_CWV = false;
const args = [];
for (const a of rawArgs) {
  if (a === '--fresh') FRESH = true;
  else if (a === '--lab-cwv') LAB_CWV = true;
  else args.push(a);
}
const INPUT = args[0] || 'input.json';
const OUTPUT = args[1] || 'output.json';
const SCRIPT = '/workspace/skills/citation-image-analysis/scripts/extract-page.js';
const CWV_SCRIPT = '/workspace/skills/citation-image-analysis/scripts/measure-cwv.js';
const PAGE_READY_MS = 15000;
const LAZY_SETTLE_MS = 1800;
// In-page flush window for the lab-CWV observers. Kept short because we only
// measure once the execution context is confirmed stable (see waitStable);
// buffered:true observers still report the page's load-time LCP/CLS/long-tasks.
const CWV_SETTLE_MS = 1000;
// Max time to wait for the JS execution context to stop being torn down. AEM /
// SPA pages frequently redirect or re-hydrate after readyState=complete, which
// destroys the context and kills any eval running across it.
const STABILIZE_MS = 6000;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const targetIdOf = (s) => {
  const m = /targetId:\s*([^\]\s]+)/.exec(s) || /^\[([A-Za-z0-9]{8,})\]/m.exec(s);
  return m ? m[1] : null;
};

if (!(await fs.exists(INPUT))) {
  console.error(`extract-pages: input not found: ${INPUT} (run export-cited-urls first)`);
  process.exit(1);
}
let entries;
try {
  entries = JSON.parse(await fs.readFile(INPUT));
} catch (e) {
  console.error(`extract-pages: cannot parse ${INPUT}: ${e.message}`);
  process.exit(1);
}
if (!Array.isArray(entries) || entries.length === 0) {
  console.error('extract-pages: input.json must be a non-empty array of cited-URL objects');
  process.exit(1);
}

// Resume: keep prior good rows, drop error markers so failed pages retry.
// --fresh ignores any existing output and starts clean.
let out = [];
const done = new Set();
if (!FRESH && (await fs.exists(OUTPUT))) {
  try {
    const prior = JSON.parse(await fs.readFile(OUTPUT));
    out = prior.filter((r) => !(r && r.skipped === true && r.error));
    for (const r of out) if (r && r.page_url) done.add(r.page_url);
  } catch {
    /* corrupt checkpoint — start fresh */
  }
}

// Map Stage 01 citation fields onto the row shape Stage 03/04 expect.
function citationFields(e) {
  const cats = Array.isArray(e.categories) ? e.categories : [];
  return {
    page_url: e.url || e.page_url,
    citation_count: e.citation_count ?? null,
    citation_rank: e.citation_rank ?? null,
    citation_trend: e.citation_trend ?? null,
    // Which Stage 01 metric drove ranking/selection: 'citation_count' (default)
    // or 'prompts_cited_in'. Stage 03 tiers off whichever this names.
    selected_by: e.selected_by || 'citation_count',
    // Dashboard reads a single category string + an ai_platforms array. The
    // "Your Cited URLs" view has no per-URL platform breakdown (it's a global
    // filter), so ai_platforms is left empty here.
    query_category: cats.join(', '),
    // The "Your Cited URLs" view is scoped to one Platform globally; Stage 01
    // captures it as `platform` and we surface it as the dashboard's ai_platforms.
    ai_platforms: e.platform ? [e.platform] : [],
    platform: e.platform ?? null,
    // Extra Stage 01 metrics carried through for future use (harmless to 03/04).
    citation_attempts: e.citation_attempts ?? null,
    prompts_cited_in: e.prompts_cited_in ?? null,
    referral_hits: e.referral_hits ?? null,
    categories: cats,
    markets: Array.isArray(e.markets) ? e.markets : [],
  };
}

const CTX_DESTROYED = /context was destroyed|cannot find context|Inspected target navigated|Execution context/i;

// Wait until the page's JS execution context stops being replaced. We stamp a
// sentinel and confirm it survives a short interval; AEM/SPA redirects and late
// hydration destroy the context after readyState=complete, which is exactly what
// kills a lab-CWV eval running across that window. Returns true once stable.
async function waitStable(tabId, ms) {
  const start = Date.now();
  while (Date.now() - start < ms) {
    const tok = `s${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const setExpr = `window.__STAB__=${JSON.stringify(tok)};'ok'`;
    await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(setExpr)}`);
    await wait(700);
    const r = await exec(`playwright-cli eval --tab=${tabId} "window.__STAB__||''"`);
    // Token survived the interval -> no navigation/context swap happened.
    if (r.exitCode === 0 && r.stdout.includes(tok)) return true;
  }
  return false;
}

// Measure lab CWV in a SINGLE async eval (the script installs observers, waits
// internally, and returns) so we never depend on window state surviving between
// evals. Best-effort: any failure returns null and never blocks extraction.
// Retries when the execution context is destroyed mid-eval (late redirect /
// hydration); buffered:true observers still capture the final page's load
// entries once the context settles. Prints a one-time diagnostic so a null
// result is explainable (eval error / unparseable output / nothing observed).
let cwvDiagShown = false;
async function measureLabCwv(tabId) {
  const MAX_TRIES = 4;
  let lastInfo = '';
  for (let attempt = 1; attempt <= MAX_TRIES; attempt++) {
    // Re-assert foreground each attempt: LCP/layout-shift are only generated for
    // a painting (visible) tab, and a redirect could have re-backgrounded it.
    await exec(`playwright-cli activate --tab=${tabId}`);
    // Make sure the context is settled before we hold a promise across it.
    await waitStable(tabId, STABILIZE_MS);
    const setExpr = `window.__CWV_SETTLE_MS__=${CWV_SETTLE_MS};'ok'`;
    await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(setExpr)}`);
    const r = await exec(`playwright-cli eval-file --tab=${tabId} ${CWV_SCRIPT}`);
    if (r.exitCode === 0) {
      try {
        const parsed = JSON.parse(r.stdout);
        if (parsed && parsed.ok) {
          if (!cwvDiagShown) {
            cwvDiagShown = true;
            console.warn(
              `    [lab-cwv diag] try ${attempt} ok supported=${JSON.stringify(parsed.supported || {})} ` +
                `lcp_entries=${parsed.lcp_entries} shift_count=${parsed.shift_count} longtask_count=${parsed.longtask_count} ` +
                `=> lcp_s=${parsed.lcp_s} cls=${parsed.cls} tbt_ms=${parsed.tbt_ms}`,
            );
          }
          return parsed;
        }
        lastInfo = `bad result: ${String(r.stdout).trim().slice(0, 200)}`;
      } catch (e) {
        lastInfo = `parse: ${e.message} :: ${String(r.stdout).trim().slice(0, 160)}`;
      }
    } else {
      lastInfo = (r.stderr || r.stdout || '').trim().slice(0, 240);
      // Context destroyed -> page navigated/hydrated mid-eval. Let the new
      // context settle, then retry (loop will re-run waitStable).
      if (CTX_DESTROYED.test(lastInfo)) {
        await wait(1200);
        continue;
      }
    }
    // Non-retryable failure (or exhausted) — stop trying.
    if (!CTX_DESTROYED.test(lastInfo)) break;
  }
  if (!cwvDiagShown) {
    cwvDiagShown = true;
    console.warn(`    [lab-cwv diag] failed after retries: ${lastInfo}`);
  }
  return null;
}

async function waitReady(tabId, ms) {
  const start = Date.now();
  while (Date.now() - start < ms) {
    const r = await exec(`playwright-cli eval --tab=${tabId} "document.readyState"`);
    if (r.exitCode === 0 && /complete|interactive/.test(r.stdout)) return true;
    await wait(300);
  }
  return false;
}

let pages = 0;
let images = 0;
let failed = 0;
const total = entries.length;

for (let i = 0; i < entries.length; i++) {
  const e = entries[i];
  const url = e.url || e.page_url;
  if (!url) continue;
  if (done.has(url)) {
    console.log(`[${i + 1}/${total}] skip (already captured): ${url}`);
    continue;
  }
  console.log(`[${i + 1}/${total}] ${url}`);

  let tabId = null;
  try {
    const opened = await exec(`playwright-cli open ${JSON.stringify(url)}`);
    if (opened.exitCode !== 0) throw new Error(opened.stderr || opened.stdout);
    tabId = targetIdOf(opened.stdout);
    if (!tabId) throw new Error(`could not parse targetId from: ${opened.stdout.trim()}`);

    // For lab CWV, foreground the tab immediately so the page loads while
    // VISIBLE. Chrome doesn't paint hidden/background tabs, so LCP and
    // layout-shift are never generated for them; foregrounding early makes LCP
    // reflect real render time rather than a late wake-up paint.
    if (LAB_CWV) await exec(`playwright-cli activate --tab=${tabId}`);

    await waitReady(tabId, PAGE_READY_MS);

    // LAB Core Web Vitals — measured BEFORE our synthetic scroll so our own
    // scrolling can't finalize LCP early or inflate CLS. A single async eval
    // installs PerformanceObservers (mandatory — Chrome doesn't expose these
    // entry types via getEntriesByType), settles so the buffered callbacks fire,
    // and returns the metrics.
    let labCwv = null;
    if (LAB_CWV) labCwv = await measureLabCwv(tabId);

    // Trigger lazy-load: to the bottom, settle, then back to the top so
    // getBoundingClientRect positions are measured from a known scroll origin.
    await exec(`playwright-cli eval --tab=${tabId} "window.scrollTo(0, document.body.scrollHeight); 'ok'"`);
    await wait(LAZY_SETTLE_MS);
    await exec(`playwright-cli eval --tab=${tabId} "window.scrollTo(0, 0); 'ok'"`);
    await wait(250);

    const res = await exec(`playwright-cli eval-file --tab=${tabId} ${SCRIPT}`);
    if (res.exitCode !== 0) throw new Error(res.stderr || res.stdout);
    let data;
    try {
      data = JSON.parse(res.stdout);
    } catch (pe) {
      throw new Error(`bad extractor output: ${pe.message} :: ${res.stdout.slice(0, 200)}`);
    }
    const page = data.page || {};
    // Attach lab CWV (if captured) to the page object so it propagates onto
    // every row spread below. These are raw numbers; the dashboard formats,
    // rates, and source-tags them (preferring OpTel field data when present).
    if (labCwv) {
      page.lab_cwv_measured = true;
      page.lab_lcp_s = labCwv.lcp_s;
      page.lab_cls = labCwv.cls;
      page.lab_tbt_ms = labCwv.tbt_ms;
    }
    const imgs = Array.isArray(data.images) ? data.images : [];
    const cf = citationFields(e);

    if (imgs.length === 0) {
      // A cited page with no qualifying images still belongs on the dashboard
      // (it has citation data). Mark it page_only — NOT skipped — so Stage 03
      // passes it through and the overview shows it with 0 images.
      out.push({ ...cf, ...page, page_only: true, note: 'no-images-found' });
    } else {
      for (const im of imgs) out.push({ ...cf, ...page, ...im });
    }
    done.add(url);
    pages++;
    images += imgs.length;
    const cwvNote = labCwv
      ? `; lab CWV LCP=${labCwv.lcp_s == null ? '?' : labCwv.lcp_s + 's'} CLS=${labCwv.cls == null ? '?' : labCwv.cls} TBT=${labCwv.tbt_ms == null ? '?' : labCwv.tbt_ms + 'ms'}`
      : '';
    console.log(`    ${imgs.length} images (${page.image_count_total ?? '?'} <img> total)${cwvNote}`);
  } catch (err) {
    failed++;
    const msg = err && err.message ? err.message : String(err);
    console.warn(`    FAILED: ${msg}`);
    out.push({ page_url: url, error: msg, skipped: true });
  } finally {
    if (tabId) {
      try {
        await exec(`playwright-cli tab-close --tab=${tabId}`);
      } catch {
        /* tab may already be gone */
      }
    }
  }

  // Checkpoint after every page — VFS persists, so an interruption is resumable.
  await fs.writeFile(OUTPUT, JSON.stringify(out, null, 2));
}

console.log(`Stage 02 done: ${pages} pages captured, ${images} image rows, ${failed} failed -> ${OUTPUT}`);
if (failed > 0) console.log('Re-run to retry failed pages (captured pages are skipped).');
console.log(`Next: score-and-enrich ${OUTPUT} dashboard-data.json`);
