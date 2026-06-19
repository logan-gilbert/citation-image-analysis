# Stage 01 — Export from Adobe LLM Optimizer

Extract the top cited URLs (ranked by **Times Cited** by default, or **Prompts
Cited In** with `--by=prompts`) and their citation metadata from the LLM
Optimizer **URL Inspector → "Your Cited URLs"** view. Output: `input.json` — a
JSON array of cited-URL objects, sorted by the chosen metric descending.

This stage scrapes a live, virtualized UI, so it is not fully deterministic. But
the brittle DOM logic is encapsulated in `scripts/extract-llmo-list.js`, which
keys off **stable ARIA attributes only** — never the build-hashed CSS classes.

## Prerequisites

- The operator is **logged into** Adobe LLM Optimizer in the browser SLICC
  controls, sitting on the URL Inspector with the **"Your Cited URLs"** tab
  selected. The script operates on that already-open tab — it does **not**
  navigate (which could trigger SSO).
- No filters or manual sorting need to be pre-applied; the script reads whatever
  the grid shows and ranks the rows itself. (If the grid _happens_ to already be
  sorted descending by the chosen metric, the script takes a faster path — see
  below.) Whatever **Platform** filter is active (e.g. `ChatGPT (Free)`) is
  auto-detected and recorded on every row, since it scopes the whole dataset.
- You also **don't** need to scroll down and bump the **"Items per page"** control:
  before collecting, the script opens that popup and selects the smallest option
  ≥ `topN` (e.g. 50), skipping when the current size already covers `topN`.

## One command

```bash
export-cited-urls [topN] [outputPath] [--platform="ChatGPT (Free)"] [--by=prompts]
# defaults: topN = 10, outputPath = input.json, --by = citations
```

`--by` chooses the ranking column:

| `--by` value                          | Column sorted/ranked on                   |
| ------------------------------------- | ----------------------------------------- |
| `citations` (default) / `times-cited` | **Times Cited** (`citation_count`)        |
| `prompts` / `prompts-cited`           | **Prompts Cited In** (`prompts_cited_in`) |

Examples:

```bash
export-cited-urls                          # top 10 by Times Cited -> input.json
export-cited-urls 25                       # top 25 by Times Cited -> input.json
export-cited-urls 50 cited.json            # top 50 -> cited.json
export-cited-urls 10 input.json --platform="ChatGPT (Free)"  # force the platform label
export-cited-urls 10 input.json --by=prompts                 # rank by Prompts Cited In
```

The orchestrator (`scripts/export-cited-urls.jsh`) **drives everything as many
short evals** — the scroll loop lives in the `.jsh`, never inside one long page
eval, and each scan **retries** on a transient CDP timeout, so no single
`Runtime.evaluate` aborts the run. `extract-llmo-list.js` exposes these probes:

| `__LLMO_CMD__` | Does                                                                                                                                                                       |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `metrics`      | Probe only — grid metrics, current `aria-sort`, detected platform, columns, visible rows. No interaction.                                                                  |
| `sortstate`    | Cheap probe of the active column's `aria-sort` + its top visible values (read between trusted clicks).                                                                     |
| `marksort`     | Stamp a unique `aria-label` on the active sort header (by stable `data-key`) so the snapshot's name-based selector resolves to it; returns the token + header `outerHTML`. |
| `unmarksort`   | Remove that marker, restoring the header's accessible name.                                                                                                                |
| `scan`         | Set the scroll viewport's `scrollTop` and return the rows the virtualizer currently has.                                                                                   |
| `pagesize`     | Read the **"Items per page"** control's current value (and whether it exists). No interaction.                                                                             |
| `markpage` / `unmarkpage` | Stamp / remove a unique `aria-label` on the items-per-page button (and neutralize its children) so the snapshot resolves it for a trusted click that opens the listbox.    |
| `pageoptions`  | Return the numeric option values in the open listbox (e.g. `[10, 20, 50]`).                                                                                                |
| `markoption`   | Stamp a unique `aria-label` on the listbox option matching `__LLMO_PAGE_TARGET__` so the snapshot resolves it for a trusted click that selects it.                          |

The orchestrator picks the ranking column from `--by` and tells the extractor via
the `__LLMO_SORT_KEY__` global (`citations` or `promptsCited`).

**Sorting uses a TRUSTED CLICK on the header centre.** Synthetic in-page events
don't fire React Aria, and a trusted keyboard `Enter` lands on the header's nested
info **"i"** button (opening a focus-trapping description dialog) instead of
sorting. A real mouse click — what the operator uses — is the only reliable
trigger, and SLICC's `playwright-cli click` performs a trusted CDP
`Input.dispatchMouseEvent` at the element's **centre**. The header's centre is on
the column _text_, well left of the info "i" at the right edge, so it sorts. The
one hitch: SLICC builds snapshot selectors from **accessibility names**, and this
header has no `aria-label`/`title` of its own (its name is the merged
`Prompts Cited In Information`), so the selector never resolves (`Element not
found`). Fix: `marksort` stamps a unique `aria-label` on the header → the snapshot
yields a selector that uniquely matches it → `click <ref>` trusted-clicks its
centre → `unmarksort` restores the name. React Aria cycles
`none → ascending → descending`, so up to two clicks reach descending; the
orchestrator waits ~1.8s between clicks (in the `.jsh`, never a page eval) and
re-reads `sortstate`.

Then it collects:

- **Fast path** — once the grid is sorted descending by the metric (the click took,
  or the operator pre-sorted Times Cited), the top N by row index _is_ the top N by
  metric, so it collects just rows `2..N+1` and stops.
- **Robust path** — if the sort never took, it scroll-collects **every** row, sorts
  by the metric itself, and takes the top N. This is the safety net that keeps
  results correct even when the trusted click fails.

Flow:

1. `playwright-cli tab-list` → find the tab matching `llmo.now` / `url-inspector`.
2. `metrics` → read the current sort, the **Platform** filter (override with
   `--platform=...`), and grid metrics.
3. **Items per page** (skipped if it already covers `topN`, or absent): `pagesize`
   → `markpage` → `snapshot` → trusted `click` to open the listbox → `pageoptions`
   → pick the smallest option ≥ `topN` → `markoption` → `snapshot` → trusted
   `click` to select it → wait for the grid to re-fetch, then re-probe `metrics`.
4. **Sort** (skipped if already descending): `marksort` → `playwright-cli snapshot`
   → trusted `playwright-cli click` on the marked header → wait → `sortstate`;
   repeat up to 4 clicks until `aria-sort` is descending or the top values confirm
   it; then `unmarksort`.
5. **Scroll-collect** (one `scan` eval per step, each retried on a transient CDP
   timeout): from the top, accumulate rows by `aria-rowindex` until it has the top
   N (fast path) or every row (robust path), or it reaches the bottom.
6. **Rank** by the chosen metric and take the top N, then write the ranked URL
   array (each row stamped with `platform` and `selected_by`) to `outputPath`.

> If the trusted click succeeds, `--by=prompts` is just as fast as Times Cited. The
> robust full-collection only kicks in when the sort can't be confirmed (it warns
> and prints the header's `outerHTML` so the selector can be tuned).

## What the in-page extractor does (`extract-llmo-list.js`)

The grid is an Adobe Spectrum / React Aria **virtualized** grid: there is no
HTML `<table>`, only `div[role="grid"]` → `div[role="row"]` →
`div[role="rowheader"|"gridcell"]`. Only ~8 of the 667 rows are in the DOM at
once. The extractor (driven one short command at a time by the orchestrator):

1. **Finds the grid** by the column header `data-key="citations"` (there can be
   more than one `[role="grid"]` on the page).
2. **Marks the sort header** (`marksort` command) by its stable `data-key`
   (`citations` / `promptsCited`) with a unique `aria-label`, so the orchestrator's
   snapshot can resolve it for a trusted click on its centre. The extractor does
   NOT activate the sort itself (React Aria ignores synthetic events).
3. **Reads the visible rows** by `aria-rowindex`. The scroll container is **not**
   the `[role=grid]` element — it's a separate inner viewport (`overflow:auto`)
   located via a scrollable-element search; the orchestrator sets _its_ `scrollTop`
   between scans (in small, overlapping steps) as the virtualizer mounts new
   windows. The `metrics` payload reports `scroller_is_grid` for debugging.
4. Maps cells by stable `aria-colindex` (via the header `data-key` map) and
   returns the parsed rows. The choice of metric (via `__LLMO_SORT_KEY__`) and the
   final ranking happen in the orchestrator.

## Column mapping (stable `data-key` → field)

| Grid column       | `data-key`     | Output field                                             |
| ----------------- | -------------- | -------------------------------------------------------- |
| URL (rowheader)   | `url`          | `url` (full `href`)                                      |
| Citation Attempts | `agenticHits`  | `citation_attempts`                                      |
| **Times Cited**   | `citations`    | `citation_count` (+ `citation_trend` from the ↑/↓ arrow) |
| Prompts Cited In  | `promptsCited` | `prompts_cited_in`                                       |
| Referral Hits     | `referralHits` | `referral_hits`                                          |
| Categories        | `categories`   | `categories` (array)                                     |
| Markets           | `regions`      | `markets` (array)                                        |

The displayed URL text is truncated (`academy.com/return-policy`); the full
`https://www.…` URL comes from the link's `href`.

## Output schema (`input.json`)

Array of objects, each:

| Field               | Type         | Notes                                                              |
| ------------------- | ------------ | ------------------------------------------------------------------ |
| `citation_rank`     | integer      | 1 = top of the chosen ranking (`--by`)                             |
| `selected_by`       | string       | Which column drove ranking: `citation_count` or `prompts_cited_in` |
| `url`               | string       | Full page URL (from `href`)                                        |
| `citation_count`    | integer      | Times Cited                                                        |
| `citation_trend`    | string       | `rising` / `declining` / `stable` (↑/↓ arrow)                      |
| `citation_attempts` | integer      | Citation Attempts column                                           |
| `prompts_cited_in`  | integer      | Prompts Cited In column                                            |
| `referral_hits`     | integer      | Referral Hits column                                               |
| `categories`        | string[]     | Category pills                                                     |
| `markets`           | string[]     | Market pills                                                       |
| `platform`          | string\|null | Active Platform filter (e.g. `ChatGPT (Free)`); same on every row  |

Stage 02 only requires `url` and `citation_count`; the rest is carried through
for the dashboard (`platform` becomes the dashboard's `ai_platforms`).

## Troubleshooting

- **"could not find an LLM Optimizer tab"** — the operator isn't on the URL
  Inspector, or the tab URL changed. The error prints the open tabs; confirm the
  page, then re-run. If the host differs from `llmo.now`, update `TAB_MATCH` in
  `export-cited-urls.jsh`.
- **`ok: false`, `error: "no-cited-urls-grid-found"`** — the grid isn't rendered
  (wrong tab/view) or the structure changed. The payload lists the ARIA roles
  present as a hint.
- **"could not confirm descending after N trusted click(s)"** — the trusted clicks
  didn't move the grid's sort. The script prints the header's `outerHTML` and falls
  back to ranking by value (full collection), so results are still correct, just
  slower. To restore the fast path, check the printed `outerHTML`: confirm its
  `data-key` is `citations`/`promptsCited` (else update `SORT_COLS` in
  `extract-llmo-list.js`), and that the column is wide enough that its centre is on
  the text rather than the info "i" button (if a column is unusually narrow, the
  centre could land on the icon).
- **"marked header not found in snapshot" / "snapshot failed"** — the `marksort`
  `aria-label` didn't surface in the snapshot, or the snapshot errored. Falls back
  to ranking by value. Re-run; if it persists, the snapshot's accessibility tree
  changed shape.
- **The info-description popover opens during a run** — means a click hit the info
  "i" button instead of the header text. This should not happen (we click the
  header centre), but if a column is very narrow it can; widen the browser window
  or the column, or reduce reliance on the click by pre-sorting Times Cited.
- **"final rows are not strictly descending" warning** — should be rare, since
  the robust fallback sorts by value itself. If seen, it usually means the run came
  up short (see below) so the tail is incomplete.
- **Fewer rows than requested** — the robust sweep didn't reach every row before
  the scan cap or a scan's retries were exhausted. The warning prints
  `collected X of Y` plus scroll diagnostics. Re-run; for very large lists, raise
  `MAX_SCANS` in `export-cited-urls.jsh` (the robust cap scales with the total row
  count, but the hard ceiling still applies).
- **"CDP command timed out after 30000ms"** as a `scan retries exhausted` line —
  the live grid blocked its main thread longer than 30s across multiple retries.
  Each scan already retries with backoff; if it still exhausts, the page is
  heavily loaded — reload the LLM Optimizer tab and re-run. A _single_ such line
  that the run recovers from is harmless (it was retried).

## Re-run / scale

`input.json` is the cache. To re-pull (e.g., bump from 10 to 50), delete or
overwrite it: `export-cited-urls 50 input.json`. Start small (10) to validate the
end-to-end pipeline before scaling.
