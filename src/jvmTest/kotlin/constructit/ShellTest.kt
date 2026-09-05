package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoopRef
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Shell3
import constructit.geom.SolidFace
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **`Feature3.Shell` — the wall, stated** (session 75): a body hollowed to a thickness, whose faces are still
 * its own.
 *
 * The queue entry's own words are the acceptance: *"a real member is hollow — 0.8–15% of its bounding box —
 * and the only route today is subtracting a hand-built inner solid"* (session 37). What is asserted here is
 * not that a cavity appeared but that the hollow body is an ordinary member of this kernel: the volume is the
 * exact figure where the boolean is exact, `assertManifold` on every body, the face list **extends** the
 * base's with the inner twin of every face at a stated offset, the open face becomes the rim it really is, a
 * working plane's section shows **both** walls, and everything outside the constant-offset tier refuses by
 * name with the route that does work.
 */
class ShellTest {
    /** Which face of the cup's own profile is the disc at the top — see [Construction.cupBody]. */
    private val TOP_OF_CUP = 2

    // ---- fixtures ----

    private fun Construction.rectLoop(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): LoopRef {
        val p0 = freePoint("${tag}0", x0.mm, y0.mm)
        val p1 = freePoint("${tag}1", x1.mm, y0.mm)
        val p2 = freePoint("${tag}2", x1.mm, y1.mm)
        val p3 = freePoint("${tag}3", x0.mm, y1.mm)
        return loop(segment(p0, p1), segment(p1, p2), segment(p2, p3), segment(p3, p0))
    }

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef = region(rectLoop(x0, y0, x1, y1, tag))

    /** The plainest body with a cap to open: a 40 x 30 plate, 20 deep. */
    private fun Construction.plate(): SolidRef = extrude(sketchOn(planeXY(), rect(0.0, 0.0, 40.0, 30.0, "p")), parameter("depth", 20.mm))

    /**
     * A turned cup's own body: a 25 x 40 rectangle standing on the axis, revolved the whole way round — so the
     * profile's fourth piece *is* the axis and sweeps nothing at all, which is the case the offset has to leave
     * alone (a wall there would be a hole down the middle).
     */
    private fun Construction.cupBody(): SolidRef {
        val o = freePoint("axisO", 0.mm, 0.mm)
        val axis = direction(o, freePoint("axisY", 0.mm, 1.mm))
        return revolveFull(sketchOn(planeXY(), rect(0.0, 0.0, 25.0, 40.0, "c")), o, axis)
    }

    private fun volume(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Double {
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    private fun why(
        ev: Evaluator,
        ref: SolidRef,
    ): String = assertNotNull((ev.eval(ref.node) as? EvalResult.Invalid)?.reason, "an invalid node with a reason")

    private fun faces(
        ev: Evaluator,
        ref: SolidRef,
    ) = assertNotNull(Section3.faces(ev.solid(ref).feature).first, "the body names its faces")

    // ---- the exact volumes: an extruded plate, open and closed ----

    /**
     * **A 40 x 30 x 20 plate shelled to 3 mm with its top open**, and the figure is exact: the cavity is the
     * footprint eroded to 34 x 24 standing from z = 3 to the top, so the material is
     * `40·30·20 − 34·24·17`.
     *
     * Exact and not within a band, and the reason is the dispatch: both the plate and its cavity are prisms on
     * one axis, so *the exact slab algebra* runs (OP-22) and no triangle of a curve is anywhere near it. The
     * tolerance is the boolean's own — the region kernel works to `GeomMath.TESS_TOL_MM` on curves and to the
     * last bits on straight ones — hence 1e-6 mm³ on a body of 24000.
     */
    @Test
    fun aPlateShelledWithItsTopOpenHasTheExactVolume() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val faces = faces(ev, plate)
        val top = faces.indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        assertClose(volume(ev, plate, "the plate"), 40.0 * 30.0 * 20.0, 1e-6)
        assertClose(
            volume(ev, shell, "the shelled plate"),
            40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 17.0,
            1e-6,
            "the wall, the floor and nothing else",
        )
    }

    /** **The same plate closed all round**: the cavity is inset from both caps, so it is 34 x 24 x 14. */
    @Test
    fun aClosedShellInsetsBothCaps() {
        val cx = Construction()
        val ev = Evaluator()
        val shell = cx.shell(cx.plate(), cx.const(3.mm), emptyList())
        assertClose(
            volume(ev, shell, "the closed shell"),
            40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 14.0,
            1e-6,
            "a closed hollow body is a legal shell",
        )
    }

    /**
     * **The opening is generic over faces, not a cap special case**: opening a *side* face leaves a window in
     * that wall, because the piece it stands over simply keeps its own carrier while its neighbours step in.
     *
     * The figure is exact and says where the material went: the cavity is 34 x 27 (the three offset pieces, the
     * clicked one where it was) standing between the two inset caps, so it is `40·30·20 − 34·27·14`. And the
     * opened wall itself becomes a **frame** — its own rectangle with the cavity's as a hole, which is
     * 34 x 14, the window the cavity comes out through.
     */
    @Test
    fun openingASideFaceLeavesAWindowInThatWall() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val shell = cx.shell(plate, cx.const(3.mm), listOf(0))
        assertClose(
            volume(ev, shell, "a plate with one wall opened"),
            40.0 * 30.0 * 20.0 - 34.0 * 27.0 * 14.0,
            1e-6,
            "the cavity reaches the surface through the wall it was told to open",
        )
        val wall = faces(ev, shell)[0]
        assertNull(wall.reason, "the opened wall is still a face you can sketch on")
        assertEquals(8, wall.outline.size, "its own rectangle, and the window as a hole")
        val hole = wall.outline.drop(4)
        val xs = hole.map { GeomMath.startOf(it).x }
        val ys = hole.map { GeomMath.startOf(it).y }
        assertClose(xs.max() - xs.min(), 34.0, 1e-9, "the window is as wide as the cavity")
        assertClose(ys.max() - ys.min(), 14.0, 1e-9, "and as tall as the space between the two caps")
    }

    /**
     * **Any number of faces may be open** — the argument is a list, and both caps open is a tube. Nothing about
     * the construction is per-case: two pieces keep their carriers instead of one.
     */
    @Test
    fun bothCapsMayBeOpenAndThatIsATube() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val base = faces(ev, plate)
        val caps =
            listOf(
                base.indexOfFirst { it.name == FaceName.Cap(SolidFace.BOTTOM) },
                base.indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) },
            )
        assertClose(
            volume(ev, cx.shell(plate, cx.const(3.mm), caps), "a tube"),
            40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 20.0,
            1e-6,
            "the cavity runs the whole depth, so what is left is four walls",
        )
    }

    /**
     * **A round wall stays round**, which is the claim the exact tier rests on: a circle of radius 20 extruded
     * 30 mm and shelled 2 mm with its top open is a tube whose bore is a *circle* of radius 18 — not a polygon
     * of the offset's own making — and the inner face says so in the surface vocabulary ([Surface3]).
     *
     * The figure is a band rather than exact for a reason that is not the shell's: the exact slab algebra
     * (OP-22) tessellates a curved boundary on its way in, so both cylinders reach the boolean as chord
     * polygons and the volume comes out **inscribed**. The offset itself is exact — the assertion on the bore's
     * radius is to 1e-9.
     */
    @Test
    fun aRoundWallStaysRound() {
        val cx = Construction()
        val ev = Evaluator()
        val disc = cx.region(cx.loop(cx.circleCR(cx.freePoint("c", 0.mm, 0.mm), cx.const(20.mm))))
        val rod = cx.extrude(cx.sketchOn(cx.planeXY(), disc), cx.const(30.mm))
        val base = faces(ev, rod)
        val top = base.indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(rod, cx.const(2.mm), listOf(top))
        val exact = PI * 400.0 * 30.0 - PI * 324.0 * 28.0
        val got = volume(ev, shell, "a tube with a floor")
        assertClose(got, exact, exact * 0.01, "the annular wall plus the floor")
        assertTrue(got <= exact, "chorded circles are inscribed, so the figure comes out under the closed form")
        val bore = assertNotNull(faces(ev, shell)[base.size].surface?.band as? Revolve3.Band.Cylinder, "the bore is a typed cylinder")
        assertClose(bore.r, 18.0, 1e-9, "one wall thickness in, and it is still a circle")
    }

    /**
     * **A hole in the profile is walled too, and it is walled by *growing*** — which is the whole reason the
     * offset needs no sign of its own: on a normalised region the outer ring runs counter-clockwise and a hole
     * runs clockwise (OP-14), so *"step every piece to its left"* erodes the one and grows the other, and both
     * mean the same wall thickness.
     *
     * A 40 x 30 plate with a 10 mm bore, shelled 3 mm and closed: the cavity is `34 x 24` less a bore grown to
     * radius 8, standing between the two inset caps. This is also the regression for the **index accounting
     * across loops** — the piece indices an open face is named by run outer loop first, then each hole, in
     * `Geom3.boundaryPieces`' own order.
     */
    @Test
    fun aHoleInTheProfileIsWalledByGrowingIt() {
        val cx = Construction()
        val ev = Evaluator()
        val bore = cx.loop(cx.circleCR(cx.freePoint("bc", 20.mm, 15.mm), cx.const(5.mm)))
        val plate = cx.extrude(cx.sketchOn(cx.planeXY(), cx.region(cx.rectLoop(0.0, 0.0, 40.0, 30.0, "h"), bore)), cx.const(20.mm))
        val base = faces(ev, plate)
        assertEquals(7, base.size, "four uprights, the bore's own wall, and the two caps")
        val shell = cx.shell(plate, cx.const(3.mm), emptyList())
        val exact = (1200.0 - PI * 25.0) * 20.0 - (34.0 * 24.0 - PI * 64.0) * 14.0
        val got = volume(ev, shell, "a bored plate, hollowed")
        assertClose(got, exact, exact * 0.01, "the outer wall, the bore's wall, and both plates")
        val inner = faces(ev, shell)[base.size + 4]
        val grown = assertNotNull(inner.surface?.band as? Revolve3.Band.Cylinder, "the bore's own inner face is a cylinder")
        assertClose(grown.r, 8.0, 1e-9, "the bore grew by one wall thickness")
    }

    /**
     * **A rounded corner offsets to a rounded corner of `R − t`** — the arc's own carrier, exactly — and a wall
     * **thicker than the corner** is refused as the *consumption* it is, with the thickest that fits named.
     *
     * Refused rather than answered with a sharp corner, and that is a decision: dropping the vanished arc would
     * change the profile's piece count, and the piece count *is* the face list's index space (OP-21). So the
     * shell declines and says what to type; dropping a consumed piece is a future extension.
     */
    @Test
    fun aRoundedCornerOffsetsToARoundedCornerAndAThickerWallSaysSo() {
        val cx = Construction()
        val ev = Evaluator()
        // a 40 x 30 plate with one corner rounded at R = 10: three runs, one arc, closed
        val a = cx.freePoint("r0", 0.mm, 0.mm)
        val b = cx.freePoint("r1", 30.mm, 0.mm)
        val c = cx.freePoint("r2", 40.mm, 10.mm)
        val d = cx.freePoint("r3", 40.mm, 30.mm)
        val e = cx.freePoint("r4", 0.mm, 30.mm)
        val corner = cx.freePoint("rc", 30.mm, 10.mm)
        val round =
            cx.region(
                cx.loop(
                    cx.segment(a, b),
                    cx.arc(corner, cx.const(10.mm), cx.const((-90.0).deg), cx.const(0.0.deg)),
                    cx.segment(c, d),
                    cx.segment(d, e),
                    cx.segment(e, a),
                ),
            )
        // 60 deep on purpose, so that at 12 mm it is the **corner** that binds and not the two caps meeting
        val plate = cx.extrude(cx.sketchOn(cx.planeXY(), round), cx.const(60.mm))
        val shell = cx.shell(plate, cx.const(3.mm), emptyList())
        val body = ev.solid(shell)
        assertManifold(body.mesh, "a plate with a rounded corner, hollowed")
        val inner = faces(ev, shell)
        val base = faces(ev, plate)
        val bore = assertNotNull(inner[base.size + 1].surface?.band as? Revolve3.Band.Cylinder, "the corner's inner face is a cylinder")
        assertClose(bore.r, 7.0, 1e-9, "the corner rounds to R − t, exactly")

        val tooThick = cx.shell(plate, cx.const(12.mm), emptyList())
        val said = why(Evaluator(), tooThick)
        assertTrue(said.contains("consumes one piece of this profile"), "a wall thicker than the corner eats it: $said")
        assertTrue(said.contains("the thickest wall that fits is about 10"), "and it names the number to type: $said")
    }

    // ---- the face list: the base's, extended ----

    /**
     * **The face list extends the base's, and the mapping outer→inner is arithmetic** (OP-21's index
     * stability): face `i` of the plate is face `i` of the shell, and its inner twin is face `n + i`.
     *
     * The open cap keeps its index and becomes the **rim** — its own outline plus the cavity's boundary as a
     * hole, which is what the body actually has there — and the *twin* of that open face is where the reason
     * lives, because there is no wall behind an opening.
     */
    @Test
    fun theFaceListExtendsTheBasesWithAnInnerTwinPerFace() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val base = faces(ev, plate)
        val top = base.indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        val shelled = faces(ev, shell)
        assertEquals(2 * base.size, shelled.size, "one inner twin per face of the base")
        for (i in base.indices) {
            if (i != top) assertEquals(base[i], shelled[i], "face #${i + 1} of the base is face #${i + 1} of the shell")
            assertEquals(FaceName.ShellInner(i), shelled[base.size + i].name, "the inner twin of face #${i + 1} stands at n + i")
        }

        // the rim: the same plane, one more ring
        val rim = shelled[top]
        assertEquals(base[top].plane, rim.plane, "hollowing a body moves no face")
        assertNull(rim.reason, "the rim is a face you can sketch on")
        assertEquals(base[top].outline.size + 4, rim.outline.size, "the cavity's own boundary, as a hole in the rim")

        // …and the twin of the open face is the one entry that says why it is not a face
        val twin = shelled[base.size + top]
        assertNull(twin.plane)
        assertTrue(assertNotNull(twin.reason).contains("is open, so there is no wall behind it"), "${twin.reason ?: ""}")

        // the pocket floor is the inner twin of the bottom cap, a plane 3 mm up whose normal points into the cavity
        val bottom = base.indexOfFirst { it.name == FaceName.Cap(SolidFace.BOTTOM) }
        val floor = assertNotNull(shelled[base.size + bottom].plane, "the pocket floor is a plane")
        assertClose(floor.origin.z, 3.0, 1e-9, "one wall thickness up from the outside")
        assertClose(floor.normal.normalized().z, 1.0, 1e-9, "and its normal points out of the wall, into the cavity")
    }

    /**
     * **The inner faces are reachable by the address space that already reached a cap** — no new machinery and
     * no format change ([Section3.FACE_ADDRESS_CONVENTION]): the base's own ends keep their addresses, the
     * inner faces take the ones after them, and every one of those was a refusal before, so nothing a file
     * stores changes meaning (OP-18).
     */
    @Test
    fun anInnerFaceHasAnAddressASketchCanBeStoredAt() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val base = faces(ev, plate)
        val top = base.indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        val feature = ev.solid(shell).feature
        val n = Geom3.boundaryPieces(feature).size
        assertEquals(4, n, "the plate's four uprights")

        // the base's caps keep the addresses they had
        assertEquals(n, Section3.addressOfFace(feature, FaceName.Cap(SolidFace.BOTTOM)))
        assertEquals(n + 1, Section3.addressOfFace(feature, FaceName.Cap(SolidFace.TOP)))
        val bottom = base.indexOfFirst { it.name == FaceName.Cap(SolidFace.BOTTOM) }
        val floorAddress = assertNotNull(Section3.addressOfFace(feature, FaceName.ShellInner(bottom)), "the pocket floor is addressed")
        assertTrue(floorAddress >= n + 2, "past the base's own ends: $floorAddress")
        val (patch, whyPatch) = Section3.facePatchOfFootprintPiece(feature, floorAddress)
        assertNotNull(patch, whyPatch?.render())
        assertEquals(FaceName.ShellInner(bottom), patch.name, "and that address is the pocket floor")
        assertClose(assertNotNull(patch.plane).origin.z, 3.0, 1e-9)
        // …and the count agrees, so a reader can walk the whole space: the four uprights, the base's two caps,
        // and one entry per inner twin (none of which stands over a footprint piece)
        assertEquals(n + 2 + base.size, Section3.faceAddressCount(feature), "sides plus every flat end")
    }

    /**
     * **A shelled body's outer face still answers a 3D pick** — the session-74 seam, consumed by a body it
     * knew nothing about: a ray's hit point is resolved against the *feature's* own face list, so the answer is
     * the face and its stored address, and no triangle's identity is involved.
     */
    @Test
    fun aShelledBodyAnswersAFacePickInSpace() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val top = faces(ev, plate).indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        val body = ev.solid(shell)
        val sag = Geom3.meshSag(body.mesh)

        // straight down onto the rim, 1 mm in from the long edge — that is the wall's top, not the floor
        val (rim, whyRim) = Section3.faceAt(body.feature, Vec3(20.0, 1.0, 20.0), Vec3(0.0, 0.0, -1.0), sag)
        assertNotNull(rim, whyRim?.render())
        assertEquals(FaceName.Cap(SolidFace.TOP), rim.patch.name, "the rim keeps the top cap's own name and address")
        assertEquals(Section3.addressOfFace(body.feature, FaceName.Cap(SolidFace.TOP)), rim.piece)

        // …and the middle of that same cap is now the pocket floor, 3 mm up
        val (floor, whyFloor) = Section3.faceAt(body.feature, Vec3(20.0, 15.0, 3.0), Vec3(0.0, 0.0, -1.0), sag)
        assertNotNull(floor, whyFloor?.render())
        assertTrue(floor.patch.name is FaceName.ShellInner, "the ray reaches the inside: ${floor.patch.name.label}")
        assertNotNull(floor.piece, "and it has an address a sketch can be stored at")
    }

    /**
     * **A working plane's section of a hollow body shows both walls.** A vertical plane down the middle of the
     * shelled plate crosses four uprights — the outer wall twice and the inner wall twice — plus the floor, and
     * every one of those pieces is a *named* face's exact cut rather than a chord of the mesh.
     */
    @Test
    fun aSectionOfAShelledBodyShowsBothWalls() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val top = faces(ev, plate).indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        val cut = Plane3(Vec3(0.0, 15.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val section = Section3.sectionOf(ev.solid(shell), cut)
        assertTrue(!section.approximated, "a shelled plate's section is exact, not chords")
        val named = section.edges.filter { it.curve != null }
        assertTrue(named.size >= 5, "both walls and the floor are named: ${named.map { it.provenance }}")
        assertTrue(named.any { it.provenance.contains("the inner face behind") }, "the inner wall is among them: ${named.map { it.provenance }}")
        // the two uprights of the inner wall stand 3 mm in from the outer ones
        val xs = section.drawn.flatMap { listOf(GeomMath.startOf(it).x, GeomMath.endOf(it).x) }.sorted()
        assertTrue(xs.any { abs(it - 3.0) < 1e-6 } && xs.any { abs(it - 37.0) < 1e-6 }, "the cavity's own walls: $xs")
    }

    // ---- the turned cup ----

    /**
     * **A revolved cup**: the 25 x 40 rectangle revolved the whole way round and shelled 2 mm with its top
     * face open. The closed form is the annular wall plus the floor —
     * `π·25²·40 − π·23²·38` — and the figure is a **band** rather than exact, because the operands here have
     * no common axis: the cavity is taken out by the general engine, so both bodies reach it as chorded
     * cylinders and the volume is *inscribed*. The band is 1% and the direction is asserted with it.
     *
     * The piece of the profile lying **on the axis** is the case that had to be got right: it sweeps no face at
     * all, so the offset leaves it alone — a wall there would have bored a hole down the middle of the cup.
     */
    @Test
    fun aRevolvedCupHasAnnularWallsAndAFloor() {
        val cx = Construction()
        val ev = Evaluator()
        val cup = cx.cupBody()
        // the profile runs (0,0) → (25,0) → (25,40) → (0,40) → back down the axis, so its **third** piece is
        // the disc at the top: the face a cup is open at
        val faces = faces(ev, cup)
        assertEquals(FaceName.Side(TOP_OF_CUP), faces[TOP_OF_CUP].name)
        assertClose(assertNotNull(faces[TOP_OF_CUP].plane).origin.y, 40.0, 1e-9, "and it stands 40 mm along the axis")
        val shell = cx.shell(cup, cx.const(2.mm), listOf(TOP_OF_CUP))
        val solid = volume(ev, cup, "the turned blank")
        val hollow = volume(ev, shell, "the cup")
        val exact = PI * 25.0 * 25.0 * 40.0 - PI * 23.0 * 23.0 * 38.0
        assertTrue(hollow < solid, "the cavity takes material out: $hollow vs $solid")
        assertClose(hollow, exact, exact * 0.01, "the annular wall plus the floor")
        assertTrue(hollow <= exact, "a chorded cylinder is inscribed, so the figure comes out under the closed form")
    }

    /**
     * **The cavity's cylinder is typed** ([Surface3] on the inner face list): the inner wall of the cup is a
     * cylinder of radius `R − t`, measured off the feature rather than asserted about it — which is what makes
     * an inner face a face a blend or a section can be a function of.
     */
    @Test
    fun theCupsInnerWallIsATypedCylinder() {
        val cx = Construction()
        val ev = Evaluator()
        val cup = cx.cupBody()
        val shell = cx.shell(cup, cx.const(2.mm), listOf(TOP_OF_CUP))
        val shelled = faces(ev, shell)
        val base = faces(ev, cup)
        // the profile's second piece is the outer wall at r = 25; its inner twin is the cavity's own cylinder
        val outer = assertNotNull(base[1].surface?.band as? Revolve3.Band.Cylinder, "the blank's wall is a cylinder")
        assertClose(outer.r, 25.0, 1e-9)
        val inner = assertNotNull(shelled[base.size + 1].surface?.band as? Revolve3.Band.Cylinder, "and so is the cavity's")
        assertClose(inner.r, 23.0, 1e-9, "one wall thickness in")
        assertClose(inner.s1 - inner.s0, 38.0, 1e-9, "from the floor to the open top")
    }

    // ---- honesty: the thickness that fits, and everything outside the tier ----

    /**
     * **A thickness the body cannot host refuses by naming the thickness that fits**, and it heals the moment a
     * smaller one is typed (OP-3) — the blends' halving search, one feature over.
     */
    @Test
    fun tooThickAWallNamesTheThickestThatFits() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val t = cx.parameter("wall", 20.mm)
        val shell = cx.shell(plate, t, emptyList())
        val said = why(ev, shell)
        assertTrue(said.contains("the thickest wall that fits is about"), "it names the number to type: $said")
        val fits = said.substringAfter("fits is about ").substringBefore(" mm").toDouble()
        // half the plate's own **depth** is what binds here, not its footprint: a closed shell insets both
        // caps, so at 10 mm the floor and the ceiling meet while the 30 mm footprint would still take 15
        assertTrue(fits > 9.9 && fits <= 10.0, "the caps meet at 10 mm, so that is the wall that fits: $fits")

        cx.set(t, 3.mm)
        assertClose(
            volume(Evaluator(), shell, "the healed shell"),
            40.0 * 30.0 * 20.0 - 34.0 * 24.0 * 14.0,
            1e-6,
            "and the same node is a body again",
        )
    }

    /** A thickness of zero or less is not a wall, and the refusal says which number it read. */
    @Test
    fun aWallNeedsAPositiveThickness() {
        val cx = Construction()
        val shell = cx.shell(cx.plate(), cx.const(0.mm), emptyList())
        assertTrue(why(Evaluator(), shell).contains("needs a positive wall thickness"), "the reason names it")
    }

    /**
     * **A partial revolve refuses by name, and the reason is a wall thickness rather than a missing case**: the
     * inset from a radial cap is an *angular* one, whose wall grows thicker with the radius, so it would not be
     * the number anybody typed.
     */
    @Test
    fun aPartialRevolveRefusesByName() {
        val cx = Construction()
        val o = cx.freePoint("axisO", 0.mm, 0.mm)
        val axis = cx.direction(o, cx.freePoint("axisX", 1.mm, 0.mm))
        val bar = cx.revolve(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 15.0, 60.0, 25.0, "b")), o, axis, cx.parameter("sweep", 90.deg))
        val shell = cx.shell(bar, cx.const(2.mm), emptyList())
        val said = why(Evaluator(), shell)
        assertTrue(said.contains("held off the two radial caps by an *angle*"), said)
        assertTrue(said.contains("future extension"), "and it is parked by name: $said")
    }

    /** Every kind outside the constant-offset tier refuses **in its own words**, each naming a route. */
    @Test
    fun everyKindOutsideTheTierRefusesInItsOwnWords() {
        val cx = Construction()
        val plate = cx.plate()
        val pad = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(35.0, 5.0, 60.0, 25.0, "q")), cx.const(20.mm))
        val fused = cx.union(plate, pad)
        val said = why(Evaluator(), cx.shell(fused, cx.const(2.mm), emptyList()))
        assertTrue(said.contains("stack of slabs from the exact boolean algebra"), said)
        assertTrue(said.contains("shell the body first"), "and it says what to do instead: $said")

        val shelled = cx.shell(plate, cx.const(2.mm), emptyList())
        val twice = why(Evaluator(), cx.shell(shelled, cx.const(2.mm), emptyList()))
        assertTrue(twice.contains("already a shell"), twice)
    }

    /**
     * **The shelled edge list extends the base's too**, and no base edge is consumed: hollowing takes material
     * from behind the faces, so every outer crease is exactly where it was and the cavity's own creases append.
     * The adjacency of the rim's inner boundary names the **rim** rather than a face that is not there.
     */
    @Test
    fun theEdgeListExtendsTheBasesAndNoEdgeIsConsumed() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val top = faces(ev, plate).indexOfFirst { it.name == FaceName.Cap(SolidFace.TOP) }
        val shell = cx.shell(plate, cx.const(3.mm), listOf(top))
        val baseEdges = assertNotNull(Section3.edges(ev.solid(plate).feature).first)
        val edges = assertNotNull(Section3.edges(ev.solid(shell).feature).first)
        assertEquals(2 * baseEdges.size, edges.size, "the base's edges, then the cavity's")
        for (i in baseEdges.indices) assertEquals(baseEdges[i], edges[i], "edge #${i + 1} keeps its index and its carrier")
        assertTrue(edges.none { it.reason != null }, "a shell consumes no edge")
        val rimInner =
            edges.drop(baseEdges.size).filter { e ->
                e.between.has(FaceName.Cap(SolidFace.TOP))
            }
        assertTrue(rimInner.isNotEmpty(), "the rim's own inner boundary is an edge of this body")
    }

    /** A face that sweeps nothing — a profile edge on the axis — cannot be the opening, and says so. */
    @Test
    fun aFaceThatSweepsNothingCannotBeOpened() {
        val cx = Construction()
        val ev = Evaluator()
        val cup = cx.cupBody()
        val onAxis = faces(ev, cup).indexOfFirst { it.reason?.contains("lies on the axis of revolution") == true }
        assertTrue(onAxis >= 0, "the cup's profile has a piece on the axis")
        val said = assertNotNull(Shell3.openFaceRefusal(ev.solid(cup).feature, onAxis))
        assertTrue(said.contains("sweeps no surface at all"), "$said")
        assertTrue(assertNotNull(Shell3.openFaceRefusal(ev.solid(cup).feature, 99)).contains("has no face #100"), "and an index past the list")
    }

    /** A shelled body is still the kind of body it was, for every question that is not about its faces. */
    @Test
    fun aShelledRevolveIsStillARevolveUndressed() {
        val cx = Construction()
        val ev = Evaluator()
        val shell = cx.shell(cx.cupBody(), cx.const(2.mm), listOf(TOP_OF_CUP))
        assertTrue(Section3.undressed(ev.solid(shell).feature) is Feature3.Revolution, "a shell is a dressing")
    }

    /** The plan a hollow body draws and is picked by is its base's own — analytic, and it forces no mesh. */
    @Test
    fun theShellDrawsItsBasesPlan() {
        val cx = Construction()
        val ev = Evaluator()
        val plate = cx.plate()
        val shell = cx.shell(plate, cx.const(3.mm), emptyList())
        assertEquals(ev.solid(plate).feature.footprint, ev.solid(shell).feature.footprint, "the same plan hint")
    }
}
