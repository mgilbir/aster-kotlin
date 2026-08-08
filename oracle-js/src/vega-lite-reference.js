#!/usr/bin/env node
/**
 * Compiles a Vega-Lite fixture with upstream's compiler and writes the Vega it produces.
 *
 * That file is the reference the Kotlin compiler is compared against property by property, so a rule
 * that drifts from upstream's is caught where it happened rather than as a wrong picture several
 * layers later. `scripts/vega-lite-oracle.sh` then renders the same file through `src/reference.js`,
 * which gives the second reference: the scene it actually draws.
 *
 * Usage: node src/vega-lite-reference.js <input.vl.json> <output.vega.json>
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import * as vegaLite from 'vega-lite';

const [inputPath, outputPath] = process.argv.slice(2);

if (!inputPath || !outputPath) {
  console.error('Usage: node src/vega-lite-reference.js <input.vl.json> <output.vega.json>');
  process.exit(2);
}

const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const { spec } = vegaLite.compile(input);

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(spec, null, 2)}\n`);

console.log(`Compiled ${inputPath} -> ${outputPath}`);
