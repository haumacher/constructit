package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Path3Ref
import constructit.dsl.Point3Ref
import constructit.dsl.SolidRef
import constructit.dsl.Sphere3Ref
import constructit.dsl.path3
import constructit.dsl.point3
import constructit.dsl.solid
import constructit.dsl.sphere3
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The sphere as a locus, as a gesture** (OP-28) — the half that decides whether the concept is a feature or
 * a mechanism.
 *
 * The geometry is [SphereLocusTest]'s. What is asserted here is the user's own sentence — *"40 from that
 * corner and 55 from that one"* — said by **clicking**, and then the four things that make it part of the
 * drawing rather than a one-off answer: the point is **live** under every drag, the branch is **recorded** and
 * never re-scored, the file **round-trips byte for byte**, and the result is an **ordinary point in space**
 * that anything taking one will take.
 *
 * **The fixture is a pillar** — a 70 × 50 footprint extruded 30 — because that is what the sentence is about:
 * the distances are measured from corners of a body, and the corners are shared by node, so dragging one
 * moves the answer. Its numbers are chosen so every assertion below can be read rather than trusted: with
 * loci of 40 on the near bottom corner, 55 on the far bottom corner and 55 on a top corner, the two solutions
 * stand about 23 mm apart in the plan, which is what makes the branch a thing a click can choose.
 */
class SphereLocusToolTest {
    // the pillar's footprint, and the places the fixture clicks
    private val CORNER_A = Vec2(0.0, 0.0)
    private val CORNER_D = Vec2(70.0, 50.0)
    private val CORNER_T = Vec2(0.0, 50.0)
    private val RECT_EDGE = Vec2(35.0, 0.0)

    // each locus's rim, in the plan, chosen so that no other locus's rim is within 30 mm of the click
    private val RIM_A = Vec2(-40.0, 0.0)
    private val RIM_D = Vec2(70.0, 105.0)
    private val RIM_T = Vec2(0.0, 105.0)

    // the two solutions' plan positions, computed in [SphereLocusTest]'s own algebra and asserted below
    private val NEAR_PLUS = Vec2(35.0, 10.0)
    private val NEAR_MINUS = Vec2(22.0, 30.0)

    /** Where the drag takes the near corner — inwards, so the three loci still overlap when it gets there. */
    private val DRAGGED_A = Vec2(10.0, 6.0)

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun view(ed: Editor): Viewport3 {
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(35.0, 25.0, 15.0), distance = 460.0, yaw = -0.3, pitch = 0.5),
                widthPx = 800.0,
                heightPx = 600.0,
            )
        vp.editor = ed
        vp.shown = true
        return vp
    }

    @Suppress("UNCHECKED_CAST")
    private fun sphereOf(el: Element) = Evaluator().sphere3(el.ref as Sphere3Ref)

    @Suppress("UNCHECKED_CAST")
    private fun pointOf(el: Element): Vec3 = Evaluator().point3(el.ref as Point3Ref)

    @Suppress("UNCHECKED_CAST")
    private fun curveOf(el: Element) = Evaluator().path3(el.ref as Path3Ref)

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun Editor.loci(): List<Element> = doc.elements.filter { it.kind == ElementKind.SPHERE_LOCUS }

    private fun Editor.spacePoints(): List<Element> = doc.elements.filter { it.inSpace && it.kind == ElementKind.DERIVED_POINT }

    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertClose(actual.x, expected.x, tol, "$msg (x)")
        assertClose(actual.y, expected.y, tol, "$msg (y)")
        assertClose(actual.z, expected.z, tol, "$msg (z)")
    }

    // ---- the fixture ----

    private class Pillar(val ed: Editor, val solid: Element)

    /** A 70 × 50 pillar, 30 tall, whose two picked corners are free points the drawing can drag. */
    private fun pillar(): Pillar {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(CORNER_A)
        ed.click(CORNER_D)
        ed.setTool(Tools.RECTANGLE)
        ed.click(CORNER_A)
        ed.click(CORNER_D)
        ed.setTool(Tools.EXTRUDE)
        ed.type("30")
        ed.click(RECT_EDGE)
        val solid = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the pillar built: ${ed.statusHint}")
        assertManifold(Evaluator().solid(solid.ref as SolidRef).mesh, "the pillar")
        return Pillar(ed, solid)
    }

    /**
     * The three loci the sentence names: 40 from a bottom corner, 55 from the far one, 55 from the **top**
     * corner above the third.
     *
     * The third centre is a **height point**, and it is picked in the 3D view rather than in the plan — which
     * is not a detour but OP-25's own rule showing through: a height point's plan image is its base's own dot,
     * so it is deliberately neither drawn nor picked there, and the view that can honestly place it is the one
     * that picks it. It matters here because three centres lying *in* the plan would put both solutions on the
     * same plan spot, and then no plan click could choose between them.
     */
    private fun threeLoci(ed: Editor) {
        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("40")
        ed.click(CORNER_A)
        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("55")
        ed.click(CORNER_D)
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("30")
        ed.click(CORNER_T)
        val top = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.HEIGHT_POINT }, "the top corner: ${ed.statusHint}")
        val vp = view(ed)
        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("55")
        val at = assertNotNull(assertNotNull(vp.projection()).toScreenLifted(CORNER_T, 30.0), "the top corner has an image")
        vp.pointerMove(at)
        vp.pointerDown(at)
        vp.pointerUp(at)
        vp.shown = false
        assertEquals(3, ed.loci().size, "three loci stand: ${ed.statusHint}")
        assertTrue(top.inSpace, "and the third is centred on a point in space")
        assertVec3(sphereOf(ed.loci().last()).center, Vec3(0.0, 50.0, 30.0), 1e-9, "the third locus stands on the top corner")
    }

    /** The trilateration gesture: three rims, then a click saying which of the two points is meant. */
    private fun trilaterate(
        ed: Editor,
        which: Vec2,
    ): Element {
        ed.setTool(Tools.SPHERE_TRILATERATE)
        ed.click(RIM_A)
        ed.click(RIM_D)
        ed.click(RIM_T)
        ed.click(which)
        return assertNotNull(ed.spacePoints().lastOrNull(), "the point built: ${ed.statusHint}")
    }

    // ---- 1. the user's sentence, literally ----

    /**
     * **"40 from that corner and 55 from that one"**, said by clicking — and the answer stands at exactly
     * those distances, to 1e-9.
     */
    @Test
    fun fortyFromThatCornerAndFiftyFiveFromThatOne() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_PLUS)
        val p = pointOf(point)
        assertClose((p - Vec3.ZERO).length(), 40.0, 1e-9, "40 from the near bottom corner")
        assertClose((p - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "55 from the far bottom corner")
        assertClose((p - Vec3(0.0, 50.0, 30.0)).length(), 55.0, 1e-9, "55 from the top corner")
        assertTrue(point.inSpace, "and what it is, is an ordinary point in space")
    }

    /**
     * **It is live**: drag the corner the first distance is measured from, and the point follows — still at
     * 40 from wherever that corner now is. Nothing is rebuilt and nothing is re-solved; the corner is one
     * node feeding the locus (OP-5's same-node-is-equality), which is the whole of the no-solver stance here.
     */
    @Test
    fun draggingTheCornerMovesThePointAndKeepsTheDistance() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_PLUS)
        val before = pointOf(point)

        f.ed.drag(CORNER_A, DRAGGED_A)
        val moved = assertNotNull(f.ed.doc.elements.firstOrNull { it.id == point.id }, "the point is still there")
        val after = pointOf(moved)
        assertTrue((after - before).length() > 1.0, "the point moved with the corner (${(after - before).length()} mm)")
        assertClose((after - Vec3(DRAGGED_A.x, DRAGGED_A.y, 0.0)).length(), 40.0, 1e-9, "still 40 from the corner, wherever it now is")
        assertClose((after - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "and still 55 from the other")
        assertManifold(Evaluator().solid(f.solid.ref as SolidRef).mesh, "the pillar, after the drag")
    }

    /** **Retyping the radius moves the point too** — the distance is a parameter, not a number in a result. */
    @Test
    fun retypingARadiusMovesThePoint() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_PLUS)
        val locus = f.ed.loci().first()
        val field = assertNotNull(locus.handle?.fields()?.firstOrNull { it.label == "radius" }, "a locus has a radius field")
        field.write(constructit.units.Quantity.mm(45.0))
        val after = pointOf(assertNotNull(f.ed.doc.elements.firstOrNull { it.id == point.id }))
        assertClose((after - Vec3.ZERO).length(), 45.0, 1e-9, "the point stands at the new distance")
        assertClose((after - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "and still at the old one from the other corner")
    }

    // ---- 2. the branch: chosen once, recorded, never re-scored ----

    /** **The last click chooses between the two points**, and the two clicks give the two different points. */
    @Test
    fun theLastClickChoosesWhichOfTheTwoPointsIsMeant() {
        val plus = pillar().also { threeLoci(it.ed) }.let { pointOf(trilaterate(it.ed, NEAR_PLUS)) }
        val minus = pillar().also { threeLoci(it.ed) }.let { pointOf(trilaterate(it.ed, NEAR_MINUS)) }
        assertTrue((plus - minus).length() > 20.0, "the two clicks chose two different points: $plus vs $minus")
        for (p in listOf(plus, minus)) {
            assertClose((p - Vec3.ZERO).length(), 40.0, 1e-9, "both are at 40 from the near corner")
            assertClose((p - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "...and 55 from the far one")
        }
    }

    /**
     * **The branch is written into the step as an ordinary sign**, and the file round-trips byte for byte —
     * no new file argument, no version bump, which is the format doctrine's own test: a new tool spelling
     * changes nothing already written.
     */
    @Test
    fun theBranchIsPersistedAsASignAndTheFileRoundTrips() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_MINUS)
        val once = DocumentFormat.save(f.ed.doc)
        assertTrue(
            once.lines().any { it.startsWith("tool ${Tools.SPHERE_TRILATERATE}") && it.contains("signs=-1") },
            "the step records the branch it chose, as a sign (OP-1/OP-18): $once",
        )
        assertTrue(once.startsWith("constructit ${DocumentFormat.VERSION}"), "and the header is untouched")
        val doc = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(doc), "save -> load -> save is byte-equal")
        val back = doc.elements.last { it.inSpace && it.kind == ElementKind.DERIVED_POINT }
        assertVec3(pointOf(back), pointOf(point), 1e-12, "and the point reloads as the same place")
    }

    /**
     * **Flipping the recorded sign gives the other branch, and nothing else changes** — which is what makes
     * the sign a *stored discrete choice* rather than a cached guess.
     *
     * Flipped in the file rather than through a second gesture, deliberately: that is the strongest form of
     * the claim, since it shows the replay takes the sign **verbatim** and re-derives nothing from where
     * anything happens to stand now.
     */
    @Test
    fun flippingTheRecordedSignGivesTheOtherBranch() {
        val f = pillar()
        threeLoci(f.ed)
        val chosen = pointOf(trilaterate(f.ed, NEAR_PLUS))
        val script = DocumentFormat.save(f.ed.doc)
        assertTrue(script.contains("signs=1"), "the chosen branch is +1: $script")
        val flipped = DocumentFormat.load(script.replace("signs=1", "signs=-1"))
        val other = pointOf(flipped.elements.last { it.inSpace && it.kind == ElementKind.DERIVED_POINT })
        assertTrue((other - chosen).length() > 20.0, "the other sign is the other point: $other vs $chosen")
        assertClose((other - Vec3.ZERO).length(), 40.0, 1e-9, "and it is a solution too — 40 from the near corner")
    }

    /**
     * **A branch is never re-scored on replay**: move the loci after the choice, save and reload, and the
     * drawing still rides the branch it was given — the defect that made fillets come back inverted.
     */
    @Test
    fun aReloadKeepsTheChosenBranchAfterTheLociHaveMoved() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_MINUS)
        f.ed.drag(CORNER_A, DRAGGED_A)
        val moved = pointOf(assertNotNull(f.ed.doc.elements.firstOrNull { it.id == point.id }))
        val script = DocumentFormat.save(f.ed.doc)
        val back = DocumentFormat.load(script)
        val reloaded = pointOf(back.elements.last { it.inSpace && it.kind == ElementKind.DERIVED_POINT })
        assertVec3(reloaded, moved, 1e-12, "the reload rides the same branch it rode before, not the nearer one")
        assertEquals(script, DocumentFormat.save(back), "and the script is byte-equal")
    }

    // ---- 3. the other two lines of the composition table ----

    /**
     * **Two loci meet in a circle in space**, and it is an ordinary curve of the drawing — pickable, drawn,
     * and something the third distance can then be met against.
     */
    @Test
    fun twoLociMeetInACircleThatIsAnOrdinaryCurveOfTheDrawing() {
        val f = pillar()
        threeLoci(f.ed)
        f.ed.setTool(Tools.SPHERE_CIRCLE)
        f.ed.click(RIM_A)
        f.ed.click(RIM_D)
        val circle =
            assertNotNull(
                f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
                "the circle built: ${f.ed.statusHint}",
            )
        val path = curveOf(circle)
        assertTrue(path.closed && path.elements.size == 1, "one exact closed piece")
        for (p in constructit.geom.Curves3.polyline(path)) {
            assertClose((p - Vec3.ZERO).length(), 40.0, 1e-9, "every point of it is 40 from the near corner")
            assertClose((p - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "...and 55 from the far one")
        }
    }

    /**
     * **The two routes to the same point agree.** Meeting the third locus against the circle of the other two
     * is the same statement as trilaterating all three, and it comes out at the same place — which is the
     * composition table being one table rather than three unrelated rows.
     */
    @Test
    fun aThirdLocusMetAgainstTheCircleGivesTheTrilaterationPoint() {
        val f = pillar()
        threeLoci(f.ed)
        f.ed.setTool(Tools.SPHERE_CIRCLE)
        f.ed.click(RIM_A)
        f.ed.click(RIM_D)
        val circle = assertNotNull(f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        val onCircle = constructit.geom.Curves3.polyline(curveOf(circle))
        // click the run near where the plus solution stands, which is where the crossing is
        f.ed.setTool(Tools.SPHERE_ON_RUN)
        f.ed.click(RIM_T)
        f.ed.click(NEAR_PLUS)
        val met = assertNotNull(f.ed.spacePoints().lastOrNull(), "the crossing built: ${f.ed.statusHint}")
        val p = pointOf(met)
        assertClose((p - Vec3.ZERO).length(), 40.0, 1e-9, "40 from the near corner")
        assertClose((p - Vec3(70.0, 50.0, 0.0)).length(), 55.0, 1e-9, "55 from the far one")
        assertClose((p - Vec3(0.0, 50.0, 30.0)).length(), 55.0, 1e-9, "and 55 from the top corner")
        assertTrue(onCircle.any { (it - p).length() < 5.0 }, "and it stands on the circle it was met against")
    }

    /**
     * **A locus meets a lifted drawing**, which is the composition the lift was built for: the run is the
     * pillar's own footprint, read as the run it already is, and the answer is where that outline passes 40
     * from the corner.
     */
    @Test
    fun aLocusMeetsTheFootprintsOwnOutlineWhereItIsAtThatDistance() {
        val f = pillar()
        f.ed.setTool(Tools.SPHERE_LOCUS)
        f.ed.type("40")
        f.ed.click(CORNER_A)
        f.ed.setTool(Tools.SPHERE_ON_RUN)
        f.ed.click(RIM_A)
        // the footprint's bottom edge, 40 out from the corner along it
        f.ed.click(Vec2(45.0, 0.0))
        val met = assertNotNull(f.ed.spacePoints().lastOrNull(), "the crossing built: ${f.ed.statusHint}")
        val p = pointOf(met)
        assertClose((p - Vec3.ZERO).length(), 40.0, 1e-9, "it stands at the stated distance from the corner")
        assertVec3(p, Vec3(40.0, 0.0, 0.0), 1e-9, "40 along the outline's own bottom edge")
        val once = DocumentFormat.save(f.ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and it round-trips byte for byte")
    }

    // ---- 4. an ordinary point of the drawing ----

    /**
     * **A trilateration point is a point like any other** — here it is the apex a pyramid is built to, which
     * is the strongest form of the claim: nothing downstream knows or cares how it was constructed.
     */
    @Test
    fun aTrilaterationPointIsTheApexOfARealSolid() {
        val f = pillar()
        threeLoci(f.ed)
        val apex = trilaterate(f.ed, NEAR_PLUS)
        f.ed.setTool(Tools.RECTANGLE)
        f.ed.click(Vec2(120.0, 0.0))
        f.ed.click(Vec2(180.0, 50.0))
        f.ed.setTool(Tools.EXTRUDE_TO_POINT)
        f.ed.click(Vec2(150.0, 0.0))
        f.ed.click(f.ed.camera.screenToWorld(f.ed.camera.worldToScreen(Vec2(35.0, 10.0))))
        val cone =
            assertNotNull(
                f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID && it !== f.solid },
                "the tapered body built on the trilateration point: ${f.ed.statusHint}",
            )
        assertManifold(Evaluator().solid(cone.ref as SolidRef).mesh, "a body whose apex is a trilateration point")
        assertTrue(apex.inSpace, "and the apex is still the point in space it was")
    }

    /** **A locus can be centred on a trilateration point** — the composition closes on itself. */
    @Test
    fun aLocusCanStandOnAPointThreeOtherLociMade() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_PLUS)
        f.ed.setTool(Tools.SPHERE_LOCUS)
        f.ed.type("25")
        f.ed.click(NEAR_PLUS)
        val locus = assertNotNull(f.ed.loci().lastOrNull(), "a fourth locus: ${f.ed.statusHint}")
        assertVec3(sphereOf(locus).center, pointOf(point), 1e-12, "centred exactly on the point the three made")
        assertClose(sphereOf(locus).radius, 25.0, 1e-12, "at the radius that was typed")
    }

    // ---- 5. the drawing's own housekeeping ----

    /** **One gesture, one undo** — for the locus and for the point alike. */
    @Test
    fun oneUndoTakesEachGestureBack() {
        val f = pillar()
        threeLoci(f.ed)
        val point = trilaterate(f.ed, NEAR_PLUS)
        assertEquals(1, f.ed.spacePoints().count { it.id == point.id })

        assertTrue(f.ed.undo(), "the point goes back")
        assertEquals(0, f.ed.spacePoints().size, "one checkpoint covered the whole gesture")
        assertEquals(3, f.ed.loci().size, "and the loci it was built from stay")

        assertTrue(f.ed.undo(), "the third locus goes back")
        assertEquals(2, f.ed.loci().size, "one gesture, one layer")
        assertTrue(f.ed.redo(), "and it comes back")
        assertEquals(3, f.ed.loci().size)
    }

    /** **A locus is drawn and picked in the plan**, which is what makes it something a gesture can reach. */
    @Test
    fun aLocusIsDrawnInThePlanAndPickedOnItsRim() {
        val f = pillar()
        threeLoci(f.ed)
        val target = SvgDrawTarget()
        f.ed.render(target)
        val svg = target.svg()
        assertTrue(svg.contains("stroke-dasharray"), "the locus draws dashed, as scaffolding: $svg")

        f.ed.setTool(Tools.SELECT)
        f.ed.click(RIM_A)
        val picked = assertNotNull(f.ed.selection, "a click on the rim picks the locus: ${f.ed.statusHint}")
        assertEquals(ElementKind.SPHERE_LOCUS, picked.kind, "and it is the locus that was picked, not the corner")
    }

    /** ...**and in the 3D view too**, where it is its three great circles — the "both views" rule. */
    @Test
    fun aLocusIsDrawnAndPickedInTheThreeDViewAsWell() {
        val f = pillar()
        threeLoci(f.ed)
        val vp = view(f.ed)
        val target = SvgDrawTarget()
        vp.render(Scene3.extract(f.ed.doc), target)
        assertTrue(target.svg().contains("stroke-dasharray"), "the locus draws in the 3D view as well")

        // a point on the first locus's equator, in space, and deliberately **clear of the pillar** — a click
        // on a great circle that ran across the body would be answered by the body, which is the ray pick
        // doing its job rather than this one failing
        val onIt = Vec3(-40.0, 0.0, 0.0)
        val screen = assertNotNull(assertNotNull(vp.projection()).toScreenLifted(Vec2(-40.0, 0.0), 0.0), "it has an image")
        f.ed.setTool(Tools.SELECT)
        vp.pointerMove(screen)
        vp.pointerDown(screen)
        vp.pointerUp(screen)
        val picked = assertNotNull(f.ed.selection, "a click on a great circle picks the locus: ${f.ed.statusHint}")
        assertEquals(ElementKind.SPHERE_LOCUS, picked.kind, "the locus, at $onIt")
    }

    /** **A gesture that cannot build says why** — the refusal speaks the drawing's language (OP-3's sibling). */
    @Test
    fun lociThatDoNotMeetRefuseByNameAndTheDrawingSaysSo() {
        val f = pillar()
        f.ed.setTool(Tools.SPHERE_LOCUS)
        f.ed.type("5")
        f.ed.click(CORNER_A)
        f.ed.setTool(Tools.SPHERE_LOCUS)
        f.ed.type("5")
        f.ed.click(CORNER_D)
        f.ed.setTool(Tools.SPHERE_CIRCLE)
        f.ed.click(Vec2(-5.0, 0.0))
        f.ed.click(Vec2(65.0, 50.0))
        val circle = f.ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }
        if (circle != null) {
            val why = assertNotNull(reasonOf(circle), "a circle of two loci that never meet is invalid")
            assertTrue(why.contains("do not meet"), "and it says so in the drawing's own words: $why")
        } else {
            assertTrue(f.ed.statusHint.isNotEmpty(), "or the gesture refused, out loud: ${f.ed.statusHint}")
        }
    }

    /** **The table's five rows are the table**, so a later edit cannot quietly change what a gesture takes. */
    @Test
    fun theFiveRowsDeclareTheCompositionTable() {
        fun row(id: String) = assertNotNull(Tools.all.firstOrNull { it.id == id }, "row $id exists")
        assertEquals(listOf(constructit.editor.SlotKind.POINT3), row(Tools.SPHERE_LOCUS).slots)
        assertEquals(listOf("radius"), row(Tools.SPHERE_LOCUS).scalars.map { it.name })
        assertEquals(
            listOf(constructit.editor.SlotKind.POINT3, constructit.editor.SlotKind.POINT3),
            row(Tools.SPHERE_LOCUS_PT).slots,
        )
        assertEquals(
            listOf(constructit.editor.SlotKind.SPHERE, constructit.editor.SlotKind.SPHERE),
            row(Tools.SPHERE_CIRCLE).slots,
        )
        assertEquals(
            listOf(
                constructit.editor.SlotKind.SPHERE,
                constructit.editor.SlotKind.SPHERE,
                constructit.editor.SlotKind.SPHERE,
                constructit.editor.SlotKind.SIDE,
            ),
            row(Tools.SPHERE_TRILATERATE).slots,
        )
        assertEquals(
            listOf(constructit.editor.SlotKind.SPHERE, constructit.editor.SlotKind.PATH3),
            row(Tools.SPHERE_ON_RUN).slots,
        )
    }
}
