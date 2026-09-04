package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The **function-family section**'s shared fixtures (OP-26, session 79 — queue entry 2, the wing's route).
 *
 * Every one of them is built through **recorded gestures**, never by reaching into the graph: the acceptance
 * this feature is judged on is as much about the file and the panel as about the mesh, so the same drawing
 * has to serve the volume assertions, the refusal wording and the byte-equal round trip.
 *
 * The one convention worth stating: a section is drawn from **two kinds of parameter** — the free named ones
 * a law may drive (`w`, `h`, `chord`) and *bound* ones that merely place its corners (`nought = 0 * w`,
 * `thickness = 0.12 * chord`). That split is the design pass's F3 made visible: what a family may drive is
 * exactly the free half, and the bound half follows by ordinary recompute at every station.
 */
object SectionFamilyFixture {
    /** Where a point-from-coordinates gesture is told "now" — empty plan, far from every fixture. */
    private val NOWHERE = Vec2(-900.0, 900.0)

    fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    fun meshOf(el: Element): Mesh3 {
        @Suppress("UNCHECKED_CAST")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    /** Why [el] has no value, or null when it has one — what a refusal fixture reads. */
    fun invalidity(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** A point of the plan at ([x], [y]) as an ordinary derived point — *Point (x, y)* over two parameters. */
    fun pointAt(
        ed: Editor,
        x: ScalarEntry,
        y: ScalarEntry,
    ): Element {
        ed.setTool(Tools.POINT_XY)
        ed.activeScalar = x
        ed.activeScalar = y
        ed.click(NOWHERE)
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.DERIVED_POINT },
            "the point (${x.name}, ${y.name}) was placed: ${ed.statusHint}",
        )
    }

    /** A **free point** of the plan at [at] — a corner that no parameter places, and a freedom the family reads. */
    fun freePointAt(
        ed: Editor,
        at: Vec2,
    ): Element {
        ed.setTool(Tools.POINT)
        ed.click(at)
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.POINT },
            "the free corner was placed: ${ed.statusHint}",
        )
    }

    /** A parameter bound to [formula] — a *derived* value, which no family law may drive (F3). */
    fun bound(
        ed: Editor,
        name: String,
        formula: String,
    ): ScalarEntry {
        val e = ed.doc.newParameter(name, 0.0.mm)
        assertTrue(ed.bindParameter(e, formula), "$name = $formula: ${ed.statusHint}")
        return ed.doc.scalars.first { it.name == name }
    }

    /**
     * A rectangle section with its lower-left corner **on the plan's origin**, `[wName]` wide and
     * `[hName]` high — the stand-in every exact-volume fixture is stated on.
     *
     * The origin corner is the point the run rides where no anchor is picked, so a rigid `scale(t)` about it
     * and a family driving `w` and `h` by the same factor describe the *identical* body — which is exactly
     * what the rigid tier's reproduction asserts (acceptance 1).
     */
    class Rect(
        val ed: Editor,
        wmm: Double,
        hmm: Double,
        wName: String = "w",
        hName: String = "h",
    ) {
        val w: ScalarEntry = ed.doc.newParameter(wName, wmm.mm)
        val h: ScalarEntry = ed.doc.newParameter(hName, hmm.mm)

        /** Zero, as a **bound** value: a corner needs one and a free one would offer a law nobody wants. */
        val nought: ScalarEntry = bound(ed, "nought", "0 * $wName")

        val corners: List<Element>
        val area: Element

        init {
            // the origin corner is a **free point** — a freedom of the drawing the family reads, which is
            // what the weld and memo fixtures are stated on
            val p0 = freePointAt(ed, Vec2(0.0, 0.0))
            val p1 = pointAt(ed, w, nought)
            val p2 = pointAt(ed, w, h)
            val p3 = pointAt(ed, nought, h)
            corners = listOf(p0, p1, p2, p3)
            val at = listOf(Vec2(0.0, 0.0), Vec2(wmm, 0.0), Vec2(wmm, hmm), Vec2(0.0, hmm))
            ed.setTool(Tools.SEGMENT)
            for (i in 0 until 4) {
                ed.click(at[i])
                ed.click(at[(i + 1) % 4])
            }
            // two adjacent pieces are all a boundary follow needs (OP-14), and the fourth click would
            // start a **second** outline over the very same ring
            ed.setTool(Tools.OUTLINE)
            ed.click(Vec2((at[0].x + at[1].x) / 2, (at[0].y + at[1].y) / 2))
            ed.click(Vec2((at[1].x + at[2].x) / 2, (at[1].y + at[2].y) / 2))
            area =
                assertNotNull(
                    ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE },
                    "the rectangle closed: ${ed.statusHint}",
                )
        }

        /** Where a click picks this section for an area slot — the middle of its top edge. */
        fun pick(): Vec2 = Vec2(pointOf(corners[2]).x / 2, pointOf(corners[2]).y)
    }

    /**
     * The **wing**: a rectangular stand-in aerofoil whose `chord` is free and whose `thickness` is bound to
     * `0.12 * chord`, with a **quarter-chord** point to ride the run on (`qc.x = 0.25 * chord`).
     *
     * The fixture the design pass named, and the reason it is the one: no rigid factor turns a 200 mm chord
     * with a 12% thickness into an 80 mm chord with a 12% thickness *of that*, and no compensation puts the
     * pivot line a quarter of the way back at every station. Both fall out of re-reading one drawing.
     */
    class Wing(val ed: Editor, chordMm: Double = 200.0) {
        val chord: ScalarEntry = ed.doc.newParameter("chord", chordMm.mm)
        val thickness: ScalarEntry = bound(ed, "thickness", "0.12 * chord")
        val nought: ScalarEntry = bound(ed, "nought", "0 * chord")
        val quarter: ScalarEntry = bound(ed, "quarter", "0.25 * chord")

        val corners: List<Element>
        val qc: Element
        val area: Element

        init {
            val t = chordMm * 0.12
            val p0 = freePointAt(ed, Vec2(0.0, 0.0))
            val p1 = pointAt(ed, chord, nought)
            val p2 = pointAt(ed, chord, thickness)
            val p3 = pointAt(ed, nought, thickness)
            corners = listOf(p0, p1, p2, p3)
            qc = pointAt(ed, quarter, nought)
            val at = listOf(Vec2(0.0, 0.0), Vec2(chordMm, 0.0), Vec2(chordMm, t), Vec2(0.0, t))
            ed.setTool(Tools.SEGMENT)
            for (i in 0 until 4) {
                ed.click(at[i])
                ed.click(at[(i + 1) % 4])
            }
            // two adjacent pieces are all a boundary follow needs (OP-14), and the fourth click would
            // start a **second** outline over the very same ring
            ed.setTool(Tools.OUTLINE)
            ed.click(Vec2((at[0].x + at[1].x) / 2, (at[0].y + at[1].y) / 2))
            ed.click(Vec2((at[1].x + at[2].x) / 2, (at[1].y + at[2].y) / 2))
            area =
                assertNotNull(
                    ed.doc.elements.lastOrNull { it.kind == ElementKind.OUTLINE },
                    "the aerofoil closed: ${ed.statusHint}",
                )
        }
    }

    /** Where [el] stands in its own plane. */
    fun pointOf(el: Element): Vec2 = (Evaluator().eval(el.ref.node) as EvalResult.Ok).value.let { (it as constructit.core.PointValue).p }

    /**
     * A straight run of [length] mm in the plan at `y = [y]`, drawn as a curve in space — the route every
     * fixture sweeps along, placed off the sections so no click is ambiguous.
     */
    fun straightRun(
        ed: Editor,
        length: Double,
        y: Double = -300.0,
        from: Double = 0.0,
    ): Element {
        val a = Vec2(from, y)
        val b = Vec2(from + length, y)
        ed.setTool(Tools.POINT)
        ed.click(a)
        ed.click(b)
        ed.setTool(Tools.CURVE3)
        ed.click(a)
        ed.click(b)
        ed.key("Enter")
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the run was drawn: ${ed.statusHint}",
        )
    }

    /** Where a click picks [run] — its middle, which no fixture's section reaches. */
    fun midOf(
        length: Double,
        y: Double = -300.0,
        from: Double = 0.0,
    ): Vec2 = Vec2(from + length / 2, y)
}
