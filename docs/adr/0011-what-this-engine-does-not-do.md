# 0011. What this engine deliberately does not do

Status: accepted (2026-09-01)

Recorded from `PROJECT_BRIEF.md` §3.1 and §3.3 when that document was retired. The list is the most
cited thing in that file, which is the reason it survives it.

## Decision

This engine does **not** contain, and is not going to grow:

- **A browser, a WebView or an SVG DOM.** SVG is an output format; see
  [0001](0001-svg-is-not-the-runtime-model.md).
- **JavaScript execution.** Vega's expression language is *interpreted* — parsed to a tree and
  walked — with no `eval` and no code generation. That is what makes it evaluable on a platform
  with no JavaScript engine, and it is why the expression module carries its own lexer and parser.
- **Full CSS parsing** or **HTML labels**.
- **Arbitrary browser event compatibility.** The event model covers what a chart needs — pointer,
  wheel, pinch, keyboard — not what a DOM emits.
- **Direct Skia integration through native code, or NDK modules.** Rendering goes through the
  platform's own canvas.

## Why

A port of a browser library is under constant pressure to reproduce the browser, and each of these
is a place where doing so would cost more than the feature is worth. A DOM would mean reimplementing
layout, hit testing and repaint; a JavaScript engine would mean shipping one, on a platform that has
no reason to have one; NDK modules would mean a build with a native toolchain in it for every
consumer.

The list is worth stating as a *decision* rather than a backlog because each entry has been asked
for at least once, and the answer is the same each time and for the same reason.

**One entry has expired and is left here to say so.** The brief also excluded a Vega-Lite compiler
from the first release; that compiler shipped, and its own record —
[0005](0005-vega-lite-compilation-is-out-of-scope.md) — is marked superseded rather than deleted.
An exclusion outliving its reason is exactly the failure mode this file is prone to, and it has
happened here before: regular-expression literals were excluded because there was no engine to hand
a pattern to, the engine arrived, and the exclusion sat unrevised through five releases (#153).

## Consequences

- A specification that depends on the DOM, on CSS, or on HTML in a label will not render as it does
  in a browser, and `SUPPORTED_FEATURES.md` is where each such gap is named.
- The expression language is a subset with its own grammar, so a construct legal in JavaScript may
  be a syntax error here — which is a claim that has to be *checked against upstream* rather than
  assumed, because upstream's own parser is the standard for what a specification may write.
- Every entry above should be re-read when the thing that motivated it changes. The Vega-Lite line
  and the regular-expression literal are both cases where nobody did.
