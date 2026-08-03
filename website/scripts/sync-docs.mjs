import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = path.resolve(websiteRoot, '..');
const sourceRoot = path.join(repositoryRoot, 'docs');
const targetRoot = path.join(websiteRoot, 'src', 'content', 'docs');

await rm(targetRoot, { recursive: true, force: true });
await mkdir(targetRoot, { recursive: true });

async function copyDirectory(sourceDirectory, targetDirectory) {
  await mkdir(targetDirectory, { recursive: true });
  for (const entry of await readdir(sourceDirectory, { withFileTypes: true })) {
    if (entry.name === 'assets' || entry.name.startsWith('.')) continue;
    const source = path.join(sourceDirectory, entry.name);
    if (entry.isDirectory()) {
      await copyDirectory(source, path.join(targetDirectory, entry.name));
      continue;
    }
    if (!entry.isFile() || !entry.name.endsWith('.md')) continue;
    const outputName = entry.name.toLowerCase() === 'readme.md' ? 'index.md' : entry.name;
    const target = path.join(targetDirectory, outputName);
    const markdown = await readFile(source, 'utf8');
    const title = markdown.match(/^#\s+(.+)$/m)?.[1]?.trim() ?? path.basename(entry.name, '.md');
    const description = markdown
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line && !line.startsWith('#') && !line.startsWith('```') && !line.startsWith('---'))
      ?.replace(/["\\]/g, '')
      .slice(0, 180) ?? `properpcloud documentation: ${title}`;
    const frontmatter = markdown.startsWith('---\n')
      ? ''
      : `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(description)}\n---\n\n`;
    await writeFile(target, frontmatter + markdown, 'utf8');
  }
}

await copyDirectory(sourceRoot, targetRoot);
await mkdir(path.join(websiteRoot, 'src', 'assets'), { recursive: true });
await mkdir(path.join(websiteRoot, 'public'), { recursive: true });
await rm(path.join(websiteRoot, 'public', 'CNAME'), { force: true });
await cp(path.join(sourceRoot, 'assets', 'logo.png'), path.join(websiteRoot, 'src', 'assets', 'logo.png'));
await cp(path.join(sourceRoot, 'assets', 'logo.png'), path.join(websiteRoot, 'public', 'favicon.png'));

console.log(`Synced Markdown documentation from ${sourceRoot} to ${targetRoot}`);
