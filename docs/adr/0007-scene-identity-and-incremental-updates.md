# 0007. Scene identity and incremental update policy

Status: accepted (2026-08-06)

## Decision

Scenes are immutable and published whole. Every published snapshot carries a monotonically increasing
`revision`. Renderers invalidate by comparing revisions, never by diffing scenes. Node ids and tuple
ids come from sequential allocators seeded per build, not from identity hash codes. Interaction state
lives outside the scene, so hover, tooltip and selection changes bump the revision without rebuilding
any marks.

## Why

**Immutability, because drawing and building must not overlap.** A scene may be built off the main
thread and is drawn on it. If the drawing thread could observe a half-updated scene, the failure would
be an intermittent visual glitch — the worst kind to diagnose. Publishing a complete immutable
snapshot makes that structurally impossible.

**Revisions, because diffing to decide whether to redraw is backwards.** The producer already knows it
changed something; making the consumer rediscover that by walking the tree costs more than the redraw
it might save. A `Long` comparison is the whole check. It also gives `requestLayout()` a clean rule:
only when the preferred size actually changes, never for a hover (ADR 0002).

**Sequential ids, because snapshots must be reproducible.** Identity hash codes vary per JVM run, so a
golden containing them would be unusable. Sequential allocation from a per-build seed means the same
inputs produce the same ids, which is what makes scene snapshots a real regression signal
(ADR 0008).

**Stable tuple identity, because a data change should not reset the user's context.** When a dataset
updates, a mark that still represents the same datum should keep its selection and its accessibility
focus. That only works if identity is carried by the tuple, not derived from list position. `TupleId`
exists for this even though the transforms that will use it are not written yet.

**Interaction state outside the scene, because hovering is not a data change.** The performance target
is explicit: a hover or selection update must not rerun the dataflow (ADR 0012). Splitting
`InteractionState` from `Scene` makes that the natural implementation rather than an optimization to
remember.

## Consequences

- Rebuilding a scene allocates a new tree. Acceptable at current scale; the dataflow's `ChangeSet`
  already models add/remove/modify so a later incremental path has somewhere to land.
- `ChangeSet` distinguishes added, removed, modified and replace-all even though the first operators may
  recompute everything. The API is the part that is hard to change later.
- Whether renderers should eventually consume a diff instead of a snapshot is deliberately unanswered;
  it is listed as pending in STATUS.md and should be decided with real data from Milestone 4.

## Revisit if

Milestone 4 shows that typical data updates touch a small fraction of marks and that whole-scene
rebuilds miss the frame budget. Node-level diffing would then be added behind the existing snapshot
API, not instead of it.
