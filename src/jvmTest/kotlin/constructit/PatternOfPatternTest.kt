package constructit

import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A pattern multiplies what rides it — recursively** (GitHub #18, OP-23 one level up).
 *
 * The user's report: a circle drawn on pattern members multiplies with the pattern, but a *polygon* drawn the
 * same way does not — and the missing abstraction they named is *"multiplying a pattern with a pattern"*. The
 * ground truth was that the polygon is **already** OP-23's composition internally (a `pattern` step plus the
 * orbits riding it), declared non-replicating only because an orbit could carry a single `tool` gesture. So
 * the fix is the orbit rule applied to the `pattern` step itself, and nothing here is polygon-shaped.
 *
 * What the assertions are about:
 * - a polygon drawn on a pattern's members multiplies with it — one polygon per cell, each anchored on its
 *   own cell's points, each copy the reference copy carried round by that cell's angle;
 * - the counts stay structural at **both** levels and a change to either is the same journal re-stamp;
 * - member addressing composes (`e@j@k`), so a gesture riding a polygon's own corner ring fans over both
 *   levels — the fan is the product;
 * - the invariance rule composes: a pick outside the inner pattern loses the inner level and says so, a pick
 *   outside the outer one refuses the whole replication and names it;
 * - one undo per gesture however nested, and byte-equal round trips at every stage.
 */
class PatternOfPatternTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun ring(
        k: Int,
        n: Int = 6,
        r: Double = 100.0,
    ): Vec2 = Vec2(r * cos(2 * PI * k / n), r * sin(2 * PI * k / n))

    private fun segments(doc: Document) = doc.elements.filter { it.kind == ElementKind.SEGMENT }

    private fun arcs(doc: Document) = doc.elements.filter { it.kind == ElementKind.ARC }

    private fun at(
        doc: Document,
        el: Element,
    ): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "a position for ${doc.nameOf(el)}")

    /** A world point [t] of the way along the segment [el] — where a fillet's leg click goes. */
    private fun alongSide(
        doc: Document,
        el: Element,
        t: Double,
    ): Vec2 {
        val s = assertNotNull(Evaluator().valueOf(el.ref) as? SegmentValue, "a segment for ${doc.nameOf(el)}")
        return s.seg.a + (s.seg.b - s.seg.a) * t
    }

    private fun assertRoundTrips(
        doc: Document,
        where: String,
    ): Document {
        val once = DocumentFormat.save(doc)
        val reloaded = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal ($where)")
        assertEquals(doc.elements.map { it.kind }, reloaded.elements.map { it.kind }, "same elements ($where)")
        return reloaded
    }

    /** A ring of [n] points of radius 100 about the origin. */
    private fun hexPattern(n: Int = 6): Editor {
        val ed = Editor()
        ed.count = n
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(ring(0, n))
        return ed
    }

    /**
     * …plus a **triangle drawn on two of its members** — centre on member 0, vertex on member 1.
     *
     * Both picks are members, so the gesture is an ordinary ride by OP-23's rule; what is new is that the
     * gesture *creates several elements*, one of the two things #18 was about.
     */
    private fun ringOfPolygons(
        n: Int = 6,
        sides: Int = 3,
    ): Editor {
        val ed = hexPattern(n)
        ed.count = sides
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0, n))
        ed.click(ring(1, n))
        return ed
    }

    // ---- 1. the report: a polygon multiplies with the ring ----

    @Test
    fun aPolygonDrawnOnPatternMembersMultipliesWithThePattern() {
        val ed = ringOfPolygons()
        // one polygon per cell of the ring, and its sides are that many times the sides of one
        assertEquals(6 * 3, segments(ed.doc).size, "three sides per cell, six cells: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("6 copies round pattern P1"), ed.statusHint)
        assertRoundTrips(ed.doc, "a ring of polygons")
    }

    /** Each copy's vertices are the reference copy's, carried round by that cell's own angle. */
    @Test
    fun everyCopySitsWhereTheCellsAngleCarriesTheReferenceCopy() {
        val ed = ringOfPolygons()
        val g = assertNotNull(ed.doc.patterns.single().gestures.singleOrNull(), "one ride")
        assertEquals(listOf(6), g.outputs.map { it.size }.distinct(), "each output orbit has one member per cell")
        for (o in g.outputs) {
            if (o.members[0].kind != ElementKind.DERIVED_POINT && o.members[0].kind != ElementKind.POINT) continue
            val base = at(ed.doc, o.members[0])
            for (k in 1 until 6) {
                val a = 2 * PI * k / 6
                val want = Vec2(base.x * cos(a) - base.y * sin(a), base.x * sin(a) + base.y * cos(a))
                val got = at(ed.doc, o.members[k])
                assertClose(got.x, want.x, tol = 1e-9, msg = "copy $k x")
                assertClose(got.y, want.y, tol = 1e-9, msg = "copy $k y")
            }
        }
    }

    /** The whole nested creation is **one** step, so one undo removes every copy of it. */
    @Test
    fun oneUndoRemovesTheWholeRideHoweverMuchItBuilt() {
        val ed = ringOfPolygons()
        assertEquals(1, ed.doc.journal.count { it.kind == "orbit" }, "one orbit step for the whole ride")
        ed.undo()
        assertEquals(0, segments(ed.doc).size, "the ring of polygons is gone in one press")
        assertEquals(6, ed.doc.patterns.single().ring.size, "and the pattern itself stays")
    }

    // ---- 2. counts stay structural at both levels ----

    @Test
    fun theRingsCountRestampsHowManyPolygonsThereAre() {
        val ed = ringOfPolygons()
        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 8), ed.statusHint)
        assertEquals(8 * 3, segments(ed.doc).size, "eight cells, three sides each")
        assertRoundTrips(ed.doc, "eight polygons")
        // …and back down, where the members a smaller count removes take their gestures' copies with them
        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 3), ed.statusHint)
        assertEquals(3 * 3, segments(ed.doc).size, "three cells: ${ed.statusHint}")
        assertRoundTrips(ed.doc, "three polygons")
    }

    /** The ride's own count is the *sides* of every polygon, and one re-stamp changes all of them. */
    @Test
    fun theRidesOwnCountRestampsEveryCopy() {
        val ed = ringOfPolygons()
        val side = segments(ed.doc).first()
        ed.clearSelection()
        ed.selectElement(side)
        val ride = assertNotNull(ed.selectedRide(), "the selection names the ride it was built by")
        assertEquals(3, ride.count)
        assertTrue(ed.setRideCount(ride, 5), ed.statusHint)
        assertEquals(6 * 5, segments(ed.doc).size, "five sides on each of six polygons: ${ed.statusHint}")
        assertRoundTrips(ed.doc, "five-sided polygons")
    }

    /**
     * A ride whose picks **span** more members than a smaller count would have is refused by name, before
     * anything happens — OP-23's one thing mod-*n* cannot absorb, unchanged by the nesting.
     */
    @Test
    fun aRideSpanningMoreMembersThanTheNewCountHasIsRefusedByName() {
        val ed = hexPattern()
        ed.count = 3
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(4)) // four members apart: a pair at six, not a pair at three
        assertEquals(6 * 3, segments(ed.doc).size, "the ride still fans at six: ${ed.statusHint}")
        val before = DocumentFormat.save(ed.doc)
        assertFalse(ed.setPatternCount(ed.doc.patterns.single(), 3), "a three-member ring has no such pair")
        assertTrue(ed.statusHint.contains("spans 5 members"), ed.statusHint)
        assertEquals(before, DocumentFormat.save(ed.doc), "and the drawing is untouched")
    }

    /** The choices a ride scored are taken **once** and handed on verbatim — a replay never scores again. */
    @Test
    fun replayTakesTheScoredChoicesVerbatim() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        val script = DocumentFormat.save(ed.doc)
        val ride = script.lines().last { it.startsWith("orbit \"P1\" polygon") }
        assertTrue(ride.contains("signs="), "the ride's own scoring is written down: $ride")
        // …and reloading rebuilds the very same arcs, which is what "never re-scored" means in the drawing
        val again = DocumentFormat.load(script)
        assertEquals(arcs(ed.doc).size, arcs(again).size)
        assertEquals(script, DocumentFormat.save(again))
    }

    /** …and the two counts are independent: changing one leaves the other alone. */
    @Test
    fun theTwoCountsAreIndependent() {
        val ed = ringOfPolygons()
        ed.clearSelection()
        ed.selectElement(segments(ed.doc).first())
        assertTrue(ed.setRideCount(assertNotNull(ed.selectedRide()), 4), ed.statusHint)
        assertEquals(6 * 4, segments(ed.doc).size)
        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 5), ed.statusHint)
        assertEquals(5 * 4, segments(ed.doc).size, "five cells of four sides: ${ed.statusHint}")
        assertRoundTrips(ed.doc, "five cells of four sides")
    }

    // ---- 3. a pattern of a pattern: the rounded polygon, and `@j@k` ----

    /**
     * A **rounded** polygon on a pattern's members is literally a pattern of a pattern: every copy of the ride
     * builds a circular pattern of its own plus the orbits riding it.
     */
    @Test
    fun aRoundedPolygonRidingAPatternIsAPatternOfAPattern() {
        val ed = hexPattern()
        ed.count = 3
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        assertEquals(6 * 3, segments(ed.doc).size, "three sides per cell: ${ed.statusHint}")
        assertEquals(6 * 3, arcs(ed.doc).size, "three roundings per cell")
        // six nested patterns, one per cell, none of them a rule the file names
        val outer = ed.doc.patterns.single()
        val ride = outer.gestures.single()
        assertEquals(6, ride.inner.size, "one nested pattern per copy")
        assertEquals(1, ed.doc.journal.count { it.kind == "pattern" }, "the nested rules have no step of their own")
        assertEquals(1, ed.doc.journal.count { it.kind == "orbit" }, "one ride, one step")
        val script = DocumentFormat.save(ed.doc)
        assertTrue(script.contains("orbit \"P1\" polygon"), script)
        assertRoundTrips(ed.doc, "a ring of rounded polygons")

        // one radius still drives every rounding of every copy, by reference
        ed.doc.setParameter(ed.doc.scalars.single { it.name == "corner" }, 4.0.mm)
        assertEquals(6 * 3, arcs(ed.doc).size)
        assertRoundTrips(ed.doc, "after retyping the radius")
    }

    /**
     * A gesture riding a **nested** pattern's members fans over both levels: the fan is the product, and the
     * step writes the composed address `e@j@k`.
     */
    @Test
    fun aGestureOnTheNestedRingFansOverBothLevelsAndWritesTheComposedAddress() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        // the nested ring of the first copy: its members are the square's corners
        val inner = assertNotNull(ed.doc.nested.firstOrNull(), "a nested pattern")
        assertEquals(4, inner.ring.size)
        val a = at(ed.doc, inner.ring.members[0])
        val b = at(ed.doc, inner.ring.members[1])
        val before = segments(ed.doc).size
        ed.setTool(Tools.SEGMENT)
        ed.click(a)
        ed.click(b)
        assertEquals(before + 6 * 4, segments(ed.doc).size, "one segment per (cell, corner): ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("24 copies"), ed.statusHint)
        assertTrue(ed.statusHint.contains("(6 x 4)"), ed.statusHint)
        val script = DocumentFormat.save(ed.doc)
        val ride = script.lines().last { it.startsWith("orbit \"P1\" segment") }
        assertTrue(Regex("pts=\\S+@\\d+@\\d+,\\S+@\\d+@\\d+").containsMatchIn(ride), ride)
        assertRoundTrips(ed.doc, "a gesture over both levels")
    }

    /** Member 0 is the anchor at **every** level, so the reference survives a re-stamp in either direction. */
    @Test
    fun aComposedAddressSurvivesARestampAtEitherLevel() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        val inner = assertNotNull(ed.doc.nested.firstOrNull())
        ed.setTool(Tools.SEGMENT)
        ed.click(at(ed.doc, inner.ring.members[0]))
        ed.click(at(ed.doc, inner.ring.members[1]))
        assertEquals(6 * 4 + 6 * 4, segments(ed.doc).size)
        // the outer count changes: both the polygons and the gesture riding their corners follow
        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 5), ed.statusHint)
        assertEquals(5 * 4 + 5 * 4, segments(ed.doc).size, "five cells: ${ed.statusHint}")
        assertRoundTrips(ed.doc, "restamped outer count with a composed ride")
    }

    // ---- 4. the invariance rule composes ----

    /** A pick genuinely outside the pattern refuses the whole replication and names it (OP-23, unchanged). */
    @Test
    fun aPickOutsideThePatternRefusesTheRideAndNamesIt() {
        val ed = hexPattern()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(300.0, 300.0))
        val loose = ed.doc.elements.last()
        ed.count = 3
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(Vec2(300.0, 300.0))
        assertEquals(3, segments(ed.doc).size, "one polygon only")
        assertTrue(ed.statusHint.contains("not replicated"), ed.statusHint)
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(loose)), ed.statusHint)
    }

    /**
     * A gesture **mixing levels** — one pick on a polygon's own corner ring, one on the outer ring the
     * polygons sit on — keeps the outer level and loses the inner one, saying which pick and which pattern.
     */
    @Test
    fun aGestureMixingLevelsKeepsTheOuterLevelAndNamesWhatCostItTheInner() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        val inner = assertNotNull(ed.doc.nested.firstOrNull())
        val before = segments(ed.doc).size
        ed.setTool(Tools.SEGMENT)
        // a corner of copy 0's polygon, and the *outer* ring's member 2 — a member of P1 but of no polygon
        ed.click(at(ed.doc, inner.ring.members[1]))
        ed.click(ring(2))
        assertEquals(before + 6, segments(ed.doc).size, "six copies, not twenty-four: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("6 copies round pattern P1"), ed.statusHint)
        assertTrue(ed.statusHint.contains("not replicated inside"), ed.statusHint)
        assertRoundTrips(ed.doc, "a gesture that lost the inner level")
    }

    // ---- 5. the rest of OP-23's rules, one level up ----

    /**
     * **Alt suppresses at the level of the gesture it is pressed in**, and OP-23's two halves still agree.
     *
     * On a slot that *places* a point Alt declines the snap, so a rounded polygon made with Alt is a one-off by
     * construction — two fresh points, the pattern untouched — and it is still a whole rounded polygon, because
     * its own internal fan is not the gesture Alt declined.
     */
    @Test
    fun altKeepsTheRideAOneOffWithoutTouchingWhatItBuildsInside() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.snapEnabled = false // Alt held
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        assertEquals(4, segments(ed.doc).size, "one polygon: ${ed.statusHint}")
        assertEquals(4, arcs(ed.doc).size, "…and all four of its corners are still rounded")
        assertEquals(2, ed.doc.patterns.size, "the ring, and the one-off polygon's own rule beside it")
        assertTrue(ed.doc.patterns.first { it.name == "P1" }.gestures.isEmpty(), "nothing rides the ring")
        assertRoundTrips(ed.doc, "an Alt one-off ride")
    }

    /** …and on a slot that *picks* geometry, Alt is what declines the fan out loud (OP-23), rides included. */
    @Test
    fun altDeclinesTheFanOnGeometryThatARideBuilt() {
        val ed = ringOfPolygons()
        val sides = segments(ed.doc)
        // the two sides of copy 0 that meet at its second vertex, clicked near that corner as a fillet wants
        val a = alongSide(ed.doc, sides[0], 0.85)
        val b = alongSide(ed.doc, sides[1], 0.15)
        ed.activeScalar = ed.doc.newParameter("fillet", 5.0.mm)
        ed.snapEnabled = false // Alt held
        ed.setTool(Tools.FILLET)
        ed.click(a)
        ed.click(b)
        assertEquals(1, arcs(ed.doc).size, "one rounding, not one per cell: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Alt keeps it a one-off"), ed.statusHint)
        assertRoundTrips(ed.doc, "an Alt one-off on a ride's geometry")
    }

    /** The **pattern tool itself** riding a pattern: the user's own phrase, multiplying a pattern with one. */
    @Test
    fun aPatternDrawnOnAPatternsMembersIsMultipliedByIt() {
        val ed = hexPattern()
        ed.count = 4
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(ring(0)) // the centre
        ed.click(ring(1)) // the reference member
        assertEquals(6, ed.doc.nested.size, "one nested pattern per cell of the ring: ${ed.statusHint}")
        assertTrue(ed.doc.nested.all { it.count == 4 && it.depth == 1 })
        assertEquals(1, ed.doc.patterns.size, "and only the outer one is a rule the file names")
        val script = DocumentFormat.save(ed.doc)
        assertTrue(script.contains("orbit \"P1\" patterncircular"), script)
        assertRoundTrips(ed.doc, "a pattern of patterns")
    }

    /**
     * A composed address travels the **delete cascade** and the **name map** like every other element
     * reference — which is the whole reason it is one (`e@0@1` names an element and says the rule).
     */
    @Test
    fun aComposedAddressTravelsTheDeleteCascadeAndTheNameMap() {
        val ed = hexPattern()
        ed.count = 4
        ed.activeScalar = ed.doc.newParameter("corner", 8.0.mm)
        ed.setTool(Tools.POLYGON)
        ed.click(ring(0))
        ed.click(ring(1))
        val inner = assertNotNull(ed.doc.nested.firstOrNull())
        ed.setTool(Tools.SEGMENT)
        ed.click(at(ed.doc, inner.ring.members[0]))
        ed.click(at(ed.doc, inner.ring.members[1]))
        val composed = 6 * 4
        assertEquals(6 * 4 + composed, segments(ed.doc).size)
        // the names in the saved script are the drawing's own, so a re-save under fresh names must round-trip
        val reloaded = assertRoundTrips(ed.doc, "a composed address")
        assertEquals(6 * 4 + composed, segments(reloaded).size)
        // deleting the ring takes the whole cone with it — both levels of it
        ed.clearSelection()
        ed.selectElement(ed.doc.patterns.single().reference)
        ed.deleteSelection()
        assertEquals(0, segments(ed.doc).size, "the ring's cone includes what rode the patterns inside it")
        assertEquals(0, ed.doc.nested.size)
        assertRoundTrips(ed.doc, "after deleting the ring")
    }

    // ---- 6. the user's own script ----

    /** The user's script, verbatim: it still loads to exactly what it always meant, and round-trips. */
    @Test
    fun theUsersScriptStillMeansWhatItMeant() {
        val doc = DocumentFormat.load(USERS_SCRIPT)
        // `tool polygon` is one application, as it always was: replay discovers no replication (OP-18)
        assertEquals(2, doc.journal.count { it.kind == "tool" }, "two polygon applications, replayed as recorded")
        assertEquals(6 + 6, segments(doc).size, "two hexagons of six sides")
        // every step comes back verbatim — only the header says which build wrote it (OP-18)
        val saved = DocumentFormat.save(doc).trim().lines()
        assertEquals(USERS_SCRIPT.trim().lines().drop(1), saved.drop(1))
        assertRoundTrips(doc, "the user's script")
    }

    /**
     * The user's script **re-performed as gestures**, which is what their report is about: the second polygon
     * now multiplies with the ring, one polygon per cell anchored on that cell's own derived points.
     */
    @Test
    fun theUsersSecondPolygonNowMultipliesWithTheRing() {
        // their construction up to the derived points, verbatim
        val doc = DocumentFormat.load(USERS_PREFIX)
        val ed = Editor(doc)
        val anchor = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == "e20" }, "the derived point e20")
        val ringMember = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == "e2" }, "the ring's member 0")
        val sidesBefore = segments(ed.doc).size
        ed.count = 6
        ed.setTool(Tools.POLYGON)
        ed.click(at(ed.doc, ringMember))
        ed.click(at(ed.doc, anchor))
        assertEquals(sidesBefore + 6 * 6, segments(ed.doc).size, "six hexagons, one per cell: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("6 copies round pattern P1"), ed.statusHint)

        // each copy is the reference copy carried round by its cell's angle
        val ride = ed.doc.patterns.single().gestures.last()
        for (o in ride.outputs) {
            val m0 = o.members[0]
            if (m0.kind != ElementKind.DERIVED_POINT) continue
            val base = at(ed.doc, m0)
            val centre = at(ed.doc, assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == "e1" }))
            for (k in 1 until 6) {
                val a = 2 * PI * k / 6
                val d = base - centre
                val want = centre + Vec2(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a))
                val got = at(ed.doc, o.members[k])
                assertClose(got.x, want.x, tol = 1e-9, msg = "copy $k x")
                assertClose(got.y, want.y, tol = 1e-9, msg = "copy $k y")
            }
        }
        assertRoundTrips(ed.doc, "the user's drawing, fixed")
    }

    private companion object {
        /** GitHub #18, the reporter's own drawing. */
        val USERS_PREFIX =
            """
            constructit 2
            point -6.17499999999998,-2.6750000000000056 -> e1
            point 71.37500000000004,28.124999999999993 -> e2
            pattern "P1" circular ref=e2 centre=e1 count=6 -> e3,e4,e5,e6,e7
            orbit "P1" ray pts=e1,e2@0 cells=-5.6249999999999805,-2.6750000000000056;59.82500000000002,55.62499999999999 -> e8,e9,e10,e11,e12,e13
            orbit "P1" perp pts=e2@0 els=e8@0 cells=63.40000000000002,59.74999999999999;60.10000000000002,56.175 -> e14,e15,e16,e17,e18,e19
            param "distance" = 20mm
            orbit "P1" ptatdist pts=e2@0 els=e14@0 cells=59.82500000000002,56.449999999999996;48.825000000000024,67.725 scalar="distance" -> e20,e21,e22,e23,e24,e25
            """.trimIndent()

        val USERS_SCRIPT =
            USERS_PREFIX + "\n" +
                """
                tool polygon pts=e2,e2 clicks=71.37500000000003,27.299999999999994;71.37500000000003,27.299999999999994 count=6 -> e26,e27,e28,e29,e30,e31,e32,e33,e34,e35,e36
                tool polygon pts=e30,e20 clicks=71.37500000000003,28.949999999999996;68.62500000000003,37.74999999999999 count=6 -> e37,e38,e39,e40,e41,e42,e43,e44,e45,e46,e47
                """.trimIndent()
    }
}
