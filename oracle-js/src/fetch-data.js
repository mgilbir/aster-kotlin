#!/usr/bin/env node
/**
 * Makes sure every dataset a fixture names by relative URL is on disk.
 *
 * Usage: node src/fetch-data.js <fixtures-root>
 *
 * Fixtures are written the way Vega's own examples are — `"url": "data/barley.json"`, a path
 * relative to the corpus — and the file it names is checked in under `<fixtures-root>/data/`. This
 * fetches the ones that are missing, from the site the Vega project publishes its example data on,
 * and writes them where both engines will find them.
 *
 * It is a step of `scripts/oracle.sh` and nowhere else, on purpose. The JVM differential tests read
 * only from disk, so a fixture's data is committed alongside its reference and a green run never
 * depends on a network connection (CONTRIBUTING.md). Downloading is an explicit act whose result
 * is reviewed, exactly like regenerating a reference.
 *
 * Nothing is re-fetched: a file already on disk is left alone, so this is a no-op offline once the
 * corpus is complete.
 */

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

/** Where the Vega project serves the data its gallery examples refer to. */
const BASE = 'https://vega.github.io/vega/';

const root = process.argv[2];
if (!root) {
  console.error('Usage: node src/fetch-data.js <fixtures-root>');
  process.exit(2);
}

const specsDir = join(root, 'specs');
const wanted = new Set();

for (const name of readdirSync(specsDir).sort()) {
  if (!name.endsWith('.vg.json')) continue;
  const spec = JSON.parse(readFileSync(join(specsDir, name), 'utf8'));
  for (const url of dataUrls(spec)) {
    if (/^[a-z+.-]+:/i.test(url) || url.startsWith('//')) {
      console.error(`  ${name}: '${url}' is absolute; check it in by hand or make it relative`);
      process.exitCode = 1;
      continue;
    }
    wanted.add(url);
  }
}

let fetched = 0;
for (const url of [...wanted].sort()) {
  const target = join(root, url);
  if (existsSync(target)) continue;
  const from = BASE + url;
  process.stdout.write(`  fetching ${url} from ${from}\n`);
  const response = await fetch(from);
  if (!response.ok) {
    console.error(`  HTTP ${response.status} for ${from}`);
    process.exitCode = 1;
    continue;
  }
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, await response.text());
  fetched++;
}

console.log(
  fetched === 0
    ? `All ${wanted.size} fixture dataset(s) already on disk.`
    : `Fetched ${fetched} of ${wanted.size} fixture dataset(s); review and commit them.`,
);

/**
 * Every `url` a dataset definition names, at the top level or inside a group mark.
 *
 * Only `data[]` entries: a `url` on a mark's encode block is an *image*, which neither engine
 * fetches, and pulling those in would download pictures nothing reads.
 */
function dataUrls(spec) {
  const urls = [];
  const walk = (scope) => {
    for (const set of scope.data || []) if (typeof set.url === 'string') urls.push(set.url);
    for (const mark of scope.marks || []) walk(mark);
  };
  walk(spec);
  return urls;
}
