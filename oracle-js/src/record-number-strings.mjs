// Records what JavaScript prints for a spread of doubles, as vectors for `numberToString`.
//
// Unlike the other recorders this replays no upstream *test*, because there is no test to replay:
// `String(x)` is a language primitive, and the only oracle for it is the language. Every number a
// chart writes as text goes through it — a tooltip, a label, `datum.value + ''` in an expression —
// and it is the one piece of formatting nothing tells us the digit count for.
//
// The spread is chosen to hit the places the two languages part company rather than to be random:
// the notation thresholds at 10^-7, 10^-6 and 10^21, the integral doubles past 2^53 where the
// shortest decimal stops being the exact one, and the subnormals down to `Number.MIN_VALUE`.
// Deterministic, so two runs produce the same file byte for byte.

import {writeFileSync, mkdirSync} from 'fs';
import {dirname, join} from 'path';
import {fileURLToPath} from 'url';

const here = dirname(fileURLToPath(import.meta.url));
const out = [];
const seen = new Set();
const buffer = new DataView(new ArrayBuffer(8));

function push(value) {
  if (!Number.isFinite(value)) return;
  buffer.setFloat64(0, value);
  const bits = buffer.getBigUint64(0).toString();
  if (seen.has(bits)) return;
  seen.add(bits);
  out.push([bits, String(value)]);
}

// A pinned generator rather than Math.random, so the corpus is the same every time.
let state = 42;
const next = () => (state = (state * 1103515245 + 12345) % 2147483648) / 2147483648;
for (let i = 0; i < 4000; i++) {
  buffer.setUint32(0, (next() * 4294967296) >>> 0);
  buffer.setUint32(4, (next() * 4294967296) >>> 0);
  push(buffer.getFloat64(0));
}

// The subnormals, where the platform's own `toString` stops being the shortest.
for (let i = 1; i <= 400; i++) push(Number.MIN_VALUE * i);

// Every decade, with mantissas that round differently.
for (let e = -320; e <= 308; e += 1)
  for (const m of [1, 1.5, 2, 3.14159, 9.999]) push(m * 10 ** e);

// The notation thresholds, and the integers on either side of 2^53 and of Long's range.
for (const v of [
  1e-7, 9.9e-7, 1e-6, 1e20, 1e21, 1.5e21, 9007199254740992, 9007199254740994,
  2 ** 63, 2 ** 63 + 2048, 1.7976931348623157e308, 5e-324, 0.1 + 0.2, 1 / 3
])
  push(v, push(-v));

for (let i = 1; i < 400; i++) push(i / 7, i / 3, 2 ** i, 1 / i, i * 1e15, i * 1e18);

const target = join(here, '..', '..', 'test-fixtures', 'upstream-vectors');
mkdirSync(target, {recursive: true});
writeFileSync(join(target, 'js-number-strings.json'), JSON.stringify({
  note: 'String(x) for a spread of doubles, as [bits, text]; see record-number-strings.mjs',
  numbers: out
}));
console.log(`  js-number-strings: ${out.length} doubles`);
