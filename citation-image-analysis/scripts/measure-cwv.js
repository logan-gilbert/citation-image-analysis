// measure-cwv.js — in-page LAB Core Web Vitals collector for Stage 02.
//
// Runs in the visited page's context via `playwright-cli eval-file` during the
// SAME page visit Stage 02 already performs (no extra page loads). It is a SINGLE
// self-contained ASYNC pass: install PerformanceObservers, await a short settle
// so their buffered callbacks fire, then return the accumulated metrics. eval-file
// runs with awaitPromise:true, so the returned promise is awaited and its value
// serialized — the same pattern Stage 01's extract-llmo-list.js uses. Doing it in
// ONE eval avoids relying on window state surviving between separate eval calls
// (an earlier two-eval version returned dashes for exactly that reason). The
// internal wait (~1.5s) is well under the CDP 30s eval budget.
//
// An observer is MANDATORY: Chrome does NOT return largest-contentful-paint /
// layout-shift / longtask via performance.getEntriesByType() — only via
// PerformanceObserver (that was the "CLS/INP = 0, LCP blank" bug).
//
// This is LAB data (a single controlled load), not field/RUM like OpTel:
//   - LCP: largest contentful paint observed (ms).
//   - CLS: Σ layout-shift values with hadRecentInput == false (simple cumulative
//     sum, not session-windowed — fine for a clearly-labelled proxy).
//   - INP can't be measured without real user interactions, so we report TBT
//     (Total Blocking Time ≈ Σ max(0, longtask − 50ms)) as the lab proxy; the
//     dashboard labels it as such.
//
// Returns null for any metric whose entry type the browser doesn't support, so
// callers can distinguish "0" (measured, none) from "not measurable". The settle
// is overridable via window.__CWV_SETTLE_MS__ (set by the orchestrator).

(async () => {
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const settle = typeof window.__CWV_SETTLE_MS__ === 'number' ? window.__CWV_SETTLE_MS__ : 1500;

  const acc = {
    lcp_ms: 0,
    lcp_entries: 0,
    cls: 0,
    shift_count: 0,
    tbt_ms: 0,
    longtask_count: 0,
    supported: {},
  };
  const handlers = [];
  const observe = (type, onEntry) => {
    try {
      if (!window.PerformanceObserver) {
        acc.supported[type] = false;
        return;
      }
      const list = PerformanceObserver.supportedEntryTypes;
      if (list && !list.includes(type)) {
        acc.supported[type] = false;
        return;
      }
      acc.supported[type] = true;
      const po = new PerformanceObserver((l) => {
        for (const e of l.getEntries()) onEntry(e);
      });
      po.observe({ type, buffered: true });
      handlers.push({ po, onEntry });
    } catch {
      acc.supported[type] = false;
    }
  };

  observe('largest-contentful-paint', (e) => {
    acc.lcp_entries++;
    const t = e.renderTime || e.loadTime || e.startTime || 0;
    if (t > acc.lcp_ms) acc.lcp_ms = t; // keep the largest candidate
  });
  observe('layout-shift', (e) => {
    if (!e.hadRecentInput) {
      acc.cls += e.value || 0;
      acc.shift_count++;
    }
  });
  observe('longtask', (e) => {
    acc.tbt_ms += Math.max(0, (e.duration || 0) - 50);
    acc.longtask_count++;
  });

  await wait(settle);

  // Flush any records queued but not yet dispatched to the callbacks, then stop.
  for (const { po, onEntry } of handlers) {
    try {
      if (po.takeRecords) for (const e of po.takeRecords()) onEntry(e);
    } catch {}
    try {
      po.disconnect();
    } catch {}
  }

  const sup = acc.supported;
  const lcp_ms = acc.lcp_entries > 0 ? acc.lcp_ms : null;
  return {
    ok: true,
    lcp_s: lcp_ms == null ? null : +(lcp_ms / 1000).toFixed(3),
    cls: sup['layout-shift'] ? +acc.cls.toFixed(4) : null,
    tbt_ms: sup['longtask'] ? Math.round(acc.tbt_ms) : null,
    // diagnostics (handy when a page reports nothing)
    lcp_entries: acc.lcp_entries,
    shift_count: acc.shift_count,
    longtask_count: acc.longtask_count,
    supported: sup,
    url: location.href,
  };
})();
