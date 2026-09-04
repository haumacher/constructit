package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.SweepProfile
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The variable-section sweep as a gesture, a panel field and a file** (OP-26, session 77 — queue entry 7).
 *
 * The geometry is [SweepLawTest]'s; this is the other half of the claim. Four things are asserted and they
 * are the four the ruling names:
 *
 * - **a constant section is untouched** — a tube built with no law stores exactly the step it always stored,
 *   with no new argument at all, and a script written before this reading existed loads and builds
 *   identically (the frozen-literal rule, OP-18: absence is the old file);
 * - **a law is ordinary expression machinery** — it reads drawing parameters, follows them by recompute, is
 *   re-stamped in place when one is renamed, and stores verbatim;
 * - **`t` is a binder** and outranks a drawing scalar of that name, exactly as a function curve's does;
 * - **what cannot carry a law refuses by name**, which is where the swept cut says why it is not one of them.
 */
class SweepLawToolTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(el: Element): Mesh3 {
        @Suppress("UNCHECKED_CAST")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    /** A flat curve in space through the given plan points — [SweepToolTest]'s own fixture. */
    private fun routeThroughPlan(
        ed: Editor,
        vararg at: Vec2,
    ): Element {
        ed.setTool(Tools.POINT)
        for (p in at) ed.click(p)
        ed.setTool(Tools.CURVE3)
        for (p in at) ed.click(p)
        ed.key("Enter")
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the route was drawn: ${ed.statusHint}",
        )
    }

    private fun tubeAlong(
        ed: Editor,
        radius: String,
        at: Vec2,
    ): Element {
        ed.setTool(Tools.TUBE)
        ed.type(radius)
        ed.click(at)
        return assertNotNull(ed.solids().lastOrNull(), "the tube was built: ${ed.statusHint}")
    }

    /** The greatest distance any vertex on the plane `x = [x]` stands from the world x axis. */
    private fun ringRadius(
        mesh: Mesh3,
        x: Double,
    ): Double {
        val on = mesh.vertices.filter { kotlin.math.abs(it.x - x) < 1e-9 }
        assertTrue(on.size >= 3, "there is a ring at x = $x: ${on.size} vertices")
        return on.maxOf { hypot(it.y, it.z) }
    }

    // ---- 1. a constant section stores and builds exactly what it always did ----

    /**
     * **A tube whose radius is one number writes no `law=` at all**, and the script it writes is a script
     * every build since the tube existed would have written.
     *
     * That is the whole of the frozen-reading requirement, and it is asserted the only way it can be: the
     * text is compared against a **literal fixture** written out here as an older build would have produced
     * it, and the body that text loads to is compared vertex for vertex against the body the gesture built.
     */
    @Test
    fun aConstantTubeWritesNoLawAndAnOlderScriptBuildsTheIdenticalBody() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        val tube = tubeAlong(ed, "7", Vec2(60.0, 0.0))

        val once = DocumentFormat.save(ed.doc)
        val step = once.lines().first { it.startsWith("tool tube") }
        assertFalse(step.contains("law="), "a section of one size states none: $step")
        assertEquals("tool tube els=e3 clicks=60,0 scalar=\"radius\" dofs=0deg;0deg -> e4", step, "and the step is the one it always was")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "it round-trips byte for byte")

        // …and the same script, read as the older file it is byte for byte, builds the identical solid
        val back = DocumentFormat.load(once)
        val reloaded = back.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val after = Evaluator().solid(reloaded.ref as SolidRef).mesh
        val before = meshOf(tube)
        assertEquals(before.vertices, after.vertices, "vertex for vertex, in the same order")
        assertEquals(before.triangles, after.triangles, "and triangle for triangle")
        assertNull(back.sweepLawOf(reloaded), "and it carries no law")
    }

    // ---- 2. the law as a gesture ----

    /**
     * **A tapered handle in one field and one gesture**: arm `5mm * (1 - t/2)` in the panel, then the tube
     * tool's ordinary radius-and-click. What comes out is the cone frustum [SweepLawTest] asserts the
     * geometry of, carried by a step that stores the law verbatim.
     */
    @Test
    fun anArmedLawTapersTheTubeAndTheStepStoresItVerbatim() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        assertTrue(ed.setSectionLaw("5mm * (1 - t/2)"), "the law is armed: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("Armed"), "and the panel says so: ${ed.statusHint}")
        val tube = tubeAlong(ed, "5", Vec2(60.0, 0.0))
        assertTrue(ed.statusHint.contains("r(t) = 5mm * (1 - t/2)"), "the tool said what it made: ${ed.statusHint}")

        val mesh = meshOf(tube)
        assertManifold(mesh, "the tapered handle")
        assertClose(ringRadius(mesh, 0.0), 5.0, 1e-12, "the law at the start of the run")
        assertClose(ringRadius(mesh, 120.0), 2.5, 1e-12, "and at its end")

        val feature = assertNotNull(Evaluator().solid(tube.ref as SolidRef).feature as? Feature3.Sweep)
        val round = assertNotNull(feature.profile as? SweepProfile.Round)
        assertEquals("5mm * (1 - t/2)", assertNotNull(round.law).text, "the feature carries the law it was built with")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.contains("law=\"5mm * (1 - t/2)\"") }, "stored verbatim, quoted so it may breathe: $once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")

        // …and the reloaded body is the same body
        val back = DocumentFormat.load(once)
        val reloaded = back.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val after = Evaluator().solid(reloaded.ref as SolidRef).mesh
        assertEquals(mesh.vertices, after.vertices, "vertex for vertex")
        assertEquals("5mm * (1 - t/2)", assertNotNull(back.sweepLawOf(reloaded)).text, "and the law comes back with it")
    }

    /**
     * **The armed law is a tool option and stays armed** — like the picked scalar and the wall side, and for
     * a reason that is forced rather than chosen: the field is in the panel and the tool comes from the
     * palette, so arming a law and arming the tool are two acts with a pick reset between them.
     *
     * What makes stickiness safe is that it is **visible** (the very field that states it shows it) and that
     * clearing it is one blank Apply.
     */
    @Test
    fun theArmedLawIsAToolOptionAndIsClearedByABlankField() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        ed.setSectionLaw("5mm * (1 - t/2)")
        val tapered = tubeAlong(ed, "5", Vec2(60.0, 0.0))
        assertNotNull(ed.doc.sweepLawOf(tapered), "the first tube took the law")

        val second = tubeAlong(ed, "5", Vec2(60.0, 0.0))
        assertNotNull(ed.doc.sweepLawOf(second), "and so does the next, because the option is still armed")

        // …and the field says so, so nothing about it is hidden
        assertEquals("5mm * (1 - t/2)", ed.sectionLawText, "the field shows what is armed")
        assertTrue(ed.setSectionLaw(""), "and a blank Apply clears it: ${ed.statusHint}")
        assertEquals("", ed.sectionLawText, "the field is empty again")
        val plain = tubeAlong(ed, "5", Vec2(60.0, 0.0))
        assertNull(ed.doc.sweepLawOf(plain), "and the next tube is a section of one size: ${ed.statusHint}")
        assertManifold(meshOf(plain), "the untapered tube")
    }

    /**
     * **A section swept with a `scale(t)`** — the other reading of the one mechanism, through the ordinary
     * two-click sweep gesture.
     */
    @Test
    fun theSweepToolScalesAPickedAreaByItsLaw() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(120.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("6")
        ed.click(Vec2(0.0, -90.0))

        ed.setSectionLaw("1 - t/2")
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(6.0, -90.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("scaled by t -> 1 - t/2"), "and it said what it scaled by: ${ed.statusHint}")

        val mesh = meshOf(solid)
        assertManifold(mesh, "the tapered swept circle")

        // the circle is drawn 90 mm off the plan's origin, and the origin is what rides the run — so the
        // scale is about *that* point: the whole section, offset included, is half again as near at the end
        fun span(x: Double): Pair<Double, Double> {
            val on = mesh.vertices.filter { kotlin.math.abs(it.x - x) < 1e-9 }
            assertTrue(on.size >= 3, "there is a ring at x = $x: ${on.size}")
            val rs = on.map { hypot(it.y, it.z) }
            return rs.min() to rs.max()
        }
        val (lo0, hi0) = span(0.0)
        val (lo1, hi1) = span(120.0)
        // a picked *area* is carried as its tessellated boundary (OP-15's standing bargain), so the diameter
        // is the polygon's and is asserted to the tessellation's own chord tolerance
        assertClose(hi0 - lo0, 12.0, constructit.geom.GeomMath.TESS_TOL_MM, "the circle is its stated diameter at the start")
        assertClose(hi1 - lo1, 6.0, constructit.geom.GeomMath.TESS_TOL_MM, "and half of it at the end of the run")
        // …and the *ratio* is exact, because a rigid scale is one multiplication of the very same polygon
        assertClose(hi0 - lo0, 2.0 * (hi1 - lo1), 1e-12, "exactly twice, polygon for polygon")
        assertClose((hi0 + lo0) / 2.0, (hi1 + lo1), 1e-12, "and the offset halves with it — the scale is about the anchor")

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.lines().any { it.startsWith("tool sweep") && it.contains("law=\"1 - t/2\"") }, "stored on the sweep's own step: $once")
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")
    }

    // ---- 3. the law is ordinary expression machinery ----

    /**
     * **A law reads the drawing's parameters, follows them, and re-stamps when one is renamed** — the three
     * properties every other expression in this engine has (OP-7's naming authority, OP-18's stored text).
     *
     * The rename is the load-bearing half: the stored text is rewritten in place under the parameter's new
     * name, so `save → load → save` is still byte-equal *after* the rename and the file still opens.
     */
    @Test
    fun aLawFollowsTheParameterItReadsAndReStampsWhenThatIsRenamed() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", Quantity.mm(10.0))
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("d * (1 - t/2)")
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        assertClose(ringRadius(meshOf(tube), 0.0), 10.0, 1e-12, "the law is the parameter it reads")

        // …retyping the parameter re-tapers the body by nothing but the recompute every other edit uses
        assertTrue(ed.setParameter(d, 16.0), "the parameter is written: ${ed.statusHint}")
        assertClose(ringRadius(meshOf(tube), 0.0), 16.0, 1e-12, "and the taper followed it")
        assertClose(ringRadius(meshOf(tube), 100.0), 8.0, 1e-12, "at both ends at once")
        assertManifold(meshOf(tube), "the re-tapered tube")

        val beforeRename = DocumentFormat.save(ed.doc)
        assertTrue(beforeRename.lines().any { it.contains("law=\"d * (1 - t/2)\"") }, "stored under the name it reads: $beforeRename")

        assertEquals("module", ed.renameParameter(d, "module"), "the parameter is renamed: ${ed.statusHint}")
        val afterRename = DocumentFormat.save(ed.doc)
        assertTrue(afterRename.lines().any { it.contains("law=\"module * (1 - t/2)\"") }, "the law re-stamped in place: $afterRename")
        assertFalse(afterRename.contains("law=\"d *"), "and the old name is gone from it: $afterRename")
        assertEquals(afterRename, DocumentFormat.save(DocumentFormat.load(afterRename)), "and it is still byte-equal on a round trip")

        // the re-stamp is exact: the only characters that changed are the name's own
        val reloaded = DocumentFormat.load(afterRename)
        val body = reloaded.elements.last { it.kind == ElementKind.SOLID }
        assertClose(ringRadius(meshOf(body), 0.0), 16.0, 1e-12, "and the reloaded body is the same body")
    }

    /**
     * **`t` is the run's own parameter and outranks a drawing scalar called `t`** — the same test shape the
     * function-curve rule has, one feature on.
     */
    @Test
    fun theRunParameterOutranksADrawingScalarOfTheSameName() {
        val ed = Editor()
        ed.doc.newParameter("t", Quantity.mm(50.0))
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("5mm * (1 - t/2)")
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        val mesh = meshOf(tube)
        assertManifold(mesh, "the tube whose law shadows a drawing scalar")
        assertClose(ringRadius(mesh, 0.0), 5.0, 1e-12, "the start of the run is t = 0, not the drawing's 50 mm")
        assertClose(ringRadius(mesh, 100.0), 2.5, 1e-12, "and its end is t = 1")
        assertTrue(DocumentFormat.save(ed.doc).lines().none { it.startsWith("tool tube") && it.contains("scalar=\"t\"") }, "the law took no scalar input for t")
    }

    /**
     * …and **a rename that would capture the binder is refused by name**, with the cure — the identical rule
     * a function curve's `t` already has, because a name a rename allows must keep its reading for ever.
     */
    @Test
    fun aRenameThatWouldCaptureTheRunParameterIsRefused() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", Quantity.mm(10.0))
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("d * (1 - t/2)")
        tubeAlong(ed, "5", Vec2(50.0, 0.0))
        assertNull(ed.doc.renameParameter(d, "t"), "the rename is refused")
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("the run's own parameter"), "and says why: $why")
        assertEquals("d", d.name, "and the parameter keeps the name it had")
    }

    /**
     * **A law whose value goes non-positive part-way along is a state, not a failure** (OP-3): the step is
     * recorded, the status line quotes the reason and the station, and the body comes back the moment the
     * parameter it reads does.
     */
    @Test
    fun aLawThatCrossesZeroSaysSoAndHeals() {
        val ed = Editor()
        val d = ed.doc.newParameter("d", Quantity.mm(10.0))
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("d - 8mm * t")
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        assertManifold(meshOf(tube), "the tube while its law is positive")

        // 6 mm taken off a 5 mm start crosses zero five-sixths of the way along
        assertTrue(ed.setParameter(d, 5.0), "the parameter is written: ${ed.statusHint}")
        val why = assertNotNull((Evaluator().eval(tube.ref.node) as? EvalResult.Invalid)?.reason, "the body says it has no size")
        assertTrue(why.contains("a tube needs a positive radius"), "in the constant refusal's words: $why")
        assertTrue(why.contains("r(t) = d - 8mm * t"), "quoting the law: $why")
        assertTrue(why.contains("along the run"), "and naming the station: $why")

        assertTrue(ed.setParameter(d, 12.0), "and the parameter moves back")
        assertNotNull(Evaluator().solid(tube.ref as SolidRef).mesh, "the body heals")
        assertManifold(meshOf(tube), "the healed tube")
    }

    /** **A text that is not an expression is refused by name and builds nothing** — before anything exists. */
    @Test
    fun aMalformedLawIsRefusedByNameAndBuildsNothing() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("5mm * (1 - ")
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(50.0, 0.0))
        assertEquals(0, ed.solids().size, "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("can't read the section's size"), "and it said why: ${ed.statusHint}")
    }

    /** …and a **name the drawing does not carry** is the ordinary unknown-name refusal, with its cure. */
    @Test
    fun aLawNamingNothingIsRefusedWithTheCure() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("wall * (1 - t/2)")
        ed.setTool(Tools.TUBE)
        ed.type("5")
        ed.click(Vec2(50.0, 0.0))
        assertEquals(0, ed.solids().size, "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("wall"), "and it named what it could not find: ${ed.statusHint}")
    }

    // ---- 4. the panel re-opens a law for editing ----

    /**
     * **The panel shows a swept body's own law and re-states it in place** — the edit that keeps the body's
     * identity, its name and everything built on it, in one undo step (the re-stamp precedent of OP-23 and
     * GitHub #7).
     */
    @Test
    fun aSweptBodysLawIsShownAndReStatedInPlace() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        val name = ed.doc.nameOf(tube)

        // select it by its own footprint, which is what the panel field follows
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 5.0))
        assertEquals(tube, ed.selection, "the tube is selected: ${ed.statusHint}")
        assertEquals("", ed.sectionLawText, "and it has no law yet")

        assertTrue(ed.setSectionLaw("5mm * (1 - t/2)"), "the law is stated on it: ${ed.statusHint}")
        val tapered = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "the body kept its name")
        assertEquals("5mm * (1 - t/2)", assertNotNull(ed.doc.sweepLawOf(tapered)).text, "and carries the law")
        assertClose(ringRadius(meshOf(tapered), 100.0), 2.5, 1e-12, "and tapers")
        assertManifold(meshOf(tapered), "the re-lawed tube")
        assertEquals(1, ed.solids().size, "no second body was made")

        // one undo takes the whole edit back
        assertTrue(ed.undo(), "the law is taken back")
        val plain = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "the body is still there")
        assertNull(ed.doc.sweepLawOf(plain), "and is a section of one size again")
        assertClose(ringRadius(meshOf(plain), 100.0), 5.0, 1e-12, "at the radius it was typed with")
    }

    /** …and **a blank field takes the law away again**, which is the section of one size back. */
    @Test
    fun aBlankFieldTakesTheLawAway() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("5mm * (1 - t/2)")
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        val name = ed.doc.nameOf(tube)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 5.0))
        assertEquals(tube, ed.selection, "the tube is selected: ${ed.statusHint}")
        assertEquals("5mm * (1 - t/2)", ed.sectionLawText, "the field shows the body's own law")

        assertTrue(ed.setSectionLaw(""), "and clearing it is accepted: ${ed.statusHint}")
        val plain = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name })
        assertNull(ed.doc.sweepLawOf(plain), "the law is gone")
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("law="), "and so is the argument: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "which is byte-equal again")
        assertClose(ringRadius(meshOf(plain), 100.0), 5.0, 1e-12, "and the body is the constant tube")
    }

    // ---- 5. what cannot carry a law refuses by name ----

    /**
     * **The swept cut is this package's recorded cut** (OP-22's extension, step 2 — see DESIGN.md), and it
     * refuses a size law *in the app*, with the reason and the way round it.
     *
     * Its section is a chain whose reach across the solid is **derived from the solid's own extent** and
     * solved together with the relevant span of the route in one fixed-point loop, so a section that scaled
     * would move that derived reach station by station. Carrying half of that would be worse than saying so.
     */
    @Test
    fun aSweptCutRefusesASizeLawByNameAndNamesTheWayRound() {
        if (!MeshBool.available) return
        val ed = Editor()

        // …a prism first, which is the generic half of the same refusal: a size law over a run belongs to a
        // swept body, and everything else says where to go instead
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(100.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("depth", Quantity.mm(40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(70.0, 0.0))
        val block = assertNotNull(ed.solids().lastOrNull(), "the block was built: ${ed.statusHint}")
        assertNull(ed.doc.sweepLawRestated(block, "1 - t/2"), "a prism carries no law over a run")
        val notSwept = assertNotNull(ed.doc.takeNote())
        assertTrue(notSwept.contains("belongs to a swept body"), "and says where a law belongs: $notSwept")

        // [SweptCutToolTest]'s own fixture: a route climbing through the block, and the channel's section
        // drawn about the plan's origin — which is what puts it on the route
        for ((base, h) in listOf(Vec2(70.0, 20.0) to "10", Vec2(70.0, 20.0) to "30", Vec2(105.0, 20.0) to "70")) {
            ed.setTool(Tools.HEIGHT_POINT)
            ed.type(h)
            ed.click(base)
        }
        val vp = Viewport3(camera = Camera3(target = Vec3(80.0, 20.0, 30.0), distance = 420.0, yaw = -0.9, pitch = 0.5), widthPx = 800.0, heightPx = 600.0)
        vp.editor = ed
        vp.shown = true
        ed.setTool(Tools.CURVE3)
        for (p in listOf(Vec3(70.0, 20.0, 10.0), Vec3(70.0, 20.0, 30.0), Vec3(105.0, 20.0, 70.0))) {
            val at = assertNotNull(vp.camera.project(p, vp.widthPx, vp.heightPx), "$p projects")
            vp.pointerDown(at)
            vp.pointerUp(at)
        }
        ed.key("Enter")
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the route: ${ed.statusHint}")
        vp.shown = false
        ed.activeScalar = ed.doc.newParameter("r", Quantity.mm(8.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))

        ed.setTool(Tools.CUT_ALONG_CURVE)
        ed.click(Vec2(70.0, 0.0))
        ed.click(Vec2(8.0, 0.0))
        ed.click(Vec2(70.0, 20.0))
        ed.click(Vec2(30.0, 30.0))
        val cut = assertNotNull(ed.solids().lastOrNull { it !== block }, "the swept cut was built: ${ed.statusHint}")

        assertNull(ed.doc.sweepLawRestated(cut, "1 - t/2"), "and the swept cut refuses a law")
        val why = assertNotNull(ed.doc.takeNote())
        assertTrue(why.contains("a swept cut states no size of its own"), "with the reason: $why")
        assertTrue(why.contains("derived from the solid it cuts"), "which is where the derived reach comes from: $why")
        assertTrue(why.contains("*Sweep*"), "and the way round it: $why")
        assertTrue(why.contains("*Subtract*"), "which is two tools that exist: $why")
    }

    /**
     * **A law armed while a tool that carries none completes is refused, never dropped** — a taper that
     * silently vanished is the one failure a status line cannot recover from.
     */
    @Test
    fun aLawArmedForATheWrongToolIsRefusedRatherThanDropped() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, -30.0))
        ed.click(Vec2(120.0, 30.0))
        ed.setSectionLaw("1 - t/2")
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(60.0, -30.0))
        assertEquals(0, ed.solids().size, "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("carries no size law over a run"), "and it said so: ${ed.statusHint}")
    }

    /** **A `law=` on a step whose tool carries none is refused at load** rather than silently ignored. */
    @Test
    fun aLawOnAToolThatCarriesNoneIsRefusedAtLoad() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, -30.0))
        ed.click(Vec2(120.0, 30.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(60.0, -30.0))
        val text = DocumentFormat.save(ed.doc)
        val tampered =
            text.lines().joinToString("\n") { line ->
                if (line.startsWith("tool extrude")) line.replace(" -> ", " law=\"1 - t/2\" -> ") else line
            }
        val err =
            try {
                DocumentFormat.load(tampered)
                null
            } catch (e: DocumentFormat.LoadError) {
                e
            }
        val why = assertNotNull(err, "a law nothing can carry is not loaded").message
        assertTrue(why!!.contains("carries no size law"), "and the load says which step and what: $why")
    }

    // ---- 6. an ordinary solid, still ----

    /** **One gesture, one undo**, and the route it rode stays — the tube's own rule with a law on it. */
    @Test
    fun oneUndoTakesTheTaperedGestureBack() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(110.0, 0.0))
        ed.setSectionLaw("6mm * (1 - t/2)")
        tubeAlong(ed, "6", Vec2(55.0, 0.0))
        assertEquals(1, ed.solids().size)

        assertTrue(ed.undo(), "the tapered tube is taken back")
        assertEquals(0, ed.solids().size, "one checkpoint covered the whole gesture")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "and the route stays")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(1, ed.solids().size)
        val back = ed.solids().single()
        assertEquals("6mm * (1 - t/2)", assertNotNull(ed.doc.sweepLawOf(back)).text, "with its law")
        assertManifold(meshOf(back), "the redone tapered tube")
    }

    /**
     * **A law reads a named point's coordinate too**, and deleting that point takes the body with it — the
     * delete cascade reaching a reference that lives inside a *text* (the session-76 rule, one feature on).
     *
     * That cascade is not a nicety: a `tool tube` step left behind naming a deleted point would produce a
     * file this build cannot open (OP-18), which is exactly the failure the expression half's probe found.
     */
    @Test
    fun aLawMayReadAPointsCoordinateAndTheDeleteCascadeReachesIt() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, -140.0))
        val p = assertNotNull(ed.doc.elements.lastOrNull { it.isPoint }, "the point was drawn")
        assertEquals("P", ed.doc.nameElement(p, "P"), "and named")
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        ed.setSectionLaw("P.x * (1 - t/2)")
        val tube = tubeAlong(ed, "5", Vec2(50.0, 0.0))
        assertClose(ringRadius(meshOf(tube), 0.0), 10.0, 1e-12, "the law reads the point's own x")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("law=\"P.x * (1 - t/2)\""), "stored under the point's current name: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and it round-trips")

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(10.0, -140.0))
        assertEquals(p, ed.selection, "the point is selected: ${ed.statusHint}")
        assertTrue(ed.deleteSelection(), "and deleted: ${ed.statusHint}")
        assertEquals(0, ed.solids().size, "the body went with the coordinate its law read: ${ed.statusHint}")
        val after = DocumentFormat.save(ed.doc)
        assertFalse(after.contains("law="), "and the file has no dangling reference: $after")
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "so it still loads")
    }

    /** **A tapered body draws a plan footprint that can be clicked**, at the size the law states there. */
    @Test
    fun aTaperedBodyIsPickedByItsOwnTaperedFootprint() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(140.0, 0.0))
        ed.setSectionLaw("4mm + 16mm * t")
        val tube = tubeAlong(ed, "4", Vec2(70.0, 0.0))
        val feature = assertNotNull(Evaluator().solid(tube.ref as SolidRef).feature as? Feature3.Sweep)
        val ys = feature.footprint.flatMap { r -> r.outer.elements.map { constructit.geom.GeomMath.startOf(it).y } }
        assertTrue(ys.any { kotlin.math.abs(kotlin.math.abs(it) - 20.0) < 0.3 }, "the hint reaches the wide end's own radius: $ys")

        // a click 18 mm off the route lands on the wide half and misses the thin one
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(130.0, 18.0))
        assertEquals(tube, ed.selection, "the wide end took the click: ${ed.statusHint}")
    }

    /** …and the mesh of a tapered body still passes through the 3D view unchanged in kind. */
    @Test
    fun aTaperedBodyIsAnOrdinarySolidInEveryOtherRespect() {
        val ed = Editor()
        routeThroughPlan(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 80.0))
        ed.setSectionLaw("8mm * (1 - t/2)")
        val tube = tubeAlong(ed, "8", Vec2(50.0, 0.0))
        assertManifold(meshOf(tube), "the tapered tube round a corner")
        assertEquals(ElementKind.SOLID, tube.kind, "it is a solid")
        assertEquals(1, ed.doc.setElementsVisible(listOf(tube), false), "it hides")
        assertEquals("Handle", ed.doc.nameElement(tube, "Handle"), "it renames")
        assertTrue(Geom3.volume(meshOf(tube)) > 0.0, "and it encloses material")
    }
}
