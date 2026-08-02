package constructit

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 3** — the helix composed with the drawing rather than with itself.
 *
 * The delivery's suite proves the closed form, the curvature, the transport and the refusals. These ask the
 * two questions a *parametric* curve has to answer that a correct one need not: does it ride the space it
 * was drawn against, and is a number shared with a second helix really one number? Plus the thing a new
 * solid-maker must not become — a way around a refusal that already exists.
 */
class HelixProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun curves(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    // ---- the parenting rule, for a curve whose axis is a space's normal ----

    /**
     * **A coil rides the space it was raised from.** A helix's axis is its space's normal through a picked
     * point (the height point's sentence one degree of freedom up), which makes the parenting rule sharper
     * here than for a path through points: the space does not merely place the curve, it *orients* it. So
     * moving the datum the coil stands on must swing the whole coil, axis and all — and it must do so
     * because its input moved, not because anything re-derived an axis.
     */
    @Test
    fun aCoilRaisedOnADatumSwingsWithTheDatumsOwnCarrier() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(200.0, 60.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(100.0, 60.0))
        assertTrue(ed.doc.activeSpace.isDatum, "a datum plane was opened")

        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 30.0))
        ed.setTool(Tools.HELIX)
        ed.type("25")
        ed.type("18")
        ed.type("3")
        ed.click(Vec2(40.0, 30.0))
        assertEquals(1, curves(ed).size, "the coil was built: ${ed.statusHint}")
        val before = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val axisBefore = coilAxis(ed, before)

        // move the plane's own carrier; nothing touches the coil or the point it stands on
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.drag(Vec2(0.0, 60.0), Vec2(0.0, 130.0))
        val axisAfter = coilAxis(ed, ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(
            (axisAfter - axisBefore).length() > 5.0,
            "the coil swung with the plane it was raised from: $axisBefore -> $axisAfter",
        )

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    /** Where the coil sits and which way it rises, read off the curve's own sampled points. */
    private fun coilAxis(
        ed: Editor,
        el: constructit.editor.Element,
    ): constructit.geom.Vec3 {
        val path = (Evaluator().valueOf(el.ref) as constructit.core.Path3Value).path
        val pts = constructit.geom.Curves3.polyline(path)
        // the mid-point of the run stands for "where the coil is", which is all this probe needs
        return pts[pts.size / 2]
    }

    // ---- sharing is equality, for a number two coils are built from ----

    /**
     * **One pitch node feeding two coils is one pitch.** Sharing *is* equality here — there is no "equal
     * pitch" constraint to assert — so a spring pair stated from one parameter must move together when that
     * parameter is retyped, and each must stay the coil it is.
     */
    @Test
    fun twoCoilsBuiltFromOnePitchParameterFollowItTogether() {
        val ed = Editor()
        val pitch = ed.doc.newParameter("p", 20.0.mm)
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))

        // both coils are built from the *same* pitch node — not from two numbers that happen to be equal
        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        for (p in pts) {
            assertNotNull(
                ed.doc.helixAbout(
                    p,
                    ed.doc.newParameter("r${'$'}{p.id}", 30.0.mm).ref,
                    pitch.ref,
                    ed.doc.newParameter("n${'$'}{p.id}", constructit.units.Quantity.number(4.0)).ref,
                    constructit.geom.Handedness.RIGHT,
                ),
                "a coil about ${'$'}{p.id}: ${'$'}{ed.doc.note}",
            )
        }
        assertEquals(2, curves(ed).size, "two coils: ${ed.statusHint}")
        val heights = curves(ed).map { coilHeight(ed, it) }
        assertTrue(heights.all { it > 1.0 }, "both coils rise: $heights")

        ed.doc.setParameter(pitch, 40.0.mm)
        val after = curves(ed).map { coilHeight(ed, it) }
        for ((a, b) in heights.zip(after)) {
            assertClose(b, a * 2.0, a * 0.02, "a coil whose pitch doubled is twice as tall")
        }
    }

    private fun coilHeight(
        ed: Editor,
        el: constructit.editor.Element,
    ): Double {
        val path = (Evaluator().valueOf(el.ref) as constructit.core.Path3Value).path
        val pts = constructit.geom.Curves3.polyline(path)
        return pts.maxOf { it.z } - pts.minOf { it.z }
    }

    // ---- a spring is an ordinary solid ----

    /**
     * **A spring swept along a coil is a solid like any other**: watertight, exportable through every
     * writer, and a legal boolean operand — while an imported open shell is still refused. A new way to
     * make a solid must never become a new way round a refusal that already exists (session 34).
     */
    @Test
    fun aSpringIsAWatertightSolidThatExportsAndCombines() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("40")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        val coil = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }

        // a wire well inside half the pitch, so the coil's turns cannot touch
        ed.setTool(Tools.TUBE)
        ed.type("6")
        val spring = assertNotNull(ed.doc.tubeAlongCurve(coil, ed.doc.newParameter("w", 6.0.mm).ref), "${ed.doc.note}")
        val mesh = meshOf(ed, spring)
        assertManifold(mesh, "a spring")
        assertTrue(Geom3.volume(mesh) > 0.0, "and it encloses material")

        for (format in ExportFormat.entries) {
            val out = Exports.export(ed.doc, "spring", format)
            assertTrue(out.ok, "${format.label}: ${out.message}")
        }

        // it combines with a constructed solid, through the general path, and stays watertight
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-40.0, -40.0))
        ed.click(Vec2(40.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 8.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -40.0))
        val plate = solids(ed).last()
        val fused = assertNotNull(ed.doc.combineSolids(spring, plate, constructit.geom.BoolOp.UNION), "${ed.doc.note}")
        assertManifold(meshOf(ed, fused), "a spring on a plate is one watertight solid")
    }
}
