package constructit

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Edge3
import constructit.editor.Painter3
import constructit.editor.Scene3
import constructit.geom.Circle
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Sketch3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Feature edges** (GitHub issue #3): one headlight cannot tell two coplanar faces apart, so a pocket's
 * floor shades exactly like the surface it was cut into and the pocket's contour vanishes. The view draws
 * the *creases* of the mesh instead, and this is what "crease" is allowed to mean.
 *
 * The threshold is the whole design, and it is pinned from both sides here: it must catch every machined
 * edge (a 90° rim), and it must **not** catch the seams of a tessellated curved surface, which differ only
 * by the chord step. Both directions are asserted, the second one with its margin stated numerically —
 * otherwise a later change to `TESS_TOL_MM` or to the threshold would silently start drawing a barrel of
 * lines down every bore.
 */
class CreaseEdgeTest {
    private val plane = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)

    private fun box(
        w: Double = 40.0,
        d: Double = 25.0,
        h: Double = 14.0,
    ) = Geom3.extrude(Sketch3(plane, listOf(Viewport3Test.rectRegion(0.0, 0.0, w, d))), h).first!!

    private fun cylinder(
        r: Double,
        h: Double,
    ): constructit.geom.Solid3 {
        val loop = Loop(listOf(ProfileElement.CircleE(Circle(Vec2(0.0, 0.0), r), ccw = true)))
        return Geom3.extrude(Sketch3(plane, listOf(Region(loop, emptyList()))), h).first!!
    }

    /** The angle between two neighbouring facets of a tessellated circle of [r] — the chord step. */
    private fun chordStepRad(r: Double): Double {
        val steps = GeomMath.chordSteps(r, 2.0 * PI, GeomMath.TESS_TOL_MM)
        return 2.0 * PI / steps
    }

    @Test
    fun aBoxYieldsExactlyItsTwelveEdges() {
        val mesh = box().mesh
        assertManifold(mesh, "box")
        val edges = Scene3.creaseEdges(mesh)
        assertEquals(12, edges.size, "a box has twelve edges and nothing else: ${edges.size} found")
        // each cap is triangulated, so the diagonals across the faces are the tempting false positives
        for (e in edges) {
            val d = e.b - e.a
            val axes = listOf(abs(d.x), abs(d.y), abs(d.z)).count { it > 1e-9 }
            assertEquals(1, axes, "a box edge runs along one axis, not across a face: ${e.a} -> ${e.b}")
        }
        // and each one is a right angle, comfortably past the threshold
        assertTrue(Scene3.CREASE_ANGLE_RAD < PI / 2.0, "the threshold must admit a 90° crease")
    }

    /**
     * A cylinder: the two rims are creases (cap meets wall at 90°), and the wall's own verticals are not —
     * the facets either side of them differ by the chord step alone.
     */
    @Test
    fun aCylinderYieldsItsTwoRimsAndNoWallVerticals() {
        val r = 10.0
        val h = 20.0
        val mesh = cylinder(r, h).mesh
        assertManifold(mesh, "cylinder")
        val step = chordStepRad(r)
        assertClose(step, 2.0 * PI / 50.0, 1e-9, "50 chords round a 10 mm bore at TESS_TOL_MM = 7.2 deg each")
        assertTrue(
            step < Scene3.CREASE_ANGLE_RAD,
            "a 10 mm bore's facets ($step rad) must stay under the threshold (${Scene3.CREASE_ANGLE_RAD} rad)",
        )
        assertClose(Scene3.CREASE_ANGLE_RAD / step, 4.17, 0.01, "and with a stated margin, not by a hair")

        val edges = Scene3.creaseEdges(mesh)
        val steps = GeomMath.chordSteps(r, 2.0 * PI, GeomMath.TESS_TOL_MM)
        assertEquals(2 * steps, edges.size, "one crease per rim chord, two rims — and nothing down the wall")
        for (e in edges) {
            assertTrue(
                abs(e.a.z - e.b.z) <= 1e-9,
                "every crease lies in a cap plane; a vertical wall seam is tessellation, not shape: ${e.a} -> ${e.b}",
            )
            assertTrue(
                abs(e.a.z) <= 1e-9 || abs(e.a.z - h) <= 1e-9,
                "and it lies on one of the two rims: ${e.a} -> ${e.b}",
            )
        }
        assertEquals(steps, edges.count { abs(it.a.z) <= 1e-9 }, "half of them on the bottom rim")
    }

    /**
     * The margin is a *formula*, not a lucky pair of numbers: a threshold `t` is clean for every radius above
     * `tol / (1 - cos(t/2))`. Asserting the inversion keeps the table in `Scene3.CREASE_ANGLE_RAD`'s comment
     * honest, and states where the limit actually bites — a sub-millimetre bore, not a 1 mm fillet.
     */
    @Test
    fun theCleanRadiusFollowsFromTheThresholdAndTheTessellationTolerance() {
        val limit = GeomMath.TESS_TOL_MM / (1.0 - cos(Scene3.CREASE_ANGLE_RAD / 2.0))
        assertClose(limit, 0.587, 1e-3, "at 30° every curved surface above ~0.59 mm radius stays clean")
        // just above the limit: clean. Just below: the facets are drawn — the honest cost, and it is tiny.
        assertTrue(chordStepRad(0.7) < Scene3.CREASE_ANGLE_RAD, "a 0.7 mm radius is still quiet")
        assertTrue(chordStepRad(0.4) > Scene3.CREASE_ANGLE_RAD, "below the limit the facets do show")
        // the everyday fillet sizes this threshold exists to protect
        for (fillet in listOf(1.0, 2.0, 5.0, 10.0)) {
            assertTrue(
                chordStepRad(fillet) < Scene3.CREASE_ANGLE_RAD,
                "a $fillet mm fillet must not be speckled (step ${chordStepRad(fillet)} rad)",
            )
        }
        // 20° would have failed exactly there, which is why the constant is 30° — recorded as a test, not
        // only as a comment, so the number cannot be lowered by accident.
        val twenty = 20.0 * PI / 180.0
        assertTrue(chordStepRad(1.0) > twenty, "at 20 deg a 1 mm fillet would be drawn as tessellation")
        assertTrue(chordStepRad(1.0) < Scene3.CREASE_ANGLE_RAD, "at 30 deg it is quiet")
    }

    /** A tangency seam is not a crease: where a fillet meets the flat it runs into, nothing is drawn. */
    @Test
    fun aFilletsTangencySeamsAreNotCreases() {
        // a 40x25 plate whose four corners are 6 mm arcs: the profile is closed, tangent at every join
        val r = 6.0
        val region = roundedRect(0.0, 0.0, 40.0, 25.0, r)
        val mesh = Geom3.extrude(Sketch3(plane, listOf(region)), 10.0).first!!.mesh
        assertManifold(mesh, "rounded plate")
        val edges = Scene3.creaseEdges(mesh)
        // The walls are one smooth band all the way round: no vertical crease anywhere, neither inside an
        // arc (tessellation) nor at a tangency (the fillet's whole point).
        for (e in edges) {
            assertTrue(
                abs(e.a.z - e.b.z) <= 1e-9,
                "a filleted wall has no vertical creases at all — found ${e.a} -> ${e.b}",
            )
        }
        // what remains is the two rims, unbroken
        assertEquals(edges.size / 2, edges.count { abs(it.a.z) <= 1e-9 }, "the rims come in equal halves")
        val tangencySeams =
            edges.count { e ->
                abs(e.a.z - e.b.z) > 1e-9
            }
        assertEquals(0, tangencySeams)
    }

    /** Determinism: same mesh, same list, in the same order — a scene is recomputed on every repaint. */
    @Test
    fun extractionIsDeterministic() {
        val mesh = box().mesh
        val a = Scene3.creaseEdges(mesh)
        val b = Scene3.creaseEdges(mesh)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].a, b[i].a, "edge $i starts at the same point")
            assertEquals(a[i].b, b[i].b, "edge $i ends at the same point")
        }
    }

    /** An empty or degenerate mesh has no creases and does not throw on the way to saying so. */
    @Test
    fun aMeshWithNothingInItHasNoCreases() {
        assertEquals(0, Scene3.creaseEdges(constructit.geom.Mesh3(emptyList(), emptyList())).size)
    }

    // ---- the issue's own fixture ----

    /**
     * The drawing from issue #3: a filleted plate extruded 80 mm, with a filleted 5 mm pocket cut into one of
     * its *side faces* through a sketch space on that face. The face is at y = 39.75, the pocket floor 5 mm
     * behind it at y = 34.75.
     *
     * First the symptom, asserted where it lives: the floor's normal and the surrounding face's normal are
     * **the same vector**, so one headlight gives them the same diffuse term and both back ends must paint
     * them the identical colour — the contour cannot come from shading, whatever the light does. Then the fix:
     * the pocket's rim is a *closed* run of creases in the face plane, and its floor is outlined against the
     * pocket wall, both there for every camera because neither depends on one.
     */
    @Test
    fun theIssuesPocketShowsItsRim() {
        val doc = DocumentFormat.load(POCKET)
        val ev = Evaluator()
        val solids = doc.elements.filter { ev.valueOf(it.ref) is SolidValue }
        val cut = (ev.valueOf(solids.last().ref) as SolidValue).solid
        assertManifold(cut.mesh, "the pocketed plate")

        // the symptom: floor and face shade identically, because they *are* parallel
        val faceY = 39.75
        val floorY = 34.75
        val nFace = assertNotNull(normalOfFaceAt(cut.mesh) { abs(it.y - faceY) <= 1e-6 }, "the cut face")
        val nFloor = assertNotNull(normalOfFaceAt(cut.mesh) { abs(it.y - floorY) <= 1e-6 }, "the pocket floor")
        assertClose(nFace.dot(nFloor), 1.0, 1e-9, "floor and face have one normal, hence one shade (issue #3)")
        // and the headlight law spells that out: same normal, same intensity, same colour string — from a
        // camera that sees straight into the pocket, which is exactly the perspective the report complains about
        val cam = Camera3(target = Vec3(-41.0, 12.0, 25.0), distance = 300.0, yaw = -1.0, pitch = 0.4)
        val light = cam.forward()

        fun litShade(n: Vec3) = Painter3.shade(Scene3.PALETTE[0], Painter3.AMBIENT + (1.0 - Painter3.AMBIENT) * abs(n.dot(light)))
        assertEquals(
            litShade(nFace),
            litShade(nFloor),
            "no light separates two parallel faces, so the contour has to come from somewhere else",
        )

        // the fix: a rim in the face plane, and a floor outline 5 mm behind it
        val plate = (ev.valueOf(solids.first().ref) as SolidValue).solid
        val before = Scene3.creaseEdges(plate.mesh)
        val after = Scene3.creaseEdges(cut.mesh)
        assertTrue(
            after.size > before.size + 8,
            "cutting a pocket must add its rim and its floor outline: ${before.size} -> ${after.size}",
        )
        val box = Geom3.bounds(cut.mesh)!!
        assertClose(box.second.y, faceY, 1e-9, "the fixture's cut face is the +y side of the plate")
        val rim = after.filter { inPlaneY(it, faceY) && withinPocket(it) }
        val floor = after.filter { inPlaneY(it, floorY) }
        assertTrue(rim.size >= 8, "the pocket's rim is drawn as creases in the cut face: ${rim.size}")
        assertEquals(rim.size, floor.size, "a prismatic pocket's rim and floor outline have the same chords")
        assertClosedLoop(rim, "rim")
        assertClosedLoop(floor, "floor")
        // Down the pocket's walls there is exactly what the sketch put there and nothing else: the fixture
        // filleted two of the four corners (r = 5 mm) and left two sharp, so two corner creases run the
        // pocket's depth — and the two filleted corners contribute none, which is the threshold doing its job.
        val walls = after.filter { withinPocket(it) && !inPlaneY(it, faceY) && !inPlaneY(it, floorY) }
        assertEquals(2, walls.size, "two sharp corners, two corner creases: ${walls.map { it.a }}")
        for (w in walls) {
            assertClose(abs(w.a.y - w.b.y), 5.0, 1e-3, "a corner crease runs the pocket's 5 mm depth")
            assertClose(abs(w.a.x - w.b.x), 0.0, 1e-3, "straight down the corner in x")
            assertClose(abs(w.a.z - w.b.z), 0.0, 1e-3, "straight down the corner in z")
        }
        assertTrue(chordStepRad(5.0) < Scene3.CREASE_ANGLE_RAD, "which is the r=5 fillet staying under the threshold")
    }

    private fun inPlaneY(
        e: Edge3,
        y: Double,
    ) = abs(e.a.y - y) <= 1e-6 && abs(e.b.y - y) <= 1e-6

    /** The pocket's own footprint on the cut face, from the fixture's sketch — x and z of the tool solid. */
    private fun withinPocket(e: Edge3): Boolean {
        fun inside(p: Vec3) = p.x in -63.0..-44.0 && p.z in 18.5..32.5
        return inside(e.a) && inside(e.b)
    }

    /** The unit normal of some triangle all of whose corners satisfy [where] — a probe onto one flat face. */
    private fun normalOfFaceAt(
        mesh: constructit.geom.Mesh3,
        where: (Vec3) -> Boolean,
    ): Vec3? {
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val b = mesh.vertices[t.b]
            val c = mesh.vertices[t.c]
            if (where(a) && where(b) && where(c)) return (b - a).cross(c - a).normalized()
        }
        return null
    }

    /** Every vertex of a closed run of edges is met exactly twice — the contour goes all the way round. */
    private fun assertClosedLoop(
        edges: List<Edge3>,
        what: String,
    ) {
        val used = HashMap<String, Int>()
        for (e in edges) {
            for (p in listOf(e.a, e.b)) used[key(p)] = (used[key(p)] ?: 0) + 1
        }
        assertTrue(
            used.values.all { it == 2 },
            "the $what closes on itself: ${used.values.groupingBy { it }.eachCount()}",
        )
    }

    private fun key(p: Vec3): String {
        fun q(v: Double) = (v * 1e6).toLong().toString()
        return "${q(p.x)},${q(p.y)},${q(p.z)}"
    }

    /** A rectangle with [r] arcs at its corners — segments and arcs joined tangentially all the way round. */
    private fun roundedRect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        r: Double,
    ): Region {
        val els = ArrayList<ProfileElement>()
        val centers =
            listOf(
                Vec2(x1 - r, y0 + r) to (-PI / 2.0),
                Vec2(x1 - r, y1 - r) to 0.0,
                Vec2(x0 + r, y1 - r) to (PI / 2.0),
                Vec2(x0 + r, y0 + r) to PI,
            )
        for ((i, cs) in centers.withIndex()) {
            val (c, start) = cs
            val arc = constructit.geom.Arc(c, r, start, start + PI / 2.0, ccw = true)
            val prev = centers[(i + 3) % 4]
            val from = constructit.geom.Arc(prev.first, r, prev.second, prev.second + PI / 2.0, ccw = true)
            els.add(ProfileElement.Seg(constructit.geom.Segment(GeomMath.arcEnd(from), GeomMath.arcStart(arc))))
            els.add(ProfileElement.ArcE(arc))
        }
        return Region(Loop(els), emptyList())
    }

    companion object {
        /**
         * The fixture from GitHub issue #3, verbatim: a filleted outline extruded 80 mm, then a filleted
         * pocket cut 5 mm into one of the resulting side faces through a sketch space on that face.
         */
        val POCKET =
            """
constructit 2
orthostart -72.5,-14.25 -> e1
orthovertex -72.5,39.75 -> e2,e3
orthovertex -9.75,39.75 -> e4,e5
orthovertex -9.75,10 -> e6,e7
orthovertex -35.75,10 -> e8,e9
orthovertex -35.75,-14.25 -> e10,e11
orthoclose -> e12
param "r" = 10mm
tool fillet els=e12,e11 clicks=-45.75,-14.75;-36.25,-3.5 scalar="r" signs=1;-1 -> e13
tool fillet els=e9,e7 clicks=-15,10;-0.75,21.5 scalar="r" signs=1;-1 -> e14
tool fillet els=e5,e7 clicks=-6.5,39.5;-0.5,32.5 scalar="r" signs=-1;1 -> e15
tool fillet els=e11,e9 clicks=-35.5,2.5;-28.25,10.5 scalar="r" signs=1;-1 -> e16
tool outline els=e3,e5,e15,e7,e14,e9,e16,e11,e13,e12 clicks=-73,14.25;-37.25,39.5;-2.9289321881345227,37.071067811865476;0.0000000000000017763568394002505,25;-2.9289321881345227,12.928932188134524;-17.875,10;-32.821067811865476,7.071067811865477;-35.75,-2.1249999999999996;-38.678932188134524,-11.321067811865476;-59.125,-14.25 -> e17,e18,e19,e20,e21,e22,e23,e24,e25,e26,e27
param "h" = 80mm
tool extrude els=e27 clicks=-51.5,40.5 scalar="h" -> e28
sketchspace "face1" el=e28 piece=8
orthostart -13.515804597701148,14.236453201970448 -> e29
point -1.5617816091953998,24.87684729064039 -> e30
point 6.319991789819376,33.80952380952381 -> e31
tool segment pts=e30,e31 clicks=-1.95587027914614,26.715927750410508;7.633620689655174,32.364532019704434 -> e32
point 16.69766009852217,25.533661740558287 -> e33
point 6.714080459770116,17.25779967159277 -> e34
tool segment pts=e33,e34 clicks=17.485837438423644,23.563218390804593;4.612274220032841,18.44006568144499 -> e35
tool segment pts=e34,e30 clicks=4.612274220032841,18.44006568144499;-1.8245073891625623,26.847290640394085 -> e36
tool segment pts=e31,e33 clicks=7.764983579638752,32.23316912972085;17.354474548440066,23.957307060755333 -> e37
param "f" = 5mm
tool fillet els=e32,e37 clicks=3.692733990147783,30.525451559934318;10.260878489326764,30.525451559934318 scalar="f" signs=-1;1 -> e38
tool fillet els=e36,e35 clicks=2.641830870279147,20.935960591133004;11.443144499178985,21.198686371100166 scalar="f" signs=1;-1 -> e39
tool outline els=e36,e39,e35,e37,e38,e32 clicks=0.9341133004926121,22.77504105090312;6.5827175697865385,19.228243021346465;13.387576162711113,22.78977637271491;13.368446652179472,28.188604109160693;6.485796607507531,31.915333630719516;0.8054126702285132,27.559667473987496 -> e40,e41,e42,e43,e44,e45,e46
param "d" = 5mm
tool cut els=e28,e46 clicks=1.0654761904761898,27.89819376026272 scalar="d" -> e47,e48
""".trimStart()
    }
}
