/**
 * Compiles **every example Vega-Lite ships** with upstream's own compiler.
 *
 * The gallery sweep's upstream half. Compiling is a pure function of the specification — no data is
 * fetched and none is needed — so all 627 of the examples in the pinned release can be put through
 * both compilers and the outputs compared property by property, without a single byte of the
 * datasets they name.
 *
 * **One process for all of them**, not one per file. `scripts/vega-lite-oracle.sh` spawns a `node`
 * per fixture, which is fine for 283 and is two to four minutes of interpreter startup for 627.
 *
 * An example upstream itself refuses is recorded as `failed` rather than dropped: the sweep's whole
 * value is that nothing goes missing quietly, and an example that stops compiling upstream after a
 * version bump is exactly the kind of thing a silent skip would hide.
 *
 * Usage: node src/vega-lite-gallery.js <examples-dir> <output-dir>
 */
import fs from 'fs';
import path from 'path';
import * as vegaLite from 'vega-lite';

const [examplesDir, outputDir] = process.argv.slice(2);
if (!examplesDir || !outputDir) {
  console.error('Usage: node src/vega-lite-gallery.js <examples-dir> <output-dir>');
  process.exit(2);
}

fs.mkdirSync(outputDir, { recursive: true });

const names = fs
  .readdirSync(examplesDir)
  .filter((f) => f.endsWith('.vl.json'))
  .sort();

const failed = [];
let written = 0;

for (const file of names) {
  const name = file.slice(0, -'.vl.json'.length);
  let input;
  try {
    input = JSON.parse(fs.readFileSync(path.join(examplesDir, file), 'utf8'));
  } catch (error) {
    failed.push({ name, stage: 'parse', message: String(error.message || error) });
    continue;
  }
  try {
    const { spec } = vegaLite.compile(input);
    fs.writeFileSync(
      path.join(outputDir, `${name}.vega.json`),
      JSON.stringify(spec, null, 2) + '\n'
    );
    written += 1;
  } catch (error) {
    failed.push({ name, stage: 'compile', message: String(error.message || error) });
  }
}

// The manifest is what the Kotlin side reads, so it cannot compare a set different from the one
// upstream actually produced.
fs.writeFileSync(
  path.join(outputDir, 'manifest.json'),
  JSON.stringify(
    {
      vegaLiteVersion: vegaLite.version,
      examples: names.length,
      compiled: written,
      failedUpstream: failed,
    },
    null,
    2
  ) + '\n'
);

console.log(
  `Compiled ${written} of ${names.length} example(s) with upstream Vega-Lite ${vegaLite.version}` +
    (failed.length ? `; ${failed.length} upstream refused` : '')
);
for (const f of failed) console.log(`  upstream refused ${f.name} (${f.stage}): ${f.message}`);
