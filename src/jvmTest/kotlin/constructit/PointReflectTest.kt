package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.deg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Point reflection** — the user's question, verbatim: *"What about point reflection — a whole missing
 * concept, right?"*
 *
 * The honest answer was *yes as a concept, no as a capability*: **Rotate** with 180° typed into it already
 * builds the very same image. What it does not build is the same **drawing**, and that is the whole of this
 * package (OP-14: a structural intent gets its own spelling). A rotation carries an angle; an angle is a
 * freedom the panel offers for ever; so a half turn spelled as a rotation can be edited to 175° and quietly
 * stop being a point reflection. *Point reflect* has **no scalar slot at all**, the half turn lives inside
 * the node as a constant map, and [theSameImageAsRotateAtOneEightyAndNotTheSameFreedom] pins both halves of
 * that claim at once — same geometry, and one of them has a number to drift while the other has none.
 *
 * The rest is Mirror's economy, asserted: two clicks, a live derived copy, an existing point shared, one
 * recorded step, a byte-equal round trip and one undo.
 */
class PointReflectTest {
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
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /** [geom] reflected through [centre], as one gesture. */
    private fun Editor.reflect(
        geom: Vec2,
        centre: Vec2,
    ) {
        setTool(Tools.POINT_REFLECT)
        click(geom)
        click(centre)
    }

    private fun pointAt(
        doc: Document,
        el: Element,
    ): Vec2 = assertNotNull(Evaluator().valueOf(el.ref) as? PointValue, "${doc.nameOf(el)} is a point").p

    private fun assertAt(
        expected: Vec2,
        actual: Vec2,
        what: String,
    ) {
        assertClose(actual.x, expected.x, 1e-9, "$what: x")
        assertClose(actual.y, expected.y, 1e-9, "$what: y")
    }

    /** The centre of a reflection: `2c − p` is where everything lands. */
    private fun through(
        c: Vec2,
        p: Vec2,
    ) = c * 2.0 - p

    // ---- 1. what it builds ----

    /**
     * A **mixed selection** — a segment, a circle and a free point — each reflected through one centre, each
     * landing at `2c − p`, and a circle keeping its radius because a half turn is a rigid motion.
     *
     * The three gestures share the centre by the ordinary point-slot rule: the first click on empty space
     * places it, the next two land on the point that is now there and reuse the node.
     */
    @Test
    fun aMixedSelectionLandsAtTwiceTheCentreMinusItself() {
        val ed = Editor()
        val c = Vec2(-40.0, -40.0)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(80.0, 80.0))
        ed.click(Vec2(100.0, 80.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 90.0))

        ed.reflect(Vec2(40.0, 20.0), c)
        ed.reflect(Vec2(80.0, 100.0), c)
        ed.reflect(Vec2(10.0, 90.0), c)

        val ev = Evaluator()
        val seg = assertNotNull(ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.getOrNull(1), "the segment's image")
        val image = assertNotNull(ev.valueOf(seg.ref) as? SegmentValue).seg
        assertAt(through(c, Vec2(20.0, 10.0)), image.a, "the segment's first end")
        assertAt(through(c, Vec2(60.0, 30.0)), image.b, "the segment's second end")

        val circle = assertNotNull(ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }.getOrNull(1), "the circle's image")
        val ring = assertNotNull(ev.valueOf(circle.ref) as? CircleValue).circle
        assertAt(through(c, Vec2(80.0, 80.0)), ring.center, "the circle's centre")
        assertClose(ring.radius, 20.0, 1e-9, "a half turn is rigid, so the radius is untouched")

        val dot = ed.doc.elements.last { it.kind == ElementKind.POINT }
        assertAt(through(c, Vec2(10.0, 90.0)), pointAt(ed.doc, dot), "the free point's image")

        // …and all three went through the *same* centre node, because clicking an existing point shares it
        val centres = ed.doc.elements.filter { it.kind == ElementKind.POINT }.map { pointAt(ed.doc, it) }
        assertEquals(1, centres.count { (it - c).length() < 1e-9 }, "one centre, shared by three gestures")
    }

    /** The copy is **derived, not stamped**: drag the centre and every image follows by recompute. */
    @Test
    fun draggingTheCentreCarriesTheImageWithIt() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        ed.reflect(Vec2(40.0, 20.0), Vec2(-40.0, -40.0))

        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(-40.0, -40.0), Vec2(-30.0, -55.0))

        val moved = Vec2(-30.0, -55.0)
        val seg = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }[1]
        val image = assertNotNull(Evaluator().valueOf(seg.ref) as? SegmentValue).seg
        assertAt(through(moved, Vec2(20.0, 10.0)), image.a, "the image follows the centre")
        assertAt(through(moved, Vec2(60.0, 30.0)), image.b, "the image follows the centre")

        // …and the original moving takes it too, which is the other half of "live"
        ed.drag(Vec2(20.0, 10.0), Vec2(25.0, 5.0))
        val again = assertNotNull(Evaluator().valueOf(seg.ref) as? SegmentValue).seg
        assertAt(through(moved, Vec2(25.0, 5.0)), again.a, "the image follows the original too")
    }

    // ---- 2. the freedom that is not there ----

    /** The gesture is two clicks and **no number**: nothing typed, nothing offered, nothing to type into. */
    @Test
    fun theGestureAddsNoScalarAnywhere() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        val before = ed.doc.scalars.size

        ed.reflect(Vec2(40.0, 20.0), Vec2(-40.0, -40.0))

        assertTrue(assertNotNull(ed.doc.toolDef(Tools.POINT_REFLECT)).scalars.isEmpty(), "the tool declares no scalar slot")
        assertEquals(before, ed.doc.scalars.size, "and the gesture created no panel row")
        val image = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }[1]
        assertTrue(ed.doc.ownFields(image).isEmpty(), "nor a step-owned freedom reachable through the image")
    }

    /**
     * **The concept, pinned.** *Rotate* at 180° and *Point reflect* build the same image to the last digit —
     * and the rotation keeps an editable angle that turns its copy into something else, while the reflection
     * has nothing to edit. Same geometry, different drawings; that difference is why the tool exists.
     */
    @Test
    fun theSameImageAsRotateAtOneEightyAndNotTheSameFreedom() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        val c = Vec2(-40.0, -40.0)

        ed.reflect(Vec2(40.0, 20.0), c)
        ed.setTool(Tools.ROTATE)
        ed.type("180")
        ed.click(Vec2(40.0, 20.0))
        ed.click(c)

        val segs = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }
        val reflected = assertNotNull(Evaluator().valueOf(segs[1].ref) as? SegmentValue).seg
        val turned = assertNotNull(Evaluator().valueOf(segs[2].ref) as? SegmentValue).seg
        assertAt(reflected.a, turned.a, "a half turn is a half turn, however it is spelled")
        assertAt(reflected.b, turned.b, "a half turn is a half turn, however it is spelled")

        // the rotation left an angle behind; retyping it un-makes the half turn — and moves *only* its copy
        val angle = assertNotNull(ed.doc.scalars.lastOrNull { it.editable }, "Rotate's angle is a panel row")
        ed.doc.setParameter(angle, 175.0.deg)
        val drifted = assertNotNull(Evaluator().valueOf(segs[2].ref) as? SegmentValue).seg
        assertTrue((drifted.a - turned.a).length() > 1.0, "the rotation's copy moved with its angle")
        val still = assertNotNull(Evaluator().valueOf(segs[1].ref) as? SegmentValue).seg
        assertAt(reflected.a, still.a, "the reflection has no angle to drift")
        assertAt(reflected.b, still.b, "the reflection has no angle to drift")
    }

    // ---- 3. it is an ordinary step ----

    /** Recorded like every transform: the file names the tool, and `save → load → save` is byte-equal. */
    @Test
    fun theStepIsWrittenAndReadBackByteEqual() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        ed.reflect(Vec2(40.0, 20.0), Vec2(-40.0, -40.0))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool ${Tools.POINT_REFLECT} ") }, "the step names the tool:\n$once")
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")

        val seg = reloaded.elements.filter { it.kind == ElementKind.SEGMENT }[1]
        val image = assertNotNull(Evaluator().valueOf(seg.ref) as? SegmentValue).seg
        assertAt(through(Vec2(-40.0, -40.0), Vec2(20.0, 10.0)), image.a, "and the reloaded image is where it was")
    }

    /** One gesture, one undo layer — and the original is still there when the image is gone. */
    @Test
    fun oneUndoTakesTheReflectionBack() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, 10.0))
        ed.click(Vec2(60.0, 30.0))
        ed.reflect(Vec2(40.0, 20.0), Vec2(-40.0, -40.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "the original and its image")

        assertTrue(ed.undo(), "the reflection is one step")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SEGMENT }, "the image is gone")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.POINT && (pointAt(ed.doc, it) - Vec2(-40.0, -40.0)).length() < 1e-9 }, "and so is the centre it placed")
    }

    /**
     * It rides a **pattern** like every other transform (OP-23): reflecting one member through the pattern's
     * own centre — an invariant of its transform — replicates the gesture round the ring in one step.
     */
    @Test
    fun aReflectionOfAPatternMemberFansOutLikeAnyOtherGesture() {
        val ed = Editor()
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val before = ed.doc.elements.size

        ed.reflect(Vec2(100.0, 0.0), Vec2(0.0, 0.0))

        assertTrue(ed.statusHint.contains("6 copies round pattern"), ed.statusHint)
        assertEquals(6, ed.doc.elements.size - before, "one gesture, one image per member")
        // a hexagon's members reflect onto each other's places, which is exactly what a half turn about the
        // ring's own centre means — the reference member's image is the antipode
        val images = ed.doc.elements.takeLast(6).map { pointAt(ed.doc, it) }
        assertTrue(
            images.any { (it - Vec2(-100.0, 0.0)).length() < 1e-9 },
            "the reference member's own image is the antipode: $images",
        )
        assertTrue(ed.undo(), "the whole orbit is one undo")
        assertEquals(before, ed.doc.elements.size, "and it all goes at once")
    }
}
