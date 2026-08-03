package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.Path3Ref
import constructit.dsl.path3
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.LinearDimension
import constructit.editor.PlanePerspective
import constructit.editor.SlotKind
import constructit.editor.ToolDef
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Handedness
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Dimension
import constructit.units.Quantity
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A point slot either shares a point or states one** (session 50) — the user's rule, made the law of the
 * slot kinds rather than of any tool: *"every tool that requires points as inputs should either select an
 * existing point or create a new free point"*.
 *
 * The report was the helix: armed on an empty sheet, its two clicks hit nothing pickable and built nothing,
 * because `POINT3` (and the element-valued point slot the dimension used) resolved through the pick alone
 * while `POINT` went through the snap-aware placement. What decides is now *why* a slot names a point — an
 * **input** the result is built from places; a **subject** the tool changes cannot, and says so — and these
 * tests are that split, asked of every tool it touches: the reported gesture on an empty sheet, the snap
 * that still applies inside the new slots, the sharing that still happens when a click lands on a point, the
 * file, the one undo, the 3D view, and each subject slot refusing in its own words.
 */
class PointSlotPlacementTest {
    private val wPx = 800.0
    private val hPx = 600.0

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

    private fun Editor.hover(world: Vec2) = pointerMove(camera.worldToScreen(world))

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.points() = doc.elements.filter { it.isPoint }

    private fun Editor.curves() = doc.elements.filter { it.kind == ElementKind.SPACE_CURVE }

    @Suppress("UNCHECKED_CAST")
    private fun helixOf(el: Element): Curve3Element.Helix3 =
        Evaluator().path3(el.ref as Path3Ref).elements.single() as Curve3Element.Helix3

    private fun posOf(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "a point value")

    private fun assertVec(
        actual: Vec3,
        expected: Vec3,
        msg: String,
        tol: Double = 1e-14,
    ) {
        assertTrue((actual - expected).length() <= tol, "$msg (was $actual, wanted $expected)")
    }

    private fun roundTrip(ed: Editor): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save must be byte-equal")
        return once
    }

    // ---- 1. the report itself ----

    /**
     * **The reported gesture, on an empty sheet**: arm *Helix (centre, start point)*, type a pitch and a turn
     * count, click twice where there is nothing — and a coil stands there, of the radius the two clicks state
     * and beginning at the second of them. Both handednesses, because the pair of ids is the pair of tools.
     */
    @Test
    fun theHelixBuildsFromTwoClicksOnAnEmptySheet() {
        for (left in listOf(false, true)) {
            val ed = Editor()
            ed.setTool(if (left) Tools.HELIX_PT_LEFT else Tools.HELIX_PT)
            ed.type("12")
            ed.type("3")
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(30.0, 0.0))
            val el = assertNotNull(ed.curves().lastOrNull(), "a coil was built: ${ed.statusHint}")
            val h = helixOf(el)
            assertClose(h.radius, 30.0, 1e-9, "the radius is what the two placed points say")
            assertEquals(12.0, h.pitch, "the typed pitch")
            assertEquals(3.0, h.turns, "the typed turn count")
            assertEquals(if (left) Handedness.LEFT else Handedness.RIGHT, h.hand)
            assertVec(h.at(0.0), Vec3(30.0, 0.0, 0.0), "and t = 0 is the start point that was placed")
            assertVec(h.origin, Vec3(0.0, 0.0, 0.0), "standing on the centre that was placed")
            assertEquals(2, ed.points().size, "exactly the two points the gesture stated — no more")
            for (p in ed.points()) assertEquals(ElementKind.POINT, p.kind, "and each is an ordinary free point")
        }
    }

    /** …and the typed spelling the same way: one empty click is a centre, and the coil stands on it. */
    @Test
    fun theTypedHelixBuildsFromOneClickOnAnEmptySheet() {
        val ed = Editor()
        ed.setTool(Tools.HELIX)
        ed.type("20")
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(5.0, -5.0))
        val h = helixOf(assertNotNull(ed.curves().lastOrNull(), "a coil: ${ed.statusHint}"))
        assertClose(h.radius, 20.0, 1e-9, "the typed radius")
        assertVec(h.origin, Vec3(5.0, -5.0, 0.0), "about the point the click stated")
        assertEquals(1, ed.points().size, "one point placed")
    }

    // ---- 2. a curve through points that were not there ----

    /**
     * **A curve through three empty clicks**, straight and smooth alike — and the points it placed are
     * ordinary free points, so dragging one moves the curve. That last half is the parenting rule the old
     * "existing points only" reading was defending: it survives, because a placed point *is* in the drawing.
     */
    @Test
    fun aCurveRunsThroughThreePointsItStated() {
        for (tool in listOf(Tools.CURVE3, Tools.CURVE3_SMOOTH)) {
            val ed = Editor()
            ed.setTool(tool)
            ed.click(Vec2(0.0, 0.0))
            ed.click(Vec2(40.0, 20.0))
            ed.click(Vec2(80.0, -10.0))
            ed.key("Enter")
            val el = assertNotNull(ed.curves().lastOrNull(), "$tool built a curve: ${ed.statusHint}")
            assertEquals(3, ed.points().size, "through three points it placed")
            val before = Evaluator().path3(el.ref as Path3Ref)
            assertVec(before.pointAt(0), Vec3(0.0, 0.0, 0.0), "the first point is where the first click was")

            ed.drag(Vec2(40.0, 20.0), Vec2(40.0, 70.0))
            val after = Evaluator().path3(el.ref as Path3Ref)
            assertVec(after.pointAt(1), Vec3(40.0, 70.0, 0.0), "$tool: the placed point moved…")
            assertTrue(
                (before.pointAt(1) - after.pointAt(1)).length() > 40.0,
                "$tool: …and the curve followed it, because it is an input and not a copy",
            )
        }
    }

    /** Knot [i] of a path in space: piece i's start, and the last piece's end for the final one. */
    private fun constructit.geom.Path3.pointAt(i: Int): Vec3 =
        elements.getOrNull(i)?.start ?: assertNotNull(elements.last().end)

    // ---- 3. mixed: one shared, one stated ----

    /**
     * **One click on a point, one on empty space.** The first shares the node — drag that point and the coil
     * follows, which is what a shared node *means* — and the second is an ordinary free point of the drawing.
     * The two halves of the rule in one gesture.
     */
    @Test
    fun aPickSharesAndAMissStatesInTheSameGesture() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        val centre = ed.points().single()

        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0)) // the existing point
        ed.click(Vec2(0.0, 25.0)) // empty space
        val el = assertNotNull(ed.curves().lastOrNull(), "the coil: ${ed.statusHint}")
        assertEquals(2, ed.points().size, "one point was already there, one was placed")
        assertVec(helixOf(el).at(0.0), Vec3(0.0, 25.0, 0.0), "it begins at the placed point")

        // the shared node: drag the point that was clicked, and the coil goes with it
        ed.drag(Vec2(0.0, 0.0), Vec2(-30.0, 0.0))
        assertVec(helixOf(el).origin, Vec3(-30.0, 0.0, 0.0), "the coil stands on the point it shares")
        assertClose(helixOf(el).radius, Vec2(30.0, 25.0).length(), 1e-9, "and the radius is what the two now say")

        // …and the placed one is free too, so it drags as well
        ed.drag(Vec2(0.0, 25.0), Vec2(0.0, 45.0))
        assertVec(helixOf(el).at(0.0), Vec3(0.0, 45.0, 0.0), "the placed point is an ordinary free point")
        assertClose((posOf(centre) - Vec2(-30.0, 0.0)).length(), 0.0, 1e-9, "and the shared one stayed where it was put")
    }

    // ---- 4. the snap applies inside the new slots ----

    /**
     * **A placing point slot goes through the one snap-aware route**, so everything a `POINT` slot's click can
     * make, these can: a click on a curve becomes a **rider** that stays on it as the curve moves.
     */
    @Test
    fun aClickOnACurveInAPoint3SlotMakesARiderThatStaysOnIt() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.type("40")
        ed.click(Vec2(0.0, 0.0))
        val circle = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE }, "${ed.statusHint}")

        ed.setTool(Tools.HELIX_PT)
        ed.type("8")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0)) // the circle's centre point
        ed.click(Vec2(40.0, 0.0)) // on the circle: a rider
        val el = assertNotNull(ed.curves().lastOrNull(), "the coil: ${ed.statusHint}")
        assertClose(helixOf(el).radius, 40.0, 1e-9, "the start point rides the circle")
        val rider = ed.doc.elements.last { it.isPoint }
        assertTrue(rider.kind != ElementKind.POINT, "the click on the curve made a rider, not a free point: ${rider.kind}")

        // grow the circle: the rider goes with it, and so does the coil's radius and phase
        ed.doc.setParameter(assertNotNull(ed.doc.scalars.first { it.name == "radius" }), Quantity.mm(60.0))
        assertClose(helixOf(el).radius, 60.0, 1e-9, "the rider stayed on the circle, so the coil grew with it")
        assertNotNull(circle)
    }

    /**
     * …and a click on a **section corner** materializes the section input (OP-17) rather than dropping a free
     * point on top of it: the coil is then anchored on the solid, and follows when the solid changes.
     */
    @Test
    fun aClickOnASectionCornerInAPoint3SlotMaterializesTheInput() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("45")
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "a datum plane cutting the pyramid: ${ed.statusHint}")

        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(50.0, 50.0)) // empty space in the section: a free point
        ed.click(Vec2(25.0, 25.0)) // a corner of the section square
        val el = assertNotNull(ed.curves().lastOrNull(), "the coil: ${ed.statusHint}")
        assertClose(helixOf(el).radius, Vec2(25.0, 25.0).length(), 1e-9, "the start point is the corner itself")
        val saved = roundTrip(ed)
        assertTrue(saved.contains("sectioninput"), "the corner is recorded as a section input: $saved")
        assertTrue(saved.contains("point 50,50"), "and the empty click as an ordinary point: $saved")

        // the section moves when the datum's height does, and the coil's start point moves with it
        val h = assertNotNull(ed.doc.spaces.last().offset, "the datum's offset is a parameter")
        ed.doc.setParameter(h, Quantity.mm(30.0))
        val two3 = 200.0 / 3.0
        val lo = (100.0 - two3) / 2.0
        assertClose(
            helixOf(el).radius,
            Vec2(50.0 - lo, 50.0 - lo).length(),
            1e-9,
            "the anchored start point followed its corner",
        )
    }

    /**
     * **The snap marker is shown wherever the click would place** — including on a repeating tool after its
     * first pick, where the slot lookup used to run off the end of the list and switch the marker off while
     * the gesture went on placing.
     */
    @Test
    fun theSnapMarkerIsShownForEverySlotThatPlaces() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))

        for (tool in listOf(Tools.HELIX_PT, Tools.CURVE3, Tools.DIM_LINEAR)) {
            val e = Editor()
            e.setTool(Tools.POINT)
            e.click(Vec2(0.0, 0.0))
            e.click(Vec2(60.0, 0.0))
            e.setTool(tool)
            if (tool == Tools.HELIX_PT) {
                e.type("10")
                e.type("2")
            }
            e.hover(Vec2(0.0, 0.0))
            assertNotNull(e.snapHint, "$tool: the first slot marks the point it would share")
            e.click(Vec2(0.0, 0.0))
            e.hover(Vec2(60.0, 0.0))
            assertNotNull(e.snapHint, "$tool: and so does the second, repeating slot included")
        }
    }

    // ---- 5. the file: every placing tool, generically ----

    /**
     * **Every tool with a placing point slot, driven from empty space and round-tripped** — a generic sweep
     * rather than a case per tool, because the rule is the slot kind's and a new row must inherit it.
     *
     * What each run asserts is the whole of "recorded, never discovered" (OP-18): the placed points are
     * ordinary `point` steps *before* the tool's own step, a reload builds the same elements with the same
     * names, and the second save is byte-equal to the first.
     */
    @Test
    fun everyToolWithAPlacingPointSlotBuildsFromEmptySpaceAndRoundTrips() {
        val covered = ArrayList<String>()
        for (def in Tools.all.filter { d -> d.slots.any { Tools.placesPointElement(it) } }) {
            if (def.slots.any { it !in POINTISH }) continue // no such row today; a new one gets its own test
            covered.add(def.id)
            val ed = Editor()
            // one point that already exists, for the subject slots that demand one
            ed.setTool(Tools.POINT)
            ed.click(SUBJECT_SPOT)
            for (slot in def.scalars) {
                ed.activeScalar =
                    ed.doc.newParameter(
                        "s${ed.doc.scalars.size}",
                        if (slot.dim == Dimension.NONE) Quantity.number(2.0) else Quantity.mm(10.0),
                    )
            }
            ed.setTool(def.id)
            runFromEmptySpace(ed, def)
            assertTrue(
                ed.doc.elements.size > 1,
                "${def.id} built something from empty clicks: ${ed.statusHint}",
            )
            assertTrue(ed.doc.steplessElements().isEmpty(), "${def.id}: every element it made has a step")
            val once = roundTrip(ed)
            val back = DocumentFormat.load(once)
            assertEquals(
                ed.doc.elements.map { "${ed.doc.nameOf(it)}:${it.kind}" },
                back.elements.map { "${back.nameOf(it)}:${it.kind}" },
                "${def.id}: the placed points come back as the same points with the same names",
            )
            assertEquals(
                ed.doc.elements.count { it.kind == ElementKind.POINT },
                back.elements.count { it.kind == ElementKind.POINT },
                "${def.id}: a replay re-runs the recorded point steps and places nothing new",
            )
        }
        assertTrue(
            covered.containsAll(listOf(Tools.CURVE3, Tools.CURVE3_SMOOTH, Tools.HELIX_PT, Tools.HELIX, Tools.DIM_LINEAR, Tools.MAKE_RELATIVE)),
            "the sweep reached the rows the report is about: $covered",
        )
    }

    /** Slot kinds the generic sweep above knows how to click. */
    private val POINTISH =
        setOf(SlotKind.INPUT_POINT, SlotKind.POINT3, SlotKind.EXISTING_POINT, SlotKind.SIDE)

    private val SUBJECT_SPOT = Vec2(-120.0, -120.0)
    private val FREE_SPOTS = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 60.0), Vec2(0.0, 60.0))

    /** Run [def] with every placing slot filled by an *empty* click, in the order the palette collects them. */
    private fun runFromEmptySpace(
        ed: Editor,
        def: ToolDef,
    ) {
        if (def.repeating) {
            for (p in FREE_SPOTS.take(maxOf(def.minPicks, 2))) ed.click(p)
            ed.key("Enter")
            return
        }
        var free = 0
        for (slot in def.slots) {
            when (slot) {
                SlotKind.EXISTING_POINT -> ed.click(SUBJECT_SPOT)
                else -> ed.click(FREE_SPOTS[free++ % FREE_SPOTS.size])
            }
        }
    }

    // ---- 6. one undo ----

    /**
     * **One undo takes back the gesture and the points it placed** — and that is not a rule of its own: it is
     * exactly what a `POINT` slot has always done, asserted here side by side so the two can never drift.
     *
     * The substrate is why: a checkpoint is the saved script, and the placed points were recorded *after* the
     * last one, so restoring it takes the whole gesture back. Points that were already in the drawing before
     * the tool was armed have a checkpoint of their own and stay.
     */
    @Test
    fun oneUndoTakesBackTheGestureAndTheseSlotsMatchThePointSlots() {
        // the POINT-slot baseline: a segment over two empty clicks
        val base = Editor()
        base.setTool(Tools.SEGMENT)
        base.click(Vec2(0.0, 0.0))
        base.click(Vec2(50.0, 0.0))
        assertEquals(3, base.doc.elements.size, "two placed points and the segment")
        assertTrue(base.undo())
        assertEquals(0, base.doc.elements.size, "one undo takes back the segment and both points it placed")

        // …and the helix, whose slots now place the same way
        val ed = Editor()
        ed.setTool(Tools.HELIX_PT)
        ed.type("12")
        ed.type("3")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertEquals(3, ed.doc.elements.size, "two placed points and the coil")
        assertTrue(ed.undo())
        assertEquals(0, ed.doc.elements.size, "the same one undo, for the same reason")
        assertTrue(ed.redo())
        assertEquals(3, ed.doc.elements.size, "and it all comes back together")

        // a point that was already there is not part of the gesture, and stays
        val kept = Editor()
        kept.setTool(Tools.POINT)
        kept.click(Vec2(0.0, 0.0))
        kept.setTool(Tools.HELIX_PT)
        kept.type("12")
        kept.click(Vec2(0.0, 0.0))
        kept.click(Vec2(30.0, 0.0))
        assertEquals(3, kept.doc.elements.size)
        assertTrue(kept.undo())
        assertEquals(1, kept.doc.elements.size, "the point drawn before the gesture stays")
        assertEquals(ElementKind.POINT, kept.doc.elements.single().kind)
    }

    // ---- 7. the subject slots refuse, each in its own words ----

    /**
     * **A subject slot that misses says what it needs and that nothing was placed** — in the tool's own role
     * word, and ending in the help, where each of these rows now states *why* it cannot place. The generic
     * "that click hit nothing pickable" would be the wrong answer here precisely because every other point
     * slot would have placed something.
     */
    @Test
    fun theSubjectSlotsRefuseByNameAndPlaceNothing() {
        val cases =
            listOf(
                Tools.JOIN to "kept point",
                Tools.MAKE_ABSOLUTE to "point",
                Tools.UNLINK to "point",
                Tools.MAKE_RELATIVE to "point",
                Tools.TANGENT_AT to "point on circle",
            )
        for ((id, role) in cases) {
            val ed = Editor()
            ed.setTool(id)
            ed.click(Vec2(10.0, 10.0))
            val label = assertNotNull(ed.doc.toolDef(id)).label
            assertEquals(0, ed.doc.elements.size, "$id placed nothing: ${ed.statusHint}")
            assertTrue(ed.statusHint.startsWith("$label needs an existing $role"), "$id says what it needs: ${ed.statusHint}")
            assertTrue(ed.statusHint.contains("nothing was placed"), "$id says nothing happened: ${ed.statusHint}")
        }

        // Join's *second* slot refuses too, in its own word, with the first pick still standing
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.JOIN)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 80.0))
        assertEquals(1, ed.doc.elements.size, "still just the one point")
        assertTrue(ed.statusHint.contains("existing welded point"), "the second slot's own word: ${ed.statusHint}")
    }

    /**
     * **Space origin stays existing-only, and the reason is self-reference.** The origin it moves is the
     * frame the whole space's coordinates are read in — so a point placed *in that space* would be stated in
     * coordinates the anchoring is about to redefine, and would move with the frame it was defining. There is
     * no fixed point of that to place; the tool wants a corner of a **section**, whose position is the
     * solid's and not the space's.
     */
    @Test
    fun spaceOriginRefusesToPlaceBecauseAPointHereWouldMoveWithTheFrameItDefines() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("0")
        ed.type("20")
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "a datum plane cutting the block: ${ed.statusHint}")

        val before = ed.doc.elements.size
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(50.0, 50.0)) // empty space inside the section
        assertEquals(before, ed.doc.elements.size, "nothing was placed: ${ed.statusHint}")
        assertTrue(ed.statusHint.startsWith("Space origin needs an existing anchor corner"), "${ed.statusHint}")
        assertTrue(ed.statusHint.contains("move with the frame it was defining"), "and why: ${ed.statusHint}")

        // …and a point drawn on this plane is refused by the document for the same reason, which is the half
        // that makes it self-reference rather than a missing feature
        ed.setTool(Tools.POINT)
        ed.click(Vec2(50.0, 50.0))
        val drawn = ed.doc.elements.last { it.kind == ElementKind.POINT }
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(50.0, 50.0))
        assertTrue(ed.statusHint.contains("moves with the frame it would define"), "the document's own words: ${ed.statusHint}")
        assertNotNull(drawn)

        // the corner, which is the input it does want, works — so this is a refusal and not a dead tool
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.doc.spaces.last().originAnchor != null, "the corner anchored the origin: ${ed.statusHint}")
    }

    // ---- 8. the 3D view ----

    /**
     * **In the 3D view an empty click still has one honest position: where the pointer's ray meets the active
     * plane** — which is where the drawing shows the cursor, and where a `POINT` slot has placed since
     * edit-in-3D. So a `POINT3` slot places there too, rather than refusing.
     *
     * The two cases where there is no such position are already answered upstream, in the one door every
     * gesture comes through (`Editor.enter`): a ray that never meets the plane is refused with a note and the
     * click does not happen at all, and a plane so edge-on that the tolerance has to be clamped says so.
     * Nothing here has to guess.
     */
    @Test
    fun aPlacingSlotInTheThreeDViewPlacesWhereTheRayMeetsThePlane() {
        val ed = Editor()
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val proj = PlanePerspective(plane, Camera3(target = Vec3(20.0, 20.0, 0.0), distance = 300.0, yaw = 0.7, pitch = 0.6), wPx, hPx)
        ed.pointing = proj

        fun clickAt(at: Vec2) {
            val s = assertNotNull(proj.toScreen(at), "the plane point is on screen")
            ed.pointerMove(s)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }

        ed.setTool(Tools.HELIX_PT)
        ed.type("12")
        ed.type("2")
        clickAt(Vec2(0.0, 0.0))
        clickAt(Vec2(30.0, 0.0))
        val el = assertNotNull(ed.curves().lastOrNull(), "the coil was built in the 3D view: ${ed.statusHint}")
        val h = helixOf(el)
        assertVec(h.origin, Vec3(0.0, 0.0, 0.0), "the centre landed where the ray met the plane", 1e-6)
        assertVec(h.at(0.0), Vec3(30.0, 0.0, 0.0), "and so did the start point", 1e-6)
        assertEquals(2, ed.points().size, "two ordinary points, on the active plane")

        // a ray that meets the plane nowhere is refused by the one door, and places nothing
        val edge = PlanePerspective(plane, Camera3(target = Vec3.ZERO, distance = 300.0, pitch = 0.0), wPx, hPx)
        val ed2 = Editor()
        ed2.pointing = edge
        ed2.setTool(Tools.HELIX_PT)
        ed2.type("12")
        ed2.pointerDown(Vec2(wPx / 2.0, 0.0))
        ed2.pointerUp(Vec2(wPx / 2.0, 0.0))
        assertEquals(0, ed2.doc.elements.count { it.kind == ElementKind.POINT }, "no point where the ray never landed: ${ed2.statusHint}")
    }

    // ---- 9. the dimension ----

    /**
     * **A linear dimension over two empty clicks** measures between two points it stated, and follows them
     * when they move — which is the whole reason a dimension's ends are *inputs* rather than a pair of
     * numbers copied out of the click.
     */
    @Test
    fun aDimensionMeasuresBetweenTwoPointsItStated() {
        val ed = Editor()
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(50.0, 20.0))
        assertEquals(2, ed.points().size, "two points were placed by the two clicks")
        val dim = assertNotNull(ed.doc.elements.lastOrNull { it.annotation is LinearDimension }, "a dimension: ${ed.statusHint}")
        val ev = Evaluator()
        assertClose(assertNotNull((ev.valueOf(dim.ref) as? constructit.core.ScalarValue)?.q?.mm), 100.0, 1e-9, "it measures the span")

        ed.drag(Vec2(100.0, 0.0), Vec2(140.0, 0.0))
        assertClose(
            assertNotNull((Evaluator().valueOf(dim.ref) as? constructit.core.ScalarValue)?.q?.mm),
            140.0,
            1e-9,
            "and it follows the point it was stated from",
        )
        roundTrip(ed)
    }

    /** *Make relative*'s anchor is an input too: an empty click there states the point to follow. */
    @Test
    fun makeRelativeStatesItsAnchorWhereThereIsNone() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 0.0))
        val follower = ed.points().single()

        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(20.0, 0.0)) // the subject: it must already exist
        ed.click(Vec2(0.0, 0.0)) // the anchor: placed here
        assertEquals(2, ed.points().size, "the anchor was stated: ${ed.statusHint}")
        val anchor = ed.doc.elements.first { it.isPoint && it !== follower }

        ed.drag(Vec2(0.0, 0.0), Vec2(0.0, 50.0))
        assertClose(posOf(follower).y, 50.0, 1e-9, "the subject followed the anchor it was given")
        assertClose(posOf(follower).x, 20.0, 1e-9, "keeping its offset")
        assertNotNull(anchor)
        roundTrip(ed)
    }

    // ---- 10. the doctrine, read off the table ----

    /**
     * **The split itself**, asserted as a table so a new row cannot quietly pick the wrong side: every point
     * slot in `Tools.all` is either one that places or one that names a subject, and nothing is both or
     * neither.
     */
    @Test
    fun everyPointSlotIsEitherAnInputOrASubject() {
        val pointSlots =
            setOf(
                SlotKind.PLACE_POINT,
                SlotKind.POINT,
                SlotKind.INPUT_POINT,
                SlotKind.EXISTING_POINT,
                SlotKind.ON_CIRCLE_POINT,
                SlotKind.POINT3,
            )
        for (kind in SlotKind.entries) {
            if (kind !in pointSlots) {
                assertTrue(!Tools.placesPoint(kind) && !Tools.needsExistingPoint(kind), "$kind is not a point slot")
                continue
            }
            assertTrue(
                Tools.placesPoint(kind) != Tools.needsExistingPoint(kind),
                "$kind must be exactly one of input (places) and subject (existing only)",
            )
        }
        // …and the rows that stay existing-only are the ones that *change* the point they name (plus the
        // rider slot, whose point has to already lie on a circle — a new free point never would)
        val subjects =
            Tools.all.filter { d -> d.slots.any { Tools.needsExistingPoint(it) } }.map { it.id }.toSet()
        assertEquals(
            setOf(Tools.JOIN, Tools.MAKE_RELATIVE, Tools.MAKE_ABSOLUTE, Tools.UNLINK, Tools.SPACE_ORIGIN, Tools.TANGENT_AT),
            subjects,
            "the subject rows, and no others",
        )
    }

    /** A round number the sweep leans on: the plan's own u is +x, so a 90° bearing is +y. */
    @Test
    fun theBearingConventionIsUnchanged() {
        val ed = Editor()
        ed.setTool(Tools.HELIX_PT)
        ed.type("10")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 25.0))
        val h = helixOf(assertNotNull(ed.curves().lastOrNull(), "${ed.statusHint}"))
        assertVec(h.at(0.0), Vec3(25.0 * kotlin.math.cos(PI / 2), 25.0, 0.0), "90° is +y", 1e-9)
    }
}
