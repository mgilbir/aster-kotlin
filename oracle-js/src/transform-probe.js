// Runs a data transform pipeline through upstream Vega and prints the resulting dataset,
// to generate reference vectors for the Kotlin transforms.
//
// Each run gets a deep copy of the input: Vega's transforms mutate the tuples they are given, so
// sharing one array between runs silently contaminates every result after the first.
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';

// Pinned for the reason `eval-probe.js` is: `sample`, `dotbin` and the force layouts all draw from
// the module-level generator, so an unpinned probe of one answers differently every run.
pinDeterminism();

const BASE = [
  { c: 'a', g: 'x', v: 1 },
  { c: 'b', g: 'x', v: 4 },
  { c: 'c', g: 'y', v: 9 },
  { c: 'd', g: 'y', v: 16 },
  { c: 'e', g: 'y', v: null },
];

export async function run(label, transform, data = BASE) {
  const values = JSON.parse(JSON.stringify(data));
  const spec = { width: 100, height: 50, data: [{ name: 't', values, transform }] };
  try {
    const view = new vega.View(vega.parse(spec), { renderer: 'none' });
    await view.runAsync();
    const out = view.data('t').map((d) => {
      const copy = {};
      for (const k of Object.keys(d).sort()) if (!k.startsWith('_')) copy[k] = d[k];
      return copy;
    });
    console.log(`${label}\n  ${JSON.stringify(out)}`);
    await view.finalize();
  } catch (e) {
    console.log(`${label}\n  ERROR ${e.message.split('\n')[0]}`);
  }
}
