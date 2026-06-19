// export-cited-urls — Stage 01 orchestrator of the citation-image-analysis pipeline.
//
// Drives Adobe LLM Optimizer hands-off: finds the operator's already-open,
// logged-in URL Inspector tab, bumps the grid's "Items per page" so enough rows
// are loadable, SCROLL-COLLECTS the cited-URLs grid, and writes the top N by the
// chosen metric (Times Cited by default, or Prompts Cited In with --by=prompts)
// to a clean `input.json` for Stage 02.
//
// Items per page: the operator no longer has to scroll down and pick a bigger
// page size — before collecting, the orchestrator opens the "Items per page"
// popup and selects the smallest option >= topN (e.g. 50). It skips when the
// current size already covers topN, and is non-fatal if the control is absent.
//
// Sorting uses a TRUSTED CLICK on the header. Synthetic in-page events don't fire
// React Aria, and a trusted Enter lands on the nested info "i" button (opening a
// focus-trapping dialog), so a real mouse click is the only reliable trigger.
// SLICC's `playwright-cli click` clicks an element's geometric CENTRE via CDP
// Input.dispatchMouseEvent — and that centre can fall on the info "i" button
// (which sorts nothing and opens a description dialog). So `marksort` both (a)
// stamps a unique aria-label on the header — its a11y name is otherwise the
// merged "<col> Information", matching no DOM node — and (b) sets
// pointer-events:none on the header's descendants so the centre press passes
// through to the pressable columnheader itself. We `snapshot` + `click` that
// ref, then `unmarksort` (which clears the marker and restores pointer-events).
// Then, to collect:
//   - FAST path: if the grid is sorted descending by the metric (our click took,
//     or the operator pre-sorted Times Cited), collect just the top N rows.
//   - ROBUST path: if the sort didn't take, collect every row and rank by the
//     metric ourselves — a safety net so results are correct regardless.
// The scroll loop runs HERE as many short evals, each scan RETRIES on a transient
// CDP timeout, and all waits happen in this orchestrator (not inside a page eval),
// so no single Runtime.evaluate aborts the run.
//
// Usage:
//   export-cited-urls [topN] [outputPath] [--platform="ChatGPT (Free)"] [--by=prompts]
//   defaults: topN = 10, outputPath = input.json, --by = citations
//
// --by=citations | --by=prompts
//   Which column to sort by and pick the top N on. 'citations' (default) uses
//   "Times Cited"; 'prompts' uses "Prompts Cited In".
//
// The active "Platform" filter is auto-detected from the page and stamped onto
// every row (it scopes the whole dataset). Override with --platform=... if
// detection misses.
//
// Prerequisite: the operator is logged into LLM Optimizer and on the
// "Your Cited URLs" view of the URL Inspector in the browser SLICC controls.

// Normalize a --by value to the grid's data-key + parsed row field + label.
const SORT_BY = {
  citations: { key: 'citations', field: 'citation_count', label: 'Times Cited' },
  promptsCited: { key: 'promptsCited', field: 'prompts_cited_in', label: 'Prompts Cited In' },
};
function resolveSortBy(raw) {
  const s = (raw || '').trim().toLowerCase();
  if (/^prompt/.test(s) || s === 'prompts-cited-in' || s === 'promptscited') return SORT_BY.promptsCited;
  return SORT_BY.citations; // default + any "times-cited"/"citations"/empty
}

const rawArgs = process.argv.slice(2);
let PLATFORM_OVERRIDE = null;
let SORT = SORT_BY.citations;
const args = [];
for (const a of rawArgs) {
  const mp = /^--platform=(.*)$/.exec(a);
  const mb = /^--by=(.*)$/.exec(a);
  if (mp) PLATFORM_OVERRIDE = mp[1].replace(/^["']|["']$/g, '');
  else if (mb) SORT = resolveSortBy(mb[1].replace(/^["']|["']$/g, ''));
  else args.push(a);
}
const TOP_N = parseInt(args[0], 10) > 0 ? parseInt(args[0], 10) : 10;
const OUT = args[1] || 'input.json';
const EXTRACTOR = '/workspace/skills/citation-image-analysis/scripts/extract-llmo-list.js';
// The real LLM Optimizer app lives at llmo.now (the URL Inspector path contains
// "url-inspector"). Adobe's marketing page business.adobe.com/products/llm-optimizer.html
// also contains "llm-optimizer" but is NOT CDP-attachable (eval/Page.enable time
// out there), so it must never be picked.
const TAB_STRONG = /llmo\.now|url-inspector/i; // the actual logged-in app
const TAB_WEAK = /llm-optimizer/i; // looser fallback (e.g. white-labelled host)
const TAB_EXCLUDE = /business\.adobe\.com|\/products\//i; // marketing / docs pages
const MAX_SCANS = Math.max(80, TOP_N * 8); // safety cap; smaller steps need more scans

function findTab(tabListStdout) {
  const tabs = [];
  for (const line of tabListStdout.split('\n').filter(Boolean)) {
    const m = /^\[([^\]]+)\]\s+(\S+)/.exec(line);
    if (m) tabs.push({ id: m[1], url: m[2] });
  }
  // Never attach to a marketing/docs page; prefer the real app tab, then any
  // looser match as a fallback.
  const usable = tabs.filter((t) => !TAB_EXCLUDE.test(t.url));
  return (
    usable.find((t) => TAB_STRONG.test(t.url)) ||
    usable.find((t) => TAB_WEAK.test(t.url)) ||
    null
  );
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// Run the extractor with a given command (and optional scrollTo), parse its JSON.
async function runStep(tabId, cmd, scrollTo) {
  const setExpr =
    `window.__LLMO_CMD__=${JSON.stringify(cmd)};` +
    `window.__LLMO_SORT_KEY__=${JSON.stringify(SORT.key)};` +
    (typeof scrollTo === 'number' ? `window.__LLMO_SCROLL_TO__=${scrollTo};` : '') +
    `'ok'`;
  const set = await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(setExpr)}`);
  if (set.exitCode !== 0) throw new Error(`set globals failed: ${set.stderr || set.stdout}`);

  const run = await exec(`playwright-cli eval-file --tab=${tabId} ${EXTRACTOR}`);
  if (run.exitCode !== 0) throw new Error(`eval-file failed: ${run.stderr || run.stdout}`);
  try {
    return JSON.parse(run.stdout);
  } catch (e) {
    throw new Error(`could not parse extractor output: ${e.message}\n${run.stdout.slice(0, 600)}`);
  }
}

// A single scan, but tolerant of a transient CDP timeout: the live grid
// occasionally blocks its main thread (server-backed lazy load / re-render) long
// enough that one Runtime.evaluate exceeds the 30s budget. That must NOT abort
// the whole collection — wait for the page to calm down and retry the same offset.
async function scanAt(tabId, offset, retries = 2) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await runStep(tabId, 'scan', offset);
    } catch (e) {
      lastErr = e;
      await wait(1500 * (attempt + 1)); // back off, let the grid settle
    }
  }
  throw lastErr;
}

// Find a snapshot `[ref=...]` whose accessible name is exactly `token`, preferring
// lines that also match `roleRe` (e.g. /button/i, /option/i) before any role.
function findRefByName(snapshotText, token, roleRe) {
  const quoted = `"${token}"`;
  const lines = (snapshotText || '').split('\n');
  for (const line of lines) {
    if (roleRe && !roleRe.test(line)) continue;
    if (!line.includes(quoted)) continue;
    const m = /\[ref=([^\]]+)\]/.exec(line);
    if (m) return m[1];
  }
  for (const line of lines) {
    if (!line.includes(quoted)) continue;
    const m = /\[ref=([^\]]+)\]/.exec(line);
    if (m) return m[1];
  }
  return null;
}

// Restore the items-per-page button's accessible name + pointer-events.
async function unmarkPage(tabId) {
  await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(`window.__LLMO_CMD__='unmarkpage';'ok'`)}`);
  try {
    await exec(`playwright-cli eval-file --tab=${tabId} ${EXTRACTOR}`);
  } catch {}
}

// Make sure the grid's "Items per page" control is large enough to hold the
// requested top N: open the popup and select the smallest option >= TOP_N (or the
// largest available if none is big enough). Skips when the current size already
// covers TOP_N or when there's no pagination control. Best-effort and NON-FATAL —
// any failure just leaves the size unchanged and collection proceeds with whatever
// rows the grid loads. Uses the same trusted-click (mark → snapshot → click)
// pattern as the sort header, since React Aria ignores synthetic events.
async function ensurePageSize(tabId) {
  let ps;
  try {
    ps = await runStep(tabId, 'pagesize');
  } catch (e) {
    console.warn(`items-per-page: could not probe the control: ${e.message} — skipping.`);
    return { changed: false };
  }
  if (!ps.found) {
    console.warn('items-per-page: control not detected on this view — skipping.');
    if (ps.pickers && ps.pickers.length) {
      console.warn(`items-per-page: pickers seen: ${JSON.stringify(ps.pickers)}`);
    } else {
      console.warn('items-per-page: no popup/picker elements were seen at all.');
    }
    return { changed: false };
  }
  const current = ps.current;
  console.log(`Items per page is currently ${current ?? '?'} (target ≥ ${TOP_N}).`);
  if (current != null && current >= TOP_N) {
    console.log(`Items per page already ${current} (≥ ${TOP_N}) — leaving as is.`);
    return { changed: false };
  }

  // 1) Open the listbox: mark the button, snapshot, trusted-click it.
  let mark;
  try {
    mark = await runStep(tabId, 'markpage');
  } catch (e) {
    console.warn(`items-per-page: markpage failed: ${e.message} — skipping.`);
    return { changed: false };
  }
  if (!mark.ok) {
    console.warn(`items-per-page: ${mark.error || 'could not mark the control'} — skipping.`);
    return { changed: false };
  }
  let snap = await exec(`playwright-cli snapshot --tab=${tabId} --no-iframes`);
  let ref = snap.exitCode === 0 ? findRefByName(snap.stdout, mark.token, /button/i) : null;
  if (!ref) {
    await unmarkPage(tabId);
    console.warn('items-per-page: control not found in snapshot — skipping.');
    return { changed: false };
  }
  const opened = await exec(`playwright-cli click --tab=${tabId} ${ref}`);
  await unmarkPage(tabId); // listbox is open now; restore the button
  if (opened.exitCode !== 0) {
    console.warn(`items-per-page: open click failed: ${opened.stderr || opened.stdout} — skipping.`);
    return { changed: false };
  }
  await wait(600); // let the listbox render

  // 2) Read the available options and choose the smallest that covers TOP_N.
  let opts = [];
  try {
    const r = await runStep(tabId, 'pageoptions');
    opts = Array.isArray(r.options) ? r.options : [];
  } catch {}
  if (!opts.length) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    console.warn('items-per-page: no options in the open listbox — skipping.');
    return { changed: false };
  }
  const bigEnough = opts.filter((v) => v >= TOP_N);
  const target = bigEnough.length ? Math.min(...bigEnough) : Math.max(...opts);
  if (target === current) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    return { changed: false };
  }

  // 3) Select the target option: mark it, snapshot, trusted-click it.
  await exec(`playwright-cli eval --tab=${tabId} ${JSON.stringify(`window.__LLMO_PAGE_TARGET__=${target};'ok'`)}`);
  let om;
  try {
    om = await runStep(tabId, 'markoption');
  } catch (e) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    console.warn(`items-per-page: markoption failed: ${e.message} — skipping.`);
    return { changed: false };
  }
  if (!om.ok) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    console.warn(`items-per-page: option ${target} not found — skipping.`);
    return { changed: false };
  }
  snap = await exec(`playwright-cli snapshot --tab=${tabId} --no-iframes`);
  ref = snap.exitCode === 0 ? findRefByName(snap.stdout, om.token, /option/i) : null;
  if (!ref) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    console.warn('items-per-page: option not found in snapshot — skipping.');
    return { changed: false };
  }
  const chose = await exec(`playwright-cli click --tab=${tabId} ${ref}`);
  if (chose.exitCode !== 0) {
    await exec(`playwright-cli press --tab=${tabId} Escape`);
    console.warn(`items-per-page: option click failed: ${chose.stderr || chose.stdout} — skipping.`);
    return { changed: false };
  }
  await wait(2000); // grid re-fetches with the new page size
  let confirmed = null;
  try {
    confirmed = (await runStep(tabId, 'pagesize')).current;
  } catch {}
  console.log(`Items per page set to ${confirmed ?? target} (was ${current ?? '?'}) to cover top ${TOP_N}.`);
  return { changed: true };
}

console.log(`Stage 01: exporting top ${TOP_N} cited URLs from LLM Optimizer (by ${SORT.label})...`);

const listed = await exec('playwright-cli tab-list');
if (listed.exitCode !== 0) {
  console.error(`export-cited-urls: tab-list failed: ${listed.stderr || listed.stdout}`);
  process.exit(1);
}
const tab = findTab(listed.stdout);
if (!tab) {
  console.error('export-cited-urls: could not find an LLM Optimizer tab.');
  console.error('Open the URL Inspector "Your Cited URLs" view (logged in) and re-run.');
  console.error('Tabs currently open:');
  console.error(listed.stdout.trim() || '(none)');
  process.exit(1);
}
console.log(`Using tab ${tab.id} -> ${tab.url}`);

// Step 1: probe the grid — fast, no interaction.
let meta;
try {
  meta = await runStep(tab.id, 'metrics');
} catch (e) {
  console.error(`export-cited-urls: ${e.message}`);
  process.exit(1);
}
if (meta.error === 'no-cited-urls-grid-found') {
  console.error('export-cited-urls: cited-URLs grid not found on the page.');
  console.error(JSON.stringify({ hint: meta.hint, roles_present: meta.roles_present }, null, 2));
  process.exit(1);
}

const platform = PLATFORM_OVERRIDE || meta.platform || null;
if (platform) {
  console.log(`Platform filter: ${platform}${PLATFORM_OVERRIDE ? ' (override)' : ' (auto-detected)'}`);
} else {
  console.warn('Platform filter not detected — pass --platform="ChatGPT (Free)" to set it on the report.');
}

// Step 1a: bump "Items per page" so the grid can load enough rows for the top N
// (e.g. select 50 when the operator left it at 10). Non-fatal; if it changes, the
// grid re-fetched, so refresh the metrics probe before sorting/collecting.
const pageResult = await ensurePageSize(tab.id);
if (pageResult.changed) {
  try {
    meta = await runStep(tab.id, 'metrics');
  } catch {
    /* keep the prior meta — collection still works off live scans */
  }
}

// Non-strict: values are non-increasing (allows ties). Used to VERIFY an
// already-confirmed descending order, where ties at the top are legitimate
// (e.g. 64, 45, 22, 22, 22).
const isDescVals = (vals) => {
  const v = (vals || []).filter((x) => x != null);
  return v.length >= 2 && v.every((x, i) => i === 0 || v[i - 1] >= x);
};
// STRICT: non-increasing AND a real drop from first to last. Critical because the
// top of an ASCENDING sort is often all-equal (e.g. many URLs with "1 prompt"),
// and a non-strict check would wrongly read [1,1,1,1] as "descending". A strict
// drop can't be faked by ties, so we never mistake ascending-with-ties for sorted.
const strictDescVals = (vals) => {
  const v = (vals || []).filter((x) => x != null);
  return v.length >= 2 && v[0] > v[v.length - 1] && v.every((x, i) => i === 0 || v[i - 1] >= x);
};
// aria-sort is AUTHORITATIVE: if React Aria says "ascending" we must NOT override
// it with a value heuristic (the all-equal-top trap). Only infer from values when
// aria-sort is absent/"none", and then require a strict descending drop.
const isDescending = (ariaSort, vals) => {
  if (ariaSort === 'descending') return true;
  if (ariaSort === 'ascending') return false;
  return strictDescVals(vals);
};
const metaVals = (meta.rows || [])
  .slice()
  .sort((a, b) => (a._rowindex || 0) - (b._rowindex || 0))
  .map((r) => r[SORT.field]);

// Step 1b: sort the chosen column descending with a TRUSTED CLICK on the header.
// React Aria ignores synthetic in-page events, and a trusted keyboard Enter lands
// on the header's nested info "i" button (opening a focus-trapping description
// dialog) instead of sorting. A real mouse click is what the operator uses, and
// SLICC's `playwright-cli click` performs a trusted CDP Input.dispatchMouseEvent
// at the element's CENTRE. The hitch: SLICC builds snapshot selectors from a11y
// NAMES, and this header has no aria-label/title of its own (its name is the
// merged "Prompts Cited In Information"), so the selector never resolves. Fix:
//   1. `marksort` stamps a unique aria-label on the header (by stable data-key)
//      AND sets pointer-events:none on its descendants so the centre press can't
//      be swallowed by the nested info "i" button,
//   2. `snapshot` then yields a selector that uniquely matches it,
//   3. `click <ref>` trusted-clicks the header CENTRE — now guaranteed to hit the
//      pressable columnheader — which sorts; then `unmarksort` restores it.
// React Aria cycles none → ascending → descending, so up to two clicks reach
// descending; cap at 4. All waits live HERE (not in a page eval). If the click
// still can't sort, the collection below falls back to ranking by value, so
// correctness never depends on the sort succeeding.
function findMarkRef(snapshotText, token) {
  // Match the COLUMNHEADER line whose accessible name is EXACTLY the token
  // (quoted). The marker also appears inside the parent header-row's aggregate
  // name (e.g. `row "URL … LLMOSORTTARGET … Markets Information"`), and that line
  // comes first — matching it builds a selector from the whole concatenation that
  // resolves to nothing. So require role=columnheader AND the quoted exact name.
  const quoted = `"${token}"`;
  for (const line of (snapshotText || '').split('\n')) {
    if (!/columnheader/i.test(line)) continue;
    if (!line.includes(quoted)) continue;
    const m = /\[ref=([^\]]+)\]/.exec(line);
    if (m) return m[1];
  }
  // Fallback: any line whose name is exactly the token (covers a build that labels
  // the header with a role other than columnheader).
  for (const line of (snapshotText || '').split('\n')) {
    if (!line.includes(quoted)) continue;
    const m = /\[ref=([^\]]+)\]/.exec(line);
    if (m) return m[1];
  }
  return null;
}
async function clickSortHeader(tabId) {
  let mark;
  try {
    mark = await runStep(tabId, 'marksort');
  } catch (e) {
    return { ok: false, error: `marksort failed: ${e.message}` };
  }
  if (!mark.ok || mark.error) return { ok: false, error: mark.error || 'sort-header-not-found', mark };
  const snap = await exec(`playwright-cli snapshot --tab=${tabId} --no-iframes`);
  if (snap.exitCode !== 0) return { ok: false, error: `snapshot failed: ${snap.stderr || snap.stdout}`, mark };
  const ref = findMarkRef(snap.stdout, mark.token);
  if (!ref) return { ok: false, error: 'marked header not found in snapshot', mark };
  const clicked = await exec(`playwright-cli click --tab=${tabId} ${ref}`);
  if (clicked.exitCode !== 0) return { ok: false, error: `click failed: ${clicked.stderr || clicked.stdout}`, mark };
  return { ok: true, mark };
}

// Clear any stray info-description dialog a prior run may have left open (it can
// trap focus); Escape goes to whatever's focused and is harmless otherwise.
await exec(`playwright-cli press --tab=${tab.id} Escape`);

let sortState = meta.aria_sort;
let sortConfirmed = isDescending(meta.aria_sort, metaVals);
let clicks = 0;
let lastMark = null;
// React Aria cycles none → ascending → descending. We keep clicking until
// aria-sort is DESCENDING (authoritative) — a single click usually lands on
// ascending, whose all-equal top (e.g. lots of "1 prompt") must NOT be mistaken
// for sorted. Cap at 4 clicks for margin.
while (!sortConfirmed && clicks < 4) {
  const c = await clickSortHeader(tab.id);
  if (c.mark) lastMark = c.mark;
  if (!c.ok) {
    console.warn(`sort: ${c.error}. Falling back to ranking by value.`);
    break;
  }
  clicks++;
  await wait(1800); // server re-fetch + virtualizer settle (outside any page eval)
  let st;
  try {
    st = await runStep(tab.id, 'sortstate');
  } catch (e) {
    console.warn(`sort: could not read sort state: ${e.message}`);
    break;
  }
  sortState = st.aria_sort;
  if (isDescending(st.aria_sort, st.top_values)) {
    sortConfirmed = true;
    break;
  }
  // Not yet descending (likely ascending after the first click) — click again.
}
// Restore the header's accessible name (best-effort).
await exec(`playwright-cli eval --tab=${tab.id} ${JSON.stringify(`window.__LLMO_CMD__='unmarksort';window.__LLMO_SORT_KEY__=${JSON.stringify(SORT.key)};'ok'`)}`);
try {
  await exec(`playwright-cli eval-file --tab=${tab.id} ${EXTRACTOR}`);
} catch {}
if (sortConfirmed) {
  console.log(`Sorted by ${SORT.label} (descending) via ${clicks} trusted click(s).`);
} else if (clicks > 0) {
  console.warn(
    `sort: could not confirm descending after ${clicks} trusted click(s) ` +
      `(last aria-sort=${JSON.stringify(sortState)}). Falling back to ranking by value.`,
  );
  if (lastMark && lastMark.header_html) console.warn(`sort: header DOM was: ${lastMark.header_html}`);
}

const collected = new Map(); // _rowindex -> row
const addRows = (rows) => {
  for (const r of rows || []) if (r && r._rowindex != null && !collected.has(r._rowindex)) collected.set(r._rowindex, r);
};

// Step 2: grab the first window from the top of the grid.
let firstWindow;
try {
  firstWindow = await runStep(tab.id, 'scan', 0);
} catch (e) {
  firstWindow = meta;
}
addRows(firstWindow.rows);

const total = meta.total_cited_urls || 0;
const clientHeight = firstWindow.clientHeight || meta.clientHeight || 400;
const scrollHeight = firstWindow.scrollHeight || meta.scrollHeight || 0;
// SMALL, OVERLAPPING steps so every row passes through a mounted window before
// the virtualizer (and the server paging behind it) recycles it.
const step = Math.max(80, Math.floor(clientHeight * 0.4));

// Decide the strategy:
//   FAST PATH  — the grid is sorted descending by the chosen metric (our trusted
//                press above took, or the operator pre-sorted it), so the top N by
//                row index IS the top N by metric — collect just rows 2..N+1.
//   ROBUST PATH — the sort didn't take, so collect EVERY row and rank by the
//                 metric ourselves. Correct regardless of the grid's order; this
//                 is the safety net that keeps results right when the sort fails.
const firstVals = (firstWindow.rows || [])
  .slice()
  .sort((a, b) => (a._rowindex || 0) - (b._rowindex || 0))
  .map((r) => r[SORT.field]);
// Use the STRICT check here too: a fast-path decision based on all-equal top
// values (an ascending sort's ties) would silently collect the WRONG rows.
const preSorted =
  (sortConfirmed || strictDescVals(firstVals)) &&
  firstVals.filter((x) => x != null).length >= Math.min(3, total);

// Scroll-collect until `enough()` returns true (or we exhaust the grid). Rows
// load lazily, so when a scan adds nothing we wait in place before advancing.
// Progress is judged by ROW GROWTH and how far DOWN the virtualizer has mounted
// (max aria-rowindex) — NOT the grid's scrollTop geometry, which is meaningless
// when an OUTER panel or the window does the scrolling (grid scrollHeight ===
// clientHeight). When several scans in a row reveal nothing new AND don't mount
// any further, we've reached the real bottom.
const STALL_LIMIT = 5;
async function collect(enough, cap) {
  let offset = (firstWindow.scrollTop || 0) + step;
  let scans = 0;
  let maxScrollTop = firstWindow.scrollTop || 0;
  let maxRowIdx = Math.max(0, ...[...collected.keys()].map((k) => +k || 0));
  let stall = 0;
  while (!enough() && scans < cap) {
    let scan;
    try {
      scan = await scanAt(tab.id, offset);
    } catch (e) {
      console.warn(`scan retries exhausted at offset ${offset}: ${e.message}. Stopping with what we have.`);
      break;
    }
    scans++;
    const before = collected.size;
    addRows(scan.rows);
    const grew = collected.size > before;
    const advanced = (scan.maxRowIndex || 0) > maxRowIdx; // virtualizer mounted further down
    if (grew || advanced) {
      stall = 0;
      maxRowIdx = Math.max(maxRowIdx, scan.maxRowIndex || 0);
    } else if (++stall >= STALL_LIMIT) {
      break; // no new rows and no further mounting ⇒ real bottom
    } else {
      await wait(400); // give lazy / server-backed rows a beat to arrive
    }
    offset = (scan.scrollTop || 0) + step; // advances the legacy inner-scroller case
    maxScrollTop = Math.max(maxScrollTop, scan.scrollTop || 0);
  }
  return { scans, maxScrollTop };
}

const haveTopN = () => {
  for (let ri = 2; ri <= TOP_N + 1; ri++) if (!collected.has(ri)) return false;
  return true;
};
// NB: we deliberately do NOT trust `total` (aria-rowcount) to decide we're done.
// Adobe's grid can under-report it (e.g. 10) while more rows load on scroll, so
// the robust sweep below runs until the virtualizer stops mounting new rows
// (collect()'s STALL_LIMIT), not until we hit `total`.

let mode = preSorted ? 'fast' : 'robust';
console.log(
  `Grid ${preSorted ? 'appears pre-sorted' : 'is not sorted'} by ${SORT.label} — ` +
    `${preSorted ? 'collecting top ' + TOP_N : 'collecting all ' + (total || '?') + ' rows then ranking'}.`,
);

// FAST path first (cheap). If the result turns out not to be descending (the
// pre-sort guess was wrong), fall through to a full collection + client-side sort.
let diag = { scans: 0, maxScrollTop: firstWindow.scrollTop || 0 };
if (mode === 'fast') {
  diag = await collect(haveTopN, MAX_SCANS);
}

function topByRowIndex() {
  return [...collected.values()].sort((a, b) => a._rowindex - b._rowindex).slice(0, TOP_N);
}
function topByMetric() {
  return [...collected.values()]
    .sort((a, b) => (b[SORT.field] ?? -Infinity) - (a[SORT.field] ?? -Infinity) || a._rowindex - b._rowindex)
    .slice(0, TOP_N);
}

let candidate = mode === 'fast' ? topByRowIndex() : [];
const candidateDesc = (rows) => rows.every((r, i) => i === 0 || (rows[i - 1][SORT.field] ?? -Infinity) >= (r[SORT.field] ?? -Infinity));

if (mode === 'fast' && (!candidateDesc(candidate) || candidate.length < Math.min(TOP_N, total || TOP_N))) {
  // Pre-sort assumption failed (or we came up short) — collect everything and rank.
  console.log('Grid was not actually sorted as expected — collecting all rows and ranking by value.');
  mode = 'robust';
}
if (mode === 'robust') {
  // Bound the full sweep generously; typical cited-URL lists are hundreds of rows.
  const cap = Math.max(MAX_SCANS, (total || TOP_N) * 4 + 80);
  const d = await collect(() => false, cap);
  diag = { scans: diag.scans + d.scans, maxScrollTop: Math.max(diag.maxScrollTop, d.maxScrollTop) };
  candidate = topByMetric();
}

const rows = candidate;
rows.forEach((r, i) => {
  r.citation_rank = i + 1; // rank by the chosen metric (kept name for downstream)
  r.selected_by = SORT.field; // which column drove selection: citation_count | prompts_cited_in
  r.platform = platform; // global Platform filter — same for every row
  delete r._rowindex;
});

if (rows.length === 0) {
  console.error('export-cited-urls: no rows extracted — is the grid visible and populated?');
  process.exit(1);
}
const sortedDesc = rows.every(
  (r, i) => i === 0 || (rows[i - 1][SORT.field] ?? -Infinity) >= (r[SORT.field] ?? -Infinity)
);
if (rows.length < TOP_N) {
  console.warn(
    `WARNING: requested ${TOP_N} but only collected ${collected.size} of ${total || '?'} rows ` +
      `(scans=${diag.scans}). scroll diag: clientHeight=${clientHeight} scrollHeight=${scrollHeight} ` +
      `maxScrollTop=${diag.maxScrollTop} scroller_is_grid=${meta.scroller_is_grid}. ` +
      `If scrollHeight≈clientHeight the scroll container wasn't found.`,
  );
}
if (!sortedDesc) {
  console.warn(`WARNING: final rows are not strictly descending by ${SORT.field}.`);
}

await fs.writeFile(OUT, JSON.stringify(rows, null, 2));

console.log(`Wrote ${rows.length} cited URLs (of ${total} total, by ${SORT.label}) -> ${OUT}`);
console.log(`Top results (by ${SORT.label}):`);
for (const r of rows.slice(0, Math.min(10, rows.length))) {
  const trend = r.citation_trend && r.citation_trend !== 'stable' ? ` (${r.citation_trend})` : '';
  const metricVal = r[SORT.field];
  const suffix = SORT.field === 'prompts_cited_in' ? ' prompts' : 'x';
  console.log(`  ${String(r.citation_rank).padStart(2)}. ${metricVal}${suffix}${trend}  ${r.url}`);
}
