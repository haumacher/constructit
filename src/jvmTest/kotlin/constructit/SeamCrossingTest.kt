package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Curves3
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Pierce3
import constructit.geom.Plane3
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A closed run's seam is a crossing like any other** (OP-26's in-place sweep, OP-18's frozen literals) —
 * the defect a probe found in session 61, queued rather than patched because the fix renumbers a stored
 * literal, and closed here with the version bump that renumbering owes.
 *
 * [Pierce3.crossings] follows the signed distance along the sampled parameter and emits a crossing where the
 * **sign changes**, skipping samples that sit exactly on the plane — which is right for a run that comes down
 * to the plane and turns back. On a **closed** run, though, the seam is not an end: the run goes on through
 * it. The walk compared every consecutive pair *except* the one that spans the run's own start, so a loop
 * whose start point sits on the plane and passes through it changed side at a place nothing ever looked at.
 *
 * The reproduction the queue entry recorded, verbatim: *a circle drawn in the plan lifts to one `Arc3`
 * starting at its plane's own +u, so a datum standing on the **x axis** through that circle reports one
 * crossing where a ring plainly has two.*
 *
 * **The format half is the reason this waited.** A seam crossing has arc length 0, so it enters the ordered
 * set at index **0** and every recorded `tool sweep signs=` on a drawing whose plane crosses the seam now
 * names the crossing one place further along. That is a stored literal changing meaning, so it is a version
 * bump plus a migration — format **3**, with the recorded index shifted on load by exactly the crossing this
 * build sees and the writing build did not. The migration is exact, and the test that matters is the last
 * kind: an old file goes on riding the crossing it rode, and reading its number verbatim would have taken the
 * user's body away.
 */
class SeamCrossingTest {
    // ---- helpers ----

    private fun pathOf(el: Element): Path3 = (Evaluator().valueOf(el.ref) as Path3Value).path

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    private fun theRun(doc: Document): Element = doc.elements.first { it.kind == ElementKind.SPACE_CURVE }

    private fun lastSolid(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /**
     * The user's ring, at format **2**, with the crossing the writing build scored: one closed run (a circle
     * lifted whole), a datum standing on the **x axis** — so the run's seam at `(50, 0, 0)` lies exactly in
     * it — and a small section drawn in that datum beside the *far* crossing.
     *
     * Hand-written in the shape a pre-bump build wrote it, and kept that way: an in-build round trip proves
     * nothing across builds (OP-18). `signs=0` is what that build recorded, because its own walk found the
     * far crossing and nothing else, so the far crossing was crossing number one.
     */
    private val ringAtFormatTwo =
        """
constructit 2
point 0,0 -> e1
point 50,0 -> e2
tool circle pts=e1,e2 clicks=0,0;50,0 -> e3
point -80,0 -> e4
point 80,0 -> e5
tool segment pts=e4,e5 clicks=-80,0;80,0 -> e6
tool lift els=e3 clicks=50,0 -> e7
param "angle" = 90deg
sketchspace "cut" line=e6 angle="angle"
point -50,8 -> e8
point -46,8 -> e9
tool circle pts=e8,e9 clicks=-50,8;-46,8 -> e10
tool sweep els=e7,e10 clicks=50,0;-50,8 signs=0 dofs=0deg;0deg -> e11
""".trimStart()

    /** The extent of the ring the format-2 build built: radius 50, a 4 mm section standing 8 mm up the datum. */
    private fun assertIsTheFarCrossingsRing(
        mesh: Mesh3,
        what: String,
    ) {
        assertClose(mesh.vertices.minOf { it.x }, -54.0, 0.05, "$what: the ring reaches one section-radius past the run")
        assertClose(mesh.vertices.maxOf { it.x }, 54.0, 0.05, "$what: …on the other side too")
        assertClose(mesh.vertices.minOf { it.z }, 4.0, 0.05, "$what: and stands 8 mm up the datum, 4 mm across")
        assertClose(mesh.vertices.maxOf { it.z }, 12.0, 0.05, "$what: …on both sides of its own centre")
    }

    // ---- the geometry: the seam is seen ----

    /**
     * **The queue entry's own reproduction**: a circle in the plan lifts to one closed `Arc3` starting at its
     * plane's own +u, and a datum standing on the **x axis** goes through that very point.
     *
     * Both crossings are exact and are the ring's two ends of a diameter: the seam at `(50, 0, 0)` with no arc
     * length at all, and the far one at `(-50, 0, 0)` half the circumference along. Before the wrap comparison
     * this reported the far one alone.
     */
    @Test
    fun aRingWhoseSeamLiesInThePlaneCrossesItTwice() {
        val doc = ringDrawing()
        val path = pathOf(theRun(doc))
        assertTrue(path.closed, "a lifted circle is a closed run, by its kind")
        assertEquals(1, path.elements.size, "of one arc, so the seam is the only hand-over there is")

        val plane = planeOf(doc, "cut")
        val hits = Pierce3.crossings(path, plane)
        assertEquals(2, hits.size, "a ring crosses a plane through it twice, seam or no seam: $hits")
        assertTrue(Pierce3.crossesAtSeam(path, plane), "and the first of them is the seam")

        assertEquals(0, hits[0].piece, "the seam is the run's own start")
        assertClose(hits[0].t, 0.0, 0.0, msg = "at parameter nothing")
        assertClose(hits[0].s, 0.0, 0.0, msg = "and at no arc length, which is what puts it first")
        assertClose(hits[0].at.x, 50.0, 1e-12, "standing where the circle was drawn to start")
        assertClose(hits[0].at.y, 0.0, 1e-12, "…on the plane it crosses")

        assertClose(hits[1].at.x, -50.0, 1e-9, "and the far crossing is the other end of that diameter")
        assertClose(hits[1].s, PI * 50.0, 1e-9, "half the circumference along")
    }

    /**
     * **The count the status line names is the count there is.** The sweep's own sentence says *"crossing k of
     * n"*, and `n` was one short on exactly this drawing.
     */
    @Test
    fun theStatusLineCountsBothCrossings() {
        val doc = ringDrawing()
        val section = doc.circle(doc.freePoint((-50.0).mm, 8.0.mm), doc.freePoint((-46.0).mm, 8.0.mm))
        assertNotNull(doc.sweepAlongCurve(theRun(doc), section), doc.note)
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("crossing 2 of 2"), "the ring crosses twice and the sentence says so: $note")
    }

    /**
     * **Analytic truth, on a run with no arithmetic in it at all**: a closed square standing upright, cut by
     * the plane through two of its opposite corners. It crosses there and nowhere else, so the crossings are
     * those two corners exactly — one of them the seam.
     */
    @Test
    fun aPlaneThroughAClosedSquaresSeamCornerCrossesItTwice() {
        val hits = Pierce3.crossings(squareLoop(closed = true), cornerPlane())
        assertEquals(2, hits.size, "two corners on the plane, and the run passes through at both: $hits")

        assertClose(hits[0].s, 0.0, 0.0, msg = "the seam corner comes first, at no arc length")
        assertClose((hits[0].at - Vec3(0.0, 0.0, 0.0)).length(), 0.0, 1e-12, "and it is the corner the run starts at")
        assertClose(hits[1].s, 200.0, 1e-9, "the opposite corner is two sides along")
        assertClose((hits[1].at - Vec3(100.0, 0.0, 100.0)).length(), 0.0, 1e-9, "and stands exactly there")
    }

    /**
     * **An open run is untouched**: the very same four pieces, not declared closed, and the wrap comparison
     * does not happen. [Path3.closed] is *structure* (OP-21) — it is never inferred from endpoints meeting —
     * so this is the same geometry read as a run with two ends, and its start is an end rather than a seam.
     */
    @Test
    fun anOpenRunOfTheSamePiecesIsUntouched() {
        val hits = Pierce3.crossings(squareLoop(closed = false), cornerPlane())
        assertEquals(1, hits.size, "the far corner alone: an end is not a seam, so nothing wraps: $hits")
        assertClose(hits[0].s, 200.0, 1e-9, "and it is the corner two sides along")
    }

    /**
     * **A touch at the seam is still not a crossing.** The ring's own plane, moved out to stand tangent to it
     * at exactly the seam: the run comes down to the plane there and turns back, changing no side.
     *
     * The rule that makes a plan curve lie *in* the plan without piercing it at every point, read round the
     * corner — and the case the wrap comparison could most easily have got wrong, since the seam sample is
     * exactly zero here too.
     */
    @Test
    fun aRingTangentToThePlaneAtItsSeamCrossesNothing() {
        val doc = ringDrawing()
        val path = pathOf(theRun(doc))
        val tangent = Plane3(Vec3(50.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        assertClose(tangent.distanceTo(Vec3(50.0, 0.0, 0.0)), 0.0, 1e-12, "the plane touches the ring at its seam")
        assertEquals(0, Pierce3.crossings(path, tangent).size, "a touch is not a crossing, at the seam as anywhere")
        assertTrue(!Pierce3.crossesAtSeam(path, tangent), "so there is no seam crossing to renumber anything")
    }

    /** The same, on a polyline — the old half of the defect, which the lift only made easy to reach. */
    @Test
    fun aClosedPolylineTouchingAtItsSeamCrossesNothing() {
        val corners = listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 100.0), Vec3(-100.0, 0.0, 100.0))
        val loop = Path3(Curves3.straightThrough(corners, closed = true), closed = true)
        val ground = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        assertEquals(0, Pierce3.crossings(loop, ground).size, "it rests on the plane at its seam and turns back")
    }

    // ---- the format: what an old file's recorded index means (OP-18) ----

    /**
     * **The one that matters: an old file goes on riding the crossing it rode.** The format-2 build's walk
     * found one crossing on this drawing and numbered it 0; this build's finds two and numbers that same one
     * 1. The load shifts the recorded index by exactly the crossing that was inserted in front of it, so the
     * body that comes back is the body that was saved — pinned by its own extent, not merely by being valid.
     *
     * Nothing is guessed and nothing is re-scored: the shift is read off the geometry the file itself
     * rebuilds, which is the geometry the old reader would have measured.
     */
    @Test
    fun aFormatTwoFileGoesOnRidingTheCrossingItRode() {
        val doc = DocumentFormat.load(ringAtFormatTwo)
        assertTrue(doc.loadNotes.isEmpty(), "the shift is exact, so there is nothing to be unsure about: ${doc.loadNotes}")
        val solid = lastSolid(doc)
        assertNull(whyInvalid(solid), "the drawing still builds its body")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the migrated ring")
        assertIsTheFarCrossingsRing(mesh, "the format-2 body")
    }

    /**
     * **…and reading that number verbatim would have taken the body away.** The same file with the same
     * number, told it is format 3: index 0 is now the *seam*, whose anchor stands 100 mm from where the
     * section is drawn, and the section outgrows the ring it would have to bend round.
     *
     * The point is not that this particular drawing refuses — it is that the two readings are different
     * drawings, which is precisely what makes the stored number's meaning something a version bump owes.
     */
    @Test
    fun readingThatNumberVerbatimWouldBeADifferentDrawing() {
        val asIfNew = ringAtFormatTwo.replaceFirst("constructit 2", "constructit 3")
        val solid = lastSolid(DocumentFormat.load(asIfNew))
        val why = assertNotNull(whyInvalid(solid), "the seam reading is a different drawing, and here it is no body at all")
        assertTrue(why.contains("pass through itself"), "and it says why, in the node's own words: $why")
    }

    /** The migrated file states the number it now means, and is a fixed point from that first save onwards. */
    @Test
    fun theMigratedFileStatesTheNewNumberAndIsThenStable() {
        val once = DocumentFormat.save(DocumentFormat.load(ringAtFormatTwo))
        assertEquals(
            atThisVersion(ringAtFormatTwo).replace("signs=0", "signs=1"),
            once,
            "the header comes up to format 3 and the recorded crossing with it — and no other line moves",
        )
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and it is a fixed point from there")
        assertIsTheFarCrossingsRing(meshOf(lastSolid(DocumentFormat.load(once))), "the re-saved body")
    }

    /** A number written by *this* build is already in this build's numbering and is taken as it stands. */
    @Test
    fun aFormatThreeNumberIsTakenAsItStands() {
        val current = atThisVersion(ringAtFormatTwo).replace("signs=0", "signs=1")
        assertEquals(current, DocumentFormat.save(DocumentFormat.load(current)), "no second shift, ever")
        assertIsTheFarCrossingsRing(meshOf(lastSolid(DocumentFormat.load(current))), "the format-3 body")
    }

    /**
     * **The origin reading is not an index and is never shifted.** A negative `signs=` is the record that the
     * section's own origin rides the run (session 58) — there is no crossing behind it to renumber.
     */
    @Test
    fun theOriginReadingIsNeverShifted() {
        val floating = ringAtFormatTwo.replace("signs=0", "signs=-1")
        val doc = DocumentFormat.load(floating)
        assertEquals(
            atThisVersion(floating),
            DocumentFormat.save(doc),
            "the header comes up to format 3 and the origin reading survives it verbatim",
        )
    }

    /**
     * **Where it cannot arbitrate it says so.** With no value for the run there is no way to tell whether this
     * drawing's numbering has moved, so the number is kept exactly as written — what the last writer meant —
     * and the load **names the element** rather than guessing quietly (OP-18's rule, as the v1 rider migration
     * follows it).
     *
     * The run here is a curve through two points standing in the same place, which is an element whose *value*
     * is invalid while the step itself replays perfectly well.
     */
    @Test
    fun aRunWithNoValueKeepsItsNumberAndTheLoadSaysSo() {
        val doc = DocumentFormat.load(brokenRunAtFormatTwo)
        val notes = doc.loadNotes
        assertEquals(1, notes.size, "one finding, about one element: $notes")
        assertTrue(notes[0].contains("no value right now"), "it says what it could not measure: ${notes[0]}")
        assertTrue(notes[0].contains("kept as it was written"), "and what it did instead: ${notes[0]}")
        assertTrue(
            DocumentFormat.save(doc).contains("signs=0"),
            "the number is written back exactly as it came:\n${DocumentFormat.save(doc)}",
        )
        assertTrue(assertNotNull(doc.note).contains("no value right now"), "and the load speaks: ${doc.note}")
    }

    // ---- fixtures ----

    /** The reproduction's drawing: a circle in the plan, lifted, and a datum standing on the **x axis**. */
    private fun ringDrawing(): Document {
        val doc = Document()
        val circle = doc.circle(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(50.0.mm, 0.0.mm))
        assertNotNull(doc.liftCurves(listOf(circle)), doc.note)
        val hinge = doc.line(doc.freePoint((-80.0).mm, 0.0.mm), doc.freePoint(80.0.mm, 0.0.mm))
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"), doc.note)
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))
        return doc
    }

    /**
     * A 100 mm square standing upright in the `y = 0` plane, starting at the origin and going round through
     * `(100, 0, 0)`, `(100, 0, 100)`, `(0, 0, 100)`.
     */
    private fun squareLoop(closed: Boolean): Path3 {
        val corners =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 0.0),
                Vec3(100.0, 0.0, 100.0),
                Vec3(0.0, 0.0, 100.0),
            )
        return Path3(Curves3.straightThrough(corners, closed = closed), closed = closed)
    }

    /**
     * The plane through the square's **diagonal** — its seam corner `(0, 0, 0)` and the opposite one
     * `(100, 0, 100)` — so the signed distance is `(z − x)/√2` and the run is on one side for two sides of the
     * square and on the other for the other two.
     */
    private fun cornerPlane(): Plane3 = Plane3(Vec3.ZERO, Vec3(1.0, 0.0, 1.0).normalized(), Vec3(0.0, 1.0, 0.0))

    /** The same shape of file, with a run whose value is invalid: two points of it stand in the same place. */
    private val brokenRunAtFormatTwo =
        """
constructit 2
point 0,0 -> e1
point 0,0 -> e2
tool curve3 els=e1,e2 clicks=0,0;0,0 -> e3
point -80,0 -> e4
point 80,0 -> e5
tool segment pts=e4,e5 clicks=-80,0;80,0 -> e6
param "angle" = 90deg
sketchspace "cut" line=e6 angle="angle"
point -50,8 -> e7
point -46,8 -> e8
tool circle pts=e7,e8 clicks=-50,8;-46,8 -> e9
tool sweep els=e3,e9 clicks=0,0;-50,8 signs=0 dofs=0deg;0deg -> e10
""".trimStart()
}
