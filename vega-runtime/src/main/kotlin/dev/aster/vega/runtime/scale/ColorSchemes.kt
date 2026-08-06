package dev.aster.vega.runtime.scale

import dev.aster.vega.scene.SceneColor

/**
 * Vega's named colour schemes.
 *
 * Only the categorical ones are here. Their values were read out of `vega.scheme(name)` rather than
 * transcribed from documentation, so they are exact.
 *
 * The continuous ramps — `blues`, `viridis`, `magma` and the rest — are deliberately absent. Each
 * needs d3's own multi-stop interpolator table, and Vega additionally samples them over a default
 * extent of roughly `[0.2, 1]` rather than the full range, so `blues` at the bottom of a domain is
 * `interpolateBlues(0.2)`, not `interpolateBlues(0)`. Guessing either part would be wrong in a way
 * that looks plausible, so a ramp scheme is reported instead. [isKnownRamp] lets the caller say
 * which.
 */
public object ColorSchemes {

  public val categorical: Map<String, List<SceneColor>> =
    mapOf(
        "category10" to "1f77b4,ff7f0e,2ca02c,d62728,9467bd,8c564b,e377c2,7f7f7f,bcbd22,17becf",
        "category20" to
          "1f77b4,aec7e8,ff7f0e,ffbb78,2ca02c,98df8a,d62728,ff9896,9467bd,c5b0d5," +
            "8c564b,c49c94,e377c2,f7b6d2,7f7f7f,c7c7c7,bcbd22,dbdb8d,17becf,9edae5",
        "category20b" to
          "393b79,5254a3,6b6ecf,9c9ede,637939,8ca252,b5cf6b,cedb9c,8c6d31,bd9e39," +
            "e7ba52,e7cb94,843c39,ad494a,d6616b,e7969c,7b4173,a55194,ce6dbd,de9ed6",
        "category20c" to
          "3182bd,6baed6,9ecae1,c6dbef,e6550d,fd8d3c,fdae6b,fdd0a2,31a354,74c476," +
            "a1d99b,c7e9c0,756bb1,9e9ac8,bcbddc,dadaeb,636363,969696,bdbdbd,d9d9d9",
        "tableau10" to "4c78a8,f58518,e45756,72b7b2,54a24b,eeca3b,b279a2,ff9da6,9d755d,bab0ac",
        "tableau20" to
          "4c78a8,9ecae9,f58518,ffbf79,54a24b,88d27a,b79a20,f2cf5b,439894,83bcb6," +
            "e45756,ff9d98,79706e,bab0ac,d67195,fcbfd2,b279a2,d6a5c9,9e765f,d8b5a5",
        "accent" to "7fc97f,beaed4,fdc086,ffff99,386cb0,f0027f,bf5b17,666666",
        "dark2" to "1b9e77,d95f02,7570b3,e7298a,66a61e,e6ab02,a6761d,666666",
        "paired" to
          "a6cee3,1f78b4,b2df8a,33a02c,fb9a99,e31a1c,fdbf6f,ff7f00,cab2d6,6a3d9a,ffff99,b15928",
        "pastel1" to "fbb4ae,b3cde3,ccebc5,decbe4,fed9a6,ffffcc,e5d8bd,fddaec,f2f2f2",
        "pastel2" to "b3e2cd,fdcdac,cbd5e8,f4cae4,e6f5c9,fff2ae,f1e2cc,cccccc",
        "set1" to "e41a1c,377eb8,4daf4a,984ea3,ff7f00,ffff33,a65628,f781bf,999999",
        "set2" to "66c2a5,fc8d62,8da0cb,e78ac3,a6d854,ffd92f,e5c494,b3b3b3",
        "set3" to
          "8dd3c7,ffffb3,bebada,fb8072,80b1d3,fdb462,b3de69,fccde5,d9d9d9,bc80bd,ccebc5,ffed6f",
        "observable10" to "4269d0,efb118,ff725c,6cc5b0,3ca951,ff8ab7,a463f2,97bbf5,9c6b4e,9498a0",
      )
      .mapValues { (_, hex) -> hex.split(',').map { SceneColor.parse("#$it")!! } }

  /** Ramp schemes upstream provides that this engine reports rather than approximating. */
  private val rampNames: Set<String> =
    setOf(
      "blues",
      "greens",
      "greys",
      "oranges",
      "purples",
      "reds",
      "bluegreen",
      "bluepurple",
      "greenblue",
      "orangered",
      "purpleblue",
      "purplebluegreen",
      "purplered",
      "redpurple",
      "yellowgreen",
      "yelloworangebrown",
      "yelloworangered",
      "blueorange",
      "brownbluegreen",
      "purplegreen",
      "pinkyellowgreen",
      "purpleorange",
      "redblue",
      "redgrey",
      "redyellowblue",
      "redyellowgreen",
      "spectral",
      "viridis",
      "magma",
      "inferno",
      "plasma",
      "cividis",
      "turbo",
      "bluepurple",
      "rainbow",
      "sinebow",
      "browns",
      "tealblues",
      "teals",
      "warmgreys",
      "goldgreen",
      "goldorange",
      "goldred",
      "lightgreyred",
      "lightgreyteal",
      "lightmulti",
      "lightorange",
      "lighttealblue",
      "darkblue",
      "darkgold",
      "darkgreen",
      "darkmulti",
      "darkred",
    )

  public fun categoricalOrNull(name: String): List<SceneColor>? = categorical[name.lowercase()]

  /** True when [name] is a scheme upstream has but this engine does not, worth saying so. */
  public fun isKnownRamp(name: String): Boolean = name.lowercase() in rampNames

  public val categoricalNames: Set<String>
    get() = categorical.keys
}
