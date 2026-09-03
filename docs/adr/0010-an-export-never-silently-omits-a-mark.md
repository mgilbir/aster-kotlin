# 0010. An export reports what it cannot draw, and never silently omits it

Status: accepted (2026-09-01)

Recorded from the retired design brief, §13 which this replaces.

## Decision

Every export path — SVG, PNG, PDF — either draws a mark or **says** it could not. An operation a
backend does not support must fall back to a compatible representation, or produce a structured
export warning naming what was dropped. Neither is optional, and silence is not one of the choices.

SVG serialisation is platform-independent and deterministic: stable generated identifiers, escaped
text, canonical numeric formatting, and a configurable decimal precision chosen to avoid visible
geometry loss while keeping the file diffable.

## Why

An export is the one output a reader takes away and shows to somebody else, and it is the output
whose correctness nobody re-checks. A chart that quietly loses a mark on the way into a PDF is
wrong in a document that outlives the session, and the loss is invisible *on screen* — the screen
drew it fine.

That asymmetry is the whole argument. Every other rendering defect is visible in the same place the
reader is already looking; this one is not, which is why it gets a rule rather than a review.

The determinism half is what makes an SVG golden mean something: an identifier that changes per run
turns every diff into noise, and once a diff is noise nobody reads it.

## Consequences

- `ExportWarning` is part of the export API rather than a log line, so a host can surface it.
- A backend gaining an operation is a change to what it warns about, not only to what it draws.
- The precision knob is a trade a caller makes: tighter files, or geometry that survives a
  round trip. The default favours not losing geometry.
- Canonical SVG is what `SvgRenderer`'s goldens compare, so this rule and
  [0008](0008-visual-regression-is-structural.md) hold each other up — the goldens are only
  readable because the output is canonical, and the canonicalisation is only trustworthy because
  the goldens would show it changing.
