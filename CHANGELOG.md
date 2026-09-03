# Changelog

Notable changes, newest first. The release workflow reads the section for the
version it is publishing and uses it as the release notes, so a version without a
section here does not get released.

## Unreleased

### Added

- **Vega-Lite coverage is measured, not described.** Six rows said `Partial` and then a paragraph of
  prose, which is safe — anything outside the subset is refused by name — but not checkable, and an
  unmeasured claim is the one that goes stale. `UpstreamVegaLiteCoverageTest` asks the compiler
  instead, deriving the inventory from the Vega-Lite schema in the pinned install: the channels of
  `FacetedEncoding`, the `Mark` enum plus the three composite marks, and the nineteen members of the
  `Transform` union. Nothing is hand-listed, so a version bump that adds a channel adds it here.

  The answer is **77 of 77** — all 41 channels, all 17 marks, all 19 transforms — and the ceiling is
  now zero refused, so a schema bump that adds something this compiler will not take fails rather
  than being absorbed into the word "partial". `docs/upstream-coverage.md` carries it beside the
  Vega numbers.

  Breadth, not depth: this says a construct is accepted rather than refused. Whether it emits the
  Vega upstream emits is `VegaLiteFixtureTest`'s question, answered property by property on every
  fixture. The rows say so in those words.

  Two runs of the probe reported four refused channels before it was trusted, both times because
  the probe's own specification was malformed — the `*Error` channels only mean anything on an
  `errorbar`, and an `errorbar` needs a continuous position of its own. That is the third time a
  coverage probe here has measured its own input; it is why the guard asserting an invented channel
  *is* counted comes with it.

- **A documented limitation now has to be pinned to a test.** `SUPPORTED_FEATURES.md` has had its
  status column generated from a run since #154, and the prose beside it has not — which is where
  the drift went. A row can claim a gap that no longer exists and nothing fails, because a run has
  nothing to say about a claim nobody made testable.

  `scripts/capabilities.py` gains the rule: a row whose status is not one of the fully-supported
  kinds must carry a `limit`, naming either a test method that **asserts** the limitation — one that
  goes red the day the gap closes — or a `scope` reason where there is no code path to test, listed
  on every run so the escape hatch stays visible and rare.

  It landed one stack ahead of its enforcement so the existing rows could be pinned a group at a
  time without a red gate on `main`, and `--selftest` exercised it from the day it arrived: an
  unwired rule that nothing checks is the very thing it exists to prevent. Four mutations were run
  against that self-test and each was caught.

  **Now enforced.** All 27 rows that claimed a limitation are pinned — 25 to a test that asserts
  it, 2 to a scope reason that says precisely why no code path exists. Applying the rule found
  **fourteen** claims that were false, every one of them understating the engine: interval
  selection, tooltip rendering, temporal colour ramps, `geopath` projections, banded legends,
  transform signal ordering, `config.range`, `config.group`, `config.projection`, signals in guide
  encode blocks, param-valued conditions, the description of a binned discrete field, `params`, and
  the direction of the curved-extent difference.

- **API:** `WordcloudTransform` joins the transform registry in `:vega-dataflow`. Additive.

- **The `wordcloud` transform**, which completes upstream's 51 documented data transforms.

  The layout is upstream's, ported: the sort, the archimedean and rectangular spirals, the board
  bitmap, the bitmask collision test, the bounding-box compaction rule, the blank-row trimming, and
  the order the random draws come off the generator.

  **It is checked to the pixel.** The reason this transform stayed unimplemented was that upstream
  decides collisions by rasterising each word through a canvas and reading `getImageData` back, and
  nothing in common Kotlin rasterises glyphs. That is a reason not to *reproduce* the mask; it is
  not a reason the layout cannot be verified. So the mask is recorded as an **input**:
  `oracle-js/src/record-wordcloud.mjs` runs upstream's own `cloudSprite` against the pinned
  `vega@6.3.1`, writes out each word's bits and where upstream then placed it, and `CloudLayoutTest`
  hands those bits back and requires all eighteen words at exactly upstream's coordinates. Every
  word's position depends on the board the words before it left, so one wrong bit moves all
  seventeen after it: it passes completely or fails obviously.

  Three deliberate mutations were checked against it and each was caught — resetting upstream's
  cumulative `seen` flag per row, assigning `last` before reading it in the sprite-shifting loop
  (JavaScript evaluates `(last << msx) | ((last = ...) >>> sx)` left to right, so the read comes
  first), and swapping the order of the two random draws.

  **What differs, and it is the mask alone.** A host that can rasterise supplies masks through
  `CloudSprites` and gets upstream's own packing. Everything else measures each word and treats it
  as a filled rectangle: same words, same sizes, same order, more air between them. Upstream's
  compaction rule — a word must *overlap* the bounds of what is already placed — is skipped in that
  case, because solid boxes cannot satisfy it and being asked to overlap while missing the ink is a
  contradiction. Measured before that was made conditional: one word placed of ten.

  Deterministic where upstream is not, since the generator is the chart's seeded `RandomStream`
  rather than `Math.random` — which is also what makes a word cloud comparable with anything.

- **API:** `ContourTransform` joins the transform registry in `:vega-dataflow`. Additive; nothing
  else in the surface moved.

- **The `contour` transform**, leaving `wordcloud` as the one of upstream's 51 documented data
  transforms that is not implemented. `contour` estimates a kernel density over point data and
  traces its level sets in one operator — superseded upstream by `kde2d` + `isocontour`, never
  removed, and still the spelling every specification written before that uses.

  It is a composition rather than an algorithm: `kde2d` already estimated the density and
  `MarchingSquares` already traced the isolines, both exact against upstream, so what was new is the
  wiring and the two decisions inside it. `zero` follows *which way in* was taken — folded into the
  extent for a grid given as `values`, not for one estimated, because a density estimate approaches
  zero at its edges and including it would put the lowest contour at a height nothing reaches. And
  the estimate is built with `counts: true`, which is upstream's `density2D()(values, true)` and the
  opposite of `kde2d`'s own default.

  That second one nearly escaped, which is worth recording. The flag scales the whole grid by a
  constant, and the thresholds come from that same grid's extent — so every contour lands in exactly
  the same place either way, with the same vertices, and a colour scale over `value` takes its domain
  from the same data and produces the same colours. The `contour-legacy` fixture compares 450 marks
  against upstream and passes with it wrong. `ContourTest` compares the level values themselves
  against upstream's, and they were out by a factor of 2.5.

- **Regular-expression literals.** `/pattern/flags` lexes to the same `VegaValue.Pattern` that
  `regexp()` builds, so `replace(datum.label, / #\d+$/, '')` — legal against upstream, whose parser
  carries `Invalid regular expression: missing /` in its own message table — is an expression this
  engine reads rather than refuses. They were excluded before `ktecma262` existed, on the argument
  that there was no engine to hand a pattern to, and the exclusion outlived the argument. (#153)

  The `/` ambiguity is resolved the way JavaScript resolves it, by the **preceding token**: a `/`
  divides when what came before it could end an expression and opens a literal when it could not.
  That rule is complete here because the language has no *word* operators — no `typeof`, no `in`,
  no statements — so an identifier is always a value and always means division.

  The literal is scanned to its delimiter and no further: escapes and character classes are
  honoured, because `/a\/b/` and `/[/]/` both contain a `/` that is not the end, and a line
  terminator in the body is refused so an unterminated literal cannot swallow the rest of a
  document. The **pattern** is not validated here — `ktecma262` compiles it, so `/x/q` comes back
  as `invalid regular expression flag 'q'` from the engine that would have run it rather than from
  a second grammar kept in step by hand.

  `TokenType` gains a `REGEX` entry, which is additive but will make an exhaustive `when` over it
  in consumer code incomplete.

### Changed

- **The blend-mode row is pinned to a test rather than to scope.** It recorded that below API 29 the
  modes `PorterDuff` cannot express are reported rather than approximated, and stood on *scope*
  because the branch is gated on `Build.VERSION.SDK_INT` and this project has no way to run below
  29 — the emulator is far above it and there is no Robolectric.

  But the branch is not the claim; the **table** is, and a table is testable anywhere.
  `porterDuffFor` is now a pure function of the mode and `AndroidBlendModeTableTest` holds it on
  whatever device runs the suite. That leaves one scope entry in the whole document.

  Two things the row had wrong. It said "the eleven above `lighten`", and `multiply` is *below*
  lighten and is also refused — deliberately, and it is the one that matters most: Android's
  `PorterDuff.MULTIPLY` is modulate, which agrees with CSS `multiply` only where the destination is
  opaque, so over the transparent background a chart has by default it makes the mark vanish. The
  count of eleven was right; the description was not.

  `internal` is JVM-public, so the extracted function appears in the Android API snapshot under a
  mangled name. Recorded rather than worked around: the snapshot exists to make surface changes
  visible, and this is one.

- **A settled decision is no longer filed beside a gap.** 22 rows claim a limitation; 10 of them are
  decisions nobody should try to close — reproducing upstream's rotated-path bounds would put every
  hit target in the wrong place, implementing `labelBound` as documented would make this engine
  differ from upstream on every chart that sets it, and a reachable `eval` would be arbitrary code
  execution in a host process. `limit.permanent` marks those, with the reason, and the counts are
  printed on every run, so the open list is 12 rather than 22.

  The flag changes what is *reported* and never what is required: a settled row still needs a test,
  because the decision has to keep being true. Making it skip the pin check fails the self-test.

- **`known-divergences.json` no longer calls its entries bugs.** The header said "every entry is a
  BUG TO FIX", and when it was written that was true: eighteen were recorded, and the ones that were
  bugs were fixed. What is left is four differences that survived review twice. The exact-set
  assertion is unchanged, and still cuts both ways — an accepted difference that quietly becomes
  agreement is as much a surprise as a new one.

### Fixed

- **A signal handler declared inside a group mark never fires, and now says so.**
  `VegaChartController.publish` builds its event bindings from the specification's *top-level*
  signals, so a signal declared inside a group carries its `on` handlers into a compile nothing
  dispatches to. The handler was unreachable in silence, which is what ADR 0011 exists to forbid.

  Found by settling two audit questions that turned out to be one fixture. Vega's own
  `overview-plus-detail` declares `brush`, `anchor`, `xdown`, `delta` and `detailDomain` inside its
  `overview` group, so **brushing the overview does not move the detail panel at all**. The audit had
  recorded that first as a scale bug (C4, `buildTime` ignoring `domainRaw`) and then as a
  `push: "outer"` question (Q6). Both were real; neither was the reason it does not work.

  Reported rather than fixed here, because the value is the harder half: signal overrides are keyed
  by name in one flat map, so a group signal's value has nowhere to live across the
  whole-specification recompile a fired handler triggers. The diagnostic names the count and the
  workaround; dispatching into group scopes is its own piece of work.

- **Two rows quoted divergence counts that were years out of date**, claiming "the last five are
  pinned" and "thirteen signatures pinned" against a file holding four. The entries had been deleted
  as the bugs were fixed, which is the mechanism working; the prose beside it was not derived from
  anything. `DocumentedNumbersTest` now checks the count against the file, so a row cannot quote a
  stale one — a claim about a file *in this repository*, checkable in a second, and unchecked for
  months.

- Two tests that borrowed `wordcloud` as their example of an unimplemented transform now use an
  invented name. With every documented transform implemented, a test about "a transform this engine
  does not have" can no longer borrow a real one; what it guards is a misspelling, or something
  upstream adds after the pinned version.

- **Upstream property coverage is measured, not written down.** `UpstreamPropertyCoverageTest` asks
  the parser about every property upstream's own schema documents — one property at a time, five
  candidate values each — and `docs/upstream-coverage.md` is generated from the answer. The result:
  **247 of 247** consumed across axis (79), legend (72), `encodeEntry` (69), projection (14) and
  mark (13).

  It exists because the prose was wrong by fifty in the flattering direction's opposite:
  `SUPPORTED_FEATURES.md` said the axis had "forty-odd properties this engine does not honour" when
  the number is zero. Five more rows said `timeParse`, the date functions, `scale`/`invert`/
  `gradient`, the colour constructors and the scale domain overrides were "not implemented" while
  every one of them works — each citing a reason that had stopped being true, "needs time scales
  first" and "a scale registry the evaluator cannot reach yet". Those six rows are corrected.

  What the measurement claims is narrow and stated in the file: whether a property *name* is
  recognised for some plausible value. Whether it is honoured *correctly* is the differential
  corpus's question, and that already answers it by comparing whole scenes.

  Two ways this nearly reported fiction, both caught before landing. Reading the `*_CONSUMED` sets
  gave "30 axis properties missing", because `AXIS_CONSUMED` is `setOf(…) + guideStyleKeys(…)` and a
  reader who stops at the first bracket undercounts by sixty. Then probing with one value per
  property gave "19 missing", because consumption is type-sensitive and `"labelColor": 1` is not
  recognised where `"labelColor": "red"` is. A test that asserts an invented property *is* counted
  is what keeps the numbers from being vacuous — and it earned that place immediately, by catching
  that the mark probe emitted `"marks"` twice and measured everything against an empty array.

- **A release verifies on macOS as well as Linux.** `release.yml`'s `verify` job ran on Linux only,
  where `check.sh` skips both Apple gates — `swift` needs a Mac and `ios-ui` needs a simulator — and
  the `publish` job that builds the XCFramework and writes `Package.swift` runs no suite of its own.
  So a release shipped the Swift package having run none of its 158 tests and none of the three UI
  tests that are the only end-to-end check of VoiceOver. 0.5.0 went out that way. `verify` is a
  two-host matrix now, the same shape `ci.yml` uses; the oracles and the release preconditions stay
  on the Linux leg, being questions asked once per release rather than once per host.

  The same hole as the instrumented suites, which `verify` also skipped for want of a device and
  which now have their own job.

- **The iOS demo's UI tests run in the gate.** Three tests, and the only place VoiceOver is checked
  end to end: every bar is its own labelled element, the axes are described, and activating an
  element selects the mark it stands for. `check.sh` gains an `ios-ui` gate, skipped with a reason
  on a Mac with no simulator runtime, and `ios-demo.sh --test` now writes JUnit XML — because
  `xcodebuild` emits only an `.xcresult`, and a gate whose result reaches nothing would have left
  the row citing it generating as unproven for ever.

- **`ktecma262` 0.3.0, and six things this repository was writing out itself are now its.** The
  library answered five capability requests (ktecma262#5 to #9) that came out of the
  regular-expression literal work, and adopting them removes about 190 lines of hand-transcribed
  ECMA-262:

  - **Whitespace and line terminators.** `isEcmaWhiteSpace` was `internal` and there was no
    *LineTerminator* predicate, so both tables were written out here — nine code points and a
    category check. Both are public now.
  - **Regular-expression literals.** `scanRegExpLiteral` finds a literal's delimiters, which is the
    escape and character-class tracking the library's own pattern parser already does. Forty-five
    lines to eight.
  - **String escapes.** `decodeEscapeSequence` owns `\xHH`, `\u{…}`, *LineContinuation* and the
    `\0`-only-if-no-digit rule. Sixty-seven lines to eight, with a new test pinning the awkward
    cases so the swap is shown to change nothing a specification can see.
  - **`new Date(...)`.** `makeFullYear`, `makeDay`, `makeTime`, `makeDate` and `timeClip` replace the
    transcription of the same. What stays is `LocalTZA` — turning a *local* time value into an
    instant needs a time-zone database, which the library rightly does not carry.
  - **`ToInt32`/`ToUint32`.** The modulo-2^32 wrap is the library's; the coercion in front of it is
    this value model's.

- **A numeric literal is read by ECMA-262's rules rather than the platform's.** `parseNumber` used
  Kotlin's `toDoubleOrNull` and `toLongOrNull(16)`, which disagree with JavaScript in both
  directions: the hex path overflowed to null past 64 bits, so `0xFFFFFFFFFFFFFFFFFF` — an ordinary
  double in a browser, at 4.72e21 — was a **syntax error**, and hex literals are exactly what colour
  arithmetic is written with. Octal did not lex at all. Kotlin also accepts `1d`, `1f` and `0x1p3`,
  none of which is a JavaScript number. (#155)

- **`DateValues.parseIso` deliberately stays.** `parseDateTimeString` was added for it and was
  measured against it before deciding: where both parse they agree exactly, but the strict grammar
  answers `NaN` for four shapes a real engine accepts and this repository's own vectors contain —
  an offset written `-0500` without its colon, a space instead of `T`, and two or nine fractional
  digits instead of three. `Date.parse` is allowed an implementation-defined fallback and every
  browser has one. Swapping would turn four accepted inputs into a datum silently leaving the domain.

### Performance

- **Resolving a font on Apple is about seven times cheaper, and a host's face is now cached at all.**
  Two things, found by measuring rather than by reading.

  The reported one: `CoreTextFonts` returned the host resolver's answer *before* the cache was
  consulted, so a host that supplied a resolver — which is what the documentation asks a host to do
  — rebuilt a `CTFontCreateCopyWithAttributes` copy for every advance, ascent, descent and line
  height, on every measured line, and again on every drawn run. The reason it was uncached was the
  key: a family name does not say which resolver answered, and the cache is process-wide, so two
  charts with different resolvers would have shared an entry. Keying on the *face the resolver
  returned* — `CFEqual`, so identity rather than a hash gamble — removes the hazard instead of
  avoiding it. (#152)

  The larger one, which the issue did not predict and the numbers did:
  `FontStack.shared.families(stack:)` was **86 per cent** of the cost of resolving a font, at 2.77µs
  of a 3.23µs call. It is a Kotlin object reached across the Obj-C bridge and it was called on every
  lookup, so a chart paid a bridge crossing and a list conversion per metric for what is a comma
  split. It is memoised per family string now — safe because it is pure, and memoised rather than
  reimplemented in Swift so that every renderer keeps splitting a family list by the same rule
  (#123). A 60-style working set went from 3.23µs to 0.48µs a call.

  The cache's own LRU bookkeeping was also linear *per hit* — `order.removeAll { $0 == key }`, an
  `==` per resident key, and for a face key that is a `CFEqual`. It stamps a counter now, so a hit is
  a dictionary read and an integer write and the only scan is on eviction.

  Worth recording against the issue's open question: `CTFontCreateCopyWithAttributes` costs about
  0.95µs, so CoreText does memoise it internally. The font copy was real but it was never the
  expensive part.

### Fixed

- **A new fixture's container announcements were compared against nothing.** `MarkContainerTest`
  walks the harvested reference and asks this engine for a matching answer, so a fixture the
  reference has never heard of is not compared — and not reported either, since there is nothing to
  disagree with. Two fixtures landed that way, because their references were built by running
  `oracle-js/src/reference.js` directly, which writes the scene comparison and nothing else, where
  `scripts/oracle.sh` also harvests the captions and the containers. The existing floor could not
  see it: it counts rows rather than fixtures, and 2,234 is comfortably over 300 either way.

  The harvest is now complete, and a second test requires every fixture in the corpus to appear in
  it. `guide-captions.json` deliberately gets no such check — a fixture with no axis, legend or
  title genuinely has no caption, and 55 of them do not.

- **A stroked `path` mark under `scaleX`/`scaleY` measured twice too wide.** The stroke allowance —
  half the width, or `miterLimit/2` widths where a miter join can run past a vertex — was added to
  the path's bounds in the mark's **own** coordinates, and the node's transform then multiplied it.
  With `scaleX: 8` and a two-unit stroke that is a 32-unit margin where upstream has 4: a path
  measured 108 units wide where upstream says 52.

  Upstream renders the path *through* the transform and expands afterwards, so the allowance is in
  screen space — `scaleX` stretches the shape, not the pen. The expansion is now divided by the
  node's own scale so that the transform puts it back, which is exact for scaling without skew and
  leaves every unscaled path where it was.

  **Nothing in the corpus covered it.** Of 195 fixtures, not one both scaled and stroked a path, so
  `path-scaled-stroke` was written to catch it — five outlines across three marks, covering the
  miter allowance, a square cap and a round join, a non-uniform scale, and a miter limit below the
  default so that "divided by the scale" is separated from "divided by whatever happens to be 4".

- **A mitre limit was never actually compared against upstream.** The differential harness recorded
  `strokeMiterLimit` as a *string*, beside the cap and the join; upstream's `normalize.js` records
  it through `styleValue`, which leaves a number a number. `compareMark` looks a channel up on the
  side upstream put it, so every mitre limit read as "absent" here and the difference was announced
  for a channel no fixture had ever set. The first fixture to set one reported five marks at once.
  It now goes on the numeric side, for paths, symbols, rects, groups and rules alike.

- **Every mark's VoiceOver touch target was stacked on the first one.** The Apple accessibility
  overlay positioned its elements with SwiftUI's `.offset`, which is a *render* transform: it moves
  what is drawn and leaves the view where layout put it, and an accessibility frame comes from
  layout. So all forty-eight elements of a bar chart reported the same origin with only their size
  varying, and touch exploration — most of what makes a chart explorable to a reader using VoiceOver
  — landed every tap on the y-axis. `.position` places the centre and is layout, so what a reader
  touches is now what is drawn.

  It survived because the two tests that could see it ran nowhere. The row in
  `SUPPORTED_FEATURES.md` claiming a SwiftUI chart view cited `AccessibilityUITests` as evidence and
  the generated status column said so — "unproven here" — and behind that the Xcode project had two
  duplicated object ids and `xcodebuild` refused to open it at all: *The project is damaged and
  cannot be opened*. Two objects each claimed one id, a file reference sharing with a build phase,
  so Xcode resolved one and called a group method on the other.

- **A time axis keeps the day width the host's locale states.** `VegaLocale.timeTickFormats` read
  the field *order* off the locale's `date` pattern and then wrote the day back as a hard-coded
  `%d`, discarding the width the same pattern gave it. A host supplying `%-d %b %Y` had `%-d`
  honoured by `timeUnitSpecifiers` and ignored here, from one locale value: `3 mei` asked for and
  `03 mei` drawn. A month-first locale was affected too, by falling through to d3's padded default
  rather than by deriving anything.

  Only the day comes from the pattern, and deliberately not through `withFields`: a week's tick
  wants the month *name*, so `%d-%m-%Y` has to yield `%d %b` and not `%d-%m` — that would be a
  different label rather than a wider one. A month-first locale writing a padded day still derives
  nothing, which is what keeps d3's default a default. (#151, the same discard as #97 one path over.)

## 0.5.0

### Changed

- **`VegaLiteCompilation.vega` states its null contract.** An ERROR-severity diagnostic does **not**
  imply `vega == null`: a document can compile to a usable chart and still report that one construct
  in it was not honoured. Null means no specification was produced at all. A host should check for
  null *and* read the diagnostics.

- **`vega-loader` is named in the README.** It is published and ABI-dumped, and was absent from both
  the module list and the pipeline diagram — a module a consumer can depend on and could not find.

- **`SceneExporter.toPng` no longer takes a `quality`.** PNG is lossless and Android documents the
  argument as ignored for it, so a caller passing 80 to get a smaller file got the same bytes and no
  way to find out why. A smaller file is `BitmapExportOptions.pixelScale`.

- **`scripts/test-core.sh` runs every core module.** It named six by hand and predated two of them,
  so `vega-lite` and `vega-loader` — about eight hundred tests, the whole Vega-Lite compiler among
  them — were absent from what the README sells as the JVM suite. A broken Vega-Lite compiler passed
  it cleanly. The list is derived from `settings.gradle.kts` now, so the next module added is in it.

- **`ChartSession.set(signal:to:)` is queued like every other mutation.** It was the one entry point
  that was not, and it is the one a reader can reach *while* a chart is loading — a slider on a
  specification still compiling remotely. `setSignal` walks the signal updater and the event
  dispatcher that `setSpecAsync` is at that moment rebuilding off the main actor, which is the exact
  race the queue exists to prevent. The value is still recorded immediately, so a choice made during
  a load survives into the chart that load produces.

- **Three process-lifetime caches on the Apple side are bounded.** Decoded images, `CTFont`s and
  dataset text all grew without limit for the life of the process, and two of them are keyed on
  something that varies per chart — a font's *size*, a dataset's URL. Images and dataset text are
  bounded in bytes rather than in entries, because a `heatmap` raster is megabytes where an icon is
  kilobytes and Vega's own `flights-200k.json` is twelve megabytes; fonts are bounded by count. All
  three evict least-recently-used, so a chart being redrawn keeps its working set.

- **`SceneDrawTarget` and `DrawTarget` carry the item's blend mode.** Both the Compose Multiplatform
  renderer and the Apple one ignored the `blend` channel outright — no drawing, no diagnostic, no
  row in the feature table — while the Android View mapped all sixteen and exported SVG carried all
  sixteen. One specification therefore produced two different pictures depending on the host, and
  only one of the two admitted to a gap. Both now map every mode, through `BlendMode` on Compose and
  `CGBlendMode` on Apple. A custom target has one more parameter on each of its five draw methods;
  on Kotlin it defaults to `SceneBlendMode.NORMAL`.

- **`rememberVegaChartController` takes the device's own text metrics.** It built a controller with
  the deterministic default engine — fixed ratios, font scale 1, no host faces — while the view
  underneath drew with `AndroidTextEngine` at the reader's own font scale. At the largest
  accessibility text size that lays every label out about a third narrower than it is painted. It
  now builds an `AndroidTextEngine` from the composition's configuration, and takes the same
  `fontResolver` the chart does.

- **`SceneExporter` centres, caps and says what it needs.** The export drew at the top-left of the
  page while every renderer centres; a `pixelScale` of 30 asked for a nine-gigabyte bitmap with
  nothing to stop it; and the default renderer has neither the host's faces nor its image resolver,
  so an export taken the obvious way was not the chart on screen. `VegaChartView.exporter()` hands
  over one built from the view's own seams.

- **A Vega-Lite specification with no `data` compiles, and reads a table called `source`.** It used
  to be an ERROR *and* a non-null result whose marks read a dataset called `""` — so a host
  following the README's stop-on-null pattern handed the runtime something broken. Upstream compiles
  it to `"data": [{"name": "source"}]`, which is exactly the seam a host supplies its own rows
  through: set `VegaChartController.hostData` to `mapOf("source" to rows)`.

- **An encoding channel that is not a channel is dropped, with a diagnostic.** Upstream warns and
  drops; this engine kept it, so a typo like `colour` entered the aggregate's `groupby`, the spoken
  description and the tooltip's field list, and produced a chart grouped by a column nothing was
  coloured with. Unchanged for `geojson`, `xError` and `yError`, which have their own message.

- **`Fields.varName` is ASCII, like the `\W → _` it transcribes.** Kotlin's `isLetterOrDigit` is
  Unicode, so a column called `año` kept its `ñ` here and lost it upstream — two different signal
  names for one specification.

- **A number written into an emitted expression is `String(n)`.** Two hand-rolled spellings — one in
  `Fields`, one in `LayoutSize` — wrote `1.0E-7` where JavaScript writes `1e-7`, and the second
  saturated `toLong()` at 9.2e18 while allowing values up to 1e21 through. Both delegate to
  `Decimals.jsString` now.

- **A mark carries a tooltip only when its specification asked for one.** `MarkEncoder` fell back
  to the whole bound row when no `tooltip` channel was written, on the stated grounds that upstream
  does the same. It does not: a probe against vega 6.3.1 answers `undefined`, and `tooltip` is not
  even a property on the item. So every mark on every chart carried a tooltip holding whatever its
  dataset held — and a chart is routinely drawn from a table with more columns in it than the chart
  shows, which makes that a disclosure on hover from a specification that asked for no tooltip.
  `NodeMetadata.tooltip` is now null unless the channel produced a non-nullish value; a host that
  wants the row reads `NodeMetadata.datum`, which is also what `MarkHovered` and `MarkClicked`
  carry. The facet-cell path in `AxisBuilder` and `LegendBuilder` already worked this way.

- **`HttpTransport.get` takes the byte budget.** A cap applied after the read is not a cap: the
  whole response is in memory by the time it is checked, which is the thing being defended against.
  The loader hands its `maxResponseBytes` down so a transport can stop reading, and the JVM one now
  does — a bounded streaming read, decoded with the charset the response declared. A custom
  transport has one more parameter; ignoring it leaves the old behaviour, since the loader still
  checks the body it gets back.

- **A font resolver is asked for one name, on every host.** `ComposeTextEngine` called a host's
  `fontFamilyResolver` once with the whole CSS stack — `"Noto Sans, Chart Sans"` — and let
  `namedFontFamily` split it, while the Android and Apple engines called theirs once per entry.
  The same host closure therefore had to be written differently per renderer, which is the #123
  defect one level up: not which names an engine reads, but what it asks a host. The engine now
  walks the stack and offers each entry in turn, and falls back to a generic itself.

  For a host passing `namedFontFamily(myFonts)` nothing changes. A host with its own closure that
  matched on the *unsplit* string has to match on a name instead — which is what it already had to
  do to work on the other two renderers.

  `namedFontFamily` correspondingly does one thing now: look a single name up in the map, quotes
  and spaces trimmed. Splitting the stack and falling back to `genericFontFamily` were the
  engine's job and are done there, so calling it directly with a stack or a generic returns `null`
  where it used to answer.

- **`SvgOptions.idPrefix` says what it is for.** Ids are sequential from document order, so two
  documents rendered with the same prefix generate the same ids and an `xlink:href="#vc0"` in the
  second resolves against the first when both are inlined into one page. No behaviour changed; the
  documentation did.

- **`AxisDefaults.TITLE_MIN_EXTENT`/`TITLE_MAX_EXTENT` are `MIN_EXTENT`/`MAX_EXTENT`.** They clamp
  the **axis** extent — the reach of its ticks and labels — which is what upstream calls `minExtent`
  and `maxExtent`; the old names read as a property of the title and are not one.

- **`groupTuples` and `groupKey` answer a `GroupKey` rather than a list of raw values.** What makes
  two rows one group is upstream's own string coercion — its `fastmap` is object-backed, so the
  number `1001` and the string `"1001"` are one property and therefore one group — and keying on
  the raw values split what upstream merges. `GroupKey.values` carries the first row's spelling,
  which is where upstream reads a group's own fields from too, so a caller reading them positionally
  changes one line.

  `BinTransform.binSettings` gained a `steps` parameter, in the position upstream reads it.

### Added

- **Four expression functions upstream has and this engine did not:** `isNaN`, `atob`, `btoa` and
  `encodeURIComponent`. They live in `vega-expression`'s **codegen whitelist** rather than in its
  `functionContext`, and the test asserting that `knownUnsupported` is empty read only the second
  table — so the guarantee was auditing the wrong list and all four were missing behind it. The
  test now reads both. `isNaN` is `Number.isNaN`, so it does not coerce: `isNaN('x')` is false.

- **`VegaValue.Undefined`, because JavaScript has two absent values and this model had one.**
  Reading a property that is not there now yields it, and nothing else produces one. `isNullish`
  is the `_ == null` screen that covers both, which is the idiom upstream writes every one of its
  own missing-value tests with.

  Two host-facing readers gained an answer: `ForeignValue.kind` and `ForeignSignals.kind` return
  `"undefined"` where they used to return `"null"` for a value an expression produced from a
  missing field. A host that does not care can test both.

- **`bin` reads `steps`**, upstream's third branch for choosing a step size: `span / maxbins`, then
  the largest listed step still below it. Neither read nor reported before, so a chart asking to be
  binned only on the round numbers it has axis labels for got whatever the automatic rule chose.

- **`pie` reads `sort`**, which decides which row gets which sweep and leaves the rows where they
  were. It is a boolean in upstream's own `Definition`, and a chart asking for its biggest slice
  first got its slices in data order with no diagnostic.

- **Eight diagnostic codes**, so a host can tell apart conditions that used to share one:
  `VEGA_DATA_LOAD_FAILED` (the only one that describes something outside the specification, and the
  only one a retry is meaningful for), `VEGA_DATA_UNREADABLE`, `VEGA_SCALE_NOT_BUILT`,
  `VEGA_ENCODE_INVALID_VALUE`, `VEGA_DUPLICATE_DEFINITION`, `VEGA_INTERACTION_UNSUPPORTED`,
  `VEGA_COMPILE_LIMIT_EXCEEDED` and `VEGA_COMPILE_FAILED`. Nothing was renamed or removed; the
  conditions that used to report `VEGA_PARSE_UNKNOWN_PROPERTY`, `VEGA_SCALE_UNSUPPORTED_TYPE` or
  `VEGA_EXPORT_IMAGE_UNRESOLVED` from the *runtime* now report one of these instead.

- **`PathData.containsNonZero`**, the containment test that agrees with what a fill paints, and
  **`isSafeHref`**, upstream's `href` allowlist as a predicate a host can apply to the link a
  `MarkClicked` carries before following it.

### Fixed

- **A deeply nested document is refused before the JSON parser descends into it.** The parser
  recurses once per `{`, so a document a few thousand levels deep exhausted the thread's stack —
  and *how many* thousand depends on the stack the host gave that thread, which is not something an
  engine can rely on: the same document parsed on a macOS laptop and took the process down on a
  Linux CI runner, in the same commit. `VegaJson.MAX_JSON_DEPTH` is checked by a scan that does not
  itself recurse, so it works on Kotlin/Native too, where a `StackOverflowError` cannot be caught at
  all.

  It is **192**, and the number was measured against the tightest target rather than the roomiest: a
  document that parses is one the compiler then walks, and through `ChartSession` on macOS that walk
  dies at about 450 where the JVM survives 511, so the parse bound has to leave room for everything
  downstream of it. 192 is sixteen times the deepest document in this repository's corpus, and still
  high enough that a document can reach `MAX_GROUP_DEPTH` and `MAX_VIEW_DEPTH` — a limit the parser
  refuses first is a limit nothing can ever report.

  This closed the last hole in "nothing throws": `SpecCompiler.compileJson`, `VegaLiteCompiler
  .compileJson` and `VegaLiteInput.toVega` all guard the *compile*, and all three parse first.

- **A `sequence` transform cannot ask for more rows than the heap holds.** Its count came straight
  from three numbers a document wrote, so `{"type": "sequence", "stop": 1e9}` was an
  `OutOfMemoryError` about four seconds later — an `Error`, so not something `SpecCompiler`'s guard
  catches, and not something Kotlin/Native could catch at all. The same shape as the stack overflows,
  one resource over. Bounded at `MAX_SEQUENCE`, which is the number its own **expression** twin has
  used since it was written; the asymmetry was the defect rather than the number.

  `density`, `cross`, `kde2d` and `isocontour` were probed for the same shape and already clamp.
  Catastrophic regex backtracking was probed too: ktecma262 has a step limit, so a
  specification-supplied `(a+)+$` is refused rather than hanging.

- **`linuxX64Test` runs in the gate.** `linuxX64` was compiled and never executed, so its
  `commonTest` suites — the decimal expansion, a specification's own regular expressions, the two LRU
  caches, and now `DeepInputTest` — had run on no machine at all for a shipped target. Named only in
  the Linux branch, because Kotlin registers the task everywhere and disables it off Linux, and a
  disabled task is the silent skip the Apple block beside it already exists to prevent.

- **The Apple surface has a generative gate too.** `DeepInputTests` drives the same pathological
  shapes through `ChartSession` — the way an app reaches them, across the Obj-C boundary, on text a
  reader pasted. Two things can break there that cannot break in the Kotlin suites: the bridge could
  lose a diagnostic, and `ChartSession` parses `hostConfigJson` itself on a path no Kotlin test
  takes.

  It found the `MAX_JSON_DEPTH` miscalibration on its first run, because it compiles what it parses
  and the Kotlin suite did not: that suite's "nested JSON" shape called `parseOrNull` and stopped,
  so it proved the parser survived and said nothing about the pipeline behind it. Both shapes now
  compile.

- **A date test no longer depends on which edition of the time-zone database the host ships.**
  `JsDateTest` pinned absolute timestamps for the years 0 and 100 in a *local* zone. What an
  implementation answers for an instant earlier than a zone's first recorded transition is not
  something the database settles, and the offset itself moves between editions — Amsterdam became a
  link to Brussels in tzdata 2022b, which changed its local mean time from +00:19:32 to +00:17:30.
  So the suite passed on macOS and failed on Linux the first time it ran on both. Those two years
  are stated in UTC now; the rule under test is how a *year* is read, which has nothing to do with
  the offset.

- **The native test binaries have a zone at all now, and it is deliberately not the JVM's.**
  `tasks.withType<Test>()` is Gradle's *JVM* test task and a `KotlinNativeTest` is a sibling of it
  rather than a subtype, so the zone, the heap and the stack were pinned for `jvmTest` and for
  nothing else — `linuxX64Test` and `macosArm64Test` ran in whatever zone the machine was in.

  They are pinned to **UTC**, not to `TEST_TIME_ZONE`. Everything that depends on the zone — the
  references, the oracle comparisons, the Node process — runs on the JVM, and no native test
  consumes a zone-dependent golden. What a native run is *for* is a `commonTest` suite that reads
  the ambient zone without meaning to, and the only thing that catches one is running it somewhere
  the JVM is not; pinning both sides to Amsterdam would throw that away to buy nothing. So a suite
  that picks up the ambient zone now fails on the machine of whoever writes it rather than on
  `main` after the merge.

  `PinnedTestZoneTest` asserts the JVM pin arrived, because a build script can state a pin but
  cannot observe what the test process got. It checks the zone by its **rules** rather than its
  name: `Europe/Amsterdam` is a link to `Europe/Brussels`, so an id comparison can fail on a host
  where the pin worked. Kotlin/Native on Linux turns out not to honour `TZ` at all — it reports
  `Etc/UTC` under a pin of `Europe/Amsterdam`, which is how all of this was found.

- **Eight suites moved from `jvmTest` to `commonTest`.** An audit of the 132 JVM suites found 76 that
  use no JVM-only API; moving all of them would be wrong, because a native run is slow and most
  assert chart semantics that cannot vary by target. The eight that moved are the ones where the
  platforms genuinely differ — the limit suites, where an overflow is catchable on the JVM and a
  `SIGSEGV` on Native, and the number, date and identifier-grammar suites, which sit on each
  platform's own maths, calendar and regex engine. The rule is written down in `build.gradle.kts` so
  the split stays a decision rather than a leftover.

- **The deep-input gate runs on every target, not just the JVM.** It matters most where it was not
  running: on Kotlin/Native a stack overflow is a `SIGSEGV` that kills the process — exit 139, no
  catch, no teardown — where the JVM raises a catchable `StackOverflowError`. So the target with the
  smallest thread stacks and the hardest failure was the one the test could not see. `DeepInputTest`
  moved to `commonTest` and now executes on all five.

  Every bound this needed was already in `commonMain`, which is why moving the test found nothing
  new — bounding rather than catching was the only thing that could have worked there.

- **A deeply nested event selector no longer takes the compiler down.** `EventSelector`'s `one` and
  `between` are **mutually** recursive over bracket nesting in a selector string, so a pasted
  Vega-Lite document with a few thousand `[` in a `select.on` threw `StackOverflowError` out of
  `VegaLiteCompiler.compileJson` and `VegaLiteInput.toVega`. A selector nested past sixteen levels is
  now taken as a literal string, which is what this parser already does with every selector it cannot
  read.

  Found by building a call graph over the compiled classes and looking for cycles — mutual recursion
  is invisible to anything that searches for a function calling itself.

- **The test JVM's stack is pinned, like its heap and its time zone.** A thread's default stack size
  is the platform's, not the project's, so the local gate reported green for a defect CI caught.
  Pinning it to 1 MB — HotSpot's own default on 64-bit Linux — makes the gate mean the same thing on
  a laptop and on CI, which is what `maxHeapSize` and `TZ` beside it already do. It found a second
  instance of the same class of bug on its first run.


- **A mark's items are in data order in the scene, as upstream's are.** `zindex` was applied when the
  scene was *built*, and upstream applies it when it draws — which this engine also does, in
  `paintOrder`, in every renderer and in the hit index. Sorting twice was not a wrong picture, but it
  made the scene tree a different tree from upstream's: `children` read positionally gave a different
  row than `items` does, and **no differential fixture could carry an item `zindex` at all**, because
  the record is compared position by position. The new `item-zindex` fixture is what found it.

- **A controller shared between two views says so.** `contentScale` is per-view, so two views of
  different sizes each overwrite the other's fit and one of them hit-tests by the ratio between them.
  Documented on the property, and a second conflicting fit now reports
  `VEGA_INTERACTION_UNSUPPORTED`.

- **`invert` is the inverse of `apply` on a multi-stop pow or log scale.** `apply` has been piecewise
  since a three-stop power scale was found interpolating across both segments; `invert` still read
  only the first and last stop, so the two were not each other's inverse and the gap grew with how
  unevenly the stops were spread. `invert('s', x())` is how a specification turns a pointer into a
  data value, so a brush on such an axis selected a range with the wrong numbers in it.

- **`lookup` matches a null key.** Upstream's index is object-backed, so a null key is stored under
  `String(null)` and a row whose key is null finds it — probed. Skipping them meant a table that
  deliberately provides a row for "no value", which is the ordinary way to label a missing category,
  gave every such row the default instead.

- **A tick label past 2^63 is the number.** `Double.toLong()` saturates rather than overflowing, so
  every value above about 9.2e18 printed the identical `9223372036854775807`.

- **A time tick is labelled by interval floors, not local field values.** d3 asks `hour(date) <
  date`; this asked `at.hour != 0`, which is the same question only in zones whose day begins at
  midnight. Santiago, Havana and Tehran move the clock forward at 00:00, so the first instant of
  that day is 01:00 — and there, once a year, a day tick was labelled as an hour.

- **`Ticks.nice` answers the domain it was given when it cannot converge**, rather than a
  half-niced one that is neither what was asked for nor a rounded number. Its cap is a safety net —
  d3 has none, because the step converges — and it was low enough to be reachable.

- **A log scale's non-integer base keeps its fractional tick count.** d3 passes `j - i`, the
  difference of two *logarithms*, straight to `ticks`, which divides by it; this truncated it to an
  integer and raised anything below one back up to one.

- **The differential comparison can see a `defined` gap.** Both sides flattened a `moveTo` and a
  `lineTo` into a bare point, and `normalize.js` never read the `defined` channel at all — so a line
  broken into subpaths and a line drawn straight through the break produced the *identical* record.
  A regression joining across a gap passed every assertion on both sides, and a gap is the entire
  point of the channel. Each vertex now carries the command that produced it, the recorder honours
  `defined` through d3's own generator, and an area records front-and-back per segment the way
  `d3.area()` emits it. The new `line-defined-gaps` fixture is the only one in the corpus with an
  interior `moveTo`.

- **An undefined point is not drawn.** With the comparison able to see it: the engine made the point
  that *broke* a series a subpath of its own, where `d3.line().defined()` drops it. Invisible under
  a butt cap, a round dot under a round one, at a coordinate the chart is saying it has no value
  for. And a subpath of exactly one point closes itself, which is a rule per subpath rather than per
  series — this was being suppressed for the whole series as soon as any break existed.

- **`scripts/check.sh` arms the Vega-Lite scene gate before running it.** The references were
  rendered at the last step, *after* the Gradle gate that needs them, so a single run armed the gate
  for the next one and never for the one printing "Green, and every gate ran" — 1126 cases skipping
  in silence. They are rendered first now, and reported as a gate of their own when `--fast` or a
  missing node skips them. `VegaLiteFixtureDifferentialTest` also fails on a *partly* rendered set
  rather than skipping most of itself.

- **The release workflow arms it too.** Its verify job never rendered the references at all, so
  every release since the gate was added published with it skipped in full — while the publish job's
  own comment said verify "has already run that comparison in full".

- **A release page cannot claim Maven Central coordinates that do not exist.** When the publish
  step's credentials are absent it skips, and the release job still tagged, pushed and wrote a page
  telling readers to add a dependency that resolves to nothing — and a tag cannot be moved, so the
  version was unreleasable afterwards. The job now reports whether it published and the notes say
  so. `--latest` is also computed rather than passed unconditionally, so a patch for an older line
  cannot move the badge backwards.

- **One `[api-snapshot-only]` marker no longer exempts a whole branch.** It was a substring search
  over every message on the branch, so a commit that re-recorded a snapshot for a cosmetic reason
  waved through a real API break in another commit. Each snapshot is now traced to the commits that
  touched it, and every one of those has to carry the marker.

- **`scripts/host-conformance.py` no longer counts a comment as a reader.**

- **The oracle's number corpus records what it meant to.** `record-number-strings.mjs` passed six
  expressions to one `push()`, which takes one — so five of six families were evaluated and thrown
  away, including the powers of two and the large integers, which is exactly where `String(x)`
  switches notation.

- **`eval-probe.js` and `transform-probe.js` pin determinism.** Without it `now()` answered the wall
  clock and `random()` an unseeded generator, so a probe of either was irreproducible — and a probe
  exists to be quoted in a comment or turned into a fixture.

- **`acorn` is pinned exactly**, in the oracle whose own rule forbids ranges.

- **A drag pans the Apple chart by the distance the finger moved.** The view divided the delta by the
  fit scale before dispatching, and `InteractionState.viewportOffset` holds a pan in surface pixels —
  `visibleViewport` is what divides. So at any fit other than 1 the chart moved by a fraction of the
  finger, and the further from 1 the fit the further the drawing lagged behind the drag. The Compose
  Multiplatform chart documents this rule beside its own dispatch and the Android View follows it.

- **`load("")` clears the chart even mid-compile.** The clear was synchronous while the compile was
  not: it emptied what the compile was about to write and the compile wrote it back, so a host
  emptying its editor was left looking at the previous chart — with `loading` stuck true, because the
  block that would have cleared it had already been counted and then cancelled.

- **A VoiceOver activation hits the mark the reader was on.** The synthesised tap applied the fit
  scale and neither the zoom nor the pan, while the frames a reader lands on carry both. So once the
  chart had been moved, activating a mark activated whichever one was drawn where it used to be.

- **A failed compile reports its own diagnostics.** `try?` gave nil on a throw, the previous
  document's diagnostics were left in place, and the failure message was read out of them — so a
  host was told a new document had failed for a reason belonging to the one before it.

- **Letter spacing is measured at the size it is drawn at.** The Apple engine applied the reader's
  text scale to the font and not to the kern, while the drawing applied it to both: at any Dynamic
  Type setting other than 1 a spaced label was laid out with one spacing and painted with another,
  and the layout that decides whether labels overlap had already used the smaller number.

- **`CoreTextTextEngine` locks the one thing it mutates.** Its header said it holds no mutable state
  and it holds `unresolvedFontFamilies`, inserted into from the measuring path — which
  `ChartSession` runs off the main actor.

- **A rectangle's image under a transform maps all four corners, on the Apple walk too.**

- **The Apple demo says what its loader actually does.** The paste screen claimed "no network
  loader" and the README said it installs `DenyLoader`; every session uses `VegaDataLoader`, which
  falls back to fetching from `vega.github.io`. A reader deciding whether it was safe to paste
  someone else's chart was being told the wrong thing about what that would fetch.

- **`scripts/host-conformance.py` no longer counts a comment as a reader.** The "read by" check was a
  substring match over whole source files, so deleting a conformance test's assertions while leaving
  the sentence above them describing what they used to check would have kept the gate green — the
  gate's own failure mode, in the gate. It matches inside string literals now, with comments stripped.

- **A continuous pan or pinch survives its own first pixel on Compose Multiplatform.** The chart's
  `pointerInput` was keyed on the viewport, and a `pointerInput` restarts its coroutine — cancelling
  whatever gesture is in flight — whenever a key changes. The documented wiring feeds
  `InteractionState.viewportOffset` back into the composable, so the first pan increment cancelled
  the detector that produced it: a drag was a sequence of one-increment gestures, each from a fresh
  centroid. The viewport is read through a `State` now and kept out of the keys.

- **A pinch on the Android View zooms about the reader's fingers.** `ScaleGestureDetector` reports
  its focus in raw view coordinates and those went straight through, while every other point the
  view dispatches has the placement's origin taken off. On any padded or centred chart the zoom
  pulled towards a point offset by exactly that origin. There is one conversion now and both
  dispatch sites use it.

- **A TalkBack activation hits the mark the reader was on.** The helper dispatched a tap in **scene**
  coordinates into a controller that reads placement-relative view pixels and then divides by the
  fit scale — so the two agreed only at scale 1, unpanned. A screen-reader user has no way to see
  that happen.

- **The drawn viewport's far corner comes from the placement.** It was the padding box's, so it was
  too large by the whole of the centring slack, all of it on the right and the bottom: an opaque
  scene background — Vega-Lite gives every chart `"background": "white"` — painted a margin down two
  of the four sides, a zoomed chart's content escaped there, and the fit scale used for drawing
  disagreed with the one reported to the host.

- **The built-in tooltip is drawn where the pointer is.** Its anchor is placement-relative and the
  canvas it is drawn on is not, so the bubble sat off by the placement's origin on every padded or
  centred chart.

- **TAB, ESC, HOME, END and the arrows are no longer swallowed.** `onKeyDown` returned true for all
  of them while the controller has no behaviour for a key and no event stream reaches one — a focus
  trap, and on a television, where the d-pad is the keyboard, a chart that could be entered and not
  left. The key is still reported on `ChartEvent`s.

- **A pan that ends says so, on both Kotlin hosts.** Neither dispatched `GesturePhase.ENDED` for a
  pan, and the Compose Multiplatform chart never passed `ended = true` to either callback — a
  parameter documented on both and dead on both. `ChartEvent.ViewportChanged` fires only on `ENDED`,
  so it never fired at all.

- **`onHover` is mouse and stylus only, as documented.** Every pointer move was reported, so a touch
  drag churned the hover state and the tooltip with it, sixty times a second, under the reader's own
  finger.

- **`onPlaced` fires when the placement changes rather than every frame.** It is called from the
  draw phase, and a host doing the documented thing with it — setting `controller.contentScale` —
  was writing to a `StateFlow` from inside a draw, which schedules the next frame.

- **A gradient-filled text mark draws its gradient on Compose Multiplatform, and a stroked one draws
  an outline.** The first fell back to solid black; the second was *filled* with the stroke's
  colour, which is heavier than an outline and solid where upstream leaves the counters open.

- **`MULTIPLY` is reported rather than approximated below API 29.** Android's
  `PorterDuff.Mode.MULTIPLY` is `[Sa*Da, Sc*Dc]` — *modulate*, not CSS multiply — so a blended mark
  over a transparent region vanished, on by far the most-used mode, on the devices where this file
  already promised to report rather than substitute.

- **An inline `imageResolver` or `fontResolver` no longer rebuilds the renderer every
  recomposition.** Both setters compare by identity and rebuild the renderer, and a lambda written
  at a call site is a fresh instance each time — so the image cache was emptied per frame and the
  "a URL is asked once, not once per frame" contract became its opposite for exactly the hosts that
  supply a resolver.

- **A font stack tries its concrete names before its generic.** A generic anywhere in the list
  preempted platform resolution of everything before it, so a device that *did* have the named face
  never drew in it.

- **A gradient is resolved against the same box on every renderer.** The Android View used the
  geometric rectangle where upstream, the SVG renderer and the Compose Multiplatform walk all use
  the stroke-widened bounds.

- **TalkBack is not re-announced on every frame of a pan.** The semantic tree was invalidated on
  every published snapshot; it is invalidated when what it *says* changes, and a moved frame is
  re-read by `ExploreByTouchHelper` without one.

- **A written date rolls over instead of throwing.** `{"month": 13}`, 30 February, hour 24 and date
  0 are all legal Vega-Lite, and all mean what the JavaScript date constructor means by them:
  upstream's `dateTimeToTimestamp` is `+new Date(...parts)`. Building a `LocalDateTime` from the
  parts instead threw `IllegalArgumentException` on every one of them, out of a public entry point,
  from a module that had no `try` in it. `JsDate` is that constructor written out — the rollover,
  the two-digit-year rule and NaN-rather-than-throw — and both `vega-lite` and `vega-expression`
  call it. Which fixes a second thing on the way: `datetime(99, 1, 1)` is February **1999** to every
  Vega renderer, and was the year 99 here.

- **The Vega-Lite compiler returns rather than crashing.** `compileJson` and `compile` are guarded,
  and `VegaLiteInput.toVega` guards its own parse. Two limits close the ways a document could reach
  a `StackOverflowError` deliberately: view nesting past 64, and more than 512 declared transforms —
  the second of which is not nesting at all, since each transform becomes a node in a chain that
  eight optimizer passes walk recursively. Upstream refuses the same documents, with a `RangeError`.
  A `repeat` grid is capped at 256 cells for the same reason: each cell is a whole compiled view.

- **An interval selection's written dates reach its store as numbers.** A store is a dataset, so
  upstream converts while compiling; the interval branch emitted the `{"year": …}` object raw, and
  the initial filtering compared a column of milliseconds against an object — false for every row —
  until the reader's first drag replaced the store.

- **A comma-separated event selector keeps every stream it names.** `{"on": "click, touchend"}`
  dropped the touch, silently, from the specification whose whole purpose in writing two was to have
  both. `Selection.on` is a list now and the emitted `events` is an array, byte-for-byte upstream's.

- **A signal rename no longer rewrites the data.** Folding two bin nodes renames the signals of one,
  and the rename is a substring replace over every string in the finished specification. A dataset's
  inline rows are the user's values, and one that happened to contain a generated name would have
  been quietly rewritten; the walk skips `values` and `datum` now.

- **A layer or concat member that is dropped says so.** A member that failed to parse, and a concat
  entry that is not an object at all, were both skipped in silence — so the chart came out a plot
  short with nothing said about which one. Upstream refuses the whole document; this reports and
  draws the rest, which is what this engine does everywhere else.

- **Two fallback diagnostics that were wrong.** The unsupported-transform message left out `bin`,
  `stack`, `timeUnit` and `impute`, all four implemented a hundred lines above it; the
  malformed-predicate message said parameters "are not implemented", and they are.

- **A stale compile cannot publish over a newer one.** Every entry point that recompiles —
  `setSpec`, `setSpecAsync`, `hostData`, `containerSize`, `setContainerSizeAsync` and the recompile
  an interaction triggers — now stamps a request number when the *caller asks*, and publishes only
  if it is still the newest. The asynchronous ones held a lock, so the compiles were ordered; the
  publishes were not, because each asked "am I finished?" rather than "am I still the answer?". A
  host that resizes while a specification is still compiling could be left looking at the earlier
  result. Two compiles running at once on one controller now also report `VEGA_COMPILE_CONCURRENT`,
  since they share one text engine and one signal table.

- **A URL is fetched once per document, not once per compile.** Every interaction here recompiles
  the whole specification and a compile resolves every dataset from scratch, so with a loader opted
  in a tap issued a blocking GET per `url` dataset — on the dispatching thread, with the loader's
  own timeouts — and a `{"type": "timer", "throttle": 500}` stream polled the network twice a
  second. `VegaChartController` now wraps the host's loader in a `CachingDataLoader`, cleared when a
  new specification arrives, because a new document is a new decision about what is behind a URL.

- **`stop()` stays stopped.** It cancelled the timers and the next publish started them again — any
  publish, and a host that keeps feeding `hostData` to a view it has torn down publishes constantly.
  A chart the host had explicitly stopped went on ticking with a repaint attached to every tick. The
  flag is a latch now, cleared only by `setSpec`/`setSpecAsync`.

- **A private address written as IPv6 is refused like any other.** `blockPrivateNetworks` read
  dotted quads, so `::ffff:169.254.169.254` — the cloud metadata endpoint in the mapped form every
  dual-stack socket connects straight through — was one notation away from being fetched.
  `::ffff:`, `64:ff9b::` and the `::` forms are unwrapped to the IPv4 address they carry, and
  `fec0::/10` joins the ranges that are refused. A public IPv6 address is still allowed: the rule is
  about reach, not notation.

- **A URL with no host, or a port that speaks another protocol, is refused.** `http:///x` parses
  with a host of `""`, which is not null — so it passed the null check and, under an empty
  allowlist, reached the transport with nowhere to connect but the local machine. And neither the
  allowlist nor the private-network rule looked at the port, so `http://host:25/` was an SMTP
  conversation whose first line a specification got to write.

- **A `Uri` writes an IPv6 literal back with its brackets.** The host is stored bare, because that
  is the form every policy rule wants, and it was written out bare too — so `sanitize` returned
  `http://2606:4700:4700::1111/x`, whose authority reparses with a port of `4700:4700::1111`.
  `sanitize` was returning something `load` would refuse.

- **A gesture carrying a number that is not a number is refused.** A NaN reaching the pan offset or
  the zoom anchor poisons every coordinate derived from it, and the failure then surfaces three
  layers away as a chart that draws nothing. Platform recognisers do produce these — a fling whose
  velocity divides by a zero time delta, a pinch whose two pointers land on one pixel — so it is
  reported as `VEGA_INTERACTION_UNSUPPORTED` and dropped.

- **A click reports the row its mark was built from.** The datum was read back out of the hit-test
  index by identity, which finds the *node*; the metadata carries the row, and it is the only thing
  that is right when two marks share a position.

- **A `window:` stream says it will not fire on its own.** Nothing in `VegaChartController` produces
  a window-sourced event, so a specification using the commonest idiom for a drag that continues
  outside the chart got a signal that never changed and no diagnostic. The watch is still
  registered — a host driving `EventDispatcher` itself can dispatch one, and does — but it is now
  reported.

- **Nothing throws, by construction.** The README stakes the whole diagnostic model on it —
  "nothing throws; a compile returns diagnostics" — and no public boundary had a catch-all or a
  depth cap, so a *specification* could take the host down seven different ways. Each of those is
  fixed where it was; `SpecCompiler.compileJson` and `compile` are now guarded as well, so the next
  one is a `VEGA_COMPILE_FAILED` diagnostic carrying the exception rather than a crash. Cancellation
  and `OutOfMemoryError` are deliberately not caught.

- **A time scale honours `domainRaw`, and nices from its bounded domain.** The time branch read
  `spec.domain` and consulted the raw domain only to suppress `nice`, so the whole ladder every
  other continuous scale climbs — raw domain, `zero`, the three `domain*` overrides, padding, nice —
  was computed and thrown away. `domainRaw` is how an interactive zoom publishes the exact interval
  it wants: the committed `overview-plus-detail.vg.json` fixture is that shape, and brushing the
  overview recompiled the detail panel with the full domain and rendered it unzoomed. A static
  compile passes the oracle because the brush signal is null at compile time, so the flagship
  interaction was inert with nothing to read. And the `nice` step re-derived from the *original*
  domain, discarding `domainMin`/`domainMax` and the padding computed immediately above it —
  Vega-Lite defaults every temporal scale to `nice: true`, so a VL `scale.domainMax` on a time axis
  silently showed the whole span.

- **A gradient legend's `values` need not be numbers.** `"values": ["2020-01-01"]` on a continuous
  colour scale — the natural way to write date stops — cast straight to `VegaValue.Num` and threw a
  `ClassCastException` out of the public `compileJson`. Unreadable entries are reported and left
  out.

- **A `tickCount` of a billion is clamped and said so.** Nothing bounded it, so it was an
  out-of-memory error on the way to building the list, or a billion-iteration walk-down before
  that. Upstream hangs on the same specification, so the limit is named in the diagnostic rather
  than hidden.

- **A mark tree nested past 64 groups is a diagnostic.** A group is the only construct that nests
  and it nests by recursion, so a machine-generated document a few thousand deep was a
  `StackOverflowError` — an `Error`, caught by nothing typed, unrecoverable on Kotlin/Native. The
  deepest nesting in the whole fixture corpus is three.

- **A projection is readable from a mark's `encode` block.** `SignalScope.withDatum` carried every
  field except the projections, so `{"size": {"signal": "4 * geoScale('p')"}}` reported
  "projection 'p' is not defined" once per compile and left the channel unset. Every geo fixture
  passed, because none of them calls a geo function inside `encode`.

- **A sort over a field some rows lack no longer throws.** A comparator that answers zero for a pair
  it cannot order is not a total order, and the JVM's TimSort detects that and throws
  `IllegalArgumentException: Comparison method violates its general contract!` once there are 32 or
  more items. Both sort paths fall back to declaration order, which is the order stability would
  have given anyway.

- **A missing scale is reported once, not once per row.** A 10,000-row mark produced 10,000
  identical ERROR diagnostics and buried everything else; `reportOnce` was in the same file, unused
  by any of the three per-datum reports. Unparseable colours and unpositionable scales are reported
  once too.

- **Diagnostic codes say what happened.** Codes are a documented public contract, and several were
  semantically wrong: a *load failure* reported `VEGA_PARSE_UNKNOWN_PROPERTY`, which says the
  document is at fault; an image with no size reported `VEGA_EXPORT_IMAGE_UNRESOLVED`, a code about
  export that a compile has no business emitting; "the scale was not built" reported
  `VEGA_SCALE_UNSUPPORTED_TYPE`, which says this engine has no such scale type. Around thirty
  unrelated runtime conditions shared one parse code. See the Added section for the eight new ones.

- **`autosize.contains: "padding"` with padding wider than the size says so.** The plotting area
  comes out negative — upstream's does too, probed — and nothing said why the chart was empty.

- **One font-weight parser, not three.** `MarkEncoder`, `TitleBuilder` and `GuideStyle` each had
  their own and they had drifted: `bolder` was 700, 800 and 700, and two of the three read a numeric
  string while the third answered 400 for it, so a title and an axis label written with the same
  weight came out at different weights. `bolder` and `lighter` are **relative** to the inherited
  weight, which in a chart is the initial 400, so CSS Fonts 4's table gives 700 and 100; `lighter`
  was 300 everywhere, a value the table does not contain.

- **A non-position scaled channel reads its `signal`.** One of the two readers of the same rule had
  drifted and skipped it, so `{"scale": "ord", "signal": "…"}` on a non-position scale silently lost
  its value while the position path eighty lines away read it.

- **`fontStyle: "oblique"` says what it drew.** It is a slanted upright face, which no text engine
  here can ask a platform for; it was drawn upright and silently, in a file where `strokeCap` and
  `strokeJoin` report an unknown value two hundred lines down.

- **`facet.aggregate.cross` is capped.** The product of the dimensions' distinct values was
  unbounded: two columns of a thousand values each is a million fully compiled cells.

- **Thirty orphaned documentation blocks were reattached to the code they describe.** Each was a
  KDoc block sitting above a *different* declaration than the one it documents — the function it
  belonged to had moved and the comment had not — so `AxisBuilder`'s explanation of `validTicks`
  documented `offsetOf`, and `SpecCompiler`'s class comment described a compiler that could not
  draw legends or titles, which two thousand lines had been contradicting for several releases.

- **A group's opacity paints its panel and is not inherited, in the SVG export too.** `opacity` was
  emitted on the `<g>` container, which composites the whole subtree — so a half-opaque group drew
  its opaque children at half, and an `opacity: 0` group made its children **vanish**. Every canvas
  renderer here does the opposite and documents it, and so does upstream: probed by rendering a
  half-opaque group with an opaque child through `view.toSVG()`, which comes back
  `<path class="background" … opacity="0.5"/>` with the child untouched. The renderer's own test
  said all this and could not see it, because "exactly one element carries an opacity" was
  satisfied by the container just as well as by the panel.

- **`zindex` decides paint order everywhere, not only in the export.** `paintOrder` reorders a
  mark's items by their `zindex`, its own comment says every renderer has to apply it, and only
  `SvgRenderer` did. So a raised mark was on top in an exported file and underneath on every screen
  — and the hit index, which numbers its entries in walk order, sent the tap to the mark drawn
  *below* it. The scene walk, the hit index, the Android canvas renderer, the Compose Multiplatform
  walk and the Swift walk all reorder now.

- **Four ways a mark could be visible and untappable.**
  - A fully transparent **group**'s children were pruned from the hit index while every renderer
    drew them.
  - The broad phase was gated on `boundsTolerance` alone, so `strokeTolerance` was unreachable past
    it: `Mouse` has a `boundsTolerance` of 0, and on an axis-aligned rule — whose bounds *are* its
    stroke width — its 2 px tolerance was effectively zero.
  - A fill was picked with the **even-odd** rule while every renderer painted it with **nonzero
    winding**, so a tap on the visibly solid centre of a self-intersecting outline missed.
  - A `path` with `fillOpacity: 0` lost its interior. `isPointInPath` never looks at alpha, so a
    transparent fill is the idiom for an invisible tap target; `hitsRect` already said so and cited
    upstream, and the branch beside it was testing `isVisible`.

- **A multi-line label is exported where it is drawn.** The export set a `dominant-baseline` on the
  `<text>` element, which is a **per-line** instruction, so a three-line label with
  `baseline: middle` had each line centred on its own `y` and the block came out
  (n − 1)·lineHeight/2 lower than every canvas renderer draws it. The one offset from the anchor to
  the first baseline is folded into `y` now, which is the rule the canvas renderers apply and the
  shape upstream emits.

- **A `javascript:` link does not survive into an export.** An `href` is a specification-controlled
  string and this project's threat model treats a specification as untrusted, so escaping it and
  writing it through produced a file that is clickable the moment a browser opens it. Upstream
  sanitizes the same string — `loader.sanitize(href, {context: 'href'})`, whose allowlist is now
  transcribed in `isSafeHref` — and refuses this set; a refused link is reported as an
  `SVG_HREF_REFUSED` warning and the mark is still drawn.

- **A control character no longer makes an export unreadable.** XML 1.0 has no way to write a C0
  control character, not even as a numeric reference, so one stray byte in a data-derived label
  made the whole file malformed and a viewer refused all of it.

- **A group with a fill and no size paints nothing**, which is what upstream paints — `M0,0h0v0h0Z`,
  probed. Falling back to the clip rectangle filled the whole clipped region instead.

- **A quoted font family keeps its comma.** `"Foo, Bar", serif` names two families and was being
  split into three, none of which a host could answer.

- **One gradient definition, however many marks share it.** The `<defs>` key carried the node's
  bounds, which the emitted `<linearGradient>` does not mention, so two marks of different sizes
  with the same gradient produced two identical definitions.

- **The canonical snapshot can see eight more things.** ADR-0008 calls it the level-2 regression
  check, and a property it does not write is a property it cannot check: `dashOffset`, `miterLimit`,
  the four **per-corner** radii, `blendMode`, a symbol's custom path, an image's `align`, `baseline`
  and `smooth`, an item's `href` and its `zindex` were all invisible to it. It also recorded
  `effectiveCornerRadius`, which is a different clamp from the `Corners.of` every renderer draws
  with — a number nothing draws.

- **`window` annotates duplicate rows separately.** Results were keyed by the row itself, and
  `VegaValue.Obj` is a value class over a map that compares structurally — so two rows that happen
  to be identical were one key and collapsed onto the last one's answer. `[{v:1},{v:1}]` with
  `ops:["sum"]` came back as `[2, 2]` where upstream answers `[1, 2]`, and `row_number`, `lag` and
  `lead` were equally wrong. Duplicate rows are ordinary; none of the fourteen replayed window
  vectors has one. It groups positions now, which is what `stack` already did and says why.

- **The aggregate cell sorts values into upstream's three boxes, not two.** `add` is
  `if (v == null || v === '') { ++missing; return } if (v !== v) return; ++valid`, and every
  boundary in it was in the wrong place:
  - the **empty string is missing**, and was entering the numeric list as a valid 0 — a dirty CSV
    column of `""` dragged every mean toward zero;
  - a **NaN is in neither box**, and was being counted as missing;
  - a value that merely *coerces* to NaN is **valid** and poisons what is computed from it: the sum
    of `[1, "abc"]` is NaN upstream and was 1 here, a total that silently omitted the row it could
    not read;
  - an **infinity is valid** and takes the extreme, and was being filtered out;
  - `m.valid ? … : undefined` guards every numeric operation including `sum`, so a group with no
    valid value has **no sum property at all** rather than a 0 — which passes an `isValid` filter
    that upstream's answer does not. A comment here claimed upstream reports 0; it was probed
    false, and the code followed it.
  - `variance`, `stdev` and `stderr` need two values and `variancep` and `stdevp` need one, so the
    sample forms over a single value are absent rather than NaN;
  - `min` and `max` track the extreme over the **raw** values with JavaScript's `<`, so a string in
    a numeric column never displaces a number rather than making the answer NaN;
  - `argmin` and `argmax` reach their answer by a different route — `extentIndex` over every stored
    row — and an infinity or a non-numeric value takes part in it, where both were skipped.

- **`joinaggregate` can compute a confidence interval.** No bootstrap closure was passed, so
  `Measure.compute` had nothing to ask and `ci0`/`ci1` wrote null onto every row of the group,
  silently — and the error bars they exist for were drawn nowhere.

- **`stack`'s `center` offset runs one cursor.** Upstream's `stackCenter` starts at
  `(max - sum) / 2` and advances by the absolute value; splitting it into positive and negative
  cursors, as `zero` correctly does, made a group holding both signs grow in two directions from
  the centre line. `[3, -5]` spans `[0,3]` and `[3,8]` upstream and spanned `[0,3]` and `[0,-5]`
  here; the one committed `center` fixture is all-positive, where the two rules agree. And
  `normalize` over a group summing to nothing produces NaN — `1/0` times `0` — which draws nothing,
  where guarding the zero drew a band of zero-height rectangles.

- **`pie` no longer corrects its input.** A negative value runs backwards over its neighbour
  upstream and a zero total divides by zero; taking the absolute value and guarding the total
  showed a plausible chart where upstream shows a broken one the reader would have asked about.

- **Eight more places a transform disagreed with upstream**, each probed:
  - `bin` writes **NaN** for a value that coerces to NaN and null only for a genuinely absent one —
    the very distinction the file's own header stresses for the out-of-extent infinities;
  - `extent` lets an infinity take the extreme and then answers `[null, null]` with a warning, as
    upstream's "Infinite extent" does, rather than reporting the finite extent of a column upstream
    refuses to report one for;
  - `pivot` keeps a **null** key, which upstream names `"null"`, and orders its columns by value
    rather than lexicographically by name — `1, 2, 10` and not `1, 10, 2`;
  - `ntile` and `nth_value` are refused without a parameter greater than zero, as upstream refuses
    them, instead of defaulting to 1 and turning `ntile` into a column of ones;
  - `countpattern` compiles its pattern through the **ECMA-262 engine** the rest of the codebase
    adopted for exactly this — it is the one transform that compiles a pattern a specification
    wrote, and Kotlin's `Regex` *throws* on `x{` and `[]`, two ordinary browser patterns;
  - a force `link`'s `distance` and `strength` expressions read the **link's row**, as upstream's
    `d => v(d, _)` does; they were evaluated against nothing, so `datum.weight` was NaN for every
    link and the springs all came out the same length;
  - `compareFieldValues` is `vega-util`'s `ascending`, so a pair it cannot order — a string against
    a number — compares equal and keeps its place, rather than falling back to a lexicographic
    comparison that looked more helpful and ordered a mixed column differently;
  - a hierarchy layout's `as` names what it names, positionally, instead of being ignored unless it
    was longer than the default list.

- **The voronoi predicate's scratch buffers are confined to one triangulation.** `Orient2d` was a
  singleton holding one set of expansion buffers, so two layouts running at once — two compiles on
  two threads, which is what the controller's async path does — wrote into each other's expansion
  and produced a triangulation that is not a triangulation of either point set.

- **`Dataflow.kt` stops advertising an engine that does not exist.** Its `ChangeSet`/`TupleId`
  contract has no consumer outside its own tests, and `TupleId` promised that identity is
  "preserved across incremental updates" — which nothing implements. Marked
  `@InternalAsterVegaApi`, so a host reading the surface finds a plan rather than a promise.

- **A signal is a dependency even when a datum field shares its name.** `collectSignals` removed a
  member-access property name **by name** from the whole expression, so `"year == datum.year"`
  reported no dependency on the signal `year` at all. `DataflowOrder` then resolved the expression
  before the signal existed and never re-evaluated it after: a slider bound to `year` moved
  nothing, with no diagnostic, because from the compiler's point of view the expression did not
  mention it. The removal was undoing something that never happened — the AST walk does not descend
  into a non-computed property — so it is gone.

- **`datetime(x)` with one argument is a time value, not a year.** Upstream's codegen is
  `datetime: 'new Date'`, so `datetime(datum.epochMillis)` — a documented idiom — is that instant.
  Reading the first argument as a year regardless made it the year 1.6 trillion, `toInt()`
  saturated on the way, and `LocalDate` threw an `IllegalArgumentException` out of a public
  compile, which every catch site in the engine is too narrowly typed to see. A one-argument
  `datetime` is now a time value (a string is parsed, as `new Date` parses one), the calendar
  constructor starts at two arguments, and a year outside the calendar is an Invalid Date rather
  than an exception. `utc(x)` keeps reading a year, because `Date.UTC` does.

- **Three ways a specification could crash the host are diagnostics.** `data()` with no arguments
  raised `NoSuchElementException`; `regexp('(')` raised the regular-expression engine's own syntax
  error from a field initializer — the exact failure class its KDoc says that engine was adopted to
  end — and a deeply nested expression raised `StackOverflowError`, which is an `Error`, is caught
  by nothing typed, and is unrecoverable on Kotlin/Native. All three now produce the ordinary
  diagnostic, and the parser counts its depth (256 levels; the deepest expression in the whole
  fixture corpus nests eight).

- **`quantileNormal(0)` is −Infinity.** `erfInverse` returned from its last branch instead of
  falling through to upstream's `p * x`, so the sign of the input was lost: the lowest rank of a QQ
  plot was drawn at the *top* of the chart, and `quantileLogNormal(0)` was +Infinity where upstream
  answers 0.

- **`span` of nothing is 0.** Upstream's is `(+a[a.length-1]) - (+a[0]) || 0`, and the `|| 0` is
  the whole of it. `span(domain('x'))` over a scale with no data answered NaN, which poisons every
  layout signal computed from it.

- **`utcOffset` answers a date.** It was registered twice and the second registration won,
  answering a number — so `isDate(utcOffset(…))` was false while `isDate(timeOffset(…))`, its
  documented twin, was true.

- **A `Date` behaves like an object, because it is one.** `datetime(0)` is truthy (it was falsey,
  so `if(datum.when, …)` took the wrong branch for exactly one instant in history and for every
  date that failed to parse), `'' + datetime(t)` is the date string rather than the epoch
  milliseconds a tooltip was showing, and a date is never `==` or `===` a number. Relational
  comparison stays numeric, because `<` asks `ToPrimitive` for the number hint.

  `String(date)` is ECMA-262 21.4.4.41's form — `Thu Jan 01 1970 01:00:00 GMT+0100`, in English,
  in the host's own zone as a browser prints it. One thing is missing and the specification is why:
  an implementation *may* append a parenthesised zone name and V8 does, and producing it needs CLDR
  data that is not available on every target this engine compiles for.

- **Eleven more places the expression engine disagreed with JavaScript**, each probed against the
  pinned upstream rather than reasoned about:
  - `sort()` is vega-util's `ascending`, so two strings compare lexicographically and everything
    else compares numerically. Comparing everything-but-two-numbers as text sorted an array of
    dates spanning a digit-count boundary backwards.
  - `clampRange` normalizes a descending range before clamping it, which is what a reversed
    y-domain feeding pan or zoom hands in.
  - `inrange`'s third and fourth arguments are **inclusivity** flags, and were being ignored — so a
    brush that deliberately excluded its upper end selected the row sitting exactly on it.
  - `timeSequence` with a step that is not a positive whole number is `[]`. A negative one walked
    *downwards*, away from `stop`, and emitted a hundred thousand timestamps before the guard
    stopped it.
  - `ToInt32`/`ToUint32` wrap modulo 2^32 rather than saturating at ±2^63, so `1e20 | 0` is
    1661992960 and not −1.
  - A property key is a **string**: `[10,20,30][1.5]` and `[10,20,30]['01']` are undefined, where
    coercing the key to a number read element 1 for both.
  - An array or a date against a primitive primitivizes to a **string**, so `[1,2] == '1,2'` is
    true where comparing numerically said false.
  - `clamp(5, 10, 0)` is 10: upstream composes `max(min, min(max, value))` and does not correct
    swapped bounds.
  - `hypot` scales by the largest magnitude before squaring, so `hypot(1e200, 1e200)` is
    1.41e200 rather than Infinity.
  - `round` keeps a negative zero and no longer rounds the tie's lower neighbour up:
    `round(0.49999999999999994)` is 0.
  - `parseInt('0xFF')` is 255 (an omitted radix is 0, not 10, and 0 means the prefix decides) and
    `parseFloat('Infinity')` is Infinity.
  - `format(null, spec)` is the string `"null"`, as its `timeParse` sibling already was.

- **Three JavaScript string escapes were silently producing the wrong text.** The lexer's fallback
  is the identity, so `'\x41'` came out as `"x41"`, `'\u{1F600}'` as `"u{1F600}"`, and a line
  continuation put a newline into the string. And a **no-break space** is JavaScript whitespace and
  is not Kotlin's, so an expression copied out of a rendered web page failed with
  `Unexpected character ' '` — two characters that look identical in a diagnostic. U+FEFF, which is
  what a UTF-8 byte-order mark decodes to, is whitespace now for the same reason.

- **A field a row does not have is `undefined`, not `null`, and the difference is the chart.**
  `Number(null)` is 0 and `Number(undefined)` is NaN, so a filter `datum.x < 10` over rows that
  have no `x` at all **kept** every one of them where upstream drops them — ordinary dirty data,
  the opposite answer, and no diagnostic. The value model carried one absent value and it coerced
  as `null`.

  Everything that follows from the distinction follows now: `'' + datum.missing` is `"undefined"`
  rather than `"null"`, `undefined == null` is true while `undefined === null` is false,
  `isDefined` answers **true** for a field that is present and null (its whole job, and it
  answered false), `isValid` and `toNumber`/`toString`/`toBoolean` screen with the loose
  comparison that covers both, and `indata` answers `undefined` for a value no row carries rather
  than a `null` standing in for one.

  One function must *not* treat them alike and now does not: upstream's `timeParse`/`utcParse`
  wrapper screens with `value === null`, so `timeParse(datum.nul, '%Y')` is the string `"null"`
  and `timeParse(datum.missing, '%Y')` is null. Probed rather than reasoned about, like every
  other expectation in `UndefinedSemanticsTest`.

  Reading a property **of** nothing still differs from upstream, deliberately and now in writing:
  JavaScript throws a `TypeError` and takes the whole chart down, and this answers `undefined`.

- **A bracket followed by a dot reads the field behind it.** `"field": "coordinates[0].lat"` —
  the ordinary shape of a GeoJSON-derived row — resolved to nothing at all, so the mark was not
  drawn and nothing said why. `parseFieldPath` added an empty segment for the `.` after the
  closing bracket, and a lookup through an empty segment misses by construction.

  The whole function is now a transcription of vega-util's `splitAccessPath`, which is what every
  upstream field accessor is built from, rather than a re-derivation of what the notation looks
  like it means. The re-derivation was wrong in three more ways the original explains: `a..b` and
  `.a` were two segments and one where upstream reads one and one, a quoted bracket ended at the
  next `]` rather than at its own closing quote (so `a["b]c"]` was cut in half), and a quote was
  honoured anywhere in a bracket rather than only where upstream honours one. One divergence is
  kept deliberately: upstream *throws* on an unterminated bracket or quote, and a field path is
  data — often pasted data — so the remainder becomes a literal segment and the lookup misses.

- **Numbers in emitted JSON are written the way `JSON.stringify` writes them.** `VegaJson.write`
  fell back to the platform's `Double.toString` for any value that was not a small integer, under
  a comment claiming JavaScript's rules. The platform's rules are not JavaScript's — it switches
  to exponential notation at 10^7 rather than 10^21, and writes `1.5E-6` where JavaScript writes
  `0.0000015` — and, being the platform's, it answers differently on each Kotlin/Native target, so
  the same value was not even the same text on two of the five hosts that emit it. It goes through
  `Decimals.jsString` now, which is the same specification the rest of the engine prints with.

- **A signal and a dataset say what they cannot honour.** Every other block in `SpecParser` names
  the properties it consumes and reports the remainder; these two read the handful they knew and
  dropped the rest in silence, against the class's own "nothing is silently dropped". Four
  properties were disappearing: a dataset's `on` triggers and `async`, and a signal's `react` and
  `push`. `push` is the expensive one — this repository's own Vega-Lite compiler emits
  `"push": "outer"` for a faceted selection, and a group's signals are resolved into a copy of the
  enclosing scope, so the name was shadowed inside the group and the outer signal never changed.
  Each now says that, by name, with its JSON path.

- **`%s` floors the epoch instead of truncating it.** d3-time-format writes
  `Math.floor(+d / 1000)`; a division that truncates toward zero is a second late for every
  instant in the half-second before an epoch second, which before 1970 is the whole axis. `%Q` and
  `%s` also write their number through `Decimals.jsString` now rather than through a `Long`, for
  the same reason the JSON writer does.

- **A `SpecParser` may be used twice.** It is public, its name is a verb, and it accumulated: a
  second specification came back carrying the first one's diagnostics and read the first one's
  `config` for any block it did not set itself. Each `parse` and `parseJson` starts from nothing.

- **One specification names one font on every host.** A specification writes a CSS stack —
  `"Noto Sans, Chart Sans"` — and `TextStyle.fontFamily` carries it whole, so each text
  engine had to split it. Each did something different: the Compose Multiplatform engine
  read the whole stack and let any entry match, the Apple renderer offered its resolver the
  **first** entry only and nothing at all when that was a generic, and the Android view
  offered the **unsplit string**. A host that registered one face under one name got that
  face on one renderer and the platform default on the others, from the same chart.

  `FontStack` in `vega-scene` is the rule once — split on commas, trim spaces and quotes,
  offer each entry in order until one answers — and all four renderers read it, the Swift
  one through the exported framework rather than restating it.

  **A generic is now offered to the resolver too.** The Apple renderer skipped one, on the
  stated grounds that answering it would draw differently there than on the Kotlin
  renderers; the Compose registry is consulted before its generic mapping, so it was
  already answering them and the reasoning had it backwards. A host that registers
  `sans-serif` has said what its sans is. (#123)

- **Every renderer says which font it could not find.** `unresolvedFontFamilies` is on the
  Android and Apple text engines now, as it has been on the Compose Multiplatform one:
  both platforms answer an unknown family with a default face, which is legible, is not
  what the specification asked for, and was **silent**. Two of the three renderers falling
  back without saying so is a fair part of why they disagreed about reading a CSS stack for
  as long as they did. A stack that ends in a generic is not a miss — it asked for the
  reader's default and got it — and neither is a family the host answered, whatever face it
  answered with. (#123)

- **`ChartGestures.none` claims no touches, as it always said it did.** It documented
  itself as "the same as passing no session", and hit testing was gated on whether a
  session was present rather than on the gesture set — so a chart with a session and
  `.none` still took touches from the scroll view around it, which is the exact symptom
  0.2.0 fixed for the no-session case. A host following that sentence had to read the
  source to find out why its page stopped scrolling.

  One respect is deliberately *not* the same, and the documentation now says so: a reader
  using VoiceOver can still explore the chart and activate a mark, because activation goes
  through an accessibility action rather than a gesture and a session is what makes it
  work. Pass no session for a chart that is inert to everything. (#124)

### Performance

- **The Android draw path allocates per *pattern*, not per mark per frame.** A `DashPathEffect` and
  three lists for every dashed node, and a gradient shader plus two array copies for every
  gradient-painted node, were built on every frame — against a file header stating that it allocates
  nothing per mark. Both are cached by what they are built from, bounded, since both come from the
  specification rather than from the data.

- **The Compose Multiplatform draw path stops re-shaping text and rebuilding paths every frame.**
  `rememberTextMeasurer()` defaults to eight cached layouts, which is the number of labels on a small
  axis, so every run past the eighth was shaped from scratch per frame; and each mark's path was
  re-transformed per frame although the `PathData` behind it is the same object. Both are cached, the
  second keyed on the path's identity.

### Internal

- **One contract, checked on every host.** `scripts/host-parity.py` checks a seam *exists* on
  each surface; it cannot check that two engines agree about what to do with it, and that is
  where every host defect in this release came from — three engines read a CSS stack three
  different ways behind a matrix that said all four had `fontResolver`. A signature does not
  say how a stack is read.

  `test-fixtures/host-conformance` writes the agreement down instead: one golden per seam, read
  by every engine that implements it, in the shape `test-fixtures/scene-walk` already uses. Three
  seams so far — how a font stack is walked, when an image resolver is asked for a url, and where
  a scene is placed in a slot. `scripts/host-conformance.py` (a `check.sh` gate) checks that each
  golden is read on every side, because a golden wired to one host leaves the disagreement as
  invisible as before while looking like coverage.

  Designing it found a fourth divergence before a line of it ran: the Compose Multiplatform engine
  called `fontFamilyResolver` once with the whole stack where the other two called it per entry, so
  the same host closure had to be written differently per renderer. It now matches.

  The conformance goldens are declared as test-task inputs, alongside the differential and
  scene-walk fixtures. Found the hard way: a deliberately broken `placement.txt` was saved and
  `jvmTest` reported *up to date*.

## 0.4.0

### Changed

Three of these break a Swift host that compiled against 0.3.0. They are first for that reason.

- **Fifty engine-internal members now require opt-in.** `@InternalAsterVegaApi` marks the
  declarations that are `public` only because Kotlin has no cross-module `internal` — the
  dataflow's `publishesSignal`, the expression evaluator's scope hooks, one AST accessor.
  Calling one from outside the engine is now a compile error unless you
  `@OptIn(InternalAsterVegaApi::class)`, and opting in means accepting that they may change
  in a patch release. **Source-breaking** for anyone who was calling them; nothing in this
  repository was.

- **`ChartSession.selectedNodeIds` hands out numbers**, `Set<Int64>` rather than
  `Set<AnyHashable>`. It carried opaque boxes because nothing could unwrap one, with a comment
  saying "opaque is enough here — the set is handed straight back to the engine". That stopped
  being true, so a host can read a selection now instead of only relaying it. **Breaking for a
  Swift host that stored the old set**; `ForeignNodeId.setOf(values:)` converts back where the
  engine wants its own form.

- **`ChartSession` exposes `hoveredNodeId` and `focusedNodeId`**, as numbers. Neither was
  reachable at all: the session published a selection and nothing else about the interaction
  state, so a host drawing its own hover affordance had to reach past it to the controller. (#120)

### Added

- **A Swift host can read a node id that arrived boxed, and a datum whose shape it does
  not know.** `ForeignNodeId` and `ForeignValue` join the five `Foreign*` accessors that
  already exist for the same reason: a Kotlin `value class` has no Obj-C representation, so
  the questions a host asks are given plain functions instead.

  `ForeignNodeId` covers the positions where a `SceneNodeId` **boxes** — a nullable one
  (`InteractionState.hoveredNodeId`, `focusedNodeId`) and a collected one
  (`ChartSelection.nodeIds`) — with `valueOrNull`, `values`, `setOf` and `noneValue`. A
  *non-null* id was already readable and is deliberately not wrapped: Kotlin/Native unwraps
  it to an `Int64`, so `SceneNode.id` crosses today under the name **`id_`**, renamed to
  avoid Obj-C's `id` keyword.

  `ForeignValue` gives `keys`, `count`, `at`, `get` and a `kind` naming the shape, plus
  `string`, `number` and `boolean` that **do not coerce** — `asString` renders a number, a
  boolean and an object all as text, so a host could not tell a field that held `"3"` from
  one that held `3`. (#120)

- **A host can draw its own legend for a banded scale**, through `ForeignScale`. The numbers
  a legend is built from — where the buckets cut, what labels them, where a value sits along
  the bar — are declared on `BinnedScale` with **default bodies**, and an Obj-C protocol
  cannot carry a default, so Kotlin/Native left them out: a `QuantizeScale` reached Swift
  with `domain`, `name` and `invertExtent` and nothing else. Nothing inside the engine
  noticed, because the engine draws its own legends. `legendExtent` is split into two
  functions rather than crossing as an opaque `KotlinPair`, and an unbounded bucket end
  answers **null** rather than zero — a cut point at 30 says nothing about how far below it
  the first bucket reaches. (#120)

### Internal

Not changes to the engine, and listed because they change what a review can catch.

- **What does *not* reach a foreign host is enumerated and gated.**
  `scripts/foreign-coverage.py` reads the committed ABI dumps against `foreign-api.txt` and
  records every public member with no foreign counterpart, each with a reason: 50 marked
  internal, 27 reachable through a `Foreign*` accessor, **0 unexplained**. A member that
  stops crossing without a recorded reason fails the build and says what to do about it.
  Three adopter reports in a row were that one defect — a type crossing while the part worth
  reading stayed behind, with nothing failing.

- **The host matrix in `README.md` is derived rather than asserted.**
  `scripts/host-parity.py` checks every seam against every host's recorded surface, and
  `check.sh` runs it. The table was written by hand, and both seams an adopter reported
  missing were absent from a host while it called the shape deliberate.

## 0.3.0

### Changed

Three of these change what an existing host sees. They are first for that reason.

- **A locale gets one date, whichever grammar the document was written in.** Vega derived
  a bucketed axis's format from the locale's own `%x`; Vega-Lite read only the field
  *order* off it and rebuilt the entry from its own directives. So the same `VegaLocale`
  produced `21-05-2026` on a Vega chart and `21 mei 2026` on a Vega-Lite one, Spanish lost
  its `de`, and `%b %d, %Y` came back without its comma — the derivation
  `timeUnitSpecifierOverrides` documents, disagreeing with the code that implemented it.
  Both paths now derive through `VegaLocale.timeUnitSpecifiers`.

  **This changes what a host-supplied locale draws.** A locale whose `%x` is numeric now
  gets a numeric month where Vega-Lite's own table would have written a name — `21-05-2026`
  rather than `21 mei 2026`. A host wanting the name states `year-month-date` in
  `timeUnitSpecifierOverrides`, which is the same lever as before and now wins for *every*
  key it names: a stated `month-date` used to be honoured on a Vega chart and silently
  dropped on a Vega-Lite one.

  `VegaLocale.EnglishUS` states its tables rather than deriving them, so it is untouched and
  all 283 Vega-Lite fixture comparisons are unmoved. `dateFieldOrder` remains, for a host
  that wants the order without the pattern; it is no longer how a specifier is derived. (#97)

- **The Android view centres a chart, as the other three renderers already did.** A scene is
  scaled to fit, so a slot of a different aspect ratio leaves a strip along one axis;
  `VegaChartView` put all of it on the right and the bottom while the Compose Multiplatform
  and SwiftUI charts split it evenly, so the same chart in the same slot sat in a different
  place depending on the host. **This moves existing Android charts** — by half the slack, and
  only where there is any: a view measured at its own preferred size is unmoved, which is most
  of them, and a chart given `match_parent` on an axis shifts by half of what was empty.
  Drawing, hit testing and the accessibility frames all read the one `placement()`, so they
  moved together rather than needing four edits. (#99)

- **The placement type moved to `vega-scene`**, the module every renderer already depends
  on, as `ScenePlacement`. `dev.aster.vega.compose.mp.ChartPlacement` is a typealias now, so
  Kotlin source keeps compiling; code already compiled against the old class will not link,
  since a typealias is resolved at the call site.

  It is **not** called `ChartPlacement` in Kotlin, and that is the Obj-C boundary rather than
  taste: `vega-scene` is exported to the Apple framework under a flat namespace, so that name
  would collide with the Swift package's own `ChartPlacement` and every Swift host would fail
  with "'ChartPlacement' is ambiguous for type lookup". `@HiddenFromObjC` is Kotlin/Native-only
  and the file compiles for the JVM too. The Swift struct is unchanged. (#99)

### Added

- **A Kotlin host can tell a failed compile from one that has not happened**, through
  `ChartState.failure`. A compile that produces no scene deliberately keeps the previous
  snapshot — a reader holds on to what they were looking at — which left nothing on the
  state saying a failure had occurred, so a host wanting its own "this chart cannot be
  drawn" copy had to infer it from the diagnostics. That inference is wrong in both
  directions: `PARSE_NOTHING_TO_DRAW` is INFO by deliberate choice, and a document can
  report errors and still draw a chart the reader can see. It is now stated: the first
  ERROR or FATAL diagnostic's message where a compile drew nothing, a plain sentence
  where nothing said anything more useful, and null again after the next compile that
  draws or any `setScene`. The Swift `ChartSession.failure` has carried exactly this
  since 0.2.0, so the same host logic is now expressible on both platforms. `ChartState`
  gains a fourth constructor parameter, defaulted, which changes its Obj-C initialiser
  selector; nothing in this repository constructs one. (#100)

- **`vega-compose` reaches the seams the view underneath already had.** `VegaChart` took a
  controller or a scene, a modifier and `onEvent`, and nothing else — so a host on the
  View-based artifact could not register a font family, raise the accessibility threshold or
  turn off the built-in tooltip, though `VegaChartView` has all three and the Compose API is
  a wrapper around that very view. `fontResolver`, `accessibilityMaxExposedMarks` and
  `tooltipsEnabled` are now parameters on both overloads. `onEvent` stays last, and anything
  added later goes before it: Kotlin binds a trailing lambda to the final parameter, so
  `VegaChart(controller) { … }` means `onEvent` only while `onEvent` is last. Pinned by
  `CallShapeTest`, which `scripts/check.sh` now compiles — it never compiled any
  `androidTest` source set before, so four of them could have stopped compiling unnoticed.
  (#99)

- **The Android renderers have an image seam at all.** `AndroidCanvasSceneRenderer` has
  taken an `AndroidImageResolver` since it could draw an `image` mark, and nothing outside
  the module could set one — so every image mark on a View or Compose host resolved to
  nothing and reported `EXPORT_IMAGE_UNRESOLVED`. The fix that gave the Compose
  Multiplatform renderer a resolver for 0.2.0 reached the Swift view and that module, and
  not this one. `VegaChartView.imageResolver`, `onUnresolvedImage` and `clearImageCache()`
  are now public and forwarded through `vega-compose`'s `VegaChart`.

  A URL is asked of the resolver **once**, not once per frame, and a refusal is remembered
  too — without which a host that fetches would have been fetching on every frame of every
  pan. `onUnresolvedImage` is told once per URL, matching the Compose Multiplatform chart so
  the same host code works on either renderer; the per-frame diagnostic is unchanged, since
  it describes the frame that was drawn. (#99)

- **Every host can be told where its chart was drawn.** `onPlaced` reports the fit scale
  and the offset from the surface's top-left corner, as a `ScenePlacement`, for a host putting
  its own overlay on a chart or turning a point of its own into scene coordinates. The Compose Multiplatform
  and SwiftUI charts have had it since they existed; `VegaChartView` and `vega-compose`
  could not, because `ChartPlacement` was declared in `vega-compose-multiplatform` and a
  `View` cannot depend on a Compose module. `VegaChartView.placement()` is public too, for
  a host that would rather ask than be told.

- **A host can hand the Apple renderer its own font**, through `resolveFont` on
  `ChartSession`, `VegaChartView` and `CoreTextTextEngine`. Both Kotlin renderers have taken
  a resolver for this since before 0.2.0; the Apple side resolved a family name through
  CoreText alone, so an app bundling a face — which most design systems do — could only reach
  a chart by registering it process-wide with `CTFontManagerRegisterGraphicsFont` and hoping
  the name matched. That works and is the wrong shape: process-wide state to configure one
  chart, invisible at the call site, and one specification drawing in different faces on
  different platforms.

  The resolver is consulted for a **named** family and not for a generic one — `sans-serif`
  asks for the reader's default, and answering it would reintroduce the difference this
  removes. It returns a *face* at any size; the chart's own size, weight and slant are
  applied to it. `VegaChartView` defaults to the session's resolver rather than asking for
  the closure twice, because the layout was measured with it: painting a face the boxes were
  not measured for puts every label off its baseline. (#106)

### Fixed

- **The README no longer teaches the Vega-Lite fallback that 0.2.0 removed**, and the
  demo no longer performs it. `converted.vegaJson ?: text` hands a document Vega-Lite
  could not compile to a parser that only understands Vega, which answers with
  complaints phrased in the wrong grammar and buries the one diagnostic that says what
  happened. That is the defect fixed in `ChartSession` for 0.2.0 (#61) — and the
  repository went on documenting it in `README.md` and doing it in both of the demo's
  conversion paths, so a Kotlin host following either reintroduced it. The example now
  branches on a null `vegaJson` and surfaces `converted.diagnostics`; the demo's paste
  screen reports the refusal through a new `PasteReport.refused` without asking the
  runtime, and its bundled-asset path stops the same way. A sentence now says what the
  nullability actually means, since a null `vegaJson` and a `wasVegaLite` of false look
  identical at the call site and only one of them is safe to pass on. Pinned by
  `VegaJsonFallbackTest`, which scans the main sources, the Swift sources and the
  README. (#101)

- **A facet header follows the reader's language whether or not the field was bucketed.**
  `Facet.headerText` threaded the locale for a field carrying a `timeUnit` and wrote
  upstream's `%b %d, %Y` outright for one that did not, three lines apart — so a grid
  split by a bucketed field was captioned in the reader's language and a grid split by a
  plain temporal field was captioned in American English. Both branches now read the same
  `year-month-date` entry, so a bucketed date and an unbucketed one cannot be captioned
  differently. `VegaLocale.EnglishUS` is byte-for-byte unchanged, which is what leaves the
  283 fixture comparisons still. (#98)

- **A date's suffix marker is no longer dropped with the field after it.** A language that
  writes a marker after each number — `%Y年%m月%d日` — derived its year-month form as
  `%Y年%m`, losing the `月`, so a bucketed axis read "2026年08". Dropping a field takes one
  adjacent separator with it, and which one depends on what the separator is for: text
  standing *between* two fields goes with the dropped one, and a marker belonging to the
  field before it stays. The two are told apart by whether the separator carries a letter
  and is attached with no space in front — which keeps `%b %d, %Y` giving `%b %Y`, keeps
  `%d-%m-%Y` giving `%d-%m`, and keeps Spanish `%e de %B de %Y` giving `%e de %B`, none of
  which move. No bundled locale is written with markers, so this only ever affected a
  host-supplied one.

### Internal

Not a change to the engine, and listed because it is a change to what a review can catch.

- **The Swift package's own API is snapshotted**, in `swift/AsterVegaRender/swift-api.txt`,
  from the symbol graph the compiler emits. `foreign-api.txt` covers what Kotlin exports to
  Obj-C; `VegaChartView.init` and `ChartSession` are Swift source and were in no snapshot at
  all, leaving them to `CallShapeTests` — which pins the shapes somebody thought to write
  down, and missed a rebound trailing closure and a missing font seam. Run by
  `scripts/swift-test.sh`, so `check.sh` covers it.

## 0.2.0

### Fixed

- **The Swift package builds on macOS again.** 0.1.0 declared
  `platforms: [.macOS(.v13), .iOS(.v16)]` and shipped an XCFramework carrying the
  two iOS slices and nothing else, so a consumer running `swift build` on a Mac —
  a plain command-line build, an Xcode preview, a package-scheme check — got `no
  such module 'AsterVega'`. iOS itself was unaffected: both slices were there, and
  an iOS Simulator build of the published package succeeds. The release XCFramework
  now carries all three slices; the debug one stays iOS-only, so
  `scripts/ios-demo.sh` keeps its fast path — the exclusion was written for that
  speed and was applied to what ships as well, which is the whole of the defect.
  The release workflow now asserts the slice list against the platforms the
  manifest promises before it tags, because nothing in the build compared the two:
  the artefact was correct by iOS's standards and wrong by the manifest's, and only
  a consumer found out. (#56)

- **A SwiftUI chart with no session installs no gestures**, and one with a session
  says which. Every handler already returned early without a session, but
  `DragGesture(minimumDistance: 0)` claimed the drag on touch-down anyway — so a
  chart in a horizontal scroll view inside a scrolling page stopped both from
  scrolling, where the same host code on Compose scrolled both ways. The button
  trait and `accessibilityRespondsToUserInteraction` are now gated on the same
  condition as the action they advertise, so VoiceOver no longer offers an
  activation that does nothing. And the tap is a `SpatialTapGesture` separate from
  the pan, so `VegaChartView(gestures: .withoutDrag)` keeps taps, hover and
  tooltips without claiming a touch. (#72)

- **The Apple renderer no longer paints a label the axis deliberately hid.** An
  overlapping axis label is hidden at zero opacity rather than removed, and the
  Swift walk guarded on `visible` alone — so the node reached the text branch,
  `brush` answered nil, and `CoreTextDrawing` read that as a paint it could not
  express and drew black. Measured on `label-overlap.vg.json`: 43 labels, 24
  hidden, 43 drawn where the Compose renderer drew 19. The Compose walk's
  zero-opacity guard is now mirrored here, so every mark type is covered and the
  two walks are back in step; the text branch also declines a run with no paint at
  all, and a nil brush is no longer painted. (#71)

- **A Vega-Lite document that will not compile is reported, not reinterpreted as
  Vega.** `ChartSession` fell back to the unconverted text, which a parser that
  only understands Vega then complained about in the wrong grammar's terms —
  measured on a refused construct, three diagnostics about `data`, a missing
  size and nothing to draw, a 0×0 scene published as the chart, and `failure`
  nil. The conversion's own diagnostics are now the chart's, `failure` says why,
  and the chart on screen is left alone. Text that is not Vega-Lite still reaches
  the Vega parser untouched. (#61)

- **A resize only recompiles a chart that asked for one, and no longer on the
  calling thread.** `VegaChartController.containerSize` recompiled
  unconditionally, so a host reporting its layout size on every resize paid a
  full compile per step of a drag for every chart on the page — including every
  chart that states its own width and height. It now skips the compile when the
  loaded document never reads `containerSize()`, and `setContainerSizeAsync` does
  the same work off the calling thread. (#59)
- **`ChartSession.settle()` waits for everything queued, not only the compile.**
  Work `serialised` deferred was started as a task nobody held, so a caller that
  set `containerSize` during a compile and then settled returned before the resize
  had run. The queue is a chain now and `settle()` loops until it is empty. The
  compile is queued with everything else, closing a narrower hazard where a load
  ran beside a deferred touch against a controller documented as unsafe for
  concurrent use. **Setting `ChartSession.containerSize` is asynchronous** — await
  `settle()` where the new scene is needed. (#60)

- **The accessibility summary threshold counts data marks, and the guides survive
  it.** It counted every focusable element, so a chart's axes and legend pushed it
  over before the data was dense — measured: 118 points, two axes and a legend is
  121 focusable elements, and the whole tree collapsed at 118 marks. A reader lost
  per-mark exploration of the entire chart rather than of the crowded part, and
  lost the axes and the legend too. The summary now stands in for the marks alone
  and counts only those, and the threshold is a parameter on every host —
  `AccessibilityTree.elements(maxExposedMarks =)`, the Compose and SwiftUI views,
  and the Android view's `accessibilityMaxExposedMarks`. (#65)

- **`legend.labelExpr` is honoured**, as `axis.labelExpr` already was. Upstream
  destructures it out and writes `encode.labels.update.text` from it on both
  guides; this compiler did it for the axis only, so a `legend.labelExpr`
  survived into the emitted Vega and the *Vega* parser reported it as an unknown
  property two stages later, while the labels were drawn from the scale's domain
  at full length. Since the untruncated labels are what the legend's width is
  computed from, a chart over long category names overflowed whatever held it,
  with no host workaround. (#70)

- **One bytecode level per release.** 0.1.0 published its jars at Java 17 and
  `vega-compose-multiplatform`'s Android AAR at Java 21, because the pin named a
  Kotlin target *type* and the AGP Kotlin Multiplatform Android target is not one
  of those — so the AAR took the level of whichever JDK cut the release. A
  Robolectric test on a JDK 17 runtime, in a build that resolves the Android
  variant, died with `UnsupportedClassVersionError` at the first composable it
  reached. The level is now pinned per compile *task*, so no target can escape it,
  and `checkBytecodeLevel` asserts it against the class files themselves rather
  than against the configuration that was wrong. (#68)

- **`tickCount` in its interval form is no longer warned about.** The field was
  read twice — once as a number, which warned `PARSE_UNKNOWN_PROPERTY` for
  `"day"` or `{"interval": "day", "step": 20}`, and then again, correctly, as an
  interval. A chart was laid out exactly as it asked and complained about at the
  same time: one warning per axis, four on a page, all false. The warning now
  fires only where a reading really fails — an interval that is not a calendar
  unit, or one named by a signal, both of which used to fall through to a
  chosen count in silence. (#69)

- **A document that draws nothing says so.** A specification with no marks, no
  axes, no legends and no title now reports `PARSE_NOTHING_TO_DRAW` at INFO.
  `{"width": 100, "height": 50}` used to compile to a usable, empty chart with
  no diagnostic of any kind, so a host reading "no diagnostics" as "there is a
  chart" could not tell an empty placeholder object from one that drew. The
  message lists the document's own top-level keys, which is what makes a
  Vega-Lite specification handed to the Vega parser — previously silent —
  legible: it reads `mark, encoding`. (#57)

### Added

- **A host can supply its own rules for a locale, not just tables.**
  `VegaLocale.rules: VegaFormatRules?` decides what a name-bearing directive says
  and what digits a number is written with — the two things a lookup table
  provably cannot express, being a contextual name (Polish `stycznia` beside a day
  number, `styczeń` alone) and a numbering system, since the engine wrote
  `value.toString()` and that is ASCII always. It is also where a *device's* own
  preferences can get in. **A specification's format decides the shape and the
  rules decide the details inside it**: `"format": "%b %d, %Y"` keeps that order,
  those fields and those widths whatever a host supplies, and literal text the
  document typed is never passed to a rule. Every method may answer null, and
  `EnglishUS` carries no rules, so the differential comparisons are untouched.

- **The two renderers' draw-call sequences are compared against each other.** Both
  `SceneWalk` implementations claim to emit "the same calls in the same order" and
  nothing checked it — which is how the Apple renderer came to paint labels the
  axis had hidden while Compose did not, with both renderers' own tests green.
  `test-fixtures/scene-walk/*.calls.txt` is written from the Compose walk by
  `SceneWalkGoldenTest` and asserted against the Swift walk by
  `SceneWalkParityTests`. It found a second divergence immediately: the Swift
  `DrawTextRun` carried no `letterSpacing`, so a spaced label was **measured**
  with spacing and **drawn** without it. Fixed. (#71)

- **A host is told which image URL could not be resolved**, once per URL:
  `VegaChart(onUnresolvedImage =)` and `VegaChartView(onUnresolvedImage:)`, both
  additive. Making it usable required fixing something else first — only
  successful decodes were cached, so a URL that had already failed was handed back
  to the host's resolver on **every frame**, and a report fired from the draw
  would have fired with it. Refusals are cached now on both renderers, and
  `ImageCache.clear()` / `CoreGraphicsTarget.clearImageCache()` give a host that
  has recovered another go. `ImageCache.unresolvedImages` is the same facts
  without a callback. (#58)

- **A host can register named font families with the Compose renderer**, through
  `namedFontFamily` or `rememberVegaTextEngine(fontFamilies)`. The default
  resolver matched the generic CSS keywords and nothing else, so a themed
  specification naming a real face was drawn in the platform's default one — and
  said nothing about it. The Apple and Android engines resolve a device family by
  name; common Compose code cannot, so only a host holding the `FontFamily` can.
  `ComposeTextEngine.unresolvedFontFamilies` names what went unresolved, a text
  engine having no diagnostics channel of its own. (#66)

- **`VegaChart(sizing = SceneSizing.Fill)`**, so `fit` has something to do. The
  composable appended `Modifier.size(scene.width.dp, scene.height.dp)`
  unconditionally, so a caller that bounded neither dimension got the scene's own
  size whatever `fit` said — for a `width: "container"` chart, about 300 units plus
  axes in however much room was going. `Fill` takes the slot where there is one
  and falls back to the scene's size where there is not. `SceneSizing.Scene`
  remains the default. (#67)

- **An image resolver on both public view APIs.** `DrawScopeTarget` and
  `CoreGraphicsTarget` have each taken one from the start and neither
  `VegaChart` nor `VegaChartView` had a parameter for it, so a chart with a
  remote image mark drew every other mark and a hole where the image would be,
  with no supported way to supply a fetcher. Exposing it also required the
  Compose decode cache to outlive a frame: a target is built per draw, so a
  heatmap's raster was being re-decoded every frame and a host's fetcher would
  have been called every frame. `ImageCache` and `rememberVegaImageCache` are
  the seam; it is bounded and least-recently-used. (#58)

- **A locale decides the order of a date, not only its names.** `%b` always
  resolved to the reader's month abbreviation, but the *pattern* came from tables
  with no locale in them, so a Dutch axis read `mei 21, 2026`. `VegaLocale.date`
  is d3's `%x` and is now derived from, and `time` likewise for the clock — so a
  locale copied out of a d3 locale JSON reads `21 mei 2026` and gets 24-hour ticks
  without being told to. This is a deliberate **divergence from upstream**, whose
  `timeUnitSpecifier` takes no locale: `VegaLocale.EnglishUS` is pinned to
  upstream's own answers and is the locale every differential comparison runs
  under, which `LocaleDefaultsTest` and the upstream vector replay now assert.
  `timeUnitSpecifierOverrides` and `timeTickFormatOverrides` state a table
  outright where derivation is not wanted. The Vega-Lite compiler carries a locale
  for the first time — the pattern is written on that side — so
  `VegaLiteCompiler`, `VegaLiteInput.toVega` and `Config` take one, and
  `ChartSession` passes its own. `VegaLocale.init`, `doCopy`,
  `TimeUnits.specifier` and `toVega` change signature at the Obj-C boundary, and
  `DateField` is new; recorded in `foreign-api.txt`. (#62)

- **A specification that reads `containerSize()` with no host size supplied now
  says so**, per dimension, as `EXPRESSION_CONTAINER_SIZE_UNANSWERED` at INFO.
  The answer is unchanged — `[null, null]` is what a browser gives outside a
  container and what upstream gives headless — but a document branching on a
  breakpoint used to take its "no container" arm with nothing in the diagnostics
  channel to explain the layout. `CompiledSpec.readsContainerSize` publishes the
  same fact, so a host can decide whether a resize is worth a recompile at all;
  it is false for any chart that declares its own width and height.
  `Expression.functionDependencies` is the new seam behind both. (#63)

- **`usermeta` reaches the host**, as `VegaSpec.usermeta` and so as
  `CompiledSpec.spec?.usermeta`, on Kotlin and Swift alike. It was previously
  discarded with one `usermeta is ignored` warning per compile, whatever it
  carried — so the diagnostic was the whole of the feature, and a document
  carrying supplementary data for the host to consume lost it unconditionally.
  Absent is null and `{}` is an empty map; a non-object `usermeta` is reported
  and dropped. Vega-Lite already carried it onto the Vega it emits, so a
  document in either grammar arrives with it intact. `VegaSpec.init` and
  `doCopy` gain a parameter, which is a new Obj-C signature — recorded in
  `foreign-api.txt`. (#64)

## 0.1.0

First release.

### The engine

A port of Vega to Kotlin Multiplatform, verified differentially against upstream
Vega 6.3.1 rather than against its own expectations: 193 fixtures are compiled by
both engines and compared mark by mark, and the d3 and `vega-*` test suites are
replayed from recorded vectors.

Expressions, signals, 49 of upstream's 51 documented data transforms, every scale
type in scope, geographic projections, and an event handler that recompiles the
chart.

### Vega-Lite

A Vega-Lite compiler, compared **property by property** against upstream
Vega-Lite 6.4.3 on 282 fixtures — not only the picture it draws, because
Vega-Lite's value is in the defaults it supplies and each of those is one property
of the output. All **627** of upstream's own gallery examples compile to the
specification upstream compiles them to.

`VegaLiteInput.toVega` accepts either grammar and says which it read, on every
host.

### Renderers

- Android `Canvas`, with accessibility nodes
- Jetpack Compose, and Compose Multiplatform for Android, iOS and the desktop
- CoreGraphics for Apple platforms, in Swift
- SVG, PNG and PDF export

### Targets

`jvm`, `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `linuxX64`, and Android. No
`iosX64`: `ktecma262` 0.2.0 does not publish that slice, so the target cannot
resolve its dependencies. See the platform table in README.md.

### What a host supplies

Everything the engine cannot know is a **constructor parameter beside the text engine**, and each
one is reachable from Kotlin *and* from Swift:

- **`locale`** — every generated string: month names, number separators, the sentences a screen
  reader is given. `VegaCaptions` is an interface rather than a table, because those sentences are
  grammar. Parsing stays English, d3's parsing being part of the wire format.
- **`timeZone`** — which zone *local* means, for a `time` scale's ticks, `timeunit`'s buckets, the
  local expression functions and the zone a timestamp with no offset in the data is read in. Null is
  the device's own. `utc` forms are untouched.
- **`hostConfig`** — a `config` block the app supplies, which the specification's own beats key by
  key: how a chart drawn on a dark surface is legible when the server chose colours for a white page.
- **`containerSize`** — what `width: "container"` asks for, a responsive width having no answer
  inside the specification.
- **`hostData`** — a table the **app** holds rather than the payload: `setData(name, rows)`, which is
  upstream's `view.data`. The rows arrive where inline values would, so `format.parse` and every
  transform run over them unchanged.

### What a host gets back

- A **scene**, and diagnostics rather than exceptions. `DiagnosticSeverity` says what each level means
  for the chart, and README.md states the policy a host should apply to it.
- **Interaction**, on every renderer: tap, long press, pan, pinch, hover and keys, dispatched into the
  specification's own event handlers. Pan and zoom are drawn, not merely accumulated.
- **Accessibility**: one element per mark, axis, legend and title, positioned so a reader can explore
  by touch — as virtual nodes on Android, as positioned elements in SwiftUI, as semantics in Compose.
- **Tooltips** as rows and text in the chart's own locale, with the anchor to put them at. Drawing the
  bubble is the app's, since what a bubble looks like is a design-system decision.
- **Fonts and text size** from the host: a resolver for a bundled face on every renderer, and the
  reader's text-scale setting applied to measurement *and* drawing.

### Consuming it

Maven Central under `io.github.mgilbir.astervega`, one version for every module,
and a Swift package whose `Package.swift` at tag `vX.Y.Z` names the XCFramework of
that same release — the release workflow writes the checksum and then creates the
tag, so one tag serves both ecosystems.

Every published module carries a committed ABI dump under `api/`, for the JVM
surface and for the native one, checked by `scripts/check.sh`.

### Fixed before the first release

- `mark.clip` on a mark that is not a group was read for bounds and never applied
  when drawing, so a value past the scale's domain was painted over the axis
  instead of being hidden.
- A chart's own `name` was used verbatim where it prefixes every scale, axis and
  signal reference; upstream substitutes non-word characters, so `score-total`
  became `score_total` there and this engine disagreed with it.
- An unknown top-level Vega-Lite property was dropped without a diagnostic, and
  `usermeta` — which upstream carries onto the compiled Vega — was one of them.
- A `$schema` naming another major version of Vega-Lite was compiled with version
  6 rules and said nothing about it.
- A **group** was picked anywhere inside its bounds, so a tap on blank space selected
  the frame every compiled chart wraps its marks in and a host reported one selected
  mark with nothing under the finger. Upstream picks a group only where it paints.
  A mark hidden by a clip was tappable for the same reason, and a rounded bar was
  picked in the corners it does not fill.
- **Pan and zoom updated the controller and did not move the chart** on the Compose
  Multiplatform and Swift renderers, so a gesture made "reset" available and appeared
  to do nothing.
- The Compose Multiplatform renderer took **no pointer input at all**: a mark could be
  activated by a screen reader and not by a finger.
- Three compile inputs — the locale, the host configuration and the container width —
  were **unreachable from Swift**, so three implemented features were available on
  Android only.
- The reader's text-size setting was honoured on Android and in Compose and ignored on
  iOS; a font the app ships could not reach a chart on the Android View at all.
- A `Modifier` passed to the Compose Multiplatform chart could not make it any bigger
  than its scene, which made the default fit mean nothing.
- The derived label a screen reader reads spoke a raw epoch number on a time axis, and
  then — briefly — a datum rounded to the axis's tick precision, which is a nicer label
  and a false statement about one measurement.
