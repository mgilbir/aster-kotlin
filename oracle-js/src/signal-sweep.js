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
  {
    name: 'a-legend-title',
    signal: 'legendTitle',
    note: 'a signal that titles a legend, which is words inside a guide rather than beside the chart',
    values: ['Series', ['two', 'lines'], '', null, 0, 1e21],
    spec: base({
      signals: [{ name: 'legendTitle', value: 'Series' }],
      scales: [
        { name: 'colour', type: 'ordinal', domain: { data: 't', field: 'k' }, range: 'category' },
      ],
      legends: [{ fill: 'colour', title: { signal: 'legendTitle' } }],
      marks: [],
    }),
  },
  {
    name: 'a-view-size',
    signal: 'w',
    note: "the view's own width, which every scale ranged on it and the surface itself follow",
    values: [200, 0, 40, -100, 1e5, null, 'wide'],
    spec: base({
      signals: [{ name: 'w', value: 200 }],
      width: { signal: 'w' },
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
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
              y: { value: 0 },
              y2: { value: 60 },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-format-specifier',
    signal: 'fmt',
    note: "an axis's number format, where the signal is a specifier rather than a quantity",
    values: ['.2f', '', '%', 'not a format', '.99f', null, 42],
    spec: base({
      signals: [{ name: 'fmt', value: '.2f' }],
      scales: [{ name: 'y', type: 'linear', domain: [0, 1], range: 'height' }],
      axes: [{ orient: 'left', scale: 'y', tickCount: 3, format: { signal: 'fmt' } }],
      marks: [],
    }),
  },
  {
    name: 'a-colour-scheme',
    signal: 'scheme',
    note: 'the name of a scheme, which is looked up rather than read as a value',
    values: ['blues', 'BLUES', 'nosuchscheme', '', null, 7],
    spec: base({
      signals: [{ name: 'scheme', value: 'blues' }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
        { name: 'c', type: 'linear', domain: [0, 100], range: { scheme: { signal: 'scheme' } } },
      ],
      marks: [
        {
          type: 'rect',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              width: { scale: 'x', band: 1 },
              y: { value: 0 },
              y2: { value: 60 },
              fill: { scale: 'c', field: 'v' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-mark-shape',
    signal: 'shape',
    note: 'a symbol shape, which is a name upstream resolves to a path and not a measurement',
    values: ['circle', 'triangle', 'M0,0L8,8Z', 'nosuchshape', '', null],
    spec: base({
      signals: [{ name: 'shape', value: 'circle' }],
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
              size: { value: 200 },
              shape: { signal: 'shape' },
              fill: { value: '#4c78a8' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-scale-range',
    signal: 'rng',
    note: 'a scale range, the domain\'s opposite end, where a reversed one reverses the chart',
    values: [[0, 100], [100, 0], [50, 50], [], null, 'height'],
    spec: base({
      signals: [{ name: 'rng', value: [0, 100] }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
        { name: 'y', type: 'linear', domain: [0, 100], range: { signal: 'rng' } },
      ],
      axes: [{ orient: 'left', scale: 'y', tickCount: 3 }],
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
    name: 'an-axis-orientation',
    signal: 'side',
    note: "which side an axis sits on, which moves the plotting area rather than a mark",
    values: ['left', 'right', 'top', 'bottom', '', null, 'sideways'],
    spec: base({
      signals: [{ name: 'side', value: 'left' }],
      scales: [{ name: 'y', type: 'linear', domain: [0, 100], range: 'height' }],
      axes: [{ orient: { signal: 'side' }, scale: 'y', tickCount: 3 }],
      marks: [],
    }),
  },
  {
    name: 'a-label-limit',
    signal: 'lim',
    note: "how much room a label has before it is cut short, where the text itself changes",
    values: [0, 12, 1, 1e6, -5, null, 'wide'],
    spec: base({
      signals: [{ name: 'lim', value: 0 }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
      ],
      axes: [{ orient: 'bottom', scale: 'x', labelLimit: { signal: 'lim' } }],
      marks: [
        {
          type: 'text',
          encode: {
            update: {
              x: { value: 10 },
              y: { value: 30 },
              text: { value: 'a label long enough to be cut' },
              limit: { signal: 'lim' },
              fontSize: { value: 11 },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-curve',
    signal: 'curve',
    note: "how a line joins its points, which changes the outline without moving one of them",
    values: ['linear', 'step', 'monotone', 'basis', '', null, 'squiggle'],
    spec: base({
      signals: [{ name: 'curve', value: 'linear' }],
      scales: [
        { name: 'x', type: 'point', domain: { data: 't', field: 'k' }, range: 'width' },
        { name: 'y', type: 'linear', domain: [0, 100], range: 'height' },
      ],
      marks: [
        {
          type: 'line',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              y: { scale: 'y', field: 'v' },
              stroke: { value: '#4c78a8' },
              interpolate: { signal: 'curve' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-corner-radius',
    signal: 'radius',
    note: "how far a rect's corners are rounded, which is geometry a bounds check cannot see",
    values: [0, 6, 1e4, -4, 0.5, null, 'round'],
    spec: base({
      signals: [{ name: 'radius', value: 0 }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.2 },
      ],
      marks: [
        {
          type: 'rect',
          from: { data: 't' },
          encode: {
            update: {
              x: { scale: 'x', field: 'k' },
              width: { scale: 'x', band: 1 },
              y: { value: 0 },
              y2: { value: 60 },
              fill: { value: '#4c78a8' },
              cornerRadius: { signal: 'radius' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-clip-flag',
    signal: 'clipped',
    note: "whether a group hides what overflows it, which decides if marks are drawn at all",
    values: [true, false, 1, 0, '', null, 'yes'],
    spec: base({
      signals: [{ name: 'clipped', value: true }],
      marks: [
        {
          type: 'group',
          clip: { signal: 'clipped' },
          encode: {
            update: {
              x: { value: 0 },
              y: { value: 0 },
              width: { value: 60 },
              height: { value: 40 },
              stroke: { value: '#888' },
            },
          },
          marks: [
            {
              type: 'rect',
              encode: {
                update: {
                  x: { value: 10 },
                  y: { value: 10 },
                  width: { value: 120 },
                  height: { value: 20 },
                  fill: { value: '#4c78a8' },
                },
              },
            },
          ],
        },
      ],
    }),
  },
  {
    name: 'a-text-anchor',
    signal: 'anchor',
    note: "which way a label hangs off its point, where the anchor moves and the point does not",
    values: ['left', 'center', 'right', '', null, 'middle', 7],
    spec: base({
      signals: [{ name: 'anchor', value: 'left' }],
      marks: [
        {
          type: 'text',
          from: { data: 't' },
          encode: {
            update: {
              x: { value: 100 },
              y: { signal: '20 * datum.n' },
              text: { field: 'k' },
              align: { signal: 'anchor' },
              fontSize: { value: 12 },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-label-angle',
    signal: 'turn',
    note: "how far an axis label is turned, which changes the room the axis needs",
    values: [0, 45, -90, 360, 0.5, null, 'sideways'],
    spec: base({
      signals: [{ name: 'turn', value: 0 }],
      scales: [
        { name: 'x', type: 'band', domain: { data: 't', field: 'k' }, range: 'width', padding: 0.1 },
      ],
      axes: [{ orient: 'bottom', scale: 'x', labelAngle: { signal: 'turn' } }],
      marks: [],
    }),
  },
  {
    name: 'a-font-size',
    signal: 'pt',
    note: 'how large a label is set, which every measurement of it follows',
    values: [11, 24, 0, -4, 0.5, null, 'large'],
    spec: base({
      signals: [{ name: 'pt', value: 11 }],
      marks: [
        {
          type: 'text',
          encode: {
            update: {
              x: { value: 10 },
              y: { value: 40 },
              text: { value: 'measure me' },
              fontSize: { signal: 'pt' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-paint-order',
    signal: 'above',
    note: "which of two overlapping marks is drawn on top, which is order and not position",
    values: [0, 1, -1, 1e6, 0.5, null, 'top'],
    spec: base({
      signals: [{ name: 'above', value: 0 }],
      marks: [
        {
          type: 'rect',
          encode: {
            update: {
              x: { value: 0 },
              y: { value: 0 },
              width: { value: 60 },
              height: { value: 40 },
              fill: { value: '#4c78a8' },
            },
          },
        },
        {
          type: 'rect',
          zindex: { signal: 'above' },
          encode: {
            update: {
              x: { value: 20 },
              y: { value: 10 },
              width: { value: 60 },
              height: { value: 40 },
              fill: { value: '#f58518' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-band-padding',
    signal: 'gap',
    note: "how much of a band is left empty, which moves every bar and the step between them",
    values: [0.1, 0, 1, 0.999, -0.5, null, 'wide'],
    spec: base({
      signals: [{ name: 'gap', value: 0.1 }],
      scales: [
        {
          name: 'x',
          type: 'band',
          domain: { data: 't', field: 'k' },
          range: 'width',
          padding: { signal: 'gap' },
        },
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
              y: { value: 0 },
              y2: { value: 60 },
              fill: { value: '#4c78a8' },
            },
          },
        },
      ],
    }),
  },
  {
    name: 'a-domain-bound',
    signal: 'top',
    note: "one end of a domain pinned while the data decides the other",
    values: [100, 0, -50, 1e6, null, 'high'],
    spec: base({
      signals: [{ name: 'top', value: 100 }],
      scales: [
        {
          name: 'y',
          type: 'linear',
          domain: { data: 't', field: 'v' },
          domainMax: { signal: 'top' },
          range: 'height',
        },
      ],
      axes: [{ orient: 'left', scale: 'y', tickCount: 3 }],
      marks: [],
    }),
  },
  {
    name: 'a-dash-pattern',
    signal: 'dash',
    note: 'the on-and-off pattern of a stroke, where the signal holds a list rather than a number',
    values: [[4, 2], [], [0, 0], [3], null, 'dashed', 6],
    spec: base({
      signals: [{ name: 'dash', value: [4, 2] }],
      marks: [
        {
          type: 'rule',
          encode: {
            update: {
              x: { value: 0 },
              y: { value: 20 },
              x2: { value: 180 },
              y2: { value: 20 },
              stroke: { value: '#333' },
              strokeWidth: { value: 2 },
              strokeDash: { signal: 'dash' },
            },
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

/**
 * The same chart rendered **fresh**, with the signal's *initial* value already set.
 *
 * A difference between this engine and upstream after a write is one of two entirely different
 * things, and the tally could not tell them apart. Either upstream draws the same chart from scratch
 * — in which case this engine, which recompiles, is simply wrong and the case is a defect — or
 * upstream draws something a fresh render never produces, because it **kept** what it had. A scale
 * whose domain is overwritten with `null` keeps its old domain; a data-driven domain keeps the order
 * its surviving rows were in and appends the re-admitted ones; an axis keeps the tick items whose
 * values survived. None of those is reachable by compiling the specification again, and none of them
 * is a defect to fix.
 *
 * Guessing which was which cost two wrong write-ups before this existed. A null domain was recorded
 * as a robustness defect — 10935 pixels against 140 — until a fresh render showed upstream at 10925.
 * A fractional tick count was recorded as a truncation defect until a fresh render showed upstream
 * drawing exactly what truncating produces. Both were retention, and both readings looked obvious.
 *
 * So the reference carries the answer rather than leaving it to be re-derived: `retains` is true when
 * upstream's own two renders disagree.
 */
async function freshRender(one, scaleNames) {
  const spec = JSON.parse(JSON.stringify(one.spec));
  const signal = (spec.signals || []).find((s) => s.name === one.signal);
  if (!signal) return null;
  delete signal.update;
  signal.value = one.value;
  pinDeterminism();
  try {
    const view = new vega.View(vega.parse(spec), { renderer: 'none' });
    let failed = false;
    view.error = () => {
      failed = true;
    };
    await view.runAsync();
    if (failed) {
      await view.finalize();
      return null;
    }
    const out = {
      size: surfaceSize(view, spec),
      scales: normalizeScales(view, scaleNames),
      ...normalizeScene(view.scenegraph().root),
    };
    await view.finalize();
    return out;
  } catch {
    return null;
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
  const written = {
    size: surfaceSize(view, one.spec),
    scales: normalizeScales(view, scaleNames),
    ...normalizeScene(view.scenegraph().root),
  };
  const fresh = await freshRender(one, scaleNames);
  writeFileSync(
    join(referenceDir, `${one.name}.reference.json`),
    canonicalJson({
      vegaVersion: vega.version,
      spec: `${one.name}.vg.json`,
      signal: one.signal,
      value: one.value === undefined ? null : one.value,
      // **Whether upstream itself draws this differently fresh.** See [freshRender].
      retains: fresh === null ? null : canonicalJson(fresh) !== canonicalJson(written),
      ...written,
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
