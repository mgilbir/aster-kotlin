// Records what Vega's own test suite asks of Vega, as differential vectors.
//
// Vega has ~3,700 assertions across ~30 packages, and they are the most carefully chosen inputs
// anyone has produced for this grammar. Transcribing their *expectations* into Kotlin would be a
// large, error-prone copy — and would inherit assertions that are deliberately loose (`t.ok(x > 0)`)
// where an exact value is what a differential port needs. So this does not read their assertions at
// all. It runs their test files against the **installed** Vega — the same 6.3.1 every reference in
// this repository is generated from — with two pieces of scaffolding:
//
//   1. a `tape` shim, so a test body runs without the real runner and without its assertions
//      mattering; and
//   2. a recording proxy around the package's exports, so every call the test makes is captured with
//      its arguments and with upstream's actual answer.
//
// What comes out is a vector file: input, and what Vega really returns. The Kotlin side replays it.
//
// Usage:  node src/record-upstream-tests.mjs <vega-source-checkout> <package> [package...]
// Writes: test-fixtures/upstream-vectors/<package>.json
import {readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync, rmSync} from 'fs';
import {basename, dirname, join, resolve} from 'path';
import {fileURLToPath, pathToFileURL} from 'url';
import {parse} from 'acorn';

// Pinned before anything reads a clock or builds a Date. A `local` time unit is *local*, so the
// recorded answers depend on the zone the recorder ran in — the same reason `scripts/oracle.sh`
// exports it for the reference run, and the same zone, so a vector recorded here is comparable with
// a Kotlin test running under `TEST_TIME_ZONE`.
process.env.TZ = process.env.TZ || 'Europe/Amsterdam';

/**
 * The same pinning `scripts/oracle.sh` applies to a reference run, for the same reason.
 *
 * A recorded vector is a checked-in artifact, so regenerating it must produce the same bytes or an
 * upgrade arrives buried in noise. Upstream's own tests build data with `Math.random` — one `sample`
 * test moved 3,216 lines between two runs — and read `Date.now`.
 *
 * **Order matters here and cost an hour.** The constants are inlined rather than imported from
 * `determinism.js`, because importing that module imports *vega*, and vega captures `Math.random`
 * into its own module-level generator as it loads. Overriding afterwards pins the tests' own calls
 * and leaves the `sample` transform drawing from the real one. Nothing may be imported before these
 * two lines.
 *
 * The values match `determinism.js`, so a vector and a fixture reference agree about what "random"
 * means: `RandomStream.DEFAULT_SEED` and `Clock.PINNED` on the Kotlin side.
 */
const SEED = 42;
const NOW = 1767225600000;
let seedState = SEED;
Math.random = () => {
  // Upstream's own `randomLCG`, inlined for the same reason the constants are.
  seedState = (seedState * 1664525 + 1013904223) % 4294967296;
  return seedState / 4294967296;
};
Date.now = () => NOW;

const here = dirname(fileURLToPath(import.meta.url));
const outputDir = resolve(here, '../../test-fixtures/upstream-vectors');

/**
 * A value as JSON, or a marker saying why it is not.
 *
 * Recording "undefined" as null would be a lie a Kotlin test could not tell from a real null, so
 * everything unrepresentable is tagged instead and the replay skips it — visibly.
 */
function encode(value, seen = new Set()) {
  if (value === undefined) return {$: 'undefined'};
  if (value === null) return null;
  if (typeof value === 'number') {
    if (Number.isNaN(value)) return {$: 'NaN'};
    if (value === Infinity) return {$: 'Infinity'};
    if (value === -Infinity) return {$: '-Infinity'};
    return value;
  }
  if (typeof value === 'bigint') return {$: 'bigint', value: value.toString()};
  if (typeof value === 'function') {
    // A Vega **accessor** is a function that carries the field path it reads, and a transform's
    // parameters are full of them — `{field: field('v')}`. Recorded as an opaque function they would
    // make every transform vector unreplayable; recorded by their `fields` they are exactly what a
    // Kotlin adapter needs.
    // A **comparator** carries `fields` as well, so `fields` alone cannot tell one from an
    // accessor — and reducing `compare(['count'], ['descending'])` to the string "count" silently
    // drops the direction, which is how a sorted `collect` looked like an unsorted one.
    if (Array.isArray(value.fields) && Array.isArray(value.orders)) {
      return {$: 'comparator', fields: value.fields, orders: value.orders};
    }
    if (Array.isArray(value.fields)) return {$: 'accessor', fields: value.fields};
    return {$: 'function', name: value.name || '(anonymous)'};
  }
  if (typeof value === 'symbol') return {$: 'symbol'};
  if (value instanceof Date) return {$: 'date', epochMillis: value.getTime()};
  if (value instanceof RegExp) return {$: 'regexp', source: value.source, flags: value.flags};
  if (typeof value !== 'object') return value;
  if (seen.has(value)) return {$: 'circular'};
  seen.add(value);
  try {
    if (Array.isArray(value)) return value.map(v => encode(v, seen));
    const out = {};
    for (const key of Object.keys(value)) out[key] = encode(value[key], seen);
    return out;
  } finally {
    seen.delete(value);
  }
}

/** Wraps every exported function so a call records itself. Values pass through untouched. */
function recordExports(moduleNamespace, packageName, calls) {
  const wrapped = {};
  for (const name of Object.keys(moduleNamespace)) {
    const value = moduleNamespace[name];
    if (typeof value !== 'function') {
      wrapped[name] = value;
      continue;
    }
    // A Proxy rather than a wrapper function, because a good third of what these packages export is
    // a **class** — a renderer, a handler, a transform — and `Class(...)` without `new` is a
    // TypeError. The proxy records a plain call and leaves a construction to behave exactly as it
    // would have; a constructed object's own behaviour is not something a vector can capture, so it
    // is noted and not pretended about.
    wrapped[name] = new Proxy(value, {
      apply(target, thisArg, args) {
        let result, threw = null;
        try {
          result = Reflect.apply(target, thisArg, args);
        } catch (error) {
          threw = error;
        }
        calls.push({
          package: packageName,
          fn: name,
          args: args.map(a => encode(a)),
          ...(threw ? {threw: threw.message.split('\n')[0]} : {result: encode(result)}),
        });
        if (threw) throw threw;
        return result;
      },
      construct(target, args, newTarget) {
        return Reflect.construct(target, args, newTarget);
      },
    });
  }
  return wrapped;
}

/**
 * Runs one test file with `tape` and the package's exports replaced.
 *
 * The rewrite is textual and deliberately narrow: the relative import of the package under test
 * becomes an import of a data URL holding the recording wrapper, and `tape` becomes the shim. A file
 * that reaches for anything else — an internal `../src/...` path, a fixture on disk — is reported
 * and skipped rather than half-run.
 */
async function runTestFile(file, packageName, calls, skipped, checkout) {
  const source = readFileSync(file, 'utf8');
  // Recorded relative to the checkout: an absolute path records whose machine ran the recorder, and
  // makes a regenerated file differ from the committed one for no reason anyone cares about.
  const where = file.startsWith(checkout) ? file.slice(checkout.length + 1) : file;
  const namespace = await import(packageName);
  const wrapped = recordExports(namespace, packageName, calls);
  const restoreTransforms = recordTransforms(namespace, packageName, calls);
  globalThis.__vegaRecorded = wrapped;
  globalThis.__vegaTape = tapeShim(where, skipped);

  let rewritten;
  try {
    rewritten = rewriteImports(source, file);
  } catch (error) {
    skipped.push({file: where, reason: `could not parse: ${error.message.split('\n')[0]}`});
    return;
  }
  // Written to a real file inside `oracle-js` rather than imported from a `data:` URL. A data URL
  // has no resolution base, so every bare specifier a test reaches for — `vega-util`,
  // `vega-datasets`, `d3-array` — fails to resolve; from here they resolve against
  // `oracle-js/node_modules`, which is the installed 6.3.1 the whole repository compares against.
  const scratch = join(here, '..', '.recorder-scratch');
  mkdirSync(scratch, {recursive: true});
  // Named after the test file and nothing else. A timestamp here reached the *recorded* text —
  // Node names the importer in a resolution failure — so two runs disagreed on a file that had not
  // changed. One package per process means no collision.
  const temporary = join(scratch, `${basename(file)}.mjs`);
  writeFileSync(temporary, rewritten);
  try {
    await import(pathToFileURL(temporary).href);
  } catch (error) {
    skipped.push({file: where, reason: `threw while running: ${scrub(error.message.split('\n')[0], scratch)}`});
  } finally {
    rmSync(temporary, {force: true});
    restoreTransforms();
  }
}

/**
 * Rewrites a test file's imports, by **AST** rather than by pattern.
 *
 * This is the part that has to survive a version upgrade, and the regex version it replaces did not
 * survive the *current* version: it handled `import {a, b}` and missed `import * as vega`, which is
 * the form 55 of these files use. A parser handles every form there is, and a form it has never seen
 * fails loudly instead of silently producing a file that still imports the real module.
 *
 * Four cases, and nothing else is touched:
 * - the package under test becomes the recording wrapper;
 * - `tape` becomes the shim;
 * - a relative helper (`./util.js`) is resolved to an absolute URL, since the rewritten source runs
 *   from a data URL and has no directory of its own;
 * - a bare import (`fs`, `d3-array`) is left alone, to resolve from `oracle-js/node_modules` —
 *   which is the same installed 6.3.1 every reference in this repository comes from.
 */
function rewriteImports(source, file) {
  const ast = parse(source, {ecmaVersion: 'latest', sourceType: 'module'});
  const edits = [];
  for (const node of ast.body) {
    if (node.type !== 'ImportDeclaration') continue;
    const from = node.source.value;
    const isPackage = from === '../index.js' || from === '../index';
    if (!isPackage && from !== 'tape' && !from.startsWith('./')) continue;

    if (from.startsWith('./')) {
      const url = pathToFileURL(resolve(dirname(file), from)).href;
      edits.push([node.start, node.end, source.slice(node.start, node.end).replace(`'${from}'`, `'${url}'`)]);
      continue;
    }
    const binding = isPackage ? 'globalThis.__vegaRecorded' : 'globalThis.__vegaTape';
    const parts = [];
    for (const specifier of node.specifiers) {
      if (specifier.type === 'ImportDefaultSpecifier' || specifier.type === 'ImportNamespaceSpecifier') {
        // `import tape from 'tape'` and `import * as vega from '../index.js'` both bind the whole
        // thing; for the package that is exactly the wrapper object.
        parts.push(`const ${specifier.local.name} = ${binding};`);
      } else {
        parts.push(`const ${specifier.local.name} = ${binding}[${JSON.stringify(specifier.imported.name)}];`);
      }
    }
    edits.push([node.start, node.end, parts.join(' ')]);
  }
  let out = source;
  for (const [start, end, text] of edits.sort((a, b) => b[0] - a[0])) {
    out = out.slice(0, start) + text + out.slice(end);
  }
  return out;
}

/**
 * Records what a **transform operator** does, which is where the interesting packages keep their
 * meaning.
 *
 * `vega-transforms` and its neighbours export operator *classes*, not functions: a test constructs
 * one through a `Dataflow` and pushes tuples at it, so nothing crosses the export boundary with plain
 * arguments and the export-level recorder sees nothing at all. One level in is a seam that sees
 * everything — `prototype.transform(_, pulse)`, called once per pulse with the resolved parameters.
 *
 * What is captured is a transform's whole contract: the parameters, the tuples that went in, and the
 * tuples that came out. Tuple identity is a `Symbol`, so it does not appear in the JSON and the
 * vectors stay comparable.
 *
 * Output is **capped**. Some of upstream's tests push tens of thousands of tuples through a
 * `crossfilter`; a vector that large is unreadable, slow to replay, and proves nothing the first two
 * hundred rows do not. Anything larger is recorded as a count, so the skip is visible rather than a
 * silently short array.
 */
function recordTransforms(moduleNamespace, packageName, calls) {
  const patched = [];
  const instanceCounter = {n: 0};
  for (const name of Object.keys(moduleNamespace)) {
    const operator = moduleNamespace[name];
    const proto = operator && operator.prototype;
    if (!proto || typeof proto.transform !== 'function' || proto.__vegaRecorded) continue;
    const original = proto.transform;
    proto.__vegaRecorded = true;
    proto.transform = function (params, pulse) {
      // A transform operator is **stateful**: `aggregate` accumulates the values it has seen, and a
      // later pulse's output depends on every pulse before it. A replay that calls a pure
      // `apply(rows, params)` can only reproduce the *first* call on a fresh operator, so each call
      // is stamped with which operator it belongs to and how many calls that operator has had. Vector
      // 2 of the cross-product test is what taught this: its input has no `b: 2` and its output does.
      if (this.__vegaInstance === undefined) this.__vegaInstance = ++instanceCounter.n;
      const sequence = (this.__vegaSequence = (this.__vegaSequence ?? -1) + 1);
      const input = capturePulse(pulse);
      const output = original.call(this, params, pulse);
      // Several operators put their answer on **themselves** rather than in the pulse: `extent`
      // leaves `[min, max]` in `this.value`, and a chart reads it as a parameter of the next
      // operator. A vector without it would record that `extent` changed nothing, which is true of
      // the tuples and useless as a check.
      const value = this.value === undefined || typeof this.value === 'function' ? undefined : encode(this.value);
      calls.push(
        withinBudget({
          package: packageName,
          op: name,
          instance: this.__vegaInstance,
          sequence,
          params: encodeParams(params),
          input,
          output: capturePulse(output && output.add !== undefined ? output : pulse),
          ...(value === undefined ? {} : {value}),
        })
      );
      return output;
    };
    patched.push(() => {
      proto.transform = original;
      delete proto.__vegaRecorded;
    });
  }
  return () => patched.forEach(undo => undo());
}

const MAX_TUPLES = 200;

/** The most JSON one vector may occupy, before its pulses are replaced by a note. */
const MAX_VECTOR_BYTES = 64 * 1024;

/**
 * Keeps one vector to a readable size.
 *
 * Capping the *number* of tuples is not enough, because a single tuple can be enormous: a `facet`
 * group tuple carries its whole partition, and recording three of those produced a 52 MB vector and a
 * 452 MB file. What matters for a replay is the parameters — the pulses are dropped with a note
 * saying so, which is visible in the ledger rather than silently short.
 */
function withinBudget(call) {
  const text = JSON.stringify(call);
  if (text.length <= MAX_VECTOR_BYTES) return call;
  return {
    ...call,
    input: {$: 'oversized', bytes: text.length},
    output: {$: 'oversized', bytes: text.length},
    ...(call.value === undefined ? {} : {value: {$: 'oversized'}}),
  };
}

/** A pulse's three change sets and its backing source, each capped and encoded. */
function capturePulse(pulse) {
  if (!pulse || typeof pulse !== 'object') return null;
  const part = tuples => {
    if (!Array.isArray(tuples)) return undefined;
    if (tuples.length > MAX_TUPLES) return {$: 'truncated', count: tuples.length};
    return tuples.map(t => encode(t));
  };
  const out = {};
  for (const key of ['add', 'rem', 'mod', 'source']) {
    const value = part(pulse[key]);
    if (value !== undefined && (!Array.isArray(value) || value.length)) out[key] = value;
  }
  return out;
}

/** Parameters, with an operator parameter replaced by the value it holds. */
function encodeParams(params) {
  if (!params || typeof params !== 'object') return encode(params);
  const out = {};
  for (const key of Object.keys(params)) {
    if (key === 'pulse' || key.startsWith('$')) continue;
    const value = params[key];
    // A parameter can be an `Operator` — `df.add([0, 10])` — and what a Kotlin adapter needs is the
    // value it currently holds, not the operator wrapper.
    out[key] = value && typeof value === 'object' && typeof value.value === 'function'
      ? encode(value.value())
      : encode(value);
  }
  return out;
}

/**
 * Replaces this machine's paths in a recorded message.
 *
 * A failure message names the importing file, and an absolute path records where somebody's checkout
 * lives — which makes a regenerated vector file differ from the committed one on another machine, for
 * no change in behaviour.
 */
function scrub(message, scratch) {
  return message
    .split(`${scratch}/`)
    .join('<recorder>/')
    .split(`${resolve(here, '..')}/`)
    .join('<oracle-js>/');
}

/** Enough of `tape` to run a test body: assertions are accepted and ignored. */
function tapeShim(file, skipped) {
  const noop = () => {};
  const t = new Proxy(
    {end: noop, plan: noop, comment: noop, skip: noop, pass: noop, fail: noop},
    {get: (target, key) => (key in target ? target[key] : noop)}
  );
  const run = (name, body) => {
    try {
      const done = body(t);
      if (done && typeof done.then === 'function') done.catch(() => {});
    } catch (error) {
      skipped.push({file, case: name, reason: `case threw: ${error.message.split('\n')[0]}`});
    }
  };
  run.skip = noop;
  run.only = run;
  return run;
}

// One package per process, and the script that drives this enforces it. Vega numbers every tuple it
// creates from a **module-level counter**, and those ids reach the data — a `lookup` transform keys
// its index by them. Recording two packages in one process therefore makes the second one's vectors
// depend on how many tuples the first one built, so a regenerated file differs from the committed one
// with no change in behaviour. A fresh process starts the counter at zero.
const [, , checkout, ...packages] = process.argv;
if (packages.length > 1) {
  console.error('record one package per process: tuple ids are a module-level counter');
  process.exit(2);
}
if (!checkout || packages.length === 0) {
  console.error('usage: node src/record-upstream-tests.mjs <vega-checkout> <package>...');
  process.exit(2);
}
mkdirSync(outputDir, {recursive: true});

for (const packageName of packages) {
  const testDir = join(resolve(checkout), 'packages', packageName, 'test');
  if (!existsSync(testDir)) {
    console.error(`${packageName}: no test directory at ${testDir}`);
    continue;
  }
  const calls = [];
  const skipped = [];
  const files = readdirSync(testDir).filter(f => f.endsWith('-test.js')).sort();
  for (const file of files) {
    await runTestFile(join(testDir, file), packageName, calls, skipped, resolve(checkout));
  }
  // Identical calls are recorded once: a test that calls `bin` with the same parameters in three
  // assertions is one vector, and a duplicate proves nothing the first did not.
  const unique = [];
  const seen = new Set();
  for (const call of calls) {
    const key = JSON.stringify([call.fn, call.op, call.instance, call.sequence, call.args, call.params, call.input, call.result, call.output, call.threw]);
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(call);
  }
  const byFunction = {};
  for (const call of unique) { const k = call.fn || call.op; byFunction[k] = (byFunction[k] || 0) + 1; }
  writeFileSync(
    join(outputDir, `${packageName}.json`),
    JSON.stringify({package: packageName, vega: '6.3.1', files: files.length, skipped, calls: unique}, null, 2) + '\n'
  );
  console.log(
    `${packageName}: ${unique.length} vectors from ${files.length - skipped.filter(s => !s.case).length}/${files.length} files` +
      `${skipped.length ? `, ${skipped.length} skipped` : ''}`
  );
  console.log('  ' + Object.entries(byFunction).sort((a, b) => b[1] - a[1]).map(([f, n]) => `${f}:${n}`).join(' '));
}
