# Handoff

Working brief for whoever picks this up next. Delete it when it stops being true.

## Where things stand

Branch `milestone-0-bootstrap`, tip `760532e`. Working tree clean, both gates green:

- `./scripts/check.sh` — format, all tests, lint, demo APK
- `./scripts/oracle.sh` — regenerates upstream references and runs the differential comparison

**67 differential fixtures pass, all matching upstream exactly.** That is the only number here
that means what it says.

## Read this before trusting the other number

A survey harness (`ExampleTriage`) runs the 93 official Vega examples and reports "42 clean".
**Do not lead with that figure, and do not treat it as progress.** "Clean" means the compiler
emitted no *error* diagnostics. It does not mean the chart renders correctly, and nothing in the
42 has ever been compared against upstream's scene.

The radar chart below proves how weak it is: a specification missing only the top-level `encode`
block scores **zero errors** — the diagnostic is a warning — and still draws its entire contents
in the wrong corner of the surface. A metric under which the most visually destructive failure
does not register is not measuring what it claims to.

Two things worth doing about this, in whatever order suits:

- Reclassify diagnostics that change geometry (`encode` ignored, interpolation substituted) as
  errors in the triage, or the survey keeps flattering itself.
- Prefer adding differential fixtures over raising the clean count.

## Immediate task: the radar chart

A user supplied Vega's radar chart and reported it renders wrong. The spec is at
`/private/tmp/claude-502/-Users-m-gilbiraud-Projects-mgilbir-aster-kotlin/7cf1ea1e-ec3f-4ba3-a1db-22bdd86d95c4/scratchpad/radar/radar.vg.json`
(copy it somewhere durable — that path is session scratch). It emits exactly:

```
WARNING  Top-level encode blocks are not implemented; 'encode' was ignored
WARNING  Interpolation method 'linear-closed' is not implemented          (x2)
ERROR    Mark refers to unknown dataset 'category-line'                   (value-text)
ERROR    Mark refers to unknown dataset 'radial-grid'                     (outer-line)
```

Three distinct gaps, ranked by what a reader actually sees:

**1. Top-level `encode` is ignored.** The block is `{"x": radius, "y": radius}` and it moves the
origin to the centre of the plot. Without it every polar coordinate is drawn around (0,0), so the
chart is pinned to the top-left with three quadrants clipped. This is the single biggest visual
defect and probably the cheapest of the three. Upstream applies it to the root group; check how
it interacts with `autosize: {"type": "none", "contains": "padding"}`, which this spec also sets
and which nothing currently reports.

**2. A mark cannot source another mark's scene items.** `"from": {"data": "category-line"}` reads
back the *items* the line mark produced, to label each vertex; `outer-line` does the same to close
the grid ring. Two of five marks are missing entirely. This is the largest of the three and needs
mark output to be addressable by name after encoding. The current message calls it an "unknown
dataset", which is accurate about the lookup but names the wrong concept and will send a reader
hunting through `data` for something that was never going to be there — worth fixing even before
the feature is.

**3. `linear-closed` is not implemented**, so both radar polygons are drawn open — last vertex
never joined back to the first. `CurveKind` in `vega-scene/.../Curves.kt` has no closed variants
at all; Vega has `linear-closed`, `basis-closed` and `cardinal-closed`, so treat it as a family.

Adding this spec as a differential fixture *first* is the right move: it fails today, and it pins
all three against upstream at once so none of them can be declared done on anyone's say-so.

## Unfinished work parked elsewhere

`kde2d` + `isocontour` are checkpointed at `6ef5428` on branch
`worktree-agent-a9f49f94103bacad5`. **Deliberately not merged.** Status:

- Compiles cleanly under `allWarningsAsErrors`, both transforms registered, `check.sh` passes.
- **Numerically unverified.** No fixture, no upstream vectors, no number ever compared. The only
  test added is a registry completeness assertion listing the two names.
- **It unblocks zero examples.** `contour-plot` also needs `heatmap` (rasterises a grid to an
  image; no raster path exists here) *and* `geopath` (geo projections, out of scope per
  PROJECT_BRIEF). `density-heatmaps` needs `heatmap`. Both are mark-level `transform` blocks —
  check `marks[].transform`, not just `data[].transform`, before scoping any example.
- Consequently these two transforms **cannot currently be differentially verified at all**,
  because nothing in the engine can draw their output. Do not merge until that changes.

## Rules that are not negotiable

- **Probe upstream before implementing; never guess.** Pinned Vega is in
  `oracle-js/node_modules/vega-*/src/`. To run it, put a `.mjs` inside `oracle-js/` and run from
  there — the package is ESM, `require` fails, and a script outside cannot resolve `vega`. Delete
  probe files afterwards.
- **Both gates green before every commit.**
- **A differential fixture is the only real evidence.** A unit test asserting your own reading of
  the spec is not. A new fixture is *expected* to fail first — that failure is the information.
  Read differences from the JUnit XML at `vega-runtime/build/test-results/test/*.xml`; it is far
  more useful than the HTML report.
- **Never weaken the comparison harness to make a fixture pass.** If a tolerance is genuinely
  needed, say so with magnitude and reason.
- **If a difference is visible in rendered SVG while the comparison passes, suspect
  `oracle-js/src/normalize.js` before the engine** — the harness has had blind spots before (a
  `path` mark's whole geometry went uncompared for a long time).
- **Nothing silently ignored** (PROJECT_BRIEF §3.3): every unsupported construct gets a named
  diagnostic. Equally, remove the diagnostic when you implement the thing — stale "not
  implemented" messages on working features have shipped twice.
- **Core stays KMP-portable**: no Android types, no JVM-only APIs. `NoAndroidTypesTest` enforces
  it. `kotlinx-datetime` for calendars, `roundHalfUp` for rounding, `PlatformDecimals` is the one
  documented exception.
- **Update `STATUS.md` and `SUPPORTED_FEATURES.md` in the same commit as the change they
  describe.** Both carry a fixture count; reconcile against
  `ls test-fixtures/specs/*.vg.json | wc -l`.
- **Never add AI/Claude attribution to a commit, PR, tag or release note.** Absolute.
- Work on the branch. Never commit to `main`; do not push or open a PR unless asked.

## If you delegate to worktree agents

- Worktrees here are created on an unrelated near-empty `first commit`. Tell every worker to
  check and `git reset --hard milestone-0-bootstrap` before starting.
- Every worker adds a fixture, so every worker edits the same counts in `STATUS.md` and
  `SUPPORTED_FEATURES.md`. Those conflict on merge by construction — reconcile yourself.
- Tell workers to commit in stages. Two agents were lost mid-task this session with everything
  uncommitted.
- Verify their claims by reading the diff, especially any change to `Differential.kt` or
  `normalize.js`. One worker's harness change looked like a loosened tolerance and was in fact a
  tightening; that was only knowable by reading it.

## Corpus and tooling

- 93 official examples: `<scratchpad>/examples/`. Re-fetch from
  <https://github.com/vega/vega/tree/main/docs/examples> if the scratchpad is gone.
- Survey: `./gradlew :vega-runtime:test --tests '*ExampleTriage*' -Dexamples.dir=<dir> --rerun-tasks -q`,
  writes `triage-report.txt` into that directory. `--rerun-tasks` matters; Gradle will otherwise
  call it up to date.
- Out of reach by design, so do not count them as targets: ~14 geo/projections/topojson, 3 force
  layout, 4 using `now()`/`random()` (deliberately refused for reproducibility), plus the raster
  family above.
