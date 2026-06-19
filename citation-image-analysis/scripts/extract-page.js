// extract-page.js — runs IN THE PAGE via `playwright-cli eval-file --tab=<id>`.
//
// Returns one object: { page: {...page-context fields...}, images: [ {...per-image fields...} ] }.
// The extract-pages.jsh orchestrator merges the citation-level fields from
// input.json (mapping Stage 01's `url` -> `page_url`, plus citation_count,
// citation_rank, citation_trend, query_category, ai_platforms) onto every image
// row before appending to output.json.
//
// The orchestrator scrolls the page to the bottom (to trigger lazy-loading) and
// back to the top BEFORE evaluating this file. It is read-only / side-effect free.
//
// Images are de-duplicated by absolute URL within the page so carousel/slider
// clones (which repeat the same src across several <img> nodes) count once.
//
// Besides the brief's signals (alt/caption/surrounding text/schema), each image
// row also carries Google image-SEO signals used by the --scoring=google rubric:
// natural_width_px/natural_height_px, img_title, has_srcset, has_picture,
// has_dimensions_attr, and preferred_image_match (URL declared via og:image /
// schema primaryImageOfPage / ImageObject). The page object adds
// page_meta_description. These are always captured (harmless to the brief
// rubric) so one crawl can be scored either way.

(() => {
  const MIN_DIM = 50; // skip icons / tracking pixels

  const text = (el) => (el && el.textContent ? el.textContent.trim() : '');
  const wordCount = (s) => (s ? s.trim().split(/\s+/).filter(Boolean).length : 0);

  // Nearest preceding element of a given tag, walking siblings then ancestors.
  function precedingByTag(start, tag) {
    const TAG = tag.toUpperCase();
    let node = start;
    while (node) {
      let sib = node.previousElementSibling;
      while (sib) {
        if (sib.tagName === TAG) return sib;
        const inner = sib.querySelectorAll ? sib.querySelectorAll(tag) : [];
        if (inner.length) return inner[inner.length - 1];
        sib = sib.previousElementSibling;
      }
      node = node.parentElement;
    }
    return null;
  }

  // ---- page-context fields ----
  const bodyClone = document.body ? document.body.cloneNode(true) : null;
  if (bodyClone) {
    bodyClone.querySelectorAll('script,style,nav,footer,noscript').forEach((n) => n.remove());
  }
  const pageWordCount = bodyClone ? wordCount(bodyClone.textContent || '') : 0;

  const schemaTypes = [];
  const schemaImageUrls = new Set();
  // Google's "preferred image" sources: schema.org primaryImageOfPage,
  // mainEntity(OfPage) image, ImageObject urls, and the og:image meta tag.
  // Used by the Google-SEO scoring rubric's preferred_image_match check.
  const preferredImageUrls = new Set();
  const preferredImageUrlsNormalized = new Set();
  const abs = (u) => {
    try {
      return new URL(u, document.baseURI).href;
    } catch (_e) {
      return typeof u === 'string' ? u : '';
    }
  };
  // Normalize URLs to improve matching: strip query strings/hashes, and strip
  // common CMS auto-resize suffixes like "-1024x768" from the filename.
  // (e.g. hero-shoe.jpg and hero-shoe-1024x768.jpg will match).
  const normalizeUrl = (u) => abs(u).split('?')[0].split('#')[0].replace(/(-\d+x\d+)(\.[a-zA-Z0-9]+)$/i, '$2');

  document.querySelectorAll('script[type="application/ld+json"]').forEach((s) => {
    try {
      const json = JSON.parse(s.textContent || '{}');
      const visit = (obj) => {
        if (!obj || typeof obj !== 'object') return;
        if (Array.isArray(obj)) return obj.forEach(visit);
        if (obj['@type']) [].concat(obj['@type']).forEach((t) => schemaTypes.push(t));
        const isImageObj = [].concat(obj['@type'] || []).includes('ImageObject');
        const collectUrl = (u, preferred) => {
          let url = '';
          if (typeof u === 'string') url = u;
          else if (u && typeof u === 'object' && typeof u.url === 'string') url = u.url;
          if (!url) return;
          schemaImageUrls.add(url);
          if (preferred) {
            preferredImageUrls.add(abs(url));
            preferredImageUrlsNormalized.add(normalizeUrl(url));
          }
        };
        if (isImageObj && obj.url) collectUrl(obj.url, true);
        if (obj.image) [].concat(obj.image).forEach((u) => collectUrl(u, true));
        // Explicit "preferred image" declarations Google calls out.
        if (obj.primaryImageOfPage) [].concat(obj.primaryImageOfPage).forEach((u) => collectUrl(u, true));
        Object.values(obj).forEach((v) => typeof v === 'object' && visit(v));
      };
      visit(json);
    } catch (_e) {
      /* ignore malformed JSON-LD */
    }
  });
  // og:image (and Twitter card image) — the most common "preferred image" signal.
  document
    .querySelectorAll('meta[property="og:image"], meta[name="og:image"], meta[name="twitter:image"]')
    .forEach((m) => {
      const c = m.getAttribute('content');
      if (c) {
        preferredImageUrls.add(abs(c));
        preferredImageUrlsNormalized.add(normalizeUrl(c));
      }
    });
  const metaDescEl = document.querySelector('meta[name="description"]');
  const pageMetaDescription = metaDescEl ? (metaDescEl.getAttribute('content') || '').trim() : '';

  const hasVideo =
    !!document.querySelector('video') ||
    [...document.querySelectorAll('iframe')].some((f) =>
      /youtube|vimeo|video|player/i.test(f.src || '')
    );

  const allImgs = [...document.querySelectorAll('img')];

  const page = {
    page_title: document.title || '',
    page_h1: text(document.querySelector('h1')),
    page_meta_description: pageMetaDescription,
    page_word_count: pageWordCount,
    page_schema_types: [...new Set(schemaTypes)],
    page_has_video: hasVideo,
    page_has_preferred_image: preferredImageUrls.size > 0,
    image_count_total: allImgs.length,
  };

  // ---- per-image fields ----
  const docHeight = Math.max(
    document.body ? document.body.scrollHeight : 0,
    document.documentElement ? document.documentElement.scrollHeight : 0
  );
  const vh = window.innerHeight || 800;

  const STOCK_HOSTS = [
    'shutterstock',
    'getty',
    'istockphoto',
    'gettyimages',
    'unsplash',
    'pexels',
    'stock.adobe',
  ];
  const GENERIC_RE = /(image|photo|img|pic)[-_]?\d+|istock|gettyimages|^[0-9a-f]{8}-[0-9a-f]{4}/i;

  // Dedup by absolute URL WITHIN the page. Carousels / sliders (Swiper, Slick,
  // etc.) clone slide DOM nodes for infinite looping, so the same image src
  // appears in several <img> elements at once. Without dedup each clone became
  // its own row, inflating the page's image count and skewing the metadata
  // averages. Keep ONE row per unique URL, preferring the "real" slide: a
  // visible (non aria-hidden) occurrence with the richest metadata. First-seen
  // order is preserved so image_index stays stable top-to-bottom.
  const byUrl = new Map();
  let seq = 0;
  const richnessOf = (r, hidden) =>
    (hidden ? 0 : 1000) + // strongly prefer the visible slide over hidden clones
    (r.alt_text_present ? 100 : 0) +
    Math.min(r.alt_text_word_count, 50) +
    (r.caption_text ? 30 : 0) +
    (r.schema_image_match ? 20 : 0);

  for (const img of allImgs) {
    const rect = img.getBoundingClientRect();
    const w = Math.round(rect.width);
    const h = Math.round(rect.height);
    if (w < MIN_DIM || h < MIN_DIM) continue;

    let absUrl = '';
    try {
      absUrl = new URL(img.currentSrc || img.src, document.baseURI).href;
    } catch (_e) {
      absUrl = img.src || '';
    }
    const filename = (absUrl.split('?')[0].split('#')[0].split('/').pop() || '').toLowerCase();
    const extMatch = filename.match(/\.([a-z0-9]+)$/);
    const format = extMatch ? extMatch[1] : '';

    const altRaw = img.getAttribute('alt');
    const altPresent = img.hasAttribute('alt') && altRaw !== null && altRaw.trim() !== '';
    const altText = altRaw || '';

    const fig = img.closest('figure');
    const caption = fig ? text(fig.querySelector('figcaption')) : '';

    const para = precedingByTag(img, 'p');
    const paraText = para ? text(para).slice(0, 300) : '';

    const absTop = rect.top + window.scrollY;
    let position = 'in-article';
    if (absTop < vh) position = 'above-fold';
    else if (docHeight && absTop > docHeight - vh) position = 'below-fold';

    let host = '';
    try {
      host = new URL(absUrl).hostname.toLowerCase();
    } catch (_e) {
      /* relative or data URL */
    }
    const isStock = STOCK_HOSTS.some((s) => host.includes(s)) || GENERIC_RE.test(filename);
    const isDecorative = img.hasAttribute('alt') && altRaw !== null && altRaw.trim() === '';

    // Google-SEO signals (always captured; harmless to the brief rubric).
    const pictureEl = img.closest('picture');
    const hasPicture = !!(pictureEl && pictureEl.querySelector('source'));
    const hasSrcset = (img.getAttribute('srcset') || '').trim() !== '';
    const hasDimensionsAttr = img.hasAttribute('width') && img.hasAttribute('height');
    const naturalW = Number(img.naturalWidth) || 0;
    const naturalH = Number(img.naturalHeight) || 0;
    const preferredMatch = preferredImageUrls.has(absUrl) || preferredImageUrlsNormalized.has(normalizeUrl(absUrl));

    const row = {
      image_url: absUrl,
      image_filename: filename,
      image_format: format,
      image_width_px: w,
      image_height_px: h,
      natural_width_px: naturalW,
      natural_height_px: naturalH,
      alt_text: altText,
      alt_text_present: altPresent,
      alt_text_length: altText.length,
      alt_text_word_count: wordCount(altText),
      img_title: (img.getAttribute('title') || '').trim(),
      caption_text: caption,
      surrounding_h2: text(precedingByTag(img, 'h2')),
      surrounding_paragraph: paraText,
      page_position: position,
      image_index: 0, // assigned after dedup, in first-seen order
      is_decorative: isDecorative,
      is_likely_stock: isStock,
      schema_image_match: schemaImageUrls.has(absUrl),
      preferred_image_match: preferredMatch,
      has_srcset: hasSrcset,
      has_picture: hasPicture,
      has_dimensions_attr: hasDimensionsAttr,
    };

    // Don't collapse distinct empty/relative URLs into one another.
    const key = absUrl || `__nourl_${seq}`;
    const hidden = !!(img.closest && img.closest('[aria-hidden="true"]'));
    const score = richnessOf(row, hidden);
    const existing = byUrl.get(key);
    if (!existing) {
      byUrl.set(key, { row, seq: seq++, score });
    } else if (score > existing.score) {
      // A richer (or visible vs. cloned) occurrence of the same image wins, but
      // we keep its original first-seen position.
      existing.row = row;
      existing.score = score;
    }
  }

  const images = [...byUrl.values()]
    .sort((a, b) => a.seq - b.seq)
    .map((e, i) => {
      e.row.image_index = i + 1;
      return e.row;
    });

  return { page, images };
})();
