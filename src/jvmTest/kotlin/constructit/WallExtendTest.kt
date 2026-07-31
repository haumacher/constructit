package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.point
import constructit.dsl.region
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.ThickCarrier
import constructit.editor.ThickNetwork
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Building a wall incrementally** (GitHub #7): with *Thicken* armed, the first click may be a wall that is
 * already there, and then the picks that follow grow *that* wall.
 *
 * What makes it an **edit** rather than a new feature is where the result goes: the wall's own `tool thicken`
 * step is re-stamped with the grown `els=`/`clicks=`/`signs=` and the script replayed — OP-23's move, applied
 * to a carrier set instead of a pattern's count. So the footprint keeps its identity and its thickness, one
 * undo steps back to the smaller wall, and every dependent (an opening, a solid) follows by recompute.
 */
class WallExtendTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun editorOn(script: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        return ed
    }

    private fun wallOf(ed: Editor): ThickNetwork = ed.doc.thickNetworks.single()

    private fun regionOf(tn: ThickNetwork) = Evaluator().region(tn.footprint.ref as RegionRef)

    private fun areaOf(
        doc: Document,
        tn: ThickNetwork,
    ) = Evaluator().scalar(doc.cx.regionArea(tn.footprint.ref as RegionRef)).base

    private fun roundTrips(doc: Document) {
        val saved = DocumentFormat.save(doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "save -> load -> save is byte-equal")
    }

    /** The hull wall of the user's plan, and the partition curve e11 whose ends land mid-way along its legs. */
    private fun hull(): Editor = editorOn(TAttachmentTest.HULL)

    // ---- the gesture ----

    @Test
    fun clickingAWallThenACurveGrowsThatWallInPlace() {
        val ed = hull()
        val wall = wallOf(ed)
        assertEquals("e17", ed.doc.nameOf(wall.footprint))
        val before = ed.doc.elements.size

        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        // the hull's own footprint, picked on its inner face (which is where its carrier runs)
        ed.click(Vec2(-67.25, 9.0))
        assertNotNull(ed.extendingWall, "the first pick was a wall: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Extending wall e17"), "the status names the wall: ${ed.statusHint}")

        ed.click(Vec2(-85.75, -20.0)) // the partition e11
        ed.key("Enter")

        assertEquals(1, ed.doc.thickNetworks.size, "still one wall — nothing new was created")
        val grown = wallOf(ed)
        assertEquals("e17", ed.doc.nameOf(grown.footprint), "and it is the same wall, by name")
        assertEquals(5, (grown.carrier as ThickCarrier.Network).curves.size)
        assertEquals(before, ed.doc.elements.size, "no element was added, and none replaced")
        assertTrue(Evaluator().eval(grown.footprint.ref.node) is EvalResult.Ok, ed.statusHint)

        // …and it is **the same footprint** the five curves would have given in one thicken: same area, same
        // rings, same corners (which is what "re-stamped with the grown carrier set" has to mean)
        val direct = DocumentFormat.load(TAttachmentTest.HULL_PLUS_E11)
        val expected = regionOf(direct.thickNetworks.single())
        val got = regionOf(grown)
        assertClose(areaOf(ed.doc, grown), areaOf(direct, direct.thickNetworks.single()), tol = 1e-9)
        assertEquals(2, got.holes.size, "the hull's one room is now two")
        assertEquals(expected.holes.size, got.holes.size)
        for ((e, g) in (listOf(expected.outer) + expected.holes).zip(listOf(got.outer) + got.holes)) {
            assertEquals(e.elements.size, g.elements.size, "the same ring, piece for piece")
            for ((p, q) in e.elements.zip(g.elements)) {
                assertClose(constructit.geom.GeomMath.startOf(q).x, constructit.geom.GeomMath.startOf(p).x, tol = 1e-9)
                assertClose(constructit.geom.GeomMath.startOf(q).y, constructit.geom.GeomMath.startOf(p).y, tol = 1e-9)
            }
        }
        roundTrips(ed.doc)
    }

    @Test
    fun undoOfAnExtensionIsOneStepBackToTheSmallerWall() {
        val ed = hull()
        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-85.75, -20.0))
        ed.key("Enter")
        assertEquals(5, (wallOf(ed).carrier as ThickCarrier.Network).curves.size)

        assertTrue(ed.undo(), "one undo step")
        val back = wallOf(ed)
        assertEquals(4, (back.carrier as ThickCarrier.Network).curves.size, "back to the hull")
        assertTrue(Evaluator().eval(back.footprint.ref.node) is EvalResult.Ok, "and its footprint is valid")
        assertEquals(1, regionOf(back).holes.size)

        assertTrue(ed.redo(), "and forward again")
        assertEquals(5, (wallOf(ed).carrier as ThickCarrier.Network).curves.size)
    }

    /** The wall's thickness stays the **wall's own**, whatever the tool's field says. */
    @Test
    fun theWallKeepsItsOwnThicknessParameter() {
        val ed = hull()
        val d = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "d" })
        ed.setTool(Tools.THICKEN)
        // another parameter picked into the tool's own field, which extension must ignore
        ed.activeScalar = ed.doc.newParameter("other", 20.0.mm)
        ed.click(Vec2(-67.25, 9.0))
        assertEquals("d", ed.activeScalar?.name, "the panel shows the wall's own parameter")
        ed.click(Vec2(-85.75, -20.0))
        ed.key("Enter")

        val grown = wallOf(ed)
        val entry = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "d" })
        assertClose(Evaluator().scalar(grown.thickness).mm, 5.0, msg = "still 5 mm, the wall's own")
        assertTrue(DocumentFormat.save(ed.doc).contains("scalar=\"${entry.name}\""), "and the step still names it")
        assertClose(Evaluator().scalar(d.ref).mm, 5.0)
    }

    // ---- journal ordering: a curve drawn after the wall ----

    /**
     * A step may only name what an earlier step declared, so extending with a curve drawn **after** the wall
     * moves the wall's own step after it. The proof that the move is right is that the script still round-trips.
     */
    @Test
    fun aCurveDrawnAfterTheWallMovesTheWallsStep() {
        val ed = hull()
        // a fresh partition, drawn now: from the hull's top leg down to its bottom one, T at both ends
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-50.0, 9.0))
        ed.click(Vec2(-50.0, -45.25))

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-50.0, -20.0))
        ed.key("Enter")

        val grown = wallOf(ed)
        assertEquals(5, (grown.carrier as ThickCarrier.Network).curves.size, ed.statusHint)
        assertTrue(Evaluator().eval(grown.footprint.ref.node) is EvalResult.Ok, ed.statusHint)
        val text = DocumentFormat.save(ed.doc)
        // the step is after the segment it names, which is the whole point of the move — and the wall is
        // renamed by it, because a script name *is* the journal's order (OP-18). The status says so.
        assertTrue(
            text.indexOf("tool segment") < text.indexOf("tool thicken"),
            "the thicken step must follow the curve it names:\n$text",
        )
        assertTrue(ed.statusHint.contains("moved after"), "and the move is spoken: ${ed.statusHint}")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and save -> load -> save is byte-equal")
        assertEquals(2, regionOf(grown).holes.size)
    }

    /**
     * …and the step takes **what is built on it** along. Here an opening is added to the wall *before* the new
     * curve is drawn, so the wall's step has to move past that curve while its own opening step stays after
     * it — one block move, and the proof is again that the script round-trips.
     */
    @Test
    fun theMoveCarriesWhatIsBuiltOnTheWallWithIt() {
        val ed = hull()
        val w = ed.doc.newParameter("w", 900.0.mm)
        assertNotNull(ed.doc.addInterval(wallOf(ed), 1, 30.0.mm, w.ref, 0.0.mm, 2100.0.mm))
        ed.checkpoint()
        // …and only now the curve to extend with
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-50.0, 9.0))
        ed.click(Vec2(-50.0, -45.25))

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 14.0)) // the footprint's outer face: the wall, unambiguously
        ed.click(Vec2(-50.0, -20.0))
        ed.key("Enter")

        val grown = wallOf(ed)
        assertEquals(5, (grown.carrier as ThickCarrier.Network).curves.size, ed.statusHint)
        val iv = assertNotNull(grown.intervals.singleOrNull(), "the opening came along: ${ed.statusHint}")
        assertClose(Evaluator().scalar(iv.position).mm, 30.0, msg = "and it is where it was")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.indexOf("tool thicken") < text.indexOf("opening"), "the opening still follows its wall:\n$text")
        assertTrue(text.indexOf("tool segment") < text.indexOf("tool thicken"), "which follows the curve:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save is byte-equal")
    }

    /** A curve built **on** the wall cannot become one of its carriers: refused, by name. */
    @Test
    fun aCurveBuiltOnTheWallCannotCarryIt() {
        val ed = hull()
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(-67.25, 14.0)) // the footprint's corners, as ordinary points
        val corners = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertTrue(corners.size >= 2, "the wall's key points: ${ed.statusHint}")
        ed.setTool(Tools.SEGMENT)
        val ev = Evaluator()
        val a = ev.point(corners[0].ref as constructit.dsl.PointRef)
        val b = ev.point(corners[2].ref as constructit.dsl.PointRef)
        ed.click(a)
        ed.click(b)
        val text = DocumentFormat.save(ed.doc)

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 14.0))
        assertNotNull(ed.extendingWall, ed.statusHint)
        ed.click((a + b) * 0.5)
        ed.key("Enter")
        assertTrue(ed.statusHint.contains("is built on that wall"), "refused by name: ${ed.statusHint}")
        assertEquals(text, DocumentFormat.save(ed.doc), "and nothing changed")
    }

    // ---- what rides along ----

    /**
     * An opening on the hull's top leg keeps its position and its jambs when a partition's T lands on that
     * very leg: an interval is anchored to the **carrier**'s arc length, which a split does not touch.
     */
    @Test
    fun anOpeningOnTheHostLegIsUnmovedByTheExtension() {
        val ed = hull()
        val wall = wallOf(ed)
        val w = ed.doc.newParameter("w", 900.0.mm)
        // leg 1 is e7, the top leg (els=e8,e7,e5,e3): an opening 30 mm along it
        assertNotNull(ed.doc.addInterval(wall, 1, 30.0.mm, w.ref, 0.0.mm, 2100.0.mm))
        val jambsBefore = ed.doc.jambsOf(wall, Evaluator()).map { it.seg }
        assertEquals(2, jambsBefore.size)
        ed.checkpoint()

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-85.75, -20.0))
        ed.key("Enter")

        val grown = wallOf(ed)
        val iv = assertNotNull(grown.intervals.singleOrNull(), "the opening came through the re-stamp")
        assertEquals(1, iv.legIndex, "on the same leg")
        assertClose(Evaluator().scalar(iv.position).mm, 30.0, msg = "at the same distance along it")
        assertClose(Evaluator().scalar(iv.width).mm, 900.0)
        val jambsAfter = ed.doc.jambsOf(grown, Evaluator()).map { it.seg }
        assertEquals(jambsBefore.size, jambsAfter.size)
        for ((x, y) in jambsBefore.zip(jambsAfter)) {
            assertClose(y.a.x, x.a.x, tol = 1e-9, msg = "a jamb does not move because the wall grew")
            assertClose(y.a.y, x.a.y, tol = 1e-9)
            assertClose(y.b.x, x.b.x, tol = 1e-9)
            assertClose(y.b.y, x.b.y, tol = 1e-9)
        }
    }

    /** A solid extruded from the footprint follows it: the volume grows by exactly the partition's slab. */
    @Test
    fun aSolidExtrudedFromTheWallFollowsTheExtension() {
        val ed = hull()
        ed.activeScalar = ed.doc.newParameter("h", 100.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-109.25, -16.25)) // the hull footprint's outer face
        val solid = assertNotNull(ed.doc.elements.firstOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        val before = Geom3.volume(Evaluator().solid(solid.ref as SolidRef).mesh)
        assertClose(before, 1567.5 * 100.0, tol = 1e-6, msg = "the hull band, 100 mm high")
        assertManifold(Evaluator().solid(solid.ref as SolidRef).mesh, "the hull solid")

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-85.75, -20.0))
        ed.key("Enter")

        val grownSolid = assertNotNull(ed.doc.elements.firstOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        val after = Geom3.volume(Evaluator().solid(grownSolid.ref as SolidRef).mesh)
        // e11 is 54.25 long and 5 thick, centred, and it meets the hull's inner faces exactly
        assertClose(after - before, 54.25 * 5.0 * 100.0, tol = 1e-6, msg = "exactly the partition's slab")
        assertManifold(Evaluator().solid(grownSolid.ref as SolidRef).mesh, "the grown wall solid")
    }

    // ---- refusals, each by name ----

    @Test
    fun anExtensionThatConnectsToNothingIsRefusedByName() {
        val ed = hull()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(200.0, 200.0))
        ed.click(Vec2(260.0, 200.0))
        val text = DocumentFormat.save(ed.doc)

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(230.0, 200.0))
        ed.key("Enter")

        assertTrue(ed.statusHint.contains("not connected"), "refused by name: ${ed.statusHint}")
        assertEquals(4, (wallOf(ed).carrier as ThickCarrier.Network).curves.size, "and the wall is untouched")
        assertEquals(text, DocumentFormat.save(ed.doc), "a refused extension changes nothing at all")
    }

    /** An **ortho-carrier** wall (drawn with the Wall tool) is refused, with the tool that does grow it named. */
    @Test
    fun anOrthoCarrierWallIsRefusedWithTheAlternativeNamed() {
        val ed = Editor()
        val t = ed.doc.newParameter("t", 10.0.mm)
        ed.activeScalar = t
        ed.setTool(Tools.WALL)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(100.0, 60.0))
        ed.finishPath()
        val wall = assertNotNull(ed.doc.thickNetworks.singleOrNull(), "the Wall tool built an ortho wall")
        assertTrue(wall.carrier is ThickCarrier.Ortho)

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(50.0, 5.0)) // its footprint's face
        assertNull(ed.extendingWall, "no extension was started")
        assertTrue(ed.statusHint.contains("Wall tool"), "the refusal names the tool that does it: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("rectilinear"), ed.statusHint)
        assertClose(Evaluator().scalar(t.ref).mm, 10.0, msg = "and nothing was built or changed")
    }

    /** A wall picked **mid-sequence** is not a carrier curve, and the status says which click it belongs to. */
    @Test
    fun aWallPickedMidSequenceIsRefusedWithTheRule() {
        val ed = hull()
        ed.activeScalar = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "d" })
        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-85.75, -20.0)) // a plain curve first: this is a *new* wall
        assertNull(ed.extendingWall)
        // the hull footprint's *outer* face, where no carrier curve runs — so this click means the wall
        ed.click(Vec2(-67.25, 14.0))
        assertTrue(ed.statusHint.contains("Pick the wall first"), "refused by rule: ${ed.statusHint}")
        assertEquals(1, ed.doc.thickNetworks.size, "and nothing was built")
    }

    /** Alt on the first click declines the extension and starts a new wall there — Alt's standing meaning. */
    @Test
    fun altOnTheFirstClickStartsANewWallInstead() {
        val ed = hull()
        ed.activeScalar = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "d" })
        ed.setTool(Tools.THICKEN)
        ed.snapEnabled = false
        ed.click(Vec2(-67.25, 9.0)) // the hull's top leg, which is also its footprint's face
        assertNull(ed.extendingWall, "Alt keeps it a new wall")
        ed.snapEnabled = true
        ed.key("Enter")
        assertEquals(2, ed.doc.thickNetworks.size, "a second wall over the same leg: ${ed.statusHint}")
    }

    // ---- and the whole of the user's report, by gestures ----

    /**
     * The issue's own sequence: the hull wall, then the two interior partition runs promoted into it — one
     * wall, three rooms, and the script it saves is the seven-curve one.
     */
    @Test
    fun theUsersPlanBuiltIncrementallyIsTheOneWallPlan() {
        val ed = hull()
        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-85.75, -20.0)) // e11
        ed.key("Enter")

        ed.setTool(Tools.THICKEN)
        ed.click(Vec2(-67.25, 9.0))
        ed.click(Vec2(-26.75, -9.5)) // e14
        ed.click(Vec2(-20.75, -21.5)) // e16
        ed.key("Enter")

        val wall = wallOf(ed)
        assertEquals(7, (wall.carrier as ThickCarrier.Network).curves.size, ed.statusHint)
        assertEquals(3, regionOf(wall).holes.size, "three rooms")
        val direct = DocumentFormat.load(TAttachmentTest.ONE_WALL)
        assertClose(areaOf(ed.doc, wall), areaOf(direct, direct.thickNetworks.single()), tol = 1e-9)
        roundTrips(ed.doc)
    }
}
