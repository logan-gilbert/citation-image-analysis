// collect-optel — collect per-page Core Web Vitals + page views from Adobe OpTel.
//
// Drives the operator's already-open, logged-in OpTel tab
// (aemcs-workspace.adobe.com) hands-off: generates a domain key for the site,
// opens the OpTel Explorer, then filters by each page's pathname and reads the
// LCP / CLS / INP / Page-views tiles. Writes a telemetry.json keyed by
// normalized page URL for enrich-telemetry to join onto a citation run.
//
// All DOM reads/writes happen in optel-explorer.js (one short eval per step);
// every wait/poll lives HERE, so no single Runtime.evaluate nears the CDP 30s
// budget. OpTel is Angular, so synthetic events from the helper drive it (see
// optel-explorer.js header) — no trusted-click dance like Stage 01's grid.
//
// Usage:
//   collect-optel <urlsSource> [telemetry.json]
//   defaults: telemetry.json = telemetry.json (cwd)
//
//   <urlsSource>  a JSON file: either an array of full URL strings, or a
//                 citation run's output.json (array of rows with `page_url`).
//
// Prerequisite: the operator is logged into OpTel and has the
//   https://aemcs-workspace.adobe.com/... tab open in the browser SLICC controls.

const HELPER = '/workspace/skills/optel-telemetry/scripts/optel-explorer.js';
const TAB_MATCH = /aemcs-workspace\.adobe\.com|optel/i;

const args = process.argv.slice(2);
const SRC = args[0];
const OUT = args[1] || 'telemetry.json';
if (!SRC) {
  console.error('collect-optel: usage: collect-optel <urlsSource> [telemetry.json]');
  process.exit(1);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// origin + lowercased-host + pathname, no trailing slash/query/hash — the same
// shape enrich-telemetry uses so the join is exact.
function normalizeUrl(u) {
  try {
    const x = new URL(u);
    const path = x.pathname.replace(/\/+$/, '') || '/';
    return `${x.protocol}//${x.host.toLowerCase()}${path}`;
  } catch {
    return String(u || '').split('?')[0].split('#')[0].replace(/\/+$/, '');
  }
}

// --- load + normalize the target URL list -------------------------------
if (!(await fs.exists(SRC))) {
  console.error(`collect-optel: source not found: ${SRC}`);
  process.exit(1);
}
let parsed;
try {
  parsed = JSON.parse(await fs.readFile(SRC));
} catch (e) {
  console.error(`collect-optel: cannot parse ${SRC}: ${e.message}`);
  process.exit(1);
}
const rawUrls = [];
if (Array.isArray(parsed)) {
  for (const item of parsed) {
    if (typeof item === 'string') rawUrls.push(item);
    else if (item && typeof item === 'object') rawUrls.push(item.page_url || item.url);
  }
}
// De-dup target pages by normalized URL, remembering a full URL per page (for
// origin + pathname). Skip rows that carry no usable URL.
const pages = new Map(); // normalized -> { url, origin, pathname }
for (const u of rawUrls) {
  if (!u || typeof u !== 'string') continue;
  let parsedUrl;
  try {
    parsedUrl = new URL(u);
  } catch {
    continue;
  }
  const key = normalizeUrl(u);
  if (!pages.has(key)) {
    pages.set(key, { url: u, origin: parsedUrl.origin, host: parsedUrl.host, pathname: parsedUrl.pathname || '/' });
  }
}
if (pages.size === 0) {
  console.error(`collect-optel: no usable URLs in ${SRC}`);
  process.exit(1);
}
// Group pages by origin — OpTel keys by domain, so we generate one key per host.
const byOrigin = new Map();
for (const [key, p] of pages) {
  if (!byOrigin.has(p.origin)) byOrigin.set(p.origin, { host: p.host, pages: [] });
  byOrigin.get(p.origin).pages.push({ key, ...p });
}
console.log(`collect-optel: ${pages.size} unique page(s) across ${byOrigin.size} domain(s).`);

// --- find the OpTel tab -------------------------------------------------
function findTab(tabListStdout) {
  const tabs = [];
  for (const line of tabListStdout.split('\n').filter(Boolean)) {
    const m = /^\[([^\]]+)\]\s+(\S+)/.exec(line);
    if (m) tabs.push({ id: m[1], url: m[2] });
  }
  return tabs.find((t) => TAB_MATCH.test(t.url)) || null;
}
function listTabIds(tabListStdout) {
  const ids = [];
  for (const line of tabListStdout.split('\n').filter(Boolean)) {
    const m = /^\[([^\]]+)\]/.exec(line);
    if (m) ids.push(m[1]);
  }
  return ids;
}

// Run the helper with a command (+ optional string arg), parse its JSON.
async function runStep(tabId, cmd, extra) {
  let setExpr = `window.__OPTEL_CMD__=${JSON.stringify(cmd)};`;
  if (extra && extra.domain != null) setExpr += `window.__OPTEL_DOMAIN__=${JSON.stringify(extra.domain)};`;
  if (extra && extra.filter != null) setExpr += `window.__OPTEL_FILTER__=${JSON.stringify(extra.filter)};`;
  setExpr += `'ok'`;
  const set = await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(setExpr)}`);
  if (set.exitCode !== 0) throw new Error(`set globals failed: ${set.stderr || set.stdout}`);
  const run = await exec(`playwright-cli eval-file --tab=${tabId} ${HELPER}`);
  if (run.exitCode !== 0) throw new Error(`eval-file failed: ${run.stderr || run.stdout}`);
  try {
    return JSON.parse(run.stdout);
  } catch (e) {
    throw new Error(`could not parse helper output: ${e.message}\n${run.stdout.slice(0, 600)}`);
  }
}
// Tolerant single step: retry on a transient CDP timeout (the live app can block
// its main thread long enough to exceed the 30s eval budget).
async function step(tabId, cmd, extra, retries = 2) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await runStep(tabId, cmd, extra);
    } catch (e) {
      lastErr = e;
      await wait(1200 * (attempt + 1));
    }
  }
  throw lastErr;
}

const listed = await exec('playwright-cli tab-list');
if (listed.exitCode !== 0) {
  console.error(`collect-optel: tab-list failed: ${listed.stderr || listed.stdout}`);
  process.exit(1);
}
let tab = findTab(listed.stdout);
if (!tab) {
  console.error('collect-optel: could not find an OpTel tab.');
  console.error('Open https://aemcs-workspace.adobe.com/... (logged in) and re-run.');
  console.error('Tabs currently open:');
  console.error(listed.stdout.trim() || '(none)');
  process.exit(1);
}
console.log(`Using OpTel tab ${tab.id} -> ${tab.url}`);

// --- ensure we're on the OpTel Explorer for a domain --------------------
// Returns the tab id of the Explorer (may be a NEW tab opened by the
// "OpTel Explorer" button).
async function ensureExplorer(tabId, host) {
  let s = await step(tabId, 'state');
  if (s.screen === 'explorer') return tabId;

  if (s.screen === 'generate') {
    console.log(`  generating domain key for ${host}...`);
    await step(tabId, 'filldomain', { domain: host });
    await wait(400);
    const g = await step(tabId, 'clickgenerate');
    if (!g.ok) throw new Error(`could not click Generate: ${g.error}`);
    // Poll until the "OpTel Explorer" button appears (key generation is async).
    let appeared = false;
    for (let i = 0; i < 20; i++) {
      await wait(1000);
      s = await step(tabId, 'state');
      if (s.screen === 'explorer') return tabId; // some builds swap in place
      if (s.has_explorer_button) {
        appeared = true;
        break;
      }
    }
    if (!appeared) throw new Error('OpTel Explorer button never appeared after Generate (key generation failed?)');

    // Click it; the Explorer usually opens in a NEW tab — detect by diffing
    // tab-list before/after. Fall back to same-tab navigation.
    const beforeIds = new Set(listTabIds((await exec('playwright-cli tab-list')).stdout));
    const e = await step(tabId, 'clickexplorer');
    if (!e.ok) throw new Error(`could not click OpTel Explorer: ${e.error}`);
    let explorerTab = tabId;
    for (let i = 0; i < 15; i++) {
      await wait(1000);
      const tl = await exec('playwright-cli tab-list');
      const newId = listTabIds(tl.stdout).find((id) => !beforeIds.has(id));
      if (newId) {
        explorerTab = newId;
        break;
      }
      // No new tab — maybe it navigated this tab to the Explorer.
      const ss = await step(tabId, 'state');
      if (ss.screen === 'explorer') {
        explorerTab = tabId;
        break;
      }
    }
    // Confirm the Explorer is ready on whichever tab we landed on.
    for (let i = 0; i < 15; i++) {
      const ss = await step(explorerTab, 'state');
      if (ss.screen === 'explorer') return explorerTab;
      await wait(1000);
    }
    throw new Error('OpTel Explorer did not become ready');
  }

  throw new Error(`OpTel tab is on an unexpected screen (state=${s.screen}). Open the OpTel domain-key page or Explorer and re-run.`);
}

// --- read one page's metrics -------------------------------------------
// Filter by pathname, then poll readmetrics until the tiles settle (two
// consecutive identical reads) or we time out. Returns a metrics object or null.
async function readPage(tabId, pathname) {
  await step(tabId, 'setfilter', { filter: pathname });
  let last = null;
  let stableHits = 0;
  for (let i = 0; i < 12; i++) {
    await wait(900);
    let m;
    try {
      m = await step(tabId, 'readmetrics');
    } catch (e) {
      continue;
    }
    if (!m.ok || !m.ready) continue;
    const sig = `${m.lcp_raw}|${m.cls_raw}|${m.inp_raw}|${m.page_views_raw}`;
    if (last && sig === last.sig) {
      if (++stableHits >= 1) return m; // two identical reads ⇒ settled
    } else {
      stableHits = 0;
    }
    last = { ...m, sig };
  }
  // Return the last reading we saw even if it never fully "settled".
  return last;
}

// --- main loop ----------------------------------------------------------
const telemetry = {};
let okCount = 0;
let explorerTab = tab.id;
for (const [origin, group] of byOrigin) {
  try {
    explorerTab = await ensureExplorer(explorerTab, group.host);
  } catch (e) {
    console.error(`collect-optel: ${origin}: ${e.message}`);
    console.error('  Skipping this domain. (Check the OpTel tab state and retry.)');
    continue;
  }
  console.log(`Reading ${group.pages.length} page(s) for ${origin} on Explorer tab ${explorerTab}...`);
  for (const p of group.pages) {
    const m = await readPage(explorerTab, p.pathname);
    // When OpTel has page views but NO CWV for a page, it renders placeholder
    // tiles ("- s", "nan") that parse to null. Drop the raw/rating for any metric
    // whose numeric value is null so those placeholders never persist (and so the
    // dashboard treats the page as "no OpTel CWV" and falls back to lab data).
    const hasCwv = m && (m.lcp_s != null || m.cls != null || m.inp_s != null);
    if (m && (hasCwv || m.page_views != null)) {
      telemetry[p.key] = {
        page_url: p.url,
        lcp_s: m.lcp_s ?? null,
        lcp_rating: m.lcp_s != null ? m.lcp_rating ?? null : null,
        lcp_raw: m.lcp_s != null ? m.lcp_raw ?? null : null,
        cls: m.cls ?? null,
        cls_rating: m.cls != null ? m.cls_rating ?? null : null,
        cls_raw: m.cls != null ? m.cls_raw ?? null : null,
        inp_s: m.inp_s ?? null,
        inp_rating: m.inp_s != null ? m.inp_rating ?? null : null,
        inp_raw: m.inp_s != null ? m.inp_raw ?? null : null,
        page_views: m.page_views ?? null,
        page_views_raw: m.page_views_raw ?? null,
        has_cwv: !!hasCwv,
      };
      okCount++;
      const cwvTag = hasCwv ? '' : ' (views only, no CWV)';
      console.log(`  ✓ ${p.pathname}  LCP=${m.lcp_s != null ? m.lcp_raw : '—'} CLS=${m.cls != null ? m.cls_raw : '—'} INP=${m.inp_s != null ? m.inp_raw : '—'} views=${m.page_views_raw || '?'}${cwvTag}`);
    } else {
      console.warn(`  ✗ ${p.pathname}  no metrics (filter matched nothing / still loading)`);
    }
  }
}

await fs.writeFile(OUT, JSON.stringify(telemetry, null, 2));
console.log(`\ncollect-optel: wrote ${okCount}/${pages.size} page(s) with telemetry -> ${OUT}`);
if (okCount === 0) {
  console.error('collect-optel: no telemetry captured — verify the OpTel tab and that filtering by pathname returns data.');
  process.exit(1);
}
