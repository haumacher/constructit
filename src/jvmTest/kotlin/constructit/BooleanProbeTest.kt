package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes chaining prismatic booleans (OP-22) through compositions their implementation never saw:
 * a bolt hole through a union, a door-cut wall under a stacked storey, and a carrier drag driving a
 * whole boolean chain. Prismatic solids are closed under same-axis booleans, so a boolean's result
 * must be as good an operand as any plain extrusion.
 */
class BooleanProbeTest {
    private fun sq(
        cx: Construction,
        tag: String,
        cxy: Vec2,
        half: Double,
    ) = cx.region(
        cx.loop(
            cx.segment(
                cx.freePoint("a$tag", (cxy.x - half).mm, (cxy.y - half).mm),
                cx.freePoint("b$tag", (cxy.x + half).mm, (cxy.y - half).mm),
            ),
            cx.segment(
                cx.freePoint("b2$tag", (cxy.x + half).mm, (cxy.y - half).mm),
                cx.freePoint("c$tag", (cxy.x + half).mm, (cxy.y + half).mm),
            ),
            cx.segment(
                cx.freePoint("c2$tag", (cxy.x + half).mm, (cxy.y + half).mm),
                cx.freePoint("d$tag", (cxy.x - half).mm, (cxy.y + half).mm),
            ),
            cx.segment(
                cx.freePoint("d2$tag", (cxy.x - half).mm, (cxy.y + half).mm),
                cx.freePoint("a2$tag", (cxy.x - half).mm, (cxy.y - half).mm),
            ),
        ),
    )

    /** (plate ∪ boss) − bolt: a boolean result is an ordinary operand for the next boolean. */
    @Test
    fun aBoltHoleThroughAUnionCutsBothParts() {
        val cx = Construction()
        val ev = { Evaluator() }
        val plate = cx.extrude(cx.sketchOn(cx.planeXY(), sq(cx, "p", Vec2(0.0, 0.0), 50.0)), cx.parameter("dp", 20.mm))
        val boss = cx.extrude(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.parameter("z", 20.mm)), sq(cx, "b", Vec2(0.0, 0.0), 20.0)), cx.parameter("db", 15.mm))
        val stack = cx.union(plate, boss)

        val hole = cx.circleCR(cx.freePoint("hc", 0.mm, 0.mm), cx.parameter("rBolt", 6.mm))
        val bolt = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(hole))), cx.parameter("dh", 35.mm))
        val done = cx.subtract(stack, bolt)

        val mesh = ev().solid(done).mesh
        assertManifold(mesh, "union minus bolt")
        val expected = 100.0 * 100.0 * 20.0 + 40.0 * 40.0 * 15.0 - PI * 36.0 * 35.0
        assertTrue(abs(Geom3.volume(mesh) - expected) / expected < 1e-3, "volume ${Geom3.volume(mesh)} vs $expected")
    }

    /** A door-cut ground floor under a plain upper storey — the house fragment, manifold and exact. */
    @Test
    fun aDoorCutWallUnionsWithTheStoreyAbove() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 200.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("h", 2600.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("w", 40.0.mm)
        ed.setTool(Tools.OPENING)
        ed.click(Vec2(20.0, 100.0)) // a door: sill 0, head 2100 by default
        ed.setTool(Tools.CUT_OPENINGS)
        ed.click(Vec2(15.0, 100.0))

        val cut = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val cutMesh = Evaluator().solid(cut.ref as SolidRef).mesh
        assertManifold(cutMesh, "door-cut wall")
        assertClose(Geom3.volume(cutMesh), 10.0 * 200.0 * 2600.0 - 40.0 * 10.0 * 2100.0, tol = 1e-3, msg = "full wall minus the door box")

        // the storey above, engine-side (the extrude TOOL is planeXY-only for now)
        val cx = ed.doc.cx
        val upper = cx.extrude(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.parameter("z1", 2600.mm)), sq(cx, "u", Vec2(20.0, 100.0), 40.0)), cx.parameter("h2", 2600.mm))

        @Suppress("UNCHECKED_CAST")
        val whole = cx.union(cut.ref as SolidRef, upper)
        val mesh = Evaluator().solid(whole).mesh
        assertManifold(mesh, "two storeys, one with a door")
        assertClose(
            Geom3.volume(mesh),
            (10.0 * 200.0 * 2600.0 - 40.0 * 10.0 * 2100.0) + 80.0 * 80.0 * 2600.0,
            tol = 1e-3,
            msg = "the union keeps both volumes",
        )
    }

    /** Dragging the carrier drives the whole chain: extrude → cut → still manifold, volume tracks. */
    @Test
    fun draggingTheCarrierDrivesTheCutWall() {
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

        @Suppress("UNCHECKED_CAST")
        fun cutMesh() = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh
        // door head 2100 exceeds the 50-high wall: the box cuts clean through vertically
        assertClose(Geom3.volume(cutMesh()), 10.0 * 100.0 * 50.0 - 15.0 * 10.0 * 50.0, tol = 1e-3)

        // stretch the wall from y=100 to y=160: the chain recomputes end to end
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(20.0, 100.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(20.0, 160.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(20.0, 160.0)))

        assertManifold(cutMesh(), "cut wall after carrier drag")
        assertClose(Geom3.volume(cutMesh()), 10.0 * 160.0 * 50.0 - 15.0 * 10.0 * 50.0, tol = 1e-3, msg = "the cut survives the stretch")
        assertEquals(
            savedScript(ed),
            savedScript(loadOf(ed)),
            "the whole chain replays",
        )
    }

    private fun savedScript(ed: Editor) = constructit.editor.DocumentFormat.save(ed.doc)

    private fun loadOf(ed: Editor): Editor {
        val e2 = Editor()
        e2.replaceDocument(constructit.editor.DocumentFormat.load(savedScript(ed)))
        return e2
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }
}
