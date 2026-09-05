package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Geom3
import constructit.geom.MeshCanon
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the two corner limits** on what the delivery never saw: two free-ended fillets of
 * two radii on opposite rim edges sectioned exactly (the very case an earlier probe had to avoid), and three
 * fills at the **far** corner of a room of other proportions, their ball checked vertex by vertex.
 */
class CornersProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun twoFreeEndedFilletsOfTwoRadiiOnOppositeRimsSectionExactly() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(30.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r2", 3.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(30.0, 40.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, ed.statusHint)
        ed.activeScalar = ed.doc.newParameter("z", 18.0.mm)
        ed.setTool(Tools.SECTION)
        ed.click(Vec2(30.0, 0.0))
        val area = ed.doc.elements.last { it.kind == ElementKind.AREA }
        assertNull((Evaluator().eval(area.ref.node) as? EvalResult.Invalid)?.why?.render("en"), "the level section closes")
        @Suppress("UNCHECKED_CAST")
        val areaNode = ed.doc.cx.regionArea(area.ref as RegionRef)
        assertNull((Evaluator().eval(areaNode.node) as? EvalResult.Invalid)?.why?.render("en"), "the section's area is a number — kind ${area.kind}, ref ${area.ref}")
        val a = Evaluator().scalar(areaNode).base
        val exact = 60.0 * (40.0 - (4.0 - sqrt(12.0)) - (3.0 - sqrt(8.0)))
        assertTrue(abs(a - exact) < 1e-6, "two insets of two radii: $a vs $exact")
        // and one band lower, where only the larger radius reaches
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "z" }, 16.5.mm)
        assertNull((Evaluator().eval(area.ref.node) as? EvalResult.Invalid)?.why?.render("en"), "the lower level section closes")
        val a2 = Evaluator().scalar(ed.doc.cx.regionArea(area.ref as RegionRef)).base
        val exact2 = 60.0 * (40.0 - (4.0 - sqrt(16.0 - 0.25)))
        assertTrue(abs(a2 - exact2) < 1e-6, "below the 3 mm band's foot only the 4 mm inset remains: $a2 vs $exact2")
    }

    private var ids = 0

    private fun room(
        cx: Construction,
        w: Double,
        d: Double,
        h: Double,
        wall: Double,
    ): SolidRef {
        val xy = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, d), Vec2(0.0, d))
        val pts = xy.map { cx.freePoint("q${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % 4]) }
        val box = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
        val faces = assertNotNull(Section3.faces(Evaluator().solid(box).feature).first)
        val top = faces.indices.first { i -> faces[i].plane?.let { abs(it.normal.normalized().z - 1.0) < 1e-9 && abs(it.origin.z - h) < 1e-9 } == true }
        return cx.shell(box, cx.const(wall.mm), listOf(top))
    }

    private fun edgeAt(
        ref: SolidRef,
        a: Vec3,
        b: Vec3,
    ): Int {
        val edges = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first)
        return assertNotNull(
            edges.indices.firstOrNull { i ->
                if (edges[i].reason != null) return@firstOrNull false
                val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                listOf(a, b).all { q -> listOf(el.start, el.end).any { (it - q).length() < 1e-6 } }
            },
            "an edge $a – $b",
        )
    }

    private fun fill(
        cx: Construction,
        on: SolidRef,
        r: Double,
        address: Int,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(BlendKind.FILLET, r))
        assertNotNull(choices, why?.render())
        return cx.blend(on, on, cx.planeXY(), cx.const(r.mm), BlendKind.FILLET, false, address, choices)
    }

    @Test
    fun threeFillsAtTheFarCornerOfAnotherRoomAreOneBallReadFromTheAir() {
        val w = 70.0
        val d = 50.0
        val h = 35.0
        val wall = 5.0
        val r = 6.0
        val corner = Vec3(w - wall, d - wall, wall)
        val cx = Construction()
        val roomRef = room(cx, w, d, h, wall)
        val before = Geom3.volume(Evaluator().solid(roomRef).mesh)
        var body = roomRef
        for (far in listOf(Vec3(wall, d - wall, wall), Vec3(w - wall, wall, wall), Vec3(w - wall, d - wall, h))) {
            body = fill(cx, body, r, edgeAt(body, corner, far))
        }
        val res = Evaluator().eval(body.node)
        assertTrue(res !is EvalResult.Invalid, "three fills build: ${(res as? EvalResult.Invalid)?.why?.render("en")}")
        val mesh = Evaluator().solid(body).mesh
        assertManifold(mesh, "the room with a rounded far corner")
        assertNull(MeshCanon.fault(mesh))
        val after = Geom3.volume(mesh)
        val wedge = r * r * (1 - Math.PI / 4)
        val runs = (w - 2 * wall) + (d - 2 * wall) + (h - wall)
        assertTrue(after > before && after < before + wedge * runs, "the fills add less than three full runs: ${after - before} vs ${wedge * runs}")
        // the corner's own surface is the ball centred r into the air along all three normals
        val centre = corner + Vec3(-r, -r, r)
        val onBall =
            mesh.vertices.filter { v ->
                v.x > corner.x - r - 1e-6 && v.y > corner.y - r - 1e-6 && v.z < corner.z + r + 1e-6 &&
                    abs(v.x - corner.x) > 1e-6 && abs(v.y - corner.y) > 1e-6 && abs(v.z - corner.z) > 1e-6 &&
                    // strictly inside the corner cell, off the three walls: the bands' and the ball's own vertices
                    (v - centre).length() < r + 0.5
            }
        assertTrue(onBall.size >= 10, "the corner cell carries a patch: ${onBall.size}")
        for (v in onBall) assertTrue(abs((v - centre).length() - r) < 1e-4, "on the ball: $v is ${(v - centre).length()} from $centre")
    }
}
