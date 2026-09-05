package constructit

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the custom blend profile (GitHub #30)**, composed with what it was not written
 * against: the tangent run round a rounded ortho corner (a circular rim among the edges), the built-in chamfer as
 * the body it must reproduce vertex for vertex, the undo stack, the file, and a plain segment as the profile.
 */
class CustomBlendProfileProbeTest {
    private val roundedPath =
        """
constructit 3
orthostart -73.625,28.875 -> e1
orthovertex -73.625,84.125 -> e2,e3
orthovertex 67.125,84.125 -> e4,e5
orthovertex 67.125,56.125 -> e6,e7
orthovertex -37.375,56.125 -> e8,e9
orthovertex -37.375,28.875 -> e10,e11
orthoclose -> e12
param "r" = 5mm
tool fillet els=e3,e5 clicks=-73.625,71.125;-64.875,84.125 scalar="r" signs=-1;1 -> e13
tool fillet els=e9,e11 clicks=-28.875,56.125;-37.625,46.625 scalar="r" signs=-1;1 -> e14
param "h" = 18mm
tool extrude els=e12 clicks=-57.375,28.625 scalar="h" -> e15
""".trimStart()

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun loaded(): Editor = Editor().also { it.replaceDocument(DocumentFormat.load(roundedPath)) }

    /** The rim over leg e3 of the rounded path — the start of a three-edge tangent run (leg, corner arc, leg). */
    private val onRim = Vec2(-73.625, 50.0)

    private fun Editor.drawChain(
        a: Vec2,
        b: Vec2,
    ): Element {
        setTool(Tools.CHAIN)
        click(a)
        click(b)
        key("Enter")
        return doc.elements.last { it.kind == ElementKind.CHAIN }
    }

    /** A one-segment profile `(3,0)→(0,3)` on the run is the built-in chamfer of 3 on that run, vertex for vertex. */
    @Test
    fun aOneSegmentProfileOnTheRoundedRunIsTheChamferOfThatRun() {
        val custom = loaded()
        custom.drawChain(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        custom.setTool(Tools.PROFILE_EDGE)
        custom.click(onRim)
        custom.click(Vec2(1.5, 1.5))
        assertEquals(2, custom.solids().size, "the profile blend: ${custom.statusHint}")
        assertTrue("(3 edges)" in custom.statusHint, "leg, the corner's arc, leg: ${custom.statusHint}")
        val a = meshOf(custom.solids().last())
        assertManifold(a, "a profile round a rounded ortho corner")

        val builtIn = loaded()
        builtIn.activeScalar = builtIn.doc.newParameter("c", 3.0.mm)
        builtIn.setTool(Tools.CHAMFER_EDGE)
        builtIn.click(onRim)
        assertEquals(2, builtIn.solids().size, "the chamfer: ${builtIn.statusHint}")
        assertTrue("(3 edges)" in builtIn.statusHint, builtIn.statusHint)
        val b = meshOf(builtIn.solids().last())
        assertManifold(b, "the chamfer round the same run")

        assertEquals(b.vertices.toSet(), a.vertices.toSet(), "the same vertices")
        assertEquals(b.triangles.size, a.triangles.size, "the same triangle count")
        assertClose(Geom3.volume(a), Geom3.volume(b), tol = 1e-9, msg = "the same body")
        assertTrue(Geom3.volume(a) < Geom3.volume(meshOf(custom.solids().first())), "material came off")
    }

    /** Undo takes the blend, redo brings the same body back, and the file rebuilds it bit for bit. */
    @Test
    fun theProfileBlendUndoesRedoesAndRoundTrips() {
        val ed = loaded()
        ed.drawChain(Vec2(3.0, 0.0), Vec2(0.0, 3.0))
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(onRim)
        ed.click(Vec2(1.5, 1.5))
        assertEquals(2, ed.solids().size, ed.statusHint)
        val v = Geom3.volume(meshOf(ed.solids().last()))
        assertTrue(ed.undo(), "undo the blend")
        assertEquals(1, ed.solids().size, "gone")
        assertTrue(ed.redo(), "redo it")
        assertEquals(2, ed.solids().size, "back")
        assertClose(Geom3.volume(meshOf(ed.solids().last())), v, tol = 1e-9, msg = "the same body after redo")
        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.lines().any { it.trim().startsWith("tool blendedge") }, "the step is recorded:\n$saved")
        val again = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(again), "byte-equal round trip")
        val back = again.elements.last { it.kind == ElementKind.SOLID }
        assertEquals(meshOf(ed.solids().last()).vertices, meshOf(back).vertices, "the reloaded body, vertex for vertex")
    }

    /** A plain segment drawn with the Segment tool is a profile too: the asymmetric chamfer's 360 mm³. */
    @Test
    fun aPlainSegmentIsAProfile() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(60.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(40.0, 20.0))
        val before = Geom3.volume(meshOf(ed.solids().single()))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(3.0, 0.0))
        ed.click(Vec2(0.0, 6.0))
        ed.setTool(Tools.PROFILE_EDGE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(1.5, 3.0))
        assertEquals(2, ed.solids().size, "a segment is an open profile: ${ed.statusHint}")
        val m = meshOf(ed.solids().last())
        assertManifold(m, "segment profile")
        assertClose(before - Geom3.volume(m), 0.5 * 3.0 * 6.0 * 40.0, tol = 1e-6, msg = "360 mm³")
    }
}
