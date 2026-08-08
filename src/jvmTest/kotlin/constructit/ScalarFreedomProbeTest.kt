package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Point3Value
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reviewer's probe for the scalar package (session 55): the three deliveries composed with each other
 * and with what the two sessions before them built.
 *
 * - **A click that commits digits can itself be an optional pick**: "type 90, click the anchor corner" must
 *   spend the 90 on the sweep's roll *and* the click on the anchor slot — the press carries two meanings in
 *   one gesture, and both must land.
 * - **A freedom is a route into OP-3's healing**: a rider at 450° on a one-turn coil is invalid by name, and
 *   *writing the turns freedom* — the very value nobody typed — is what heals it.
 * - **The freedom survives the file and the undo**, re-fetched across each rebuild.
 */
class ScalarFreedomProbeTest {
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

    private fun at(el: Element): Vec3? = (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.let { (it.value as? Point3Value)?.p }

    private fun ownField(
        ed: Editor,
        el: Element,
        label: String,
    ) = assertNotNull(
        (ed.doc.ownFields(el) + (el.handle?.fields() ?: emptyList())).firstOrNull { it.label == label },
        "$label is a field of ${ed.doc.nameOf(el)}",
    )

    /**
     * **"Type 90, click the corner, click the square" is a rolled, anchored sweep** — the typed digits commit
     * on the anchor's own click (session 55), the click still fills the optional slot (session 54), the roll
     * lands as the parameter the digits made, and the twist nobody typed is a freedom on the solid.
     */
    @Test
    fun typedDigitsCommitOnTheClickThatStatesTheAnchor() {
        val ed = Editor()
        ed.camera = Camera(-800.0, 500.0, 40.0)
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(32.0, 7.0))

        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        // the digits, committed by the very click that picks the anchor corner — no Enter anywhere
        ed.key("9")
        ed.key("0")
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(31.0, 5.0))
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, ed.statusHint)
        assertTrue(ed.statusHint.contains("riding on"), "the click was still the anchor's: ${ed.statusHint}")
        assertNull((Evaluator().eval(solid.ref.node) as? EvalResult.Invalid)?.reason, "and the rolled sweep is valid")
        assertManifold(Evaluator().solid(solid.ref as SolidRef).mesh, "the rolled, anchored sweep")

        val roll = assertNotNull(ed.doc.scalars.firstOrNull { it.name.startsWith("roll") }, "the 90 became the roll parameter")
        assertClose(
            assertNotNull(((Evaluator().eval(roll.ref.node) as? EvalResult.Ok)?.value as? constructit.core.ScalarValue)?.q).deg,
            90.0,
            1e-9,
            "at the value the digits said",
        )
        ownField(ed, solid, "twist")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is byte-equal")
    }

    /**
     * **Writing the turns freedom heals the rider that outran the coil** (OP-3 through session 55's freedom):
     * a one-turn coil (nobody typed a count) with a rider sent to 450° is invalid naming 360; writing 2 into
     * the coil's own "turns" field brings the point back exactly where it said it was. One undo takes the
     * write back — the freedom's edit is a step like any other — and the healed drawing is a file.
     */
    @Test
    fun writingTheTurnsFreedomHealsTheRiderPastTheEnd() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        // no turn count typed: the coil is one turn, and that value is a freedom of the step
        ed.click(Vec2(0.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        ed.setTool(Tools.POINT_ON_HELIX)
        ed.click(Vec2(20.0, 0.0))
        val rider = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.ON_CURVE }, ed.statusHint)
        val angle = assertNotNull(rider.handle?.fields()?.firstOrNull { it.label == "angle" })
        angle.write(450.0.deg)
        ed.checkpoint()
        val bad = Evaluator().eval(rider.ref.node)
        assertTrue(bad is EvalResult.Invalid, "450° is past a one-turn coil")
        assertTrue((bad as EvalResult.Invalid).reason.contains("450"), "named: ${bad.reason}")

        val turns = ownField(ed, coil, "turns")
        turns.write(constructit.units.Quantity.number(2.0))
        ed.checkpoint()
        assertNotNull(at(rider), "the rider healed")
        assertClose(assertNotNull(at(rider)).z, 15.0, 1e-9, "exactly where 450° stands: 1.25 pitches up")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the healed drawing round-trips")
        val back = DocumentFormat.load(once)
        val coil2 = back.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertClose(
            assertNotNull(assertNotNull((back.ownFields(coil2)).firstOrNull { it.label == "turns" }).read(Evaluator())).value,
            2.0,
            1e-9,
            "and the freedom came back at the written value",
        )

        assertTrue(ed.undo(), "one undo takes the write back")
        val coil3 = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val rider3 = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        assertClose(
            assertNotNull(assertNotNull(ed.doc.ownFields(coil3).firstOrNull { it.label == "turns" }).read(Evaluator())).value,
            1.0,
            1e-9,
            "to the default it stood at",
        )
        assertTrue(Evaluator().eval(rider3.ref.node) is EvalResult.Invalid, "and the rider is honestly off the end again")
    }
}
