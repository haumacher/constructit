package constructit

import constructit.core.ArcValue
import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SegmentValue
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The format is versioned, and a scored choice is stored in the file** (OP-18, *Versioning & migration*).
 *
 * Reported as data loss: a drawing came back with *"fillets inverted, producing sharp corners"* after being
 * reopened, and the rider on a construction line had moved along its carrier. The two halves of the answer are
 * asserted here.
 *
 * - **A stored literal's meaning is frozen** the moment a build that writes it might have shipped. The header
 *   therefore carries a version, the loader accepts 1 and 2, and loading a 1 *migrates* it — the geometry the
 *   old writer meant is what comes back, and where a v1 value is genuinely ambiguous the load **says so**
 *   instead of guessing (`Document.loadNotes`).
 * - **A choice scored from clicks is persisted at creation.** A fillet's variant used to be re-scored on every
 *   load, against whatever the geometry had become since; it is now written into the step as `signs=` and
 *   replayed verbatim.
 */
class FormatVersionTest {
    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    private fun rider(doc: Document): Element = doc.elements.first { it.kind == ElementKind.ON_CURVE }

    private fun riderParam(
        doc: Document,
        el: Element = rider(doc),
    ): Double {
        val node = doc.riderParam(el)!!
        return ((Evaluator().eval(node) as EvalResult.Ok).value as ScalarValue).q.mm
    }

    private fun arcOf(el: Element): ArcValue? = ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? ArcValue)

    private fun lastArc(doc: Document): ArcValue = arcOf(doc.elements.last { it.kind == ElementKind.ARC })!!

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

    // ---- the header ----

    @Test
    fun aSaveNamesTheVersionItWrites() {
        val doc = Document()
        doc.freePoint(0.0.mm, 0.0.mm)
        assertEquals("constructit 2", DocumentFormat.save(doc).lineSequence().first(), "the file says what it is")
    }

    @Test
    fun aNewerFileSaysItIsNewerRatherThanBeingMisread() {
        val newer = runCatching { DocumentFormat.load("constructit 3\npoint 0,0 -> e1\n") }.exceptionOrNull()
        assertTrue(newer is DocumentFormat.LoadError, "got: $newer")
        assertTrue(newer.message!!.contains("newer version"), "got: ${newer.message}")
        val alien = runCatching { DocumentFormat.load("sketchup 1\n") }.exceptionOrNull()
        assertTrue(alien is DocumentFormat.LoadError && alien.message!!.contains("not a ConstructIt drawing"), "got: $alien")
    }

    @Test
    fun aFormatOneFileStillLoadsAndIsSavedAsFormatTwo() {
        val doc = DocumentFormat.load("constructit 1\npoint 10,20 -> e1\n")
        assertEquals(Vec2(10.0, 20.0), pos(doc.elements[0]))
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.startsWith("constructit 2\n"), saved)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and the migrated file is stable")
    }

    // ---- the along-carrier anchor: the one stored meaning that changed (OP-20, as built) ----

    /**
     * A diagonal segment from (20,10), so its carrier line's `origin` is **21.2132 mm along** the line from the
     * point of that line nearest the world origin — which is exactly the offset between the two readings of a
     * distance along a carrier. The rider's recorded position is the point at 20 mm *from the segment's own
     * end*, i.e. what a pre-anchoring build meant by `dofs=20mm`.
     */
    private val legacyRider =
        """
constructit 1
point 20,10 -> e1
point 120,110 -> e2
tool segment pts=e1,e2 clicks=20,10;120,110 -> e3
pointoncurve e3 34.14213562373095,24.14213562373095 dofs=20mm -> e4
""".trimStart()

    /** Where the file put it: the recorded position is creation-time truth, so it decides which reading is meant. */
    @Test
    fun aFormatOneDistanceAlongACarrierIsMigratedToTodaysAnchor() {
        val doc = DocumentFormat.load(legacyRider)
        val recorded = Vec2(34.14213562373095, 24.14213562373095)
        assertClose((pos(rider(doc)) - recorded).length(), 0.0, 1e-9, "the rider is where the v1 writer put it")
        // ...which is *not* what the same number means today — the migration is doing real work
        assertClose(riderParam(doc), 41.21320343559643, 1e-9, "re-stated against the anchor that belongs to the line")
        assertTrue(
            doc.loadNotes.any { it.contains("measured from the carrier's own end") },
            "the migration says what it decided: ${doc.loadNotes}",
        )
        // and the v2 file it saves means the same geometry without any migration
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.contains("dofs=41.21320343559643mm"), saved)
        val again = DocumentFormat.load(saved)
        assertTrue(again.loadNotes.isEmpty(), "nothing left to decide: ${again.loadNotes}")
        assertClose((pos(rider(again)) - recorded).length(), 0.0, 1e-9)
        assertEquals(saved, DocumentFormat.save(again), "byte-stable at v2")
    }

    /** The same script *claiming* to be v2 is read the modern way — which is the drift the version bump prevents. */
    @Test
    fun withoutTheVersionTheSameNumberWouldMeanSomewhereElse() {
        val doc = DocumentFormat.load(legacyRider.replaceFirst("constructit 1", "constructit 2"))
        assertClose(riderParam(doc), 20.0, 1e-9, "taken as today's anchor")
        assertClose((pos(rider(doc)) - Vec2(19.14213562373095, 9.142135623730951)).length(), 0.0, 1e-9, "21.2 mm short")
    }

    /**
     * The **honest residual**: once the rider has been moved, or its host turned by an edit upstream, the
     * recorded position no longer arbitrates. Today's reading is kept — the geometry the last writer saved —
     * and the load names the element rather than deciding quietly.
     */
    @Test
    fun anAmbiguousFormatOneDistanceIsKeptAndReported() {
        val script = legacyRider.replaceFirst("dofs=20mm", "dofs=33mm")
        val doc = DocumentFormat.load(script)
        assertClose(riderParam(doc), 33.0, 1e-9, "no evidence for the other reading, so nothing is invented")
        assertTrue(doc.loadNotes.any { it.contains("moved since it was created") }, "got: ${doc.loadNotes}")
    }

    // ---- a scored choice belongs in the step (OP-1) ----

    /**
     * Two crossing segments: the user rounds the corner **left** of the crossing (clicks at x=20 on the
     * horizontal leg, above the crossing on the vertical one). Then the vertical leg is dragged left, past the
     * click on the horizontal one — so the quadrant that click *scores* has flipped, while the quadrant the
     * user chose has not.
     */
    private fun crossing(): Editor {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(60.0, -50.0))
        ed.click(Vec2(60.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("f", 8.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(60.0, 30.0))
        return ed
    }

    @Test
    fun aFilletWritesTheVariantItScored() {
        val saved = DocumentFormat.save(crossing().doc)
        assertTrue(Regex("tool fillet [^\n]*signs=-1;1").containsMatchIn(saved), saved)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and it round-trips verbatim")
    }

    @Test
    fun aFilletKeepsItsVariantWhenALegMovesPastTheClickThatScoredIt() {
        val ed = crossing()
        val chosen = lastArc(ed.doc).arc.center
        assertTrue(chosen.x < 60.0 && chosen.y > 0.0, "the rounding sits in the upper-left corner: $chosen")

        // the crossing moves to x=10, leaving the click at x=20 on the *other* side of it
        ed.drag(Vec2(60.0, -50.0), Vec2(10.0, -50.0))
        ed.drag(Vec2(60.0, 50.0), Vec2(10.0, 50.0))
        val moved = lastArc(ed.doc).arc.center
        assertTrue(moved.x < 10.0 && moved.y > 0.0, "it followed the corner, still upper-left: $moved")

        val reloaded = DocumentFormat.load(DocumentFormat.save(ed.doc))
        val after = lastArc(reloaded).arc.center
        assertClose((after - moved).length(), 0.0, 1e-9, "and the reload rebuilds that fillet, not its sibling")
    }

    /** A format-1 fillet carries no signs: it is scored **once** on load, then written down for good. */
    @Test
    fun aFormatOneFilletIsScoredOnceAndThenStored() {
        val v1 = DocumentFormat.save(crossing().doc).replaceFirst("constructit 2", "constructit 1").replace(" signs=-1;1", "")
        assertTrue(!v1.contains("signs="), v1)
        val doc = DocumentFormat.load(v1)
        val saved = DocumentFormat.save(doc)
        assertTrue(Regex("tool fillet [^\n]*signs=-1;1").containsMatchIn(saved), saved)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "stable from then on")
    }

    // ---- the reported drawing (a six-spoke wheel), verbatim ----

    /**
     * The user's file as it was saved before the incident (format 1). It is the load-bearing fixture for the
     * whole of this: a rider on a perpendicular bisector (`e18`) whose carrier has since been **turned** by an
     * edit upstream — the other rider `e3` was dragged from 92.83° to 118.46° round the wheel's rim — plus four
     * fillets whose variants were, until now, re-scored on every load.
     */
    private val wheel =
        """
constructit 1
param "r" = 122mm
point -6,3.5 -> e1
tool circleR pts=e1 clicks=-5.25,2.5 scalar="r" -> e2
pointoncurve e2 -12.02973535129569,125.35090189076706 dofs=118.46225976403586deg -> e3
tool segment pts=e1,e3 clicks=-5.75,3.75;-12,124.75 -> e4
param "d" = 10mm
tool parallelat els=e4 clicks=-7,30.5;0,32.25 scalar="d" -> e5
tool parallelat els=e4 clicks=-8,32.75;-15.75,32.75 scalar="d" -> e6
group "strebe" els=e6,e5
tool arraycircular pts=e1 els=e5 clicks=2.5,32.25;-5.75,4.75 count=6 -> e7,e8,e9,e10,e11
tool intersect els=e5,e2 clicks=-1.5,116.25;8.5,124.25 -> e12,e13
tool intersect els=e8,e2 clicks=81.5,71.75;84.5,85.5 -> e14,e15
tool arccs pts=e1,e14,e13 clicks=-5,3;91.75,77.25;-0.5,126.5 -> e16
tool perpbis pts=e13,e14 clicks=-2.75,125.25;89.75,77 -> e17
pointoncurve e17 14.118741663069027,42.702264910197286 dofs=52.86964276686915mm -> e18
tool perp pts=e18 els=e17 clicks=14.25,43.25;15,42.25 -> e19
tool intersect els=e5,e19 clicks=1.25,62.25;6.5,49.75 -> e20
tool intersect els=e19,e8 clicks=23,41.5;37.75,43.25 -> e21
tool segment pts=e14,e21 clicks=91,77.75;29.25,39 -> e22
tool segment pts=e21,e20 clicks=29.5,37.75;3.25,53.25 -> e23
tool segment pts=e20,e13 clicks=3.25,53.25;-1.25,125 -> e24
param "f" = 14mm
tool fillet els=e5,e19 clicks=1,70.5;7.75,49.25 scalar="f" -> e25
tool fillet els=e23,e22 clicks=22.5,41.75;41,45.25 scalar="f" -> e26
tool keypoints els=e25 clicks=3.75,61.5 -> e27,e28,e29
tool keypoints els=e26 clicks=34,44.75 -> e30,e31,e32
tool segment pts=e29,e31 clicks=9.5,47.5;21,40.75 -> e33
tool fillet els=e24,e16 clicks=1.582395332810995,52.404821316619085;22.863387068348143,120.58663949843712 scalar="f" -> e34
tool fillet els=e16,e8 clicks=80.92123830801745,87.94201139926363;76.9956184733067,69.14035850670169 scalar="f" -> e35
tool keypoints els=e34 clicks=3.64851103529033,120.3800279281892 -> e36,e37,e38
tool keypoints els=e35 clicks=83.19396558074472,78.4378791678587 -> e39,e40,e41
hide els=e16
hide els=e24
hide els=e22
hide els=e23
hide els=e30
hide els=e27
hide els=e39
hide els=e36
hide els=e2
tool segment pts=e38,e40 clicks=16.251816820414273,123.89242462240406;79.26834574603399,90.00812710174297 -> e42
tool segment pts=e41,e32 clicks=76.9956184733067,69.14035850670169;33.40057715099275,40.21473867199101 -> e43
tool segment pts=e28,e37 clicks=0.9625606220671945,57.57011057281742;-2.343224501899741,109.63622627529665 -> e44
show els=e16
hide els=e42
tool arccs pts=e1,e40,e38 clicks=-5.85562119611461,3.231267597610915;80.09479202702572,90.62796181248677;15.01214739892667,124.7188709033958 -> e45
hide els=e16
group "hole" els=e43,e26,e33,e25,e44,e34,e45,e35,e1,e3,e18
""".trimStart()

    /**
     * **What the migration decides for the reported file.** `e18`'s recorded position is 19.06 mm off its
     * carrier *now*, because the edit that turned that carrier happened after `e18` was placed; so the position
     * cannot say which anchor the stored 52.8696 mm was measured from, and the value is kept as the last writer
     * meant it — the geometry the user saved. The load names the element instead of moving it.
     */
    @Test
    fun theReportedWheelLoadsAsItWasSavedAndSaysWhatItCouldNotDecide() {
        val doc = DocumentFormat.load(wheel)
        val e18 = doc.elements[17]
        assertEquals(ElementKind.ON_CURVE, e18.kind, "the rider on the perpendicular bisector")
        assertClose(riderParam(doc, e18), 52.86964276686915, 1e-9, "exactly the value the file carries")
        assertClose((pos(e18) - Vec2(-4.670790821513371, 53.014077539242976)).length(), 0.0, 1e-9)
        assertTrue(doc.loadNotes.any { it.contains(e18.id) && it.contains("off that carrier") }, "got: ${doc.loadNotes}")

        // …and every fillet is a real rounding of f = 14 mm: tangent to both its legs, which is what a sharp
        // corner is the absence of. Asserted per fillet, in mm, against the legs the step named.
        assertTangent(doc, fillet = 25, legs = listOf(5, 19))
        assertTangent(doc, fillet = 26, legs = listOf(23, 22))
        assertTangent(doc, fillet = 34, legs = listOf(24, 16))
        assertTangent(doc, fillet = 35, legs = listOf(16, 8))
    }

    /**
     * That the fillet the script calls `e[fillet]` stands **exactly** its own radius from each of `e[legs]`'s
     * carriers — the definition of the rounding being tangent there, and the thing an inverted variant breaks.
     */
    private fun assertTangent(
        doc: Document,
        fillet: Int,
        legs: List<Int>,
    ) {
        val arc = arcOf(doc.elements[fillet - 1])!!.arc
        assertClose(arc.radius, 14.0, 1e-9, "e$fillet is a fillet of f")
        for (leg in legs) {
            val el = doc.elements[leg - 1]
            val gap =
                when (val v = (Evaluator().eval(el.ref.node) as EvalResult.Ok).value) {
                    is LineValue -> ((arc.center - v.line.origin) - v.line.dir * (arc.center - v.line.origin).dot(v.line.dir)).length()
                    is SegmentValue -> v.seg.let { s -> (s.b - s.a).normalized().let { d -> ((arc.center - s.a) - d * (arc.center - s.a).dot(d)).length() } }
                    is ArcValue -> kotlin.math.abs((arc.center - v.arc.center).length() - v.arc.radius)
                    is CircleValue -> kotlin.math.abs((arc.center - v.circle.center).length() - v.circle.radius)
                    else -> throw AssertionError("e$leg is not a fillet leg: $v")
                }
            assertClose(gap, 14.0, 1e-6, "e$fillet is tangent to e$leg")
        }
    }

    /**
     * **The archaeology, pinned.** The one thing that could have made `e18`'s stored 52.8696 mm a *misread* is
     * its recorded position, and this is what that position proves: put the wheel's other rider `e3` back at
     * **its** own recorded position (92.83294421497253° — the angle of the coordinates the step carries, against
     * the rim), and `e18`'s recorded position lies on the perpendicular bisector to 1e-9 mm.
     *
     * So the position was exactly on its carrier when it was written, and it is 19.06 mm off that carrier now
     * because `e3` was later dragged 25.6° round the rim, which turns `e13`, `e14` and hence the bisector. The
     * position is stale *by design* (OP-18 keeps a click verbatim and restates the parameter), not evidence of a
     * wrong anchor — and reading it as a target would silently undo the drag.
     */
    @Test
    fun theRidersRecordedPositionWasOnItsCarrierWhenItWasRecorded() {
        val atCreation = wheel.replaceFirst("dofs=118.46225976403586deg", "dofs=92.83294421497253deg")
        val doc = DocumentFormat.load(atCreation)
        val bisector = (Evaluator().eval(doc.elements[16].ref.node) as EvalResult.Ok).value as LineValue
        val recorded = Vec2(14.118741663069027, 42.702264910197286)
        val l = bisector.line
        val gap = ((recorded - l.origin) - l.dir * (recorded - l.origin).dot(l.dir)).length()
        assertClose(gap, 0.0, 1e-9, "the recorded position was on the carrier as it stood then")
        // and with e3 where the file's own `dofs=` puts it, that same position is 19.06 mm off it
        val turned = DocumentFormat.load(wheel)
        val t = (Evaluator().eval(turned.elements[16].ref.node) as EvalResult.Ok).value as LineValue
        val tl = t.line
        assertClose(((recorded - tl.origin) - tl.dir * (recorded - tl.origin).dot(tl.dir)).length(), 19.059487428891327, 1e-9)
    }

    /** And the migrated file is a v2 file that says everything the v1 one only implied. */
    @Test
    fun theReportedWheelIsSavedWithItsFilletVariantsAndIsThenStable() {
        val doc = DocumentFormat.load(wheel)
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.startsWith("constructit 2\n"), saved.lineSequence().first())
        assertEquals(4, Regex("tool fillet [^\n]*signs=").findAll(saved).count(), "every fillet now states its variant:\n$saved")
        val reloaded = DocumentFormat.load(saved)
        assertTrue(reloaded.loadNotes.isEmpty(), "nothing left to decide: ${reloaded.loadNotes}")
        assertEquals(saved, DocumentFormat.save(reloaded), "byte-stable at v2")
        // the geometry is the same drawing, fillet for fillet
        val before = doc.elements.filter { it.kind == ElementKind.ARC }.mapNotNull { arcOf(it)?.arc?.center }
        val after = reloaded.elements.filter { it.kind == ElementKind.ARC }.mapNotNull { arcOf(it)?.arc?.center }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) -> assertClose((b - a).length(), 0.0, 1e-9, "an arc moved: $b -> $a") }
    }

    /** The rider's own parameter survives being reloaded from the v2 file it wrote — no drift on reopen. */
    @Test
    fun reopeningTheMigratedWheelDoesNotMoveTheRider() {
        var text = DocumentFormat.save(DocumentFormat.load(wheel))
        val first = DocumentFormat.load(text).let { riderParam(it, it.elements[17]) }
        repeat(3) {
            val doc = DocumentFormat.load(text)
            assertClose(riderParam(doc, doc.elements[17]), first, 0.0, "the parameter is bit-identical on every reopen")
            text = DocumentFormat.save(doc)
        }
        assertNotNull(text)
    }
}
