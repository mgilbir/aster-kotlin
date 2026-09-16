/**
 * Canonicalization shared by every oracle script.
 *
 * These rules must match `SceneSnapshotSerializer` on the Kotlin side exactly (ADR 0008): sorted keys, fixed numeric precision, normalized negative zero, explicit non-finite tokens,
 * and no generated identifiers. If the two drift apart, differential tests report formatting
 * differences instead of behavioural ones.
 */

export const DEFAULT_PRECISION = 6;

/** Matches `canonicalNumberString` in vega-model. */
export function canonicalNumber(value, precision = DEFAULT_PRECISION) {
  if (typeof value !== 'number') return value;
  if (Number.isNaN(value)) return 'NaN';
  if (value === Infinity) return 'Infinity';
  if (value === -Infinity) return '-Infinity';
  const rounded = Number(value.toFixed(precision));
  // toFixed can produce "-0"; normalize it away.
  return rounded === 0 ? 0 : rounded;
}

/** Recursively sorts object keys and canonicalizes numbers. */
export function canonicalize(value, precision = DEFAULT_PRECISION) {
  if (Array.isArray(value)) return value.map((item) => canonicalize(item, precision));
  if (value === null || typeof value !== 'object') return canonicalNumber(value, precision);

  const result = {};
  for (const key of Object.keys(value).sort()) {
    result[key] = canonicalize(value[key], precision);
  }
  return result;
}

export function canonicalJson(value, precision = DEFAULT_PRECISION) {
  return `${JSON.stringify(canonicalize(value, precision), null, 2)}\n`;
}

/**
 * Normalizes SVG text for golden comparison: sorted attributes, collapsed whitespace and stripped
 * generated ids (ADR 0008).
 */
export function canonicalSvg(svg) {
  return (
    svg
      // Vega numbers ids per render; ours are numbered per document. Neither is meaningful.
      .replace(/id="[^"]*"/g, 'id="ID"')
      .replace(/url\(#[^)]*\)/g, 'url(#ID)')
      .replace(/>\s+</g, '><')
      // **Only the whitespace that is formatting.** A run containing a line break is indentation
      // and collapses; a run of plain spaces is *content* and stays, because the captions and mark
      // descriptions harvested out of this file are read from these very attributes. Collapsing
      // every run flattened them: upstream's caption for an axis whose labels are all empty is
      // `values from  to `, two spaces around a value that formatted to nothing — `domainCaption`
      // interpolates `values from ${fmt(d[0])} to ${fmt(peek(d))}` and neither end formats — and
      // this rewrote it to one before the reference was written. The comparison then failed against
      // an engine that had it right, which is the worst shape a reference can take: a gate that
      // reports a defect the code does not have.
      .replace(/[ \t]*[\r\n]+[ \t]*/g, ' ')
      .trim() + '\n'
  );
}

/**
 * Reduces a Vega scenegraph to the fields the differential tests compare: mark counts, types, datum
 * identity, coordinates and bounds (ADR 0008). Everything else in Vega's runtime
 * scenegraph is implementation detail.
 */
export function summarizeScene(node, precision = DEFAULT_PRECISION) {
  const summary = {
    marktype: node.marktype ?? 'group',
    role: node.role ?? null,
    name: node.name ?? null,
    items: (node.items ?? []).map((item) => summarizeItem(item, precision)),
  };
  return canonicalize(summary, precision);
}

function summarizeItem(item, precision) {
  const geometry = {};
  for (const key of ['x', 'y', 'x2', 'y2', 'width', 'height', 'size', 'angle', 'opacity']) {
    if (item[key] !== undefined) geometry[key] = canonicalNumber(item[key], precision);
  }
  for (const key of ['fill', 'stroke', 'shape', 'text', 'align', 'baseline', 'font']) {
    if (item[key] !== undefined) geometry[key] = item[key];
  }
  if (item.strokeWidth !== undefined) {
    geometry.strokeWidth = canonicalNumber(item.strokeWidth, precision);
  }
  if (item.bounds) {
    geometry.bounds = [
      canonicalNumber(item.bounds.x1, precision),
      canonicalNumber(item.bounds.y1, precision),
      canonicalNumber(item.bounds.x2, precision),
      canonicalNumber(item.bounds.y2, precision),
    ];
  }
  if (item.items) {
    geometry.items = item.items.map((child) => summarizeScene(child, precision));
  }
  return geometry;
}
