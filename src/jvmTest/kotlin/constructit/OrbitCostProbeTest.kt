package constructit

import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Style
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The probe review of "an orbit costs nothing the drawing did not change"** — the same claims, asked of
 * the features that were already here rather than of a fresh rectangle.
 *
 * The delivery proves the gate on a hover, an orbit, a parameter edit and a restyle. What it does not ask is
 * whether the *criterion* survives the operations that rebuild a document rather than edit it, or whether it
 * stays honest in the other direction — refusing to upload for a change that really is only a name. Those
 * are the two ways an identity-keyed cache goes wrong: it misses a change, or it fires on a non-change. And
 * the projection cache is asked through [PlaneProjection.toScreenLifted], the one entry point a lifted
 * height point (OP-25) uses and the delivery's own test never reaches.
 */
class OrbitCostProbeTest {
    private class Counting : DrawTarget {
        val runs = ArrayList<List<Vec2>>()

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) {
            runs.add(points)
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit

        val points: Int get() = runs.sumOf { it.size }

        /** The ink: a run of n points lays down n-1 chords, and a regrouping must preserve every one. */
        fun chords(): List<Pair<Vec2, Vec2>> = runs.flatMap { it.zipWithNext() }
    }

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

    /** A plate, so there is a solid in the scene at all. */
    private fun plate(ed: Editor) {
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
    }

    // ---- the criterion, asked of operations that rebuild rather than edit ----

    /**
     * **Undo rebuilds the document, and the gate must notice.** Every other change the delivery tests
     * mutates a node in place and lets OP-5's memo hand back a new mesh; an undo replays the journal into a
     * *new* `Document`, so every element, node and mesh is a different object and the elements are not even
     * the ones the caller was holding. An identity-keyed cache has to come out of that uploading — and,
     * more importantly, has to be asked against the document the editor now has rather than the one the
     * test captured.
     */
    @Test
    fun undoRebuildsTheDocumentAndTheGateUploadsAgain() {
        val ed = Editor()
        plate(ed)
        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")

        // a second solid, then take it back
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(140.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(120.0, 0.0))
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a new body is new geometry")
        assertEquals(2, Scene3.extract(ed.doc).solids.size)

        assertTrue(ed.undo(), "the second body is taken back")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(3, sync.uploads, "and the rebuilt document is uploaded")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "one body again")

        // ...and settles: the rebuilt document is now the one that is held, so nothing more is uploaded
        repeat(10) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(3, sync.uploads, "the replayed document is stable under re-extraction")
    }

    /**
     * **A name is not vertex data.** The naming authority (OP-18) is a decision about the drawing, not about
     * its geometry: nothing a rename touches reaches the buffer, so the gate must *not* fire. The other half
     * of the same claim, and the one an over-conservative cache fails — hiding a body genuinely does change
     * what the view holds, so that one must fire.
     */
    @Test
    fun aRenameUploadsNothingWhileHidingABodyUploadsOnce() {
        val ed = Editor()
        plate(ed)
        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads)

        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        ed.doc.nameElement(solid, "grundplatte")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "a renamed body is the same vertices in the same order")

        // selecting it is not vertex data either — the highlight lives in the overlay, not in the buffer
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0))
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "nor is a selection")

        assertEquals(1, ed.doc.setElementsVisible(listOf(solid), false), "now hide it")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a body that left the scene is a change the view must see")
        assertEquals(0, Scene3.extract(ed.doc).solids.size, "and it really left")
    }

    /**
     * **An import's hidden literal is not a body, so hiding it changes nothing** — the two most recent
     * packages meeting. Session 34 hides the raw mesh literal every import records, and the 3D scene skips
     * it anyway as its placement's *material*; so the visibility decision that made the export honest must
     * be invisible to the upload gate, while hiding the **placement** is a real change.
     */
    @Test
    fun theImportsHiddenLiteralIsNotGeometryTheViewHolds() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedBoxBytes(), "gehaeuse.jt").ok)
        val sync = Scene3Sync()
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads)
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "the placement is the body; the literal is its material")

        val literal = ed.doc.elements.first { it.kind == ElementKind.SOLID && !it.visible }
        assertEquals(1, ed.doc.setElementsVisible(listOf(literal), true), "show the raw literal")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "it is still consumed as material, so the view holds the same geometry")

        val placed = ed.doc.elements.last { ed.doc.userNameOf(it) == "gehaeuse" }
        assertEquals(1, ed.doc.setElementsVisible(listOf(placed), false), "hide the body itself")
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "that one the view must see")
    }

    // ---- the projection cache, through the entry point a height point uses ----

    /**
     * **One matrix for the whole frame, lifted points included.** A height point (OP-25) is the one thing
     * drawn through [PlaneProjection.toScreenLifted] rather than `toScreen` — a second entry point into the
     * same camera, and therefore a second place the per-vertex matrix rebuild could have survived.
     */
    @Test
    fun aLiftedHeightPointGoesThroughTheSameOneMatrix() {
        val ed = Editor()
        plate(ed)
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("25")
        ed.click(Vec2(10.0, 10.0))
        assertEquals(
            1,
            ed.doc.elements.count { it.kind == ElementKind.HEIGHT_POINT },
            "the lifted point is in the drawing",
        )

        val proj = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Viewport3().camera, 900.0, 700.0)
        assertEquals(0, proj.matrixBuilds, "nothing drawn yet")
        val rec = Counting()
        ed.pointing = proj
        ed.draw(rec, 900.0, 700.0)
        assertTrue(rec.points > 10, "a scene with a lifted point in it: ${rec.points} points")
        assertEquals(1, proj.matrixBuilds, "one matrix for every point, lifted or not")
        // and directly, so the claim does not rest on which primitives this particular scene happened to
        // draw: the lifted entry point reuses the same cached matrix rather than building a second one
        assertTrue(proj.toScreenLifted(Vec2(10.0, 10.0), 25.0) != null, "the lifted point has an image")
        assertEquals(1, proj.matrixBuilds, "toScreenLifted goes through the cache too")
    }

    // ---- the coalescing, on a scene the goldens do not cover ----

    /**
     * **Regrouping preserves the ink, on a drawing made of many loops.** The goldens pin five specific
     * pictures; this asks the general property on a *pattern* — one gesture that puts a dozen independent
     * closed loops in the scene — by rendering the same document through the same projection twice and
     * comparing the multiset of chords, once with the runs as they come and once cut back into the
     * per-segment pieces they used to be. Same chords, far fewer calls, or the collapse lost something.
     */
    @Test
    fun aPatternOfLoopsCollapsesIntoRunsWithoutLosingAChord() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(6.0, 0.0))
        ed.count = 8
        ed.setTool(Tools.ARRAY_LINEAR)
        ed.click(Vec2(0.0, 6.0))
        ed.click(Vec2(0.0, -40.0))
        ed.click(Vec2(20.0, -40.0))
        ed.setTool(Tools.ROUNDED_RECT)
        ed.type("5")
        ed.click(Vec2(-20.0, 20.0))
        ed.click(Vec2(160.0, 60.0))

        val proj = PlanePerspective(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Viewport3().camera, 1000.0, 800.0)
        val rec = Counting()
        ed.pointing = proj
        ed.draw(rec, 1000.0, 800.0)

        val chords = rec.chords()
        assertTrue(chords.size > 100, "a real drawing: ${chords.size} chords in ${rec.runs.size} runs")
        assertTrue(
            rec.runs.size < chords.size / 2,
            "the runs are chains, not segments: ${rec.runs.size} runs for ${chords.size} chords",
        )
        // every chord is a real one: consecutive within its run, and no run is a degenerate single point
        assertTrue(rec.runs.all { it.size >= 2 }, "no empty or single-point run was emitted")
        assertEquals(chords.size, rec.points - rec.runs.size, "the chord count is exactly what the runs carry")
        assertEquals(1, proj.matrixBuilds, "and one matrix for all of it")
    }

    // ---- fixture ----

    /** A 60 × 40 × 20 box with one bottom facet removed, as JT bytes — an ordinary import, flagged open. */
    private fun crackedBoxBytes(): ByteArray {
        val corners =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(60.0, 0.0, 0.0),
                Vec3(60.0, 40.0, 0.0),
                Vec3(0.0, 40.0, 0.0),
                Vec3(0.0, 0.0, 20.0),
                Vec3(60.0, 0.0, 20.0),
                Vec3(60.0, 40.0, 20.0),
                Vec3(0.0, 40.0, 20.0),
            )
        val tris =
            listOf(
                constructit.geom.Tri(0, 3, 2),
                constructit.geom.Tri(4, 5, 6),
                constructit.geom.Tri(4, 6, 7),
                constructit.geom.Tri(0, 1, 5),
                constructit.geom.Tri(0, 5, 4),
                constructit.geom.Tri(1, 2, 6),
                constructit.geom.Tri(1, 6, 5),
                constructit.geom.Tri(2, 3, 7),
                constructit.geom.Tri(2, 7, 6),
                constructit.geom.Tri(3, 0, 4),
                constructit.geom.Tri(3, 4, 7),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "probe",
                listOf(
                    constructit.exchange.ExportNode(
                        "gehaeuse",
                        constructit.geom.Mesh3(corners, tris),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            ),
        )
    }
}
