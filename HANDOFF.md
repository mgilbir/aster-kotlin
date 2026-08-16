# Handoff

Working brief for whoever picks this up next. Delete it when it stops being true.

## Where things stand

Branch `milestone-0-bootstrap`. Working tree clean, both gates green:

- `./scripts/check.sh` — format, all tests, lint, demo APK
- `./scripts/oracle.sh` — regenerates upstream references and runs the differential comparison

**177 differential fixtures pass, all matching upstream exactly.** That is the only number here
that means what it says.

## Read this before trusting the other number

A survey harness (`ExampleTriage`) runs the 93 official Vega examples and reports a "clean" count.
**Do not lead with that figure, and do not treat it as progress.** "Clean" means the compiler
emitted no *error* diagnostics. It does not mean the chart renders correctly, and nothing in that
count has ever been compared against upstream's scene.

The radar chart proved how weak it is, twice over. A specification missing only the top-level
`encode` block scored zero errors — the diagnostic was a warning — and still drew its entire
contents in the wrong corner. And once the errors *were* gone and the differential fixture passed,
the chart was still visibly wrong: the grid lines painted over the data, because mark `zindex` had
been parsed and silently dropped and nothing in the comparison could see paint order.

Two things worth doing about this, in whatever order suits:

- Reclassify diagnostics that change geometry as errors in the triage, or the survey keeps
  flattering itself.
- Prefer adding differential fixtures over raising the clean count — and **look at the rendered SVG
  after a fixture goes green**, because that is what caught both of the above.

## The radar chart is done

`test-fixtures/specs/radar.vg.json` is Vega's own radar chart, and it passes. Getting there needed
four things in the engine and two in the harness; all six are described in STATUS.md under "Known
failing fixtures". In short:

- Top-level `encode` is implemented — the chart's own group item, encoded like any other group mark
  over upstream's two defaults (origin at 0,0, extent of the plotting area).
- `autosize.contains: "padding"` is implemented, and shrinks the `width`/`height` signals.
- A mark can be drawn from another mark's scene items (`"from": {"data": "<mark name>"}`).
- `linear-closed`, `basis-closed` and `cardinal-closed` are implemented.
- A `line` mark is now filled when it encodes a fill.
- Mark `zindex` decides paint order, upstream's way — a **negative** `zindex` raises a mark rather
  than sinking it.

Two new fixtures came with it: `curves-closed`, because the radar only exercises one of the three
closed families, and `autosize-none`, because nothing else covered a `none` chart with padding.

## So is the grouped bar chart

`grouped-bar.vg.json` is Vega's `grouped-bar-chart` example, taken the same way, and it found three
more silences:

- `round: true` on a **continuous** scale was parsed and dropped, so every bar was a fraction of a
  unit wide. It is the output that rounds, not the scale — upstream swaps the range interpolator.
- `mult`, `offset`, `exponent` and `round` were read only on a *scaled* value reference. Upstream
  appends them to every one, and each is itself a value reference, so `{"field": "y", "offset":
  {"field": "height", "mult": 0.5}}` centres a label in a band.
- `contrast()` is implemented. White and black are within 1% of each other against Vega's default
  blue, so this is not a place to approximate.

## And so is the barley trellis

`barley-trellis.vg.json` is Vega's `barley-trellis-plot`, loading its 120 rows from the example's own
`"url": "data/barley.json"` through the file loader. It found four more:

- **`width` and `height` are signals, not just properties.** This chart declares its height as
  `6 * (offset + cellHeight)`; the plotting area is now settled after the signals resolve.
- A mark could be drawn from a plain mark but not from a **group** mark. Six cell titles were
  missing.
- Guide `encode` blocks. Folded into the properties they duplicate — `encode.grid.enter.strokeDash`
  *is* `gridDash` upstream — which is also what makes them participate in measurement.
- A legend over a `stroke` scale was missing upstream's explicit `transparent` fill. The test is on
  the `fill` channel alone, not on whether the legend maps any colour.

And one the comparison still cannot see, found by looking at the SVGs: a `zindex: 1` axis paints
**after** the legends upstream, and was painting before them here.

## And so is the connected scatter plot

`connected-scatter.vg.json` had been waiting only on the loader. With its data it found two more:

- An **ordinal scale with a numeric range** could not position a mark — refused as "no numeric
  range" — which is exactly how a label is nudged clear of its point. Upstream applies the scale and
  uses whatever number comes out; so does this now.
- An axis `format` was reported and ignored, so a price axis read `1.5` for upstream's `$1.50`. The
  currency symbol is its own slot in d3's grammar, and the caption a screen reader hears follows
  **each axis's** format rather than the scale's.

## The loader is done, and it is what was blocking the rest

The engine had a file loader and an HTTP loader and no way to compose them, and
`VegaChartController` had no seam to pass either — so no host could opt in to loading at all. Both
are fixed:

- `FallbackDataLoader` (common Kotlin) tries loaders in order, first one that serves the URI wins.
  Only a `LoadDeniedException` moves on; a broken socket propagates.
- `VegaDataLoaders.directoryThenNetwork(dir)` is the arrangement a corpus needs: read `data/x.json`
  from `dir` if it is there, fetch it from `https://vega.github.io/vega/` if it is not, and with
  `cacheDownloads = true` write it to `dir` so the second run is offline. The base URL's host is the
  allowlist by default.
- `VegaChartController(loader = ...)`. Still `DenyLoader` unless a host says otherwise.

**The fixture harness uses the file half only.** A green run must not depend on a connection, so
`test-fixtures/data/` is checked in beside `test-fixtures/reference/` and `scripts/oracle.sh` fetches
what is missing as a deliberate step. The oracle resolves a fixture's `url` against the spec's own
directory and then the corpus root, which is what `oracle-js/src/file-loader.js` does — the two
engines have to read the same bytes.

`ExampleTriage` uses the network half with caching, so a corpus of bare specifications fills itself
in on the first run. It is a survey run by hand, not a gate.

The **demo** wires it too — `directoryThenNetwork(cacheDir, cacheDownloads = true)` — so pasting a
gallery example with `"url": "data/..."` draws the chart. It needs `INTERNET` in the manifest, and
`DemoActivityTest.aPastedSpecificationLoadsItsDataFromTheGallery` proves it on the device over a real
socket, which is the only place a missing permission would show up.

**Pre-existing, unrelated:** `DemoActivityTest.theClipboardIsReadBackAsTextWhileTheAppHasFocus` fails
on this emulator — the clipboard needs foreground focus on Android 10 and later, which the run does
not reliably give. It fails identically on the commit before the loader work, and connected tests are
not part of `check.sh`.

## And so is the Wilkinson dot plot

`dot-plot-wilkinson.vg.json` needed three things, and the third took two wrong guesses to find:

- A top-level signal calling `scale()` — `scale('x', step) - scale('x', 0)`, a step in data units
  turned into pixels. Signal-free scales are now built before the signals.
- A dataset of bare numbers, wrapped as `{"data": value}` the way upstream's `ingest` does.
- **One epsilon.** A value landing exactly on a bin boundary divides to a whole number only in exact
  arithmetic: `(9.1 - 1.95) / 0.65` is 10.999999999999998 in doubles, so flooring it put the row one
  column to the left. Upstream adds `1e-14` inside the floor. Three of the 48 points sit on a
  boundary — enough to make the tallest column one dot short and the chart 9.75 units too short, and
  nothing else in the chart was wrong.

Both wrong guesses are worth knowing: `dotbin` is innocent (identical output, smoothed and not) and
so is `nice` (already defaulting to true). Both are pinned now, so the next person does not re-check
them. What found it was printing every signal from both engines side by side —
`size`, `ddext`, `hdext`, `ddh`, `hdh`, `height` — and seeing that only `hdext` differed, by one.
That technique is cheap and worked immediately where reading the transforms did not.

## The compile phases are gone, and so is `probability-density`

The last big structural difference from upstream is closed. The engine ran three fixed phases — all
data, then all signals, then all scales — and `probability-density` cannot be resolved by any fixed
order of them: `xscale`'s domain names the `points` dataset, and the `density` dataset's `extent` is
`{"signal": "domain('xscale')"}`. `DataflowOrder` replaces the phases with one dependency ranking
over all three kinds, which is what upstream gets from putting every operator into a single dataflow.

Worth knowing before touching it:

- Edges come from where upstream's come from. `Expression` now reports `dataDependencies` and
  `scaleDependencies` **by name**, read off the string literal a `data()` or `scale()` is handed,
  exactly as `vega-functions`' `dataVisitor` and `scaleVisitor` do at parse time.
- Ties break towards signals, then datasets, then scales, and that is load-bearing rather than
  cosmetic. It is what keeps a signal reading no dataset ahead of every dataset — the property the
  old seeding pass existed for — and it means every signal that has *become* resolvable resolves
  before the next dataset runs.
- **A transform can *publish* a signal**, and reading one is an edge to the dataset that writes it.
  `{"type": "extent", "signal": "vals"}` is the shape. This is required, not an improvement: the old
  phases resolved every signal again after all the data, so a wrong early value was overwritten;
  resolving each signal exactly once removes that safety net. `dot-plot-wilkinson` covered the
  pattern only by luck (its `ddh` reaches the data through `size` and a scale), so
  `published-signals` isolates it — it fails without the edge.
- `dataFreeSignals` and `ScaleSpec.isSignalFree` are gone. Do not reintroduce a predicate of that
  shape; the general order subsumes both, and `isSignalFree` had a hole (it never looked inside a
  range array) that only showed up once the graph did the same job properly.
- **The residual gap is transform expression parameters.** `filter`'s `expr`, `formula`'s `expr` and
  `cross`'s `filter` are the only three parameters upstream declares as `type: 'expr'` (probe it with
  `T.Definition.params` over `vega.transforms`). They are per-row expressions rather than
  `{"signal": ...}` references, so they are *not* edges, and a dataset carrying one is not held back
  for the signal it reads. It usually works anyway because of the tie-break; when it does not, the
  warning in `DataResolver` is the only thing that says so. Adding those three as edges is the
  obvious next increment and is small — the risk is that a new edge turns a chart that happened to
  work into a reported cycle, so do it behind the gate.
- **A group mark still has three phases**: its own data, then its own signals, then its own scales.
  Nothing in the corpus needs them interleaved, because the enclosing scope is entirely settled by
  the time a group is reached. If an example ever does, `DataflowOrder` is reusable as-is —
  `ScopeCompiler.nest` is where it would go.

## Pick the next example the same way

The method that worked seven times: take one real example, add it as a differential fixture *first*, let
it fail, fix what it names, then open `build/fixture-svg/<name>.ours.svg` next to
`build/oracle-reference/<name>.svg` and look at them. The fixture tells you the geometry is right;
only the SVG tells you the chart is. Note that upstream draws a `rect` mark as an SVG `<path>` and
scatters zero-extent `class="background"` and `class="foreground"` paths through its output — strip
those before comparing, they paint nothing.

**Data is no longer a reason to skip an example.** Copy the spec in, run `./scripts/oracle.sh`, and
its datasets are fetched into `test-fixtures/data/` and committed. Discount every loader diagnostic
when judging how far an example is from passing.

**Where the corpus stands, from `ExampleTriage` rather than from memory: 91 of the 93 compile
clean, 2 report errors.** Read the *movement* rather than the number: 70 → 79 as the stochastic and
crossfilter work landed, **79 → 75 when mark-level `transform` was implemented** — the survey
becoming honest, because five charts had been dropping a whole block silently — and 75 → 80 as the
raster family and `force` landed.

The 2 that remain **cannot be verified against upstream at all**, and one of them upstream itself
refuses. There is no category of outstanding work left in the corpus.

**`time-units` is done** and is a fixture; STATUS.md describes the five things it needed. The
handoff's prediction was right as far as it went — the domain field is a `FieldRef` now — but the two
"small residuals" were not small and were not what they looked like. The missing tick and the wrong
width were **one** defect each, and neither was arithmetic on ticks: the tick came back the moment the
domain was no longer empty, and the width was a floating-point crumb in a rotated text mark's bounds
that `Math.ceil` turned into a whole unit of plotting area. Read the STATUS section before assuming
anything similar is a rounding tolerance.

**`calendar-view` is done** too, and the old note above it was wrong in an instructive way: the
marks were not transposed, the *facets* were in the opposite order because a mark `sort` over
`datum.year` could only read `x` and `y` and silently tied. And `timeOffset` was implemented but
returning its argument. STATUS.md has the six things it needed.

**`crossfilter-flights` is done** and is a fixture. The note that used to sit here said it could not
be one because it draws 600,098 scene nodes; **upstream draws 171**. The 600,098 was this engine
drawing every unfiltered row three times over because the two transforms were missing. A triage node
count measures how wrong we are, not how big the chart is — do not size a decision off one. The test
heap is pinned at 2 GB now, for the unrelated reason that 200,000 rows through three `bin`
transforms is a large live set when transforms copy rather than mutate.

## The three refusals are lifted; two are finished and one is not

The owner asked for all three of the brief's scope refusals to be overturned and for every example to
pass. **PROJECT_BRIEF.md §3.3 and §18.2 are stale on this point** and should be amended when the geo
work lands; until then, read them as history rather than as policy.

**1. `random()` and `now()` — done.** `RandomStream` is upstream's `randomLCG` and
`oracle-js/src/determinism.js` puts the same generator and a stopped clock into upstream, so these
charts can have references at all. `clock`, `watch`, `error-bars`, `hypothetical-outcome-plots`,
`pi-monte-carlo`, `serpentine-timeline` and `bar-line-toggle` are all fixtures and all pass.

**2. The raster family — done.** `volcano-contours`, `density-heatmaps` and `contour-plot` are
fixtures and match exactly. `isocontour`, `geopath` without a projection, `kde2d`, `heatmap`, a
raster payload on `ImageNode`, a PNG encoder and mark-level `transform` all landed with them. The
parked `worktree-agent-a9f49f94103bacad5` branch is **superseded** for `isocontour` — this port is
verified against upstream where that one never was — and should be dropped rather than merged.

The harness was strengthened first, as the previous handoff insisted: `normalize.js` now takes an
FNV-1a digest of `getImageData` on the upstream side and the Kotlin side hashes its own pixels, so an
image mark is compared by what it *draws*. A blank image cannot pass.

**3. Force layout — done, and it was never the irreproducible thing it is assumed to be.**
`force-directed`, `beeswarm` and `packed-bubble` are fixtures. d3-force seeds a fixed LCG rather than
reaching for `Math.random`, and a node with no position starts on a phyllotaxis spiral; what is left
is arithmetic. See SUPPORTED_FEATURES.md for the three behaviours that had to come from upstream
rather than from its schema — in particular that **an omitted force parameter falls to d3's default,
not the one Vega documents**, because Vega only forwards the parameters a specification wrote.

## `regexp` and `test` were missing, and a bound text field is what showed it

Vega's job-voyager example filters with `test(regexp(query,'i'), datum.job)`. Neither function existed
here, so the filter **threw for every row** the moment the query was not empty: every row dropped and
the chart went blank. Nothing in the corpus caught it, because no fixture typed anything into anything
— it took a control bound to that signal for the failure to become visible.

`isRegExp` was the tell, and it had been rationalised: "no value this engine can produce is a regular
expression, and upstream answers false for every one of them too". True only because `regexp()` was
missing. If a predicate's justification is that nothing can reach the state it tests, check whether the
thing that produces that state exists.

A pattern is now `VegaValue.Pattern`, a variant beside `Timestamp` rather than an object with marker
fields — `isRegExp` has to answer true, and `'' + regexp('a.b','i')` has to be `/a.b/i`. Adding the
variant broke four exhaustive `when`s in main sources and three in tests, which is the whole cost of
doing it properly. Flags: `i`, `m`, `s` map onto Kotlin's; `g`, `y`, `u` are accepted and ignored,
except in `replace`, where `g` decides first-match against all-matches.
## The core is multiplatform, and compiling it found what the grep could not
`vega-model`, `-expression`, `-dataflow`, `-scene`, `-runtime` and `-svg` are now
`kotlin("multiplatform")`: a `jvm` target plus `macosArm64`, `iosArm64`, `iosSimulatorArm64` and
`linuxX64`, all four compiled by `scripts/check.sh`. `vega-loader` and `test-fixtures` stay Kotlin/JVM
on purpose — a socket, a file on disk, and test scaffolding are the platform, not the port.
Sources did **not** move. Each module points `commonMain` at `src/main/kotlin` and `jvmTest` at
`src/test/kotlin`, so the diff is a build change rather than two hundred renamed files.
**What compiling found that six milestones of `NoAndroidTypesTest` did not.** `LinkedHashMap` is
common Kotlin, so two caches that *subclassed* it — `TextLayoutCache` and `CachingExpressionCompiler`,
both using the three-argument access-order constructor and `removeEldestEntry` — passed the grep
cleanly. Neither exists off the JVM, where the class is final besides. Both are now an explicit LRU in
four lines: remove-and-reinsert on a hit, evict `keys.first()` when over capacity. `Character.digit`
was the third, replaced by the ASCII digit rule — which is what `parseInt` does anyway, so the
narrower answer is the more faithful one.
**The trap, which cost a green run.** The multiplatform plugin gives a module `jvmTest` and **no
`test` task**. `./gradlew test` therefore matched nothing in six modules, ran no core tests at all,
and printed BUILD SUCCESSFUL. If you add a module or a script, check that what you run actually runs:
there is now a `test` alias registered in every multiplatform module, and `oracle.sh` says `jvmTest`
outright because `--tests` only takes a real `Test` task.
**Tests run on a native target now.** `commonTest` source sets in `vega-model`, `-scene`,
`-expression` and `-runtime` hold 23 tests that execute on **Kotlin/Native as well as the JVM**, and
`scripts/check.sh` runs `macosArm64Test`. They cover exactly what was in doubt: the decimal expansion
(bit manipulation and 64-bit arithmetic), a specification's own regular expressions (which also
exercises `ktecma262`'s native artifacts), d3's tick algorithm (`kotlin.math` is the platform's
library on each target), and the two LRU caches that only exist in their present form because of
these targets.

Three things to know before adding more:

- **A backtick test name cannot contain a comma** — or `.`, `;`, `:`, brackets — on Kotlin/Native,
  where it becomes a symbol name. It fails the *compile*, not the run.
- `iosSimulatorArm64Test` is the natural addition, and this machine cannot run it: no simulator
  runtime is installed, so Xcode refuses the task ("does not support simulator tests for
  ios_simulator_arm64"). The Apple targets still **compile** in the gate.
- The differential fixtures stay on the JVM: they read specifications and references off disk.

The suite has already earned itself. `TicksCommonTest` asserted that `tickIncrement(0, 1, 2)` is 0.5;
d3 returns **-2**, a negative *divisor*, because a step below one is expressed as something to divide
by. The native run is what refused it, and the fix was to read the answer out of `d3-array` instead of
assuming it.

## Regular expressions are ECMA-262 now

`VegaValue.Pattern` compiles with **`io.github.mgilbir:ktecma262`**, an ECMA-262 engine in common
Kotlin, instead of Kotlin's `Regex`. A pattern in a specification is JavaScript's; Kotlin's `Regex` is
whatever the platform has — `java.util.regex` on Android, a different engine on each native target.
The four divergences that had been measured are now vectors, and they pass:

| pattern, subject | upstream | before | now |
| --- | --- | --- | --- |
| `a$` on `"a\n"` | no match | matched | no match |
| `x{` on `"x{"` | matches | **threw** | matches |
| `\a` on `"a"` | matches | no match | matches |
| `[]` on `"a"` | never matches | **threw** | never matches |

Three things came with the swap, all of them upstream behaviour this engine had approximated:

- **Flags are honoured, not translated.** `g`, `y`, `u`, `v` and `d` used to be accepted and dropped
  because Kotlin had no equivalent. `g` in particular decides whether `replace` replaces once or
  throughout, and the engine applies that itself — so `Functions.replace` no longer branches on flag
  text.
- **`$` substitution in a replacement is JavaScript's**: `$&`, `` $` ``, `$'`, `$1`, `$<name>`, with an
  invalid reference emitted literally. Kotlin's `Regex` spells that differently.
- **`split` takes a pattern.** It had been stringifying one to `/\d+/` and splitting on that literal,
  so it silently returned the whole string. Capture groups now appear in the result and a
  non-participating group is a hole (`null`), which is what JavaScript does.

`test` deliberately calls `findAll(...).isNotEmpty()` rather than the engine's `test`. JavaScript's
`RegExp.test` advances `lastIndex` under `g`, so consecutive calls alternate; upstream gets away with it
because it builds a fresh `RegExp` per evaluation, while a `Pattern` here is built once and read per
row. The stateless form is what keeps a filter's answer independent of how many rows preceded it.

**Not blocked any more.** `io.github.mgilbir:ktecma262:0.1.4` is on Maven Central with all seven
modules — `common`, `jvm`, `js` and the four native ones — so it resolves like any other dependency
and `settings.gradle.kts` is back to `google()` and `mavenCentral()` alone. Verified by moving the
locally published copies out of `~/.m2` and resolving every target from Central with
`--refresh-dependencies`: a green build quietly reading a local artifact would prove nothing.

Two things about that dependency are worth remembering. A published version cannot be amended, so a
release that omits a target needs a new number rather than a re-upload — 0.1.2 shipped `jvm` and `js`
only, and 0.1.3 declared native variants whose per-target modules were absent, which fails later and
more confusingly than omitting them would. And if a `mavenLocal()` is ever added back, it belongs
**above** `mavenCentral()`: Gradle takes the first repository holding a coordinate, so a partially
published version of the same number would otherwise win.

Worth knowing about that repository's release workflow, since it caused a false alarm: it publishes to
Central and **never creates a GitHub Release**, so `gh release list` showing only v0.1.0 says nothing
about what is published. Its version comes from `build.gradle.kts` and is *guarded* against the tag
name, so a tag/version mismatch fails the run loudly rather than silently shipping the wrong number.
The v0.1.2 tag run did fail — 401 from the staging API — and a later manual dispatch is what published
it, as a `user_managed` deployment needing a click in the portal.


## `Regex` is the platform's, and upstream's is ECMA-262
Vega's `regexp`, `test` and `replace` take a pattern **from the specification**, and upstream runs it
through JavaScript's own engine. Kotlin's `Regex` delegates to whatever the target has — `java.util.regex`
on the JVM and Android, Kotlin/Native's own engine on the native targets, the real thing on JS — so the
same specification can behave three ways, none of them guaranteed to be upstream's. Measured, not
assumed:
| pattern, subject | ECMA-262 (upstream) | JVM (what Android runs) |
| --- | --- | --- |
| `a$` on `"a\n"` | no match | **matches** — Java's `$` sits before a final line terminator |
| `x{` on `"x{"` | matches, `{` is literal | **throws** `Illegal repetition` |
| `\a` on `"a"` | matches, identity escape (Annex B) | no match |
| `[]` on `"a"` | never matches, by definition | **throws** `Unclosed character class` |
The two throws are the serious half: `test(regexp('x{'), 'x{')` is `true` upstream and an exception
here, and a specification is data — often *pasted* data — so that is a chart taken down by a string
someone typed.
**The swap surface is one line.** `VegaValue.Pattern.regex` is the only place a specification's own
pattern becomes a `Regex`, and it has exactly three readers: `test` (`Functions.kt:375`) and the two
branches of `replace` (`386`, `388`). Everything else that uses `Regex` in the core — twelve sites —
is a fixed pattern this repository wrote, where the only requirement is that the platforms agree with
each other.
The owner is porting an ECMA-262 engine to Kotlin in a separate effort and will supply the URL; when it
lands, point `Pattern` at it and the divergence closes on every target at once. The table above is the
first four vectors to write. Until then this is a **known**, measured divergence rather than an
unexamined one — and note that no test in this repository runs on a native target, so Kotlin/Native's
regex behaviour is unmeasured even for our own internal patterns.
## Vega's own tests, recorded rather than transcribed

Upstream has ~3,700 assertions across ~30 packages, and they are the best-chosen inputs anyone has
produced for this grammar. `oracle-js/src/record-upstream-tests.mjs` turns them into differential
vectors **without reading their assertions**: it runs each of their test files against the installed
Vega 6.3.1 with the package's exports wrapped in a recording proxy, and writes every call — arguments,
and what upstream actually returned — to `test-fixtures/upstream-vectors/<package>.json`.

Recording beats transcribing for two reasons. Their assertions are sometimes deliberately loose
(`t.ok(x > 0)`) where a port needs the exact value; and on a **version upgrade** the diff is data
rather than a hand-edit spread across Kotlin. Re-run the recorder against a newer checkout and read
the JSON diff.

The imports are rewritten by **AST** (`acorn`), which is the part that has to survive that upgrade. The
regex version it replaced did not survive the *current* version: it handled `import {a, b}` and missed
`import * as vega`, the form 55 of these files use. Recorded today:

| package | vectors | files run |
| --- | --- | --- |
| `vega-time` | 460 | 9/9 |
| `vega-scenegraph` | 190 | 9/17 |
| `vega-expression` | 149 | 2/2 |
| `vega-statistics` | 112 | 13/13 |
| `vega-scale` | 34 | 3/3 |
| `vega-functions` | 33 | 5/5 |
| `vega-event-selector` | 18 | 1/1 |
| `vega-format` | 11 | 3/3 |

**The transform seam is in, and it was the whole prize.** `vega-transforms` and its neighbours export
operator *classes* driven through a `Dataflow`, so nothing crosses the export boundary with plain
arguments — the export recorder saw zero calls from 30 files that ran perfectly. One level in,
`prototype.transform(_, pulse)` sees everything: parameters, the tuples in, the tuples out, and the
operator's own value where it keeps one. That took the corpus from 1,007 vectors to **1,749**, with 548
from `vega-transforms` alone.

Four things had to be right, and each was learned by getting it wrong:

- **Accessors and comparators both carry `fields`.** Reducing `compare(['count'], ['descending'])` to
  the string `count` drops the direction, and a sorted `collect` then looks like an unsorted one. A
  comparator also has `orders`, which is how they are told apart; where the direction is genuinely
  lost the vector is marked unreplayable rather than mis-compared.
- **A tuple can be enormous.** Capping the number of tuples is not enough: a `facet` group tuple
  carries its whole partition, and three of them produced a 52 MB vector inside a **452 MB** file that
  exhausted a 2 GB heap before a single transform ran. Vectors are now capped by encoded size.
- **Operators are stateful.** `aggregate` remembers every group value it has seen, so a later pulse's
  output depends on every pulse before it — one recorded output contains a cell for a value that is
  not in its input. Each call is stamped with its operator instance and sequence number, and only the
  first call on a fresh operator is replayed.
- **An `expr` recorded as an accessor was a JavaScript function.** `d => d.id * 2` keeps only the
  field it read, so replaying it as the expression `id` computes something else and looks like a bug
  in `formula`.

## Regenerating the vectors: `scripts/record-upstream-vectors.sh`

One command, and the only thing it needs told is nothing:

```
./scripts/record-upstream-vectors.sh
```

**The vectors themselves are not committed.** They are derived — nearly 5 MB of somebody else's
inputs, rebuildable byte-identically — so the repository carries the *recipe* instead. Two consequences
to know: a fresh clone has no vectors, so `UpstreamTimeVectorsTest` and `UpstreamTransformVectorsTest`
are **skipped** rather than passing (an assumption, and `scripts/check.sh` prints a note saying so);
and `known-divergences.json` *is* committed, because it is not a copy of upstream but this engine's
own list of places it disagrees, with a bug behind each entry.

It reads the pinned version from the **installed** Vega (`oracle-js/node_modules/vega/package.json`),
clones that exact tag of the monorepo into `build/vega-upstream` — the tests are not published to npm —
records every package, and drops any file that recorded nothing rather than leaving an empty one that
looks like coverage.

**Upgrading Vega is therefore: bump `oracle-js/package.json`, `npm ci`, run this, read the diff.** The
tag is not written down anywhere else.

Getting that diff to mean something took four fixes, because a checked-in artifact that changes on
every regeneration is worse than no artifact:

- **One package per process.** Vega numbers every tuple from a module-level counter and those ids
  reach the data — a `lookup` keys its index by them — so recording two packages together made the
  second depend on the first.
- **Paths relative to the checkout**, and this machine's paths scrubbed out of recorded error text.
- **Deterministic scratch file names.** A timestamp in the name reached the recorded text, because
  Node names the importer in a resolution failure.
- **`Math.random` and `Date.now` pinned**, with the same seed and instant as `determinism.js`, so a
  vector and a fixture reference agree on what "random" means. **Order matters**: the constants are
  inlined rather than imported from `determinism.js`, because importing that imports *vega*, which
  captures `Math.random` into its own generator as it loads — pinning afterwards fixed the tests' own
  calls and left the `sample` transform drawing from the real one. That was 8 vectors that changed on
  every run.

Two consecutive runs are now byte-identical, which is the property that makes the diff readable.

## d3's tests are in the corpus too, and they are the bigger half

Most of the *arithmetic* this engine ports is d3's rather than Vega's — ticks, scales, curves, arcs,
colour, number and time formatting, calendar intervals — and d3 tests it far more thoroughly than any
chart-level fixture can. `scripts/record-upstream-vectors.sh` records nine d3 packages beside the Vega
ones, taking the corpus to **4,898 vectors**: `d3-array` 1,538, `d3-time` 1,047, `d3-interpolate` 253,
`d3-color` 216, `d3-scale` 55, `d3-shape` 31, `d3-path` 7.

Five differences from Vega, all handled by the same recorder:

- **mocha, not tape**, and `it` is a *global* there rather than an import, so it is installed on
  `globalThis` and the file is left alone.
- **`../src/index.js`**, not `../index.js` — d3 tests import the source; both are pointed at the
  installed build, which is what every reference here is generated from.
- **One repository per package**, tagged `v<version>`; the version comes from `node_modules`, so the
  tag is derived rather than written down.
- **Its own timezone.** `d3-time` runs its suite in `America/Los_Angeles` and its answers depend on
  it, so the zone is read from the package's own `scripts.test` and **recorded in the vector file** —
  a replay has to know which zone produced a local interval.
- **`new Date()`**, which pinning `Date.now` does not cover. The `Date` constructor is replaced too,
  or `d3-time`'s vectors change on every run.

`d3-time` alone yields more vectors than all of `vega-transforms`, and it lands squarely on
`TimeIntervals`: every interval's `floor`, `ceil`, `round`, `offset`, `range`, `count` and `every`,
local and UTC.

**Where it is thin, and why.** `d3-shape` (31), `d3-scale` (55) and `d3-hierarchy` (1) are
builder-shaped: `arc()`, `scaleLinear()` and `stratify()` return an object that is *configured* by
chained calls and only then asked a question, so a single call is not a vector — the state is the
input. That is the same problem the transform seam solved with an instance-and-sequence stamp, and the
same solution applies: record the chain per object and replay it in order. `d3-format` records nothing
at all yet (24 files, all skipped) and is worth a look, because it is what `Decimals` and the
`format` expression implement.

## Replaying d3-color: the parser is a **superset**, deliberately

`UpstreamD3ColorVectorsTest` replays 34 of d3-color's `color()` vectors against `SceneColor.parse`.
Nineteen of them expect **null** — a malformed colour is half of d3's corpus and the half a
hand-written test skips, because rejecting one matters as much as parsing one: the alternative is a
mark painted an arbitrary colour rather than left alone.

Two things came out of it, neither a bug in the parser:

- **Units.** `SceneColor` keeps channels in 0..1 and d3 keeps them in 0..255, so the comparison scales
  rather than pretending they agree.
- **This parser accepts a superset.** d3's regular expressions require integers, so it rejects
  `rgb(120.5,30,50)`, a trailing `rgb(120.,...)` and an alpha of `1.` — all of which a *browser*
  accepts. That is the right rule for **rendering**, where a `fill` goes to a renderer rather than to
  d3, and the wrong one for **`luminance()` and `contrast()`**, which are d3-color calls upstream and
  would answer NaN for those strings. Thirteen signatures are pinned in `known-divergences.json` so a
  further loosening has to be a decision rather than an accident.

What is not replayed is named: `hsl`, `hcl`, `lab`, `lch`, `cubehelix` and `gray` are colour *spaces*
this engine does not model as objects — it keeps one representation — so there is nothing to compare
component by component.

## Two more replays: the path parser and the bin algorithm

**`vega-scenegraph`'s `pathParse`** (20 replayed). Every `shape` mark, custom `symbol` and `path` in a
specification arrives as an SVG path string, and upstream's corpus is the grammar's traps: implicit
repeats (`M0,0 1,1 2,2` is a move and two lines), a relative run with no separators (`l.5.5.3.3`),
`H`/`V` shorthands, a `z` mid-path, two `Z`s in a row, numbers glued together by their signs
(`M-1-1H1V1`). The two representations differ on purpose — upstream keeps the raw letters, this engine
resolves them to absolute points — so the adapter applies upstream's own resolution rules to its
segments and compares the *behaviour* rather than the notation. Everything agrees except one, pinned:
a path that starts with a **drawing command rather than a move**. Upstream's parser emits lines from
the origin; this one refuses the string, which is what the SVG grammar requires and what a browser
does — so upstream disagrees with itself between its canvas and SVG renderers, and refusing is the
answer that matches what a reader sees in the DOM.

**`vega-statistics`' `bin` and `quantiles`** (13 replayed, all agreeing). The interesting part is what
the first run *looked* like: four disagreements that were all the adapter's fault, and worth writing
down because the next adapter will meet the same two shapes.

- `binSettings` here is upstream's `bin` **plus** the realignment its `Bin` *transform* applies on top
  (`start + ceil((stop - start) / step) * step`), because both belong to the settings a histogram is
  drawn from. The vector records the bare function, so the comparison applies upstream's own
  realignment to upstream's own result. Without that it looked like this engine was extending the last
  bin — it is, and so is upstream, one layer up.
- One `quantiles` vector passes **objects behind an accessor** rather than numbers. Reading numbers
  out of it with `mapNotNull` produced an empty list and a NaN answer, which looked exactly like a bug
  in `quantiles`.

`known-divergences.json` now tags each entry with a `kind` and a `subject`, because three replays read
it and each must assert only its own: a list asserted whole fails as soon as a second replay adds to
it.

## Wrapping a **returned function** tripled the corpus

d3's interpolators, its scales, `timeFloor('year')`, a regression's `predict` — a whole family of APIs
is *built and then called*, and recording only the construction recorded `{$: 'function'}` and nothing
else. The recorder now wraps what comes back, and every call on it becomes a vector carrying the
arguments it was **built with**:

```json
{"fn": "interpolateRgb()", "constructedWith": ["steelblue", "brown"], "args": [0.25],
 "result": "rgb(94, 108, 146)"}
```

`d3-interpolate` went from 253 vectors to 533 and the whole corpus from 4,898 to **15,220**. One level
only: a longer chain is a builder, and belongs to the instance-and-sequence recording the transforms
use.

`UpstreamInterpolateVectorsTest` replays 68 of them against `ColorSpaces` — `rgb`, `lab`, `hcl`, `hsl`,
`cubehelix`, both hue directions, and the generic `interpolate` — and **all agree**. Worth noting why
these vectors are worth having: which space a ramp passes *through* is invisible at its ends, so a
test that checks the endpoints passes on every implementation. These sample t = 0, 0.25, 0.5, 0.75, 1,
which is where a wrong space shows. The comparison is channel by channel with a half-unit tolerance,
because upstream prints `rgb(94, 108, 146)` with its channels already rounded.

## One replay covers every operator package now

`UpstreamTransformVectorsTest` reads all seven packages whose meaning lives in an operator —
`vega-transforms`, `-encode`, `-geo`, `-hierarchy`, `-regression`, `-crossfilter`, `-voronoi` — because
they share the `prototype.transform` seam. 151 replayed vectors became **169**, `stack` among them.

It also learned a distinction worth keeping: some operators publish something that is **not tuples**.
`crossfilter` maintains an *index* — its pulse carries positions, and `resolvefilter` reads that index
rather than rows — so comparing it against rows compares two different contracts. Detected by shape
rather than by name, and counted rather than called a divergence.

## Builder chains: the last recording shape, and what it found

`d3-scale` and `d3-shape` are written as builders — `scaleLinear().domain([0, 1]).range([0, 100])`
configures an object and only then answers a question — so a single call is not a vector: *the state
is the input*. The recorder follows the chain now. Every chainable call (one that returns the object)
is remembered, and a call that answers something records the configuration that produced it:

```json
{"fn": "scaleLinear()", "chain": [["domain", [[1, 2]]], ["range", [[10, 20]]]],
 "method": "invert", "args": [30], "result": 1}
```

**`d3-scale` went from 55 vectors to 1,129.** Two details were needed to make it true rather than
plausible, and both were found by a wrong answer:

- **The chain belongs to the object, not the wrapper.** A test does not have to chain — `const s =
  scaleLinear(); s.domain([1, 2]); s(0.5)` throws the return value away — so holding the chain in the
  wrapper loses it, and the vector then claims `scaleLinear()(0.5)` is 1.5, which is true of the
  configured scale and nonsense on its own. It lives in a `WeakMap` keyed by the object.
- **d3 takes configuration as constructor arguments too**: `scaleLinear([1, 2])` sets the range, and
  ignoring that made a configured scale look like the default one.

`UpstreamD3ScaleVectorsTest` replays **109** vectors across seven scale families — linear, pow, sqrt,
log, symlog, band, point, quantize, threshold — evaluating, inverting and ticking each, and found a
real bug: **`clamp` works both ways in d3 and only one way here.** Inverting a position past
the end of the range, which is every pointer event past the end of an axis, returned a value outside
the domain: with domain [0, 1] and range [10, 20], `invert(30)` read **2** where upstream reads 1. A
brush or a tooltip built on that selects data the scale says is not there.

## What the scale replay counts as *not comparable*, and why

Of 1,134 recorded `d3-scale` vectors, 109 replay. The rest are named in the ledger, and the reasons are
the interesting part — each is a place where this engine is built differently rather than wrongly:

- **88 are upstream's reflection API**: `domain()` with no arguments asks a d3 scale what it holds. A
  Vega scale is built from a specification and does not answer questions about itself.
- **A colour or string range** — `range(["red", "blue"])`, `range(["0px", "2px"])` — is a *different
  scale* here: colour ramps live in `SequentialColorScale` and nothing interpolates a string.
- **A domain with more than two stops** is upstream's polylinear scale, modelled apart.
- `scaleTime`, `scaleQuantile`, `scaleOrdinal`, `scaleSequential`, `scaleDiverging` and `scaleRadial`
  have no adapter yet — the next mechanical step, not a limit of the recording.

Two more scale-shaped facts learned by getting them wrong: a **log** scale's default domain is
`[1, 10]`, not `[0, 1]` — taking the usual default made every unconfigured log scale answer NaN — and
the numeric-range restriction belongs to the *continuous* families only, since `quantize` and
`threshold` are supposed to have value ranges.

One divergence is pinned rather than fixed: a threshold scale whose **cut points are strings**. d3
compares them with JavaScript's `<`, which is lexicographic (`"12" < "2"`), where this engine parses
them to numbers. Supporting it means carrying `VegaValue` thresholds and JS comparison through the
scale, for a specification that writes a numeric cut point in quotes.

## A third test runner, and three bugs in number formatting

`d3-format` recorded **nothing** — 24 of 24 files skipped — because it uses **vitest**. Vega uses
`tape`, most of d3 uses `mocha`, and the packages d3 has migrated use vitest, so the recorder now
shims all three. It cares about none of their assertions: the vector is what the library *returned*,
not what the test believed about it, so `expect(x).toBe(y)` is accepted and dropped like the rest.

0 → **774 vectors**, and 106 of them replay against the `format` expression function. Three real bugs,
all on the path every axis label takes:

- **`format(",d")` of 1e21 printed `9,223,372,036,854,775,807`.** It went through a `Long`, which
  saturates. Expanding the double exactly is not right either: d3 hands anything from 1e21 up to
  `toLocaleString`, which writes the **shortest** representation — 1.3e27 is
  `1300000000000000000000000000` where that double's exact value is `1300000000000000044761612288`.
- **Precision was not clamped.** d3 caps it at 20, because that is as far as JavaScript's `toFixed`
  goes, so `.30f` writes twenty decimals upstream and wrote thirty here: a wider column, and a number
  claiming more than a double holds.
- **`%` defaulted to no decimals** where d3 defaults to six, so `format('%')` of 0.001 read `0%`
  instead of `0.100000%`. The `%` sign also has to be appended **after** trimming — with it attached,
  `~%` could not see the trailing zeros it was asked to trim.

**And then the gap was closed.** Counting it was not the point of counting it: `NumberFormat` is now
the whole grammar — `[[fill]align][sign][symbol][0][width][,][.precision][~][type]` with every type d3
has, `e f g r s % p b o d x X c n` and the typeless `.12~g`. Replayed vectors went from 106 to **403**.

The pieces that look decorative are the ones that carry meaning, and each was transcribed rather than
guessed:

- **`s`** picks an SI prefix from the value's own magnitude, and the prefix belongs to the *suffix* —
  so it survives trimming and stays outside the grouped digits. This is what turns 1,200,000 into
  `1.2M` on an axis.
- **`align`** decides where padding goes, and `=` puts it between the sign and the digits, which is
  what lines up a column of signed numbers.
- **`sign`** has four forms; `(` writes a negative in parentheses, as an accountant does.
- **Grouping happens before padding unless the fill is `0`**, in which case it happens after, so
  `08,d` of 1234 is `0,001,234` and not `0001,234`.

Two traps, both found by a crash or a wrong answer rather than by reading:

- **A precision of zero is falsy in JavaScript.** `formatDecimalParts(x, p)` asks for the *shortest*
  form when `p` is 0, and passing the zero through asked for minus-one digits and threw. `siPrefixed`
  reaches it, because its fallback asks for `max(0, precision + index - 1)` digits.
- **`JSON.stringify(-0)` is `"0"`.** The recorder lost the sign of negative zero, and d3 decides a
  sign with `1 / value < 0`: `format("+f")(-0)` is `−0.000000` where `+0` is `+0.000000`. The recorder
  tags it now, which is a fidelity fix for every future vector as well.

One divergence pinned: `format("s")` of the smallest subnormal. d3 falls back to JavaScript's shortest
representation for the digits — `(4.9e-324).toExponential()` is `5e-324` — where this engine's
shortest-form routine writes `4.9e-324`. A shortest-round-trip printer would close it; nothing about
formatting would.

## d3-time-format was never recorded, and it hid nine missing directives

`d3-time-format` was simply **absent from the package list** — the one that formats every label on
every time axis. Adding it gave 422 vectors, 312 of which replay, and they found that this engine's
directive table was missing nine entries and all three padding modifiers:

- **`%-S`, `%_S`, `%0S`** — no padding, space padding, zero padding. A specification writes `%-I` to
  get "9am" rather than "09am", and this emitted the directive back **unchanged**, so the label read
  `%-I`.
- **`%c`, `%x`, `%X`** — the locale's date, time and both, which d3's en-US locale spells `%-m/%-d/%Y`
  and `%-I:%M:%S %p`.
- **`%G`, `%g`, `%V`** — the ISO week trio, where a week belongs to the year holding its Thursday.
- **`%u`** (Monday-based weekday), **`%Q`** and **`%s`** (the instant itself), **`%Z`** (the offset).

Two arithmetic details came from reading d3's `pad` rather than assuming: the **sign goes outside the
width** — the year -2 is `-0002`, four digits *plus* a minus — and `%Y` is `year % 10000`, so the year
10002 formats as `0002` rather than as something six digits wide.

And a real crash: **`%Q` could not parse any real timestamp.** The parser read the digits into an
`Int`, and an epoch in milliseconds is thirteen of them, so `toInt()` threw — for every date since
1970. It reads a `Double` now, which also covers `%s` past 2038.

`d3-dsv` (79 vectors) and `d3-geo` (236) are recorded too, and want adapters: `autoType` and the
delimiter parsers land on the loader, `geoCentroid`, `geoBounds`, `geoArea` and `geoContains` on the
projection code.

## `String(x)` was wrong for one double in sixty, and nothing was checking it

Chasing the last d3-format mismatch — `format("s")` on the smallest subnormal — led somewhere much
larger than the mismatch. The digits for that case come from JavaScript's `toExponential()` with no
argument, which is the *shortest decimal no other double is nearer to*, and this engine was getting
that from Kotlin's `Double.toString`. It is not the same function. A sweep of 7,957 doubles against
Node found **91 disagreements**, in three kinds:

- **Notation.** Kotlin goes exponential at 10^7, JavaScript at 10^21. So `1777860673.6878662`
  printed as `1.7778606736878662e+9` — on a tooltip, on an axis label, anywhere a number over ten
  million was not a whole one. 57 of the 91, and the everyday one.
- **`toLong()` saturates.** The integer fast path was guarded by `< 1e21`, but `toLong` stops at
  9.2×10^18, so every integral double above that printed as **`-9223372036854775808`**.
- **Shortest is not exact.** Past 2^53 an integral double is not the integer it looks like:
  `319615008869810176` prints as `319615008869810200`, because that is the shorter decimal that
  still names those bits.

The fix is one algorithm rather than three patches. `Decimals.shortest` finds the fewest digits that
read back, and it is cheap because the expansion is already exact: round it to *k* digits, ask
whether the text reads back as the same double, and binary-search *k* — round-tripping is monotone,
so five tries settle it and seventeen always work. `JsSemantics.numberToString` then just places the
point per ECMA-262, and `NumberFormat` asks the same question instead of taking the printed form
back apart.

One rule was only visible because the corpus was large: `String(x)` breaks a tie towards the **even**
digit where `toFixed` rounds away from zero, so `170255292857.578125` prints as `…57812`. One double
in 7,957 turned on it.

`TransformReferenceTest`'s mixture expectation had `1.8736413883569446e-4` written into it — a form
JavaScript never prints. It was captured from this engine's own output rather than from upstream, so
it had been asserting the bug. Corrected against upstream, not deleted.

The sweep is now `oracle-js/src/record-number-strings.mjs`, run by
`scripts/record-upstream-vectors.sh` and replayed by `UpstreamNumberStringVectorsTest`, comparing by
**bits** so a parse disagreement cannot hide as a print agreement.

## An `s` axis was labelled in mixed units

`vega-format`'s `formatSpan` is what turns `"format": "s"` on an axis into labels, and it does not
call `format` — it calls **`formatPrefix`**, which fixes one SI prefix for the whole span from its
largest magnitude. This engine resolved every label on its own instead, so an axis over two million
read `500k | 1M | 1.5M | 2M`: three different units down one axis, which reads like a data error
rather than a formatting one. Upstream reads `0.5M | 1.0M | 1.5M | 2.0M`.

The four d3 functions behind that were never ported, and the vectors were sitting unreplayed:
`precisionFixed` (10), `precisionRound` (8), `precisionPrefix` (129) and `formatPrefix` (17). They
are in `NumberFormat` now and the corpus went from 403 replayed to **548**. Three things came out of
making them pass:

- **`precisionRound` subtracts the step from the bound first.** `precisionRound(0.01, 1)` is 2, not
  3 — the digits needed for 0.99, because the largest value an axis labels is a step below its
  bound. The copy in `Ticks` had the same omission, and it put an extra decimal on every `g`, `p`,
  `r` and `e` axis.
- **The prefix is a suffix, not an append.** This d3 passes it *into* the formatter, so it sits
  inside the width and inside the accountant's parentheses: `formatPrefix(" $12,.1s", 1e6)(-4.2e7)`
  is twelve characters wide *including* the `M`, and `formatPrefix("($~s", 1000)(-1000)` is `($1k)`,
  not `($1)k`.
- **`floor(log10(x))` is not the decimal exponent.** It is wrong exactly at the decade boundaries,
  which is where every tick step lands. `Decimals.shortest` answers it exactly, so the three
  precision helpers use that.

`spanSpecifier` could not express any of this — one SI prefix for a span is a property of the span,
not of a specifier string — so the six call sites now take a `spanFormatter` instead. `test-fixtures/
specs/si-prefix-axis.vg.json` pins it differentially: 179 fixtures.

## The CSV reader lost blank lines and joined classic-Mac files into one row

`vega-loader` reads every CSV and TSV through `dsvFormat(delimiter).parse`, so `DelimitedText` is
the code between a `"url"` and the first datum. Replaying d3-dsv's own corpus against it found two
real faults, both of the quiet kind:

- **A lone `\r` was treated as nothing.** It is a row terminator — d3 ends a row on `\r`, `\n` or
  `\r\n` — so a file with classic-Mac line endings came back as **one row**, every field belonging
  to no header.
- **A blank line was dropped.** Upstream reads it as a record of empty fields; this skipped any row
  that was a single empty cell, which loses a datum from the middle of a file. The comment said it
  was tidying a trailing newline, but the row splitter already handles that — a trailing terminator
  closes its row and starts no new one.

`parseRows` and the formatting side (`formatValue`, `formatRow`, `formatRows`, `format`) were
missing and are now there, which is what let the corpus reach them: 40 of 79 vectors replay.

The other 39 are counted with reasons rather than skipped. **32 are `autoType`, which `vega-loader`
does not use** — Vega infers types with its own `inferTypes` driven by `format.parse`, so d3's rules
are not this engine's to match. Six pass a row-conversion function, whose body a vector cannot
record. One is the `dsvFormat` constructor.

`DataResolver` also had `format.header` wrong, and the comment explained the wrong thing: upstream
quotes those names with **`JSON.stringify`** (`vega-util`'s `stringValue`), not with the delimited
format's own quoting. The two differ for every name that is not plain — `a\b` comes back as `a\\b`,
a tab comes back as the two characters `\t`, and a name containing a quote breaks the row so
thoroughly that upstream yields **no columns at all**. Verified end to end and matched, including
that the `\u2028` replacement only rewrites the first occurrence, because JavaScript's `replace`
with a string pattern does.

## `geoArea(null, f)` was measured on a page that was never drawn

Upstream's `geoMethod` branches on the projection: with one it measures through the path generator,
and with **none** it calls d3's *spherical* function. This engine did not make that distinction — it
ran the planar path sinks over raw longitude and latitude — so a measurement on the globe came back
in the wrong units entirely. A one-degree box is `1` square degree that way and `0.000305`
steradians the right way, a factor of about three thousand, and the centroid of anything larger than
a city was in the wrong place.

`SphericalMeasure` ports d3's three stream sinks: **area** as the spherical excess from the south
pole by Cagnoli's theorem, **centroid** as three accumulators reporting the highest dimension
present, and **bounds** — much the hardest, because longitude wraps. Two islands at ±179° are two
degrees apart across the antimeridian, not 358° the other way, so d3 collects a range per line,
merges the overlaps, and takes the inverse of the **largest gap**: the widest stretch of longitude
the geometry does not occupy is the part to leave out. 113 of 236 vectors replay.

One thing had to be copied rather than reasoned about. d3 accumulates its winding sum with
`delta + (delta > 0 ? 360 : -360)`, which is *not* the correction that normalises a wrap — it pushes
a step across the antimeridian further the way it went, so the sum counts turns instead of measuring
displacement. Writing the sensible version instead put the poles on the wrong side, which is how the
two polar-polygon vectors failed.

The rest of the corpus is counted with reasons: `geoContains` (42), `geoInterpolate` (10) and
`geoDistance` (3) are recorded because they are in the package, but nothing in Vega calls them.
`geoStream`, `geoCircle` and `geoRotation` are the remaining reachable ones.

**And a false cycle that would have blocked all of this.** `geoArea(null, feature)` failed to
compile at all: the visitor that collects scale references treated only a *string* literal as a
name, so a `null` first argument read as "some scale, we cannot tell which" and made **every** scale
a dependency of the dataset — a cycle reported for a chart upstream compiles. Upstream's visitor
branches on the node being a `Literal`, not on its type. Any literal now names whatever it
stringifies to, which matches no operator and contributes nothing.

`test-fixtures/specs/spherical-measures.vg.json` pins it, both windings of the same ring included:
a spherical polygon has no outside, so the box wound one way is `0.000305 sr` and wound the other is
`12.566066 sr` — 4π, everything else on Earth. 180 fixtures.

## The rest of d3-geo, and one adapter reading the wrong column

Rotation and the stream walk are replayed too, taking d3-geo to **144 of 236**. Both were clean
first time, which is worth recording as much as a fix would be: the rotation composition and the
`geoStream` walk were already right.

`geoStream`'s corpus is shapes rather than answers — an unknown geometry type, a **null geometry**,
empty coordinate arrays, points carrying an elevation — and upstream walks every one of them and
reports nothing. The sink it was handed is recorded as an empty object, so what is replayable is the
part that matters: that none of them is an error. All 22 walk.

The 32 `geoGraticule()` and `geoCircle()` vectors looked unreplayable here, and that claim was
**wrong** — see the section below. The recorder had captured their configuration all along.

**And an adapter bug that looked exactly like an engine bug.** One rotation vector disagreed, and
not subtly — upstream mapped the point to the origin where this engine turned it a hundred degrees.
The engine was right: the vector was `rotation.invert(p)`, which the recorder files under the same
`fn` as `rotation(p)` and distinguishes with a `method` field the adapter was ignoring. Comparing an
inverse against a forward looks precisely like a rotation applied backwards.

That is a mistake with reach, so the other adapters were audited for it. Only this one had it:
`UpstreamD3ScaleVectorsTest` reads the field, and the two remaining packages with `method` vectors
— `d3-array`'s `bin()` and `d3-time`'s `timeInterval()`, both built from anonymous functions a
vector cannot record — are already counted as unmapped rather than replayed.

## The chain was in the file the whole time

The previous section claimed the recorder could not capture how a builder is configured, so the
graticule and circle vectors were unreplayable. That was wrong, and wrong in the same way as the
rotation bug two paragraphs above it: a recorded vector carries a **`chain`** field —
`[["extent", [[[-90,-45],[90,45]]]], ["step", [[45,45]]], ["precision", [3]]]` — and the claim came
from printing `constructedWith` and not looking further. No recorder work was needed. The work was
an adapter that reads the field.

With that, all 16 graticule vectors replay and d3-geo reaches **160 of 236**, clean. Three things
had to be added to `Graticule` for the corpus to reach them: `outline()`, `lineStrings()`, and the
getters d3 answers when a setter is called with no argument. The outline is the one worth a note —
down the western meridian, east along the northern parallel, back up the eastern one and west along
the southern, each leg dropping its first point because the previous leg already ended there.

The remaining 76 are now fully accounted for rather than merely counted: 55 are functions nothing in
Vega calls (`geoContains` 42, `geoInterpolate` 10, `geoDistance` 3), 16 are `geoCircle`, which has no
generator here because Vega does not call one either, and 5 are constructors and one non-geometry
argument.

**The lesson is diagnostic, and it is now a test.** `UpstreamVectorShapeTest` asserts the recorder
emits only fields someone has considered, so a *new* one fails the build rather than being silently
dropped by fifteen adapters. What it cannot catch is an adapter ignoring a field that already
exists — for that the rule is the one the rotation vector taught, and it is written where the next
person will read it: **a structural disagreement is an adapter bug until proven otherwise.** A
hundred degrees of rotation is not a rounding error, and no engine gets a formula that wrong while
getting the other eight vectors exactly right.

## 135 crashes were sitting in the "unmapped" column

`UpstreamD3ScaleVectorsTest` wrapped each replay in `runCatching` and filed a thrown exception as
**unmapped** — the same column as "no equivalent scale here". So `scaleLinear.ticks (threw:
IllegalArgumentException): 52` read like a feature nobody had ported, when it meant this engine
crashed on `scaleLinear().ticks(10)`. A throw is a failure, not a gap, and counting it otherwise is
the harness excusing the engine, which is the one thing this comparison must never do.

With that changed, coverage went from **130 to 338** replayed and three real faults came out:

- **A range longer than its domain threw.** d3 uses `min(domain.length, range.length)` stops, so
  `domain([-10, 0]).range([0, 1, 2])` maps -5 to 0.5 and ignores the spare stop. This refused it
  outright, which takes a whole chart down for a specification upstream draws — and a range with a
  stop left over is an ordinary thing to have while editing one.
- **No transformed scale was piecewise.** Log, power and symlog are upstream's `continuous()`
  wearing a transform, so they take a domain of more than two stops exactly as a linear one does.
  `TransformedScale` read only the first and last, so a three-stop power scale over `[4, 2, 1] ->
  [1, 2, 4]` answered 3.5 for 1.5 where upstream answers 3 — interpolating straight across both
  segments and ignoring the middle stop entirely.
- **And it interpolated in the wrong arithmetic**, `r0 + u * (r1 - r0)` rather than d3's
  `r0 * (1 - u) + r1 * u`. `LinearScale` already carries a comment about why that matters — the two
  differ in the last bits, and an axis rounds ticks to whole pixels — but the transformed families
  had never been given the same treatment.

Two of the remaining differences were the *adapter* rather than the engine, and both were the kind
that looks like a bug. It defaulted an unconfigured range to **empty** instead of d3's `[0, 1]` and
built the scale before the guard meant to skip colour ranges; and it applied a **linear** `nice` to
a log domain, which took `[1.5, 50]` to `[0, 50]`, and zero has no logarithm — so the scale answered
NaN for everything.

One divergence stands, pinned as it was: a threshold scale whose cut points are quoted strings, which
d3 bisects lexicographically. That was already a deliberate entry in `known-divergences.json`, and
reclassifying it as "not comparable" would have quietly downgraded a difference someone chose to
record.

`test-fixtures/specs/polylinear-scales.vg.json` pins the rest: at the middle domain stop every
family lands exactly on the middle range stop, which is the property the old code could not have.
181 fixtures.

## Two ways to step a day, and this engine knew neither

d3-time was at 366 of 1051 replayed because `TimeStepper` had `floor`, `offset` and `range` and
nothing else. Adding **`ceil`**, **`round`** and **`count`** — and noticing that calling an interval
*is* flooring it, and that `unixDay` is `utcDay` for every method here — took it to **688**, all
clean first time. The one piece of arithmetic worth keeping in view is `count`'s daylight-saving
correction: a local day is not always 86,400,000 milliseconds, so the day a clock springs forward
would otherwise report 30 days in a 31-day March.

`timeTicks` and `utcTicks` needed a second adapter, because `TimeTicks` lives in the runtime while
`TimeStepper` lives in the model. 47 vectors, and they found three real faults — all of them in the
part that decides what a time axis is labelled in.

- **A stepped day ignored its step when flooring.** `TimeInterval.DAY` floored to the start of
  whatever day it was given, so `range` anchored a two-day grid to wherever the domain happened to
  begin. d3 selects the 1st, 3rd, 5th … of each month, so every label on such an axis was a day out.
- **And there are two day intervals, not one.** d3 builds its local tick table on `timeDay`, whose
  `every(n)` tests the day of the month, and its **UTC** table on `unixDay`, which tests days since
  1970. The same two-day step therefore lands on odd days of the month locally and on even *epoch*
  days in UTC. Getting the local case right left the UTC case wrong in a different way.
- **A zero-width domain drew no ticks.** The tick increment of `[t, t]` is `NaN`, and
  `coerceAtLeast(1.0)` does not rescue a `NaN` — so the stepper was built with a step of zero and
  enumerated nothing. Upstream puts one tick on a single-valued domain, which is what an axis over
  one datum should show.

Offsetting a stepped day now walks day by day rather than adding `step` days, which is d3's filtered
interval and matters at the end of a month: after the 31st comes the 1st, two selected days in a
row. That is upstream's own quirk, reproduced rather than smoothed.

What is left in d3-time is honest: 43 `every` calls that return a function a vector cannot record,
about 270 weekday-anchored intervals (`timeMonday` and its six siblings, which Vega never asks for —
its `week` is Sunday), and 19 `range` calls with a step, which is d3's *other* stepping rule and a
different function from the one Vega uses.

## Where the remaining packages stand

Replayed: `d3-time` (366), `d3-array` (252), `d3-color` (34), `vega-time` (281), `vega-transforms`
(151). The rest, with what each would need:

- **`vega-statistics` (112)** — `bin`, `quartiles`, `quantiles`, `dotbin` are plain functions and map
  directly; the `regression*` family passes an **accessor** and returns a `predict` function, so only
  its coefficients are comparable.
- **`vega-scenegraph` (190)** — `pathParse`, `pathRender` and `boundStroke` land on `PathData` and the
  bounds code. The most valuable of the remainder.
- **`d3-interpolate` (253)** — the interpolators behind every continuous colour scale.
- **`vega-expression` (149)** — `parseExpression` returns an **AST**, so replaying it means comparing
  tree shapes rather than values; the existing `ExpressionReferenceTest` compares *evaluated* results,
  which is the more useful half.
- **`vega-scale` (34)** — `validTicks(scale, ticks, count)` takes a **scale function** as its first
  argument, which a vector cannot hold. Only the identity-scale calls are replayable as written.
- **`d3-shape` (31), `d3-scale` (55), `d3-hierarchy` (1)** — builder-shaped, so a single call is not a
  vector; the state is the input. Same fix as the transform seam: record the chain per object with an
  instance stamp and replay it in order.

## Replaying d3-array rewrote the tick algorithm, and found three bugs

`UpstreamD3ArrayVectorsTest` replays 252 of d3-array's `ticks`, `tickIncrement`, `tickStep` and `nice`
vectors against `Ticks`. It started at 62 disagreements and ends at **zero**, and the reason is worth
knowing: this engine had transcribed the *older* d3 algorithm.

**d3-array 3.2 rewrote it.** `tickSpec` now returns the first and last tick *indices* along with the
increment, and — the part that matters — **retries with twice the count** when the interval comes back
empty and the count is between a half and two:

```js
if (i2 < i1 && 0.5 <= count && count < 2) return tickSpec(start, stop, count * 2);
```

So `ticks(1, 364, 1)` is `[200]` upstream and was *nothing* here. `"tickCount": 1` is an ordinary thing
to write, and it produced an axis with no ticks at all. The count also has to be a **`Double`**: the
retry passes `count * 2` and the condition is fractional, so an `Int` cannot express the algorithm.

Two more, both from the same corpus:

- **A NaN bound came back as -Infinity.** `tickIncrement` had an early guard d3 does not have. `nice`
  then multiplied that infinity out and returned a domain of `[NaN, NaN]` — a chart that does not
  draw — where upstream leaves the domain alone. `nice` now also stops on a zero or non-finite step,
  as d3 does.
- **`tickStep` is its own function**, not `stepFrom(tickIncrement(...))`. That composition loses the
  sign of a reversed span and answers NaN where d3 answers 0. `Ticks.step` is the transcription.

And one found by the harness rather than in it: `NumberValues.resolveInt` used Kotlin's `toInt`, where
`Double.POSITIVE_INFINITY.toInt()` is `Int.MAX_VALUE` — so a `"tickCount": {"signal": "1/0"}` asked an
axis for two billion ticks and exhausted the heap. It is zero now, which is upstream's answer.

Two JVM tests asserted the old behaviour in so many words and were corrected against upstream, the same
way the arc test was: `TicksTest` on the NaN bound, and `TicksCommonTest` on the same value.

## Replaying d3-time found a daylight-saving bug in `TimeStepper`

`UpstreamD3TimeVectorsTest` replays 366 of d3-time's 1,047 recorded calls against `TimeStepper`, in
**America/Los_Angeles** — d3's own suite zone, read from the vector file rather than assumed, which
also proves the stepper does not depend on the machine's.

Two real bugs, both now fixed, and zero divergences left in what is replayed:

- **Flooring an hour across a fall-back was an hour early.** The sub-day intervals rebuilt a local
  time — `atTime(date, hour, 0, 0)` — and 01:30 happens *twice* in Los Angeles on 6 November 2011, so
  reconstructing resolved to the first occurrence and moved an instant in the second hour backwards.
  d3 subtracts instead (`date - ms - seconds*1e3 - minutes*durationMinute`), which keeps the instant's
  own offset, so each of the two 01:00s floors to itself. `second`, `minute` and `hour` now subtract
  at step 1; a larger step still snaps the field, which is `every(step)` behaviour and what Vega's own
  vectors expect.
- **A non-positive step enumerated forever-ish.** d3 gives up — `if (!(step > 0)) return []` — where
  this returned a boundary. A step of 0, of -1 and of `null` all expect nothing.

**One stated difference, not a bug.** d3's `interval.range(start, stop, step)` steps *from the range
start*; a `TimeStepper` with a step snaps to a global grid, which is d3's other spelling,
`every(step).range(...)`, and is the one Vega uses and this engine implements. Those vectors are
counted as a different function rather than compared.

What is not replayed is named in the ledger: `count` (266), `ceil` (144), `round` (43) and `every` (43)
are d3 methods this engine does not model, and weekday-anchored weeks — `timeMonday` through
`timeSaturday` — have no equivalent because Vega's grammar exposes one `week`, starting Sunday.

## The five transform bugs upstream's tests found are fixed

All five are closed and the operator replay is at **zero mismatches** over 167 vectors. Each was
invisible to the 178 differential fixtures, because no fixture happens to use those parameters.

- **`window` ranked every row 1 without a `sort`.** The peer-group scan tested `comparator != null`
  *first*, so an unsorted window never advanced: `rank` and `dense_rank` came back as 1 for every row
  of the table where upstream counts 1, 2, 3. With no comparator every row is its own peer group,
  which the `cume_dist` scan beside it already assumed.
- **`timeunit` never inferred anything.** `inferUnits` has its own algorithm upstream —
  `detectTimeUnits`, a table of grains tested for *alignment* — and this engine fell through to the
  extent-binning path, which chooses by span. A year of month starts came back bucketed by **day**.
  The table is transcribed in `TimeUnits.detect`, including the wrinkle that makes it work: the weekly
  grain is **skippable**, so a run of dates that is not weekly does not stop the scan reaching the
  monthly grain below it. `inferUnits` also *overrides* `units`, `step`, `maxbins` and `extent`, with
  the warning upstream emits.
- **`impute` invented rows without saying so, and missed one.** Upstream marks a synthesised tuple
  `_impute: true`, which is how a downstream mark tells it from a real row. And the key domain is
  `keyvals` **and then** the keys the data has: taking `keyvals` *instead* lost a row wherever a group
  was missing an observed key as well.
- **`aggregate` ignored `cross`.** The full cross-product of the group-by values is now emitted, empty
  cells and all, in the product's own order after the observed ones.
- **`bin` and `timeunit` ignored `interval: false`**, which means "the start only". Both wrote an end
  nobody asked for, so a `groupby` on the pair grouped by something upstream does not have.

Three existing tests asserted the old impute output and were corrected against upstream's source
rather than deleted — the same pattern as the arc and tick tests before them.

## The inverted-radius arc: it was a guard, not the geometry

Reported as "an arc whose `outerRadius` is smaller than its `innerRadius` draws nothing here and
something upstream", and true — but not where it looked. `ArcPath` has always carried d3's rule
(`arc.js:101`, *ensure that the outer radius is always larger than the inner radius*, which **swaps**
rather than rejects). `MarkEncoder.arc` never let it see the values: it opened with

```kotlin
if (outerRadius <= 0.0 || startAngle == endAngle) return null
```

Three of upstream's five items died there, and `arc-radii-inverted` pins all five:

| written | upstream draws |
| --- | --- |
| inner 20, outer 55 | the ring |
| inner 55, outer 20 | **the same ring** — the radii are swapped |
| inner 40, no outer | a solid **wedge** of radius 40: the defaulted zero outer radius swaps to the inside |
| inner 0, outer −40 | a point, `M0,0Z` — an item, with nothing drawn |
| angle of zero | a line from outer to inner, `M0,-45L0,-15Z` |

The third is the one a specification writes by accident, and it is the difference between a donut and
an empty canvas. The last two matter for a different reason: **upstream still emits the item**. Dropping
it is not a smaller error than drawing it wrongly — every later item shifts up an index and the mark's
container disagrees with upstream's, which is exactly what `MarkContainerTest` exists to catch.

The lesson is the one this repository keeps relearning: when a shape is wrong, check whether the
geometry is even being called. The port was faithful; a validity check written above it was not.

## A portability seam was hiding a correctness bug
The core had one JVM-only file, `PlatformDecimals`, and it explained itself well enough that nobody
questioned it for six milestones: rounding a double at N places must round its **exact** binary value,
and common Kotlin has no arbitrary-precision arithmetic. The first half is right and the second is a
non sequitur. A finite double *is* `m × 2^e`, so its decimal expansion is finite — `m × 2^-k` is
`(m × 5^k) × 10^-k`, at most ~767 digits — and producing it needs multiply-by-a-word, a shift, and a
divide-by-a-billion to read the digits off. `Decimals` is eighty lines of that and no library.
**What the seam was hiding.** `exponential` had been `String.format("%.Ne")`, and Java's `%e` rounds
the double's *shortest printable form* rather than its exact value. So `format('.2e', 2.675)` gave
`2.68e+0` where upstream gives `2.67e+0` — the exact value is `2.674999…`, which is the very case the
file's own documentation used as its argument. Wrong since the formatter existed, and invisible
because nothing compared that path against upstream. The replacement disagreed with the reference on
its first run, which is how it surfaced.
Two lessons worth keeping:
- **A platform seam is where a comparison stops.** Everything else in the engine is checked against
  upstream; this one file was checked against the JVM, and the JVM is not the thing being ported.
- **Keep the oracle in the test.** `BigDecimal` is still here — as `DecimalsTest.Reference`, run over
  150,000 random bit patterns. Removing a dependency from the *engine* is not the same as giving up
  the check, and the check is what caught this.
Writing the vectors for it also found a second gap in the neighbourhood: the specifier grammar
accepted `d`, `f`, `e` and `%` but not `g` or `~`, so `format(x, ".3g")` fell through to plain number
text — `g` had been implemented all along and was only reachable through the *typeless* specifier,
which d3 aliases to `.12~g`. Fixed. What is still outside the grammar (`s`, `r`, `b`/`o`/`x`/`X`/`c`,
`n`, and the fill/align/sign/width slots) is now stated plainly in SUPPORTED_FEATURES, including the
uncomfortable part: an unreadable specifier is **not** reported, because an expression evaluates where
no diagnostic collector reaches. Upstream throws "invalid format" there.

## `bind` is described, not drawn — and building the demo found two bugs under it

The 149 "bindings have no equivalent here" diagnostics were the wrong conclusion. Upstream's binding is
a *description* of a control bolted to a DOM implementation of it; only the second half belongs to a
browser. So the description is parsed like any other grammar (`SignalBind`, five shapes matching
`bind.js`), the runtime exposes `controller.inputs` — control plus current value, republished on every
compile — and `controller.setSignal` is the way back, taking the same path a fired handler does.

**Keep the widgets out of the library.** `SignalControls.kt` lives in the demo, draws Material 3, and is
about 170 lines; a host with other widgets writes its own against the same two members. Putting Material
into `vega-compose` would make that choice for every host, and there is nothing in the file worth
sharing.

**A generic input's extra properties are grammar, and are carried rather than reported.** The two
diagnostics `job-voyager` produced were `bind.placeholder` and `bind.autocomplete`, and the answer was
not to special-case either: upstream's generic generator copies *every* remaining property onto the
input element, and its schema agrees — of the five `bind` variants, the one for an input outside the
four structured kinds is the only one with `additionalProperties: true`. So `Field.attributes` carries
all of them and a host uses what it has a widget for (the demo shows `placeholder`, ignores
`autocomplete` — there is no form here for a browser to autofill from). The four structured kinds went
the *other* way, from a pooled key list to a per-shape one, because upstream closes each with
`additionalProperties: false`: `{"input": "checkbox", "min": 0}` is now reported as a mistake rather
than as an unimplemented feature, which is the accurate statement. Checked against the corpus before
committing — two false gaps gone, no example newly reported.

Two bugs came out of driving it on a device, and neither could have been found any other way:

- **`setSpecAsync` recorded neither the specification's text nor a fresh set of overrides.** A chart
  loaded through it had nothing to recompile *from*, so no signal change could redraw it — not a
  control, not a handler, not a tap on a mark. Every JVM test used `setSpec`; the demo uses the other
  one. Every interactive specification in the demo had been inert.
- **A domain `sort` whose `order` is a signal was read with `asString()`**, so the object stringified to
  something that did not begin with "desc" and every such domain came out ascending. `domain-sort-order`
  pins it, and also pins the upstream quirk found while writing it: two domains differing *only* in the
  signal share one sort operator, and the second silently takes the first's order.

If you touch the controls, drive them on the emulator rather than trusting the tests: both of the above
passed every JVM test in the repository.

## Read the diagnostics the 93 examples produce, not just the clean count

The triage reports a *count* of warnings per example and nothing about what they say, which hides both
real gaps and pure noise. Compile all 93 through `SpecCompiler` and group the messages — twenty lines
of throwaway test — and the distribution is the work list. It has now been run once and found three
things worth having, in the order the counts put them:

- **406 × "Cannot read field 'Year' as 'date:%Y-%m-%d'".** Upstream's `parse` takes `date:` and `utc:`
  followed by a d3 pattern, quoted or not, split on the *first* colon. Without it a whole column stays
  text and an axis is drawn from strings. The parser for it already existed — `TimeParse`, written for
  the expression `timeParse` — and was simply not wired to the loader.
- **7 × "'offset' must be a number or a signal reference".** Upstream's `numberValue` is a **value
  reference**, so a guide's number may go through a scale: parallel coordinates writes
  `{"scale": "ord", "value": "Cylinders", "mult": -1}` on each of seven axes, which is how they end up
  side by side rather than stacked. Resolved by the encoder's own channel code against the empty datum
  a guide has.
- **318 × "Could not parse colour 'null'".** Pure noise, and the third time this class of thing has
  turned up: a colour that resolves to nothing is *no paint*, not a bad colour. Stringifying first
  turned every unset stroke into the word "null" and then into a complaint, on charts that were
  drawing correctly.

A second pass over the same distribution found two more, both in the axis:

- **`encode.ticks.update.y`, and the rest of a part's geometry.** Upstream merges a guide's `encode`
  block into the part's own encoders and applies it **last**, so `y`, `x2` and their friends override
  the geometry the guide computed. `warming-stripes` reaches through a tick to draw a marker at a
  chosen temperature; nothing in the axis vocabulary can say that.
- **A stroke channel that reads `datum`.** Folding a guide encode channel into the property it
  duplicates is right for a constant and for a signal over the chart's state, and cannot work for
  `{"signal": "datum.value === marked ? 2 : 1"}` — at parse time there is no tick to read, so every
  tick got the false branch. The stroke channels are resolved again per tick now, which costs nothing
  where they are constant and is the only way that form can work.

The rest are honest: input widgets that have no equivalent here, the extended projection family
upstream itself refuses, `wordcloud`, and two properties (`marknames`, a mark-level `index`) that are
in nobody's schema and are ignored by both engines.

## What is left: two examples, and neither can be verified

**177 differential fixtures pass. 91 of the 93 examples compile clean.** Everything that can be
checked against upstream has been.

### `projections` — upstream refuses it too

It names `airy`, `armadillo`, `baker`, `berghaus`, `bottomley`, `collignon`, `eckert1`, `guyou`,
`hammer`, `littrow`, `wagner6`, `wiechel`, `winkel3` and the interrupted and polyhedral families —
and `vega-projection` imports exactly **one** projection from `d3-geo-projection`, `geoMollweide`,
which this engine has. Running the example through the pinned oracle gives `Error: Unrecognized
projection type: airy`. The Vega website registers those types itself before rendering that page; a
bare Vega cannot draw it, and our diagnostic says what upstream's says. **Do not read its error count
as outstanding work.** If the extended family is ever wanted, `Projections.byName` is where a type is
added and `GeoProjectionTypesTest` is how it is proved.

### `word-cloud` — the one thing that was not attempted, and why

`labeled-scatter-plot` and `word-cloud` used to be a pair: both transforms reach for a `<canvas>`
Node has not got. They are **not** the same case, and the difference is what decided one and not the
other.

`label` rasterises the marks it must avoid — circles, a line — and asks only whether a pixel got *any*
coverage. That question has a geometric answer: does the shape overlap the pixel's square? So the
transform is implemented, with the occupancy computed analytically, the two halves that *can* be
pinned pinned (`BitmapTest`), and a warning on every use naming the one step that is not upstream's.
The two answers differ only on pixels a shape barely grazes.

`wordcloud` rasterises **glyphs**. `cloudSprite` sets a font, calls `fillText`, and reads the pixel
mask back; the packing then slides each word along an Archimedean spiral until its *mask* stops
colliding with the masks already placed. Words interlock into each other's gaps — a descender under a
crossbar — and that interlocking is the whole visual character of a word cloud. There is no geometric
answer to "which pixels does the word 'GRAMMARS' cover in 36px sans-serif": it depends on the font's
outlines and the rasteriser's hinting.

Substituting bounding boxes would produce a chart that looks like a word cloud and is not Vega's — a
visibly looser packing, and unlike `label` there is no pinned half to stand behind it. Everything that
determines the picture would be the invented part: the spiral is four lines and the generator is
already shared. **So it is not implemented, and the transform is reported by name.**

What would change that, in order of cost:

1. Install the native `canvas` package in `oracle-js`. That gives upstream a real oracle for both
   transforms — and costs the guarantee that references can be regenerated offline from a checked-out
   tree, which PROJECT_BRIEF.md §21 asks for. An owner's call, not a mechanical one.
2. With an oracle in hand, `label`'s analytic occupancy could be *measured* against a real
   rasteriser's rather than reasoned about, and its warning either removed or made precise.
3. `wordcloud` would still need a glyph rasteriser on the Kotlin side to match, which is a font engine
   and not a port of Vega.

  > Owner's decision on `wordcloud` and on adding a native canvas to the oracle: _not yet made._

### Where the numeric fidelity is hard-won, for whoever changes it next

Four places carry arithmetic that cannot be simplified without breaking a chart, and each has its
reasoning in the code rather than here:

- `Orient2d` — Shewchuk's adaptive predicate. `voronoi` is a sequence of orientation decisions and a
  single wrong sign changes the whole diagram.
- `Adder` — the same idea for `polygonContains`, where the sign of a sum around 1e-12 decides whether
  a continent is filled or left as a hole.
- `ResampleStream` — the guard is `!(d2 > 4 * delta2)` because `d2` is **NaN** for the first point of
  every line, and `NaN <= x` is false too.
- `Delaunator.quicksort` and `RandomStream` — two places where the *order* of operations is part of
  the answer, not an implementation detail.

Two of the 60 projection vectors are compared **within one printed digit** rather than exactly.
`azimuthalEqualArea` and `azimuthalEquidistant` clip at 179.999 degrees, where their scale factor is
114,591 and its derivative is 3.8e14 — so a one-ulp difference in `cos` between V8 and the JVM, which
neither runtime promises to avoid, moves a coordinate by 1.3e-4 of a pixel. That is enough to cross a
rounding boundary in the third decimal and nothing more. The arithmetic is in the test's comment.

And one **deliberate difference** in the comparison, stated so it is not mistaken for a tolerance: a
reference carrying a `strokeWidth` with no `stroke` colour describes an outline that is never
painted, and this engine records no stroke at all for it. A reference carrying a stroke colour still
demands a stroke of that width.

## The chart can be handed a clock now

`Scheduler` is one method — run this later, once or repeating — passed to `VegaChartController` and
defaulting to null. With one, a `debounce` is upstream's trailing edge exactly and a timer stream ticks
at its interval with `timestamp` and `elapsed` on its event. Without one, nothing changed: the debounce
fires eagerly, the timer does not fire, and both say so. **Keep that default.** It is what makes a
chart a pure function of its specification, and so comparable against upstream at all; every fixture in
the corpus depends on it without knowing.

Three things worth not rediscovering:

- **A tick recompiles, and a recompile must not restart the timers.** Doing so cancels the running ones
  mid-flight, resets every `elapsed`, and drops the ticks in between. They are keyed by
  (signal, interval) and left alone unless the specification's own timers change.
- **Test against virtual time.** `SchedulerInteractionTest` has a fake scheduler whose clock the test
  advances by hand, and the controller's own `clock` is moved with it. That is exact, where sleeping
  would be slow and flaky at once — and it caught the restart bug on the first run.
- **The scope is the whole lifecycle question.** The demo passes `CoroutineScheduler(rememberCoroutineScope())`,
  so every pending tick is cancelled when the composition goes away and nothing has to remember to.
  `controller.stop()` is there for a host without that luxury.

What this does *not* do is make an animation verifiable. The harness compares the scene upstream
reaches after `runAsync`, and for a specification with a timer `runAsync` never returns — so a ticking
chart has no reference to compare against, whatever this engine does with it.

## The timer-as-a-loop is resolved, and it found a transform bug

The section below was written as future work: `donut-chart-labelled` passes the differential and still
looks wrong, because its timer is standing in for a loop and the fixture compares the frame *before*
the loop runs. With a scheduler it now runs to its own fixed point and the labels spread —
`TimerLoopTest` pins that, and pins that running the clock on afterwards changes nothing, which is
what makes it a loop rather than an animation. Compile-time convergence, which the note below
proposes, would be a *divergence* from the only reference obtainable: upstream's `runAsync` never
returns for this specification, so the reference is the unsettled frame and the fixture is right to
match it.

Writing that test found a real bug two layers down, and the way it hid is the lesson. The `values`
aggregate operation collects the **rows** of a group, not the column the schema makes you name;
upstream pushes the tuple and ignores the field. Ours collected the column, so
`pluck(datum.shiftArray, 'shift')` — reading a *different* column back out of those rows — returned
nothing but nulls and every label's shift was zero. No fixture could see it: the array is only ever
read by a **signal**, so every compared scene agreed. If a transform's output is consumed by an
expression rather than by a mark, the corpus is blind to it, and the only way in is a test that runs
the thing.

## The note that was: a timer used as a `for` loop

`donut-chart-labelled` passes the differential and still looks wrong in the demo: its three most
crowded labels — United States, France, Germany — are drawn on top of each other, where the gallery
shows them spread down the page.

**The fixture is not lying.** Upstream's static scene stacks them too; the reference has all three at
`y = -9.501909, x = 228`. What the gallery shows is a *later frame*.

The reason there are frames at all is worth understanding before anyone touches it: the timer is not
animating anything, it is standing in for a loop Vega's expression language cannot express.

The stream itself now parses as what it is — a `timer` source rather than a view event of type
`timer` — and the dispatcher reports that firing it needs a clock. Before that the loop below did not
merely fail to run; nothing said so.

| signal | role |
| --- | --- |
| `shiftArray` | how far each label must move to clear the one above it |
| `counter` | `counter < length(data('labelPositions')) ? counter + 1 : counter`, fired by a `{"type": "timer"}` — the loop variable |
| `p1` | on each `counter` change, `shiftArray[counter-1] + p1` clamped at 0 — the accumulator |
| `p2` | on each `p1` change, `p1 + ',' + p2` — the output array, built by string concatenation |
| `shiftArrayRunning` | `reverse(split(p2, ','))` — that string turned back into an array |
| `labelPositionsFinal` | `shiftArrayRunning[index-1] + bin` — each label's final `y` |

Each label's offset is the running total of every overlap above it: a prefix sum. The giveaway that
this is a workaround rather than a design is `p2` accumulating an array by joining with commas and
splitting again, because there is no append either — and `p1` carrying `"force": true`, which a loop
needs and an animation would not.

**So this probably does not need a scheduler.** The loop has a termination condition: `counter` stops
at 33 and everything downstream stops with it. A bounded iteration with a fixed point can be run to
convergence at *compile* time — fire the `on` handlers whose source is a signal or a timer, repeatedly,
until nothing changes — with no wall clock, no repainting, and the chart still a pure function of the
specification.

Three things to weigh first, none of them checked:

- **Not every timer loop converges.** `clock` and `watch` read `now()` and never settle. They are
  already refused, but a convergence pass needs an iteration cap so a non-converging specification
  stops at an arbitrary frame rather than hanging.
- **The differential harness cannot verify the settled state.** The oracle captures upstream after
  `runAsync`, which is the unshifted frame. Pinning the converged layout needs evidence of another
  kind — and `runAsync` on this specification never returns, because the timer keeps the dataflow
  alive.
- It would change what a compile *is*. Today a signal's `on` handlers are applied only by the
  interaction layer, on a real event; running them at compile time until they settle is a different
  contract, and STATUS's "Next three tasks" item 1 describes the neighbouring gaps in the same
  machinery.

## A signal can now drive a signal, and that was the big one

`{"events": {"signal": "brush"}}` is how one signal is derived from another, and it is not a corner:
**79** handlers across twenty of Vega's 93 published examples use it — every pan, zoom, brush and
overview-plus-detail in the gallery — against **none** for the `{"scale": ...}` form. It was parsed
and never fired.

It was invisible for a reason worth keeping: **nothing fires at initialization**. Probed both ways —
a chain two deep takes `a = 5` to `b = 10` and `c = 11` in one run, and with no change at all both
signals keep their declared values — so the scene the differential harness compares is the scene
before anything has happened, and every fixture was right. A pan that did nothing looked like a chart
with no pan in it.

The implementation is a loop in `VegaChartController.cascade`, and two details are load-bearing.
Dependency order falls out of it rather than needing a sort, because each round fires only the
handlers whose source changed in the round before; and the diagnostics from a cycle are reported
*after* `publish`, since publishing replaces them with the new compile's and the cycle is a fact about
the interaction rather than about the specification's text. A cycle is capped and reported the way
`DataflowOrder` reports one among `update` expressions — upstream refuses such a specification
outright, and drawing with one signal stuck beats not drawing.

The scale form stays reported. A recompile rebuilds every scale, so nothing here says which one
*moved*, and firing on all of them would run the handler when nothing had changed.

## Writing the three untried combinations found four bugs

STATUS's "next tasks" named three combinations the corpus had never met. Two of the three failed on
arrival, which is the method working:

- **An axis on a discretizing scale** was skipped outright — a whole axis missing from a chart that
  asked for one. Each of the four ticks at something different and upstream picks by what the scale
  *has*: bins, then a `ticks` method, then the domain.
- **A `tickCount` written as a time interval** was dropped in silence, so a night was ticked at
  whatever round number the count algorithm liked.
- **A group shadowing the outer scope's signals and scales** passed unchanged.

Two more fell out of the first, and both were the kind that only a fixture finds. A `bin-ordinal`
domain taken from a field kept its **duplicates**, so the bisection counted equal values and the
lowest bin was painted with the highest bucket's colour. And **every plural interval name matched
nothing** — `TimeInterval` is spelled `HOUR`, Vega's unit is `"hours"` — so `nice: "hours"` had been
silently doing nothing too, and `"quarter"` is not an interval at all but three months. That one is
now in `TimeInterval.forUnit`, which is the only place a unit name should ever be matched.

The first draft of the discretizing fixture is worth remembering as a mistake: it gave the scales
**colour** ranges, which is what a discretizing scale is usually for. Upstream then positions every
tick at `NaN` — a colour is not a length — and the fixture would have been asking this engine to
reproduce meaningless output, pixel for pixel. Numeric ranges make the same four rules visible and the
comparison mean something. If a fixture's expected output looks like garbage, the fixture is wrong
before the engine is.

## The event functions were missing under everything else

`x()` is the second commonest expression in an interactive specification after `datum` — forty uses
across Vega's 93 examples, in every brush and every pan — and it, `y()`, `xy()` and `item()` were not
implemented at all. Worth knowing why that stayed invisible: they only ever appear inside `on`
handlers, so no fixture can reach them, and the handler that used one failed at evaluation time into a
collector nobody read. Two of those collectors are now drained into the published diagnostics.

`x()` is **not** `event.x`. Upstream takes `offset(view)` — padding plus the autosize origin — off the
canvas point first, which is exactly what the root group carries as its translation, so the answer is
in the space the marks are placed in. A chart with no padding hides the difference completely, so test
with padding. The argument forms (`x(item)`, `group()`) walk the chain of groups above an item and are
refused by name: the event value here does not carry that chain.

With `item()` in place, `encode` handlers fell out. Upstream desugars `{"encode": "select"}` into
`encode(item(), 'select')`, and doing the same in the parser means one path serves both spellings. The
ordering rule was probed in both directions and is worth not re-deriving: the overlay beats the mark's
`update` on the pass that applies it and loses to it on every pass after. That is reproduced by
putting the block after `update` while it is fresh and before it once it is not — `ItemEncode.fresh`,
aged by the controller once the compile has happened. And the handler changes no signal value at all,
so the redraw has to be triggered by the overlay itself.

## Pick the next fixture by counting, not by taste

The three combinations STATUS named were used up, so the next candidates came from a mechanical count:
for each of the 49 implemented transforms, how many fixtures use it? Three had **none** — `impute`,
`nest`, `pivot` — and fourteen have exactly **one**. The count is a few lines of Python over
`override val type: String = "…"` in `vega-dataflow/.../transform/*.kt` against `"type": "…"` in
`test-fixtures/specs/*.vg.json`; run it again after adding a transform.

Run the same count over the **operation names** — aggregates and window operations live in `ops` arrays
rather than as a transform `type`, so a count of transform types misses them entirely. Seven of the 25
aggregates had never been asked for, and the fixture written for them found `distinct` counting the
wrong thing; ten of the thirteen window operations had never been asked for either, and those turned
out to be right. Both counts are now zero. The same trick applies to every **vocabulary** a specification draws from,
and the counts are worth re-running rather than trusting: scale types and mark types came back fully
covered, while the symbol shapes were missing seven, the curve families three, and `timeunit` five of
its eleven units — all now covered, all already correct. The **projections** were the last vocabulary
short and are now covered too — all twelve remaining families, all already correct.

Writing that one cost two wrong drafts, both worth knowing about. A projection reference cannot be a
**signal**: `{"projection": {"signal": "parent.p"}}` is rejected by upstream's parser, so a grid of
projections has to name each one literally rather than facet over them. And `geopoint` belongs in the
**data** pipeline: as a *mark* transform it runs after encoding and writes onto the items, so the
symbols came back at `NaN` upstream while this engine put them at the projection's translate. The
fixture was wrong both times, not the engine — but a fixture whose expected output is `NaN` is telling
you to rewrite the fixture.

One fixture is enough to catch a transform that does nothing and not enough to catch one whose
*options* are ignored. `hierarchy-options` was written for exactly that — a radius column on `pack`,
rounding and padding on `partition`, the cluster method with separation off on `tree`, and output names
of the specification's choosing on both — and it passed on arrival, which is the other outcome and
worth having. That is exactly what `nest-treemap` found: `sort` on a hierarchy layout was
reading its field off the row rather than off the node, so `{"field": "value"}` — the layout's own
computed total, the only sensible thing to sort a hierarchy by — found nothing and sorted nothing.

`label` is the one transform that can never have a fixture: its occupancy bitmap is built from a
canvas upstream, and there is no canvas under Node to produce a reference from.

## What is left, and the one technique that finds it

The remaining inventory is **legend, title and layout properties**, plus a short tail. Every encode
channel, every axis property and every projection property in Vega's schema is now either drawn or
explained by name.

**Find the next gap by diffing the schema against the parser's tables, not by reading diagnostics.**
`ENCODE_UNSUPPORTED` being empty did not mean nothing was missing — it only held channels somebody had
*noticed*. Eleven more were falling through to the generic message, and two of those (`scaleX`,
`scaleY`) were already drawn: they had come off the unsupported list without being added to the
consumed one, so every specification using them was told it had been ignored while it was honoured.
The two tables are meant to be a partition of the vocabulary, and only the schema shows what has fallen
between. `oracle-js/node_modules/vega/build/vega-schema.json` has a definition per block
(`encodeEntry`, `axis`, `legend`, `title`, `layout`, `projection`, `scale`, `mark`); collect each
table's string literals out of `SpecParser.kt`, expand `guideStyleKeys(...)` by hand, and subtract.
Anything left is either a gap or a stale diagnostic, and telling those two apart is a grep.

As of this handoff the subtraction leaves **nothing** for `encodeEntry`, `axis`, `title`, `scale`,
`projection` or `mark`, and this for the rest:

- **Legend:** none. All 72 of upstream's legend properties are read, including the `strokeDash` and
  `strokeWidth` **channels** (on a legend those name *scales*; the legend background's own width and
  dash are a separate thing and come from `config.legend` alone) and `gridAlign`. Do not re-report
  `titleAnchor`, `clipHeight` or the background — they are done.
- **Axis:** none. All 79 of upstream's axis properties are read. `labelFlushOffset` was the last, and
  it was a **stale** report: the explanation said it "needs labelFlush", which had been implemented
  for some time. `labelBound` is consumed and deliberately **inert**: upstream's bound test runs
  before the label bounds exist, so it culls nothing, and implementing the documented behaviour would
  be a real difference. See STATUS.md.
- **Title:** none. All 31 of upstream's title properties are read, `encode` and `style` included.
  `encode` splits three ways — `group` for the group the heading sits in, `title` for its text,
  `subtitle` for the second line — plus the deprecated form, where a block naming none of those three
  applies to the *text*. `style` **replaces** the `group-title` slot rather than adding to it.

**The subtraction is now empty for every block.** What is left is one level down: a **channel** a
guide's `encode` cannot express is named one at a time — a title's `encode.title.update.x`, for
instance — rather than the whole block being reported as unread. That is where to look next, and
`UnhandledPropertiesTest` asserts the naming still happens.

**A signal in a guide's styling now works**, which it did not until recently and said nothing about.
`labelFontSize: {"signal": "n"}` always worked, because that property is read through
`numberOrSignal`; `labelColor: {"signal": "c"}` was dropped in silence, because the styling block
took only a literal — so a chart colouring its axis from a control drew black labels and looked
finished. `GuideStroke` now carries a `signals` map beside its constants and the builders substitute
a resolved copy once, before anything reads it, which is what kept the change from spreading. A guide **`encode` channel** valued by a signal folds too, and for a reason worth keeping in
mind: the fold happens at parse time where no signal has a value, so it only works where the *target
property* can carry one — the styling block records it and everything read through `numberOrSignal`
resolves it. A channel aimed at a plain string property (`symbolType`, `orient`, `format`) is still
named, because folding an object into one would stringify it.

**The scene has no mark level, and two things a mark carries had nowhere to live.** Upstream's group
holds *marks* and each mark holds items; here a mark's items are the group's children directly. That
is the right trade for a differential comparison — the harness reads a flat list of drawn things — but
a mark's own `description` is announced on the container it draws its items inside, and there was no
container. Both now travel on the items: `NodeMetadata.markOrdinal` (which of its parent's marks this
came from, upstream's `markpath`) and `markAccessibility` (the announcement, one instance per mark held
by reference), and the renderer rebuilds the container from a run of items that agree on both. The
ordinal was needed for its own sake: without it two `rect` marks declared side by side read as one run,
and an item `zindex` in the second could be painted among the first's items.

Verified the way `zindex` had to be — by harvesting upstream's own output. `./scripts/oracle.sh` now
writes `test-fixtures/reference/mark-containers.json` beside the captions, and `MarkContainerTest`
compares 2,038 announcements across the corpus: role, role description, label and hidden, as a multiset
per fixture. Two things it taught, both already in the code: upstream announces a **symbol** legend's
entries as a group mark container and a gradient legend's as nothing, and a mark that produced no items
still gets a container upstream — an empty one, which is skipped in the harvest because assistive
technology walks past a group with no content and comparing it would fail over a difference nobody can
hear. If you touch guide internals, expect this test rather than the differential to be what notices.

**The harness compares the scene, not the drawing.** That is the right trade almost everywhere and it
has one blind spot worth remembering: anything upstream decides at *render* time is invisible to it.
`zindex` was the example — upstream keeps its items in data order and reorders inside `visit`, so a
chart drawing in the wrong order agreed on every compared number. If you suspect a gap that the
fixtures cannot see, ask whether the behaviour lives in the scene or in the renderer, and if it is the
renderer then probe upstream's **SVG** and pin the answer in a unit test.

**Every `config` block is now read, and the last one to arrive was the odd one out.** `config.events`
is not a drawing instruction: it is the embedder's policy on which listeners a view may attach, so a
host that writes `{"events": {"window": false}}` is refusing to let a chart it did not write watch the
pointer across the whole page. Parsed-and-dropped meant that refusal was ignored in silence. It is
enforced where the listeners are *made* — upstream's `permit`, called from `events()` — and not where
events arrive, because a policy that let the listener register and then filtered the events would
report nothing and behave almost the same until it did not. Two details are worth not re-deriving,
both probed: a **list is an allow-list**, and `timer` is the one key upstream's
`initializeEventConfig` leaves un-unpacked, so an array there matches nothing and permits nothing —
carried through as upstream carries it rather than corrected.

Implementing it turned up an unrelated silent gap in the same machinery. `{"type": "timer"}` is not an
event type but a **source**, with the throttle as its interval, and upstream's stream parser rewrites
it one layer above the selector grammar — which we had not. So a timer stream read as a view event of
type `timer` that nothing ever raises: the signal simply never changed, and said nothing about why.
Both spellings (`"timer{500}"` and the object form) are now folded onto a `timer` source in
`EventSelector.asTimerStream`, which is also what makes the `timer` policy key reachable, and the
dispatcher reports that firing one needs a clock it does not have. See "Possible future work: a timer
used as a `for` loop" above — that is the one specification in the corpus that wants it.

**`Functions.knownUnsupported` is empty, and it should stay that way.** It is a list of work, not a
verdict: every entry that was ever on it came off, and each excuse was softer than it read. If you add
one, add the reason too — the evaluator reads it out instead of saying "unknown function" — but treat
it as a to-do. The two rules that survived the emptying are worth knowing:

- A function whose *observable* answer with no browser and no running view matches upstream's in the
  same position is **implemented**, not excused: `screen`, `windowSize`, `intersect`, `inScope`. A
  compiled scene is permanently in that position and so is `renderer: 'none'`, which is what the
  oracle renders every fixture in.
- A function whose return value upstream cannot use from a specification at all — probed on every
  channel that accepts one — is implemented as the part of it a value model can hold, and the
  divergence is stated in the KDoc: `pathShape`, `geoShape`, `copy`.

Run the same subtraction over the *encode* vocabulary and the pattern repeats: several channels
reported as unimplemented had a property behind them all along, one map entry away in
`AXIS_ENCODE_PARTS`/`LEGEND_ENCODE_PARTS`. Both maps and both whole-block gaps (`encode.gradient` and
`encode.legend`) are now closed. Two channels are deliberately **not** folded, both for the same reason read from opposite ends: the
channel and the property are not one thing. A ramp label's `align`/`baseline` — upstream derives a
gradient label's alignment from where along the bar it sits and reads no property for it, so the
channel works there and the property does not, which is why `legendEncodeParts` has to know what kind
of legend it is looking at. And a symbol swatch's `fill` — upstream sets it from `symbolFillColor` and
then *overwrites* it from the legend's own colour scale, while an `encode` block is applied after both
and beats the scale. Folding either one would make a property work where upstream ignores it. Checked
against the `addEncoders` tables in `vega-parser/src/parsers/guides/`, which is the list of channels
that do have a property behind them; both maps now cover it exactly.
- **Layout:** none. All ten of upstream's layout properties are read, and `row-footer` and
  `column-footer` are recognised roles — they used to fall through to `CELL` and be gridded among the
  cells.
- **Mark:** none. The last two were `key` and a mark-level `description`, and both were more than
  they looked. `key` reads like a hint about redraws and is upstream's `DataJoin`: it maps each key to
  **one** item, so two rows sharing a key are one mark and the later row's values are drawn in the
  earlier row's *position*. `description` belongs to the mark's **container**, a level this scene does
  not have — see "The scene has no mark level" below.
- **Tail:** none. Facet aggregates take all 26 operations; the report only ever fired for a name
  upstream rejects too, and says so now. `timeunit` unit inference and its `step` are *done*.
  `config.range`, the named ranges, all four geo expression functions and the `lab`/`hcl` colour
  helpers are *done*. The colour interpolation spaces are *done*, and so are all 26
  aggregate operations — the `impute`, `pivot` and `window` reports were **never gaps**: each fired
  only for a name upstream itself rejects, and each read as one. They now say so.

**Before adding any of it, check the harness can see it.** That has now been the eleventh finding of
its kind and the largest: `shape` marks were compared by fill and stroke alone, so every map in the
corpus was green on colour. Whatever the next property is, ask what number in
`oracle-js/src/normalize.js` and `Differential.kt` would change if it were wrong, and if the answer is
"none", add it there first and expect existing references to move.

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
  there — the package is ESM, `require` fails, a script outside cannot resolve `vega`, and the
  default export does not exist (`import * as vega from 'vega'`). Delete probe files afterwards.
- **Both gates green before every commit.**
- **A differential fixture is the only real evidence.** A unit test asserting your own reading of
  the spec is not. A new fixture is *expected* to fail first — that failure is the information.
  Read differences from the JUnit XML at `vega-runtime/build/test-results/test/*.xml`; it is far
  more useful than the HTML report.
- **Never weaken the comparison harness to make a fixture pass.** If a tolerance is genuinely
  needed, say so with magnitude and reason. Strengthening it is welcome and shows up as a purely
  additive diff to the checked-in references — the `closed` channel added 25 lines and changed none.
- **If a difference is visible in rendered SVG while the comparison passes, suspect
  `oracle-js/src/normalize.js` before the engine.** This has now happened six times.
- **Nothing silently ignored** (PROJECT_BRIEF §3.3): every unsupported construct gets a named
  diagnostic. Equally, remove the diagnostic when you implement the thing — stale "not
  implemented" messages on working features have shipped twice. And note the third failure mode
  `zindex` showed: a property parsed into the model, never read, and never reported either.
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
- Tell workers to commit in stages. Two agents were lost mid-task in one session with everything
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
- **Everything is a target now.** The categories that used to be out of reach — geo/projections/
  topojson, the raster family, `now()`/`random()` — were reopened by the owner; see "The three
  refusals are lifted" above for where each stands. Force layout is the one thing nobody has ruled
  on either way: `force-directed-layout` compiles clean today because its layout transform is
  reported and the marks still draw, so it is *quietly* wrong rather than refused.
- **Do not size a decision off a triage node count.** `crossfilter-flights` was written off as
  unfixturable because it "draws 600,098 scene nodes"; upstream draws 171, and the 600,098 was this
  engine drawing every unfiltered row because two transforms were missing. The count measures how
  wrong we are.
