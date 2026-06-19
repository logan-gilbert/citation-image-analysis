// extract-llmo-list.js — Stage 01 in-page helper for Adobe LLM Optimizer.
//
// Runs in the page context via `playwright-cli eval-file --tab=<id>`. It is
// invoked REPEATEDLY by the export-cited-urls.jsh orchestrator, one short call
// per step, so no single Runtime.evaluate approaches the CDP 30s timeout (the
// scroll loop and all waits live in the .jsh, not here). This script READS the
// grid and MARKS the sort header, but it does NOT itself activate the sort:
// React Aria ignores synthetic in-page click/key events, so the orchestrator
// performs a TRUSTED `playwright-cli click` on the header (CDP
// Input.dispatchMouseEvent at the header's centre — on the column TEXT, away from
// the info "i" button at the right edge). The action is chosen by globals set
// before each call:
//
//   window.__LLMO_CMD__        'metrics' (default) | 'sortstate' | 'marksort' | 'scan'
//                              | 'pagesize' | 'markpage' | 'unmarkpage'
//                              | 'pageoptions' | 'markoption' (items-per-page)
//   window.__LLMO_SCROLL_TO__  number — for 'scan', the scroll viewport's scrollTop
//   window.__LLMO_SORT_KEY__   'citations' (default, Times Cited) | 'promptsCited'
//                              (Prompts Cited In) — the column to sort/rank by
//   window.__LLMO_PAGE_TARGET__ number — for 'markoption', the items-per-page
//                              option value to select (e.g. 50)
//
//   - 'metrics': locate the grid; return current aria-sort, detected platform,
//     grid metrics, column map, and the currently-visible rows. No interaction.
//   - 'sortstate': return the active column's aria-sort + top visible values
//     (cheap probe used by the orchestrator between trusted clicks).
//   - 'marksort': stamp a unique aria-label on the active column header (targeted
//     by stable data-key) so SLICC's snapshot — whose name-based selectors can't
//     otherwise resolve this header — produces a selector that matches it. The
//     orchestrator then snapshots + trusted-clicks that ref. Returns the token,
//     the current aria-sort/top values, and the header's outerHTML (for debugging).
//   - 'scan': set the viewport scrollTop, let the virtualizer mount, return the
//     rows currently in the DOM (keyed by aria-rowindex via `_rowindex`).
//   - 'pagesize'/'markpage'/'unmarkpage'/'pageoptions'/'markoption': drive the
//     "Items per page" React Aria popup button the same trusted-click way as the
//     sort header — read the current size, mark the button to open the listbox,
//     read the available option values, then mark the target option to select it
//     (so the grid loads enough rows to collect the requested top N).
//
// Selectors key off STABLE attributes only — ARIA roles, data-key, aria-colindex,
// aria-sort, aria-rowindex. The grid's CSS classes are build-hashed; never use
// them. The grid is an Adobe Spectrum / React Aria virtualized grid (no <table>).
// The scroll container is NOT the [role=grid] element — it's a separate inner
// viewport (overflow:auto) found via findScroller(); scroll THAT, not the grid.

(async () => {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  const KNOWN_COLS = {
    url: 'URL',
    agenticHits: 'Citation Attempts',
    citations: 'Times Cited',
    promptsCited: 'Prompts Cited In',
    referralHits: 'Referral Hits',
    categories: 'Categories',
    regions: 'Markets',
  };

  // Best-effort read of the active "Platform" filter (e.g. "ChatGPT (Free)").
  // The whole cited-URLs dataset is scoped to this platform, so it applies to
  // every row. Prefer interactive controls, then fall back to any short leaf.
  const KNOWN_PLATFORMS = [
    'ChatGPT',
    'Google AI Overviews',
    'AI Overviews',
    'AI Mode',
    'Perplexity',
    'Gemini',
    'Copilot',
    'Microsoft Copilot',
    'Claude',
    'Meta AI',
    'Grok',
    'DeepSeek',
  ];
  function matchPlatform(raw) {
    const s = (raw || '').trim();
    if (!s || s.length > 40) return null;
    const base = s.replace(/\s*\([^)]*\)\s*$/, '').trim(); // strip a "(Free)" suffix
    for (const k of KNOWN_PLATFORMS) if (base.toLowerCase() === k.toLowerCase()) return s;
    return null;
  }
  // A control's selected value can live in .value (inputs/comboboxes), the value
  // attribute, or textContent — check all three.
  function controlText(el) {
    const v = el.value != null ? String(el.value) : '';
    return v || el.getAttribute('value') || el.textContent || '';
  }
  function detectPlatform() {
    // 1) Most reliable: the combobox explicitly labelled "Platform" (its value
    //    holds the active selection, e.g. an <input value="ChatGPT (Free)">).
    for (const el of document.querySelectorAll('[aria-label]')) {
      if (!/^platform$/i.test((el.getAttribute('aria-label') || '').trim())) continue;
      const v = controlText(el).trim();
      if (v) return matchPlatform(v) || v; // trust the labelled control even for new platforms
    }
    // 2) Bounded scan of interactive controls — NEVER a full-DOM
    //    querySelectorAll('*') (that scans tens of thousands of nodes on the
    //    live LLMO app and can blow the CDP 30s eval budget).
    const controls = document.querySelectorAll(
      '[role="combobox"],[aria-haspopup],[aria-pressed="true"],[role="option"][aria-selected="true"],button,[role="button"],input'
    );
    let i = 0;
    for (const el of controls) {
      if (++i > 800) break;
      const hit = matchPlatform(controlText(el));
      if (hit) return hit;
    }
    return null;
  }

  function findGrid() {
    const grids = [...document.querySelectorAll('[role="grid"]')];
    return (
      grids.find((g) => g.querySelector('[role="columnheader"][data-key="citations"]')) ||
      grids.find((g) => g.querySelector('[role="row"]')) ||
      grids[0] ||
      null
    );
  }

  // The [role=grid] element is usually NOT the scroll container — React Aria /
  // Spectrum virtualized grids scroll an inner viewport (overflow:auto) whose
  // scrollHeight spans all rows. Setting scrollTop on the wrong element does
  // nothing (the visible window never changes). Pick the best scrollable element
  // among the grid, its descendants, and a few ancestors.
  function scrollableScore(el) {
    const sh = el.scrollHeight || 0;
    const ch = el.clientHeight || 0;
    if (ch <= 0 || sh <= ch + 4) return -1;
    let score = sh;
    try {
      const oy = getComputedStyle(el).overflowY;
      if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') score += 1e9;
    } catch {}
    return score;
  }
  function findScroller(grid) {
    const cands = [grid, ...grid.querySelectorAll('*')];
    let p = grid.parentElement;
    let hops = 0;
    while (p && p !== document.body && hops < 10) {
      cands.push(p);
      p = p.parentElement;
      hops++;
    }
    let best = grid;
    let bestScore = scrollableScore(grid);
    for (const el of cands) {
      const s = scrollableScore(el);
      if (s > bestScore) {
        bestScore = s;
        best = el;
      }
    }
    return best;
  }

  function headerMap(grid) {
    const m = {};
    for (const h of grid.querySelectorAll('[role="columnheader"]')) {
      const key = h.getAttribute('data-key');
      const ci = parseInt(h.getAttribute('aria-colindex') || '', 10);
      if (key && ci) m[key] = ci;
    }
    return m;
  }

  // Which column we sort by + collect the top N on. Default 'citations'
  // (Times Cited); the orchestrator can switch it to 'promptsCited' via the
  // __LLMO_SORT_KEY__ global. `field` is the corresponding parsed row property.
  const SORT_COLS = {
    citations: { label: 'Times Cited', field: 'citation_count' },
    promptsCited: { label: 'Prompts Cited In', field: 'prompts_cited_in' },
  };
  function sortKey() {
    const k = window.__LLMO_SORT_KEY__;
    return SORT_COLS[k] ? k : 'citations';
  }
  function sortHeader(grid) {
    const key = sortKey();
    const label = SORT_COLS[key].label;
    return (
      grid.querySelector(`[role="columnheader"][data-key="${key}"]`) ||
      [...grid.querySelectorAll('[role="columnheader"]')].find(
        (h) => (h.textContent || '').trim().toLowerCase() === label.toLowerCase()
      ) ||
      null
    );
  }

  function headerSort(grid) {
    const h = sortHeader(grid);
    return h ? h.getAttribute('aria-sort') : null;
  }

  // Top visible values of the ACTIVE sort column (citation_count or
  // prompts_cited_in) — lets the orchestrator confirm descending order.
  function topValues(grid, H, n) {
    const field = SORT_COLS[sortKey()].field;
    return readVisible(grid, H)
      .sort((a, b) => a._rowindex - b._rowindex)
      .slice(0, n || 6)
      .map((r) => r[field]);
  }

  function cellByCol(row, col) {
    if (!col) return null;
    return (
      row.querySelector(`[role="rowheader"][aria-colindex="${col}"]`) ||
      row.querySelector(`[role="gridcell"][aria-colindex="${col}"]`) ||
      row.querySelector(`[aria-colindex="${col}"]`) ||
      row.querySelector(`[data-column-index="${col - 1}"]`)
    );
  }

  const txt = (el) => (el ? (el.textContent || '').trim() : '');
  const intOf = (s) => {
    const d = String(s).replace(/[^\d-]/g, '');
    return d === '' || d === '-' ? null : parseInt(d, 10);
  };
  const trendOf = (s) => (/↑|▲/.test(s) ? 'rising' : /↓|▼/.test(s) ? 'declining' : 'stable');

  function pills(cell) {
    if (!cell) return [];
    const seen = new Set();
    const out = [];
    const push = (t) => {
      const s = (t || '').trim();
      if (!s || seen.has(s)) return;
      if (/^\+\s*\d+$/.test(s)) return; // overflow chip like "+2"
      if (/^\d+\s*more$/i.test(s)) return; // "3 more"
      seen.add(s);
      out.push(s);
    };

    // 1) When chips are truncated, the full comma/semicolon list is usually kept
    //    in a title or aria-label on the cell (or a descendant). Prefer that.
    const titled = [cell, ...cell.querySelectorAll('[title],[aria-label]')];
    for (const el of titled) {
      const tv =
        (el.getAttribute && (el.getAttribute('title') || el.getAttribute('aria-label'))) || '';
      if (tv && /[,;]/.test(tv)) {
        tv.split(/[,;]/).forEach(push);
        if (out.length) return out;
      }
    }

    // 2) React Spectrum renders each chip's label as a <span data-rsp-slot="text">.
    //    This slot attribute is part of the RSP runtime, so (unlike the hashed
    //    class names) it's stable across builds. This is the primary path.
    const slots = cell.querySelectorAll('[data-rsp-slot="text"]');
    if (slots.length) {
      slots.forEach((s) => push(s.textContent));
      if (out.length) return out;
    }

    // 3) Explicit chip/list elements (roles survive build-hashing; class names don't).
    const chips = cell.querySelectorAll('[role="listitem"],[role="option"],li');
    if (chips.length) {
      chips.forEach((c) => push(c.textContent));
      if (out.length) return out;
    }

    // 4) Fallback: leaf text nodes.
    for (const el of cell.querySelectorAll('*')) {
      if (el.children.length !== 0) continue;
      push(el.textContent);
    }
    // 5) Last resort: the cell's own text, split on commas if present.
    if (!out.length) {
      const t = (cell.textContent || '').trim();
      if (/[,;]/.test(t)) t.split(/[,;]/).forEach(push);
      else push(t);
    }
    return out;
  }

  function parseRow(row, H, rowindex) {
    const urlCell = cellByCol(row, H.url || 1);
    const a = urlCell ? urlCell.querySelector('a[href]') : null;
    const url = a ? a.href : null;
    if (!url) return null;
    const citTxt = txt(cellByCol(row, H.citations));
    return {
      _rowindex: rowindex,
      url,
      citation_count: intOf(citTxt),
      citation_trend: trendOf(citTxt),
      citation_attempts: intOf(txt(cellByCol(row, H.agenticHits))),
      prompts_cited_in: intOf(txt(cellByCol(row, H.promptsCited))),
      referral_hits: intOf(txt(cellByCol(row, H.referralHits))),
      categories: pills(cellByCol(row, H.categories)),
      markets: pills(cellByCol(row, H.regions)),
    };
  }

  function readVisible(grid, H) {
    const rows = [];
    for (const row of grid.querySelectorAll('[role="row"]')) {
      const ri = parseInt(row.getAttribute('aria-rowindex') || '', 10);
      if (!ri || ri < 2) continue; // rowindex 1 is the header row
      const obj = parseRow(row, H, ri);
      if (obj) rows.push(obj);
    }
    return rows;
  }

  // --- dispatch ---
  const grid = findGrid();
  if (!grid) {
    return {
      ok: false,
      error: 'no-cited-urls-grid-found',
      hint: 'Is the operator on the LLM Optimizer URL Inspector "Your Cited URLs" view?',
      roles_present: [
        ...new Set([...document.querySelectorAll('[role]')].map((e) => e.getAttribute('role'))),
      ].slice(0, 40),
    };
  }

  const H = headerMap(grid);
  const cmd = window.__LLMO_CMD__ || 'metrics';

  const scroller = findScroller(grid);

  if (cmd === 'scan') {
    const to = window.__LLMO_SCROLL_TO__;
    // Advance the virtualizer in a way AGNOSTIC to which element actually scrolls.
    // Adobe's grid sometimes scrolls an inner viewport (overflow:auto) and
    // sometimes loads ~10 rows and scrolls the OUTER panel / window instead — then
    // the grid's own scrollHeight === clientHeight and setting its scrollTop does
    // nothing. So (1) set scrollTop on the detected inner scroller for the legacy
    // case, and (2) pull the LAST mounted row to the viewport bottom with
    // scrollIntoView, which walks up to whichever ancestor is the real scroller
    // (inner viewport, outer panel, or the window) and nudges the next batch in.
    const advance = typeof to === 'number' && isFinite(to) && to > 0;
    if (typeof to === 'number' && isFinite(to)) {
      try {
        scroller.scrollTop = to;
        // Some virtualizers only re-mount on the scroll event, not a scrollTop set.
        scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
      } catch {}
    }
    if (advance) {
      try {
        const rs = grid.querySelectorAll('[role="row"]');
        const last = rs[rs.length - 1];
        if (last && last.scrollIntoView) last.scrollIntoView({ block: 'end', inline: 'nearest' });
      } catch {}
    }
    await wait(350); // let the virtualizer mount + server-backed rows arrive
    const vis = readVisible(grid, H);
    const maxRowIndex = vis.reduce((m, r) => Math.max(m, r._rowindex || 0), 0);
    let winY = 0;
    try {
      winY = window.scrollY || (document.scrollingElement || document.documentElement).scrollTop || 0;
    } catch {}
    return {
      ok: true,
      mode: 'scan',
      scrollTop: scroller.scrollTop,
      clientHeight: scroller.clientHeight,
      scrollHeight: scroller.scrollHeight,
      winY,
      maxRowIndex,
      rows: vis,
    };
  }

  if (cmd === 'sortstate') {
    // Cheap probe used between header clicks: current sort + the top visible
    // values of the active column. No scrolling, no platform scan.
    return {
      ok: true,
      mode: 'sortstate',
      sort_key: sortKey(),
      aria_sort: headerSort(grid),
      top_values: topValues(grid, H, 6),
    };
  }

  const MARK_TOKEN = 'LLMOSORTTARGET';
  if (cmd === 'marksort') {
    // Stamp a unique aria-label on the sort HEADER (by stable data-key) so the
    // orchestrator's snapshot produces a name-based selector that resolves to it.
    //
    // SLICC's `playwright-cli click` clicks an element's geometric CENTRE. The
    // header's nested info "i" button can sit at (or near) that centre depending
    // on column width / text alignment, so a centre-click would land on the icon
    // and open a focus-trapping description dialog instead of sorting. To make the
    // click land on the pressable columnheader regardless of where the icon is, we
    // disable pointer-events on ALL of the header's descendants here — so the
    // trusted mouse press at the centre passes through to the columnheader itself
    // (which carries data-react-aria-pressable and the usePress handler). The grid
    // re-renders after each sort, so marksort (which runs before every click)
    // re-applies this each time; `unmarksort` clears it at the end.
    //
    // Why a marker at all: the header carries no aria-label/title of its own, and
    // its accessible NAME is "Prompts Cited In Information" (the info-icon's label
    // merges in) → the snapshot's name-based selector matches no DOM node. Stamping
    // our own aria-label gives the snapshot a selector that uniquely hits the header.
    const h = sortHeader(grid);
    if (!h)
      return { ok: false, mode: 'marksort', error: 'sort-header-not-found', sort_key: sortKey() };
    try {
      h.setAttribute('aria-label', MARK_TOKEN);
    } catch {}
    // Make every child transparent to hit-testing so the centre press targets the
    // pressable columnheader, never the info "i" button.
    let neutralized = 0;
    try {
      for (const el of h.querySelectorAll('*')) {
        el.style.pointerEvents = 'none';
        neutralized++;
      }
    } catch {}
    let rect = null;
    try {
      const r = h.getBoundingClientRect();
      rect = {
        x: Math.round(r.x),
        y: Math.round(r.y),
        w: Math.round(r.width),
        h: Math.round(r.height),
      };
    } catch {}
    return {
      ok: true,
      mode: 'marksort',
      sort_key: sortKey(),
      token: MARK_TOKEN,
      aria_sort: headerSort(grid),
      top_values: topValues(grid, H, 6),
      rect,
      neutralized, // # of descendants made pointer-events:none for the click
      header_html: (h.outerHTML || '').slice(0, 300), // truncated — for debugging the selector
    };
  }
  if (cmd === 'unmarksort') {
    // Remove the marker so the header's accessible name is restored, and clear the
    // pointer-events:none we set on the header's descendants in marksort.
    const h = sortHeader(grid);
    if (h) {
      if (h.getAttribute('aria-label') === MARK_TOKEN) {
        try {
          h.removeAttribute('aria-label');
        } catch {}
      }
      try {
        for (const el of h.querySelectorAll('*')) el.style.pointerEvents = '';
      } catch {}
    }
    return { ok: true, mode: 'unmarksort', sort_key: sortKey() };
  }

  // --- items-per-page pagination control ---------------------------------
  // The pagination footer has a React Aria popup button (aria-haspopup="listbox",
  // aria-label="Items per page") whose label shows the current size (e.g. "20").
  // It caps how many rows the grid will ever mount, so collecting more than the
  // current size requires bumping it. Driven with the SAME trusted-click pattern
  // as the sort header: mark → snapshot → trusted click. Keyed off the stable
  // aria-label / role=option, never hashed classes.
  // The button's accessible NAME — aria-label wins, else the resolved text of the
  // aria-labelledby targets, else its own text. (Different LLMO builds label this
  // control either way.)
  function accName(el) {
    const al = (el.getAttribute('aria-label') || '').trim();
    if (al) return al;
    const lb = el.getAttribute('aria-labelledby');
    if (lb) {
      return lb
        .split(/\s+/)
        .map((id) => {
          const e = document.getElementById(id);
          return e ? (e.textContent || '').trim() : '';
        })
        .filter(Boolean)
        .join(' ')
        .trim();
    }
    return (el.textContent || '').trim();
  }
  // The current page-size shown on the picker — its [data-slot=label] span, else
  // any numeric run in its text.
  function pickerLabelText(el) {
    if (!el) return '';
    const lab = el.querySelector('[data-slot="label"]') || el.querySelector('[data-rsp-slot="text"]');
    return ((lab ? lab.textContent : el.textContent) || '').trim();
  }
  const PAGE_SIZES = new Set([10, 15, 20, 25, 30, 50, 100, 200]);
  function findPageButton() {
    const cands = [...document.querySelectorAll('[aria-haspopup="listbox"],button,[role="button"]')];
    // 1) precise: accessible name (aria-label OR labelledby text) says "per page".
    for (const el of cands) {
      if (/items?\s*per\s*page/i.test(accName(el))) return el;
    }
    for (const el of cands) {
      if (/per\s*page/i.test(accName(el) + ' ' + (el.getAttribute('title') || ''))) return el;
    }
    // 2) heuristic: a listbox-popup whose CURRENT label is just a page-size number
    //    (e.g. "10"/"20"/"50"). The Platform combobox's value is text, not a bare
    //    number, so this won't collide with it.
    for (const el of cands) {
      if (el.getAttribute('aria-haspopup') !== 'listbox') continue;
      const m = pickerLabelText(el).match(/^\s*(\d{1,3})\s*$/);
      if (m && PAGE_SIZES.has(parseInt(m[1], 10))) return el;
    }
    return null;
  }
  function pageButtonValue(btn) {
    if (!btn) return null;
    const m = pickerLabelText(btn).match(/\d+/) || String(btn.textContent || '').match(/\d+/);
    return m ? parseInt(m[0], 10) : null;
  }
  // For diagnostics when detection fails: every popup/picker on the page.
  function pagePickerDump() {
    const out = [];
    for (const el of document.querySelectorAll('[aria-haspopup],[role="button"],button')) {
      const hp = el.getAttribute('aria-haspopup');
      if (!hp && el.tagName !== 'BUTTON' && el.getAttribute('role') !== 'button') continue;
      const name = accName(el).slice(0, 50);
      const label = pickerLabelText(el).slice(0, 20);
      if (!hp && !/page|\d/.test(name + label)) continue; // keep the dump small
      out.push({ tag: el.tagName.toLowerCase(), haspopup: hp || null, name, label });
      if (out.length >= 12) break;
    }
    return out;
  }
  // Numeric option values currently in the open listbox (role=option).
  function openPageOptions() {
    const out = [];
    for (const o of document.querySelectorAll('[role="option"]')) {
      const m = (o.textContent || '').match(/\d+/);
      if (m) out.push({ value: parseInt(m[0], 10), el: o });
    }
    return out;
  }
  const PAGE_TOKEN = 'LLMOPAGETARGET';

  if (cmd === 'pagesize') {
    const btn = findPageButton();
    return {
      ok: true,
      mode: 'pagesize',
      found: !!btn,
      current: pageButtonValue(btn),
      html: btn ? (btn.outerHTML || '').slice(0, 220) : null,
      pickers: btn ? undefined : pagePickerDump(), // only when we couldn't find it
    };
  }
  // Stamp our token as the element's accessible NAME. The page button carries
  // BOTH aria-label and aria-labelledby, and aria-labelledby WINS per the a11y
  // name spec — so we must temporarily remove aria-labelledby (stashing it for
  // restore) for our aria-label to take effect in SLICC's snapshot.
  function markName(el, token) {
    const lb = el.getAttribute('aria-labelledby');
    if (lb != null) {
      try {
        el.setAttribute('data-llmo-prev-labelledby', lb);
        el.removeAttribute('aria-labelledby');
      } catch {}
    }
    try {
      el.setAttribute('aria-label', token);
    } catch {}
  }
  function unmarkName(el, token) {
    if (!el) return;
    try {
      if (el.getAttribute('aria-label') === token) el.removeAttribute('aria-label');
    } catch {}
    const prev = el.getAttribute('data-llmo-prev-labelledby');
    if (prev != null) {
      try {
        el.setAttribute('aria-labelledby', prev);
        el.removeAttribute('data-llmo-prev-labelledby');
      } catch {}
    }
  }

  if (cmd === 'markpage') {
    const btn = findPageButton();
    if (!btn) return { ok: false, mode: 'markpage', error: 'page-button-not-found' };
    markName(btn, PAGE_TOKEN);
    let neutralized = 0;
    try {
      for (const el of btn.querySelectorAll('*')) {
        el.style.pointerEvents = 'none';
        neutralized++;
      }
    } catch {}
    let rect = null;
    try {
      const r = btn.getBoundingClientRect();
      rect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
    } catch {}
    return { ok: true, mode: 'markpage', token: PAGE_TOKEN, current: pageButtonValue(btn), neutralized, rect };
  }
  if (cmd === 'unmarkpage') {
    const btn =
      document.querySelector(`[aria-label="${PAGE_TOKEN}"]`) ||
      document.querySelector('[data-llmo-prev-labelledby]') ||
      findPageButton();
    if (btn) {
      unmarkName(btn, PAGE_TOKEN);
      try {
        for (const el of btn.querySelectorAll('*')) el.style.pointerEvents = '';
      } catch {}
    }
    return { ok: true, mode: 'unmarkpage' };
  }
  if (cmd === 'pageoptions') {
    const vals = openPageOptions().map((o) => o.value).filter((v) => v != null);
    return { ok: true, mode: 'pageoptions', options: [...new Set(vals)].sort((a, b) => a - b) };
  }
  if (cmd === 'markoption') {
    const target = parseInt(window.__LLMO_PAGE_TARGET__, 10);
    const opts = openPageOptions();
    const hit = opts.find((o) => o.value === target);
    if (!hit)
      return { ok: false, mode: 'markoption', error: 'option-not-found', target, options: opts.map((o) => o.value) };
    markName(hit.el, PAGE_TOKEN);
    let neutralized = 0;
    try {
      for (const el of hit.el.querySelectorAll('*')) {
        el.style.pointerEvents = 'none';
        neutralized++;
      }
    } catch {}
    return { ok: true, mode: 'markoption', token: PAGE_TOKEN, value: target, neutralized };
  }

  // default: 'metrics' — probe only, NO interaction, returns fast.
  return {
    ok: true,
    mode: 'metrics',
    sort_key: sortKey(),
    aria_sort: headerSort(grid),
    platform: detectPlatform(),
    total_cited_urls: (parseInt(grid.getAttribute('aria-rowcount') || '', 10) || 1) - 1,
    clientHeight: scroller.clientHeight,
    scrollHeight: scroller.scrollHeight,
    scrollTop: scroller.scrollTop,
    scroller_is_grid: scroller === grid,
    columns_detected: Object.keys(H).map((k) => ({
      data_key: k,
      label: KNOWN_COLS[k] || null,
      colindex: H[k],
    })),
    rows: readVisible(grid, H),
  };
})();
