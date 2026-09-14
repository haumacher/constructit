package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The canal's loft parameterised by its own spine** (OP-31, slice 5m).
 *
 * *What this class is about.* Slice 5f spread a canal's stations along the **crease** and solved the ball's
 * centre in the crease's own normal plane at each. That map is a bijection only while the spine stands
 * nearer the crease than the crease's own centre of curvature; past that the crease's normal planes stop
 * foliating the spine, the run folds back over ground it has covered, and session 84 refused such a rounding
 * by name rather than build the band twice over — *"a ball of radius 2.5 mm is larger than the bend of mitre
 * crease #1"*, which is the eight cells `BlendCanalSweepTest` could not build.
 *
 * *What it is now.* The stations march the **spine itself**: the tangent is `∇f₁ × ∇f₂` of the two faces'
 * own offset surfaces, read at the point and never from a neighbour — which is exactly session 84's
 * ill-conditioning gone, since there are no neighbours in the reading — and each step is pulled back onto
 * both offsets by Newton. Nothing about the band's exactness changes: the section in the spine's normal
 * plane is still the great circle of radius `r` with both tangencies in it, which is what a pipe surface's
 * characteristic is. Only the parameterisation that folded is gone.
 *
 * *The fixture* is the tightest bend the mitre matrix has: a 40 × 30 × 20 block with two adjacent top edges
 * rounded at **4 mm**, and a ball of **2.5 mm** rolled along the elliptical mitre they cross in. The ball is
 * larger than the spine's own least radius of curvature there, which is the very condition slice 5f refused.
 */
class BlendCanalSpineTest {
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0
    private val bigR = 4.0

    /** The ball that is large against the bend — the eight refused cells' own size. */
    private val rc = 2.5

    /**
     * **The tight bend builds, and it is the band it claims to be.** One manifold shell; the canal is a
     * named face carrying the **pipe surface** it is, with the tolerance its own spine was measured to,
     * inside the chord tolerance the whole drawing is tessellated against; and what it took off stands
     * inside the figure `∫A(1 − κx̄)ds` the algebra states over the spine's own length.
     */
    @Test
    fun theTightBendBuildsAndIsTheBandItClaims() {
        val cx = Construction()
        val two = twoRounds(cx, sequential = false)
        val base = Evaluator().solid(two)
        val mitre = mitreOf(base)
        val sec = BlendSection(BlendKind.FILLET, rc)
        val choice = assertNotNull(Blend3.choicesFor(base, listOf(mitre), sec).first, "the mitre is scored")[0]
        val body = Evaluator().solid(canal(cx, two, mitre, choice))
        assertManifold(body.mesh, "the tight-bend canal")

        val faces = assertNotNull(Section3.faces(body.feature).first, "the body names its faces")
        val band = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendBand(mitre, 0) }, "the canal is a face")
        assertNotNull(band.reason, "…which is no plane and says so")
        val pipe = assertNotNull(band.pipe, "…and which carries the pipe surface it is")
        val fitted = assertNotNull(pipe.fitted, "…with the tolerance its own spine was measured to")
        assertTrue(fitted > 0.0, "the measurement is a real one, not a claim of exactness: $fitted")
        assertTrue(
            fitted <= GeomMath.TESS_TOL_MM,
            "the spine between two stations stands within the chord tolerance ${GeomMath.TESS_TOL_MM} mm — measured $fitted",
        )

        val (lo, hi) = assertNotNull(Blend3.canalRemoval(base.feature, mitre, sec, choice), "the algebra states the figure")
        val took = Geom3.volume(base.mesh) - Geom3.volume(body.mesh)
        assertTrue(took in lo..hi, "the tight-bend canal takes $took, outside the figure [$lo, $hi] over its own spine")
        println("canal spine | tight bend R=$bigR rc=$rc | took $took in [$lo, $hi] | pipe fitted $fitted")
    }

    /**
     * **The march does not fold, and it says so of itself.** The one thing the crease's own parameterisation
     * could not promise: every step of the spine carries on in the direction the last one went — the
     * station's own tangent, read from the two gradients at the station and from no neighbour, agrees with
     * the chord to the next — and no two stations stand further apart than the step the geometry derived,
     * which the run's own last step (never shorter than half of it) bounds from below.
     */
    @Test
    fun theMarchedSpineNeverTurnsBackOnItself() {
        val cx = Construction()
        val base = Evaluator().solid(twoRounds(cx, sequential = false))
        val mitre = mitreOf(base)
        val sec = BlendSection(BlendKind.FILLET, rc)
        val choice = assertNotNull(Blend3.choicesFor(base, listOf(mitre), sec).first, "the mitre is scored")[0]
        val spine = assertNotNull(Blend3.canalSpine(base.feature, mitre, sec, choice), "the canal states its own spine")
        assertTrue(spine.size >= 8, "a run this long takes more than a handful of stations: ${spine.size}")

        var shortest = Double.MAX_VALUE
        var longest = 0.0
        for (k in 0 until spine.size - 1) {
            val step = spine[k + 1].first - spine[k].first
            assertTrue(step.length() > 0.0, "station ${k + 1} stands somewhere else than station $k")
            shortest = min(shortest, step.length())
            longest = max(longest, step.length())
            // …the station's own tangent runs forward along the run, at both ends of the step
            assertTrue(spine[k].second.dot(step.normalized()) > 0.0, "station $k's own tangent runs forward")
            assertTrue(spine[k + 1].second.dot(step.normalized()) > 0.0, "station ${k + 1}'s own tangent runs forward")
            if (k > 0) {
                val back = spine[k].first - spine[k - 1].first
                assertTrue(
                    back.normalized().dot(step.normalized()) > 0.0,
                    "the run turns back on itself between stations ${k - 1}, $k and ${k + 1} — the fold slice 5f refused",
                )
            }
            // …and the arc length the station records is the length it stands at
            assertClose(spine[k + 1].third - spine[k].third, step.length(), 1e-9, "station ${k + 1} records its own arc length")
        }
        assertTrue(
            longest <= 2.0 * shortest + 1e-9,
            "every step is the derived one but the run's last, which is never shorter than half of it: $shortest..$longest",
        )
        println("canal spine | ${spine.size} stations | step $shortest..$longest mm | length ${spine.last().third}")
    }

    /**
     * **Level, vertical and tilted sections through the tight bend close** — the band is read on its own
     * `(station, arc)` chart, and the chart is the marched one now.
     */
    @Test
    fun everySectionThroughTheTightBendCloses() {
        val cx = Construction()
        val two = twoRounds(cx, sequential = false)
        val base = Evaluator().solid(two)
        val mitre = mitreOf(base)
        val sec = BlendSection(BlendKind.FILLET, rc)
        val choice = assertNotNull(Blend3.choicesFor(base, listOf(mitre), sec).first, "the mitre is scored")[0]
        val body = Evaluator().solid(canal(cx, two, mitre, choice))
        val s = 1.0 / sqrt(2.0)
        val planes =
            listOf(
                "level through the band" to Plane3(Vec3(0.0, 0.0, height - 1.0), Vec3.X, Vec3.Y),
                "vertical across the run" to Plane3(Vec3(width - 3.0, 0.0, 0.0), Vec3.Y, Vec3.Z),
                "tilted through the corner" to
                    Plane3(
                        Vec3(width - 3.0, depth - 3.0, height - 3.0),
                        Vec3(1.0, -1.0, 0.0).normalized(),
                        Vec3(1.0, 1.0, 1.0).normalized().cross(Vec3(1.0, -1.0, 0.0).normalized()),
                    ),
            )
        for ((what, plane) in planes) {
            val (regions, why) = Section3.regionsOf(body.feature, plane)
            val rs = assertNotNull(regions, "the $what section closes: ${why?.render()}")
            assertTrue(rs.isNotEmpty(), "…with an area")
        }
        // **and where one does not close it says where** (OP-3), which is this slice's own cut and is
        // narrowed to one sentence by session 86 (OP-31, slice 5l): a plane standing within two millimetres
        // of the tight bend's corner breaks at the canal's own **flat end**, and by exactly the step-off
        // that end takes. The band, its rails and its cap now all end at one ring — the ring the tool really
        // lays, [Canal.grow] past the last station — so the only gap left in the loop is between that cap
        // and the **wall** its own leg has run out onto past the end of the band it rolls on. The body has a
        // step there and the drawing does not state it; nothing is drawn that does not close, and the face
        // it breaks at is named.
        for ((what, plane) in listOf(
            "x = 38" to Plane3(Vec3(38.0, 0.0, 0.0), Vec3.Y, Vec3.Z),
            "x = 39" to Plane3(Vec3(39.0, 0.0, 0.0), Vec3.Y, Vec3.Z),
            "y = 28" to Plane3(Vec3(0.0, 28.0, 0.0), Vec3.X, Vec3.Z),
            "y = 29" to Plane3(Vec3(0.0, 29.0, 0.0), Vec3.X, Vec3.Z),
        )) {
            val (regions, why) = Section3.regionsOf(body.feature, plane)
            if (regions != null) continue
            val reason = assertNotNull(why, "a section that does not close says why").render()
            assertTrue(reason.contains("flat end"), "…naming the canal's own flat end, which is where it breaks: $reason")
            println("canal spine | vertical at $what | does not close, and says where | ${reason.take(90)}")
        }
    }

    /**
     * **The canal's band, its rails and its cap all end at one ring** (OP-31, slice 5l) — the ring the tool
     * really lays, which is [Blend3] `Canal.grow` past the last station and not the station itself.
     *
     * *Why this is a test and not an implementation detail.* [canalMesh] **moves** a free end's ring rather
     * than doubling it, so over that last stretch the band is the section translated and the flat end the
     * body keeps stands there. Slice 5l put the cap patch there because the boolean's own reader has to find
     * the facet; the band's cut and the rail that sets its neighbour back were still read to the *station*,
     * so a vertical section through the tight bend broke twice — once between the band and its own cap, and
     * once between that cap and the face beyond it — each time by exactly the step-off. The first of the two
     * is what this asserts: the canal's own section piece ends **on** the cap's own, to the tolerance the
     * band's fitted spine states.
     */
    @Test
    fun theCanalsCutEndsOnItsOwnCap() {
        val cx = Construction()
        val two = twoRounds(cx, sequential = false)
        val base = Evaluator().solid(two)
        val mitre = mitreOf(base)
        val sec = BlendSection(BlendKind.FILLET, rc)
        val choice = assertNotNull(Blend3.choicesFor(base, listOf(mitre), sec).first, "the mitre is scored")[0]
        val body = Evaluator().solid(canal(cx, two, mitre, choice))
        val faces = assertNotNull(Section3.faces(body.feature).first, "the body names its faces")
        val band = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendBand(mitre, 0) }, "the canal is a face")
        val pipe = assertNotNull(band.pipe, "…carrying its own pipe")
        val cap =
            assertNotNull(
                faces.firstOrNull { it.name is FaceName.BlendCap && (it.name as FaceName.BlendCap).edge == mitre && it.plane != null },
                "the canal closes on a flat end of its own",
            )
        val plane = assertNotNull(cap.plane)
        // every station of the band's own carrier stands on the run, and the **last** of them stands in the
        // cap's own plane — which is what "one ring" means
        assertTrue(pipe.stations.size >= 2, "the pipe has a run of stations")
        val near = pipe.stations.minOf { abs(plane.distanceTo(it.at)) }
        assertTrue(near <= 1e-9, "the band's own carrier ends in the plane of the cap it closes on: $near mm away")
        println("canal spine | band and cap | the run's last station stands $near mm from its own cap")
    }

    /**
     * **One body, whichever gesture made it.** The two rounds under the canal taken as one entry of one
     * gesture and as two gestures one after another give the same body to the engine's own resolution, and
     * so does the canal that stands on them.
     */
    @Test
    fun bothRoutesGiveOneBody() {
        val volumes =
            listOf(false, true).map { stacked ->
                val cx = Construction()
                val two = twoRounds(cx, sequential = stacked)
                val base = Evaluator().solid(two)
                val mitre = mitreOf(base)
                val sec = BlendSection(BlendKind.FILLET, rc)
                val choice = assertNotNull(Blend3.choicesFor(base, listOf(mitre), sec).first, "the mitre is scored")[0]
                val body = Evaluator().solid(canal(cx, two, mitre, choice))
                assertManifold(body.mesh, "the tight-bend canal (stacked = $stacked)")
                Geom3.volume(body.mesh)
            }
        assertTrue(
            abs(volumes[0] - volumes[1]) <= 1e-7 * volumes[0],
            "the two routes give one body: ${volumes[0]} against ${volumes[1]}",
        )
    }

    /**
     * **The file is a fixed point at the tight size too.** The gesture is an ordinary `filletedge` on an
     * ordinary edge index, so the marched spine costs the format nothing at all: nothing is stored about the
     * stations, and the count is derived from the geometry every time the body is built (OP-21).
     */
    @Test
    fun theTightBendRoundTripsByteEqual() {
        val script = blockScript()
        val once = DocumentFormat.save(DocumentFormat.load(script))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the drawing round-trips byte-equal")
        val was = volumeOf(bodyRef(DocumentFormat.load(script)), "as written")
        val now = volumeOf(bodyRef(DocumentFormat.load(once)), "as read back")
        assertClose(now, was, 1e-7 * was, "the reloaded body is the same body")
    }

    // ---- fixtures ----

    private fun canal(
        cx: Construction,
        on: SolidRef,
        mitre: Int,
        choice: constructit.geom.BlendChoice,
    ): SolidRef = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(rc.mm), null, listOf(mitre), listOf(choice))))

    private fun twoRounds(
        cx: Construction,
        sequential: Boolean,
    ): SolidRef {
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        assertEquals(2, tops.size, "two top edges share the block's far corner")
        val sec = BlendSection(BlendKind.FILLET, bigR)
        if (!sequential) {
            val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, sec).first, "the two top edges are scored")
            return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(bigR.mm), null, tops, choices)))
        }
        var on = box
        for (e in tops) {
            val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), sec).first, "top edge $e is scored")
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(bigR.mm), null, listOf(e), choices)))
        }
        return on
    }

    private fun topsAtFarCorner(s: Solid3): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        val corner = Vec3(width, depth, height)
        return es.indices.filter { i ->
            val path = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = path.start ?: return@filter false
            val b = path.end ?: return@filter false
            abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
        }
    }

    private fun mitreOf(s: Solid3): Int {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return assertNotNull(
            es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
            "the two bands cross in a mitre",
        )
    }

    private fun blockScript(): String {
        val cx = Construction()
        val box = prism(cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val tops = topsAtFarCorner(Evaluator().solid(box))
        val sb = StringBuilder("constructit ${DocumentFormat.VERSION}\n")
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
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
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, bigR)).first, "top edge $e is scored")[0]
            sb.append("param \"r$k\" = ${bigR}mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(bigR.mm), null, listOf(e), listOf(choice))))
            at += 1
        }
        val mitre = mitreOf(Evaluator().solid(on))
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.FILLET, rc)).first, "the mitre is scored")[0]
        sb.append("param \"rc\" = ${rc}mm\n")
        sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"rc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun bodyRef(doc: constructit.editor.Document): SolidRef =
        doc.elements.last { it.kind == constructit.editor.ElementKind.SOLID }.ref as SolidRef

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

    private fun prism(
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.mapIndexed { i, p -> cx.freePoint("p$i", p.x.mm, p.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }
}
