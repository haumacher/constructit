package constructit

import constructit.core.Evaluator
import constructit.core.Node
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **The probe for the deferred mesh** (session 52, slice A) — the claim taken past "a plan drag builds no
 * triangles" and put next to the things that live downstream of a mesh.
 *
 * Three questions the delivery's own acceptance does not ask. (a) The mesh *object* is what `SceneSync`
 * swaps on (`had.mesh === node.mesh`), so deferral is only a win in the 3D view if an unrelated 2D edit
 * leaves that object alone — otherwise every stray point re-uploads a spring to the GPU. (b) The new exact
 * plan hint is stated in terms of a *round* section's support point; a **non-round** one takes the other
 * branch, and if that branch fell back to the mesh the report's fix would hold for tubes only. And it is the
 * **pick target**, so it has to be clickable where it is drawn. (c) Everything that genuinely needs
 * triangles — a swept solid's section, a volume, an export — must still get the same numbers as before, and
 * must still get them after a save and a reload.
 */
class DeferredMeshProbeTest {
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

    private fun nodes(ed: Editor): List<Node> {
        val seen = LinkedHashMap<Node, Unit>()

        fun walk(n: Node) {
            if (seen.put(n, Unit) != null) return
            n.inputs.forEach { walk(it) }
        }
        ed.doc.elements.forEach { walk(it.ref.node) }
        ed.doc.scalars.forEach { walk(it.ref.node) }
        return seen.keys.toList()
    }

    private fun meshes(ed: Editor): Int = nodes(ed).sumOf { it.meshCount }

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(
        ed: Editor,
        el: Element,
    ): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    /**
     * A tube along a three-turn coil — the body from the report. The coil is drawn from two placed points
     * (session 51), so its centre is draggable in the plan, which is what the drag below grabs.
     */
    private fun springAt(
        ed: Editor,
        centre: Vec2,
        pitch: String = "10",
        turns: String = "3",
        profileR: String = "3",
    ): Element {
        ed.setTool(Tools.HELIX_PT)
        ed.type(pitch)
        ed.type(turns)
        ed.click(centre)
        ed.click(centre + Vec2(20.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "a coil: ${ed.statusHint}")
        ed.setTool(Tools.TUBE)
        ed.type(profileR)
        ed.click(centre + Vec2(20.0, 0.0))
        return assertNotNull(solids(ed).lastOrNull(), "a tube along the coil: ${ed.statusHint}")
    }

    /**
     * **An unrelated 2D edit must leave the mesh object alone.** `SceneSync` re-uploads a body when its
     * `Mesh3` is a different object (`had.mesh === node.mesh`), so a deferral that handed out a fresh mesh
     * per extraction would have moved the cost from meshing to uploading and the 3D view would still stutter.
     *
     * The chain of claims: extract twice with nothing touched and it is the *same object* (OP-5's memo
     * returns the same value, whose cell is already built); place a point somewhere else — a real edit, a new
     * step, a checkpoint — and it is *still* the same object, because the tube's cone was not in it; move the
     * coil's centre and it is a *new* one, with exactly one derivation charged for it.
     */
    @Test
    fun anUnrelatedEditDoesNotRebuildOrReuploadTheMesh() {
        val ed = Editor()
        val tube = springAt(ed, Vec2(0.0, 0.0))
        assertEquals(0, meshes(ed), "the plan alone built nothing")

        val first = Scene3.extract(ed.doc).solids.single().mesh
        assertEquals(1, meshes(ed), "the 3D view asked once")
        assertSame(first, Scene3.extract(ed.doc).solids.single().mesh, "…and a second look is the same object")
        assertEquals(1, meshes(ed), "…with no second derivation")

        ed.setTool(Tools.POINT)
        ed.click(Vec2(200.0, 200.0))
        assertSame(
            first,
            Scene3.extract(ed.doc).solids.single().mesh,
            "a point elsewhere is not this body's business — no re-upload",
        )
        assertEquals(1, meshes(ed), "…and no rebuild")

        // now touch what the tube is built on: a new mesh, derived exactly once
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(0.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(4.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(4.0, 0.0)))
        val second = Scene3.extract(ed.doc).solids.single().mesh
        assertTrue(second !== first, "moving the coil's centre is a new body")
        assertEquals(2, meshes(ed), "…derived once, not once per consumer")
        assertNotNull(tube)
    }

    /**
     * **A square-sectioned run gets the exact hint too, and the hint is clickable.** The support point of a
     * *round* section is the analytic radius; a general section takes the other branch. If that branch had
     * been left on the mesh silhouette, the report's own fix would cover tubes and nothing else.
     *
     * So: a bar of square section swept along a coil, dragged in the plan — **zero** triangles — and then a
     * click on the hint where it is drawn still picks the body, which is the invariant that makes a footprint
     * hint a pick target at all (OP-17).
     */
    @Test
    fun aNonRoundSectionAlsoDrawsItsPlanFromTheRunAndStaysPickable() {
        val ed = Editor()
        // a square profile in the plan, then a coil, then the sweep that carries the square along it
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-4.0, -4.0))
        ed.click(Vec2(4.0, 4.0))
        ed.setTool(Tools.HELIX_PT)
        // pitch 20: the square's reach from the run is its half-diagonal (5.66 mm), so the coil must leave
        // more than 11.3 mm between passes — at 10 the sweep refuses by name, which is correct and not this
        // probe's subject
        ed.type("20")
        ed.type("2")
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(85.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "a coil: ${ed.statusHint}")
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(85.0, 0.0))
        ed.click(Vec2(0.0, -4.0))
        val bar = assertNotNull(solids(ed).lastOrNull(), "a square bar along the coil: ${ed.statusHint}")
        assertEquals(0, meshes(ed), "building it in the plan built no triangles: ${ed.statusHint}")

        // its plan hint exists and is made of loops the run states — no mesh asked for
        val hint = Evaluator().solid(bar.ref as SolidRef).feature.footprint
        assertTrue(hint.isNotEmpty(), "the bar draws a footprint hint")
        assertEquals(0, meshes(ed), "…and drawing it needed no mesh")

        // a full plan repaint, then a drag of the coil's centre: still nothing
        ed.render(SvgDrawTarget())
        ed.setTool(Tools.SELECT)
        val from = ed.camera.worldToScreen(Vec2(60.0, 0.0))
        ed.pointerDown(from)
        for (i in 1..8) ed.pointerMove(ed.camera.worldToScreen(Vec2(60.0 + i * 0.5, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(64.0, 0.0)))
        assertEquals(0, meshes(ed), "eight frames of dragging a square-sectioned spring: no triangles")

        // …and the hint is still what the pointer reaches: click the outer rail of the coil's plan
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(64.0 + 25.0, 0.0))
        assertTrue(
            ed.selectionCount > 0,
            "a click on the drawn hint picks something rather than nothing: ${ed.statusHint}",
        )
        assertNotNull(coil)
    }

    /**
     * **What genuinely needs triangles still gets the same ones, and gets them after a reload.** A sweep has
     * no analytic faces, so its section *is* the mesh section (`SWEEP_ONLY`) — the documented cost of slice A
     * is that such a document pays for a mesh. That cost is pinned here rather than left in prose, together
     * with the numbers on either side of a save: a volume is a number the drawing reports, so it may not
     * depend on when the mesh happened to be built.
     */
    @Test
    fun aVolumeAndASectionAreTheSameNumbersBeforeAndAfterASaveAlthoughBothPayForAMesh() {
        val ed = Editor()
        val tube = springAt(ed, Vec2(0.0, 0.0), pitch = "12", turns = "2", profileR = "2.5")
        assertEquals(0, meshes(ed), "nothing yet")

        val vol = volumeOf(ed, tube)
        assertTrue(meshes(ed) >= 1, "a volume is a number, so it paid for the mesh — the documented cost")
        // a torus-ish approximation is not the point; the point is that it is a real, stable number
        assertTrue(vol > 0.0, "a spring has volume")

        // asking again costs nothing more: the cell is built
        val before = meshes(ed)
        assertEquals(vol, volumeOf(ed, tube), absoluteTolerance = 0.0, message = "the same triangles, so the very same number")
        assertEquals(before, meshes(ed), "…and no second derivation")

        val text = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(back), "save → load → save is byte-equal")
        val reloaded = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the tube came back")

        @Suppress("UNCHECKED_CAST")
        val volBack = Geom3.volume(Evaluator().solid(reloaded.ref as SolidRef).mesh)
        assertEquals(vol, volBack, absoluteTolerance = 1e-9, message = "a reloaded body reports the same volume")

        // and the analytic sanity check the number is worth: a tube of radius 2.5 on a 2-turn coil of
        // radius 20, pitch 12 — length is the coil's arc length, so volume ≈ pi r^2 L, inscribed twice
        val r = 20.0
        val b = 12.0 / (2.0 * PI)
        val len = 2.0 * 2.0 * PI * kotlin.math.sqrt(r * r + b * b)
        val analytic = PI * 2.5 * 2.5 * len
        assertTrue(vol < analytic, "an inscribed tube must fall short of pi*r^2*L (was $vol, analytic $analytic)")
        assertTrue(vol > analytic * 0.9, "…but only by the tessellation, not by an order (was $vol, analytic $analytic)")
    }
}
