package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Probe review of slice 3 — the composition no package saw end to end: a **blend whose radius is an
 * expression**, dressing a partial revolve as a `Feature3.Blend`, whose **cap trim must move when the
 * master parameter moves**; then a second blend chained onto the dressed body sharing the same derived
 * radius, and the whole story judged through the file.
 */
class BlendFeatureProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun solidOf(el: Element) = Evaluator().solid(el.ref as SolidRef)

    private fun volumeOf(el: Element): Double {
        val mesh = solidOf(el).mesh
        assertManifold(mesh, "a probed body")
        return Geom3.volume(mesh)
    }

    /** The largest distance from the revolve's own x-axis over the cap patch's outline corners. */
    private fun capReach(el: Element): Double {
        val (faces, why) = Section3.faces(solidOf(el).feature)
        assertNull(why, "the dressed body names its faces")
        val cap =
            assertNotNull(
                assertNotNull(faces).firstOrNull { it.name is FaceName.RevolveCap },
                "a partial revolve keeps its caps",
            )
        val plane = assertNotNull(cap.plane, "the cap stays a plane")
        var reach = 0.0
        for (piece in cap.outline) {
            for (p in listOf(constructit.geom.GeomMath.startOf(piece), constructit.geom.GeomMath.endOf(piece))) {
                val w = plane.toWorld(p)
                reach = maxOf(reach, sqrt(w.y * w.y + w.z * w.z))
            }
        }
        return reach
    }

    /**
     * The whole session in one drawing: a quarter ring, its outer cap edge filleted with a radius **bound to
     * `d/4`**, dressed as a feature whose cap trim is analytic — so editing `d` must move the trim, re-round
     * the body, and re-say the band's cylinder; a second fillet on the inner edge chains onto the dressed
     * body sharing the same derived radius; and the file tells the same story it was told.
     */
    @Test
    fun aDerivedRadiusMovesTheTrimTheBandAndTheChain() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(60.0, 25.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(30.0, 15.0))
        ed.click(Vec2(10.0, 0.0))
        val base = ed.solids().single()
        val baseVolume = volumeOf(base)
        assertClose(baseVolume, 6000.0 * PI, tol = 30.0, msg = "the quarter ring")

        val d = ed.doc.newParameter("d", 8.0.mm)
        val r = ed.doc.newParameter("r", 1.0.mm)
        assertTrue(ed.doc.bindParameter(r, "d/4"), "the radius derives from d: ${ed.doc.note}")

        // the outer cap edge, filleted with the derived radius — the dressed feature, not a mesh boolean
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(30.0, 25.0))
        val dressed = ed.solids().last()
        assertIs<constructit.geom.Feature3.Blend>(solidOf(dressed).feature, "the analytic tier: ${ed.statusHint}")

        val quarter = (1 - PI / 4) * 60.0
        val removedAt2 = baseVolume - volumeOf(dressed)
        assertTrue(
            removedAt2 in (quarter * 4.0 * 0.85)..(quarter * 4.0 * 1.35),
            "the quarter-round at r = d/4 = 2: $removedAt2 vs ${quarter * 4.0}",
        )
        // the outer leg is a cylinder, not a plane: the cap tangency lands at sqrt((25 − r)² − r²)
        assertClose(capReach(dressed), sqrt(23.0 * 23.0 - 4.0), tol = 1e-6, msg = "the cap outline is trimmed to the exact tangency")
        val band =
            assertNotNull(
                Section3.faces(solidOf(dressed).feature).first!!.last { it.name is FaceName.BlendBand }.surface,
                "the band says its surface",
            )
        assertClose(assertIs<Revolve3.Band.Cylinder>(band.band).r, 2.0, tol = 1e-9, msg = "a cylinder of the derived radius")

        // one edit of the master: the trim, the band and the volume all follow
        ed.doc.setParameter(d, 12.0.mm)
        val removedAt3 = baseVolume - volumeOf(dressed)
        assertTrue(
            removedAt3 in (quarter * 9.0 * 0.85)..(quarter * 9.0 * 1.35),
            "the same body re-rounded at r = 3: $removedAt3 vs ${quarter * 9.0}",
        )
        assertClose(capReach(dressed), sqrt(22.0 * 22.0 - 9.0), tol = 1e-6, msg = "the trim moved with the parameter")
        val band3 =
            assertNotNull(Section3.faces(solidOf(dressed).feature).first!!.last { it.name is FaceName.BlendBand }.surface)
        assertClose(assertIs<Revolve3.Band.Cylinder>(band3.band).r, 3.0, tol = 1e-9, msg = "the band re-says its radius")

        // a second fillet on the inner edge, chained onto the dressed body, sharing the same derived radius
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(30.0, 15.0))
        val chained = ed.solids().last()
        val removedBoth = baseVolume - volumeOf(chained)
        assertTrue(
            removedBoth in (quarter * 18.0 * 0.85)..(quarter * 18.0 * 1.35),
            "two quarter-rounds of one derived radius: $removedBoth vs ${quarter * 18.0}",
        )

        // the file tells the same story
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "byte-equal round trip")
        val loaded = DocumentFormat.load(once)
        val tip = loaded.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val tipMesh = (Evaluator().eval(tip.ref.node) as? EvalResult.Ok)?.let { Evaluator().solid(tip.ref as SolidRef).mesh }
        val reloadedVolume = Geom3.volume(assertNotNull(tipMesh, "the reloaded chain builds"))
        assertClose(reloadedVolume, volumeOf(chained), tol = 1e-6, msg = "the reloaded drawing is the same drawing")
    }
}
