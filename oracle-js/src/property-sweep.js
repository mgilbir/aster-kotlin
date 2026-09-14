#!/usr/bin/env node
/**
 * One specification per **property value Vega's own schema declares**, rendered with upstream.
 *
 * Usage: node src/property-sweep.js <output-directory>
 *
 * The other corpora here are collections of charts: the 200 fixtures, Vega-Lite's 627 examples, 1981
 * specifications from GitHub, 63 Deneb templates. Every one of them is a sample of what people
 * *draw*, and they agree with upstream to the last mark — which is exactly why they have stopped
 * finding anything. What they cannot reach is a property nobody happened to use, or a value of it
 * nobody happened to choose: `tickBand: "extent"`, `labelOverlap: "greedy"`, `align: "all"`,
 * `bandPosition: 0`. Those are not exotic; they are simply not in anybody's chart.
 *
 * `vega/build/vega-schema.json` is the list of them, and it is machine-readable: every guide, scale
 * and mark property with its type, and for the many that take a word, the enumeration of the words.
 * This walks that schema and writes one small chart per (property, value) pair, so the sweep covers
 * the declared surface rather than the used one.
 *
 * ### What is swept, and what is left out
 *
 * Six families: `axis`, `legend`, `title`, `scale`, a **mark's own properties**, and the **encode
 * channels** every mark item carries. The first four are where this engine's code is densest — a
 * guide is a layout, a text measurement and half a dozen marks — and the last two are the widest
 * declared surface there is: sixty channels, most of which no chart in any corpus sets. A property
 * is swept when the schema says enough to choose values honestly:
 *
 *   * an **enum**, including one inside a `oneOf` beside a signal reference: every word it lists;
 *   * a **boolean**: both;
 *   * a **number**: a small fixed set, since the schema does not say what a sensible one is;
 *   * a **number array** of a stated length: one plausible tuple.
 *
 * Anything else is skipped and *counted*: a free string (there is no honest value to choose), an
 * object whose shape is another definition, a property that names something the base chart does not
 * have. The manifest records the skips with their reason, so the sweep's coverage is a number
 * somebody can read rather than a claim.
 *
 * ### The base chart
 *
 * One bar chart, deliberately ordinary and deliberately complete: a band scale and a linear scale,
 * an ordinal colour scale with a legend, an axis on the bottom and one on the left, and a title. A
 * property is applied to *one* place in it — the bottom axis, the legend, the title, or the named
 * scale — so a difference the sweep reports names the property that caused it.
 *
 * A value upstream refuses is recorded as a refusal rather than dropped, the way the wild and Deneb
 * corpora record theirs: "upstream will not draw this either" is an agreement, and a sweep that
 * quietly dropped them would be reporting a match rate over a corpus nobody can name.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import * as vega from 'vega';
import { pinDeterminism } from './determinism.js';
import { canonicalJson, canonicalNumber } from './canonical.js';
import { normalizeScales, normalizeScene } from './normalize.js';

// Read rather than imported: the package does not export its build directory, and the schema is
// data this reads rather than a module it depends on.
const schema = JSON.parse(
  readFileSync(new URL('../node_modules/vega/build/vega-schema.json', import.meta.url), 'utf8'),
);

const [outDir] = process.argv.slice(2);
if (!outDir) {
  console.error('Usage: node src/property-sweep.js <output-directory>');
  process.exit(2);
}

/** The chart every swept property is applied to. */
function baseSpec() {
  return {
    $schema: 'https://vega.github.io/schema/vega/v6.json',
    width: 200,
    height: 120,
    padding: 5,
    title: { text: 'Quarterly totals', subtitle: 'by region' },
    data: [
      {
        name: 't',
        values: [
          { c: 'alpha', v: 28, g: 'north' },
          { c: 'beta', v: 55, g: 'south' },
          { c: 'gamma', v: 43, g: 'north' },
          { c: 'delta', v: 91, g: 'east' },
        ],
      },
    ],
    scales: [
      { name: 'x', type: 'band', domain: { data: 't', field: 'c' }, range: 'width', padding: 0.1 },
      { name: 'y', type: 'linear', domain: { data: 't', field: 'v' }, range: 'height', nice: true },
      { name: 'colour', type: 'ordinal', domain: { data: 't', field: 'g' }, range: 'category' },
    ],
    axes: [
      { orient: 'bottom', scale: 'x', title: 'category' },
      { orient: 'left', scale: 'y', title: 'amount', tickCount: 4 },
    ],
    legends: [{ fill: 'colour', title: 'region' }],
    marks: [
      {
        type: 'rect',
        from: { data: 't' },
        encode: {
          enter: {
            x: { scale: 'x', field: 'c' },
            width: { scale: 'x', band: 1 },
            y: { scale: 'y', field: 'v' },
            y2: { scale: 'y', value: 0 },
            fill: { scale: 'colour', field: 'g' },
          },
        },
      },
    ],
  };
}


/**
 * One mark of each type, drawn from the same three rows, for the channel sweep.
 *
 * Deliberately plain: enough encoding to put the mark on the chart and nothing more, so a swept
 * channel is the only thing that varies. `image` is absent — it needs a file to load, and a
 * comparison that fetched one would be measuring the network.
 */
const MARK_BASES = {
  rect: () => ({
    type: 'rect',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c' },
        width: { scale: 'x', band: 1 },
        y: { scale: 'y', field: 'v' },
        y2: { scale: 'y', value: 0 },
        fill: { value: 'steelblue' },
      },
    },
  }),
  symbol: () => ({
    type: 'symbol',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        size: { value: 200 },
        fill: { value: 'steelblue' },
      },
    },
  }),
  text: () => ({
    type: 'text',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        text: { field: 'c' },
        fill: { value: '#333333' },
      },
    },
  }),
  line: () => ({
    type: 'line',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        stroke: { value: 'steelblue' },
        strokeWidth: { value: 2 },
      },
    },
  }),
  area: () => ({
    type: 'area',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        y2: { scale: 'y', value: 0 },
        fill: { value: 'steelblue' },
      },
    },
  }),
  arc: () => ({
    type: 'arc',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { value: 60 },
        startAngle: { value: 0 },
        endAngle: { value: 2 },
        outerRadius: { value: 25 },
        innerRadius: { value: 8 },
        fill: { value: 'steelblue' },
      },
    },
  }),
  rule: () => ({
    type: 'rule',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        y2: { scale: 'y', value: 0 },
        stroke: { value: '#333333' },
        strokeWidth: { value: 2 },
      },
    },
  }),
  path: () => ({
    type: 'path',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        path: { value: 'M-8,0L0,-12L8,0Z' },
        fill: { value: 'steelblue' },
      },
    },
  }),
  trail: () => ({
    type: 'trail',
    from: { data: 't' },
    encode: {
      enter: {
        x: { scale: 'x', field: 'c', band: 0.5 },
        y: { scale: 'y', field: 'v' },
        size: { value: 6 },
        fill: { value: 'steelblue' },
      },
    },
  }),
};

/** Where each family's property is written into the base chart. */
const FAMILIES = {
  // The **bottom** axis, which is the one with a band scale under it: half of what an axis property
  // decides — `tickBand`, `bandPosition`, `labelOverlap` — only means anything over bands.
  axis: (spec, property, value) => {
    spec.axes[0][property] = value;
  },
  legend: (spec, property, value) => {
    spec.legends[0][property] = value;
  },
  title: (spec, property, value) => {
    spec.title[property] = value;
  },
  // The **band** scale, for the same reason: `align`, `padding`, `round` and `reverse` are its
  // properties, and a linear scale ignores most of them.
  scale: (spec, property, value) => {
    spec.scales[0][property] = value;
  },
  // The bar mark's own properties — `clip`, `interactive`, `aria` and the rest — rather than its
  // channels.
  mark: (spec, property, value) => {
    spec.marks[0][property] = value;
  },
  // An **encode channel**, written into the mark's `enter` block as a literal value. Sixty of them
  // are declared and a bar chart reads perhaps eight, so this is the widest gap between what the
  // schema says and what any corpus exercises.
  //
  // One family per **mark type**, because which channels mean anything is the mark's own question:
  // a rect ignores `tension`, a line ignores `cornerRadius`, and only an arc reads `padAngle`. The
  // channel table is swept in full against each of them rather than against a guess at which pairs
  // matter — a pair that draws nothing is an agreement like any other, and the one place it is not
  // is exactly what this is for.
  ...Object.fromEntries(
    Object.entries(MARK_BASES).map(([type, mark]) => [
      `encode-${type}`,
      (spec, property, value) => {
        spec.marks = [mark()];
        spec.marks[0].encode.enter[property] = { value };
      },
    ]),
  ),
};

/**
 * Properties left out on purpose, with the reason recorded rather than assumed.
 *
 * Three kinds: what identifies a thing rather than styles it, what this base chart has nowhere to
 * put, and what needs a value no schema can supply.
 *
 * Keyed by **family first**, because the same name means different things in two of them: a
 * legend's `fill` names the scale it describes and a mark's `fill` is a colour, and skipping the
 * second for the first one's reason quietly dropped six channels from the sweep.
 */
const SHARED_SKIP = {
  name: 'names the object rather than styling it',
  scale: 'names the scale rather than styling it',
  type: 'the scale type; sweeping it rebuilds the chart rather than varying it',
  domain: 'needs data of its own',
  domainRaw: 'needs data of its own',
  domainMin: 'needs data of its own',
  domainMax: 'needs data of its own',
  domainMid: 'needs data of its own',
  range: 'needs a range of its own',
  rangeMin: 'needs a range of its own',
  rangeMax: 'needs a range of its own',
  values: 'needs values of its own',
  encode: 'a block of encoders rather than a value',
  style: 'names config blocks the base chart does not declare',
  interactive: 'no pointer in a static render',
  format: 'a format specifier, which the schema does not enumerate',
  formatType: 'meaningless without a matching format',
  text: 'the title text itself',
  subtitle: 'the subtitle text itself',
  scheme: 'a scheme name, swept through range instead',
  reverse: 'covered by the boolean sweep of the scale family',
  bins: 'needs bin boundaries of its own',
  nice: 'takes a count or an interval as well as a boolean; not honestly enumerable here',
  init: 'an initial value for an interactive scale',
  on: 'event handlers, which a static render never fires',
  // A mark's own structure rather than its appearance: sweeping these builds a different chart
  // instead of varying one.
  from: 'names the data the mark is drawn from',
  marks: 'nested marks, which is a different chart',
  transform: 'a pipeline of its own',
  sort: 'needs a field to sort by',
  key: 'names the field items are matched by',
  role: 'names what the mark is, which the engine derives',
  // Channels that need something the base chart does not have, or that would replace its geometry.
  url: 'an image to load, which a static comparison has nowhere to fetch from',
  path: 'an SVG path, which belongs to a path mark rather than a rect',
  shape: 'a symbol shape, which belongs to a symbol mark',
  text: 'the text of a text mark',
  defined: 'breaks a line or an area, neither of which this chart draws',
  tooltip: 'a value no static scene shows',
  x: 'the geometry the base chart encodes from its own data',
  x2: 'the geometry the base chart encodes from its own data',
  xc: 'the geometry the base chart encodes from its own data',
  y: 'the geometry the base chart encodes from its own data',
  y2: 'the geometry the base chart encodes from its own data',
  yc: 'the geometry the base chart encodes from its own data',
  width: 'the geometry the base chart encodes from its own data',
  height: 'the geometry the base chart encodes from its own data',
};

/** Skips that belong to **one** family, where the same name means something else in another. */
const FAMILY_SKIP = {
  legend: {
    fill: 'names the scale a legend describes',
    stroke: 'names the scale a legend describes',
    size: 'names the scale a legend describes',
    shape: 'names the scale a legend describes',
    opacity: 'names the scale a legend describes',
    strokeDash: 'names the scale a legend describes',
    strokeWidth: 'names the scale a legend describes',
  },
  encode: {
    // A rect draws neither, and a sweep of a rect chart has nothing to say about them.
    size: 'a symbol channel, which this chart has no symbol for',
  },
};

/**
 * The properties a family declares, dug out of however the schema spells that family.
 *
 * Three spellings, and each one says something: an `axis` is a plain object; a `legend` is an
 * `allOf` of the shared part and the per-kind parts, so every branch's properties belong to it; and
 * a `title` or a `scale` is a `oneOf` — a title may be written as a bare string, and a scale is a
 * different object for every scale type. The band branch is the one taken here, because the band
 * scale is the one these are applied to.
 */
function propertiesOf(family) {
  // Every `encode-<marktype>` family reads the same channel table; the mark type decides which of
  // them mean anything, not which of them exist.
  const named = family.startsWith('encode-') ? 'encodeEntry' : family;
  const definition = schema.definitions[named];
  const merged = {};
  const visit = (fragment) => {
    if (!fragment) return;
    if (fragment.properties) Object.assign(merged, fragment.properties);
    for (const branch of fragment.allOf || []) visit(branch);
    if (fragment.oneOf) {
      const branches = fragment.oneOf.filter((b) => b.properties);
      const banded = branches.find(
        (b) => b.properties.type && (b.properties.type.enum || []).includes('band'),
      );
      visit(banded || branches[0]);
    }
  };
  visit(definition);
  if (!Object.keys(merged).length) {
    throw new Error(`the schema has no properties for '${family}'`);
  }
  return merged;
}

/** The numbers tried for a `number`-typed property, and why these. */
const NUMBERS = [0, 0.5, 8, -4];

/** The one colour tried where the schema says a colour, chosen to be unlike every default. */
const COLOUR = '#b35a1f';

/**
 * Every branch a schema fragment can take, with `$ref`s followed.
 *
 * Following them is what makes the sweep reach the properties worth sweeping: `labelOverlap`,
 * `tickBand`, `tickExtra` and `tickCount` are all a bare `$ref`, so a reader that stopped at the
 * reference saw no values at all and skipped exactly the ones no chart in any corpus sets. The
 * depth cap is against the encoder-value definitions, which nest through `rule`, `stringModifiers`
 * and back into themselves; nothing useful is picked out of those, and the cap keeps the walk
 * finite rather than trusting that.
 */
function branchesOf(fragment, depth = 0) {
  if (!fragment || depth > 8) return [];
  if (fragment.$ref) {
    const name = fragment.$ref.split('/').pop();
    return branchesOf(schema.definitions[name], depth + 1);
  }
  const nested = fragment.oneOf || fragment.anyOf || fragment.allOf;
  if (nested) return nested.flatMap((branch) => branchesOf(branch, depth + 1));
  // An **encoder value**, which is where a channel's own enumeration lives: a channel is declared
  // as `{"value": {"enum": […]}}` rather than as the enum itself, because a channel may equally be
  // a field, a scale lookup or a signal. Following the `value` property is what makes the sixty
  // encode channels reachable at all; without it every one of them read as unenumerable.
  if (fragment.properties && fragment.properties.value) {
    return branchesOf(fragment.properties.value, depth + 1);
  }
  return [fragment];
}

/** Collects the candidate values a schema fragment declares, or null where there are none. */
function valuesFor(fragment) {
  const branches = branchesOf(fragment);
  for (const branch of branches) {
    // `null` is in several of these enumerations as "unset", which is what leaving the property out
    // already tests.
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
  // A colour is named by the definition it references rather than by the property's name, so this
  // stays schema-driven: `titleColor` is `oneOf [null, string, colorValue]`, and the bare `string`
  // branch on its own would be skipped as a free string.
  if (JSON.stringify(fragment).includes('colorValue')) {
    return { kind: 'colour', values: [COLOUR] };
  }
  for (const branch of branches) {
    if (branch.type === 'array' && branch.items && branch.items.type === 'number') {
      return { kind: 'array', values: [[4, 2]] };
    }
  }
  return null;
}

/** A file-name-safe spelling of a value. */
function slug(value) {
  return JSON.stringify(value)
    .replace(/[^A-Za-z0-9.-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40) || 'blank';
}

rmSync(outDir, { recursive: true, force: true });
const specDir = join(outDir, 'specs');
const referenceDir = join(outDir, 'reference');
mkdirSync(specDir, { recursive: true });
mkdirSync(referenceDir, { recursive: true });

const cases = [];
const skipped = [];
const used = new Map();

for (const [family, apply] of Object.entries(FAMILIES)) {
  const definition = propertiesOf(family);
  for (const [property, fragment] of Object.entries(definition)) {
    const reason =
      (FAMILY_SKIP[family] || {})[property] ||
      (family.startsWith('encode-') ? (FAMILY_SKIP.encode || {})[property] : undefined) ||
      SHARED_SKIP[property];
    if (reason) {
      skipped.push({ family, property, reason });
      continue;
    }
    const candidates = valuesFor(fragment);
    if (!candidates) {
      skipped.push({ family, property, reason: 'the schema declares no enumerable value here' });
      continue;
    }
    for (const value of candidates.values) {
      const spec = baseSpec();
      apply(spec, property, value);
      // A name that has already been used gets a number: two values can slug the same way — `0`
      // and `-0`, `"a b"` and `"a_b"` — and a second file overwriting the first would silently
      // shrink the sweep.
      let name = `${family}-${property}-${slug(value)}`;
      if (used.has(name)) name = `${name}-${used.get(name) + 1}`;
      used.set(name, (used.get(name) || 0) + 1);
      cases.push({ name, family, property, value, spec });
    }
  }
}

let rendered = 0;
const refused = [];

for (const one of cases) {
  writeFileSync(join(specDir, `${one.name}.vg.json`), JSON.stringify(one.spec, null, 2) + '\n');
  // Re-seeded per specification, since the generator is module-level upstream: without this the
  // second chart continues the first one's sequence and its reference means something else.
  pinDeterminism();
  let view;
  let failure = null;
  try {
    view = new vega.View(vega.parse(one.spec), { renderer: 'none' });
    view.error = (error) => {
      failure = error;
    };
    await view.runAsync();
  } catch (error) {
    failure = error;
  }
  if (failure) {
    refused.push({ ...one, spec: undefined, reason: String(failure.message ?? failure).slice(0, 120) });
    await view?.finalize();
    continue;
  }

  const scaleNames = one.spec.scales.map((s) => s.name);
  const reference = {
    vegaVersion: vega.version,
    spec: `${one.name}.vg.json`,
    size: surfaceSize(view),
    scales: normalizeScales(view, scaleNames),
    ...normalizeScene(view.scenegraph().root),
  };
  writeFileSync(join(referenceDir, `${one.name}.reference.json`), canonicalJson(reference));
  rendered++;
  await view.finalize();
}

writeFileSync(
  join(outDir, 'manifest.json'),
  JSON.stringify(
    {
      vegaVersion: vega.version,
      generated: cases.length,
      rendered,
      refused,
      skipped,
    },
    null,
    2,
  ) + '\n',
);

console.log(`Generated ${cases.length} case(s) from the schema: ${rendered} rendered, ${refused.length} refused by upstream.`);
console.log(`${skipped.length} property(ies) skipped; see ${join(outDir, 'manifest.json')}.`);

/** The rendered surface, as `reference.js` measures it: content bounds plus padding. */
function surfaceSize(view) {
  const padding = view.padding() || {};
  const left = padding.left || 0;
  const top = padding.top || 0;
  const right = padding.right || 0;
  const bottom = padding.bottom || 0;
  const frame = view.scenegraph().root.items[0];
  const bounds = frame && frame.bounds;
  if (!bounds) {
    return { width: view.width() + left + right, height: view.height() + top + bottom };
  }
  return {
    width: canonicalNumber(bounds.x2 - bounds.x1 + left + right),
    height: canonicalNumber(bounds.y2 - bounds.y1 + top + bottom),
  };
}
