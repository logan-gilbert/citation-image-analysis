# Stage 03 — Score and enrich

Compute three derived fields per image row and write the dashboard-ready dataset.
This stage is **fully deterministic** — it is the `score-and-enrich` command
(`scripts/score-and-enrich.jsh`). Do not reimplement it inline.

## Run it

```bash
score-and-enrich output.json dashboard-data.json
# defaults if you omit args: input=output.json, output=dashboard-data.json, scoring=google
# add --scoring=original to use the original Phase 1 rubric instead of Google's
score-and-enrich output.json dashboard-data.json --scoring=original
```

It filters out `skipped` rows, then adds to every remaining image row:

1. **`metadata_completeness_score`** (0–100, weighted). The weights depend on
   `--scoring` (default `google`):

   **`--scoring=original`** (the original Phase 1 brief rubric; `brief` is also accepted):

   | Condition                               | Points |
   | --------------------------------------- | ------ |
   | `alt_text_present` is true              | +30    |
   | `alt_text_word_count` ≥ 10              | +20    |
   | `caption_text` non-empty                | +20    |
   | `surrounding_paragraph` word count ≥ 50 | +15    |
   | `schema_image_match` is true            | +15    |

   **`--scoring=google`** (default, aligned to [Google image SEO best practices](https://developers.google.com/search/docs/appearance/google-images),
   which Google states also feed AI/Search visual surfaces):

   | Condition                                              | Points |
   | ----------------------------------------------------- | ------ |
   | Alt text present                                       | +15    |
   | Alt descriptive (4–16 words, not keyword-stuffed)     | +10    |
   | Descriptive filename (not `IMG0023`/`image1`/hash)    | +10    |
   | Caption present                                        | +8     |
   | Relevant nearby text ≥ 50 words                        | +7     |
   | Preferred image (og:image / primaryImageOfPage / ImageObject) | +15 |
   | Page has structured data (JSON-LD)                     | +5     |
   | Responsive image (`srcset` or `<picture>`)            | +10    |
   | Supported image format (jpg/png/webp/svg/avif/gif/bmp)| +7     |
   | High resolution (natural width ≥ 1200, sane aspect)   | +8     |
   | `width`/`height` attributes set (layout stability)    | +5     |

   Both rubrics score from the **same** Stage 02 `output.json` (extract-page.js
   always captures the signals both need), so you can compare by re-scoring one
   crawl two ways. The chosen rubric is stamped on every row as `scoring_mode`,
   and a per-image `score_breakdown` array (`{ key, label, group, max, earned,
   detail }`) is emitted so Stage 04 renders the exact rubric the run used.

2. **`image_informational_score`** — `informative` / `decorative` / `stock`.
   Precedence: stock → decorative → informative, with a `decorative` fallback.

3. **`citation_tier`** — `high` / `mid` / `low` by tertile across the unique
   pages, ranked by whichever Stage 01 metric drove selection (`selected_by`):
   `citation_count` (default) or `prompts_cited_in` (when Stage 01 ran with
   `--by=prompts`). The chosen field is logged as `Tiering pages by: <field>`.

## What it logs

Record counts plus min/max/avg `metadata_completeness_score` per tier. If the
high tier's average is **not** above the low tier's, it prints a `WARNING` — that
is a real finding (no positive image-metadata signal in this dataset), not a bug.

## When to edit the script

Only if the scoring weights or classification rules themselves change. Editing a
deterministic script is the right way to change scoring — never hand-edit
`dashboard-data.json`. After editing, re-validate against the fixture:

```bash
cd /workspace/skills/citation-image-analysis
score-and-enrich fixtures/sample-output.json /tmp/dd.json && cat /tmp/dd.json | head
```
