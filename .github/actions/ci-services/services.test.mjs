import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { collectReports, uploadReports, dependencyCache } from './services.mjs';

async function fixture(t) {
  const workspace = await fs.mkdtemp(path.join(os.tmpdir(), 'jarvis-ci-'));
  t.after(() => fs.rm(workspace, { recursive: true, force: true }));
  const root = path.join(workspace, 'Part 7/app/build');
  const reports = path.join(root, 'test-results/testDebugUnitTest');
  await fs.mkdir(reports, { recursive: true });
  return { workspace, root, reports };
}

test('upload only approved reports and wait for a real service ID', async t => {
  const { workspace, root, reports } = await fixture(t);
  await fs.writeFile(path.join(reports, 'TEST-demo.xml'), '<testsuite/>');
  await fs.writeFile(path.join(reports, '.env'), 'never-upload');
  await fs.writeFile(path.join(root, 'private.db'), 'never-upload');
  const calls = [];
  const result = await uploadReports({ workspace, kind: 'unit', name: 'unit-reports', log() {},
    client: { async uploadArtifact(...args) { calls.push(args); return { id: 42 }; } } });
  assert.deepEqual(result, { uploaded: true, id: 42 });
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0][1], [path.join(reports, 'TEST-demo.xml')]);
  assert.equal(calls[0][2], root);
});

test('missing reports do not claim successful upload', async t => {
  const { workspace } = await fixture(t);
  let called = false;
  const result = await uploadReports({ workspace, kind: 'lint', name: 'lint', log() {},
    client: { async uploadArtifact() { called = true; } } });
  assert.equal(result.uploaded, false);
  assert.equal(called, false);
});

test('artifact failure propagates instead of reporting success', async t => {
  const { workspace, reports } = await fixture(t);
  await fs.writeFile(path.join(reports, 'TEST-demo.xml'), '<testsuite/>');
  await assert.rejects(uploadReports({ workspace, kind: 'unit', name: 'unit', log() {},
    client: { async uploadArtifact() { throw new Error('service unavailable'); } } }));
  await assert.rejects(uploadReports({ workspace, kind: 'unit', name: '../escape', client: {} }));
});

test('report kind and file symlinks cannot escape into private data', async t => {
  const { workspace, reports } = await fixture(t);
  const privateFile = path.join(workspace, 'secret.xml');
  await fs.writeFile(privateFile, 'never-upload');
  await fs.symlink(privateFile, path.join(reports, 'TEST-symlink.xml'));
  await assert.rejects(collectReports(workspace, 'unit'), /symlink/);
  await assert.rejects(collectReports(workspace, '../../'), /Unsupported/);
});

test('symlinked report ancestors are rejected', async t => {
  const { workspace, reports } = await fixture(t);
  const outside = path.join(workspace, 'outside');
  await fs.mkdir(outside);
  await fs.writeFile(path.join(outside, 'TEST-private.xml'), 'never-upload');
  await fs.rm(reports, { recursive: true });
  await fs.symlink(outside, reports);
  await assert.rejects(collectReports(workspace, 'unit'), /symlink/);
});

test('oversized report fails before uploading any bytes', async t => {
  const { workspace, reports } = await fixture(t);
  const file = await fs.open(path.join(reports, 'TEST-large.xml'), 'w');
  await file.truncate(50 * 1024 * 1024 + 1);
  await file.close();
  await assert.rejects(collectReports(workspace, 'unit'), /budget/);
});

test('PR cache saves are refused, restores use only dependency and wrapper paths', async () => {
  let saved = false;
  const calls = [];
  const client = { async saveCache() { saved = true; return 1; },
    async restoreCache(...args) { calls.push(args); return undefined; } };
  const input = { home: '/tmp/gradle', key: 'jarvis-gradle-Linux-abc', event: 'pull_request', ref: 'refs/pull/1/merge', client, log() {} };
  assert.equal((await dependencyCache({ ...input, mode: 'save-cache' })).status, 'skipped');
  assert.equal(saved, false);
  assert.equal((await dependencyCache({ ...input, mode: 'restore-cache' })).status, 'miss');
  assert.deepEqual(calls[0][0], ['/tmp/gradle/caches/modules-2', '/tmp/gradle/wrapper/dists']);
});

test('cache failures stay visible and never print credential-bearing error text', async () => {
  const messages = [];
  const result = await dependencyCache({ mode: 'restore-cache', home: '/tmp/gradle', key: 'jarvis-gradle-Linux-abc',
    client: { async restoreCache() { throw new Error('private-runtime-token'); } }, log: text => messages.push(text) });
  assert.equal(result.status, 'unavailable');
  assert.match(messages[0], /warning/);
  assert.ok(!messages.join('').includes('private-runtime-token'));
});
