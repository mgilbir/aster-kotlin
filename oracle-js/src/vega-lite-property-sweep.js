#!/usr/bin/env node
/**
 * One Vega-Lite specification per **property value its own schema declares**, compiled with upstream.
 *
 * The sister of `property-sweep.js`, one layer up. That one sweeps what Vega declares and compares
 * the *scene*; this sweeps what **Vega-Lite** declares and compares the **Vega it compiles into**,
 * because that is where a Vega-Lite defect lives. Vega-Lite's whole value is the defaults it
 * supplies — a scale type, a stack transform, a tick count, a label angle, a band size — and every
 * one of them is a property of the specification it emits. Comparing the emitted Vega names the rule
 * that drifted; comparing the picture would say "some marks moved".
 *
 * The surface is much larger than Vega's: 458 definitions, a `MarkDef` of 88 properties, an
 * `Encoding` of 38 channels, a `Config` of 72. The fixture corpus covers 283 charts of it, which is
 * what people draw; this covers what the schema says can be written.
 *
 * ### What is swept
 *
 * One family per **mark type**, sweeping the `MarkDef` table — `{"mark": {"type": "bar", …}}` — for
 * the reason the Vega sweep has one per mark type: which properties mean anything is the mark's own
 * question, and `cornerRadiusEnd` belongs to a bar where `interpolate` belongs to a line.
 *
 * And one family per **encoding channel**, sweeping the properties that channel's field definition
 * declares — `{"encoding": {"y": {"field": "c", "type": "nominal", …}}}`. The same reasoning one
 * level over: what a property means is the channel's question, and a `stack` belongs to a position
 * where a `legend` belongs to a colour. Several channels appear twice under different measures,
 * because the measure is most of what decides the answer: an `x` over a number and an `x` over a
 * date are different charts, and a rule that reads one correctly can still read the other wrong.
 *
 * A channel's chart gives the swept channel a column of the kind its own properties are about, and
 * names that channel separately from the encoding — several of these charts need a second channel to
 * be a chart at all, and a property written on the wrong one would be measuring that instead. The
 * facet channels take a *different* column from the one on `x` for the same reason: sharing it made
 * upstream emit `groupby: ["c", "c"]` where this compiler emits `["c"]`, and every case in both
 * families then reported that one disagreement rather than the property it was there to try.
 *
 * Values are chosen the way the Vega sweep chooses them, from the schema and nothing else: an enum's
 * words, both booleans, a small fixed set of numbers, one colour. Anything the schema does not say
 * enough about is skipped **and counted**, with its reason, in the manifest.
 *
 * A specification upstream refuses is recorded as a refusal rather than dropped, for the reason
 * every corpus here records them: "upstream will not compile this either" is an agreement.
 *
 * Usage: node src/vega-lite-property-sweep.js <output-directory>
 */

import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import * as vegaLite from 'vega-lite';

const schema = JSON.parse(
  readFileSync(new URL('../node_modules/vega-lite/build/vega-lite-schema.json', import.meta.url), 'utf8'),
);

const [outDir] = process.argv.slice(2);
if (!outDir) {
  console.error('Usage: node src/vega-lite-property-sweep.js <output-directory>');
  process.exit(2);
}

/** The rows every chart here draws: a category, a number, a second number, and a date. */
const ROWS = [
  { c: 'alpha', v: 28, w: 3, t: '2024-01-07' },
  { c: 'beta', v: 55, w: 7, t: '2024-03-19' },
  { c: 'gamma', v: 43, w: 5, t: '2024-06-02' },
  { c: 'delta', v: 91, w: 9, t: '2024-09-11' },
];

/**
 * The encoding each mark type is given, so that a swept property has a chart to mean something on.
 *
 * Deliberately plain and deliberately *complete* for the type: an arc needs a `theta`, a line needs
 * an ordered `x`, a text needs something to write. A property that means nothing on the chart it
 * lands on is an agreement like any other — both compilers ignore it — and the ones that do mean
 * something are the point.
 */
const MARK_ENCODINGS = {
  bar: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' } },
  line: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' } },
  area: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' } },
  point: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } },
  circle: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } },
  square: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } },
  tick: { x: { field: 'v', type: 'quantitative' }, y: { field: 'c', type: 'nominal' } },
  rect: {
    x: { field: 'c', type: 'nominal' },
    y: { field: 't', type: 'ordinal' },
    color: { field: 'v', type: 'quantitative' },
  },
  rule: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' } },
  text: {
    x: { field: 'c', type: 'nominal' },
    y: { field: 'v', type: 'quantitative' },
    text: { field: 'v', type: 'quantitative' },
  },
  arc: { theta: { field: 'v', type: 'quantitative' }, color: { field: 'c', type: 'nominal' } },
  trail: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' } },
};

/** The chart a swept mark property is applied to. */
function markBaseSpec(type) {
  return {
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    width: 200,
    height: 120,
    data: { values: ROWS },
    mark: { type },
    encoding: MARK_ENCODINGS[type],
  };
}

/**
 * The chart each swept **encoding channel** is applied to.
 *
 * One per channel rather than one for all of them, for the same reason the mark families have one
 * per type: a property is only worth comparing where the channel it sits on is doing something. A
 * `bin` on a nominal column and a `timeUnit` on a number are agreements nobody learns from, so the
 * column each channel is given is the kind its own properties are about — `w` and `v` are numbers,
 * `c` is a category and `t` is a date.
 *
 * The **swept** channel is named separately from the encoding because several of these charts need a
 * second channel to be a chart at all, and a property written on the wrong one would be measuring
 * that instead.
 */
const CHANNEL_CHARTS = {
  x: { mark: 'point', channel: 'x', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } } },
  y: { mark: 'point', channel: 'y', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' } } },
  'x-temporal': { mark: 'line', channel: 'x', encoding: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' } } },
  'y-nominal': { mark: 'bar', channel: 'y', encoding: { x: { field: 'v', type: 'quantitative' }, y: { field: 'c', type: 'nominal' } } },
  x2: { mark: 'bar', channel: 'x2', encoding: { x: { field: 'w', type: 'quantitative' }, x2: { field: 'v' }, y: { field: 'c', type: 'nominal' } } },
  color: { mark: 'bar', channel: 'color', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, color: { field: 'w', type: 'quantitative' } } },
  'color-nominal': { mark: 'bar', channel: 'color', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, color: { field: 'c', type: 'nominal' } } },
  size: { mark: 'point', channel: 'size', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' }, size: { field: 'v', type: 'quantitative' } } },
  opacity: { mark: 'point', channel: 'opacity', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' }, opacity: { field: 'v', type: 'quantitative' } } },
  shape: { mark: 'point', channel: 'shape', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' }, shape: { field: 'c', type: 'nominal' } } },
  strokeWidth: { mark: 'point', channel: 'strokeWidth', encoding: { x: { field: 'w', type: 'quantitative' }, y: { field: 'v', type: 'quantitative' }, strokeWidth: { field: 'v', type: 'quantitative' } } },
  text: { mark: 'text', channel: 'text', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, text: { field: 'v', type: 'quantitative' } } },
  detail: { mark: 'line', channel: 'detail', encoding: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' }, detail: { field: 'c', type: 'nominal' } } },
  order: { mark: 'line', channel: 'order', encoding: { x: { field: 't', type: 'temporal' }, y: { field: 'v', type: 'quantitative' }, order: { field: 'w', type: 'quantitative' } } },
  theta: { mark: 'arc', channel: 'theta', encoding: { theta: { field: 'v', type: 'quantitative' }, color: { field: 'c', type: 'nominal' } } },
  radius: { mark: 'arc', channel: 'radius', encoding: { theta: { field: 'v', type: 'quantitative' }, radius: { field: 'w', type: 'quantitative' } } },
  xOffset: { mark: 'bar', channel: 'xOffset', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, xOffset: { field: 'c', type: 'nominal' } } },
  row: { mark: 'bar', channel: 'row', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, row: { field: 't', type: 'ordinal' } } },
  column: { mark: 'bar', channel: 'column', encoding: { x: { field: 'c', type: 'nominal' }, y: { field: 'v', type: 'quantitative' }, column: { field: 't', type: 'ordinal' } } },
};

/** The chart a swept encoding property is applied to. */
function channelBaseSpec(key) {
  const chart = CHANNEL_CHARTS[key];
  return {
    $schema: 'https://vega.github.io/schema/vega-lite/v6.json',
    width: 200,
    height: 120,
    data: { values: ROWS },
    mark: { type: chart.mark },
    encoding: structuredClone(chart.encoding),
  };
}

/**
 * The properties a channel definition declares, taken from the branch that names a **field**.
 *
 * A channel is an `anyOf` over the several shapes it can take — a field, a literal `datum`, a bare
 * `value`, and a conditional wrapping each — and merging all of them would sweep `value` onto a
 * definition that has a `field`, which is not a specification anybody can write. The base charts
 * above all encode fields, so the field branch is the one whose properties are worth asking about.
 */
function fieldDefProperties(channel) {
  const fragment = schema.definitions.FacetedEncoding?.properties?.[channel];
  if (!fragment) return null;
  const branch = branchesOf(fragment).find((it) => it.properties?.field);
  return branch?.properties ?? null;
}

/**
 * A family is a property table, a chart to try each property on, and where on that chart it goes.
 *
 * It used to be only the first of those — the sweep read `MarkDef` and nothing else, and the mark
 * type was the whole of what varied. The encoding is a second surface of the same kind and a larger
 * one, so the loop below now asks each family what to read rather than knowing.
 */
const FAMILIES = [
  ...Object.keys(MARK_ENCODINGS).map((type) => ({
    name: `mark-${type}`,
    properties: () => schema.definitions.MarkDef.properties,
    baseSpec: () => markBaseSpec(type),
    apply: (spec, property, value) => {
      spec.mark[property] = value;
    },
    skip: (property) => SKIP[property],
  })),
  ...Object.keys(CHANNEL_CHARTS).map((key) => ({
    name: `encoding-${key}`,
    properties: () => fieldDefProperties(CHANNEL_CHARTS[key].channel),
    baseSpec: () => channelBaseSpec(key),
    apply: (spec, property, value) => {
      spec.encoding[CHANNEL_CHARTS[key].channel][property] = value;
    },
    skip: (property) => ENCODING_SKIP[property],
  })),
];

/**
 * Properties that are not a *setting* on the mark, and why each is left alone.
 *
 * Read like the Vega sweep's skips: the manifest records them so that the coverage is a number
 * somebody can read rather than a claim.
 */
const SKIP = {
  type: 'the mark type itself, which the family already fixes',
  // A composite mark's parts are whole specifications of their own.
  box: 'a box plot part, which is a specification rather than a value',
  median: 'a box plot part, which is a specification rather than a value',
  outliers: 'a box plot part, which is a specification rather than a value',
  rule: 'a box plot part, which is a specification rather than a value',
  ticks: 'a box plot part, which is a specification rather than a value',
  extent: 'a box plot or error bar extent, whose meaning depends on the composite type',
  point: 'a line or area overlay, which is a mark specification rather than a value',
  line: 'an area overlay, which is a mark specification rather than a value',
  style: 'names config blocks the base chart does not declare',
  tooltip: 'a pointer affordance, and there is no pointer in a compiled specification',
  text: 'the text itself, which the encoding supplies',
  url: 'an image to fetch, and a comparison that fetched one would be measuring the network',
  description: 'prose, emitted verbatim',
  aria: 'an accessibility flag with no geometry',
  ariaRole: 'prose, emitted verbatim',
  ariaRoleDescription: 'prose, emitted verbatim',
  href: 'a link, which draws nothing',
  x: 'a position the encoding already supplies',
  y: 'a position the encoding already supplies',
  x2: 'a position the encoding already supplies',
  y2: 'a position the encoding already supplies',
  theta: 'a position the encoding already supplies',
  theta2: 'a position the encoding already supplies',
  radius: 'a position the encoding already supplies',
  radius2: 'a position the encoding already supplies',
  width: "the mark's own size, which the view already fixes",
  height: "the mark's own size, which the view already fixes",
  clip: 'clipping, which changes no property of the compiled specification',
  font: 'a font family, resolved by whatever engine measures the text',
  timeUnitBandSize: 'a band size in time units, which needs a time-unit encoding to mean anything',
  timeUnitBandPosition:
    'a band position in time units, which needs a time-unit encoding to mean anything',
};

/**
 * Properties of a channel definition that are not a *setting* on the channel, and why each is left.
 *
 * The three guide blocks are the large omission and they are deliberate: an `axis`, a `legend` and a
 * `header` are whole tables of properties apiece, so each is a family of its own to write rather
 * than a value to try here. The Vega sweep covers the axis and legend upstream *emits*; what is not
 * covered anywhere yet is the Vega-Lite spelling that produces them.
 */
const ENCODING_SKIP = {
  field: 'the column itself, which the chart already fixes',
  type: 'the measure, which decides what every other property here means',
  datum: 'a literal standing where the column is, so not a property of this definition',
  value: 'a literal standing where the column is, so not a property of this definition',
  condition: 'a definition of its own, tried against a selection this chart does not declare',
  axis: 'a table of properties, and a family of its own to write',
  legend: 'a table of properties, and a family of its own to write',
  header: 'a table of properties, and a family of its own to write',
  scale: 'a table of properties, and a family of its own to write',
  impute: 'an imputation, which is a specification rather than a value',
  bin: 'a bin, whose object form is a table of properties and whose bare form the mark families already draw',
};

/** The numbers tried for a `number`-typed property, and why these. */
const NUMBERS = [0, 0.5, 8, -4];

/** The one colour tried where the schema says a colour, chosen to be unlike every default. */
const COLOUR = '#b35a1f';

/**
 * Every branch a schema fragment can take, with `$ref`s followed.
 *
 * Vega-Lite wraps nearly everything in a `anyOf` with a `ExprRef` and a conditional form, so a
 * reader that stopped at the reference would see no values at all.
 */
function branchesOf(fragment, depth = 0) {
  if (!fragment || depth > 8) return [];
  if (fragment.$ref) {
    const name = fragment.$ref.split('/').pop();
    return branchesOf(schema.definitions[name], depth + 1);
  }
  const nested = fragment.oneOf || fragment.anyOf || fragment.allOf;
  if (nested) return nested.flatMap((branch) => branchesOf(branch, depth + 1));
  return [fragment];
}

/** Collects the candidate values a schema fragment declares, or null where there are none. */
function declaredValues(fragment) {
  const branches = branchesOf(fragment);
  for (const branch of branches) {
    if (branch.enum) {
      const values = branch.enum.filter((v) => v !== null);
      if (values.length) return { kind: 'enum', values };
    }
  }
  for (const branch of branches) {
    if (branch.type === 'boolean') return { kind: 'boolean', values: [true, false] };
  }
  for (const branch of branches) {
    if (branch.type === 'number') return { kind: 'number', values: NUMBERS };
  }
  // A colour is named by the definition it references rather than by the property's name.
  if (JSON.stringify(fragment).includes('Color')) return { kind: 'colour', values: [COLOUR] };
  for (const branch of branches) {
    if (branch.type === 'array' && (!branch.items || branch.items.type === 'number')) {
      return { kind: 'array', values: [[4, 2]] };
    }
  }
  return null;
}

/** A file-name-safe spelling of a value. */
function slug(value) {
  return (
    JSON.stringify(value)
      .replace(/[^A-Za-z0-9.-]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 40) || 'blank'
  );
}

rmSync(outDir, { recursive: true, force: true });
const specDir = join(outDir, 'specs');
const referenceDir = join(outDir, 'reference');
mkdirSync(specDir, { recursive: true });
mkdirSync(referenceDir, { recursive: true });

const cases = [];
const skipped = [];
const used = new Map();

for (const { name: family, properties, baseSpec, apply, skip } of FAMILIES) {
  const table = properties();
  if (!table) {
    skipped.push({ family, property: '*', reason: 'the schema declares no such definition' });
    continue;
  }
  for (const [property, fragment] of Object.entries(table)) {
    const reason = skip(property);
    if (reason) {
      skipped.push({ family, property, reason });
      continue;
    }
    const candidates = declaredValues(fragment);
    if (!candidates) {
      skipped.push({
        family,
        property,
        reason: 'the schema declares no enumerable value here',
      });
      continue;
    }
    for (const value of candidates.values) {
      const spec = baseSpec();
      apply(spec, property, value);
      let name = `${family}-${property}-${slug(value)}`;
      if (used.has(name)) name = `${name}-${used.get(name) + 1}`;
      used.set(name, (used.get(name) || 0) + 1);
      cases.push({ name, family, property, value, spec });
    }
  }
}

let compiled = 0;
const refused = [];

for (const one of cases) {
  writeFileSync(join(specDir, `${one.name}.vl.json`), `${JSON.stringify(one.spec, null, 2)}\n`);
  try {
    const { spec } = vegaLite.compile(one.spec);
    writeFileSync(
      join(referenceDir, `${one.name}.vega.json`),
      `${JSON.stringify(spec, null, 2)}\n`,
    );
    compiled++;
  } catch (error) {
    refused.push({
      ...one,
      spec: undefined,
      reason: String(error?.message ?? error).slice(0, 160),
    });
  }
}

writeFileSync(
  join(outDir, 'manifest.json'),
  `${JSON.stringify(
    { vegaLiteVersion: vegaLite.version, generated: cases.length, compiled, refused, skipped },
    null,
    2,
  )}\n`,
);

console.log(
  `Generated ${cases.length} case(s) from Vega-Lite's schema: ${compiled} compiled, ${refused.length} refused by upstream.`,
);
console.log(`${skipped.length} property(ies) skipped; see ${join(outDir, 'manifest.json')}.`);
