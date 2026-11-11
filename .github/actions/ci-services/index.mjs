import path from 'node:path';
import os from 'node:os';
import { dependencyCache, uploadReports } from './services.mjs';

try {
  const mode = process.env.INPUT_MODE;
  if (mode === 'reports') {
    const { DefaultArtifactClient } = await import('@actions/artifact');
    await uploadReports({
      workspace: process.env.GITHUB_WORKSPACE,
      kind: process.env.INPUT_KIND || 'unit',
      name: process.env.INPUT_NAME || 'verification-reports',
      client: new DefaultArtifactClient(),
    });
  } else {
    await dependencyCache({
      mode,
      home: process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle'),
      key: process.env.INPUT_KEY,
      event: process.env.GITHUB_EVENT_NAME,
      ref: process.env.GITHUB_REF,
      client: await import('@actions/cache'),
    });
  }
} catch {
  console.error('CI report/cache action failed. No upload success is claimed; inspect the preceding step.');
  process.exitCode = 1;
}
