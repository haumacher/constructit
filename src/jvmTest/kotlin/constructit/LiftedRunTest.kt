package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LoopValue
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Pierce3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The lift: a curve drawn in a plane is the run it already is** (OP-26, step 1's missing source), and the
 * **local** embedding criterion read in the direction a bend actually folds.
 *
 * The report. The user rebuilt their pillar with **rounded corners** — a `roundrect` footprint, 10 mm fillets
 * — and could not sweep at all: neither the rounded outline in the plan nor the extruded footprint would be
 * accepted as the sweep's *"curve in space"*, and the alternative the refusal named (*"draw the route with
 * Curve through points first"*) is a dead end, because a curve through points is a polyline and cannot follow
 * an arc.
 *
 * Two things were missing, and this pins both:
 *
 * - **the lift.** A curve in space had seven sources (through points, a helix, two views combined, a section,
 *   a connection, a projection onto a face, an imported wireframe) and not the trivial one: the curve already
 *   drawn, lying in the plane it was drawn in. Every `PATH3` slot now reads a drawn pick as the run it
 *   describes — exactly as a `POINT3` slot reads a 2D point as the point in space it is — and the *Lift
 *   drawing into space* tool names the same node where the run is wanted as an element of its own.
 * - **the local embedding term.** `κ·reach ≥ 1` measured the section's greatest distance from the run **in any
 *   direction**; what folds a sweep is what the section reaches **towards the centre of the bend**. The user's
 *   foundation reaches 18 mm outwards and 27 mm up and *nothing* towards the pillar, so every 10 mm fillet
 *   refused a body that could not touch itself. It is the identical correction session 59 made to the global
 *   term, made to the local one.
 */
class LiftedRunTest {
    // ---- helpers ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Two world points are the same place, to [tol] mm — the 3D twin of [assertClose]. */
    private fun assertVec3(
        actual: Vec3,
        expected: Vec3,
        tol: Double = 1e-9,
        msg: String = "",
    ) {
        assertTrue((actual - expected).length() <= tol, "expected $expected but was $actual. $msg")
    }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun planOutline(doc: Document): Element =
        doc.elements.first { it.kind == ElementKind.OUTLINE && it.space == Document.PLAN_SPACE }

    private fun section(doc: Document): Element =
        doc.elements.first { it.kind == ElementKind.OUTLINE && it.space == "plane1" }

    private fun lastSolid(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    private fun pathOf(el: Element): Path3 = (Evaluator().valueOf(el.ref) as Path3Value).path

    private class Bounds(mesh: Mesh3) {
        val xs = mesh.vertices.map { it.x }
        val ys = mesh.vertices.map { it.y }
        val zs = mesh.vertices.map { it.z }
    }

    /** How far [p] stands from the **boundary** of [mesh] — the closest point of the closest triangle. */
    private fun distanceToSurface(
        p: Vec3,
        mesh: Mesh3,
    ): Double =
        mesh.triangles.minOf { t ->
            (closestOnTriangle(p, mesh.vertices[t.a], mesh.vertices[t.b], mesh.vertices[t.c]) - p).length()
        }

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

    /** The user's gesture: click the plan outline, then the section — two clicks, no anchor. */
    private fun sweptRoundThePillar(doc: Document = DocumentFormat.load(ROUND_PILLAR_CIT)): Pair<Document, Element> {
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val picks =
            Picks(
                emptyList(),
                listOf(planOutline(doc), section(doc)),
                Vec2(42.0, 4.0),
                listOf(Vec2(-24.5, -27.0), Vec2(42.0, 4.0)),
            )
        doc.runTool(assertNotNull(Tools.byId(Tools.SWEEP)), picks, emptyList())
        return doc to lastSolid(doc)
    }

    // ---- 1. the user's own file ----

    /** **The file the user sent loads with nothing ambiguous, and writes itself back unchanged** (OP-18). */
    @Test
    fun theUsersFileLoadsCleanAndRoundTripsByteEqual() {
        val doc = DocumentFormat.load(ROUND_PILLAR_CIT)
        assertTrue(doc.loadNotes.isEmpty(), "nothing about this file is ambiguous: ${doc.loadNotes}")
        val once = DocumentFormat.save(doc)
        assertEquals(ROUND_PILLAR_CIT, once, "the file is written back exactly as it came")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and again")
    }

    /**
     * **The report, closed: the foundation sweeps round the rounded outline.**
     *
     * Two clicks — the plan's own outline and the section drawn in `plane1` — and nothing else. The outline is
     * read as the run it already is, the section rides where that run pierces its own plane (the in-place
     * sweep, session 58), and the body is a foundation standing on the ground round the whole pillar,
     * fillets and all.
     */
    @Test
    fun theRoundedOutlineIsTheRunAndTheFoundationSweepsRoundIt() {
        val (doc, solid) = sweptRoundThePillar()
        assertNull(whyInvalid(solid), "the body is valid: ${doc.note}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the foundation round the rounded pillar")
        assertTrue(constructit.geom.Geom3.volume(mesh) > 0.0, "and it is a solid the right way out: ${constructit.geom.Geom3.volume(mesh)}")

        val b = Bounds(mesh)
        // **it sits on the ground**: the section's lowest point in plane1 is y = 0, which is world z = 0
        assertClose(b.zs.min(), 0.0, 1e-9, "it stands on the ground, where the section was drawn")
        assertClose(b.zs.max(), 27.004059571993878, 1e-9, "…and exactly as tall as the section is")

        // **the plan extent is the rounded footprint offset by the section's own reach**, exactly. The pillar
        // spans x −51.5…16.5 and y −27.5…33; the section runs from the wall (plane1's x = 33, where the border
        // pierces it) out to x = 51.143586883130496, so it reaches 18.143586883130496 mm outwards everywhere.
        val out = 51.143586883130496 - 33.0
        assertClose(b.xs.min(), -51.5 - out, 1e-9, "the body reaches the section's own width outside the border")
        assertClose(b.xs.max(), 16.5 + out, 1e-9, "…on the other x side too")
        assertClose(b.ys.min(), -27.5 - out, 1e-9, "…and on both y sides")
        assertClose(b.ys.max(), 33.0 + out, 1e-9, "…exactly, because the run follows the drawing exactly")
    }

    /**
     * **The drawn section is a section of the swept body** — asserted the only way that means anything: every
     * corner of the drawing, mapped into the world by its own plane, is a point of the body's surface.
     *
     * Exact on the straight leg the section rides (the run is a straight segment there and the section a
     * polygon, so the band the drawing lies in is flat and the corners land on it to the last bits of a
     * double). The tolerance is stated rather than chosen for comfort: 1e-9 mm.
     */
    @Test
    fun theDrawnSectionIsASectionOfTheSweptBody() {
        val (doc, solid) = sweptRoundThePillar()
        val plane = planeOf(doc, "plane1")
        val mesh = meshOf(solid)
        val loop = (Evaluator().valueOf(section(doc).ref) as LoopValue).loop
        val corners = loop.elements.map { GeomMath.startOf(it) }
        val off = corners.map { distanceToSurface(plane.toWorld(it), mesh) }
        assertTrue(off.max() <= 1e-9, "every corner of the drawing is a point of the body: ${off.max()} mm off")
    }

    /**
     * **The run follows the arcs**, and it follows them as arcs: the lifted border is four segments and four
     * exact [Curve3Element.Arc3] pieces of the fillet radius the user typed, not a fit and not a polyline.
     *
     * This is what *Curve through points* could not do and what the refusal used to send the user to.
     */
    @Test
    fun theLiftedBorderIsSegmentsAndExactArcsOfTheTypedRadius() {
        val doc = DocumentFormat.load(ROUND_PILLAR_CIT)
        val run = assertNotNull(doc.liftCurves(listOf(planOutline(doc))), doc.note)
        val path = pathOf(run)
        assertTrue(path.closed, "a traced outline lifts closed, by its kind")
        assertEquals(4, path.elements.count { it is Curve3Element.Seg3 }, "four straight sides")
        val arcs = path.elements.filterIsInstance<Curve3Element.Arc3>()
        assertEquals(4, arcs.size, "and four fillets, each one arc")
        for (a in arcs) {
            assertClose(a.radius, 10.0, 1e-12, "each fillet is the radius the parameter states, exactly")
            assertClose(kotlin.math.abs(a.sweepAngle), kotlin.math.PI / 2.0, 1e-12, "…through a right angle")
            assertClose(a.center.z, 0.0, 1e-12, "…lying in the plan it was drawn in")
        }
        // the run's own length: four sides shortened by two fillet radii each, plus four quarter circles
        val length = 2.0 * (68.0 - 20.0) + 2.0 * (60.5 - 20.0) + 2.0 * kotlin.math.PI * 10.0
        assertClose(Curves3.length(path), length, 1e-9, "so its length is the drawing's own arithmetic")
        assertTrue(doc.note!!.contains("exact"), "and the lift says it fitted nothing: ${doc.note}")
    }

    /**
     * **A run that pierces a plane on an arc is found there** — [Pierce3] crossing an analytic arc, which the
     * in-place sweep needs before it can seed a frame anywhere but on a straight leg.
     *
     * The plane is turned so that it cuts the border through two of its fillets; the crossings are bisected on
     * the arc's own formula, so each one stands on the fillet's radius to the last bits.
     */
    @Test
    fun aCrossingOnAFilletIsFoundAndIsExactOnTheArc() {
        val doc = DocumentFormat.load(ROUND_PILLAR_CIT)
        val run = assertNotNull(doc.liftCurves(listOf(planOutline(doc))), doc.note)
        val path = pathOf(run)
        // the corner circle of the top-right fillet: centre (6.5, 23), radius 10 — a vertical plane through
        // its centre at 45° cuts that fillet exactly at its middle
        val centre = Vec3(6.5, 23.0, 0.0)
        val diag = Vec3(1.0, 1.0, 0.0).normalized()
        val plane = Plane3(centre, diag, Vec3.Z)
        val hits = Pierce3.crossings(path, plane)
        val onArc = hits.filter { path.elements[it.piece] is Curve3Element.Arc3 }
        assertTrue(onArc.isNotEmpty(), "the plane crosses the run on a fillet: ${hits.size} crossings")
        for (h in onArc) {
            val arc = path.elements[h.piece] as Curve3Element.Arc3
            assertClose((h.at - arc.center).length(), 10.0, 1e-12, "the crossing stands on the fillet's own radius")
            assertClose(plane.distanceTo(h.at), 0.0, 1e-9, "and on the plane it crosses")
            assertClose(h.tangent.dot(h.at - arc.center), 0.0, 1e-9, "with the arc's own tangent there")
        }
    }

    /**
     * **The fillet radius stays live**: retype the parameter and the body follows, because the lift is a node
     * over the drawing rather than a copy of it (OP-21).
     *
     * A 10 mm fillet on a 68 × 60.5 footprint, taken to 25 mm, moves nothing about the extent — the corners
     * are rounded *inside* the rectangle — so what is asserted is the run itself: its length shortens by
     * exactly what the arithmetic says, and the swept body's own volume follows it.
     */
    @Test
    fun retypingTheFilletRadiusMovesTheBody() {
        val (doc, solid) = sweptRoundThePillar()
        val before = constructit.geom.Geom3.volume(meshOf(solid))
        val run = pathOf(assertNotNull(doc.liftCurves(listOf(planOutline(doc))), doc.note))
        assertClose(Curves3.length(run), 2.0 * 48.0 + 2.0 * 40.5 + 2.0 * kotlin.math.PI * 10.0, 1e-9, "the run as drawn")

        val radius = assertNotNull(doc.scalars.firstOrNull { it.name == "radius" }, "the fillet radius is a parameter")
        doc.setParameter(radius, Quantity.mm(25.0))

        val after = pathOf(assertNotNull(doc.liftCurves(listOf(planOutline(doc))), doc.note))
        assertClose(
            Curves3.length(after),
            2.0 * (68.0 - 50.0) + 2.0 * (60.5 - 50.0) + 2.0 * kotlin.math.PI * 25.0,
            1e-9,
            "and the run follows the retyped radius",
        )
        assertTrue(
            after.elements.filterIsInstance<Curve3Element.Arc3>().all { kotlin.math.abs(it.radius - 25.0) < 1e-12 },
            "every fillet of the run is the new radius",
        )
        assertNull(whyInvalid(solid), "the body is still a body: ${whyInvalid(solid)}")
        assertManifold(meshOf(solid), "the foundation on a 25 mm fillet")
        assertTrue(
            kotlin.math.abs(constructit.geom.Geom3.volume(meshOf(solid)) - before) > 1.0,
            "and the body moved with it: $before → ${constructit.geom.Geom3.volume(meshOf(solid))}",
        )
    }

    /**
     * **The choice speaks and the step records it** — the in-place crossing, exactly as it does on a run drawn
     * with *Curve through points*, plus the lift's own sentence — and the drawing round-trips byte-equal.
     */
    @Test
    fun theStatusLineSpeaksTheLiftAndTheCrossingAndTheFileRoundTrips() {
        val (doc, _) = sweptRoundThePillar()
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("reading it as the run it already is where it is drawn in plan"), "the lift speaks: $note")
        assertTrue(note.contains("riding where"), "…and so does the crossing: $note")
        assertTrue(note.contains("crossing 2 of 2, the one nearest the section"), "…which one and why: $note")
        assertTrue(note.contains("pick a point of the section to ride it elsewhere"), "…and the alternative: $note")

        val script = DocumentFormat.save(doc)
        val step = script.lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(step.contains("els=e43,e34"), "the step names the drawing it swept along: $step")
        assertTrue(step.contains("signs=1"), "and the crossing it chose: $step")
        assertEquals(script, DocumentFormat.save(DocumentFormat.load(script)), "and the drawing round-trips byte-equal")
        val back = DocumentFormat.load(script)
        assertTrue(back.loadNotes.isEmpty(), "nothing is ambiguous about it: ${back.loadNotes}")
        val there = Bounds(meshOf(lastSolid(back)))
        assertClose(there.zs.min(), 0.0, 1e-12, "and it comes back standing where it stood")
        assertClose(there.zs.max(), 27.004059571993878, 1e-12, "…exactly")
    }

    /**
     * **A section edited to fold at a fillet is refused by name, at the station it folds** — and the refusal
     * reaches the user through [Editor.validityNote] when the drawing is driven through the editor.
     *
     * The section is dragged **inwards**, so that it reaches 12 mm towards the centre of a 10 mm fillet. That
     * really does turn inside out, and the corrected criterion says so with the same voice as ever.
     */
    @Test
    fun aSectionThatReachesIntoAFilletIsRefusedByNameAtItsStation() {
        val doc = DocumentFormat.load(ROUND_PILLAR_CIT)
        doc.activeSpace = assertNotNull(doc.spaceNamed("plane1"))
        // a section drawn from the wall (plane1's x = 33, where the border pierces it) **inwards**, 12 mm into
        // a pillar whose corners are rounded at 10 mm — so at every fillet it reaches past the centre of the
        // bend, which is a fold and nothing else
        val corners =
            listOf(Vec2(33.0, 0.0), Vec2(21.0, 0.0), Vec2(21.0, 10.0), Vec2(33.0, 10.0))
                .map { doc.freePoint(it.x.mm, it.y.mm) }
        val sides = corners.indices.map { doc.segment(corners[it], corners[(it + 1) % corners.size]) }
        val mids =
            listOf(Vec2(27.0, 0.0), Vec2(21.0, 5.0), Vec2(27.0, 10.0), Vec2(33.0, 5.0))
        val inward = assertNotNull(doc.buildOutline(sides, mids), "the inward section: ${doc.note}")

        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val solid = assertNotNull(doc.sweepAlongCurve(planOutline(doc), inward), doc.note)
        val why = assertNotNull(whyInvalid(solid), "a section reaching 12 mm into a 10 mm fillet folds through itself")
        // 11.978 rather than a flat 12: the frame stands on the **chord** the spine walks, so the section's
        // axes are half a sampling step off the arc's own normal there — which is the resolution this whole
        // criterion is stated at (OP-15), and the frame the mesh is actually built in
        assertTrue(why.contains("the profile's reach into the bend (11.978 mm)"), "named by what it reaches inwards: $why")
        assertTrue(why.contains("radius 10 mm"), "against the fillet it outgrows: $why")
        assertTrue(why.contains("mm along the path"), "and where along the run that is: $why")
        assertTrue(why.contains("pass through itself"), "and the consequence: $why")

        // …and it reaches the user through the editor's own validity channel
        val ed = Editor(doc)
        ed.revalidate()
        assertTrue(assertNotNull(ed.validityNote).contains("pass through itself"), "the editor says it: ${ed.validityNote}")
        assertTrue(assertNotNull(ed.validityNote).contains(doc.nameOf(solid)), "…and names the body: ${ed.validityNote}")
    }

    // ---- 2. the lift as a thing of its own ----

    /**
     * **An open chain of a segment and an arc lifts, sweeps and takes a station** — the multi-pick gesture,
     * where the run is the pieces in the order they were clicked, each flipped as it must be to continue.
     */
    @Test
    fun anOpenChainOfASegmentAndAnArcLiftsAndSweepsAndStations() {
        val ed = Editor()
        // a 100 mm run east, then a quarter turn north about (100, 50)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.ARC_CS)
        ed.click(Vec2(100.0, 50.0))
        ed.click(Vec2(100.0, 0.0))
        ed.click(Vec2(150.0, 50.0))
        val seg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        val arc = ed.doc.elements.first { it.kind == ElementKind.ARC }

        val run = assertNotNull(ed.doc.liftCurves(listOf(seg, arc)), ed.doc.note)
        val path = pathOf(run)
        assertTrue(!path.closed, "an open chain lifts open")
        assertEquals(2, path.elements.size, "two pieces, in the order they were clicked")
        assertTrue(path.elements[0] is Curve3Element.Seg3 && path.elements[1] is Curve3Element.Arc3, "a segment then an arc")
        assertClose(Curves3.length(path), 100.0 + 0.5 * kotlin.math.PI * 50.0, 1e-9, "and its length is the drawing's")
        assertVec3(assertNotNull(path.start), Vec3(0.0, 0.0, 0.0), msg = "the run starts where the first pick starts")
        assertVec3(assertNotNull(path.end), Vec3(150.0, 50.0, 0.0), msg = "and ends where the last one does")

        // a tube along it is a body
        val tube = assertNotNull(ed.doc.tubeAlongCurve(run, ed.doc.newParameter("r", 4.0.mm).ref), ed.doc.note)
        assertNull(whyInvalid(tube), "the tube along the chain is valid: ${ed.doc.note}")
        assertManifold(meshOf(tube), "a tube along a lifted segment-and-arc chain")

        // …and a station 100 mm along stands exactly where the segment hands over to the arc
        assertNotNull(ed.doc.createStationSpace(run, ed.doc.newParameter("d", 100.0.mm).ref), ed.doc.note)
        assertVec3(planeOf(ed.doc, ed.doc.activeSpace.name).origin, Vec3(100.0, 0.0, 0.0), msg = "the station stands at the hand-over")
    }

    /**
     * **The lifted run is an element like any other** — named, hidden, and shared by two bodies at once, which
     * is the reason the tool exists beside the slot's own coercion.
     */
    @Test
    fun aLiftedRunIsAnElementThatCanBeNamedHiddenAndShared() {
        val doc = DocumentFormat.load(ROUND_PILLAR_CIT)
        doc.runTool(
            assertNotNull(Tools.byId(Tools.LIFT)),
            Picks(emptyList(), listOf(planOutline(doc)), Vec2(-24.5, -27.0), listOf(Vec2(-24.5, -27.0))),
            emptyList(),
        )
        val run = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(ElementKind.SPACE_CURVE, run.kind, "what comes out is a curve in space")
        doc.nameElement(run, "border")
        assertEquals("border", doc.userNameOf(run), "and it takes a name")

        val a = assertNotNull(doc.tubeAlongCurve(run, doc.newParameter("r1", 3.0.mm).ref), doc.note)
        val b = assertNotNull(doc.sweepAlongCurve(run, section(doc)), doc.note)
        assertNull(whyInvalid(a), "one run, two bodies: ${whyInvalid(a)}")
        assertNull(whyInvalid(b), "…and the second is the foundation: ${whyInvalid(b)}")
        assertManifold(meshOf(a), "the tube on the shared run")
        assertManifold(meshOf(b), "the foundation on the shared run")

        doc.setElementsVisible(listOf(run), false)
        assertTrue(!run.visible, "and it hides like any element")
        assertNull(whyInvalid(b), "without taking the bodies built on it with it")
    }

    /**
     * **The gesture, end to end, through the editor** — click the outline with the *Lift* tool armed, and the
     * step is recorded so that a replay re-discovers nothing.
     */
    @Test
    fun theLiftGestureRecordsItsOwnStepAndReplaysExactly() {
        val ed = Editor(DocumentFormat.load(ROUND_PILLAR_CIT))
        ed.setTool(Tools.LIFT)
        ed.click(Vec2(-24.5, -27.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertClose(Curves3.length(pathOf(run)), 2.0 * 48.0 + 2.0 * 40.5 + 2.0 * kotlin.math.PI * 10.0, 1e-9, "the run is the border")

        val script = DocumentFormat.save(ed.doc)
        assertTrue(script.lineSequence().any { it.startsWith("tool lift els=e43") }, "the lift is a step of its own:\n$script")
        assertEquals(script, DocumentFormat.save(DocumentFormat.load(script)), "and it round-trips byte-equal")
        val back = DocumentFormat.load(script)
        assertClose(
            Curves3.length(pathOf(back.elements.last { it.kind == ElementKind.SPACE_CURVE })),
            2.0 * 48.0 + 2.0 * 40.5 + 2.0 * kotlin.math.PI * 10.0,
            1e-9,
            "and comes back the same run",
        )
    }

    /** **What cannot be a run refuses by name, with the way out** (the *refusals speak* rule). */
    @Test
    fun whatCannotBeARunRefusesByName() {
        val doc = Document()
        val line = doc.line(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(100.0.mm, 0.0.mm))
        assertNull(doc.liftCurves(listOf(line)), "a line runs on for ever")
        assertTrue(assertNotNull(doc.takeNote()).contains("a run is lifted out of a drawn curve"), "and says what it wanted")

        val p = assertNotNull(doc.elementFor(doc.freePoint(10.0.mm, 10.0.mm)))
        assertNull(doc.liftCurves(listOf(p)), "a point is a place, not a run")
        assertNull(doc.liftCurves(emptyList()), "and an empty pick builds nothing")
        assertTrue(assertNotNull(doc.takeNote()).contains("click the drawing"), "and says so")

        // a chain that does not connect is the *node's* business, so it is invalid with the gap named and it
        // heals when the drawing moves (OP-3)
        val one = doc.segment(doc.freePoint(0.0.mm, 50.0.mm), doc.freePoint(40.0.mm, 50.0.mm))
        val two = doc.segment(doc.freePoint(60.0.mm, 50.0.mm), doc.freePoint(100.0.mm, 50.0.mm))
        val broken = assertNotNull(doc.liftCurves(listOf(one, two)), doc.note)
        val why = assertNotNull(whyInvalid(broken), "two pieces that do not meet are no run")
        assertTrue(why.contains("does not meet the previous one"), "and the node says so with the gap: $why")
    }

    // ---- the fixture ----

    companion object {
        /**
         * **The user's own drawing, verbatim** (OP-18's fixture rule): a pillar with a `roundrect` footprint of
         * 10 mm fillets, extruded 200 mm; a vertical sketch plane cut through it on the perpendicular bisector
         * of one wall; a foundation profile constructed in place in that plane, against the wall and on the
         * ground; and the rounded plan outline that is to be the route.
         *
         * What they could not do with it is sweep: the outline was refused as *"not a curve in space"* and the
         * alternative it named — *Curve through points* — is a polyline and cannot follow the fillets.
         */
        val ROUND_PILLAR_CIT =
            """
            constructit 2
            param "radius" = 10mm
            point -51.5,33 -> e1
            point 16.5,-27.5 -> e2
            tool roundrect pts=e1,e2 clicks=-51.5,33;16.5,-27.5 scalar="radius" -> e3,e4,e5,e6,e7,e8,e9,e10
            param "depth" = 200mm
            tool extrude els=e9 clicks=-51.25,7.25 scalar="depth" -> e11
            tool keypoints els=e7 clicks=-19,-27.5 -> e12,e13
            tool perpbis pts=e13,e12 clicks=-42.5,-28.5;7.75,-28 -> e14
            param "angle" = 90deg
            sketchspace "plane1" line=e14 angle="angle"
            sectioninput "plane1" el=e11 edge=7 -> e15
            tool keypoints els=e15 clicks=33.54934747145189,77.03099510603589 -> e16,e17
            orthostart 33,0.00000000000000000000000000000019721522630525295 -> e18
            weldortho e18 e17
            orthovertex 51.143586883130496,0.00000000000000000000000000000019721522630525295 -> e19,e20
            orthovertex 51.143586883130496,8.860472688863375 -> e21,e22
            orthovertex 33,8.860472688863375 -> e23,e24
            attachortho e23 e15
            tool circle pts=e23,e21 clicks=33.54934747145189,29.526916802610117;61.21655791190866,31.61500815660685 -> e25
            tool intersect els=e25,e15 clicks=44.77283849918435,54.323001631321375;32.766313213703114,73.3768352365416 -> e26,e27
            tool arccs pts=e23,e21,e26 clicks=33.54934747145189,29.265905383360522;62.26060358890703,29.526916802610117;31.461256117455154,55.88907014681892 -> e28
            hide els=e25
            hide els=e24
            tool segment pts=e26,e18 clicks=34.07137030995107,56.672104404567705;32.766313213703114,-0.4893964110929853 -> e29
            tool outline els=e22,e28,e29,e20 clicks=60.17251223491029,13.605220228384992;56.518352365416,44.143556280587276;33,28.5;46.86092985318109,0.032626427406199025 -> e30,e31,e32,e33,e34
            space "plan"
            tool outline els=e7,e6,e5,e4,e3,e10,e9,e8 clicks=-24.5,-27;14.5,-22.5;16.5,2.7499999999999982;13.571067811865476,30.071067811865476;-17.5,33;-48.571067811865476,30.071067811865476;-51.5,2.75;-48.571067811865476,-24.571067811865476 -> e35,e36,e37,e38,e39,e40,e41,e42,e43
            """.trimIndent() + "\n"
    }
}
