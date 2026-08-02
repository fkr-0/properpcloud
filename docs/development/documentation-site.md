# Documentation site

## Source of truth

Markdown under `docs/` is authoritative. The website directory contains rendering configuration, not a second hand-maintained copy of the content.

`website/scripts/sync-docs.mjs` performs a clean synchronization before every build:

1. remove the generated Starlight content directory;
2. copy Markdown recursively;
3. rename `README.md` to `index.md` for clean section routes;
4. derive title and description frontmatter when absent;
5. copy the project logo and custom-domain `CNAME` into generated assets.

## Local development

```bash
cd website
npm ci
npm run dev
```

The production-equivalent check is:

```bash
make docs-build
```

That command runs the sync step, Astro type/content validation, and a static production build.

## Rendering model

Astro pre-renders the documentation to static HTML. This keeps the output compatible with GitHub Pages while retaining component-level server rendering during the build. No application server or runtime JavaScript framework is required to serve the manual.

## GitHub Pages workflow

The Pages workflow uses GitHub's artifact-based deployment path:

```text
checkout
  -> setup Node
  -> npm ci
  -> npm run build
  -> configure Pages
  -> upload Pages artifact
  -> deploy Pages
```

Deployment requires `pages: write` and `id-token: write`, and targets the protected `github-pages` environment. The custom domain is included in the artifact and configured through the repository Pages API.

## Link discipline

- Use relative Markdown links inside `docs/`.
- Do not link into generated `website/src/content/docs` paths.
- Avoid raw HTML when normal Markdown is sufficient.
- Keep headings unique within a page so generated anchors remain stable.
- Run `make docs-build` before committing renamed pages.
