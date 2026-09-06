package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.BlendKind
import constructit.geom.Combine3
import constructit.geom.Curves3
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
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The incongruent inside corner** (OP-31, slice 5a — the fitted tier's first slice; GitHub #36).
 *
 * *What was missing.* Where the face two rounded edges share turns an **inside** corner the two bands do not
 * overlap at all, so a pair that is not congruent landed on no common ring, built no corner, and left
 * GitHub #31's spike standing between the two band ends. Item 1's matrix named 24 such cells and item 2
 * made them **refuse**; this makes them build.
 *
 * *What is built, and why it is the rolling ball's own answer.* A rolling-ball blend removes the material no
 * ball of the family can be kept out of, so the corner is the **union of the two balls' pivots** about the
 * upright — and where one section contains the other that union *is* the containing one's pivot, the
 * contained ball sweeping nothing the other has not already swept. So the **deeper** of the two travels
 * ([Blend3]'s `Ledge`), turning through the corner's exterior angle until its section stands in the other
 * band's own end plane; between the two unlike sections stands a piece of that plane, exact, and the body
 * has a **ledge** there. Nothing about it is fitted: a plane through a horn torus' own axis cuts it in a
 * circle, and a cone in a straight line.
 */
class BlendIncongruentCornerTest {
    private val L = LBlock()

    /** The two top edges that meet at the plan's own inside corner, with the concave upright between them. */
    private val alongY = 13

    private val alongX = 14

    private fun bodyOf(
        entries: List<Rounding>,
        route: Route,
    ): Solid3 {
        val (stages, why) = L.run(entries, route)
        return Evaluator().solid(assertNotNull(stages, "$entries by $route: $why").last())
    }

    private fun volumeOf(
        entries: List<Rounding>,
        route: Route,
    ): Double {
        val mesh = bodyOf(entries, route).mesh
        assertManifold(mesh, "$entries by $route")
        return Geom3.volume(mesh)
    }

    private fun facesOf(solid: Solid3) = assertNotNull(Section3.faces(solid.feature).first, "it names its faces")

    private fun edgesOf(solid: Solid3) = assertNotNull(Section3.edges(solid.feature).first, "it names its edges")

    /** The corner patches of a dressed body, in the order the corner itself put them there. */
    private fun cornerFaces(solid: Solid3) = facesOf(solid).filter { it.name is FaceName.BlendCorner }

    /** The area a closed outline encloses, by the shoelace over its own tessellation. */
    private fun areaOf(outline: List<ProfileElement>): Double {
        val pts = outline.flatMap { GeomMath.tessellatePiece(it) }
        var a = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2.0
    }

    // ---- 1. one body, whichever way the gestures arrive ----

    /**
     * **Both orders, one dressing and a stack: one body.** Which gesture arrived last may not decide what a
     * body is (OP-30), and here it cannot: the corner is one function of the pair, the deeper section is the
     * one that travels whichever of the two was drawn first, and both bands run their whole edges either way.
     */
    @Test
    fun bothOrdersAndBothRoutesAreOneBody() {
        val deepFirst = listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 3.0))
        val shallowFirst = deepFirst.reversed()
        val vs =
            listOf(
                volumeOf(deepFirst, Route.ONE_PASS),
                volumeOf(deepFirst, Route.STACKED),
                volumeOf(shallowFirst, Route.ONE_PASS),
                volumeOf(shallowFirst, Route.STACKED),
            )
        for (v in vs) assertClose(v, vs[0], 1e-6, "every route builds one body: $vs")
        // …and it is the corner's own body and not the naive one: the deeper section's pivot, by Pappus
        val naive = assertNotNull(naive(L.block, deepFirst), "the naive figure")
        val take = Figures.pivotTakes(4.0, BlendKind.FILLET, PI / 2.0, 0.0)
        assertClose(take, 4.818969, 1e-5, "the pivot the 4 mm ball sweeps about the sharp upright")
        assertTrue(vs[0] < naive.lo, "the corner takes material the naive figure keeps: ${vs[0]} against $naive")
        val bracket = assertNotNull(predict(L.block, deepFirst), "the algebra brackets the cell")
        assertTrue(vs[0] in bracket, "…and the body is inside the bracket: ${vs[0]} not in $bracket")
        assertClose(vs[0], 40357.224160, 1e-4, "…and it has not drifted")
    }

    /**
     * **A fillet beside a bevel of its own size: the bevel travels.** The two have the same setback, so
     * neither is *larger* — but a bevel's chord stands further from the corner than the round's arc at every
     * depth, so the bevel's wedge contains the round's and it is the bevel's ball that sweeps the corner.
     * The corner's surface says so by name: a **cone**, not a torus.
     */
    @Test
    fun theDeeperSectionTravelsAndTheCornerSaysWhichItWas() {
        val round = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 3.0)), Route.ONE_PASS)
        val torus =
            assertNotNull(cornerFaces(round).mapNotNull { it.surface?.band as? Revolve3.Band.Torus }.firstOrNull(), "a torus turns the corner")
        assertClose(torus.minor, 4.0, 1e-9, "and its tube is the **deeper** of the two roundings")
        assertClose(torus.rc, 4.0, 1e-9, "…on the horn torus' own centre circle, the upright being sharp")

        val mixed = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.CHAMFER, 4.0)), Route.ONE_PASS)
        assertNotNull(cornerFaces(mixed).mapNotNull { it.surface?.band as? Revolve3.Band.Cone }.firstOrNull(), "a cone turns it where the bevel is deeper")
        val v = Geom3.volume(mixed.mesh)
        assertManifold(mixed.mesh, "the fillet and the bevel")
        val take = Figures.pivotTakes(4.0, BlendKind.CHAMFER, PI / 2.0, 0.0)
        assertClose(take, 16.75516, 1e-4, "the cone the bevel's own section sweeps")
        val bracket = assertNotNull(predict(L.block, listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.CHAMFER, 4.0))), "bracketed")
        assertTrue(v in bracket, "$v not in $bracket")
    }

    // ---- 2. the ledge is a face, and it is exact ----

    /**
     * **The ledge is a face of the body with a closed, exact outline** — the piece of the shallower band's
     * own end plane the deeper section's arrival leaves standing.
     *
     * Its area is the two wedges' difference to the last digit, which is the whole of what says the walk
     * lands where it should: too short a turn or too deep a section and the crescent is a different size.
     */
    @Test
    fun theLedgeIsAFaceWithAClosedExactOutline() {
        for (
        (second, area) in
        listOf(
            Rounding(alongX, BlendKind.FILLET, 3.0) to
                Figures.wedgeAreaByChords(4.0, BlendKind.FILLET) - Figures.wedgeAreaByChords(3.0, BlendKind.FILLET),
            Rounding(alongX, BlendKind.CHAMFER, 4.0) to
                Figures.wedgeAreaByChords(4.0, BlendKind.CHAMFER) - Figures.wedgeAreaByChords(4.0, BlendKind.FILLET),
        )
        ) {
            val solid = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), second), Route.ONE_PASS)
            val ledge = assertNotNull(cornerFaces(solid).lastOrNull(), "the corner's last patch is the ledge")
            assertNotNull(ledge.plane, "${ledge.name.label.render()} is a plane")
            assertEquals(null, ledge.reason, "…with nothing to refuse")
            assertEquals(null, ledge.fitted, "…and nothing fitted about it")
            assertTrue(ledge.outline.size >= 2, "…and an outline of its own")
            val ends = ledge.outline.map { GeomMath.startOf(it) to GeomMath.endOf(it) }
            for (k in ends.indices) {
                assertClose((ends[k].second - ends[(k + 1) % ends.size].first).length(), 0.0, 1e-9, "the outline closes, piece $k")
            }
            assertTrue(ledge.outline.none { it is ProfileElement.BezierE }, "…and no piece of it is fitted")
            assertClose(areaOf(ledge.outline), area, 1e-9, "the ledge is the two wedges' difference")
        }
    }

    /**
     * **The edge list states the corner's own curves**: the rail the ball's tangency draws on the shared
     * face, and the two rings the ledge is bounded by — the walk's arrival, and the shallower band's own end
     * section. Every one of them is exact, and every one of them lies on the two faces it separates.
     */
    @Test
    fun theEdgeListStatesTheCornersRailAndItsTwoRings() {
        val solid = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 3.0)), Route.ONE_PASS)
        val edges = edgesOf(solid)
        val rail = edges.filter { it.name is EdgeName.BlendCornerRail }
        assertEquals(1, rail.size, "one rail, the walk being one leg")
        val rings = edges.filter { it.name is EdgeName.BlendMitre }
        assertEquals(2, rings.size, "and the ledge's two rings: ${rings.map { it.name.label.render() }}")
        for (e in rings) assertEquals(null, e.fitted, "${e.name.label.render()} is exact")
        // the two rings are the two sections' own arcs, so their ends stand at the tangencies: 4 mm and 3 mm
        // out along the shared face from the corner, and the same depths below it
        val corner = Vec3(-5.521648428788623, 0.375, 20.0)
        for ((e, r) in rings.zip(listOf(4.0, 3.0))) {
            val pts = assertNotNull(constructit.geom.Blend3.edgePath(e).first, "${e.name.label.render()} has a curve").elements
            val ends = listOf(pts.first().start, pts.last().end)
            assertClose(ends.maxOf { abs(it.z - 20.0) }, r, 1e-9, "${e.name.label.render()} reaches $r mm down")
            assertClose(ends.maxOf { (it - corner).length() }, r, 1e-9, "…and $r mm out from the corner")
        }
    }

    // ---- 3. what the drawing says about it ----

    /**
     * **A level section through the corner closes** — at the ledge's own height, above it and below it. The
     * ledge is a face like any other and the walk's rail carries the shared face's boundary round the corner,
     * so nothing of the loop is missing at any depth.
     */
    @Test
    fun theLevelSectionThroughTheCornerCloses() {
        val solid = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 3.0)), Route.ONE_PASS)
        for (z in listOf(19.9, 18.5, 17.5, 10.0)) {
            val plane = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val (regions, why) = Section3.regionsOf(solid.feature, plane)
            assertNotNull(regions, "the level at $z closes: ${why?.render()}")
            assertEquals(1, regions.size, "…into one area at $z")
        }
    }

    /**
     * **A face space opens on the ledge, and on a bevel's band beside it** — the ledge is a plane with an
     * exact outline, which is the whole of what [Section3.facePatchOfFootprintPiece] asks of a face.
     */
    @Test
    fun aFaceSpaceOpensOnTheLedgeAndOnTheBevelBand() {
        val solid = bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.CHAMFER, 4.0)), Route.ONE_PASS)
        var opened = 0
        for (f in facesOf(solid)) {
            if (f.name !is FaceName.BlendBand && f.name !is FaceName.BlendCorner) continue
            if (f.plane == null || f.reason != null) continue
            val at = assertNotNull(Section3.addressOfFace(solid.feature, f.name), "${f.name.label.render()} has an address")
            val (patch, why) = Section3.facePatchOfFootprintPiece(solid.feature, at)
            assertNotNull(patch, "a space opens on ${f.name.label.render()}: ${why?.render()}")
            assertTrue(patch.outline.isNotEmpty(), "…with its own outline as the section input")
            opened++
        }
        assertTrue(opened >= 2, "the bevel's band and the ledge both take a space, not $opened")
    }

    // ---- 4. the file, the undo, and the body that may not move ----

    /**
     * **The drawing round-trips byte-equal**, corner and all: the corner is derived from the pair and the
     * file stores neither it nor anything about it.
     */
    @Test
    fun theFileRoundTripsByteEqual() {
        val once = DocumentFormat.save(DocumentFormat.load(script(4.0, BlendKind.FILLET, 3.0, BlendKind.FILLET)))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole drawing round-trips byte-equal")
    }

    /**
     * **The second rounding comes off and comes back, exactly.** The corner is a fact about the *pair*, so
     * taking one of the two entries away takes the whole corner with it — and one undo puts the pair, its
     * ledge and its walk back bit for bit, not to a tolerance.
     */
    @Test
    fun theSecondRoundingComesOffAndOneUndoBringsItBack() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script(4.0, BlendKind.FILLET, 3.0, BlendKind.FILLET)))
        val entries = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }
        assertEquals(2, entries.size, "two entries of one dressing")
        val pair = volumeOf(bodyRef(ed.doc), "the pair with its corner")
        assertClose(pair, 40357.224160, 1e-4, "the pair is the corner's own body")

        ed.selectElement(entries.last())
        assertTrue(ed.deleteSelection(), "the shallower rounding comes off: ${ed.statusHint}")
        val alone = volumeOf(bodyRef(ed.doc), "the deeper band alone")
        assertTrue(alone > pair + 100.0, "…and its band's worth of material with it: $alone against $pair")
        // and with it the corner: one band alone states no corner patch at all
        assertEquals(
            0,
            facesOf(Evaluator().solid(bodyRef(ed.doc))).count { it.name is FaceName.BlendCorner },
            "a lone band has no corner",
        )

        assertTrue(ed.undo(), "the removal is one undo step")
        assertEquals(pair, volumeOf(bodyRef(ed.doc), "the pair, restored"), "one undo gives the pair back to the last bit")
    }

    /**
     * **The congruent pair is unchanged to the last bit** (session 80's own body). The ledge is reached only
     * where the two sections land on no common ring, so a pair that does is the corner it always was — and
     * that is asserted as an exact equality rather than a tolerance.
     */
    @Test
    fun theCongruentPairIsUnchangedToTheLastBit() {
        val v = volumeOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 4.0)), Route.ONE_PASS)
        assertEquals(40254.536658410776, v, "session 80's own corner, bit for bit")
        assertEquals(
            1,
            cornerFaces(bodyOf(listOf(Rounding(alongY, BlendKind.FILLET, 4.0), Rounding(alongX, BlendKind.FILLET, 4.0)), Route.ONE_PASS)).size,
            "and it states one corner patch, not two: a congruent pair has no ledge",
        )
    }

    // ---- 5. the convex crossing: the same pair, and the crease the boolean's trim leaves ----

    /**
     * **Where two incongruent bands *cross*, the crease between them is named and says it is fitted.**
     *
     * At a **convex** corner an incongruent pair costs nothing — the two tools overlap and the boolean trims
     * them exactly, which is session 79's cut (2) and stays. What the drawing did not say is what the body
     * *has* there. Two cylinders of unlike radius whose axes are skew meet in a **quartic**, in no plane and
     * in none of this drawing's closed forms, so it is stated as a chain of cubics through points that are
     * every one of them exact on both cylinders — and every point between them is inside the tolerance the
     * edge itself carries, which this measures against the two exact surfaces rather than against the fit.
     */
    @Test
    fun theCreaseWhereTwoUnlikeBandsCrossIsNamedAndFitted() {
        val solid = bodyOf(listOf(Rounding(12, BlendKind.FILLET, 4.0), Rounding(alongY, BlendKind.FILLET, 3.0)), Route.ONE_PASS)
        val crease =
            assertNotNull(edgesOf(solid).firstOrNull { it.name is EdgeName.BlendMitre }, "the crossing states its crease")
        val tol = assertNotNull(crease.fitted, "and says it is fitted")
        assertTrue(tol <= Combine3.FIT_TOL_MM, "to the drawing's own fit tolerance or better: $tol")
        assertTrue("fitted to within" in Section3.words(crease).render(), "…and says so in words: ${Section3.words(crease).render()}")
        assertTrue("0 mm" !in Section3.words(crease).render(), "…with the number it means: ${Section3.words(crease).render()}")
        val chain = assertIs<EdgeGeom.InSpace>(crease.geom, "two skew cylinders meet in no plane")

        // the two exact cylinders: 4 mm about the axis under edge #13, 3 mm about the one under edge #14
        val axisA = Vec3(0.0, -28.375, 16.0) to Vec3(1.0, 0.0, 0.0)
        val axisB = Vec3(-8.521648428788623, 0.0, 17.0) to Vec3(0.0, 1.0, 0.0)

        fun off(
            p: Vec3,
            axis: Pair<Vec3, Vec3>,
            r: Double,
        ): Double {
            val d = p - axis.first
            return abs((d - axis.second * d.dot(axis.second)).length() - r)
        }
        var worst = 0.0
        for (piece in chain.chain) for (p in Curves3.sample(piece)) worst = max(worst, max(off(p, axisA, 4.0), off(p, axisB, 3.0)))
        assertTrue(worst <= tol, "every point of the chain is within $tol mm of both cylinders, worst $worst")
        // …and its ends are exact: the two rails meeting on the shared face, and the deeper band's own depth
        assertClose(
            (chain.chain.first().start - Vec3(-8.521648428788623, -28.375, 20.0)).length(),
            0.0,
            1e-6,
            "the crease begins where the two rails cross on the top face",
        )
    }

    /**
     * **Two bevels crossing meet in a straight line, and it is stated as one** — nothing is fitted that this
     * drawing can say exactly, which is the other half of the fitted tier's own rule.
     */
    @Test
    fun theCreaseBetweenTwoBevelsIsExact() {
        val solid = bodyOf(listOf(Rounding(12, BlendKind.CHAMFER, 4.0), Rounding(alongY, BlendKind.CHAMFER, 3.0)), Route.ONE_PASS)
        val crease = assertNotNull(edgesOf(solid).firstOrNull { it.name is EdgeName.BlendMitre }, "the crossing states its crease")
        assertEquals(null, crease.fitted, "two planes meet in a line, and a line is exact")
        val g = assertIs<EdgeGeom.Straight>(crease.geom, "so it is a straight run")
        // it lies in both bevel planes: 45° off the top face over each of the two edges
        for (
        (base, n) in
        listOf(
            Vec3(0.0, -28.375, 20.0) to Vec3(0.0, 1.0, -1.0).normalized(),
            Vec3(-8.521648428788623, 0.0, 20.0) to Vec3(1.0, 0.0, 1.0).normalized(),
        )
        ) {
            for (p in listOf(g.a, g.b, (g.a + g.b) * 0.5)) {
                assertClose((p - base).dot(n), 0.0, 1e-6, "the crease lies in the bevel plane through $base")
            }
        }
    }

    /**
     * **And the planar band's own outline follows that crease**, fitted and closed: session 81 gave the band
     * its extent, and what the extent ends on is the very curve the edge list now names.
     */
    @Test
    fun theBevelBandsOutlineFollowsTheCreaseItEndsOn() {
        val solid = bodyOf(listOf(Rounding(12, BlendKind.FILLET, 4.0), Rounding(alongY, BlendKind.CHAMFER, 3.0)), Route.ONE_PASS)
        val band =
            assertNotNull(
                facesOf(solid).firstOrNull { it.name is FaceName.BlendBand && (it.name as FaceName.BlendBand).edge == alongY },
                "the bevel's own band",
            )
        val plane = assertNotNull(band.plane, "a bevel's band is a plane")
        val tol = assertNotNull(band.fitted, "and its outline says it is fitted where it runs into the fillet")
        assertTrue(tol >= Combine3.FIT_TOL_MM, "…to the tolerance the fit actually reached, which a kink can hold above the one asked for")
        val ends = band.outline.map { GeomMath.startOf(it) to GeomMath.endOf(it) }
        for (k in ends.indices) {
            assertClose((ends[k].second - ends[(k + 1) % ends.size].first).length(), 0.0, 1e-9, "the outline closes, piece $k")
        }
        // every fitted piece of it **at the corner end** stands on the fillet's own cylinder, which is what
        // trims it there; the chain at the other end is the band's own free end and is nowhere near it
        val corner = Vec3(-5.521648428788623, -32.375, 20.0)
        val axis = Vec3(0.0, -28.375, 16.0) to Vec3(1.0, 0.0, 0.0)
        var worst = 0.0
        var seen = 0
        for (e in band.outline) {
            if (e !is ProfileElement.BezierE) continue
            for (q in GeomMath.tessellatePiece(e)) {
                val p = plane.toWorld(q)
                if ((p - corner).length() > 10.0) continue
                val d = p - axis.first
                worst = max(worst, abs((d - axis.second * d.dot(axis.second)).length() - 4.0))
                seen++
            }
        }
        assertTrue(seen > 0, "the outline carries the fitted chain")
        assertTrue(worst <= tol, "and every point of it is on the fillet's cylinder inside the tolerance it states: $worst against $tol")
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun bodyRef(doc: constructit.editor.Document): SolidRef =
        (doc.elements.last { it.kind == ElementKind.SOLID } as Element).ref as SolidRef

    /** The L-block and one or two roundings, written as a **script** — the file the drawing is. */
    private fun script(
        first: Double,
        firstKind: BlendKind,
        second: Double?,
        secondKind: BlendKind?,
    ): String {
        val plan = L.plan
        val sb = StringBuilder("constructit 6\n")
        sb.append("orthostart ${plan[0].x},${plan[0].y} -> e1\n")
        var n = 2
        for (i in 1 until plan.size) {
            sb.append("orthovertex ${plan[i].x},${plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = 20mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-48.125,37.875 scalar=\"h\" -> e$n\n")
        var next = n + 1
        val tool = { k: BlendKind -> if (k == BlendKind.CHAMFER) "chamferedge" else "filletedge" }
        sb.append("param \"r0\" = ${first}mm\n")
        sb.append("tool ${tool(firstKind)} els=e$n clicks=0,0 scalar=\"r0\" signs=$alongY;-1;1;0;1 -> e$next,e${next + 1}\n")
        val body = "e$next"
        next += 2
        if (second != null && secondKind != null) {
            sb.append("param \"r1\" = ${second}mm\n")
            sb.append("tool ${tool(secondKind)} els=$body clicks=0,0 scalar=\"r1\" signs=$alongX;-1;1;0;1 -> e$next\n")
        }
        return sb.toString()
    }
}
