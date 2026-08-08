package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for session 53 (OP-26): compositions the package's own tests never tried.
 *
 * - **A rider is a full citizen upstream, under session 49's reading of a lifted start**: a second coil takes
 *   the rider as its start point, and what that states is the rider's **bearing and radius across the axis**
 *   — the coil begins *level with its centre* (`Helix3.origin`'s documented meaning), so the first coil's
 *   pitch moves the rider without moving the second coil, while its **radius** moves both. The second coil's
 *   own key points carry the edit two hops from the parameter that caused it.
 * - ***Make absolute* is one undo step, across the document rebuild**: undo replays the last checkpoint into
 *   a fresh `Document`, so every handle is refetched — and the rider must come back *on its winding* (450°,
 *   not 90°), redo must free it again in place, and the rebuilt rider must still drag like one.
 */
class HelixRiderProbeTest {
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

    private fun coil(
        ed: Editor,
        at: Vec2,
    ): Element {
        ed.setTool(Tools.POINT)
        ed.click(at)
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(at)
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
    }

    private fun at(el: Element): Vec3? = (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p }

    private fun assertVec(
        actual: Vec3?,
        expected: Vec3,
        msg: String,
    ) {
        val a = assertNotNull(actual, "$msg — the point has no value")
        assertTrue((a - expected).length() <= 1e-9, "$msg (was $a, wanted $expected)")
    }

    private fun rider(
        ed: Editor,
        where: Vec2,
    ): Element {
        ed.setTool(Tools.POINT_ON_HELIX)
        ed.click(where)
        return assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, ed.statusHint)
    }

    private fun setAngle(
        rider: Element,
        deg: Double,
    ) {
        val field = assertNotNull(rider.handle?.fields()?.firstOrNull { it.label == "angle" }, "the rider has an angle field")
        field.write(deg.deg)
    }

    private fun angleOf(
        ed: Editor,
        rider: Element,
    ): Double = assertNotNull(ed.doc.restatedRiderParam(rider, Evaluator()), "the rider has a parameter").deg

    /**
     * **What a lifted start point states is its bearing and radius, and an edit crosses both hops.** A rider
     * at 450° stands 15 mm above the plan; a second coil started at it begins **level with its own centre**
     * at the rider's bearing — session 49's documented reading, which no earlier test asserted against a
     * genuinely lifted input. So coil₁'s *pitch* moves the rider without moving coil₂, while coil₁'s
     * *radius* moves the rider's bearing distance and coil₂'s key points follow, two hops from the parameter.
     */
    @Test
    fun aLiftedStartStatesItsBearingAndEditsCrossBothHops() {
        val ed = Editor()
        coil(ed, Vec2(0.0, 0.0))
        val r = rider(ed, Vec2(20.0, 0.0))
        setAngle(r, 450.0)
        assertVec(at(r), Vec3(0.0, 20.0, 15.0), "the rider at 450°: one and a quarter turns round, 1.25 pitches up")

        // a second coil about a far-away axis, its start point the rider — through the ordinary gesture,
        // whose second click lands on the rider's plan projection and must *share* its node, not copy it
        ed.setTool(Tools.HELIX_PT)
        ed.type("6")
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        val coil2 = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue(coil2.ref.node.inputs.any { it === r.ref.node }, "the rider's node is shared: ${ed.statusHint}")

        // its key points, extracted by the ordinary gesture — clicked at the far side of its projection,
        // where nothing of coil₁ is drawn
        val reach = 100.0 + sqrt(100.0 * 100.0 + 20.0 * 20.0)
        val before = ed.doc.elements.toSet()
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(reach, 0.0))
        val pts2 = ed.doc.elements.filter { it !in before }
        assertEquals(3, pts2.size, "centre, start, end of the second coil: ${ed.statusHint}")
        assertVec(at(pts2[0]), Vec3(100.0, 0.0, 0.0), "coil₂'s centre is its own axis point")
        assertVec(at(pts2[1]), Vec3(0.0, 20.0, 0.0), "coil₂ begins at the rider's bearing, level with its centre")
        assertVec(at(pts2[2]), Vec3(0.0, 20.0, 6.0), "and one turn of its own pitch ends directly above")

        // coil₁'s pitch lifts the rider straight up — which a start point read across the axis does not see
        val pitch = ed.doc.scalars.first { it.name.startsWith("pitch") }
        ed.doc.setParameter(pitch, 24.0.mm)
        assertVec(at(r), Vec3(0.0, 20.0, 30.0), "the rider kept its angle and climbed with the pitch")
        assertVec(at(pts2[1]), Vec3(0.0, 20.0, 0.0), "a lift says nothing across the axis (session 49)")

        // coil₁'s radius moves the rider's bearing distance — which it does see, two hops away
        val radius = ed.doc.scalars.first { it.name.startsWith("radius") }
        ed.doc.setParameter(radius, 30.0.mm)
        assertVec(at(r), Vec3(0.0, 30.0, 30.0), "the rider stands on the widened coil")
        assertVec(at(pts2[1]), Vec3(0.0, 30.0, 0.0), "coil₂'s start key point followed the rider's bearing")
        assertVec(at(pts2[2]), Vec3(0.0, 30.0, 6.0), "and coil₂'s end followed its start")
        assertVec(at(pts2[0]), Vec3(100.0, 0.0, 0.0), "while coil₂'s centre stayed on its own axis")

        // the whole chain is a file
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
    }

    /**
     * ***Make absolute* is one undo step, and undo puts the rider back on its winding.** Undo replays the
     * last checkpoint into a fresh `Document`, so the probe refetches every handle — then redo frees the
     * point again in place, undo once more, and the rebuilt rider still drags like one in the plan,
     * keeping the winding a plan gesture cannot name.
     */
    @Test
    fun undoOfMakeAbsoluteRestoresTheRiderOnItsWinding() {
        val ed = Editor()
        coil(ed, Vec2(0.0, 0.0))
        val r = rider(ed, Vec2(20.0, 0.0))
        setAngle(r, 450.0)
        ed.checkpoint()
        val was = assertNotNull(at(r))

        assertTrue(ed.doc.makeAbsolute(r), "Make absolute frees it: ${ed.doc.note}")
        ed.checkpoint()
        assertEquals(ElementKind.HEIGHT_POINT, r.kind, "freed into the pair of freedoms a point in space has")

        assertTrue(ed.undo(), "one undo takes the freeing back")
        // the document was rebuilt: every earlier handle is stale, so ask the new one
        val back = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "the rider rides again")
        assertNotNull(ed.doc.riderOf(back), "as a rider, not a lookalike")
        assertClose(angleOf(ed, back), 450.0, 1e-9, "on the winding it was freed from — 450°, not 90°")
        assertVec(at(back), was, "standing where it stood")

        // redo frees it again, in place — the other direction across the same rebuild
        assertTrue(ed.redo(), "redo the freeing")
        val freed = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.HEIGHT_POINT }, "freed again")
        assertVec(at(freed), was, "in the very place")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the freed point is a file")

        // back to the rider once more, and the rebuilt rider still drags like one: round the plan,
        // keeping its winding (which clears the redo history, so it is the probe's last act)
        assertTrue(ed.undo(), "undo the freeing again")
        val again = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, "riding again")
        ed.setTool(Tools.SELECT)
        val grab = ed.camera.worldToScreen(Vec2(0.0, 20.0))
        ed.pointerMove(grab)
        ed.pointerDown(grab)
        val to = ed.camera.worldToScreen(Vec2(-20.0, 0.0))
        ed.pointerMove(to)
        ed.pointerUp(to)
        assertClose(angleOf(ed, again), 540.0, 1.0, "the plan drag moved it round its own winding, not back to the first")
    }
}
