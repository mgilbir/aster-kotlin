// Records what upstream's `label` transform decides, as vectors a Kotlin test can replay.
//
// The transform this repository could not check. `vega-label` builds its occupancy bitmap by
// drawing the avoided marks into a canvas and reading the alpha back; `vega-canvas` answers null
// under Node, so upstream's own transform throws there and `SUPPORTED_FEATURES.md` has said since
// it was written that "there is no reference to compare against".
//
// There is, with the same trick `record-wordcloud.mjs` uses: install `canvas` for the run and let
// upstream rasterise properly. That is why this is a script of its own rather than part of the
// reference corpus.
//
// **Needs `canvas`**, which `oracle-js` does not depend on and must not: installing it switches
// upstream's text measurement from its no-canvas fallback to real font metrics, and the fixture
// corpus is recorded against the fallback. Adding it to `package.json` would silently move every
// axis title in the corpus. So this asks for it explicitly and says so when it is missing.
//
// **The text metrics are recorded too, and that is the load-bearing part.** Canvas changes
// upstream's text measurement, so a label measured here is a different width from the same label
// measured by the engine under test — and a label's width decides whether it fits, which decides
// its anchor and whether it is dropped at all. Comparing placements without also handing over the
// widths would compare fonts and call the answer "occupancy".
//
// So each label's measured width goes into the vectors and the Kotlin side measures with those.
// What is then compared is *which anchor each label took* and *which labels were dropped*, given
// the same boxes on the same marks — which is exactly what the occupancy bitmap decides, and
// nothing else.
//
// The output is committed, like the wordcloud vectors and for the same reason: the gate has no
// canvas, so it cannot rebuild them.
//
//   npm install --no-save canvas
//   node src/record-label.mjs ../test-fixtures/upstream-vectors-label.json

import {writeFileSync} from 'node:fs';
import * as vega from 'vega';
import {textMetrics} from 'vega-scenegraph';

try {
  await import('canvas');
} catch {
  console.error(
    'record-label.mjs needs the `canvas` package, which oracle-js deliberately does not depend on\n' +
    "— installing it changes upstream's text measurement and would move every reference in the\n" +
    'fixture corpus. Install it for this run only:\n' +
    '\n' +
    '  npm install --no-save canvas\n'
  );
  process.exit(2);
}

const POINTS = [
  {x: 20, y: 20, t: 'alpha'}, {x: 60, y: 40, t: 'beta'},
  {x: 100, y: 30, t: 'gamma'}, {x: 140, y: 70, t: 'delta'},
  {x: 40, y: 120, t: 'epsilon'}, {x: 120, y: 150, t: 'zeta'},
  {x: 170, y: 110, t: 'eta'}, {x: 80, y: 180, t: 'theta'}
];

// Deliberately crowded: eight points inside a quarter of the surface, so the bitmap has to refuse
// some labels outright. This is the case the row is about — one pixel decides whether a label fits.
const CROWDED = [
  {x: 40, y: 40, t: 'one'}, {x: 52, y: 44, t: 'two'}, {x: 64, y: 38, t: 'three'},
  {x: 46, y: 56, t: 'four'}, {x: 58, y: 60, t: 'five'}, {x: 70, y: 52, t: 'six'},
  {x: 44, y: 70, t: 'seven'}, {x: 62, y: 74, t: 'eight'}
];

const dots = (values, size) => ({
  type: 'symbol', name: 'dots', from: {data: 'pts'},
  encode: {enter: {x: {field: 'x'}, y: {field: 'y'}, size: {value: size},
                   fill: {value: '#4c78a8'}}}
});

const labels = transform => ({
  type: 'text', name: 'labels', from: {data: 'dots'},
  encode: {enter: {text: {field: 'datum.t'}, fill: {value: '#000000'},
                   fontSize: {value: 11}}},
  transform: [transform]
});

const chart = (values, size, transform) => ({
  $schema: 'https://vega.github.io/schema/vega/v6.json',
  width: 200, height: 200, padding: 5,
  data: [{name: 'pts', values}],
  marks: [dots(values, size), labels(transform)]
});

// Each scenario turns one knob, so a disagreement points at the knob rather than at "labels".
const SCENARIOS = [
  {
    name: 'four-anchors',
    why: 'the ordinary case: room for every label, and the anchor order decides which side it takes',
    spec: chart(POINTS, 200, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left'], offset: [3], avoidBaseMark: true
    })
  },
  {
    name: 'eight-anchors',
    why: 'the diagonals too, which are the anchors scaled by 1/sqrt(2)',
    spec: chart(POINTS, 200, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left',
               'top-left', 'top-right', 'bottom-left', 'bottom-right'],
      offset: [4], avoidBaseMark: true
    })
  },
  {
    name: 'crowded-drops',
    why: 'eight points in a quarter of the surface, so the bitmap has to refuse some outright',
    spec: chart(CROWDED, 400, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left'], offset: [2], avoidBaseMark: true
    })
  },
  {
    name: 'no-base-mark',
    why: 'the same crowd without avoiding the dots, so only label-on-label collisions count',
    spec: chart(CROWDED, 400, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left'], offset: [2], avoidBaseMark: false
    })
  },
  {
    name: 'large-offset',
    why: 'a wide offset pushes labels off the surface, which is a different refusal from a collision',
    spec: chart(POINTS, 200, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left'], offset: [22], avoidBaseMark: true
    })
  },
  {
    name: 'big-marks',
    why: 'large symbols occupy more of the bitmap, so more labels are pushed outward or dropped',
    spec: chart(POINTS, 2000, {
      type: 'label', size: [{signal: 'width'}, {signal: 'height'}],
      anchor: ['top', 'bottom', 'right', 'left'], offset: [3], avoidBaseMark: true
    })
  }
];

const out = [];
for (const scenario of SCENARIOS) {
  const view = new vega.View(vega.parse(scenario.spec), {renderer: 'none'});
  await view.runAsync();
  const mark = view.scenegraph().root.items[0].items.find(m => m.name === 'labels');
  out.push({
    name: scenario.name,
    why: scenario.why,
    spec: scenario.spec,
    // `opacity` is how upstream drops a label it cannot place: the item stays and is drawn
    // invisibly, which is why the count of items is not the count of labels shown.
    labels: mark.items.map(it => ({
      text: it.text,
      // Upstream's own measurement of this label, so the comparison is of placement rather than of
      // fonts. `textMetrics.width` is what `vega-label` itself calls.
      width: textMetrics.width(it, it.text),
      fontSize: it.fontSize,
      align: it.align,
      baseline: it.baseline,
      opacity: it.opacity,
      // Rounded to the pixel the bitmap works in. The sub-pixel part is font metrics, which are
      // this run's canvas fonts and not something the engine under test can or should match.
      x: Math.round(it.x),
      y: Math.round(it.y)
    }))
  });
}

const path = process.argv[2] || '../test-fixtures/upstream-vectors-label.json';
writeFileSync(path, JSON.stringify({
  package: 'vega-label',
  version: JSON.parse(
    (await import('node:fs')).readFileSync('node_modules/vega/package.json', 'utf8')
  ).version,
  note: 'Recorded with `canvas` installed, which oracle-js does not depend on. ' +
        'Anchors and drops are the comparable part; x/y are this run\'s font metrics.',
  scenarios: out
}, null, 1) + '\n', 'utf8');
console.log(`Recorded ${out.length} label scenarios, ` +
            `${out.reduce((n, s) => n + s.labels.length, 0)} labels, to ${path}`);
