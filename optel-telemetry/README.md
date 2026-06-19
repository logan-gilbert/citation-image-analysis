# optel-telemetry

A SLICC skill that collects per-page **Core Web Vitals** (LCP, CLS, INP) and
**page views** from Adobe's **OpTel** (Operational Telemetry) tool and writes a
`telemetry.json` keyed by normalized page URL.

> **Note:** This is an independent, personal project, not an official product of
> or endorsed by any company. It drives an OpTel tab the operator is already
> logged into and reads what's on screen, using only the tool's publicly
> documented domain-key / Explorer flow. Product names are referenced only to
> describe where the data comes from.

Built as a reusable connector: it pairs with `citation-image-analysis` (via the
`--telemetry` pipeline flag and the `enrich-telemetry` join command) to add page
performance context to a citation run, but it can be used standalone for any list
of URLs.

## What's in here

| File                         | Role                                                              |
| ---------------------------- | ----------------------------------------------------------------- |
| `SKILL.md`                   | Entry point. Loads into the agent prompt; documents the commands. |
| `scripts/collect-optel.jsh`  | Orchestrator: find the OpTel tab, generate key, read each page.   |
| `scripts/optel-explorer.js`  | In-page helper (via `playwright-cli eval-file`): state + metrics. |

`collect-optel` is auto-discovered as a shell command by basename — call it from
any directory.

## Install

This skill ships in the same repo as `citation-image-analysis`, in the
[`optel-telemetry/`](.) folder (the folder name becomes the installed skill
name). Install both skills into a running SLICC instance with `upskill`:

```bash
upskill logan-gilbert/citation-image-analysis          # list the skills in the repo
upskill logan-gilbert/citation-image-analysis --all    # install all of them
```

That installs this skill into `/workspace/skills/optel-telemetry/` in the
instance's VFS. Verify with `skill list` (look for `optel-telemetry`). You can
also copy the `optel-telemetry/` folder into `/workspace/skills/` directly.

## Run it

```bash
# 1) Have a logged-in OpTel tab open (aemcs-workspace.adobe.com).
# 2) Collect CWV + page views for a citation run's pages:
collect-optel /workspace/citation-run/output.json /workspace/citation-run/telemetry.json

# 3) Join onto the run and refresh its dashboard (citation-image-analysis skill):
enrich-telemetry /workspace/citation-run
```

Or in one shot via the pipeline flag:

```bash
citation-pipeline 20 --scoring=google --telemetry
```

## Reading the numbers

- **LCP / CLS / INP** are page-level **confounders** for the "do better images
  earn more AI citations?" question. Google "good" thresholds: LCP ≤ 2.5s,
  CLS ≤ 0.1, INP ≤ 200ms. Colored green/amber/red from OpTel's own rating class.
- **Page views** are **descriptive only** — downstream of citations, so
  conditioning on them would be collider bias. Never used as a quality control.

## Design notes

- **Orchestrator vs. in-page helper split**, mirroring Stage 01 of
  `citation-image-analysis`: all waits/polling live in the `.jsh`; the `.js`
  helper does one short DOM op per call so no eval nears the CDP 30s budget.
- **Synthetic events are fine here.** OpTel is Angular (zone.js binds via
  `addEventListener`), unlike LLM Optimizer's React Aria grid which needs trusted
  CDP clicks. If a future OpTel build hardens against synthetic events, switch the
  fills/clicks to snapshot + trusted-click in the orchestrator.
- **Stable selectors only:** `#filter`, `#lcp/#cls/#inp/#pageviews`,
  `input[formcontrolname="domain"]`, and the semantic `score-good|score-ni|score-poor`
  rating classes — never build-hashed CSS.
