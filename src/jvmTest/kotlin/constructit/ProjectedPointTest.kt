package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SourceNode
import constructit.dsl.CircleRef
import constructit.dsl.PointRef
import constructit.dsl.circle
import constructit.dsl.isValid
import constructit.dsl.plane
import constructit.dsl.point
import constructit.dsl.resultOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Projected point on plane** (GitHub #14), the user's demand verbatim:
 *
 * > "A plane already displays the outline of geometries constructed on its ancestor panes. However,
 * > sometimes this is not the most convenient way to anchor constructions on that pane. I'd like to select a
 * > point defined on an ancestor pane to get a derived point on my pane that is the projection of the
 * > ancestor pane's point on my pane."
 *
 * One derived node, `Construction.projectToPlane`: the source's world position (through `pointInSpace`, the
 * one seam every point kind publishes it through, OP-26) dropped along the **target plane's normal** onto it,
 * read in the plane's own (u, v). The source is shared by node, so the projection follows every edit to it —
 * the no-solver stance, applied to anchoring across panes.
 *
 * Asserted here: the foot geometry, that the projection follows a dragged source / an edited parameter / a
 * tilted source plane, that a construction anchored on it (a circle, a weld) follows, that it serves a
 * *derived* source (an intersection), that *Make absolute* frees it in place, the two refusals, and that a
 * file carrying one round-trips byte-for-byte with no version bump.
 */
class ProjectedPointTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /**
     * A plan segment along the x-axis and a datum turned 45° out of the plan about it. The view is left on
     * the datum (the plane a projection would land on); [datumName] names it.
     */
    private fun fixture(): Pair<Editor, String> {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("45")
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.activeSpace.isDatum, "the view is on the new datum: ${ed.statusHint}")
        return ed to ed.activeSpace.name
    }

    private fun Editor.datumPlane(name: String): Plane3 = Evaluator().plane(assertNotNull(doc.spaceNamed(name)?.plane))

    /** A free point placed in the plan at [at], the source to project. */
    private fun Editor.planPoint(at: Vec2): Element {
        val was = activeSpace.name
        setActiveSpace(Document.PLAN_SPACE)
        val ref = doc.freePoint(at.x.mm, at.y.mm)
        val el = assertNotNull(doc.elementFor(ref))
        setActiveSpace(was)
        return el
    }

    /** The perpendicular foot of [world] on [pl], computed independently of the op under test. */
    private fun foot(
        pl: Plane3,
        world: Vec3,
    ): Vec3 {
        val n = pl.normal.normalized()
        return world - n * (world - pl.origin).dot(n)
    }

    private fun uvOf(
        el: Element,
        ev: Evaluator = Evaluator(),
    ): Vec2 = ev.point(el.ref as PointRef)

    // ---- the foot geometry ----

    @Test
    fun aPlanPointProjectsToThePerpendicularFootOnTheDatum() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src), "the projection is built: ${ed.doc.note}")

        assertEquals(datum, proj.space, "the projected point belongs to the datum it was projected onto")
        assertTrue(proj.isPoint, "it is an ordinary point of that plane")

        val pl = ed.datumPlane(datum)
        val srcW = Vec3(30.0, 40.0, 0.0)
        val f = foot(pl, srcW)

        // its (u, v) is the source's world position read in the plane's own frame (the perpendicular drop)
        val uv = uvOf(proj)
        val expectedUv = pl.toLocal(srcW)
        assertClose(uv.x, expectedUv.x, 1e-9, "u of the foot")
        assertClose(uv.y, expectedUv.y, 1e-9, "v of the foot")

        // and its world position, lifted back onto the plane, is the foot itself
        val world = pl.toWorld(uv)
        assertClose(world.x, f.x, 1e-9, "world x = foot x")
        assertClose(world.y, f.y, 1e-9, "world y = foot y")
        assertClose(world.z, f.z, 1e-9, "world z = foot z")
        // the foot lies in the plane: no component along the normal
        assertClose((f - pl.origin).dot(pl.normal.normalized()), 0.0, 1e-9, "the foot is on the plane")
    }

    // ---- sharing: the projection follows the source ----

    @Test
    fun draggingTheSourceMovesTheProjectionToTheNewFoot() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src))
        val pl = ed.datumPlane(datum)

        // drag the source: mutate its own free coordinates, exactly as the FreePointHandle does
        (src.ref.node as SourceNode).value = PointValue(Vec2(70.0, -20.0))
        val f = foot(pl, Vec3(70.0, -20.0, 0.0))
        val world = pl.toWorld(uvOf(proj))
        assertClose(world.x, f.x, 1e-9, "x follows the drag")
        assertClose(world.y, f.y, 1e-9, "y follows the drag")
        assertClose(world.z, f.z, 1e-9, "z follows the drag")
    }

    @Test
    fun editingAParameterTheSourceDependsOnMovesTheProjection() {
        val (ed, datum) = fixture()
        ed.setActiveSpace(Document.PLAN_SPACE)
        val px = ed.doc.newParameter("px", 30.0.mm)
        val py = ed.doc.newParameter("py", 40.0.mm)
        val srcRef = ed.doc.pointFromCoordinates(px.ref, py.ref)
        val src = assertNotNull(ed.doc.elementFor(srcRef))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src))
        val pl = ed.datumPlane(datum)

        ed.doc.setParameter(px, (-10.0).mm)
        ed.doc.setParameter(py, 55.0.mm)
        val f = foot(pl, Vec3(-10.0, 55.0, 0.0))
        val world = pl.toWorld(uvOf(proj))
        assertClose(world.x, f.x, 1e-9, "x follows the parameter")
        assertClose(world.y, f.y, 1e-9, "y follows the parameter")
    }

    @Test
    fun tiltingTheSourcesOwnPlaneMovesTheProjection() {
        // source on ITS OWN datum (a second plane), projected onto the first: moving the source's plane moves it
        val (ed, target) = fixture()
        // a second datum on the same hinge, tilted differently, to carry the source
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0))
        val srcSpace = ed.activeSpace.name
        assertNotEquals(target, srcSpace)
        // a free point on the source datum, then project it onto the target datum
        val local = Vec2(20.0, 15.0)
        val srcRef = ed.doc.freePoint(local.x.mm, local.y.mm)
        val src = assertNotNull(ed.doc.elementFor(srcRef))
        ed.setActiveSpace(target)
        val proj = assertNotNull(ed.doc.projectToPlane(src))

        fun projectedWorld(): Vec3 = ed.datumPlane(target).toWorld(uvOf(proj))

        // the source's world position, computed from its own plane and its local coordinates
        fun sourceWorld(): Vec3 = ed.datumPlane(srcSpace).toWorld(local)

        val before = projectedWorld()
        assertClose((before - foot(ed.datumPlane(target), sourceWorld())).length(), 0.0, 1e-6, "starts at the foot")

        // tilt the source plane: retype its angle parameter, which moves everything drawn on it. The two
        // datums each own an angle parameter; the source datum's is the one created second.
        val srcAngle = ed.doc.scalars.filter { it.editable && it.name.startsWith("angle") }.last()
        ed.doc.setParameter(srcAngle, 75.0.deg())
        val after = projectedWorld()
        assertTrue((after - before).length() > 1.0, "the projection moved when the source's plane tilted")
        // and it is still the foot of the source's *new* world position
        val f = foot(ed.datumPlane(target), sourceWorld())
        assertClose(after.x, f.x, 1e-6, "x is the new foot")
        assertClose(after.y, f.y, 1e-6, "y is the new foot")
        assertClose(after.z, f.z, 1e-6, "z is the new foot")
    }

    // ---- compose: constructions anchored on the projection follow ----

    @Test
    fun aCircleCentredOnTheProjectionFollowsTheSource() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src))
        // a circle centred on the projected point, radius 12
        val r = ed.doc.newParameter("r", 12.0.mm)
        val circle = ed.doc.circleCR(proj.ref as PointRef, r.ref)

        val ev1 = Evaluator()
        val c1 = ev1.circle(circle.ref as CircleRef).center
        assertClose(c1.x, uvOf(proj, ev1).x, 1e-9, "the circle sits on the projection")

        // drag the source: the circle's centre moves with the projection, radius unchanged
        (src.ref.node as SourceNode).value = PointValue(Vec2(10.0, 90.0))
        val ev2 = Evaluator()
        val c2 = ev2.circle(circle.ref as CircleRef).center
        val projUv = uvOf(proj, ev2)
        assertClose(c2.x, projUv.x, 1e-9, "the centre followed the source")
        assertClose(c2.y, projUv.y, 1e-9, "the centre followed the source")
        assertClose(ev2.circle(circle.ref as CircleRef).radius, 12.0, 1e-9, "the radius did not change")
    }

    @Test
    fun aPointCanBeWeldedOntoTheProjection() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src))
        // a free point of the datum, welded onto the projection (the projection is the master)
        val aliasRef = ed.doc.freePoint(5.0.mm, 5.0.mm)
        val alias = assertNotNull(ed.doc.elementFor(aliasRef))
        assertTrue(ed.doc.weld(alias, proj), "the point welds onto the projection: ${ed.doc.note}")
        // the alias now stands where the projection does, and follows the source
        (src.ref.node as SourceNode).value = PointValue(Vec2(80.0, 10.0))
        val ev = Evaluator()
        val aliasP = ev.point(aliasRef)
        val projUv = uvOf(proj, ev)
        assertClose(aliasP.x, projUv.x, 1e-9, "the welded point follows the projection")
        assertClose(aliasP.y, projUv.y, 1e-9, "the welded point follows the projection")
    }

    // ---- the seam serves every point kind: a derived source ----

    @Test
    fun aDerivedSourceProjects() {
        val (ed, datum) = fixture()
        ed.setActiveSpace(Document.PLAN_SPACE)
        // two crossing segments; their intersection is a derived point (not a free one)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 60.0))
        val segA = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 60.0))
        ed.click(Vec2(60.0, 0.0))
        val segB = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val xRefs = ed.doc.intersect(segA, segB)
        val cross = assertNotNull(ed.doc.elementFor(xRefs.first()))
        assertEquals(ElementKind.DERIVED_POINT, cross.kind, "the source is a derived point")
        assertClose(Evaluator().point(cross.ref as PointRef).x, 30.0, 1e-9, "they cross at (30, 30)")

        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(cross), "an intersection projects: ${ed.doc.note}")
        val pl = ed.datumPlane(datum)
        val f = foot(pl, Vec3(30.0, 30.0, 0.0))
        val world = pl.toWorld(uvOf(proj))
        assertClose(world.x, f.x, 1e-9, "the foot of the intersection")
        assertClose(world.y, f.y, 1e-9)
        assertClose(world.z, f.z, 1e-9)
    }

    // ---- Make absolute frees it in place ----

    @Test
    fun makeAbsoluteFreesItInPlaceAndRoundTrips() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(src))
        val before = uvOf(proj)

        assertTrue(ed.doc.makeAbsolute(proj), "the projection frees: ${ed.doc.note}")
        assertEquals(ElementKind.POINT, proj.kind, "it is a free point now")
        assertNull(ed.doc.projectedOf(proj), "it no longer follows the source")
        val after = uvOf(proj)
        assertClose(after.x, before.x, 1e-9, "nothing moved at the moment of the change")
        assertClose(after.y, before.y, 1e-9)

        // freed, it no longer follows the source
        (src.ref.node as SourceNode).value = PointValue(Vec2(90.0, 90.0))
        val stayed = uvOf(proj)
        assertClose(stayed.x, before.x, 1e-9, "the freed point stays put")

        // save -> load -> save is byte-equal (the freed position is restated on the `absolute` step)
        val s1 = DocumentFormat.save(ed.doc)
        val s2 = DocumentFormat.save(DocumentFormat.load(s1))
        assertEquals(s1, s2, "make-absolute round-trips byte-for-byte")
    }

    // ---- the refusals ----

    @Test
    fun refusesToProjectAPointAlreadyInThePlane() {
        val (ed, datum) = fixture()
        // a point drawn ON the datum itself: projecting it onto the datum is itself
        ed.setActiveSpace(datum)
        val onDatumRef = ed.doc.freePoint(10.0.mm, 20.0.mm)
        val onDatum = assertNotNull(ed.doc.elementFor(onDatumRef))
        val before = ed.doc.elements.size
        val refused = ed.doc.projectToPlane(onDatum)
        assertNull(refused, "a point already in the plane is refused")
        assertEquals(before, ed.doc.elements.size, "nothing was built")
        assertTrue(ed.doc.note!!.contains(ed.doc.nameOf(onDatum)), "the refusal names the element: ${ed.doc.note}")
        assertTrue(ed.doc.note!!.contains(datum), "and names the plane: ${ed.doc.note}")
    }

    @Test
    fun aSourceWithNoValueMakesTheProjectionInvalidNotRefused() {
        val (ed, datum) = fixture()
        ed.setActiveSpace(Document.PLAN_SPACE)
        // two parallel segments never cross: their intersection has no value
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val segA = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(60.0, 20.0))
        val segB = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val cross = assertNotNull(ed.doc.elementFor(ed.doc.intersect(segA, segB).first()))
        assertFalse(Evaluator().isValid(cross.ref), "the parallel intersection has no value")

        ed.setActiveSpace(datum)
        val proj = assertNotNull(ed.doc.projectToPlane(cross), "it is built, not refused — the value heals")
        val res = Evaluator().resultOf(proj.ref)
        assertTrue(res is EvalResult.Invalid, "the projection is invalid with a reason")
        assertNotNull((res as EvalResult.Invalid).reason)
    }

    // ---- the tool gesture, across a space switch (crossSpace) ----

    @Test
    fun theToolProjectsAcrossASpaceSwitch() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))

        // arm on the plan where the source lives, pick it, switch to the datum, click to land the projection
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.PROJECT_TO_PLANE)
        ed.click(Vec2(30.0, 40.0)) // shares the source's node
        assertTrue(ed.setActiveSpace(datum), "switch to the target pane")
        assertTrue(ed.statusHint.contains("kept across the switch"), "the pick survives: ${ed.statusHint}")
        ed.click(Vec2(10.0, 10.0)) // SIDE: commit onto this plane (position is not the result)

        val proj = assertNotNull(ed.doc.elements.lastOrNull { ed.doc.projectedOf(it) != null }, "a projection was built")
        assertEquals(datum, proj.space, "it landed on the datum, not the plan the source was picked in")
        val pl = ed.datumPlane(datum)
        val f = foot(pl, Vec3(30.0, 40.0, 0.0))
        val world = pl.toWorld(uvOf(proj))
        assertClose(world.x, f.x, 1e-9, "the foot")
        assertClose(world.y, f.y, 1e-9)
        assertClose(world.z, f.z, 1e-9)
        // it shares the source's node
        assertTrue(ed.doc.projectedOf(proj)!!.source === src, "the projection shares the source element")
    }

    // ---- persistence ----

    @Test
    fun aFileWithAProjectedPointRoundTripsByteForByte() {
        val (ed, datum) = fixture()
        val src = ed.planPoint(Vec2(30.0, 40.0))
        ed.setActiveSpace(datum)
        assertNotNull(ed.doc.projectToPlane(src))

        val s1 = DocumentFormat.save(ed.doc)
        assertTrue(s1.contains("projectplane"), "the file records a projectplane step: $s1")
        val reloaded = DocumentFormat.load(s1)
        assertTrue(reloaded.loadNotes.isEmpty(), "no migration was needed: ${reloaded.loadNotes}")
        val s2 = DocumentFormat.save(reloaded)
        assertEquals(s1, s2, "a projected point round-trips byte-for-byte")

        // the reloaded projection is geometrically the same and still follows its source
        val proj = assertNotNull(reloaded.elements.lastOrNull { reloaded.projectedOf(it) != null })
        val pl = Evaluator().plane(assertNotNull(reloaded.spaceNamed(datum)?.plane))
        val f = foot(pl, Vec3(30.0, 40.0, 0.0))
        val world = pl.toWorld(Evaluator().point(proj.ref as PointRef))
        assertClose(world.x, f.x, 1e-9, "the reloaded foot")
        assertClose(world.y, f.y, 1e-9)
    }

    @Test
    fun anOlderFileWithoutOneStillLoads() {
        // the header version is unchanged, so a drawing that predates this feature loads with no notes
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(5.0, 5.0))
        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        assertTrue(reloaded.loadNotes.isEmpty(), "an ordinary drawing loads clean: ${reloaded.loadNotes}")
        assertEquals(text, DocumentFormat.save(reloaded), "and round-trips")
    }

    private fun assertNotEquals(
        a: String,
        b: String,
    ) = assertTrue(a != b, "expected different spaces but both were $a")

    private fun Double.deg() = constructit.units.Quantity.deg(this)
}
