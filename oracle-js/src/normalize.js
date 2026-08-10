/**
 * Flattens a Vega scenegraph into the normalized comparison model the Kotlin side also emits.
 *
 * Structure is deliberately *not* preserved. Vega nests marktypes inside group items; this engine
 * produces a flat list of scene nodes. Comparing tree shapes would therefore report a difference on
 * every fixture without saying anything about correctness. What matters, and what this captures, is
 * what PROJECT_BRIEF.md 18.4 asks for: mark count, mark types, coordinates and bounds — expressed in
 * **absolute** coordinates so neither side's grouping choices affect the result.
 *
 * Text is reported by content and anchor rather than by glyph bounds, because font metrics legitimately
 * differ between a browser and Android (see docs/adr/0006).
 */

import { canonicalNumber, DEFAULT_PRECISION } from './canonical.js';
import {
  curveBasis,
  curveBasisClosed,
  curveCardinal,
  curveCardinalClosed,
  curveLinearClosed,
  curveMonotoneX,
  curveMonotoneY,
  curveNatural,
  curveStep,
  curveStepAfter,
  curveStepBefore,
  line,
} from 'd3-shape';
import { sceneVisit } from 'vega-scenegraph';

/** Channels compared per mark type. Anything not listed here is ignored on both sides. */
const GEOMETRY_CHANNELS = {
  group: ['x', 'y', 'width', 'height'],
  rect: ['x', 'y', 'width', 'height'],
  rule: ['x', 'y', 'x2', 'y2'],
  line: ['x', 'y'],
  area: ['x', 'y', 'y2'],
  symbol: ['x', 'y', 'size'],
  text: ['x', 'y'],
  image: ['x', 'y', 'width', 'height'],
  // An arc's centre says nothing about the wedge it drew, so compare the drawn extent instead. This
  // engine builds an arc as a path with the centre already applied, and has no centre to report.
  arc: [],
  path: ['x', 'y'],
  trail: ['x', 'y'],
  shape: ['x', 'y'],
};

const STYLE_CHANNELS = ['fill', 'stroke', 'strokeWidth', 'opacity', 'fillOpacity', 'strokeOpacity'];
const TEXT_CHANNELS = ['text', 'align', 'baseline', 'font', 'fontSize', 'fontWeight', 'fontStyle', 'angle'];

/**
 * FNV-1a over a canvas's pixels, in the packed `0xAARRGGBB` order the Kotlin scene stores.
 *
 * Read back with `getImageData`, which is the only way to see what a `heatmap` actually painted:
 * upstream keeps a `<canvas>` on the scene item, and comparing an image mark by its box alone would
 * let a blank one through. Returned as a *string*, because the value overflows a double's exact
 * integer range and JSON would round it.
 */
function rasterDigest(image) {
  if (!image || typeof image.getContext !== 'function') return undefined;
  const { width, height } = image;
  if (!width || !height) return undefined;
  const pix = image.getContext('2d').getImageData(0, 0, width, height).data;
  // BigInt, because the hash is 64-bit and a double loses the low bits after 2^53.
  const MASK = (1n << 64n) - 1n;
  let hash = 0xcbf29ce484222325n;
  for (let i = 0; i < pix.length; i += 4) {
    const packed = (BigInt(pix[i + 3]) << 24n) | (BigInt(pix[i]) << 16n) | (BigInt(pix[i + 1]) << 8n) | BigInt(pix[i + 2]);
    let value = packed;
    for (let b = 0; b < 4; ++b) {
      hash = (hash ^ (value & 0xffn)) & MASK;
      hash = (hash * 0x100000001b3n) & MASK;
      value >>= 8n;
    }
  }
  // As a signed 64-bit value, which is what a Kotlin `Long` prints.
  return BigInt.asIntN(64, hash).toString();
}

/**
 * A numeric channel written as a string, read as the number it is.
 *
 * A specification may write `"labelFontSize": "12"`, and upstream carries that straight onto the
 * scene item and hands the string to its renderer, which emits `font-size="12px"`. This engine
 * parses it once and stores 12. The two agree on what gets drawn, and comparing `"12"` against `12`
 * as different *types* reported a difference that does not exist — while quietly hiding any real
 * one on that channel, since the string form matched nothing on the other side either.
 *
 * Deliberately narrow: only the channels this harness already treats as numbers, and only when the
 * string parses as a finite number. `text` is not one of those — a label reading "12" is a label.
 */
function numericIfPossible(value) {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (trimmed === '' || !Number.isFinite(+trimmed)) return value;
  return +trimmed;
}

/**
 * A dash pattern, joined into one comparable string.
 *
 * Reported separately from the scalar channels because it is an array, and added because it was the
 * fourth thing this harness compared its way past: a dashed gridline and a solid one have identical
 * geometry, identical colour and identical width, so nothing else here can tell them apart.
 */
function dashOf(item) {
  const dash = item.strokeDash;
  if (!Array.isArray(dash) || dash.length === 0) return undefined;
  return dash.join(',');
}

/**
 * @param root the value of `view.scenegraph().root`
 * @returns {{marks: Array<object>}} marks in paint order, absolute coordinates
 */
export function normalizeScene(root, precision = DEFAULT_PRECISION) {
  const marks = [];
  walkMarktype(root, 0, 0, marks, precision);
  return { marks };
}

/**
 * Mark types Vega emits one item per datum for, but which draw as a single connected shape.
 *
 * This engine produces one path node for the whole series instead, so comparing item-for-item would
 * report a structural difference on every line chart. Both sides collapse to one record carrying the
 * point list, which is what actually has to agree.
 */
const SERIES_TYPES = new Set(['line', 'area']);

/**
 * A `trail` is a series upstream and one filled shape here.
 *
 * Unlike a line, its outline is not derivable from the item positions — the width at each point
 * turns the series into a run of capsules — and unlike a step or a spline, `d3-shape` has no curve
 * to ask, because the generator is Vega's own and emits true circular arcs where this engine emits
 * cubics. So the two are compared by the extent they actually cover, the way an arc is. That
 * catches a trail too thick, too thin or in the wrong place, and would not catch its segments drawn
 * in the wrong order.
 */
const EXTENT_ONLY_SERIES = new Set(['trail']);

/** A marktype node: `{marktype, role, items: [...]}`. */
function walkMarktype(marktype, dx, dy, out, precision) {
  const type = marktype.marktype || 'group';

  if (SERIES_TYPES.has(type) && (marktype.items || []).length > 0) {
    out.push(seriesRecord(type, marktype, dx, dy, precision));
    return;
  }

  if (EXTENT_ONLY_SERIES.has(type) && (marktype.items || []).length > 0) {
    out.push(extentRecord(type, marktype, dx, dy, precision));
    return;
  }

  for (const item of marktype.items || []) {
    if (type === 'group') {
      // A group item positions its children; Vega stores child coordinates relative to it.
      const ox = dx + (item.x || 0);
      const oy = dy + (item.y || 0);
      // Only emit the group itself when it paints something; an invisible layout group would be a
      // structural difference, not a visual one.
      if (item.fill || item.stroke) {
        out.push(record('group', marktype.role, item, ox - (item.x || 0), oy - (item.y || 0), precision));
      }
      // Paint order, not declaration order: a mark's `zindex` decides which of two overlapping
      // marks is on top, and this list is compared positionally, so emitting it any other way would
      // let a chart whose grid lines paint over its data match a reference where they do not.
      // `sceneVisit` is Vega's own traversal rather than a reimplementation of its rule, which is
      // less obvious than it looks — a negative `zindex` raises a mark rather than sinking it.
      sceneVisit(item, child => walkMarktype(child, ox, oy, out, precision));
    } else {
      out.push(record(type, marktype.role, item, dx, dy, precision));
    }
  }
}

/**
 * The curves both engines can draw, so the comparison can see the outline rather than the points.
 *
 * `monotone` is two curves: Vega picks between d3's X and Y forms from the mark's `orient`, so this
 * is resolved per item rather than by name alone.
 */
const CURVES = {
  'step': curveStep,
  'step-before': curveStepBefore,
  'step-after': curveStepAfter,
  'basis': curveBasis,
  'cardinal': curveCardinal,
  'natural': curveNatural,
  'linear-closed': curveLinearClosed,
  'basis-closed': curveBasisClosed,
  'cardinal-closed': curveCardinalClosed,
};

/** The return leg of an area steps the opposite way round; every other curve is symmetric. */
const MIRRORED = {
  'step-before': 'step-after',
  'step-after': 'step-before',
};

function curveFor(interpolate, orient) {
  if (interpolate === 'monotone') {
    return orient === 'horizontal' ? curveMonotoneY : curveMonotoneX;
  }
  return CURVES[interpolate];
}

/**
 * The corners d3 puts between a series' points, read back out of d3 rather than reconstructed.
 *
 * Collected through a recording context rather than by parsing the path string: `d3-path` rounds
 * its output to three decimals, which is finer than any pixel and far coarser than this
 * comparison's tolerance.
 */
function expandCurve(points, curve) {
  if (!curve) return {points, closed: false};
  const collected = [];
  let closed = false;
  const context = {
    moveTo: (x, y) => collected.push([x, y]),
    lineTo: (x, y) => collected.push([x, y]),
    // A cubic's control points are part of the outline: two curves through the same anchors are
    // different shapes, and comparing anchors alone would not see it.
    bezierCurveTo: (x1, y1, x2, y2, x, y) =>
      collected.push([x1, y1], [x2, y2], [x, y]),
    // Whether the outline joins back onto itself, which no point list can express. `linear-closed`
    // emits exactly the same points as `linear` and draws a polygon rather than a polyline, so
    // without this the comparison cannot tell the two apart. Read from d3 rather than inferred from
    // the method's name: the closed *splines* wrap their control window instead of emitting a `Z`,
    // and only d3 knows which does which.
    closePath: () => { closed = true; },
  };
  line().curve(curve).x(p => p[0]).y(p => p[1]).context(context)(points);
  return {points: collected.length ? collected : points, closed};
}

/**
 * One record for a series compared by the ground it covers rather than by its outline.
 *
 * The bounds come from the marktype rather than from any one item, because the shape is the union
 * of every segment.
 */
function extentRecord(type, marktype, dx, dy, precision) {
  const first = marktype.items[0];
  const b = marktype.bounds;
  const entry = {
    type,
    role: marktype.role || null,
    shapeLeft: canonicalNumber(b.x1 + dx, precision),
    shapeTop: canonicalNumber(b.y1 + dy, precision),
    shapeWidth: canonicalNumber(b.x2 - b.x1, precision),
    shapeHeight: canonicalNumber(b.y2 - b.y1, precision),
  };
  for (const channel of STYLE_CHANNELS) {
    if (first[channel] !== undefined) entry[channel] = canonicalNumber(first[channel], precision);
  }
  if (entry.fillOpacity !== undefined && entry.fill === undefined) delete entry.fillOpacity;
  if (entry.strokeOpacity !== undefined && entry.stroke === undefined) delete entry.strokeOpacity;
  return entry;
}

/**
 * One record for a whole line or area: its point list plus the style of its first item.
 *
 * Vega repeats the style on every item of a series, so reading it from the first is faithful.
 */
function seriesRecord(type, marktype, dx, dy, precision) {
  const items = marktype.items;
  const first = items[0];
  const entry = { type, role: marktype.role || null };

  // The outline, in the order it would be drawn: forward along the primary boundary, and for an area
  // back along the secondary one. Both sides emit this same order so the lists compare textually.
  const points = [];
  const push = (x, y) => {
    points.push(canonicalNumber((x || 0) + dx, precision));
    points.push(canonicalNumber((y || 0) + dy, precision));
  };
  // A step interpolation puts corners between the data points, so the drawn outline is not the
  // item list. Rather than reimplementing the staircase here — a second copy that could be wrong
  // the same way the port is — the corners come from d3-shape itself, which is what Vega draws
  // with. Every other curve family is reported as unsupported by the engine, so this only has to
  // cover the steps.
  const curve = curveFor(first.interpolate, first.orient);

  const primary = items.map(i => [i.x || 0, i.y || 0]);
  const drawn = expandCurve(primary, curve);
  for (const [x, y] of drawn.points) push(x, y);
  if (type === 'area') {
    const secondary = [...items]
      .reverse()
      .map(i => [i.x2 !== undefined ? i.x2 : i.x || 0, i.y2 !== undefined ? i.y2 : i.y || 0]);
    const back = expandCurve(
      secondary,
      curveFor(MIRRORED[first.interpolate] || first.interpolate, first.orient)
    );
    for (const [x, y] of back.points) push(x, y);
  } else {
    // An area's outline is closed on both sides by construction, so the flag would say nothing
    // there; on a line it is the whole difference between a polyline and a polygon.
    entry.closed = drawn.closed ? 1 : 0;
  }
  entry.points = points.join(' ');
  if (first.interpolate) entry.interpolate = first.interpolate;

  for (const channel of STYLE_CHANNELS) {
    if (first[channel] !== undefined) entry[channel] = canonicalNumber(first[channel], precision);
  }
  const seriesDash = dashOf(first);
  if (seriesDash !== undefined) entry.strokeDash = seriesDash;
  return entry;
}

function record(type, role, item, dx, dy, precision) {
  const entry = { type, role: role || null };
  const channels = GEOMETRY_CHANNELS[type] || ['x', 'y'];

  // Vega keeps a text mark's dx/dy as separate render-time offsets; this engine folds them into the
  // anchor, which draws identically. Fold them here too so the two agree — through the rotation,
  // because Vega applies them *inside* it (`translate(x,y) rotate(a) translate(dx,dy)`), so on rotated
  // text an offset runs along the text rather than along the page.
  let textDx = 0, textDy = 0;
  if (type === 'text' && (item.dx || item.dy)) {
    const dx = item.dx || 0, dy = item.dy || 0, a = ((item.angle || 0) * Math.PI) / 180;
    textDx = dx * Math.cos(a) - dy * Math.sin(a);
    textDy = dx * Math.sin(a) + dy * Math.cos(a);
  }

  for (const channel of channels) {
    let value = item[channel];
    if (value === undefined) {
      // Vega defaults a missing x2/y2 to the corresponding x/y.
      if (channel === 'x2') value = item.x;
      else if (channel === 'y2') value = item.y;
      else continue;
    }
    if (typeof value !== 'number') continue;
    const offset =
      channel.startsWith('x') ? dx + textDx : channel.startsWith('y') ? dy + textDy : 0;
    // Width, height and size are extents, not positions, so they take no offset.
    const isExtent = channel === 'width' || channel === 'height' || channel === 'size';
    entry[channel] = canonicalNumber(isExtent ? value : value + offset, precision);
  }

  // An image's anchor is not where it is drawn: Vega keeps `align` and `baseline` on the item and
  // offsets only at paint time. Comparing the drawn corner would agree with an engine that folded
  // the offset into `x`, and that engine's scene would then disagree with Vega's on every centred
  // image — so compare the anchor and the two channels that say what it means.
  if (type === 'image') {
    entry.url = item.url;
    entry.align = item.align || 'left';
    entry.baseline = item.baseline || 'top';
    // An image mark that carries *pixels* rather than an address — a `heatmap`'s output — is
    // otherwise compared only by its box, so a blank raster in the right place would pass. The
    // digest is a cheap stand-in for the pixels themselves, which no reference file should hold:
    // the same FNV-1a over the same packed ARGB values the Kotlin side computes.
    const digest = rasterDigest(item.image);
    if (digest !== undefined) {
      entry.rasterWidth = item.image.width;
      entry.rasterHeight = item.image.height;
      entry.rasterDigest = digest;
    }
  }

  for (const channel of STYLE_CHANNELS) {
    if (item[channel] !== undefined) entry[channel] = canonicalNumber(item[channel], precision);
  }
  // Vega sets a legend symbol's strokeWidth whether or not it has a stroke colour, so an unstroked
  // swatch carries a width that paints nothing. This engine has no way to say "a width with no
  // stroke", and would not want one, so drop the width when there is nothing to draw it with.
  if (entry.strokeWidth !== undefined && entry.stroke === undefined) delete entry.strokeWidth;
  // Same reasoning for the opacities. Vega carries `fillOpacity` on an item whether or not it has a
  // fill, and this engine's scene graph puts the opacity *on* the fill, so an unfilled mark has
  // nowhere to keep one. Nothing is drawn differently either way.
  if (entry.fillOpacity !== undefined && entry.fill === undefined) delete entry.fillOpacity;
  if (entry.strokeOpacity !== undefined && entry.stroke === undefined) delete entry.strokeOpacity;
  const dash = dashOf(item);
  if (dash !== undefined) entry.strokeDash = dash;
  // A symbol's `size` channel says nothing about the outline it actually draws, and Vega ships its own
  // symbol table rather than d3's — so compare the drawn extent, not just the requested size.
  //
  // A `path` mark is the same case and was for a long time the wider hole: its whole geometry lives
  // in a path string, and the only channels compared were the anchor it hangs from. Two engines
  // could agree on `x` and `y` and draw completely different outlines — which is exactly what a
  // `linkpath` transform emitting the wrong shape would look like.
  if ((type === 'symbol' || type === 'arc' || type === 'path') && item.bounds) {
    entry.shapeLeft = canonicalNumber(item.bounds.x1 + dx, precision);
    entry.shapeTop = canonicalNumber(item.bounds.y1 + dy, precision);
    entry.shapeWidth = canonicalNumber(item.bounds.x2 - item.bounds.x1, precision);
    entry.shapeHeight = canonicalNumber(item.bounds.y2 - item.bounds.y1, precision);
  }
  if (type === 'text') {
    for (const channel of TEXT_CHANNELS) {
      // `null` counts as absent, not as the four letters `String(null)` spells. A text item can
      // genuinely have no text — a banded legend's lowest label has no lower bound to write — and
      // recording that as "null" would let a label that really said "null" compare equal to it.
      if (item[channel] !== undefined && item[channel] !== null) {
        entry[channel] =
          channel === 'text'
            ? item[channel]
            : canonicalNumber(numericIfPossible(item[channel]), precision);
      }
    }
    // A text mark's content is whatever the field held, so a numeric field gives a numeric `text`.
    // Both engines draw its string form, so compare that rather than its type — and an **array** is
    // upstream's multi-line form, so its lines are joined the way both engines lay them out rather
    // than the way `String()` would, with commas.
    //
    // A **null** text is not a text at all, and is not an empty one either: the item exists and is
    // measured, and it has nothing to draw. `String(null)` is the four letters "null", and writing
    // those into the reference had this engine dutifully drawing them — the first band of a
    // discretizing scale's legend reaches to negative infinity and has no lower bound to write.
    // Dropping the property instead is what `TextNode.absent` models on this side, so the two agree
    // without either of them inventing an empty string somebody could have meant.
    if (entry.text === null) {
      delete entry.text;
    } else if (entry.text !== undefined) {
      entry.text = Array.isArray(entry.text) ? entry.text.join('\n') : String(entry.text);
    }
  }
  return entry;
}

/**
 * One value of a scale's domain or range, as something the two engines can be held to.
 *
 * A `Date` becomes the instant it denotes. `String(date)` would otherwise pin the comparison to
 * Node's own wording of the machine's zone — "Sun Jan 01 2012 00:00:00 GMT+0100 (Central European
 * Standard Time)" — which says nothing a reference should assert and hides the number underneath.
 * The instant is exactly as strict and is what a temporal domain actually is.
 */
function scaleValue(value, precision) {
  if (value instanceof Date) return canonicalNumber(+value, precision);
  return typeof value === 'number' ? canonicalNumber(value, precision) : String(value);
}

/** Scale outputs, compared exactly: they are pure arithmetic with no rendering in the way. */
export function normalizeScales(view, names, precision = DEFAULT_PRECISION) {
  const result = {};
  for (const name of names) {
    let scale;
    try {
      scale = view.scale(name);
    } catch {
      continue;
    }
    if (!scale) continue;
    const entry = {
      domain: scale.domain().map((d) => scaleValue(d, precision)),
      range: scale.range().map((r) => scaleValue(r, precision)),
    };
    if (typeof scale.bandwidth === 'function') {
      entry.bandwidth = canonicalNumber(scale.bandwidth(), precision);
      entry.step = canonicalNumber(scale.step(), precision);
    }
    if (typeof scale.ticks === 'function') {
      entry.ticks = scale.ticks().map((t) => canonicalNumber(t, precision));
    }
    result[name] = entry;
  }
  return result;
}
