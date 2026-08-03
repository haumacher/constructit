package constructit

import constructit.core.Evaluator
import constructit.dsl.Path3Ref
import constructit.dsl.path3
import constructit.dsl.plane
import constructit.dsl.valueOf
import constructit.editor.Dependencies
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe for "a point slot either shares a point or states one"** (session 51) — the law taken past the
 * plan sheet it was reported on and composed with the features it has to live beside.
 *
 * The delivery proved the gesture on an empty plan. What is asked here is whether the *mechanism* is
 * general: a point placed by a `POINT3` slot on a **face** must belong to that face and be read in its
 * coordinates (OP-17's stamping rule, which every creation route has had to be audited against); a
 * cross-space gesture must place each point in the space that was active when it was clicked (OP-26's
 * `crossSpace`); the placed points must arrive in the tool's own slots *in order*, which the dependency
 * view is the independent reader of (OP-14); and the one row that carries **both** halves of the law —
 * *Make relative*, a subject slot and an input slot side by side — must place the anchor, refuse the
 * subject, and survive being undone by *Make absolute* (OP-4).
 */
class PointSlotPlacementProbeTest {
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

    private fun points(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.POINT }

    private fun coilOf(ed: Editor): Element =
        assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "a coil was built: ${ed.statusHint}",
        )

    @Suppress("UNCHECKED_CAST")
    private fun startOf(
        ed: Editor,
        coil: Element,
    ): Vec3 = Curves3.polyline(Evaluator().path3(coil.ref as Path3Ref)).first()

    @Suppress("UNCHECKED_CAST")
    private fun helix(
        ed: Editor,
        coil: Element,
    ): Curve3Element.Helix3 = Evaluator().path3(coil.ref as Path3Ref).elements.single() as Curve3Element.Helix3

    /** A plate, then its front face as the working plane — the standard face fixture. */
    private fun plateWithFace(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("t", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.activeSpace.isFace, "the plate's front face is the working plane: ${ed.statusHint}")
        return ed
    }

    /**
     * **A coil drawn on a face out of empty space belongs to that face.** The two points a `POINT3` slot
     * places are stamped with the active space and read in its coordinates — so the coil stands *on the
     * face*, rising along the face's normal, not along the plan's.
     *
     * This is the audit OP-17 forced on every creation route ("an ortho path drawn on a face left its
     * corners in the plan") asked of the newest one. It cannot be seen on a plan sheet, where every space
     * is the plan and a mis-stamped point still lands in the right place.
     */
    @Test
    fun pointsPlacedOnAFaceBelongToTheFace() {
        val ed = plateWithFace()
        val face = ed.activeSpace
        ed.setTool(Tools.HELIX_PT)
        ed.type("6")
        ed.type("2")
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(12.0, 10.0))
        val coil = coilOf(ed)

        val placed = points(ed)
        assertEquals(2, placed.size, "both clicks stated a point: ${ed.statusHint}")
        for (p in placed) assertEquals(face.name, ed.doc.spaceOf(p).name, "…and each belongs to the face it was drawn on")

        // read in the face's own frame: the coil starts at the point that was clicked, in world terms
        val plane = Evaluator().plane(assertNotNull(face.plane))
        val wantStart = plane.toWorld(Vec2(12.0, 10.0))
        assertTrue((startOf(ed, coil) - wantStart).length() <= 1e-9, "the coil begins at the placed start point, on the face")
        // …and rises along the face's normal, which is nothing like the plan's z
        val axis = helix(ed, coil).axis.normalized()
        val n = plane.normal.normalized()
        assertTrue(kotlin.math.abs(axis.dot(n)) > 0.999, "the axis is the face's normal (was $axis, face normal $n)")
        assertTrue(kotlin.math.abs(axis.z) < 0.01, "…and not the plan's, which a mis-stamped point would have given")

        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal")
        val reloaded = back.elements.filter { it.kind == ElementKind.POINT }
        assertEquals(2, reloaded.size, "the two placed points come back")
        for (p in reloaded) assertEquals(face.name, back.spaceOf(p).name, "…still on the face")
    }

    /**
     * **A point placed on a face is an ordinary point of that face: draggable, and the coil follows.** The
     * placement route ends in `Document.freePoint`, so what it makes is a free source node like any other —
     * the claim that would fail if a placed point were a one-off literal baked into the tool's step.
     */
    @Test
    fun aPlacedPointOnAFaceStaysDraggableAndTheCoilFollows() {
        val ed = plateWithFace()
        ed.setTool(Tools.HELIX_PT)
        ed.type("6")
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(12.0, 10.0))
        val coil = coilOf(ed)
        assertEquals(12.0, helix(ed, coil).radius, absoluteTolerance = 1e-9)

        ed.drag(Vec2(12.0, 10.0), Vec2(20.0, 10.0))
        assertEquals(20.0, helix(ed, coil).radius, absoluteTolerance = 1e-9, message = "dragging the placed start point restates the radius")

        val plane = Evaluator().plane(assertNotNull(ed.activeSpace.plane))
        assertTrue(
            (startOf(ed, coil) - plane.toWorld(Vec2(20.0, 10.0))).length() <= 1e-9,
            "…and the coil still begins exactly at it",
        )
    }

    /**
     * **A cross-space gesture places each point where it was clicked.** OP-26's `crossSpace` keeps the picks
     * across a change of working plane; with placement in the slot, the *space* each new point is stamped
     * with is now part of what that promise means — click two in the plan, switch to the face, click a
     * third, and the curve runs from the plan up to the face.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCurveAcrossTwoSpacesPlacesEachPointInTheSpaceItWasClickedIn() {
        val ed = plateWithFace()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "back to the plan to start the curve")
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(-30.0, -30.0))
        ed.click(Vec2(-10.0, -30.0))
        val face = ed.doc.spaces.first { it.isFace }
        assertTrue(ed.setActiveSpace(face.name), "switch to the face mid-gesture")
        ed.click(Vec2(20.0, 15.0))
        ed.key("Enter")

        val curve = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "a curve: ${ed.statusHint}")
        val placed = points(ed)
        assertEquals(3, placed.size, "three clicks, three stated points")
        assertEquals(
            listOf(Document.PLAN_SPACE, Document.PLAN_SPACE, face.name),
            placed.map { ed.doc.spaceOf(it).name },
            "each point belongs to the space that was active when it was clicked",
        )
        // the curve is genuinely out of plane: its last point is on the face, off the plan's z = 0
        val pts = Curves3.polyline(Evaluator().path3(curve.ref as Path3Ref)).last()
        assertTrue(kotlin.math.abs(pts.z) > 1.0, "the run climbs onto the face (ends at $pts)")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
    }

    /**
     * **The placed points land in the slots they were clicked for, and an independent reader agrees.** The
     * dependency view names each input by the *role* the tool's step gives it (OP-14), so it is the one
     * place outside the tool where slot order is visible — the check that a placement appended to
     * `Picks.elements` in the right position rather than merely somewhere.
     */
    @Test
    fun theDependencyViewNamesEachPlacedPointByItsSlot() {
        val ed = Editor()
        ed.setTool(Tools.HELIX_PT)
        ed.type("5")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        val coil = coilOf(ed)

        val inputs = Dependencies.inputsOf(ed.doc, coil)
        assertEquals(2, inputs.size, "the coil is built from the two points it placed: $inputs")
        assertEquals(listOf("centre", "start point"), inputs.map { it.role }, "…each in its own slot's word")
        // the centre is the first click and the start the second — the order the gesture stated
        assertTrue(
            ed.doc.elements.indexOf(inputs[0].element) < ed.doc.elements.indexOf(inputs[1].element),
            "and they were created in click order",
        )
    }

    /**
     * **The one row that carries both halves of the law.** *Make relative* names a **subject** (the point
     * being re-parameterized, which must already stand) and an **input** (the anchor it is to follow, which
     * an empty click states). Both must behave, in one gesture: the subject refuses by name, the anchor is
     * placed, the offset is live, and *Make absolute* still undoes the whole thing (OP-4).
     */
    @Test
    fun makeRelativePlacesItsAnchorAndStillRefusesItsSubject() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, 0.0))
        val subject = points(ed).single()

        // the subject slot: a miss places nothing and says why
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(-50.0, -50.0))
        assertEquals(1, points(ed).size, "the subject slot placed nothing")
        val hint = ed.statusHint ?: ""
        assertTrue(hint.contains("needs an existing"), "…and said so: $hint")
        assertTrue(hint.contains("nothing was placed"), "…naming the fact that nothing happened: $hint")

        // now the gesture for real: the subject is clicked, the anchor is stated by an empty click
        ed.click(Vec2(10.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        assertEquals(2, points(ed).size, "the anchor was placed: ${ed.statusHint}")
        val anchor = points(ed).last()
        assertTrue(anchor !== subject, "…and it is a point of its own")

        fun subjectAt(): Vec2 {
            val v = Evaluator().valueOf(subject.ref) as constructit.core.PointValue
            return Vec2(v.p.x, v.p.y)
        }
        assertEquals(10.0, subjectAt().x, absoluteTolerance = 1e-9)

        // live: moving the placed anchor takes the subject along, keeping distance and angle
        ed.drag(Vec2(40.0, 0.0), Vec2(40.0, 20.0))
        assertEquals(20.0, subjectAt().y, absoluteTolerance = 1e-6, message = "the subject follows the anchor it was given")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")

        // …and the pair still closes: Make absolute frees it where it stands
        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(subjectAt())
        ed.drag(Vec2(40.0, 20.0), Vec2(60.0, 20.0))
        assertEquals(20.0, subjectAt().y, absoluteTolerance = 1e-6, message = "freed, it stays where it stood")
    }
}
