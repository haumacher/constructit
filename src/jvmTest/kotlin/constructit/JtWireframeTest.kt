package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.Path3Ref
import constructit.dsl.SolidRef
import constructit.dsl.path3
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.Imports
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Path3
import constructit.geom.Vec2
import constructit.geom.Vec3
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.PolylineSet
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.write.writeJt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4
import de.haumacher.kotlinjt.scene.Mesh as JtMesh
import de.haumacher.kotlinjt.scene.Vec3 as JtVec3

/**
 * **Imported curves — OP-26's step 9: a file's wireframe is a run of the drawing.**
 *
 * A JT file carries plenty of geometry that is not a body: centrelines, sketches, construction curves. Until
 * this step they were skipped and named. They now come in under the **identical contract an imported mesh
 * has** — a frozen literal in the file's own coordinates with a parametric placement, offering no
 * construction inputs — which is what most of this class asserts, one clause of that sentence at a time.
 *
 * The rest asserts the one thing wireframes have that meshes do not: a **recorded** gesture that turns a flat
 * run into ordinary 2D sketch geometry, refusing by name when the run is not flat, with the tolerance
 * asserted from both sides.
 */
class JtWireframeTest {
    // ---- fixtures: real JT bytes, written by the sibling's own writer ----

    /**
     * A file with one wireframe part, whose runs are exactly [runs] (in millimetres), at [pose].
     *
     * The sibling library's **writer** takes polyline sets, so these are real bytes and the whole reading path
     * is under test — not a `Scene` handed straight to the importer. (Where a scene *is* built in memory below
     * it is for a case bytes cannot state, exactly as `JtImportTest` does.)
     */
    private fun wireBytes(
        vararg runs: List<Vec3>,
        name: String = "centreline",
        pose: JtMat4 = JtMat4.IDENTITY,
    ): ByteArray = writeJt(wireScene(*runs, name = name, pose = pose))

    private fun wireScene(
        vararg runs: List<Vec3>,
        name: String = "centreline",
        pose: JtMat4 = JtMat4.IDENTITY,
    ): Scene {
        val positions = ArrayList<JtVec3>()
        val lines = ArrayList<List<Int>>()
        for (run in runs) {
            val idx =
                run.map { p ->
                    positions.add(JtVec3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
                    positions.size - 1
                }
            lines.add(idx)
        }
        val wire = SceneNode(name, pose, emptyList(), listOf(PolylineSet(positions, lines)), null, emptyList())
        return Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(wire)), emptyList())
    }

    /** A unit cube as the library's mesh — the mixed file's body half (`JtImportTest`'s own fixture). */
    private fun cubeMesh(): JtMesh {
        val p =
            listOf(
                JtVec3(0f, 0f, 0f),
                JtVec3(1f, 0f, 0f),
                JtVec3(1f, 1f, 0f),
                JtVec3(0f, 1f, 0f),
                JtVec3(0f, 0f, 1f),
                JtVec3(1f, 0f, 1f),
                JtVec3(1f, 1f, 1f),
                JtVec3(0f, 1f, 1f),
            )
        val quads =
            listOf(
                listOf(0, 3, 2, 1),
                listOf(4, 5, 6, 7),
                listOf(0, 1, 5, 4),
                listOf(1, 2, 6, 5),
                listOf(2, 3, 7, 6),
                listOf(3, 0, 4, 7),
            )
        val tris = ArrayList<JtMesh.Triangle>()
        for (q in quads) {
            tris.add(JtMesh.Triangle(q[0], q[1], q[2], -1, -1, -1))
            tris.add(JtMesh.Triangle(q[0], q[2], q[3], -1, -1, -1))
        }
        return JtMesh(p, emptyList(), tris)
    }

    /** A bent centreline in space — three straight pieces, none of them in one plane with the others. */
    private val bentRun =
        listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(100.0, 80.0, 0.0), Vec3(100.0, 80.0, 60.0))

    /** A flat rectangle in the file's z = 0 plane — the everyday "this part came with its sketch" case. */
    private val flatRun =
        listOf(Vec3(0.0, 0.0, 0.0), Vec3(60.0, 0.0, 0.0), Vec3(60.0, 40.0, 0.0), Vec3(0.0, 40.0, 0.0), Vec3(0.0, 0.0, 0.0))

    /**
     * A four-point run that misses a plane by exactly `h / 2` — a saddle, and the deviation is *exact* rather
     * than fitted.
     *
     * Its two diagonals sit at ±h/2, so the point set is symmetric under a quarter turn with z flipped: the
     * Newell normal is therefore exactly +z and the best plane is exactly z = 0, whichever way the arithmetic
     * associates. That is what makes the tolerance assertable from both sides at 10 % either way instead of
     * against a number a fit happened to produce.
     */
    private fun saddle(h: Double): List<Vec3> =
        listOf(
            Vec3(100.0, 0.0, h / 2),
            Vec3(0.0, 100.0, -h / 2),
            Vec3(-100.0, 0.0, h / 2),
            Vec3(0.0, -100.0, -h / 2),
        )

    // ---- helpers ----

    private fun runsOf(ed: Editor): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }

    /** The runs a user sees: the placements, not the literals the import hid under them. */
    private fun shownRuns(ed: Editor): List<Element> = runsOf(ed).filter { it.visible }

    @Suppress("UNCHECKED_CAST")
    private fun pathOf(
        ed: Editor,
        el: Element,
    ): Path3 = Evaluator().path3(el.ref as Path3Ref)

    private fun pointsOf(
        ed: Editor,
        el: Element,
    ): List<Vec3> = Curves3.polyline(pathOf(ed, el))

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

    /** How long a drawn segment measures, from its value — what a traced sketch is checked against. */
    private fun lengthOf(el: Element): Double {
        val v = Evaluator().valueOf(el.ref) as constructit.core.SegmentValue
        return (v.seg.b - v.seg.a).length()
    }

    /** Where a sketch space's frame stands right now — the plane it publishes, evaluated. */
    private fun originOf(space: constructit.editor.SketchSpace): Vec3 {
        val ref = assertNotNull(space.plane, "a wireframe space has a plane")
        return ((Evaluator().eval(ref.node) as EvalResult.Ok).value as constructit.core.PlaneValue).plane.origin
    }

    /** A constructed run through freshly drawn points — the contrast every "imported" claim is made against. */
    private fun constructedRun(
        ed: Editor,
        vararg at: Vec2,
    ): Element {
        ed.setTool(Tools.POINT)
        for (p in at) ed.click(p)
        ed.setTool(Tools.CURVE3)
        for (p in at) ed.click(p)
        ed.key("Enter")
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "a constructed run: ${ed.statusHint}")
    }

    private fun assertClose(
        actual: Double,
        expected: Double,
        tol: Double,
        msg: String = "",
    ) = assertTrue(abs(actual - expected) <= tol, "$msg: expected $expected, got $actual")

    // ---- 1: a wireframe part arrives as runs, with the file's own points ----

    /**
     * **The file said points; the drawing says the same points.** Two polylines in one part come back as two
     * runs of the drawing, each a chain of straight pieces through exactly the coordinates the file listed —
     * no fitting, no smoothing, nothing recognised.
     */
    @Test
    fun aWireframePartArrivesAsRunsWithTheFilesOwnPoints() {
        val ed = Editor()
        val result = ed.importFile(wireBytes(bentRun, flatRun), "wire.jt")
        assertTrue(result.ok, result.message)
        assertEquals(emptyList(), result.bodies, "this file has no body at all")
        assertEquals(listOf("centreline", "centreline2"), result.runs, "both runs came in, named by the authority")
        assertTrue(result.message.contains("2 wireframe runs"), result.message)
        assertTrue(result.notes.none { it.contains("wireframe") }, "and none of it is a note any more: ${result.notes}")

        val shown = shownRuns(ed)
        assertEquals(2, shown.size, "two runs, and the two literals under them are hidden")
        assertEquals(bentRun, pointsOf(ed, shown[0]), "the bent run's own points, to the last bit")
        assertEquals(flatRun, pointsOf(ed, shown[1]), "and the flat one's")
        assertTrue(
            pathOf(ed, shown[0]).elements.all { it is Curve3Element.Seg3 },
            "a polyline is a chain of segments — nothing was fitted on the way in",
        )
        assertEquals(3, pathOf(ed, shown[0]).elements.size, "four points, three pieces")
    }

    /** A file in inches states its unit, so a run arrives in millimetres like everything else. */
    @Test
    fun aRunIsScaledToMillimetres() {
        val ed = Editor()
        val scene =
            Scene(
                LengthUnit.INCHES,
                SceneNode(
                    "asm",
                    JtMat4.IDENTITY,
                    emptyList(),
                    emptyList(),
                    null,
                    listOf(
                        SceneNode(
                            "rail",
                            JtMat4.IDENTITY,
                            emptyList(),
                            listOf(PolylineSet(listOf(JtVec3(0f, 0f, 0f), JtVec3(1f, 0f, 0f)), listOf(listOf(0, 1)))),
                            null,
                            emptyList(),
                        ),
                    ),
                ),
                emptyList(),
            )
        assertTrue(Imports.importScene(ed.doc, scene, "inches.jt").ok)
        assertClose(Curves3.length(pathOf(ed, shownRuns(ed).single())), 25.4, 1e-9, "one inch is 25.4 mm")
    }

    // ---- 2: it is a body of the drawing, exactly as an imported mesh is ----

    /**
     * **Named, placed, dragged, hidden, saved and undone** — the mesh's whole contract, asserted on a run.
     *
     * The placement is the interesting half: the file's pose lands in an ordinary point and an ordinary angle
     * parameter, so the run moves because *the drawing* moved it, not because anything was re-read.
     */
    @Test
    fun anImportedRunIsABodyOfTheDrawingLikeAnImportedMesh() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(bentRun), "wire.jt").ok)
        val run = shownRuns(ed).single()
        assertEquals("centreline", ed.doc.userNameOf(run), "the file's name, through the naming authority")

        // ...placed by an anchor point and an angle, both ordinary nodes
        val anchor = ed.doc.elements.last { it.kind == ElementKind.POINT }
        assertNotNull(ed.doc.scalars.firstOrNull { it.name.startsWith("place-angle") }, "and a turn parameter")
        val at = (Evaluator().eval(anchor.ref.node) as EvalResult.Ok).let { (it.value as PointValue).p }
        assertClose(at.x, 0.0, 1e-9, "the file put this part at the origin")

        // ...and it follows its dragged anchor, rigidly
        ed.drag(Vec2(at.x, at.y), Vec2(at.x + 30.0, at.y + 10.0))
        val moved = pointsOf(ed, run)
        assertEquals(bentRun.map { it + Vec3(30.0, 10.0, 0.0) }, moved, "the whole run moved with its point")

        // ...hidden and shown like anything else
        ed.doc.setElementsVisible(listOf(run), false)
        assertFalse(run.visible)
        ed.doc.setElementsVisible(listOf(run), true)
        assertTrue(run.visible)

        // ...save → load → save byte-equal, with the points bit-identical
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().any { it.startsWith("importcurve ") }, "the run is one `importcurve` step")
        assertTrue(text.contains("path="), "which carries the points themselves, never the file's bytes")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
        val back = reloaded.elements.filter { it.kind == ElementKind.SPACE_CURVE && it.visible }.single()
        assertEquals(moved, Curves3.polyline(Evaluator().path3(back.ref as Path3Ref)), "every point came back bit-identical")
    }

    /** An import is **one** checkpoint, wireframe included: one undo takes every run a file brought in. */
    @Test
    fun undoRemovesEveryRunOneImportBrought() {
        val ed = Editor()
        val before = ed.doc.elements.size
        assertTrue(ed.importFile(wireBytes(bentRun, flatRun), "wire.jt").ok)
        assertEquals(before + 5, ed.doc.elements.size, "two literals, two placements and the one shared anchor")
        assertTrue(ed.undo(), "there is something to undo")
        assertEquals(before, ed.doc.elements.size, "one undo, and everything the file brought is gone")
        assertEquals(emptyList(), runsOf(ed))
    }

    /** A run's literal is **hidden** in a recorded step, exactly as a body's is — one run is drawn, not two. */
    @Test
    fun theRawLiteralIsHiddenAndTheDecisionIsRecorded() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(bentRun), "wire.jt").ok)
        assertEquals(2, runsOf(ed).size, "the literal and the placement riding it")
        assertEquals(1, shownRuns(ed).size, "and only the placement is drawn")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().any { it.startsWith("hide ") }, "the hide is a recorded decision, not runtime state")
        val reloaded = DocumentFormat.load(text)
        assertEquals(1, reloaded.elements.count { it.kind == ElementKind.SPACE_CURVE && it.visible }, "and it survives a reload")
    }

    // ---- 3: it offers no construction inputs ----

    /**
     * **An imported run is a sink** — the same limit an imported body has, asserted rather than assumed.
     *
     * The literal is a node with **no inputs**: nothing of the file's geometry is a node of the drawing, so
     * there is no point to drag, no piece to break and no end to weld onto. The placement's inputs are the
     * four the drawing itself made — the literal, the space's plane, the anchor and the angle — and *those*
     * are what a user edits. A **constructed** run is the contrast: its points are its inputs.
     */
    @Test
    fun anImportedRunOffersNoConstructionInputs() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(bentRun), "wire.jt").ok)
        val literal = runsOf(ed).first { !it.visible }
        val placed = shownRuns(ed).single()

        assertEquals(0, literal.ref.node.inputs.size, "the literal is a constant: it takes nothing")
        assertEquals(4, placed.ref.node.inputs.size, "the placement takes the literal, the plane, the point and the angle")
        assertTrue(placed.ref.node.inputs.first() === literal.ref.node, "and the run it moves is the literal")

        // nothing of the file is a point of the drawing: the only point an import made is the anchor
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.POINT }, "one anchor, and not one file vertex")
        assertEquals(emptyList(), ed.doc.extractPoints(placed), "a run in space offers no key points to extract")
        assertNull(ed.doc.takeNote())

        // ...and the contrast: a constructed run *is* its points
        val ed2 = Editor()
        val built = constructedRun(ed2, Vec2(0.0, 0.0), Vec2(50.0, 0.0), Vec2(50.0, 40.0))
        assertEquals(3, built.ref.node.inputs.size, "a constructed run takes the three points it runs through")
    }

    // ---- 4: the mixed file ----

    /**
     * **A file with meshes *and* wireframe brings both in, and the result says so per part.**
     *
     * The note this replaces — *"centreline is wireframe only (no triangles) — not imported"* — is gone, and
     * that is the point of the step: what used to be an apology is a run of the drawing.
     */
    @Test
    fun aMixedFileBringsBothInAndSaysSoPerPart() {
        val ed = Editor()
        val wire =
            SceneNode(
                "centreline",
                JtMat4.IDENTITY,
                emptyList(),
                listOf(PolylineSet(listOf(JtVec3(0f, 0f, 0f), JtVec3(10f, 0f, 0f), JtVec3(10f, 10f, 5f)), listOf(listOf(0, 1, 2)))),
                null,
                emptyList(),
            )
        val cube = SceneNode("cube", JtMat4.IDENTITY, listOf(cubeMesh()), emptyList(), null, emptyList())
        val scene =
            Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(wire, cube)), emptyList())
        val result = Imports.importScene(ed.doc, scene, "mixed.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("cube"), result.bodies, "the body, by name")
        assertEquals(listOf("centreline"), result.runs, "and the wireframe, by name — in its own list")
        assertEquals(emptyList(), result.notes, "nothing left to apologise for")
        assertTrue(result.message.contains("1 body and 1 wireframe run"), result.message)
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID && it.visible })
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE && it.visible })
    }

    // ---- 5: the recorded planar gesture, and the tolerance from both sides ----

    /**
     * **A flat run becomes a sketch on its own plane, and the step replays.**
     *
     * What comes out is *ordinary* 2D geometry: free points and segments, in a sketch space whose plane is the
     * run's. The segments measure exactly what the file's chords measure, so nothing was projected — and the
     * whole thing survives `save → load → save` byte-equal, because the step carries the transcribed
     * coordinates rather than re-measuring the run.
     */
    @Test
    fun aFlatRunBecomesASketchOnItsOwnPlane() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(flatRun), "sketch.jt").ok)
        val run = shownRuns(ed).single()
        assertTrue(pathOf(ed, run).closed, "the file's own index run closes, so the value says so")

        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(30.0, 0.0))
        val space = ed.doc.activeSpace
        assertTrue(space.isWire, "the view is now on the wireframe's own plane: ${ed.doc.spaceLabel(space)}")
        assertTrue(ed.doc.spaceLabel(space).contains("plane of centreline"), ed.doc.spaceLabel(space))

        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT && it.space == space.name }
        val segs = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT && it.space == space.name }
        assertEquals(4, pts.size, "four points, each stated once")
        assertEquals(4, segs.size, "and four segments, because the run closes")
        assertClose(segs.sumOf { lengthOf(it) }, 60.0 + 40.0 + 60.0 + 40.0, 1e-9, "the traced segments measure the file's own chords")

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().any { it.startsWith("wiresketch ") }, "the tracing is one recorded step")
        assertTrue(text.lines().any { it.startsWith("sketchspace ") && it.contains("wire=") }, "and the plane is another")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
        assertEquals(4, reloaded.elements.count { it.kind == ElementKind.SEGMENT }, "and the sketch replays")
    }

    /** A traced point is the drawing's own from then on: drag it, and the save restates where it went. */
    @Test
    fun aTracedPointIsAnOrdinaryFreePointAndItsDragSurvivesASave() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(flatRun), "sketch.jt").ok)
        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(30.0, 0.0))
        val corner = ed.doc.elements.first { it.kind == ElementKind.POINT && it.space == ed.doc.activeSpace.name }
        val where = (Evaluator().eval(corner.ref.node) as EvalResult.Ok).let { (it.value as PointValue).p }
        ed.drag(where, where + Vec2(7.0, 0.0))
        val after = (Evaluator().eval(corner.ref.node) as EvalResult.Ok).let { (it.value as PointValue).p }
        assertClose(after.x - where.x, 7.0, 1e-6, "the traced point drags like any other")

        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "and the step restates where it went")
        val back = reloaded.elements.first { it.kind == ElementKind.POINT && it.space == ed.doc.activeSpace.name }
        val there = (Evaluator().eval(back.ref.node) as EvalResult.Ok).let { (it.value as PointValue).p }
        assertClose(there.x, after.x, 1e-9, "the dragged position came back, not the file's")
    }

    /** A run that is not flat is **refused by name**, and nothing is built — never quietly flattened. */
    @Test
    fun aRunThatIsNotFlatIsRefusedByName() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(bentRun), "route.jt").ok)
        val spaces = ed.doc.spaces.size
        val elements = ed.doc.elements.size
        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(50.0, 0.0))
        val note = ed.statusHint
        assertTrue(note.contains("centreline"), "the refusal names the run: $note")
        assertTrue(note.contains("not flat"), note)
        assertTrue(note.contains("curve in space, not a sketch"), note)
        assertEquals(spaces, ed.doc.spaces.size, "no space was made")
        assertEquals(elements, ed.doc.elements.size, "and nothing was built")
    }

    /**
     * **The tolerance, from both sides**: 0.01 mm, measured as the greatest distance of any of the run's
     * points from the best plane through them.
     *
     * The saddle's deviation is exactly `h / 2`, so these two runs miss a plane by 0.009 mm and 0.011 mm — ten
     * per cent either side of the limit. The lower one is a flat sketch written in single precision; the upper
     * one is a curve in space, and the drawing says which is which instead of flattening both.
     */
    @Test
    fun theFlatnessToleranceIsAssertedFromBothSides() {
        assertClose(Curves3.FLAT_TOL_MM, 0.01, 0.0, "the limit is one hundredth of a millimetre")

        val inside = Editor()
        assertTrue(inside.importFile(wireBytes(saddle(0.018)), "almost.jt").ok)
        inside.setTool(Tools.SKETCH_FROM_WIRE)
        inside.click(Vec2(50.0, 50.0))
        assertTrue(inside.doc.activeSpace.isWire, "0.009 mm off a plane is flat: ${inside.statusHint}")
        assertEquals(3, inside.doc.elements.count { it.kind == ElementKind.SEGMENT }, "and the open four-point run traced")

        val outside = Editor()
        assertTrue(outside.importFile(wireBytes(saddle(0.022)), "curved.jt").ok)
        outside.setTool(Tools.SKETCH_FROM_WIRE)
        outside.click(Vec2(50.0, 50.0))
        assertFalse(outside.doc.activeSpace.isWire, "0.011 mm off a plane is not")
        assertTrue(outside.statusHint.contains("not flat"), outside.statusHint)
        assertEquals(0, outside.doc.elements.count { it.kind == ElementKind.SEGMENT })

        // ...and the measurement itself, stated directly, so the two sides are about the geometry and not
        // about how the gesture happens to be driven
        assertNotNull(Curves3.planeOfRun(Path3(Curves3.straightThrough(saddle(0.018)))).first)
        assertNull(Curves3.planeOfRun(Path3(Curves3.straightThrough(saddle(0.022)))).first)
    }

    /**
     * **A straight run is refused too, and for the honest reason**: it lies in infinitely many planes, so
     * there is no plane to pick and picking one would be inventing a rotation.
     */
    @Test
    fun aStraightRunHasNoPlaneAndSaysSo() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0))), "rail.jt").ok)
        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(100.0, 0.0))
        assertTrue(ed.statusHint.contains("infinitely many planes"), ed.statusHint)
        assertFalse(ed.doc.activeSpace.isWire)
    }

    /**
     * **A constructed run is refused by name**, and the reason is doctrine rather than convenience: its
     * planarity is either a fact of how it was built or a value that changes under a drag, and a *gesture* may
     * not refuse on a value. Only a frozen literal moved by a rigid placement can answer this question the
     * same way for ever, which is why the tool is offered on imports alone.
     */
    @Test
    fun aConstructedRunIsRefusedBecauseItsPlanarityIsNotAMeasurement() {
        val ed = Editor()
        constructedRun(ed, Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0))
        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.statusHint.contains("was not imported"), ed.statusHint)
        assertFalse(ed.doc.activeSpace.isWire, "and no space was made")
    }

    /** The traced sketch **rides the placement**: drag the wireframe's anchor and the sketch space follows. */
    @Test
    fun theTracedSketchFollowsTheImportedRunsPlacement() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(flatRun), "sketch.jt").ok)
        ed.setTool(Tools.SKETCH_FROM_WIRE)
        ed.click(Vec2(30.0, 0.0))
        val space = ed.doc.activeSpace
        val before = originOf(space)

        ed.doc.switchSpace("plan")
        val anchor = ed.doc.elements.first { it.kind == ElementKind.POINT && it.space == "plan" }
        val at = (Evaluator().eval(anchor.ref.node) as EvalResult.Ok).let { (it.value as PointValue).p }
        ed.drag(at, at + Vec2(25.0, 15.0))

        val after = originOf(space)
        assertEquals(before + Vec3(25.0, 15.0, 0.0), after, "the sketch plane moved with the body, exactly")
    }

    // ---- 6: it composes ----

    /**
     * **A tube sweeps along an imported run**, and a **station** stands on one — the two things a run in
     * space is *for*, asked of a run that came out of a file rather than out of a construction.
     *
     * Nothing about either operation knows where the run came from, which is the whole claim of reusing the
     * `SPACE_CURVE` element kind: a `PATH3` slot takes a run, and an imported run is a run.
     */
    @Test
    fun aTubeSweepsAlongAnImportedRunAndAStationStandsOnIt() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(bentRun), "route.jt").ok)
        val run = shownRuns(ed).single()

        ed.setTool(Tools.TUBE)
        ed.type("6")
        ed.click(Vec2(50.0, 0.0))
        val tube = ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }
        assertNotNull(tube, "a tube rides an imported route: ${ed.statusHint}")
        val mesh = Evaluator().solid(tube.ref as SolidRef).mesh
        assertTrue(constructit.geom.Geom3.volume(mesh) > 0.0, "and it encloses material")
        assertNull(constructit.geom.Watertight.defect(mesh), "watertight, like every solid this kernel makes")

        ed.setTool(Tools.STATION)
        ed.type("100")
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.doc.activeSpace.isStation, "a station stands on it: ${ed.statusHint}")
        assertEquals(run.id, ed.doc.activeSpace.station?.id, "and it is a station of the imported run")
    }

    /**
     * **A connect joins an imported run to a constructed one** — a derived curve over an imported operand,
     * which is the provenance mix OP-26's parenting rule says must simply work.
     */
    @Test
    fun aConnectJoinsAnImportedRunToAConstructedOne() {
        val ed = Editor()
        assertTrue(ed.importFile(wireBytes(listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0))), "stub.jt").ok)
        val built = constructedRun(ed, Vec2(200.0, 60.0), Vec2(300.0, 60.0))

        ed.setTool(Tools.CONNECT)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(200.0, 60.0))
        val join = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val path = pathOf(ed, join)
        assertTrue(join !== built, "the join is a curve of its own: ${ed.statusHint}")
        assertTrue(path.elements.isNotEmpty(), "and it has geometry: ${ed.statusHint}")
        // it meets an end of each run — which end is the click's choice, persisted; what matters here is that
        // an *imported* operand is an operand like any other
        val imported = pointsOf(ed, shownRuns(ed).first())
        val constructed = pointsOf(ed, built)
        assertTrue(
            (imported + constructed).any { (it - path.start!!).length() < 1e-6 },
            "the join starts on one of the two runs (at ${path.start})",
        )
        assertTrue(
            (imported + constructed).any { (it - path.end!!).length() < 1e-6 },
            "and ends on the other (at ${path.end})",
        )
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }
}
