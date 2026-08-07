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
  curveCardinal,
  curveMonotoneX,
  curveMonotoneY,
  curveNatural,
  curveStep,
  curveStepAfter,
  curveStepBefore,
  line,
} from 'd3-shape';

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
      for (const child of item.items || []) walkMarktype(child, ox, oy, out, precision);
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
  if (!curve) return points;
  const collected = [];
  const context = {
    moveTo: (x, y) => collected.push([x, y]),
    lineTo: (x, y) => collected.push([x, y]),
    // A cubic's control points are part of the outline: two curves through the same anchors are
    // different shapes, and comparing anchors alone would not see it.
    bezierCurveTo: (x1, y1, x2, y2, x, y) =>
      collected.push([x1, y1], [x2, y2], [x, y]),
    closePath: () => {},
  };
  line().curve(curve).x(p => p[0]).y(p => p[1]).context(context)(points);
  return collected.length ? collected : points;
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
  for (const [x, y] of curve ? expandCurve(primary, curve) : primary) push(x, y);
  if (type === 'area') {
    const secondary = [...items]
      .reverse()
      .map(i => [i.x2 !== undefined ? i.x2 : i.x || 0, i.y2 !== undefined ? i.y2 : i.y || 0]);
    const back = curve
      ? expandCurve(secondary, curveFor(MIRRORED[first.interpolate] || first.interpolate, first.orient))
      : secondary;
    for (const [x, y] of back) push(x, y);
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
  if ((type === 'symbol' || type === 'arc') && item.bounds) {
    entry.shapeLeft = canonicalNumber(item.bounds.x1 + dx, precision);
    entry.shapeTop = canonicalNumber(item.bounds.y1 + dy, precision);
    entry.shapeWidth = canonicalNumber(item.bounds.x2 - item.bounds.x1, precision);
    entry.shapeHeight = canonicalNumber(item.bounds.y2 - item.bounds.y1, precision);
  }
  if (type === 'text') {
    for (const channel of TEXT_CHANNELS) {
      if (item[channel] !== undefined) entry[channel] = canonicalNumber(item[channel], precision);
    }
    // A text mark's content is whatever the field held, so a numeric field gives a numeric `text`.
    // Both engines draw its string form, so compare that rather than its type.
    if (entry.text !== undefined) entry.text = String(entry.text);
  }
  return entry;
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
      domain: scale.domain().map((d) => (typeof d === 'number' ? canonicalNumber(d, precision) : String(d))),
      range: scale.range().map((r) => (typeof r === 'number' ? canonicalNumber(r, precision) : String(r))),
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
