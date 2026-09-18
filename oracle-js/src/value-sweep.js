#!/usr/bin/env node
/**
 * One specification per **(chart, column of values)** pair, rendered with upstream.
 *
 * Usage: node src/value-sweep.js <output-directory>
 *
 * Every other corpus here varies the **specification**: the fixtures are charts somebody drew, the
 * schema sweep is one chart with a property changed, the Vega-Lite sweep is one encoding changed.
 * All of them hold the *data* still, and the data in all of them is tidy — numbers where numbers go
 * and words where words go.
 *
 * Real data is not tidy, and this is where the differences have been. Six defects found in one
 * afternoon all came from a value nobody expected to be there:
 *
 *   * `toDate` of a word is `NaN` and not nothing, so an unreadable date stays in the chart;
 *   * a stack's groups are keyed by `JSON.stringify`, so a NaN stacks with the nulls;
 *   * a discrete domain holds values, so `+null` is `0` and `+"null"` is `NaN`;
 *   * a scale reads `""` as **zero** and a null as nothing, a distinction a CSV makes constantly;
 *   * `quantize` does not coerce at all, so a word lands in the first bucket;
 *   * an axis joins its ticks by value, so two entries with one text draw one tick.
 *
 * Not one of them was reachable by changing a property. So this sweep holds the specification still
 * and varies the **column**, which is the axis of coverage nothing else here has.
 *
 * ### What is varied
 *
 * A set of columns, each a list of values chosen because JavaScript treats it differently from the
 * obvious reading: a null against the word `null`, an empty cell against a zero, a number against
 * the same number written as text, a flag, a negative zero, a value past the precision where
 * integers stop being exact, and the notation thresholds where `String(x)` changes shape.
 *
 * ### What it is varied in
 *
 * One chart per **scale family and mark type**, because a value's journey is the scale it goes
 * through and the mark it lands in: a band, a linear, a log, a time, an ordinal, a quantize, a
 * quantile and a threshold; a rect, a line, an area, a symbol, an arc and a text. The swept column
 * is the one the chart's *position* reads, so a value that is placed differently moves a mark, and
 * the chart draws an axis and a legend over the same column so the same value is also formatted,
 * captioned and enumerated.
 *
 * ### Refusals
 *
 * A specification upstream will not render is recorded as a refusal rather than dropped — "upstream
 * will not draw this either" is an agreement — exactly as the schema sweep records its own.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';
import { canonicalJson } from './canonical.js';
import { normalizeScales, normalizeScene, surfaceSize } from './normalize.js';

const [outDir] = process.argv.slice(2);
if (!outDir) {
  console.error('Usage: node src/value-sweep.js <output-directory>');
  process.exit(2);
}

/**
 * The columns, each a reason rather than a random spread.
 *
 * `note` says what the column is *for*, and it is written into the manifest so a difference the
 * sweep reports can be read without opening the specification.
 */
const COLUMNS = [
  { name: 'plain', note: 'ordinary numbers, the control', values: [1, 2, 3, 4] },
  { name: 'with-a-null', note: 'a null, which no scale coerces', values: [1, null, 3, 4] },
  {
    name: 'with-the-word-null',
    note: 'the word for nothing, which is not nothing: +"null" is NaN where +null is 0',
    values: [1, 'null', 3, 4],
  },
  {
    name: 'with-an-empty-cell',
    note: 'the empty string, which Number reads as 0 and a parse rejects — every CSV has these',
    values: [1, '', 3, 4],
  },
  { name: 'with-a-word', note: 'a word with no number in it, which reaches NaN', values: [1, 'abc', 3, 4] },
  { name: 'with-a-flag', note: 'a boolean, which is 1 and 0 to every coercion', values: [1, true, false, 4] },
  {
    name: 'a-number-and-its-word',
    note: 'the same number as a number and as text, which key alike and compare differently',
    values: [1, 2, '2', 4],
  },
  { name: 'all-words', note: 'a column of numeric text, which a domain sorts as text or as numbers', values: ['10', '9', '100', '2'] },
  { name: 'with-a-negative-zero', note: 'a negative zero, which JSON writes as 0 and Double.equals does not', values: [0, -0, 1, 2] },
  { name: 'with-a-negative', note: 'a negative, for the minus sign a label writes', values: [-5, 2, -1200, 4] },
  {
    name: 'past-exact-integers',
    note: 'past 2^53, where the shortest decimal stops being the exact one',
    values: [9007199254740993, 9007199254740994, 1, 2],
  },
  {
    name: 'at-the-notation-thresholds',
    note: 'where String(x) changes shape: 1e21 above and 1e-7 below',
    values: [1e-7, 1e-6, 1e20, 1e21],
  },
  { name: 'with-a-date-string', note: 'an ISO date among numbers, which only a time scale reads', values: ['2024-01-07', 2, 3, 4] },
  { name: 'all-dates', note: 'a column of ISO dates, for the scales that parse them', values: ['2024-01-07', '2024-03-19', '2024-06-02', '2024-11-30'] },
  { name: 'one-value', note: 'a degenerate domain, where every span is zero', values: [7, 7, 7, 7] },
  { name: 'with-a-huge-gap', note: 'a domain wide enough to change a tick step', values: [1, 1000000, 2, 3] },
];

/** A chart per scale family and mark type; `field` names the column the sweep replaces. */
function CHARTS() {
  const rows = (values) => values.map((v, i) => ({ v, k: `r${i}`, n: i + 1 }));
  const base = (extra) => ({
    $schema: 'https://vega.github.io/schema/vega/v6.json',
    width: 200,
    height: 120,
    padding: 5,
    data: [{ name: 't', values: [] }],
    ...extra,
  });

  const withAxes = (scaleY, markType, encodeExtra = {}) =>
    base({
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
        scaleY,
      ],
      axes: [
        { orient: 'bottom', scale: 'x' },
        { orient: 'left', scale: 'y', tickCount: 4 },
      ],
      marks: [
        {
          type: markType,
          from: { data: 't' },
          encode: {
            enter: {
              x: { scale: 'x', field: 'k', band: 0.5 },
              y: { scale: 'y', field: 'v' },
              ...encodeExtra,
            },
          },
        },
        {
          type: 'text',
          from: { data: 't' },
          encode: {
            enter: {
              x: { value: 0 },
              y: { signal: '12 * datum.n - 6' },
              text: { signal: "'' + scale('y', datum.v)" },
              fontSize: { value: 8 },
              baseline: { value: 'top' },
            },
          },
        },
      ],
    });

  return [
    {
      name: 'linear-symbol',
      note: 'a linear scale under a symbol, the plainest journey a value takes',
      spec: withAxes(
        { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height', nice: true },
        'symbol',
        { size: { value: 50 } },
      ),
    },
    {
      name: 'linear-rect-stacked',
      note: 'a stack, whose groups are keyed by JSON and whose totals are a scale domain',
      spec: base({
        data: [
          {
            name: 't',
            values: [],
            transform: [{ type: 'stack', groupby: ['k'], field: 'v' }],
          },
        ],
        scales: [
          { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
          { name: 'y', type: 'linear', domain: { data: 't', field: 'y1' }, range: 'height', nice: true },
        ],
        axes: [{ orient: 'left', scale: 'y', tickCount: 4 }],
        marks: [
          {
            type: 'rect',
            from: { data: 't' },
            encode: {
              enter: {
                x: { scale: 'x', field: 'k' },
                width: { scale: 'x', band: 1 },
                y: { scale: 'y', field: 'y0' },
                y2: { scale: 'y', field: 'y1' },
              },
            },
          },
        ],
      }),
    },
    {
      name: 'band-over-the-values',
      note: 'the swept column as a **discrete domain**, where values are kept and keyed',
      spec: base({
        scales: [{ name: 'x', type: 'band', domain: { data: 't', field: 'v' }, range: 'width', padding: 0.1 }],
        axes: [{ orient: 'bottom', scale: 'x', labelOverlap: false }],
        legends: [],
        marks: [
          {
            type: 'rect',
            from: { data: 't' },
            encode: {
              enter: {
                x: { scale: 'x', field: 'v' },
                width: { scale: 'x', band: 1 },
                y: { value: 0 },
                y2: { value: 60 },
              },
            },
          },
        ],
      }),
    },
    {
      name: 'ordinal-colour',
      note: 'the swept column as an **ordinal** domain with a legend, which enumerates it',
      spec: base({
        scales: [
          { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
          { name: 'colour', type: 'ordinal', domain: { data: 't', field: 'v' }, range: 'category' },
        ],
        legends: [{ fill: 'colour' }],
        marks: [
          {
            type: 'rect',
            from: { data: 't' },
            encode: {
              enter: {
                x: { scale: 'x', field: 'k' },
                width: { scale: 'x', band: 1 },
                y: { value: 0 },
                y2: { value: 60 },
                fill: { scale: 'colour', field: 'v' },
              },
            },
          },
        ],
      }),
    },
    {
      name: 'time-line',
      note: 'a time scale, which parses what it is given and labels it as a date',
      spec: withAxes(
        { name: 'y', type: 'time', domain: { data: 't', field: 'v' }, range: 'height' },
        'line',
      ),
    },
    {
      name: 'log-symbol',
      note: 'a log scale, whose domain must clear zero and whose ticks are not linear',
      spec: withAxes(
        { name: 'y', type: 'log', domain: { data: 't', field: 'v' }, range: 'height' },
        'symbol',
        { size: { value: 50 } },
      ),
    },
    {
      name: 'quantize-colour',
      note: 'quantize, which does not coerce at all and bisects with whatever it is handed',
      spec: withAxes(
        { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height' },
        'symbol',
        { size: { value: 50 }, fill: { scale: 'q', field: 'v' } },
      ),
      extraScales: [{ name: 'q', type: 'quantize', domain: [0, 4], range: ['#eee', '#999', '#333'] }],
    },
    {
      name: 'quantile-colour',
      note: 'quantile, whose domain is the column itself and whose samples are filtered',
      spec: withAxes(
        { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height' },
        'symbol',
        { size: { value: 50 }, fill: { scale: 'qt', field: 'v' } },
      ),
      extraScales: [
        { name: 'qt', type: 'quantile', domain: { data: 't', field: 'v' }, range: ['#eee', '#999', '#333'] },
      ],
    },
    {
      name: 'threshold-colour',
      note: 'threshold, which shares quantize’s uncoerced reading and its own cut points',
      spec: withAxes(
        { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height' },
        'symbol',
        { size: { value: 50 }, fill: { scale: 'th', field: 'v' } },
      ),
      extraScales: [{ name: 'th', type: 'threshold', domain: [2], range: ['#eee', '#333'] }],
    },
    {
      name: 'arc-theta',
      note: 'a pie, where the column becomes an angle through a transform rather than a scale',
      spec: base({
        data: [{ name: 't', values: [], transform: [{ type: 'pie', field: 'v' }] }],
        marks: [
          {
            type: 'arc',
            from: { data: 't' },
            encode: {
              enter: {
                x: { value: 100 },
                y: { value: 60 },
                startAngle: { field: 'startAngle' },
                endAngle: { field: 'endAngle' },
                outerRadius: { value: 50 },
              },
            },
          },
        ],
      }),
    },
    {
      name: 'text-of-the-value',
      note: 'the column written out, which is String(x) and nothing else',
      spec: base({
        marks: [
          {
            type: 'text',
            from: { data: 't' },
            encode: {
              enter: {
                x: { value: 5 },
                y: { signal: '20 * datum.n - 10' },
                text: { field: 'v' },
                baseline: { value: 'top' },
              },
            },
          },
        ],
      }),
    },
    {
      name: 'aggregated',
      note: 'the aggregates, which skip a row with no number in it rather than reading it as zero',
      spec: base({
        data: [
          { name: 't', values: [] },
          {
            name: 'summary',
            source: 't',
            transform: [
              {
                type: 'aggregate',
                fields: ['v', 'v', 'v', 'v', 'v'],
                ops: ['count', 'valid', 'min', 'max', 'sum'],
                as: ['count', 'valid', 'lo', 'hi', 'total'],
              },
            ],
          },
        ],
        marks: [
          {
            type: 'text',
            from: { data: 'summary' },
            encode: {
              enter: {
                x: { value: 5 },
                y: { value: 10 },
                text: {
                  signal:
                    "'count ' + datum.count + ' valid ' + datum.valid + ' min ' + datum.lo + ' max ' + datum.hi + ' sum ' + datum.total",
                },
                fontSize: { value: 9 },
              },
            },
          },
        ],
      }),
    },
  ];
}

function withColumn(chart, column) {
  const spec = JSON.parse(JSON.stringify(chart.spec));
  const rows = column.values.map((v, i) => ({ v, k: `r${i}`, n: i + 1 }));
  spec.data[0].values = rows;
  if (chart.extraScales) spec.scales = [...(spec.scales || []), ...chart.extraScales];
  return spec;
}

const specDir = join(outDir, 'specs');
const referenceDir = join(outDir, 'reference');
rmSync(outDir, { recursive: true, force: true });
mkdirSync(specDir, { recursive: true });
mkdirSync(referenceDir, { recursive: true });

const charts = CHARTS();
const cases = [];
for (const chart of charts) {
  for (const column of COLUMNS) {
    cases.push({
      name: `${chart.name}--${column.name}`,
      chart: chart.name,
      column: column.name,
      note: `${chart.note}; ${column.note}`,
      spec: withColumn(chart, column),
    });
  }
}

let rendered = 0;
const refused = [];

for (const one of cases) {
  writeFileSync(join(specDir, `${one.name}.vg.json`), JSON.stringify(one.spec, null, 2) + '\n');
  pinDeterminism();
  let view;
  let failure = null;
  try {
    view = new vega.View(vega.parse(one.spec), { renderer: 'none' });
    view.error = (error) => {
      failure = error;
    };
    await view.runAsync();
  } catch (error) {
    failure = error;
  }
  if (failure) {
    refused.push({
      name: one.name,
      chart: one.chart,
      column: one.column,
      reason: String(failure.message ?? failure).slice(0, 120),
    });
    await view?.finalize();
    continue;
  }

  const scaleNames = (one.spec.scales || []).map((s) => s.name);
  const reference = {
    vegaVersion: vega.version,
    spec: `${one.name}.vg.json`,
    size: surfaceSize(view, one.spec),
    scales: normalizeScales(view, scaleNames),
    ...normalizeScene(view.scenegraph().root),
  };
  writeFileSync(join(referenceDir, `${one.name}.reference.json`), canonicalJson(reference));
  rendered++;
  await view.finalize();
}

writeFileSync(
  join(outDir, 'manifest.json'),
  JSON.stringify(
    {
      vegaVersion: vega.version,
      charts: charts.map((c) => ({ name: c.name, note: c.note })),
      columns: COLUMNS.map((c) => ({ name: c.name, note: c.note })),
      generated: cases.length,
      rendered,
      refused,
    },
    null,
    2,
  ) + '\n',
);

console.log(
  `Generated ${cases.length} case(s) from ${charts.length} chart(s) and ${COLUMNS.length} column(s): ` +
    `${rendered} rendered, ${refused.length} refused by upstream.`,
);
