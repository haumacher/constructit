package constructit

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Style
import constructit.editor.Styles
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **An orbit changes no model value, so it may recompute, re-project and re-emit nothing.**
 *
 * The user loaded a 36-body JT assembly and found the three.js preview smooth while the 3D *construction*
 * view was unusable to orbit. The cause was four independent defects, none of them in the geometry: the
 * view-projection matrix was rebuilt per vertex, a chain of segments was emitted as one draw call per
 * segment, a camera drag rebuilt the whole side panel, and a plain hover re-uploaded the mesh. What they
 * have in common is the thing this file asserts — *work done because the eye moved, or because the pointer
 * moved, rather than because the drawing changed*.
 *
 * Every assertion here is a **count**, never a wall clock. A timing assertion on a shared machine is a coin
 * toss, and it would also be asserting the wrong thing: what was wrong was structural (`O(points)` matrices,
 * `O(segments)` draw calls, `O(triangles)` per mousemove), so the structure is what is pinned. Where the
 * count depends on the real fixture the test says so and skips when it is not there, exactly as
 * [JtImportTest] does.
 */
class RenderCostTest {
    /** Everything a [DrawTarget] was asked to draw, kept so a test can count *and* look at it. */
    private class Recording : DrawTarget {
        val polylines = ArrayList<Pair<List<Vec2>, Style>>()
        var circles = 0
        var dots = 0
        var polygons = 0

        val points: Int get() = polylines.sumOf { it.first.size }

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            polylines.add(points to style)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) {
            polygons++
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) {
            circles++
        }

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) {
            dots++
        }

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit

        /** The polylines drawn in [style] — one element's worth, so a count is about that element. */
        fun inStyle(style: Style): List<List<Vec2>> = polylines.filter { it.second == style }.map { it.first }
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    /** A number typed for a tool's scalar slot, key by key, as the shell delivers it. */
    private fun Editor.type(digits: String) {
        digits.forEach { key(it.toString()) }
        key("Enter")
    }

    /** The consecutive pairs of a polyline — the segments a stroke really lays down. */
    private fun chords(points: List<Vec2>): List<Pair<Vec2, Vec2>> = points.zipWithNext()

    // ---- (b) the chain coalescing, exactly ----

    /**
     * **A closed loop is one polyline, and it closes.** Four segments meeting end to end were four draw
     * calls; they are one run of five points whose last is its first, which is the same ink and one stroke.
     */
    @Test
    fun aClosedRectangularLoopDrawsAsOnePolylineOfFivePoints() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        // two picks fix the direction and the boundary-follow closes the ring (OP-14) — a `Loop` of four Segs
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(60.0, 20.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "one traced loop to draw")
        val rec = Recording()
        ed.render(rec)

        val loops = rec.inStyle(Styles.RESULT)
        assertEquals(1, loops.size, "the rectangle's four sides are one polyline, not four: ${loops.map { it.size }}")
        val ring = loops.single()
        assertEquals(5, ring.size, "four corners and the closing repeat")
        assertEquals(ring.first(), ring.last(), "the run comes back to where it started, so the loop draws closed")
    }

    /**
     * **A tessellated arc joins the run like any other piece.** A rounded rectangle is four segments and
     * four arcs; the arcs are already multi-point runs, and the rule that joins them is the same one — the
     * end of the last piece is the start of the next.
     */
    @Test
    fun aLoopOfSegmentsAndArcsDrawsAsOnePolyline() {
        val ed = Editor()
        ed.setTool(Tools.ROUNDED_RECT)
        ed.type("8")
        ed.click(Vec2(-60.0, -40.0))
        ed.click(Vec2(60.0, 40.0))
        ed.setTool(Tools.OUTLINE)
        // a side and the corner arc next to it (clicked at its 45 degree point); the follow walks the rest
        val d = 8.0 * kotlin.math.cos(kotlin.math.PI / 4)
        ed.click(Vec2(0.0, 40.0))
        ed.click(Vec2(52.0 + d, 32.0 + d))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "one traced loop to draw")
        val rec = Recording()
        ed.render(rec)

        val loops = rec.inStyle(Styles.RESULT)
        assertEquals(1, loops.size, "segments and arcs are one run: ${loops.map { it.size }}")
        val ring = loops.single()
        assertTrue(ring.size > 9, "the four arcs contributed their chords: ${ring.size} points")
        assertEquals(ring.first(), ring.last(), "and it still closes")
    }

    /**
     * **An open chain stays open — the session-34 constraint, and the one way this could have been wrong.**
     *
     * A `Loop` from [constructit.geom.Silhouette] is not always closed: an inconsistently wound or
     * non-manifold mesh claims one directed edge twice, the outline no longer balances, and the walk
     * genuinely dead-ends. That outline is drawn as the open polyline it is. Coalescing must therefore
     * *break* the run at every gap and never bridge one — a bridge would be a line across the drawing that
     * exists nowhere in the model, which is exactly the failure the old per-segment emission could not have.
     *
     * So the assertion is made against the source: every chord the target was given is a piece of the
     * body's own footprint, and every piece of the footprint was given to the target. Nothing invented,
     * nothing lost — and more than one polyline, because the chain really is in pieces.
     */
    @Test
    fun anOpenSilhouetteChainDrawsInPiecesAndInventsNoJoiningSegment() {
        val ed = Editor()
        assertTrue(ed.importFile(inconsistentlyWoundBytes(), "wonky.jt").ok)
        val rec = Recording()
        ed.render(rec)

        val ev = Evaluator()
        val body = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val footprint = (ev.valueOf(body.ref) as SolidValue).solid.feature.footprint
        assertTrue(footprint.isNotEmpty(), "the wonky body has an outline to draw")
        val open = footprint.count { r -> !closes(r.outer.elements) }
        assertTrue(open > 0, "and at least one of its chains really is open ($open of ${footprint.size})")

        // what the model says, in screen pixels — the projection is the canvas camera, the same one the
        // renderer used, so the two are comparable exactly
        val expected = HashSet<Pair<Vec2, Vec2>>()
        for (region in footprint) {
            for (loop in listOf(region.outer) + region.holes) {
                for (el in loop.elements) {
                    val s = (el as ProfileElement.Seg).segment
                    expected.add(ed.camera.worldToScreen(s.a) to ed.camera.worldToScreen(s.b))
                }
            }
        }
        val drawn = rec.inStyle(Styles.SOLID)
        assertTrue(drawn.size > 1, "an outline in pieces is drawn in pieces, not bridged into one: ${drawn.size}")
        val drawnChords = drawn.flatMap { chords(it) }
        assertEquals(
            expected.size,
            drawnChords.size,
            "every piece of the outline is drawn exactly once — no chord added, none dropped",
        )
        for (c in drawnChords) {
            assertTrue(c in expected, "a segment was drawn that is not in the chain: $c")
        }
        // ...and the collapse is real: 1 polyline per piece would be `expected.size` of them
        assertTrue(drawn.size < expected.size, "${drawn.size} polylines for ${expected.size} pieces")
    }

    /** Whether a chain's last piece ends where its first begins. */
    private fun closes(elements: List<ProfileElement>): Boolean {
        val first = elements.first() as? ProfileElement.Seg ?: return true
        val last = elements.last() as? ProfileElement.Seg ?: return true
        return last.segment.b == first.segment.a
    }

    // ---- (a) the view-projection matrix, built once per projection ----

    /**
     * **One matrix per projection, however many points go through it.**
     *
     * [PlanePerspective.matrixBuilds] is the seam, and it is `internal` for this test alone: what is being
     * asserted is a *count of work*, which nothing observable from outside the module reports. The
     * alternative — spying on [constructit.editor.Camera3] — has no seam at all, since it is a data class.
     */
    @Test
    fun theViewProjectionIsBuiltOncePerProjectionNotOncePerPoint() {
        val ed = Editor()
        ed.setTool(Tools.ROUNDED_RECT)
        ed.type("8")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))

        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val proj = PlanePerspective(plane, Viewport3().camera, 800.0, 600.0)
        assertEquals(0, proj.matrixBuilds, "nothing drawn yet, nothing built")

        val rec = Recording()
        ed.pointing = proj
        ed.draw(rec, 800.0, 600.0)
        assertTrue(rec.points > 50, "a scene with something in it: ${rec.points} projected points")
        assertEquals(1, proj.matrixBuilds, "one matrix for ${rec.points} points")
    }

    // ---- (b) + (a) together, on the real assembly ----

    /**
     * **The whole-scene count, on the file the report came from.** The numbers in the message are the
     * measurement: they were 110652 polylines before the coalescing and are two orders of magnitude fewer
     * after, over the *same* points — which is the property that matters, since a collapse that lost
     * geometry would collapse further still.
     */
    @Test
    fun theImportedAssemblysOverlayEmitsFarFewerPolylinesOverTheSamePoints() {
        val fixture = File("/home/haui/devel/kotlinJT/fixtures/nist-mtc-crada-assembly.jt")
        if (!fixture.isFile) return // the sibling's committed fixture; nothing to say without it
        val ed = Editor()
        assertTrue(ed.importFile(fixture.readBytes(), fixture.name).ok)

        val rec = Recording()
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val proj = PlanePerspective(plane, Viewport3().camera, 1200.0, 800.0)
        ed.pointing = proj
        ed.draw(rec, 1200.0, 800.0)

        // The chords are the ink: a run of n points lays down n-1 of them, and *that* is what must be
        // preserved by a regrouping. Before the coalescing this frame was 110652 polylines of two points
        // (221304 points, 110652 chords); it is now 3202 polylines of 113854 points — the same 110652
        // chords, in 2.9 % of the draw calls.
        val chords = rec.points - rec.polylines.size
        val message = "${rec.polylines.size} polylines, ${rec.points} points, $chords chords, ${proj.matrixBuilds} matrix build(s)"
        assertTrue(rec.polylines.size < 5000, "the overlay is emitted as chains, not as segments — $message")
        assertTrue(chords > 100000, "and every chord of the assembly is still drawn — $message")
        assertEquals(1, proj.matrixBuilds, "one matrix for the whole frame — $message")
    }

    // ---- (e) a hover does not re-upload the mesh ----

    /**
     * **A hover moves no vertex.** The 3D view's geometry is rebuilt only when the document's *solids*
     * change, and the criterion is mesh identity (OP-5) rather than a flag anybody has to remember to set —
     * see [Scene3Sync]. This drives the real path: a previewing tool armed, hovers routed through
     * [Viewport3] exactly as the browser routes them, and the shell's own upload gate behind them.
     */
    @Test
    fun aHoverOverTheThreeDViewUploadsNothingWhileAnEditUploadsOnce() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))

        // the browser shell's own wiring, in the two lines it is: a change asks, and Scene3Sync answers
        val sync = Scene3Sync()
        var checks = 0
        ed.onChange = {
            checks++
            sync.update(Scene3.extract(ed.doc)) { }
        }
        val view = Viewport3(widthPx = 800.0, heightPx = 600.0)
        view.editor = ed
        view.shown = true
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")

        // a point-placing tool armed, and the pointer moved across the view: previews refresh, nothing else
        ed.setTool(Tools.SEGMENT)
        for (i in 0 until 40) view.pointerMove(Vec2(200.0 + i, 300.0 + i))
        assertTrue(checks >= 40, "the hovers really did reach the editor and report a change ($checks)")
        assertEquals(1, sync.uploads, "...and not one of them rebuilt the mesh")

        // an orbit, for the same reason one step further out: it does not even reach the editor
        view.cameraModifier = true
        view.pointerDown(Vec2(400.0, 300.0))
        for (i in 0 until 20) view.pointerMove(Vec2(400.0 + i * 3, 300.0))
        view.pointerUp(Vec2(460.0, 300.0))
        assertEquals(1, sync.uploads, "an orbit uploads nothing")

        // ...while a change that really is a change does
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "h" }, 35.0.mm)
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a solid that changed is uploaded again")
    }

    /** The gate's own claim, on its own: identical extractions are identical geometry (OP-5). */
    @Test
    fun repeatedExtractionsOfAnUnchangedDocumentUploadOnce() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        val sync = Scene3Sync()
        repeat(20) { sync.update(Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "the mesh is the same object every time, so it is uploaded once")

        // a colour is *in* the vertex data, so assigning one must re-upload even though no mesh changed
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        ed.doc.setMaterial(solid, constructit.editor.Appearance(color = "#123456"))
        sync.update(Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "a restyled solid is new vertex data")
    }

    // ---- fixtures ----

    /**
     * A body whose triangles are **inconsistently wound**: one directed edge is claimed twice, so its
     * silhouette cannot close and comes out as open chains — the session-34 case, written to real JT bytes
     * so it arrives through the ordinary import route and is drawn as an ordinary body.
     */
    private fun inconsistentlyWoundBytes(): ByteArray {
        val v =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 0.0),
                Vec3(50.0, 100.0, 0.0),
                Vec3(50.0, 50.0, 0.0),
                Vec3(50.0, -50.0, 0.0),
            )
        // All three face the projection, and the edge 0-1 is claimed **twice** in one direction and once in
        // the other — so the directed edges no longer balance at vertex 0, the outline walk dead-ends, and
        // one of the two chains comes out open. That is the session-34 case, made as small as it goes.
        val mesh =
            constructit.geom.Mesh3(
                v,
                listOf(
                    constructit.geom.Tri(0, 1, 2),
                    constructit.geom.Tri(0, 1, 3),
                    constructit.geom.Tri(1, 0, 4),
                ),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "wonky",
                listOf(constructit.exchange.ExportNode("wonky", mesh, constructit.editor.Appearance.DEFAULT)),
            ),
        )
    }
}
