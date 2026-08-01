package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe on the issue-11 fix, composing it with the architectural stack the delivery never touched: the
 * user's exact floor plan, its outline **thickened into a wall and raised into 3D**, then the placed group
 * dragged — the entire downstream cone (junctions, walls, solid) must ride the frame as one rigid thing,
 * and the story must replay byte-equal.
 */
class PlacedJunctionProbeTest {
    private val script =
        """
        constructit 2
        orthostart -44.654651610640634,-42.891501448519534 -> e1
        orthovertex -44.654651610640634,56.932564229106646 -> e2,e3
        orthovertex 10.006561466646858,56.932564229106646 -> e4,e5
        orthovertex 10.006561466646858,23.881406168687448 -> e6,e7
        orthovertex 88.55852142198333,23.881406168687448 -> e8,e9
        orthovertex 88.55852142198333,-42.891501448519534 -> e10,e11
        orthoclose -> e12
        orthostart 10.006561466646858,23.881406168687448 -> e13
        weldortho e13 e6
        orthovertex 10.006561466646858,-42.891501448519534 -> e14,e15
        attachortho e14 e12
        orthostart 10.006561466646858,-2.647242681581133 -> e16
        attachortho e16 e15
        orthovertex 54.4041523101855,-2.647242681581133 -> e17,e18
        orthovertex 54.4041523101855,-42.891501448519534 -> e19,e20
        attachortho e19 e12
        group "all" els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13,e14,e15,e16,e17,e18,e19,e20
        place "all" at=21.951934905671344,7.020531390293556 angle=0deg
        """.trimIndent() + "\n"

    @Test
    fun theWholeBuildingRidesTheFrame() {
        val ed = Editor(DocumentFormat.load(script))

        // the outline becomes a wall, the wall becomes a storey — downstream of the group, not in it
        ed.activeScalar = ed.doc.newParameter("d", 5.0.mm)
        ed.setTool(Tools.THICKEN)
        ed.justification = Justification.CENTER
        val s = ed.camera.worldToScreen(Vec2(0.0, -42.891501448519534))
        ed.pointerMove(s)
        ed.pointerDown(s)
        ed.pointerUp(s)
        ed.key("Enter")
        ed.setTool(Tools.EXTRUDE)
        for (c in "40") ed.key(c.toString())
        ed.key("Enter")
        val f = ed.camera.worldToScreen(Vec2(0.0, -40.391501448519534))
        ed.pointerMove(f)
        ed.pointerDown(f)
        ed.pointerUp(f)
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        fun mesh() = Evaluator().solid(part.ref as SolidRef).mesh
        assertManifold(mesh(), "the walls of the user's plan")
        val vol0 = Geom3.volume(mesh())
        val lo0 = Geom3.bounds(mesh())!!.first
        assertTrue(vol0 > 0.0, "the storey stands: ${ed.statusHint}")

        // every 2D value, before
        val evB = Evaluator()
        val before = ed.doc.elements.associate { ed.doc.nameOf(it) to evB.valueOf(it.ref) }

        // the frame drag: click a member (selects the placed group), drag by (50, 30)
        ed.setTool(Tools.SELECT)
        val g0 = ed.camera.worldToScreen(Vec2(-44.654651610640634, -42.891501448519534))
        ed.pointerDown(g0)
        ed.pointerUp(g0)
        val g1 = ed.camera.worldToScreen(Vec2(-44.654651610640634 + 50.0, -42.891501448519534 + 30.0))
        ed.pointerDown(g0)
        ed.pointerMove(g1)
        ed.pointerUp(g1)

        // the invariant the issue was about, over EVERY member value: one rigid translation
        val evA = Evaluator()
        for (el in ed.doc.elements) {
            val name = ed.doc.nameOf(el)
            val vb = before[name] ?: continue
            val va = evA.valueOf(el.ref)
            if (vb is PointValue && va is PointValue) {
                assertClose(va.p.x - vb.p.x, 50.0, tol = 1e-9, msg = "$name rides in x")
                assertClose(va.p.y - vb.p.y, 30.0, tol = 1e-9, msg = "$name rides in y")
            }
            if (vb is SegmentValue && va is SegmentValue) {
                assertClose(va.seg.a.x - vb.seg.a.x, 50.0, tol = 1e-9, msg = "$name.a rides in x")
                assertClose(va.seg.a.y - vb.seg.a.y, 30.0, tol = 1e-9, msg = "$name.a rides in y")
                assertClose(va.seg.b.x - vb.seg.b.x, 50.0, tol = 1e-9, msg = "$name.b rides in x")
                assertClose(va.seg.b.y - vb.seg.b.y, 30.0, tol = 1e-9, msg = "$name.b rides in y")
            }
        }

        // ...and the 3D building followed whole: same volume, shifted bounds
        assertManifold(mesh(), "the walls after the move")
        assertClose(Geom3.volume(mesh()), vol0, tol = 1e-6, msg = "a rigid move takes no material")
        val lo1 = Geom3.bounds(mesh())!!.first
        assertClose(lo1.x - lo0.x, 50.0, tol = 1e-9, msg = "the storey rode in x")
        assertClose(lo1.y - lo0.y, 30.0, tol = 1e-9, msg = "the storey rode in y")

        // the whole story — plan, walls, storey, move — replays byte-equal
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the moved building replays byte-equal")
    }
}
