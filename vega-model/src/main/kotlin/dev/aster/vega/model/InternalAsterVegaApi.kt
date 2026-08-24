package dev.aster.vega.model

/**
 * Public because Kotlin has no cross-module `internal`, and **not** part of what a host may call.
 *
 * Every module here compiles separately, so a declaration one module needs from another has to be
 * `public` whatever it is for. That made `public` say two different things — "call this" and "the
 * compiler made me" — and there was no way to tell them apart. It is why "does everything a host
 * needs reach a host" was unanswerable until `scripts/foreign-coverage.py` enumerated it.
 *
 * This says which. A caller outside the engine gets a compile error unless it opts in, and the
 * coverage list stops being a flat file to eyeball: a member marked with this is *expected* not to
 * reach a foreign host, so an **unmarked** one that stops crossing is a real signal.
 *
 * Opting in is allowed — the annotation is a warning about support, not a lock. What it means is
 * that these have no compatibility promise and may change in a patch release.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "This is internal to the engine: public only because Kotlin has no cross-module `internal`, " +
      "with no compatibility promise. Opt in with @OptIn(InternalAsterVegaApi::class) if you " +
      "accept that it may change in a patch release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
)
public annotation class InternalAsterVegaApi
