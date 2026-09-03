# Fixture index

Generated from the corpus by `FixtureIndexTest`, which fails when this file has drifted:

```sh
./gradlew :vega-runtime:jvmTest -PupdateGoldens=true --rerun-tasks
```

196 Vega differential fixtures and 283 Vega-Lite fixtures.

Every column is read off disk. A Vega fixture's mark count and mark types come from its
**upstream** reference, so they are upstream's answer rather than this port's opinion of it.

## Vega

| Fixture | Marks | Mark types | Transforms | Scales |
| --- | --- | --- | --- | --- |
| `aggregate-ops-rest` | 14 | rect, rule, text | aggregate | band, linear |
| `aggregate-ops-tail` | 43 | rect, rule, symbol, text | aggregate, fold | band, linear, ordinal |
| `airport-connections` | 650 | path, symbol, text | aggregate, collect, filter, geopath, geopoint, linkpath, lookup, voronoi | linear |
| `arc-padding` | 10 | arc | pie | ordinal |
| `arc-radii-inverted` | 5 | arc | — | ordinal |
| `area-gaps` | 22 | area, line, rule, text | — | linear |
| `autosize-fit-x` | 24 | rect, rule, text | — | band, linear |
| `autosize-fit-y` | 24 | rect, rule, text | — | band, linear |
| `autosize-fit` | 24 | rect, rule, text | — | band, linear |
| `autosize-none` | 19 | rect, rule, text | — | band, linear |
| `axis-discretizing` | 89 | rect, rule, symbol, text | bin | bin-ordinal, quantile, quantize, threshold |
| `axis-label-angle` | 33 | rect, rule, text | — | band, linear |
| `axis-label-bound` | 30 | rect, rule, text | — | band, linear |
| `axis-label-flush` | 85 | line, rule, text | — | linear |
| `axis-label-offset` | 58 | rect, rule, text | — | band, linear |
| `axis-placement` | 54 | rect, rule, text | — | band, linear |
| `axis-style` | 37 | rect, rule, text | — | band, linear |
| `axis-tick-min-step` | 97 | rule, text | — | linear, log |
| `axis-values` | 34 | rect, rule, text | — | band, linear |
| `axis-variants` | 34 | rect, rule, text | — | band, linear |
| `band-padding` | 22 | rect, rule, symbol, text | — | band, linear, point |
| `bar-line-toggle` | 100 | rect, rule, text | collect, filter, formula, sequence | band, linear |
| `bar` | 48 | rect, rule, text | — | band, linear |
| `barley-trellis` | 468 | group, rule, symbol, text | — | band, linear, ordinal, point |
| `beeswarm` | 100 | rule, symbol, text | force | band, ordinal |
| `bin-settings` | 36 | rect, rule, text | aggregate, bin, filter | linear |
| `bin-to-ordinal` | 36 | rect, rule, text | aggregate, bin | bin-ordinal, linear |
| `binned-scales` | 69 | rect, rule, text | — | bin-ordinal, linear, quantile, quantize, threshold |
| `box-plot` | 32 | rect, rule, text | aggregate | band, linear |
| `budget-forecasts` | 77 | line, rule, symbol, text | aggregate, filter, formula, lookup | band, linear |
| `calendar-view` | 6311 | rect, text | aggregate, filter, formula, sequence, timeunit, window | band, linear |
| `centre-anchors` | 22 | group, line, rect, symbol, text | — | — |
| `clock` | 91 | arc, symbol, text | formula, sequence | linear |
| `colour-interpolation` | 180 | rect, text | — | linear |
| `colour-ramps` | 57 | rect, rule, text | — | band, linear |
| `colour-scheme` | 39 | rect, rule, text | — | band, linear, ordinal |
| `config-group-projection` | 2 | group, path | geoshape | — |
| `config-marks` | 31 | rect, rule, symbol, text | — | band, linear |
| `config-range` | 20 | rect, symbol, text | — | band, linear, ordinal |
| `config-theme` | 42 | rect, rule, symbol, text | — | band, linear, ordinal |
| `connected-scatter` | 146 | line, rule, symbol, text | — | linear, ordinal |
| `container-size` | 2 | rect, text | — | — |
| `continuous-scale-padding` | 22 | line, rule, symbol, text | — | linear, time |
| `contour-legacy` | 450 | path, rule, symbol, text | contour, filter, geopath | linear |
| `contour-plot` | 460 | image, path, rule, symbol, text | filter, geopath, heatmap, isocontour, kde2d | linear, ordinal |
| `county-unemployment` | 3622 | rect, shape, text | filter, geoshape, lookup | quantize |
| `crossfilter-flights` | 171 | group, rect, rule, text | aggregate, bin, crossfilter, resolvefilter | band, linear |
| `csv-header` | 41 | rect, rule, text | — | band, linear |
| `curves-closed` | 3 | line | — | linear, point |
| `curves` | 27 | area, line, rule, text | — | linear |
| `density-heatmaps` | 76 | image, rect, rule, text | filter, heatmap, kde2d | linear |
| `density-options` | 37 | area, line, rule, symbol, text | kde, loess | linear, ordinal |
| `density-plot` | 40 | area, line, rule, symbol, text | density, dotbin, kde, stack | linear |
| `diverging-colour` | 31 | rect, rule, text | — | band, linear |
| `domain-limits` | 37 | rect, rule, symbol, text | — | band, linear, symlog |
| `domain-sort-order` | 32 | rect, rule, text | — | band, linear |
| `donut-chart-labelled` | 108 | arc, path, rect, text | bin, collect, extent, filter, formula, joinaggregate, pie, sequence, window | ordinal |
| `dorling-cartogram` | 113 | symbol, text | filter, force, formula, lookup | linear |
| `dot-plot-wilkinson` | 120 | rule, symbol, text | bin, dotbin, extent, stack | linear |
| `dot-plot` | 28 | rule, symbol, text | — | linear, point |
| `ecma-trim` | 19 | rule, text | formula | band |
| `edge-bundling` | 989 | line, symbol, text | filter, formula, stratify, tree | ordinal |
| `encode-channels-tail` | 15 | group, image, line, path, rect, rule, symbol, text | — | — |
| `encode-channels` | 15 | area, group, line, rect, rule, text | — | band, linear |
| `error-bars` | 61 | rect, rule, symbol, text | aggregate, formula | band, linear |
| `expressions` | 26 | rect, rule, text | extent, filter, formula | band, linear |
| `facet-trellis` | 55 | group, rect, rule, text | — | band, linear |
| `filter-by-pattern` | 35 | rect, rule, text | filter, formula | band, linear |
| `flatten-arrays` | 25 | rect, rule, symbol, text | aggregate, flatten, formula | band, linear, ordinal |
| `force-directed` | 331 | path, symbol | force, linkpath | ordinal |
| `format-parse` | 1 | text | formula, lookup | — |
| `geo-measures` | 5 | rect, shape, symbol, text | geoshape | — |
| `geo-points` | 30 | rule, symbol, text | geopoint | — |
| `geojson-transform` | 14 | symbol | geojson, geopoint | — |
| `global-development` | 175 | rule, symbol, text | aggregate, collect, filter, formula, lookup | linear, ordinal |
| `gradient-fills` | 6 | rect, symbol | — | band, linear, sequential |
| `grouped-bar` | 56 | rect, rule, text | — | band, linear, ordinal |
| `guide-caps-and-offsets` | 57 | rect, rule, symbol, text | — | band, linear, ordinal |
| `guide-encode-channels` | 46 | group, rect, rule, symbol, text | — | linear, ordinal |
| `guide-encode-geometry` | 32 | rect, rule, text | — | band, linear |
| `guide-encode-text` | 43 | rect, rule, text | — | band, linear |
| `guide-style-signals` | 46 | rect, rule, symbol, text | — | band, linear, ordinal |
| `hierarchy-options` | 40 | rect, symbol, text | pack, partition, stratify, tree | — |
| `histogram-null-values` | 47 | rect, rule, text | aggregate, bin, extent, filter | band, linear |
| `histogram` | 33 | rect, rule, text | aggregate, bin | linear |
| `href-links` | 17 | rect, rule, symbol, text | — | band, linear, ordinal |
| `hypothetical-outcome-plots` | 60 | rect, rule, text | formula, sequence | band, linear |
| `identity-projection` | 7 | path, symbol, text | geopoint, geoshape | — |
| `identity-scale` | 4 | symbol, text | — | identity, ordinal |
| `image-marks` | 18 | image, symbol | — | — |
| `impute-pivot` | 41 | rect, rule, symbol, text | impute, pivot, stack | band, linear, ordinal |
| `indata-membership` | 25 | rect, rule, text | — | band, linear |
| `interactive-legend` | 454 | rect, rule, symbol, text | filter | linear, ordinal |
| `invert-buckets` | 19 | rule, text | — | band, quantile, quantize, threshold |
| `item-zindex` | 5 | rect, rule, text | — | — |
| `label-limit` | 33 | rect, rule, symbol, text | — | band, linear, ordinal |
| `label-overlap` | 90 | rect, rule, text | — | band, linear |
| `layout-center` | 12 | rect, text | — | ordinal |
| `legend-background` | 19 | group, rect, symbol, text | — | band, linear, ordinal |
| `legend-columns` | 10 | symbol, text | — | ordinal |
| `legend-discretizing` | 66 | rect, rule, symbol, text | — | linear, quantile, quantize, threshold |
| `legend-format-type` | 29 | rect, rule, symbol, text | — | linear, utc |
| `legend-grid-align` | 45 | rect, symbol, text | — | band, linear |
| `legend-stroke-channels` | 24 | line, symbol, text | — | linear, ordinal |
| `legend-style` | 30 | line, rule, symbol, text | — | linear, ordinal |
| `legend-symbol-limit` | 39 | rect, symbol, text | — | band, linear, ordinal |
| `legend-title-anchor` | 59 | rect, symbol, text | — | band, linear, ordinal |
| `legends` | 28 | rect, rule, symbol, text | — | band, linear, ordinal |
| `line-area` | 50 | area, line, rule, symbol, text | — | linear |
| `line-defined-gaps` | 3 | area, line | — | linear |
| `link-paths` | 77 | path, symbol, text | formula, linkpath, stratify, tree, treelinks | ordinal |
| `local-time-dst` | 35 | line, rule, symbol, text | — | linear, time, utc |
| `log-axis-labels` | 154 | rule, text | — | log |
| `log-scale` | 75 | rule, symbol, text | — | log, sqrt |
| `luminance-contrast` | 24 | rect, text | — | band |
| `map-with-tooltip` | 3623 | group, rect, shape, text | filter, formula, geoshape, lookup | quantize |
| `mark-clip` | 39 | line, rule, symbol, text | — | linear |
| `mark-descriptions` | 25 | rect, rule, text | — | band, linear |
| `mark-join-key` | 23 | line, rect, rule, symbol, text | — | band, linear, ordinal |
| `modify-dataset` | 11 | rect, rule, text | — | band, linear |
| `multi-source-pluck` | 14 | rect, rule, text | formula | band, linear |
| `named-range` | 60 | rect, rule, text | stack | band, linear, ordinal |
| `negative-labels` | 45 | rect, rule, symbol, text | — | band, linear, ordinal |
| `nest-treemap` | 28 | rect, text | filter, nest, treemap | ordinal |
| `nested-groups` | 14 | group, line, symbol, text | — | linear |
| `overview-plus-detail` | 71 | area, group, rect, rule, text | — | linear, time |
| `packed-bubble` | 32 | symbol, text | force | linear, ordinal |
| `pacman` | 392 | arc, rect, symbol, text | collect, formula, sequence | band |
| `parallel-coordinates` | 554 | line, rule, text | filter, formula | linear, point |
| `parse-date-patterns` | 27 | line, rule, symbol, text | — | linear, utc |
| `path-marks` | 34 | path, rule, symbol, text | — | linear, point |
| `pi-monte-carlo` | 2148 | arc, group, rule, symbol, text | filter, formula, sequence, window | linear |
| `pie` | 13 | arc, symbol, text | pie | ordinal |
| `platformer` | 7517 | image, rect | formula | linear |
| `polylinear-scales` | 7 | text | formula | band, linear, log, pow, symlog |
| `probability-density` | 533 | area, line, rect, rule, symbol, text | aggregate, density | linear, ordinal |
| `projection-families` | 84 | group, path, symbol, text | geopath, geopoint, graticule | — |
| `projection-fit-composite` | 3 | symbol | geojson, geopoint | — |
| `projection-fit-two-publishers` | 4 | symbol | geojson, geopoint | — |
| `projection-fit` | 13 | shape | formula, geoshape | — |
| `published-signals` | 16 | rect, rule, text | extent | linear |
| `qq-plot` | 332 | rule, symbol, text | formula, quantile | linear |
| `radar` | 31 | line, rule, text | aggregate | linear, ordinal, point |
| `radial-tree-layout` | 755 | path, symbol, text | formula, linkpath, stratify, tree, treelinks | linear |
| `ramp-through-grey` | 20 | rect | — | band, linear |
| `reshape-matrix` | 33 | rect, rule, text | collect, countpattern, cross, formula | band, linear |
| `reshape` | 30 | rect, rule, text | collect, fold, identifier, project | band, linear, ordinal |
| `sample-reservoir` | 65 | rect, rule, text | sample | band, linear |
| `scale-domain-implicit` | 13 | rect, rule, text | — | band, ordinal |
| `scale-domain-raw` | 73 | rule, symbol, text | — | linear |
| `scale-nice-intervals` | 47 | rule, symbol, text | — | linear, utc |
| `scale-variants` | 43 | rule, symbol, text | — | point, pow, symlog |
| `scheme-forms` | 31 | rect, rule, text | — | band, linear, ordinal |
| `scope-shadowing` | 11 | rect, rule, text | — | linear |
| `sequence-lookup` | 27 | line, rect, rule, text | formula, lookup, sequence | band, linear |
| `serpentine-timeline` | 80 | line, symbol, text | filter, flatten, formula, joinaggregate, lookup, project, window | band, linear |
| `share-of-total` | 26 | rect, rule, text | formula, joinaggregate | band, linear |
| `si-prefix-axis` | 63 | rect, rule, text | — | band, linear |
| `signal-transform-params` | 29 | rect, rule, text | aggregate, filter, window | band, linear |
| `size-legend` | 36 | rule, symbol, text | — | linear, ordinal |
| `sorted-domain` | 35 | rect, rule, symbol, text | aggregate | band, linear, ordinal |
| `spherical-measures` | 19 | rule, text | formula | band |
| `stack-diverging` | 45 | rect, rule, symbol, text | stack | band, linear, ordinal |
| `stack-offsets` | 32 | area, rule, symbol, text | stack | linear, ordinal |
| `stacked-bar` | 42 | rect, rule, text | aggregate, stack | band, linear |
| `statistics` | 28 | line, rule, symbol, text | quantile, regression | linear |
| `step-lines` | 24 | area, line, rule, text | — | linear |
| `stock-index-chart` | 53 | line, rule, text | filter, formula, lookup | linear, ordinal, time |
| `symbols-and-curves` | 17 | line, symbol, text | — | linear |
| `text-anchors` | 18 | symbol, text | — | — |
| `text-and-cells` | 28 | path, rect, symbol, text | collect, countpattern, identifier, voronoi | band, linear |
| `text-array-lines` | 6 | text | — | — |
| `time-axis` | 32 | line, rule, symbol, text | — | linear, utc |
| `time-units` | 40 | rect, rule, text | aggregate, timeunit | band, linear |
| `timeunit-dst-scale` | 37 | rect, rule, symbol, text | aggregate, timeunit | linear, time, utc |
| `timeunit-inferred` | 102 | rect, rule, text | aggregate, timeunit | linear, utc |
| `timeunit-units` | 25 | rect, rule, text | aggregate, timeunit | linear, utc |
| `timeunit` | 25 | rect, rule, text | aggregate, timeunit | linear, utc |
| `title-encode` | 11 | group, rect, rule, text | — | band, linear |
| `title-style-block` | 9 | rect, rule, text | — | band, linear |
| `title-style` | 4 | rect, text | — | band, linear |
| `titles` | 28 | rect, rule, symbol, text | — | band, linear, ordinal |
| `trail` | 28 | line, rule, text, trail | — | linear |
| `tree-layouts` | 31 | rule, symbol, text | filter, formula, lookup, pack, stratify, tree | ordinal |
| `treemap` | 18 | rect, text | filter, formula, partition, stratify, treemap | ordinal |
| `trellis-bands` | 32 | rect, text | — | ordinal |
| `trellis-headers` | 8 | group, text | aggregate | — |
| `trellis-layout` | 10 | group, rect | — | — |
| `trend-lines` | 33 | line, rule, symbol, text | loess, regression | linear |
| `u-district-cuisine` | 252 | area, rule, symbol, text | density | band, linear, ordinal |
| `unicode-identifiers` | 1 | text | formula | — |
| `volcano-contours` | 21 | path | geopath, isocontour | linear |
| `watch` | 90 | arc, rule, text | formula, sequence | linear |
| `window-ops` | 18 | rect, text | window | band, linear |
| `window` | 43 | line, rule, symbol, text | window | linear, ordinal, point |
| `world-map` | 178 | shape | geoshape, graticule | — |

## Vega-Lite

| Fixture | Composition | Marks | Transforms |
| --- | --- | --- | --- |
| `aggregate-bar` | single view | bar | — |
| `aggregate-ops` | layer | point, rule, text | — |
| `animated-frames` | single view | point | — |
| `arc-labelled` | layer | arc, text | — |
| `arc-mark-polar-bounds` | hconcat | arc | — |
| `arc-ordinal` | single view | arc | — |
| `arc-radial-histogram` | layer | arc, text | — |
| `arc-stated-radius` | single view | arc | — |
| `area-line-overlay` | single view | area | — |
| `area` | single view | area | — |
| `argmax` | single view | bar | — |
| `axis-values` | layer | point, text | — |
| `band-fraction` | single view | bar | — |
| `bar-corner-radius` | single view | bar | — |
| `bar-heatlane` | layer | bar | — |
| `bar` | single view | bar | — |
| `bin-and-stack-transforms` | single view | rect | — |
| `bin-extent-from-brush` | vconcat | bar | — |
| `binned-axis` | single view | bar | — |
| `binned-band-position` | layer | bar, tick | — |
| `binned-channels` | single view | point | — |
| `binned-facet` | single view | bar | — |
| `binned-legend` | single view | point | — |
| `binned-month-labelled` | layer | bar, text | — |
| `binned-nonposition` | single view | point | — |
| `binned-size` | single view | circle | — |
| `binned-time-unit` | single view | bar | — |
| `boxplot-1d-ticks` | single view | boxplot | — |
| `boxplot-1d` | single view | boxplot | — |
| `boxplot-coloured` | single view | boxplot | — |
| `boxplot-grouped-offset` | single view | boxplot | — |
| `boxplot-no-outliers` | single view | boxplot | — |
| `boxplot-tooltip-plain` | single view | boxplot | — |
| `boxplot-tooltip` | single view | boxplot | — |
| `boxplot` | single view | boxplot | — |
| `colour-domain-order-grouped` | single view | bar | — |
| `colour-domain-order` | single view | bar | — |
| `coloured-boxplot` | single view | boxplot | — |
| `concat-bin-above-formula` | hconcat | bar | — |
| `concat-filtered-branch-order` | hconcat | bar | — |
| `concat-in-facet` | facet, hconcat | bar, point | — |
| `concat-independent-legend` | hconcat | point | — |
| `concat-of-repeats` | repeat, vconcat | bar | — |
| `concat-plot-own-resolve` | layer, vconcat | bar, circle | — |
| `concat-shared-transform` | concat, hconcat | point, text | — |
| `concat-sources` | hconcat | bar, point | — |
| `concat-steps` | hconcat | bar | — |
| `concat-two-bins-one-node` | hconcat | bar | — |
| `concat-with-a-trellis` | facet, vconcat | line | — |
| `concat` | hconcat | bar, point | — |
| `conditional-axis-config` | single view | line | — |
| `conditional-axis` | single view | bar | — |
| `conditional-colour` | single view | bar | — |
| `conditional-test` | single view | bar | — |
| `config-axis-disable` | single view | line | — |
| `config-number-format-type` | facet | point | — |
| `config-tooltip-format` | single view | line | — |
| `connected-scatter` | single view | line | — |
| `crossfilter-binned` | layer, repeat | bar | — |
| `dash-legend` | single view | line | — |
| `date-predicates` | single view | line | — |
| `dated-domain` | single view | bar | — |
| `datum-datetime` | layer | line, rule | — |
| `datum-rules` | layer | bar, rule | — |
| `day-sorted` | single view | circle | — |
| `detail-lines` | single view | line | — |
| `donut` | single view | arc | — |
| `dotted-columns` | single view | point | — |
| `dual-axis` | layer | bar, line | — |
| `empty-title` | single view | bar | — |
| `errorband-single-part` | single view | errorband | — |
| `errorband` | single view | errorband | — |
| `errorbar-iqr` | single view | errorbar | — |
| `errorbar-plain-tooltip` | single view | errorbar | — |
| `errorbar-pre-summarised-ends` | layer | errorbar, point | — |
| `errorbar-pre-summarised-error-2` | layer | errorbar, point | — |
| `errorbar-pre-summarised-error` | layer | errorbar, point | — |
| `errorbar-timeunit` | single view | errorbar | — |
| `errorbar` | single view | errorbar | — |
| `expression-properties` | single view | rect | — |
| `facet-arc` | single view | arc | — |
| `facet-binned-row` | single view | point | — |
| `facet-cell-grid-size` | facet | bar | — |
| `facet-channel-columns` | facet | point | — |
| `facet-concat-size` | facet, hconcat | bar, point | — |
| `facet-cross-cardinality` | facet | rect | — |
| `facet-cross-layer-transforms` | facet, layer | point, rule | — |
| `facet-footer` | single view | bar | — |
| `facet-header-style` | single view | bar | — |
| `facet-header-title-empty` | single view | point | — |
| `facet-in-facet-columns` | facet | point | — |
| `facet-in-facet-concat` | facet, hconcat | bar, point | — |
| `facet-in-facet-deep` | facet | point | — |
| `facet-in-facet-mixed` | facet | point | — |
| `facet-in-facet-rows` | facet | point | — |
| `facet-independent-axis` | facet | point | — |
| `facet-independent-step` | facet | bar | — |
| `facet-independent-unaligned` | single view | bar | — |
| `facet-layer-independent-scale` | facet, layer | line, point | — |
| `facet-legend` | single view | bar | — |
| `facet-no-header` | single view | bar | — |
| `facet-operator` | facet | bar | — |
| `facet-sort-op` | single view | bar | — |
| `facet-sort` | single view | bar | — |
| `facet-spacing` | single view | bar | — |
| `facet-split-layer` | facet, layer | point, rule | — |
| `facet-split-wrapped` | facet | point | — |
| `facet-wrap` | facet | bar | — |
| `facet-wrapped-headerless` | facet | point | — |
| `faceted-density` | single view | area | — |
| `faceted-grid` | single view | bar | — |
| `faceted-rows` | single view | bar | — |
| `faceted` | single view | bar | — |
| `gallery-rules` | vconcat | bar, line | — |
| `generated-sequence` | single view | line | — |
| `geo-brush-one-channel` | single view | circle | — |
| `geo-brush` | single view | circle | — |
| `geo-points` | single view | circle | — |
| `geo-repeat` | repeat | geoshape | — |
| `geo-rules` | layer | circle, rule | — |
| `geo-shapes` | layer | geoshape | — |
| `geo-trellis` | single view | geoshape | — |
| `grouped-bar` | single view | bar | — |
| `grouped-labels` | layer | bar, text | — |
| `grouped-offset-range` | single view | bar | — |
| `histogram` | single view | bar | — |
| `image-marks` | single view | image | — |
| `impute-area` | single view | line | — |
| `impute-mean` | single view | line | — |
| `impute-sequence` | single view | line | — |
| `impute-transform` | single view | line | — |
| `inferred-types` | single view | bar | — |
| `invalid-break-paths-domains` | single view | point | — |
| `invalid-modes` | layer | line, point | — |
| `invalid-shown` | single view | point | — |
| `label-expression` | single view | bar | — |
| `labelled-bar` | layer | bar, text | — |
| `layer-axis-property-merge` | layer | errorband, line | — |
| `layer-axis-title` | layer | point | — |
| `layer-bin-above-filter` | layer | bar | — |
| `layer-errorbar-shared-aggregate` | layer | errorbar, point | — |
| `layer-first-source-is-the-chart` | layer | line, point, rect | — |
| `layer-identity-one-layer` | layer | bar | — |
| `layer-in-layer` | layer | line, point | — |
| `layer-incompatible-colour` | layer | rect, text | — |
| `layer-independent-legend` | layer | circle | — |
| `layer-independent` | layer | bar, rule | — |
| `layer-label-angle-null` | layer | bar, point | — |
| `layer-mixed-style` | layer | bar, rule, text | — |
| `layer-own-data-no-inherit` | layer | bar, rule | — |
| `layer-shared-bin-transform` | layer | bar | — |
| `layer-shared-bin` | layer | bar, point | — |
| `layer-shared-encoding-blank` | layer | bar, rule | — |
| `layer-shared-flow` | layer | bar, text | — |
| `layer-sources` | layer | bar, rule | — |
| `layered` | layer | bar, rule | — |
| `legend-gradient-horizontal` | single view | point | — |
| `legend-label-expr` | single view | bar | — |
| `line-conditional-colour-group` | single view | line | — |
| `line-points` | single view | line | — |
| `line-temporal` | single view | line | — |
| `linked-points` | single view | point | — |
| `log-scale` | single view | point | — |
| `lookup-from-a-selection` | layer | line, point | — |
| `lookup` | single view | bar | — |
| `merged-legend-title` | single view | circle | — |
| `month-predicates` | single view | bar | — |
| `moved-axis` | single view | bar | — |
| `multi-line` | single view | line | — |
| `named-chart` | single view | point | — |
| `named-datasets` | layer | line | — |
| `named-trellis` | facet | point | — |
| `nested-aggregate` | single view | bar | — |
| `nested-concat-sizes` | hconcat, vconcat | point | — |
| `nested-concat-titles` | hconcat, vconcat | point | — |
| `nested-concat` | hconcat, vconcat | bar, point, tick | — |
| `nested-fields` | single view | bar | — |
| `nested-layer` | layer | bar, rule, text | — |
| `normalized-and-whole` | hconcat | bar | — |
| `nudged-bins` | single view | bar | — |
| `offset-band-centred` | single view | bar | — |
| `offset-facet` | single view | bar | — |
| `offset-jitter` | single view | point | — |
| `offset-points` | single view | point | — |
| `offset-variants` | single view | bar | — |
| `offset-without-position` | single view | bar | — |
| `one-dimensional` | vconcat | bar, tick | — |
| `one-field-two-channels` | single view | point | — |
| `opacity-value` | single view | circle | — |
| `painted-view` | single view | rect | — |
| `param-condition` | single view | point | — |
| `params` | layer | bar, rule | — |
| `path-mark-no-dimension` | single view | line | — |
| `pie` | single view | arc | — |
| `point-size` | single view | point | — |
| `quantile-size` | single view | circle | — |
| `radial-rings` | single view | arc | — |
| `ranged-bar` | single view | bar | — |
| `rect-shifted-buckets` | single view | rect | — |
| `repeat-grid` | repeat | point | — |
| `repeat-in-facet` | facet, repeat | point | — |
| `repeat-layer-of-layers` | layer, repeat | line | — |
| `repeat-layer` | layer, repeat | line | — |
| `repeat-over-layer` | layer, repeat | point | — |
| `repeat-selection-per-copy` | repeat | point | — |
| `repeat-splom-shared-filter` | repeat | point | — |
| `repeat` | repeat | bar | — |
| `reshape` | single view | bar | — |
| `resolve-legends` | hconcat | bar, point | — |
| `reversed-bins` | single view | bar | — |
| `rounded-bars` | single view | bar | — |
| `rule-range` | single view | rule | — |
| `rule-spanning` | layer | rule | — |
| `scale-domain-from-param` | single view | point | — |
| `scale-invalid-config` | single view | point | — |
| `scale-overrides` | single view | point | — |
| `scale-padding-stated` | single view | bar | — |
| `scale-range-field` | single view | circle | — |
| `scale-range-from-params` | layer | point | — |
| `scale-union-domain` | layer | point, rect | — |
| `scatter` | single view | point | — |
| `selection-bind-inputs` | single view | circle | — |
| `selection-bind-scales-concat` | vconcat | point | — |
| `selection-bind-scales` | single view | circle | — |
| `selection-filter-timeunit` | layer | bar, rule | — |
| `selection-interval-concat` | vconcat | bar, point | — |
| `selection-interval` | single view | point | — |
| `selection-nearest-tooltip` | single view | circle | — |
| `selection-nearest` | single view | circle | — |
| `selection-point-fields` | single view | point | — |
| `selection-point-projected` | single view | point | — |
| `selection-point` | single view | rect | — |
| `selection-project-conditional-colour` | single view | bar | — |
| `selection-scale-domain` | vconcat | point | — |
| `selection-two-brushes` | single view | circle | — |
| `shared-encoding` | layer | point, rule | — |
| `shifted-aggregate` | single view | bar | — |
| `shifted-buckets` | layer | bar, line | — |
| `signal-guide` | single view | point | — |
| `sort-array` | single view | bar | — |
| `sort-by-channel` | single view | bar | — |
| `sorted-bar` | single view | bar | — |
| `spaced-field-name` | single view | bar | — |
| `splom-brushed` | repeat | point | — |
| `stack-center` | single view | area | — |
| `stack-order-reverse` | vconcat | bar, tick | — |
| `stack-shared-by-detail` | layer | bar, text | — |
| `stacked-area-binned` | single view | area | — |
| `stacked-bar-rounded` | single view | bar | — |
| `stacked-bar` | single view | bar | — |
| `stated-parse` | single view | line | — |
| `step-for-position` | single view | bar | — |
| `styled-labels` | layer | bar, text | — |
| `temporal-domain-bounds` | single view | line | — |
| `temporal-units` | single view | point | — |
| `text-format` | layer | bar, text | — |
| `text-heatmap` | single view | rect | — |
| `theme-axis-and-tick` | layer | text, tick | — |
| `themed` | single view | bar | — |
| `tick-at-a-constant` | single view | tick | — |
| `tick-styled-constant` | layer | text, tick | — |
| `tick` | single view | tick | — |
| `time-unit-band-size` | single view | bar | — |
| `timeunit-band-end` | single view | area | — |
| `timeunit-bar` | single view | bar | — |
| `timeunit-ordinal` | single view | bar | — |
| `timeunit-step` | single view | line | — |
| `timeunit-twice` | single view | line | — |
| `titled-plots` | concat | point | — |
| `tooltip-argmin-field` | single view | bar | — |
| `tooltip-single-list` | single view | point | — |
| `tooltips` | layer | bar, point | — |
| `trail` | single view | trail | — |
| `transforms` | single view | point | — |
| `trellis-cross-sort` | single view | point | — |
| `trellis-header-label-expr` | facet | area | — |
| `trellis-rotated-captions` | single view | bar | — |
| `trellis-selections` | single view | circle | — |
| `trellis-sort-array` | single view | bar | — |
| `trellis-timeunit` | single view | line | — |
| `usermeta` | single view | line | — |
| `vconcat` | vconcat | line, tick | — |
| `view-cursor` | single view | line | — |
