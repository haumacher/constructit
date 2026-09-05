package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import constructit.l10n.contains
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A turned part's flat faces, reached by clicking** — the editor half of item 4 of the sphere queue
 * (OP-17): *Sketch on face* on a shaft's flat end, on a partial revolve's cap, and the identity of both
 * surviving every edit of the profile that made them.
 *
 * The geometry is [RevolveFaceTest]'s. What is asserted here is that nothing about the *addressing* had to
 * change to make this work: a revolution's face is named by the profile boundary-piece index the
 * `sketchspace el= piece=` step has always recorded, so the same click, the same step and the same reload
 * reach the same face — and a curved band declines in the words of the surface it actually is.
 */
class RevolveFaceSpaceTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun Editor.meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun Editor.featureOf(el: Element) = Evaluator().solid(el.ref as SolidRef).feature as Feature3.Revolution

    /**
     * The fixture: a **turned shaft**, 100 long and 20 in radius, about a line drawn along `y = 0`.
     *
     * The profile is a rectangle whose bottom edge lies **on** the axis, so its four boundary pieces are —
     * in [constructit.geom.Geom3.boundaryPieces] order — the axis edge (which sweeps nothing), the flat end
     * at `x = 100`, the barrel, and the flat end at `x = 0`.
     */
    private fun shaft(
        angle: String? = null,
        len: Double = 100.0,
        r: Double = 20.0,
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(len, r))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-30.0, 0.0))
        ed.click(Vec2(len + 30.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        if (angle != null) ed.type(angle)
        // the profile is picked by one of its own boundary edges; the top one is unambiguous, the bottom
        // one lying exactly under the axis line
        ed.click(Vec2(len / 2, r))
        ed.click(Vec2(-20.0, 0.0))
        return ed
    }

    /** The index of the flat end at the far side of the shaft, found by its family rather than assumed. */
    private fun farEndPiece(ed: Editor): Int {
        val faces = assertNotNull(constructit.geom.Section3.faces(ed.featureOf(ed.solids().last())).first)
        return faces.indices
            .filter { faces[it].surface?.band is constructit.geom.Revolve3.Band.Planar }
            .maxByOrNull { (faces[it].surface?.band as constructit.geom.Revolve3.Band.Planar).s }!!
    }

    // ---- the acceptance: a boss on the end of a turned part ----

    /**
     * **A shaft's flat end is a sketch space, and a circle drawn there extrudes into a boss.** One click
     * on the footprint edge the end projects to, one circle, one extrude — and what comes out is a
     * watertight part, which is the whole of *watertight or refused* on this path.
     */
    @Test
    fun aBossGrowsOnTheFlatEndOfATurnedPart() {
        val ed = shaft()
        val body = ed.solids().single()
        val piece = farEndPiece(ed)
        assertEquals(null, ed.doc.faceRefusal(body, piece), "the flat end takes a sketch")

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(100.0, 10.0))
        val space = ed.activeSpace
        assertTrue(!space.isPlan, "the view switched to the end face: ${ed.statusHint}")
        assertEquals(piece, space.piece, "…the face the profile edge names")

        // the origin of a face of revolution is where its axis pierces it, so a concentric boss is at (0, 0)
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("boss", 12.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(8.0, 0.0))

        val boss = assertNotNull(ed.solids().lastOrNull { it !== body }, "the boss was built: ${ed.statusHint}")
        assertManifold(ed.meshOf(boss), "a boss on the end of a turned part")
        // it stands **out** of the material, along the face's own normal — past the shaft's far end
        val xs = ed.meshOf(boss).vertices.map { it.x }
        assertClose(xs.min(), 100.0, tol = 1e-6, msg = "the boss starts at the end face")
        assertClose(xs.max(), 112.0, tol = 1e-6, msg = "…and stands the typed depth proud of it")
        val ys = ed.meshOf(boss).vertices.map { it.y }
        assertTrue(ys.max() <= 8.0 + 1e-6, "…and it is the 8 mm circle, concentric with the shaft: ${ys.max()}")
    }

    /** The same on a **partial** revolve's cap, which is picked by clicking the face rather than an edge. */
    @Test
    fun aBossGrowsOnAPartialRevolvesCap() {
        val ed = shaft(angle = "90")
        val body = ed.solids().single()
        assertEquals(constructit.geom.Turn3.Arc(0.0, PI / 2), ed.featureOf(body).turn, ed.statusHint)

        ed.setTool(Tools.SKETCH_ON_FACE)
        // inside the profile and clear of every edge: the cap standing in this plane, not a band
        ed.click(Vec2(50.0, 10.0))
        val space = ed.activeSpace
        assertTrue(!space.isPlan, "the cap opened as a space: ${ed.statusHint}")
        assertEquals(4, space.piece, "the caps come after the profile's own four pieces, low angle first")

        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(50.0, 10.0))
        ed.click(Vec2(56.0, 10.0))
        ed.activeScalar = ed.doc.newParameter("stub", 9.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(56.0, 10.0))
        val stub = assertNotNull(ed.solids().lastOrNull { it !== body }, "the stub was built: ${ed.statusHint}")
        assertManifold(ed.meshOf(stub), "a stub on a partial revolve's cap")
        // the low-angle cap's outward normal points out of the sweep, so the stub stands clear of the body
        assertTrue(ed.meshOf(stub).vertices.all { it.z <= 1e-6 }, "out of the sweep, not into it")
        assertClose(ed.meshOf(stub).vertices.minOf { it.z }, -9.0, tol = 1e-6, msg = "the typed depth, the other way")
    }

    /** A **curved** band declines in the words of the surface it actually is, and names what does work. */
    @Test
    fun aCurvedBandRefusesByTheNameOfItsOwnSurface() {
        val ed = shaft()
        val body = ed.solids().single()
        val faces = assertNotNull(constructit.geom.Section3.faces(ed.featureOf(body)).first)
        val barrel = faces.indexOfFirst { it.surface?.band is constructit.geom.Revolve3.Band.Cylinder }
        val why = assertNotNull(ed.doc.faceRefusal(body, barrel), "a cylinder is no plane")
        assertTrue("cylinder" in why, "…and it says which surface it is: $why")
        assertTrue("datum plane" in why, "…and what does work instead: $why")

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.activeSpace.isPlan, "the view stayed in the plan: ${ed.statusHint}")
        assertTrue("cylinder" in ed.statusHint, "and the status line speaks: ${ed.statusHint}")

        // the edge on the axis is the other honest refusal, and it names the axis
        val axisEdge = faces.indexOfFirst { it.surface?.band is constructit.geom.Revolve3.Band.Degenerate }
        val axisWhy = assertNotNull(ed.doc.faceRefusal(body, axisEdge))
        assertTrue("axis of revolution" in axisWhy, "$axisWhy")
    }

    /**
     * **The two consumers the seam has, on a turned part.** A section input takes the barrel's circle as an
     * ordinary [constructit.core.CircleValue] and it **follows** the radius; and an intersection curve — the
     * section promoted into space (OP-26's step 6) — reports itself **exact** rather than chords, which is
     * the sentence this whole package was for.
     */
    @Test
    fun aTurnedPartsSectionFeedsBothSeamsAndSaysItIsExact() {
        val ed = shaft()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(60.0, -40.0))
        ed.click(Vec2(60.0, -10.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(60.0, -25.0))
        val datum = assertNotNull(ed.doc.spaceNamed("plane1"), ed.statusHint)
        val (solidEl, sec) = assertNotNull(ed.doc.spaceSections(datum, Evaluator()).firstOrNull())
        val i = sec.edges.indexOfFirst { it.curve is ProfileElement.CircleE }
        assertTrue(i >= 0, "the barrel's cut is a circle: ${sec.edges.map { it.reason }}")

        val input = assertNotNull(ed.doc.sectionInput(datum, Document.SectionInput.EDGE, i, solidEl), ed.statusHint)
        assertEquals(ElementKind.CIRCLE, input.kind, "an ordinary circle element, on the working plane")
        val r0 = (Evaluator().valueOf(input.ref) as constructit.core.CircleValue).circle.radius
        assertClose(r0, 20.0, tol = 1e-9, msg = "the turned radius, as a construction input")

        // OP-26's step 6: the same section, promoted into space — and it says it is exact
        ed.setActiveSpace(datum.name)
        ed.setTool(Tools.INTERSECTION_CURVE)
        val circle = (assertNotNull(sec.edges[i].curve) as ProfileElement.CircleE).circle
        ed.click(circle.center + Vec2(circle.radius, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        assertTrue("exact" in ed.statusHint, "the note names the class: ${ed.statusHint}")
        assertTrue("chords" !in ed.statusHint, "…and it is not chords any more: ${ed.statusHint}")

        // and the input follows the part (OP-17's liveness), by the very index it recorded
        ed.setActiveSpace("plan")
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(100.0, 20.0), Vec2(100.0, 28.0))
        ed.drag(Vec2(0.0, 20.0), Vec2(0.0, 28.0))
        val again = Evaluator().valueOf(input.ref) as constructit.core.CircleValue
        assertClose(again.circle.radius, 28.0, tol = 1e-6, msg = "the same index, the new radius")
    }

    /**
     * **`facePlane` reaches a partial revolve's caps now**, which is the accessor *Extrude on face* asks —
     * so a boss can be raised on the end of a partial turned part without opening a space at all. The cut
     * this reverses is quoted in [constructit.geom.Geom3.facePlane]'s own comment; a **complete** revolution
     * still has neither cap and refuses in the words its kind uses.
     */
    @Test
    fun extrudeOnFaceReachesAPartialRevolvesCap() {
        val ed = shaft(angle = "90")
        val body = ed.solids().single()
        assertEquals(null, constructit.geom.Geom3.facePlane(ed.featureOf(body), constructit.geom.SolidFace.TOP).second)

        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(-60.0, 60.0))
        ed.click(Vec2(-53.0, 60.0))
        val area = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE })
        ed.activeScalar = ed.doc.newParameter("pip", 6.0.mm)
        ed.setTool(Tools.EXTRUDE_ON_FACE)
        ed.click(Vec2(50.0, 20.0))
        ed.click(Vec2(-53.0, 60.0))
        val raised = assertNotNull(ed.solids().lastOrNull { it !== body }, "raised off the cap: ${ed.statusHint}")
        assertManifold(ed.meshOf(raised), "a pip raised off a partial revolve's end cap")
        assertTrue(area.visible)

        // a complete revolution has no such face, and says so
        val whole = shaft()
        val why =
            assertNotNull(
                constructit.geom.Geom3.facePlane(whole.featureOf(whole.solids().single()), constructit.geom.SolidFace.TOP).second,
            )
        assertTrue("complete revolution" in why, "$why")
    }

    /** A **complete** revolution has no caps, and says so in the words its own kind uses. */
    @Test
    fun aCompleteRevolutionRefusesACapByName() {
        val ed = shaft()
        val body = ed.solids().single()
        val why = assertNotNull(ed.doc.faceRefusal(body, 4), "there is no fifth face")
        assertTrue("complete revolution" in why, "$why")
        assertTrue("no start and no end" in why, "$why")
    }

    // ---- the identity rule, exercised through the editor ----

    /**
     * **The face keeps its name through every edit of the profile.** Retype the shaft's radius and drag its
     * length, and the space opened on the flat end is still that end — it has *moved*, which is the point:
     * the address is the construction's and the geometry is the value's (OP-17's liveness).
     */
    @Test
    fun theEndFaceIsTheSameFaceAfterTheProfileMoves() {
        val ed = shaft()
        val body = ed.solids().single()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(100.0, 10.0))
        val space = ed.activeSpace
        val piece = space.piece
        val before = assertNotNull(ed.doc.planeOf(space, Evaluator()))
        assertClose(before.origin.x, 100.0, tol = 1e-9, msg = "the end face stands at the shaft's far end")

        // drag the profile's far corner: the same face, further out
        ed.setActiveSpace("plan")
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(100.0, 20.0), Vec2(140.0, 26.0))
        val after = assertNotNull(ed.doc.planeOf(ed.doc.spaceNamed(space.name)!!, Evaluator()))
        assertEquals(piece, ed.doc.spaceNamed(space.name)!!.piece, "the same address, a new place")
        assertClose(after.origin.x, 140.0, tol = 1e-6, msg = "the face followed the corner that moved it")
        assertClose(
            abs(after.normal.normalized().x),
            1.0,
            tol = 1e-9,
            msg = "…and still faces along the axis",
        )
        assertManifold(ed.meshOf(body), "the part is watertight after the drag")
    }

    /**
     * A face space on a turned part **round-trips**: the same `sketchspace el= piece=` step the extrude has
     * always written reaches the same face on reload, with no argument added and no version bumped.
     */
    @Test
    fun aFaceSpaceOnATurnedPartRoundTrips() {
        val ed = shaft()
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(100.0, 10.0))
        ed.setTool(Tools.CIRCLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(7.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("sketchspace") }
        assertTrue("piece=" in step && "el=" in step, "the address is the one it always was: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
        val back = DocumentFormat.load(text)
        val space = assertNotNull(back.spaces.firstOrNull { !it.isPlan })
        assertEquals(ed.activeSpace.piece, space.piece, "the same face, after the round trip")
    }

    // ---- the sections a working plane on a turned part now offers ----

    /**
     * **A datum through a shaft offers its exact circle as an input** — the liveness OP-17 asks of every
     * face-derived construction, now reaching a turned part: retype the radius and the input follows.
     */
    @Test
    fun aSectionOfATurnedPartIsAnExactInputThatFollowsTheRadius() {
        val ed = shaft()
        // a datum standing **across** the shaft, hinged on a line drawn clear of the profile
        ed.setTool(Tools.LINE)
        ed.click(Vec2(60.0, -40.0))
        ed.click(Vec2(60.0, -10.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(60.0, -25.0))
        val datum = assertNotNull(ed.doc.spaceNamed("plane1"), ed.statusHint)
        val sec = assertNotNull(ed.doc.spaceSections(datum, Evaluator()).firstOrNull()).second
        assertTrue(!sec.approximated, "a cut across a shaft is its own circle")
        val circle = sec.drawn.filterIsInstance<ProfileElement.CircleE>().single()
        assertClose(circle.circle.radius, 20.0, tol = 1e-9, msg = "the turned radius, exactly")

        // move the profile's radius and the very same index reads the new circle
        ed.setActiveSpace("plan")
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(100.0, 20.0), Vec2(100.0, 33.0))
        ed.drag(Vec2(0.0, 20.0), Vec2(0.0, 33.0))
        val again = assertNotNull(ed.doc.spaceSections(ed.doc.spaceNamed("plane1")!!, Evaluator()).firstOrNull()).second
        assertClose(
            again.drawn.filterIsInstance<ProfileElement.CircleE>().single().circle.radius,
            33.0,
            tol = 1e-6,
            msg = "the same face, the new radius — OP-17's liveness",
        )
    }
}
