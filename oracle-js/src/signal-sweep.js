#!/usr/bin/env node
/**
 * One specification per **(chart, signal value)** pair, rendered with upstream *after the signal has
 * been set*.
 *
 * Every corpus here compares a chart **as first drawn**. The fixtures, the gallery, the wild
 * specifications, the schema sweeps and the value sweep all build a view, run it once and compare
 * what came out. None of them ever changes anything and looks again.
 *
 * That leaves a whole half of the engine unchecked. `VegaChartController.setSignal` pins a value,
 * cascades it through every signal sourced on it, and compiles the specification again — which is
 * what a slider, a dropdown or a fired handler actually does. It has unit tests asserting what it
 * *should* produce; nothing has ever asked upstream what it *does* produce.
 *
 * ### What is varied
 *
 * A signal's value, and deliberately not only sensible ones. The lesson of the value sweep is that
 * defects live where a value nobody expected arrives: a null, a word where a number goes, an empty
 * list, a negative where only positives were imagined. A binding is exactly such a door — a host
 * writes through it whatever its control produced.
 *
 * ### What it is varied in
 *
 * One chart per **thing a signal can reach**, because the recompile has to carry the new value that
 * far: a scale's domain, a scale's range, a mark's own property, an axis's tick count, a title's
 * words, a transform's parameter, and a signal **derived** from the one being set — which is the
 * cascade rather than the write.
 *
 * ### Refusals
 *
 * A value upstream will not accept is recorded as a refusal rather than dropped, as every sweep here
 * records its own: "upstream will not draw this either" is an agreement.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';
import { canonicalJson } from './canonical.js';
import { normalizeScales, normalizeScene, surfaceSize } from './normalize.js';

const [outDir] = process.argv.slice(2);
if (!outDir) {
  console.error('Usage: node src/signal-sweep.js <output-directory>');
  process.exit(2);
}

/**
 * **`update`, never `enter`.** An `enter` encode runs once, when an item is created, and a signal
 * written afterwards never reaches it: probed, a `strokeWidth` in `enter` stays at its first value
 * however often the signal changes, while the same channel in `update` tracks it. The first version
 * of this sweep put everything in `enter` and then reported 27 differences, every one of them the
 * sweep's own specifications asking upstream not to react and this engine reacting anyway. `update`
 * runs on the first render as well, so it is the whole encode and not half of one.
 */
const ROWS = [
  { k: 'a', v: 28, n: 1 },
  { k: 'b', v: 55, n: 2 },
  { k: 'c', v: 43, n: 3 },
  { k: 'd', v: 91, n: 4 },
];

const base = (extra) => ({
  $schema: 'https://vega.github.io/schema/vega/v6.json',
  width: 200,
  height: 120,
  padding: 5,
  data: [{ name: 't', values: ROWS }],
  ...extra,
});

/**
 * Each chart names **one** signal and the values to write into it.
 *
 * `note` says what the signal reaches, and it is written into the manifest so a difference can be
 * read without opening the specification.
 */
const CHARTS = [
  {
    name: 'a-scale-domain',
    signal: 'dom',
    note: 'a signal that *is* a scale domain, so the write reaches the scale and every mark on it',
    values: [[0, 100], [0, 10], [50, 50], [100, 0], [0, 0], null, 'not a domain', []],
    spec: base({
      signals: [{ name: 'dom', value: [0, 100] }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
        { name: 'y', type: 'linear', domain: { signal: 'dom' }, range: 'height' },
      ],
      axes: [{ orient: 'left', scale: 'y', tickCount: 4 }],
      marks: [
        {
          type: 'rect',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              width: { scale: 'x', band: 1 },
              y: { scale: 'y', field: 'v' },
              y2: { scale: 'y', value: 0 },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-mark-property',
    signal: 'thickness',
    note: 'a signal read straight by a mark, where the value never passes through a scale',
    values: [10, 0, -5, 0.5, 1e6, null, 'wide', true],
    spec: base({
      signals: [{ name: 'thickness', value: 10 }],
      scales: [
        { name: 'x', type: 'point', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.5 },
      ],
      marks: [
        {
          type: 'symbol',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              y: { value: 60 },
              size: { value: 80 },
              stroke: { value: '#333' },
              strokeWidth: { signal: 'thickness' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-tick-count',
    signal: 'ticks',
    note: 'a signal an axis counts with, which decides how many labels a reader gets',
    values: [4, 0, 1, 40, -3, 2.5, null, 'four'],
    spec: base({
      signals: [{ name: 'ticks', value: 4 }],
      scales: [{ name: 'y', type: 'linear', domain: [0, 100], range: 'height' }],
      axes: [{ orient: 'left', scale: 'y', tickCount: { signal: 'ticks' } }],
      marks: [],
    }),
  },
  {
    name: 'a-title',
    signal: 'heading',
    note: 'a signal that is a title, which is words rather than geometry and is also captioned',
    values: ['Revenue', '', 'A much longer heading than the chart is wide', 0, null, ['two', 'lines'], 1e21],
    spec: base({
      title: { text: { signal: 'heading' } },
      signals: [{ name: 'heading', value: 'Revenue' }],
      scales: [{ name: 'y', type: 'linear', domain: [0, 100], range: 'height' }],
      axes: [{ orient: 'left', scale: 'y', tickCount: 4 }],
      marks: [],
    }),
  },
  {
    name: 'a-transform-parameter',
    signal: 'cutoff',
    note: 'a signal a filter reads, so the write changes how many rows exist rather than how they look',
    values: [40, 0, 100, -1, null, 'forty', 1e21],
    spec: base({
      signals: [{ name: 'cutoff', value: 40 }],
      data: [
        {
          name: 't',
          values: ROWS,
          transform: [{ type: 'filter', expr: 'datum.v > cutoff' }],
        },
      ],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
        { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height' },
      ],
      axes: [{ orient: 'bottom', scale: 'x' }],
      marks: [
        {
          type: 'rect',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              width: { scale: 'x', band: 1 },
              y: { scale: 'y', field: 'v' },
              y2: { value: 120 },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-derived-signal',
    signal: 'scale',
    note: 'a signal two others are computed from, so what is tested is the cascade and not the write',
    values: [2, 1, 0, -2, 0.25, null, 'twice'],
    spec: base({
      signals: [
        { name: 'scale', value: 2 },
        { name: 'height2', update: '60 * scale' },
        { name: 'label', update: "'x' + scale" },
      ],
      scales: [
        { name: 'x', type: 'point', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.5 },
      ],
      marks: [
        {
          type: 'rect',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k', offset: -8 },
              width: { value: 16 },
              y: { value: 10 },
              y2: { signal: 'height2' },
            },
          },
        },
        {
          type: 'text',
          encode: {
            update: { x: { value: 2 }, y: { value: 2 }, text: { signal: 'label' }, baseline: { value: 'top' } },
          },
        },
      ],
    }),
  },
];

const specDir = join(outDir, 'specs');
const referenceDir = join(outDir, 'reference');
rmSync(outDir, { recursive: true, force: true });
mkdirSync(specDir, { recursive: true });
mkdirSync(referenceDir, { recursive: true });

/**
 * A value's name in a case's file name — short, stable and readable in a report.
 *
 * **The sign is spelled out**, because stripping it is a lie a report then tells: the first version
 * turned `-5` into `5`, so a case about a negative stroke width read as one about a positive one and
 * was triaged as the wrong defect. A leading `-` cannot stay, since the case name is split on `--`.
 */
function slug(value, index) {
  if (value === null) return 'null';
  if (Array.isArray(value)) {
    if (value.length === 0) return 'empty-list';
    return `list-${value.map((v) => String(v).replace('-', 'minus')).join('-')}`;
  }
  const text = String(value).replace(/^-/, 'minus');
  if (text === '') return 'empty';
  return text.replace(/[^A-Za-z0-9.-]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || `v${index}`;
}

const cases = [];
for (const chart of CHARTS) {
  chart.values.forEach((value, index) => {
    cases.push({
      name: `${chart.name}--${slug(value, index)}`,
      chart: chart.name,
      signal: chart.signal,
      value,
      note: chart.note,
      spec: chart.spec,
    });
  });
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
    // **Run first, then set, then run again.** Setting a signal on a view that has never run is not
    // the case this sweep is about: a host writes to a chart that is already on screen, and the
    // question is what the *second* run produces.
    await view.runAsync();
    view.signal(one.signal, one.value);
    await view.runAsync();
  } catch (error) {
    failure = error;
  }
  if (failure) {
    refused.push({
      name: one.name,
      chart: one.chart,
      reason: String(failure.message ?? failure).slice(0, 120),
    });
    await view?.finalize();
    continue;
  }

  const scaleNames = (one.spec.scales || []).map((s) => s.name);
  writeFileSync(
    join(referenceDir, `${one.name}.reference.json`),
    canonicalJson({
      vegaVersion: vega.version,
      spec: `${one.name}.vg.json`,
      signal: one.signal,
      value: one.value === undefined ? null : one.value,
      size: surfaceSize(view, one.spec),
      scales: normalizeScales(view, scaleNames),
      ...normalizeScene(view.scenegraph().root),
    }),
  );
  rendered++;
  await view.finalize();
}

writeFileSync(
  join(outDir, 'manifest.json'),
  JSON.stringify(
    {
      vegaVersion: vega.version,
      charts: CHARTS.map((c) => ({ name: c.name, signal: c.signal, note: c.note })),
      generated: cases.length,
      rendered,
      refused,
      cases: cases.map((c) => ({ name: c.name, signal: c.signal, value: c.value === undefined ? null : c.value })),
    },
    null,
    2,
  ) + '\n',
);

console.log(
  `Generated ${cases.length} case(s) from ${CHARTS.length} chart(s): ` +
    `${rendered} rendered, ${refused.length} refused by upstream.`,
);
