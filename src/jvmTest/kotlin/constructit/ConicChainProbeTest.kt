package constructit

import constructit.core.Evaluator
import constructit.core.ScalarValue
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.ellipse
import constructit.dsl.point
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.Glb
import constructit.exchange.Stl
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.deg
import constructit.units.mm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probes on conics, composing them with the week's other packages: the **exact section ellipse under a
 * datum-angle re-edit** (the analytic path must re-derive, never re-score), and an **elliptic extrusion
 * leaving through the export seam**, its volume read back from the STL's own triangles.
 */
class ConicChainProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** The tilted section's ellipse re-derives analytically when the datum's angle parameter moves. */
    @Test
    fun theSectionEllipseFollowsTheDatumAngleExactly() {
        val c = Construction()
        val solid =
            c.extrude(
                c.sketchOn(c.planeXY(), c.region(c.loop(c.circleCR(c.freePoint("o", 0.0.mm, 100.0.mm), c.parameter("r", 30.0.mm))))),
                c.parameter("h", 80.0.mm),
            )
        val hinge = c.lineThrough(c.freePoint("a", 0.0.mm, 0.0.mm), c.freePoint("b", 10.0.mm, 0.0.mm))
        val t = c.parameter("t", 30.0.deg)
        val section = c.section(solid, c.datumPlane(c.planeXY(), hinge, t))
        val i =
            assertNotNull((Evaluator().valueOf(section) as? constructit.core.SectionValue)?.section)
                .edges.indexOfFirst { it.curve is constructit.geom.ProfileElement.EllipseE }
        assertTrue(i >= 0)
        val curve = c.sectionEllipse(section, i)
        val centre = c.ellipseCenter(curve)

        assertClose(Evaluator().ellipse(curve).major, 30.0 / cos(PI / 6.0), tol = 1e-12, msg = "r / cos 30")
        assertClose(Evaluator().point(centre).y, 100.0 / cos(PI / 6.0), tol = 1e-12, msg = "the centre at 30")

        // at 45° the plane leaves through the cylinder's top end (z = 130·tan45 > 80): the input must go
        // invalid WITH the ends spoken — never a silently wrong ellipse
        (t.node as constructit.core.SourceNode).value = ScalarValue(45.0.deg)
        val why = Evaluator().eval(curve.node)
        assertTrue(why is constructit.core.EvalResult.Invalid, "the cut leaves through the end: $why")
        assertTrue((why as constructit.core.EvalResult.Invalid).reason.contains("end"), "the reason names the ends: ${why.reason}")

        // …and it heals (OP-3): back inside the barrel, the analytic path re-derives to full precision —
        // no staleness, no re-scoring, no approximation sneaking in at a new angle
        (t.node as constructit.core.SourceNode).value = ScalarValue(20.0.deg)
        assertClose(Evaluator().ellipse(curve).major, 30.0 / cos(20.0 * PI / 180.0), tol = 1e-12, msg = "r / cos 20")
        assertClose(Evaluator().ellipse(curve).minor, 30.0, tol = 1e-12, msg = "the minor stays the radius")
        assertClose(Evaluator().point(centre).y, 100.0 / cos(20.0 * PI / 180.0), tol = 1e-12, msg = "the centre followed")
    }

    /** An ellipse drawn, extruded and exported: the STL's own triangles carry the same body. */
    @Test
    fun anEllipticExtrusionLeavesThroughTheExportSeam() {
        val ed = Editor()
        ed.setTool(Tools.ELLIPSE)
        ed.click(Vec2(50.0, 50.0))
        ed.click(Vec2(110.0, 50.0))
        ed.click(Vec2(50.0, 80.0))
        ed.setTool(Tools.EXTRUDE)
        for (ch in "50") ed.key(ch.toString())
        ed.key("Enter")
        ed.click(Vec2(110.0, 50.0))
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.lastOrNull()
        assertTrue(part != null, "the elliptic prism was built: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        val vol = Geom3.volume(Evaluator().solid(part.ref as SolidRef).mesh)
        val analytic = PI * 60.0 * 30.0 * 50.0
        assertTrue(vol < analytic && vol > 0.995 * analytic, "an inscribed tessellation, close below pi*a*b*h: $vol vs $analytic")

        val scene = ExportScene.extract(ed.doc, "elliptic")
        assertEquals(1, scene.nodes.size, "one body: ${scene.notes}")
        val stl = Stl.write(scene)
        val b = ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80)
        val n = b.getInt()
        var stlVol = 0.0
        repeat(n) {
            repeat(3) { b.getFloat() }
            val v = Array(3) { doubleArrayOf(b.getFloat().toDouble(), b.getFloat().toDouble(), b.getFloat().toDouble()) }
            b.getShort()
            stlVol += (
                v[0][0] * (v[1][1] * v[2][2] - v[2][1] * v[1][2]) -
                    v[1][0] * (v[0][1] * v[2][2] - v[2][1] * v[0][2]) +
                    v[2][0] * (v[0][1] * v[1][2] - v[1][1] * v[0][2])
            ) / 6.0
        }
        assertClose(stlVol, vol, tol = 1.0, msg = "the STL is the same elliptic body (float32)")
        assertEquals("glTF", Glb.write(scene).copyOfRange(0, 4).decodeToString())

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "ellipse + extrude replays byte-equal")
    }
}
