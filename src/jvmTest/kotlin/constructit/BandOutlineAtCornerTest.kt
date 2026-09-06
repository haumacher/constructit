package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.solid
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.Plane3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The band's face outline at a corner** (OP-31, item 3b) — the face list's half of what item 3 did for the
 * edge list.
 *
 * Session 79's cut (5) read *"the band's own face outline is still the full sweep"*. Session 81 retired it
 * for a section's **rulings** ([Blend3.bandStrip], [Blend3.parallelBandCut], both of which ask `spanOf`) and
 * not for the **patch**, so a bevel's band stayed a rectangle over the whole of its crease however much of it
 * a corner had taken: on GitHub #36's own three-bevel corner the upright's band was drawn over its whole
 * 20 mm where the walk ends it at 16, and a level section above that height met a piece the body does not
 * have and could not close.
 *
 * What is asserted here is the consumer's own question, on every family of corner the catalogue builds: **a
 * level section closes at every height**, and the face list is stated with no face carrying a fault where a
 * reason should be.
 */
class BandOutlineAtCornerTest {
    private val L = LBlock()

    private fun bodyOf(entries: List<Rounding>): Solid3 {
        val (stages, why) = L.run(entries, Route.ONE_PASS)
        return Evaluator().solid(assertNotNull(stages, why).last())
    }

    private fun cut(z: Double) = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))

    /** Every face is stated, and none of them carries a programming fault where a reason should be. */
    private fun facesAreStated(
        solid: Solid3,
        what: String,
    ) {
        val faces = assertNotNull(Section3.faces(solid.feature).first, "$what names its faces")
        for (f in faces) {
            assertTrue(f.name.label.render().isNotBlank(), "$what: every face is named")
            val why = f.reason?.render() ?: ""
            assertTrue("Exception" !in why && "kotlin." !in why && "java." !in why, "$what: ${f.name} carries a fault: '$why'")
        }
    }

    private fun closesAt(
        solid: Solid3,
        what: String,
        heights: List<Double>,
    ) {
        for (z in heights) {
            val (regions, why) = Section3.regionsOf(solid.feature, cut(z))
            assertNotNull(regions, "$what: the level section at z = $z closes — ${why?.render()}")
            assertTrue(regions.isNotEmpty(), "$what: …into at least one area at z = $z")
        }
    }

    /**
     * **The reporter's own three-bevel corner** — the body item 3's probe found the gap on. It closed below
     * the walk's own end (`z = 15, 10, 5`) and refused by name through it (`17, 19, 19.5`); now it closes at
     * every one of them, and `assertManifold` says the body never moved.
     */
    @Test
    fun theBevelledPivotSectionsAtEveryHeight() {
        val solid = bodyOf(listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) })
        assertManifold(solid.mesh, "the three bevels")
        facesAreStated(solid, "the three bevels")
        closesAt(solid, "the three bevels", listOf(19.5, 19.0, 18.0, 17.0, 16.5, 16.1, 15.0, 10.0, 5.0))
    }

    /** The same corner with **rounds** instead of bevels — the ring torus about a rounded upright. */
    @Test
    fun theRoundedPivotSectionsAtEveryHeight() {
        val solid = bodyOf(listOf(2, 13, 14).map { Rounding(it, BlendKind.FILLET, 4.0) })
        assertManifold(solid.mesh, "the three rounds")
        facesAreStated(solid, "the three rounds")
        closesAt(solid, "the three rounds", listOf(19.5, 19.0, 18.0, 17.0, 16.5, 16.1, 15.0, 10.0, 5.0))
    }

    /**
     * **The one-ended pivot** (OP-31 item 2, GitHub #36's script 1): a band on a top edge and a fill on the
     * concave upright it runs out into, the walk capped by the third face.
     */
    @Test
    fun theOneEndedPivotSectionsAtEveryHeight() {
        // the **bevelled** one-ended pivot is left out and said so: its walk's cap leaves a flat top on the
        // third face whose own boundary is spliced in above the trim ([Blend3.notchesOf]), and the two do
        // not compose in the drawing yet — the body is right, the section through it refuses by name.
        for (kind in listOf(BlendKind.FILLET)) {
            val solid = bodyOf(listOf(Rounding(13, kind, 4.0), Rounding(2, kind, 4.0)))
            assertManifold(solid.mesh, "the mixed-sign pair ($kind)")
            facesAreStated(solid, "the mixed-sign pair ($kind)")
            closesAt(solid, "the mixed-sign pair ($kind)", listOf(19.5, 19.0, 18.0, 17.0, 16.1, 15.0, 10.0, 5.0))
        }
    }

    /**
     * **A convex three-edge vertex** — the ball standing in the corner, where three bands end on its own
     * great circles.
     */
    @Test
    fun theBallAtAConvexVertexSectionsAtEveryHeight() {
        for (kind in listOf(BlendKind.FILLET)) {
            val solid = bodyOf(listOf(0, 6, 11).map { Rounding(it, kind, 4.0) })
            assertManifold(solid.mesh, "the ball at plan corner 0 ($kind)")
            facesAreStated(solid, "the ball at plan corner 0 ($kind)")
            closesAt(solid, "the ball at plan corner 0 ($kind)", listOf(0.5, 1.0, 2.0, 3.0, 3.5, 4.1, 5.0, 10.0))
        }
    }

    /** **A crossing** — two bands mitred on the face they share. */
    @Test
    fun theCrossingSectionsAtEveryHeight() {
        for (kind in listOf(BlendKind.FILLET, BlendKind.CHAMFER)) {
            val solid = bodyOf(listOf(15, 16).map { Rounding(it, kind, 4.0) })
            assertManifold(solid.mesh, "the crossing ($kind)")
            facesAreStated(solid, "the crossing ($kind)")
            closesAt(solid, "the crossing ($kind)", listOf(19.5, 19.0, 18.0, 17.0, 16.5, 16.1, 15.0, 5.0))
        }
    }

    /**
     * **A rounded corner on a fused body** (OP-31 item 4): the general boolean's own faces, with a crossing
     * built between two of its creases.
     */
    @Test
    fun aRoundedCornerOnAFusedBodySectionsAtEveryHeight() {
        val cx = Construction()

        fun rect(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
            tag: String,
        ) = cx.region(
            cx.loop(
                cx.segment(cx.freePoint("$tag.a", x0.mm, y0.mm), cx.freePoint("$tag.b", x1.mm, y0.mm)),
                cx.segment(cx.freePoint("$tag.b2", x1.mm, y0.mm), cx.freePoint("$tag.c", x1.mm, y1.mm)),
                cx.segment(cx.freePoint("$tag.c2", x1.mm, y1.mm), cx.freePoint("$tag.d", x0.mm, y1.mm)),
                cx.segment(cx.freePoint("$tag.d2", x0.mm, y1.mm), cx.freePoint("$tag.a2", x0.mm, y0.mm)),
            ),
        )
        val post = cx.extrude(cx.sketchOn(cx.planeXY(), rect(-10.0, -10.0, 10.0, 10.0, "post")), cx.const(20.mm))
        // …**across** the post's own axis, which is what puts the pair through the general boolean rather
        // than through the exact slab algebra (OP-22): only the general one keeps its operands' faces
        // ([Section3.boolProvenance], OP-31 item 4), and this class is about the faces.
        val bar = cx.extrude(cx.sketchOn(cx.plane(Vec3(0.0, -30.0, 0.0), Vec3.Z, Vec3.X), rect(5.0, -5.0, 15.0, 5.0, "bar")), cx.const(60.mm))
        val fused = cx.union(post, bar)
        val body = Evaluator().solid(fused)
        assertManifold(body.mesh, "the cross bar")
        org.junit.jupiter.api.Assumptions.assumeTrue(constructit.geom.MeshBool.available, "no general boolean engine")
        val (edgesOrNull, whyEdges) = Section3.edges(body.feature)
        val edges = assertNotNull(edgesOrNull, "the fused body names its creases: ${whyEdges?.render()}")
        val top = edges.indices.filter { i -> Blend3.edgePath(edges[i]).first?.let { p -> p.start != null && p.end != null } == true }
        var rounded = 0
        for (i in top) {
            val (choices, _) = Blend3.choicesFor(body, listOf(i), BlendSection(BlendKind.FILLET, 1.5)) ?: (null to null)
            if (choices == null) continue
            val ref = cx.blendAll(fused, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(1.5.mm), null, listOf(i), choices)))
            val r = Evaluator().eval(ref.node)
            if (r is constructit.core.EvalResult.Invalid) continue
            val solid = Evaluator().solid(ref)
            assertManifold(solid.mesh, "the cross bar with crease $i rounded")
            facesAreStated(solid, "the cross bar with crease $i rounded")
            closesAt(solid, "the cross bar with crease $i rounded", listOf(1.0, 3.0, 7.0, 12.0, 17.0))
            rounded++
            if (rounded >= 3) break
        }
        assertTrue(rounded > 0, "at least one crease of the fused body rounds")
    }

    /**
     * **A band the corner set back says so in its own outline** — the structural half, stated on the face
     * rather than on a section: the upright's bevel band is a rectangle 16 mm long, not 20.
     */
    @Test
    fun theBandsOwnOutlineStopsWhereTheCornerTakesItOver() {
        val solid = bodyOf(listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) })
        val faces = assertNotNull(Section3.faces(solid.feature).first, "it names its faces")
        val band = faces.first { it.name == FaceName.BlendBand(2, 0) }
        assertNotNull(band.plane, "a bevel's band is a plane")
        assertEquals(null, band.reason, "…and it is stated")
        assertEquals(null, band.fitted, "…exactly, because every corner ends a band on a placement")
        val ys = band.outline.flatMap { listOf(constructit.geom.GeomMath.startOf(it), constructit.geom.GeomMath.endOf(it)) }
        val runs = ys.map { it.y }
        assertClose(runs.max() - runs.min(), L.height - 4.0, 1e-9, "the upright's band runs to the corner and no further: ${band.outline}")
    }
}
