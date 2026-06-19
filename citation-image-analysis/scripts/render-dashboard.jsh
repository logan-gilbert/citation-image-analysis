// render-dashboard — Stage 04 of the citation-image-analysis pipeline.
//
// Generates dashboard.html from dashboard-data.json, plus two sibling JS files
// (<base>.data.js holding window.__DATA__, and <base>.app.js with the renderer).
//
// Why EXTERNAL scripts, not inline: the Chrome extension serves previews from a
// chrome-extension:// origin whose manifest CSP is `script-src 'self'` — inline
// <script> tags are BLOCKED there, so an inlined dataset/renderer silently never
// runs and the page body stays empty. Same-origin external <script src> files
// satisfy 'self' and are served from the VFS by the preview service worker, so
// the dashboard renders identically in the CLI float, the extension float, and a
// plain browser (relative src also resolves over file://).
//
// Deviation from the brief, on purpose: View 3 uses pure CSS bars instead of a
// CDN Chart.js — zero-dependency, no build step, no network.
//
// SLICC does not open file:// URLs — `open dashboard.html` serves the file (and
// its sibling .js) through the preview service worker.
//
// Usage:
//   render-dashboard [input.json] [output.html]
//   defaults: input = dashboard-data.json, output = dashboard.html

const args = process.argv.slice(2);
const INPUT_FILE = args[0] || 'dashboard-data.json';
const OUTPUT_FILE = args[1] || 'dashboard.html';

if (!(await fs.exists(INPUT_FILE))) {
  console.error(`render-dashboard: input not found: ${INPUT_FILE}`);
  process.exit(1);
}

let data;
try {
  data = JSON.parse(await fs.readFile(INPUT_FILE));
} catch (e) {
  console.error(`render-dashboard: cannot parse ${INPUT_FILE}: ${e.message}`);
  process.exit(1);
}

if (!Array.isArray(data) || data.length === 0) {
  console.error('render-dashboard: expected a non-empty JSON array');
  process.exit(1);
}

const generatedAt = new Date().toISOString();
const pageCount = new Set(data.map((r) => r.page_url)).size;
const imageCount = data.filter((r) => r.image_url && !r.page_only).length;
// Which scoring rubric Stage 03 used for this dataset (stamped on every row).
const scoringMode = (data.find((r) => r && r.scoring_mode) || {}).scoring_mode || 'google';
const scoringLabel = scoringMode === 'google' ? 'Google SEO' : 'Original';

// Defined as a real function so .toString() yields literal source — this lets
// the client code use its own template literals without colliding with the
// .jsh template literal that wraps the HTML.
function clientMain() {
  const DATA = window.__DATA__ || [];
  // Which Stage 01 metric drove ranking/selection. When the run used
  // --by=prompts, the overview's primary column shows "Prompts cited in" counts
  // instead of "Times Cited".
  const SELECTED_BY = (DATA.find((r) => r && r.selected_by) || {}).selected_by || 'citation_count';
  const METRIC =
    SELECTED_BY === 'prompts_cited_in'
      ? { field: 'prompts_cited_in', label: 'Prompts cited in', noun: 'prompts' }
      : { field: 'citation_count', label: 'Citations', noun: 'citations' };
  const esc = (s) =>
    String(s == null ? '' : s).replace(
      /[&<>"']/g,
      (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
    );
  const fmtScore = (n) => (n == null ? '—' : Math.round(n));
  const scoreClass = (n) => (n == null ? '' : n <= 40 ? 'red' : n <= 70 ? 'amber' : 'green');

  // --- Core Web Vitals (display-only) ---------------------------------------
  // CWV come from LAB data measured during the Stage 02 visit (--lab-cwv): a
  // synthetic single-load measurement of LCP + CLS, with TBT as a lab proxy for
  // INP (true INP needs real users). Readings are clearly marked as synthetic.
  // CWV are page-level confounders for the image-quality⇄citations question.
  const cwvClass = (rating) =>
    rating === 'good' ? 'green' : rating === 'needs-improvement' ? 'amber' : rating === 'poor' ? 'red' : '';
  // Google thresholds (also used for the lab readings).
  const rateLcp = (s) => (s == null ? null : s <= 2.5 ? 'good' : s <= 4 ? 'needs-improvement' : 'poor');
  const rateCls = (v) => (v == null ? null : v <= 0.1 ? 'good' : v <= 0.25 ? 'needs-improvement' : 'poor');
  // TBT (lab INP proxy) thresholds, per Lighthouse.
  const rateTbt = (ms) => (ms == null ? null : ms <= 200 ? 'good' : ms <= 600 ? 'needs-improvement' : 'poor');
  const fmtSec = (s) => (s == null ? null : (s < 10 ? s.toFixed(1) : Math.round(s)) + ' s');
  const fmtMs = (ms) => (ms == null ? null : Math.round(ms) + ' ms');

  // Resolve a single page's lab CWV into a normalized object. All readings are
  // synthetic single-load measurements; INP is a TBT proxy (inp_proxy: true).
  const pageCwv = (p) => {
    const labHasCwv = p.lab_cwv_measured === true && (p.lab_lcp_s != null || p.lab_cls != null || p.lab_tbt_ms != null);
    if (labHasCwv) {
      const tbt = p.lab_tbt_ms ?? null;
      return {
        source: 'lab',
        lcp_s: p.lab_lcp_s ?? null, lcp_rating: rateLcp(p.lab_lcp_s), lcp_raw: fmtSec(p.lab_lcp_s),
        cls: p.lab_cls ?? null, cls_rating: rateCls(p.lab_cls), cls_raw: p.lab_cls == null ? null : p.lab_cls.toFixed(3),
        inp_s: tbt == null ? null : tbt / 1000, inp_rating: rateTbt(tbt), inp_raw: fmtMs(tbt), inp_proxy: true,
      };
    }
    return { source: null };
  };
  const HAS_CWV = DATA.some((r) => pageCwv(r).source);

  // A pill for a single CWV metric. All readings are synthetic, so pills carry a
  // dashed outline; an optional proxy marker flags the TBT-as-INP reading.
  const cwvPill = (raw, rating, proxy) => {
    if (raw == null || raw === '') return '<span class="pill">—</span>';
    const cls = `pill ${cwvClass(rating)} lab`;
    return `<span class="${cls}">${esc(raw)}${proxy ? '<span class="proxy-star">*</span>' : ''}</span>`;
  };

  // Google's CWV "good" thresholds + the lab/proxy caveats.
  const CWV_LEGEND =
    'CWV green/amber/red use Google thresholds (LCP ≤ 2.5s, CLS ≤ 0.1, INP ≤ 200ms). ' +
    'They are <b>lab (synthetic, single-load)</b> measurements (dashed pills) taken during the page visit; ' +
    'INP is a <b>TBT proxy</b> (marked <b>*</b>) since true INP needs real users. ' +
    'These are page-level confounders to watch alongside image quality.';

  // --- scoring transparency -------------------------------------------------
  // The score breakdown is the single source of truth emitted by Stage 03 on
  // each row (row.score_breakdown = [{ key, label, group, max, earned, detail }]),
  // so the dashboard renders whatever rubric the run used (brief or google)
  // without re-implementing the scoring math. A small brief fallback keeps data
  // files produced before score_breakdown existed renderable.
  const wc = (s) => (s ? String(s).trim().split(/\s+/).filter(Boolean).length : 0);
  const SCORING_MODE = (DATA.find((r) => r && r.scoring_mode) || {}).scoring_mode || 'google';
  const SCORE_MAX = 100;
  const MODE_LABEL = SCORING_MODE === 'google' ? 'Google SEO' : 'Original';
  // Legacy brief rubric — ONLY used when a row has no emitted score_breakdown.
  const FALLBACK_RULES = [
    { key: 'alt_present', label: 'Alt text present', group: 'Alt text', max: 30, met: (r) => r.alt_text_present === true, detail: (r) => `alt_text_present = ${r.alt_text_present === true}` },
    { key: 'alt_len', label: 'Alt text ≥ 10 words', group: 'Alt text', max: 20, met: (r) => (r.alt_text_word_count ?? 0) >= 10, detail: (r) => `alt_text_word_count = ${r.alt_text_word_count ?? 0} (need ≥ 10)` },
    { key: 'caption', label: 'Caption present', group: 'Caption', max: 20, met: (r) => !!(r.caption_text && String(r.caption_text).trim() !== ''), detail: (r) => (r.caption_text && String(r.caption_text).trim() ? 'figcaption present' : 'no <figcaption> found') },
    { key: 'para', label: 'Surrounding text ≥ 50 words', group: 'Surrounding text', max: 15, met: (r) => wc(r.surrounding_paragraph) >= 50, detail: (r) => `surrounding paragraph = ${wc(r.surrounding_paragraph)} words (need ≥ 50)` },
    { key: 'schema', label: 'In ImageObject schema', group: 'Schema match', max: 15, met: (r) => r.schema_image_match === true, detail: (r) => (r.schema_image_match === true ? 'URL found in JSON-LD ImageObject' : 'URL not in any ImageObject schema') },
  ];
  // Per-image parts: prefer the emitted breakdown; else compute the brief fallback.
  const scoreParts = (row) => {
    if (row && Array.isArray(row.score_breakdown)) {
      return row.score_breakdown.map((p) => ({ key: p.key, label: p.label, group: p.group, max: p.max, earned: p.earned, detail: p.detail }));
    }
    return FALLBACK_RULES.map((rule) => ({ key: rule.key, label: rule.label, group: rule.group, max: rule.max, earned: rule.met(row) ? rule.max : 0, detail: rule.detail(row) }));
  };
  const scoreTotal = (row) => {
    if (row && typeof row.metadata_completeness_score === 'number') return row.metadata_completeness_score;
    return scoreParts(row).reduce((a, p) => a + p.earned, 0);
  };
  // Compact header label + criterion description per rule key, so the Overview
  // expanded subtable can use short column headers (less horizontal scroll) with
  // a hover tooltip explaining what each column checks. Keyed by the stable rule
  // `key` so it covers both the brief and google rubrics; anything unmapped
  // falls back to the full label.
  const SHORT_LABELS = {
    alt_present: { short: 'Alt', desc: 'Image has a non-empty alt attribute' },
    alt_len: { short: 'Alt ≥10w', desc: 'Alt text is at least 10 words' },
    alt_descriptive: { short: 'Alt quality', desc: 'Alt text is descriptive, not keyword-stuffed' },
    img_title: { short: 'Title', desc: 'Image has a title attribute' },
    filename_descriptive: { short: 'Filename', desc: 'Descriptive filename (not a generic/resizer name like width3840.jpg)' },
    caption: { short: 'Caption', desc: 'Image has a <figcaption>' },
    para: { short: 'Context', desc: 'At least 50 words of surrounding text' },
    surrounding: { short: 'Context', desc: 'Relevant text near the image' },
    schema: { short: 'Schema', desc: 'Image URL appears in a JSON-LD ImageObject' },
    page_meta_desc: { short: 'Meta desc', desc: 'Page has a meta description' },
    structured_data: { short: 'Structured data', desc: 'Page has JSON-LD structured data' },
    responsive: { short: 'Responsive', desc: 'Uses srcset / <picture> for responsive delivery' },
    format: { short: 'Format', desc: 'Modern/supported format (WebP, AVIF, JPEG, PNG, GIF)' },
    resolution: { short: 'High res', desc: 'At least 1200px wide' },
    dimensions_attr: { short: 'Dimensions', desc: 'width & height attributes set (avoids layout shift)' },
  };
  // Column + group metadata derived from the rubric actually present in the data
  // (first scored row's breakdown), falling back to the brief rules.
  const SCORE_RULES = (() => {
    const r = DATA.find((x) => x && Array.isArray(x.score_breakdown) && x.score_breakdown.length);
    const parts = r ? r.score_breakdown : FALLBACK_RULES;
    return parts.map((p) => {
      const s = SHORT_LABELS[p.key] || {};
      return { key: p.key, label: p.label, short: s.short || p.label, desc: s.desc || p.label, group: p.group, max: p.max };
    });
  })();
  const SCORE_GROUPS = (() => {
    const order = [];
    const byLabel = new Map();
    for (const p of SCORE_RULES) {
      const g = p.group || p.label;
      if (!byLabel.has(g)) { byLabel.set(g, { label: g, keys: [], max: 0 }); order.push(g); }
      const e = byLabel.get(g);
      e.keys.push(p.key);
      e.max += p.max;
    }
    return order.map((g) => byLabel.get(g));
  })();

  // Tooltip HTML: a single image's atomic breakdown (used by Image Frequency
  // single-appearance and as the base building block).
  const partsTipHTML = (row, heading) => {
    const head = heading ? `<div class="tip-h">${esc(heading)}</div>` : '';
    const rows = scoreParts(row)
      .map(
        (p) =>
          `<tr class="${p.earned ? 'met' : 'miss'}"><td>${esc(p.label)}</td>` +
          `<td class="num">${p.earned}/${p.max}</td><td class="dim">${esc(p.detail || '')}</td></tr>`,
      )
      .join('');
    return `${head}<table class="tiptab"><tbody>${rows}</tbody><tfoot><tr><td>Total</td>` +
      `<td class="num">${scoreTotal(row)}/${SCORE_MAX}</td><td></td></tr></tfoot></table>`;
  };
  // Tooltip HTML: per-condition AVERAGE across a set of image rows (used by the
  // Overview page-average pill and Image Frequency multi-appearance images).
  // Aligns parts by key so it works for any rubric.
  const avgPartsTipHTML = (rows, heading) => {
    const scored = rows.filter((r) => r && r.image_url && !r.page_only);
    if (!scored.length) return `<div class="tip-h">${esc(heading || '')}</div><div class="dim">No scored images.</div>`;
    const head = heading ? `<div class="tip-h">${esc(heading)} · ${scored.length} image${scored.length === 1 ? '' : 's'}</div>` : '';
    const order = [];
    const acc = new Map();
    for (const r of scored) {
      for (const p of scoreParts(r)) {
        if (!acc.has(p.key)) { acc.set(p.key, { label: p.label, max: p.max, sum: 0, met: 0 }); order.push(p.key); }
        const e = acc.get(p.key);
        e.sum += p.earned;
        if (p.earned > 0) e.met += 1;
      }
    }
    let totalAvg = 0;
    const body = order.map((k) => {
      const e = acc.get(k);
      const avg = e.sum / scored.length;
      totalAvg += avg;
      const pctMet = Math.round((e.met / scored.length) * 100);
      return `<tr><td>${esc(e.label)}</td><td class="num">${avg.toFixed(1)}/${e.max}</td><td class="dim">${pctMet}% of images</td></tr>`;
    }).join('');
    return `${head}<table class="tiptab"><tbody>${body}</tbody><tfoot><tr><td>Avg total</td>` +
      `<td class="num">${totalAvg.toFixed(1)}/${SCORE_MAX}</td><td></td></tr></tfoot></table>`;
  };

  // CSP-safe floating tooltip: a single body-appended div positioned with
  // clientX/clientY (position:fixed) so it is never clipped by the table's
  // overflow:hidden. Bound via addEventListener (inline handlers are blocked by
  // the extension's `script-src 'self'` CSP).
  let tipEl;
  const ensureTip = () => {
    if (!tipEl) {
      tipEl = document.createElement('div');
      tipEl.className = 'tip';
      tipEl.style.display = 'none';
      document.body.appendChild(tipEl);
    }
    return tipEl;
  };
  const positionTip = (e) => {
    const t = ensureTip();
    const pad = 14;
    let x = e.clientX + pad;
    let y = e.clientY + pad;
    const r = t.getBoundingClientRect();
    if (x + r.width > window.innerWidth - 8) x = Math.max(8, e.clientX - r.width - pad);
    if (y + r.height > window.innerHeight - 8) y = Math.max(8, e.clientY - r.height - pad);
    t.style.left = x + 'px';
    t.style.top = y + 'px';
  };
  const bindTip = (el, html) => {
    if (!el || !html) return;
    el.classList.add('has-tip');
    el.addEventListener('mouseenter', (e) => {
      const t = ensureTip();
      t.innerHTML = html;
      t.style.display = 'block';
      positionTip(e);
    });
    el.addEventListener('mousemove', positionTip);
    el.addEventListener('mouseleave', () => {
      if (tipEl) tipEl.style.display = 'none';
    });
  };

  // Aggregate per page.
  const pageMap = new Map();
  for (const r of DATA) {
    if (!pageMap.has(r.page_url)) {
      pageMap.set(r.page_url, {
        page_url: r.page_url,
        citation_count: r.citation_count ?? 0,
        prompts_cited_in: r.prompts_cited_in ?? 0,
        metric_value: r[METRIC.field] ?? 0,
        citation_rank: r.citation_rank ?? null,
        citation_tier: r.citation_tier || 'low',
        ai_platforms: r.ai_platforms || [],
        query_category: r.query_category || '',
        citation_trend: r.citation_trend || '',
        page_title: r.page_title || '',
        page_has_preferred_image: r.page_has_preferred_image === true,
        image_count_total: r.image_count_total ?? 0,
        // Page-level lab CWV (same for every row of a page), from --lab-cwv.
        lab_cwv_measured: r.lab_cwv_measured === true,
        lab_lcp_s: r.lab_lcp_s ?? null,
        lab_cls: r.lab_cls ?? null,
        lab_tbt_ms: r.lab_tbt_ms ?? null,
        images: [],
      });
    }
    // page_only rows (cited pages with 0 qualifying images) create the page
    // entry but contribute no image to the gallery/averages.
    if (r.image_url && !r.page_only) pageMap.get(r.page_url).images.push(r);
  }
  const pages = [...pageMap.values()];
  for (const p of pages) {
    const scores = p.images.map((i) => i.metadata_completeness_score ?? 0);
    p.avg_score = scores.length ? scores.reduce((a, b) => a + b, 0) / scores.length : null;
    p.qualifying_images = p.images.length;
  }
  pages.sort((a, b) => b.metric_value - a.metric_value);

  // Aggregate per unique image URL (View 4).
  const imgMap = new Map();
  for (const r of DATA) {
    if (!r.image_url || r.page_only) continue;
    if (!imgMap.has(r.image_url)) {
      imgMap.set(r.image_url, { image_url: r.image_url, pages: new Set(), cites: [], scores: [], rows: [] });
    }
    const e = imgMap.get(r.image_url);
    e.pages.add(r.page_url);
    e.cites.push(r.citation_count ?? 0);
    e.scores.push(r.metadata_completeness_score ?? 0);
    e.rows.push(r); // kept so the hover can show this image's score breakdown
  }
  const images = [...imgMap.values()].map((e) => ({
    image_url: e.image_url,
    page_count: e.pages.size,
    avg_citation: e.cites.reduce((a, b) => a + b, 0) / e.cites.length,
    avg_score: e.scores.reduce((a, b) => a + b, 0) / e.scores.length,
    rows: e.rows,
  }));
  images.sort((a, b) => b.page_count - a.page_count || b.avg_citation - a.avg_citation);

  const tierBadge = (t) => `<span class="tier tier-${esc(t)}">${esc(t)}</span>`;
  const thumb = (url) => `<img class="thumb" loading="lazy" src="${esc(url)}" alt="">`;

  // Mark broken thumbnails. Image error events don't bubble, so capture them at
  // the document level — one listener covers every dynamically-inserted thumb
  // (inline onerror would be blocked by the extension's `script-src 'self'` CSP).
  document.addEventListener(
    'error',
    (e) => {
      const t = e.target;
      if (t && t.tagName === 'IMG' && t.classList.contains('thumb')) {
        t.classList.add('broken');
        t.removeAttribute('src');
      }
    },
    true,
  );

  const root = document.getElementById('app');

  function sortableTable(rows, cols, opts) {
    opts = opts || {};
    let sortKey = opts.sortKey || cols[0].key;
    let sortDir = opts.sortDir || -1;
    const wrap = document.createElement('div');
    function draw() {
      const sorted = [...rows].sort((a, b) => {
        const av = cols.find((c) => c.key === sortKey).val(a);
        const bv = cols.find((c) => c.key === sortKey).val(b);
        if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * sortDir;
        return String(av).localeCompare(String(bv)) * sortDir;
      });
      wrap.innerHTML =
        '<table><thead><tr>' +
        cols
          .map(
            (c) =>
              `<th data-k="${c.key}">${esc(c.label)}${sortKey === c.key ? (sortDir < 0 ? ' ▼' : ' ▲') : ''}</th>`,
          )
          .join('') +
        '</tr></thead><tbody>' +
        sorted.map((r) => '<tr>' + cols.map((c) => `<td>${c.cell ? c.cell(r) : esc(c.val(r))}</td>`).join('') + '</tr>').join('') +
        '</tbody></table>';
      wrap.querySelectorAll('th').forEach((th) =>
        th.addEventListener('click', () => {
          const k = th.dataset.k;
          if (k === sortKey) sortDir *= -1;
          else {
            sortKey = k;
            sortDir = -1;
          }
          draw();
        }),
      );
      const bodyRows = [...wrap.querySelectorAll('tbody tr')];
      if (opts.onRowClick) {
        bodyRows.forEach((tr, i) => {
          tr.classList.add('clickable');
          tr.addEventListener('click', () => opts.onRowClick(sorted[i]));
        });
      }
      // Per-column hover tooltips and click-to-expand subrows. Both bind to the
      // cell's inner element (e.g. the score pill) so the affordance is scoped
      // to the value, not the whole cell.
      cols.forEach((c, ci) => {
        if (!c.tip && !c.expand) return;
        bodyRows.forEach((tr, i) => {
          const rowObj = sorted[i];
          const td = tr.children[ci];
          if (!td) return;
          const cellEl = td.firstElementChild || td;
          if (c.tip) bindTip(cellEl, c.tip(rowObj));
          if (c.expand) {
            cellEl.classList.add('score-cell');
            cellEl.addEventListener('click', (e) => {
              // Don't let the expand toggle also trigger the row's onRowClick.
              e.stopPropagation();
              const next = tr.nextElementSibling;
              if (next && next.classList.contains('subrow')) {
                next.remove();
                cellEl.classList.remove('open');
                return;
              }
              const sub = document.createElement('tr');
              sub.className = 'subrow';
              sub.innerHTML = `<td colspan="${cols.length}">${c.expand(rowObj)}</td>`;
              tr.after(sub);
              cellEl.classList.add('open');
              // Wire hover tooltips on any compact subtable headers (short label
              // shown, full label + criterion + max revealed on hover).
              sub.querySelectorAll('th[data-desc]').forEach((th) => {
                const full = th.getAttribute('data-full') || '';
                const desc = th.getAttribute('data-desc') || '';
                const mx = th.getAttribute('data-max') || '';
                bindTip(th, `<div class="tip-h">${esc(full)} · /${esc(mx)}</div>${esc(desc)}`);
              });
            });
          }
        });
      });
    }
    draw();
    return wrap;
  }

  function viewOverview() {
    const c = document.createElement('div');
    const cols = [
      { key: 'rank', label: 'Rank', val: (p) => p.citation_rank ?? 9999 },
      { key: 'url', label: 'Page', val: (p) => p.page_url, cell: (p) => `<a href="${esc(p.page_url)}" target="_blank" rel="noopener">${esc(p.page_title || p.page_url)}</a>` },
      { key: 'cites', label: METRIC.label, val: (p) => p.metric_value },
      { key: 'tier', label: 'Tier', val: (p) => p.citation_tier, cell: (p) => tierBadge(p.citation_tier) },
      { key: 'platforms', label: 'AI platforms', val: (p) => (p.ai_platforms || []).join(', ') },
      { key: 'cat', label: 'Category', val: (p) => p.query_category },
      { key: 'trend', label: 'Trend', val: (p) => p.citation_trend },
    ];
    if (SCORING_MODE === 'google') {
      cols.push({
        key: 'pref', label: 'Preferred Img', val: (p) => p.page_has_preferred_image ? 1 : 0, cell: (p) => p.page_has_preferred_image ? '<span style="color:#0b8043;font-weight:bold;">Yes</span>' : '<span class="dim">No</span>'
      });
    }
    cols.push({ key: 'imgs', label: 'Images', val: (p) => p.qualifying_images });
    if (HAS_CWV) {
      // Lab Core Web Vitals (sortable by the numeric value; colored by rating).
      // Lower is better for all three, so an ascending sort surfaces the worst.
      // INP* is a TBT proxy (lab readings only).
      cols.push({ key: 'lcp', label: 'LCP', val: (p) => { const v = pageCwv(p).lcp_s; return v == null ? Infinity : v; }, cell: (p) => { const c = pageCwv(p); return cwvPill(c.lcp_raw, c.lcp_rating, false); } });
      cols.push({ key: 'cls', label: 'CLS', val: (p) => { const v = pageCwv(p).cls; return v == null ? Infinity : v; }, cell: (p) => { const c = pageCwv(p); return cwvPill(c.cls_raw, c.cls_rating, false); } });
      cols.push({ key: 'inp', label: 'INP', val: (p) => { const v = pageCwv(p).inp_s; return v == null ? Infinity : v; }, cell: (p) => { const c = pageCwv(p); return cwvPill(c.inp_raw, c.inp_rating, c.inp_proxy); } });
    }
    cols.push({
      key: 'score',
      label: `Avg metadata (${MODE_LABEL})`,
      val: (p) => p.avg_score,
      cell: (p) =>
        p.avg_score == null
          ? '<span class="pill">—</span>'
          : `<span class="pill ${scoreClass(p.avg_score)}">${fmtScore(p.avg_score)}</span>`,
      tip: (p) => (p.images.length ? avgPartsTipHTML(p.images, 'Page metadata average') : ''),
      expand: (p) => (p.images.length ? pageImagesSubtable(p) : '<div class="dim">No qualifying images on this page.</div>'),
    });

    c.appendChild(
      sortableTable(pages, cols, { sortKey: 'cites', sortDir: -1, onRowClick: (p) => showPage(p) })
    );
    const hint = document.createElement('p');
    hint.className = 'hint';
    hint.innerHTML =
      `Scoring rubric: <b>${esc(MODE_LABEL)}</b> (0–${SCORE_MAX}). Click any row to inspect that page’s images. Hover the <b>Avg metadata</b> score for a per-condition average, or click the score to expand every image’s breakdown.` +
      (HAS_CWV ? ` ${CWV_LEGEND}` : '');
    c.prepend(hint);
    return c;
  }

  // Full-width subtable injected under an Overview row: one line per image with
  // its total and each scoring condition's earned/max.
  function pageImagesSubtable(p) {
    const head =
      '<tr><th>Image</th><th>File</th><th class="num">Total</th>' +
      SCORE_RULES.map(
        (r) =>
          `<th class="num has-tip" data-full="${esc(r.label)}" data-desc="${esc(r.desc)}" data-max="${r.max}">` +
          `${esc(r.short)}<span class="dim"> /${r.max}</span></th>`,
      ).join('') +
      '</tr>';
    const body = p.images
      .map((im) => {
        const parts = scoreParts(im);
        const total = parts.reduce((a, x) => a + x.earned, 0);
        const fname = (im.image_filename || im.image_url || '').toString();
        return (
          '<tr>' +
          `<td>${thumb(im.image_url)}</td>` +
          `<td class="trunc">${esc(fname)}</td>` +
          `<td class="num"><span class="pill ${scoreClass(total)}">${total}</span></td>` +
          parts.map((x) => `<td class="num ${x.earned ? 'met' : 'miss'}">${x.earned}/${x.max}</td>`).join('') +
          '</tr>'
        );
      })
      .join('');
    return `<table class="subtable"><thead>${head}</thead><tbody>${body}</tbody></table>`;
  }

  function showPage(p) {
    setActive('detail');
    const host = document.getElementById('view-detail');
    host.innerHTML =
      `<button class="back" data-back="overview">← Back to overview</button>` +
      `<h2>${esc(p.page_title || p.page_url)}</h2>` +
      `<p class="hint">${esc(p.page_url)} · ${p.metric_value} ${METRIC.noun} · ${tierBadge(p.citation_tier)}</p>` +
      (() => {
        const c = pageCwv(p);
        if (!c.source) return '';
        const cwv =
          `<span class="cwv-badge src src-lab">Lab (synthetic)</span>` +
          `<span class="cwv-badge ${cwvClass(c.lcp_rating)}">LCP <b>${esc(c.lcp_raw || '—')}</b></span>` +
          `<span class="cwv-badge ${cwvClass(c.cls_rating)}">CLS <b>${esc(c.cls_raw || '—')}</b></span>` +
          `<span class="cwv-badge ${cwvClass(c.inp_rating)}">INP${c.inp_proxy ? '*' : ''} <b>${esc(c.inp_raw || '—')}</b></span>`;
        return (
          '<div class="cwv-row">' +
          cwv +
          (c.inp_proxy ? '<span class="cwv-note">*INP is a TBT lab proxy</span>' : '') +
          '</div>'
        );
      })() +
      (p.images.length === 0
        ? '<p class="hint">No qualifying images (≥ 50×50 px) were found on this cited page.</p>'
        : '') +
      '<div class="gallery">' +
      p.images
        .map((im, idx) => {
          const parts = scoreParts(im);
          const total = parts.reduce((a, x) => a + x.earned, 0);
          const partByKey = new Map(parts.map((x) => [x.key, x]));
          const alt = im.alt_text_present
            ? `<div class="alt">${esc(im.alt_text)}</div>`
            : '<div class="alt missing">MISSING ALT</div>';
          // Grouped itemized breakdown; groups + weights come from the active rubric.
          const groupRows = SCORE_GROUPS.map((g) => {
            const earned = g.keys.reduce((a, k) => a + (partByKey.get(k)?.earned || 0), 0);
            const gmax = g.max;
            const cls = earned === gmax ? 'met' : earned === 0 ? 'miss' : 'partial';
            return (
              `<div class="bd-item ${cls}" data-i="${idx}" data-g="${esc(g.label)}">` +
              `<span class="bd-l">${esc(g.label)}</span><span class="bd-n">${earned}/${gmax}</span></div>`
            );
          }).join('');
          const prefTag = SCORING_MODE === 'google' && im.preferred_image_match ? '<span class="info" style="background:#dcfce7;color:#0b8043;margin-left:4px;" title="Preferred Image (og:image / schema)">★ PREFERRED</span>' : '';
          return (
            '<div class="card">' +
            thumb(im.image_url) +
            alt +
            `<div class="meta"><span class="info info-${esc(im.image_informational_score)}">${esc(im.image_informational_score || '')}</span>${prefTag}` +
            `<span class="pos">${esc(im.page_position || '')}</span></div>` +
            `<div class="scorebar"><div class="fill ${scoreClass(total)}" style="width:${total}%"></div></div>` +
            `<div class="bd"><div class="bd-total">Metadata <b>${total}</b>/${SCORE_MAX}</div>${groupRows}</div>` +
            (im.surrounding_h2 ? `<div class="ctx"><b>H2:</b> ${esc(im.surrounding_h2)}</div>` : '') +
            (im.surrounding_paragraph ? `<div class="ctx">${esc(im.surrounding_paragraph)}</div>` : '') +
            '</div>'
          );
        })
        .join('') +
      '</div>';
    // Back button + per-line breakdown tooltips wired here (inline handlers are
    // blocked by the extension CSP, so everything binds via addEventListener).
    const back = host.querySelector('[data-back]');
    if (back) back.addEventListener('click', () => document.querySelector('[data-tab=overview]').click());
    host.querySelectorAll('.bd-item').forEach((el) => {
      const im = p.images[+el.dataset.i];
      const g = SCORE_GROUPS.find((gg) => gg.label === el.dataset.g);
      if (!im || !g) return;
      const partByKey = new Map(scoreParts(im).map((x) => [x.key, x]));
      const rowsH = g.keys
        .map((k) => {
          const part = partByKey.get(k);
          if (!part) return '';
          return (
            `<tr class="${part.earned ? 'met' : 'miss'}"><td>${esc(part.label)}</td>` +
            `<td class="num">${part.earned}/${part.max}</td><td class="dim">${esc(part.detail || '')}</td></tr>`
          );
        })
        .join('');
      bindTip(el, `<div class="tip-h">${esc(g.label)}</div><table class="tiptab"><tbody>${rowsH}</tbody></table>`);
    });
  }

  function viewTiers() {
    const tiers = ['high', 'mid', 'low'];
    const agg = {};
    for (const t of tiers) agg[t] = { score: [], alt: 0, n: 0, info: 0, dec: 0, stock: 0, imgsByPage: new Map() };
    for (const r of DATA) {
      if (!r.image_url || r.page_only) continue; // tier stats are image-level
      const t = r.citation_tier || 'low';
      const a = agg[t];
      if (!a) continue;
      a.score.push(r.metadata_completeness_score ?? 0);
      a.n++;
      if (r.alt_text_present) a.alt++;
      if (r.image_informational_score === 'informative') a.info++;
      else if (r.image_informational_score === 'stock') a.stock++;
      else a.dec++;
      a.imgsByPage.set(r.page_url, (a.imgsByPage.get(r.page_url) || 0) + 1);
    }
    const avg = (arr) => (arr.length ? arr.reduce((x, y) => x + y, 0) / arr.length : 0);
    const pct = (num, den) => (den ? (100 * num) / den : 0);
    function metric(label, fn, unit) {
      const rows = tiers
        .map((t) => {
          const a = agg[t];
          const v = fn(a);
          return `<div class="brow"><span class="blabel">${t}</span><div class="btrack"><div class="bfill tier-${t}" style="width:${Math.min(100, v)}%"></div></div><span class="bval">${v.toFixed(unit === '%' ? 0 : 1)}${unit || ''}</span></div>`;
        })
        .join('');
      return `<div class="metric"><h3>${esc(label)}</h3>${rows}</div>`;
    }
    const c = document.createElement('div');
    c.innerHTML =
      '<p class="hint">Comparative view across citation tiers — the core question of the experiment.</p>' +
      metric(`Avg metadata completeness — ${MODE_LABEL} (0–${SCORE_MAX})`, (a) => avg(a.score)) +
      metric('% images with alt text', (a) => pct(a.alt, a.n), '%') +
      metric('% informative images', (a) => pct(a.info, a.n), '%') +
      metric('% stock images', (a) => pct(a.stock, a.n), '%') +
      metric('Avg images per page', (a) => avg([...a.imgsByPage.values()]));
    return c;
  }

  function viewFrequency() {
    const c = document.createElement('div');
    c.appendChild(
      sortableTable(images, [
        { key: 'thumb', label: 'Image', val: (i) => i.image_url, cell: (i) => thumb(i.image_url) },
        { key: 'url', label: 'URL', val: (i) => i.image_url, cell: (i) => `<a href="${esc(i.image_url)}" target="_blank" rel="noopener" class="trunc">${esc(i.image_url)}</a>` },
        { key: 'pages', label: 'Pages', val: (i) => i.page_count },
        { key: 'cite', label: 'Avg citations', val: (i) => i.avg_citation, cell: (i) => i.avg_citation.toFixed(1) },
        {
          key: 'score',
          label: `Image metadata score (${MODE_LABEL})`,
          val: (i) => i.avg_score,
          cell: (i) => `<span class="pill ${scoreClass(i.avg_score)}">${fmtScore(i.avg_score)}</span>`,
          tip: (i) =>
            i.page_count > 1
              ? avgPartsTipHTML(i.rows, `Averaged over ${i.page_count} pages`)
              : partsTipHTML(i.rows[0], 'Image metadata breakdown'),
        },
      ]),
    );
    const hint = document.createElement('p');
    hint.className = 'hint';
    hint.innerHTML = `Each row is a unique image. <b>Image metadata score</b> is that image’s own ${esc(MODE_LABEL)} completeness score (averaged across the pages it appears on). Hover it for the per-condition breakdown.`;
    c.prepend(hint);
    return c;
  }

  const views = {
    overview: viewOverview,
    tiers: viewTiers,
    frequency: viewFrequency,
  };
  const built = {};

  function setActive(tab) {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === tab));
    // Use an explicit 'block' for the active view. Setting display = '' would
    // only clear the inline style and let the stylesheet's `.view{display:none}`
    // win, leaving every view hidden.
    document.querySelectorAll('.view').forEach((v) => (v.style.display = v.id === 'view-' + tab ? 'block' : 'none'));
  }

  function mountTab(tab) {
    const host = document.getElementById('view-' + tab);
    if (!built[tab] && views[tab]) {
      host.appendChild(views[tab]());
      built[tab] = true;
    }
    setActive(tab);
  }

  document.querySelectorAll('.tab').forEach((t) =>
    t.addEventListener('click', () => mountTab(t.dataset.tab)),
  );
  mountTab('overview');
  void root;
}

const clientSrc = '(' + clientMain.toString() + ')();';
const dataJson = JSON.stringify(data).replace(/</g, '\\u003c');

// Sibling filenames derived from the output HTML name, written next to it.
// Referenced by RELATIVE basename so the same paths resolve over the preview
// service worker (CLI + extension) and over file://.
const slash = OUTPUT_FILE.lastIndexOf('/');
const outDir = slash >= 0 ? OUTPUT_FILE.slice(0, slash + 1) : '';
const outName = slash >= 0 ? OUTPUT_FILE.slice(slash + 1) : OUTPUT_FILE;
const outBase = outName.replace(/\.html?$/i, '') || 'dashboard';
const dataFileName = `${outBase}.data.js`;
const appFileName = `${outBase}.app.js`;

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI Citation Image Performance — Dashboard</title>
<style>
  :root { --navy:#1B3A5C; --ink:#222; --muted:#6b7280; --line:#e5e7eb; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif; color:var(--ink); background:#f6f8fa; }
  header { background:var(--navy); color:#fff; padding:18px 24px; }
  header h1 { margin:0 0 4px; font-size:18px; }
  header .summary { font-size:12px; opacity:.85; }
  .mode-badge { display:inline-block; padding:1px 8px; border-radius:10px; font-weight:600; opacity:1;
                background:rgba(255,255,255,.18); }
  .mode-badge.mode-google { background:#0b8043; }
  nav { display:flex; gap:4px; padding:0 24px; background:#fff; border-bottom:1px solid var(--line); position:sticky; top:0; z-index:5; }
  .tab { padding:12px 16px; cursor:pointer; border:none; background:none; font:inherit; color:var(--muted); border-bottom:2px solid transparent; }
  .tab.active { color:var(--navy); border-bottom-color:var(--navy); font-weight:600; }
  main { padding:24px 28px; max-width:none; margin:0; }
  .view { display:none; }
  table { width:100%; border-collapse:collapse; background:#fff; border:1px solid var(--line); border-radius:8px; overflow:hidden; }
  th,td { text-align:left; padding:8px 12px; border-bottom:1px solid var(--line); font-size:13px; vertical-align:middle; }
  th { background:#f0f4f8; cursor:pointer; user-select:none; white-space:nowrap; }
  tbody tr.clickable:hover { background:#eef5ff; cursor:pointer; }
  a { color:#1a5fb4; text-decoration:none; } a:hover { text-decoration:underline; }
  .trunc { display:inline-block; max-width:360px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; vertical-align:bottom; }
  .hint { color:var(--muted); font-size:12px; margin:0 0 12px; }
  .tier { display:inline-block; padding:1px 8px; border-radius:10px; font-size:11px; font-weight:600; color:#fff; }
  .tier-high { background:#2e7d32; } .tier-mid { background:#f59e0b; } .tier-low { background:#9ca3af; }
  .pill { display:inline-block; min-width:34px; text-align:center; padding:1px 8px; border-radius:10px; font-weight:600; color:#fff; }
  .pill.red,.fill.red,.bfill.red { background:#dc2626; } .pill.amber,.fill.amber { background:#f59e0b; } .pill.green,.fill.green { background:#2e7d32; }
  .gallery { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:16px; }
  .card { background:#fff; border:1px solid var(--line); border-radius:8px; padding:10px; }
  .thumb { width:100%; height:140px; object-fit:cover; border-radius:6px; background:#eef0f2; display:block; }
  .thumb.broken { display:flex; }
  td .thumb { width:72px; height:48px; }
  .alt { font-size:12px; margin:8px 0 4px; }
  .alt.missing { color:#dc2626; font-weight:600; }
  .meta { display:flex; justify-content:space-between; align-items:center; margin:4px 0; }
  .info { font-size:10px; font-weight:600; padding:1px 6px; border-radius:8px; text-transform:uppercase; }
  .info-informative { background:#dcfce7; color:#166534; } .info-decorative { background:#f3f4f6; color:#6b7280; } .info-stock { background:#fee2e2; color:#991b1b; }
  .pos { font-size:11px; color:var(--muted); }
  .scorebar { height:6px; background:#eef0f2; border-radius:4px; overflow:hidden; }
  .scorebar .fill { height:100%; }
  .scoreval { font-size:11px; color:var(--muted); margin-top:2px; }
  .ctx { font-size:11px; color:#374151; margin-top:6px; } .ctx.schema { color:#166534; }
  .metric { background:#fff; border:1px solid var(--line); border-radius:8px; padding:14px 18px; margin-bottom:14px; }
  .metric h3 { margin:0 0 10px; font-size:13px; }
  .brow { display:flex; align-items:center; gap:10px; margin:6px 0; }
  .blabel { width:42px; font-size:12px; color:var(--muted); text-transform:capitalize; }
  .btrack { flex:1; height:14px; background:#eef0f2; border-radius:7px; overflow:hidden; }
  .bfill { height:100%; } .bfill.tier-high{background:#2e7d32;} .bfill.tier-mid{background:#f59e0b;} .bfill.tier-low{background:#9ca3af;}
  .bval { width:60px; text-align:right; font-size:12px; font-variant-numeric:tabular-nums; }
  .back { background:none; border:none; color:#1a5fb4; cursor:pointer; padding:0; font:inherit; margin-bottom:8px; }
  /* shared numeric / dim cells */
  td.num, th.num { text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }
  .dim { color:var(--muted); }
  .met { color:#166534; } .miss { color:#b91c1c; } .partial { color:#b45309; }
  /* floating tooltip (body-appended, never clipped) */
  .tip { position:fixed; z-index:50; max-width:340px; background:#111827; color:#fff; padding:8px 10px;
         border-radius:6px; font-size:11px; line-height:1.45; box-shadow:0 6px 20px rgba(0,0,0,.28); pointer-events:none; }
  .tip .tip-h { font-weight:600; margin-bottom:4px; }
  .tip .tiptab { width:100%; border:none; background:none; border-radius:0; }
  .tip .tiptab td { border:none; padding:1px 6px 1px 0; font-size:11px; color:#e5e7eb; vertical-align:top; }
  .tip .tiptab td.num { color:#fff; }
  .tip .tiptab td.dim { color:#9ca3af; }
  .tip .tiptab tr.met td:first-child::before { content:'✓ '; color:#4ade80; }
  .tip .tiptab tr.miss td:first-child::before { content:'✗ '; color:#f87171; }
  .tip .tiptab tfoot td { border-top:1px solid #374151; font-weight:600; padding-top:3px; }
  .has-tip { cursor:help; }
  /* expandable Overview score pill */
  .score-cell { cursor:pointer; }
  .score-cell::after { content:' ▾'; font-size:9px; opacity:.65; }
  .score-cell.open::after { content:' ▴'; }
  /* expanded per-image subtable under an Overview row */
  tr.subrow > td { background:#f8fafc; padding:0; }
  .subtable { border:none; border-radius:0; }
  .subtable th, .subtable td { font-size:12px; padding:6px 10px; border-bottom:1px solid var(--line); }
  .subtable th { background:#eef2f7; }
  .subtable td.met { color:#166534; } .subtable td.miss { color:#9ca3af; }
  .subtable td .thumb { width:64px; height:40px; }
  /* Page Detail itemized breakdown */
  .bd { margin:6px 0 2px; }
  .bd-total { font-size:12px; margin-bottom:4px; }
  .bd-item { display:flex; justify-content:space-between; align-items:center; font-size:11px; padding:2px 4px;
             border-radius:4px; cursor:help; }
  .bd-item + .bd-item { margin-top:1px; }
  .bd-item.met { background:#f0fdf4; } .bd-item.miss { background:#fef2f2; } .bd-item.partial { background:#fffbeb; }
  .bd-item .bd-n { font-variant-numeric:tabular-nums; font-weight:600; }
  /* Page Detail Core Web Vitals badges */
  .cwv-row { display:flex; flex-wrap:wrap; gap:8px; margin:0 0 12px; }
  .cwv-badge { font-size:11px; padding:2px 10px; border-radius:10px; background:#f3f4f6; color:#374151; border:1px solid var(--line); }
  .cwv-badge b { font-variant-numeric:tabular-nums; }
  .cwv-badge.green { background:#dcfce7; color:#166534; border-color:#bbf7d0; }
  .cwv-badge.amber { background:#fef3c7; color:#92400e; border-color:#fde68a; }
  .cwv-badge.red { background:#fee2e2; color:#991b1b; border-color:#fecaca; }
  .cwv-badge.src { font-weight:600; }
  .cwv-badge.src.src-lab { background:#f5f3ff; color:#5b21b6; border-color:#ddd6fe; }
  .cwv-note { font-size:10px; color:var(--muted); align-self:center; }
  /* lab CWV pills: dashed outline so synthetic readings are obvious in tables */
  .pill.lab { border:1px dashed rgba(0,0,0,.35); }
  .proxy-star { font-weight:700; }
</style>
</head>
<body>
<header>
  <h1>AI Citation Image Performance</h1>
  <div class="summary">${pageCount} pages · ${imageCount} images · <span class="mode-badge mode-${scoringMode}">Scoring: ${scoringLabel}</span> · generated ${generatedAt}</div>
</header>
<nav>
  <button class="tab" data-tab="overview">Citation overview</button>
  <button class="tab" data-tab="detail">Page detail</button>
  <button class="tab" data-tab="tiers">Quality by tier</button>
  <button class="tab" data-tab="frequency">Image frequency</button>
</nav>
<main id="app">
  <section class="view" id="view-overview"></section>
  <section class="view" id="view-detail"><p class="hint">Select a page from the Citation overview tab.</p></section>
  <section class="view" id="view-tiers"></section>
  <section class="view" id="view-frequency"></section>
</main>
<script src="${dataFileName}"></script>
<script src="${appFileName}"></script>
</body>
</html>`;

// window.__DATA__ in its own file so the renderer (which reads it) loads after.
await fs.writeFile(`${outDir}${dataFileName}`, `window.__DATA__ = ${dataJson};\n`);
await fs.writeFile(`${outDir}${appFileName}`, `${clientSrc}\n`);
await fs.writeFile(OUTPUT_FILE, html);
console.log(`Wrote dashboard (${pageCount} pages, ${imageCount} images) -> ${OUTPUT_FILE}`);
console.log(`  + ${outDir}${dataFileName}, ${outDir}${appFileName}`);
console.log(`Open it in the browser with:  open ${OUTPUT_FILE}`);
