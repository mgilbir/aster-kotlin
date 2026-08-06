// Evaluates Vega expressions with upstream Vega, to generate reference vectors for the Kotlin port.
// Reads newline-separated expressions on argv or stdin; prints "expr\tJSON result" or "expr\tERROR msg".
import * as vega from 'vega';

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
