package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A group carries the freedom it is built on — of every kind (OP-16 × OP-4 case b).**
 *
 * The user's drawing: a segment, a **rider** on it carrying a circle's centre, and the circle's rim point made
 * **relative** to that centre. Grouping the circle and the segment and placing the group was refused, and the
 * figure could not be moved. Two independent causes, both of them about the same blind spot — the closure
 * analysis and the placement capture only understood plain two-coordinate free points:
 *
 * - the create dialog could not offer the rider or the relative point, so they stayed outside the group and the
 *   free points the group *did* want to capture were "also used by" them (the conflict refusal);
 * - and even inside, a rider is **not** rigid under a frame while its parameter is anchored to the world
 *   (OP-20): moving the frame would slide it along its own carrier. Capturing it therefore means re-anchoring
 *   it to a point of that carrier — the stated-anchor form of OP-4 case (b) — which is what makes the whole
 *   figure rigid.
 *
 * The group default flipped with it: the closure is **ticked by default**, so a naive group is movable, and
 * what unticking costs is said at creation time rather than at a much later Place click.
 */
class GroupClosureTest {
    /** The reported file, verbatim — including the two duplicate `attach` steps it acquired. */
    private val fixture =
        """
constructit 1
point 33.5500136602691,50.982941738952256 -> e1
point -88,38 -> e2
tool segment pts=e1,e2 clicks=37.25,65.75;-85.25,29 -> e3
pointoncurve e3 -32.15366972477062,44.92889908256881 dofs=39.714768200735946mm -> e4
point -31.41684948491862,62.48999658370565 -> e5
tool circle pts=e4,e5 clicks=-32.25,45.25;-23.5,22.5 dofs=23.76582628534832mm;56.61051866120128deg -> e6
tool makerel els=e5,e4 clicks=-23.25,22.25;-32.75,38.25 dofs=23.76582628534832mm;56.61051866120128deg
attach e5 e6
attach e5 e6
tool makeabs els=e5 clicks=-36.471215000000036,65.94519818181818 dofs=23.76582628534832mm;56.61051866120128deg
tool makerel els=e5,e4 clicks=-36.01666954545458,66.1724709090909;-43.51666954545458,47.081561818181825 dofs=23.76582628534832mm;56.61051866120128deg
attach e5 e6
group "g1" els=e6,e3
""".trimStart()

    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
        steps: Int = 4,
    ) {
        pointerDown(camera.worldToScreen(from))
        for (i in 1..steps) pointerMove(camera.worldToScreen(from + (to - from) * (i.toDouble() / steps)))
        pointerUp(camera.worldToScreen(to))
    }

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /** The element the script calls `eN` (the document's own ids skip: a rider's parameter takes one too). */
    private fun el(
        ed: Editor,
        n: Int,
    ): Element = ed.doc.elements[n - 1]

    private fun radius(ed: Editor): Double =
        ((Evaluator().eval(el(ed, 6).ref.node) as EvalResult.Ok).value as CircleValue).circle.radius

    /** Every point of the drawing, for a rigidity check. */
    private fun points(ed: Editor): List<Vec2> = ed.doc.elements.filter { it.isPoint }.map { pos(it) }

    /** The fixture, with the recorded group dissolved so the dialog can make it again. */
    private fun loaded(): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        ed.doc.groups.firstOrNull()?.let { ed.ungroup(it) }
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** Select the circle and the segment, the way the user did: click one, Shift+click the other. */
    private fun selectCircleAndSegment(ed: Editor) {
        val centre = pos(el(ed, 4))
        val r = radius(ed)
        ed.click(centre - Vec2(0.0, r)) // the circle, at its lowest point (nothing else is there)
        ed.click(Vec2(9.24, 48.38), additive = true) // the segment, a fifth along from its first end
    }

    // ---- the reported failure, and what the closure now offers ----

    /**
     * The dialog offers **every** kind of freedom the members reach, labelled by kind, and ticks it — so the
     * group takes the rider and the relative point in, and the step records them.
     */
    @Test
    fun theDialogOffersEveryKindOfFreedomAndTicksIt() {
        val ed = loaded()
        selectCircleAndSegment(ed)
        assertEquals(2, ed.selectionCount, "the circle and the segment")
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        val labels = d.candidates.map { it.label }
        assertEquals(4, labels.size, "two free points, the rider, the relative point: $labels")
        // the labels name elements the way the file does (OP-18): what the panel says is what the script says
        assertTrue(labels.any { it.startsWith("e4 — slides on e3") }, "$labels")
        assertTrue(labels.any { it.startsWith("e5 — relative to e4") }, "$labels")
        assertTrue(d.candidates.all { it.checked }, "and the group default is to take them along")

        d.name = "fig"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertEquals(
            listOf(6, 3, 1, 2, 4, 5).map { el(ed, it).id },
            ed.doc.groupMembers(g).map { it.id },
            "the ticked candidates are members",
        )
        // …and the recorded step lists them, so the membership survives the file
        val line = DocumentFormat.save(ed.doc).lines().first { it.startsWith("group ") }
        assertEquals("group \"fig\" els=e6,e3,e1,e2,e4,e5", line)
        assertTrue(d.warnings.isEmpty(), "nothing is left outside, so there is nothing to warn about")
    }

    /**
     * **The acceptance case.** With everything ticked the group places, and moving its frame moves the whole
     * figure rigidly — the rider included, because the placement re-anchored it to its own carrier.
     */
    @Test
    fun theFixtureGroupPlacesAndMovesAsOneRigidFigure() {
        val ed = loaded()
        selectCircleAndSegment(ed)
        val before = points(ed)
        val r = radius(ed)
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "fig"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()

        // the dialog's "movable (with frame)" tick is on by default, so creating and placing are one
        // operation and one undo step (OP-16)
        assertTrue(g.placed, "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("re-anchored to their carrier"), "got: ${ed.statusHint}")
        assertEquals(before, points(ed), "placing moves nothing — it only changes how the figure is held")
        assertEquals(1, g.capturedRiders.size, "the rider is measured from its carrier's end now")

        // move the frame: everything shifts by the same vector, and the circle keeps its radius
        ed.selectGroup(g)
        val origin = ed.doc.frameValueOf(g)!!.origin
        ed.drag(origin, origin + Vec2(50.0, 20.0))
        assertTrue(ed.statusHint.startsWith("Moved"), "got: ${ed.statusHint}")
        for ((was, now) in before.zip(points(ed))) {
            assertClose((now - was).x, 50.0, 1e-9, "the figure moved as one")
            assertClose((now - was).y, 20.0, 1e-9)
        }
        assertClose(radius(ed), r, 1e-9, "…and nothing was stretched")
    }

    /** Inside the placed group, the rider still slides along its segment and the radius is still editable. */
    @Test
    fun insideThePlacedGroupTheRiderStillSlidesAndTheRadiusStillEdits() {
        val ed = placed()
        val rider = el(ed, 4)
        val carrier = pos(el(ed, 1)) to pos(el(ed, 2))
        val was = pos(rider)

        // reach the member alone (click, then click again), then drag it along its host
        ed.click(was)
        ed.click(was)
        val target = was + (carrier.second - carrier.first).normalized() * 20.0
        ed.drag(was, target)
        assertClose((pos(rider) - target).length(), 0.0, 1e-6, "it slid 20 mm along the segment")
        val d = (pos(rider) - carrier.first)
        val dir = (carrier.second - carrier.first).normalized()
        assertClose((d - dir * d.dot(dir)).length(), 0.0, 1e-9, "and stayed on it")

        // the rim point's distance is still its own degree of freedom (OP-4 case b) — reached alone, since a
        // click on a member of a placed group addresses the group's frame first (OP-16)
        ed.setTool(Tools.SELECT)
        ed.click(pos(el(ed, 5)))
        ed.click(pos(el(ed, 5)))
        assertEquals(listOf("distance", "angle"), ed.selectionFields().map { it.label })
        assertTrue(ed.writeSelectionField(0, 30.0))
        assertClose(radius(ed), 30.0, 1e-9, "typing the radius still works inside a placed group")
    }

    /** The placed figure survives the file: `save -> load -> save` byte-equal, and it still moves as one. */
    @Test
    fun theePlacedFixtureRoundTripsAndStillMoves() {
        val ed = placed()
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("place ") }, once)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be identical")

        val fresh = Editor()
        fresh.replaceDocument(reloaded)
        assertEquals(points(ed), points(fresh), "every position came back exactly")
        val g = fresh.doc.groups.single()
        assertEquals(1, g.capturedRiders.size, "…including the rider's stated anchor")
        val before = points(fresh)
        fresh.selectGroup(g)
        val origin = fresh.doc.frameValueOf(g)!!.origin
        fresh.drag(origin, origin + Vec2(-10.0, 5.0))
        for ((was, now) in before.zip(points(fresh))) {
            assertClose((now - was).x, -10.0, 1e-9)
            assertClose((now - was).y, 5.0, 1e-9)
        }
    }

    /** Unplacing gives back exactly what placing took: the rider is world-anchored again, where it stands. */
    @Test
    fun unplacingRestoresTheWorldAnchoredForm() {
        val ed = placed()
        val before = points(ed)
        assertTrue(ed.unplaceGroup(ed.doc.groups.single()))
        assertEquals(before, points(ed), "nothing moved")
        assertFalse(ed.doc.riderOf(el(ed, 4))!!.carrierRelative, "the rider owns its absolute parameter again")
        assertEquals(1, ed.doc.riderAnchors().size, "…so a gesture compensates it again (OP-20)")
        assertTrue(ed.doc.groups.single().capturedRiders.isEmpty())
    }

    /** The fixture, grouped with everything ticked and placed. */
    private fun placed(): Editor {
        val ed = loaded()
        selectCircleAndSegment(ed)
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "fig"
        assertTrue(ed.confirmCreate())
        assertTrue(ed.doc.groups.single().placed, "confirming gives the group its frame: ${ed.statusHint}")
        ed.clearSelection()
        return ed
    }

    // ---- the default tick, and the honest report when it is undone ----

    /** A naive group — select the visible geometry, group, place — is movable, because the closure came along. */
    @Test
    fun aNaiveGroupOfTheVisibleGeometryIsMovable() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0)) // the segment alone: it owns nothing of its own
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        assertEquals(2, d.candidates.size, "its two endpoints")
        assertTrue(d.candidates.all { it.checked })
        d.name = "bar"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertEquals(3, ed.doc.groupMembers(g).size, "the endpoints joined the segment")
        assertTrue(g.placed, "and it is movable the moment it exists: ${ed.statusHint}")
        ed.selectGroup(g)
        ed.drag(ed.doc.frameValueOf(g)!!.origin, ed.doc.frameValueOf(g)!!.origin + Vec2(10.0, 10.0))
        assertClose(pos(el(ed, 1)).x, 10.0, 1e-9, "and it moves")
        assertClose(pos(el(ed, 1)).y, 10.0, 1e-9)
    }

    /**
     * Unticking is still there — and what it costs is reported **at creation time**, in the words Place would
     * have used much later (OP-16's honest-failure rule).
     */
    @Test
    fun untickingAFreedomIsReportedWhenTheGroupIsMade() {
        val ed = loaded()
        selectCircleAndSegment(ed)
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        // leave the two free endpoints out: the rider and the relative point then still use them
        d.candidates.filter { !it.label.contains("—") }.forEach { it.checked = false }
        d.name = "half"
        assertTrue(ed.confirmCreate(), "the group is still made — the report is not a refusal")
        assertTrue(d.warnings.isNotEmpty(), "but it says what placement would refuse")
        assertTrue(d.warnings.any { it.contains("cannot move independently") }, "got: ${d.warnings}")
        assertTrue(d.warnings.any { it.contains("shared with the drawing outside") }, "got: ${d.warnings}")
        assertTrue(d.warnings.any { it.contains("e1") }, "naming what holds it: ${d.warnings}")
        // …and placing it is still allowed — the group does own the rider's freedom — so the frame tick placed
        // it, and the placement says, as it always did, which members the frame will not move (OP-16's
        // boundary-attachment rule). That sentence used to arrive far too late; now it arrives here.
        assertTrue(ed.doc.groups.single().placed, "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("will not follow it"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("e3"), "the segment is one of them: ${ed.statusHint}")
    }

    /**
     * The fourth kind, for completeness: a **ratio point** is a dimensionless share of its span, so a group
     * that carries the span's ends carries it rigidly with nothing to re-anchor — but it has to be *offered*,
     * or it stays outside and the endpoints it uses become a conflict (the same trap the rider fell into).
     */
    @Test
    fun aRatioPointIsOfferedAndIsRigidWithNothingToCapture() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.MIDPOINT)
        for (c in ".25") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(30.0, 0.0)) // the segment…
        ed.click(Vec2(15.0, 0.0), additive = true) // …and the ratio point on it
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        assertTrue(d.candidates.any { it.label.contains("a ratio along its span") }, d.candidates.map { it.label }.toString())
        d.name = "bar"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertTrue(g.placed, "got: ${ed.statusHint}")
        assertTrue(g.capturedRiders.isEmpty(), "a share of a span needs no re-anchoring")
        assertFalse(ed.statusHint.contains("will not follow"), "got: ${ed.statusHint}")

        // and the figure moves as one, the ratio point included
        val before = points(ed)
        ed.selectGroup(g)
        val origin = ed.doc.frameValueOf(g)!!.origin
        ed.drag(origin, origin + Vec2(12.0, -7.0))
        for ((was, now) in before.zip(points(ed))) {
            assertClose((now - was).x, 12.0, 1e-9)
            assertClose((now - was).y, -7.0, 1e-9)
        }
    }

    /**
     * **What a *turned* frame carries, stated.** The re-anchored rider is rigid under rotation too — its
     * distance is measured along the carrier, which turns with the group. A **polar** offset is not: its
     * bearing is stated in the world's axes, so the rim point keeps its direction from the centre while
     * everything else turns. The circle itself is unaffected (its radius is that distance), which is why the
     * limit is worth naming rather than guessing at: it moves a *marker* point, not the figure.
     */
    @Test
    fun aTurnedFrameCarriesTheRiderButKeepsThePolarBearing() {
        val ed = placed()
        val g = ed.doc.groups.single()
        val origin = ed.doc.frameValueOf(g)!!.origin
        val riderWas = pos(el(ed, 4))
        val bearingWas = (pos(el(ed, 5)) - pos(el(ed, 4))).angle()
        val r = radius(ed)

        // rotate the frame by 30° through its own field (OP-13: the frame is a handle like any other)
        ed.selectGroup(g)
        val angle = ed.selectionFields().indexOfFirst { it.label == "angle" }
        assertTrue(ed.writeSelectionField(angle, 30.0))

        val turn = { p: Vec2 ->
            val c = kotlin.math.cos(30.0 * kotlin.math.PI / 180.0)
            val s = kotlin.math.sin(30.0 * kotlin.math.PI / 180.0)
            val d = p - origin
            origin + Vec2(d.x * c - d.y * s, d.x * s + d.y * c)
        }
        assertClose((pos(el(ed, 4)) - turn(riderWas)).length(), 0.0, 1e-9, "the rider turned with its carrier")
        assertClose(radius(ed), r, 1e-9, "and the circle is unchanged")
        assertClose((pos(el(ed, 5)) - pos(el(ed, 4))).angle(), bearingWas, 1e-9, "the polar bearing stays in world axes")
    }

    /**
     * **A rider's position is restated as its own parameter, never as a rewritten click** — one rule for the
     * `pointoncurve` step and for the point-on-line/circle *tools* alike.
     *
     * The tool step used to rewrite its last click to the rider's current position, and a **turned** placed
     * group broke that outright: the click replays against the geometry as it stands *before* the placement
     * (unturned), so the rider was re-projected somewhere else and `save → load → save` was not byte-equal.
     * The click is a **choice** (which curve, which side) and stays verbatim; the position is state and rides
     * `dofs=`.
     */
    @Test
    fun aRidersPositionIsRestatedAsItsParameterAndSurvivesATurnedPlacement() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-60.0, 20.0))
        ed.click(Vec2(60.0, 20.0))
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(-10.0, 20.0))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-80.0, -20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(80.0, 60.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(80.0, 60.0)))
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        d.name = "arm"
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertTrue(g.placed, "got: ${ed.statusHint}")

        // the step states the rider's parameter and keeps the click it was placed by
        val line = DocumentFormat.save(ed.doc).lines().first { it.startsWith("tool ptonline") }
        assertTrue(line.contains("clicks=-10,20"), "the click is a choice and stays verbatim: $line")
        assertTrue(line.contains("dofs="), "and the position is state: $line")

        // turn the frame 90° — the case that used to break the file
        ed.selectGroup(g)
        val angle = ed.selectionFields().indexOfFirst { it.label == "angle" }
        assertTrue(ed.writeSelectionField(angle, 90.0))
        val once = DocumentFormat.save(ed.doc)
        assertTrue(!Regex("[0-9][Ee][-+]?[0-9]").containsMatchIn(once), "no exponent in the script: $once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "a turned figure replays byte-equal")
        val fresh = Editor()
        fresh.replaceDocument(DocumentFormat.load(once))
        assertEquals(points(ed), points(fresh), "and the rider is exactly where it was on the turned carrier")
    }

    // ---- journal hygiene ----

    /**
     * **A refused rewiring records nothing.** The reported file carried `attach e5 e6` twice — the drag magnet
     * offered an attach to the very circle the point defines, the release refused it (a relative point has no
     * coordinates left to bind), and the step was recorded all the same. Two drags, two junk steps.
     */
    @Test
    fun aDuplicateAttachCanNeverBeRecorded() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(20.0, 30.0))
        val free = el(ed, 4)
        val segment = el(ed, 3)
        assertTrue(ed.doc.attachToCurve(free, segment), "the first attach binds the point to the curve")
        val steps = ed.doc.journal.size
        assertFalse(ed.doc.attachToCurve(free, segment), "the second has nothing left to bind")
        assertEquals(steps, ed.doc.journal.size, "…so it records nothing")
        assertEquals(1, ed.doc.journal.count { it.kind == "attach" })
    }

    /** And the magnet no longer offers what the release would refuse: a bound point cannot connect. */
    @Test
    fun theMagnetDoesNotOfferAnAttachToABoundPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0)) // a circle through the free point e2
        ed.setTool(Tools.SELECT)
        assertTrue(ed.doc.makeRelative(el(ed, 2), el(ed, 1)), "e2 now follows e1")
        val steps = ed.doc.journal.size
        // drag the relative point round its own circle, ending on the circle it defines
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 30.0), steps = 6)
        assertEquals(steps, ed.doc.journal.size, "no attach step, and no weld step, was recorded")
        assertTrue(ed.doc.journal.none { it.kind == "attach" })
    }

    /**
     * `makerel → makeabs → makerel` records **exactly** its own three steps and nothing else — the whole
     * script, asserted, because the reported file's trail is what a leaking recorder looks like.
     */
    @Test
    fun aMakeRelativeRoundOfChangesRecordsExactlyItsOwnSteps() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 40.0))
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(30.0, 40.0))
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.MAKE_ABSOLUTE)
        ed.click(Vec2(30.0, 40.0))
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(Vec2(30.0, 40.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(
            // the free point's restated position carries the last bit of the polar round trip Make absolute
            // performed (x = 50 mm at 53.13°), which is arithmetic and not a leak
            """
constructit 2
point 0,0 -> e1
point 30.000000000000004,40 -> e2
tool makerel els=e2,e1 clicks=30,40;0,0
tool makeabs els=e2 clicks=30,40
tool makerel els=e2,e1 clicks=30,40;0,0 dofs=50mm;53.13010235415598deg
""".trimStart(),
            DocumentFormat.save(ed.doc),
            "three operations, three steps, and only the live one restates its offset",
        )
    }

    /**
     * **What the duplicate steps mean on load, decided.** They replay as refusals — the point they name is
     * already bound — so they change nothing, and because a refused rewiring now records nothing the file
     * **heals**: saving the reloaded drawing writes the script without them, with the same geometry.
     */
    @Test
    fun anOldFileWithDuplicateAttachStepsHealsOnLoad() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(fixture))
        assertTrue(ed.doc.journal.none { it.kind == "attach" }, "the refused attaches are not re-recorded")
        val saved = DocumentFormat.save(ed.doc)
        assertFalse(saved.contains("attach"), saved)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and the healed file is stable")
        // the geometry is the file's: the rim point still follows the centre, and the circle still fits
        assertNotNull(ed.doc.relativeOf(el(ed, 5)))
        assertClose(radius(ed), (pos(el(ed, 5)) - pos(el(ed, 4))).length(), 1e-9)
    }
}
