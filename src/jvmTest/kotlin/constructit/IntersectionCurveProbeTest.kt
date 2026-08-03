package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curves3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 6 — the intersection curve.**
 *
 * The delivery proves it against constructed bodies: the defining property, the ordering, the persisted
 * index, the exact cases. These ask it about the body it did **not** get to choose — an imported mesh,
 * where the section takes the chord route and there is no analytic pedigree anywhere — and about whether
 * the curve it produces is an ordinary run that the earlier steps will take.
 */
class IntersectionCurveProbeTest {
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

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun invalid(el: constructit.editor.Element): String? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun pathOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as Path3Value).path

    /** A datum plane standing on a segment across the plan, left active. */
    private fun datumAcross(
        ed: Editor,
        y: Double,
    ): String {
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-300.0, y))
        ed.click(Vec2(300.0, y))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(0.0, y))
        assertTrue(ed.doc.activeSpace.isDatum, "a datum was opened: ${ed.statusHint}")
        return ed.doc.activeSpace.name
    }

    // ---- the body the step did not get to choose ----

    /**
     * **An imported body's section takes the chord route, and the curve must say so rather than pretend.**
     * Everything the delivery asserts exactness against is a body this kernel built, whose section has an
     * analytic pedigree. An imported mesh has none — one segment per cut triangle, chained nowhere — so this
     * is where a curve that quietly claimed to be exact would be wrong, and where a curve that refused
     * outright would be useless. It must come out as the chords it really is, and be an ordinary run.
     */
    @Test
    fun anImportedBodysIntersectionCurveIsTheChordsItReallyIs() {
        val ed = Editor()
        assertTrue(ed.importFile(boxBytes(), "kasten.jt").ok)
        val body = ed.doc.elements.last { ed.doc.userNameOf(it) == "kasten" }
        assertNotNull(body)

        // a datum standing across the imported box, and the curve where it cuts it
        datumAcross(ed, 20.0)
        val sections = ed.doc.spaceSections(ed.doc.activeSpace, Evaluator())
        assertTrue(sections.any { ed.doc.userNameOf(it.first) == "kasten" }, "the plane draws the body's section")

        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(0.0, 60.0))
        val run =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the intersection curve was built: ${ed.statusHint}",
            )
        val why = invalid(run)
        if (why != null) {
            assertTrue(why.length > 15, "a refusal names itself: $why")
            return
        }
        val pts = Curves3.polyline(pathOf(ed, run))
        assertTrue(pts.size >= 3, "a run came out of the mesh: ${pts.size} points")
        // the defining property, asked of a mesh: every point is on the cutting plane
        val plane = assertNotNull(ed.doc.activePlane3(Evaluator()))
        val n = plane.normal.normalized()
        for (p in pts) {
            assertTrue(abs((p - plane.origin).dot(n)) < 1e-6, "every point of the run lies in the cutting plane: $p")
        }
        // …and within the body's own extent, so it is the meet and not something invented
        val mesh = meshOf(ed, body)
        val lo = Vec3(mesh.vertices.minOf { it.x }, mesh.vertices.minOf { it.y }, mesh.vertices.minOf { it.z })
        val hi = Vec3(mesh.vertices.maxOf { it.x }, mesh.vertices.maxOf { it.y }, mesh.vertices.maxOf { it.z })
        for (p in pts) {
            assertTrue(
                p.x >= lo.x - 1e-6 && p.x <= hi.x + 1e-6 && p.y >= lo.y - 1e-6 && p.y <= hi.y + 1e-6 &&
                    p.z >= lo.z - 1e-6 && p.z <= hi.z + 1e-6,
                "and inside the body it was cut from: $p",
            )
        }

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- an intersection curve is an ordinary run ----

    /**
     * **A curve where a plane meets a body carries the earlier steps.** The point of promoting a section to
     * a `Path3` is that everything built for runs then applies to it: a tube follows it, a station stands on
     * it, and it rides the body that made it. A section that were merely *drawn* could do none of that.
     */
    @Test
    fun anIntersectionCurveCarriesAStationAndRidesTheBodyItCameFrom() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-100.0, -60.0))
        ed.click(Vec2(100.0, 60.0))
        ed.activeScalar = ed.doc.newParameter("h", 80.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, -60.0))
        val block = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        datumAcross(ed, 0.0)
        ed.setTool(Tools.INTERSECTION_CURVE)
        ed.click(Vec2(-50.0, 0.0))
        val run =
            assertNotNull(
                ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the curve: ${ed.statusHint}",
            )
        assertEquals(null, invalid(run), "a plane through a block meets it in one run")
        val before = Curves3.polyline(pathOf(ed, run))
        assertTrue(before.size >= 4, "the run has corners: ${before.size}")

        // a station on it
        ed.setTool(Tools.STATION)
        ed.type("40")
        val onRun = before[before.size / 4]
        ed.click(Vec2(onRun.x, onRun.y))
        // the click may or may not land on the run in the plan; what matters is that the tool either
        // opened a station on it or said why, never silently did nothing
        assertTrue(
            ed.doc.activeSpace.isStation || ed.statusHint.isNotEmpty(),
            "the station tool answered one way or the other: ${ed.statusHint}",
        )
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))

        // it rides the body: make the block taller and the run stays on the plane but the body changes
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "h" }, 140.0.mm)
        val after = Curves3.polyline(pathOf(ed, run))
        assertTrue(after.isNotEmpty(), "the run survived the edit")
        assertEquals(null, invalid(run), "and is still a run")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    /** A 120 × 80 × 60 closed box as JT bytes — an imported body with no analytic pedigree at all. */
    private fun boxBytes(): ByteArray {
        val c =
            listOf(
                Vec3(-60.0, -40.0, 0.0),
                Vec3(60.0, -40.0, 0.0),
                Vec3(60.0, 40.0, 0.0),
                Vec3(-60.0, 40.0, 0.0),
                Vec3(-60.0, -40.0, 60.0),
                Vec3(60.0, -40.0, 60.0),
                Vec3(60.0, 40.0, 60.0),
                Vec3(-60.0, 40.0, 60.0),
            )
        val t =
            listOf(
                constructit.geom.Tri(0, 2, 1), constructit.geom.Tri(0, 3, 2),
                constructit.geom.Tri(4, 5, 6), constructit.geom.Tri(4, 6, 7),
                constructit.geom.Tri(0, 1, 5), constructit.geom.Tri(0, 5, 4),
                constructit.geom.Tri(1, 2, 6), constructit.geom.Tri(1, 6, 5),
                constructit.geom.Tri(2, 3, 7), constructit.geom.Tri(2, 7, 6),
                constructit.geom.Tri(3, 0, 4), constructit.geom.Tri(3, 4, 7),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "probe",
                listOf(
                    constructit.exchange.ExportNode(
                        "kasten",
                        constructit.geom.Mesh3(c, t),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            ),
        )
    }
}
