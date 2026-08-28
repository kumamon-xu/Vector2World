import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../spike-viewer/package.json', import.meta.url));
const { validateBytes } = require('gltf-validator');

const inputs = process.argv.slice(2);
if (inputs.length === 0) {
  console.error('Usage: node scripts/validate-glb.mjs <file-or-directory> [...]');
  process.exit(2);
}

function collect(input) {
  const absolute = path.resolve(input);
  if (!fs.existsSync(absolute)) throw new Error(`Validation input does not exist: ${absolute}`);
  if (fs.statSync(absolute).isFile()) return absolute.toLowerCase().endsWith('.glb') ? [absolute] : [];
  return fs.readdirSync(absolute, { withFileTypes: true }).flatMap((entry) =>
    collect(path.join(absolute, entry.name)));
}

const files = [...new Set(inputs.flatMap(collect))].sort();
if (files.length === 0) {
  console.error('No GLB files found in the supplied validation inputs.');
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const report = await validateBytes(new Uint8Array(fs.readFileSync(file)), {
    uri: path.basename(file),
    maxIssues: 10_000,
    ignoredIssues: [],
    severityOverrides: {},
  });
  const issues = report.issues;
  const invalid = issues.truncated || issues.numErrors > 0 || issues.numWarnings > 0;
  console.log(JSON.stringify({
    file,
    validatorVersion: report.validatorVersion,
    valid: !invalid,
    issues,
  }));
  failed ||= invalid;
}

if (failed) process.exit(1);
