// Records what d3 prints when it formats a value that is **not an instant**, as vectors for
// `TimeFormat`.
//
// Like `record-number-strings.mjs` this replays no upstream *test*, because there is none to
// replay: d3-time-format's suite formats dates, and an Invalid Date is not a date. What it prints
// for one is nonetheless observable, reachable from a specification, and load-bearing — a
// `formatType: "time"` on a column of words is enough, since Vega-Lite then writes
// `toDate(datum[...])` over it and `Date.parse` answers `NaN` rather than nothing. The label that
// results is garbage in every case, and it is *upstream's* garbage: it decides the width of a
// rotated label and so the height of the whole chart, which is where an eight-pixel difference
// nobody could explain turned out to come from.
//
// Every answer here falls out of two rules and three accidents of JavaScript, and the vectors are
// recorded rather than derived so that nothing in this repository has to claim which:
//
//   - every numeric field of an Invalid Date is `NaN`, and goes through the *same* pad, so `%Y`
//     pads "NaN" to four characters as `0NaN` while `%d` pads it to two and leaves it alone;
//   - every *name* field indexes its locale's array out of range and contributes nothing, because
//     `[undefined].join('')` is the empty string — so `%B` is `""`, not `"undefined"`;
//   - `%I` is `12` (`NaN % 12 || 12`, and `NaN` is falsy), `%p` is `AM` (`+(NaN >= 12)` is `0`) and
//     `%q` is `1` (`1 + ~~(NaN / 3)`, and `~~NaN` is `0`).
//
// The whole directive table crossed with every pad modifier, both zones, and the **multi-format**
// that a guide with no specifier uses — which picks `%Y` for an Invalid Date, every one of its
// `interval(d) < d` tests being false.

import {writeFileSync, mkdirSync} from 'fs';
import {dirname, join} from 'path';
import {fileURLToPath} from 'url';
import {timeFormat, utcFormat} from 'd3-time-format';
import {timeFormatDefaultLocale} from 'vega-format';

const here = dirname(fileURLToPath(import.meta.url));

// d3's own table, in its own order, plus the two shapes that are not table entries: a literal
// percent, and a directive with no entry at all.
const DIRECTIVES = [
  ...'aAbBcdefgGHIjLmMpqQsSuUVwWxXyYZ',
  '%',
  '~',
];
const MODIFIERS = ['', '-', '_', '0'];

// The **one** value this whole file is about, and the reason no vector carries an `args`: an
// Invalid Date cannot be written to JSON — `JSON.stringify(new Date(NaN))` is `null` and the
// recorder's own date encoding would carry a NaN epoch — so the argument is implicit in the file
// rather than written wrongly in every row. `fn` and `constructedWith` are the recorder's usual
// fields for `format(spec)(value)`, which is the shape of every call here.
const invalid = new Date(NaN);
const calls = [];

const record = (fn, pattern, result) => calls.push({fn, constructedWith: [pattern], result});

for (const directive of DIRECTIVES) {
  for (const modifier of MODIFIERS) {
    const pattern = `%${modifier}${directive}`;
    record('timeFormat', pattern, timeFormat(pattern)(invalid));
    record('utcFormat', pattern, utcFormat(pattern)(invalid));
  }
}

// A pattern is not only directives: the literal text around them survives, and a percent at the
// very end consumes itself.
for (const pattern of ['%Y-%m-%d', '%b %d, %Y', 'week %V of %G', '.0%', '%', 'x%', '%-Y!%0d']) {
  record('timeFormat', pattern, timeFormat(pattern)(invalid));
  record('utcFormat', pattern, utcFormat(pattern)(invalid));
}

// The multi-format, which is what a guide naming `formatType` and no format uses. `vega-format`
// reaches it by handing its `timeFormat` something that is not a string — so there is no pattern
// to record, and `constructedWith` is empty rather than null.
const locale = timeFormatDefaultLocale();
calls.push({fn: 'timeMultiFormat', constructedWith: [], result: locale.timeFormat(undefined)(invalid)});
calls.push({fn: 'utcMultiFormat', constructedWith: [], result: locale.utcFormat(undefined)(invalid)});

const file = join(here, '../../test-fixtures/upstream-vectors/d3-invalid-date.json');
mkdirSync(dirname(file), {recursive: true});
writeFileSync(
  file,
  JSON.stringify(
    {
      note:
        'd3-time-format and vega-format applied to a value that is not an instant. The ' +
        'argument is that value in every call and is not written: JSON cannot carry an Invalid ' +
        'Date. See record-invalid-date-formats.mjs',
      calls,
    },
    null,
    2,
  ) + '\n',
);
console.log(`==> ${calls.length} vectors in test-fixtures/upstream-vectors/d3-invalid-date.json`);
