package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.PointRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The in-place sweep: a section is swept from where it is drawn** (OP-26, step 2 extended — the user's own
 * design).
 *
 * The report. A pillar, a vertical sketch plane cut through it, and a foundation profile constructed **in
 * place** in that plane — sitting on the ground, hugging the pillar's wall, and therefore drawn 21…33 mm off
 * that plane's origin, because that is where the material is. Swept along the pillar's own bottom border with
 * nothing picked, the body came out valid and **floating**: `z ∈ [21.68, 33.16]` instead of standing on the
 * ground, because *"no anchor"* meant *"the plane's origin rides the run"* and the section's own plane
 * coordinates were carried into the moving frame verbatim.
 *
 * The user's reading, adopted: *"Shouldn't the point where the space curve intersects the outline plane be
 * used as the anchor point that sweeps the plane? … the outline intersects potentially multiple times — what
 * about using the one that is nearest to the constructed outline?"*
 *
 * So a picked-nothing sweep whose run **pierces the section's own plane** now rides that crossing, with the
 * frame seeded there from the plane's own axes — the drawing *is* the run's section at that place. Which
 * crossing is a choice scored once and recorded (`signs=`); *where* it is stays a live value the body follows.
 *
 * What this pins, in order: the old file keeps the old reading for ever (OP-18 — a stored literal's meaning is
 * frozen, and an absent one is a literal too); the same drawing swept again sits on the ground; the drawn
 * outline is literally a section of the body; the choice is in the step and is never re-scored; a picked point
 * still wins; a run that crosses nothing keeps the origin reading and says so; the crossing going away is
 * named invalidity that heals; and the tube gains nothing.
 */
class InPlaceSweepTest {
    // ---- helpers ----

    private fun named(
        doc: Document,
        name: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
            "the drawing has $name",
        )

    private fun theRun(doc: Document): Element = doc.elements.first { it.kind == ElementKind.SPACE_CURVE }

    private fun theSection(doc: Document): Element = doc.elements.first { it.kind == ElementKind.OUTLINE }

    private fun lastSolid(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    /** A mesh's extent in the three axes — how a swept body is measured here. */
    private class Bounds(mesh: Mesh3) {
        val xs = mesh.vertices.map { it.x }
        val ys = mesh.vertices.map { it.y }
        val zs = mesh.vertices.map { it.z }
    }

    /**
     * How far [p] stands from the **boundary** of [mesh] — the closest point of the closest triangle.
     *
     * The honest reading of *"this point is a point of the swept body's surface"*: not a plane test and not a
     * vertex test, since the drawn outline's corners land in the middle of the band's own triangles.
     */
    private fun distanceToSurface(
        p: Vec3,
        mesh: Mesh3,
    ): Double =
        mesh.triangles.minOf { t ->
            (closestOnTriangle(p, mesh.vertices[t.a], mesh.vertices[t.b], mesh.vertices[t.c]) - p).length()
        }

    /** The closest point of triangle (a, b, c) to [p] — barycentric, with the six degenerate regions clamped. */
    private fun closestOnTriangle(
        p: Vec3,
        a: Vec3,
        b: Vec3,
        c: Vec3,
    ): Vec3 {
        val ab = b - a
        val ac = c - a
        val ap = p - a
        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return a
        val bp = p - b
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return b
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) return a + ab * (d1 / (d1 - d3))
        val cp = p - c
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return c
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) return a + ac * (d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)))
        val denom = 1.0 / (va + vb + vc)
        return a + ab * (vb * denom) + ac * (vc * denom)
    }

    /** The corners of the section, in its own plane's coordinates — what a station-section is checked against. */
    private fun sectionCorners(doc: Document): List<Vec2> {
        val loop = (Evaluator().valueOf(theSection(doc).ref) as constructit.core.LoopValue).loop
        return loop.elements.map { constructit.geom.GeomMath.startOf(it) }
    }

    // ---- 1. the user's own file, verbatim ----

    /**
     * **The file the user sent loads with the reading it was written with**, floating and all — the frozen
     * literal (OP-18).
     *
     * This is the doctrine winning over the bug report, deliberately: the step records no crossing because no
     * build that wrote it had one to record, so the only thing it can mean is what it meant. A drawing that
     * came back different from the one that was saved would be a worse defect than the one being fixed, and a
     * silent behaviour change on load is exactly what the versioning rule forbids. What the user does to get
     * the new reading is one gesture — sweep it again — and the test below is that gesture.
     */
    @Test
    fun theUsersFileLoadsWithTheReadingItWasWrittenWith() {
        val doc = DocumentFormat.load(PILLAR_CIT)
        assertTrue(doc.loadNotes.isEmpty(), "nothing about this file is ambiguous: ${doc.loadNotes}")
        val solid = lastSolid(doc)
        assertNull(whyInvalid(solid), "the body the user saw is valid — the symptom was where it stood")
        val b = Bounds(meshOf(solid))
        assertManifold(meshOf(solid), "the user's floating foundation")
        assertClose(b.zs.min(), 21.680818540354267, 1e-9, "it floats, exactly as it did")
        assertClose(b.zs.max(), 33.16070346923052, 1e-9, "…by the section's own plane coordinates")
        assertClose(b.xs.min(), -53.88068910149195, 1e-9, "and the plan extent is the one that was reported")
        assertClose(b.ys.max(), 39.56150764184622, 1e-9, "…on both axes")
    }

    /** …and it writes itself back unchanged, with no crossing put into an older writer's mouth. */
    @Test
    fun theUsersFileRoundTripsByteEqualWithNoRecordedCrossing() {
        val once = DocumentFormat.save(DocumentFormat.load(PILLAR_CIT))
        assertEquals(atThisVersion(PILLAR_CIT), once, "the file is written back exactly as it came")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and again")
        assertTrue(
            once.lineSequence().first { it.startsWith("tool sweep") }.let { !it.contains("signs=") },
            "no reading is recorded for a step that recorded none",
        )
    }

    // ---- 2. the same drawing, swept again ----

    /**
     * The user's drawing with the sweep re-made **through the tool** — the gesture, so the step is recorded
     * and the choice with it (a direct call to [Document.sweepAlongCurve] builds the same body but writes no
     * journal entry, which is the half these tests are about).
     */
    private fun sweptAgain(doc: Document = DocumentFormat.load(PILLAR_CIT)): Pair<Document, Element> {
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val picks =
            Picks(
                emptyList(),
                listOf(theRun(doc), theSection(doc)),
                Vec2(21.798402744891664, -0.22838499184339317),
                listOf(Vec2(-4.5, -23.5), Vec2(21.798402744891664, -0.22838499184339317)),
            )
        doc.runTool(assertNotNull(Tools.byId(Tools.SWEEP)), picks, emptyList())
        return doc to lastSolid(doc)
    }

    /**
     * **The foundation sits on the ground** — the report, closed.
     *
     * The border crosses the section's plane twice (once at each of the two walls it is parallel to); the
     * crossing nearest the drawn section is the one at the wall the foundation hugs, and riding it puts the
     * section exactly where it was drawn: `z` from 0 to the section's own height, the plan extent the section's
     * own reach either side of the border.
     */
    @Test
    fun theFoundationSitsOnTheGroundWhenTheSweepIsMadeAgain() {
        val (doc, solid) = sweptAgain()
        assertNull(whyInvalid(solid), "the body is valid: ${doc.note}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the in-place foundation")
        val b = Bounds(mesh)
        assertClose(b.zs.min(), 0.0, 1e-9, "it sits on the ground, where it was drawn")
        assertClose(b.zs.max(), 17.880689101491953, 1e-9, "…and stands exactly as tall as the section is")
        // the section reaches 11.4799 mm out from the border, on every side of it
        assertClose(b.xs.min(), -47.47988492887625, 1e-9, "and hugs the border all the way round")
        assertClose(b.xs.max(), 26.875713220400847, 1e-9, "…on both x sides")
        assertClose(b.ys.min(), -34.97988492887625, 1e-9, "…and both y sides")
        assertClose(b.ys.max(), 33.16070346923052, 1e-9, "…the last of which is the section's own far edge")
    }

    /**
     * **The choice speaks** — the status line says where the section rides, which crossing of how many it is,
     * why that one, and what to do instead (the *refusals speak* rule, applied to a choice made for the user).
     */
    @Test
    fun theStatusLineSaysWhereTheSectionRidesAndWhatTheAlternativeIs() {
        val (doc, _) = sweptAgain()
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("riding where"), "it says the section rides a crossing: $note")
        assertTrue(note.contains("pierces plane1"), "…of which plane: $note")
        assertTrue(note.contains("crossing 2 of 2, the one nearest the section"), "…which one and why: $note")
        assertTrue(note.contains("pick a point of the section to ride it elsewhere"), "…and the alternative: $note")
    }

    /**
     * **The drawn outline is a section of the swept body** — the whole claim of the seeded frame, asserted the
     * only way that means anything: every corner of the drawing, mapped into the world by its own plane, is a
     * point of the body's surface.
     *
     * The tolerance is stated rather than chosen for comfort. The run is a polyline and the section a polygon,
     * so the band the drawing lies in is **flat** and the corners land on it to the last bits of a double; the
     * assertion is made at 0.5 mm — the scale a mesh tolerance lives at here ([GeomMath.TESS_TOL_MM] is
     * 0.02 mm, and the anchored sweep's own fixture asserts 0.4627 mm) — and then again at 1e-9 mm, so what
     * the reading actually achieves is on the record and not only what it promises.
     *
     * And the pierce is **mid-run**: 122 mm along a 193 mm loop, two thirds of the way round and inside a
     * piece rather than at a station, which is the case a frame seeded only at a run's start could not do.
     */
    @Test
    fun theDrawnOutlineIsASectionOfTheSweptBodyMidRun() {
        val (doc, solid) = sweptAgain()
        val plane = planeOf(doc, "plane1")
        val mesh = meshOf(solid)
        val off = sectionCorners(doc).map { distanceToSurface(plane.toWorld(it), mesh) }
        assertTrue(off.max() <= 0.5, "every corner of the drawing is a point of the body: ${off.max()} mm off")
        assertTrue(off.max() <= 1e-9, "…and on a straight run through a polygon it is exact: ${off.max()} mm off")

        val run = (Evaluator().valueOf(theRun(doc).ref) as constructit.core.Path3Value).path
        val hits = constructit.geom.Pierce3.crossings(run, plane)
        assertEquals(2, hits.size, "the border crosses the section's plane twice")
        assertClose(hits[1].s, 122.27456097764116, 1e-6, "and the one it rides is two thirds of the way round")
        assertTrue(hits[1].t > 0.0 && hits[1].t < 1.0, "inside a piece of the run, not at a station of it")
    }

    /**
     * **The choice is in the step, and the drawing round-trips byte-equal** — a scored choice persisted at
     * creation, never re-scored on replay (OP-1/OP-18).
     */
    @Test
    fun theRecordedCrossingRidesTheStepAndTheDrawingRoundTripsByteEqual() {
        val (doc, solid) = sweptAgain()
        val script = DocumentFormat.save(doc)
        val step = script.lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(step.contains("signs=1"), "the crossing it chose is written down: $step")
        assertEquals(script, DocumentFormat.save(DocumentFormat.load(script)), "and the drawing round-trips byte-equal")

        val back = DocumentFormat.load(script)
        assertTrue(back.loadNotes.isEmpty(), "nothing is ambiguous about it: ${back.loadNotes}")
        val there = Bounds(meshOf(lastSolid(back)))
        val here = Bounds(meshOf(solid))
        assertClose(there.zs.min(), here.zs.min(), 1e-12, "and it comes back standing where it stood")
        assertClose(there.zs.max(), here.zs.max(), 1e-12, "…exactly")
    }

    /**
     * **The other crossing is a choice the file can state** — which is what makes it a recorded choice rather
     * than a nearness: change the index by hand and the section rides the far side of the pillar, with the
     * drawing standing at *that* crossing instead.
     */
    @Test
    fun theOtherCrossingIsAChoiceTheFileStates() {
        val t = twoCrossings()
        val here = Bounds(meshOf(sweepGesture(t.doc, t.run, t.section)))
        val far = DocumentFormat.save(t.doc).replace("signs=1", "signs=0")
        val other = DocumentFormat.load(far)
        val solid = lastSolid(other)
        assertNull(whyInvalid(solid), "the far crossing builds too: ${other.note}")
        assertManifold(meshOf(solid), "the section riding the far crossing")
        // the two crossings stand 85 mm apart along the plane, so riding the other one reads the section from
        // 85 mm away — here inside the loop rather than beside it, so the ring comes out that much *narrower*.
        // A different solid, which is the whole point: an index in the file, not a nearness re-measured.
        val there = Bounds(meshOf(solid))
        assertTrue(kotlin.math.abs(span(there) - span(here)) > 100.0, "a different body: ${span(there)} vs ${span(here)}")
        assertTrue(other.loadNotes.isEmpty(), "and the file said which one, so nothing was guessed: ${other.loadNotes}")
    }

    /**
     * **A picked point still supersedes everything** — the anchored sweep is untouched, and a step that states
     * an anchor records no crossing, because the anchor *is* the record.
     */
    @Test
    fun aPickedPointSupersedesTheCrossing() {
        val doc = DocumentFormat.load(PILLAR_CIT)
        doc.activeSpace = assertNotNull(doc.spaceNamed("plane1"))
        val anchor: PointRef = doc.freePoint(21.680818540354267.mm, 0.0.mm)
        val solid =
            assertNotNull(
                doc.sweepAlongCurve(theRun(doc), theSection(doc), null, null, anchor),
                "the anchored sweep was built: ${doc.note}",
            )
        assertTrue(assertNotNull(doc.note).contains("riding on"), "and it says so: ${doc.note}")
        val step = DocumentFormat.save(doc).lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(!step.contains("signs="), "a stated anchor records no crossing: $step")
        assertNull(whyInvalid(solid), "and it is a body")
        assertManifold(meshOf(solid), "the anchored foundation")
        // **and it means exactly what it meant before**, which is the point: an anchor states which *point* of
        // the section travels and nothing about which way is up, so the frame is still the run's own space's
        // — the section's own x reads as height here, as it always did. The anchor is placed at the very point
        // the crossing is, so the *only* difference from the in-place body is the frame, and it is total.
        val anchored = Bounds(meshOf(solid))
        assertClose(anchored.zs.min(), 0.0, 1e-9, "read from the anchor, the section starts at the run")
        assertClose(anchored.zs.max(), 11.479884928876253, 1e-9, "…and stands its own *width* up, not its height")
        val (_, inPlace) = sweptAgain()
        assertClose(Bounds(meshOf(inPlace)).zs.max(), 17.880689101491953, 1e-9, "where the in-place reading stands it as drawn")
    }

    /**
     * **A roll turns the section about the run where it rides it** — the seed states which way is up, and roll
     * and twist are what they always were, applied on top of it.
     */
    @Test
    fun aRollTurnsTheSectionAboutTheRunAtTheCrossing() {
        val doc = DocumentFormat.load(PILLAR_CIT)
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val quarter = doc.newParameter("roll", Quantity.deg(90.0))
        val solid = assertNotNull(doc.sweepAlongCurve(theRun(doc), theSection(doc), quarter.ref))
        assertNull(whyInvalid(solid), "a rolled in-place sweep is still a body: ${doc.note}")
        assertManifold(meshOf(solid), "the rolled foundation")
        // the section stood 0…17.88 up and 0…11.48 out from the run; a quarter turn about the run swaps them
        val b = Bounds(meshOf(solid))
        assertClose(b.zs.max() - b.zs.min(), 11.479884928876253, 1e-6, "what stood up now lies across the run")
        val (_, plain) = sweptAgain()
        val was = Bounds(meshOf(plain))
        assertClose(was.zs.max() - was.zs.min(), 17.880689101491953, 1e-6, "…where unrolled it stood its own height")
    }

    /**
     * **A twist still has to close a closed run's frame**, in-place or not — the seed moves where the one
     * stated direction is stated and changes nothing about the loop's own holonomy.
     */
    @Test
    fun aTwistStillHasToCloseAClosedRunsFrame() {
        val doc = DocumentFormat.load(PILLAR_CIT)
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val twist = doc.newParameter("twist", Quantity.deg(30.0))
        val solid = assertNotNull(doc.sweepAlongCurve(theRun(doc), theSection(doc), null, twist.ref))
        val why = assertNotNull(whyInvalid(solid), "a third of a turn does not come back to itself")
        assertTrue(why.contains("does not come back to itself"), "and says so: $why")
        assertTrue(why.contains("state a twist of"), "…with the twist that would close it: $why")
    }

    /** **One undo takes the whole gesture** — the solid and the choice recorded with it. */
    @Test
    fun oneUndoTakesTheWholeInPlaceSweep() {
        val ed = constructit.editor.Editor(DocumentFormat.load(PILLAR_CIT))
        val before = DocumentFormat.save(ed.doc)
        val solids = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        sweptAgain(ed.doc)
        ed.checkpoint()
        assertTrue(DocumentFormat.save(ed.doc).contains("signs=1"), "the gesture recorded its crossing")
        assertTrue(ed.undo(), "one undo")
        assertEquals(solids, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "and the solid is gone")
        assertEquals(before, DocumentFormat.save(ed.doc), "…and the choice recorded with it")
    }

    // ---- 3. the readings a crossing does not have ----

    /**
     * A plan run and a plan section: the run **lies in** the section's plane, so it crosses it nowhere and the
     * section's own origin rides it — the reading every drawing had before, kept for the case that still means
     * it, and **said** rather than left to be discovered.
     */
    @Test
    fun aRunThatCrossesNothingKeepsTheOriginReadingAndSaysSo() {
        val doc = Document()
        val a = doc.freePoint((-40.0).mm, (-25.0).mm)
        val b = doc.freePoint(40.0.mm, (-25.0).mm)
        val c = doc.freePoint(40.0.mm, 25.0.mm)
        val d = doc.freePoint((-40.0).mm, 25.0.mm)
        val corners = listOf(a, b, c, d).map { assertNotNull(doc.elementFor(it)) }
        val run = assertNotNull(doc.curveThroughPoints(corners + corners.first(), false), doc.note)
        val centre = doc.freePoint(5.0.mm, 3.0.mm)
        val rim = doc.freePoint(7.0.mm, 3.0.mm)
        val section = doc.circle(centre, rim)

        val solid = assertNotNull(doc.sweepAlongCurve(run, section), "the sweep was built: ${doc.note}")
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("own origin riding the run"), "the origin reading is named: $note")
        assertTrue(note.contains("does not cross"), "…with the reason it is the one: $note")
        assertNull(whyInvalid(solid), "and it is a body")
        assertManifold(meshOf(solid), "a plan section on a plan run")
        // the section is drawn 5 mm out and 3 mm along from the plan's origin, and the plan's own normal
        // starts the frame — so it rides the run 5 mm up and 3 mm to one side of it, as it always did
        val bounds = Bounds(meshOf(solid))
        // a circle is swept as the polygon of chords it is tessellated into, so its extremes stand a chord's
        // sagitta inside the true ones — the 0.05 mm this allows is that, and nothing else
        assertClose(bounds.zs.min(), 3.0, 0.05, "the section's own x reads as height, off the origin")
        assertClose(bounds.zs.max(), 7.0, 0.05, "…by exactly what it was drawn off it")
    }

    /** **The tube gains nothing from a crossing**, for the reason it gained no anchor: it has no drawn section
     *  to be in place, and a circle about the run is already where it belongs. */
    @Test
    fun theTubeGainsNothingFromACrossing() {
        val doc = DocumentFormat.load(PILLAR_CIT)
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val r = doc.newParameter("tube", Quantity.mm(3.0))
        val picks = Picks(emptyList(), listOf(theRun(doc)), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0)))
        doc.runTool(assertNotNull(Tools.byId(Tools.TUBE)), picks, listOf(r))
        val tube = lastSolid(doc)
        assertNull(whyInvalid(tube), "the tube is a body: ${doc.note}")
        assertManifold(meshOf(tube), "a tube along the pillar's border")
        val step = DocumentFormat.save(doc).lineSequence().last { it.startsWith("tool tube") }
        assertTrue(!step.contains("signs="), "and it records no crossing, because it has none to make: $step")
        val b = Bounds(meshOf(tube))
        assertClose(b.zs.min(), -3.0, 1e-9, "its section is centred on the run, by definition")
        assertClose(b.zs.max(), 3.0, 1e-9, "…on both sides of it")
    }

    // ---- 4. several crossings: the one nearest the section, and it stays that one ----

    /** A rectangle in the plan, a vertical plane cutting it in two places, and a small round section drawn in
     *  that plane beside one of the two crossings. */
    private class TwoCrossings(val doc: Document) {
        lateinit var run: Element
        lateinit var section: Element
        lateinit var hingeA: Element
        lateinit var hingeB: Element
        lateinit var centre: Element
    }

    /** Run a tool over element picks, so the drawing this test builds is one a file can hold. */
    private fun runElements(
        doc: Document,
        id: String,
        picks: List<Element>,
    ) = doc.runTool(
        assertNotNull(Tools.byId(id)),
        Picks(emptyList(), picks, Vec2(0.0, 0.0), picks.map { Vec2(0.0, 0.0) }),
        emptyList(),
    )

    private fun twoCrossings(): TwoCrossings {
        val doc = Document()
        val t = TwoCrossings(doc)
        val corners =
            listOf(
                doc.freePoint((-200.0).mm, (-150.0).mm),
                doc.freePoint(200.0.mm, (-150.0).mm),
                doc.freePoint(200.0.mm, 150.0.mm),
                doc.freePoint((-200.0).mm, 150.0.mm),
            ).map { assertNotNull(doc.elementFor(it)) }
        runElements(doc, Tools.CURVE3, corners + corners.first())
        t.run = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        // a plane cutting the loop **across one corner**, so its two crossings are near each other: a section
        // riding either of them stands well inside the loop's own size, which is what keeps both readings
        // ordinary bodies and the comparison between them about the choice rather than about degeneracy
        val h1 = doc.freePoint(100.0.mm, 190.0.mm)
        val h2 = doc.freePoint(220.0.mm, 70.0.mm)
        t.hingeA = assertNotNull(doc.elementFor(h1))
        t.hingeB = assertNotNull(doc.elementFor(h2))
        doc.runTool(assertNotNull(Tools.byId(Tools.LINE)), Picks(listOf(h1, h2), emptyList(), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0))), emptyList())
        val hinge = doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"), "the datum stands on the line")
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))
        // where the run crosses that plane, in the plane's own coordinates — the section is drawn beside one
        // of them, which is the whole of what "the one nearest the drawing" means
        val plane = planeOf(doc, "cut")
        val at = plane.toLocal(Vec3(140.0, 150.0, 0.0))
        val centre = doc.freePoint((at.x + 4.0).mm, 6.0.mm)
        t.centre = assertNotNull(doc.elementFor(centre))
        // a *radius* circle, so dragging its centre moves the section rigidly instead of resizing it
        doc.runTool(
            assertNotNull(Tools.byId(Tools.CIRCLE_R)),
            Picks(listOf(centre), emptyList(), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0))),
            listOf(doc.newParameter("rad", Quantity.mm(3.0))),
        )
        t.section = doc.elements.last { it.kind == ElementKind.CIRCLE }
        return t
    }

    /**
     * **The crossing nearest the drawing is the one taken, and a later edit never re-takes it** (OP-1).
     *
     * The run crosses the section's plane twice, the section is drawn beside one of them, and that one is
     * chosen and written into the step. Dragging the section afterwards — right past the middle, so the *other*
     * crossing is now the nearer one — moves the body with it and does not move it to the other crossing: the
     * choice was scored once, at the gesture, and is read back rather than re-decided.
     */
    @Test
    fun theNearestCrossingIsTakenOnceAndNotReTakenWhenTheDrawingDrifts() {
        val t = twoCrossings()
        val solid = sweepGesture(t.doc, t.run, t.section)
        val note = assertNotNull(t.doc.note)
        assertTrue(note.contains("crossing 2 of 2, the one nearest the section"), "it says which of the two: $note")
        assertNull(whyInvalid(solid), "and it is a body")
        assertManifold(meshOf(solid), "a round section riding a crossing")
        val before = Bounds(meshOf(solid))
        val recorded = DocumentFormat.save(t.doc).lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(recorded.contains("signs=1"), "the crossing is recorded: $recorded")
        // the other crossing is a different body, and while the section stands between the two both readings
        // are ordinary solids — which is what makes the comparison about the *choice*
        val otherBefore = Bounds(meshOf(assertNotNull(t.doc.sweepAlongCurve(t.run, t.section, pierce = 0))))
        assertTrue(
            abs(span(before) - span(otherBefore)) > 100.0,
            "the other crossing gives a different ring: ${span(before)} vs ${span(otherBefore)}",
        )

        // drag the section 120 mm along the plane — well past the middle, so the *other* crossing is the
        // nearer one now (the two stand 85 mm apart along it)
        assertNotNull(t.centre.handle, "the section's centre is draggable")
            .drag(assertNotNull(positionOf(t.centre)) + Vec2(-120.0, 0.0), Evaluator())
        assertEquals(
            recorded,
            DocumentFormat.save(t.doc).lineSequence().last { it.startsWith("tool sweep") },
            "the step still records the crossing it scored, unchanged",
        )
        // …and so does the body. Which crossing it rides is not visible in the drawing's own plane — the
        // section stands where it is drawn either way — so it is asserted where it *is* visible: the section
        // now stands 116 mm off the crossing it rides instead of 4, and the ring is that much wider than it
        // was.
        val after = Bounds(meshOf(solid))
        assertTrue(span(after) - span(before) > 200.0, "the ring widened with the drawing it still reads: ${span(after)}")
        // And it is emphatically not the body the other crossing would now give. Dragged this far, the *other*
        // reading stands the section 204 mm inside a loop whose own inradius is 150, so the ring it would
        // sweep folds through itself — which the embedding criterion refuses by name (OP-9, the antiparallel
        // legs of a plan loop; session 59). Before the drag the same comparison was two ordinary bodies,
        // asserted above: what changed is the geometry, not the recorded choice.
        val flipped = assertNotNull(t.doc.sweepAlongCurve(t.run, t.section, pierce = 0))
        val why = assertNotNull(whyInvalid(flipped), "the other crossing would fold the ring through itself")
        assertTrue(why.contains("cut into itself"), "and says so by name: $why")
    }

    /** A sweep made **through the tool**, so the step and its recorded crossing exist. */
    private fun sweepGesture(
        doc: Document,
        run: Element,
        section: Element,
    ): Element {
        val picks = Picks(emptyList(), listOf(run, section), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0)))
        doc.runTool(assertNotNull(Tools.byId(Tools.SWEEP)), picks, emptyList())
        return lastSolid(doc)
    }

    /** How wide the body stands in the plan — what the section's offset from its crossing shows up as. */
    private fun span(b: Bounds): Double = (b.xs.max() - b.xs.min()) + (b.ys.max() - b.ys.min())

    private fun positionOf(el: Element): Vec2? =
        ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? constructit.core.PointValue)?.p

    /**
     * **When the crossing it rides goes away, the sweep says so and heals** (OP-3) — never a silent re-choice
     * of a neighbouring crossing, and never a gesture refusal on a live number.
     */
    @Test
    fun theSweepSaysSoWhenTheCrossingItRidesGoesAwayAndHealsWhenItComesBack() {
        val t = twoCrossings()
        val solid = assertNotNull(t.doc.sweepAlongCurve(t.run, t.section), "the sweep was built: ${t.doc.note}")
        assertNull(whyInvalid(solid), "a body to begin with")

        // slide the plane off the end of the run: it now crosses nothing at all
        val ev = Evaluator()
        assertNotNull(t.hingeA.handle).drag(Vec2(300.0, -80.0), ev)
        assertNotNull(t.hingeB.handle).drag(Vec2(300.0, 80.0), Evaluator())
        val why = assertNotNull(whyInvalid(solid), "the crossing it rides is gone")
        assertTrue(why.contains("rides the run where it crosses"), "and the node names what it was riding: $why")
        assertTrue(why.contains("does not cross that plane at all any more"), "…and what became of it: $why")
        assertTrue(why.contains("sweep the section again"), "…and the way out: $why")

        // and back where it was, exactly
        assertNotNull(t.hingeA.handle).drag(Vec2(100.0, 190.0), Evaluator())
        assertNotNull(t.hingeB.handle).drag(Vec2(220.0, 70.0), Evaluator())
        assertNull(whyInvalid(solid), "and it heals the moment the run crosses there again")
        assertManifold(meshOf(solid), "the healed sweep")
    }

    /** A run that crosses **once** says so without counting, because there is nothing to count. */
    @Test
    fun aSingleCrossingIsNamedWithoutACount() {
        val doc = Document()
        val a = doc.freePoint((-50.0).mm, 0.0.mm)
        val b = doc.freePoint(50.0.mm, 0.0.mm)
        val run =
            assertNotNull(
                doc.curveThroughPoints(listOf(assertNotNull(doc.elementFor(a)), assertNotNull(doc.elementFor(b))), false),
                doc.note,
            )
        val hinge = doc.line(doc.freePoint(0.0.mm, (-40.0).mm), doc.freePoint(0.0.mm, 40.0.mm))
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"))
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))
        val plane = planeOf(doc, "cut")
        val at = plane.toLocal(Vec3(0.0, 0.0, 0.0))
        val section = doc.circle(doc.freePoint((at.x + 3.0).mm, 5.0.mm), doc.freePoint((at.x + 6.0).mm, 5.0.mm))

        val solid = assertNotNull(doc.sweepAlongCurve(run, section), doc.note)
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("riding where"), "the crossing is named: $note")
        assertTrue(!note.contains("crossing 1 of 1"), "and one of one is not a count worth printing: $note")
        assertNull(whyInvalid(solid), "and it is a body: $note")
        assertManifold(meshOf(solid), "a bar riding a single crossing")
        // the section is drawn 3 mm off the crossing along the plane and 5 mm up it, so the bar stands there
        val b2 = Bounds(meshOf(solid))
        assertClose(min(b2.zs.min(), 0.0), 0.0, 1e-9, "the section stands where it is drawn")
        assertClose(max(b2.zs.max(), 8.0), 8.0, 1e-9, "…5 mm up, 3 mm radius")
    }

    // ---- the fixture ----

    companion object {
        /**
         * **The user's own drawing, verbatim** (OP-18's fixture rule): a pillar extruded from an ortho outline,
         * a vertical sketch plane cut through it, a foundation profile constructed in place in that plane from
         * the pillar's own section, the pillar's bottom border as a closed curve in the plan, and the sweep —
         * with no anchor pick — that came out floating.
         */
        val PILLAR_CIT =
            """
            constructit 2
            orthostart -36,21.680818540354267 -> e1
            orthovertex 15.395828291524595,21.680818540354267 -> e2,e3
            orthovertex 15.395828291524595,-23.5 -> e4,e5
            orthovertex -36,-23.5 -> e6,e7
            orthoclose -> e8
            param "r" = 200mm
            tool extrude els=e7 clicks=-6.75,-23.5 scalar="r" -> e9
            tool perpbis pts=e1,e2 clicks=-35,32.25;22.25,32.25 -> e10
            param "angle" = 90deg
            sketchspace "plane1" line=e10 angle="angle"
            sectioninput "plane1" el=e9 edge=3 -> e11
            tool keypoints els=e11 clicks=31.667210440456785,44.143556280587276 -> e12,e13
            sectioninput "plane1" el=e9 edge=4 -> e14
            tool keypoints els=e14 clicks=14.179445350734108,0.032626427406199025 -> e15,e16
            tool line pts=e16,e15 clicks=-24.189233278955943,-0.4893964110929853;30.623164763458416,0.2936378466557912 -> e17
            pointoncurve e17 54.37520391517131,0 dofs=33.16070346923052mm -> e18
            tool segment pts=e15,e18 clicks=31.928221859706376,-0.4893964110929853;54.375203915171305,0.5546492659053834 -> e19
            pointoncurve e11 31.5,24.30668841761826 dofs=-17.880689101491953mm -> e20
            tool perp pts=e18 els=e17 clicks=83.08646003262645,0.032626427406199025;54.636215334420896,0.5546492659053834 -> e21
            pointoncurve e21 54.37520391517131,14.38825448613377 dofs=10.055509683729293mm -> e22
            tool segment pts=e18,e22 clicks=55.68026101141926,-0.7504078303425775;53.592169657422524,14.38825448613377 -> e23
            tool segment pts=e22,e20 clicks=53.592169657422524,14.38825448613377;30.36215334420882,24.567699836867863 -> e24
            tool segment pts=e20,e15 clicks=32.45024469820556,22.479608482871125;32.45024469820556,0.032626427406199025 -> e25
            tool outline els=e25,e24,e23,e19 clicks=32.18923327895597,9.951060358890702;41.585644371941285,19.869494290375204;54.37520391517131,7.194127243066885;42.937601957585656,0 -> e26,e27,e28,e29,e30
            space "plan"
            tool curve3 els=e6,e4,e2,e1,e6 clicks=-35.75,-24.5;21.75,-23.75;22,31.5;-36.25,30.25;-36,-25 -> e31
            space "plane1"
            tool makerel els=e18,e15 clicks=43.18857981128731,0.2107641895467509;31.205913150809764,0.5235088309397611 dofs=11.479884928876253mm
            space "plan"
            name e31 "border"
            space "plane1"
            tool sweep els=e31,e30 clicks=-4.5,-23.5;21.798402744891664,-0.22838499184339317 dofs=0deg;0deg -> e32
            space "plan"
            material e32 color=#9141ac rough=0.6 metal=0.1
            """.trimIndent() + "\n"
    }
}
