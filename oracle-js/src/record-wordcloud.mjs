// Records what upstream's `wordcloud` does, as vectors a Kotlin test can replay.
//
// Two things come out, and the split is the whole point of the file.
//
// **The sprites.** Upstream's collision detection rasterises each word through a canvas 2D context
// and reads `getImageData` back as one bit per pixel. Nothing in `:vega-dataflow` rasterises
// glyphs, and nothing should — a font belongs to the host — so the mask is recorded as an *input*
// rather than reproduced. `cloudSprite` below is `vega-wordcloud/src/CloudLayout.js`'s own
// function, using the same canvas calls in the same order against the same pinned package.
//
// **The placements.** Upstream's `wordcloud` transform run on the same words with the generator
// seeded, which is what the layout port has to reproduce given those sprites.
//
// Together they check the thing that can be checked: hand the port upstream's own pixels and it
// must place every word exactly where upstream placed it. What is left over is glyph rasterisation,
// which is recorded here and is a host's job everywhere else.
//
// **Needs `canvas`**, which `oracle-js` does not depend on and must not: installing it switches
// upstream's text measurement from its no-canvas fallback to real font metrics, and the 196-fixture
// reference corpus is recorded against the fallback. Adding it to `package.json` would silently
// move every axis title in the corpus. So this script asks for it explicitly and says so when it is
// missing.
//
//   node src/record-wordcloud.mjs <out.json>

import {writeFileSync} from 'node:fs';
import * as vega from 'vega';

let NodeCanvas;
try {
  NodeCanvas = await import('canvas');
} catch {
  console.error(
    'record-wordcloud.mjs needs the `canvas` package, which oracle-js deliberately does not depend\n' +
    'on — installing it changes upstream\'s text measurement and would move every reference in the\n' +
    'fixture corpus. Install it for this run only:\n' +
    '\n' +
    '  npm install --no-save canvas\n'
  );
  process.exit(2);
}

const SEED = 42;
const cw = 1 << 11 >> 5;
const ch = 1 << 11;
const cloudRadians = Math.PI / 180;

// The words, chosen so the layout has something to do: a wide range of sizes so the sort matters,
// a word long enough to be refused at the largest size, and both cases so the blank-row trimming
// above the glyphs has an effect worth seeing.
const WORDS = [
  {text: 'visualization', count: 100}, {text: 'grammar', count: 84},
  {text: 'Vega', count: 72},          {text: 'interaction', count: 66},
  {text: 'scale', count: 58},         {text: 'ENCODING', count: 51},
  {text: 'data', count: 45},          {text: 'transform', count: 40},
  {text: 'signal', count: 35},        {text: 'mark', count: 31},
  {text: 'axis', count: 27},          {text: 'legend', count: 24},
  {text: 'layout', count: 21},        {text: 'pixel', count: 18},
  {text: 'glyph', count: 16},         {text: 'w', count: 14},
  {text: 'lowercase', count: 12},     {text: 'CAPS', count: 10},
];

const SIZE = [400, 300];
const FONT = 'Helvetica';
const FONT_SIZE_RANGE = [10, 56];

function getContext(canvas) {
  canvas.width = canvas.height = 1;
  const ratio = Math.sqrt(canvas.getContext('2d').getImageData(0, 0, 1, 1).data.length >> 2);
  canvas.width = (cw << 5) / ratio;
  canvas.height = ch / ratio;
  const context = canvas.getContext('2d');
  context.fillStyle = context.strokeStyle = 'red';
  context.textAlign = 'center';
  return {context, ratio};
}

// `vega-wordcloud/src/CloudLayout.js`, cloudSprite — transcribed, not adapted. The one change is
// that it records each word's mask instead of leaving it on the tag.
function cloudSprite(contextAndRatio, data) {
  const c = contextAndRatio.context, ratio = contextAndRatio.ratio;
  c.clearRect(0, 0, (cw << 5) / ratio, ch / ratio);
  let x = 0, y = 0, maxh = 0;
  const n = data.length;
  let di = -1, d, w, w32, h, i, j;
  while (++di < n) {
    d = data[di];
    c.save();
    c.font = d.style + ' ' + d.weight + ' ' + ~~((d.size + 1) / ratio) + 'px ' + d.font;
    w = c.measureText(d.text + 'm').width * ratio;
    d.measured = w;  // before rounding: what CloudSprites.measure has to answer
    h = d.size << 1;
    if (d.rotate) {
      const sr = Math.sin(d.rotate * cloudRadians), cr = Math.cos(d.rotate * cloudRadians);
      const wcr = w * cr, wsr = w * sr, hcr = h * cr, hsr = h * sr;
      w = (Math.max(Math.abs(wcr + hsr), Math.abs(wcr - hsr)) + 0x1f) >> 5 << 5;
      h = ~~Math.max(Math.abs(wsr + hcr), Math.abs(wsr - hcr));
    } else {
      w = (w + 0x1f) >> 5 << 5;
    }
    if (h > maxh) maxh = h;
    if (x + w >= (cw << 5)) { x = 0; y += maxh; maxh = 0; }
    if (y + h >= ch) break;
    c.translate((x + (w >> 1)) / ratio, (y + (h >> 1)) / ratio);
    if (d.rotate) c.rotate(d.rotate * cloudRadians);
    c.fillText(d.text, 0, 0);
    if (d.padding) { c.lineWidth = 2 * d.padding; c.strokeText(d.text, 0, 0); }
    c.restore();
    d.width = w; d.height = h; d.xoff = x; d.yoff = y;
    d.x1 = w >> 1; d.y1 = h >> 1; d.x0 = -d.x1; d.y0 = -d.y1;
    d.hasText = true;
    x += w;
  }
  const pixels = c.getImageData(0, 0, (cw << 5) / ratio, ch / ratio).data;
  while (--di >= 0) {
    d = data[di];
    if (!d.hasText) continue;
    w = d.width; w32 = w >> 5; h = d.y1 - d.y0;
    // The **untrimmed** mask, which is what the Kotlin side is handed: the trimming is part of the
    // port and has to be exercised rather than baked in here.
    const raw = [];
    for (i = 0; i < h * w32; i++) raw[i] = 0;
    for (j = 0; j < h; j++) {
      for (i = 0; i < w; i++) {
        const k = w32 * j + (i >> 5);
        const m = pixels[((d.yoff + j) * (cw << 5) + (d.xoff + i)) << 2] ? 1 << (31 - (i % 32)) : 0;
        raw[k] |= m;
      }
    }
    d.mask = raw;
  }
}

const sizeScale = vega.scale('sqrt')()
  .domain([Math.min(...WORDS.map(w => w.count)), Math.max(...WORDS.map(w => w.count))])
  .range(FONT_SIZE_RANGE);

// The tags exactly as `cloud.layout()` builds them, in its sort order.
const tags = WORDS.map(w => ({
  text: w.text, font: FONT, style: 'normal', weight: 'normal', rotate: 0,
  size: ~~(sizeScale(w.count) + 1e-14), padding: 1,
  xoff: 0, yoff: 0, x1: 0, y1: 0, x0: 0, y0: 0, hasText: false, sprite: null,
})).sort((a, b) => b.size - a.size);

cloudSprite(getContext(NodeCanvas.createCanvas(1, 1)), tags);

// And what the transform itself produced, with the generator seeded the way a chart seeds it.
vega.setRandom(vega.randomLCG(SEED));
const spec = {
  width: SIZE[0], height: SIZE[1], padding: 0,
  data: [{
    name: 'cloud', values: WORDS,
    transform: [{
      type: 'wordcloud', size: SIZE, text: {field: 'text'}, rotate: 0,
      font: FONT, fontSize: {field: 'count'}, fontSizeRange: FONT_SIZE_RANGE, padding: 1,
      spiral: 'archimedean',
    }],
  }],
  marks: [],
};
const view = new vega.View(vega.parse(spec), {renderer: 'none'}).initialize();
await view.runAsync();

const placements = view.data('cloud').map(row => ({
  text: row.text, x: row.x, y: row.y, fontSize: row.fontSize, angle: row.angle,
}));

const out = {
  note: 'Recorded from the pinned vega by oracle-js/src/record-wordcloud.mjs. Do not hand-edit.',
  vegaVersion: vega.version,
  seed: SEED,
  size: SIZE,
  font: FONT,
  fontSizeRange: FONT_SIZE_RANGE,
  words: WORDS,
  sprites: tags.map(t => ({
    text: t.text, size: t.size, rotate: t.rotate, padding: t.padding,
    measured: t.measured,
    hasText: t.hasText, width: t.width, height: t.height,
    x0: t.x0, y0: t.y0, x1: t.x1, y1: t.y1,
    mask: t.mask ?? null,
  })),
  placements,
};

const target = process.argv[2] ?? 'wordcloud-vectors.json';
writeFileSync(target, JSON.stringify(out));
const lit = out.sprites.reduce((a, s) => a + (s.mask ?? []).filter(Boolean).length, 0);
console.log(
  `Wrote ${target}: ${out.sprites.length} sprites (${lit} non-empty mask words), ` +
  `${placements.filter(p => Number.isFinite(p.x)).length}/${placements.length} words placed`
);
