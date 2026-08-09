package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.LineValue
import constructit.core.SolidValue
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.resultOf
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Line
import constructit.geom.Mesh3
import constructit.geom.Turn3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The revolution's interval** (OP-17 slice 2, session 63) — the user's own design, in three parts:
 *
 * 1. *"By default the revolution could be complete 360° — which produces a 3D object without the start and
 *    end sides — maybe worth a separate flag for the revolution result 'full'?"* Adopted as **structure**,
 *    not as a flag over a value: with no angle stated the gesture builds a complete revolution whose graph
 *    holds no angle node at all, so no later edit of anything can open it. That is OP-14's circle-vs-arc
 *    rule (*"a circle is not faked as a full-turn arc whose 0-vs-2π sweep is ambiguous"*) one dimension up.
 * 2. A **signed** sweep: a negative angle turns the body the other way about the axis, which used to be
 *    refused outright.
 * 3. A stated **offset**: *"you could produce the same effect by giving −30° offset and revolving 30°. But
 *    this would also allow the revolution to go from one side of the construction plane to the other (e.g.
 *    −15° offset)."* The body occupies `[offset, offset + angle]`, the profile being the generator at 0.
 *
 * **Which way is positive, and why the user's revolve went the other way from their plan rotation.** The
 * kernel's frame is `A` along the axis, `P` radial, `N = A × P`, and the sweep is `P·cos θ + N·sin θ`. The
 * axis is canonicalized so the profile lies on `+P` — which negates `A` *and* `P` together and therefore
 * leaves `N = A × P` alone. So `N` is nothing other than **the sketch plane's own normal**, and a positive
 * angle always turns the profile toward the front of the plane it was drawn on, whatever direction the axis
 * happened to be drawn in and whichever side of it the profile sits. A plane whose normal faces away from
 * the plan's `+z` therefore sweeps *clockwise* in plan while the plan's own *Rotate* turns counter-clockwise
 * — which is exactly what the user saw, and what a stated sign now answers.
 */
class RevolveIntervalTest {
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

    private fun named(
        doc: Document,
        n: String,
    ): Element = assertNotNull(doc.elements.firstOrNull { doc.userNameOf(it) == n || doc.nameOf(it) == n }, "the drawing has $n")

    private fun meshOf(el: Element): Mesh3 {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        return (r.value as SolidValue).solid.mesh
    }

    private fun featureOf(el: Element): Feature3.Revolution =
        (((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as SolidValue).solid.feature as Feature3.Revolution)

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    // ---- the kernel fixture: a 10 x 60 bar standing 15 mm off the X axis, in the world XY plane ----
    //
    // Revolved a full turn it is a tube: outer radius 25, bore 15, length 60 — the same body
    // `SolidToolTest` measures, so the numbers below are the analytic ones and not a golden.

    private val fullTubeVolume = PI * (25.0 * 25.0 - 15.0 * 15.0) * 60.0

    private fun Construction.bar(): RegionRef {
        val p0 = freePoint("p0", 0.mm, 15.mm)
        val p1 = freePoint("p1", 60.mm, 15.mm)
        val p2 = freePoint("p2", 60.mm, 25.mm)
        val p3 = freePoint("p3", 0.mm, 25.mm)
        return region(loop(segment(p0, p1), segment(p1, p2), segment(p2, p3), segment(p3, p0)))
    }

    private fun Construction.turned(
        deg: Double,
        offsetDeg: Double? = null,
    ): SolidRef {
        val o = freePoint("axisO", 0.mm, 0.mm)
        val axis = direction(o, freePoint("axisX", 1.mm, 0.mm))
        return revolve(
            sketchOn(planeXY(), bar()),
            o,
            axis,
            parameter("sweep", deg.deg),
            offsetDeg?.let { parameter("offset", it.deg) },
        )
    }

    /**
     * The mesh of [s] — through an [Evaluator] of its own, because the cache is keyed by node **id** and two
     * `Construction`s number their nodes from the same well.
     */
    private fun mesh(s: SolidRef): Mesh3 = Evaluator().solid(s).mesh

    private fun Construction.turnedFull(): SolidRef {
        val o = freePoint("axisO", 0.mm, 0.mm)
        val axis = direction(o, freePoint("axisX", 1.mm, 0.mm))
        return revolveFull(sketchOn(planeXY(), bar()), o, axis)
    }

    // ---- 1. the interval, and the one winding rule that serves every sign ----

    /**
     * **A positive sweep turns toward the sketch plane's normal**, a negative one away from it — the whole
     * sign convention, asserted where it can be read off a bounding box. The plan's normal is `+z`, so a
     * quarter turn out of the plan fills `z ≥ 0` and a quarter turn back fills `z ≤ 0`.
     */
    @Test
    fun aPositiveSweepTurnsTowardTheSketchPlanesNormalAndANegativeOneAwayFromIt() {
        val c = Construction()
        val up = c.turned(90.0)
        val down = c.turned(-90.0)
        val ev = Evaluator()
        assertManifold(ev.solid(up).mesh, "quarter tube, +90°")
        assertManifold(ev.solid(down).mesh, "quarter tube, −90°")

        val a = assertNotNull(Geom3.bounds(ev.solid(up).mesh))
        assertClose(a.first.z, 0.0, 1e-9, msg = "a positive sweep starts on the plane it was drawn in")
        assertClose(a.second.z, 25.0, 2.0 * GeomMath.TESS_TOL_MM, msg = "...and reaches the outer radius toward +normal")
        val b = assertNotNull(Geom3.bounds(ev.solid(down).mesh))
        assertClose(b.second.z, 0.0, 1e-9, msg = "a negative sweep also starts on that plane")
        assertClose(b.first.z, -25.0, 2.0 * GeomMath.TESS_TOL_MM, msg = "...and reaches the same way behind it")
        assertClose(
            Geom3.volume(ev.solid(down).mesh),
            Geom3.volume(ev.solid(up).mesh),
            1e-9,
            msg = "the two are mirror images, so exactly as much material either way",
        )
    }

    /**
     * **A negative sweep is the offset interval walked the other way** — the normalization, stated as the
     * identity the user asked for: *"you could produce the same effect by giving −30° offset and revolving
     * 30°"*. Not merely the same volume: the same [Turn3.Arc], hence vertex for vertex the same mesh.
     */
    @Test
    fun aNegativeSweepIsTheSameBodyAsTheIntervalStatedByAnOffset() {
        val c = Construction()
        val negative = c.turned(-30.0)
        val offset = c.turned(30.0, offsetDeg = -30.0)
        val ev = Evaluator()
        val ta = (ev.solid(negative).feature as Feature3.Revolution).turn
        val tb = (ev.solid(offset).feature as Feature3.Revolution).turn
        assertEquals(ta, tb, "one interval, however it was stated")
        assertEquals(Turn3.Arc(-PI / 6.0, 0.0), ta, "and it is [−30°, 0]")
        assertManifold(ev.solid(negative).mesh, "−30° sweep")
        assertManifold(ev.solid(offset).mesh, "offset −30°, sweep 30°")
        assertClose(Geom3.volume(ev.solid(offset).mesh), Geom3.volume(ev.solid(negative).mesh), 1e-9, msg = "same volume")
        val a = assertNotNull(Geom3.bounds(ev.solid(negative).mesh))
        val b = assertNotNull(Geom3.bounds(ev.solid(offset).mesh))
        assertEquals(a, b, "same bounding box, to the last bit")
    }

    /**
     * **Every combination of signs is watertight**, which is the claim the cap-winding derivation has to
     * carry: a positive sweep, a negative one, either sign of offset, an interval straddling zero, and the
     * complete revolution — all closed, all wound outward, and all holding the same material as the sector
     * they subtend (Pappus, to the tessellation's own tolerance).
     */
    @Test
    fun everySignCombinationIsWatertightAndHoldsItsOwnSector() {
        for ((sweep, offset) in listOf(30.0 to null, -30.0 to null, 30.0 to 45.0, 30.0 to -45.0, -30.0 to 45.0, 30.0 to -15.0, -30.0 to 15.0)) {
            val mesh = mesh(Construction().turned(sweep, offsetDeg = offset))
            assertManifold(mesh, "sweep $sweep offset $offset")
            val exact = fullTubeVolume * abs(sweep) / 360.0
            assertTrue(
                abs((exact - Geom3.volume(mesh)) / exact) < 5e-3,
                "sweep $sweep offset $offset holds ${Geom3.volume(mesh)} mm³, not the $exact mm³ of its sector",
            )
        }
        val whole = mesh(Construction().turnedFull())
        assertManifold(whole, "complete revolution")
        assertTrue(abs((fullTubeVolume - Geom3.volume(whole)) / fullTubeVolume) < 5e-3, "and the whole ring holds the whole tube")
    }

    /**
     * **A straddling interval is symmetric about the plane the profile was drawn on** — the user's own
     * second use of the offset (*"−15° offset"*), and the honest measure of it: reflect the mesh in that
     * plane and every vertex of the reflection is a vertex of the original.
     */
    @Test
    fun anIntervalStraddlingZeroIsSymmetricAboutTheSketchPlane() {
        val c = Construction()
        val s = c.turned(30.0, offsetDeg = -15.0)
        val mesh = Evaluator().solid(s).mesh
        assertManifold(mesh, "straddling body")
        val b = assertNotNull(Geom3.bounds(mesh))
        assertClose(b.first.z, -b.second.z, 1e-9, msg = "it reaches as far behind the plan as in front of it")
        for (v in mesh.vertices) {
            val mirrored = Vec3(v.x, v.y, -v.z)
            assertTrue(
                mesh.vertices.any { (it - mirrored).length() < 1e-9 },
                "the reflection of $v in the sketch plane is a vertex of the body too",
            )
        }
    }

    // ---- 2. full is a kind, not a value ----

    /**
     * **The complete revolution has no ends**, and the mesh's own topology says so rather than a triangle
     * count anybody has to trust: a body swept the whole way round a profile that misses the axis is a
     * **torus** (Euler characteristic 0 — the seam welded, no caps anywhere), while the same profile swept
     * 90° is a **ball** (characteristic 2), its two ends closed by the caps. `χ = V − E + F` with `E = 3F/2`
     * on a closed triangulation, so `χ = V − F/2`.
     */
    @Test
    fun aCompleteRevolutionIsATorusAndAPartialOneIsABall() {
        val whole = mesh(Construction().turnedFull())
        val part = mesh(Construction().turned(90.0))
        assertManifold(whole, "complete revolution")
        assertManifold(part, "90° revolution")
        assertEquals(0, whole.vertexCount - whole.triangleCount / 2, "no caps and no seam: the body is a ring")
        assertEquals(2, part.vertexCount - part.triangleCount / 2, "two caps close the partial body")
    }

    /**
     * **A stated 360° still closes**, and it must: every revolve written before this package recorded an
     * angle, and `360°` in one of those files means the closed body it always built (OP-18 — a stored
     * literal's meaning is frozen). The structural form is what a *new* drawing gets when nothing is typed;
     * it is not a reinterpretation of an old one.
     */
    @Test
    fun aStatedFullTurnStillClosesBecauseThatIsWhatEveryOlderFileMeans() {
        val stated = mesh(Construction().turned(360.0))
        val whole = mesh(Construction().turnedFull())
        assertManifold(stated, "stated 360°")
        assertEquals(whole.triangleCount, stated.triangleCount, "no caps either, so the same triangles")
        assertClose(Geom3.volume(stated), Geom3.volume(whole), 1e-9, msg = "and the same material")
    }

    /**
     * The difference the two forms make is **what an edit can do to them**: a stated angle is a value, so
     * dragging it to 350° opens the body — while the complete revolution has no such value to drag, which
     * is the whole point of making it a kind.
     */
    @Test
    fun onlyTheStatedFormCanBeOpenedByAnEdit() {
        val c = Construction()
        val sweep = c.parameter("sweep", 360.0.deg)
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val stated = c.revolve(c.sketchOn(c.planeXY(), c.bar()), o, axis, sweep)
        val whole = c.turnedFull()
        assertEquals(Turn3.Full, (Evaluator().solid(whole).feature as Feature3.Revolution).turn)
        c.set(sweep, 350.0.deg)
        val ev = Evaluator()
        assertEquals(Turn3.Arc(0.0, 350.0 * PI / 180.0), (ev.solid(stated).feature as Feature3.Revolution).turn)
        assertManifold(ev.solid(stated).mesh, "opened body")
        assertEquals(Turn3.Full, (ev.solid(whole).feature as Feature3.Revolution).turn, "and the complete one is untouched")
        assertManifold(ev.solid(whole).mesh, "complete revolution")
    }

    // ---- 3. what is refused, and heals ----

    /** A zero-length interval builds nothing and says why, and heals when the angle is given (OP-3). */
    @Test
    fun aZeroSweepIsRefusedWithAReasonAndHeals() {
        val c = Construction()
        val sweep = c.parameter("sweep", 0.0.deg)
        val o = c.freePoint("axisO", 0.mm, 0.mm)
        val axis = c.direction(o, c.freePoint("axisX", 1.mm, 0.mm))
        val s = c.revolve(c.sketchOn(c.planeXY(), c.bar()), o, axis, sweep)
        val why = (Evaluator().resultOf(s) as? EvalResult.Invalid)?.reason
        assertNotNull(why, "a body swept through nothing is not a body")
        assertTrue(why.contains("sweeps none"), why)
        c.set(sweep, 30.0.deg)
        assertManifold(Evaluator().solid(s).mesh, "healed body")
    }

    /** More than a full turn is refused whichever way it goes round — it would fold through itself. */
    @Test
    fun moreThanAFullTurnIsRefusedEitherWay() {
        for (deg in listOf(370.0, -370.0)) {
            val why = (Evaluator().resultOf(Construction().turned(deg)) as? EvalResult.Invalid)?.reason
            assertNotNull(why, "$deg° must be refused")
            assertTrue(why.contains("full turn"), why)
        }
    }

    /**
     * An offset **beyond** a full turn is not refused: it is a rotation of the whole body, and 400° about an
     * axis is 40° about it. Nothing folds, so nothing declines.
     */
    @Test
    fun anOffsetBeyondAFullTurnIsJustAnotherPlaceToStart() {
        val far = mesh(Construction().turned(30.0, offsetDeg = 400.0))
        val near = mesh(Construction().turned(30.0, offsetDeg = 40.0))
        assertManifold(far, "offset 400°")
        assertClose(Geom3.volume(far), Geom3.volume(near), 1e-9, msg = "400° round is 40° round")
        val a = assertNotNull(Geom3.bounds(far))
        val b = assertNotNull(Geom3.bounds(near))
        assertClose(a.first.z, b.first.z, 1e-9, msg = "and in the same place")
        assertClose(a.second.z, b.second.z, 1e-9)
    }

    // ---- 4. the gesture, the panel and the file ----

    /** A wall footprint beside a drawn axis: the tool fixture, one plan drawing for every gesture below. */
    private fun barEditor(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", 10.0.mm)
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 60.0))
        ed.finishPath()
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 20.0))
        return ed
    }

    private fun Editor.revolveGesture() {
        setTool(Tools.REVOLVE)
        click(Vec2(15.0, 30.0))
        click(Vec2(0.0, 10.0))
    }

    /**
     * **The gesture with no angle typed builds the complete revolution** — the user's default — and the step
     * says so by carrying no scalar at all. A spelling, not a number: there is nothing in the file for a
     * later value to disagree with.
     */
    @Test
    fun theGestureWithNoAngleBuildsACompleteRevolutionAndSpellsItWithNoScalar() {
        val ed = barEditor()
        ed.revolveGesture()
        val solid = ed.solids().single()
        assertEquals(Turn3.Full, featureOf(solid).turn, "a kind, not an angle: ${ed.statusHint}")
        assertManifold(meshOf(solid), "complete revolution by gesture")

        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool revolve") }
        assertFalse(step.contains("scalar="), "the complete revolution states no angle: $step")
        assertFalse(step.contains("dofs="), "...and owns no offset either, because it has no start: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
        assertEquals(Turn3.Full, featureOf(DocumentFormat.load(text).elements.first { it.kind == ElementKind.SOLID }).turn)
    }

    /** One gesture, one undo layer — the complete revolution is one operation like any other. */
    @Test
    fun oneUndoTakesBackTheWholeRevolveGesture() {
        val ed = barEditor()
        ed.revolveGesture()
        assertEquals(1, ed.solids().size)
        ed.undo()
        assertEquals(0, ed.solids().size, "one press, one gesture")
    }

    /**
     * **A typed angle builds the partial**, and its offset is a freedom the *step* owns (OP-13): nobody
     * stated it, so it stands at zero, is restated in the file (`dofs=`) and is editable for ever through
     * the body's own fields. The complete revolution has no such field, because it has no start — the two
     * are mutually exclusive by construction rather than by a rule anybody has to check.
     */
    @Test
    fun aTypedAngleBuildsAPartialWhoseOffsetIsAFreedomOfTheStep() {
        val ed = barEditor()
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(0.0, 10.0))
        val solid = ed.solids().single()
        assertEquals(Turn3.Arc(0.0, PI / 2.0), featureOf(solid).turn, "the interval starts where it was drawn: ${ed.statusHint}")

        val offset = assertNotNull(ed.doc.ownFields(solid).firstOrNull { it.label == "offset" }, "the body carries its offset")
        assertTrue(offset.writable, "and it can be written")
        offset.write((-90.0).deg)
        assertEquals(Turn3.Arc(-PI / 2.0, 0.0), featureOf(solid).turn, "written negative, the body moves behind the plane")
        assertManifold(meshOf(solid), "offset body")

        val text = DocumentFormat.save(ed.doc)
        val step = text.lines().single { it.startsWith("tool revolve") }
        assertTrue(step.contains("scalar=\"angle\""), step)
        assertTrue(step.contains("dofs=-90deg"), "the offset is restated as the value it stands at: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
        assertEquals(
            Turn3.Arc(-PI / 2.0, 0.0),
            featureOf(DocumentFormat.load(text).elements.first { it.kind == ElementKind.SOLID }).turn,
            "and the reloaded body stands where it stood",
        )
    }

    /**
     * **One gesture, one undo layer — with the numbers in it.** Typing the angle and the offset makes two
     * parameters, and both are *half* of the operation they were typed for, so a single press takes the
     * body, the angle and the offset back together (the rule [Editor.checkpoint] states).
     */
    @Test
    fun oneUndoTakesBackTheBodyAndBothNumbersTypedForIt() {
        val ed = barEditor()
        val before = ed.doc.scalars.size
        ed.setTool(Tools.REVOLVE)
        ed.type("30")
        ed.type("45")
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(0.0, 10.0))
        assertEquals(before + 2, ed.doc.scalars.size, "the two typed numbers are parameters of the drawing")
        ed.undo()
        assertEquals(0, ed.solids().size, "one press, one gesture")
        assertEquals(before, ed.doc.scalars.size, "...and the numbers typed for it went with it")
    }

    /**
     * **What reads a revolution still reads it.** The plan hint is the *sketch* — where the profile was
     * drawn — which is what addresses the feature, and stays so for a body standing 45° away from it: the
     * drawing is where you click to reach the solid, and where the material actually is, is what the tool's
     * own note says. Since item 4 of the sphere queue the section machinery **names** the body's faces
     * instead of refusing them (OP-17), so the plane offset behind the drawing cuts the material it finds
     * there and offers it as inputs — the addressing claim above is untouched by that, which is the point.
     */
    @Test
    fun aBodyStatedByAnOffsetStillDrawsAndPicksWhereItWasSketched() {
        val c = Construction()
        val body = c.turned(-30.0, offsetDeg = -45.0)
        val feature = Evaluator().solid(body).feature as Feature3.Revolution
        assertEquals(
            feature.sketch.regions,
            feature.footprint,
            "the plan hint is the sketch as drawn, which is what a click reaches the body by",
        )
        val cut = c.section(body, c.planeOffset(c.planeXY(), c.parameter("h", (-15).mm)))
        val s = Evaluator().valueOf(cut) as? constructit.core.SectionValue ?: error("a section value")
        assertTrue(s.section.pieces.isNotEmpty(), "a body standing behind its drawing still sections where the material is")
        assertEquals(null, s.section.inputsRefusal, "a revolution names its faces now (queue item 4): ${s.section.inputsRefusal}")
        assertTrue(s.section.edges.isNotEmpty(), "…so the section has named faces to address")
        assertTrue(!s.section.approximated, "…and a cut perpendicular to the axis is exact, not chords")
    }

    /**
     * **The status line promises only what it will build.** With no angle stated the tool is about to build
     * the complete revolution, which never receives the offset slot — so naming an offset there would be a
     * promise it cannot keep. State the angle and the offset appears, because now there is one.
     */
    @Test
    fun theStatusLineNamesAnOffsetOnlyWhenThereWillBeOne() {
        val ed = barEditor()
        ed.setTool(Tools.REVOLVE)
        val whole = ed.currentHelp().substringAfter(" Using ")
        assertTrue(whole.startsWith("angle = 360° (default)"), whole)
        assertFalse(whole.contains("offset"), "a complete revolution has no start to offset: $whole")
        ed.type("90")
        val partial = ed.currentHelp().substringAfter(" Using ")
        assertTrue(partial.contains("offset = 0° (default)"), "with an angle stated there is an interval to place: $partial")
    }

    /** The complete revolution offers no offset field at all — a body with no start has nowhere to put one. */
    @Test
    fun aCompleteRevolutionOffersNoOffsetInThePanel() {
        val ed = barEditor()
        ed.revolveGesture()
        assertNull(
            ed.doc.ownFields(ed.solids().single()).firstOrNull { it.label == "offset" },
            "nothing to state: the body has no start",
        )
    }

    /**
     * **Both numbers stated in one gesture**, which is the other half of the offset being an ordinary slot:
     * type the angle, type the offset, click twice. Both become named parameters, so either can afterwards
     * be shared with anything else in the drawing (OP-5) — sharing a node is how this program says "the
     * same angle".
     */
    @Test
    fun theAngleAndTheOffsetCanBothBeTypedInTheGesture() {
        val ed = barEditor()
        ed.setTool(Tools.REVOLVE)
        ed.type("30")
        ed.type("45")
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(0.0, 10.0))
        val solid = ed.solids().single()
        assertClose(featureOf(solid).turn.let { (it as Turn3.Arc).start } * 180.0 / PI, 45.0, 1e-9, msg = "it starts where it was told")
        assertClose((featureOf(solid).turn as Turn3.Arc).end * 180.0 / PI, 75.0, 1e-9, msg = "and ends a sweep later")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().single { it.startsWith("tool revolve") }.contains("scalar=\"angle\",\"offset\""), text)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
    }

    /**
     * **A stray angle never becomes an offset** ([constructit.editor.ScalarSlot.typedOnly]). Two revolves in
     * one drawing, each with its own angle parameter picked from the panel: the second takes the angle it
     * was given and *not* the first one's angle as an offset, which is the accident two same-dimension slots
     * would otherwise be one click away from.
     */
    @Test
    fun aParameterLeftInThePanelNeverDriftsIntoTheOffset() {
        val ed = barEditor()
        ed.activeScalar = ed.doc.newParameter("first", 90.0.deg)
        ed.revolveGesture()
        ed.activeScalar = ed.doc.newParameter("second", 30.0.deg)
        ed.revolveGesture()
        val second = ed.solids().last()
        assertEquals(Turn3.Arc(0.0, PI / 6.0), featureOf(second).turn, "the second revolve starts on its own plane, not 90° away")
    }

    /**
     * **An old file means what it always meant.** The text is this build's own, with the one argument this
     * package added struck back out of it — which is exactly the file a build from before the package wrote:
     * two revolves, one at 360° and one at 30°, each spelled `scalar=` with no `dofs=` beside it. The first
     * is closed, the second is the sector it always was, and both re-save with the offset freedom they have
     * now gained, standing at the zero they always meant (OP-18: an argument that never existed cannot have
     * meant something else).
     */
    @Test
    fun aFileWrittenBeforeThisPackageKeepsItsAngleMeaning() {
        val ed = barEditor()
        ed.activeScalar = ed.doc.newParameter("turn", 360.0.deg)
        ed.revolveGesture()
        ed.activeScalar = ed.doc.newParameter("part", 30.0.deg)
        ed.revolveGesture()
        val text = DocumentFormat.save(ed.doc).replace(" dofs=0deg", "")
        assertFalse(text.contains("dofs="), "the pre-package spelling of both steps:\n$text")

        val doc = DocumentFormat.load(text)
        assertTrue(doc.loadNotes.isEmpty(), "nothing about it is ambiguous: ${doc.loadNotes}")
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size)
        assertEquals(Turn3.Arc(0.0, 2.0 * PI), featureOf(solids[0]).turn, "a stated 360° is still a stated angle")
        assertEquals(Turn3.Arc(0.0, PI / 6.0), featureOf(solids[1]).turn, "and 30° still means 30° from the drawing")
        for (s in solids) assertManifold(meshOf(s), "reloaded ${doc.nameOf(s)}")
        val twelfth = Geom3.volume(meshOf(solids[1]))
        val ring = Geom3.volume(meshOf(solids[0]))
        assertTrue(
            abs(ring - 12.0 * twelfth) / ring < 1e-4,
            "the full turn holds twelve of the 30° sectors ($ring vs ${12.0 * twelfth} mm³ — the two are inscribed at slightly different station counts)",
        )
        val again = DocumentFormat.save(doc)
        assertEquals(2, again.lines().count { it.startsWith("tool revolve") && it.contains("dofs=0deg") }, again)
        assertEquals(again, DocumentFormat.save(DocumentFormat.load(again)), "and it is a fixed point from there on")
    }

    // ---- 5. the user's plate: an interval stated by an offset lands where the plan says ----

    /**
     * The drawing this package came from ([ChainCutFixture]): a plate, a handle revolved 30° on the upright
     * space `plane1`, and a base line rotated by `angle4 = −15°` in the plan (`e32`).
     *
     * **The claim, and its derivation.** `plane1` stands on the base line `e4` with `v = +z`, so its normal
     * is `u × z`, which is `e4`'s direction turned **−90°** about the plan's `+z`. A positive revolve sweeps
     * toward that normal (see this class's header), so *one degree of revolve is one degree of plan rotation
     * the other way round* for this drawing — which is precisely why the user's handle went away from their
     * rotated line instead of onto it. Read off the model rather than assumed: the fixture's own 30° body
     * occupies plan angles −30°…0°.
     */
    @Test
    fun theRevolveSweepsOppositeToThePlanRotationBecauseOfWhereItsPlaneFaces() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val span = planAngleSpan(meshOf(named(ed.doc, "e30")))
        assertClose(span.first, -30.0, 1e-9, msg = "the 30° handle reaches 30° clockwise in plan")
        assertClose(span.second, 0.0, 1e-9, msg = "...starting on the plane it was drawn in")
        assertClose(planAngleOf(named(ed.doc, "e32")), -15.0, 1e-9, msg = "while the plan's own Rotate by −15° goes 15° clockwise")
    }

    /**
     * **The pairing the user asked for**: the same profile and the same axis, stated as `offset −30°,
     * sweep 30°`, so the body occupies `[−30°, 0]` about the axis — the mirror of the handle they had. Its
     * end section **at the interval's start** lies exactly in the vertical plane through the base line
     * rotated by **+30°** in the plan; +30 and not −30 because a revolve degree is a plan degree the other
     * way round here (the test above derives it), which is the sign the user now states instead of fighting.
     *
     * The measure is in millimetres, as the claim is: no vertex of the body crosses that plane, and exactly
     * the profile's six corners lie in it.
     */
    @Test
    fun anIntervalStatedByAnOffsetLandsItsEndSectionInThePlaneOfALineRotatedByTheSameAngle() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        // the partner line, rotated the way the user rotated theirs — geometry first, then the centre
        ed.activeScalar = ed.doc.newParameter("turn", 30.0.deg)
        ed.setTool(Tools.ROTATE)
        ed.click(Vec2(-133.90595700511545, -4.165800667679536))
        ed.click(Vec2(-13.510957005115916, 5.816699332320425))
        val rotated = ed.doc.elements.last { it.kind == ElementKind.LINE }
        assertClose(planAngleOf(rotated), 30.0, 1e-9, msg = "the partner of the user's −15°, the other way")

        val body = revolvedHandle(ed, sweepDeg = 30.0, offsetDeg = -30.0)
        assertManifold(meshOf(body), "the paired handle")
        assertEquals(Turn3.Arc(-PI / 6.0, 0.0), featureOf(body).turn, "the body occupies [offset, offset + angle]")

        val d = distancesToVerticalPlane(meshOf(body), rotated)
        assertTrue(d.max() <= 1e-6, "no part of the body crosses the rotated line's plane (worst ${d.max()} mm)")
        assertTrue(d.min() < -20.0, "and the body has real depth behind it (${d.min()} mm)")
        assertEquals(6, d.count { abs(it) <= 1e-6 }, "the end section is the profile's own six corners, in that plane")
    }

    /**
     * **The straddle, on the user's own −15°**: `offset −15°, sweep 30°` puts half the handle either side of
     * `plane1`, and its far end lands in the plane of `e32` — the line they had already rotated by −15°. The
     * symmetry is measured by reflecting the mesh in `plane1` and finding every reflected vertex back in the
     * body.
     */
    @Test
    fun aStraddlingIntervalIsSymmetricAboutThePlaneItWasDrawnOnAndEndsOnTheUsersLine() {
        val ed = Editor(DocumentFormat.load(ChainCutFixture.CIT))
        val body = revolvedHandle(ed, sweepDeg = 30.0, offsetDeg = -15.0)
        val mesh = meshOf(body)
        assertManifold(mesh, "the straddling handle")
        val span = planAngleSpan(mesh)
        assertClose(span.first, -15.0, 1e-9, msg = "15° clockwise in plan")
        assertClose(span.second, 15.0, 1e-9, msg = "...and 15° counter-clockwise: it straddles the drawing")

        val d = distancesToVerticalPlane(mesh, named(ed.doc, "e32"))
        assertEquals(6, d.count { abs(it) <= 1e-6 }, "one end section lies in the plane of the user's own rotated line")

        // the symmetry, honestly: reflect in plane1 and every vertex comes back
        val plane = featureOf(body).sketch.plane
        for (v in mesh.vertices) {
            val h = (v - plane.origin).dot(plane.normal)
            val mirrored = v - plane.normal * (2.0 * h)
            assertTrue(mesh.vertices.any { (it - mirrored).length() < 1e-9 }, "the reflection of $v in plane1 is a vertex too")
        }
    }

    /** The fixture's profile `e28` revolved about its axis `e29` again, with a stated interval. */
    private fun revolvedHandle(
        ed: Editor,
        sweepDeg: Double,
        offsetDeg: Double,
    ): Element {
        ed.setActiveSpace("plane1")
        ed.setTool(Tools.REVOLVE)
        ed.type("${abs(sweepDeg)}")
        ed.type("${abs(offsetDeg)}")
        ed.click(Vec2(84.9038970502295, 4.076096924511516))
        ed.click(Vec2(-13.262378894393418, 27.726098394917802))
        val made = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        // a negative number cannot be *typed* anywhere in this program (the pad takes digits and a dot), so
        // it is stated where every negative number is stated: as the parameter's own value in the panel
        if (offsetDeg < 0) ed.doc.setParameter(paramNamed(ed, "offset"), offsetDeg.deg)
        if (sweepDeg < 0) ed.doc.setParameter(paramNamed(ed, "angle"), sweepDeg.deg)
        return made
    }

    private fun paramNamed(
        ed: Editor,
        name: String,
    ): ScalarEntry = assertNotNull(ed.doc.scalars.lastOrNull { it.name == name }, "the gesture named a parameter $name")

    /** The plate's centre, which every plan angle below is measured about — the revolve axis, seen in plan. */
    private val plateCentre = Vec2(-13.75, 5.75)

    /** The base line `e4`'s plan direction: the zero of every plan angle here. */
    private val baseDir = Vec2(75.75 - -13.75, 13.25 - 5.75).normalized()

    private fun planAngle(p: Vec2): Double {
        val d = p - plateCentre
        return kotlin.math.atan2(baseDir.x * d.y - baseDir.y * d.x, baseDir.dot(d)) * 180.0 / PI
    }

    private fun planAngleSpan(mesh: Mesh3): Pair<Double, Double> {
        val a = mesh.vertices.map { planAngle(Vec2(it.x, it.y)) }
        return a.min() to a.max()
    }

    private fun planAngleOf(line: Element): Double {
        val dir = lineOf(line).dir
        return kotlin.math.atan2(baseDir.x * dir.y - baseDir.y * dir.x, baseDir.dot(dir)) * 180.0 / PI
    }

    /**
     * Every vertex's signed distance to the **vertical plane through [line]**, positive on the side plan
     * angles increase toward — so a body that ends on that plane has a maximum of zero.
     */
    private fun distancesToVerticalPlane(
        mesh: Mesh3,
        line: Element,
    ): List<Double> {
        val l = lineOf(line)
        val n = l.dir.perp()
        return mesh.vertices.map { (Vec2(it.x, it.y) - l.origin).dot(n) }
    }

    private fun lineOf(line: Element): Line =
        ((Evaluator().eval(line.ref.node) as EvalResult.Ok).value as LineValue).line

    // ---- 6. the stated limit, retired ----

    /**
     * **The limit OP-17 recorded is gone**: *"a partial Revolve in a face space still sweeps inward … the
     * honest fix is a `dir` argument on the feature, which is not built."* It is built, and it is not an
     * argument of its own — it is the *sign* of the angle, which is where a direction belongs in a program
     * whose freedoms are all numbers in a panel.
     *
     * The same profile on the same face of the same plate, revolved ±30° about a line drawn on that face:
     * one body stands out of the material and the other reaches into it, both watertight, mirror images of
     * each other in the face. Which of the two a positive angle gives is the face's own normal (out of the
     * material, since session 32 stopped flipping the frame) — and it no longer matters, because the other
     * one is one minus sign away.
     */
    @Test
    fun aPartialRevolveInAFaceSpaceGoesEitherWayNow() {
        val bodies =
            listOf(30.0, -30.0).map { deg ->
                val ed = Editor()
                ed.setTool(Tools.RECTANGLE)
                ed.click(Vec2(0.0, 0.0))
                ed.click(Vec2(80.0, 50.0))
                ed.activeScalar = ed.doc.newParameter("thickness", 20.0.mm)
                ed.setTool(Tools.EXTRUDE)
                ed.click(Vec2(40.0, 0.0))
                ed.setTool(Tools.SKETCH_ON_FACE)
                ed.click(Vec2(40.0, 0.0))
                ed.setTool(Tools.CIRCLE_R)
                ed.type("2.5")
                ed.click(Vec2(-15.0, 12.0))
                ed.setTool(Tools.LINE)
                ed.click(Vec2(-40.0, 2.0))
                ed.click(Vec2(-40.0, 20.0))
                ed.activeScalar = ed.doc.newParameter("sweep", deg.deg)
                ed.setTool(Tools.REVOLVE)
                ed.click(Vec2(-12.5, 12.0))
                ed.click(Vec2(-40.0, 10.0))
                assertEquals(2, ed.solids().size, "the $deg° body was built: ${ed.statusHint}")
                meshOf(ed.solids().last())
            }
        val out = assertNotNull(Geom3.bounds(bodies[0]))
        val into = assertNotNull(Geom3.bounds(bodies[1]))
        for (m in bodies) assertManifold(m, "face-space partial revolve")
        // the plate's material is at y > 0 and its front face is the plane y = 0
        assertClose(out.second.y, 0.0, 1e-9, msg = "a positive sweep leaves the face...")
        assertTrue(out.first.y < -13.0, "...and stands clear of the material (${out.first.y} mm)")
        assertClose(into.first.y, 0.0, 1e-9, msg = "a negative one starts at the same face...")
        assertTrue(into.second.y > 13.0, "...and reaches into the material instead (${into.second.y} mm)")
        assertClose(Geom3.volume(bodies[1]), Geom3.volume(bodies[0]), 1e-9, msg = "the two are mirror images")
    }
}
