package dev.aster.vega.runtime.scale

import dev.aster.vega.scene.SceneColor

/**
 * Vega's named colour schemes.
 *
 * Only the categorical ones are here. Their values were read out of `vega.scheme(name)` rather than
 * transcribed from documentation, so they are exact.
 *
 * The continuous ramps are here too, and they are **Vega's own tables, not d3's** — the same lesson
 * the symbol shapes taught. `blues` at the bottom of a domain is `#cfe1f2`, a fifth of the way into
 * d3's own `interpolateBlues`, because Vega's table starts there rather than at white. That looked
 * like a scale-level extent of `[0.2, 1]` applied on top of d3, and reading
 * `vega-scale/palettes.js` is what showed it is not: the narrowing is baked into the table, and
 * nothing at the scale level does it.
 *
 * Once the table is right the interpolation is ordinary: Vega joins the stops with plain piecewise
 * RGB, which is what [SequentialColorScale] already does.
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

  /**
   * The stops of each continuous scheme, as Vega's own six-hex-digit runs.
   *
   * Transcribed from `vega-scale/src/palettes.js` rather than from d3, because they are not the
   * same colours — see the note on this object.
   */
  private val rampHex: Map<String, String> =
    mapOf(
      "blues" to "cfe1f2bed8eca8cee58fc1de74b2d75ba3cf4592c63181bd206fb2125ca40a4a90",
      "greens" to "d3eecdc0e6baabdda594d3917bc77d60ba6c46ab5e329a512089430e7735036429",
      "greys" to "e2e2e2d4d4d4c4c4c4b1b1b19d9d9d8888887575756262624d4d4d3535351e1e1e",
      "oranges" to "fdd8b3fdc998fdb87bfda55efc9244f87f2cf06b18e4580bd14904b93d029f3303",
      "purples" to "e2e1efd4d4e8c4c5e0b4b3d6a3a0cc928ec3827cb97566ae684ea25c3696501f8c",
      "reds" to "fdc9b4fcb49afc9e80fc8767fa7051f6573fec3f2fdc2a25c81b1db21218970b13",
      "bluegreen" to "d5efedc1e8e0a7ddd18bd2be70c6a958ba9144ad77319c5d2089460e7736036429",
      "bluepurple" to "ccddecbad0e4a8c2dd9ab0d4919cc98d85be8b6db28a55a6873c99822287730f71",
      "greenblue" to "d3eecec5e8c3b1e1bb9bd8bb82cec269c2ca51b2cd3c9fc7288abd1675b10b60a1",
      "orangered" to "fddcaffdcf9bfdc18afdad77fb9562f67d53ee6545e24932d32d1ebf130da70403",
      "purpleblue" to "dbdaebc8cee4b1c3de97b7d87bacd15b9fc93a90c01e7fb70b70ab056199045281",
      "purplebluegreen" to "dbd8eac8cee4b0c3de93b7d872acd1549fc83892bb1c88a3097f8702736b016353",
      "purplered" to "dcc9e2d3b3d7ce9eccd186c0da6bb2e14da0e23189d91e6fc61159ab07498f023a",
      "redpurple" to "fccfccfcbec0faa9b8f98faff571a5ec539ddb3695c41b8aa908808d0179700174",
      "yellowgreen" to "e4f4acd1eca0b9e2949ed68880c97c62bb6e47aa5e3297502083440e723b036034",
      "yelloworangebrown" to "feeaa1fedd84fecc63feb746fca031f68921eb7215db5e0bc54c05ab3d038f3204",
      "yelloworangered" to "fee087fed16ffebd59fea849fd903efc7335f9522bee3423de1b20ca0b22af0225",
      "blueorange" to "134b852f78b35da2cb9dcae1d2e5eff2f0ebfce0bafbbf74e8932fc5690d994a07",
      "brownbluegreen" to "704108a0651ac79548e3c78af3e6c6eef1eac9e9e48ed1c74da79e187a72025147",
      "purplegreen" to "5b1667834792a67fb6c9aed3e6d6e8eff0efd9efd5aedda971bb75368e490e5e29",
      "purpleorange" to "4114696647968f83b7b9b4d6dadbebf3eeeafce0bafbbf74e8932fc5690d994a07",
      "redblue" to "8c0d25bf363adf745ef4ae91fbdbc9f2efeed2e5ef9dcae15da2cb2f78b3134b85",
      "redgrey" to "8c0d25bf363adf745ef4ae91fcdccbfaf4f1e2e2e2c0c0c0969696646464343434",
      "yellowgreenblue" to "eff9bddbf1b4bde5b594d5b969c5be45b4c22c9ec02182b82163aa23479c1c3185",
      "redyellowblue" to "a50026d4322cf16e43fcac64fedd90faf8c1dcf1ecabd6e875abd04a74b4313695",
      "redyellowgreen" to "a50026d4322cf16e43fcac63fedd8df9f7aed7ee8ea4d86e64bc6122964f006837",
      "pinkyellowgreen" to "8e0152c0267edd72adf0b3d6faddedf5f3efe1f2cab6de8780bb474f9125276419",
      "spectral" to "9e0142d13c4bf0704afcac63fedd8dfbf8b0e0f3a1a9dda269bda94288b55e4fa2",
      "viridis" to
        "440154470e61481a6c482575472f7d443a834144873d4e8a39568c35608d31688e2d708e2a788e27818e" +
          "23888e21918d1f988b1fa08822a8842ab07f35b77943bf7154c56866cc5d7ad1518fd744a5db36bcdf27" +
          "d2e21be9e51afde725",
      "magma" to
        "0000040404130b0924150e3720114b2c11603b0f704a107957157e651a80721f817f24828c29819a2e80" +
          "a8327db6377ac43c75d1426fde4968e95462f1605df76f5cfa7f5efc8f65fe9f6dfeaf78febf84fece91" +
          "fddea0fcedaffcfdbf",
      "inferno" to
        "0000040403130c0826170c3b240c4f330a5f420a68500d6c5d126e6b176e781c6d86216b932667a12b62" +
          "ae305cbb3755c73e4cd24644dd513ae65c30ed6925f3771af8850ffb9506fca50afcb519fac62df6d645" +
          "f2e661f3f484fcffa4",
      "plasma" to
        "0d088723069033059742039d5002a25d01a66a00a87801a88405a7900da49c179ea72198b12a90ba3488" +
          "c33d80cb4779d35171da5a69e16462e76e5bed7953f2834cf68f44fa9a3dfca636fdb32ffec029fcce25" +
          "f9dc24f5ea27f0f921",
      "cividis" to
        "00205100235800265d002961012b65042e670831690d346b11366c16396d1c3c6e213f6e26426e2c456e" +
          "31476e374a6e3c4d6e42506e47536d4c566d51586e555b6e5a5e6e5e616e62646f66676f6a6a706e6d71" +
          "7270717573727976737c79747f7c75827f758682768985778c8877908b78938e789691789a94789e9778" +
          "a19b78a59e77a9a177aea575b2a874b6ab73bbaf71c0b26fc5b66dc9b96acebd68d3c065d8c462ddc85f" +
          "e2cb5ce7cf58ebd355f0d652f3da4ff7de4cfae249fce647",
      "rainbow" to
        "6e40aa883eb1a43db3bf3cafd83fa4ee4395fe4b83ff576eff6659ff7847ff8c38f3a130e2b72fcfcc36" +
          "bee044aff05b8ff4576ff65b52f6673af27828ea8d1ddfa319d0b81cbecb23abd82f96e03d82e14c6edb" +
          "5a5dd0664dbf6e40aa",
      "sinebow" to
        "ff4040fc582af47218e78d0bd5a703bfbf00a7d5038de70b72f41858fc2a40ff402afc5818f4720be78d" +
          "03d5a700bfbf03a7d50b8de71872f42a58fc4040ff582afc7218f48d0be7a703d5bf00bfd503a7e70b8d" +
          "f41872fc2a58ff4040",
      "turbo" to
        "23171b32204a3e2a71453493493eae4b49c54a53d7485ee44569ee4074f53c7ff8378af93295f72e9ff4" +
          "2ba9ef28b3e926bce125c5d925cdcf27d5c629dcbc2de3b232e9a738ee9d3ff39347f68950f9805afc77" +
          "65fd6e70fe667cfd5e88fc5795fb51a1f84badf545b9f140c5ec3cd0e637dae034e4d931ecd12ef4c92b" +
          "fac029ffb626ffad24ffa223ff9821ff8d1fff821dff771cfd6c1af76118f05616e84b14df4111d5380f" +
          "cb2f0dc0260ab61f07ac1805a313029b0f00950c00910b00",
      "browns" to "eedbbdecca96e9b97ae4a865dc9856d18954c7784cc0673fb85536ad44339f3632",
      "tealblues" to "bce4d89dd3d181c3cb65b3c245a2b9368fae347da0306a932c5985",
      "teals" to "bbdfdfa2d4d58ac9c975bcbb61b0af4da5a43799982b8b8c1e7f7f127273006667",
      "warmgreys" to "dcd4d0cec5c1c0b8b4b3aaa7a59c9998908c8b827f7e7673726866665c5a59504e",
      "goldgreen" to "f4d166d5ca60b6c35c98bb597cb25760a6564b9c533f8f4f33834a257740146c36",
      "goldorange" to "f4d166f8be5cf8aa4cf5983bf3852aef701be2621fd65322c54923b142239e3a26",
      "goldred" to "f4d166f6be59f9aa51fc964ef6834bee734ae56249db5247cf4244c43141b71d3e",
      "lightgreyred" to "efe9e6e1dad7d5cbc8c8bdb9bbaea9cd967ddc7b43e15f19df4011dc000b",
      "lightgreyteal" to "e4eaead6dcddc8ced2b7c2c7a6b4bc64b0bf22a6c32295c11f85be1876bc",
      "lightmulti" to "e0f1f2c4e9d0b0de9fd0e181f6e072f6c053f3993ef77440ef4a3c",
      "lightorange" to "f2e7daf7d5baf9c499fab184fa9c73f68967ef7860e8645bde515bd43d5b",
      "lighttealblue" to "e3e9e0c0dccf9aceca7abfc859afc0389fb9328dad2f7ca0276b95255988",
      "darkblue" to "3232322d46681a5c930074af008cbf05a7ce25c0dd38daed50f3faffffff",
      "darkgold" to "3c3c3c584b37725e348c7631ae8b2bcfa424ecc31ef9de30fff184ffffff",
      "darkgreen" to "3a3a3a215748006f4d048942489e4276b340a6c63dd2d836ffeb2cffffaa",
      "darkmulti" to "3737371f5287197d8c29a86995ce3fffe800ffffff",
      "darkred" to "3434347036339e3c38cc4037e75d1eec8620eeab29f0ce32ffeb2c",
    )

  public val ramps: Map<String, List<SceneColor>> = rampHex.mapValues { (_, hex) ->
    hex.chunked(6).mapNotNull { SceneColor.parse("#$it") }
  }

  public fun categoricalOrNull(name: String): List<SceneColor>? = categorical[name.lowercase()]

  /** The stops of a continuous scheme, for a scale to interpolate between. */
  public fun rampOrNull(name: String): List<SceneColor>? = ramps[name.lowercase()]

  /** True when [name] is a scheme upstream has but this engine does not, worth saying so. */
  public fun isKnownRamp(name: String): Boolean = name.lowercase() in ramps

  public val categoricalNames: Set<String>
    get() = categorical.keys
}
