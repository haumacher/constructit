package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Curve3Element
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Edges are chains, and the mitre crease is named** (OP-31, item 3; GitHub #36's script 2).
 *
 * > *"You can fillet a chamfer edge, but not an edge created by the joining of two chamfers."*
 *
 * The reporter's second script builds the right *body* and the wrong *edge list*, and this is the list's own
 * test. Three claims, each asserted on the drawing rather than on a volume, because a volume cannot tell them
 * apart (a band over 28.75 mm and one over 32.75 mm both fall inside the chord margin the matrix allows):
 *
 * 1. **a rail is stated over its crease's own run** — from where the band starts to where a corner takes it
 *    over — and no stated crease of the body begins inside material;
 * 2. **the corner's own curves are edges**: the mitre where two bands cross, exactly (a straight segment
 *    between two bevels, an ellipse arc between two rounds), and the rail that carries a band's tangency
 *    round a pivot, leg by leg;
 * 3. **a rail, the corner curve beside it and the next rail are one chain**, so one pick rounds the whole
 *    ribbon exactly as one pick has rounded a tangent-continuous rim since GitHub #29.
 *
 * And the honesty line the tier moved: a rounding **along** an elliptical mitre is refused by name, because
 * its section would change from one end of the arc to the other.
 */
class BlendChainEdgeTest {
    private val L = LBlock()

    /** GitHub #36's script 2, verbatim — the required fixture. */
    private val script2 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 4mm
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
param "r2" = 4mm
tool filletedge els=e14 clicks=-33.91557367038956,-1.7134784580017737 scalar="r2" signs=20;-1;1;0;1 -> e18,e19
""".trimStart()

    private fun bodyOf(script: String): Solid3 {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script))
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        return Evaluator().solid(el.ref as SolidRef)
    }

    /** The three bevels of script 2, built through the DSL — the body the file's last step stands on. */
    private fun threeBevels(size: Double = 4.0): Body {
        val (stages, why) = L.run(listOf(2, 13, 14).map { Rounding(it, BlendKind.CHAMFER, size) }, Route.ONE_PASS)
        return Body(Evaluator().solid(assertNotNull(stages, why).last()))
    }

    /** How long edge [i] runs, whichever curve it is. */
    private fun runLength(
        b: Body,
        i: Int,
    ): Double {
        val path = Blend3.edgePath(b.edges[i]).first ?: return 0.0
        return path.elements.sumOf { e ->
            when (e) {
                is Curve3Element.Seg3 -> (e.end - e.start).length()
                is Curve3Element.Arc3 -> e.radius * abs(e.sweepAngle)
                else -> 0.0
            }
        }
    }

    /** Whether [p] has material on every side of it — inside the solid and on no face of it. */
    private fun deepInside(
        b: Body,
        p: Vec3,
    ): Boolean {
        val e = 0.05
        return listOf(Vec3(e, 0.0, 0.0), Vec3(-e, 0.0, 0.0), Vec3(0.0, e, 0.0), Vec3(0.0, -e, 0.0), Vec3(0.0, 0.0, e), Vec3(0.0, 0.0, -e))
            .all { Geom3.encloses(b.mesh, p + it) }
    }

    private fun ends(
        b: Body,
        i: Int,
    ): Pair<Vec3, Vec3>? {
        val path = Blend3.edgePath(b.edges[i]).first ?: return null
        val s = path.start ?: return null
        val e = path.end ?: return null
        return s to e
    }

    // ---- 1. the rail runs the crease ----

    /**
     * **Every rail of script 2's three bevels runs its own crease, and nothing is buried.**
     *
     * Edge 13 is 32.75 mm long and its bevel's crease runs 28.75 of them: the pivot at the plan's inside
     * corner takes the last 4, which is the bevel's own setback. Before this session both rails were stated
     * at the whole 32.75, and the two that lie on a **side** face ran their last 4 mm through the material
     * the fill put there — a stated crease beginning inside the solid, which is the structural form of the
     * defect and the one a volume cannot see.
     */
    @Test
    fun everyRailRunsItsCreaseAndNoneOfThemIsBuried() {
        val b = threeBevels()
        val c = 4.0
        for (edge in listOf(13, 14)) {
            val rails = b.edges.indices.filter { b.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == edge } }
            assertEquals(2, rails.size, "edge $edge's band has two rails")
            for (r in rails) {
                assertClose(runLength(b, r), L.block.length(edge) - c, 1e-9, "rail $r runs edge $edge's crease, not edge $edge")
            }
        }
        // the concave upright's own band is ended by the corner too, at z = 20 − c
        for (r in b.edges.indices.filter { b.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == 2 } }) {
            assertClose(runLength(b, r), L.height - c, 1e-9, "the upright's rail $r stops where the corner takes over")
        }
        for (i in b.edges.indices) {
            if (b.edges[i].reason != null) continue
            val (s, e) = ends(b, i) ?: continue
            val d = (e - s).length()
            if (d <= 1e-9) continue
            val u = (e - s) * (1.0 / d)
            assertTrue(!deepInside(b, s + u * 0.1), "edge $i (${b.edges[i].name}) begins inside the material")
            assertTrue(!deepInside(b, e - u * 0.1), "edge $i (${b.edges[i].name}) ends inside the material")
        }
        assertManifold(b.mesh, "the three bevels")
    }

    // ---- 2. the corner's own curves ----

    /**
     * **The mitre between two bevels is a straight crease, and it is where the two rails cross.**
     *
     * Two equal bevels on the L's top edges 12 and 13 meet at the plan's convex corner. Their removal splits
     * on the plane equidistant from the two edges (session 79), so the ring both tubes end on is the section
     * carried through that placement — a **segment**, since a bevel's section is one. Its two ends are the
     * crossings of the two bands' rails: the pair on the top face, and the pair on the two side faces.
     */
    @Test
    fun theMitreBetweenTwoBevelsIsTheStraightCreaseTheTwoRailsCrossAt() {
        val (stages, why) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.CHAMFER, 4.0) }, Route.ONE_PASS)
        val b = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        assertManifold(b.mesh, "two bevels crossing")
        val mitres = b.edges.indices.filter { b.edges[it].name is EdgeName.BlendMitre }
        assertEquals(1, mitres.size, "one crossing, one mitre: ${b.edges.map { it.name }}")
        val m = b.edges[mitres.first()]
        assertEquals(
            setOf(FaceName.BlendBand(12, 0), FaceName.BlendBand(13, 0)),
            setOf(m.between.a, m.between.b),
            "the mitre is between the two bands",
        )
        assertTrue(m.reason == null, "a bevel's mitre is a crease of the body: ${m.reason?.render()}")
        val piece = (m.geom as EdgeGeom.OnPlane).piece
        assertTrue(piece is ProfileElement.Seg, "two bevels meet in a straight segment, not a $piece")
        // its two ends: where the two top-face rails cross, and where the two side-face rails cross
        val (s, e) = assertNotNull(ends(b, mitres.first()), "the mitre has two ends")
        val top = listOf(s, e).first { abs(it.z - L.height) < 1e-9 }
        val low = listOf(s, e).first { abs(it.z - L.height) >= 1e-9 }
        assertClose(top.x, -9.521648428788623, 1e-9, "the top end stands where edge 13's top rail runs")
        assertClose(top.y, -28.375, 1e-9, "…and where edge 12's top rail runs")
        assertClose(low.z, L.height - 4.0, 1e-9, "the low end stands where both side rails run")
        assertClose(low.x, -5.521648428788623, 1e-9, "…on the upright the two side faces share")
        assertClose(low.y, -32.375, 1e-9, "…on the upright the two side faces share")
    }

    /**
     * **The mitre between two rounds is an ellipse arc, exactly** — two equal cylinders whose axes meet cut
     * in a plane ellipse (OP-24's own vocabulary), which is session 79's own prediction made a value: *"the
     * ellipse arc in the mitre plane, the wedge's own blend curve stretched by 1/sin(θ/2)"*.
     *
     * And a rounding carried **along** it is refused by name, with what does work: its section would change
     * from one end of the arc to the other, which this drawing states for no edge (OP-31, Tier B, item 5).
     */
    @Test
    fun theMitreBetweenTwoRoundsIsAnEllipseArcAndARoundingAlongItIsRefusedByName() {
        val (stages, why) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.FILLET, 4.0) }, Route.ONE_PASS)
        val b = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        assertManifold(b.mesh, "two rounds crossing")
        val at = b.edges.indices.first { b.edges[it].name is EdgeName.BlendMitre }
        val piece = (b.edges[at].geom as EdgeGeom.OnPlane).piece
        assertTrue(piece is ProfileElement.EllipticArcE, "two equal cylinders whose axes meet cut in an ellipse, not a $piece")
        val el = (piece as ProfileElement.EllipticArcE).arc.ellipse
        // the two edges stand at a right angle, so the section is stretched by 1/sin(θ/2) = √2 one way
        assertClose(el.minor, 4.0, 1e-9, "the tube's own radius across the mitre")
        assertClose(el.major, 4.0 * kotlin.math.sqrt(2.0), 1e-9, "…and the section stretched by 1/sin(θ/2) along it")

        val (choices, whyChoice) = Blend3.choicesFor(b.solid, listOf(at), BlendSection(BlendKind.FILLET, 1.0))
        val reason =
            if (choices == null) {
                assertNotNull(whyChoice, "a refusal has a reason").render()
            } else {
                val (out, whyOut) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.FILLET, 4.0) } + Rounding(at, BlendKind.FILLET, 1.0), Route.STACKED)
                assertTrue(out == null, "a rounding along an elliptical mitre may not build silently")
                assertNotNull(whyOut, "a refusal has a reason")
            }
        assertTrue("ellipse" in reason, "the refusal names the curve: '$reason'")
        assertTrue("mitre" in reason, "…and says it is a mitre: '$reason'")
        assertTrue("chamfer" in reason, "…and says what does work: '$reason'")
    }

    /**
     * **A pivot's rails are edges too, and they are exact**: a leg that turns carries the travelling
     * section's tangency round the pivot on a **circle**, a leg that slides carries it along a straight run.
     * A bevelled upright walks turn–slide–turn, so the corner between three bevels puts three of them on the
     * body — and each one runs from where the band's own rail stops.
     */
    @Test
    fun aPivotsRailsAreExactAndTheyStartWhereTheBandsRailStops() {
        val b = threeBevels()
        val rails = b.edges.indices.filter { b.edges[it].name is EdgeName.BlendCornerRail }
        assertEquals(3, rails.size, "turn, slide, turn")
        val kinds =
            rails.map { i ->
                when (val g = b.edges[i].geom) {
                    is EdgeGeom.Straight -> "straight"
                    is EdgeGeom.OnPlane -> if (g.piece is ProfileElement.ArcE) "arc" else g.piece::class.simpleName!!
                }
            }
        assertEquals(listOf("arc", "straight", "arc"), kinds, "a bevelled upright is a turn, a slide and a turn")
        for (i in rails) {
            assertTrue(b.edges[i].reason == null, "a bevel's corner rail is a crease: ${b.edges[i].reason?.render()}")
            assertTrue(b.edges[i].between.has(FaceName.Cap(constructit.geom.SolidFace.TOP)), "it lies in the face the walk runs in: ${b.edges[i].between}")
        }
        // the first leg starts exactly where edge 13's top rail stops
        val top13 = b.edges.indices.first { b.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == 13 } && b.edges[it].between.has(FaceName.Cap(constructit.geom.SolidFace.TOP)) }
        val stop = assertNotNull(ends(b, top13), "the rail has two ends").second
        val starts = rails.map { assertNotNull(ends(b, it), "a leg has two ends") }
        assertTrue(
            starts.any { (s, e) -> (s - stop).length() <= 1e-6 || (e - stop).length() <= 1e-6 },
            "a leg of the corner rail begins where the band's rail stops: $stop vs $starts",
        )
    }

    /**
     * **A round's corner curves are stated and marked as no crease.** A ball rolling off a straight run onto
     * a pivot is tangent to its own band along the whole hand-over, so there is nothing there to round — and
     * saying so by name beats leaving the curve out of a list that claims to state what the body has.
     */
    @Test
    fun aRoundsCornerCurvesAreThereAndSayTheyAreHandOversRatherThanCreases() {
        val (stages, why) = L.run(listOf(2, 13, 14).map { Rounding(it, BlendKind.FILLET, 4.0) }, Route.ONE_PASS)
        val b = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        assertManifold(b.mesh, "three rounds at the mixed vertex")
        val rails = b.edges.indices.filter { b.edges[it].name is EdgeName.BlendCornerRail }
        assertTrue(rails.isNotEmpty(), "a round's pivot has its rails too: ${b.edges.map { it.name }}")
        for (i in rails) {
            val reason = assertNotNull(b.edges[i].reason, "corner rail $i says it is no crease").render()
            assertTrue("tangent" in reason, "…and says why: '$reason'")
        }
    }

    // ---- 3. the chain ----

    /**
     * **Script 2, verbatim: the fillet runs the whole ribbon.**
     *
     * The reporter's last step addresses rail 20 — the top-face rail of edge 13's bevel. That rail runs
     * 28.75 mm, then the pivot's own three legs carry it round, then edge 14's rail carries it on: one
     * tangent-continuous chain, and one pick takes all of it. So the figure is the band's own wedge at the
     * rail's 135° dihedral swept along the chain's total run — a constant section along a smooth path, with
     * no corner term to add — and the file is a fixed point of save.
     */
    @Test
    fun theReportersSecondScriptRoundsTheWholeRibbon() {
        val body = bodyOf(script2)
        assertManifold(body.mesh, "script 2")
        val v = Geom3.volume(body.mesh)

        val dressed = threeBevels()
        val rail =
            dressed.edges.indices.first {
                dressed.edges[it].name.let { n -> n is EdgeName.BlendRail && n.edge == 13 } &&
                    dressed.edges[it].between.has(FaceName.Cap(constructit.geom.SolidFace.TOP))
            }
        assertEquals(20, rail, "the reporter's own address")
        val run = assertNotNull(Blend3.targets(dressed.solid.feature, false, rail, Blend3.chainRun()).first, "the chain through rail $rail")
        assertEquals(5, run.size, "rail, three legs of the pivot, rail: $run")
        val total = run.sumOf { runLength(dressed, it) }
        val band = Figures.wedgeArea(4.0, BlendKind.FILLET, 3.0 * PI / 4.0) * total
        val surplus = Figures.chordSurplus(4.0, total)
        val noise = 1e-5 * dressed.volume
        val bracket = Bracket(dressed.volume - band - surplus - noise, dressed.volume - band + noise)
        assertTrue(v in bracket, "script 2 built $v, outside the band over the ribbon's $total mm: $bracket")

        val once = DocumentFormat.save(DocumentFormat.load(script2))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file round-trips byte-equal")
    }

    /**
     * **The stored address is not silently re-read.** The reporter's file says `constructit 6`, and under
     * that version its last step rounded one straight rail; it now rounds the whole ribbon, which is a
     * different body from the same literal. So the load **says so**, once, and the version this build
     * writes has risen ([DocumentFormat.CHAINED_RAIL_VERSION], OP-18).
     */
    @Test
    fun anOlderFileIsToldThatItsRoundingNowRunsTheWholeChain() {
        val doc = DocumentFormat.load(script2)
        assertTrue(doc.loadNotes.isNotEmpty(), "the load says what changed")
        assertTrue(doc.loadNotes.any { "runs along" in it }, "…in its own words: ${doc.loadNotes}")
        val once = DocumentFormat.save(doc)
        assertTrue(once.startsWith("constructit ${DocumentFormat.VERSION}"), once.lines().first())
        assertEquals(7, DocumentFormat.CHAINED_RAIL_VERSION, "the version that says a rail is a chain")
        // …and having been saved at the new version, it is read without a note
        assertTrue(DocumentFormat.load(once).loadNotes.isEmpty(), "the migrated file needs no note: ${DocumentFormat.load(once).loadNotes}")
    }

    /**
     * **A fillet along the mitre between two bevels builds, with the wedge figure at the mitre's measured
     * dihedral** — the reporter's own ask, *"an edge created by the joining of two chamfers"*.
     */
    @Test
    fun aFilletOnTheMitreBetweenTwoBevelsBuildsAtTheMeasuredWedge() {
        val (stages, why) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.CHAMFER, 4.0) }, Route.ONE_PASS)
        val b = Body(Evaluator().solid(assertNotNull(stages, why).last()))
        val at = b.edges.indices.first { b.edges[it].name is EdgeName.BlendMitre }
        val theta = assertNotNull(b.wedgeAngle(at), "two planes have a dihedral")
        val size = 1.0
        val on = Rounding(at, BlendKind.FILLET, size)
        val (out, whyOut) = L.run(listOf(12, 13).map { Rounding(it, BlendKind.CHAMFER, 4.0) } + on, Route.STACKED)
        val v = measure(assertNotNull(out, whyOut).last(), "a fillet on the mitre between two bevels")
        val len = runLength(b, at)
        val band = Figures.wedgeArea(size, BlendKind.FILLET, theta) * len
        val surplus = Figures.chordSurplus(size, len)
        val noise = 1e-5 * b.volume
        val bracket = Bracket(b.volume - band - surplus - noise, b.volume - band + noise)
        assertTrue(v in bracket, "the mitre fillet built $v, outside $bracket (θ = $theta, L = $len)")
        println("mitre fillet | built | $v | $bracket at θ = $theta over $len mm")
    }

    /**
     * **A body dressed along a chain is a body like any other**: it names its faces and its edges, every one
     * of them, and not one of them says something a programming fault put there.
     *
     * The defect this pins: `Piece.length` read `seg!!`, so the moment a rounding ran along a **chain** —
     * whose pieces are arcs as often as segments — asking that body for its edges threw, and every consumer
     * died with it (a level section came back `Invalid("java.lang.NullPointerException")`, and a face space,
     * a further pick or the panel's readout would have done the same).
     */
    @Test
    fun theChainedBodyStatesItsFacesAndItsEdges() {
        val body = bodyOf(script2)
        val edges = assertNotNull(Section3.edges(body.feature).first, "the chained body names its edges")
        val faces = assertNotNull(Section3.faces(body.feature).first, "…and its faces")
        assertTrue(edges.size > L.block.count, "the base's edges and the dressing's: ${edges.size}")
        for (e in edges) {
            assertTrue(e.name.label.render().isNotBlank(), "every edge is named")
            val why = e.reason?.render() ?: ""
            assertTrue("Exception" !in why && "kotlin." !in why && "java." !in why, "edge ${e.name} carries a fault, not a reason: '$why'")
        }
        for (f in faces) {
            assertTrue(f.name.label.render().isNotBlank(), "every face is named")
            val why = f.reason?.render() ?: ""
            assertTrue("Exception" !in why && "kotlin." !in why && "java." !in why, "face ${f.name} carries a fault, not a reason: '$why'")
        }
    }

    /**
     * **A level section through the chained body is stated at every height, and closes wherever its own base
     * closes.** The heights that do not close are a standing gap of the **pivot** and not of the chain: the
     * plain three-bevel body — the base of this fixture, and untouched by this package — refuses by name at
     * exactly the same heights, because a band's own face outline is still the full sweep (session 79's cut
     * (5)) and the upright's band is therefore drawn over the 4 mm the corner took from it. Asserted as the
     * two claims that are this package's: never a thrown fault, and never worse than the base.
     */
    @Test
    fun aLevelSectionThroughTheChainedBodyIsStatedAndCloses() {
        val body = bodyOf(script2)
        val base = threeBevels().solid
        for (z in listOf(19.0, 19.5, 17.0, 15.0, 10.0, 5.0)) {
            val cut = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val (regions, why) = Section3.regionsOf(body.feature, cut)
            val reason = why?.render() ?: ""
            assertTrue("Exception" !in reason && "kotlin." !in reason, "the section at z = $z is stated rather than thrown: '$reason'")
            // **and it closes below the ribbon it rounds.** Above that it refuses **by name**, and the
            // reason is a class item 3b names rather than a fault: a rounding whose target is a *corner
            // curve* takes a strip off the corner's own faces, and a strip on a **cone** is a surface
            // offset this vocabulary has no word for (`correctedOutline`'s own honesty line) — so the
            // corner keeps its whole turn and the loop meets a piece the body no longer has. Nothing is
            // drawn wrongly: the section says so instead of closing on a stale curve.
            if (z <= 17.0) {
                assertNotNull(regions, "the section at z = $z closes below the ribbon: '$reason'")
                assertTrue(regions.isNotEmpty(), "…into at least one area at z = $z")
            } else {
                assertTrue(regions != null || reason.isNotBlank(), "the section at z = $z is refused by name")
            }
        }
    }

    /**
     * **A second rounding on the chained body builds** — the matrix's rail class one level down, which is
     * what says a dressing on a dressing on a dressing is nothing special.
     */
    @Test
    fun aSecondRoundingOnTheChainedBodyBuilds() {
        val body = bodyOf(script2)
        val edges = assertNotNull(Section3.edges(body.feature).first, "the chained body names its edges")
        // an upright of the block no rounding of this dressing touches, still a crease and still a straight run
        val at = edges.indices.first { edges[it].name == EdgeName.Upright(4) }
        assertTrue(edges[at].reason == null, "edge $at is still a crease")
        val (choices, why) = Blend3.choicesFor(body, listOf(at), BlendSection(BlendKind.FILLET, 3.0))
        assertNotNull(choices, "a rounding of edge $at is scored: ${why?.render()}")
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(script2))
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val ref =
            ed.doc.cx.blendAll(
                el.ref as SolidRef,
                ed.doc.cx.planeXY(),
                listOf(Construction.BlendRun(BlendKind.FILLET, ed.doc.cx.const(3.0.mm), null, listOf(at), choices)),
            )
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "the second rounding builds: ${(r as? EvalResult.Invalid)?.reason}")
        val v = Evaluator().solid(ref).mesh
        assertManifold(v, "a rounding on the chained body")
        val after = Geom3.volume(v)
        val bracket = assertNotNull(predict(Body(body), listOf(Rounding(at, BlendKind.FILLET, 3.0))), "the band over the upright's own run")
        assertTrue(after in bracket, "it takes the band over the upright's own ${L.height} mm: $after vs $bracket")
    }

    // ---- 4. the fitted carrier ----

    /**
     * **A face whose boundary was fitted says so, and to what** (OP-31, Tier B — *"since roundings are
     * essential for all kinds of objects, an approximation is better than nothing at all"*).
     *
     * The one-ended pivot's cap leaves a flat top on the third face whose boundary is a torus met by a plane
     * parallel to its own axis — a spiric of Perseus, a quartic, and no member of this drawing's vocabulary.
     * It is stated as a fitted cubic chain, and since this session the **value** says it is fitted.
     */
    @Test
    fun theFaceWhoseBoundaryWasFittedSaysSoAndAnExactOneSaysNothing() {
        val (stages, why) = L.run(listOf(Rounding(13, BlendKind.FILLET, 4.0), Rounding(2, BlendKind.FILLET, 4.0)), Route.ONE_PASS)
        val solid = Evaluator().solid(assertNotNull(stages, why).last())
        assertManifold(solid.mesh, "the mixed-sign pair")
        val faces = assertNotNull(Section3.faces(solid.feature).first, "it names its faces")
        val fitted = faces.filter { it.fitted != null }
        assertEquals(1, fitted.size, "exactly one face's boundary is fitted: ${faces.map { it.name.label.render() to it.fitted }}")
        assertClose(fitted.first().fitted!!, constructit.geom.Combine3.FIT_TOL_MM, 1e-15, "…to the tolerance it was fitted to")
        val said = Section3.words(fitted.first()).render()
        assertTrue("fitted" in said, "a reader of the face is told: '$said'")
        assertTrue(constructit.geom.Frames3.mm(constructit.geom.Combine3.FIT_TOL_MM) in said, "…and to what: '$said'")
        for (f in faces.filter { it.fitted == null }) {
            assertEquals(f.name.label.render(), Section3.words(f).render(), "an exact face says nothing extra")
        }
    }
}
