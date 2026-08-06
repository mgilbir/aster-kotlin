#!/usr/bin/env node
/**
 * Compiles a Vega-Lite specification to Vega with the upstream compiler.
 *
 * Native Vega-Lite compilation is out of scope for the first release (PROJECT_BRIEF.md 3.1), so
 * Vega-Lite fixtures are compiled here and the Kotlin runtime consumes the resulting Vega JSON.
 *
 * Usage: node src/compile-vega-lite.js <input.vl.json> <output.vg.json>
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import * as vegaLite from 'vega-lite';

const [inputPath, outputPath] = process.argv.slice(2);

if (!inputPath || !outputPath) {
  console.error('Usage: node src/compile-vega-lite.js <input.vl.json> <output.vg.json>');
  process.exit(2);
}

const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const { spec, normalized } = vegaLite.compile(input);

if (!normalized) {
  console.error('vega-lite.compile returned no normalized specification');
  process.exit(1);
}

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(spec, null, 2)}\n`);

console.log(`Compiled ${inputPath} -> ${outputPath}`);
