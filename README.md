# citation-image-analysis

A SLICC skill that links AI-search citation data (Adobe LLM Optimizer) to the
images and page context of cited pages, then renders a self-contained dashboard.

**Core question:** do pages that earn more AI citations share common image
characteristics — metadata completeness, informational quality, page context, or
position — compared to pages that earn few or no citations?

> **Note:** This is an independent, personal project. It is not an official
> product of, affiliated with, or endorsed by any company. "Adobe LLM Optimizer"
> and other product names are referenced only to describe where the input data
> comes from; the skill drives a browser tab the operator is already logged into
> and reads what's on screen.

## What's in here

| File                           | Role                                                                 |
| ------------------------------ | -------------------------------------------------------------------- |
| `SKILL.md`                      | Entry point. Loads into the agent prompt; orchestrates the 4 stages.   |
| `01-export-llm-optimizer.md`    | Stage 01 — scrape LLM Optimizer → `input.json` (non-deterministic).    |
| `02-extract-pages.md`           | Stage 02 — extract images + context → `output.json` (live web).        |
| `03-score-and-enrich.md`        | Stage 03 — run `score-and-enrich` → `dashboard-data.json`.             |
| `04-render-dashboard.md`        | Stage 04 — run `render-dashboard` → `dashboard.html`.                  |
| `scripts/citation-pipeline.jsh` | One command runs all 4 stages + auto-opens the dashboard.             |
| `scripts/export-cited-urls.jsh` | Stage 01 orchestrator: finds the LLMO tab, sorts, scroll-collects.    |
| `scripts/extract-llmo-list.js`  | Runs in-page via `playwright-cli eval-file`; reads the cited grid.    |
| `scripts/extract-pages.jsh`     | Stage 02 orchestrator: visits each URL, merges citation fields.       |
| `scripts/extract-page.js`       | Runs in-page via `playwright-cli eval-file`; returns page + images.   |
| `scripts/measure-cwv.js`        | In-page LAB CWV reader (LCP/CLS + TBT proxy) for Stage 02 `--lab-cwv`. |
| `scripts/score-and-enrich.jsh`  | Deterministic scoring/classification/tiering command.                 |
| `scripts/render-dashboard.jsh`  | Deterministic, zero-dependency dashboard generator.                   |
| `fixtures/sample-output.json`   | Sample stage-02 output for offline testing of stages 03→04.           |

`citation-pipeline`, `score-and-enrich`, and `render-dashboard` are
auto-discovered as shell commands by basename — call them from any directory.

## Install

This is a standalone [SLICC](https://github.com/cursor/slicc) skill. The skill
lives in the [`citation-image-analysis/`](citation-image-analysis/) folder of
this repo (the folder name becomes the installed skill name). Install it into a
running SLICC instance with `upskill`, pointing at this repo:

```bash
upskill logan-gilbert/citation-image-analysis          # list the skill(s) in the repo
upskill logan-gilbert/citation-image-analysis --all    # install it
```

That installs the skill into `/workspace/skills/citation-image-analysis/` in the
instance's VFS. Verify with `skill list` (look for `citation-image-analysis`)
and `skill read citation-image-analysis`.

You can also copy the `citation-image-analysis/` folder into `/workspace/skills/`
directly in a running instance, or vendor it into a SLICC fork under
`packages/vfs-root/workspace/skills/` so it bundles into every build.

### Requirements

- A SLICC instance with the `playwright-cli` command available.
- For `--lab-cwv`, SLICC needs a `playwright-cli activate` (bring-tab-to-front)
  command so pages paint while measured; without it LCP/CLS may read empty
  (long-task/TBT still works) and the rest of the pipeline is unaffected.

## Run it

Start the pipeline from a chat prompt to the cone, e.g.:

> "Run the citation image analysis pipeline. I'm logged into LLM Optimizer."

The cone will load `SKILL.md` and walk the four stages. Or run the whole thing
yourself with one command — it creates its own run directory
(`/workspace/citation-run`), chains all four stages, and opens the dashboard:

```bash
citation-pipeline 20 --by=prompts --fresh   # top 20 by Prompts Cited In, clean run
citation-pipeline 20 --scoring=original      # score with the original Phase 1 rubric
citation-pipeline 20 --lab-cwv               # + synthetic (lab) Core Web Vitals
```

`--scoring` picks the Stage 03 metadata rubric: `google` (default, aligned to
[Google image SEO best practices](https://developers.google.com/search/docs/appearance/google-images))
or `original` (the Phase 1 brief's 5-signal rubric). Both score from the same
crawl; compare by running into two `--dir` folders.

`--lab-cwv` (optional) captures per-page **Core Web Vitals** during the Stage 02
visit and shows them in the dashboard — display-only: a synthetic single-load
LCP + CLS, plus **TBT as a lab proxy for INP**. Readings are clearly marked
**lab (synthetic)** (dashed pills, INP\*). CWV are page-level confounders to
weigh against image quality.

To drive just the deterministic back half:

```bash
mkdir -p /workspace/citation-run && cd /workspace/citation-run
# ...produce input.json (stage 01) and output.json (stage 02)...
score-and-enrich output.json dashboard-data.json
render-dashboard dashboard-data.json dashboard.html
open dashboard.html
```

## Try it now (no LLM Optimizer needed)

```bash
cd /workspace/skills/citation-image-analysis
score-and-enrich fixtures/sample-output.json /tmp/dd.json   # Google-SEO rubric (default)
render-dashboard /tmp/dd.json /tmp/dashboard.html
open /tmp/dashboard.html

# or score the same fixture with the original Phase 1 rubric and compare
score-and-enrich fixtures/sample-output.json /tmp/dd-original.json --scoring=original
render-dashboard /tmp/dd-original.json /tmp/dashboard-original.html
open /tmp/dashboard-original.html
```

Expect 5 scored image rows, three citation tiers, and the four-view dashboard.

## Design decisions

- **Deterministic work lives in `.jsh`, not prose.** SLICC has no `slicc run`;
  skills are agent guidance. Pushing scoring and rendering into scripts makes
  re-runs reproducible.
- **View 3 chart type: grouped bar chart (CSS), not box plots.** The brief left
  this to the developer. Grouped bars compare the high/mid/low citation tiers
  across the metrics that answer the core question (avg metadata completeness,
  % with alt text, % informative, % stock, avg images per page) and read clearly
  at a glance. Box plots would need full per-image distributions per tier — more
  data and harder to scan for a yes/no "do cited pages differ?" answer. Bars are
  rendered as pure CSS (no Chart.js), keeping the file dependency- and
  network-free.
- **Header shows run timestamp + record counts.** The dashboard header renders
  `<N> pages · <M> images · generated <ISO-8601 timestamp>` so every export is
  self-identifying (recommended in the brief — done).
- **No CDN, and no inline scripts.** The extension serves previews from a
  `chrome-extension://` origin whose CSP is `script-src 'self'`, which blocks both
  CDN and inline `<script>`. The dashboard ships its data and renderer as
  same-origin sibling `.js` files loaded via `<script src>`, so it renders
  identically in the CLI float, the extension float, and a plain browser.
- **Scoops for parallelism**, not concurrent browser tabs (shared tab namespace).
- **Tab selection is host-scoped.** Stage 01 attaches only to the real app
  (`llmo.now` / `url-inspector`) and explicitly skips the `business.adobe.com`
  marketing page, which is not CDP-attachable.

## Phase 2 (not built)

`05-dam-match` would cross-reference `image_url`s against DAM assets. SLICC
already provides `mount --source da://<org>/<repo>` (Adobe da.live) and
`mount --source s3://...` (CDN-origin buckets) as the building blocks.
