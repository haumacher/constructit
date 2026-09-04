package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Probe review of the 3D edge pick (GitHub #24)** — the underside from below, the *face* rows through the
 * same ray, and the two canvases agreeing on one address after an undo.
 */
class EdgeBlend3DPickProbeTest {
    private val fixture =
        """
constructit 3
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "h2" = 4mm
""".trimStart()

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun armed(tool: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        ed.activeScalar = ed.doc.scalars.first { it.name == "h2" }
        ed.setTool(tool)
        return ed
    }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    private fun stepOf(
        ed: Editor,
        word: String,
    ): String? =
        DocumentFormat.save(ed.doc).lines().map { it.trim() }.firstOrNull { it.startsWith("tool $word") }

    private fun addressOf(step: String): Int = step.substringAfter("signs=").substringBefore(";").toInt()

    @Suppress("UNCHECKED_CAST")
    private fun feature(ed: Editor) = Evaluator().solid(ed.solids().first().ref as SolidRef).feature

    @Suppress("UNCHECKED_CAST")
    private fun assertSolidsManifold(ed: Editor) {
        for (el in ed.solids()) assertManifold(Evaluator().solid(el.ref as SolidRef).mesh, ed.doc.nameOf(el))
    }

    /** The z of every end of every edge the blend at [address] runs along. */
    private fun edgeHeights(
        ed: Editor,
        whole: Boolean,
        address: Int,
    ): Set<Double> {
        val f = feature(ed)
        val edges = assertNotNull(Section3.edges(f).first)
        val targets = assertNotNull(Blend3.targets(f, whole, address).first, "targets of #$address")
        return targets.flatMap { i ->
            val p = assertNotNull(Blend3.edgePath(edges[i]).first)
            listOf(assertNotNull(p.start).z, assertNotNull(p.end).z)
        }.toSet()
    }

    /** Looking up from under the plate, the rim you see is the bottom one — and that is the one broken. */
    @Test
    fun fromBelowTheBottomRimIsTheOneFilleted() {
        val ed = armed(Tools.BLEND_EDGE)
        view(ed, Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.5, pitch = -1.1)).clickWorld(Vec3(-16.2, -32.375, 0.0))
        val step = assertNotNull(stepOf(ed, "filletedge"), "a fillet from below: ${ed.statusHint}")
        assertEquals(setOf(0.0), edgeHeights(ed, false, addressOf(step)), "the bottom rim, both ends: $step / ${ed.statusHint}")
        assertSolidsManifold(ed)
    }

    /** The *face* rows take the ray too: the top cap from above, the bottom cap from below — six edges each. */
    @Test
    fun theFaceRowsPickTheCapTheRayMeets() {
        val above = armed(Tools.BLEND_FACE)
        view(above, Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.7, pitch = 1.0)).clickWorld(Vec3(-16.0, -20.0, 20.0))
        val top = assertNotNull(stepOf(above, "filletfaceedges"), "top cap from above: ${above.statusHint}")
        assertEquals(setOf(20.0), edgeHeights(above, true, addressOf(top)), "the top cap's whole boundary: $top / ${above.statusHint}")
        assertTrue("6 edges" in above.statusHint, "all six pieces: ${above.statusHint}")
        assertSolidsManifold(above)

        val below = armed(Tools.CHAMFER_FACE)
        view(below, Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = 0.7, pitch = -1.0)).clickWorld(Vec3(-16.0, -20.0, 0.0))
        val bottom = assertNotNull(stepOf(below, "chamferfaceedges"), "bottom cap from below: ${below.statusHint}")
        assertEquals(setOf(0.0), edgeHeights(below, true, addressOf(bottom)), "the bottom cap's whole boundary: $bottom / ${below.statusHint}")
        assertSolidsManifold(below)
    }

    /** A 3D pick, undone, then the same rim clicked in the plan: one address, one step text. */
    @Test
    fun theTwoCanvasesWriteTheSameStepForTheSameRim() {
        val ed = armed(Tools.BLEND_EDGE)
        view(ed, Camera3(target = Vec3(17.0, -8.0, 10.0), distance = 300.0, yaw = -0.9, pitch = 0.9)).clickWorld(Vec3(-16.2, -32.375, 20.0))
        val in3d = assertNotNull(stepOf(ed, "filletedge"), "from the 3D view: ${ed.statusHint}")
        assertTrue(ed.undo(), "undo the 3D fillet")
        assertEquals(null, stepOf(ed, "filletedge"), "gone")
        ed.pointing = null
        // undo rebuilt the document, so the scalar handle is stale: re-fetch it
        ed.activeScalar = ed.doc.scalars.first { it.name == "h2" }
        ed.setTool(Tools.BLEND_EDGE)
        val s = ed.camera.worldToScreen(Vec2(-16.2, -32.375))
        ed.pointerDown(s)
        ed.pointerUp(s)
        val flat = assertNotNull(stepOf(ed, "filletedge"), "from the plan: ${ed.statusHint}")
        assertEquals(addressOf(in3d), addressOf(flat), "one edge, two gestures: $in3d vs $flat")
        assertEquals(in3d.substringAfter("scalar="), flat.substringAfter("scalar="), "same choices after the click")
        assertSolidsManifold(ed)
    }
}
