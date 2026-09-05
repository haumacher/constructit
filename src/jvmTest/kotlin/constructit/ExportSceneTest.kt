package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.LengthUnit
import constructit.geom.Axis3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **The neutral scene seam** — the one thing every export and the in-app preview reads.
 *
 * What is asserted here is the *contract*, because four consumers depend on it and none of them may re-decide
 * any part of it: which bodies are in (visible, valid, not another body's material), what they are called
 * (the naming authority's script name — OP-18), that the meshes are the kernel's **own objects** rather than
 * copies (OP-9's sink, OP-5's memo — which is also what makes the preview's incremental upload possible), and
 * that the unit and up-axis are stated in the model instead of left to each reader's folklore.
 */
class ExportSceneTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    /** The acceptance pyramid: a 100 × 100 plan square with its apex 90 mm over the centre. */
    private fun pyramid(ed: Editor = Editor()): Editor {
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        return ed
    }

    /**
     * The whole contract in one drawing: **a pyramid, a frustum, a hidden solid and an invalid one**.
     *
     * The frustum rides a datum plane, so the drawing also proves a body sketched on something other than the
     * plan exports like any other; the hidden solid is a recorded decision (OP-18's visibility reversal) and
     * the invalid one is a height retyped to zero (OP-3). Both are *named* in the notes, because a body
     * silently missing from a file is exactly the surprise an export must not spring — and the notes are
     * otherwise empty, which is the rule that makes them worth reading: **silence means success.**
     */
    @Test
    fun theSceneHoldsTheVisibleValidSolidsNamedByTheAuthority() {
        val ed = pyramid()
        val pyr = ed.solids().single()

        // a plain prism beside it, then hidden
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("hidden_h", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        // the click picks the *footprint* — a leg of the closed path, not the area inside it
        ed.click(Vec2(175.0, 0.0))
        val hidden = ed.solids().last()
        ed.selectElement(hidden)
        ed.setSelectionVisible(false)

        // ...and one whose height is zero, so its node is invalid and it contributes nothing (OP-3)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(250.0, 0.0))
        ed.click(Vec2(300.0, 50.0))
        val badHeight = ed.doc.newParameter("bad_h", 30.0.mm)
        ed.activeScalar = badHeight
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(275.0, 0.0))
        val invalid = ed.solids().last()
        ed.setParameter(badHeight, 0.0)

        val scene = ExportScene.extract(ed.doc, "acceptance")
        assertEquals(1, scene.nodes.size, "one exportable body: ${scene.nodes.map { it.name }} / ${scene.notes}")
        assertEquals(ed.doc.nameOf(pyr), scene.nodes.single().name, "named by the authority (OP-18), not by an id of its own")
        assertSame(ed.meshOf(pyr), scene.nodes.single().mesh, "the kernel's own mesh object, not a copy (OP-5/OP-9)")
        assertEquals(LengthUnit.MILLIMETRE, scene.unit, "the unit is stated in the model")
        assertEquals(Axis3.Z, scene.up, "...and so is which way is up")
        assertNull(scene.refusal, "there is something to export")

        val notes = scene.notes
        assertEquals(2, notes.size, "exactly the two skips are spoken: $notes")
        assertTrue(notes.any { it.contains(ed.doc.nameOf(hidden)) && it.contains("hidden") }, "the hidden body, by name: $notes")
        assertTrue(notes.any { it.contains(ed.doc.nameOf(invalid)) && it.contains("invalid") }, "the invalid body, by name: $notes")
    }

    /** The frustum across two sketch planes exports as one body, named and with the kernel's mesh. */
    @Test
    fun aBodySketchedOnADatumPlaneExportsLikeAnyOther() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(80.0, 80.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")

        val solid = ed.solids().single()
        val scene = ExportScene.extract(ed.doc, "frustum")
        assertEquals(listOf(ed.doc.nameOf(solid)), scene.nodes.map { it.name })
        assertSame(ed.meshOf(solid), scene.nodes.single().mesh)
        assertTrue(scene.notes.isEmpty(), "nothing to say: ${scene.notes}")
        assertManifold(scene.nodes.single().mesh, "the exported frustum")
    }

    /**
     * **Composition exports as the part, not as the operand stack.** A drilled pyramid is a `Cut` over the raw
     * extrusion; only the tip of that chain is an output, exactly as in the 3D view (`Document.isMaterial`) —
     * and the operands are *not* noted, because they were never bodies of their own.
     */
    @Test
    fun aCutChainExportsAsOneBodyTheTip() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            constructit.geom.MeshBool.available,
            "the drill is a cross-axis boolean (OP-9): ${constructit.geom.MeshBool.status}",
        )
        val ed = pyramid()
        // the drill is sketched on one of the pyramid's lateral faces, which is where Cut works (OP-8/OP-17):
        // in that face's own coordinates the apex is the origin and the base edge lies at the slant height
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 0.0))
        val slant = kotlin.math.sqrt(50.0 * 50.0 + 90.0 * 90.0)
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(0.0, slant * 0.6))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(6.0, slant * 0.6))
        assertEquals(3, ed.solids().size, "the raw pyramid, the drill and the cut result all exist as elements")

        val tip = ed.solids().last()
        val scene = ExportScene.extract(ed.doc, "drilled")
        assertEquals(listOf(ed.doc.nameOf(tip)), scene.nodes.map { it.name }, "the part tip only: ${scene.notes}")
        assertTrue(scene.notes.isEmpty(), "an operand is material, not a skipped body: ${scene.notes}")
        assertManifold(scene.nodes.single().mesh, "the drilled pyramid, as exported")
    }

    /** A wall with an opening cut into it: one watertight body out of the architectural route (OP-21). */
    @Test
    fun aWallWithAnOpeningExportsWatertight() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 100.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 50.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("w", 15.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(20.0, 50.0))
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(15.0, 50.0))

        val scene = ExportScene.extract(ed.doc, "wall")
        assertEquals(1, scene.nodes.size, "the cut wall, not the raw extrusion beside it: ${scene.nodes.map { it.name }}")
        assertManifold(scene.nodes.single().mesh, "the wall with its opening")
        assertNull(constructit.exchange.ThreeMf.check(scene), "and the printing check agrees")
    }

    /** Nothing solid yet: the refusal says so, and there are no bytes to be had. */
    @Test
    fun anEmptyDrawingRefusesWithAReason() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        val scene = ExportScene.extract(ed.doc, "empty")
        assertTrue(scene.isEmpty)
        assertTrue(
            scene.refusal!!.contains("no solid"),
            "the refusal says what is missing: ${scene.refusal}",
        )
    }

    /** ...and when every body is hidden or invalid, the refusal **names them** rather than shrugging. */
    @Test
    fun aRefusalNamesWhatItSkipped() {
        val ed = pyramid()
        val pyr = ed.solids().single()
        ed.selectElement(pyr)
        ed.setSelectionVisible(false)
        val scene = ExportScene.extract(ed.doc, "all-hidden")
        assertTrue(
            scene.refusal!!.contains(ed.doc.nameOf(pyr)) && scene.refusal!!.contains("hidden"),
            "the refusal names the body it skipped: ${scene.refusal}",
        )
    }

    /** The scene survives a save/load round trip unchanged — it is a function of the recorded construction. */
    @Test
    fun theSceneIsAFunctionOfTheRecordedConstruction() {
        val ed = pyramid()
        val before = ExportScene.extract(ed.doc, "pyramid")
        val back = DocumentFormat.load(DocumentFormat.save(ed.doc))
        val after = ExportScene.extract(back, "pyramid")
        assertEquals(before.nodes.map { it.name }, after.nodes.map { it.name })
        assertEquals(before.nodes.single().mesh.vertices, after.nodes.single().mesh.vertices)
        assertEquals(before.nodes.single().mesh.triangles, after.nodes.single().mesh.triangles)
    }
}
