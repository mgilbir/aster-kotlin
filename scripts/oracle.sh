#!/usr/bin/env bash
# Differential tests against upstream Vega.
#
# 1. install pinned Node dependencies with `npm ci`
# 2. run each fixture through upstream Vega
# 3. export reference scene data and SVG
# 4. canonicalize the output
# 5. compare it with the Kotlin output
# 6. write readable differences under build/oracle-diffs
#
# The reference files under test-fixtures/reference/ are checked in, so the JVM differential tests run
# without Node or a network. This script regenerates them; review the resulting diff the way you would
# review a golden, because a change here means either the port or upstream moved.
set -euo pipefail
cd "$(dirname "$0")/.."

# A `time` scale is local, so upstream's output depends on the machine's zone. Pinned to the same
# zone the JVM tests use (build.gradle.kts), so a checked-in reference means the same thing on any
# machine — and so a fixture can cross a daylight-saving boundary on purpose.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
DIFF_DIR="$ROOT/build/oracle-diffs"
REFERENCE_DIR="$ROOT/test-fixtures/reference"
SCENE_DIR="$ROOT/build/oracle-reference"
FIXTURES="$ROOT/test-fixtures/specs"

mkdir -p "$DIFF_DIR" "$REFERENCE_DIR" "$SCENE_DIR"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund)

shopt -s nullglob
specs=("$FIXTURES"/*.vg.json)
if [[ ${#specs[@]} -eq 0 ]]; then
  echo "No fixtures in $FIXTURES" >&2
  exit 1
fi

# A fixture may name its data the way Vega's own examples do, by relative URL. The file itself is
# checked in beside the references so the JVM tests stay offline; this fetches any that are missing,
# which is a deliberate step whose result gets reviewed and committed.
echo "==> Checking fixture datasets"
(cd oracle-js && node src/fetch-data.js "$ROOT/test-fixtures")

echo "==> Rendering ${#specs[@]} fixture(s) with upstream Vega"
for spec in "${specs[@]}"; do
  name="$(basename "$spec" .vg.json)"
  # The comparison reference the JVM tests read.
  (cd oracle-js && node src/reference.js "$spec" "$REFERENCE_DIR/$name.reference.json")
  # Human-readable scene summary and SVG, for eyeballing a disagreement.
  (cd oracle-js && node src/render.js "$spec" "$SCENE_DIR/$name")
done

# The accessibility captions upstream puts on an axis or a legend. They are `aria-label` attributes
# rather than geometry, so the mark comparison cannot see them; harvesting them from the same renders
# gives them a reference of their own that stays in step with the fixtures.
echo "==> Harvesting guide captions"
python3 - "$SCENE_DIR" "$REFERENCE_DIR/guide-captions.json" "$FIXTURES" <<'PYTHON'
import glob, html, json, os, re, sys
scene_dir, out, fixtures = sys.argv[1], sys.argv[2], sys.argv[3]
# Only the fixtures that exist *now*. The scene directory is build output and is never cleaned, so a
# fixture that has been deleted leaves its SVG behind and would keep contributing a caption nothing
# can produce any more — a reference that fails for a chart nobody has.
current = {os.path.basename(p)[:-len('.vg.json')] for p in glob.glob(os.path.join(fixtures, '*.vg.json'))}
rows = []
for path in sorted(glob.glob(os.path.join(scene_dir, '*.svg'))):
    if os.path.basename(path)[:-4] not in current:
        continue
    svg = open(path).read()
    for m in re.finditer(r'aria-roledescription="(axis|legend|title|subtitle)" aria-label="([^"]*)"', svg):
        # The caption a reader hears, not its XML spelling: an ampersand in a legend title arrives
        # here as `&amp;` because it came out of an attribute, and the engine never escapes one.
        rows.append({"fixture": os.path.basename(path)[:-4],
                     "kind": m.group(1), "caption": html.unescape(m.group(2))})
json.dump(rows, open(out, 'w'), indent=1, ensure_ascii=False)
print(f"Wrote {len(rows)} guide caption(s) to {out}")
PYTHON

# The container upstream draws every item of a mark inside, and the announcement it hangs on it: a
# mark's own `description` is heard once there rather than on each of its items. Like the captions
# above these are attributes rather than geometry, so the mark comparison is blind to them.
echo "==> Harvesting mark containers"
python3 - "$SCENE_DIR" "$REFERENCE_DIR/mark-containers.json" "$FIXTURES" <<'PYTHON'
import glob, html, json, os, re, sys
scene_dir, out, fixtures = sys.argv[1], sys.argv[2], sys.argv[3]
current = {os.path.basename(p)[:-len('.vg.json')] for p in glob.glob(os.path.join(fixtures, '*.vg.json'))}
# The roles that are a *mark* rather than a guide. A guide's container is announced as "axis" or
# "legend" and is covered by the caption reference; these are the ones a specification's own marks
# produce, the frame and a trellis's headers included.
MARK_ROLES = re.compile(r'^(mark|scope|frame|row-header|row-footer|row-title|column-header|column-footer|column-title)$')
ATTR = re.compile(r'([-a-z]+)="([^"]*)"')
rows = []
for path in sorted(glob.glob(os.path.join(scene_dir, '*.svg'))):
    fixture = os.path.basename(path)[:-4]
    if fixture not in current:
        continue
    svg = open(path).read()
    for tag in re.finditer(r'<g class="mark-([a-z]+) role-([a-z-]+)[^"]*"([^>]*)>', svg):
        kind, role, rest = tag.group(1), tag.group(2), tag.group(3)
        if not MARK_ROLES.match(role):
            continue
        # A mark that produced no items still gets its container upstream, and this engine has no
        # place for one: the announcement travels on the items, so with no items there is nothing to
        # carry it. Skipped rather than compared, because an empty container is inaudible — assistive
        # technology walks past a group with no content — and recording it would fail a comparison
        # over a difference nobody can hear. A mark that draws *anything* is compared in full.
        # Empty either way it is spelled: `<g ...></g>` or, when the mark has no items at all,
        # `<g .../>`.
        if rest.endswith('/') or svg[tag.end():tag.end() + 4] == '</g>':
            continue
        attrs = dict(ATTR.findall(rest))
        described = attrs.get('aria-roledescription', '').endswith('mark container')
        hidden = attrs.get('aria-hidden') == 'true'
        if not described and not hidden:
            continue
        rows.append({"fixture": fixture, "kind": kind,
                     "role": None if hidden else attrs.get('role'),
                     "roleDescription": None if hidden else attrs.get('aria-roledescription'),
                     "label": html.unescape(attrs['aria-label']) if 'aria-label' in attrs else None,
                     "hidden": hidden})
json.dump(rows, open(out, 'w'), indent=1, ensure_ascii=False)
print(f"Wrote {len(rows)} mark container(s) to {out}")
PYTHON

# The caption and container references are read by tests of their own, which are run here beside the
# comparison: this script is what regenerates what they read, so a fixture whose captions moved should
# fail here rather than in the next full test run.
echo "==> Comparing with the Kotlin runtime"
if ./gradlew --console=plain :vega-runtime:test \
  --tests '*Differential*' \
  --tests '*GuideCaptionTest' \
  --tests '*MarkContainerTest' \
  > "$DIFF_DIR/differential.log" 2>&1; then
  echo "Differential tests passed for ${#specs[@]} fixture(s)."
  echo "Reference data: $REFERENCE_DIR"
  echo "Upstream scenes and SVG: $SCENE_DIR"
else
  echo "Differential tests FAILED. Details:" >&2
  echo "  $DIFF_DIR/differential.log" >&2
  echo "  HTML report: $ROOT/vega-runtime/build/reports/tests/test/index.html" >&2
  # Surface the assertion text directly; the log is long and the useful part is the diff list.
  grep -A 30 "AssertionFailedError" "$DIFF_DIR/differential.log" | head -60 >&2 || true
  exit 1
fi
