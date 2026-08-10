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
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The edge blend as a gesture** (session 71, slice 2) — one `SOLID` pick and one length, and everything the
 * click decides written into the step's `signs=` and never scored again.
 *
 * The four tool rows are two operations at two granularities: *Fillet edge* / *Chamfer edge* break the edge
 * the click landed nearest, *Fillet the edges of a face* / *Chamfer the edges of a face* break the whole
 * boundary chain of the face the click is looking at — *"all of the curve parts"* in one click, which is the
 * user's own words for the motivating case (a profile revolved less than a full turn, whose two cap faces
 * stay sharp).
 *
 * What is asserted here is not that a mesh appeared but that the blend is an ordinary member of the graph and
 * of the file: watertight, parametric through a shared radius (and through an *expression* over one), riding
 * the generic `tool` step with a byte-equal round trip, one undo per gesture, and a stored choice that a
 * reload takes verbatim even where re-scoring it would now choose otherwise.
 */
class EdgeBlendToolTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    private fun volumeOf(el: Element): Double {
        val mesh = meshOf(el)
        assertManifold(mesh, "a blended body")
        return Geom3.volume(mesh)
    }

    // ---- the fixtures ----

    /** A 40 x 30 plate 20 deep, drawn and extruded by gestures — the plainest body with a cap edge. */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.solids().size, "the plate: ${ed.statusHint}")
        return ed
    }

    /**
     * **The motivating case, verbatim**: a 10 x 60 bar beside the X axis, revolved a quarter turn — *"the two
     * faces of an outline swept for less than 360° are always sharp"*.
     */
    private fun quarterTube(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(60.0, 25.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(30.0, 15.0))
        ed.click(Vec2(10.0, 0.0))
        assertEquals(1, ed.solids().size, "the quarter tube: ${ed.statusHint}")
        return ed
    }

    // ---- one pick, one edge ----

    @Test
    fun onePickBreaksOneEdgeAndSaysWhichOne() {
        val ed = plate()
        val before = volumeOf(ed.solids().single())
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        assertEquals(2, ed.solids().size, "a blend is a solid of its own: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("boundary edge #1 of the top face"), "and it names the edge it broke: $said")
        val after = volumeOf(ed.solids().last())
        val exact = (1.0 - PI / 4.0) * 16.0 * 40.0
        assertTrue(after < before, "a convex edge loses material")
        assertClose(before - after, exact, exact * 0.03, "the quarter-round of radius 4 along 40 mm")
    }

    /**
     * **Looking down at a plate, its top and bottom rims draw one line** — and the pick takes the one you can
     * see. Stated here because it is a decision rather than an accident (see `Document.edgeNear`).
     */
    @Test
    fun aFlatViewPicksTheEdgeNearestTheEye() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("r", 3.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(40.0, 15.0))
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("of the top face"), "the top rim, not the bottom one: $said")
    }

    @Test
    fun theChamferRowBevelsTheSameEdge() {
        val ed = plate()
        val before = volumeOf(ed.solids().single())
        ed.activeScalar = ed.doc.newParameter("c", 4.0.mm)
        ed.setTool(Tools.CHAMFER_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val after = volumeOf(ed.solids().last())
        assertClose(before - after, 4.0 * 4.0 * 40.0 / 2.0, 0.01, "a 45 degree bevel of setback 4 along 40 mm")
    }

    // ---- one pick, a whole boundary chain ----

    @Test
    fun oneClickBreaksEveryPieceOfACapEdge() {
        val ed = quarterTube()
        val before = volumeOf(ed.solids().single())
        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 25.0))
        assertEquals(2, ed.solids().size, "one gesture, one body: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("the cap at the start of the sweep"), "it names the face: $said")
        assertTrue(said.contains("(4 edges)"), "and how many pieces it broke: $said")
        val after = volumeOf(ed.solids().last())
        assertTrue(after < before, "four convex edges lose material: $after vs $before")

        // …and the same profile with only **one** piece broken loses strictly less
        val one = quarterTube()
        one.activeScalar = one.doc.newParameter("r", 2.0.mm)
        one.setTool(Tools.BLEND_EDGE)
        one.click(Vec2(30.0, 25.0))
        val single = volumeOf(one.solids().last())
        assertTrue(single > after, "one piece of the chain takes less than all four: $single vs $after")
        val edgeSaid = assertNotNull(one.statusHint)
        assertTrue(edgeSaid.contains("profile edge #"), "and the single pick names one profile edge: $edgeSaid")
    }

    /**
     * **A tangent-continuous chain blends with no crack at the tangency corners, and no double count either.**
     *
     * The rim of an extruded **rounded rectangle** is eight pieces — four straight runs and four arcs — that
     * hand over to each other smoothly, and one click breaks all of them. Watertightness alone would not
     * notice a gap or an overlap at the eight joins, so the **volume** is asserted against the closed-form
     * figure: each straight run takes the wedge's area times its length, and each corner takes Pappus' —
     * `A·2π·(R − ū)` over the four quarters together, with `A = (1 − π/4)r²` the wedge's area and
     * `ū = r(5/6 − π/4)/(1 − π/4)` its centroid in from the rim. A crack would leave material behind and an
     * overlap would take it twice; neither reading passes this.
     *
     * The band is 5%, and both halves of it are named: the chord model on the straights
     * (`π·r·t/3` per mm) and, on the corners, the fact that the body's own cylindrical wall reaches the
     * engine as a chord polygon so the blend is tangent to the polygon rather than to the cylinder.
     */
    @Test
    fun aTangentContinuousRimBlendsAsOneRibbon() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("corner", 10.0.mm)
        ed.setTool(Tools.ROUNDED_RECT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(30.0, 0.0))
        val before = volumeOf(ed.solids().single())

        ed.activeScalar = ed.doc.newParameter("r", 3.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 0.0))
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("the top face"), "the rim of the top face: $said")
        assertTrue(said.contains("(8 edges)"), "all eight pieces of it: $said")
        val after = volumeOf(ed.solids().last())

        val r = 3.0
        val area = (1.0 - PI / 4.0) * r * r
        val inset = r * (5.0 / 6.0 - PI / 4.0) / (1.0 - PI / 4.0)
        val straights = 2.0 * (60.0 - 20.0) + 2.0 * (40.0 - 20.0)
        val exact = area * straights + area * 2.0 * PI * (10.0 - inset)
        assertClose(before - after, exact, exact * 0.05, "the whole rim's quarter-round, joins included")
    }

    // ---- the forked feature chain: a blend after an ordinary boolean ----
    //
    // The recorded probe classic, one feature over (*"sequential cuts must target the part's tip"*). A blend
    // asks **two** questions of the body the click reached and they have two different answers: *whose edges
    // am I naming* walks backwards down the part's spine to the analytic body that still names them, and
    // *what do I apply to* walks forwards to the drawing's tip of that body's chain
    // (`Document.tipOfChain`, the very authority a face-space cut uses). Get the second wrong and the drawing
    // holds a blended-but-unfused body beside a fused-but-unblended one, each claiming to be the part.

    /** The plate with a pad fused onto its right end, clear of the rims along `y = 0` and `y = 30`. */
    private fun platePlusPad(): Editor {
        val ed = plate()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, 5.0))
        ed.click(Vec2(60.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, 5.0))
        assertEquals(2, ed.solids().size, "the pad stands alone first: ${ed.statusHint}")
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        assertEquals(3, ed.solids().size, "the union is one new body: ${ed.statusHint}")
        // plate 24000 + pad 25 x 20 x 20 − the 5 x 20 x 20 they share
        assertClose(volumeOf(ed.solids().last()), 24000.0 + 10000.0 - 2000.0, 1.0, "the fused part")
        return ed
    }

    @Test
    fun aChamferAfterAUnionCutsThePartsTipRatherThanForking() {
        val ed = platePlusPad()
        val union = ed.solids().last()
        val fused = volumeOf(union)
        ed.activeScalar = ed.doc.newParameter("c", 5.0.mm)
        ed.setTool(Tools.CHAMFER_EDGE)
        ed.click(Vec2(15.0, 0.0))
        assertEquals(4, ed.solids().size, "one more body, not a second reading of the part: ${ed.statusHint}")
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("${ed.doc.nameOf(union)} with a chamfer"), "it cut the fused part: $said")
        assertTrue(said.contains("of the top face"), "along the plate's own rim: $said")
        val after = volumeOf(ed.solids().last())
        // the whole 40 mm rim is still there to bevel (the pad stops short of y = 0), so it is exact
        assertClose(fused - after, 5.0 * 5.0 / 2.0 * 40.0, 1.0, "a 45 degree bevel of setback 5 along 40 mm")
        assertTrue(after > 30000.0, "and the pad is still on it — a fork would read about 23500")
    }

    @Test
    fun aFaceChainAfterAUnionAlsoCutsThePartsTip() {
        val ed = platePlusPad()
        val union = ed.solids().last()
        val fused = volumeOf(union)
        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(15.0, 0.0))
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("${ed.doc.nameOf(union)} with a fillet"), "the face row cuts the fused part too: $said")
        assertTrue(said.contains("(4 edges)"), "and takes the plate's whole cap boundary: $said")
        val after = volumeOf(ed.solids().last())
        assertTrue(after < fused, "four convex edges lose material: $after vs $fused")
        assertTrue(after > 30000.0, "and the pad is still on it")
    }

    /**
     * **Blend → union → blend stays one chain**, and the numbers say so: the first fillet's notch survives the
     * union, the union's pad survives the second fillet, and the second fillet takes exactly its own
     * quarter-round from the body as it stands. The two rims are opposite ones, so no corner is shared and
     * nothing is taken twice.
     */
    @Test
    fun aBlendAfterABlendAfterAUnionStaysOneChain() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("r1", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val filleted = volumeOf(ed.solids().last())
        val removed1 = 24000.0 - filleted

        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, 5.0))
        ed.click(Vec2(60.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, 5.0))
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        val union = ed.solids().last()
        val fused = volumeOf(union)
        assertClose(fused, filleted + 10000.0 - 2000.0, 1.0, "the union kept the first fillet's notch")

        ed.activeScalar = ed.doc.newParameter("r2", 3.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 30.0))
        val said = assertNotNull(ed.statusHint)
        assertTrue(said.contains("${ed.doc.nameOf(union)} with a fillet"), "the second blend cut the fused part: $said")
        val after = volumeOf(ed.solids().last())
        val exact = (1.0 - PI / 4.0) * 9.0 * 40.0
        assertTrue(after >= fused - exact * 1.3 && after <= fused - exact, "the far rim's own quarter-round, and only it")
        assertTrue(removed1 > 0.0 && after > 30000.0, "both features and the pad are on one body")
        roundTripsHere(ed)
    }

    private fun roundTripsHere(ed: Editor) {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole chain round-trips byte-equal")
        val back = DocumentFormat.load(once)
        assertClose(
            volumeOf(back.elements.last { it.kind == ElementKind.SOLID }),
            volumeOf(ed.solids().last()),
            1e-6,
            "and the reloaded chain is the same chain",
        )
    }

    // ---- the radius is an ordinary parameter ----

    /**
     * **Equality by sharing, not by constraint**: one parameter feeding two blends on two different edges is
     * *"the same radius on all these edges"*, and retyping it moves both — which is the no-solver stance's own
     * answer, reached with nothing new.
     */
    @Test
    fun oneRadiusParameterDrivesTwoBlends() {
        val ed = plate()
        val plain = volumeOf(ed.solids().single())
        val r = ed.doc.newParameter("r", 3.0.mm)
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 30.0))
        assertEquals(3, ed.solids().size, "two blends chained on the plate: ${ed.statusHint}")
        val two = volumeOf(ed.solids().last())
        val exact = 2.0 * (1.0 - PI / 4.0) * 9.0 * 40.0
        assertClose(plain - two, exact, exact * 0.04, "two quarter-rounds of radius 3 along 40 mm each")

        // one number, both roundings — no relation was asserted, the node is simply shared
        ed.doc.setParameter(r, 6.0.mm)
        val bigger = volumeOf(ed.doc.elements.last { it.kind == ElementKind.SOLID })
        val exactBig = 2.0 * (1.0 - PI / 4.0) * 36.0 * 40.0
        assertClose(plain - bigger, exactBig, exactBig * 0.04, "and at radius 6 both grow together")
    }

    /**
     * **…and it composes with the expressions package**: a blend radius bound to `d/4` follows `d`, because an
     * expression is `boundTo` generalized and a blend's size is an ordinary scalar node like any other.
     */
    @Test
    fun aBlendRadiusBoundToAnExpressionFollowsIt() {
        val ed = plate()
        val plain = volumeOf(ed.solids().single())
        val d = ed.doc.newParameter("d", 16.0.mm)
        val r = ed.doc.newParameter("r", 1.0.mm)
        assertTrue(ed.doc.bindParameter(r, "d/4"), "r = d/4: ${ed.doc.note}")
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val atFour = volumeOf(ed.solids().last())
        val exact = (1.0 - PI / 4.0) * 16.0 * 40.0
        assertClose(plain - atFour, exact, exact * 0.03, "d = 16 makes the radius 4")

        ed.doc.setParameter(d, 8.0.mm)
        val atTwo = volumeOf(ed.doc.elements.last { it.kind == ElementKind.SOLID })
        val exactTwo = (1.0 - PI / 4.0) * 4.0 * 40.0
        assertClose(plain - atTwo, exactTwo, exactTwo * 0.05, "and d = 8 makes it 2 — the blend follows d")
    }

    // ---- the file, the undo, and the stored choice ----

    @Test
    fun theGestureRoundTripsByteForByteAndTakesOneUndo() {
        val ed = quarterTube()
        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 25.0))
        assertEquals(2, ed.solids().size)

        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool ${Tools.BLEND_FACE}") }
        assertTrue(step.contains("signs="), "the blend restates every choice it scored: $step")
        // the address, then four integers per broken edge
        val signs = step.substringAfter("signs=").substringBefore(" ").trim().split(";")
        assertEquals(1 + 4 * 4, signs.size, "one address and four choices per edge: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")

        val back = DocumentFormat.load(text)
        val body = back.elements.last { it.kind == ElementKind.SOLID }
        assertClose(volumeOf(body), volumeOf(ed.solids().last()), 1e-6, "and the reloaded body is the same body")

        ed.undo()
        assertEquals(1, ed.solids().size, "one press takes back the whole blend")
        ed.redo()
        assertEquals(2, ed.solids().size, "and one press puts it back")
    }

    /**
     * **A stored sign is never re-scored** (OP-1/OP-18, the fillet's own lesson one dimension up).
     *
     * The blend's address is an index into an *ordered* list, and which edge is nearest a fixed click moves
     * when the body does. So: break an edge, then drag the profile until a fresh click at the same place
     * would land on a different one, and assert the reload still breaks the edge that was clicked.
     */
    @Test
    fun replayTakesTheStoredEdgeEvenWhereScoringWouldNowChooseAnother() {
        val ed = plate()
        ed.activeScalar = ed.doc.newParameter("r", 3.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val chosen = assertNotNull(ed.statusHint)
        assertTrue(chosen.contains("boundary edge #1 of the top face"), "the click chose the y = 0 rim: $chosen")

        // pull the rectangle's far side down past the clicked one, so the *nearest* edge to that click is now
        // the other long run — a fresh score would choose differently. (The tool stays armed after a gesture,
        // so it is disarmed first: a pointer-down with a tool armed is a pick, not a drag.)
        ed.setTool(Tools.SELECT)
        for (corner in listOf(Vec2(40.0, 30.0), Vec2(0.0, 30.0))) {
            val from = ed.camera.worldToScreen(corner)
            val to = ed.camera.worldToScreen(Vec2(corner.x, -8.0))
            ed.pointerMove(from)
            ed.pointerDown(from)
            ed.pointerMove(to)
            ed.pointerUp(to)
        }

        val text = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(text)
        val steps = text.lines().filter { it.startsWith("tool ${Tools.BLEND_EDGE}") }
        assertEquals(1, steps.size, "one blend step\n$text")
        val address = steps[0].substringAfter("signs=").substringBefore(";").toInt()
        val reloaded = DocumentFormat.save(back)
        assertEquals(text, reloaded, "save -> load -> save byte-equal after the drag")
        assertTrue(
            reloaded.lines().single { it.startsWith("tool ${Tools.BLEND_EDGE}") }.contains("signs=$address;"),
            "the reload takes the recorded edge verbatim rather than scoring the click again",
        )
        assertManifold(meshOf(back.elements.last { it.kind == ElementKind.SOLID }), "the reloaded blend")
    }

    // ---- refusal and heal, at the gesture ----

    @Test
    fun aRadiusThatOutgrowsThePlateRefusesAndHealsWhenItIsRetyped() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 6.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        val r = ed.doc.newParameter("r", 10.0.mm)
        ed.activeScalar = r
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        val el = ed.solids().last()
        val why = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason
        val said = assertNotNull(why, "a 10 mm round on a 6 mm plate has nowhere to go")
        assertTrue(said.contains("boundary edge #1 of the top face"), "the refusal names the edge: $said")
        assertTrue(said.contains("largest that fits"), "and says what to type instead: $said")

        ed.doc.setParameter(r, 2.0.mm)
        assertManifold(meshOf(el), "and it heals when the radius comes down")
    }
}
