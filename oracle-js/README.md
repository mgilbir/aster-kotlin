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

## Status

The scripts and canonicalization are in place. Wiring them into a Gradle differential-test task
lands with Milestone 3, when the Kotlin runtime can consume a Vega specification at all. Until then
`scripts/oracle.sh` reports that there is nothing to compare rather than passing vacuously.
