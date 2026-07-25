package constructit

import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.ThickPath
import constructit.editor.Tools
import constructit.geom.GeomMath
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OP-21 — a wall is an *output feature*, and the model of it is the generic **thick path**: an offset
 * region around a carrier polyline with parametric interval features along it. The two defects this
 * replaces are what most of these tests pin: the footprint is **computed**, not regenerated, and it is
 * a **pure function of its parameters**, so no ordering decision is frozen at build time.
 */
class ThickPathTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** A thick path over an L-shaped open carrier: two legs, 10 thick, corner at (50,0). */
    private fun lShaped(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 4.0)) // +X -> corner (50,0)
        ed.click(Vec2(47.0, 40.0)) // +Y -> end (50,40)
        ed.finishPath()
        return ed
    }

    /** A straight 100-long thick path plus a shared 10-wide opening parameter. */
    private fun straightWithWidth(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 3.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("w", 10.0.mm)
        ed.setTool(Tools.OPENING)
        return ed
    }

    private fun regionOf(tp: ThickPath) = Evaluator().region(tp.footprint.ref as RegionRef)

    /** The x-extents of the plan's face pieces along [y], the drawn form of "solid here, gap there". */
    private fun facePieces(
        ed: Editor,
        tp: ThickPath,
        y: Double,
    ): List<Pair<Double, Double>> =
        ed.doc
            .planOf(tp, Evaluator())!!
            .filter { kotlin.math.abs(it.a.y - y) < 1e-9 && kotlin.math.abs(it.b.y - y) < 1e-9 }
            .map { minOf(it.a.x, it.b.x) to maxOf(it.a.x, it.b.x) }
            .sortedBy { it.first }

    @Test
    fun anOpenCarrierGivesOneMitredLoopAndNoHoles() {
        val ed = lShaped()
        val tp = ed.doc.thickPaths.single()
        assertEquals(ElementKind.AREA, tp.footprint.kind, "the footprint is a result-layer area (OP-14)")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.AREA }, "one element for the whole feature")

        val reg = regionOf(tp)
        assertTrue(reg.holes.isEmpty(), "an open carrier bounds a single loop")
        assertEquals(6, reg.outer.elements.size, "3 corners per side, joined by two end caps")
        assertTrue(GeomMath.signedArea(reg.outer) > 0.0, "outer boundary normalised counter-clockwise")

        val pts = reg.outer.elements.map { GeomMath.startOf(it) }

        fun has(
            x: Double,
            y: Double,
        ) = pts.any { kotlin.math.abs(it.x - x) < 1e-6 && kotlin.math.abs(it.y - y) < 1e-6 }
        // the corner is the *intersection of the offset face lines* — a mitre, not a plain offset
        assertTrue(has(45.0, 5.0), "inner mitre corner")
        assertTrue(has(55.0, -5.0), "outer mitre corner")

        // and it stays a function of the thickness parameter
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "t" }, 20.0.mm)
        val moved = regionOf(tp).outer.elements.map { GeomMath.startOf(it) }
        assertTrue(moved.any { kotlin.math.abs(it.x - 40.0) < 1e-6 && kotlin.math.abs(it.y - 10.0) < 1e-6 }, "mitre follows thickness")
    }

    @Test
    fun aClosedCarrierRingGivesARegionWithAHole() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // clicking the start closes the loop and finishes

        val tp = ed.doc.thickPaths.single()
        assertTrue(tp.closed)
        val reg = regionOf(tp)
        assertEquals(1, reg.holes.size, "a wall ring is exactly OP-14's hole machinery")
        assertEquals(4, reg.outer.elements.size)
        assertEquals(4, reg.holes.single().elements.size)
        // centerline 60x40 at thickness 10: outer 70x50, inner 50x30
        assertClose(GeomMath.signedArea(reg.outer), 70.0 * 50.0, tol = 1e-6)
        assertClose(GeomMath.signedArea(reg.holes.single()), -(50.0 * 30.0), tol = 1e-6, msg = "holes run clockwise")
        val area = ed.doc.cx.regionArea(tp.footprint.ref as RegionRef)
        assertClose(Evaluator().scalar(area).base, 2.0 * 10.0 * (60.0 + 40.0), tol = 1e-6, msg = "the band area is 2t(w+h)")
    }

    /**
     * The deeper of OP-21's two corrections: in plan a window does not interrupt the wall — below the
     * sill and above the head there is material, and even a door leaves a lintel. So the footprint is
     * *unbroken*, and the plan gap is drawn from it rather than cut into it.
     */
    @Test
    fun intervalsDoNotCutTheFootprint() {
        val ed = straightWithWidth()
        val tp = ed.doc.thickPaths.single()
        val before = regionOf(tp)
        val nodesBefore = ed.doc.cx.nodesCreated

        ed.click(Vec2(25.0, 0.0))
        ed.click(Vec2(65.0, 0.0))
        assertEquals(2, tp.intervals.size)

        assertEquals(before, regionOf(tp), "the footprint region is identical with and without openings")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.AREA }, "still one element")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "an interval adds no construction geometry at all — only its own parameters")
        // but nothing of the footprint was rebuilt: the plan is where the gap appears
        assertEquals(listOf(0.0 to 20.0, 30.0 to 60.0, 70.0 to 100.0), facePieces(ed, tp, 5.0))
    }

    /**
     * The headline regression (OP-21 defect 2). The as-built wall sorted each leg's openings by their
     * *evaluated* position while assembling the graph, so structure depended on the values held at build
     * time and dragging one opening past another left the faces split in the stale order. The order is
     * now read where it belongs — inside the computation — so it re-sorts itself, and no element or node
     * is replaced by the edit.
     */
    @Test
    fun movingAnIntervalPastAnotherResortsThePlanWithoutRebuilding() {
        val ed = straightWithWidth()
        val tp = ed.doc.thickPaths.single()
        ed.click(Vec2(25.0, 0.0)) // opening at 20..30
        ed.click(Vec2(65.0, 0.0)) // opening at 60..70
        assertEquals(listOf(0.0 to 20.0, 30.0 to 60.0, 70.0 to 100.0), facePieces(ed, tp, 5.0))

        val nodesBefore = ed.doc.cx.nodesCreated
        val elementsBefore = ed.doc.elements.toList()
        val footprintBefore = tp.footprint
        val regionBefore = regionOf(tp)

        // move the *first* opening past the second — a pure value edit
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "pos" }, 80.0.mm)

        assertEquals(listOf(0.0 to 60.0, 70.0 to 80.0, 90.0 to 100.0), facePieces(ed, tp, 5.0), "the plan re-sorted itself")
        assertEquals(listOf(0.0 to 60.0, 70.0 to 80.0, 90.0 to 100.0), facePieces(ed, tp, -5.0), "on both faces")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "a value edit must not grow the graph — nothing is regenerated")
        assertEquals(elementsBefore, ed.doc.elements, "and no element is replaced")
        assertTrue(footprintBefore === tp.footprint, "the footprint element survives the edit")
        assertEquals(regionBefore, regionOf(tp), "the footprint itself never noticed")
    }

    @Test
    fun thePlanBreaksBothFacesAtEachIntervalAndDrawsJambs() {
        val ed = straightWithWidth()
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "w" }, 20.0.mm)
        ed.click(Vec2(50.0, 0.0)) // centred on the click -> 40..60
        val tp = ed.doc.thickPaths.single()
        val plan = ed.doc.planOf(tp, Evaluator())!!

        fun has(
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
        ) = plan.any {
            fun eq(
                p: Double,
                q: Double,
            ) = kotlin.math.abs(p - q) < 1e-6
            (eq(it.a.x, ax) && eq(it.a.y, ay) && eq(it.b.x, bx) && eq(it.b.y, by)) ||
                (eq(it.a.x, bx) && eq(it.a.y, by) && eq(it.b.x, ax) && eq(it.b.y, ay))
        }
        assertTrue(has(0.0, 5.0, 40.0, 5.0), "solid face piece up to the opening")
        assertTrue(has(60.0, 5.0, 100.0, 5.0), "solid face piece after the opening")
        assertTrue(has(40.0, 5.0, 40.0, -5.0), "jamb at the opening start")
        assertTrue(has(60.0, 5.0, 60.0, -5.0), "jamb at the opening end")
        assertTrue(has(0.0, 5.0, 0.0, -5.0), "end cap")
        assertTrue(has(100.0, 5.0, 100.0, -5.0), "end cap")

        // position is anchored at the start edge, so widening extends the end
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "w" }, 40.0.mm)
        assertEquals(listOf(0.0 to 40.0, 80.0 to 100.0), facePieces(ed, tp, 5.0), "the end jamb follows the width parameter")

        // every value of an interval is a typed field (OP-13), heights included
        val iv = tp.intervals.single()
        assertClose(Evaluator().scalar(iv.sill).mm, 0.0)
        assertClose(Evaluator().scalar(iv.head).mm, 2100.0)
        assertTrue(ed.doc.scalars.any { it.name == "sill" && it.editable } && ed.doc.scalars.any { it.name == "head" && it.editable })
    }

    /** Justification is a property of the carrier's own direction, so it needs no inside/outside. */
    @Test
    fun justificationPutsTheMaterialOnOneSideOfTheCarrier() {
        for ((just, lo, hi) in listOf(Triple(Justification.CENTER, -5.0, 5.0), Triple(Justification.LEFT, 0.0, 10.0), Triple(Justification.RIGHT, -10.0, 0.0))) {
            val ed = Editor()
            val t = ed.doc.newParameter("t", 10.0.mm)
            ed.setTool(Tools.ORTHO_PATH)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(100.0, 2.0)) // carrier runs +X, so "left" is +Y
            ed.finishPath()
            val tp = assertNotNull(ed.doc.buildThickPath(ed.doc.orthoPaths.single(), t.ref, just))
            val ys = regionOf(tp).outer.elements.map { GeomMath.startOf(it).y }
            assertClose(ys.min(), lo, msg = "$just")
            assertClose(ys.max(), hi, msg = "$just")
            assertClose(kotlin.math.abs(GeomMath.signedArea(regionOf(tp).outer)), 1000.0, msg = "$just")

            // and it survives the round trip, since replay must build the same shape
            val text = DocumentFormat.save(ed.doc)
            assertTrue(text.contains("wall \"t\" ${just.name.lowercase()}"), "got:\n$text")
            assertEquals(just, DocumentFormat.load(text).thickPaths.single().justification)
        }
    }

    /** A carrier whose legs are collinear has no mitre at all: invalid with a reason, and it heals (OP-3). */
    @Test
    fun aDegenerateCarrierIsInvalidRatherThanWrong() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 0.0.mm)
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 2.0))
        ed.finishPath()
        val tp = assertNotNull(ed.doc.buildThickPath(ed.doc.orthoPaths.single(), t.ref))
        val bad = Evaluator().eval(tp.footprint.ref.node)
        assertTrue(bad is constructit.core.EvalResult.Invalid && bad.reason.contains("non-zero thickness"), "got: $bad")
        assertTrue(ed.doc.planOf(tp, Evaluator()) == null, "and there is nothing to draw")

        ed.doc.setParameter(t, 10.0.mm)
        assertTrue(Evaluator().eval(tp.footprint.ref.node) is constructit.core.EvalResult.Ok, "healed by a parameter edit")
    }

    /** The carrier is a plain ortho path: its corners keep dragging exactly as they did before. */
    @Test
    fun theCarrierStaysEditableAndTheFootprintFollows() {
        val ed = lShaped()
        val tp = ed.doc.thickPaths.single()
        assertTrue(tp.carrier === ed.doc.orthoPaths.single(), "the carrier is the retained path")
        val nodesBefore = ed.doc.cx.nodesCreated

        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(50.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(70.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(70.0, 0.0)))

        val pts = regionOf(tp).outer.elements.map { GeomMath.startOf(it) }
        assertTrue(pts.any { kotlin.math.abs(it.x - 65.0) < 1e-6 && kotlin.math.abs(it.y - 5.0) < 1e-6 }, "the mitre moved with the corner")
        assertEquals(nodesBefore, ed.doc.cx.nodesCreated, "dragging the carrier rebuilds nothing")
    }

    @Test
    fun aThickPathWithOpeningsRendersItsPlan() {
        val ed = straightWithWidth()
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "w" }, 20.0.mm)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(75.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.canvasW = 400.0
        ed.canvasH = 200.0
        ed.camera = Camera.centered(400.0, 200.0, scale = 3.0).pan(-150.0, 0.0)

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_thick_path_plan", target.svg())
    }

    /** The ring case through the renderer: both mitred loops of a room, with a door in one wall. */
    @Test
    fun aClosedRingRendersBothLoopsOfItsPlan() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 3.0))
        ed.click(Vec2(58.0, 40.0))
        ed.click(Vec2(2.0, 40.0))
        ed.click(Vec2(0.0, 0.0)) // closes and finishes
        ed.activeScalar = ed.doc.newParameter("w", 20.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(30.0, 0.0)) // a door in the bottom wall
        ed.setTool(Tools.SELECT)
        ed.canvasW = 300.0
        ed.canvasH = 240.0
        ed.camera = Camera.centered(300.0, 240.0, scale = 3.0).pan(-90.0, 60.0)

        val target = SvgDrawTarget()
        ed.render(target)
        Golden.check("editor_thick_path_ring", target.svg())
    }
}
