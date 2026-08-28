// Evaluates Vega expressions with upstream Vega, to generate reference vectors for the Kotlin port.
// Reads newline-separated expressions on argv or stdin; prints "expr\tJSON result" or "expr\tERROR msg".
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';

// **Pinned, like every other script that reads upstream.** Without it `now()` answered the wall
// clock and `random()` drew from an unseeded generator, so a probe of anything built on either — a
// `datetime()` with no arguments, a `sample`, a force layout's jitter — answered differently every
// run. A probe exists to be quoted in a comment or turned into a fixture, and one that cannot be
// reproduced is worth neither. `reference.js` and `render.js` have called this since it existed.
pinDeterminism();

const expressions = process.argv.slice(2);

for (const expr of expressions) {
  let out;
  try {
    const view = new vega.View(
      vega.parse({ signals: [{ name: 'out', init: expr }] }),
      { renderer: 'none' },
    );
    await view.runAsync();
    out = JSON.stringify(view.signal('out')) ?? 'undefined';
    await view.finalize();
  } catch (e) {
    out = `ERROR ${e.message.split('\n')[0]}`;
  }
  console.log(`${expr}\t${out}`);
}
