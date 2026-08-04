import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { readReleaseData } from '../src/lib/release-data.mjs';

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const release = await readReleaseData();
const home = await readFile(path.join(websiteRoot, 'dist', 'index.html'), 'utf8');
const changelog = await readFile(path.join(websiteRoot, 'dist', 'changelog', 'index.html'), 'utf8');

assert.ok(home.includes(`>${release.tag}</span>`), 'header does not display the latest release tag');
assert.ok(home.includes(release.apkUrl), 'landing page does not link the current Android APK');
assert.ok(home.includes(release.appImageUrl), 'landing page does not link the current AppImage');
assert.ok(home.includes(release.flatpakUrl), 'landing page does not link the current Flatpak');
assert.equal(
  (home.match(/<h1[^>]*>properpcloud documentation<\/h1>/g) || []).length,
  1,
  'landing page title must render exactly once',
);
assert.ok(changelog.includes(release.version), 'generated changelog omits the latest release');
assert.ok(changelog.includes(release.releaseUrl), 'generated changelog omits the latest release link');
assert.ok(
  changelog.includes(`${release.repositoryUrl}/edit/main/CHANGELOG.md`),
  'generated changelog edit link does not target the canonical root file',
);

console.log(`Documentation smoke: ${release.tag}, downloads, header, and changelog verified`);
