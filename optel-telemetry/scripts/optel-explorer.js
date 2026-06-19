// optel-explorer.js — in-page helper for Adobe OpTel (Operational Telemetry).
//
// Runs in the page context via `playwright-cli eval-file --tab=<id>`. It is
// invoked REPEATEDLY by the collect-optel.jsh orchestrator, one short call per
// step, so no single Runtime.evaluate approaches the CDP 30s timeout (all the
// waiting/polling lives in the .jsh, not here).
//
// Unlike Stage 01's LLM Optimizer grid (React Aria, which ignores synthetic
// events and forces a TRUSTED CDP click), OpTel is an Angular / Angular-Material
// app: zone.js binds its handlers with addEventListener, so a synthetic
// `dispatchEvent('input')` updates the reactive form control and a synthetic
// `.click()` fires the button handler. That lets this helper perform the
// fills/clicks directly by STABLE id, and the orchestrator just sequences them
// with waits. If a future OpTel build hardens against synthetic events, swap
// these to snapshot+trusted-click in the orchestrator (like Stage 01).
//
// The action is chosen by globals set before each call:
//   window.__OPTEL_CMD__      'state' (default) | 'readmetrics' | 'filldomain'
//                             | 'clickgenerate' | 'clickexplorer' | 'setfilter'
//   window.__OPTEL_DOMAIN__   for 'filldomain' — the base host (e.g. www.example.com)
//   window.__OPTEL_FILTER__   for 'setfilter'  — the page pathname to filter on
//
//   - 'state': which screen the OpTel tab is on:
//       'generate' — the domain-key form (has the formcontrolname="domain" input)
//       'explorer' — the OpTel Explorer (has #filter and the #lcp/#cls/#inp tiles)
//       'unknown'  — neither matched (wrong tab / still loading)
//     Also reports whether the "OpTel Explorer" button is present (it appears
//     after a domain key is generated) and the current #filter value.
//   - 'readmetrics': read the LCP / CLS / INP / Page views tiles — raw text, the
//     parsed numeric value, and each tile's rating from its score-* class.
//   - 'filldomain': set the domain-key form's domain input + fire input/change.
//   - 'clickgenerate': click the "Generate" button on the domain-key form.
//   - 'clickexplorer': click the "OpTel Explorer" button (opens the Explorer).
//   - 'setfilter': set #filter to a pathname + fire input/change/keyup so the
//     Explorer's tiles refresh in place.
//
// Selectors key off STABLE ids/attributes only (#filter, #lcp, #cls, #inp,
// #pageviews, formcontrolname="domain"); the app's CSS classes are build-hashed,
// so we only read the `score-*` rating class which is semantic and stable.

(() => {
  const cmd = window.__OPTEL_CMD__ || 'state';
  const text = (el) => (el && el.textContent ? el.textContent.trim() : '');

  // "2.6 s" -> 2.6 ; "220 ms" -> 0.22 ; "0.0041" -> 0.0041 (seconds for time
  // metrics, unitless for CLS). Returns null when no number is present.
  function parseSeconds(raw) {
    const s = String(raw == null ? '' : raw).trim();
    const m = s.match(/(-?\d+(?:\.\d+)?)/);
    if (!m) return null;
    let n = parseFloat(m[1]);
    if (Number.isNaN(n)) return null;
    if (/ms\b/i.test(s)) n = n / 1000; // milliseconds -> seconds
    return n;
  }
  function parseFloatOrNull(raw) {
    const m = String(raw == null ? '' : raw).match(/(-?\d+(?:\.\d+)?)/);
    return m ? parseFloat(m[1]) : null;
  }
  // "320k" -> 320000 ; "1.2M" -> 1200000 ; "1,234" -> 1234 ; "950" -> 950.
  function parseCount(raw) {
    const s = String(raw == null ? '' : raw).trim().replace(/,/g, '');
    const m = s.match(/(-?\d+(?:\.\d+)?)\s*([kKmMbB])?/);
    if (!m) return null;
    let n = parseFloat(m[1]);
    if (Number.isNaN(n)) return null;
    const suffix = (m[2] || '').toLowerCase();
    if (suffix === 'k') n *= 1e3;
    else if (suffix === 'm') n *= 1e6;
    else if (suffix === 'b') n *= 1e9;
    return Math.round(n);
  }
  // Map the tile's score-* class to a rating. score-ni = "needs improvement".
  function ratingOf(li) {
    if (!li) return null;
    const cls = li.className || '';
    if (/\bscore-good\b/.test(cls)) return 'good';
    if (/\bscore-ni\b/.test(cls)) return 'needs-improvement';
    if (/\bscore-poor\b/.test(cls)) return 'poor';
    return null;
  }

  // Set a value the way a real keystroke would: use the element's native value
  // setter (Angular/React proxy `.value`, so a plain assignment can be missed),
  // then fire input/change/keyup so reactive form controls + filter listeners update.
  function setNativeValue(el, value) {
    if (!el) return false;
    const proto = Object.getPrototypeOf(el);
    const desc = Object.getOwnPropertyDescriptor(proto, 'value');
    const setter = desc && desc.set;
    el.focus();
    if (setter) setter.call(el, value);
    else el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
    return true;
  }
  function findButtonByText(re) {
    for (const b of document.querySelectorAll('button')) {
      if (re.test((b.textContent || '').trim())) return b;
    }
    return null;
  }

  const domainInput = document.querySelector('input[formcontrolname="domain"]');
  const filterInput = document.querySelector('#filter');
  const lcpTile = document.querySelector('#lcp');

  if (cmd === 'state') {
    // The "OpTel Explorer" button only appears once a domain key is generated.
    let explorerButton = false;
    for (const b of document.querySelectorAll('button')) {
      if (/optel\s*explorer/i.test((b.textContent || '').trim())) {
        explorerButton = true;
        break;
      }
    }
    let screen = 'unknown';
    if (filterInput && lcpTile) screen = 'explorer';
    else if (domainInput) screen = 'generate';
    return {
      ok: true,
      mode: 'state',
      screen,
      has_explorer_button: explorerButton,
      filter_value: filterInput ? String(filterInput.value || '') : null,
      url: location.href,
    };
  }

  if (cmd === 'readmetrics') {
    if (!lcpTile && !filterInput) {
      return { ok: false, mode: 'readmetrics', error: 'not-on-explorer', url: location.href };
    }
    const clsTile = document.querySelector('#cls');
    const inpTile = document.querySelector('#inp');
    const pvTile = document.querySelector('#pageviews');
    const lcpRaw = text(lcpTile && lcpTile.querySelector('p'));
    const clsRaw = text(clsTile && clsTile.querySelector('p'));
    const inpRaw = text(inpTile && inpTile.querySelector('p'));
    // Page views may include a trailing <span class="extra"> (sampling factor);
    // read only the leading number, not the extra.
    const pvP = pvTile && pvTile.querySelector('p');
    let pvRaw = '';
    if (pvP) {
      const extra = pvP.querySelector('.extra');
      pvRaw = extra ? (pvP.textContent || '').replace(extra.textContent || '', '').trim() : text(pvP);
    }
    // "ready" once at least one core-web-vital tile shows a value.
    const ready = !!(lcpRaw || clsRaw || inpRaw);
    return {
      ok: true,
      mode: 'readmetrics',
      ready,
      filter_value: filterInput ? String(filterInput.value || '') : null,
      lcp_raw: lcpRaw,
      lcp_s: parseSeconds(lcpRaw),
      lcp_rating: ratingOf(lcpTile),
      cls_raw: clsRaw,
      cls: parseFloatOrNull(clsRaw),
      cls_rating: ratingOf(clsTile),
      inp_raw: inpRaw,
      inp_s: parseSeconds(inpRaw),
      inp_rating: ratingOf(inpTile),
      page_views_raw: pvRaw,
      page_views: parseCount(pvRaw),
    };
  }

  if (cmd === 'filldomain') {
    if (!domainInput) return { ok: false, mode: 'filldomain', error: 'no-domain-input' };
    const val = String(window.__OPTEL_DOMAIN__ || '').trim();
    if (!val) return { ok: false, mode: 'filldomain', error: 'no-domain-value' };
    setNativeValue(domainInput, val);
    return { ok: true, mode: 'filldomain', value: String(domainInput.value || '') };
  }

  if (cmd === 'clickgenerate') {
    // The Generate button carries the paper-plane icon; match it by its text.
    const btn = findButtonByText(/generate/i) || document.querySelector('button.adobe-button.blue');
    if (!btn) return { ok: false, mode: 'clickgenerate', error: 'no-generate-button' };
    btn.click();
    return { ok: true, mode: 'clickgenerate' };
  }

  if (cmd === 'clickexplorer') {
    const btn = findButtonByText(/optel\s*explorer/i);
    if (!btn) return { ok: false, mode: 'clickexplorer', error: 'no-explorer-button' };
    btn.click();
    return { ok: true, mode: 'clickexplorer' };
  }

  if (cmd === 'setfilter') {
    if (!filterInput) return { ok: false, mode: 'setfilter', error: 'no-filter-input' };
    const val = String(window.__OPTEL_FILTER__ == null ? '' : window.__OPTEL_FILTER__);
    setNativeValue(filterInput, val);
    return { ok: true, mode: 'setfilter', value: String(filterInput.value || '') };
  }

  return { ok: false, error: `unknown-cmd:${cmd}` };
})();
