import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  readReleaseData,
  recentReleasesMarkdown,
  replaceReleaseTokens,
} from '../src/lib/release-data.mjs';

async function fixture(t, changelog) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'properpcloud-release-data-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(path.join(root, 'VERSION'), '0.2.0\n', 'utf8');
  await writeFile(path.join(root, 'CHANGELOG.md'), changelog, 'utf8');
  return root;
}

test('derives the latest published release and every binary URL from canonical files', async (t) => {
  const root = await fixture(t, `# Changelog

## [Unreleased]

## [0.1.9] - 2026-08-03

## [0.1.8] - 2026-08-02
`);
  const release = await readReleaseData(root);

  assert.equal(release.workingVersion, '0.2.0');
  assert.equal(release.version, '0.1.9');
  assert.equal(release.tag, 'v0.1.9');
  assert.equal(release.date, '2026-08-03');
  assert.match(release.apkUrl, /properpcloud-0\.1\.9-demo-debug\.apk$/);
  assert.match(release.appImageUrl, /properpcloud-0\.1\.9-x86_64\.AppImage$/);
  assert.match(release.flatpakUrl, /properpcloud-0\.1\.9-x86_64\.flatpak$/);
  assert.equal(release.releases.length, 2);
});

test('renders release tokens and a recent-version table', async (t) => {
  const root = await fixture(t, `# Changelog

## [0.1.9] - 2026-08-03

## [0.1.8] - 2026-08-02
`);
  const release = await readReleaseData(root);
  const rendered = replaceReleaseTokens(
    'Download {{LATEST_RELEASE_TAG}} at {{LATEST_RELEASE_URL}}.\n\n{{RECENT_RELEASES_TABLE}}',
    release,
  );

  assert.match(rendered, /Download v0\.1\.9/);
  assert.match(rendered, /releases\/tag\/v0\.1\.9/);
  assert.match(recentReleasesMarkdown(release), /\| \[v0\.1\.8\]/);
});

test('fails closed on unknown release tokens or absent dated releases', async (t) => {
  const root = await fixture(t, '# Changelog\n\n## [Unreleased]\n');
  await assert.rejects(readReleaseData(root), /no dated semantic release section/);

  const validRoot = await fixture(t, '# Changelog\n\n## [0.1.9] - 2026-08-03\n');
  const release = await readReleaseData(validRoot);
  assert.throws(
    () => replaceReleaseTokens('{{UNKNOWN_RELEASE_VALUE}}', release),
    /Unresolved documentation release token/,
  );
});
