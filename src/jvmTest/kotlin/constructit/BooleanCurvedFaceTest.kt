package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.plane
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.BoolOp
import constructit.geom.EdgeGeom
import constructit.geom.FaceName
import constructit.geom.FacePatch
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.SolidEdge
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Watertight
import constructit.l10n.contains
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Curved faces through the general boolean** (OP-31, slice 5c) — the commonest mechanical bodies there are,
 * asserted as arithmetic.
 *
 * *What this class is about.* Item 4 kept a boolean's faces only where **every** operand face was a plane, so
 * one bored hole — *"the only way to create the base solid of such structure"* (GitHub #36's reporter) — and
 * the whole body went back to being a mesh: nothing to round, nothing to sketch on, nothing to section
 * exactly. A boolean never *moves* a surface, curved or not, so the rule is item 4's with one word struck
 * out: the result face keeps the operand's own **carrier** — the cylinder an extruded arc sweeps, the cone or
 * sphere a revolution sweeps, the torus a rounding's band is — and only its **trim** is emergent.
 *
 * *What is asserted, case by case.* A bored block: the bore is a cylinder by name, both rims are exact
 * circles, the faces it opens in carry those circles in their own outlines and their areas are `ab − πr²` to
 * the last digit, and a 2 mm rounding of a rim is an exact **torus** whose volume is Pappus' own figure. A
 * counterbore: two coaxial bores, an annular floor that is a plane and three circular creases. A pin through
 * a plate: one cylinder cut into two faces, each named, with a circular crease apiece. A pipe tee: two equal
 * cylinders crossing, whose crease this drawing has no name for — stated as a **fitted** chain through points
 * exact on both cylinders, within the tolerance it says it reached, and a rounding along it refused by name.
 * A turned part fused to a block: a cone's circle. An **imported** operand: still the sink OP-9 named.
 */
class BooleanCurvedFaceTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "no general boolean engine: ${MeshBool.status}")

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    /** A click in the 3D view **aimed at a point of the world** — which is what aiming at a face means. */
    private fun Viewport3.clickWorld(p: Vec3): Vec2 {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
        return s
    }

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = 1200.0, heightPx = 800.0)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val a = freePoint("$tag.a", x0.mm, y0.mm)
        val b = freePoint("$tag.b", x1.mm, y0.mm)
        val d = freePoint("$tag.c", x1.mm, y1.mm)
        val e = freePoint("$tag.d", x0.mm, y1.mm)
        return region(loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a)))
    }

    /** The block every fixture below is cut out of: 40 × 30 × 20 on the XY plane. */
    private fun Construction.block(tag: String = "b"): SolidRef = extrude(sketchOn(planeXY(), rect(0.0, 0.0, 40.0, 30.0, tag)), const(20.mm))

    /**
     * A drill along **+X**, of radius [r], on the axis `(y, z) = (cy, cz)`, running from `x = x0` for [depth].
     *
     * Along X and not along Z deliberately: a bore square to the block's own extrusion axis is the *exact*
     * slab algebra's case (OP-22) and never reaches the general engine at all, so it would say nothing about
     * this slice.
     */
    private fun Construction.drill(
        r: Double,
        cy: Double,
        cz: Double,
        x0: Double,
        depth: Double,
        tag: String,
    ): SolidRef {
        val c = freePoint("$tag.c", cy.mm, cz.mm)
        val circle = region(loop(circleCR(c, const(r.mm))))
        return extrude(sketchOn(plane(Vec3(x0, 0.0, 0.0), Vec3.Y, Vec3.Z), circle), const(depth.mm))
    }

    private fun built(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Solid3 {
        val solid = ev.solid(ref)
        assertManifold(solid.mesh, what)
        assertTrue(solid.feature is Feature3.MeshBoolean, "$what takes the general path, not ${solid.feature::class.simpleName}")
        return solid
    }

    private fun facesOf(
        feature: Feature3,
        what: String,
    ): List<FacePatch> {
        val (faces, why) = Section3.faces(feature)
        return assertNotNull(faces, "$what names its faces: ${why?.render()}")
    }

    private fun edgesOf(
        feature: Feature3,
        what: String,
    ): List<SolidEdge> {
        val (edges, why) = Section3.edges(feature)
        return assertNotNull(edges, "$what names its creases: ${why?.render()}")
    }

    /** The one face of [faces] whose carrier is a cylinder of radius [r] — asserted to be the only one. */
    private fun cylinders(
        faces: List<FacePatch>,
        r: Double,
    ): List<FacePatch> = faces.filter { (it.surface?.band as? Revolve3.Band.Cylinder)?.r?.let { q -> abs(q - r) < 1e-9 } == true }

    /** The area a face's own outline encloses, in its own frame — exact for straight and circular pieces. */
    private fun area(outline: List<ProfileElement>): Double {
        var a = 0.0
        for (e in outline) {
            when (e) {
                is ProfileElement.Seg -> a += e.segment.a.x * e.segment.b.y - e.segment.b.x * e.segment.a.y
                is ProfileElement.CircleE -> a += (if (e.ccw) 1.0 else -1.0) * 2.0 * PI * e.circle.radius * e.circle.radius
                else -> throw AssertionError("this fixture's faces are straight runs and whole circles, not $e")
            }
        }
        return a / 2.0
    }

    // ---- (a) a bored block ----

    /**
     * **A block with a bore through it**: the bore is a cylinder by name, its two rims are exact circles, and
     * the faces it opens in carry those circles in their own boundaries.
     */
    @Test
    fun aBoredBlockNamesItsBoreAndBothOfItsRims() {
        requireEngine()
        val cx = Construction()
        val bored = cx.subtract(cx.block(), cx.drill(5.0, 15.0, 10.0, -5.0, 50.0, "d"))
        val ev = Evaluator()
        val body = built(ev, bored, "the bored block")
        val faces = facesOf(body.feature, "the bored block")

        // the bore itself: one face, on the drill's own cylinder, and it is not a plane to sketch on
        val bore = cylinders(faces, 5.0)
        assertEquals(1, bore.size, "the bore is one face of the body")
        val surface = assertNotNull(bore[0].surface)
        assertEquals(Vec3.X, surface.axis, "…about the drill's own axis")
        assertTrue(surface.full, "…and all the way round")
        assertNotNull(bore[0].reason, "…and it says why no sketch space opens on it")
        assertTrue(assertNotNull(bore[0].reason).contains("cylinder"), "…naming the surface it is: ${bore[0].reason?.render()}")

        // the two walls the bore opens in: their outlines are the wall less the bore's own circle
        val walls = faces.filter { it.plane != null && it.outline.any { e -> e is ProfileElement.CircleE } }
        assertEquals(2, walls.size, "the bore opens in exactly two faces")
        for (w in walls) {
            val circle = assertNotNull(w.outline.filterIsInstance<ProfileElement.CircleE>().singleOrNull(), "one circle apiece")
            assertClose(circle.circle.radius, 5.0, tol = 1e-12, msg = "the bore's own radius, exactly")
            assertClose(area(w.outline), 30.0 * 20.0 - PI * 25.0, tol = 1e-9, msg = "the wall less the bore: ab − πr²")
        }

        // both rims are exact circles of the drill's own radius, each between the bore and one wall
        val rims =
            edgesOf(body.feature, "the bored block").filter {
                (it.geom as? EdgeGeom.OnPlane)?.piece is ProfileElement.CircleE
            }
        assertEquals(2, rims.size, "two rims and no more")
        for (rim in rims) {
            val piece = ((rim.geom as EdgeGeom.OnPlane).piece as ProfileElement.CircleE)
            assertClose(piece.circle.radius, 5.0, tol = 1e-12, msg = "a rim is the bore's own circle")
            assertTrue(rim.fitted == null, "…exactly, with nothing fitted about it")
            assertTrue(rim.between.has(bore[0].name), "…and it stands between the bore and a wall")
        }

        // the same drawing gives the same faces in the same order, every time (OP-4)
        val again = built(Evaluator(), bored, "the bored block, evaluated again")
        assertEquals(faces.map { it.name }, facesOf(again.feature, "again").map { it.name }, "the face list is a function of the drawing")
        assertEquals(
            edgesOf(body.feature, "the bored block").map { it.name },
            edgesOf(again.feature, "again").map { it.name },
            "and so is the crease list",
        )
    }

    /**
     * **The rim of a bore, rounded**: a circular crease between a plane and a cylinder square to it, whose
     * rounding is the exact **torus** slice 5b's rule says it is — Pappus' own figure, bracketed by the
     * chords the engine actually gets.
     */
    @Test
    fun aBoredBlocksRimRoundsToAnExactTorus() {
        requireEngine()
        val cx = Construction()
        val bored = cx.subtract(cx.block(), cx.drill(5.0, 15.0, 10.0, -5.0, 50.0, "d"))
        val ev = Evaluator()
        val body = built(ev, bored, "the bored block")
        val before = Geom3.volume(body.mesh)
        val rim =
            edgesOf(body.feature, "the bored block").indexOfFirst {
                (it.geom as? EdgeGeom.OnPlane)?.piece is ProfileElement.CircleE
            }
        assertTrue(rim >= 0, "the body has a rim to round")

        val r = 2.0
        val (targets, whyT) = Blend3.targets(body.feature, false, rim)
        assertNotNull(targets, "the rim is a target: ${whyT?.render()}")
        val (choices, whyC) = Blend3.choicesFor(body, targets, BlendSection(BlendKind.FILLET, r))
        assertNotNull(choices, "…and the ball fits: ${whyC?.render()}")
        val rounded = ev.solid(cx.blend(bored, bored, cx.planeXY(), cx.const(r.mm), BlendKind.FILLET, false, rim, choices))
        assertManifold(rounded.mesh, "the rounded rim")

        // the band is a torus about the bore's own axis, at the bore's radius plus the rounding's
        val band = assertNotNull(facesOf(rounded.feature, "the rounded body").firstOrNull { it.name is FaceName.BlendBand })
        val torus = assertNotNull(band.surface?.band as? Revolve3.Band.Torus, "the band of a circular crease is a torus")
        assertClose(torus.rc, 7.0, tol = 1e-9, msg = "the ring radius is the bore's plus the rounding's")
        assertClose(torus.minor, r, tol = 1e-9, msg = "…and the tube radius is the rounding's own")

        // Pappus: the wedge between the two faces, carried once round the axis at its own centroid radius
        val rho = 5.0 + Figures.centroidReach(r, BlendKind.FILLET)
        val exact = Figures.revolutionTakes(r, BlendKind.FILLET, PI / 2.0, 2.0 * PI, rho)
        val chorded = Figures.wedgeAreaByChords(r, BlendKind.FILLET) * 2.0 * PI * rho
        val took = before - Geom3.volume(rounded.mesh)
        assertTrue(took >= exact - 1e-6, "the rounding takes at least Pappus' own $exact mm³ — it took $took")
        assertTrue(took <= chorded + 1e-6, "…and at most the same figure by chords ($chorded) — it took $took")

        // a level section through the torus closes, and it is exact rather than chords
        val cut = Section3.sectionOf(rounded, Plane3(Vec3(0.0, 0.0, 19.0), Vec3.X, Vec3.Y))
        assertTrue(cut.pieces.isNotEmpty(), "the plane cuts the rounded body")
        assertTrue(!cut.approximated, "…exactly, and not in chords")
    }

    /**
     * **A sketch space still opens on a flat face of a bored body**, and the file it is written into loads
     * back to itself — the address a bore's own face takes is a stored one (OP-18).
     */
    @Test
    fun aSketchSpaceOpensOnABoredBlockAndTheFileIsAFixedPoint() {
        requireEngine()
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        // a bore drilled through the y = 0 wall: a cross-axis boolean, hence the general engine
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(0.0, 10.0))
        ed.setTool(Tools.CUT)
        ed.type("40")
        ed.click(Vec2(5.0, 10.0))
        val bored = ed.solids().last()
        val feature = Evaluator().solid(bored.ref as constructit.dsl.SolidRef).feature
        assertTrue(feature is Feature3.MeshBoolean, "the general boolean's result: ${ed.statusHint}")
        val faces = facesOf(feature, "the bored plate")
        assertTrue(cylinders(faces, 5.0).size == 1, "the bore is one named cylinder")

        // the top face still opens a space, and it carries the bore's own circle where the bore reaches it
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val spaces = ed.doc.spaces.size
        val vp = view(ed, Camera3(target = Vec3(20.0, 15.0, 10.0), distance = 260.0, yaw = -1.1, pitch = 0.8))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(20.0, 15.0, 20.0))
        assertEquals(spaces + 1, ed.doc.spaces.size, "a space opened on the top: ${ed.statusHint}")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is a fixed point")
    }

    // ---- (b) a counterbore ----

    /**
     * **A counterbore**: two coaxial bores of unlike radius, one cut after the other, so the second boolean's
     * operand is itself a boolean whose faces are curved.
     *
     * The step between them is a **plane** — the annular floor, whose own boundary is two concentric circles
     * — and every crease of the body is a circle: the wide bore against the wall it opens in, the wide bore
     * against the floor, the floor against the narrow bore. Two coaxial *cylinders* of unlike radius never
     * meet at all, which is why the floor has to be there and why the creases are what they are.
     */
    @Test
    fun aCounterboresStepIsAPlaneBetweenTwoCirclesAndEveryCreaseIsOne() {
        requireEngine()
        val cx = Construction()
        val through = cx.subtract(cx.block(), cx.drill(4.0, 15.0, 10.0, -5.0, 50.0, "d1"))
        val counter = cx.subtract(through, cx.drill(8.0, 15.0, 10.0, -5.0, 15.0, "d2"))
        val ev = Evaluator()
        val body = built(ev, counter, "the counterbored block")
        val faces = facesOf(body.feature, "the counterbored block")

        assertEquals(1, cylinders(faces, 4.0).size, "the narrow bore is one face")
        assertEquals(1, cylinders(faces, 8.0).size, "the wide bore is one face")
        // the step: a plane whose boundary is the two circles, of area π(8² − 4²)
        val floor =
            assertNotNull(
                faces.firstOrNull { p ->
                    p.plane != null && p.outline.size == 2 && p.outline.all { e -> e is ProfileElement.CircleE }
                },
                "the annular floor is a plane between two circles",
            )
        assertClose(abs(area(floor.outline)), PI * (64.0 - 16.0), tol = 1e-9, msg = "…of the annulus' own area")

        val circles =
            edgesOf(body.feature, "the counterbored block").filter {
                (it.geom as? EdgeGeom.OnPlane)?.piece is ProfileElement.CircleE
            }
        val radii = circles.map { (((it.geom as EdgeGeom.OnPlane).piece as ProfileElement.CircleE).circle.radius) }.sorted()
        assertEquals(listOf(4.0, 4.0, 8.0, 8.0), radii.map { kotlin.math.round(it * 1e9) / 1e9 }, "four circular creases: two of each bore")
        assertTrue(circles.all { it.fitted == null }, "and every one of them exact")
    }

    // ---- (c) a pin through a plate ----

    /**
     * **A pin driven through a plate**: the plate cuts the pin's one cylinder into **two** faces of the body,
     * each named as a piece of that one operand face, and each meets the wall it comes out of in a circle.
     */
    @Test
    fun aPinThroughAPlateSplitsOneCylinderIntoTwoNamedFaces() {
        requireEngine()
        val cx = Construction()
        val pin = cx.drill(5.0, 15.0, 10.0, -20.0, 80.0, "pin")
        val ev = Evaluator()
        val body = built(ev, cx.union(cx.block(), pin), "the pinned plate")
        val faces = facesOf(body.feature, "the pinned plate")

        val pieces = cylinders(faces, 5.0)
        assertEquals(2, pieces.size, "the plate cuts the pin's cylinder in two")
        val names = pieces.map { it.name as FaceName.BoolFace }
        assertTrue(names.all { it.operand == 1 && it.of == FaceName.Side(0) }, "both are pieces of the pin's own one face")
        assertEquals(listOf(0, 1), names.map { it.piece }.sorted(), "…numbered as pieces of it")

        val circles =
            edgesOf(body.feature, "the pinned plate").filter {
                (it.geom as? EdgeGeom.OnPlane)?.piece is ProfileElement.CircleE
            }
        assertEquals(2, circles.size, "the pin enters and leaves through one circle apiece")
        for (c in circles) {
            assertClose(
                ((c.geom as EdgeGeom.OnPlane).piece as ProfileElement.CircleE).circle.radius,
                5.0,
                tol = 1e-12,
                msg = "the pin's own radius",
            )
            assertTrue(c.fitted == null, "and it is exact")
        }
    }

    // ---- (d) a pipe tee ----

    /**
     * **Two equal cylinders crossing**: the crease between them is a curve this drawing has **no name for**,
     * so it is stated as a chain of cubics through points that are every one of them exact on *both*
     * cylinders — and the tolerance it carries is the one it reached, measured here against the cylinders
     * themselves and never against the fit.
     *
     * A rounding along such a crease is refused **by name**: its own normal section turns along the run, and
     * a rolling ball there sweeps a canal surface this drawing states for no edge (OP-31's cut, slice 5f).
     */
    @Test
    fun aPipeTeesCreaseIsFittedWithinItsOwnStatedToleranceAndRefusesARounding() {
        requireEngine()
        val cx = Construction()
        val main = cx.drill(8.0, 15.0, 10.0, -5.0, 50.0, "a")
        val cCentre = cx.freePoint("t.c", 20.mm, 15.mm)
        val branch =
            cx.extrude(
                cx.sketchOn(cx.plane(Vec3(0.0, 0.0, -5.0), Vec3.X, Vec3.Y), cx.region(cx.loop(cx.circleCR(cCentre, cx.const(4.mm))))),
                cx.const(30.mm),
            )
        val ev = Evaluator()
        val body = built(ev, cx.union(main, branch), "the pipe tee")
        val faces = facesOf(body.feature, "the pipe tee")
        assertEquals(1, cylinders(faces, 8.0).size, "the tee has the main pipe's own cylinder")
        // …and the branch's own cylinder in two pieces, since the branch goes clean through the main
        assertEquals(2, cylinders(faces, 4.0).size, "…and the branch's, cut in two by the main pipe")

        val fitted = edgesOf(body.feature, "the pipe tee").filter { it.geom is EdgeGeom.InSpace }
        assertTrue(fitted.isNotEmpty(), "the crease between the two pipes has no name and is fitted")
        for (e in fitted) {
            val tol = assertNotNull(e.fitted, "a fitted crease says how far it may be")
            val chain = (e.geom as EdgeGeom.InSpace).chain
            var worst = 0.0
            for (piece in chain) {
                for (k in 0..16) {
                    val p = constructit.geom.Frames3.pointAt(piece, k / 16.0)
                    val dA = abs(kotlin.math.hypot(p.y - 15.0, p.z - 10.0) - 8.0)
                    val dB = abs(kotlin.math.hypot(p.x - 20.0, p.y - 15.0) - 4.0)
                    worst = kotlin.math.max(worst, kotlin.math.max(dA, dB))
                }
            }
            assertTrue(worst <= tol + 1e-9, "every point of the chain stands within the stated $tol mm of both cylinders — worst $worst")
            assertTrue(chain.size >= 2, "…and it took more than one span to get there")
        }

        // …and a rounding **along** it is the canal band slice 5f built, on a spine that is fitted where the
        // crease is: the ball's centre is solved station by station against the two cylinders themselves, so
        // every tangency is still exact pointwise and only the run's own parametrisation is a fit. Until that
        // slice this read *"a rounding along a fitted crease is refused"* and asserted the sentence naming
        // its turning normal section; it builds, or it names what stopped it, and this asserts whichever.
        val index = edgesOf(body.feature, "the pipe tee").indexOfFirst { it.geom is EdgeGeom.InSpace }
        val (targets, why) = Blend3.targets(body.feature, false, index)
        if (targets == null) {
            assertTrue(assertNotNull(why).contains("normal section"), "a rounding along a fitted crease is refused by name: $why")
            return
        }
        val sec = BlendSection(BlendKind.FILLET, 1.0)
        val (choices, whyC) = Blend3.choicesFor(body, targets, sec)
        if (choices == null) {
            val reason = assertNotNull(whyC, "a refusal has a reason").render()
            assertTrue(reason.contains("#") || reason.contains("crease") || reason.contains("face"), "…and it names something: $reason")
            println("pipe tee | refused | ${reason.take(90)}")
            return
        }
        val before = Geom3.volume(body.mesh)
        val ref =
            cx.blendAll(
                cx.union(main, branch),
                cx.planeXY(),
                listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(1.mm), null, targets, choices)),
            )
        val r = Evaluator().eval(ref.node)
        if (r is EvalResult.Invalid) {
            assertTrue(r.reason.contains("#") || r.reason.contains("crease"), "a rounding that cannot be built names something: ${r.reason}")
            println("pipe tee | refused | ${r.reason.take(90)}")
            return
        }
        val rounded = Evaluator().solid(ref)
        assertManifold(rounded.mesh, "the pipe tee's crease rounded")
        val moved = abs(Geom3.volume(rounded.mesh) - before)
        val (lo, hi) = assertNotNull(Blend3.canalRemoval(body.feature, targets[0], sec, choices[0]), "the algebra states its figure")
        assertTrue(moved in lo..hi, "the canal along the tee's crease moves $moved, outside its own bracket [$lo, $hi]")
        println("pipe tee | built | moved $moved in [$lo, $hi]")
    }

    /**
     * **Two *equal* cylinders crossing** — the one whole case this slice cuts, and it refuses by name.
     *
     * Two cylinders of the same radius whose axes meet are **tangent** to each other at the two points where
     * their crease crosses itself: their normals are parallel there, so the crease has no direction and no
     * point of it can be pulled onto both surfaces at once. (What it really is, is two plane ellipses — a
     * degenerate pair this drawing does not recognise; a future extension, and named as one.) The provenance
     * refuses **wholly** rather than fitting through a singularity, which is the *watertight-or-refused* rule
     * applied to a name.
     */
    @Test
    fun twoEqualCylindersCrossingAreTangentAndRefuseByName() {
        requireEngine()
        val cx = Construction()
        val main = cx.drill(5.0, 15.0, 10.0, -5.0, 50.0, "eq")
        val cCentre = cx.freePoint("eq.t", 20.mm, 15.mm)
        val branch =
            cx.extrude(
                cx.sketchOn(cx.plane(Vec3(0.0, 0.0, -5.0), Vec3.X, Vec3.Y), cx.region(cx.loop(cx.circleCR(cCentre, cx.const(5.mm))))),
                cx.const(30.mm),
            )
        val ev = Evaluator()
        val body = ev.solid(cx.union(main, branch))
        assertManifold(body.mesh, "two equal pipes crossing")
        val (faces, why) = Section3.faces(body.feature)
        assertTrue(faces == null, "a crease through a tangency is not stated")
        assertTrue(assertNotNull(why).contains("do not determine"), "…and the refusal says so: ${why?.render()}")
    }

    /**
     * **An oblique bore**: the rim where a plane crosses a cylinder askew is a true **ellipse**, and it is
     * stated as one — in the crease list and in the face's own boundary, where its area is `ab − πrr'`.
     */
    @Test
    fun anObliqueBoresRimsAreExactEllipses() {
        requireEngine()
        val cx = Construction()
        // a drill along (1, 0, 1)/√2 whose axis passes through (20, 15, 10): it enters the bottom face at
        // x = 10 and leaves the top at x = 30, cutting both in a 45° ellipse
        val c = cx.freePoint("ob.c", 15.mm, (-7.0710678118654755).mm)
        val circle = cx.region(cx.loop(cx.circleCR(c, cx.const(5.mm))))
        val bore =
            cx.extrude(
                cx.sketchOn(cx.plane(Vec3(-10.0, 0.0, -10.0), Vec3.Y, Vec3(-1.0, 0.0, 1.0)), circle),
                cx.const(80.mm),
            )
        val ev = Evaluator()
        val body = built(ev, cx.subtract(cx.block(), bore), "the obliquely bored block")
        val faces = facesOf(body.feature, "the obliquely bored block")
        assertEquals(1, cylinders(faces, 5.0).size, "the bore is one cylinder")

        val ellipses =
            edgesOf(body.feature, "the obliquely bored block").filter {
                (it.geom as? EdgeGeom.OnPlane)?.piece.let { p -> p is ProfileElement.EllipseE || p is ProfileElement.EllipticArcE }
            }
        assertEquals(2, ellipses.size, "two rims, and both of them ellipses")
        for (e in ellipses) {
            val piece = (e.geom as EdgeGeom.OnPlane).piece
            val ell = if (piece is ProfileElement.EllipseE) piece.ellipse else (piece as ProfileElement.EllipticArcE).arc.ellipse
            assertClose(ell.minor, 5.0, tol = 1e-9, msg = "the bore's own radius across")
            assertClose(ell.major, 5.0 * kotlin.math.sqrt(2.0), tol = 1e-9, msg = "…and its radius over the slope")
            assertTrue(e.fitted == null, "and the ellipse is exact")
        }
    }

    // ---- (e) a turned part fused to a block ----

    /**
     * **A cone from [Revolve3], fused to a block**: the cone's carrier survives the boolean and the crease
     * where the block's top face cuts it is the **circle** that plane ∩ cone is — a conic, exactly, and a
     * face space still opens on the block's own top face beside it.
     */
    @Test
    fun aRevolvedConeFusedToABlockKeepsItsConeAndCutsTheTopFaceInACircle() {
        requireEngine()
        val cx = Construction()
        // a cone about the Z axis at (20, 15): base radius 10 at z = 15, apex at z = 35
        val o = cx.freePoint("cone.o", 20.mm, 15.mm)
        val axis = cx.direction(o, cx.freePoint("cone.a", 20.mm, 16.mm))
        val a = cx.freePoint("cone.p0", 20.mm, 15.mm)
        val b = cx.freePoint("cone.p1", 30.mm, 15.mm)
        val c = cx.freePoint("cone.p2", 20.mm, 35.mm)
        val profile = cx.region(cx.loop(cx.segment(a, b), cx.segment(b, c), cx.segment(c, a)))
        val cone =
            cx.revolveFull(
                cx.sketchOn(cx.plane(Vec3(0.0, 15.0, 0.0), Vec3.X, Vec3.Z), profile),
                o,
                axis,
            )
        val ev = Evaluator()
        val coneSolid = ev.solid(cone)
        assertManifold(coneSolid.mesh, "the cone")
        val body = built(ev, cx.union(cx.block(), cone), "the block with a cone on it")
        val faces = facesOf(body.feature, "the block with a cone on it")

        val coneFace = assertNotNull(faces.firstOrNull { it.surface?.band is Revolve3.Band.Cone }, "the cone's own carrier survives")
        assertNotNull(coneFace.reason, "…and it is not a plane to sketch on")

        // the crease where the block's top face meets it: a circle of the cone's own radius there
        val circles =
            edgesOf(body.feature, "the block with a cone on it").filter {
                (it.geom as? EdgeGeom.OnPlane)?.piece.let { p -> p is ProfileElement.CircleE || p is ProfileElement.ArcE }
            }
        assertTrue(circles.isNotEmpty(), "plane ∩ cone is a conic and is stated as one")
        val rim =
            assertNotNull(
                circles.firstOrNull { it.between.has(coneFace.name) },
                "…the one between the cone and the face it stands in",
            )
        val piece = (rim.geom as EdgeGeom.OnPlane).piece
        val radius = if (piece is ProfileElement.CircleE) piece.circle.radius else (piece as ProfileElement.ArcE).arc.radius
        assertClose(radius, 7.5, tol = 1e-9, msg = "the cone's own radius where the block's top face crosses it")
        assertTrue(rim.fitted == null, "and it is exact")

        // the block's top face is still a plane with its own boundary, the cone's circle among its pieces
        val top =
            assertNotNull(
                faces.firstOrNull { p ->
                    val pl = p.plane
                    pl != null && abs(pl.origin.z - 20.0) < 1e-9 && p.outline.any { e -> e is ProfileElement.CircleE }
                },
                "the block's top face keeps its plane and gains the cone's circle",
            )
        assertClose(abs(area(top.outline)), 40.0 * 30.0 - PI * 7.5 * 7.5, tol = 1e-9, msg = "the top face less the cone's own footprint")
    }

    // ---- (g) a dressed body as an operand ----

    /**
     * **A dressed body is a boolean operand like any other**: the **torus** a rounded rim leaves survives the
     * next boolean as its own carrier, and a section that cuts it in a curve the vocabulary has no name for
     * comes back as chords and *says so* (OP-15's honesty line, moved outward but not crossed).
     *
     * This is the one case where a curved result face is read through the chart's own **marching** rather
     * than through [Revolve3]'s exact table: a plane parallel to a torus' axis and off it cuts it in a
     * quartic, which is stated for no edge and drawn as the chords it is.
     */
    @Test
    fun aRoundedRimSurvivesAsATorusThroughAFurtherBooleanAndSectionsAsChords() {
        requireEngine()
        val cx = Construction()
        val bored = cx.subtract(cx.block(), cx.drill(5.0, 15.0, 10.0, -5.0, 50.0, "d"))
        val ev = Evaluator()
        val body = built(ev, bored, "the bored block")
        val rim =
            edgesOf(body.feature, "the bored block").indexOfFirst {
                (it.geom as? EdgeGeom.OnPlane)?.piece is ProfileElement.CircleE
            }
        val (targets, whyT) = Blend3.targets(body.feature, false, rim)
        assertNotNull(targets, "the rim is a target: ${whyT?.render()}")
        val (choices, whyC) = Blend3.choicesFor(body, targets, BlendSection(BlendKind.FILLET, 2.0))
        assertNotNull(choices, "…and the ball fits: ${whyC?.render()}")
        val dressed = cx.blend(bored, bored, cx.planeXY(), cx.const(2.mm), BlendKind.FILLET, false, rim, choices)

        // a rib laid across the block's top, well clear of the rounded rim: another cross-axis boolean, over
        // an operand whose own faces include a torus
        val ribPlan = cx.rect(20.0, 0.0, 30.0, 10.0, "rib")
        val rib = cx.extrude(cx.sketchOn(cx.plane(Vec3(0.0, 0.0, 0.0), Vec3.Z, Vec3.X), ribPlan), cx.const(30.mm))
        val fused = built(ev, cx.union(dressed, rib), "the ribbed, rounded, bored block")
        val faces = facesOf(fused.feature, "the ribbed, rounded, bored block")
        val torus = assertNotNull(faces.firstOrNull { it.surface?.band is Revolve3.Band.Torus }, "the rim's torus survives the boolean")
        val band = torus.surface?.band as Revolve3.Band.Torus
        assertClose(band.rc, 7.0, tol = 1e-9, msg = "…with the ring radius it was built with")
        assertClose(band.minor, 2.0, tol = 1e-9, msg = "…and the tube radius")

        // a plane parallel to the torus' own axis and off it: a quartic, so the cut is chords and says so
        val cut = Section3.sectionOf(fused, Plane3(Vec3(0.0, 0.0, 12.0), Vec3.X, Vec3.Y))
        assertTrue(cut.pieces.isNotEmpty(), "the plane cuts the body")
        assertTrue(cut.approximated, "…and the torus' own quartic comes back as the chords it is")
    }

    // ---- (f) the sink that stands ----

    /** **An operand with no carrier at all** — a body read from a file — still refuses, in the old words. */
    @Test
    fun aBooleanOverAnImportedOperandStillRefuses() {
        requireEngine()
        val cx = Construction()
        val ev = Evaluator()
        val drill = ev.solid(cx.drill(5.0, 15.0, 10.0, -5.0, 50.0, "imp"))
        val imported = Solid3.of(Feature3.Imported("pin.jt", openShell = Watertight.defect(drill.mesh)), drill.mesh)
        val (out, whyBool) = Geom3.combine(BoolOp.UNION, ev.solid(cx.block()), imported)
        assertTrue(whyBool == null, "the boolean itself is unaffected: ${whyBool?.render()}")
        val feature = assertNotNull(assertNotNull(out).feature as? Feature3.MeshBoolean)
        assertTrue(feature.provenance == null, "an imported operand has no faces to trace to")
        val (faces, refusal) = Section3.faces(feature)
        assertTrue(faces == null, "so the result names none either")
        assertTrue(assertNotNull(refusal).contains("mesh-only"), "and says so in the old words: ${refusal?.render()}")
    }

    /**
     * **An operand face whose surface this drawing has no name for** — an elliptic cylinder — is the other
     * half of the sink, and it refuses naming the face rather than the route.
     */
    @Test
    fun aBooleanOverAnUnnameableSurfaceRefusesNamingTheFace() {
        requireEngine()
        val cx = Construction()
        val centre = cx.freePoint("e.c", 15.mm, 10.mm)
        val ell =
            cx.extrude(
                cx.sketchOn(
                    cx.plane(Vec3(-5.0, 0.0, 0.0), Vec3.Y, Vec3.Z),
                    cx.region(cx.loop(cx.ellipseCAB(centre, cx.freePoint("e.a", 21.mm, 10.mm), cx.const(3.mm)))),
                ),
                cx.const(50.mm),
            )
        val ev = Evaluator()
        val (out, whyBool) = Geom3.combine(BoolOp.SUBTRACT, ev.solid(cx.block()), ev.solid(ell))
        assertTrue(whyBool == null, "the boolean itself is unaffected: ${whyBool?.render()}")
        val feature = assertNotNull(assertNotNull(out).feature as? Feature3.MeshBoolean)
        val (faces, refusal) = Section3.faces(feature)
        assertTrue(faces == null, "an elliptic cylinder is no carrier this drawing has")
        assertTrue(assertNotNull(refusal).contains("neither a plane nor a surface"), "…and it says so: ${refusal?.render()}")
    }
}
