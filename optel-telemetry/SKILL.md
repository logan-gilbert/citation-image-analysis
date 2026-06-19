---
name: optel-telemetry
description: |
  Use this when the user wants per-page Core Web Vitals (LCP, CLS, INP) and page
  views from Adobe's internal Operational Telemetry (OpTel) tool — e.g. "collect
  OpTel CWV for these pages", "pull LCP/CLS/INP from OpTel", or to add page
  performance context to a citation-image-analysis run (the `--telemetry` flag).
  Drives an already-open, logged-in OpTel tab to generate a domain key, open the
  OpTel Explorer, filter by each page path, and read the metric tiles. Read this
  BEFORE running so you use the deterministic .jsh orchestrator + in-page helper.
allowed-tools: bash, read_file, write_file, edit_file
---

# OpTel Telemetry

A reusable connector to Adobe's internal **OpTel** (Operational Telemetry) tool.
It collects per-page **Core Web Vitals** (LCP, CLS, INP) and **page views** for a
list of URLs and writes a `telemetry.json` keyed by normalized page URL. The
companion `enrich-telemetry` command (in the `citation-image-analysis` skill)
joins that onto a citation run so the dashboard can display page performance
alongside image-quality scores.

## How this skill is built (read this first)

Like Stage 01 of `citation-image-analysis`, this splits a thin **orchestrator**
(`scripts/collect-optel.jsh`, all waits/polling/looping) from an **in-page
helper** (`scripts/optel-explorer.js`, all DOM reads/writes, one short call per
step). No single `Runtime.evaluate` nears the CDP 30s budget.

Unlike the LLM Optimizer grid (React Aria — needs trusted CDP clicks), OpTel is
an **Angular / Angular-Material** app: zone.js binds handlers via
`addEventListener`, so synthetic `input`/`change`/`click` events from the helper
drive it. Selectors key off **stable ids** only (`#filter`, `#lcp`, `#cls`,
`#inp`, `#pageviews`, `input[formcontrolname="domain"]`) and the semantic
`score-good | score-ni | score-poor` rating classes — never build-hashed CSS.

## Prerequisites

- The operator is logged into OpTel and has the tab open in the browser SLICC
  controls: `https://aemcs-workspace.adobe.com/customer/generate-optel-domain-key`
  (the domain-key form) **or** an OpTel Explorer tab.
- The target URLs belong to a domain the operator can query in OpTel.

## Commands

### `collect-optel <urlsSource> [telemetry.json]`

```bash
collect-optel /workspace/citation-run/output.json /workspace/citation-run/telemetry.json
collect-optel urls.json telemetry.json    # urls.json = ["https://www.example.com/a", ...]
```

`<urlsSource>` is a JSON file: either an array of full URL strings, or a citation
run's `output.json` (rows with `page_url`). The orchestrator:

1. finds the OpTel tab (`tab-list`, match `aemcs-workspace.adobe.com`/`optel`);
2. per domain: if on the key form, fills the **Domain** input with the URL host
   (e.g. `www.example.com`), clicks **Generate**, waits for the **OpTel
   Explorer** button, clicks it, and follows the new tab it opens;
3. per page: fills `#filter` with the URL `pathname`, polls until the LCP/CLS/INP
   tiles settle, and reads each tile's value + rating class;
4. writes `telemetry.json` keyed by normalized URL (origin + lowercased host +
   pathname, no trailing slash/query/hash), with `lcp_s`, `lcp_rating`, `cls`,
   `cls_rating`, `inp_s`, `inp_rating`, `page_views`, and the raw strings.

It is **non-fatal-friendly**: a domain it can't reach is skipped with a warning.

### Joining onto a citation run

Use `enrich-telemetry` (the `citation-image-analysis` skill) to merge a
`telemetry.json` onto a run's `output.json`, or just run the pipeline with
`--telemetry`, which calls both for you between Stage 02 and Stage 03:

```bash
citation-pipeline 20 --scoring=google --telemetry
```

## What gets measured (and how to read it)

- **LCP / CLS / INP** are page-level **confounders** for the "do better images
  earn more AI citations?" question — a page can score high on images yet load
  slowly (or vice-versa), which can mask the image signal. They're shown so you
  can eyeball that. Google "good" thresholds: LCP ≤ 2.5s, CLS ≤ 0.1, INP ≤ 200ms.
- **Page views** are **display-only**. They sit *downstream* of citations
  (more citations → more visits), so conditioning on them would be **collider
  bias**. They are never used as a quality control — just context.

### Gaps: pages with no OpTel field data

Many pages won't report (the OpTel package isn't installed, or traffic is too
low to sample). The `citation-image-analysis` pipeline fills those gaps with
**lab CWV** measured during its Stage 02 page visit (`extract-pages --lab-cwv`,
which `--telemetry` enables automatically): a synthetic single-load LCP + CLS,
plus **TBT as a lab proxy for INP** (true INP needs real users). The dashboard
prefers OpTel **field** data per page and only falls back to lab where OpTel is
absent, tagging each page `cwv_source` (`optel` | `lab`) and marking lab readings
(dashed pills, INP\*). This connector itself only produces OpTel field data.

## Live-UI assumptions to validate (handled defensively)

These mirror how Stage 01 was tuned against the live LLMO grid; verify on first
real run and adjust the helper if a build differs:

- the **OpTel Explorer** opens in a **new tab** (we detect it by diffing
  `tab-list`; we fall back to same-tab navigation);
- filtering by `pathname` updates the `#lcp/#cls/#inp` tiles **in place** (if a
  result row must be selected first, add a click-first-match step in the helper);
- rating class names are `score-good | score-ni | score-poor`.

## Don't

- Don't close tabs you didn't open.
- Don't treat page views as a quality signal or "control" for it (collider).
- Don't hard-code build-hashed CSS classes — use the stable ids above.
