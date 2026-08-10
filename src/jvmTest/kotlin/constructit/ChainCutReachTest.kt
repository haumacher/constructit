package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SolidValue
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.SlotKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The chain cut, made reachable** (OP-22's extension; OP-13's 2D/3D split; session 63) — the user's plate
 * and handle.
 *
 * The report was about *reaching the inputs*, not about the operator: a plate (a circle in the plan extruded
 * 2 mm) with a handle revolved on the upright space `plane1`, and two lines drawn back in the plan to trim
 * the handle with. *"I'm not able to select the required inputs in a way the tool requires. Selecting a solid
 * is complete pain, because it cannot simply be selected in 3D by 'clicking on it'. It simply does not
 * work."* Three separate walls stood between that drawing and the cut it wanted, and this is the test of all
 * three:
 *
 * 1. **a line was not a legal chain.** The refusal conceded it in its own words — the *Chain* tool's help has
 *    always said *"two clicks give an infinite line"* — so the drawing's own lines, and the **mirror** of one,
 *    are chains now (`Document.chainOf`, `Chains.ofLine`). A **ray** is not, and says why: it stops, so the
 *    plane closes round its end and there are not two sides to choose between.
 * 2. **the two picks could not span spaces.** The solid lives on `plane1` and the chain in the plan, and
 *    switching between them dropped the half-collected gesture. The six chain-cut rows declare `crossSpace`
 *    for the boolean's own reason (a solid is a **body**, not a drawing), and the cutting fence stands normal
 *    to the **chain's own space** whichever space is showing — which is what `Document.cutByChain` always
 *    computed and is now what the gesture can reach.
 * 3. **a solid could not be clicked in 3D at all.** A `SOLID` slot now resolves by **ray ∩ mesh** over the
 *    bodies the 3D view draws, nearest hit first — and *ahead* of the two flat routes, which the user's own
 *    drawing forces: a handle standing on the rim of a plate is within a pick tolerance of the **plate's**
 *    footprint circle, so footprint-first answered a click aimed squarely at the handle with the plate.
 *
 * Face-ID provenance stays parked exactly where it was: what a ray comes back with is the **body**, never a
 * face of it, so nothing here has to invent an identity that could not survive a recompute.
 */
class ChainCutReachTest {
    // ---- the drawing, and the numbers read off it ----

    private val PLAN = "plan"
    private val DATUM = "plane1"

    /**
     * `chain2` (`e42`) as the file states it: the line from `e40`, which rides the rotated line `e32`, to
     * `e41`, which is welded onto the circle/line intersection `e34` at (75.75, 13.25). Written here as the
     * two numbers the assertions below are stated against, and **checked against the model** in
     * [theTwoChainsAreTheLinesTheUserDrew] rather than trusted.
     */
    private val CHAIN2_ORIGIN = Vec2(123.67935077443069, -19.0018826287483)
    private val CHAIN2_DIR = Vec2(-0.8296539518016404, 0.5582779955003792)

    /** The point both chains run through, and the one the trimmed handle's extent is measured against. */
    private val CHAIN_THROUGH = Vec2(75.75, 13.25)

    private fun loaded(): Editor = Editor(DocumentFormat.load(ChainCutFixture.CIT))

    private fun named(
        doc: Document,
        n: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == n || doc.nameOf(it) == n },
            "the drawing has $n",
        )

    private fun plate(doc: Document): Element = named(doc, "e5")

    private fun handle(doc: Document): Element = named(doc, "e30")

    private fun chain2(doc: Document): Element = named(doc, "chain2")

    private fun chain1(doc: Document): Element = named(doc, "chain1")

    private fun valueOf(el: Element): SolidValue? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? SolidValue

    private fun meshOf(el: Element): Mesh3 = assertNotNull(valueOf(el), "a solid with a value").solid.mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun solids(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    /** The one solid the gesture added, i.e. the last one in document order. */
    private fun newest(doc: Document): Element = solids(doc).last()

    // ---- gestures ----

    /** A click in whichever view is driving: the 3D projection when one is set, the 2D canvas otherwise. */
    private fun Editor.click(world: Vec2) {
        val s = assertNotNull(pointing?.toScreen(world) ?: camera.worldToScreen(world), "the point has an image")
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /**
     * A 3D view looking at [at] from a fixed three-quarter station — the browser's own seam
     * ([Editor.pointing]), which is how every 3D gesture in this suite is driven headlessly (OP-12).
     */
    private fun Editor.look(
        at: Vec3,
        yaw: Double = 0.6,
        pitch: Double = 0.5,
        distance: Double = 300.0,
    ) {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        pointing = PlanePerspective(plane, Camera3(target = at, distance = distance, yaw = yaw, pitch = pitch), 800.0, 600.0)
    }

    private fun centreOf(el: Element): Vec3 {
        val b = assertNotNull(Geom3.bounds(meshOf(el)), "the solid has bounds")
        return (b.first + b.second) * 0.5
    }

    /** Where a body is clicked in the 3D view: straight at the middle of it, in the plane's own coordinates. */
    private fun Editor.aimAt(el: Element): Vec2 {
        val plane = assertNotNull(doc.activePlane3(Evaluator()), "the active space has a plane")
        return plane.toLocal(centreOf(el))
    }

    /** A point on `chain2`, [t] mm along its direction from its origin — where the chain is clicked. */
    private fun onChain2(t: Double): Vec2 = Vec2(CHAIN2_ORIGIN.x + CHAIN2_DIR.x * t, CHAIN2_ORIGIN.y + CHAIN2_DIR.y * t)

    /** A point [d] mm off `chain2` at [t] along it — where the *side to keep* is clicked (+ is its left). */
    private fun besideChain2(
        t: Double,
        d: Double,
    ): Vec2 {
        val p = onChain2(t)
        return Vec2(p.x - CHAIN2_DIR.y * d, p.y + CHAIN2_DIR.x * d)
    }

    /** How far [p] lies to the **left** of the line through [origin] with direction [dir] — the chain's sign. */
    private fun sideOf(
        p: Vec2,
        origin: Vec2 = CHAIN2_ORIGIN,
        dir: Vec2 = CHAIN2_DIR,
    ): Double = dir.x * (p.y - origin.y) - dir.y * (p.x - origin.x)

    /** The range of [sideOf] over every vertex of [mesh] — how far it reaches either side of the chain. */
    private fun sideSpan(
        mesh: Mesh3,
        origin: Vec2 = CHAIN2_ORIGIN,
        dir: Vec2 = CHAIN2_DIR,
    ): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (v in mesh.vertices) {
            val s = sideOf(Vec2(v.x, v.y), origin, dir)
            lo = minOf(lo, s)
            hi = maxOf(hi, s)
        }
        return lo to hi
    }

    /** The handle's own footprint hint on `plane1` — the drawing a `SOLID` slot picks it by there (OP-17). */
    private fun handleFootprintAim(doc: Document): Vec2 {
        val seg =
            assertNotNull(
                valueOf(handle(doc))
                    ?.solid
                    ?.feature
                    ?.footprint
                    ?.flatMap { it.outer.elements }
                    ?.filterIsInstance<ProfileElement.Seg>()
                    ?.firstOrNull(),
                "the handle's footprint has a straight piece to click",
            ).segment
        return Vec2((seg.a.x + seg.b.x) / 2.0, (seg.a.y + seg.b.y) / 2.0)
    }

    /**
     * **`save → load → save` is a fixed point on the FIRST pass** — and, where [text] is the user's own
     * pre-package paste, leaves every line alone but the three named below.
     *
     * The fixed point is the strong half and is asserted first, for any text: what a save writes is exactly
     * what the next load says, with nothing left to settle. It was not so until session 63's creep was closed
     * — `e40` is `attach`ed to `e32`, the `attach` step restated *nothing*, and replay re-derived the rider's
     * parameter by projecting the point's own restated position, which is derived geometry the moment the point
     * rides the curve. The projection did not reproduce its own output to the last bit, so the last digit moved
     * about 1e-13 mm on every pass and took four of them to settle. The step now restates the parameter, as
     * `pointoncurve` always has, so the position it derives is bit-identical to the one that was written.
     *
     * Three named differences remain on the **first** save of the user's file, none of them a change of
     * meaning. (1) `attach e40 e32` gains that very `dofs=`, an argument no earlier build wrote — an absent one
     * still means "re-derive it from the recorded position", which is what that file always meant (OP-18).
     * (2) The `point -> e40` line moves by that same last digit, once: the file's stored foot is re-derived one
     * final time on the way in, and never again. (3) The revolve step gains its *offset* freedom (`dofs=0deg`),
     * for the same versioning reason as (1). And the header itself comes up: this fixture was written at format
     * 2 and is kept that way, because a fixture edited to say the current version stops being a test of
     * anything.
     *
     * A text this build wrote is held to more than that: it must come back **byte-identical**, no exceptions.
     */
    private fun assertRoundTrips(text: String) {
        val once = DocumentFormat.save(DocumentFormat.load(text))
        assertEquals(
            once,
            DocumentFormat.save(DocumentFormat.load(once)),
            "save -> load -> save must be a fixed point on the first pass, with nothing left to settle",
        )
        if (text.startsWith(DocumentFormat.HEADER)) {
            assertEquals(text, once, "a text this build wrote comes back byte for byte, attachment and all")
            return
        }
        val differing = text.lines().zip(once.lines()).filter { it.first != it.second }
        assertEquals(text.lines().size, once.lines().size, "no step is added or lost")
        for (d in differing) {
            if (d.first.startsWith("constructit ")) {
                assertEquals(DocumentFormat.HEADER, d.second, "the header comes up to the version this build writes and nothing else does")
                continue
            }
            if (d.first.startsWith("tool revolve ")) {
                assertEquals(d.first.replace(" -> ", " dofs=0deg -> "), d.second, "the revolve gains its offset freedom and nothing else")
                continue
            }
            if (d.first.startsWith("attach ")) {
                assertEquals(d.first, withoutRestatedAttach(d.second), "the attach gains the freedom it restates and nothing else")
                continue
            }
            assertTrue(d.first.endsWith("-> e40"), "only the attached point's restated position moves, not ${d.first}")
            val a = d.first.removePrefix("point ").removeSuffix(" -> e40").split(",").map { it.toDouble() }
            val b = d.second.removePrefix("point ").removeSuffix(" -> e40").split(",").map { it.toDouble() }
            assertClose(a[0], b[0], 1e-12, msg = "and it moves by less than a picometre in x")
            assertClose(a[1], b[1], 1e-12, msg = "and in y")
        }
    }

    // ---- 0. the drawing itself ----

    /**
     * The user's script loads clean and is a **fixed point of save → load → save**, on the first pass.
     *
     * Two lines of the paste are not bit-identical to what this build writes back, both named in
     * [assertRoundTrips]: `attach e40 e32` gains the freedom it restates, and `e40`'s stored position is
     * re-derived one last time as the file comes in. From that save on, nothing moves at all — which is what
     * session 63's creep item asked for and what the assertion above now claims.
     */
    @Test
    fun theUsersDrawingLoadsAndSettlesOnItsFirstSave() {
        val doc = DocumentFormat.load(ChainCutFixture.CIT)
        assertRoundTrips(ChainCutFixture.CIT)
        assertEquals(2, solids(doc).size, "the plate and the handle")
        for (s in solids(doc)) assertManifold(meshOf(s), doc.nameOf(s))
        assertEquals(DATUM, handle(doc).space, "the handle was sketched on the upright space")
        assertEquals(PLAN, chain2(doc).space, "and both cutting lines were drawn in the plan")
        assertEquals(PLAN, chain1(doc).space)
    }

    /** Both chains are ordinary **lines**, and `chain1` is the *mirror* of `chain2` — a line still. */
    @Test
    fun theTwoChainsAreTheLinesTheUserDrew() {
        val doc = DocumentFormat.load(ChainCutFixture.CIT)
        assertEquals(ElementKind.LINE, chain2(doc).kind, "chain2 is a line, not a drawn chain")
        assertEquals(ElementKind.LINE, chain1(doc).kind, "and a mirrored line is a line by construction")
        // the numbers the assertions below are written against, read off the model rather than assumed
        assertClose(sideOf(CHAIN_THROUGH), 0.0, 1e-6, msg = "chain2 runs through (75.75, 13.25)")
        val span = sideSpan(meshOf(handle(doc)))
        assertClose(span.first, -5.2601970507166484, 1e-6, msg = "the handle reaches 5.26 mm to chain2's right")
        assertClose(span.second, 42.561274686507566, 1e-6, msg = "and 42.56 mm to its left")
    }

    // ---- 1. the user's cut, both ways in ----

    /**
     * **(a) The whole gesture through a 3D ray.** Arm *Cut by chain*, click the **handle in the 3D view** —
     * which is the pick that did not exist — then the chain in the plan, then the side to keep. The handle is
     * trimmed, the cut is watertight, and the step records the solid the ray found by name.
     */
    @Test
    fun theHandleIsCutByAThreeDRayPickThenTheChainInThePlan() {
        val ed = loaded()
        val doc = ed.doc
        ed.look(centreOf(handle(doc)))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(handle(doc)))
        // back to the 2D canvas for the chain and the side, exactly as a user drops out of the 3D view
        ed.pointing = null
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))

        assertEquals(3, solids(doc).size, "the cut is a third solid; its operand stays in the drawing")
        val cut = newest(doc)
        assertNull(whyInvalid(cut), "the cut evaluates")
        assertManifold(meshOf(cut), "the trimmed handle")
        assertTrue(
            ed.statusHint.contains("${doc.nameOf(handle(doc))} cut by ${doc.nameOf(chain2(doc))}"),
            "and it says what it cut with what: ${ed.statusHint}",
        )
        assertTrue(ed.statusHint.contains("keeping the left side"), "naming the half it kept: ${ed.statusHint}")

        val saved = DocumentFormat.save(doc)
        assertTrue(
            saved.contains("tool cutbychain els=${doc.nameOf(handle(doc))},${doc.nameOf(chain2(doc))}"),
            "the step names the handle the ray found and the line, in pick order:\n$saved",
        )
        assertTrue(saved.contains("signs=1 ->"), "and the side it kept is a persisted sign (OP-1):\n$saved")
        assertRoundTrips(saved)
        val reloaded = DocumentFormat.load(saved)
        assertClose(
            Geom3.volume(meshOf(newest(reloaded))),
            Geom3.volume(meshOf(cut)),
            1e-6,
            msg = "and replay rebuilds the very same body",
        )
    }

    /**
     * **(b) The same cut without ever using the 3D view**: pick the handle on `plane1` by its own footprint,
     * switch to the plan — the pick survives, and the status line says so — and click the chain and the side
     * there. Same body, to the last vertex.
     */
    @Test
    fun theSameCutIsMadeByPickingTheHandleOnItsOwnPlaneAndSwitchingSpaces() {
        val viaRay =
            run {
                val ed = loaded()
                ed.look(centreOf(handle(ed.doc)))
                ed.setTool(Tools.CUT_BY_CHAIN)
                ed.click(ed.aimAt(handle(ed.doc)))
                ed.pointing = null
                ed.click(onChain2(30.0))
                ed.click(besideChain2(30.0, 20.0))
                meshOf(newest(ed.doc))
            }

        val ed = loaded()
        val doc = ed.doc
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(handleFootprintAim(doc))
        ed.setActiveSpace(PLAN)
        assertTrue(
            ed.statusHint.startsWith("Cut by chain: 1 pick kept across the switch to plan"),
            "the pick spans the switch, and says so: ${ed.statusHint}",
        )
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))

        assertEquals(3, solids(doc).size, "one cut")
        val cut = meshOf(newest(doc))
        assertManifold(cut, "the trimmed handle")
        assertClose(Geom3.volume(cut), Geom3.volume(viaRay), 1e-6, msg = "the two routes in build the same body")
        assertEquals(viaRay.vertices.size, cut.vertices.size, "vertex for vertex")
    }

    /**
     * **The cut is geometrically right, and that is a statement about which plane the fence stands in.** The
     * chain is drawn in the **plan**, so its fence is the vertical prism over the line — never over `plane1`,
     * where the handle's own sketch lives. So every vertex of the kept half is on the kept side of that line
     * *whatever its height*, and the half that goes is exactly the material on the other side.
     *
     * Kept side: `+1`, the **left** of the chain's run from (123.68, −19.00) towards (75.75, 13.25). The
     * handle reaches from 5.260 mm right of that line to 42.561 mm left of it; keeping the left therefore
     * leaves a body spanning 0 … 42.561 mm across the chain, and the chain runs through (75.75, 13.25), which
     * is where the kept half's bounding box stops.
     */
    @Test
    fun theFenceStandsNormalToThePlanBecauseThatIsWhereTheChainIsDrawn() {
        val ed = loaded()
        val doc = ed.doc
        ed.look(centreOf(handle(doc)))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(handle(doc)))
        ed.pointing = null
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))
        val cut = meshOf(newest(doc))
        val whole = meshOf(handle(doc))

        // a tessellation tolerance: the boolean welds on a 1e-7 lattice and the mesh carries float32 positions
        val tol = 1e-3
        val kept = sideSpan(cut)
        assertTrue(kept.first > -tol, "no material survives on the discarded side (it reaches ${kept.first} mm)")
        assertClose(kept.second, sideSpan(whole).second, tol, msg = "and the kept side is untouched out to its far edge")
        assertClose(kept.second, 42.561274686507566, tol, msg = "which is 42.561 mm left of the chain")

        val b = assertNotNull(Geom3.bounds(cut), "the trimmed handle has bounds")
        val wb = assertNotNull(Geom3.bounds(whole), "the handle has bounds")
        assertClose(b.second.y, CHAIN_THROUGH.y, tol, msg = "the chain runs through y = 13.25, so the kept half stops there")
        assertTrue(wb.second.y > b.second.y + 0.5, "and the whole handle reached past it (to ${wb.second.y} mm)")
        assertClose(b.first.z, wb.first.z, tol, msg = "a vertical fence takes nothing off the bottom")
        assertClose(b.second.z, wb.second.z, tol, msg = "nor off the top — it is a prism, not a slice")
        assertClose(b.first.x, wb.first.x, tol, msg = "and the far end of the handle is untouched")
    }

    /**
     * **The mirror image of a line cuts exactly as the line does.** `chain1` is `chain2` mirrored about the
     * rotated line `e32` — still an `ElementKind.LINE`, so it fills the chain slot by construction and needs
     * no second drawing. Cutting with it keeps the complementary material, which is what a mirrored cut is
     * for.
     */
    @Test
    fun theMirroredLineCutsJustAsTheChainDoes() {
        val ed = loaded()
        val doc = ed.doc
        val line = assertNotNull((Evaluator().eval(chain1(doc).ref.node) as? EvalResult.Ok)?.value, "chain1 has a value")
        val dir = (line as constructit.core.LineValue).line.dir.normalized()
        val origin = line.line.origin

        ed.look(centreOf(handle(doc)))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(handle(doc)))
        ed.pointing = null
        val on = Vec2(origin.x + dir.x * 30.0, origin.y + dir.y * 30.0)
        ed.click(on)
        // the side to keep: 20 mm to the chain's *right*, where the bulk of the handle is
        ed.click(Vec2(on.x + dir.y * 20.0, on.y - dir.x * 20.0))

        val cut = newest(doc)
        assertNull(whyInvalid(cut), "the mirrored line is a legal chain: ${whyInvalid(cut)}")
        assertManifold(meshOf(cut), "the handle trimmed by the mirrored line")
        assertTrue(
            ed.statusHint.contains("cut by ${doc.nameOf(chain1(doc))}"),
            "and the cut names the mirrored line: ${ed.statusHint}",
        )
        val span = sideSpan(meshOf(cut), origin, dir)
        assertTrue(span.second < 1e-3, "nothing survives to the left of chain1 (it reaches ${span.second} mm)")
        assertClose(
            span.first,
            sideSpan(meshOf(handle(doc)), origin, dir).first,
            1e-3,
            msg = "and the right of it is untouched",
        )
    }

    /**
     * **Split by a line, and the two halves are the whole body.** The same operand rule one row along, and the
     * exactest statement there is that a cut removes what it should: nothing is lost and nothing is counted
     * twice.
     */
    @Test
    fun theTwoHalvesOfASplitByALineAddUpToTheWholeHandle() {
        val ed = loaded()
        val doc = ed.doc
        val before = Geom3.volume(meshOf(handle(doc)))
        ed.look(centreOf(handle(doc)))
        ed.setTool(Tools.SPLIT_BY_CHAIN)
        ed.click(ed.aimAt(handle(doc)))
        ed.pointing = null
        ed.click(onChain2(30.0))

        assertEquals(4, solids(doc).size, "the two halves, beside the plate and the handle")
        val halves = solids(doc).takeLast(2)
        for (h in halves) assertManifold(meshOf(h), doc.nameOf(h))
        val sum = halves.sumOf { Geom3.volume(meshOf(it)) }
        assertClose(sum, before, 1e-2, msg = "the two halves are the handle, to a tessellation tolerance")
        assertClose(Geom3.volume(meshOf(halves[0])), 1358.9303116642059, 1e-2, msg = "the left half")
        assertClose(Geom3.volume(meshOf(halves[1])), 127.47928683831742, 1e-2, msg = "and the right one")
        assertTrue(ed.statusHint.contains("split by"), "and the split says what it made: ${ed.statusHint}")
    }

    /**
     * **The kept side is a sign, and swinging the chain never swaps it** (OP-1, the promise this feature was
     * given at birth). The chain is a line through a point that rides `e32`, so re-parameterizing `angle4`
     * swings it — far enough here that the position the user clicked ends up on the *other* side. A re-scored
     * choice would come back with the complementary body; the recorded one comes back with the same half.
     */
    @Test
    fun swingingTheChainAfterwardsNeverSwapsTheKeptSide() {
        val ed = loaded()
        val doc = ed.doc
        ed.look(centreOf(handle(doc)))
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(ed.aimAt(handle(doc)))
        ed.pointing = null
        ed.click(onChain2(30.0))
        val sideClick = besideChain2(30.0, 20.0)
        ed.click(sideClick)
        val cutId = newest(doc).id
        assertTrue(sideOf(sideClick) > 0.0, "the side was clicked on the chain's left")

        val angle4 = assertNotNull(doc.scalars.firstOrNull { it.name == "angle4" }, "the drawing has the parameter angle4")
        doc.setParameter(angle4, Quantity(80.0 * kotlin.math.PI / 180.0, Dimension.ANGLE))
        val moved = (Evaluator().eval(chain2(doc).ref.node) as EvalResult.Ok).value as constructit.core.LineValue
        val nowSide = sideOf(sideClick, moved.line.origin, moved.line.dir.normalized())
        assertTrue(nowSide < 0.0, "the swing carried that click across the chain (it is now $nowSide mm to its right)")

        val cut = assertNotNull(doc.elements.firstOrNull { it.id == cutId }, "the cut is still there")
        assertNull(whyInvalid(cut), "and still evaluates: ${whyInvalid(cut)}")
        assertManifold(meshOf(cut), "the trimmed handle after the swing")
        val span = sideSpan(meshOf(cut), moved.line.origin, moved.line.dir.normalized())
        assertTrue(span.first > -1e-3, "the same half — the chain's left — is still the one kept, not the complement")
        assertTrue(span.second > 1.0, "and it is a body, not an empty one")
        assertTrue(DocumentFormat.save(doc).contains("signs=1 ->"), "the sign in the file never moved")
    }

    // ---- 2. what a chain may be, and what it may not ----

    /** A **ray** is refused by name, and named with the line it is one click from being. */
    @Test
    fun aRayIsRefusedBecauseItDoesNotSeparateThePlane() {
        val ed = loaded()
        val doc = ed.doc
        // a ray in the plan, drawn through two of the drawing's own points
        ed.setTool(Tools.RAY)
        ed.click(Vec2(100.0, -30.0))
        ed.click(Vec2(60.0, 20.0))
        val ray = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.RAY }, "the drawing has a ray")
        val before = solids(doc).size

        assertNull(doc.cutByChain(handle(doc), ray, Vec2(0.0, 0.0), emptyList()), "a ray cannot cut")
        assertEquals(before, solids(doc).size, "and nothing was built")
        assertTrue(
            (doc.note ?: "").startsWith("Cut by chain: ${doc.nameOf(ray)} is a ray — it stops, so the plane flows round its end"),
            "the refusal names the element and why: ${doc.note}",
        )
        assertTrue((doc.note ?: "").contains("cut with the line through it"), "and the way forward: ${doc.note}")
    }

    /** Anything that neither closes nor runs on for ever is still refused, and now names the line too. */
    @Test
    fun aSegmentIsStillRefusedAndTheAlternativeNamesALine() {
        val ed = loaded()
        val doc = ed.doc
        val seg = assertNotNull(doc.elements.firstOrNull { it.kind == ElementKind.SEGMENT }, "the drawing has a segment")
        assertNull(doc.cutByChain(handle(doc), seg, Vec2(0.0, 0.0), emptyList()), "a segment cannot cut")
        assertTrue(
            (doc.note ?: "").startsWith("Cut by chain: ${doc.nameOf(seg)} is") && (doc.note ?: "").contains("cut with a chain or a line"),
            "the refusal offers the line as well as the closed curve: ${doc.note}",
        )
    }

    /** The chain slot itself takes a line — the pick, not only the build. */
    @Test
    fun theChainSlotAcceptsALine() {
        val ed = loaded()
        val doc = ed.doc
        assertTrue(doc.isChainCandidate(chain2(doc), Evaluator()), "a line fills the chain slot")
        assertTrue(doc.isChainCandidate(chain1(doc), Evaluator()), "and so does a mirrored one")
        val seg = assertNotNull(doc.elements.firstOrNull { it.kind == ElementKind.SEGMENT }, "a segment")
        assertTrue(!doc.isChainCandidate(seg, Evaluator()), "a segment does not")
        assertEquals("chain, line or closed curve", Tools.roleOfKind(SlotKind.CHAIN), "and the slot says so")
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(onChain2(30.0))
        assertTrue(
            ed.statusHint.startsWith("That click hit no solid"),
            "the first slot is still the solid's — a line does not jump the queue: ${ed.statusHint}",
        )
    }

    /**
     * **The side to keep is clicked where the chain is drawn, and a switch away from it refuses by name.**
     *
     * The trap `crossSpace` opens, closed with it: a click is a bare position, and `Chains.sideAt` reads it in
     * the **chain's** plane. While the picks could not span spaces the two frames were always the same one;
     * now that a user is invited to switch between picks, a side clicked on `plane1` against a chain drawn in
     * the plan would be scored against a line whose coordinates it does not share — the wrong half, kept
     * silently, and then frozen into `signs=` where OP-1 guarantees it is never reconsidered. A gesture
     * refusal is the honest answer, and it is also what keeps every recorded `clicks=` in one stated frame.
     */
    @Test
    fun theSideToKeepMustBeClickedWhereTheChainIsDrawn() {
        val ed = loaded()
        val doc = ed.doc
        val before = solids(doc).size
        // in the plan, where chain2 is drawn, a side click is read in the frame it was made in
        assertNotNull(doc.cutByChain(handle(doc), chain2(doc), besideChain2(30.0, 20.0), emptyList()), "the ordinary way")
        ed.setActiveSpace(DATUM)
        assertNull(
            doc.cutByChain(handle(doc), chain2(doc), Vec2(0.0, 5.0), emptyList()),
            "…but from plane1 the position means something else, so it refuses rather than guessing",
        )
        assertEquals(before + 1, solids(doc).size, "and the refusal built nothing")
        assertTrue(
            (doc.note ?: "").startsWith("Cut by chain: the side to keep is clicked beside ${doc.nameOf(chain2(doc))}"),
            "the refusal names the chain and where it lives: ${doc.note}",
        )
        assertTrue((doc.note ?: "").contains("switch back there and click the side"), "and the way forward: ${doc.note}")

        // …and a replay is never scored, so it is never refused: the recorded sign carries it
        assertNotNull(
            doc.cutByChain(handle(doc), chain2(doc), Vec2(0.0, 5.0), listOf(1)),
            "a replay hands back its sign and never comes here",
        )
    }

    // ---- 3. the tool table ----

    /** All six chain-cut rows span spaces, for the boolean's own reason: a solid is a body, not a drawing. */
    @Test
    fun theSixChainCutRowsSpanSpaces() {
        val doc = Document()
        for (
        id in listOf(
            Tools.CUT_BY_CHAIN,
            Tools.SPLIT_BY_CHAIN,
            Tools.CUT_ALONG_CURVE,
            Tools.CUT_ALONG_CURVE_FLAT,
            Tools.SPLIT_ALONG_CURVE,
            Tools.SPLIT_ALONG_CURVE_FLAT,
        )
        ) {
            assertTrue(assertNotNull(doc.toolDef(id), "the table has $id").crossSpace, "$id keeps its picks across a switch")
        }
    }

    // ---- 4. a drop speaks ----

    /**
     * **A switch that drops half a gesture says so.** The keep has always spoken; the drop was the silent one,
     * and silence is exactly the case where the canvas changes anyway and the user reads a new drawing rather
     * than a lost pick.
     */
    @Test
    fun aSwitchThatDropsPicksSaysWhatWentAndWhy() {
        val ed = loaded()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(30.0, 30.0))
        ed.setActiveSpace(DATUM)
        assertTrue(
            ed.statusHint.startsWith("Segment: 1 pick dropped — its picks do not span planes, so the gesture starts over here."),
            "the drop names the tool, the reason and where the gesture stands: ${ed.statusHint}",
        )
        assertTrue(ed.statusHint.contains("Sketching on plane1"), "and it is composed with the space note: ${ed.statusHint}")
    }

    /** A switch with nothing half-collected is the plain space note it always was. */
    @Test
    fun aSwitchWithNothingCollectedSaysOnlyWhereYouAre() {
        val ed = loaded()
        ed.setTool(Tools.SEGMENT)
        ed.setActiveSpace(DATUM)
        assertTrue(ed.statusHint.startsWith("Sketching on plane1"), "nothing was dropped, so nothing is said: ${ed.statusHint}")
        assertTrue(!ed.statusHint.contains("dropped"), "no phantom report")
    }

    // ---- 5. the rules a 3D pick obeys ----

    /**
     * **The nearer of two bodies under the ray takes the click** — the whole reason a ray beats a distance,
     * and asserted as an *ordering* rather than as an outcome: both bodies are shown to be on the very ray
     * the click casts, the handle's hit is shown to be the nearer one, and the pick is shown to be the
     * handle. A test that only checked the last of those would pass under "the farthest wins" too.
     */
    @Test
    fun theNearerOfTwoOverlappingBodiesTakesTheRay() {
        val ed = loaded()
        val doc = ed.doc
        // where the handle stands over the plate: the plate reaches x = 76.06, the handle from x = 67.51
        val over = Vec2(72.0, -20.0)
        ed.look(Vec3(over.x, over.y, 4.5), pitch = 1.2)
        val ray = assertNotNull(assertNotNull(ed.pointing, "a 3D view is driving").eyeRay(over), "and it has an eye")
        val tHandle = assertNotNull(Geom3.rayMesh(ray, meshOf(handle(doc))), "the ray meets the handle")
        val tPlate = assertNotNull(Geom3.rayMesh(ray, meshOf(plate(doc))), "and the plate behind it")
        assertTrue(tHandle < tPlate, "the handle is the nearer of the two ($tHandle mm against $tPlate mm)")

        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(over)
        ed.pointing = null
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))
        assertTrue(
            DocumentFormat.save(doc).contains("tool cutbychain els=${doc.nameOf(handle(doc))},"),
            "…and the nearer one is what the click picked",
        )
    }

    /** **A hidden solid is never offered to a ray** — ghosted or not, the toggle is a view and never a pick. */
    @Test
    fun aRayNeverOffersAHiddenSolidWhetherGhostedOrNot() {
        for (showHidden in listOf(false, true)) {
            val ed = loaded()
            val doc = ed.doc
            ed.showHidden = showHidden
            val at = centreOf(handle(doc))
            doc.setElementsVisible(listOf(handle(doc)), false)
            ed.look(at)
            ed.setTool(Tools.CUT_BY_CHAIN)
            // straight at the hidden handle's own middle — the click that picked it while it was visible
            val aim = assertNotNull(doc.activePlane3(Evaluator()), "the plan").toLocal(at)
            assertNull(
                Geom3.rayMesh(assertNotNull(assertNotNull(ed.pointing, "3D").eyeRay(aim), "eye"), meshOf(plate(doc))),
                "the plate is not under this click, so whatever answers is not it (showHidden=$showHidden)",
            )
            ed.click(aim)
            ed.pointing = null
            ed.click(onChain2(30.0))
            ed.click(besideChain2(30.0, 20.0))
            val saved = DocumentFormat.save(doc)
            assertTrue(
                !saved.contains("tool cutbychain els=${doc.nameOf(handle(doc))},"),
                "the hidden handle is never what the click came back with (showHidden=$showHidden):\n$saved",
            )
            assertTrue(
                Scene3.extract(doc, Evaluator(), if (showHidden) setOf(handle(doc)) else emptySet())
                    .solids
                    .none { it.elementId == handle(doc).id && !it.ghost },
                "…because it is not a shaded body in the scene either (showHidden=$showHidden)",
            )
        }
    }

    /**
     * **A ray that hits no mesh changes nothing.** The flat routes still answer where they always did, so a
     * miss in the 3D view falls through to exactly what the click meant before — and in the plan, where there
     * is no eye at all, the ray route does not exist.
     */
    @Test
    fun aRayThatMissesFallsThroughToTheFlatRoutes() {
        val ed = loaded()
        val doc = ed.doc
        ed.setActiveSpace(DATUM)
        // aimed far off the part, so no mesh is under the cursor; the footprint is what is clicked
        ed.look(Vec3(80.0, 0.0, 4.0), yaw = 0.6, pitch = 0.4, distance = 400.0)
        // just outside the profile's lower edge: past the body, still well inside the pick tolerance of the
        // footprint hint — the "click beside the part" case the three routes are ordered for
        val aim = handleFootprintAim(doc).let { Vec2(it.x, it.y - 1.5) }
        val ray = assertNotNull(assertNotNull(ed.pointing, "a 3D view is driving").eyeRay(aim), "and it has an eye")
        for (s in solids(doc)) {
            assertNull(Geom3.rayMesh(ray, meshOf(s)), "the ray really misses ${doc.nameOf(s)}")
        }
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(aim)
        ed.setActiveSpace(PLAN)
        assertTrue(
            ed.statusHint.startsWith("Cut by chain: 1 pick kept"),
            "the footprint answered where the ray did not: ${ed.statusHint}",
        )
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))
        assertTrue(
            DocumentFormat.save(doc).contains("tool cutbychain els=${doc.nameOf(handle(doc))},"),
            "…and what it answered with is the handle, by name",
        )
    }

    /** In the plan there is no eye, so nothing about picking there changed — the footprint keeps every click. */
    @Test
    fun thePlanHasNoRayAndItsPickingIsUntouched() {
        val ed = loaded()
        val doc = ed.doc
        assertNull(ed.camera.eyeRay(Vec2(0.0, 0.0)), "the 2D canvas has no viewing ray to shoot")
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.CUT_BY_CHAIN)
        ed.click(handleFootprintAim(doc))
        ed.setActiveSpace(PLAN)
        ed.click(onChain2(30.0))
        ed.click(besideChain2(30.0, 20.0))
        assertEquals(3, solids(doc).size, "the plan-only route still builds the cut")
        assertManifold(meshOf(newest(doc)), "the trimmed handle")
    }

    /** A body is **selected** by clicking it in 3D too, and a point of the drawing still outranks it. */
    @Test
    fun aBodyIsSelectedByClickingItInThreeD() {
        val ed = loaded()
        val doc = ed.doc
        // aimed at the handle where no drawing of the plan lies under it, so nothing outranks the body
        val at = Vec3(76.0, -20.0, 4.5)
        ed.look(at, pitch = 0.9)
        ed.setTool(Tools.SELECT)
        ed.click(assertNotNull(doc.activePlane3(Evaluator()), "the plan").toLocal(at))
        assertEquals(handle(doc).id, ed.selection?.id, "the body under the ray is what the click selected")
    }

    /**
     * **…and a point of the drawing still outranks it**, which is the half that keeps this a better answer to
     * "which solid" rather than a new precedence. `e37` is a welded intersection point standing over the
     * handle; a click on it selects the point, with the body still reachable one step along the cycle.
     */
    @Test
    fun aPointOfTheDrawingStillOutranksTheBodyBehindIt() {
        val ed = loaded()
        val doc = ed.doc
        val point = named(doc, "e37")
        val where = assertNotNull((Evaluator().eval(point.ref.node) as? EvalResult.Ok)?.value as? PointValue, "e37 has a place").p
        val at = Vec3(where.x, where.y, 4.5)
        ed.look(at, pitch = 0.9)
        val plan = assertNotNull(doc.activePlane3(Evaluator()), "the plan")
        val aim = plan.toLocal(at)
        assertNotNull(
            Geom3.rayMesh(assertNotNull(assertNotNull(ed.pointing, "3D").eyeRay(aim), "eye"), meshOf(handle(doc))),
            "the handle really is under this click",
        )
        ed.setTool(Tools.SELECT)
        ed.click(aim)
        assertEquals(point.id, ed.selection?.id, "the point takes it, not the body behind: ${ed.statusHint}")
        assertTrue(ed.key("Tab"), "and the cycle steps on")
        assertEquals(handle(doc).id, ed.selection?.id, "…to the body the ray found")
    }
}
