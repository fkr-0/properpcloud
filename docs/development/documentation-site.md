# Documentation site

## Source of truth

Markdown under `docs/`, the root `CHANGELOG.md`, and `VERSION` are authoritative. The website directory contains rendering configuration, not a second hand-maintained copy of the content.

`website/scripts/sync-docs.mjs` performs a clean synchronization before every build:

1. remove the generated Starlight content directory;
2. copy Markdown recursively;
3. rename `README.md` to `index.md` for clean section routes;
4. derive title and description frontmatter;
5. remove the copied top-level heading because Starlight renders the frontmatter title;
6. derive release/download placeholders from the latest dated changelog section;
7. publish the root changelog as the generated `/changelog/` page;
8. preserve edit links to each canonical Markdown source, including renamed `README.md` files;
9. copy the project logo into generated assets.

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

That command runs release-data unit tests, the sync step, Astro type/content validation, a static production build, and a generated-site smoke that checks the release header, direct binary links, unique landing-page title, and changelog route.

## Rendering model

Astro pre-renders the documentation to static HTML. This keeps the output compatible with GitHub Pages while retaining component-level server rendering during the build. No application server or runtime JavaScript framework is required to serve the manual.

## GitHub Pages workflow

The Pages workflow uses GitHub's artifact-based deployment path:

```text
checkout
  -> setup Node
  -> configure Pages
  -> npm ci
  -> npm run build
  -> upload Pages artifact
  -> deploy Pages
```

Deployment requires `pages: write` and `id-token: write`, and targets the protected `github-pages` environment.

Before the workflow can deploy for the first time, an administrator must open **Settings → Pages** and select **GitHub Actions** as the publishing source. The normal workflow token cannot enable Pages by itself. The custom domain is likewise configured in repository Pages settings; a `CNAME` file inside an artifact-based Actions deployment is not authoritative. `website/astro.config.mjs` must use the same public site URL.

## Link discipline

- Use relative Markdown links inside `docs/`.
- Do not link into generated `website/src/content/docs` paths.
- Avoid raw HTML when normal Markdown is sufficient. Semantic HTML is reserved for presentation that Markdown cannot express cleanly, such as the landing-page release/download cards.
- Keep headings unique within a page so generated anchors remain stable.
- Run `make docs-build` before committing renamed pages.
