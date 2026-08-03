# Arch package release gate

`PKGBUILD.in` is intentionally not a floating package recipe. The final `PKGBUILD`
is rendered only after an immutable release source archive exists:

```sh
python3 scripts/render-arch-pkgbuild.py \
  --version 0.1.9 \
  --source-url https://github.com/fkr-0/properpcloud/archive/refs/tags/v0.1.9.tar.gz \
  --source-archive properpcloud-v0.1.9.tar.gz \
  --output packaging/arch/PKGBUILD
```

The renderer calculates the exact archive SHA-256 and refuses `SKIP`, non-HTTPS
sources, unstable version strings, symlinked archives, or unresolved placeholders.
The rendered recipe must then pass `makepkg --verifysource`, `makepkg --cleanbuild`,
package-content review, and a clean-container installation smoke. Until those steps
are retained in `docs/releases/0.1.9.yml`, Arch packaging remains an explicit external
gate rather than a release claim.
