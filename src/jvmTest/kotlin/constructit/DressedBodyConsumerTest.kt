package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Everything built on a dressed body follows its roundings** (OP-30, and the orchestrator's probe that
 * found it did not).
 *
 * The cause, once, because every test here holds the same rule. A dressed body publishes its solid behind a
 * re-pointable view ([constructit.dsl.Construction.indirect]), and **adding** a rounding always re-stamped
 * that view in place — but **removing** one went through the delete machinery's journal edit and *replay*,
 * which builds a whole new document. Everything already built on the body then went on reading the body of
 * the document that was thrown away. Removing a rounding is now the same in-place re-stamp that adding one
 * is ([Document.removeDressingEntry]), so the two are one mechanism and there is one thing to be right.
 *
 * The audit behind these tests: **every** consumer of a solid element in `Document` takes `el.ref` — the
 * view — and therefore reads the body as it stands (`spaceSectionNodeOf`, `intrinsicSectionNode`,
 * `createFaceSpace`, `cutOnFace`, `extrudeOnFace`, `projectOntoFace`, `sectionSolid`, `combineSolids`,
 * `shellSolid`, `cutOpenings`, `cutByChain`, `splitByChain`, `placeSolid`, `measureSolidVolume`,
 * `measureSolidExtent`, and the blend itself). The two functions that *do* resolve a view to a concrete node
 * — `builtRef` and `pieceRef` — are the trimmable-curve pair (GitHub #25) and are reached only for a
 * `SEGMENT` or an `ARC`; `isTrimmed` is now bounded to those two kinds for the same reason, so a re-stamped
 * body cannot answer a question that is about a trim. The export seam, the 3D scene and the hit test hold no
 * node at all: they evaluate the element on every pass.
 *
 * Two mutations are available to *every* consumer and both are asserted here: a rounding **removed**, and a
 * rounding's size **retyped**. Adding one is asserted where it is reachable — a consumer that is itself a
 * *solid* built on the body (a placement, a union, a cut) makes that body no longer the drawing's tip, and a
 * further rounding then chains onto the tip rather than joining the dressing, which is OP-17's own rule and
 * is asserted as such.
 */
class DressedBodyConsumerTest {
    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun entries(doc: Document) = doc.elements.filter { it.kind == ElementKind.DRESSING }

    private fun solids(doc: Document) = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    private fun invalidity(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    /**
     * A 60 x 40 plate 20 deep whose **whole top face** is rounded and whose upright at (0, 0) is too — two
     * entries of one dressing sharing the one radius `r`, which is the body every consumer below is built on.
     * The picks are made in the 3D view because that is where a face and an upright are reachable.
     */
    private fun dressedPlate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        val vp = view(ed, Camera3(target = Vec3(30.0, 20.0, 10.0), distance = 250.0, yaw = -1.0, pitch = 1.1))
        val s = assertNotNull(vp.camera.project(Vec3(30.0, 20.0, 20.0), vp.widthPx, vp.heightPx), "the top face has an image")
        vp.pointerDown(s)
        vp.pointerUp(s)
        assertEquals(1, entries(ed.doc).size, "the cap's rounding: ${ed.statusHint}")
        roundUpright(ed, Vec3(0.0, 0.0, 10.0))
        assertEquals(2, entries(ed.doc).size, "the upright's rounding: ${ed.statusHint}")
        return ed
    }

    /** The upright through [mid], picked in the 3D view from the first camera that reaches it. */
    private fun roundUpright(
        ed: Editor,
        mid: Vec3,
    ) {
        val before = entries(ed.doc).size
        ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
        for (yaw in listOf(-2.3, -2.0, -2.7, 2.3, 3.5, -1.6, 4.0, 0.8, 1.6)) {
            ed.activeScalar = ed.doc.scalars.first { it.name == "r" }
            ed.setTool(Tools.BLEND_EDGE)
            val vp = view(ed, Camera3(target = mid, distance = 150.0, yaw = yaw, pitch = 0.25))
            val s = vp.camera.project(mid, vp.widthPx, vp.heightPx) ?: continue
            vp.pointerDown(s)
            vp.pointerUp(s)
            if (entries(ed.doc).size == before + 1) return
        }
        throw AssertionError("no camera reached the upright at $mid: ${ed.statusHint}")
    }

    private fun body(ed: Editor): Element = solids(ed.doc).first { ed.doc.dressingOf(it) != null }

    private fun removeLastEntry(ed: Editor) {
        val n = entries(ed.doc).size
        ed.clearSelection()
        ed.selectElement(entries(ed.doc).last())
        assertTrue(ed.deleteSelection(), "the last rounding comes off: ${ed.statusHint}")
        assertEquals(n - 1, entries(ed.doc).size, "one rounding fewer: ${ed.statusHint}")
    }

    /**
     * The rule, over a consumer's own exact number: it **moves** when a rounding is taken off the body it is
     * built on, and it **moves** when that body's remaining rounding is retyped — and comes back when the
     * number does.
     */
    private fun follows(
        ed: Editor,
        what: String,
        value: () -> Double,
    ) {
        val both = value()
        removeLastEntry(ed)
        val one = value()
        assertTrue(abs(one - both) > 1e-6 * abs(both) + 1e-9, "$what follows a rounding being removed: $one vs $both")
        val r = ed.doc.scalars.first { it.name == "r" }
        ed.doc.setParameter(r, 6.0.mm)
        val bigger = value()
        assertTrue(abs(bigger - one) > 1e-6 * abs(one) + 1e-9, "$what follows the size being retyped: $bigger vs $one")
        ed.doc.setParameter(r, 4.0.mm)
        assertTrue(abs(value() - one) <= 1e-6 * abs(one) + 1e-9, "…and comes back with it: ${value()} vs $one")
    }

    // ---- 1. a section of the body (an AREA of the drawing) ----

    @Test
    fun aSectionOfTheBodyFollows() {
        val ed = dressedPlate()
        ed.activeScalar = ed.doc.newParameter("z", 18.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(30.0, 0.0))
        val area = ed.doc.elements.last { it.kind == ElementKind.AREA }
        assertNull(invalidity(area), "the section is an area: ${ed.statusHint}")

        @Suppress("UNCHECKED_CAST")
        fun areaNow(): Double {
            assertNull(invalidity(area), "the section stays valid")
            return Evaluator().scalar(ed.doc.cx.regionArea(area.ref as RegionRef)).base
        }
        follows(ed, "the section's area") { areaNow() }
        assertTrue(ed.doc.elements.any { it === area }, "and it is the same element throughout")
    }

    // ---- 2. a sketch space on one of its faces ----

    @Test
    fun aFaceSpaceOnTheBodyFollows() {
        val ed = dressedPlate()
        // the x = 0 face: the cap's rounding trims its top and the upright's trims its left end, so both
        // mutations move the boundary this space draws — a face space's *plane* deliberately does not move
        // (a rounding trims a face, it does not shift it), which is what a stored `piece=` depends on
        val space = assertNotNull(ed.doc.createFaceSpace(body(ed), 3), "a sketch space on the x = 0 face: ${ed.doc.note}")
        assertEquals(3, space.piece, "and it records the footprint piece, not a face index")

        fun outlineArea(): Double {
            val pts = assertNotNull(ed.doc.faceOutline(space, Evaluator()), "the face draws its boundary")
            return abs(pts.indices.sumOf { pts[it].cross(pts[(it + 1) % pts.size]) }) / 2.0
        }
        follows(ed, "the face's own outline") { outlineArea() }
        assertEquals(space, ed.doc.spaceNamed(space.name), "the space is the same space throughout")
    }

    // ---- 3. a cut into one of its faces ----

    @Test
    fun aCutOnAFaceOfTheBodyFollows() {
        val ed = dressedPlate()
        val part = body(ed)
        // the gesture, not the API: *Sketch on face* opens the top face as a space, a circle is drawn on it
        // and *Cut* drills the part — the route a user takes, and the one that keeps the editor's own camera
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "the y = 0 face opened as a space: ${ed.statusHint}")
        assertEquals(part, ed.activeSpace.anchor, "…of the dressed body")
        // the face's own (u, v): u along the picked edge about its midpoint, v up into the face
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(0.0, 9.0))
        ed.setTool(Tools.CUT)
        ed.type("8")
        ed.click(Vec2(5.0, 9.0))
        val cut = solids(ed.doc).last()
        assertTrue(cut !== part, "the cut built a body: ${ed.statusHint}")
        assertNull(invalidity(cut), "the cut body builds")
        follows(ed, "the cut body's volume") { volumeOf(cut) }
    }

    // ---- 4. an operand of an ordinary boolean ----

    @Test
    fun aBooleanOperandFollows() {
        val ed = dressedPlate()
        // a pad overlapping the plate's right end, fused with it
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(50.0, 5.0))
        ed.click(Vec2(90.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(70.0, 5.0))
        val pad = solids(ed.doc).last()
        val fused = assertNotNull(ed.doc.combineSolids(body(ed), pad, BoolOp.UNION), "the union: ${ed.doc.note}")
        assertNull(invalidity(fused), "the union builds")
        follows(ed, "the fused body's volume") { volumeOf(fused) }
    }

    // ---- 5. a placement of it ----

    @Test
    fun aPlacedCopyFollows() {
        val ed = dressedPlate()
        val at = ed.doc.freePoint(200.0.mm, 0.0.mm)
        val placed = assertNotNull(ed.doc.placeSolid(body(ed), at), "the placement: ${ed.doc.note}")
        assertNull(invalidity(placed), "the placed body builds")
        follows(ed, "the placed copy's volume") { volumeOf(placed) }
    }

    // ---- 6. a measurement of it ----

    @Test
    fun aVolumeMeasurementFollowsIncludingARoundingAdded() {
        val ed = dressedPlate()
        val vol = assertNotNull(ed.doc.measureSolidVolume(body(ed)), "a volume measurement")

        fun now(): Double = Evaluator().scalar(vol.ref).base
        // a measurement builds no solid, so the body is still the drawing's tip and a third rounding
        // **joins** it — the one mutation a downstream body cannot have
        val two = now()
        roundUpright(ed, Vec3(60.0, 0.0, 10.0))
        assertEquals(3, entries(ed.doc).size, "a third rounding: ${ed.statusHint}")
        val three = now()
        assertTrue(three < two - 1e-9, "the measurement follows a rounding being added: $three vs $two")
        follows(ed, "the volume measurement") { now() }
    }

    // ---- 7. the export seam, which the 3D view reads the same way ----

    @Test
    fun theExportSeamFollows() {
        val ed = dressedPlate()

        fun exported(): Double {
            val scene = constructit.exchange.ExportScene.extract(ed.doc, "dressed")
            assertEquals(1, scene.nodes.size, "one body to export — an entry has no triangles of its own")
            return scene.triangleCount.toDouble()
        }
        follows(ed, "the exported triangle count") { exported() }
    }

    // ---- 8. what is **not** a consumer of a dressed body, audited and stated ----

    @Test
    fun aShellOfADressedBodyIsRefusedByName() {
        val ed = dressedPlate()
        assertNull(
            ed.doc.shellSolid(body(ed), ed.doc.newParameter("wall", 2.0.mm).ref, open = true, at = Vec2(30.0, 20.0)),
            "a shell of a rounded body is refused, so it is no consumer of one",
        )
        assertTrue("shell the body first and round it afterwards" in assertNotNull(ed.doc.note), "…by name: ${ed.doc.note}")
    }

    // ---- 9. and the file, across every mutation ----

    @Test
    fun theFileIsAFixedPointAcrossEveryMutation() {
        val ed = dressedPlate()
        ed.activeScalar = ed.doc.newParameter("z", 18.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(30.0, 0.0))

        fun holds(what: String) {
            val text = DocumentFormat.save(ed.doc)
            assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "$what — a fixed point:\n$text")
            val replayed = DocumentFormat.load(text)
            assertClose(
                volumeOf(body(ed)),
                volumeOf(replayed.elements.first { replayed.dressingOf(it) != null }),
                abs(volumeOf(body(ed))) * 1e-9,
                "$what — the in-place body is the replayed body",
            )
        }
        holds("two roundings")
        removeLastEntry(ed)
        holds("one taken off in place")
        roundUpright(ed, Vec3(60.0, 0.0, 10.0))
        holds("another put on in place")
        assertTrue(ed.undo(), "undo the addition")
        assertEquals(1, entries(ed.doc).size, "…and it is gone")
        assertTrue(ed.undo(), "undo the removal")
        assertEquals(2, entries(ed.doc).size, "…and the removed one is back")
    }
}
