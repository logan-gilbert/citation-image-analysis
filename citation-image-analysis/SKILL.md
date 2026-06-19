---
name: citation-image-analysis
description: |
  Use this when the user wants to analyze how AI search citations relate to the
  images on cited pages — e.g. "run the citation image pipeline", "do cited pages
  have better image metadata?", "build the AI citation image dashboard", or any
  task involving Adobe LLM Optimizer citation data plus per-page image extraction.
  Covers the four-stage pipeline (export citations, extract page images, score
  metadata, render dashboard) and its companion scripts. Read this BEFORE running
  any stage so you use the deterministic .jsh scripts instead of re-deriving logic.
allowed-tools: bash, read_file, write_file, edit_file
---

# Citation Image Analysis

A four-stage pipeline that connects Adobe LLM Optimizer citation data to the
image content and page context of cited pages, then renders a self-contained
dashboard. It answers one question: **do pages that earn more AI citations share
common image characteristics (metadata completeness, informational quality,
context, position) compared to pages that earn few or none?**

## How this skill is built (read this first)

SLICC skills are not an executable pipeline runner — there is no `slicc run`.
This skill is **you (the cone) reading these instructions and acting**. To keep
re-runs reproducible, the deterministic work lives in companion scripts you
invoke from `bash`, not in prose you re-interpret each time:

| Stage | What it does                             | How you run it                                                                         | Deterministic?                   |
| ----- | ---------------------------------------- | -------------------------------------------------------------------------------------- | -------------------------------- |
| 01    | Export top cited URLs from LLM Optimizer | `export-cited-urls` (`scripts/export-cited-urls.jsh` + `scripts/extract-llmo-list.js`) | Semi (live UI, stable selectors) |
| 02    | Extract images + page context per URL    | `extract-pages` (`scripts/extract-pages.jsh` + `scripts/extract-page.js`)              | Semi (live web)                  |
| 03    | Score metadata, classify, assign tiers   | `score-and-enrich` (`scripts/score-and-enrich.jsh`)                                    | **Yes**                          |
| 04    | Generate `dashboard.html`                | `render-dashboard` (`scripts/render-dashboard.jsh`)                                    | **Yes**                          |

The `.jsh` scripts are auto-discovered as shell commands by basename
(`export-cited-urls`, `score-and-enrich`, `render-dashboard`) — call them
directly from any cwd. Stage 01 still scrapes a live, virtualized UI, but the
brittle DOM logic is encapsulated in `extract-llmo-list.js`, keyed off **stable
ARIA attributes** (`role`, `data-key`, `aria-colindex`, `aria-sort`,
`aria-rowindex`) — never the build-hashed CSS classes.

## Prerequisites

- The operator is logged into Adobe LLM Optimizer in the browser SLICC controls.
- All target URLs are publicly reachable without auth.

## Run the full pipeline

### Option A — all at once (recommended)

One command runs all four stages in order and auto-opens the dashboard, stopping
on the first failure. It creates its own run directory
(`/workspace/citation-run` by default), so you can run it from **any** cwd — no
`mkdir`/`cd` needed:

```bash
citation-pipeline 10                       # top 10, end to end
citation-pipeline 25 --fresh               # start clean (don't resume/append)
citation-pipeline 10 --platform="ChatGPT (Free)"   # force the platform label
citation-pipeline 10 --by=prompts          # rank by "Prompts Cited In" instead of "Times Cited"
citation-pipeline 10 --scoring=original    # score with the original Phase 1 rubric
citation-pipeline 10 --lab-cwv             # also capture synthetic (lab) Core Web Vitals
citation-pipeline 10 --dir=/workspace/run-2   # write artifacts elsewhere
```

`--by` selects the ranking column for Stage 01: `citations` (default, **Times
Cited**) or `prompts` (**Prompts Cited In**). `--scoring` selects the metadata
rubric for Stage 03: `google` (default, aligned to [Google image SEO best
practices](https://developers.google.com/search/docs/appearance/google-images))
or `original` (the Phase 1 brief's 5-signal rubric). To compare rubrics, run the
pipeline into two `--dir` folders (e.g.
`--scoring=google --dir=/workspace/run-google` and
`--scoring=original --dir=/workspace/run-original`) and open both dashboards.
`--dir` overrides where artifacts are written (default `/workspace/citation-run`).

`--lab-cwv` (optional) captures per-page **Core Web Vitals** during the Stage 02
visit and shows them in the dashboard (display-only): a synthetic single-load
measurement of LCP + CLS, with **TBT as a lab proxy for INP** (true INP needs
real users). Readings are clearly marked **lab (synthetic)** (dashed pills,
INP\*). CWV are page-level **confounders** to watch alongside image quality. Lab
capture adds ~1.5s per page (a short settle so late LCP/shifts register).

> **Lab CWV briefly foregrounds each tab.** Chrome never computes LCP or
> layout-shift for hidden/background tabs (only long tasks run), so `--lab-cwv`
> calls `playwright-cli activate` to bring each page to the foreground while it
> paints. Expect the active tab to flip from page to page during Stage 02; this
> is required to get real lab LCP/CLS and is harmless to the data.

This works **today in the SLICC CLI float** — no Chrome extension required.
Requires the operator logged into LLM Optimizer on "Your Cited URLs".

### Option B — stage by stage

Run the stages individually (useful for debugging or re-running just one). These
use relative filenames, so first pick a working directory:

```bash
mkdir -p /workspace/citation-run && cd /workspace/citation-run
```

1. **Stage 01 — export.** If `input.json` already exists, skip to stage 02 and
   log "Using existing input.json". Otherwise, with the operator logged into the
   LLM Optimizer **URL Inspector → "Your Cited URLs"** view:
   ```bash
   export-cited-urls 10 input.json              # top 10 by Times Cited
   export-cited-urls 10 input.json --by=prompts # top 10 by Prompts Cited In
   ```
   The script finds the open LLM Optimizer tab, sorts by the chosen column
   (`--by=citations` default, or `--by=prompts`) descending, scroll-collects the
   top N, and writes `input.json`. See `01-export-llm-optimizer.md` for the output
   schema and troubleshooting.
2. **Stage 02 — extract.** Run `extract-pages` (see `02-extract-pages.md`). It
   visits each cited URL, renders the page, extracts image + page data, merges the
   citation fields, and writes `output.json` (one row per image per page). It is
   sequential, resumable, and checkpoints after every page — pass `--fresh` to
   start clean instead of resuming/appending. For large runs, parallelize across
   URLs with **scoops**, not background tabs (see that doc).
3. **Stage 03 — score.** Deterministic, one command:
   ```bash
   score-and-enrich output.json dashboard-data.json                    # Google-SEO rubric (default)
   score-and-enrich output.json dashboard-data.json --scoring=original # original Phase 1 rubric
   ```
   Both rubrics score from the same `output.json`. See `03-score-and-enrich.md`
   for the full weight tables.
4. **Stage 04 — render.** Deterministic, one command, then open it:
   ```bash
   render-dashboard dashboard-data.json dashboard.html
   open dashboard.html
   ```

Log total runtime and record counts at each stage.

## Stage reference docs

Read the per-stage doc with `read_file` (or `skill read`) right before running
that stage — don't load all four into context at once:

- `01-export-llm-optimizer.md`
- `02-extract-pages.md`
- `03-score-and-enrich.md`
- `04-render-dashboard.md`

## Test the back half without the web

Stages 03→04 are pure data transforms. Validate them offline against the bundled
fixture before wiring up the brittle scraping stages:

```bash
cd /workspace/skills/citation-image-analysis
score-and-enrich fixtures/sample-output.json /tmp/dd.json
render-dashboard /tmp/dd.json /tmp/dashboard.html
open /tmp/dashboard.html
```

You should see 5 scored rows, three citation tiers, and a four-view dashboard.

## Deliberate decisions (don't "fix" these)

- **Dashboard uses CSS bars, not CDN Chart.js.** The brief specified Chart.js
  from a CDN, but SLICC's extension float blocks CDN `<script>` tags (CSP). The
  zero-dependency CSS version is fully self-contained and renders identically in
  every float. If the user insists on Chart.js, vendor it into the HTML rather
  than loading from a CDN.
- **`open` serves via the preview service worker, not `file://`.** That is
  expected — inlining the JSON (the brief's CORS workaround) still applies.
- **Parallelism is via scoops, not concurrent tabs.** All agents share one tab
  namespace; `&`-backgrounded `playwright-cli` over shared tabs is fragile.

## Don't

- Don't reimplement scoring or dashboard logic inline — call the `.jsh` scripts.
- Don't close browser tabs you did not open during stage 02.
- Don't run stage 01 at full scale before a 5-URL test passes.
- Don't author or edit this skill under `.agents/` or `.claude/` — it is a native
  `/workspace/skills/` skill.

## Phase 2 (out of scope now)

A future `05-dam-match` stage cross-references extracted `image_url`s against DAM
asset records. SLICC already has the primitives: `mount --source da://<org>/<repo>`
for Adobe da.live and `mount --source s3://...` for CDN-origin buckets.
