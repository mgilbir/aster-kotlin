#!/usr/bin/env node
/**
 * Produces the reference file a Kotlin differential test compares against.
 *
 * Usage: node src/reference.js <spec.vg.json> <output.reference.json>
 *
 * The output is checked into `test-fixtures/reference/`, so JVM tests need neither Node nor a network
 * connection (CONTRIBUTING.md). Regenerating it is an explicit act — `scripts/oracle.sh` — and the
 * resulting diff has to be reviewed like a golden.
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileLoader, rootsFor } from './file-loader.js';
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';
import { canonicalJson, canonicalNumber } from './canonical.js';
import {
  normalizeNestedScales,
  normalizeScales,
  normalizeScene,
  surfaceSize,
} from './normalize.js';

const [specPath, outputPath] = process.argv.slice(2);

if (!specPath || !outputPath) {
  console.error('Usage: node src/reference.js <spec.vg.json> <output.reference.json>');
  process.exit(2);
}

const spec = JSON.parse(readFileSync(specPath, 'utf8'));

// Seed the generator and stop the clock before anything parses; see determinism.js.
pinDeterminism();

const view = new vega.View(vega.parse(spec), {
  renderer: 'none',
  loader: fileLoader(rootsFor(specPath)),
});

// A view **logs** a run-time error and carries on with a half-built scene: the operator that threw
// produced nothing, everything downstream of it never ran, and what is left is whatever had been
// built by then — marks with no bounds, marks that do not exist. That is a snapshot of a crash, not
// a picture to be agreed with, so it is reported here and the caller records the specification as
// one there is no reference for. A parse failure already throws; this is the other half.
let failure = null;
view.error = (error) => {
  failure = error;
};
await view.runAsync();
if (failure) {
  console.error(`${specPath}: the view reported an error while running: ${failure.message ?? failure}`);
  process.exit(3);
}

const scaleNames = (spec.scales || []).map((s) => s.name);
const reference = {
  // Recorded so a mismatch in the pinned version is visible rather than mysterious.
  vegaVersion: vega.version,
  spec: specPath.split('/').pop(),
  // The rendered surface size, which under Vega's default `autosize: pad` is the content bounds plus
  // padding — not width/height plus padding, because axis labels hang outside the plotting area.
  size: surfaceSize(view, spec),
  scales: normalizeScales(view, scaleNames),
  // Only when there is something to record, so the 199 committed references do not all gain an
  // empty key. Most charts declare no scale inside a group.
  ...(() => {
    const nested = normalizeNestedScales(view, spec);
    return Object.keys(nested).length ? { nestedScales: nested } : {};
  })(),
  ...normalizeScene(view.scenegraph().root),
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, canonicalJson(reference));

await view.finalize();


console.log(`Wrote ${outputPath} (${reference.marks.length} marks)`);
