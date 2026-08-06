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
  arc: ['x', 'y'],
  path: ['x', 'y'],
  trail: ['x', 'y'],
  shape: ['x', 'y'],
};

const STYLE_CHANNELS = ['fill', 'stroke', 'strokeWidth', 'opacity', 'fillOpacity', 'strokeOpacity'];
const TEXT_CHANNELS = ['text', 'align', 'baseline', 'font', 'fontSize', 'fontWeight', 'angle'];

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
const SERIES_TYPES = new Set(['line', 'area', 'trail']);

/** A marktype node: `{marktype, role, items: [...]}`. */
function walkMarktype(marktype, dx, dy, out, precision) {
  const type = marktype.marktype || 'group';

  if (SERIES_TYPES.has(type) && (marktype.items || []).length > 0) {
    out.push(seriesRecord(type, marktype, dx, dy, precision));
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
  for (const item of items) push(item.x, item.y);
  if (type === 'area') {
    for (const item of [...items].reverse()) {
      push(item.x2 !== undefined ? item.x2 : item.x, item.y2 !== undefined ? item.y2 : item.y);
    }
  }
  entry.points = points.join(' ');

  for (const channel of STYLE_CHANNELS) {
    if (first[channel] !== undefined) entry[channel] = canonicalNumber(first[channel], precision);
  }
  return entry;
}

function record(type, role, item, dx, dy, precision) {
  const entry = { type, role: role || null };
  const channels = GEOMETRY_CHANNELS[type] || ['x', 'y'];

  // Vega keeps a text mark's dx/dy as separate render-time offsets; this engine folds them into the
  // anchor, which draws identically. Fold them here too so the two agree.
  const textDx = type === 'text' ? item.dx || 0 : 0;
  const textDy = type === 'text' ? item.dy || 0 : 0;

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

  for (const channel of STYLE_CHANNELS) {
    if (item[channel] !== undefined) entry[channel] = canonicalNumber(item[channel], precision);
  }
  // A symbol's `size` channel says nothing about the outline it actually draws, and Vega ships its own
  // symbol table rather than d3's — so compare the drawn extent, not just the requested size.
  if (type === 'symbol' && item.bounds) {
    entry.shapeWidth = canonicalNumber(item.bounds.x2 - item.bounds.x1, precision);
    entry.shapeHeight = canonicalNumber(item.bounds.y2 - item.bounds.y1, precision);
  }
  if (type === 'text') {
    for (const channel of TEXT_CHANNELS) {
      if (item[channel] !== undefined) entry[channel] = canonicalNumber(item[channel], precision);
    }
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
