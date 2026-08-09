package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Camera3
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ToolCategory
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Turn3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The ball** — item 3 of DESIGN.md's session-52 sphere queue, and the whole of its claim is that *nothing
 * new was needed*.
 *
 * Two rows, two spellings, and underneath them a construction anybody could have drawn by hand: a pole-to-pole
 * arc, the diameter that closes it, and that half-disc given a **complete revolution** about the diameter. So
 * what is asserted here is not "a ball appeared" but that the ball is an ordinary member of everything the tool
 * already has — a `Revolution` whose turn is the *kind* `Turn3.Full` (session 63), hence watertight with no
 * angle node for a later edit to open; a live DAG (drag the centre, retype the radius, drag the surface point);
 * an operand of the booleans; a body a working plane cuts; a mesh a ray hits; a `tool` step that round-trips
 * byte-for-byte and peels in one undo.
 *
 * **The numbers are the queue entry's own, and they are honest.** At r = 20 and the default tessellation
 * tolerance the shell is 4970 triangles and encloses 33402.9 mm³ against 33510.3 analytic — **0.32 % short**,
 * because it is inscribed twice over (a chorded meridian carried on chorded parallels). That is a *Revolution*
 * property shared by every turned part in the tool, and closing it is item 4 of the queue, not this gesture's
 * business — so the band asserted below is a band under the exact volume, never a claim of exactness.
 */
class SphereTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun solidOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(el: Element) = Evaluator().solid(el.ref as SolidRef).feature

    private fun posOf(el: Element): Vec2 = assertNotNull((Evaluator().valueOf(el.ref) as? PointValue)?.p)

    private fun exactBall(r: Double) = 4.0 / 3.0 * PI * r * r * r

    /**
     * The inscribed-mesh band: a chorded revolution encloses **less** than the ball it approximates, and by
     * the queue entry's measured 0.32 % at r = 20 and the default tolerance. Asserted as a band rather than a
     * number so it survives a tessellation change that keeps the promise, and one-sided because being *over*
     * the analytic volume would mean the shell is not inscribed at all.
     */
    private fun assertInscribedBall(
        el: Element,
        r: Double,
        what: String,
    ) {
        val mesh = meshOf(el)
        assertManifold(mesh, what)
        val v = Geom3.volume(mesh)
        val exact = exactBall(r)
        assertTrue(v < exact, "$what: an inscribed shell encloses less than the ball — $v against $exact")
        assertTrue(v > exact * 0.99, "$what: and not much less — $v against $exact (${(1 - v / exact) * 100}% short)")
    }

    /** *Sphere (centre, radius)*: a radius typed, the centre clicked. */
    private fun ballByRadius(
        r: String = "20",
        at: Vec2 = Vec2(0.0, 0.0),
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.SPHERE_R)
        ed.type(r)
        ed.click(at)
        return ed
    }

    /** *Sphere (centre, surface point)*: two clicks, and the radius is the distance between them. */
    private fun ballBySurfacePoint(
        r: Double = 20.0,
        at: Vec2 = Vec2(0.0, 0.0),
    ): Editor {
        val ed = Editor()
        ed.setTool(Tools.SPHERE)
        ed.click(at)
        ed.click(at + Vec2(r, 0.0))
        return ed
    }

    // ---- what both gestures build ----

    /**
     * **Both spellings build the same ball**, and it is a solid in every sense the tool means: watertight,
     * inscribed in the analytic sphere by a fraction of a percent, and 40 mm across in all three directions.
     */
    @Test
    fun bothSpellingsBuildTheSameWatertightBall() {
        for ((name, ed) in listOf("radius" to ballByRadius(), "surface point" to ballBySurfacePoint())) {
            val ball = solidOf(ed.doc)
            assertInscribedBall(ball, 20.0, "the ball by $name")
            val b = assertNotNull(Geom3.bounds(meshOf(ball)), "the ball has bounds")
            for (extent in listOf(b.second.x - b.first.x, b.second.y - b.first.y, b.second.z - b.first.z)) {
                assertClose(extent, 40.0, tol = 0.1, msg = "the ball by $name is a diameter wide on every axis")
            }
        }
        assertClose(
            Geom3.volume(meshOf(solidOf(ballByRadius().doc))),
            Geom3.volume(meshOf(solidOf(ballBySurfacePoint().doc))),
            tol = 1e-9,
            msg = "the two spellings differ in what was stated, never in what was built",
        )
    }

    /**
     * **Capless by structure, not by value** (session 63). The body's turn is the *kind* `Turn3.Full`, so the
     * graph holds no angle node at all: there is nothing for a later edit, or a shared parameter drifting off
     * 360°, to crack the shell open with. The element the tool made carries no owned scalar freedom either,
     * which is the same statement read from the step's side.
     */
    @Test
    fun theBallIsAFullTurnByKindSoNoEditCanOpenIt() {
        val ed = ballByRadius()
        val ball = solidOf(ed.doc)
        val feature = assertNotNull(featureOf(ball) as? Feature3.Revolution, "a ball is a revolution")
        assertEquals(Turn3.Full, feature.turn, "a kind, not an angle of 360°")
        assertTrue(ed.doc.ownFields(ball).none { it.dim == constructit.units.Dimension.ANGLE }, "there is no angle to type")
    }

    /**
     * **The scaffolding stays live and stays scaffolding**: the arc and the diameter are ordinary elements the
     * user can click, dimension or hide — and the graph knows they are construction for the body, so the dim
     * toggle finds them (OP-14).
     */
    @Test
    fun theArcAndTheAxisStayLiveAndReadAsScaffolding() {
        val ed = ballByRadius()
        val arc = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.ARC }, "the meridian half")
        val diameter = assertNotNull(ed.doc.elements.singleOrNull { it.kind == ElementKind.SEGMENT }, "the axis")
        assertTrue(arc.visible && diameter.visible, "a ball whose radius cannot be re-grabbed is no use")
        val scaffolding = ed.doc.scaffoldingElements()
        assertTrue(arc in scaffolding && diameter in scaffolding, "…and a ball that clutters the drawing is no use either")
        // and the two poles are exactly antipodal by construction — a point reflection, which carries no angle
        val poles = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }.map { posOf(it) }
        assertEquals(2, poles.size, "two poles")
        assertClose((poles[0] + poles[1]).length(), 0.0, tol = 1e-9, msg = "the poles straddle the centre")
        assertClose((poles[0] - poles[1]).length(), 40.0, tol = 1e-9, msg = "…a whole diameter apart")
    }

    // ---- live: every input still drives the body ----

    /** Drag the centre and the ball goes with it — the DAG is ordinary. */
    @Test
    fun draggingTheCentreMovesTheBall() {
        val ed = ballByRadius()
        ed.drag(Vec2(0.0, 0.0), Vec2(60.0, 25.0))
        val b = assertNotNull(Geom3.bounds(meshOf(solidOf(ed.doc))))
        val centre = (b.first + b.second) * 0.5
        assertClose(centre.x, 60.0, tol = 0.1, msg = "the ball followed its centre")
        assertClose(centre.y, 25.0, tol = 0.1, msg = "…in both directions")
        assertInscribedBall(solidOf(ed.doc), 20.0, "the moved ball")
    }

    /** Retype the radius parameter and it resizes — the radius is a value, so it is editable for ever (OP-13). */
    @Test
    fun retypingTheRadiusResizesTheBall() {
        val ed = ballByRadius()
        val r = assertNotNull(ed.doc.scalars.firstOrNull { it.editable }, "the typed radius is a parameter")
        ed.doc.setParameter(r, 30.0.mm)
        assertInscribedBall(solidOf(ed.doc), 30.0, "the retyped ball")
    }

    /**
     * The second spelling's radius is a **derived distance**, so dragging the surface point resizes the ball —
     * and it is genuinely a *surface* point: it lies on the shell it stated, before and after the drag.
     */
    @Test
    fun draggingTheSurfacePointResizesTheBall() {
        val ed = ballBySurfacePoint()
        assertInscribedBall(solidOf(ed.doc), 20.0, "the ball as clicked")
        ed.drag(Vec2(20.0, 0.0), Vec2(35.0, 0.0))
        assertInscribedBall(solidOf(ed.doc), 35.0, "the ball after the drag")
        assertTrue(ed.doc.scalars.none { it.editable }, "nothing here is a free radius parameter")
    }

    /**
     * **A click on an existing point shares it** (OP-5), so the ball's radius is that point's distance and
     * whatever moves the point moves the ball. Sharing a node *is* equality — no constraint is asserted.
     */
    @Test
    fun theSurfacePointIsSharedWhenItIsClickedOnAnExistingPoint() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(25.0, 0.0))
        val marker = ed.doc.elements.single { it.kind == ElementKind.POINT }

        ed.setTool(Tools.SPHERE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(25.0, 0.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.POINT }, "the centre is new, the surface point is not")
        assertInscribedBall(solidOf(ed.doc), 25.0, "the ball on the shared point")

        ed.drag(Vec2(25.0, 0.0), Vec2(0.0, 40.0))
        assertClose(posOf(marker).y, 40.0, tol = 1e-6, msg = "the very point that was clicked moved")
        assertInscribedBall(solidOf(ed.doc), 40.0, "…and the ball it is shared with followed")
    }

    // ---- composability: it is a solid like any other ----

    /**
     * One boolean, both ways round: the ball fuses with a 30 × 30 × 10 prism standing through it, and the
     * prism is taken out of it — watertight each time, and the volumes say which operation happened.
     */
    @Test
    fun theBallUnionsAndSubtractsWithAnotherSolid() {
        for (op in listOf(Tools.UNION, Tools.SUBTRACT)) {
            val ed = ballByRadius(at = Vec2(0.0, 0.0))
            val ballVolume = Geom3.volume(meshOf(solidOf(ed.doc)))
            ed.setTool(Tools.RECTANGLE)
            ed.click(Vec2(-15.0, -15.0))
            ed.click(Vec2(15.0, 15.0))
            ed.setTool(Tools.EXTRUDE)
            ed.type("10")
            ed.click(Vec2(-15.0, 0.0))
            val prism = solidOf(ed.doc)

            ed.setTool(op)
            ed.click(Vec2(0.0, 20.0)) // the ball's footprint hint (the half-disc's pole)
            ed.click(Vec2(-15.0, 0.0)) // the prism's footprint
            val combined = solidOf(ed.doc)
            assertTrue(combined !== prism, "the boolean made a body: ${ed.statusHint}")
            assertNull(reasonOf(combined), "got: ${ed.statusHint}")
            assertManifold(meshOf(combined), "the ball $op a prism")
            val v = Geom3.volume(meshOf(combined))
            if (op == Tools.UNION) {
                assertTrue(v > ballVolume, "fusing adds the corners of the prism that stand out: $v against $ballVolume")
            } else {
                assertTrue(v < ballVolume - 8000.0, "cutting takes the prism's 9000 mm³ out: $v against $ballVolume")
            }
        }
    }

    /**
     * **A working plane through the centre sections it — as an exact circle, and the help says so.** The datum
     * is hinged on the ball's own diameter at 90°, so it stands upright through the axis and cuts a great
     * circle. Since item 4 of the sphere queue landed the ball is **one spherical face** ([Revolve3]), so what
     * comes back is a single `CircleE` of the drawn radius to the last bit — not twenty chords a hair short of
     * one. This is the honesty clause this suite used to carry, come true: the *picture* is still triangles
     * (which is why the volume band below stays), the *section* is not.
     */
    @Test
    fun aPlaneThroughTheCentreSectionsTheBallAsAnExactCircle() {
        val ed = ballByRadius()
        val diameter = ed.doc.elements.single { it.kind == ElementKind.SEGMENT }
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(0.0, 10.0)) // on the axis, between the poles

        val space = assertNotNull(ed.doc.spaceNamed("plane1"), "the datum was made: ${ed.statusHint}")
        assertTrue(diameter.visible, "the axis it was hinged on is a curve like any other")
        val sections = ed.doc.spaceSections(space, Evaluator())
        val section = assertNotNull(sections.singleOrNull(), "the plane cuts exactly the ball: $sections").second
        assertTrue(!section.approximated, "exact since surfaces of revolution gained analytic faces (queue item 4)")
        assertEquals(null, section.inputsRefusal, "…so it offers construction inputs like any named section")

        val circles = section.drawn.filterIsInstance<ProfileElement.CircleE>()
        assertEquals(1, circles.size, "one circle, not a barrel of chords: ${section.drawn.size} pieces")
        assertClose(circles[0].circle.radius, 20.0, tol = 1e-9, msg = "a great circle, exact to the drawn radius")
        assertClose(circles[0].circle.center.x, 0.0, tol = 1e-9, msg = "centred where the ball is")
        assertClose(circles[0].circle.center.y, 0.0, tol = 1e-9, msg = "…in both directions")
    }

    /**
     * **An off-centre plane gives the exact small circle**, which is the other half of what item 4 bought:
     * `√(r² − d²)` from the ball's own parameters, at every distance, and nothing at all past the surface.
     */
    @Test
    fun anOffCentrePlaneSectionsTheBallAsTheExactSmallCircle() {
        val ed = ballByRadius()

        @Suppress("UNCHECKED_CAST")
        val solid = Evaluator().solid(solidOf(ed.doc).ref as SolidRef)
        for (d in listOf(6.0, 15.0)) {
            val sec = Section3.sectionOf(solid, Plane3(Vec3(0.0, 0.0, d), Vec3.X, Vec3.Y))
            assertTrue(!sec.approximated, "exact $d mm off the centre too")
            val c = sec.drawn.filterIsInstance<ProfileElement.CircleE>().single()
            assertClose(c.circle.radius, kotlin.math.sqrt(400.0 - d * d), tol = 1e-9, msg = "the small circle at $d")
        }
        assertTrue(
            Section3.sectionOf(solid, Plane3(Vec3(0.0, 0.0, 24.0), Vec3.X, Vec3.Y)).isEmpty,
            "a plane clear of the ball cuts nothing at all",
        )
    }

    /** The ball is a mesh a ray finds: dead centre it is hit at exactly the radius, and clear of it, missed. */
    @Test
    fun theBallIsRayPickableInThreeDimensions() {
        val ed = ballByRadius()
        val mesh = meshOf(solidOf(ed.doc))
        val camera = Camera3(target = Vec3.ZERO, distance = 300.0, yaw = 0.6, pitch = 0.5)
        val hit = assertNotNull(Geom3.rayMesh(camera.unproject(Vec2(400.0, 300.0), 800.0, 600.0), mesh), "the ray hits the ball")
        assertClose(hit, 280.0, tol = 0.3, msg = "…at the near surface, a radius in front of the centre")
        assertNull(Geom3.rayMesh(camera.unproject(Vec2(0.0, 0.0), 800.0, 600.0), mesh), "and a ray past it misses")
    }

    /** It exports: the STL is the ball's own triangles, and the format's size formula vouches for the count. */
    @Test
    fun theBallExports() {
        val ed = ballByRadius()
        val result = Exports.export(ed.doc, "ball", ExportFormat.STL)
        val bytes = assertNotNull(result.bytes, "refused: ${result.message}")
        assertEquals(84 + 50 * meshOf(solidOf(ed.doc)).triangles.size, bytes.size, "84 bytes of header plus 50 per facet")
    }

    // ---- the file, and the gesture as one act ----

    /**
     * **One gesture, one undo layer** — the profile elements included. A compound tool records one `tool` step
     * (the rounded rectangle's precedent), so the whole ball peels at once and nothing is left standing where
     * the body used to be.
     */
    @Test
    fun theWholeBallPeelsInOneUndo() {
        val ed = ballByRadius()
        val before = ed.doc.elements.size
        assertEquals(6, before, "centre, two poles, the arc, the diameter and the body")
        assertTrue(ed.undo(), "the gesture is undoable")
        assertEquals(
            emptyList(),
            ed.doc.elements.map { "${it.id}:${it.kind}" },
            "one press takes the whole ball — body, profile, poles and the centre the click placed",
        )
        assertTrue(ed.redo(), "and it comes back whole")
        assertEquals(before, ed.doc.elements.size, "every element of it")
        assertInscribedBall(solidOf(ed.doc), 20.0, "the redone ball")
    }

    /** The file: byte-equal on the round trip, and a replay that rebuilds the very same volume. */
    @Test
    fun theBallRoundTripsAndReplaysToTheSameBody() {
        for (ed in listOf(ballByRadius(), ballBySurfacePoint())) {
            val text = DocumentFormat.save(ed.doc)
            val reloaded = DocumentFormat.load(text)
            assertEquals(emptyList(), reloaded.loadNotes, "it loads clean")
            assertEquals(text, DocumentFormat.save(reloaded), "save -> load -> save must be byte-equal")
            assertEquals(ed.doc.elements.size, reloaded.elements.size, "and the same elements come back")
            assertClose(
                Geom3.volume(meshOf(solidOf(reloaded))),
                Geom3.volume(meshOf(solidOf(ed.doc))),
                tol = 1e-9,
                msg = "replay rebuilds the same body, never a re-derived one",
            )
        }
    }

    // ---- the pairing ----

    /**
     * **The two rows are the circle's two rows, one dimension up.** They sit beside each other among the
     * solids, they take the same slots in the same order as their circle counterparts, and they say it in the
     * same words — because a tool id is what a file records (OP-18) and the pairing is a promise about
     * meaning, not a layout accident.
     */
    @Test
    fun theTwoRowsMirrorTheCirclePair() {
        val byId = Tools.all.associateBy { it.id }
        val solids = Tools.all.filter { it.category == ToolCategory.SOLIDS }.map { it.id }
        val i = solids.indexOf(Tools.SPHERE_R)
        assertTrue(i >= 0 && solids.getOrNull(i + 1) == Tools.SPHERE, "the pair stands together: $solids")

        for ((sphere, circle) in listOf(Tools.SPHERE_R to Tools.CIRCLE_R, Tools.SPHERE to Tools.CIRCLE)) {
            val s = assertNotNull(byId[sphere])
            val c = assertNotNull(byId[circle])
            assertEquals(c.slots, s.slots, "$sphere takes what $circle takes, in that order")
            assertEquals(c.scalars.map { it.name }, s.scalars.map { it.name }, "$sphere states what $circle states")
            assertEquals(c.slotNames.first(), s.slotNames.first(), "and calls the first pick the same thing")
        }
        assertEquals(listOf("centre", "surface point"), assertNotNull(byId[Tools.SPHERE]).slotNames)
        // the honesty clause the queue entry asks for, in both rows
        for (id in listOf(Tools.SPHERE_R, Tools.SPHERE)) {
            val help = assertNotNull(byId[id]).help
            assertTrue("revolve" in help, "$id says the body is a revolve")
            assertTrue("exact circle" in help, "$id says its section is an exact circle now (queue item 4)")
            assertTrue("chorded" !in help, "$id no longer claims a chorded section: $help")
        }
    }

    // ---- refusals ----

    /**
     * **Refusals speak, in the circle's own words** (OP-3). Both spellings hand [Document] the very circle
     * node their circle tool builds, so a non-positive radius and a surface point standing on the centre
     * decline exactly as they decline for a circle — and both **heal** the moment the number does.
     */
    @Test
    fun aBallWithNoSizeRefusesByNameAndHeals() {
        val zero = Editor()
        zero.setTool(Tools.SPHERE_R)
        zero.type("0")
        zero.click(Vec2(0.0, 0.0))
        val ball = solidOf(zero.doc)
        assertTrue("non-positive radius" in assertNotNull(reasonOf(ball)), "got: ${zero.statusHint}")
        assertTrue("ball" in zero.statusHint, "and the note is about the ball: ${zero.statusHint}")
        val r = assertNotNull(zero.doc.scalars.firstOrNull { it.editable })
        zero.doc.setParameter(r, 12.0.mm)
        assertNull(reasonOf(solidOf(zero.doc)), "a value condition heals (OP-3)")
        assertInscribedBall(solidOf(zero.doc), 12.0, "the healed ball")

        val onCentre = Editor()
        onCentre.setTool(Tools.SPHERE)
        onCentre.click(Vec2(0.0, 0.0))
        onCentre.click(Vec2(0.0, 0.0))
        assertTrue("zero-radius circle" in assertNotNull(reasonOf(solidOf(onCentre.doc))), "got: ${onCentre.statusHint}")
        onCentre.drag(Vec2(0.0, 0.0), Vec2(0.0, 0.0))
    }

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** A ball built on a datum plane lies in *that* plane — the revolve is plane-anchored, so it just works. */
    @Test
    fun aBallOnADatumPlaneStandsInThatPlane() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(50.0, 0.0))
        assertEquals("plane1", ed.doc.activeSpace.name, "the view switched to the datum: ${ed.statusHint}")

        ed.setTool(Tools.SPHERE_R)
        ed.type("15")
        ed.click(Vec2(40.0, 30.0))
        val ball = solidOf(ed.doc)
        assertInscribedBall(ball, 15.0, "the ball on the datum")
        val b = assertNotNull(Geom3.bounds(meshOf(ball)))
        val centre = (b.first + b.second) * 0.5
        // the datum stands upright on the segment along world x, so its (40, 30) is 40 along x and 30 up
        assertClose(centre.x, 40.0, tol = 0.1, msg = "the centre is the datum's own point")
        assertClose(centre.z, 30.0, tol = 0.1, msg = "…standing off the plan, because that is where the plane is")
        assertClose(abs(centre.y), 0.0, tol = 0.1, msg = "…and in the plane, not beside it")
    }
}
