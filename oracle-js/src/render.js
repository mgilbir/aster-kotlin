#!/usr/bin/env node
/**
 * Renders a Vega specification with upstream Vega and writes canonical reference output.
 *
 * Usage: node src/render.js <spec.vg.json> <output-prefix>
 *
 * Produces `<output-prefix>.scene.json` (the summarized scenegraph) and `<output-prefix>.svg`.
 * Nothing here touches the network: the specification must inline its data or point at a local file.
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileLoader, rootsFor } from './file-loader.js';
import * as vega from 'vega';
import { canonicalJson, canonicalSvg, summarizeScene } from './canonical.js';

const [specPath, outputPrefix] = process.argv.slice(2);

if (!specPath || !outputPrefix) {
  console.error('Usage: node src/render.js <spec.vg.json> <output-prefix>');
  process.exit(2);
}

const spec = JSON.parse(readFileSync(specPath, 'utf8'));

const view = new vega.View(vega.parse(spec), {
  renderer: 'none',
  // Tests must not hit the network (PROJECT_BRIEF.md 21).
  loader: fileLoader(rootsFor(specPath)),
});

const svg = await view.toSVG();
const scene = summarizeScene(view.scenegraph().root);

mkdirSync(dirname(outputPrefix), { recursive: true });
writeFileSync(`${outputPrefix}.scene.json`, canonicalJson(scene));
writeFileSync(`${outputPrefix}.svg`, canonicalSvg(svg));

await view.finalize();

console.log(`Wrote ${outputPrefix}.scene.json and ${outputPrefix}.svg`);
