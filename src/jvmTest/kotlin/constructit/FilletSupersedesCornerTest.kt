package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A fillet supersedes the corner** — GitHub issues #25 and #29, the user's design.
 *
 * *"Extruding a filleted ortho-path creates an un-filleted 3D object … The problem is most likely that the
 * fillet tool only creates the fillet arc, but does not supersede it with its filleted version."*
 *
 * Both halves of the ruling are here: on an **ortho path** a fillet on two adjacent legs becomes that
 * corner's own radius, read by everything that reads the loop; on **plain legs** the tool records in one step
 * what the reporter had to build by hand — the arc, the legs trimmed to the tangencies, the corner point left
 * standing as construction. The reporter's own two scripts are the fixtures, verbatim.
 */
class FilletSupersedesCornerTest {
    /** The reporter's script for #25, verbatim: a five-corner ortho loop, two corners filleted, extruded. */
    private val issue25 =
        """
constructit 3
orthostart -73.625,28.875 -> e1
orthovertex -73.625,84.125 -> e2,e3
orthovertex 67.125,84.125 -> e4,e5
orthovertex 67.125,56.125 -> e6,e7
orthovertex -37.375,56.125 -> e8,e9
orthovertex -37.375,28.875 -> e10,e11
orthoclose -> e12
param "r" = 5mm
tool fillet els=e3,e5 clicks=-73.625,71.125;-64.875,84.125 scalar="r" signs=-1;1 -> e13
tool fillet els=e9,e11 clicks=-28.875,56.125;-37.625,46.625 scalar="r" signs=-1;1 -> e14
param "h" = 18mm
tool extrude els=e12 clicks=-57.375,28.625 scalar="h" -> e15
""".trimStart()

    /** The reporter's *base case* for #25: two plain segments over a shared corner, filleted. */
    private val plainLegs =
        """
constructit 3
point -71.375,24.375 -> e1
point -9.625,84.375 -> e2
tool segment pts=e1,e2 clicks=-71.375,24.375;-9.625,84.375 -> e3
point 27.125,0.875 -> e4
tool segment pts=e2,e4 clicks=-9.625,84.375;27.125,0.875 -> e5
param "r" = 8mm
tool fillet els=e3,e5 clicks=-27.125,66.875;-2.875,70.875 scalar="r" signs=-1;1 -> e6
""".trimStart()

    /** What the reporter had to build by hand for the same result — *"only this one results in a 'filleted' corner"*. */
    private val plainLegsByHand =
        """
constructit 3
point -71.375,24.375 -> e1
point -9.625,84.375 -> e2
tool segment pts=e1,e2 clicks=-71.375,24.375;-9.625,84.375 -> e3
point 27.125,0.875 -> e4
tool segment pts=e2,e4 clicks=-9.625,84.375;27.125,0.875 -> e5
param "r" = 8mm
tool fillet els=e3,e5 clicks=-27.125,66.875;-2.875,70.875 scalar="r" signs=-1;1 -> e6
tool keypoints els=e6 clicks=-11.375,77.875 -> e7,e8,e9
tool segment pts=e8,e1 clicks=-17.625,76.125;-71.625,24.125 -> e10
tool segment pts=e9,e4 clicks=-4.375,73.375;27.625,1.125 -> e11
hide els=e3
hide els=e5
hide els=e2
hide els=e7
""".trimStart()

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun el(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "$name is in the drawing")

    private fun segOf(
        ed: Editor,
        name: String,
    ) = assertNotNull((Evaluator().valueOf(el(ed, name).ref) as? SegmentValue)?.seg, "$name draws a segment")

    private fun pos(el: Element): Vec2 =
        assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p, "the point has a value")

    /** The area the path's own published loop encloses — the one thing that says the corners are rounded. */
    private fun loopArea(ed: Editor): Double {
        val loop = ed.doc.orthoLoopOf(ed.doc.orthoPaths.single())
        val v = Evaluator().eval(ed.doc.cx.loopArea(loop).node)
        assertTrue(v is EvalResult.Ok, "the rounded loop closes: ${(v as? EvalResult.Invalid)?.reason}")
        return ((v as EvalResult.Ok).value as constructit.core.ScalarValue).q.base
    }

    private fun assertRoundTrips(text: String) {
        val once = DocumentFormat.save(DocumentFormat.load(text))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is a fixed point")
        assertEquals(atThisVersion(text), once, "an older file comes back byte for byte but for its header")
    }

    // ---- #25: the ortho path's own corner radius ----

    /**
     * The reported symptom, as geometry: the extruded solid is **rounded at both filleted corners**, so the
     * sharp corner tips the un-filleted body had are gone from its mesh and its rim runs over eight pieces
     * instead of six.
     *
     * The volume is the signed closed form, and the sign is the honest part: the reporter's loop is an L, so
     * the corner at `(-73.625, 84.125)` is **convex** and loses `(1 − π/4)·r²` while the notch corner at
     * `(-37.375, 56.125)` is **reflex** and gains exactly as much — the two cancel, and this shape's rounded
     * volume is its polygon's. A number that cannot tell the fix from the bug is no assertion, which is why
     * the strict one is [aRoundedRectangleLosesExactlyTheCornerSlivers] below (all four corners convex).
     */
    @Test
    fun theExtrudedFilletedPathIsRounded() {
        val ed = load(issue25)
        val solid = el(ed, "e15")
        val r = 5.0
        val h = 18.0
        val polygon = 140.75 * 55.25 - 104.5 * 27.25
        val sliver = (1.0 - PI / 4.0) * r * r
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        // the *mesh* chords its two arcs (OP-15: exact where exact, chords between, and the chords are what
        // is printed), so the closed form is asserted on the loop's own area below and here to the
        // tessellation's own tolerance — which is still a hundred times tighter than the corner slivers
        assertClose(Geom3.volume(mesh), (polygon - sliver + sliver) * h, 1e-1, "one corner convex, one reflex")
        assertManifold(mesh, "e15")
        // the two sharp corner tips are **not in the body any more** — the thing the reporter could see
        for (tip in listOf(Vec2(-73.625, 84.125), Vec2(-37.375, 56.125))) {
            assertTrue(
                mesh.vertices.none { kotlin.math.abs(it.x - tip.x) < 1e-9 && kotlin.math.abs(it.y - tip.y) < 1e-9 },
                "$tip is a filleted corner, so the solid has no vertex standing on it",
            )
        }
        assertRoundTrips(issue25)
    }

    /**
     * The strict number, on a shape whose corners are all convex: a 60 × 40 closed ortho path with two
     * corners filleted to r = 6 and extruded 12 mm has exactly two corner slivers less than the box.
     */
    @Test
    fun aRoundedRectangleLosesExactlyTheCornerSlivers() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)), "a closed rectangular path")
        val r = doc.newParameter("r", constructit.units.Quantity.mm(6.0))
        assertNotNull(
            doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)),
            "the corner at (60, 0) is rounded: ${doc.note}",
        )
        assertNotNull(
            doc.filletBetweenCurves(path.legs[2], path.legs[3], r.ref, Vec2(30.0, 40.0), Vec2(0.0, 20.0)),
            "and the corner at (0, 40): ${doc.note}",
        )
        val h = doc.newParameter("h", constructit.units.Quantity.mm(12.0))
        val solid = assertNotNull(doc.extrudeSolid(path.legs[0], h.ref), "the rounded loop extrudes: ${doc.note}")
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        val want = (60.0 * 40.0 - 2.0 * (1.0 - PI / 4.0) * 36.0) * 12.0
        // exact on the loop the solid is a prism over — the loop *is* the construction — and to the
        // tessellation's tolerance on the mesh, which chords the two arcs
        assertClose(
            Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base * 12.0,
            want,
            1e-9,
            "two corners of r = 6 rounded away, exactly",
        )
        assertClose(Geom3.volume(mesh), want, 5.0, "and the mesh follows within its chords")
        assertManifold(mesh, "the rounded prism")
        // and the same radius on both corners is *one* parameter, so retyping it re-rounds both at once
        doc.setParameter(r, constructit.units.Quantity.mm(3.0))
        val re = Evaluator().solid(solid.ref as SolidRef).mesh
        assertClose(
            Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base * 12.0,
            (60.0 * 40.0 - 2.0 * (1.0 - PI / 4.0) * 9.0) * 12.0,
            1e-9,
            "r = 3 now, and both corners took it",
        )
        assertManifold(re, "the re-rounded prism")
    }

    /** The loop the path publishes is the rounded one: twelve pieces — ten legs and two arcs — and it closes. */
    @Test
    fun theLoopIsTheRoundedOne() {
        val ed = load(issue25)
        val pieces = ed.doc.roundedPiecesOf(ed.doc.orthoPaths.single())
        assertEquals(8, pieces.size, "six legs and two corner arcs")
        assertEquals(2, pieces.count { it.kind == ElementKind.ARC }, "the two fillet arcs are pieces of the loop")
        // the pieces are the *trimmed* legs, so the loop closes on the arcs and encloses the signed area
        val r = 5.0
        val sliver = (1.0 - PI / 4.0) * r * r
        assertClose(loopArea(ed), 140.75 * 55.25 - 104.5 * 27.25 - sliver + sliver, 1e-9)
        assertEquals(4, pieces.count { ed.doc.isTrimmed(it) }, "the four legs the two roundings touch are trimmed")
    }

    /**
     * The tangencies are **key points of the drawing**: the trimmed leg now ends at one and the arc begins
     * there, so *Key points* on either hands the tangency back — snappable and dimensionable like any point.
     */
    @Test
    fun theTangenciesAreKeyPointsOfTheRoundedLoop() {
        val ed = load(issue25)
        val before = ed.doc.elements.size
        ed.setTool(Tools.KEY_POINTS)
        val leg = el(ed, "e3")
        val on = Vec2(-73.625, 50.0)
        ed.pointerDown(ed.camera.worldToScreen(on))
        ed.pointerUp(ed.camera.worldToScreen(on))
        val made = ed.doc.elements.drop(before).filter { it.isPoint }.map { pos(it) }
        assertTrue(made.isNotEmpty(), "e3 hands back its ends: ${ed.statusHint}")
        assertTrue(
            made.any { (it - Vec2(-73.625, 79.125)).length() < 1e-9 },
            "the trimmed leg's own end *is* the tangency, 5 mm short of the corner: $made",
        )
        val was = ed.doc.elements.size
        val arc = el(ed, "e13")
        val onArc = Vec2(-71.0, 82.0)
        ed.pointerDown(ed.camera.worldToScreen(onArc))
        ed.pointerUp(ed.camera.worldToScreen(onArc))
        val fromArc = ed.doc.elements.drop(was).filter { it.isPoint }.map { pos(it) }
        assertTrue(fromArc.any { (it - Vec2(-68.625, 79.125)).length() < 1e-9 }, "the arc's centre: $fromArc")
        assertTrue(fromArc.any { (it - Vec2(-73.625, 79.125)).length() < 1e-9 }, "and both tangencies: $fromArc")
        assertTrue(fromArc.any { (it - Vec2(-68.625, 84.125)).length() < 1e-9 }, "and both tangencies: $fromArc")
        assertEquals(ElementKind.ARC, arc.kind, "the corner arc is an ordinary element of the drawing")
        assertEquals(leg.kind, ElementKind.SEGMENT)
    }

    /** Retyping the radius re-rounds the loop: one parameter, both corners, by construction. */
    @Test
    fun retypingTheRadiusReRoundsBothCorners() {
        val ed = load(issue25)
        val row = ed.doc.scalars.first { it.name == "r" }
        ed.doc.setParameter(row, constructit.units.Quantity.mm(9.0))
        val arcs = ed.doc.elements.filter { it.kind == ElementKind.ARC }
        assertEquals(2, arcs.size)
        for (a in arcs) {
            val v = assertNotNull(Evaluator().valueOf(a.ref) as? constructit.core.ArcValue, "the arc is still there")
            assertClose(v.arc.radius, 9.0, 1e-9, "both corners took the new radius")
        }
        // and the legs followed: the tangency on e3 is now 9 mm below the corner
        val e3 = segOf(ed, "e3")
        assertClose(kotlin.math.max(e3.a.y, e3.b.y), 84.125 - 9.0, 1e-9, "e3 ends 9 mm short of the corner")
    }

    // ---- #25: plain legs — the trim, in one step ----

    /** The reporter's base case: after the fillet, e3 ends at the first tangency and e5 starts at the second. */
    @Test
    fun aFilletOnPlainLegsTrimsThem() {
        val ed = load(plainLegs)
        val arc = assertNotNull(Evaluator().valueOf(el(ed, "e6").ref) as? constructit.core.ArcValue, "e6 is the arc")
        val t1 = GeomMath.arcStart(arc.arc)
        val t2 = GeomMath.arcEnd(arc.arc)
        val e3 = segOf(ed, "e3")
        val e5 = segOf(ed, "e5")
        assertTrue(endsAt(e3, t1), "e3 ends at the tangency $t1, not at the corner: $e3")
        assertTrue(endsAt(e5, t2), "and e5 at the other one: $e5")
        // the corner point is still there, as construction, and still free
        val corner = el(ed, "e2")
        assertClose((pos(corner) - Vec2(-9.625, 84.375)).length(), 0.0, 1e-9)
        assertTrue(corner.draggable, "the corner keeps its degree of freedom")
        assertRoundTrips(plainLegs)
    }

    /** And the geometry is what the reporter's hand-built version draws — the same three curves. */
    @Test
    fun theTrimmedLegsAreWhatTheHandBuiltVersionDraws() {
        val one = load(plainLegs)
        val other = load(plainLegsByHand)
        val a3 = segOf(one, "e3")
        val a5 = segOf(one, "e5")
        val b10 = segOf(other, "e10")
        val b11 = segOf(other, "e11")
        // the hand-built segments run from the tangency to the far point; the trimmed legs from the far point
        // to the tangency — the same two segments, either way round
        assertTrue(sameSegment(a3, b10), "the trimmed e3 is the hand-built e10: $a3 vs $b10")
        assertTrue(sameSegment(a5, b11), "the trimmed e5 is the hand-built e11: $a5 vs $b11")
    }

    /** Dragging the corner point moves both trimmed legs, and the rounding stays exactly tangent. */
    @Test
    fun draggingTheCornerKeepsTheRounding() {
        val ed = load(plainLegs)
        val corner = el(ed, "e2")
        val from = pos(corner)
        val to = from + Vec2(6.0, -11.0)
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(from))
        ed.pointerMove(ed.camera.worldToScreen(to))
        ed.pointerUp(ed.camera.worldToScreen(to))
        assertClose((pos(corner) - to).length(), 0.0, 1e-9, "the corner moved")
        val arc = assertNotNull(Evaluator().valueOf(el(ed, "e6").ref) as? constructit.core.ArcValue).arc
        val e3 = segOf(ed, "e3")
        val e5 = segOf(ed, "e5")
        assertTrue(endsAt(e3, GeomMath.arcStart(arc)), "e3 still ends at the tangency: $e3")
        assertTrue(endsAt(e5, GeomMath.arcEnd(arc)), "e5 still ends at the other: $e5")
        // tangency, exactly: the arc's radius to each tangency is perpendicular to that leg
        val d3 = (e3.b - e3.a).normalized()
        assertClose((GeomMath.arcStart(arc) - arc.center).normalized().dot(d3), 0.0, 1e-9, "e3 is tangent")
        val d5 = (e5.b - e5.a).normalized()
        assertClose((GeomMath.arcEnd(arc) - arc.center).normalized().dot(d5), 0.0, 1e-9, "e5 is tangent")
    }

    /** Whether the trimmed leg [s] has an end at [p] — which end is whichever one the corner was not at. */
    private fun endsAt(
        s: constructit.geom.Segment,
        p: Vec2,
    ): Boolean = (s.a - p).length() < 1e-9 || (s.b - p).length() < 1e-9

    /**
     * **One undo takes the whole thing back**, and deleting the arc does too: the trims are what the fillet
     * step *makes*, so the substrate of undo (the saved script, OP-18) has nothing else to say — replaying
     * without that step rebuilds the legs whole.
     */
    @Test
    fun oneUndoTakesTheTrimBackWithTheArc() {
        // the reporter's base case, with the fillet made as the *gesture* it is — which is what undo undoes
        val ed = load(plainLegs.lines().dropLast(2).joinToString("\n") + "\n")
        ed.activeScalar = ed.doc.newParameter("r", constructit.units.Quantity.mm(8.0))
        ed.setTool(Tools.FILLET)
        for (at in listOf(Vec2(-27.125, 66.875), Vec2(-2.875, 70.875))) {
            ed.pointerDown(ed.camera.worldToScreen(at))
            ed.pointerUp(ed.camera.worldToScreen(at))
        }
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.ARC }, "the rounding: ${ed.statusHint}")
        assertTrue(ed.doc.isTrimmed(el(ed, "e3")), "the fillet trimmed e3")
        assertTrue(ed.undo(), "one step back")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.ARC }, "the arc is gone")
        val e3 = segOf(ed, "e3")
        assertTrue(endsAt(e3, Vec2(-9.625, 84.375)), "and e3 reaches its corner again: $e3")
        assertTrue(!ed.doc.isTrimmed(el(ed, "e3")), "nothing is trimmed any more")
    }

    /** Deleting the rounding is the same statement made the other way round: the legs come back whole. */
    @Test
    fun deletingTheRoundingUntrimsItsLegs() {
        val ed = load(plainLegs)
        ed.selectElement(el(ed, "e6"))
        assertTrue(ed.deleteSelection(), "the arc is deletable")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.ARC })
        val e3 = segOf(ed, "e3")
        assertTrue(endsAt(e3, Vec2(-9.625, 84.375)), "e3 reaches its corner again: $e3")
        assertTrue(endsAt(segOf(ed, "e5"), Vec2(-9.625, 84.375)), "and so does e5")
    }

    /**
     * A rounded corner survives the path's own topology edits: **breaking** a leg elsewhere on the loop
     * leaves the rounding where it is, and the loop the path publishes follows the break — one view, rebound
     * by whatever changes the pieces.
     */
    @Test
    fun breakingAnotherLegKeepsTheRoundedCorner() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)))
        val r = doc.newParameter("r", constructit.units.Quantity.mm(5.0))
        assertNotNull(doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)))
        val h = doc.newParameter("h", constructit.units.Quantity.mm(10.0))
        val solid = assertNotNull(doc.extrudeSolid(path.legs[0], h.ref), "the rounded loop extrudes")
        assertTrue(doc.breakOrthoLeg(path, 2, Vec2(30.0, 40.0), Vec2(30.0, 40.0)), "the far side breaks (OP-19)")
        val pieces = doc.roundedPiecesOf(path)
        assertEquals(7, pieces.size, "six legs after the break, and the corner arc")
        assertTrue(pieces.any { it.kind == ElementKind.ARC }, "the rounding is still a piece of the loop")
        // the body follows the path it was raised from — one view, rebound
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertManifold(mesh, "the broken-and-rounded prism")
        assertClose(
            Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base,
            60.0 * 40.0 - (1.0 - PI / 4.0) * 25.0,
            1e-9,
            "a break moves no area, and the corner is still rounded",
        )
    }

    // ---- what does not fit, and what is not touched ----

    /**
     * A radius the corner cannot host is **invalid with the reason and the number that fits**, and it heals
     * (OP-3): the leg says how much of itself is left, the loop and the solid over it say the same thing
     * through the cascade, and coming back down restores the body exactly.
     */
    @Test
    fun aRadiusTheCornerCannotHostSaysSoAndHeals() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)), "a closed rectangular path")
        val r = doc.newParameter("r", constructit.units.Quantity.mm(6.0))
        assertNotNull(doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)))
        val h = doc.newParameter("h", constructit.units.Quantity.mm(12.0))
        val solid = assertNotNull(doc.extrudeSolid(path.legs[0], h.ref), "the rounded loop extrudes")
        val was = Evaluator().solid(solid.ref as SolidRef).mesh.let { constructit.geom.Geom3.volume(it) }

        doc.setParameter(r, constructit.units.Quantity.mm(70.0))
        val why =
            assertNotNull(
                (Evaluator().eval(path.legs[1].ref.node) as? EvalResult.Invalid)?.reason,
                "the leg the rounding overruns is invalid, not quietly turned round",
            )
        assertTrue(why.contains("the rounding overruns this leg"), why)
        assertTrue(why.contains("the largest that fits here is 40 mm from the corner"), "it names the number: $why")
        // …and the 60 mm leg of the same corner says its own number
        val other = assertNotNull((Evaluator().eval(path.legs[0].ref.node) as? EvalResult.Invalid)?.reason)
        assertTrue(other.contains("the largest that fits here is 60 mm from the corner"), other)
        // and the solid says the same thing, through the cascade (whichever leg it meets first)
        val solidWhy = assertNotNull((Evaluator().eval(solid.ref.node) as? EvalResult.Invalid)?.reason, "the body follows")
        assertTrue(solidWhy.contains("the largest that fits here is"), solidWhy)

        doc.setParameter(r, constructit.units.Quantity.mm(6.0))
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertClose(constructit.geom.Geom3.volume(mesh), was, 1e-9, "it heals to exactly the body it was")
        assertManifold(mesh, "the healed prism")
    }

    /**
     * **A rounding takes material off a leg exactly when its handover lands on that leg**, and where it does
     * not the piece is untouched — which is what a rounding *against a carrier* has always been (a fillet on
     * a shallow corner whose tangency falls past the leg's own end; `ArcCarrierTest`'s rule one construction
     * over). So no drawing written before this reading loses geometry.
     */
    @Test
    fun aRoundingWhoseTangencyMissesTheLegLeavesItWhole() {
        val ed = Editor()
        val doc = ed.doc
        // a very shallow corner: two 30 mm legs meeting at about 10°, rounded with r = 14 mm, whose tangency
        // is some 160 mm along each leg — far beyond either
        val a = doc.freePoint(constructit.units.Quantity.mm(0.0), constructit.units.Quantity.mm(0.0))
        val b = doc.freePoint(constructit.units.Quantity.mm(30.0), constructit.units.Quantity.mm(0.0))
        val c = doc.freePoint(constructit.units.Quantity.mm(0.45), constructit.units.Quantity.mm(5.21))
        val leg1 = doc.segment(b, a)
        val leg2 = doc.segment(b, c)
        val r = doc.newParameter("r", constructit.units.Quantity.mm(14.0))
        assertNotNull(doc.filletBetweenCurves(leg1, leg2, r.ref, Vec2(15.0, 0.0), Vec2(15.2, 2.6)), "the arc is built")
        assertTrue(!ed.doc.isTrimmed(leg1) && !ed.doc.isTrimmed(leg2), "neither leg gave anything up")
        assertClose(segOf(ed, ed.doc.nameOf(leg1)).let { (it.b - it.a).length() }, 30.0, 1e-9, "e4 is its whole self")
        assertTrue(Evaluator().valueOf(leg1.ref) != null && Evaluator().valueOf(leg2.ref) != null, "and both draw")
    }

    /** A leg between **two** rounded corners is trimmed at both ends — the trims compose, in one chain. */
    @Test
    fun aLegSharedByTwoCornersIsTrimmedAtBothEnds() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)))
        val r = doc.newParameter("r", constructit.units.Quantity.mm(5.0))
        // both corners of leg 1 (the right-hand side, from (60,0) to (60,40))
        assertNotNull(doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)))
        assertNotNull(doc.filletBetweenCurves(path.legs[1], path.legs[2], r.ref, Vec2(60.0, 20.0), Vec2(30.0, 40.0)))
        val side = assertNotNull(Evaluator().valueOf(path.legs[1].ref) as? SegmentValue).seg
        assertClose(kotlin.math.min(side.a.y, side.b.y), 5.0, 1e-9, "trimmed 5 mm up from (60, 0)")
        assertClose(kotlin.math.max(side.a.y, side.b.y), 35.0, 1e-9, "and 5 mm down from (60, 40)")
        assertClose(side.a.x, 60.0, 1e-9)
        // the loop closes over six pieces: four legs, two arcs
        val pieces = doc.roundedPiecesOf(path)
        assertEquals(6, pieces.size, "four legs and two corner arcs")
        val area = Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base
        assertClose(area, 60.0 * 40.0 - 2.0 * (1.0 - PI / 4.0) * 25.0, 1e-9, "two corner slivers off the box")
    }

    /**
     * **The boundary is one traced loop with the rounded corner in it**: over the trimmed `e3`, the arc, the
     * trimmed `e5` and a closing segment, the *Outline* tool's follow closes in two clicks — the fillet's
     * tangencies being joints the construction registered — and the area is the triangle's less exactly the
     * sliver the rounding took out of its corner.
     */
    @Test
    fun anOutlineOverTheTrimmedLegsAndTheArcClosesWithTheRoundedCorner() {
        val ed = load(plainLegs)
        val e1 = Vec2(-71.375, 24.375)
        val e2 = Vec2(-9.625, 84.375)
        val e4 = Vec2(27.125, 0.875)
        // the closing side, so the three curves bound something
        ed.setTool(Tools.SEGMENT)
        ed.pointerDown(ed.camera.worldToScreen(e1))
        ed.pointerUp(ed.camera.worldToScreen(e1))
        ed.pointerDown(ed.camera.worldToScreen(e4))
        ed.pointerUp(ed.camera.worldToScreen(e4))

        val arc = assertNotNull(Evaluator().valueOf(el(ed, "e6").ref) as? constructit.core.ArcValue).arc
        ed.setTool(Tools.OUTLINE)
        for (at in listOf((e1 + e2) * 0.5, GeomMath.sampleArc(arc, 2)[1])) {
            ed.pointerDown(ed.camera.worldToScreen(at))
            ed.pointerUp(ed.camera.worldToScreen(at))
        }
        val outline =
            assertNotNull(
                ed.doc.elements.singleOrNull { it.kind == ElementKind.OUTLINE },
                "two clicks close the whole boundary: ${ed.statusHint}",
            )

        @Suppress("UNCHECKED_CAST")
        val loop = assertNotNull(Evaluator().valueOf(outline.ref) as? constructit.core.LoopValue).loop
        assertEquals(4, loop.elements.size, "the two trimmed legs, the rounding and the closing side")
        assertEquals(1, loop.elements.count { it is constructit.geom.ProfileElement.ArcE }, "the corner is an arc of it")

        // the area, exactly: the triangle less the corner sliver — the kite between the tangent lines and
        // the corner, less the arc's own sector
        val u = (e1 - e2).normalized()
        val v = (e4 - e2).normalized()
        val theta = kotlin.math.acos(u.dot(v))
        val r = 8.0
        val sliver = r * r / kotlin.math.tan(theta / 2.0) - r * r * (PI - theta) / 2.0
        val triangle = kotlin.math.abs((e2 - e1).cross(e4 - e1)) / 2.0
        assertClose(kotlin.math.abs(GeomMath.signedArea(loop)), triangle - sliver, 1e-9, "the corner is rounded away")
    }

    // ---- the wall over a rounded corner: both faces follow it ----

    /**
     * **A thickened filleted ortho path has both its faces rounded**, exactly: a ring between two offsets of
     * the same carrier encloses `perimeter × thickness` whatever the carrier is made of — `L·t` along every
     * straight run, `φ·r·t` round every arc, and nothing at a right-angle mitre — so the number is the
     * rounded carrier's own perimeter times the wall.
     *
     * And it is followed **after the fact**: the wall here is built *before* the corner is rounded, and the
     * rounding reaches it with nothing rewired, because each corner's radius is a node from the moment the
     * vertex exists and rounding it is a binding (OP-5).
     */
    @Test
    fun aWallOverARoundedCornerHasBothFacesRounded() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)))
        val t = doc.newParameter("t", constructit.units.Quantity.mm(8.0))
        val wall = assertNotNull(doc.buildThickPath(path, t.ref), "the wall is built first: ${doc.note}")
        assertClose(footprintArea(doc, wall), 2.0 * (60.0 + 40.0) * 8.0, 1e-9, "a mitred ring: perimeter × t")

        val r = doc.newParameter("r", constructit.units.Quantity.mm(6.0))
        assertNotNull(doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)))
        val perimeter = 2.0 * (60.0 + 40.0) - 2.0 * 6.0 + PI / 2.0 * 6.0
        assertClose(footprintArea(doc, wall), perimeter * 8.0, 1e-9, "the rounded carrier's perimeter × t")

        // both faces are arcs about the corner's own centre, at r ± t/2 — exact, not approximated
        val region = assertNotNull(Evaluator().valueOf(wall.footprint.ref) as? constructit.core.RegionValue).region
        val radii =
            (listOf(region.outer) + region.holes)
                .flatMap { it.elements }
                .filterIsInstance<constructit.geom.ProfileElement.ArcE>()
                .map { it.arc.radius }
                .sorted()
        assertEquals(2, radii.size, "one arc per face: $radii")
        assertClose(radii[0], 2.0, 1e-9, "the inner face rounds at r − t/2")
        assertClose(radii[1], 10.0, 1e-9, "and the outer at r + t/2")
    }

    /** A wall too thick for the corner it must round says so, names the thickness that fits, and heals. */
    @Test
    fun aWallTooThickForItsCornerRefusesByName() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)))
        val r = doc.newParameter("r", constructit.units.Quantity.mm(6.0))
        assertNotNull(doc.filletBetweenCurves(path.legs[0], path.legs[1], r.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)))
        val t = doc.newParameter("t", constructit.units.Quantity.mm(8.0))
        val wall = assertNotNull(doc.buildThickPath(path, t.ref), "the wall is built: ${doc.note}")
        val was = footprintArea(doc, wall)

        doc.setParameter(t, constructit.units.Quantity.mm(14.0))
        val why =
            assertNotNull(
                (Evaluator().eval(wall.footprint.ref.node) as? EvalResult.Invalid)?.reason,
                "a 14 mm wall cannot round a 6 mm corner",
            )
        assertTrue(why.contains("the inner face of a 14 mm wall cannot follow a corner radius of 6 mm"), why)
        assertTrue(why.contains("the largest thickness that fits there is 12 mm"), "it names the number: $why")

        doc.setParameter(t, constructit.units.Quantity.mm(8.0))
        assertClose(footprintArea(doc, wall), was, 1e-9, "and it heals to exactly the wall it was")
    }

    /** The area a wall's footprint encloses, hole and all. */
    private fun footprintArea(
        doc: constructit.editor.Document,
        wall: constructit.editor.ThickNetwork,
    ): Double {
        @Suppress("UNCHECKED_CAST")
        val ref = wall.footprint.ref as constructit.dsl.RegionRef
        return Evaluator().scalar(doc.cx.regionArea(ref)).base
    }

    /**
     * **A path's leg is the path's own to round.** A rounding that is not two adjacent legs of one path takes
     * nothing off a leg that belongs to one — two legs of *different* paths meeting at a junction, or two
     * legs of one path that are not neighbours — because a retained path publishes its boundary as an
     * ordered chain and no piece of it would fill the gap. Both loops stay closed and both extrude.
     */
    @Test
    fun aRoundingAcrossTwoPathsLeavesBothLoopsWhole() {
        val ed = Editor()
        val doc = ed.doc
        val a = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(40.0, 40.0)))
        val b = assertNotNull(doc.orthoRectangle(Vec2(40.0, 40.0), Vec2(80.0, 80.0)))
        val r = doc.newParameter("r", constructit.units.Quantity.mm(6.0))
        // the two paths touch at (40, 40); round "the corner" between a leg of each
        assertNotNull(
            doc.filletBetweenCurves(a.legs[1], b.legs[3], r.ref, Vec2(40.0, 20.0), Vec2(40.0, 60.0)),
            "the arc is still built, against the two carriers: ${doc.note}",
        )
        for (path in listOf(a, b)) {
            assertEquals(4, doc.roundedPiecesOf(path).size, "the path's loop is its four legs, whole")
            assertClose(Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base, 1600.0, 1e-9)
        }
        assertTrue(doc.roundedPiecesOf(a).none { doc.isTrimmed(it) }, "nothing was taken off either path")
        // ...and two legs of *one* path that are not neighbours are the same case
        val far = doc.newParameter("r2", constructit.units.Quantity.mm(4.0))
        doc.filletBetweenCurves(a.legs[0], a.legs[2], far.ref, Vec2(20.0, 0.0), Vec2(20.0, 40.0))
        assertEquals(4, doc.roundedPiecesOf(a).size, "parallel legs have no corner to round")
    }

    // ---- the chamfer twin ----

    /** *Chamfer* is the same sentence: on two adjacent legs of an ortho path it is that corner's setback. */
    @Test
    fun aChamferOnAnOrthoCornerIsThatCornersSetback() {
        val ed = Editor()
        val doc = ed.doc
        val path = assertNotNull(doc.orthoRectangle(Vec2(0.0, 0.0), Vec2(60.0, 40.0)))
        val d = doc.newParameter("d", constructit.units.Quantity.mm(8.0))
        val bevel =
            assertNotNull(
                doc.chamferBetweenCurves(path.legs[0], path.legs[1], d.ref, Vec2(30.0, 0.0), Vec2(60.0, 20.0)),
                "the corner at (60, 0) is bevelled: ${doc.note}",
            )
        assertEquals(ElementKind.SEGMENT, bevel.kind, "a bevel is a straight piece of the boundary")
        val pieces = doc.roundedPiecesOf(path)
        assertEquals(5, pieces.size, "four legs and the bevel")
        assertTrue(pieces.any { it === bevel }, "the bevel is a piece of the path's own loop")
        val area = Evaluator().scalar(doc.cx.loopArea(doc.orthoLoopOf(path))).base
        assertClose(area, 60.0 * 40.0 - 0.5 * 8.0 * 8.0, 1e-9, "a right-angled triangle off the corner, exactly")
        val h = doc.newParameter("h", constructit.units.Quantity.mm(10.0))
        val solid = assertNotNull(doc.extrudeSolid(path.legs[0], h.ref), "and it extrudes: ${doc.note}")
        val mesh = Evaluator().solid(solid.ref as SolidRef).mesh
        assertClose(constructit.geom.Geom3.volume(mesh), (60.0 * 40.0 - 32.0) * 10.0, 1e-9, "exact: no arc to chord")
        assertManifold(mesh, "the bevelled prism")
    }

    /** And on plain legs it trims them to the bevel's own ends, exactly as the rounding does. */
    @Test
    fun aChamferOnPlainLegsTrimsThem() {
        val ed = Editor()
        val doc = ed.doc
        val a = doc.freePoint(constructit.units.Quantity.mm(0.0), constructit.units.Quantity.mm(0.0))
        val corner = doc.freePoint(constructit.units.Quantity.mm(50.0), constructit.units.Quantity.mm(0.0))
        val c = doc.freePoint(constructit.units.Quantity.mm(50.0), constructit.units.Quantity.mm(40.0))
        val leg1 = doc.segment(a, corner)
        val leg2 = doc.segment(corner, c)
        val d = doc.newParameter("d", constructit.units.Quantity.mm(10.0))
        val bevel =
            assertNotNull(
                doc.chamferBetweenCurves(leg1, leg2, d.ref, Vec2(25.0, 0.0), Vec2(50.0, 20.0)),
                "the bevel is built: ${doc.note}",
            )
        assertTrue(doc.isTrimmed(leg1) && doc.isTrimmed(leg2), "both legs gave the corner up")
        val s1 = assertNotNull(Evaluator().valueOf(leg1.ref) as? SegmentValue).seg
        val s2 = assertNotNull(Evaluator().valueOf(leg2.ref) as? SegmentValue).seg
        assertClose(kotlin.math.max(s1.a.x, s1.b.x), 40.0, 1e-9, "e4 stops 10 mm short of the corner")
        assertClose(kotlin.math.min(s2.a.y, s2.b.y), 10.0, 1e-9, "and e5 starts 10 mm past it")
        val b = assertNotNull(Evaluator().valueOf(bevel.ref) as? SegmentValue).seg
        assertTrue(endsAt(b, Vec2(40.0, 0.0)) && endsAt(b, Vec2(50.0, 10.0)), "the bevel joins the two ends: $b")
        // the corner point stays, as construction, and still moves both legs and the bevel
        assertTrue(assertNotNull(doc.elementFor(corner)).draggable, "the corner keeps its freedom")
    }

    private fun sameSegment(
        a: constructit.geom.Segment,
        b: constructit.geom.Segment,
    ): Boolean =
        ((a.a - b.a).length() < 1e-9 && (a.b - b.b).length() < 1e-9) ||
            ((a.a - b.b).length() < 1e-9 && (a.b - b.a).length() < 1e-9)
}
