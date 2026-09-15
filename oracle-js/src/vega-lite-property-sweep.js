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

const FAMILIES = Object.fromEntries(
  Object.keys(MARK_ENCODINGS).map((type) => [
    `mark-${type}`,
    (spec, property, value) => {
      spec.mark[property] = value;
    },
  ]),
);

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

const markDef = schema.definitions.MarkDef.properties;
const cases = [];
const skipped = [];
const used = new Map();

for (const [family, apply] of Object.entries(FAMILIES)) {
  for (const [property, fragment] of Object.entries(markDef)) {
    const reason = SKIP[property];
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
      const spec = markBaseSpec(family.slice('mark-'.length));
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
