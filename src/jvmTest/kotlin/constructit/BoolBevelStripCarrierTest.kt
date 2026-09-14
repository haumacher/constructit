package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.Construction
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.FacePatch
import constructit.geom.Feature3
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A bevel's ruled strip as a carrier through the general boolean** (OP-31, slice 5r; GitHub #36).
 *
 * *What this class is about.* [BoolFace3][constructit.geom.BoolFace3] places a result triangle on an operand
 * face by asking each carrier whether the triangle sits on it. Slice 5c gave the vocabulary its curved
 * carriers, slice 5l the **pipe** a canal band is, and slice 5n left the **ruled strip** a chamfer leaves
 * along a crease of changing dihedral as the one face that was only ever *drawn*: a body carrying one
 * refused its whole face list by name. This slice makes it the fifth carrier, and it owes exactly what the
 * pipe owed one surface over.
 *
 * *The five obligations.* A **`sits` predicate** ([Ruled3][constructit.geom.Ruled3]`.offset` — the ruling `p`
 * stands on, found by the same scan-and-golden-section the pipe's spine is read by, and then the strip's own
 * normal there, with `Ruled3.near` as the cheap box reject a face list of thirty carriers needs); a **chart**
 * (`(station, t)`, the arc length along the run and the fraction along the ruling, whose forward map
 * `a + t(b − a)` is **exact** in `t` whatever the ruling is); **creases** (no name in this drawing's
 * vocabulary, so a fitted chain through points exact on both — `exactCurves` returns null for a strip by
 * rule, as it does for a pipe); its place in the result **face list** (none of its own: the strip is an
 * ordinary entry of the operand's face list and the carrier list is built in that order, so no stored
 * address moves); and every **reader** — `regionsOf`, `faces`, `faceAt` and the sketch space, which still
 * declines a strip in the words slice 5n gave it.
 *
 * *What is exact and what is fitted* (Tier B). Every ruling this drawing solved is exact at **both** ends;
 * a ruling between two of them is a Catmull–Rom interpolant of the two rails, and how far that may stand
 * from the truth is measured and carried on the face and on every crease fitted against it — together with
 * the strip's own **chord**, which is how far the loft's flat quad between two rulings stands from the strip
 * and therefore the recognition tolerance a lookup against this carrier is owed.
 */
class BoolBevelStripCarrierTest {
    private var ids = 0
    private val width = 40.0
    private val depth = 30.0
    private val height = 20.0
    private val setback = 1.0

    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    // ---- the fixtures ----

    private fun prismOn(
        plane: Plane3,
        cx: Construction,
        xy: List<Vec2>,
        h: Double,
    ): SolidRef {
        val pts = xy.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val segs = xy.indices.map { cx.segment(pts[it], pts[(it + 1) % xy.size]) }
        return cx.extrude(cx.sketchOn(cx.plane(plane.origin, plane.u, plane.v), cx.region(cx.loop(*segs.toTypedArray()))), cx.const(h.mm))
    }

    /** The block with its two far top edges rounded at 4 mm, on any plane. */
    private fun twoRounds(
        cx: Construction,
        plane: Plane3,
    ): SolidRef {
        val box = prismOn(plane, cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es = edgesOf(Evaluator().solid(box))
        val corner = plane.toWorld(Vec2(width, depth)) + plane.normal.normalized() * height
        val n = plane.normal.normalized()
        val top = plane.origin.dot(n) + height
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.dot(n) - top) < 1e-9 && abs(b.dot(n) - top) < 1e-9 && ((a - corner).length() < 1e-6 || (b - corner).length() < 1e-6)
            }
        assertEquals(2, tops.size, "two top edges share the block's far corner")
        val body = Evaluator().solid(box)
        val choices = assertNotNull(Blend3.choicesFor(body, tops, BlendSection(BlendKind.FILLET, 4.0)).first, "the two top edges are scored")
        return cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, choices)))
    }

    /** …and the elliptical mitre they cross in **bevelled** at 1 mm — the ruled strip itself. */
    private fun bevelledBody(
        cx: Construction,
        plane: Plane3 = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
    ): SolidRef {
        val two = twoRounds(cx, plane)
        val es = edgesOf(Evaluator().solid(two))
        val mitre =
            assertNotNull(
                es.indices.firstOrNull { es[it].name is EdgeName.BlendMitre && es[it].reason == null && es[it].geom is EdgeGeom.OnPlane },
                "the two bands cross in a mitre",
            )
        val (choices, why) = Blend3.choicesFor(Evaluator().solid(two), listOf(mitre), BlendSection(BlendKind.CHAMFER, setback))
        assertNotNull(choices, "the mitre is scored for a chamfer: ${why?.render()}")
        return cx.blendAll(two, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.CHAMFER, cx.const(setback.mm), null, listOf(mitre), choices!!)))
    }

    /** A drill of radius [r] along the plane's own normal, through the whole body. */
    private fun drill(
        cx: Construction,
        plane: Plane3,
        at: Vec2,
        r: Double,
    ): SolidRef {
        val c = cx.freePoint("d${ids++}", at.x.mm, at.y.mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(r.mm))))
        val base = cx.plane(plane.origin - plane.normal.normalized() * 5.0, plane.u, plane.v)
        return cx.extrude(cx.sketchOn(base, circle), cx.const((height + 10.0).mm))
    }

    // ---- readers ----

    private fun facesOf(s: Solid3): List<FacePatch> {
        val (fs, why) = Section3.faces(s.feature)
        return assertNotNull(fs, "it names its faces: ${why?.render()}")
    }

    private fun edgesOf(s: Solid3) = assertNotNull(Section3.edges(s.feature).first, "it names its edges")

    private fun areaAt(
        s: Solid3,
        plane: Plane3,
        what: String,
    ): Double {
        val (regions, why) = Section3.regionsOf(s.feature, plane)
        return regionArea(assertNotNull(regions, "$what closes: ${why?.render()}"))
    }

    private fun regionArea(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    /** Every face of the body says what it is — a carrier of one of the five kinds, or a reason of its own. */
    private fun everyFaceNamed(
        fs: List<FacePatch>,
        what: String,
    ) {
        assertTrue(fs.isNotEmpty(), "$what has faces")
        for (f in fs) {
            assertTrue(
                f.plane != null || f.surface != null || f.pipe != null || f.strip != null || f.reason != null,
                "${f.name.label.render()} of $what says what it is",
            )
        }
    }

    /** The strip's own pieces of the result, each carrying the ruled surface it is a patch of. */
    private fun stripsOf(fs: List<FacePatch>): List<FacePatch> = fs.filter { it.strip != null }

    // ---- (a) a bore straight through the strip ----

    /**
     * **A bore through the strip region itself**, which is where the fifth carrier earns its name: the
     * bore's own cylinder crosses the strip, so the result has a crease between two surfaces neither of
     * which is a plane and one of which is a ruled strip. Every face is named, the strip's remaining pieces
     * carry their trim in `(station, t)`, and the level, the vertical and the tilted section all close.
     */
    @Test
    fun aBoreThroughTheStripIsNamedAndEverySectionCloses() {
        requireEngine()
        val cx = Construction()
        val body = bevelledBody(cx)
        val plain = Evaluator().solid(body)
        assertManifold(plain.mesh, "the bevelled body")
        val r = 1.5
        val bored = Evaluator().solid(cx.subtract(body, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(38.0, 28.0), r)))
        assertManifold(bored.mesh, "the bevelled body bored through its own strip")
        assertTrue(bored.feature is Feature3.MeshBoolean, "it takes the general path")
        val fs = facesOf(bored)
        everyFaceNamed(fs, "the bored bevelled body")

        val strips = stripsOf(fs)
        assertTrue(strips.isNotEmpty(), "the bevel's ruled strip is still named, in the pieces the bore left of it")
        for (b in strips) {
            val strip = assertNotNull(b.strip, "${b.name.label.render()} carries its own rulings")
            assertTrue(strip.rulings.size >= 2, "…at least two of them")
            assertTrue(b.plane == null && b.surface == null && b.pipe == null, "…and is neither a plane, nor a revolution, nor a pipe")
            assertNotNull(b.fitted, "…and says how far its own statement may be")
            assertTrue(b.outline.isNotEmpty(), "…with its trim stated in the strip's own (station, t)")
            // …and that trim really is in the strip's own chart: every point of it stands inside the run
            // and inside its own ruling, which is what `(station, t)` means and no other chart says
            for (e in b.outline) {
                for (q in GeomMath.tessellatePiece(e, 1e-3)) {
                    assertTrue(q.x >= -1e-6 && q.x <= strip.length + 1e-6, "…the station inside the run: ${q.x} of ${strip.length}")
                    assertTrue(q.y >= -1e-3 && q.y <= 1.0 + 1e-3, "…and t inside its own ruling: ${q.y}")
                }
            }
        }

        val level = Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y)
        val vertical = Plane3(Vec3(38.0, 0.0, 0.0), Vec3.Y, Vec3.Z)
        val tilted = Plane3(Vec3(30.0, 22.0, 14.0), Vec3.X, Vec3(0.0, cos(PI / 7), sin(PI / 7)))
        for ((what, plane) in listOf("the level section at z = 19.5" to level, "a vertical section through the bore" to vertical, "a tilted section through the bore" to tilted)) {
            val (regions, why) = Section3.regionsOf(bored.feature, plane)
            assertNotNull(regions, "$what through the bored strip closes: ${why?.render()}")
        }

        // …and the vertical section's own area is stated: the plane stands on the bore's own axis, so what
        // it loses to the bore is a strip **2r wide** running from the bottom face to whatever the block's
        // dressed top stands at over that strip — between the corner the two rounds and the bevel have cut
        // down to and the block's full height. That is a bracket the construction states and not a number
        // read back off the same mesh.
        val was = areaAt(plain, vertical, "the unbored bevelled body's vertical section at x = 38")
        val now = areaAt(bored, vertical, "the bored body's vertical section at x = 38")
        val took = was - now
        assertTrue(
            took in (2.0 * r * (height - 4.0))..(2.0 * r * height),
            "the vertical section at x = 38 loses the bore's own strip: $took, outside [${2.0 * r * (height - 4.0)}, ${2.0 * r * height}]",
        )
        val cut = Geom3.volume(plain.mesh) - Geom3.volume(bored.mesh)
        assertTrue(
            cut in (PI * r * r * (height - 4.0))..(PI * r * r * height),
            "the bore takes its own cylinder, less what the two rounds and the bevel had already taken: $cut",
        )
        println("bevel carrier | bore through the strip | ${fs.size} faces, ${strips.size} strip piece(s), vertical $was - $took -> $now")
    }

    // ---- (b) what a fitted crease reached ----

    /**
     * **Every crease the bore leaves on the strip stands within the tolerance it states** — asserted point
     * by point against *both* surfaces it runs between, which is the only honest reading of a curve this
     * drawing states as a chain (Tier B).
     */
    @Test
    fun everyFittedCreaseOfTheBoredStripStandsWithinTheToleranceItStates() {
        requireEngine()
        val cx = Construction()
        val body = bevelledBody(cx)
        val strip = assertNotNull(facesOf(Evaluator().solid(body)).firstOrNull { it.strip != null }?.strip, "the bevel is a ruled strip")
        val bore = 1.5
        val bored = Evaluator().solid(cx.subtract(body, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(38.0, 28.0), bore)))
        val fitted = edgesOf(bored).filter { it.reason == null && it.geom is EdgeGeom.InSpace && it.fitted != null }
        assertTrue(fitted.isNotEmpty(), "the bore through the strip leaves creases this drawing has no name for")
        var worst = 0.0
        var onTheStrip = 0
        for (e in fitted) {
            val tol = assertNotNull(e.fitted)
            val pts = (e.geom as EdgeGeom.InSpace).chain.flatMap { span -> (0..8).map { Frames3.pointAt(span, it / 8.0) } }

            fun offBore(p: Vec3) = abs(sqrt((p.x - 38.0) * (p.x - 38.0) + (p.y - 28.0) * (p.y - 28.0)) - bore)
            // which crease runs on the strip is read off the strip itself and never off a name
            if (pts.any { abs(strip.offset(it)) > max(tol, 1e-2) || offBore(it) > max(tol, 1e-2) }) continue
            onTheStrip++
            for (p in pts) {
                val d = offBore(p)
                val q = abs(strip.offset(p))
                worst = max(worst, max(d, q))
                assertTrue(d <= tol + 1e-9, "${e.name.label.render()} stands within its stated $tol mm of the bore — $d")
                assertTrue(q <= tol + 1e-9, "…and of the bevel's own strip — $q")
            }
        }
        assertTrue(onTheStrip > 0, "the bore leaves at least one crease running on the bevel's own strip")
        println("bevel carrier | fitted creases | ${fitted.size}, $onTheStrip on the strip, worst $worst")
    }

    // ---- (c) the other route ----

    /** **A boss fused on** — the union rather than the difference, and the same naming. */
    @Test
    fun aBossFusedOntoABevelledBodyIsNamedTheSameWay() {
        requireEngine()
        val cx = Construction()
        val body = bevelledBody(cx)
        val before = Geom3.volume(Evaluator().solid(body).mesh)
        val boss = drill(cx, Plane3(Vec3(0.0, 0.0, height), Vec3.X, Vec3.Y), Vec2(36.0, 27.0), 2.5)
        val fused = Evaluator().solid(cx.union(body, boss))
        assertManifold(fused.mesh, "a boss fused onto the bevelled body")
        assertTrue(Geom3.volume(fused.mesh) > before, "a boss adds material")
        val fs = facesOf(fused)
        everyFaceNamed(fs, "the bevelled body with a boss")
        assertTrue(stripsOf(fs).isNotEmpty(), "the ruled strip survives the union as a face of its own")
        assertNotNull(
            Section3.regionsOf(fused.feature, Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y)).first,
            "a level section through the boss and the strip closes: ${Section3.regionsOf(fused.feature, Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y)).second?.render()}",
        )
        println("bevel carrier | boss fused | ${fs.size} faces, ${stripsOf(fs).size} strip piece(s)")
    }

    // ---- (d) the same body in five poses ----

    /** **One body, wherever it stands.** Five poses of the bored bevelled body: every one named, one volume. */
    @Test
    fun everyPoseOfTheBoredBevelledBodyBuildsAndIsNamed() {
        requireEngine()
        val poses =
            listOf(
                "XY" to Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                "shifted YZ" to Plane3(Vec3(17.0, -9.0, 4.0), Vec3.Y, Vec3.Z),
                "turned 30° about x" to Plane3(Vec3.ZERO, Vec3.X, Vec3(0.0, cos(PI / 6), sin(PI / 6))),
                "turned 30° about y" to Plane3(Vec3.ZERO, Vec3(cos(PI / 6), 0.0, -sin(PI / 6)), Vec3.Y),
                "turned 45° about z and shifted" to Plane3(Vec3(-3.0, 11.0, -7.0), Vec3(1.0, 1.0, 0.0) * (1.0 / sqrt(2.0)), Vec3.Z),
            )
        var first: Double? = null
        var named = 0
        for ((what, plane) in poses) {
            val cx = Construction()
            val body = bevelledBody(cx, plane)
            val bored = Evaluator().solid(cx.subtract(body, drill(cx, plane, Vec2(12.0, 12.0), 3.0)))
            assertManifold(bored.mesh, "the bored bevelled body on $what")
            val v = Geom3.volume(bored.mesh)
            if (first == null) first = v else assertClose(v, first, tol = 2e-2, msg = "the same body on $what takes the same volume")
            val fs = facesOf(bored)
            everyFaceNamed(fs, "the bored bevelled body on $what")
            assertTrue(stripsOf(fs).isNotEmpty(), "the ruled strip is named on $what")
            named++
            println("bevel carrier | pose $what | volume $v, ${fs.size} faces")
        }
        assertEquals(poses.size, named, "every pose of the bored bevelled body is named")
    }

    // ---- (e) the readers a face owes ----

    /** **`faceAt` picks the strip**, and the sketch space still declines it in the words slice 5n gave it. */
    @Test
    fun aRayOntoTheStripPicksItAndTheSketchSpaceDeclinesItInItsOwnWords() {
        requireEngine()
        val cx = Construction()
        val body = bevelledBody(cx)
        val bored = Evaluator().solid(cx.subtract(body, drill(cx, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), Vec2(12.0, 12.0), 3.0)))
        val strip = assertNotNull(stripsOf(facesOf(bored)).firstOrNull(), "the bored body keeps its strip")
        val ruled = assertNotNull(strip.strip, "…with its rulings")
        // stand on the strip itself — the middle of the middle ruling — and shoot at it
        val mid = ruled.world(ruled.length / 2.0, 0.5)!!
        val pick = assertNotNull(Section3.faceAt(bored.feature, mid, Vec3(-1.0, -1.0, -1.0).normalized(), 0.05).first, "a ray onto the strip answers")
        assertEquals(strip.name, pick.patch.name, "…and the face it answers with is the strip")
        val said = assertNotNull(pick.patch.reason, "the strip refuses a sketch space").render()
        assertTrue(said.contains("ruled strip"), "…in the words that say it is a ruled strip: $said")
        println("bevel carrier | faceAt on the strip | ${strip.name.label.render()}")
    }

    /**
     * **A strip and a pipe in one body, through one boolean** — the block's four top edges rounded, one
     * mitre filleted into a canal band and another bevelled into a ruled strip, and the whole thing bored.
     * The fourth carrier and the fifth stand in one face list and neither refuses the other.
     */
    @Test
    fun aCanalBandAndARuledStripGoThroughOneBooleanTogether() {
        requireEngine()
        val cx = Construction()
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val both = pipeAndStripBody(cx, plane)
        val body = Evaluator().solid(both)
        assertManifold(body.mesh, "a block with a canal band on one mitre and a ruled strip on another")
        val dressed = facesOf(body)
        assertTrue(dressed.any { it.pipe != null }, "the dressed body carries a canal band")
        assertTrue(dressed.any { it.strip != null }, "…and a ruled strip")

        val bored = Evaluator().solid(cx.subtract(both, drill(cx, plane, Vec2(20.0, 15.0), 4.0)))
        assertManifold(bored.mesh, "…bored")
        val fs = facesOf(bored)
        everyFaceNamed(fs, "the bored body carrying both")
        assertTrue(fs.any { it.pipe != null }, "the fourth carrier is in the result's face list")
        assertTrue(fs.any { it.strip != null }, "…and so is the fifth")
        println("bevel carrier | pipe and strip in one body | ${fs.size} faces")
    }

    /** The block's four top edges rounded, one mitre filleted into a canal band and another bevelled. */
    private fun pipeAndStripBody(
        cx: Construction,
        plane: Plane3,
    ): SolidRef {
        val box = prismOn(plane, cx, listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth)), height)
        val es = edgesOf(Evaluator().solid(box))
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9
            }
        assertEquals(4, tops.size, "the block has four top edges")
        val choices = assertNotNull(Blend3.choicesFor(Evaluator().solid(box), tops, BlendSection(BlendKind.FILLET, 4.0)).first, "all four are scored")
        val four = cx.blendAll(box, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, tops, choices)))
        val mitres =
            edgesOf(Evaluator().solid(four)).let { list ->
                list.indices.filter { list[it].name is EdgeName.BlendMitre && list[it].reason == null && list[it].geom is EdgeGeom.OnPlane }
            }
        assertTrue(mitres.size >= 2, "four rounds cross in four mitres: ${mitres.size}")
        val rounded =
            cx.blendAll(
                four,
                cx.planeXY(),
                listOf(
                    Construction.BlendRun(
                        BlendKind.FILLET,
                        cx.const(1.0.mm),
                        null,
                        listOf(mitres[0]),
                        assertNotNull(Blend3.choicesFor(Evaluator().solid(four), listOf(mitres[0]), BlendSection(BlendKind.FILLET, 1.0)).first, "the first mitre is scored"),
                    ),
                ),
            )
        return cx.blendAll(
            rounded,
            cx.planeXY(),
            listOf(
                Construction.BlendRun(
                    BlendKind.CHAMFER,
                    cx.const(setback.mm),
                    null,
                    listOf(mitres[1]),
                    assertNotNull(
                        Blend3.choicesFor(Evaluator().solid(rounded), listOf(mitres[1]), BlendSection(BlendKind.CHAMFER, setback)).first,
                        "the second mitre is scored for a chamfer",
                    ),
                ),
            ),
        )
    }

    /**
     * **A face a boolean never touched comes through it unchanged — for every carrier kind** (OP-31, slice
     * 5r's probe). The body carries all four: the block's **planes**, the four **revolution** bands its top
     * edges are rounded with, the **pipe** one mitre's canal band is, and the **ruled strip** another mitre's
     * bevel is. It is bored once through the middle, clear of all the dressing, and then bored a second time
     * somewhere else — and the second boolean, which reaches none of them, must leave every one of them with
     * the same number of pieces and the same trim. That is the chaining rule (slice 5c) said about a face
     * rather than about an address, and a piece count that moves under an unrelated cut shifts every slot
     * after it.
     *
     * And **no two faces of one body answer to one name**: a label is what a refusal and a panel row say.
     */
    @Test
    fun everyCarrierKindComesThroughASecondBooleanUnchanged() {
        requireEngine()
        val cx = Construction()
        val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val both = pipeAndStripBody(cx, plane)
        val once = cx.subtract(both, drill(cx, plane, Vec2(20.0, 15.0), 4.0))
        val first = Evaluator().solid(once)
        assertManifold(first.mesh, "the dressed body bored once")
        val fs1 = facesOf(first)
        everyFaceNamed(fs1, "the dressed body bored once")
        val twice = Evaluator().solid(cx.subtract(once, drill(cx, plane, Vec2(8.0, 22.0), 2.5)))
        assertManifold(twice.mesh, "…and a second time, clear of everything")
        val fs2 = facesOf(twice)
        everyFaceNamed(fs2, "the dressed body bored twice")

        // the twice-bored body's **first operand** is the once-bored body, so its first-operand faces are
        // exactly the once-bored body's faces, one boolean on
        val mine = fs2.filter { (it.name as? FaceName.BoolFace)?.operand == 0 }
        for (
        (what, of) in
        listOf<Pair<String, (FacePatch) -> Boolean>>(
            "plane" to { f -> f.plane != null },
            "revolution" to { f -> f.surface != null },
            "pipe" to { f -> f.pipe != null },
            "ruled strip" to { f -> f.strip != null },
        )
        ) {
            val a = fs1.filter(of)
            val b = mine.filter(of)
            assertTrue(a.isNotEmpty(), "the body carries a $what face at all")
            assertEquals(a.size, b.size, "the second boolean leaves the $what faces as they were: ${a.map { it.name.label.render() }} / ${b.map { it.name.label.render() }}")
            val da = a.map { chartSpan(it) }.sorted()
            val db = b.map { chartSpan(it) }.sorted()
            // …and their trims. The second boolean **re-meshes** the whole body and re-reads every trim off
            // the new mesh, so a trim that the cut never reached is the same trim stated a second time and
            // not the same numbers: what it may move by is the tier it is stated to (OP-31, Tier B — a
            // chord's tolerance at either end of it), and what it may **not** do is gain or lose a piece.
            for (i in da.indices) {
                assertClose(db[i], da[i], tol = 5e-2, msg = "…and their trims: the $what face #$i spans ${db[i]} where it spanned ${da[i]}")
            }
            println("bevel carrier | chained | $what: ${a.size} faces, spans ${da.map { (it * 1000).toInt() / 1000.0 }}")
        }
        for (fs in listOf(fs1, fs2)) {
            val labels = fs.filter { it.outline.isNotEmpty() || it.plane != null }.map { it.name.label.render() }
            assertEquals(labels.size, labels.toSet().size, "every face of the body has a name of its own: ${labels.groupBy { it }.filter { it.value.size > 1 }.keys}")
        }
    }

    /** The diagonal of a face's own trim in its own chart — what says a trim is the trim it was. */
    private fun chartSpan(f: FacePatch): Double {
        var lo = Vec2(Double.MAX_VALUE, Double.MAX_VALUE)
        var hi = Vec2(-Double.MAX_VALUE, -Double.MAX_VALUE)
        for (e in f.outline) {
            for (q in GeomMath.tessellatePiece(e, 1e-3)) {
                lo = Vec2(kotlin.math.min(lo.x, q.x), kotlin.math.min(lo.y, q.y))
                hi = Vec2(kotlin.math.max(hi.x, q.x), kotlin.math.max(hi.y, q.y))
            }
        }
        return if (hi.x < lo.x) 0.0 else (hi - lo).length()
    }

    // ---- (f) the editor route, and the file ----

    /**
     * **The route a user takes**: the bevel as a file, a hole cut into the block's wall through the editor —
     * a face space opened by a plan click, a circle, *Cut* — the result named with the strip among its
     * faces, and the file a fixed point.
     */
    @Test
    fun aHoleCutIntoTheBevelledBodyThroughTheEditorIsNamedAndTheFileIsAFixedPoint() {
        requireEngine()
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(bevelScript()))
        val plain = bodyOf(ed)
        assertManifold(plain.mesh, "the bevelled body from the file")

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(width, 15.0))
        assertTrue(!ed.activeSpace.isPlan, "the wall opened as a space: ${ed.statusHint}")
        val plane = ((Evaluator().eval(assertNotNull(ed.activeSpace.plane, "the space has a plane").node) as EvalResult.Ok).value as PlaneValue).plane
        assertClose(abs(plane.normal.normalized().x), 1.0, 1e-9, "…and it is the x = 40 wall: ${plane.normal}")
        val centre = Vec3(width, 15.0, 8.0)
        val local = Vec2((centre - plane.origin).dot(plane.u.normalized()), (centre - plane.origin).dot(plane.v.normalized()))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("3")
        ed.click(local)
        val before = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        ed.setTool(Tools.CUT)
        ed.type("6")
        // a circle is clicked on its **outline** — its centre is a point, and the point would win (OP-16)
        ed.click(local + Vec2(3.0, 0.0))
        assertTrue(ed.doc.elements.count { it.kind == ElementKind.SOLID } > before, "the cut is a new body: ${ed.statusHint}")
        val bored = bodyOf(ed)
        assertManifold(bored.mesh, "the bevelled body with a hole in its wall")
        val fs = facesOf(bored)
        everyFaceNamed(fs, "the bored bevelled body from the editor")
        assertTrue(stripsOf(fs).isNotEmpty(), "the ruled strip is a named face of the bored body")
        assertClose(level(bored, 3.0, "the bored body"), width * depth, 1e-6, "below the hole the block is whole")

        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal:\n$once")
        val ed2 = Editor()
        ed2.replaceDocument(back)
        val again = bodyOf(ed2)
        assertManifold(again.mesh, "the reloaded body")
        val v = Geom3.volume(bored.mesh)
        assertClose(Geom3.volume(again.mesh), v, 1e-7 * v, "the reloaded body is the same body, to the float32 snap")
        assertTrue(stripsOf(facesOf(again)).isNotEmpty(), "…and it names its strip too")
        println("bevel carrier | editor route | ${fs.size} faces, ${stripsOf(fs).size} strip piece(s)")
    }

    // ---- the file, and the editor's own helpers ----

    private fun bevelScript(): String {
        val cx = Construction()
        val plan = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, depth), Vec2(0.0, depth))
        val pts = plan.map { cx.freePoint("p${ids++}", it.x.mm, it.y.mm) }
        val box = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.loop(*plan.indices.map { cx.segment(pts[it], pts[(it + 1) % plan.size]) }.toTypedArray()))), cx.const(height.mm))
        val corner = Vec3(width, depth, height)
        val es = edgesOf(Evaluator().solid(box))
        val tops =
            es.indices.filter { i ->
                val path = Blend3.edgePath(es[i]).first ?: return@filter false
                val a = path.start ?: return@filter false
                val b = path.end ?: return@filter false
                abs(a.z - height) < 1e-9 && abs(b.z - height) < 1e-9 && ((a - corner).length() < 1e-9 || (b - corner).length() < 1e-9)
            }
        assertEquals(2, tops.size, "two top edges share the far corner")
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
            val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, 4.0)).first, "top edge $e is scored")[0]
            sb.append("param \"r$k\" = 4mm\n")
            sb.append("tool filletedge els=e$at clicks=0,0 scalar=\"r$k\" signs=$e;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
            val (cs, why) = Blend3.choicesFor(Evaluator().solid(on), listOf(e), BlendSection(BlendKind.FILLET, 4.0))
            assertNotNull(cs, "edge $e is scored: ${why?.render()}")
            on = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(4.0.mm), null, listOf(e), cs!!)))
            at += 1
        }
        val es2 = edgesOf(Evaluator().solid(on))
        val mitre =
            assertNotNull(
                es2.indices.firstOrNull { es2[it].name is EdgeName.BlendMitre && es2[it].reason == null && es2[it].geom is EdgeGeom.OnPlane },
                "the two bands cross in a mitre",
            )
        val choice = assertNotNull(Blend3.choicesFor(Evaluator().solid(on), listOf(mitre), BlendSection(BlendKind.CHAMFER, setback)).first, "the mitre is scored")[0]
        sb.append("param \"sc\" = ${setback}mm\n")
        sb.append("tool chamferedge els=e$at clicks=0,0 scalar=\"sc\" signs=$mitre;${choice.signs().joinToString(";")} -> e${at + 1},e${at + 2}\n")
        return sb.toString()
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(ed: Editor): Solid3 = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)

    private fun level(
        s: Solid3,
        z: Double,
        what: String,
    ): Double {
        val (regions, why) = Section3.regionsOf(s.feature, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        return regionArea(assertNotNull(regions, "$what: the level section at z = $z closes: ${why?.render()}"))
    }
}
