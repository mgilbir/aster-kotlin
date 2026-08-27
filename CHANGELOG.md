# Changelog

Notable changes, newest first. The release workflow reads the section for the
version it is publishing and uses it as the release notes, so a version without a
section here does not get released.

## Unreleased

### Changed

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

### Fixed

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
