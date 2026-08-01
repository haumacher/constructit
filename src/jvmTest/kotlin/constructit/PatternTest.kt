package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Arg
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Pattern
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Vec2
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Patterns as orbits** (OP-23): a pattern is a *rule* — a reference member, what it is repeated about,
 * and a count — and every later gesture whose inputs touch its members is replicated by index shift.
 *
 * The assertions that matter are the consequences, not the positions:
 * - one segment gesture makes every side and one fillet gesture rounds every corner, because a gesture is
 *   replicated rather than its result copied;
 * - adjacent copies **share the ring's point nodes**, so there is no seam to mend and the outline tracer
 *   crosses copy boundaries with no new machinery;
 * - a non-invariant input refuses to fan, and says which input it was; Alt declines deliberately;
 * - the whole orbit of a gesture is one step, hence one undo;
 * - a count change re-stamps: the ring is rebuilt and every gesture re-runs at the new count.
 */
class PatternTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Digits into the armed tool's scalar slot, then Enter — the typed-value flow (OP-13). */
    private fun Editor.type(v: String) {
        v.forEach { key(it.toString()) }
        key("Enter")
    }

    private fun ring(
        k: Int,
        n: Int = 6,
        r: Double = 100.0,
    ): Vec2 = Vec2(r * cos(2 * PI * k / n), r * sin(2 * PI * k / n))

    /** A point on the segment between two ring members, [t] of the way along. */
    private fun along(
        a: Vec2,
        b: Vec2,
        t: Double,
    ): Vec2 = a + (b - a) * t

    private fun segments(doc: Document) = doc.elements.filter { it.kind == ElementKind.SEGMENT }

    private fun arcs(doc: Document) = doc.elements.filter { it.kind == ElementKind.ARC }

    private fun assertRoundTrips(
        ed: Editor,
        where: String,
    ): Document {
        val once = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal ($where)")
        assertEquals(ed.doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same elements ($where)")
        return reloaded
    }

    /** A hexagonal pattern: centre at the origin, reference vertex at (100, 0), six instances. */
    private fun hexPattern(n: Int = 6): Editor {
        val ed = Editor()
        ed.count = n
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0)) // the centre
        ed.click(ring(0, n)) // the reference member
        return ed
    }

    /** …plus the one segment gesture that makes every side. */
    private fun hexSides(n: Int = 6): Editor {
        val ed = hexPattern(n)
        ed.setTool(Tools.SEGMENT)
        ed.click(ring(0, n))
        ed.click(ring(1, n))
        return ed
    }

    /** …plus the one fillet gesture that rounds every corner, radius 20. */
    private fun roundedHex(n: Int = 6): Editor {
        val ed = hexSides(n)
        ed.activeScalar = ed.doc.newParameter("fillet", 20.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(along(ring(0, n), ring(1, n), 0.7))
        ed.click(along(ring(1, n), ring(2, n), 0.3))
        return ed
    }

    private fun patternOf(ed: Editor): Pattern = assertNotNull(ed.doc.patterns.singleOrNull(), "one pattern")

    // ---- 1. the ring ----

    @Test
    fun aPatternRepeatsItsReferencePointAndNothingElse() {
        val ed = hexPattern()
        val p = patternOf(ed)
        assertEquals(6, p.count)
        assertEquals(6, p.ring.size, "the reference member plus five copies")
        val ev = Evaluator()
        for (k in 0 until 6) {
            val at = assertNotNull((ev.valueOf(p.ring.members[k].ref) as? PointValue)?.p, "member $k")
            assertClose(at.x, ring(k).x, tol = 1e-9, msg = "member $k x")
            assertClose(at.y, ring(k).y, tol = 1e-9, msg = "member $k y")
        }
        // the clicked points plus five derived ones, and nothing more: a pattern is a rule, not a drawing
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.POINT })
        assertEquals(5, ed.doc.elements.count { it.kind == ElementKind.DERIVED_POINT })
        assertTrue(ed.statusHint.contains("Pattern P1"), ed.statusHint)
        assertRoundTrips(ed, "the ring")
    }

    // ---- 2. the rule: one gesture, every copy ----

    @Test
    fun oneSegmentGestureMakesEverySide() {
        val ed = hexSides()
        assertEquals(6, segments(ed.doc).size, "one click pair, six sides")
        assertTrue(ed.statusHint.contains("6 copies round pattern P1"), ed.statusHint)
        val ev = Evaluator()
        val sides = segments(ed.doc).map { assertNotNull((ev.valueOf(it.ref) as? SegmentValue)?.seg) }
        // side j runs from member j to member j+1, mod 6 — the closing side included
        for (j in 0 until 6) {
            assertClose((sides[j].a - ring(j)).length(), 0.0, tol = 1e-9, msg = "side $j starts at member $j")
            assertClose((sides[j].b - ring((j + 1) % 6)).length(), 0.0, tol = 1e-9, msg = "side $j ends at member ${j + 1}")
        }
        assertRoundTrips(ed, "the sides")
    }

    /**
     * **The seam that is not there.** The copies are built *on* the shared members, not transformed off copy
     * 0, so adjacent sides reference the very same point node — sharing a node *is* coincidence (OP-5), and
     * nothing has to be welded, mended or tolerance-matched afterwards.
     */
    @Test
    fun adjacentSidesShareTheRingsPointNodes() {
        val ed = hexSides()
        val p = patternOf(ed)
        val sides = segments(ed.doc)
        for (j in 0 until 6) {
            val shared = p.ring.members[(j + 1) % 6].ref.node
            assertTrue(sides[j].ref.node.inputs.any { it === shared }, "side $j must end on the shared member node")
            assertTrue(sides[(j + 1) % 6].ref.node.inputs.any { it === shared }, "side ${j + 1} must start on it")
        }
        // and moving the reference member moves both sides that meet there, with nothing recomputed twice
        ed.setTool(Tools.SELECT)
        val from = ring(0)
        ed.pointerDown(ed.camera.worldToScreen(from))
        ed.pointerMove(ed.camera.worldToScreen(from + Vec2(10.0, 0.0)))
        ed.pointerUp(ed.camera.worldToScreen(from + Vec2(10.0, 0.0)))
        val ev = Evaluator()
        val moved = segments(ed.doc).map { assertNotNull((ev.valueOf(it.ref) as? SegmentValue)?.seg) }
        assertClose(moved[0].a.x, 110.0, tol = 1e-9, msg = "the first side follows the dragged member")
        assertClose(moved[5].b.x, 110.0, tol = 1e-9, msg = "and so does the closing side")
        // the drag is state on the reference point's own step; a gesture's recorded rule is untouched by it
        assertRoundTrips(ed, "after dragging the reference member")
    }

    @Test
    fun oneFilletGestureRoundsEveryCorner() {
        val ed = roundedHex()
        assertEquals(6, arcs(ed.doc).size, "one fillet, six roundings")
        // every rounding shares the one radius parameter, so retyping it re-rounds all of them (OP-5)
        val radius = ed.doc.scalars.single { it.name == "fillet" }
        ed.doc.setParameter(radius, 30.0.mm)
        val ev = Evaluator()
        val radii = arcs(ed.doc).mapNotNull { (ev.valueOf(it.ref) as? constructit.core.ArcValue)?.arc?.radius }
        assertEquals(6, radii.size)
        for (r in radii) assertClose(r, 30.0, tol = 1e-9, msg = "every rounding follows the one parameter")
        assertRoundTrips(ed, "the roundings")
    }

    /**
     * The acceptance model: a rounded hexagon from scratch, traced in **two clicks** and extruded
     * watertight. The tracer crosses every copy boundary and every fillet joint with no new machinery —
     * which is the point of building the copies on shared members (OP-14, OP-23).
     */
    @Test
    fun theRoundedPolygonTracesInTwoClicksAndExtrudesWatertight() {
        val ed = roundedHex()
        ed.setTool(Tools.OUTLINE)
        ed.click(along(ring(0), ring(1), 0.5)) // a side
        ed.click(filletMid(1)) // the rounding it hands over to

        val outline = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE }, "two clicks must close it: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Followed"), ed.statusHint)
        assertEquals(12, stepPieces(ed.doc), "six sides and six roundings, all recorded")
        assertTrue(outline.isResult)

        ed.activeScalar = ed.doc.newParameter("depth", 5.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(along(ring(0), ring(1), 0.5))
        assertManifold(ed.mesh(), "extruded rounded hexagon")
        assertRoundTrips(ed, "outline and solid")
    }

    // ---- 3. what does not replicate ----

    @Test
    fun aGestureReachingOutsideThePatternIsNotReplicatedAndSaysWhy() {
        val ed = hexPattern()
        ed.setTool(Tools.SEGMENT)
        ed.click(ring(0))
        ed.click(Vec2(300.0, 300.0)) // a point of its own, which the pattern's turn does not fix
        assertEquals(1, segments(ed.doc).size, "one segment, not six")
        assertTrue(ed.statusHint.startsWith("not replicated:"), ed.statusHint)
        assertTrue(ed.statusHint.contains("is outside the pattern"), ed.statusHint)
        assertRoundTrips(ed, "an unreplicated gesture")
    }

    /** The centre *is* invariant under a rotation, so a spoke to it is a legitimate replicated gesture. */
    @Test
    fun theCentreIsInvariantSoASpokeFansOut() {
        val ed = hexPattern()
        ed.setTool(Tools.SEGMENT)
        ed.click(ring(0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(6, segments(ed.doc).size, "six spokes")
        assertRoundTrips(ed, "spokes")
    }

    /** Alt has always meant *leave the model as I put it*; declining the orbit is that sentence again. */
    @Test
    fun altKeepsAFeatureAOneOff() {
        val ed = hexSides()
        ed.activeScalar = ed.doc.newParameter("keyway", 15.0.mm)
        ed.snapEnabled = false // Alt
        ed.setTool(Tools.FILLET)
        ed.click(along(ring(0), ring(1), 0.7))
        ed.click(along(ring(1), ring(2), 0.3))
        assertEquals(1, arcs(ed.doc).size, "one rounding only")
        assertTrue(ed.statusHint.contains("Alt keeps it a one-off"), ed.statusHint)
        assertRoundTrips(ed, "a one-off")
    }

    // ---- 4. one gesture, one undo ----

    @Test
    fun undoRemovesTheWholeOrbitOfOneGesture() {
        val ed = hexSides()
        assertEquals(6, segments(ed.doc).size)
        assertTrue(ed.undo(), "the segment gesture is one undo step")
        assertEquals(0, segments(ed.doc).size, "the whole orbit goes")
        assertEquals(6, patternOf(ed).ring.size, "the pattern itself stays")
        assertTrue(ed.redo())
        assertEquals(6, segments(ed.doc).size)
    }

    /** Deleting the pattern takes every gesture that rode it — a rule nothing can outlive. */
    @Test
    fun deletingTheRingTakesEveryGestureWithIt() {
        val ed = roundedHex()
        ed.setTool(Tools.SELECT)
        ed.click(ring(1)) // a derived ring member: its creating step is the pattern
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(0, segments(ed.doc).size)
        assertEquals(0, arcs(ed.doc).size)
        assertEquals(0, ed.doc.patterns.size)
        assertRoundTrips(ed, "after deleting the pattern")
    }

    // ---- 5. the count change ----

    @Test
    fun theCountRestampsEverythingThatRidesThePattern() {
        val ed = roundedHex()
        ed.setTool(Tools.OUTLINE)
        ed.click(along(ring(0), ring(1), 0.5))
        ed.click(filletMid(1))
        ed.activeScalar = ed.doc.newParameter("depth", 5.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(along(ring(0), ring(1), 0.5))
        assertManifold(ed.mesh(), "hexagon")

        // 6 -> 8: eight sides, eight roundings, and the traced boundary re-follows itself
        assertTrue(ed.setPatternCount(patternOf(ed), 8), ed.statusHint)
        assertEquals(8, patternOf(ed).ring.size)
        assertEquals(8, segments(ed.doc).size, "eight sides")
        assertEquals(8, arcs(ed.doc).size, "eight roundings")
        assertEquals(16, stepPieces(ed.doc), "the outline re-traced itself round sixteen pieces")
        assertManifold(ed.mesh(), "octagon")
        assertRoundTrips(ed, "count 8")

        // ...and down again to five
        assertTrue(ed.setPatternCount(patternOf(ed), 5), ed.statusHint)
        assertEquals(5, segments(ed.doc).size)
        assertEquals(5, arcs(ed.doc).size)
        assertEquals(10, stepPieces(ed.doc))
        assertManifold(ed.mesh(), "pentagon")
        assertRoundTrips(ed, "count 5")
    }

    /** A one-off survives a re-count when the member it is anchored to survives — and says so when not. */
    @Test
    fun aOneOffSurvivesTheCountChangeWhileItsAnchorDoes() {
        val ed = hexSides()
        ed.activeScalar = ed.doc.newParameter("keyway", 15.0.mm)
        ed.snapEnabled = false
        ed.setTool(Tools.FILLET)
        ed.click(along(ring(0), ring(1), 0.7)) // anchored on side 0, which every count has
        ed.click(along(ring(1), ring(2), 0.3))
        ed.snapEnabled = true
        assertEquals(1, arcs(ed.doc).size)

        assertTrue(ed.setPatternCount(patternOf(ed), 8), ed.statusHint)
        assertEquals(8, segments(ed.doc).size)
        assertEquals(1, arcs(ed.doc).size, "the one-off stays single")
        assertTrue(ed.setPatternCount(patternOf(ed), 4), ed.statusHint)
        assertEquals(4, segments(ed.doc).size)
        assertEquals(1, arcs(ed.doc).size, "still single")
        assertRoundTrips(ed, "a one-off through two re-counts")
    }

    @Test
    fun aOneOffAnchoredOnAMemberACountDoesNotHaveIsDroppedAndNamed() {
        val ed = hexSides()
        ed.activeScalar = ed.doc.newParameter("keyway", 15.0.mm)
        ed.snapEnabled = false
        ed.setTool(Tools.FILLET)
        ed.click(along(ring(4), ring(5), 0.7)) // anchored on side 4
        ed.click(along(ring(5), ring(0), 0.3))
        ed.snapEnabled = true
        assertEquals(1, arcs(ed.doc).size)

        assertTrue(ed.setPatternCount(patternOf(ed), 3), ed.statusHint)
        assertEquals(3, segments(ed.doc).size)
        assertEquals(0, arcs(ed.doc).size, "its anchor is gone, so it is dropped rather than moved")
        assertTrue(ed.statusHint.contains("dropped"), ed.statusHint)
        assertRoundTrips(ed, "after a loss")
    }

    /** The one thing mod-n cannot absorb: a gesture spanning more members than the new count has. */
    @Test
    fun aCountTooSmallForAGesturesSpanIsRefusedWithTheReason() {
        val ed = hexPattern()
        ed.setTool(Tools.SEGMENT)
        ed.click(ring(0))
        ed.click(ring(3)) // a long diagonal: offsets 0 and 3
        assertEquals(6, segments(ed.doc).size)
        val p = patternOf(ed)
        assertNotNull(ed.doc.restampRefusal(p, 3), "three members cannot carry a 0-to-3 gesture")
        assertFalse(ed.setPatternCount(p, 3))
        assertTrue(ed.statusHint.contains("can't re-stamp"), ed.statusHint)
        assertEquals(6, segments(ed.doc).size, "and nothing happened")
        assertTrue(ed.setPatternCount(p, 7), ed.statusHint)
        assertEquals(7, segments(ed.doc).size)
    }

    // ---- 5b. a subtractive orbit is a CHAIN, not a fan ----

    /**
     * The mechanical payoff (OP-17 × OP-23): a ring of circles on a solid's face and **one** Cut gives a bolt
     * circle of pockets **in one body** — because a face-part tool's base operand is re-resolved per copy, so
     * copy *k* subtracts from what copy *k*-1 left instead of forking back onto the plate.
     *
     * The volume is the assertion that tells a chain from a fan: four forks would leave a tip missing one
     * pocket. And the chain re-stamps like any other gesture, because what the step records is the rule.
     */
    @Test
    fun aFacePartOrbitChainsItsCutsAndRestamps() {
        assumeTrue(MeshBool.available, "mesh boolean engine unavailable")
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 80.0))
        ed.activeScalar = ed.doc.newParameter("t", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(60.0, 0.0))
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(60.0, 0.0))
        // the face's own coordinates: u along the picked edge about its midpoint, v up into the face
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(10.0, 15.0))
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(10.0, 15.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(Vec2(10.0, 15.0))
        assertEquals(4, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, ed.statusHint)

        ed.setTool(Tools.CUT)
        ed.type("8")
        ed.click(Vec2(13.0, 15.0))
        assertTrue(ed.statusHint.contains("4 copies round pattern P1"), ed.statusHint)

        fun plate(pockets: Int) = 120.0 * 80.0 * 30.0 - pockets * PI * 9.0 * 8.0
        assertManifold(ed.mesh(), "bolt circle of pockets")
        assertClose(ed.volume(), plate(4), tol = plate(4) * 2e-3, msg = "four pockets in one body, not one")
        // the chained base is recorded as the *rule* that resolved it, since copy k's base is a different
        // body at every count — a baked chain of names could not survive a re-stamp
        assertTrue(DocumentFormat.save(ed.doc).contains("orbit \"P1\" cut els=e15@0 part=tip"), DocumentFormat.save(ed.doc))
        assertRoundTrips(ed, "a chained cut")

        // the whole chain is one gesture, hence one undo
        assertTrue(ed.undo())
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the plate alone: no half-chain is left")
        assertClose(ed.volume(), 120.0 * 80.0 * 30.0, tol = 1e-6, msg = "the whole chain of pockets goes")
        assertTrue(ed.redo())

        // ...and it re-stamps: six pockets, still one body
        assertTrue(ed.setPatternCount(patternOf(ed), 6), ed.statusHint)
        assertEquals(6, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertManifold(ed.mesh(), "six pockets")
        assertClose(ed.volume(), plate(6), tol = plate(6) * 2e-3, msg = "the chain re-ran at the new count")
        assertRoundTrips(ed, "a chained cut at count 6")
    }

    // ---- 6. the linear variant ----

    /** A row of holes: base + step vector, then one circle on member 0 (OP-23's non-wrapping case). */
    @Test
    fun aLinearPatternRepeatsAlongItsVector() {
        val ed = Editor()
        ed.count = 5
        ed.setTool(Tools.PATTERN_LINEAR)
        ed.click(Vec2(0.0, 0.0)) // the base
        ed.click(Vec2(30.0, 0.0)) // the step vector's end
        val p = patternOf(ed)
        assertEquals(5, p.ring.size)
        assertFalse(p.wraps, "a row runs out; it does not close")

        ed.activeScalar = ed.doc.newParameter("r", 6.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        assertEquals(5, ed.doc.elements.count { it.kind == ElementKind.CIRCLE }, "five holes from one click")
        assertRoundTrips(ed, "a hole row")

        // a gesture between neighbours makes one fewer copy than there are members — the honest answer for a
        // row, where wrapping round would jump back across the whole thing
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        assertEquals(4, segments(ed.doc).size, "four gaps between five holes")
        assertRoundTrips(ed, "a row with rungs")

        assertTrue(ed.setPatternCount(p, 7), ed.statusHint)
        assertEquals(7, ed.doc.elements.count { it.kind == ElementKind.CIRCLE })
        assertEquals(6, segments(ed.doc).size)
    }

    // ---- 7. the everyday shortcut ----

    @Test
    fun theRoundedPolygonShortcutBuildsThePatternComposition() {
        val ed = Editor()
        ed.count = 6
        ed.activeScalar = ed.doc.newParameter("corner", 20.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(ring(0))
        assertEquals(6, segments(ed.doc).size, "six sides")
        assertEquals(6, arcs(ed.doc).size, "six roundings")
        val p = patternOf(ed)
        assertEquals(6, p.ring.size, "and it is a live pattern, not a frozen shape")
        val script = DocumentFormat.save(assertRoundTrips(ed, "rounded polygon"))
        assertTrue(script.contains("pattern \"P1\" circular"), script)
        assertTrue(script.contains("orbit \"P1\" segment"), script)
        assertTrue(script.contains("orbit \"P1\" fillet"), script)

        // one radius drives every corner, and the count re-stamps like any other pattern
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "corner" }, 10.0.mm)
        assertTrue(ed.setPatternCount(patternOf(ed), 5), ed.statusHint)
        assertEquals(5, segments(ed.doc).size)
        assertEquals(5, arcs(ed.doc).size)
    }

    /** With no radius the polygon is exactly the tool it always was — same step, same elements. */
    @Test
    fun thePlainPolygonIsUnchanged() {
        val ed = Editor()
        ed.count = 5
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(50.0, 0.0))
        assertEquals(5, segments(ed.doc).size)
        assertEquals(0, arcs(ed.doc).size)
        assertNull(ed.doc.patterns.firstOrNull(), "no pattern: the plain polygon is the construction it was")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("tool polygon pts=e1,e2"), text)
        assertFalse(text.contains("scalar="), "an unused defaulted slot costs the step nothing: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }

    // ---- helpers that need the geometry ----

    /** A point on the rounding at member [k] of a hexagon of circumradius 100 — just inside the corner. */
    private fun filletMid(
        k: Int,
        n: Int = 6,
        r: Double = 20.0,
    ): Vec2 {
        val v = ring(k, n)
        val a = (ring(k - 1 + n, n) - v).normalized()
        val b = (ring((k + 1) % n, n) - v).normalized()
        val bisector = (a + b).normalized()
        // the rounding's centre lies along the bisector; its nearest point to the corner is on the arc
        val half = kotlin.math.acos(a.dot(b)) / 2.0
        val d = r / sin(half)
        return v + bisector * (d - r)
    }

    /** How many pieces the recorded Outline step names — the boundary the file replays (OP-14). */
    private fun stepPieces(doc: Document): Int {
        val step = doc.journal.last { it.kind == "tool" && (it.args.firstOrNull() as? Arg.Text)?.s == Tools.OUTLINE }
        val els = step.args.filterIsInstance<Arg.Keyed>().first { it.key == "els" }.value as Arg.Els
        return els.els.size
    }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.mesh() = Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh

    private fun Editor.volume() = Geom3.volume(mesh())
}
