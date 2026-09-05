package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Custom blend profiles as a gesture** (GitHub #30, session 80) — two picks, no number typed, and
 * everything the click decided written into the step's `signs=` and never scored again.
 *
 * The two rows are the four built-in ones at one more granularity: *Blend edge with a profile* shapes the
 * edge the click landed nearest, *Blend the edges of a face with a profile* shapes the whole boundary chain
 * of the face under it. What differs is that the section is a **drawing** rather than a number, which is why
 * the step names two elements — the body and the profile — and why `signs=` carries **five** integers per
 * edge instead of four: the fifth is which end of the profile is the setback on which face.
 *
 * The plate is drawn away from the origin so that the profile, which must be drawn **about** it (its two
 * coordinates are the two setbacks), never overlaps the body's own footprint.
 */
class CustomBlendProfileToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        val mesh: Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh
        assertManifold(mesh, "a shaped body")
        return Geom3.volume(mesh)
    }

    /** A 40 x 30 plate 20 deep, standing at (20, 20) so the profile drawn about the origin is clear of it. */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(60.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 20.0))
        assertEquals(1, ed.solids().size, "the plate: ${ed.statusHint}")
        return ed
    }

    /** The asymmetric bevel `(3, 0) → (0, 6)`, drawn as a chain about the origin. */
    private fun Editor.drawProfile(
        a: Vec2 = Vec2(3.0, 0.0),
        b: Vec2 = Vec2(0.0, 6.0),
    ): Element {
        setTool(Tools.CHAIN)
        click(a)
        click(b)
        key("Enter")
        return doc.elements.last { it.kind == ElementKind.CHAIN }
    }

    // ---- one pick, one edge ----

    @Test
    fun twoClicksShapeOneEdgeWithTheDrawingsOwnTwoSetbacks() {
        val ed = plate()
        val before = volumeOf(ed.solids().single())
        ed.drawProfile()
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        assertEquals(2, ed.solids().size, "the shaped body is a solid of its own: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue("boundary edge #1 of the top face" in said, "it names the edge it shaped: $said")
        assertTrue("the profile is an ordinary drawing, so reshaping it re-cuts the body" in said, said)
        assertClose(before - volumeOf(ed.solids().last()), 0.5 * 3.0 * 6.0 * 40.0, 1e-6, "360 mm^3 — the two setbacks the drawing states")
    }

    /** …and the whole rim of the face the click lands on, in one gesture, with its corners mitred. */
    @Test
    fun aFaceGestureShapesTheWholeRim() {
        val ed = plate()
        val before = volumeOf(ed.solids().single())
        ed.drawProfile()
        ed.setTool(Tools.PROFILE_FACE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        assertEquals(2, ed.solids().size, "the shaped body: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue("the top face" in said && "(4 edges)" in said, "it names the face and how many edges it took: $said")
        val took = before - volumeOf(ed.solids().last())
        val exact = 9.0 * 140.0 - 4.0 * 18.0
        assertClose(took, exact, abs(exact) * 1e-5, "1188 mm^3 — four bands and four mitres")
    }

    // ---- the file ----

    @Test
    fun theStepNamesTheProfileAndRestatesFiveSignsPerEdgeAndRoundTrips() {
        val ed = plate()
        val profile = ed.drawProfile()
        ed.setTool(Tools.PROFILE_FACE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        assertEquals(2, ed.solids().size, "the shaped body: ${ed.statusHint}")

        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool ${Tools.PROFILE_FACE}") }
        val els = step.substringAfter("els=").substringBefore(" ").trim().split(",")
        assertEquals(2, els.size, "the step names the body and the profile: $step")
        val chainStep = text.lines().single { it.startsWith("tool ${Tools.CHAIN}") }
        assertEquals(chainStep.substringAfter("-> ").trim(), els[1], "…the profile second, in slot order: $step")
        val signs = step.substringAfter("signs=").substringBefore(" ").trim().split(";")
        assertEquals(1 + 5 * 4, signs.size, "one address and **five** choices per edge: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")

        val back = DocumentFormat.load(text)
        val body = back.elements.last { it.kind == ElementKind.SOLID }
        assertClose(volumeOf(body), volumeOf(ed.solids().last()), 1e-9, "and the reloaded body is the same body")

        ed.undo()
        assertEquals(1, ed.solids().size, "one press takes back the whole gesture")
        ed.redo()
        assertEquals(2, ed.solids().size, "and one press puts it back")
    }

    /** **No version bump**: two ids that could never be written before become writable, and nothing else. */
    @Test
    fun theFileStaysAtTheVersionItWas() {
        val ed = plate()
        ed.drawProfile()
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        val text = DocumentFormat.save(ed.doc)
        assertEquals("constructit ${DocumentFormat.VERSION}", text.lines().first(), "the header is unchanged")
    }

    // ---- the profile is an ordinary drawing ----

    @Test
    fun reshapingTheProfileReCutsTheBodyAndDeletingItCascades() {
        val ed = plate()
        ed.drawProfile()
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        val body = ed.solids().last()
        val before = volumeOf(ed.solids().first())
        assertClose(before - volumeOf(body), 360.0, 1e-6, "360 mm^3")

        // drag the profile's own end from (3, 0) to (5, 0): the body re-cuts, and nothing is rebuilt
        ed.setTool(Tools.SELECT)
        ed.pointerMove(ed.camera.worldToScreen(Vec2(3.0, 0.0)))
        ed.pointerDown(ed.camera.worldToScreen(Vec2(3.0, 0.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(5.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(5.0, 0.0)))
        assertClose(before - volumeOf(body), 0.5 * 5.0 * 6.0 * 40.0, 1e-6, "600 mm^3 — the drawing *is* the section")

        // …and deleting the profile takes the body it shaped with it, as any operand does
        ed.selectAt(Vec2(1.5, 3.0))
        assertEquals(ElementKind.CHAIN, ed.selection?.kind, "the profile is selected")
        assertTrue(ed.deleteSelection())
        assertEquals(1, ed.solids().size, "the shaped body cascades with its profile: ${ed.statusHint}")
    }

    // ---- what is refused, in the gesture's own words ----

    @Test
    fun aClosedCurveIsRefusedByNameRatherThanIgnored() {
        val ed = plate()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(4.0, 0.0))
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(4.0, 0.0))
        assertEquals(1, ed.solids().size, "nothing is built from a closed profile")
        val said = assertNotNull(ed.statusHint)
        assertTrue("is closed, and a rounding's profile has two ends" in said, said)
        assertTrue("Break it, or draw an open chain" in said, said)
    }

    /** A profile whose ends miss the two faces heals the moment it is drawn where it belongs. */
    @Test
    fun aProfileOffTheAxesSaysSoAndHeals() {
        val ed = plate()
        ed.drawProfile(Vec2(3.0, 1.5), Vec2(0.0, 6.0))
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.75))
        assertEquals(2, ed.solids().size, "the element is there — it is its value that is refused")
        val why = assertNotNull((Evaluator().eval(ed.solids().last().ref.node) as? EvalResult.Invalid)?.reason)
        assertTrue("do not state a setback on each face" in why, why)
        assertTrue("Move them onto the axes" in why, why)

        // move the stray end onto the axis and the body builds
        ed.setTool(Tools.SELECT)
        ed.pointerMove(ed.camera.worldToScreen(Vec2(3.0, 1.5)))
        ed.pointerDown(ed.camera.worldToScreen(Vec2(3.0, 1.5)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(3.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(3.0, 0.0)))
        assertTrue(Evaluator().eval(ed.solids().last().ref.node) is EvalResult.Ok, "it heals when the drawing is put right")
    }

    /** Both rows are in the palette, each with a glyph of its own. */
    @Test
    fun bothRowsAreInThePaletteWithTheirOwnGlyphs() {
        val edge = assertNotNull(Tools.all.firstOrNull { it.id == Tools.PROFILE_EDGE }, "Blend edge with a profile")
        val face = assertNotNull(Tools.all.firstOrNull { it.id == Tools.PROFILE_FACE }, "Blend the edges of a face with a profile")
        assertNotNull(edge.icon, "the edge row has a glyph")
        assertNotNull(face.icon, "the face row has a glyph")
        assertTrue(edge.scalars.isEmpty() && face.scalars.isEmpty(), "no number is typed: the drawing states the size")
    }
}
