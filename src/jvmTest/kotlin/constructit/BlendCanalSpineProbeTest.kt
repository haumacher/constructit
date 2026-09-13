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
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Surface3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adversarial probe of slice 5m** (OP-31), written after the delivery and never seen by it. The marched
 * spine is a construction, so the body has to be a pure function of its parameters *along a live edit*: the
 * canal's radius typed from 1 to the tight 2.5 in the panel gives the very body a fresh build at 2.5 gives,
 * back to 1 gives the first body back, and undo walks the same two steps. And the spine has to march a
 * crease it was not tuned on — the fitted quartic between two rounds of **unlike** size — at the tight size
 * too: built inside its own figure, or refused in a sentence of the drawing's own.
 */
class BlendCanalSpineProbeTest {
    /**
     * Which part of a rail is its **interior** — the run's middle half. At either end the contact really
     * does run a little past the band's own trim, because that is where the crease itself runs off the wall
     * and the ball rolls on past it; that is (5l)'s recorded ground and not this statement's business.
     */
    private val INTERIOR_FROM = 0.25

    /**
     * How far past its own band a contact may stand in the run's interior, in radians — an **eighth of the
     * quarter turn** a rounding band covers. The largest a build here reaches is 0.053 rad (the ball of
     * radius 2 where the crease runs off the smaller band), and the wrong branch stood **0.54 to 0.69** rad
     * round the same cylinder at every size, so nothing about the reading is marginal.
     */
    private val END_SLACK_RAD = PI / 16.0

    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun prism(cx: Construction): SolidRef {
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val pts = plan.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*plan.indices.map { cx.segment(pts[it], pts[(it + 1) % plan.size]) }.toTypedArray()))), cx.const(height.mm))
    }

    /** The two top edges that share the block's far corner, in edge-list order. */
    private fun topsAtFarCorner(s: Solid3): List<Int> {
        val corner = Vec3(width, depth, height)
        val es = edgesOf(s)
        return es.indices.filter { i ->
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
        }
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: Int,
        size: Double,
    ): Pair<SolidRef?, String?> {
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(address), BlendSection(BlendKind.FILLET, size))
        if (choices == null) return null to (why?.render() ?: "no choice")
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, listOf(address), choices)))
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) return null to r.why.render()
        return ref to null
    }

    private fun mitreOf(s: Solid3): Int? {
        val es = edgesOf(s)
        return es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null }
    }

    /** The block with its two far top edges rounded one at a time at [r0] and [r1], and the mitre between them. */
    private fun twoRounds(
        cx: Construction,
        r0: Double,
        r1: Double,
    ): SolidRef {
        val box = prism(cx)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        assertEquals(2, tops.size, "two top edges share the far corner")
        var on = box
        for ((k, e) in tops.withIndex()) {
            val (next, why) = round(cx, on, e, if (k == 0) r0 else r1)
            on = assertNotNull(next, "top edge $e rounds at ${if (k == 0) r0 else r1}: $why")
        }
        return on
    }

    /** `BlendCanalTest`'s file: the block, the two rounds at [r0] and [r1], the canal along their mitre at [rc]. */
    private fun canalScript(
        rc: Double,
        r0: Double = 4.0,
        r1: Double = 4.0,
    ): String {
        val cx = Construction()
        val box = prism(cx)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        sb.append("orthostart ${plan[0].x},${plan[0].y} -> e1\n")
        var n = 2
        for (i in 1 until plan.size) {
            sb.append("orthovertex ${plan[i].x},${plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = ${height}mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-10,-10 scalar=\"h\" -> e$n\n")
        var at = n
        var on = box
        for ((k, e) in tops.withIndex()) {
            val rk = if (k == 0) r0 else r1
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, rk)).first, "top edge $e is scored")[0]
            sb.append("param \"r$k\" = ${rk}mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            on = assertNotNull(round(cx, on, e, rk).first, "top edge $e rounds")
            at += 1
        }
        val mitre = assertNotNull(mitreOf(Evaluator().solid(on)), "the two bands cross in a mitre")
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, rc)).first, "the mitre is scored")[0]
        sb.append("param \"rc\" = ${rc}mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(
        ed: Editor,
        what: String,
    ): Double {
        val ref = ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "$what builds: ${(r as? EvalResult.Invalid)?.why?.render()} / ${ed.statusHint}")
        val mesh = Evaluator().solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    /** A fresh build of the canal at [rc] between rounds of [r0] and [r1], the DSL way — the reference a live edit has to reproduce. */
    private fun freshVolume(
        rc: Double,
        r0: Double = 4.0,
        r1: Double = 4.0,
    ): Double {
        val cx = Construction()
        val two = twoRounds(cx, r0, r1)
        val mitre = assertNotNull(mitreOf(Evaluator().solid(two)), "the mitre")
        val (ref, why) = round(cx, two, mitre, rc)
        val body = Evaluator().solid(assertNotNull(ref, "the canal at $rc builds fresh: $why"))
        assertManifold(body.mesh, "the canal at $rc, built fresh")
        return Geom3.volume(body.mesh)
    }

    /**
     * **A canal's rails run along the bands the ball rolls on, not round the far side of their cylinders**
     * (OP-31, slice 5s).
     *
     * The ball rolls **in the material**, so its contact on each wall is a point of that wall's own patch —
     * of the quarter cylinder a rounding band is, and not of the other three quarters of the same infinite
     * cylinder. That is the statement the canal along a fitted quartic broke: its second rail stood on the
     * **inner** branch of the smaller cylinder — at `z = 19.5` the run ended at `y = 27 − 1.658` where the
     * band's own surface stands at `y = 27 + 1.658` — so the tool took a razor-thin sliver five millimetres
     * from the corner and left the corner sharp.
     *
     * It is read against the band's **own** stated turn ([Surface3.turnStart]…[Surface3.turnEnd]) rather
     * than a hand-written quarter, and over the run's **interior**: at either end the contact is allowed to
     * run a little past the band's own trim, since that is where the crease itself runs off the wall and it
     * is (5l)'s recorded ground, while the wrong branch stands more than thirty degrees round the cylinder
     * along the whole of the run's middle.
     */
    private fun assertOnTheirOwnBands(
        body: Solid3,
        crease: Int,
        what: String,
    ): Double {
        val bands =
            assertNotNull(Section3.faces(body.feature).first, "$what: it names its faces")
                .mapNotNull { f -> f.surface?.takeIf { it.band is Revolve3.Band.Cylinder }?.let { f.name to it } }
        assertTrue(bands.size >= 2, "$what: the two rounds are cylindrical bands: ${bands.size}")
        var checked = 0
        var worst = 0.0
        for (e in edgesOf(body)) {
            val rail = e.name as? EdgeName.BlendRail ?: continue
            if (rail.edge != crease || e.reason != null) continue
            val g = e.geom as? EdgeGeom.InSpace ?: continue
            val pts = g.chain.map { it.start } + g.chain.last().end
            // the rail lies on exactly one of the two cylinders — the wall it is the contact on
            val on = bands.filter { (_, s) -> pts.all { abs(radialMiss(s, it)) <= 1e-5 } }
            assertEquals(1, on.size, "$what: rail ${rail.label.render()} is the contact on one of the two bands")
            val (name, surface) = on[0]
            for ((i, q) in pts.withIndex()) {
                val u = i.toDouble() / (pts.size - 1)
                if (u < INTERIOR_FROM || u > 1.0 - INTERIOR_FROM) continue
                val past = pastItsOwnTurn(surface, q)
                worst = max(worst, past)
                assertTrue(past <= END_SLACK_RAD, "$what: a point of rail ${rail.label.render()} at $q stands $past rad round ${name.label.render()}'s own cylinder from the band itself")
            }
            checked++
        }
        assertEquals(2, checked, "$what: the canal's two rails are each read against the band they roll on")
        return worst
    }

    /** How far [p] stands from [s]'s own cylinder, signed outward. */
    private fun radialMiss(
        s: Surface3,
        p: Vec3,
    ): Double {
        val rel = p - s.origin
        val rad = rel - s.axis.normalized() * rel.dot(s.axis.normalized())
        return rad.length() - ((s.band as Revolve3.Band.Cylinder).r)
    }

    /** How far round the cylinder [p] stands from [s]'s own stated turn, in radians — nothing when on it. */
    private fun pastItsOwnTurn(
        s: Surface3,
        p: Vec3,
    ): Double {
        if (s.full) return 0.0
        val rel = p - s.origin
        val ax = s.axis.normalized()
        val rad = rel - ax * rel.dot(ax)
        val t = kotlin.math.atan2(rad.dot(s.binormal), rad.dot(s.ref))
        val lo = kotlin.math.min(s.turnStart, s.turnEnd)
        val hi = kotlin.math.max(s.turnStart, s.turnEnd)
        val two = 2.0 * kotlin.math.PI

        fun wrap(x: Double): Double {
            var v = x
            while (v <= -PI) v += two
            while (v > PI) v -= two
            return v
        }
        return max(0.0, max(wrap(lo - t), wrap(t - hi)))
    }

    /**
     * **A live edit of the canal's radius is the fresh body, both ways, and undo walks it back.** The file is
     * written at 1 mm; the panel takes it to the tight 2.5 (the size that refused before this slice) and back.
     */
    @Test
    fun theCanalsRadiusEditedLiveIsTheFreshBodyAndUndoWalksItBack() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(canalScript(1.0)))
        val atOne = volumeOf(ed, "the canal at 1 mm from the file")
        val freshOne = freshVolume(1.0)
        assertClose(atOne, freshOne, 1e-7 * freshOne, "the file's canal at 1 mm is the fresh one")
        val rc = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "rc" }, "the canal's radius is a parameter: ${ed.doc.scalars.map { it.name }}")

        assertTrue(ed.setParameter(rc, 2.5), "the radius takes 2.5: ${ed.statusHint}")
        val atTight = volumeOf(ed, "the canal at 2.5 mm by a live edit")
        val freshTight = freshVolume(2.5)
        assertClose(atTight, freshTight, 1e-7 * freshTight, "the live edit to 2.5 is the body a fresh build at 2.5 gives")
        assertTrue(atTight < atOne, "the larger ball takes more: $atTight vs $atOne")
        // the band the edit made is the pipe the drawing states, with a spine tolerance inside the chord tolerance
        @Suppress("UNCHECKED_CAST")
        val tight = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        val pipes = assertNotNull(Section3.faces(tight.feature).first, "it names its faces").filter { it.pipe != null }
        assertEquals(1, pipes.size, "one canal band")
        val (regions, why) = Section3.regionsOf(tight.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(regions, "the level section through the tight canal closes: ${why?.render()}")

        val edited = ed.doc.scalars.first { it.name == "rc" }
        assertTrue(ed.setParameter(edited, 1.0), "…and back to 1: ${ed.statusHint}")
        assertClose(volumeOf(ed, "back at 1 mm"), atOne, 1e-7 * atOne, "back at 1 mm it is the first body again")

        assertTrue(ed.undo(), "undo the edit back to 1")
        assertClose(volumeOf(ed, "after one undo"), atTight, 1e-7 * atTight, "one undo is the tight body")
        assertTrue(ed.undo(), "undo the edit to 2.5")
        assertClose(volumeOf(ed, "after two undos"), atOne, 1e-7 * atOne, "two undos are the file's body")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file after the round of edits is a fixed point")
    }

    /**
     * **The unlike-size canal through the file and a live edit** (slice 5s's probe): the rounds at 4 and 3, the
     * canal's radius typed from 0.5 to 2 and back. Each is the fresh body, the level section through the
     * bands closes at both sizes (the corner is really rounded now — before slice 5s the tool took a sliver
     * five millimetres away and the corner stayed sharp), the rails stand on their own bands, and two undos
     * walk it back.
     */
    @Test
    fun theUnlikeSizeCanalEditedLiveIsTheFreshBodyAndItsSectionCloses() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(canalScript(0.5, 4.0, 3.0)))
        val atHalf = volumeOf(ed, "the unlike-size canal at 0.5 from the file")
        val freshHalf = freshVolume(0.5, 4.0, 3.0)
        assertClose(atHalf, freshHalf, 1e-7 * freshHalf, "the file's canal at 0.5 is the fresh one")
        // the two rounds alone, for the removal
        val plainCx = Construction()
        val plain = Geom3.volume(Evaluator().solid(twoRounds(plainCx, 4.0, 3.0)).mesh)
        assertTrue(plain - atHalf > 0.05, "the canal at 0.5 takes real material at the corner, not a sliver: ${plain - atHalf}")

        @Suppress("UNCHECKED_CAST")
        fun body() = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        val (r05, w05) = Section3.regionsOf(body().feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(r05, "the level section through the bands closes at 0.5: ${w05?.render()}")

        val rc = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "rc" }, "the canal's radius is a parameter")
        assertTrue(ed.setParameter(rc, 2.0), "the radius takes 2: ${ed.statusHint}")
        val atTwo = volumeOf(ed, "the unlike-size canal at 2 by a live edit")
        val freshTwo = freshVolume(2.0, 4.0, 3.0)
        assertClose(atTwo, freshTwo, 1e-7 * freshTwo, "the live edit to 2 is the fresh body at 2")
        assertTrue(plain - atTwo > plain - atHalf, "the larger ball takes more: ${plain - atTwo} vs ${plain - atHalf}")
        val (r2, w2) = Section3.regionsOf(body().feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
        assertNotNull(r2, "the level section through the bands closes at 2: ${w2?.render()}")
        // the canal's rails stand on their own bands: every rail point within a hair of one of the two cylinders
        val es = edgesOf(body())
        val rails = es.filter { it.reason == null && it.geom is EdgeGeom.InSpace && it.name is EdgeName.BlendRail && it.name.label.render().contains("#") }
        assertTrue(rails.isNotEmpty(), "the canal states its rails: ${es.map { it.name.label.render() }.takeLast(8)}")
        for (rail in rails) {
            for (span in (rail.geom as EdgeGeom.InSpace).chain) {
                val p = span.start
                val offBig = abs(kotlin.math.hypot(p.x - (width - 4.0), p.z - (height - 4.0)) - 4.0)
                val offSmall = abs(kotlin.math.hypot(p.y - (depth - 3.0), p.z - (height - 3.0)) - 3.0)
                val onBig = offBig < 1e-3 && p.y <= depth + 1e-6 && p.y >= depth - 3.0 - 1e-6
                val onSmall = offSmall < 1e-3 && p.x <= width + 1e-6 && p.x >= width - 4.0 - 1e-6
                assertTrue(onBig || onSmall, "${rail.name.label.render()}: a rail knot stands on one of the two bands, on the band's own side: $p ($offBig, $offSmall)")
            }
        }

        val edited = ed.doc.scalars.first { it.name == "rc" }
        assertTrue(ed.setParameter(edited, 0.5), "…and back to 0.5")
        assertClose(volumeOf(ed, "back at 0.5"), atHalf, 1e-7 * atHalf, "back at 0.5 it is the first body again")
        assertTrue(ed.undo() && ed.undo(), "two undos")
        assertClose(volumeOf(ed, "after two undos"), atHalf, 1e-7 * atHalf, "two undos are the file's body")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the file is a fixed point")
    }

    /**
     * **The fitted quartic between two rounds of unlike size, at the tight size.** The spine marched off the
     * two offset cylinders does not know it is on an ellipse or a quartic; at every size it either builds
     * inside its own figure or refuses in the drawing's words — never in the engine's.
     */
    @Test
    fun theUnlikeRoundsMitreMarchesAtEverySizeOrRefusesInTheDrawingsWords() {
        var built = 0
        var refused = 0
        for (rc in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val two = twoRounds(cx, 4.0, 3.0)
            val body = Evaluator().solid(two)
            assertManifold(body.mesh, "two unlike rounds crossing")
            val before = Geom3.volume(body.mesh)
            val es = edgesOf(body)
            val crease = es.indices.firstOrNull { es[it].reason == null && es[it].geom is EdgeGeom.InSpace && es[it].name is EdgeName.BlendMitre }
            if (crease == null) {
                println("unlike spine | rc=$rc | no stated crease between the two bands")
                return
            }
            val sec = BlendSection(BlendKind.FILLET, rc)
            val (choices, why) = Blend3.choicesFor(body, listOf(crease), sec)
            val (ref, whyBuild) = if (choices == null) null to why?.render() else round(cx, two, crease, rc)
            if (ref == null) {
                val words = assertNotNull(whyBuild, "a refusal has words")
                assertTrue("Manifold" !in words && "status" !in words && "closed shell" !in words, "rc=$rc: refused in the drawing's words, not the engine's: $words")
                assertTrue("mitre" in words || "crease" in words || "ball" in words || "rounding" in words, "rc=$rc: the refusal names the crease or the ball: $words")
                println("unlike spine | rc=$rc | refused | ${words.take(120)}")
                refused++
                continue
            }
            val rounded = Evaluator().solid(ref)
            assertManifold(rounded.mesh, "the unlike-size canal at $rc")
            val took = before - Geom3.volume(rounded.mesh)
            val (lo, hi) = assertNotNull(Blend3.canalRemoval(body.feature, crease, sec, assertNotNull(choices)[0]), "rc=$rc: the algebra states its figure")
            assertTrue(took in lo..hi, "rc=$rc: the canal takes $took, outside its own bracket [$lo, $hi]")
            // **and the figure can refuse a body that removes nothing** (OP-31, slice 5s): a bracket whose
            // lower bound is negative admits one, and this one's was, at this very size
            assertTrue(lo > 0.0, "rc=$rc: the figure's own lower bound is a removal and not a licence: $lo")
            // **the ball rolls in the material, so every contact stands on the band it rolls on** (OP-31,
            // slice 5s) — the statement the wrong branch broke, asserted on the rails themselves
            val past = assertOnTheirOwnBands(rounded, crease, "rc=$rc")
            // below both bands the block is whole and exact; through them the section **closes** — it broke
            // at the smaller band at every size until slice 5s, and what broke it was not the section reader
            // but the body: the canal's second rail stood on the inner branch of the smaller cylinder, so
            // the band it claimed to have bitten was untouched there
            val (below, wb) = Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, 15.0), Vec3.X, Vec3.Y))
            assertClose(abs(constructit.geom.GeomMath.signedArea(assertNotNull(below, "rc=$rc: below the bands the section closes: ${wb?.render()}")[0].outer)), width * depth, 1e-9, "rc=$rc: below the bands the block is whole")
            val (regions, w) = Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, 17.5), Vec3.X, Vec3.Y))
            assertNotNull(regions, "rc=$rc: the level section through the bands closes: ${w?.render()}")
            println("unlike spine | rc=$rc | built | took $took in [$lo, $hi] | level section through the bands closes | the rails stand at most $past rad off their own bands")
            built++
        }
        println("unlike spine | $built built, $refused refused")
        assertTrue(built > 0, "the unlike-size mitre rounds at some size")
    }

    /**
     * **…and the branch statement can fail** (OP-31, slice 5s) — the same check, put to the body the defect
     * built, so that it cannot pass by being unable to fail.
     *
     * The wrong branch is the rail **reflected in the plane through the wall's own axis and the contact** —
     * the same cylinder, the other side of it — which is exactly what the march marched along the fitted
     * quartic before this slice. Reflected there, every one of the run's own interior points stands more
     * than half a radian round the cylinder from the band the ball rolls on, and [pastItsOwnTurn] says so.
     */
    @Test
    fun theBranchStatementRejectsTheRailReflectedOntoTheOtherSideOfTheSameCylinder() {
        val cx = Construction()
        val two = twoRounds(cx, 4.0, 3.0)
        val body = Evaluator().solid(two)
        val es = edgesOf(body)
        val crease = assertNotNull(es.indices.firstOrNull { es[it].reason == null && es[it].geom is EdgeGeom.InSpace && es[it].name is EdgeName.BlendMitre }, "the fitted quartic")
        val (ref, why) = round(cx, two, crease, 1.0)
        val rounded = Evaluator().solid(assertNotNull(ref, "the canal at 1 mm builds: $why"))
        assertEquals(0.0, assertOnTheirOwnBands(rounded, crease, "the canal at 1 mm"), "as built, the rails stand on their own bands")
        // the smaller of the two bands, and the rail that is the contact on it
        val bands =
            assertNotNull(Section3.faces(rounded.feature).first, "it names its faces")
                .mapNotNull { f -> f.surface?.takeIf { it.band is Revolve3.Band.Cylinder }?.let { f.name to it } }
        val smaller = assertNotNull(bands.minByOrNull { (it.second.band as Revolve3.Band.Cylinder).r }, "the smaller band").second
        var reflected = 0
        var worst = 0.0
        for (e in edgesOf(rounded)) {
            val rail = e.name as? EdgeName.BlendRail ?: continue
            if (rail.edge != crease || e.reason != null) continue
            val g = e.geom as? EdgeGeom.InSpace ?: continue
            val pts = g.chain.map { it.start } + g.chain.last().end
            if (pts.any { abs(radialMiss(smaller, it)) > 1e-5 }) continue
            for ((i, q) in pts.withIndex()) {
                val u = i.toDouble() / (pts.size - 1)
                if (u < INTERIOR_FROM || u > 1.0 - INTERIOR_FROM) continue
                // the reflection that swaps the two branches: the component of the radial along the band's
                // own `ref` is kept and the one across it is turned about, which is the same cylinder read
                // on its other side
                val rel = q - smaller.origin
                val ax = smaller.axis.normalized()
                val rad = rel - ax * rel.dot(ax)
                val flipped = rad - smaller.binormal * (2.0 * rad.dot(smaller.binormal))
                val mirrored = smaller.origin + ax * rel.dot(ax) + flipped
                assertClose(radialMiss(smaller, mirrored), 0.0, 1e-9, "the reflection stays on the same cylinder")
                val past = pastItsOwnTurn(smaller, mirrored)
                worst = max(worst, past)
                assertTrue(past > END_SLACK_RAD, "the reflected rail point $mirrored is $past rad off the band — the check would not have caught it")
                reflected++
            }
        }
        assertTrue(reflected > 0, "the smaller band carries one of the canal's two rails")
        println("unlike spine | the seeded wrong branch stands up to $worst rad off the smaller band, against a slack of $END_SLACK_RAD")
    }

    /**
     * **The bevel along the same fitted quartic does not share the defect, and this is what says so**
     * (OP-31, slice 5s). A bevel has no spine: every ruling is a closed reading of the crease point under
     * it, and its two setback points are **stepped along each wall's own trace** from that point rather
     * than solved on an offset surface, so there is no second branch for a solve to land on. Its two rails
     * stand on the bands the setback is measured along, at every setback that builds.
     */
    @Test
    fun theBevelAlongTheSameFittedQuarticStandsOnItsOwnBands() {
        var built = 0
        for (d in listOf(0.5, 1.0, 1.5, 2.0, 2.5)) {
            val cx = Construction()
            val two = twoRounds(cx, 4.0, 3.0)
            val body = Evaluator().solid(two)
            val es = edgesOf(body)
            val crease = assertNotNull(es.indices.firstOrNull { es[it].reason == null && es[it].geom is EdgeGeom.InSpace && es[it].name is EdgeName.BlendMitre }, "the fitted quartic")
            val sec = BlendSection(BlendKind.CHAMFER, d)
            val (choices, why) = Blend3.choicesFor(body, listOf(crease), sec)
            if (choices == null) {
                println("unlike bevel | d=$d | refused | ${why?.render()?.take(90)}")
                continue
            }
            val ref = cx.blendAll(two, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.CHAMFER, cx.const(d.mm), null, listOf(crease), choices)))
            val r = Evaluator().eval(ref.node)
            if (r is EvalResult.Invalid) {
                println("unlike bevel | d=$d | refused | ${r.why.render().take(90)}")
                continue
            }
            val strip = Evaluator().solid(ref)
            assertManifold(strip.mesh, "the bevel along the fitted quartic at $d")
            val past = assertOnTheirOwnBands(strip, crease, "bevel d=$d")
            println("unlike bevel | d=$d | built | the rails stand at most $past rad off their own bands")
            built++
        }
        assertTrue(built >= 4, "the bevel along the fitted quartic builds at most of the setbacks: $built")
    }
}
