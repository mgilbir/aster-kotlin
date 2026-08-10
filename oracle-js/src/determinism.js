/**
 * Makes upstream Vega reproducible, so a chart built on `random()` or `now()` can have a reference.
 *
 * Both are ordinary non-determinism, and both have an injection point. `vega-functions` calls a
 * module-level `random` binding that `setRandom` replaces, and it code-generates `now()` as the
 * literal `Date.now`, which is looked up when the expression runs rather than when it is compiled.
 * Replacing the two pins the whole view.
 *
 * The seed and the instant are duplicated in `vega-expression`'s `RandomStream.DEFAULT_SEED` and
 * `Clock.PINNED`. They have to agree: the differential comparison is only meaningful because both
 * engines draw the *same* sequence from the *same* generator, in the same order.
 */
import * as vega from 'vega';
import { installCanvasShim } from './canvas-shim.js';

/** Matches `RandomStream.DEFAULT_SEED`. */
export const SEED = 42;

/** Matches `Clock.PINNED`: 2026-01-01T00:00:00Z. */
export const NOW = 1767225600000;

/**
 * Call before parsing a specification.
 *
 * Per process rather than per view, because the generator is module-level upstream — which is also
 * why a script that renders several specifications has to reset it between them, or the second one
 * continues the first one's sequence.
 */
export function pinDeterminism() {
  vega.setRandom(vega.randomLCG(SEED));
  Date.now = () => NOW;
  // `heatmap` allocates a canvas and writes pixels into it. Plain Node has none, so the transform
  // throws and the two examples built on it have no reference at all; see canvas-shim.js for why a
  // buffer with no renderer behind it is a faithful oracle for that one transform.
  installCanvasShim();
}
