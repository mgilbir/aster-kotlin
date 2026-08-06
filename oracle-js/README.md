# oracle-js

Upstream Vega, pinned, used as the reference implementation for differential tests
(PROJECT_BRIEF.md 6.1, 18.4).

Every dependency is pinned to an exact version. Never use `latest`, a range, or an unpinned
dependency here: the whole point of the oracle is that its output does not move under us.

## Install

```bash
cd oracle-js
npm ci
```

`npm ci` installs exactly what `package-lock.json` records. Run `npm install` only when
deliberately changing a pinned version, and commit the updated lock file with an explanation.

## Usage

Render a Vega specification and emit both a canonical scene description and SVG:

```bash
node src/render.js ../test-fixtures/specs/bar.vg.json build/bar
```

That writes `build/bar.scene.json` and `build/bar.svg`.

Compile a Vega-Lite specification down to Vega (the native compiler is out of scope for the first
release, PROJECT_BRIEF.md 3.1):

```bash
node src/compile-vega-lite.js input.vl.json output.vg.json
```

## Canonicalization

`src/canonical.js` normalizes the reference output the same way the Kotlin side does:

- object keys sorted
- numbers rounded to a fixed precision
- `-0` normalized to `0`
- non-finite values written as explicit tokens
- generated identifiers stripped

Both sides must apply identical rules, otherwise a diff reports formatting rather than behaviour.

## Differential references

`src/reference.js` emits the normalized comparison model the Kotlin differential tests read, and
`../scripts/oracle.sh` regenerates one per fixture into `../test-fixtures/reference/`. Those files are
checked in, so the JVM tests need neither Node nor a network.

`src/normalize.js` flattens Vega's scenegraph into that model. Structure is deliberately not preserved:
Vega nests marktypes inside group items and emits one item per datum even for a line, whereas this
engine produces a flat node list with one path per series. Comparing tree shape would report a
difference on every fixture while saying nothing about correctness, so both sides collapse to absolute
coordinates, and lines and areas to a single outline point list.

Two other normalizations, each because the drawn result is identical either way:

- a text mark's `dx`/`dy` are folded into its anchor, where Vega keeps them as separate render-time
  offsets
- glyph bounds are excluded entirely, since font metrics legitimately differ from a browser's
  (`../docs/adr/0006-text-measurement-and-font-policy.md`)

## Probes

Three scripts exist to establish upstream behaviour before implementing it, rather than guessing:

- `src/eval-probe.js` — evaluates expressions and prints their results
- `src/transform-probe.js` — runs a transform pipeline and prints the resulting dataset
- `src/render.js` — renders a fixture to a canonical scene summary and SVG, for eyeballing

Every reference vector in the Kotlin tests came from one of these. `transform-probe.js` deep-copies its
input per run, because Vega's transforms mutate the tuples they are given and a shared array silently
contaminates every result after the first.
