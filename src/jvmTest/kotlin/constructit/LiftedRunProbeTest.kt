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
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Curve3Element
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The lift, composed with what was already there** — the probe review of OP-26's step-1 completion (session
 * 61): the same mechanism met by features it was not written against.
 *
 * A closed **conic** lifted whole (the `2π` case the elliptic-arc vocabulary refuses to write down); a run
 * lifted out of a **tilted datum**, which is the parenting rule paying out — turn the datum and the run turns;
 * the plan projection of an arc standing **edge-on**, which is the one place [Curves3.projectedOnto] honestly
 * stops; a lifted run **placed** somewhere else, which carries an `Arc3` through a rigid map; and a
 * **delete** of the drawing underneath it, which must take the run and everything built on it.
 */
class LiftedRunProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun pathOf(el: Element): Path3 = (Evaluator().valueOf(el.ref) as Path3Value).path

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    /**
     * **A circle lifted whole is a closed run of one arc, and a tube along it is a torus** — Pappus to a
     * tenth of a per cent, which is the honest reading of "it is embedded and it went round exactly once".
     *
     * The `2π` case is the one an elliptic arc cannot state (a full sweep is ambiguous between 0 and a turn),
     * so it is one [Curve3Element.Arc3] and its plan shadow is a whole ellipse rather than a trimmed one.
     */
    @Test
    fun aLiftedCircleIsOneClosedArcAndTubesIntoATorus() {
        val doc = Document()
        val circle = doc.circle(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(50.0.mm, 0.0.mm))
        val run = assertNotNull(doc.liftCurves(listOf(circle)), doc.note)
        val path = pathOf(run)
        assertTrue(path.closed, "a circle lifts closed, by its kind")
        val arc = assertNotNull(path.elements.singleOrNull() as? Curve3Element.Arc3, "one exact arc: ${path.elements}")
        assertClose(arc.radius, 50.0, 1e-12, "of the circle's own radius")
        assertClose(kotlin.math.abs(arc.sweepAngle), 2.0 * PI, 1e-12, "going round exactly once")
        assertClose(Curves3.length(path), 2.0 * PI * 50.0, 1e-9, "so the run is the circumference")

        // its shadow in its own plane is the whole conic, not a trimmed arc that would have to say 0 or 2π
        val shadow = Curves3.projectedOnto(path, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y))
        assertEquals(1, shadow.size, "one piece: $shadow")
        val ellipse = assertNotNull(shadow[0] as? ProfileElement.EllipseE, "a whole conic: ${shadow[0]}")
        assertTrue(ellipse.ellipse.isCircular, "and seen square on it is exactly circular")
        assertClose(ellipse.ellipse.major, 50.0, 1e-12, "at the radius it was drawn")

        val tube = assertNotNull(doc.tubeAlongCurve(run, doc.newParameter("r", 6.0.mm).ref), doc.note)
        assertNull(whyInvalid(tube), "a torus is a body: ${whyInvalid(tube)}")
        val mesh = meshOf(tube)
        assertManifold(mesh, "the torus")
        // Pappus: 2π²Rr². The mesh is chords inside the true surface **twice over** — the section is a
        // polygon in its ring and the ring is a polygon round the run — so it undercuts by a few tenths of a
        // per cent and can never overshoot. That it is *within* the torus at all is the real assertion: a body
        // that had gone round twice, or folded through itself, would not be.
        val pappus = 2.0 * PI * PI * 50.0 * 36.0
        val v = Geom3.volume(mesh)
        assertTrue(v > 0.0 && v <= pappus, "it encloses no more than the torus it approximates: $v vs $pappus")
        assertTrue(v > pappus * 0.995, "…and all but the chords of it: $v vs $pappus")
    }

    /**
     * **The in-place sweep can be seeded in the middle of an arc** — which nothing could do before, because
     * no run had an arc in it: a lifted circle is *one* piece, so every crossing of it is strictly interior to
     * that piece and the frame is stated there rather than at a station.
     *
     * The section is drawn in a vertical datum the run passes through, at the crossing, and it comes out as
     * the run's own section there: the ring stands where the drawing stands, which is the whole claim of a
     * seeded frame (`Pierce3.readingAt` → `FrameSeed` → `Frames3.spanOf`) met by an analytic arc.
     */
    @Test
    fun theInPlaceSweepCanBeSeededInTheMiddleOfAnArc() {
        val doc = Document()
        val circle = doc.circle(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(50.0.mm, 0.0.mm))
        val run = assertNotNull(doc.liftCurves(listOf(circle)), doc.note)
        assertTrue(pathOf(run).elements.single() is Curve3Element.Arc3, "the run is one arc")

        // a vertical plane on the **y** axis: the ring crosses it at (0, ±50, 0), both strictly inside the
        // one arc — which is what this probe is about, a frame seeded in the *middle* of a piece. Deliberately
        // not the x axis, where the lifted circle's own **seam** sits exactly on the plane: that is the case
        // this probe found `Pierce3` blind to, and it is now a crossing at the seam with a test of its own
        // (`SeamCrossingTest`, and the format 3 bump its renumbering owed — OP-18).
        val hinge = doc.line(doc.freePoint(0.0.mm, (-80.0).mm), doc.freePoint(0.0.mm, 80.0.mm))
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"), "the datum stands on the line")
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))
        val plane = planeOf(doc, "cut")
        val hits = constructit.geom.Pierce3.crossings(pathOf(run), plane)
        assertEquals(2, hits.size, "a ring crosses a plane through it twice")
        for (h in hits) {
            assertEquals(0, h.piece, "…both on the one arc")
            assertTrue(h.t > 1e-6 && h.t < 1.0 - 1e-6, "…strictly inside it: t = ${h.t}")
        }

        // a small section drawn at the crossing, in the datum's own coordinates
        val at = plane.toLocal(Vec3(0.0, 50.0, 0.0))
        val section = doc.circle(doc.freePoint(at.x.mm, (at.y + 8.0).mm), doc.freePoint((at.x + 4.0).mm, (at.y + 8.0).mm))
        val solid = assertNotNull(doc.sweepAlongCurve(run, section), doc.note)
        assertNull(whyInvalid(solid), "a section riding a crossing on an arc is a body: ${doc.note}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the ring seeded mid-arc")
        assertTrue(Geom3.volume(mesh) > 0.0, "and the right way out: ${Geom3.volume(mesh)}")
        assertTrue(assertNotNull(doc.note).contains("riding where"), "and the choice speaks: ${doc.note}")

        // the drawing is the body's section there: the section stands 8 mm up the datum from the crossing, so
        // the ring's own centre line is a circle of radius 50 lifted 8 mm out of the plan
        val zs = mesh.vertices.map { it.z }
        assertClose(zs.min(), 4.0, 0.05, "the section rides where it was drawn, 8 mm up and 4 mm across")
        assertClose(zs.max(), 12.0, 0.05, "…on both sides of its own centre")
    }

    /**
     * **A run lifted out of a tilted datum rides that datum** — the parenting rule, which is the whole reason
     * a lift needs no coordinates of its own: retype the datum's angle and the run turns with it, by
     * recompute.
     */
    @Test
    fun aRunLiftedOutOfADatumRidesTheDatumsAngle() {
        val doc = Document()
        val hinge = doc.line(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(100.0.mm, 0.0.mm))
        val angle = doc.newParameter("tilt", Quantity.deg(90.0))
        assertNotNull(doc.createDatumSpace(hinge, angle.ref, "wall"), "the datum stands on the line")
        doc.activeSpace = assertNotNull(doc.spaceNamed("wall"))
        val seg = doc.segment(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(0.0.mm, 40.0.mm))
        val run = assertNotNull(doc.liftCurves(listOf(seg)), doc.note)

        // upright: the run climbs 40 mm out of the plan
        val up = assertNotNull(pathOf(run).end)
        assertClose(up.z, 40.0, 1e-9, "the run stands up out of the plan: $up")

        // half the tilt: it leans, and nothing was rebuilt to make it
        doc.setParameter(angle, Quantity.deg(45.0))
        val leaning = assertNotNull(pathOf(run).end)
        assertClose(leaning.z, 40.0 * kotlin.math.sin(PI / 4.0), 1e-9, "and follows the datum's angle: $leaning")
        assertTrue(kotlin.math.abs(leaning.y) > 1.0, "…leaning out of the plan as the datum does: $leaning")
    }

    /**
     * **An arc standing edge-on has no ellipse, and says so by being the line it is** — the one place the
     * exact projection stops (`Curves3.projectedOnto`), reached by looking along an arc's own plane.
     *
     * The shadow is a segment traversed twice, which is not one piece of anything, so it is drawn as the
     * chords it is — and every one of them lies on the line the arc projects onto, which is what the assertion
     * checks rather than the count.
     */
    @Test
    fun anArcSeenEdgeOnProjectsToTheLineItReallyIs() {
        val arc =
            Curve3Element.Arc3.about(
                Vec3(0.0, 0.0, 20.0),
                Vec3.X,
                Vec3.Z,
                30.0,
                0.0,
                PI,
            )
        // looking along the arc's own plane (its normal lies *in* the viewing plane)
        val edgeOn = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val shadow = Curves3.projectedOnto(Path3(listOf(arc)), edgeOn)
        assertTrue(shadow.isNotEmpty(), "it draws something")
        assertTrue(shadow.all { it is ProfileElement.Seg }, "chords, because a segment traversed twice is no conic: $shadow")
        for (piece in shadow) {
            val seg = (piece as ProfileElement.Seg).segment
            assertClose(seg.a.y, 0.0, 1e-12, "every chord lies on the line the arc projects onto")
            assertClose(seg.b.y, 0.0, 1e-12, "…at both ends")
            assertTrue(kotlin.math.abs(seg.a.x) <= 30.0 + 1e-9, "…and inside its own radius")
        }
        // …and seen square on, the same arc is the exact conic it is
        val square = Plane3(Vec3.ZERO, Vec3.X, Vec3.Z)
        val exact = Curves3.projectedOnto(Path3(listOf(arc)), square)
        assertEquals(1, exact.size, "one piece: $exact")
        assertTrue(exact[0] is ProfileElement.EllipticArcE, "an exact conic: ${exact[0]}")
    }

    /**
     * **A lifted run can be placed somewhere else, arcs and all** — `Path3.movedBy` over an `Arc3`, which is
     * the rigid map every placement is.
     */
    @Test
    fun aLiftedRunWithArcsCanBePlacedSomewhereElse() {
        val doc = Document()
        val circle = doc.circle(doc.freePoint(0.0.mm, 0.0.mm), doc.freePoint(20.0.mm, 0.0.mm))
        val run = assertNotNull(doc.liftCurves(listOf(circle)), doc.note)
        val at = doc.freePoint(200.0.mm, 100.0.mm)
        val placed = assertNotNull(doc.placeCurve(run, at, null), doc.note)
        val arc = assertNotNull(pathOf(placed).elements.singleOrNull() as? Curve3Element.Arc3, "still one arc")
        assertClose(arc.radius, 20.0, 1e-12, "a rigid map keeps the radius")
        assertClose(arc.center.x, 200.0, 1e-9, "and moves the centre where the point says")
        assertClose(arc.center.y, 100.0, 1e-9, "…on both axes")

        // and it is live: drag the point the placement rides and the run goes with it
        assertNotNull(doc.elementFor(at)?.handle, "the placement point is draggable").drag(Vec2(240.0, 100.0), Evaluator())
        assertClose((pathOf(placed).elements[0] as Curve3Element.Arc3).center.x, 240.0, 1e-9, "the placed run follows it")
    }

    /**
     * **Deleting the drawing takes the lift and everything built on it** — the ordinary reference rule, met by
     * a node whose input is a *drawn curve* rather than a curve in space.
     */
    @Test
    fun deletingTheDrawingTakesTheLiftAndTheBodyWithIt() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val circle = ed.doc.elements.last { it.kind == ElementKind.CIRCLE }
        ed.setTool(Tools.LIFT)
        ed.click(Vec2(60.0, 0.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(60.0, 0.0))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "a torus on the lifted circle: ${ed.statusHint}")

        ed.selectElement(circle)
        assertTrue(ed.deleteSelection(), "the drawing is deletable: ${ed.statusHint}")
        assertTrue(ed.doc.elements.none { it === run }, "the run goes with the drawing it read")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "and so does the body built on it")
        val script = DocumentFormat.save(ed.doc)
        assertTrue(!script.contains("tool lift"), "and the step with it:\n$script")
        assertEquals(script, DocumentFormat.save(DocumentFormat.load(script)), "what is left round-trips")
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }
}
