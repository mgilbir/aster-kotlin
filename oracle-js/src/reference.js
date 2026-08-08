#!/usr/bin/env node
/**
 * Produces the reference file a Kotlin differential test compares against.
 *
 * Usage: node src/reference.js <spec.vg.json> <output.reference.json>
 *
 * The output is checked into `test-fixtures/reference/`, so JVM tests need neither Node nor a network
 * connection (PROJECT_BRIEF.md 21). Regenerating it is an explicit act — `scripts/oracle.sh` — and the
 * resulting diff has to be reviewed like a golden.
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileLoader, rootsFor } from './file-loader.js';
import * as vega from 'vega';
import { canonicalJson, canonicalNumber } from './canonical.js';
import { normalizeScales, normalizeScene } from './normalize.js';

const [specPath, outputPath] = process.argv.slice(2);

if (!specPath || !outputPath) {
  console.error('Usage: node src/reference.js <spec.vg.json> <output.reference.json>');
  process.exit(2);
}

const spec = JSON.parse(readFileSync(specPath, 'utf8'));

const view = new vega.View(vega.parse(spec), {
  renderer: 'none',
  loader: fileLoader(rootsFor(specPath)),
});
await view.runAsync();

const scaleNames = (spec.scales || []).map((s) => s.name);
const reference = {
  // Recorded so a mismatch in the pinned version is visible rather than mysterious.
  vegaVersion: vega.version,
  spec: specPath.split('/').pop(),
  // The rendered surface size, which under Vega's default `autosize: pad` is the content bounds plus
  // padding — not width/height plus padding, because axis labels hang outside the plotting area.
  size: surfaceSize(view),
  scales: normalizeScales(view, scaleNames),
  ...normalizeScene(view.scenegraph().root),
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, canonicalJson(reference));

await view.finalize();

function surfaceSize(view) {
  const padding = view.padding() || {};
  const left = padding.left || 0;
  const top = padding.top || 0;
  const right = padding.right || 0;
  const bottom = padding.bottom || 0;

  // `autosize: none` uses the declared size verbatim and lets the content overflow, so the frame
  // bounds say nothing about how large the surface is. Reading them anyway made a chart whose labels
  // overhang look bigger than it renders.
  const autosize = spec.autosize;
  const type = typeof autosize === 'string' ? autosize : autosize && autosize.type;
  if (type === 'none') {
    return { width: view.width() + left + right, height: view.height() + top + bottom };
  }

  const frame = view.scenegraph().root.items[0];
  const bounds = frame && frame.bounds;
  if (!bounds) {
    return { width: view.width() + left + right, height: view.height() + top + bottom };
  }
  return {
    width: canonicalNumber(bounds.x2 - bounds.x1 + left + right),
    height: canonicalNumber(bounds.y2 - bounds.y1 + top + bottom),
  };
}

console.log(`Wrote ${outputPath} (${reference.marks.length} marks)`);
