// score-and-enrich — Stage 03 of the citation-image-analysis pipeline.
//
// Reads the raw page/image extraction (output.json), computes three derived
// fields per image row, and writes a dashboard-ready dataset.
//
// Deterministic: no LLM, no browser, no network. Safe to re-run; given the same
// input it always produces the same output. This is why scoring lives in a .jsh
// rather than in prose the agent re-interprets each run.
//
// Usage:
//   score-and-enrich [input.json] [output.json] [--scoring=google|original]
//   defaults: input = output.json, output = dashboard-data.json, scoring = google
//
//   --scoring=google    (default) a rubric aligned to Google's image SEO best
//                       practices (https://developers.google.com/search/docs/appearance/google-images),
//                       which Google states also feed AI/Search visual surfaces.
//   --scoring=original  the Phase 1 brief's original 5-signal metadata rubric
//                       ('brief' is still accepted as an alias for this).
//
// Either rubric scores from the SAME Stage 02 output.json — extract-page.js
// always captures the signals both need — so you can compare by re-scoring one
// crawl two ways (run the pipeline into two --dir folders). The chosen rubric is
// stamped on every row (scoring_mode) along with a per-image score_breakdown so
// the dashboard renders the exact rubric a run used.

const rawArgs = process.argv.slice(2);
let SCORING = 'google';
const args = [];
for (const a of rawArgs) {
  if (/^--scoring=/.test(a)) SCORING = a.slice('--scoring='.length).replace(/^["']|["']$/g, '').toLowerCase();
  else args.push(a);
}
// 'brief' is the legacy name for the original rubric — accept it as an alias.
if (SCORING === 'brief') SCORING = 'original';
if (SCORING !== 'original' && SCORING !== 'google') {
  console.error(`score-and-enrich: unknown --scoring=${SCORING} (use 'google' or 'original')`);
  process.exit(1);
}
const INPUT_FILE = args[0] || 'output.json';
const OUTPUT_FILE = args[1] || 'dashboard-data.json';

function wordCount(s) {
  if (!s || typeof s !== 'string') return 0;
  return s.trim().split(/\s+/).filter(Boolean).length;
}

// ---- helpers shared by the Google rubric ---------------------------------
function fileBaseName(fn) {
  return String(fn || '')
    .split('/')
    .pop()
    .replace(/\.[a-z0-9]+$/i, '');
}
// Generic / non-descriptive filenames Google warns against (IMG0023, image1,
// pic.gif, hashes, all-digits, single words like "logo"/"banner", or resizer patterns).
function isGenericFilename(fn) {
  const b = fileBaseName(fn).toLowerCase();
  if (!b || b.length < 4) return true;
  if (!/[a-z]{3,}/.test(b)) return true; // no real word at all
  return (
    /^(image|photo|img|pic|picture|untitled|screenshot|screen[-_]?shot|download|default|logo|banner|hero|thumbnail|thumb|placeholder|asset|width|height|size|scaled|resized)[-_ ]?\d*$/.test(b) ||
    /(image|photo|img|pic|width|height|size|scaled|resized)[-_]?\d+/.test(b) ||
    /istock|gettyimages|shutterstock/.test(b) ||
    /^[0-9a-f]{8,}$/.test(b) ||
    /^\d+$/.test(b)
  );
}
// Alt text that looks keyword-stuffed (low unique-word ratio, heavy repetition,
// or excessive length) — Google may treat this as spam, so it shouldn't earn
// the "descriptive" credit.
function isStuffedAlt(s) {
  const words = String(s || '')
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  if (words.length < 6) return false;
  if (words.length > 20) return true;
  const uniq = new Set(words);
  if (uniq.size / words.length < 0.5) return true;
  const counts = {};
  for (const w of words) {
    counts[w] = (counts[w] || 0) + 1;
    if (counts[w] >= 4) return true;
  }
  return false;
}
const LEGACY_FORMATS = new Set(['jpg', 'jpeg', 'png', 'gif', 'bmp']);
const NEXTGEN_FORMATS = new Set(['webp', 'svg', 'avif']);
function aspectOk(w, h) {
  if (!(w > 0) || !(h > 0)) return false;
  const r = w / h;
  return r >= 0.2 && r <= 5; // not extremely narrow / wide
}

// ---- rubric registry ------------------------------------------------------
// Each rule: { key, label, group, max, met(row), detail(row) }. The sum of
// earned points is metadata_completeness_score (0–100). The dashboard renders
// straight from the emitted breakdown, so labels/details live here only.
const BRIEF_RULES = [
  {
    key: 'alt_present',
    label: 'Alt text present',
    group: 'Alt text',
    max: 30,
    met: (r) => r.alt_text_present === true,
    detail: (r) => `alt_text_present = ${r.alt_text_present === true}`,
  },
  {
    key: 'alt_len',
    label: 'Alt text \u2265 10 words',
    group: 'Alt text',
    max: 20,
    met: (r) => (r.alt_text_word_count ?? 0) >= 10,
    detail: (r) => `alt_text_word_count = ${r.alt_text_word_count ?? 0} (need \u2265 10)`,
  },
  {
    key: 'caption',
    label: 'Caption present',
    group: 'Caption',
    max: 20,
    met: (r) => !!(r.caption_text && String(r.caption_text).trim() !== ''),
    detail: (r) => (r.caption_text && String(r.caption_text).trim() ? 'figcaption present' : 'no <figcaption> found'),
  },
  {
    key: 'para',
    label: 'Surrounding text \u2265 50 words',
    group: 'Surrounding text',
    max: 15,
    met: (r) => wordCount(r.surrounding_paragraph) >= 50,
    detail: (r) => `surrounding paragraph = ${wordCount(r.surrounding_paragraph)} words (need \u2265 50)`,
  },
  {
    key: 'schema',
    label: 'In ImageObject schema',
    group: 'Schema match',
    max: 15,
    met: (r) => r.schema_image_match === true,
    detail: (r) => (r.schema_image_match === true ? 'URL found in JSON-LD ImageObject' : 'URL not in any ImageObject schema'),
  },
];

const GOOGLE_RULES = [
  {
    key: 'alt_present',
    label: 'Alt text present',
    group: 'Alt text',
    max: 15,
    met: (r) => r.alt_text_present === true,
    detail: (r) => `alt_text_present = ${r.alt_text_present === true}`,
  },
  {
    key: 'alt_descriptive',
    label: 'Alt descriptive (not stuffed)',
    group: 'Alt text',
    max: 10,
    score: (r) => {
      const wc = r.alt_text_word_count ?? 0;
      if (isStuffedAlt(r.alt_text)) return 0;
      if (wc >= 4 && wc <= 16) return 10;
      if (wc > 0) return 5;
      return 0;
    },
    detail: (r) => {
      const wc = r.alt_text_word_count ?? 0;
      if (isStuffedAlt(r.alt_text)) return `looks keyword-stuffed (${wc} words)`;
      if (wc >= 4 && wc <= 16) return `alt_text_word_count = ${wc} (optimal 4\u201316)`;
      if (wc > 0) return `alt_text_word_count = ${wc} (partial credit)`;
      return `alt_text_word_count = ${wc} (need info-rich description)`;
    },
  },
  {
    key: 'img_title',
    label: 'Title attribute present',
    group: 'Alt text',
    max: 5,
    met: (r) => !!r.img_title,
    detail: (r) => (r.img_title ? `title attribute present: "${r.img_title}"` : 'no title attribute found'),
  },
  {
    key: 'filename_descriptive',
    label: 'Descriptive filename',
    group: 'Filename',
    max: 10,
    met: (r) => !isGenericFilename(r.image_filename),
    detail: (r) => (isGenericFilename(r.image_filename) ? `generic/resizer filename "${r.image_filename || ''}"` : `descriptive filename "${r.image_filename || ''}"`),
  },
  {
    key: 'caption',
    label: 'Caption present',
    group: 'Context',
    max: 8,
    met: (r) => !!(r.caption_text && String(r.caption_text).trim() !== ''),
    detail: (r) => (r.caption_text && String(r.caption_text).trim() ? 'figcaption present' : 'no <figcaption> found'),
  },
  {
    key: 'surrounding',
    label: 'Relevant nearby text',
    group: 'Context',
    max: 7,
    score: (r) => {
      const w = wordCount(r.surrounding_paragraph);
      if (w >= 50) return 7;
      if (w >= 25) return 4;
      if (w >= 10) return 2;
      return 0;
    },
    detail: (r) => {
      const w = wordCount(r.surrounding_paragraph);
      if (w >= 50) return `surrounding paragraph = ${w} words (optimal \u2265 50)`;
      if (w >= 25) return `surrounding paragraph = ${w} words (partial credit)`;
      if (w >= 10) return `surrounding paragraph = ${w} words (minimal credit)`;
      return `surrounding paragraph = ${w} words (need \u2265 10)`;
    },
  },
  {
    key: 'page_meta_desc',
    label: 'Page has meta description',
    group: 'Context',
    max: 5,
    met: (r) => !!r.page_meta_description,
    detail: (r) => (r.page_meta_description ? 'page meta description present' : 'landing page missing meta description'),
  },
  {
    key: 'structured_data',
    label: 'Page has structured data',
    group: 'Schema',
    max: 5,
    met: (r) => Array.isArray(r.page_schema_types) && r.page_schema_types.length > 0,
    detail: (r) => (Array.isArray(r.page_schema_types) && r.page_schema_types.length ? `schema.org types: ${r.page_schema_types.join(', ')}` : 'no JSON-LD structured data on page'),
  },
  {
    key: 'responsive',
    label: 'Responsive (srcset / <picture>)',
    group: 'Responsive & format',
    max: 10,
    met: (r) => r.has_srcset === true || r.has_picture === true,
    detail: (r) => {
      const parts = [];
      if (r.has_srcset) parts.push('srcset');
      if (r.has_picture) parts.push('<picture>');
      return parts.length ? `responsive via ${parts.join(' + ')}` : 'no srcset or <picture> source';
    },
  },
  {
    key: 'format',
    label: 'Supported image format',
    group: 'Responsive & format',
    max: 12,
    score: (r) => {
      const f = String(r.image_format || '').toLowerCase();
      if (NEXTGEN_FORMATS.has(f)) return 12;
      if (LEGACY_FORMATS.has(f)) return 5;
      return 0;
    },
    detail: (r) => {
      const f = String(r.image_format || '').toLowerCase();
      if (NEXTGEN_FORMATS.has(f)) return `next-gen format = ${f}`;
      if (LEGACY_FORMATS.has(f)) return `legacy web format = ${f} (use webp/avif for full credit)`;
      return `unsupported/unknown format "${f}"`;
    },
  },
  {
    key: 'resolution',
    label: 'High resolution (\u2265 1200px wide)',
    group: 'Quality',
    max: 8,
    met: (r) => (r.natural_width_px ?? 0) >= 1200 && aspectOk(r.natural_width_px, r.natural_height_px),
    detail: (r) => {
      const nw = r.natural_width_px ?? 0;
      const nh = r.natural_height_px ?? 0;
      if (!nw || !nh) return 'natural size unknown (lazy/undecoded)';
      if (!aspectOk(nw, nh)) return `extreme aspect ratio (${nw}\u00d7${nh})`;
      return `natural size ${nw}\u00d7${nh} (need width \u2265 1200)`;
    },
  },
  {
    key: 'dimensions_attr',
    label: 'width/height attributes set',
    group: 'Quality',
    max: 5,
    met: (r) => r.has_dimensions_attr === true,
    detail: (r) => (r.has_dimensions_attr === true ? 'width & height attributes present' : 'missing width/height attributes (layout shift risk)'),
  },
];

const RULES = SCORING === 'google' ? GOOGLE_RULES : BRIEF_RULES;

// Compute the per-image breakdown + total for the active rubric.
function scoreRow(row) {
  const breakdown = RULES.map((rule) => {
    let earned = 0;
    if (typeof rule.score === 'function') {
      earned = rule.score(row);
    } else if (typeof rule.met === 'function') {
      earned = rule.met(row) ? rule.max : 0;
    }
    return {
      key: rule.key,
      label: rule.label,
      group: rule.group,
      max: rule.max,
      earned,
      detail: rule.detail(row),
    };
  });
  const total = breakdown.reduce((a, p) => a + p.earned, 0);
  return { breakdown, total };
}

// Three-value classification. Precedence: stock > decorative > informative,
// with a low-information fallback of "decorative" when nothing else matches.
function informationalScore(row) {
  const altWc = row.alt_text_word_count ?? 0;
  const captionEmpty = !row.caption_text || String(row.caption_text).trim() === '';
  if (row.is_likely_stock === true) return 'stock';
  if (row.is_decorative === true || (altWc < 3 && captionEmpty)) return 'decorative';
  if (altWc >= 5) return 'informative';
  return 'decorative';
}

if (!(await fs.exists(INPUT_FILE))) {
  console.error(`score-and-enrich: input not found: ${INPUT_FILE}`);
  process.exit(1);
}

let rows;
try {
  rows = JSON.parse(await fs.readFile(INPUT_FILE));
} catch (e) {
  console.error(`score-and-enrich: cannot parse ${INPUT_FILE}: ${e.message}`);
  process.exit(1);
}

if (!Array.isArray(rows)) {
  console.error('score-and-enrich: expected a JSON array of image rows');
  process.exit(1);
}

// Three row kinds from stage 02:
//   - error markers ({ skipped:true, error }) — dropped here.
//   - page_only rows (cited page with 0 qualifying images) — kept, tiered, but
//     not scored, so the dashboard's overview still lists the page.
//   - real image rows — scored below.
const valid = rows.filter((r) => r && r.skipped !== true && !r.page_only && r.image_url);
const pageOnly = rows.filter((r) => r && r.skipped !== true && r.page_only === true);
console.log(`Scoring rubric: ${SCORING} (${RULES.length} signals, max 100)`);
console.log(`Valid image rows: ${valid.length} (of ${rows.length} total); page-only (0-image) pages: ${pageOnly.length}`);

if (valid.length === 0 && pageOnly.length === 0) {
  console.error('score-and-enrich: no valid image rows or pages to score');
  process.exit(1);
}

// citation_tier by tertile across ALL unique pages (image + page-only), ranked
// by whichever Stage 01 metric drove selection (`selected_by`): citation_count
// (default) or prompts_cited_in. This keeps tiers consistent with how the URLs
// were chosen when running with --by=prompts.
const allRows = [...valid, ...pageOnly];
// Every row in a run shares the same `selected_by` (stamped uniformly by Stage
// 01). Tier by that metric, defaulting to citation_count for older datasets.
const tierField = allRows.some((r) => r.selected_by === 'prompts_cited_in') ? 'prompts_cited_in' : 'citation_count';
const pageMetric = new Map();
for (const r of allRows) {
  if (!pageMetric.has(r.page_url)) pageMetric.set(r.page_url, r[tierField] ?? 0);
}
const pages = [...pageMetric.entries()].sort((a, b) => b[1] - a[1]);
const tierByPage = new Map();
const n = pages.length;
pages.forEach(([url], i) => {
  const pct = n <= 1 ? 0 : i / n; // 0 = highest on the ranking metric
  tierByPage.set(url, pct < 1 / 3 ? 'high' : pct < 2 / 3 ? 'mid' : 'low');
});
console.log(`Tiering pages by: ${tierField}`);

for (const r of valid) {
  const { breakdown, total } = scoreRow(r);
  r.metadata_completeness_score = total;
  r.score_breakdown = breakdown;
  r.scoring_mode = SCORING;
  r.image_informational_score = informationalScore(r);
  r.citation_tier = tierByPage.get(r.page_url) || 'low';
}
for (const r of pageOnly) {
  r.metadata_completeness_score = null; // no images to score
  r.score_breakdown = null;
  r.scoring_mode = SCORING;
  r.image_informational_score = null;
  r.citation_tier = tierByPage.get(r.page_url) || 'low';
}

const outRows = [...valid, ...pageOnly];
await fs.writeFile(OUTPUT_FILE, JSON.stringify(outRows, null, 2));

// Sanity check: high-tier pages should trend higher on metadata completeness.
const byTier = { high: [], mid: [], low: [] };
for (const r of valid) byTier[r.citation_tier]?.push(r.metadata_completeness_score);

function stats(arr) {
  if (!arr.length) return { n: 0, min: null, max: null, avg: null };
  const sum = arr.reduce((a, b) => a + b, 0);
  return {
    n: arr.length,
    min: Math.min(...arr),
    max: Math.max(...arr),
    avg: +(sum / arr.length).toFixed(1),
  };
}

const hi = stats(byTier.high);
const mid = stats(byTier.mid);
const lo = stats(byTier.low);
console.log('metadata_completeness_score by tier:');
console.log(`  high: ${JSON.stringify(hi)}`);
console.log(`  mid:  ${JSON.stringify(mid)}`);
console.log(`  low:  ${JSON.stringify(lo)}`);
if (hi.avg !== null && lo.avg !== null && hi.avg <= lo.avg) {
  console.warn(
    'WARNING: high-tier average is not above low-tier average — no positive image-metadata signal in this dataset.',
  );
}
console.log(`Wrote ${outRows.length} rows (${valid.length} image + ${pageOnly.length} page-only) -> ${OUTPUT_FILE}`);
