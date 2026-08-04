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

The executable gate performs the complete immutable-source path in an isolated Arch
container:

```sh
make arch-package-gate
```

It downloads the current `v$(cat VERSION)` archive over verified HTTPS, renders the
recipe, runs `makepkg --verifysource` and `makepkg --cleanbuild`, installs the resulting
package, verifies the license inventory, and runs the installed `properpcloud --smoke`.
The package digest and results are retained under `build/evidence/` and generated package
bytes remain ignored under `build/arch-gate/`.

The gate uses a reusable Gradle dependency cache but never reuses source, package, or
installation roots. A public AUR submission remains a separate publication action and is
not implied by a successful local clean build.
