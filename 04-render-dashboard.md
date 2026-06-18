# Stage 04 — Render the dashboard

Generate `dashboard.html` (plus two sibling JS files) from `dashboard-data.json`
and open it. **Deterministic** — it is the `render-dashboard` command
(`scripts/render-dashboard.jsh`). Do not hand-write HTML.

## Run it

```bash
render-dashboard dashboard-data.json dashboard.html
open dashboard.html
```

`open` serves the file through SLICC's preview service worker (not `file://`) and
shows it to the operator.

## What it produces

A zero-dependency HTML file plus two same-directory scripts loaded via
`<script src>`: `<base>.data.js` (sets `window.__DATA__`) and `<base>.app.js`
(the renderer). They are **external, not inline**, because the Chrome extension
serves previews from a `chrome-extension://` origin whose CSP is `script-src
'self'` — inline `<script>` is blocked there, so an inlined dataset/renderer
would leave the page body empty. Same-origin external scripts are served from the
VFS by the preview service worker and render identically in the CLI float, the
extension float, and a plain browser. The four views:

1. **Citation overview** — sortable table of cited pages (rank, page, the ranking
   metric, tier badge, AI platforms, category, trend, image count, avg metadata
   score). The metric column reflects how Stage 01 was run: it shows **Citations**
   (Times Cited) by default, or **Prompts cited in** when the run used
   `--by=prompts` (detected from each row's `selected_by`). Click a row to drill
   into that page.
2. **Page detail** — image gallery for the selected page: thumbnail, alt text (or
   a red MISSING badge), a colour-coded metadata bar (red ≤40, amber ≤70, green
   > 70), the informational badge, page position, surrounding H2/paragraph, and a
   > schema-match indicator.
3. **Quality by tier** — comparative CSS bars across high/mid/low tiers: avg
   metadata completeness, % with alt text, % informative, % stock, avg images per
   page. **This view is the answer to the experiment's core question.**
4. **Image frequency** — unique image URLs ranked by how many cited pages they
   appear on, with avg citation count and avg metadata score. Sortable.

## Deliberate deviation from the brief

The brief asked for Chart.js from a CDN. This dashboard uses **pure CSS bars and
vanilla JS instead** — the extension's `script-src 'self'` CSP blocks CDN
`<script src>` (and inline `<script>`) entirely, so a zero-dependency render is
the only thing that works in every float. The result is more portable, not less.
If the operator specifically wants Chart.js, vendor the library's source into a
same-directory `.js` file loaded via `<script src>`; do not link a CDN.

## Notes

- Thumbnails load live from `image_url` with `loading="lazy"`; broken/hotlink-
  protected images fall back to a grey placeholder via `onerror`.
- All sorting is client-side; there are no network calls beyond image thumbnails.
- Edit `scripts/render-dashboard.jsh` to change layout or add views; re-validate
  against the fixture (see the SKILL.md "Test the back half" section).
