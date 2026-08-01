package constructit

import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.RenderMesh
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Normals, shared by the GLB writer and the preview** — smooth where the surface is smooth, sharp where it
 * is not.
 *
 * Neither naive answer is acceptable and both are tempting, which is why this has a test of its own: averaging
 * every incident face rounds a box's corners, and one normal per facet turns a bore into a barrel of visible
 * strips. The threshold that separates the two cases is not a new number — it is `Scene3.CREASE_ANGLE_RAD`,
 * the same 30° the 3D view already draws feature edges at, so a crease the editing view draws a line along is
 * a crease the preview and the exported GLB shade as one.
 */
class RenderMeshTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun meshOf(ed: Editor) =
        ExportScene.extract(ed.doc).nodes.last { it.name == ed.doc.nameOf(ed.doc.elements.last { e -> e.kind == ElementKind.SOLID }) }.mesh

    private fun box(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("h", Quantity.mm(10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        return ed
    }

    private fun normalsOf(m: RenderMesh): List<Vec3> =
        (0 until m.vertexCount).map { Vec3(m.normals[it * 3], m.normals[it * 3 + 1], m.normals[it * 3 + 2]) }

    /** A box stays a box: six axis-aligned normals, four vertices each, and not one of them averaged. */
    @Test
    fun aBoxKeepsItsSixFlatFaces() {
        val m = RenderMesh.of(meshOf(box()))
        assertEquals(m.indices.size, 3 * (2 * 2 * 3), "12 triangles: two per face")
        val normals = normalsOf(m)
        for (n in normals) {
            assertClose(n.length(), 1.0, 1e-12, "every normal is a unit vector")
            val axes = listOf(abs(n.x), abs(n.y), abs(n.z))
            assertClose(axes.max(), 1.0, 1e-12, "a box's normal points down one axis — nothing was averaged: $n")
        }
        val unique = normals.map { Triple(kotlin.math.round(it.x), kotlin.math.round(it.y), kotlin.math.round(it.z)) }.toSet()
        assertEquals(6, unique.size, "six distinct face directions: $unique")
        assertEquals(24, m.vertexCount, "eight corners, three faces each — the split the sharp edges force")
    }

    /**
     * A **cylinder** is the opposite case: the side facets differ by the tessellation step (far under the
     * threshold), so their shared vertices average into one radial normal per position — the surface reads as
     * round — while the cap's vertices keep ±Z, because a cap meets the side at 90°.
     */
    @Test
    fun aCylindersSideIsSmoothAndItsCapIsNot() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        for (c in "20") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("h", Quantity.mm(30.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        val mesh = meshOf(ed)
        assertManifold(mesh, "the cylinder")
        val m = RenderMesh.of(mesh)
        val normals = normalsOf(m)

        val caps = normals.count { abs(abs(it.z) - 1.0) < 1e-9 }
        val side = normals.filter { abs(it.z) < 1e-9 }
        assertTrue(caps > 0 && side.isNotEmpty(), "both kinds are there: $caps caps, ${side.size} side")
        assertEquals(normals.size, caps + side.size, "and nothing in between — a cap meets the side at 90°")
        for (n in side) {
            assertClose(kotlin.math.sqrt(n.x * n.x + n.y * n.y), 1.0, 1e-9, "a side normal is radial: $n")
        }
        // the side's normals are *averaged*: no two adjacent facets share one, so there are as many distinct
        // radial directions as there are positions round the circle
        val distinct = side.map { Pair(kotlin.math.round(it.x * 1e6), kotlin.math.round(it.y * 1e6)) }.toSet()
        assertTrue(distinct.size > 16, "one smooth normal per position round the bore, not per facet: ${distinct.size}")
    }

    /** Deterministic: the same mesh gives byte-identical arrays, which is what the GLB golden rests on. */
    @Test
    fun theArraysAreAFunctionOfTheMesh() {
        val mesh = meshOf(box())
        val a = RenderMesh.of(mesh)
        val b = RenderMesh.of(mesh)
        assertTrue(a.positions.contentEquals(b.positions))
        assertTrue(a.normals.contentEquals(b.normals))
        assertTrue(a.indices.contentEquals(b.indices))
    }

    /** The bounds are what a glTF POSITION accessor must state: the component-wise min and max. */
    @Test
    fun theBoundsAreTheComponentWiseExtremes() {
        val (lo, hi) = RenderMesh.of(meshOf(box())).bounds()
        assertEquals(listOf(0.0, 0.0, 0.0), lo.toList())
        assertEquals(listOf(40.0, 20.0, 10.0), hi.toList())
    }
}
