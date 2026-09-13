#!/usr/bin/env node
/**
 * Turns a directory of Deneb templates into plain Vega specifications that can actually be run.
 *
 * Usage: node src/deneb-prepare.js <templateDir> <outputDir>
 *
 * The templates are written for Deneb, a Power BI custom visual, and three things in them are
 * Power BI's rather than Vega's. Each is undone here, deliberately and in the open, because the
 * corpus is only worth having if both engines are handed *the same plain Vega*:
 *
 * 1. **No data.** Every template's root dataset is `{"name": "dataset"}`; Power BI injects the
 *    table at run time. What the template expects is declared in `usermeta.dataset` — a list of
 *    columns with a `key` (`__0__`, `__1__`, …, which is what the specification references), a
 *    `name` and a `type` of `text`, `numeric` or `dateTime`. Rows are synthesised from that
 *    declaration, deterministically, so a rerun compares the same chart.
 *
 *    The values are **plausible, not meaningful**: a sankey given twelve rows of `Alpha`→`Bravo`
 *    draws something no analyst would want. That is fine and is the point — the question this
 *    corpus asks is whether two engines make the same thing of one specification, not whether the
 *    chart is any good. Where it costs something is coverage: a template whose layout collapses on
 *    nonsense data exercises less of the renderer than it would on real data.
 *
 * 2. **`pbiColor(n)`**, used by all 62 templates and defined by Deneb, not by Vega — it returns the
 *    nth colour of the report theme. Upstream Vega cannot evaluate these specifications either
 *    without the function registered. Rather than teach both engines a Power BI concept, every call
 *    is replaced by the literal colour it would return, from the palette below. All 112 call sites
 *    pass integer literals, so the substitution is total and needs no evaluator.
 *
 * 3. **`//` comments**, in one template. JSON has none; they are stripped.
 *
 * 4. **The viewport.** Every template computes `width` and `height` from `containerSize()` — the
 *    element Deneb renders into. Headless there is no element and the call answers nothing, so
 *    `containerSize()[0]-30` is NaN: a scale range of NaN, a chart of nothing, and a comparison of
 *    two engines' treatment of NaN rather than of the template. The update is dropped, which leaves
 *    the signal's own `value` — the fallback size the template's author wrote for this case, and
 *    all 58 that ask declare one.
 *
 * `usermeta` itself is dropped once it has been read. Vega ignores it, but it carries a base64 PNG
 * preview per template — most of the repository's 18 MB — and a corpus is easier to look at without
 * it.
 */

import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const [templateDir, outputDir] = process.argv.slice(2);
if (!templateDir || !outputDir) {
  console.error('Usage: node src/deneb-prepare.js <templateDir> <outputDir>');
  process.exit(2);
}

/**
 * Power BI's default theme colours, which is what `pbiColor` indexes.
 *
 * Only the first four are ever asked for. The values matter to nobody here — both engines are given
 * the same literal — but they are the real palette so a rendered template looks like the template.
 */
const PALETTE = ['#118DFF', '#12239E', '#E66C37', '#6B007B', '#E044A7', '#744EC2'];

/**
 * `pbiColor(index, shade)`: Deneb lightens the colour towards white for a positive shade and darkens
 * it towards black for a negative one. One template asks for `-0.25`.
 */
function pbiColor(index, shade = 0) {
  const hex = PALETTE[index % PALETTE.length];
  const channels = [1, 3, 5].map((at) => parseInt(hex.slice(at, at + 2), 16));
  const towards = shade >= 0 ? 255 : 0;
  const mixed = channels.map((c) => Math.round(c + (towards - c) * Math.abs(shade)));
  return '#' + mixed.map((c) => c.toString(16).padStart(2, '0')).join('');
}

/** Twelve stable labels, so a categorical scale has a domain that sorts the same way every run. */
const LABELS = [
  'Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo', 'Foxtrot',
  'Golf', 'Hotel', 'India', 'Juliett', 'Kilo', 'Lima',
];
const ROWS = LABELS.length;

/**
 * A number that varies without being random: a small linear congruential sequence, seeded by the
 * column, so column two is not column one and a rerun is the same run. `Math.random` here would
 * make every comparison a new one.
 */
function numbers(seed) {
  const out = [];
  let state = (seed + 1) * 7919;
  for (let i = 0; i < ROWS; i++) {
    state = (state * 1103515245 + 12345) % 2147483648;
    out.push(1 + (state % 1000) / 10);
  }
  return out;
}

/** Twelve consecutive months, as text the dataset asks Vega to parse — see `format.parse` below. */
function dates() {
  return Array.from({ length: ROWS }, (_, i) => `2020-${String(i + 1).padStart(2, '0')}-01`);
}

/** `//` to end of line, outside of strings. One template is JSON with comments. */
function stripComments(text) {
  let out = '';
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      out += c;
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') { inString = true; out += c; continue; }
    if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
      continue;
    }
    out += c;
  }
  return out;
}

function walkFiles(dir) {
  const found = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) found.push(...walkFiles(path));
    else if (entry.endsWith('.json')) found.push(path);
  }
  return found;
}

mkdirSync(outputDir, { recursive: true });

let written = 0;
const skipped = [];
for (const path of walkFiles(templateDir).sort()) {
  const raw = readFileSync(path, 'utf8');
  let template;
  try {
    template = JSON.parse(stripComments(raw));
  } catch (error) {
    skipped.push([relative(templateDir, path), `unreadable: ${error.message}`]);
    continue;
  }
  if (!String(template.$schema || '').includes('vega.github.io/schema/vega/')) {
    skipped.push([relative(templateDir, path), 'not a Vega specification']);
    continue;
  }

  const columns = template.usermeta?.dataset ?? [];
  if (columns.length === 0) {
    skipped.push([relative(templateDir, path), 'declares no dataset to stand in for']);
    continue;
  }

  // Built before the substitution below so a column named like a call cannot be caught by it.
  const rows = [];
  const parse = {};
  const values = columns.map((column, index) => {
    if (column.type === 'numeric') return numbers(index);
    if (column.type === 'dateTime') { parse[column.key] = 'date'; return dates(); }
    return LABELS;
  });
  for (let row = 0; row < ROWS; row++) {
    rows.push(Object.fromEntries(columns.map((c, i) => [c.key, values[i][row]])));
  }

  delete template.usermeta;
  let body = JSON.stringify(template);
  body = body.replace(/pbiColor\(\s*(-?\d+)\s*(?:,\s*(-?[\d.]+)\s*)?\)/g, (_, index, shade) =>
    `'${pbiColor(Number(index), shade === undefined ? 0 : Number(shade))}'`,
  );
  const spec = JSON.parse(body);

  // **The size Power BI gives the visual.** Every template computes `width` and `height` from
  // `containerSize()`, which is the DOM element Deneb renders into: headless there is no element,
  // the call answers nothing, and `containerSize()[0]-30` is NaN — a scale range of NaN, a chart
  // of nothing, and a comparison of two engines' treatment of NaN rather than of the template.
  // Dropping the update leaves the signal's own `value`, which is the fallback size the template's
  // author wrote for exactly this case. All 58 that ask declare one.
  for (const signal of spec.signals ?? []) {
    if (typeof signal.update === 'string' && signal.update.includes('containerSize()')) {
      if (signal.value === undefined) signal.value = 400;
      delete signal.update;
    }
  }

  // Only the root Power BI supplies. A template that also declares its own tables — a map's state
  // outlines, a generated grid — keeps them exactly as written.
  const supplied = (spec.data ?? []).find((d) => d.name === 'dataset');
  if (!supplied) {
    skipped.push([relative(templateDir, path), 'no root dataset called "dataset"']);
    continue;
  }
  supplied.values = rows;
  if (Object.keys(parse).length) supplied.format = { parse };

  const name = relative(templateDir, path)
    .replace(/\.deneb-template\.json$|\.json$/, '')
    .replace(/[\/\\]/g, '__');
  writeFileSync(join(outputDir, `${name}.vg.json`), JSON.stringify(spec, null, 1));
  written++;
}

for (const [name, why] of skipped) console.log(`  skipped ${name}: ${why}`);
console.log(`Prepared ${written} template(s) into ${outputDir}`);
