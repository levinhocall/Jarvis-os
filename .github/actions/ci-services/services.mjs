import fs from 'node:fs/promises';
import path from 'node:path';

const REPORT_ROOTS = Object.freeze({
  unit: ['test-results/testDebugUnitTest', 'reports/tests/testDebugUnitTest'],
  lint: ['reports/lint-results-debug.xml', 'reports/lint-results-debug.html', 'reports/lint-results-debug.txt'],
});
const MAX_FILES = 4096;
const MAX_BYTES = 50 * 1024 * 1024;
const REPORT_EXTENSIONS = new Set(['.xml', '.html', '.txt', '.css', '.js']);

/** Explicit build-report roots only; never archive arbitrary workspace paths or symlink targets. */
export async function collectReports(workspace, kind) {
  if (!Object.hasOwn(REPORT_ROOTS, kind)) throw new Error('Unsupported report kind');
  const root = path.resolve(workspace, 'Part 7/app/build');
  const files = [];
  let bytes = 0;
  async function visit(candidate) {
    let stat;
    try { stat = await fs.lstat(candidate); }
    catch (error) { if (error.code === 'ENOENT') return; throw error; }
    if (stat.isSymbolicLink()) throw new Error('Report symlinks are forbidden');
    if (stat.isDirectory()) {
      for (const name of (await fs.readdir(candidate)).sort()) await visit(path.join(candidate, name));
    } else if (stat.isFile() && REPORT_EXTENSIONS.has(path.extname(candidate))) {
      const actual = await fs.realpath(candidate);
      const actualRoot = await fs.realpath(root);
      if (!actual.startsWith(actualRoot + path.sep)) throw new Error('Report escaped build directory');
      bytes += stat.size;
      if (files.length >= MAX_FILES || bytes > MAX_BYTES) throw new Error('Report upload exceeds budget');
      files.push(actual);
    }
  }
  // Verify ancestors too: lstat of a final file alone would follow a symlinked parent.
  for (const relative of REPORT_ROOTS[kind]) {
    let ancestor = path.resolve(workspace);
    for (const piece of ['Part 7', 'app', 'build', ...relative.split('/')]) {
      ancestor = path.join(ancestor, piece);
      try {
        if ((await fs.lstat(ancestor)).isSymbolicLink()) throw new Error('Report ancestor symlink forbidden');
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    await visit(path.join(root, relative));
  }
  return { root, files, bytes };
}

export async function uploadReports({ workspace, kind, name, client, log = console.log }) {
  if (!/^[a-zA-Z0-9_.-]{1,100}$/.test(name)) throw new Error('Invalid artifact name');
  const report = await collectReports(workspace, kind);
  if (report.files.length === 0) {
    log('No report files were generated; no artifact uploaded. Inspect the Gradle failure above.');
    return { uploaded: false };
  }
  const response = await client.uploadArtifact(name, report.files, report.root, { retentionDays: 14 });
  if (!response.id) throw new Error('Artifact service did not return an upload ID');
  log(`Uploaded ${report.files.length} report files (${report.bytes} bytes); artifact ID ${response.id}.`);
  return { uploaded: true, id: response.id };
}

/** Cache is optional optimization; it cannot turn a failed Gradle check into a passing check. */
export async function dependencyCache({ mode, home, key, event, ref, client, log = console.log }) {
  if (!['restore-cache', 'save-cache'].includes(mode)) throw new Error('Unsupported cache operation');
  if (!/^jarvis-gradle-[a-zA-Z0-9_.-]{1,400}$/.test(key)) throw new Error('Invalid dependency cache key');
  if (mode === 'save-cache' && (event !== 'push' || ref !== 'refs/heads/main')) {
    log('Dependency cache save skipped: only successful main pushes may write.');
    return { status: 'skipped' };
  }
  const paths = [path.join(home, 'caches/modules-2'), path.join(home, 'wrapper/dists')];
  try {
    if (mode === 'restore-cache') {
      const hit = await client.restoreCache(paths, key);
      log(hit ? 'Gradle dependency cache restored.' : 'Gradle dependency cache miss.');
      return { status: hit ? 'hit' : 'miss' };
    }
    const id = await client.saveCache(paths, key);
    if (id < 0) {
      log('::warning::Dependency cache was not saved (service returned no cache ID).');
      return { status: 'unavailable' };
    }
    log(`Gradle dependency cache saved (ID ${id}).`);
    return { status: 'saved' };
  } catch {
    // Do not print SDK error messages/URLs, which can contain runtime credentials.
    log('::warning::Gradle dependency cache unavailable; verification continues without cache.');
    return { status: 'unavailable' };
  }
}
