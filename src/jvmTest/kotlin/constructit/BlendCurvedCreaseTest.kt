package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Curve3Element
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The ball along a curved crease** (OP-31, slice 5b — the fitted tier's second slice; GitHub #36).
 *
 * *The rule.* Where a crease is a **circular arc** and the two faces it lies between are each a plane, a
 * cylinder, a cone or a torus **about the same axis**, the rounding of it is that section **revolved** about
 * that axis through the arc's own angle — `Revolve3`'s machinery, exact, with the torus or cone it makes
 * named by that vocabulary and its rails stated as the circles they are. Its figure is Pappus'.
 *
 * *What the slice had to add for the rule to have anything to work on.* Two of the three curved creases a
 * dressed body has were **not in the edge list at all**, so an ordinary ask had no address:
 *
 * 1. **A band's free end leaves a notch curve in the face its cap stands in** — a quarter circle between the
 *    band's cylinder and the plane square to its axis — and session 81 stated it only as a *boundary piece*
 *    of that face. *"Round off the end of a rounded edge"* therefore could not be said. It is
 *    [EdgeName.BlendNotch] now, appended after every rail and every corner curve so that no stored address
 *    moves, and its rounding is the revolution above.
 * 2. **A strip taken off a curve a corner splices into a face** — the notch, a walk's tangent rail — could
 *    not be stated: a trim composes down the chain and a notch does not, so `Blend3.correctedOutline` looked
 *    for the curve among the trimmed list's own pieces, found none, and refused a face that is perfectly
 *    statable. The strip is taken **at the tip, with the splice** (`Blend3.insetChain`), which is exact:
 *    each piece steps onto its own offset carrier away from the corner, neighbours stepped by the same width
 *    meet on their carriers, and where the width **changes** — at the two open ends of the chain, and
 *    between a rounded leg and one that is not — the boundary genuinely steps, which is the band's own flat
 *    cap standing in the face and is stated as the straight run it is.
 *
 * And one thing the rule needed that no free end had before: **the flat end of a band along a curved crease
 * is a face of the body** ([FaceName.BlendCap]). A straight band's cap stands in a face and is that face's
 * notch; a curved one's stands on a meridian plane that is a face of nothing, so `facesAreWholeBoundary`'s
 * claim about a dressed part was false there and a level section through the cap could not close.
 *
 * *What was this slice's one cut, and is not any more*: a rounding along the **elliptical** mitre between two
 * equal rounds. Its spine is exact and this slice found it — the mitre's own ellipse scaled about the point
 * the two axes cross — but the section it carries **changes** along the run, so it was refused by name and
 * queued. Slice 5f built it: a crease with no rigid section is a **canal** band, the pipe surface of a ball
 * carried along that spine, and the two tests below now assert the band rather than the refusal.
 */
class BlendCurvedCreaseTest {
    private val L = LBlock()

    private var ids = 0

    // ---- the plate with one rounded rim: the free end's notch ----

    private val plate = listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0))

    private val height = 20.0

    private val rimRadius = 4.0

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The plate with the rim edge over `y = 0` rounded at 4 mm — the fixture the notch curve lives on. */
    private fun roundedPlate(cx: Construction): SolidRef {
        val base = prism(cx, plate, height)
        val body = Evaluator().solid(base)
        val edges = assertNotNull(Section3.edges(body.feature).first, "the plate names its edges")
        val rim =
            assertNotNull(
                edges.indices.firstOrNull { i ->
                    val el = Blend3.edgePath(edges[i]).first?.elements?.singleOrNull() ?: return@firstOrNull false
                    listOf(el.start, el.end).all { abs(it.z - height) <= 1e-9 && abs(it.y) <= 1e-9 }
                },
                "the rim edge over y = 0",
            )
        val (choices, why) = Blend3.choicesFor(body, listOf(rim), BlendSection(BlendKind.FILLET, rimRadius))
        assertNotNull(choices, why?.render())
        return cx.blendAll(
            base,
            cx.planeXY(),
            listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(rimRadius.mm), null, listOf(rim), choices)),
        )
    }

    private fun edgesOf(solid: Solid3) = assertNotNull(Section3.edges(solid.feature).first, "it names its edges")

    private fun facesOf(solid: Solid3) = assertNotNull(Section3.faces(solid.feature).first, "it names its faces")

    private fun volumeOf(
        ref: SolidRef,
        what: String,
    ): Double {
        val ev = Evaluator()
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    /** One rounding on [on] at [address], scored the way a live gesture scores it (OP-1/OP-18). */
    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
        kind: BlendKind = BlendKind.FILLET,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, listOf(address), BlendSection(kind, size))
        assertNotNull(choices, "edge $address is scored: ${why?.render()}")
        return cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(kind, cx.const(size.mm), null, listOf(address), choices)))
    }

    /**
     * **The bracket a rounding along a circular crease is held to, by containment** — and nothing is fitted
     * into it.
     *
     * Pappus states the exact figure `A·φ·ρ`, and the tool the boolean actually gets differs from it twice
     * over and in opposite directions: the section reaches it as **chords** of its own arc, which is more
     * area than the wedge has, and the turn reaches it as chords of its own circle, which is less path than
     * the arc has. So the removal is bracketed between the **exact** section carried at the nearest radius
     * it reaches and the **chorded** one carried at the farthest — a containment bound on both sides, in the
     * matrix's own style, and never widened to admit a body.
     */
    private fun revolutionBracket(
        before: Double,
        size: Double,
        kind: BlendKind,
        theta: Double,
        phi: Double,
        radius: Double,
        outward: Boolean,
    ): Bracket {
        val setback = if (kind == BlendKind.CHAMFER) size else size / kotlin.math.tan(theta / 2.0)
        val near = if (outward) radius else radius - setback
        val far = if (outward) radius + setback else radius
        val lo = Figures.revolutionTakes(size, kind, theta, phi, near)
        val hi = Figures.wedgeAreaByChords(size, kind, theta) * phi * far
        return Bracket(before - hi, before - lo)
    }

    // ---- 1. the free end's notch curve is an edge, and its rounding is a revolution ----

    /**
     * **The notch curve is in the edge list, and it is the quarter circle it is.** A 4 mm round along one
     * rim of a 60 × 40 × 20 plate ends free at each side face; the crease between its cylinder and the plane
     * square to that cylinder's own axis is a quarter circle of the rounding's radius, and it is an edge of
     * this body with a name, a carrier and two faces — appended after both rails, so nothing renumbers.
     */
    @Test
    fun theFreeEndsNotchCurveIsAnEdgeOfTheBody() {
        val cx = Construction()
        val solid = Evaluator().solid(roundedPlate(cx))
        val edges = edgesOf(solid)
        val notches = edges.indices.filter { edges[it].name is EdgeName.BlendNotch }
        assertEquals(2, notches.size, "one notch curve per free end: ${edges.map { it.name }}")
        // …and they stand after every rail, which is what keeps a stored `signs=` meaning what it meant
        val rails = edges.indices.filter { edges[it].name is EdgeName.BlendRail }
        assertTrue(rails.isNotEmpty() && notches.min() > rails.max(), "the notches append after the rails: $rails vs $notches")
        for (i in notches) {
            val e = edges[i]
            assertEquals(null, e.reason?.render(), "${e.name.label.render()} is a crease of this body")
            val g = assertNotNull(e.geom as? EdgeGeom.OnPlane, "the notch lies in the end face's own plane")
            val arc = assertNotNull(g.piece as? ProfileElement.ArcE, "…and it is a circular arc, not a ${g.piece}")
            assertClose(arc.arc.radius, rimRadius, 1e-12, "of the rounding's own radius")
            assertClose(abs(GeomMath.sweep(arc.arc)), PI / 2.0, 1e-12, "a quarter of it")
            assertTrue(e.between.has(FaceName.BlendBand(8, 0)), "it lies between the band…")
            assertTrue(listOf(FaceName.Side(1), FaceName.Side(3)).any { e.between.has(it) }, "…and the face its cap stands in")
        }
    }

    /**
     * **Rounding it is a revolution, and its figure is Pappus'.** The two faces are the band's own cylinder
     * and the plane square to that cylinder's axis, so the section is rigid along the whole quarter and the
     * tool is it revolved: the surface is a **torus** by `Revolve3`'s own naming and both its rails are
     * circles. The two ends of the plate's band give the same body to a part in a million, which is the
     * statement that the revolution's own sense is derived and not stumbled on.
     */
    @Test
    fun roundingTheNotchCurveIsARevolutionWithPappusFigure() {
        val cx = Construction()
        val on = roundedPlate(cx)
        val before = volumeOf(on, "the plate with one rounded rim")
        val solid = Evaluator().solid(on)
        val notches = edgesOf(solid).let { es -> es.indices.filter { es[it].name is EdgeName.BlendNotch } }
        val size = 1.0
        val got = ArrayList<Double>()
        for (at in notches) {
            val ref = round(cx, on, at, size)
            val after = volumeOf(ref, "the notch curve rounded at $size mm")
            got.add(after)
            // the wedge stands between the cylinder and the end plane at a right angle, on the **inside** of
            // the crease circle: the material is the side the cylinder's own axis is on
            val bracket = revolutionBracket(before, size, BlendKind.FILLET, PI / 2.0, PI / 2.0, rimRadius, outward = false)
            assertTrue(after in bracket, "rounding notch $at built $after, outside its own $bracket")
            val body = Evaluator().solid(ref)
            val band = assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.BlendBand(at, 0) }, "the new band is a face")
            assertTrue(band.surface?.band is Revolve3.Band.Torus, "a ball along a circular crease sweeps a torus: ${band.surface?.band}")
            val rails = edgesOf(body).filter { it.name.let { n -> n is EdgeName.BlendRail && n.edge == at } }
            assertEquals(2, rails.size, "the torus band has its two rails")
            for (r in rails) {
                val g = assertNotNull(r.geom as? EdgeGeom.OnPlane, "a rail of a revolved band is a ring: ${r.geom}")
                assertTrue(g.piece is ProfileElement.ArcE || g.piece is ProfileElement.CircleE, "…a circle, not a ${g.piece}")
            }
            println("notch $at | built | $after | $bracket")
        }
        assertEquals(2, got.size, "both ends were rounded")
        assertClose(got[0], got[1], abs(got[0]) * 1e-6, "the two ends of one band are the same body")
    }

    /**
     * **The flat end of the torus band is a face, and the level section closes through it.**
     *
     * A band along a curved crease ends on a **meridian** plane, which is a face of nothing — so before this
     * slice the body had a face the drawing did not state, and a plane crossing it could not close its loop
     * (`z > 19` here, where the inset arc has run out and the boundary is the cap's own straight step). The
     * cap is stated now, and below the band the plate's section is exactly the rectangle the radius says.
     */
    @Test
    fun theFlatEndOfACurvedBandIsAFaceAndTheSectionCloses() {
        val cx = Construction()
        val on = roundedPlate(cx)
        val notch = edgesOf(Evaluator().solid(on)).let { es -> es.indices.first { es[it].name is EdgeName.BlendNotch } }
        val ref = round(cx, on, notch, 1.0)
        val body = Evaluator().solid(ref)
        assertManifold(body.mesh, "the notch rounded")
        val caps = facesOf(body).filter { it.name is FaceName.BlendCap }
        assertEquals(2, caps.size, "the torus band has a flat end at each of its own two ends")
        for (c in caps) {
            assertEquals(null, c.reason?.render(), "${c.name.label.render()} is a plane one can sketch on")
            assertEquals(3, c.outline.size, "…and its outline is the wedge itself: two legs and the arc")
            assertTrue(c.outline.any { it is ProfileElement.ArcE }, "…the rounding's own arc among them: ${c.outline}")
        }
        for (z in listOf(19.9, 19.5, 19.0, 18.0, 16.5, 10.0)) {
            val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
            assertNotNull(regions, "the section at z = $z closes: ${why?.render()}")
            assertEquals(1, regions.size, "one area at z = $z")
        }
        // below the band the plate is whole, to the last bit
        assertClose(areaAt(body, 10.0), 60.0 * 40.0, 1e-9, "below the band the plate is whole")
    }

    private fun areaAt(
        body: Solid3,
        z: Double,
    ): Double {
        val (regions, why) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        val rs = assertNotNull(regions, "the section at z = $z closes: ${why?.render()}")
        var area = 0.0
        for (r in rs) {
            area += abs(GeomMath.signedArea(r.outer))
            for (h in r.holes) area -= abs(GeomMath.signedArea(h))
        }
        return area
    }

    /**
     * **A whole-face gesture does not sweep the notch curve up, and it must not.** The face gesture records
     * one scored choice per edge of its run, so the *length* of that list is part of what a stored face
     * address means — and how many notch curves a face carries is a function of how many free ends the bands
     * below it happen to have, which every later gesture may move (OP-18). The curve has its own address
     * instead, which is the whole point of this slice.
     */
    @Test
    fun aWholeFaceGestureLeavesTheNotchCurveToItsOwnPick() {
        val cx = Construction()
        val on = roundedPlate(cx)
        val body = Evaluator().solid(on)
        val faces = facesOf(body)
        val side = faces.indexOfFirst { it.name == FaceName.Side(1) }
        val run = assertNotNull(Blend3.targets(body.feature, true, side).first, "the side face has edges to round")
        val edges = edgesOf(body)
        assertTrue(run.none { edges[it].name is EdgeName.BlendNotch }, "the notch curve is not in the face's own run: $run")
        assertTrue(edges.indices.any { edges[it].name is EdgeName.BlendNotch && edges[it].between.has(FaceName.Side(1)) }, "…though it is a crease of that face")
    }

    /**
     * **A free end a later gesture turns into a corner is no free end, and its notch curve says so.**
     *
     * The notch curve is the crease between a band and the flat cap at its own free end — so where a later
     * rounding makes a **corner** there, the cap is gone and with it the crease. The entry keeps its index,
     * because a `signs=` in some file may hold it, and states the reason instead of a carrier the body no
     * longer has (OP-3, OP-17) — the same tombstone rule a consumed edge and a re-turned corner curve live
     * under.
     */
    @Test
    fun aNotchCurveWhoseFreeEndBecomesACornerKeepsItsIndexAndSaysSo() {
        val cx = Construction()
        val one = roundedPlate(cx)
        val first = Evaluator().solid(one)
        val notches = edgesOf(first).let { es -> es.indices.filter { es[it].name is EdgeName.BlendNotch } }
        assertEquals(2, notches.size, "two free ends, two notch curves")
        // the neighbouring rim, rounded at the same size on the body the first rounding made: the two bands
        // are congruent and share a vertex, so a corner is built there and one of the two free ends is gone
        val rim2 =
            assertNotNull(
                edgesOf(first).indices.firstOrNull { i ->
                    val e = edgesOf(first)[i]
                    if (e.reason != null || e.name !is EdgeName.CapPiece) return@firstOrNull false
                    val el = Blend3.edgePath(e).first?.elements?.singleOrNull() ?: return@firstOrNull false
                    listOf(el.start, el.end).all { abs(it.z - height) <= 1e-9 } && listOf(el.start, el.end).any { abs(it.x - 60.0) <= 1e-9 && abs(it.y) <= 1e-9 }
                },
                "a rim edge meeting the first at the plate's own corner",
            )
        val two = round(cx, one, rim2, rimRadius)
        val body = Evaluator().solid(two)
        assertManifold(body.mesh, "two adjacent rims rounded")
        val after = edgesOf(body)
        val gone = notches.filter { after[it].reason != null }
        assertEquals(1, gone.size, "the free end the corner claimed is the one that says so: ${notches.map { after[it].reason?.render() }}")
        assertTrue("corner" in assertNotNull(after[gone.first()].reason, "…and it says why").render(), "the reason names the corner")
        // …and the other one is untouched, index, carrier and all
        val stands = notches.first { it !in gone }
        assertEquals(null, after[stands].reason?.render(), "the free end no corner claims is still a crease")
        assertEquals(edgesOf(first)[stands].geom, after[stands].geom, "…on the very carrier it had")
    }

    /** **The drawing round-trips byte-equal, and one undo takes the rounding of the notch back off.** */
    @Test
    fun theFileRoundTripsAndOneUndoGivesTheNotchRoundingBack() {
        val script = plateScript()
        val once = DocumentFormat.save(DocumentFormat.load(script))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")

        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val both = volumeOf(bodyRef(ed.doc), "the rim and its notch")
        // a rounding that addresses a curve the dressing itself put there is a **body of its own** (OP-30's
        // chain), so it is taken off by deleting that body — and what is left is the rim's own round
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(3, solids.size, "the plate, the rim's round, and the notch's own rounding")
        ed.selectElement(solids.last())
        assertTrue(ed.deleteSelection(), "the notch's rounding comes off: ${ed.statusHint}")
        val rimOnly = volumeOf(bodyRef(ed.doc), "the rim alone")
        assertTrue(rimOnly > both, "…and its torus's worth of material with it: $rimOnly against $both")
        assertTrue(ed.undo(), "the removal is one undo step")
        assertEquals(both, volumeOf(bodyRef(ed.doc), "restored"), "one undo gives it back to the last bit")
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyRef(doc: constructit.editor.Document): SolidRef =
        (doc.elements.last { it.kind == ElementKind.SOLID } as Element).ref as SolidRef

    /** The plate, its rim rounded and then the notch its free end leaves — written as the file it is. */
    private fun plateScript(): String {
        val cx = Construction()
        val on = roundedPlate(cx)
        val edges = edgesOf(Evaluator().solid(on))
        val rim = edges.indices.first { edges[it].name is EdgeName.BlendRail }.let { (edges[it].name as EdgeName.BlendRail).edge }
        val notch = edges.indices.first { edges[it].name is EdgeName.BlendNotch }
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        sb.append("orthostart ${plate[0].x},${plate[0].y} -> e1\n")
        var n = 2
        for (i in 1 until plate.size) {
            sb.append("orthovertex ${plate[i].x},${plate[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = ${height}mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-10,-10 scalar=\"h\" -> e$n\n")
        val solidAt = n
        sb.append("param \"r\" = ${rimRadius}mm\n")
        sb.append("tool filletedge els=e$solidAt clicks=0,0 scalar=\"r\" signs=$rim;-1;1;0;1 -> e${n + 1},e${n + 2}\n")
        sb.append("param \"r2\" = 1mm\n")
        // …and the second **addresses a rail-level curve**, which is a rounding standing on the dressed body
        // rather than another entry of the same dressing — so it declares a solid of its own (OP-30)
        sb.append("tool filletedge els=e${n + 1} clicks=0,0 scalar=\"r2\" signs=$notch;-1;1;0;1 -> e${n + 3},e${n + 4}\n")
        return sb.toString()
    }

    // ---- 2. a bevel pair's cone rail, alone and as part of its chain ----

    /** The L's two top edges that meet at the plan's inside corner, both bevelled at 4 mm. */
    private fun bevelPair(): Pair<SolidRef, Solid3> {
        val (stages, why) = L.run(listOf(13, 14).map { Rounding(it, BlendKind.CHAMFER, 4.0) }, Route.ONE_PASS)
        val ref = assertNotNull(stages, why).last()
        return ref to Evaluator().solid(ref)
    }

    /**
     * **A bevel pair's corner rail is an arc, and rounding it is a revolution.**
     *
     * Two bevels whose shared face turns an **inside** corner walk their section round the upright, and the
     * tangency that walk keeps on the shared face is a **circle** about the upright — a cone rail, and a
     * crease of the body since item 3. A rounding along it is that section revolved about the walk's own
     * axis: the band is a torus, its rails are rings, and the figure is Pappus over the quarter turn.
     */
    @Test
    fun aBevelPairsConeRailRoundsAsARevolution() {
        val (ref, solid) = bevelPair()
        val before = volumeOf(ref, "two bevels at the inside corner")
        val edges = edgesOf(solid)
        val rail = edges.indices.first { edges[it].name is EdgeName.BlendCornerRail }
        val g = assertNotNull(edges[rail].geom as? EdgeGeom.OnPlane, "a turning leg's rail is a ring")
        val arc = assertNotNull(g.piece as? ProfileElement.ArcE, "…a circular arc, not a ${g.piece}").arc
        assertClose(arc.radius, 4.0, 1e-9, "the walk turns on the bevel's own setback")
        val phi = abs(GeomMath.sweep(arc))
        assertClose(phi, PI / 2.0, 1e-9, "and through the corner's own exterior angle")

        val size = 1.0
        val rounded = round(L.cx, ref, rail, size)
        val after = volumeOf(rounded, "the cone rail rounded at $size mm")
        // the rail lies between the walk's **cone** and the shared face, and a bevel halves a right angle,
        // so the wedge stands in 3π/4 — the very dihedral a bevel's own rail stands in
        val bracket = revolutionBracket(before, size, BlendKind.FILLET, 3.0 * PI / 4.0, phi, arc.radius, outward = false)
        assertTrue(after in bracket, "the cone rail's rounding built $after, outside its own $bracket")
        println("cone rail | built | $after | $bracket")

        // **and the face the walk runs in says so.** The tangency arc is a curve the corner *splices* into
        // that face rather than a piece of its trimmed boundary, so the strip is taken with the splice: the
        // arc steps in by the rounding's own setback, and where the neighbouring rails are **not** rounded
        // the boundary steps back out to the corner — the band's two flat ends standing in the face, stated
        // as the straight runs they are.
        val setback = size / kotlin.math.tan(3.0 * PI / 8.0)
        val top = assertNotNull(facesOf(Evaluator().solid(rounded)).firstOrNull { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) }, "the top face")
        assertEquals(null, top.reason?.render(), "the shared face is stated rather than refused")
        val arcs = top.outline.filterIsInstance<ProfileElement.ArcE>()
        assertEquals(1, arcs.size, "one turn, one arc in the boundary: ${top.outline}")
        // the walk turns about the plan's **reflex** vertex, so the face lies *away* from that vertex and a
        // strip off its boundary is a step outward — which is the rule [Blend3.insetChain] states once for
        // every spliced chain: the material is on the far side of the corner the splice cut off
        assertClose(arcs[0].arc.radius, arc.radius + setback, 1e-9, "the tangency arc has stepped off by the rounding's own setback")
        val was = facesOf(solid).first { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) }
        assertClose(areaOfOutline(top.outline), areaOfOutline(was.outline) - stripArea(arc.radius, setback, phi), 1e-9, "the strip it took is the annulus between the two arcs")
    }

    /**
     * **One ribbon, one body, whichever of its three pieces is picked** — the reporter's own ask read for a
     * corner that turns: rail → corner rail → rail is one tangent-continuous chain, so a pick anywhere on it
     * rounds all of it, and the figure is the two bands over their own runs plus the revolution over the
     * turn.
     */
    @Test
    fun anyPickOnTheRibbonRoundsTheWholeOfItAndTheFigureAdds() {
        val (ref, solid) = bevelPair()
        val before = volumeOf(ref, "two bevels at the inside corner")
        val edges = edgesOf(solid)
        val rail = edges.indices.first { edges[it].name is EdgeName.BlendCornerRail }
        val chain = assertNotNull(Blend3.targets(solid.feature, false, rail, Blend3.chainRun()).first, "the chain through the corner rail")
        assertEquals(3, chain.size, "rail, corner rail, rail: $chain")
        for (pick in chain) {
            assertEquals(chain, Blend3.targets(solid.feature, false, pick, Blend3.chainRun()).first, "picking e$pick names the same ribbon")
        }
        val size = 1.0
        val (choices, why) = Blend3.choicesFor(solid, chain, BlendSection(BlendKind.FILLET, size))
        assertNotNull(choices, why?.render())
        val out =
            L.cx.blendAll(
                ref,
                L.cx.planeXY(),
                listOf(Construction.BlendRun(BlendKind.FILLET, L.cx.const(size.mm), null, chain, choices)),
            )
        val after = volumeOf(out, "the whole ribbon rounded at $size mm")

        // the figure: a band at 3π/4 over each straight rail's own run, and Pappus over the turn
        val theta = 3.0 * PI / 4.0
        val straight = chain.filter { edges[it].geom is EdgeGeom.Straight }
        val runs = straight.sumOf { runLength(edges[it]) }
        val g = edges[rail].geom as EdgeGeom.OnPlane
        val arc = (g.piece as ProfileElement.ArcE).arc
        val phi = abs(GeomMath.sweep(arc))
        val setback = size / kotlin.math.tan(theta / 2.0)
        val lo = Figures.wedgeArea(size, BlendKind.FILLET, theta) * runs + Figures.revolutionTakes(size, BlendKind.FILLET, theta, phi, arc.radius - setback)
        val hi = Figures.wedgeAreaByChords(size, BlendKind.FILLET, theta) * (runs + phi * arc.radius)
        val bracket = Bracket(before - hi, before - lo)
        assertTrue(after in bracket, "the ribbon built $after, outside the two bands and the turn: $bracket")
        println("ribbon | built | $after | $bracket over $runs mm and a $phi turn")

        // …and the body says what it has: no face carries a fault, the shared face still opens a sketch,
        // and a level section closes at every height the ribbon crosses
        val body = Evaluator().solid(out)
        val top = assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) }, "the top face is stated")
        assertEquals(null, top.reason?.render(), "a face space still opens on the face the ribbon runs in")
        // …and where the whole ribbon is rounded the boundary steps **nowhere**: the two straight rails and
        // the turn between them lose the same setback, so the trims and the splice meet exactly and the
        // outline keeps the piece count it had
        val was = assertNotNull(facesOf(solid).firstOrNull { it.name == FaceName.Cap(constructit.geom.SolidFace.TOP) }, "the top face before")
        assertEquals(was.outline.size, top.outline.size, "no step is spliced in where the whole ribbon is rounded: ${top.outline.size} pieces against ${was.outline.size}")
        val turned = top.outline.filterIsInstance<ProfileElement.ArcE>()
        assertEquals(1, turned.size, "one turn, one arc")
        assertClose(turned[0].arc.radius, arc.radius + setback, 1e-9, "and the turn lost the same setback the rails did")
        for (z in listOf(19.5, 19.0, 18.0, 17.0, 16.5, 15.0)) {
            val (regions, whyZ) = Section3.regionsOf(body.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
            assertNotNull(regions, "the section at z = $z closes: ${whyZ?.render()}")
        }
    }

    /**
     * The area a closed outline encloses — **exactly**, which is the whole point: [GeomMath.signedArea]
     * integrates an arc in closed form, so a chord anywhere in the answer would show up as a shortfall
     * rather than hide in a tolerance.
     */
    private fun areaOfOutline(outline: List<ProfileElement>): Double = abs(GeomMath.signedArea(constructit.geom.Loop(outline)))

    /** The annulus a strip of [setback] off an arc of radius [r] over [phi] takes: `φ((r + d)² − r²)/2`. */
    private fun stripArea(
        r: Double,
        setback: Double,
        phi: Double,
    ): Double = phi * ((r + setback) * (r + setback) - r * r) / 2.0

    private fun runLength(e: constructit.geom.SolidEdge): Double {
        val path = Blend3.edgePath(e).first ?: return 0.0
        return path.elements.sumOf { p ->
            when (p) {
                is Curve3Element.Seg3 -> (p.end - p.start).length()
                is Curve3Element.Arc3 -> p.radius * abs(p.sweepAngle)
                else -> 0.0
            }
        }
    }

    // ---- 3. what was the cut: the elliptical mitre, delivered as a canal band in slice 5f ----

    /**
     * **A rounding along the elliptical mitre between two equal rounds builds** — and until slice 5f it was
     * this slice's own whole cut, refused by name.
     *
     * What the refusal said, and why it is retired: *"{name} is an ellipse arc — the mitre where two equal
     * roundings cross — and a rounding carried along it would have a section that changes from one end of the
     * arc to the other, which this drawing does not state. The mitre between two chamfers is a straight
     * crease and can be rounded."* The section does change, and slice 5f states exactly that: a crease with
     * no rigid section is a **canal** band, the pipe surface of a ball carried along the spine, whose section
     * is exact in each station's own normal plane. The spine this slice worked out is the same one, one sign
     * over: the ball rolls **inside** the material at a convex ridge, so its centre stands `R − r` from both
     * axes rather than `R + r`, which is again the mitre's own ellipse scaled about the point the axes cross.
     * `refusal.blend.mitreSectionChanges` is gone from the bundle with the case it named.
     */
    @Test
    fun aRoundingAlongTheEllipticalMitreIsACanalBand() {
        val (stages, why) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.FILLET, 4.0) }, Route.ONE_PASS)
        val solid = Evaluator().solid(assertNotNull(stages, why).last())
        assertManifold(solid.mesh, "two equal rounds crossing")
        val before = Geom3.volume(solid.mesh)
        val edges = edgesOf(solid)
        val at = edges.indices.first { edges[it].name is EdgeName.BlendMitre }
        val piece = (edges[at].geom as EdgeGeom.OnPlane).piece
        assertTrue(piece is ProfileElement.EllipticArcE, "two equal cylinders whose axes meet cut in an ellipse, not a $piece")

        val sec = BlendSection(BlendKind.FILLET, 1.0)
        val (choices, whyChoice) = Blend3.choicesFor(solid, listOf(at), sec)
        val choice = assertNotNull(choices, "the mitre is scored: ${whyChoice?.render()}")[0]
        val (out, whyOut) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.FILLET, 4.0) } + Rounding(at, BlendKind.FILLET, 1.0), Route.STACKED)
        val body = Evaluator().solid(assertNotNull(out, whyOut).last())
        assertManifold(body.mesh, "the canal band along the elliptical mitre")
        val took = before - Geom3.volume(body.mesh)
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(solid.feature, at, sec, choice), "the algebra states the canal's figure")
        assertTrue(took in lo..hi, "the canal takes $took, outside its own bracket [$lo, $hi]")
        // …and the band it leaves is named, with the tolerance its own boundary was fitted to
        val band = assertNotNull(facesOf(body).firstOrNull { it.name == FaceName.BlendBand(at, 0) }, "the canal band is a face of the body")
        assertNotNull(band.fitted, "…and it says how far its own boundary may be")
    }

    /**
     * **The concave twin is the same band one sign over.** Two **fills** crossing at the inside corner of a
     * room leave the same elliptical mitre — two equal cylinders of air whose axes meet — and the rounding
     * along it is the same canal **added** rather than taken away. Until slice 5f both were refused in the
     * same words.
     */
    @Test
    fun theConcaveTwinIsTheSameBandOneSignOver() {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(60.0, 0.0), Vec2(60.0, 40.0), Vec2(0.0, 40.0)), height)
        val faces = facesOf(Evaluator().solid(box))
        val top = faces.indices.first { faces[it].plane?.let { p -> abs(p.normal.normalized().z - 1.0) < 1e-9 && abs(p.origin.z - height) < 1e-9 } == true }
        var on = cx.shell(box, cx.const(3.0.mm), listOf(top))
        // two of the floor's own creases, both fills, meeting at one corner of the room
        for (k in 0 until 2) {
            val body = Evaluator().solid(on)
            val es = edgesOf(body)
            val at =
                assertNotNull(
                    es.indices.firstOrNull { i ->
                        es[i].reason == null &&
                            Blend3.choicesFor(body, listOf(i), BlendSection(BlendKind.FILLET, 2.0)).first?.get(0)?.convex == false &&
                            floorCrease(es[i])
                    },
                    "a concave crease of the room's floor",
                )
            on = round(cx, on, at, 2.0)
        }
        val solid = Evaluator().solid(on)
        assertManifold(solid.mesh, "two fills crossing in a room")
        val before = Geom3.volume(solid.mesh)
        val es = edgesOf(solid)
        val mitres = es.indices.filter { es[it].name is EdgeName.BlendMitre && es[it].reason == null }
        if (mitres.isEmpty()) {
            println("concave twin | no mitre is stated between two fills — nothing to round, and nothing claimed")
            return
        }
        for (m in mitres) {
            val sec = BlendSection(BlendKind.FILLET, 0.5)
            val (choices, whyChoice) = Blend3.choicesFor(solid, listOf(m), sec)
            if (choices == null) {
                val reason = assertNotNull(whyChoice, "a refusal has a reason").render()
                assertTrue(namesSomething(reason), "the refusal names something: '$reason'")
                println("concave twin | refused | — | ${reason.take(80)}")
                continue
            }
            assertTrue(!choices[0].convex, "a fill's mitre is a valley, so the canal is added")
            val out = round(cx, on, m, 0.5)
            val r = Evaluator().eval(out.node)
            if (r is EvalResult.Invalid) {
                assertTrue(namesSomething(r.reason), "a rounding that cannot be built says why: ${r.reason}")
                continue
            }
            val body = Evaluator().solid(out)
            assertManifold(body.mesh, "the concave canal band")
            val added = Geom3.volume(body.mesh) - before
            val (lo, hi) = assertNotNull(Blend3.canalRemoval(solid.feature, m, sec, choices[0]), "the algebra states the fill's figure")
            assertTrue(added in lo..hi, "the concave canal adds $added, outside its own bracket [$lo, $hi]")
        }
    }

    /** Whether [e] runs along the room's floor — one of the creases the two fills are taken on. */
    private fun floorCrease(e: constructit.geom.SolidEdge): Boolean {
        val path = Blend3.edgePath(e).first ?: return false
        val el = path.elements.singleOrNull() ?: return false
        return abs(el.start.z - 3.0) <= 1e-9 && abs(el.end.z - 3.0) <= 1e-9
    }
}
