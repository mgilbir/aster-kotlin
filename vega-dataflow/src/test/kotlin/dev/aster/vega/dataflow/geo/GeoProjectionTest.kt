package dev.aster.vega.dataflow.geo

import dev.aster.vega.model.VegaJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The projection pipeline, against path strings taken from d3-geo itself.
 *
 * Every expected string below is what `geoPath(projection)(geometry)` produced on the pinned
 * upstream, and each geometry is here for a reason. The triangle is the ordinary case. The one
 * straddling longitude 180 is cut in two by the antimeridian pre-clip. The long diagonal line is
 * where adaptive resampling shows: two points in, dozens out, each bisected along the great circle
 * until the curve is within a fraction of a pixel. The bare point is drawn as a circle, which is
 * what d3 does with a `Point` geometry. And the large rectangle reaches past what a mercator map
 * can show, so the post-clip has to cut it against the square that holds one turn of the world.
 *
 * A path string compares far more of this than a bounding box would: it is every coordinate, in
 * order, rounded the way upstream rounds them.
 */
class GeoProjectionTest {

  private fun path(projection: Projection, geometry: String): String? {
    val sink = PathStringSink()
    GeoJsonStream.stream(VegaJson.parse(geometry), projection.stream(sink))
    return sink.result()
  }

  @Test
  fun `mercator draws a plain polygon`() {
    val projection = Projections.mercator().scale(150.0).translate(450.0, 250.0)
    assertEquals(
      "M423.82,276.314L476.18,276.314L463.398,236.843L450,196.543L436.602,236.843ZM921.239,-221.239L921.239,-202.002L921.239,-97.668L921.239,7.766L921.239,117.794L921.239,189.52L921.239,250L921.239,310.48L921.239,382.206L921.239,492.234L921.239,721.239L921.239,721.239L-21.239,721.239L-21.239,721.239L-21.239,492.234L-21.239,382.206L-21.239,310.48L-21.239,250L-21.239,189.52L-21.239,117.794L-21.239,7.766L-21.239,-97.668L-21.239,-202.002L-21.239,-221.239L-21.239,-221.239L921.239,-221.239Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-10,-10],[10,-10],[0,20],[-10,-10]]]}",
      ),
    )
  }

  @Test
  fun `mercator draws a polygon across the antimeridian`() {
    val projection = Projections.mercator().scale(150.0).translate(450.0, 250.0)
    assertEquals(
      "M-21.239,223.284L4.941,223.686L4.941,276.314L-21.239,276.716L-21.239,223.284ZM921.239,276.716L895.059,276.314L895.059,223.686L895.059,223.686L921.239,223.284L921.239,276.716Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[170,10],[-170,10],[-170,-10],[170,-10],[170,10]]]}",
      ),
    )
  }

  @Test
  fun `mercator draws a long line, resampled along the great circle`() {
    val projection = Projections.mercator().scale(150.0).translate(450.0, 250.0)
    assertEquals(
      "M135.841,117.794L107.879,131.399L83.301,147.09L61.698,163.853L42.491,181.059L8.976,215.648L-21.239,250M921.239,250L891.024,284.352L857.509,318.941L838.302,336.147L816.699,352.91L792.121,368.601L764.159,382.206",
      path(projection, "{\"type\":\"LineString\",\"coordinates\":[[-120,45],[120,-45]]}"),
    )
  }

  @Test
  fun `mercator draws a bare point, drawn as a circle`() {
    val projection = Projections.mercator().scale(150.0).translate(450.0, 250.0)
    assertEquals(
      "M476.18,196.543m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z",
      path(projection, "{\"type\":\"Point\",\"coordinates\":[10,20]}"),
    )
  }

  @Test
  fun `mercator draws a rectangle wider than the map`() {
    val projection = Projections.mercator().scale(150.0).translate(450.0, 250.0)
    assertEquals(
      "M-21.239,721.239L-21.239,699.352L2.749,697.436L25.581,691.962L46.404,683.641L64.798,673.349L80.716,661.895L94.343,649.913L115.884,625.947L131.711,603.293L143.609,582.486L160.051,546.264L170.767,515.956L178.288,490.081L188.201,447.544L188.201,332.396L188.201,250L188.201,167.604L188.201,52.456L188.201,52.456L178.288,9.919L170.767,-15.956L160.051,-46.264L143.609,-82.486L131.711,-103.293L115.884,-125.947L94.343,-149.913L80.716,-161.895L64.798,-173.349L46.404,-183.641L25.581,-191.962L2.749,-197.436L-21.239,-199.352L-21.239,-221.239L-21.239,-221.239L921.239,-221.239L921.239,-221.239L921.239,-199.352L921.239,-199.352L897.251,-197.436L874.419,-191.962L853.596,-183.641L835.202,-173.349L819.284,-161.895L805.657,-149.913L784.116,-125.947L768.289,-103.293L756.391,-82.486L739.949,-46.264L729.233,-15.956L721.712,9.919L711.799,52.456L711.799,167.604L711.799,250L711.799,332.396L711.799,447.544L721.712,490.081L729.233,515.956L739.949,546.264L756.391,582.486L768.289,603.293L784.116,625.947L805.657,649.913L819.284,661.895L835.202,673.349L853.596,683.641L874.419,691.962L897.251,697.436L921.239,699.352L921.239,721.239L921.239,721.239L-21.239,721.239Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-100,60],[100,60],[100,-60],[-100,-60],[-100,60]]]}",
      ),
    )
  }

  @Test
  fun `a rotated and recentred mercator draws a plain polygon`() {
    val projection =
      Projections.mercator()
        .scale(200.0)
        .translate(400.0, 300.0)
        .rotate(doubleArrayOf(-40.0, 20.0, 10.0))
        .center(15.0, 5.0)
    assertEquals(
      "M176.652,333.204L241.145,309.258L207.281,264.837L169.258,219.509L173.162,277.936ZM975.959,-310.843L975.959,-285.194L975.959,-146.082L975.959,-5.503L975.959,141.201L975.959,236.836L975.959,317.475L975.959,398.115L975.959,493.75L975.959,640.454L975.959,945.794L975.959,945.794L-280.678,945.794L-280.678,945.794L-280.678,640.454L-280.678,493.75L-280.678,398.115L-280.678,317.475L-280.678,236.836L-280.678,141.201L-280.678,-5.503L-280.678,-146.082L-280.678,-285.194L-280.678,-310.843L-280.678,-310.843L975.959,-310.843Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-10,-10],[10,-10],[0,20],[-10,-10]]]}",
      ),
    )
  }

  @Test
  fun `a rotated and recentred mercator draws a polygon across the antimeridian`() {
    val projection =
      Projections.mercator()
        .scale(200.0)
        .translate(400.0, 300.0)
        .rotate(doubleArrayOf(-40.0, 20.0, 10.0))
        .center(15.0, 5.0)
    assertEquals(
      "M804.97,301.747L869.463,325.693L845.992,393.418L811.691,380.968L778.749,367.049Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[170,10],[-170,10],[-170,-10],[170,-10],[170,10]]]}",
      ),
    )
  }

  @Test
  fun `a rotated and recentred mercator draws a long line, resampled along the great circle`() {
    val projection =
      Projections.mercator()
        .scale(200.0)
        .translate(400.0, 300.0)
        .rotate(doubleArrayOf(-40.0, 20.0, 10.0))
        .center(15.0, 5.0)
    assertEquals(
      "M-211.109,235.454L-246.724,249.209L-280.678,264.604M975.959,264.604L929.128,288.724L883.908,313.926L792.645,363.783L743.868,386.374L691.624,405.456L664.107,413.072L635.746,419.055L606.708,423.171L577.232,425.238",
      path(projection, "{\"type\":\"LineString\",\"coordinates\":[[-120,45],[120,-45]]}"),
    )
  }

  @Test
  fun `a rotated and recentred mercator draws a bare point, drawn as a circle`() {
    val projection =
      Projections.mercator()
        .scale(200.0)
        .translate(400.0, 300.0)
        .rotate(doubleArrayOf(-40.0, 20.0, 10.0))
        .center(15.0, 5.0)
    assertEquals(
      "M203.861,205.192m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z",
      path(projection, "{\"type\":\"Point\",\"coordinates\":[10,20]}"),
    )
  }

  @Test
  fun `a rotated and recentred mercator draws a rectangle wider than the map`() {
    val projection =
      Projections.mercator()
        .scale(200.0)
        .translate(400.0, 300.0)
        .rotate(doubleArrayOf(-40.0, 20.0, 10.0))
        .center(15.0, 5.0)
    assertEquals(
      "M23.046,945.794L10.622,929.909L-6.601,902.71L-19.884,877.744L-30.358,854.916L-45.706,814.807L-62.796,782.494L-75.452,753.683L-92.795,704.65L-104.076,664.206L-112.012,629.876L-122.531,573.572L-129.32,528.002L-137.976,455.034L-143.772,395.077L-152.853,288.762L-157.601,234.409L-163.572,173.571L-163.572,173.571L-177.799,132.028L-197.532,84.27L-210.878,57.312L-227.901,27.923L-250.312,-3.94L-264.303,-20.636L-280.678,-37.581L-280.678,-177.631L-280.678,-310.843L-280.678,-310.843L975.959,-310.843L975.959,-310.843L975.959,-177.631L975.959,-37.581L975.959,-37.581L952.814,-57.453L924.988,-76.003L909.195,-84.276L892.17,-91.541L874.016,-97.522L854.921,-101.953L835.155,-104.611L815.059,-105.352L795.011,-104.133L775.38,-101.023L756.493,-96.189L738.602,-89.868L706.389,-73.851L690.199,-57.896L676.291,-42.068L653.903,-11.647L636.864,16.572L623.538,42.507L604.03,88.365L590.281,127.912L571.449,194.529L558.069,251.414L536.022,355.243L524.065,409.196L508.738,469.802L502.612,546.179L497.572,595.141L489.323,657.469L482.709,696.8L472.66,744.881L455.501,806.614L441.453,845.23L420.084,891.135L404.815,917.325L384.771,945.68L384.669,945.794L23.046,945.794Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-100,60],[100,60],[100,-60],[-100,-60],[-100,60]]]}",
      ),
    )
  }

  @Test
  fun `equirectangular draws a plain polygon`() {
    val projection = Projections.equirectangular().scale(120.0).translate(400.0, 250.0)
    assertEquals(
      "M379.056,270.944L420.944,270.944L410.718,239.488L400,208.112L389.282,239.488ZM23.009,61.504L400,61.504L776.991,61.504L776.991,108.628L776.991,155.752L776.991,202.876L776.991,250L776.991,297.124L776.991,344.248L776.991,391.372L776.991,438.496L400,438.496L23.009,438.496L23.009,391.372L23.009,344.248L23.009,297.124L23.009,250L23.009,202.876L23.009,155.752L23.009,108.628L23.009,61.504Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-10,-10],[10,-10],[0,20],[-10,-10]]]}",
      ),
    )
  }

  @Test
  fun `equirectangular draws a polygon across the antimeridian`() {
    val projection = Projections.equirectangular().scale(120.0).translate(400.0, 250.0)
    assertEquals(
      "M23.009,228.74L43.953,229.056L43.953,270.944L23.009,271.26L23.009,228.74ZM776.991,271.26L756.047,270.944L756.047,229.056L756.047,229.056L776.991,228.74L776.991,271.26Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[170,10],[-170,10],[-170,-10],[170,-10],[170,10]]]}",
      ),
    )
  }

  @Test
  fun `equirectangular draws a long line, resampled along the great circle`() {
    val projection = Projections.equirectangular().scale(120.0).translate(400.0, 250.0)
    assertEquals(
      "M148.673,155.752L126.303,163.695L106.641,173.461L89.358,184.586L73.992,196.692L47.181,222.755L23.009,250M776.991,250L752.819,277.245L726.008,303.308L710.642,315.414L693.359,326.539L673.697,336.305L651.327,344.248",
      path(projection, "{\"type\":\"LineString\",\"coordinates\":[[-120,45],[120,-45]]}"),
    )
  }

  @Test
  fun `equirectangular draws a bare point, drawn as a circle`() {
    val projection = Projections.equirectangular().scale(120.0).translate(400.0, 250.0)
    assertEquals(
      "M420.944,208.112m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z",
      path(projection, "{\"type\":\"Point\",\"coordinates\":[10,20]}"),
    )
  }

  @Test
  fun `equirectangular draws a rectangle wider than the map`() {
    val projection = Projections.equirectangular().scale(120.0).translate(400.0, 250.0)
    assertEquals(
      "M776.991,73.495L708.162,75.76L667.293,81.038L645.113,87.557L631.96,94.593L623.386,101.875L617.37,109.292L609.44,124.336L609.44,187.168L609.44,250L609.44,312.832L609.44,375.664L617.37,390.708L623.386,398.125L631.96,405.407L645.113,412.443L667.293,418.962L708.162,424.24L776.991,426.505L776.991,438.496L400,438.496L23.009,438.496L23.009,426.505L91.838,424.24L132.707,418.962L154.887,412.443L168.04,405.407L176.614,398.125L182.63,390.708L190.56,375.664L190.56,312.832L190.56,250L190.56,187.168L190.56,124.336L190.56,124.336L182.63,109.292L176.614,101.875L168.04,94.593L154.887,87.557L132.707,81.038L91.838,75.76L23.009,73.495L23.009,61.504L400,61.504L776.991,61.504Z",
      path(
        projection,
        "{\"type\":\"Polygon\",\"coordinates\":[[[-100,60],[100,60],[100,-60],[-100,-60],[-100,60]]]}",
      ),
    )
  }
}
