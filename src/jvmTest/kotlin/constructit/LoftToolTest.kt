package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.Quantity
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The **loft by clicking** (OP-17's third feature): *Extrude to point* — the pyramid and the cone — and
 * *Loft*, the general run of sections, which may lie on **different sketch planes**.
 *
 * What this file is for, beyond "the gesture works": the three things that make a feature part of this
 * design rather than an add-on. The apex is a real point element, so it drags and can be **shared**; the seam
 * is a discrete choice **scored once and persisted** in the step's `signs=`, so a reload is the solid the
 * user chose and not the one today's geometry would score; and a loft is a solid like any other — it goes
 * into a boolean chain, it draws a footprint hint in its own space, and its dependency rows name what it was
 * built from.
 *
 * Every solid here is checked with [assertManifold], and every gesture is checked through
 * save → load → save byte-equality.
 */
class LoftToolTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun Editor.featureOf(el: Element): Feature3 = Evaluator().solid(el.ref as SolidRef).feature

    private fun Editor.volumeOf(el: Element): Double = Geom3.volume(meshOf(el))

    /** save → load → save, byte-equal, with the document that came back — the persistence gate (OP-18). */
    private fun roundTrip(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        return back
    }

    /** A 100 × 100 rectangle in the plan, as a closed ortho path — the section everything below lofts. */
    private fun plan(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        return ed
    }

    /** The pyramid gesture: a height, the area's south leg, and where the apex stands. */
    private fun pyramid(apexAt: Vec2 = Vec2(50.0, 50.0)): Editor {
        val ed = plan()
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(apexAt)
        return ed
    }

    // ---- 1. the pyramid, by clicking ----

    /**
     * Two clicks and a number make the acceptance pyramid: **exactly** 300000 mm³, five planar faces,
     * watertight — and the file says so on reload.
     */
    @Test
    fun aPyramidByClicking() {
        val ed = pyramid()
        val solid = ed.solids().single()
        assertManifold(ed.meshOf(solid), "pyramid")
        assertClose(ed.volumeOf(solid), 300000.0, 1e-6, "a third of the box: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("exact"), "the status line names the honesty class: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("apex"), "…and what it is made of: ${ed.statusHint}")

        val back = roundTrip(ed)
        val reloaded = back.elements.single { it.kind == ElementKind.SOLID }
        @Suppress("UNCHECKED_CAST")
        assertClose(
            Geom3.volume(Evaluator().solid(reloaded.ref as SolidRef).mesh),
            300000.0,
            1e-6,
            "and it comes back the same solid",
        )
    }

    /** The apex is a point of the drawing: drag it and the pyramid leans, at the same volume (Cavalieri). */
    @Test
    fun draggingTheApexLeansThePyramid() {
        val ed = pyramid()
        val solid = ed.solids().single()
        val before = Geom3.bounds(ed.meshOf(solid))!!
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(50.0, 50.0), Vec2(180.0, 40.0))
        val after = Geom3.bounds(ed.meshOf(solid))!!
        assertTrue(after.second.x > before.second.x + 50.0, "the apex moved out over the plan, so the box grew")
        assertManifold(ed.meshOf(solid), "oblique pyramid")
        assertClose(ed.volumeOf(solid), 300000.0, 1e-6, "leaning it over changes no volume")
        roundTrip(ed)
    }

    /** Retyping the height in the panel is the feature's own degree of freedom (OP-13). */
    @Test
    fun retypingTheHeightGrowsThePyramid() {
        val ed = pyramid()
        val height = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "height" })
        ed.doc.setParameter(height, Quantity.mm(30.0))
        assertClose(ed.volumeOf(ed.solids().single()), 100000.0, 1e-6, "a third of the base times the new height")
        assertManifold(ed.meshOf(ed.solids().single()), "shorter pyramid")
    }

    /**
     * **Clicking an existing point as the apex shares it** — ordinary shared-node semantics (OP-5), so one
     * point drives the pyramid *and* whatever else was built on it: moving the point moves both.
     */
    @Test
    fun clickingAnExistingPointSharesTheApex() {
        val ed = plan()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(60.0, 40.0))
        // a circle on that same point, so the sharing is observable from two consumers at once
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(60.0, 40.0))
        ed.click(Vec2(70.0, 40.0))
        val points = ed.doc.elements.count { it.kind == ElementKind.POINT }

        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(60.0, 40.0))
        assertEquals(points, ed.doc.elements.count { it.kind == ElementKind.POINT }, "the apex click found the point instead of placing one")
        // ...and what it *did* add is the height point standing over it (OP-25) — one per gesture, whether
        // the base was clicked or placed, which is what makes the apex the same kind of thing either way
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.HEIGHT_POINT }, "one height point over the shared base")

        val solid = ed.solids().single()
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        val boxBefore = Geom3.bounds(ed.meshOf(solid))!!
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(60.0, 40.0), Vec2(150.0, 40.0))
        val boxAfter = Geom3.bounds(ed.meshOf(solid))!!
        assertTrue(boxAfter.second.x > boxBefore.second.x + 40.0, "the pyramid followed the point")
        val moved = (Evaluator().valueOf(circle.ref) as constructit.core.CircleValue).circle
        assertClose(moved.center.x, 150.0, 1e-6, "and so did the circle: one node, two consumers")
        assertManifold(ed.meshOf(solid), "pyramid on a shared apex")
        roundTrip(ed)
    }

    // ---- 2. the general loft, across sketch planes ----

    /**
     * **A frustum whose two sections live on two sketch planes**, entirely by clicking: the plan's square,
     * then a square drawn on a datum plane parallel to the plan 60 mm up, then Enter.
     *
     * The picks survive the change of space — declared by the tool ([Tools.LOFT]'s `crossSpace`) and said in
     * the status line — because a loft whose sections could not span planes could not build this at all. The
     * volume is the exact prismatoid: 60/3·(10000 + 3600 + √(10000·3600)) = 392000 mm³.
     */
    @Test
    fun aFrustumAcrossTwoSketchPlanes() {
        val ed = frustum()
        val solid = ed.solids().single()
        assertManifold(ed.meshOf(solid), "frustum across two planes")
        assertClose(ed.volumeOf(solid), 392000.0, 1e-6, "the prismatoid volume, exactly: ${ed.statusHint}")
        val feature = ed.featureOf(solid) as Feature3.Loft
        assertEquals(2, feature.sections.size, "two sections")
        assertTrue(!feature.approximated, "two polygons on two planes are still the exact class")
        roundTrip(ed)
    }

    /**
     * The plan square, a parallel datum plane 60 mm above it (0° and an offset — the parallel case), a 60 × 60
     * square drawn there, and the two lofted.
     */
    private fun frustum(): Editor {
        val ed = plan()
        // a datum plane parallel to the plan: 0° about the rectangle's south leg, offset 60 mm along its normal
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("60")
        ed.click(Vec2(30.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "the view switched to the datum plane: ${ed.statusHint}")
        // the datum's own coordinates: u along the south leg from the carrier's foot (the origin), v the plan's
        // in-plane perpendicular, so a square drawn at (20, 20)-(80, 80) sits over the middle of the plan
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(80.0, 80.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(50.0, 20.0)) // the top square's south leg, in this space
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "back to the plan for the other section")
        assertTrue(ed.statusHint.contains("kept"), "the tool says its picks survived the switch: ${ed.statusHint}")
        ed.click(Vec2(30.0, 0.0)) // the plan square's south leg
        ed.key("Enter")
        return ed
    }

    /** A loft's element belongs to its **first section's** space, which is where its footprint hint is drawn. */
    @Test
    fun theLoftIsAtHomeInItsFirstSectionsSpace() {
        val ed = frustum()
        val solid = ed.solids().single()
        val datum = ed.doc.spaces.last()
        assertEquals(datum.name, solid.space, "the first section was drawn on the datum plane, so that is its home")
        val footprint = (ed.featureOf(solid) as Feature3.Loft).footprint
        assertEquals(1, footprint.size, "the plan it shows is its first section")
        val box = footprint[0].outer.elements.map { Geom3.tessellateLoop(constructit.geom.Loop(listOf(it))) }.flatten()
        assertTrue(box.all { abs(it.x) <= 80.0 + 1e-9 && abs(it.y) <= 80.0 + 1e-9 }, "…in that section's own coordinates")
        // ...and the solid is listed whichever space is active, because a solid belongs to none (OP-17)
        assertTrue(ed.doc.listedElements().any { it === solid }, "the solid is listed on the datum plane")
        ed.setActiveSpace(Document.PLAN_SPACE)
        assertTrue(ed.doc.listedElements().any { it === solid }, "and in the plan")
    }

    // ---- 3. the seam: scored once, persisted, replayed verbatim ----

    /**
     * **The seam is a click and a persisted choice.** The same two sections, clicked at two different corners
     * of the upper one, give two different solids — and each comes back as *itself* on reload, because the
     * choice rides the step's `signs=` and is never re-scored (OP-1/OP-18).
     */
    @Test
    fun theSeamChoiceIsRecordedAndReplayedVerbatim() {
        // near a corner, deliberately: a click at a leg's midpoint is the same distance from both its ends,
        // and what the seam names is a corner
        val a = twistedFrustum(Vec2(30.0, 20.0))
        val b = twistedFrustum(Vec2(70.0, 20.0))
        val volA = a.volumeOf(a.solids().single())
        val volB = b.volumeOf(b.solids().single())
        assertManifold(a.meshOf(a.solids().single()), "seam A")
        assertManifold(b.meshOf(b.solids().single()), "seam B")
        assertTrue(abs(volA - volB) > 1000.0, "clicking a different corner is a different solid ($volA vs $volB)")

        val signsA = DocumentFormat.save(a.doc).lines().first { it.startsWith("tool loft") }
        val signsB = DocumentFormat.save(b.doc).lines().first { it.startsWith("tool loft") }
        assertTrue(signsA.contains("signs="), "the seam is written down: $signsA")
        assertTrue(signsA != signsB, "and the two choices are different steps")

        for ((ed, vol) in listOf(a to volA, b to volB)) {
            val back = roundTrip(ed)
            val reloaded = back.elements.single { it.kind == ElementKind.SOLID }
            @Suppress("UNCHECKED_CAST")
            assertClose(
                Geom3.volume(Evaluator().solid(reloaded.ref as SolidRef).mesh),
                vol,
                1e-6,
                "each replays as the solid its own click chose",
            )
        }
    }

    /**
     * The plan square lofted to a **rotated** square on a parallel plane, with the upper section clicked at
     * [corner] — which is what scores the correspondence.
     */
    private fun twistedFrustum(corner: Vec2): Editor {
        val ed = plan()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("70")
        ed.click(Vec2(30.0, 0.0))
        // a 60 x 60 square on the datum plane, its own legs the pieces a click can name
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 20.0))
        ed.click(Vec2(80.0, 80.0))
        ed.setTool(Tools.LOFT)
        ed.click(corner)
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        return ed
    }

    // ---- 4. a loft is a solid like any other ----

    /**
     * **A hole cut through the pyramid**, which is the sequential-feature rule reaching the newest solid class
     * without a line of code that knows what a loft is: a datum plane on the pyramid's own base edge, a circle
     * drawn there, *Cut* — and the cut's operand is the part's **tip**, so a second cut chains onto the first
     * instead of forking the model (OP-17).
     */
    @Test
    fun aCutThroughAPyramidChainsOntoIt() {
        assumeTrue(
            MeshBool.available,
            "a loft shares no axis with anything, so cutting one is the general engine's job (OP-9): ${MeshBool.status}",
        )
        val ed = pyramid()
        val pyramidSolid = ed.solids().single()
        val whole = ed.volumeOf(pyramidSolid)
        // a vertical datum plane on the base's south edge: the part it cuts is the pyramid (its hinge is part
        // of the construction the pyramid was built from)
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        assertEquals(pyramidSolid.id, assertNotNull(ed.activeSpace.anchor).id, "the datum knows which part it cuts")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("8")
        ed.click(Vec2(50.0, 12.0))
        ed.setTool(Tools.CUT)
        ed.type("120")
        ed.click(Vec2(58.0, 12.0))
        val cut = ed.solids().last()
        assertTrue(cut !== pyramidSolid, "the cut is a new solid")
        assertManifold(ed.meshOf(cut), "pyramid with a bore")
        assertTrue(ed.volumeOf(cut) < whole - 100.0, "material came out (${ed.volumeOf(cut)} of $whole)")

        // a second cut on the same plane chains onto the first: one body, two bores
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(50.0, 40.0))
        ed.setTool(Tools.CUT)
        ed.type("120")
        ed.click(Vec2(55.0, 40.0))
        val second = ed.solids().last()
        assertManifold(ed.meshOf(second), "pyramid with two bores")
        assertTrue(ed.volumeOf(second) < ed.volumeOf(cut) - 10.0, "the second bore took more material out again")
        val inputs = ed.doc.let { d -> constructit.editor.Dependencies.inputsOf(d, second).map { it.element } }
        assertTrue(inputs.any { it === cut }, "…and it was cut *from the first cut*, not from the plain pyramid")
        roundTrip(ed)
    }

    /**
     * The accessor a loft still does not have declines **by name** — there is no silent nothing (OP-3).
     *
     * *Section* wants a `Region` (one closed area, to extrude again), and a loft has no *analytic* one: that
     * cut stands. What no longer stands is the second half this test used to assert — that **sketch-on-face**
     * refuses a loft outright. A flat face of a loft is a face space since the section-inputs package (see
     * `SectionInputTest`), so the pick that used to be refused now opens one, and the refusal moved to where
     * it belongs: a **ruled** face.
     */
    @Test
    fun theAccessorsALoftDoesNotHaveDeclineOutLoud() {
        val ed = pyramid()
        ed.setTool(Tools.SECTION)
        ed.type("30")
        ed.click(Vec2(30.0, 0.0))
        // the section element exists but is invalid, and the reason says why a loft has none
        val section = ed.doc.elements.last()
        val result = Evaluator().eval(section.ref.node)
        assertTrue(result is constructit.core.EvalResult.Invalid, "a loft has no prismatic cross-section")
        assertTrue((result as constructit.core.EvalResult.Invalid).reason.contains("changes along the run"), result.reason)
        assertEquals(1, ed.solids().size, "and nothing was built by the refusal: ${ed.statusHint}")
    }

    /** The dependency rows name what a loft was built from: its section's legs and its apex point. */
    @Test
    fun theDependencyRowsNameTheSectionsAndTheApex() {
        val ed = pyramid()
        val solid = ed.solids().single()
        ed.selectElement(solid)
        val inputs = ed.selectionInputs()
        assertEquals(5, inputs.size, "four legs of the base plus the apex; got ${inputs.map { ed.doc.nameOf(it.element) }}")
        assertTrue(inputs.any { it.element.isPoint && it.role == "apex" }, "the apex is named by the tool's own slot word")
        assertTrue(inputs.count { it.element.kind == ElementKind.SEGMENT } == 4, "and the section's four legs are inputs")
    }

    // ---- 5. guides, by clicking ----

    /**
     * A **guide** added in the same gesture: an open curve among the picks is the rail the run follows, and the
     * loft says so. Drawn on a vertical datum plane through the section's own corner, from the base corner to
     * the top corner, bowed — so it passes through corresponding points, which is the rule.
     */
    @Test
    fun aGuideIsOneMorePickInTheSameGesture() {
        val ed = plan()
        // the upper section, on a parallel plane 100 mm up
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("100")
        ed.click(Vec2(30.0, 0.0))
        val top = ed.activeSpace.name
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        // a **vertical** plane on the plan square's south leg — u along +x, v out of the plan along +z — and an
        // arc drawn in it from the plan square's (0, 0) corner to the top square's, bowed out to x = −40
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        val guidePlane = ed.activeSpace.name
        ed.setTool(Tools.ARC_3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(-40.0, 50.0))
        ed.click(Vec2(0.0, 100.0))

        ed.setTool(Tools.LOFT)
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.click(Vec2(30.0, 0.0))
        ed.setActiveSpace(top)
        ed.click(Vec2(30.0, 0.0))
        ed.setActiveSpace(guidePlane)
        ed.click(Vec2(-40.0, 50.0))
        assertEquals(3, ed.toolPicks.size, "two sections and the guide, all picked: ${ed.statusHint}")
        ed.key("Enter")

        val solid = ed.solids().single()
        assertManifold(ed.meshOf(solid), "guided loft")
        val feature = ed.featureOf(solid) as Feature3.Loft
        assertEquals(1, feature.guides.size, "the open curve became a guide: ${ed.statusHint}")
        assertEquals(2, feature.sections.size, "…and the two areas stayed sections")
        assertTrue(feature.approximated, "a curved guide is the approximated class (OP-15)")
        assertTrue(ed.statusHint.contains("guide"), "the status line says the run is shaped: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("approximated"), "…and that it is approximated: ${ed.statusHint}")
        // the guide is honoured: the shell reaches out past the sections, where the arc bows — to within the
        // chord tolerance the guide is sampled at, which is what "approximated" means here (OP-15)
        assertClose(
            Geom3.bounds(ed.meshOf(solid))!!.first.x,
            -40.0,
            // The guide arc through (0,0),(-40,50),(0,100) has radius 51.25 mm — above the 20 mm crossover,
            // so it is sampled at its own scale-relative tolerance now (GitHub #13), and the shell reaches
            // its extreme to within one chord's sagitta, that effective tolerance rather than the flat 0.02.
            constructit.geom.GeomMath.effectiveTol(51.25),
            "the run bows out to the arc's own extreme",
        )
        roundTrip(ed)
    }

    /** A guide that does not pass through corresponding points leaves an invalid loft **that says why**. */
    @Test
    fun aGuideThatMissesItsCornerSaysSo() {
        val ed = plan()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("100")
        ed.click(Vec2(30.0, 0.0))
        val top = ed.activeSpace.name
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        // a vertical plane on the diagonal of the plan square, and a straight guide from one corner to the
        // *opposite* one — it meets both sections, but not at corresponding boundary parameters
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(50.0, 50.0))
        val diagonal = ed.activeSpace.name
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(141.42135623730951, 100.0))

        ed.setTool(Tools.LOFT)
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.click(Vec2(30.0, 0.0))
        ed.setActiveSpace(top)
        ed.click(Vec2(30.0, 0.0))
        ed.setActiveSpace(diagonal)
        ed.click(Vec2(70.0, 50.0))
        ed.key("Enter")

        val solid = ed.solids().single()
        val result = Evaluator().eval(solid.ref.node)
        assertTrue(result is constructit.core.EvalResult.Invalid, "the loft cannot honour that guide")
        assertTrue(ed.statusHint.contains("invalid right now"), "and the gesture said so at once: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("corresponding points"), "with the rule it broke: ${ed.statusHint}")
        roundTrip(ed)
    }

    // ---- 6. refusals speak ----

    /** One section and nothing else is refused **by name**, with the tool that does do it named. */
    @Test
    fun oneSectionIsRefusedWithTheAlternativeNamed() {
        val ed = plan()
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        assertEquals(0, ed.solids().size, "nothing was built")
        assertTrue(ed.statusHint.contains("at least"), "the refusal says what is missing: ${ed.statusHint}")
    }

    /** A pick that can be no part of a loft is refused, and the status line names the element. */
    @Test
    fun aPickThatIsNoPartOfALoftIsRefusedByName() {
        val ed = plan()
        // a dimension: an annotation, neither area, point nor curve
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(50.0, -20.0))
        val loft = ed.doc.loftSolid(listOf(ed.doc.elements.last { it.kind == ElementKind.DIMENSION }), listOf(Vec2(0.0, 0.0)))
        assertEquals(null, loft, "no loft is built from an annotation")
        assertTrue(assertNotNull(ed.doc.note).contains("neither an area"), "and the reason names it: ${ed.doc.note}")
    }

    // ---- 7. undo, redo, and the preview ----

    /** A loft is one gesture, hence one undo step; redo puts it back. */
    @Test
    fun undoOfALoftIsOneStepAndRedoRestoresIt() {
        val ed = frustum()
        val saved = DocumentFormat.save(ed.doc)
        assertEquals(1, ed.solids().size)
        assertTrue(ed.undo(), "undo")
        assertEquals(0, ed.solids().size, "the whole loft went, in one step")
        assertTrue(ed.redo(), "redo")
        assertEquals(1, ed.solids().size, "and came back")
        assertClose(ed.volumeOf(ed.solids().single()), 392000.0, 1e-6, "as the same solid")
        assertEquals(saved, DocumentFormat.save(ed.doc), "byte for byte the same script")
    }

    /**
     * The **seam is visible before the click**: hovering a second section draws the vertex the click would
     * choose and the rails it would pair — and hovering creates nothing (the preview rule).
     */
    @Test
    fun theSeamIsVisibleInThePreviewBeforeTheClick() {
        val ed = plan()
        // a second square in the *same* space, so the rails have an honest common picture
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, 120.0))
        ed.click(Vec2(80.0, 180.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(30.0, 0.0))
        val nodes = ed.doc.cx.nodesCreated
        val elements = ed.doc.elements.size
        val script = DocumentFormat.save(ed.doc)

        ed.hover(Vec2(50.0, 120.0))
        val shapes = ed.previewShapes
        assertTrue(shapes.size > 1, "the hover draws the seam and its rails")
        assertTrue(shapes.any { it is constructit.editor.PreviewShape.Dot }, "the seam vertex is marked")
        assertTrue(shapes.count { it is constructit.editor.PreviewShape.Seg } >= 8, "and the correspondence is drawn as rails")
        assertEquals(nodes, ed.doc.cx.nodesCreated, "hovering created no node")
        assertEquals(elements, ed.doc.elements.size, "…and no element")
        assertEquals(script, DocumentFormat.save(ed.doc), "…and changed no step")
    }

    // ---- 8. composition with what was already there ----

    /**
     * **Deleting the apex takes the loft with it**, and deleting the loft leaves its sections standing — the
     * ordinary dependency rule (a delete replays the surviving script), with no case for the newest feature.
     */
    @Test
    fun deletingTheApexTakesTheLoftWithIt() {
        val ed = pyramid()
        ed.setTool(Tools.SELECT)
        ed.selectElement(ed.doc.elements.last { it.kind == ElementKind.POINT })
        assertTrue(ed.deleteSelection(), "the apex goes")
        assertEquals(0, ed.solids().size, "…and the pyramid with it, since it was built on it")
        assertTrue(ed.doc.orthoPaths.single().closed, "the base is still there")

        val again = pyramid()
        again.setTool(Tools.SELECT)
        again.selectElement(again.solids().single())
        assertTrue(again.deleteSelection(), "the solid goes")
        assertEquals(0, again.solids().size)
        assertEquals(1, again.doc.elements.count { it.isPoint && it.kind == ElementKind.POINT }, "the apex stays: nothing was built on it")
        assertEquals(DocumentFormat.save(again.doc), DocumentFormat.save(DocumentFormat.load(DocumentFormat.save(again.doc))))
    }

    /**
     * **A bolt circle of cones**: the loft rides OP-23's orbit rule with no line of code that knows about
     * either. A circular pattern of points, one circle built on a member (which fans), then *Extrude to point*
     * on that circle with the member as its apex — and the gesture is stamped round the ring.
     */
    @Test
    fun aPatternOfMembersFansTheConeGesture() {
        val ed = Editor()
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.count = 4
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("10")
        ed.click(Vec2(60.0, 0.0))
        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "one circle per member: ${ed.statusHint}")

        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("30")
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        assertEquals(4, ed.solids().size, "one cone per member: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("copies round pattern"), "and the orbit says so: ${ed.statusHint}")
        val volumes = ed.solids().map { ed.volumeOf(it) }
        for ((i, s) in ed.solids().withIndex()) {
            assertManifold(ed.meshOf(s), "cone ${i + 1}")
            assertClose(volumes[i], volumes[0], 1e-6, "every copy is the same cone")
        }
        val centres = ed.solids().map { Geom3.bounds(ed.meshOf(it))!!.let { b -> (b.first + b.second) * 0.5 } }
        assertEquals(4, centres.distinctBy { "${it.x},${it.y}" }.size, "…in four different places round the ring")
        roundTrip(ed)
    }

    /**
     * ...and where the orbit rule declines, it **says so**: a pyramid whose base is a plain rectangle and whose
     * apex is a pattern member is not fanned, because the base would have to travel with the copies and does
     * not. One pyramid is built, and the status line names the input that stopped the fan (OP-23's rule, not
     * the loft's — which is the point of checking it here).
     */
    @Test
    fun anUnpatternedBaseDeclinesTheFanOutLoud() {
        val ed = plan()
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.count = 4
        ed.click(Vec2(50.0, 50.0))
        ed.click(Vec2(120.0, 50.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("40")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(120.0, 50.0))
        assertEquals(1, ed.solids().size, "one pyramid, not four")
        assertTrue(ed.statusHint.contains("not replicated"), "and the refusal is spoken: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("outside the pattern"), "…naming why: ${ed.statusHint}")
        assertManifold(ed.meshOf(ed.solids().single()), "the one pyramid")
        roundTrip(ed)
    }

    /**
     * Two sections in one space is an ordinary loft too — a plain prism-like run — which is what makes the
     * cross-space case an *extension* of the gesture rather than the only way to use it.
     */
    @Test
    fun twoSectionsInOneSpaceLoftAsWell() {
        val ed = plan()
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("40")
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.LOFT)
        ed.click(Vec2(30.0, 0.0))
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.click(Vec2(30.0, 0.0))
        ed.key("Enter")
        val solid = ed.solids().single()
        assertManifold(ed.meshOf(solid), "prismatic loft")
        assertClose(ed.volumeOf(solid), 400000.0, 1e-6, "a square run 40 mm with no change of section is a box")
    }
}
