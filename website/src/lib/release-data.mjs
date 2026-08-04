import { access, readFile } from 'node:fs/promises';
import path from 'node:path';

const repositoryUrl = 'https://github.com/fkr-0/properpcloud';

async function discoverRepositoryRoot(start = process.env.PROPERPCLOUD_REPOSITORY_ROOT || process.cwd()) {
  let candidate = path.resolve(start);
  while (true) {
    try {
      await Promise.all([
        access(path.join(candidate, 'VERSION')),
        access(path.join(candidate, 'CHANGELOG.md')),
      ]);
      return candidate;
    } catch {
      const parent = path.dirname(candidate);
      if (parent === candidate) break;
      candidate = parent;
    }
  }
  throw new Error(`Could not locate properpcloud VERSION and CHANGELOG.md above ${start}`);
}

const releaseHeading = /^## \[([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)\] - (\d{4}-\d{2}-\d{2})$/gm;

export async function readReleaseData(repositoryRoot) {
  const root = repositoryRoot ? path.resolve(repositoryRoot) : await discoverRepositoryRoot();
  const [workingVersionRaw, changelog] = await Promise.all([
    readFile(path.join(root, 'VERSION'), 'utf8'),
    readFile(path.join(root, 'CHANGELOG.md'), 'utf8'),
  ]);
  const workingVersion = workingVersionRaw.trim();
  const releases = Array.from(changelog.matchAll(releaseHeading), ([, version, date]) => ({
    version,
    tag: `v${version}`,
    date,
    releaseUrl: `${repositoryUrl}/releases/tag/v${version}`,
  }));

  if (!releases.length) {
    throw new Error('CHANGELOG.md has no dated semantic release section');
  }

  const latest = releases[0];
  const downloadBase = `${repositoryUrl}/releases/download/${latest.tag}`;

  return {
    repositoryUrl,
    workingVersion,
    version: latest.version,
    tag: latest.tag,
    date: latest.date,
    releaseUrl: latest.releaseUrl,
    latestReleaseUrl: `${repositoryUrl}/releases/latest`,
    allReleasesUrl: `${repositoryUrl}/releases`,
    checksumsUrl: `${downloadBase}/SHA256SUMS`,
    evidenceUrl: `${downloadBase}/release-evidence.json`,
    apkUrl: `${downloadBase}/properpcloud-${latest.version}-demo-debug.apk`,
    appImageUrl: `${downloadBase}/properpcloud-${latest.version}-x86_64.AppImage`,
    flatpakUrl: `${downloadBase}/properpcloud-${latest.version}-x86_64.flatpak`,
    sourceArchiveUrl: `${repositoryUrl}/archive/refs/tags/${latest.tag}.tar.gz`,
    archRecipeUrl: `${repositoryUrl}/tree/main/packaging/arch`,
    releases,
  };
}

export function recentReleasesMarkdown(release, limit = 4) {
  const rows = release.releases.slice(0, limit).map(({ version, date, releaseUrl }, index) => {
    const status = index === 0 ? 'Latest stable release' : 'Previous release';
    return `| [v${version}](${releaseUrl}) | ${date} | ${status} |`;
  });
  return [
    '| Version | Published | Status |',
    '| --- | --- | --- |',
    ...rows,
  ].join('\n');
}

export function replaceReleaseTokens(markdown, release) {
  const replacements = new Map([
    ['{{LATEST_RELEASE_VERSION}}', release.version],
    ['{{LATEST_RELEASE_TAG}}', release.tag],
    ['{{LATEST_RELEASE_DATE}}', release.date],
    ['{{LATEST_RELEASE_URL}}', release.releaseUrl],
    ['{{LATEST_RELEASES_URL}}', release.allReleasesUrl],
    ['{{LATEST_APK_URL}}', release.apkUrl],
    ['{{LATEST_APPIMAGE_URL}}', release.appImageUrl],
    ['{{LATEST_FLATPAK_URL}}', release.flatpakUrl],
    ['{{LATEST_CHECKSUMS_URL}}', release.checksumsUrl],
    ['{{LATEST_EVIDENCE_URL}}', release.evidenceUrl],
    ['{{LATEST_SOURCE_ARCHIVE_URL}}', release.sourceArchiveUrl],
    ['{{ARCH_RECIPE_URL}}', release.archRecipeUrl],
    ['{{RECENT_RELEASES_TABLE}}', recentReleasesMarkdown(release)],
  ]);

  let rendered = markdown;
  for (const [token, value] of replacements) rendered = rendered.replaceAll(token, value);

  const unresolved = rendered.match(/\{\{[A-Z0-9_]+\}\}/g);
  if (unresolved) {
    throw new Error(`Unresolved documentation release token(s): ${[...new Set(unresolved)].join(', ')}`);
  }
  return rendered;
}
