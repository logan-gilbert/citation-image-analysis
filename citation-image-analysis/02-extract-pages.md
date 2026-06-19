# Stage 02 — Extract images + page context

Visit each URL in `input.json` (Stage 01 output), render the full page (including
lazy-loaded images), and extract image-level + page-level data. Output:
`output.json`, one row per image per page, with the citation fields carried
through. Stage 03 (`score-and-enrich`) reads this file directly.

## One command

```bash
extract-pages [inputPath] [outputPath]
# defaults: input = input.json, output = output.json
```

`scripts/extract-pages.jsh` loops every cited URL and, per page:

1. `playwright-cli open <url>` (captures the targetId), waits for `readyState`.
2. Scrolls to the bottom, waits for lazy images to load, scrolls back to top.
3. Runs `extract-page.js` via `eval-file`, returning `{ page, images }`.
4. Merges the Stage 01 citation fields onto every image row and appends to
   `output.json`.
5. Closes the tab it opened.

It is **sequential and resumable** — re-running skips pages already captured and
retries any that errored. It checkpoints `output.json` after every page (the VFS
persists), so an interruption loses nothing.

## Field mapping (Stage 01 → Stage 02 row)

Stage 01 emits `url`; Stage 03/04 key on `page_url`. The orchestrator maps:

| Stage 01 field   | output.json field | Notes                                                                                        |
| ---------------- | ----------------- | -------------------------------------------------------------------------------------------- |
| `url`            | `page_url`        | full page URL                                                                                |
| `citation_count` | `citation_count`  | Times Cited                                                                                  |
| `citation_rank`  | `citation_rank`   | 1 = top of the chosen ranking                                                                |
| `citation_trend` | `citation_trend`  | rising / declining / stable                                                                  |
| `selected_by`    | `selected_by`     | which metric drove ranking (`citation_count` or `prompts_cited_in`); Stage 03 tiers off this |
| `categories[]`   | `query_category`  | joined to a string for the dashboard column                                                  |
| `platform`       | `ai_platforms`    | the global Platform filter (e.g. `ChatGPT (Free)`), wrapped in an array; same for every row  |

`citation_attempts`, `prompts_cited_in`, `referral_hits`, `categories[]`, and
`markets[]` are also carried through (harmless extras for 03/04).

## Fields produced by extract-page.js

Page-level (once per URL, repeated on each image row): `page_title`, `page_h1`,
`page_word_count`, `page_schema_types`, `page_has_video`, `image_count_total`.

Image-level (per image, after filtering out anything < 50×50 px): `image_url`,
`image_filename`, `image_format`, `image_width_px`, `image_height_px`,
`alt_text`, `alt_text_present`, `alt_text_length`, `alt_text_word_count`,
`caption_text`, `surrounding_h2`, `surrounding_paragraph`, `page_position`,
`image_index`, `is_decorative`, `is_likely_stock`, `schema_image_match`.

The surrounding-context fields (`surrounding_h2`, `surrounding_paragraph`) use a
backward-DOM heuristic — spot-check a few pages and refine `extract-page.js` if a
particular site's markup fools it.

## Scaling past ~10–20 URLs: use scoops

The orchestrator is sequential. For large runs, fan the URL list across **scoops**
(not background tabs — all agents share one tab namespace, so `&`-backgrounded
`playwright-cli` is fragile). Give each scoop a slice of `input.json` and a part
file, then concatenate:

```text
scoop_scoop({ name: "extract-a", allowedCommands: ["playwright-cli","node","cat"],
  writablePaths: ["/scoops/extract-a/","/shared/citation/"],
  prompt: "Run extract-pages for this slice <slice of input.json>, writing to
           /shared/citation/output.part-a.json. Close every tab you open." })
# ...spawn extract-b / extract-c with other slices...
scoop_wait({ scoop_names: ["extract-a","extract-b","extract-c"] })
# then concatenate the part files into output.json
```

## Error handling

On open/extract failure, the orchestrator appends a marker row and continues:

```json
{ "page_url": "<URL>", "error": "<message>", "skipped": true }
```

Stage 03 filters these out. Re-running `extract-pages` drops the markers and
retries just those URLs.

A cited page with **0 qualifying images** (nothing ≥ 50×50 px) is not an error —
it's written as a `page_only` row (`{ ...citation+page fields, page_only: true }`)
so Stage 03 keeps it and the dashboard still lists the page in the Citation
Overview with an image count of 0.

## Critical rules (SLICC specifics)

- **Operator must be reachable for any URL that needs auth.** The target pages
  here are public retail pages, so no login is expected.
- **Never close tabs you did not open.** The orchestrator only closes tabs whose
  targetId it captured from its own `open` calls.
- **Tune timeouts for slow sites** via `PAGE_READY_MS` / `LAZY_SETTLE_MS` at the
  top of `extract-pages.jsh`.
