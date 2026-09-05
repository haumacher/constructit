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
import constructit.editor.SketchSpace
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshBool
import constructit.geom.Section3
import constructit.geom.SkinRow
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The loft over drawn sections as a gesture, and as a file** (OP-26's hull route, session 78 — queue
 * entry 1).
 *
 * The geometry is [SkinTest]'s; this is the other half of the claim, and it is the half that decides whether
 * the feature is a mechanism or a special case: *a section of a skin is an ordinary sketch on an ordinary
 * station*, so everything here is asserted through gestures that already existed — draw on a station, loft,
 * slide the station, match two curves, rename one, delete one, pick a face in the 3D view, sketch on it,
 * cut through it, save, reload, undo — and none of it through anything the skin brought with it.
 *
 * The one genuinely new gesture is *Match sections*, and it is asserted as what it is: an **edit** of the
 * loft's own step, one undo, stored by the two curves' script names.
 */
class SkinToolTest {
    private val wPx = 800.0
    private val hPx = 600.0

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

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(el: Element): Feature3 = Evaluator().solid(el.ref as SolidRef).feature

    private fun Editor.invalidReason(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun requireEngine() =
        assumeTrue(
            MeshBool.available,
            "a cut into a skin is cross-axis and needs the general boolean engine (Manifold, OP-9): ${MeshBool.status}",
        )

    private fun view(
        ed: Editor,
        cam: Camera3,
    ): Viewport3 {
        val vp = Viewport3(camera = cam, widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        return vp
    }

    private fun Viewport3.clickWorld(p: Vec3) {
        val s = assertNotNull(camera.project(p, widthPx, heightPx), "$p has an image on screen")
        pointerDown(s)
        pointerUp(s)
    }

    private fun Viewport3.clickPlane(at: Vec2) {
        val p = assertNotNull(projection(), "a working plane under the 3D view")
        val s = assertNotNull(p.toScreen(at), "$at has an image")
        pointerDown(s)
        pointerUp(s)
    }

    // ---- the fixture: a straight run in the plan, and stations across it ----

    /**
     * A straight run of 300 mm along +X through two plan points — [StationToolTest]'s own fixture, because a
     * skin's sections live on stations and a station's arithmetic should be arithmetic: the station `d` mm
     * along stands at `(d, 0, 0)` facing +X, its own u axis is world +Z and its v is −Y. So a rectangle drawn
     * there from `(-a, -a)` to `(a, a)` is the square `y, z ∈ [−a, a]` at `x = d`.
     */
    private fun run300(ed: Editor): Element {
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(300.0, 0.0))
        ed.key("Enter")
        return assertNotNull(
            ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE },
            "the run was drawn: ${ed.statusHint}",
        )
    }

    private fun Editor.stationOn(
        mm: String,
        at: Vec2 = Vec2(150.0, 0.0),
    ): SketchSpace {
        setActiveSpace("plan")
        setTool(Tools.STATION)
        type(mm)
        click(at)
        assertTrue(doc.activeSpace.isStation, "the station opened: $statusHint")
        return doc.activeSpace
    }

    /** A regular hexagon of circumradius [r] about the station's origin — six pieces, one per side. */
    private fun Editor.hexHere(r: Double): Element {
        count = 6
        setTool(Tools.POLYGON)
        click(Vec2(0.0, 0.0))
        click(Vec2(r, 0.0))
        return assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, "the section: $statusHint")
    }

    /** The midpoint of side [i] of [hexHere]'s hexagon — where a pick lands on that piece and no other. */
    private fun hexSide(
        r: Double,
        i: Int,
    ): Vec2 {
        val a = PI * i / 3.0
        val b = PI * (i + 1) / 3.0
        return Vec2(r * (cos(a) + cos(b)) / 2.0, r * (sin(a) + sin(b)) / 2.0)
    }

    /** A square of half-width [half], drawn on the station that is active — four pieces, one per side. */
    private fun Editor.squareHere(half: Double): Element {
        setTool(Tools.RECTANGLE)
        click(Vec2(-half, -half))
        click(Vec2(half, half))
        return assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, "the section: $statusHint")
    }

    /**
     * Two squares on two stations of one run — 40 × 40 at 60 mm and 20 × 20 at 240 mm — and the skin over
     * them, lofted by clicking the far section first so the **stations'** order is what decides the run.
     */
    private fun frustum(row: String = Tools.LOFT_RULED): Editor {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.squareHere(10.0)
        // the near section is clicked second, and the skin still runs 60 → 240
        ed.setTool(row)
        ed.click(Vec2(0.0, -10.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        return ed
    }

    // ---- 1. the acceptance: the body, the file, the undo ----

    /**
     * **A ruled skin over two stationed sections is the prismatoid between them, exactly** — and it is an
     * ordinary solid: it saves as one step, replays byte-equal, and one undo takes it back.
     */
    @Test
    fun aRuledSkinOverTwoStationsIsTheExactPrismatoidAndRoundTrips() {
        val ed = frustum()
        val body = assertNotNull(ed.solids().singleOrNull(), "one solid came out of it: ${ed.statusHint}")
        val mesh = meshOf(body)
        assertManifold(mesh, "the stationed frustum")
        // 180 mm of run between the two stations, 40 × 40 to 20 × 20: h/6 · (A₀ + 4·A½ + A₁)
        assertClose(Geom3.volume(mesh), 180.0 / 6.0 * (1600.0 + 4.0 * 900.0 + 400.0), 1e-9, "the frustum's volume")
        assertTrue(ed.statusHint.contains("2 sections skinned"), "the status line says what it made: ${ed.statusHint}")

        val text = DocumentFormat.save(ed.doc)
        val step = assertNotNull(text.lines().firstOrNull { it.contains("tool ${Tools.LOFT_RULED}") }, text)
        assertTrue("els=" in step, "the sections are named: $step")
        assertTrue("match=" !in step, "and nothing is matched, so nothing is written: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
        val reloaded = DocumentFormat.load(text)
        val there = assertNotNull(reloaded.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertClose(Geom3.volume(meshOf(there)), Geom3.volume(mesh), 1e-12, "and the reloaded body is the same body")

        ed.undo()
        assertTrue(ed.solids().isEmpty(), "one undo takes the loft back: ${ed.statusHint}")
        ed.redo()
        assertEquals(1, ed.solids().size, "and redo brings it back")
    }

    /** **A station slides and the skin follows** — the distance is an ordinary parameter, so nothing was built for this. */
    @Test
    fun aStationSlidesAndTheSkinFollows() {
        val ed = frustum()
        val body = ed.solids().single()
        val far = assertNotNull(ed.doc.spaceNamed("station2")?.along, "the second station's distance is a parameter")
        ed.doc.setParameter(far, 200.0.mm)
        assertManifold(meshOf(body), "the skin after its station slid")
        assertClose(
            Geom3.volume(meshOf(body)),
            140.0 / 6.0 * (1600.0 + 4.0 * 900.0 + 400.0),
            1e-9,
            "the skin is 140 mm long now",
        )
        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        val there = assertNotNull(reloaded.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertClose(Geom3.volume(meshOf(there)), Geom3.volume(meshOf(body)), 1e-12, "and the file says so too")
        // the slide was an uncheckpointed value edit, so the first undo returns it
        ed.undo()
        val back = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertClose(Geom3.volume(meshOf(back)), 180.0 / 6.0 * (1600.0 + 4.0 * 900.0 + 400.0), 1e-9, "undo slides it back")
    }

    // ---- 2. the correspondence: what the counts cannot decide, and the Match that does ----

    /**
     * **Differing piece counts refuse asking for a pair, and *Match sections* is the answer** — the whole of
     * the user's own mechanism, end to end: the refusal names both counts and both cures, the Match re-stamps
     * the loft's own step (one undo, same body, same name), and the pair is stored by the two curves' script
     * names.
     */
    @Test
    fun differingCountsWaitForAMatchAndTheMatchIsAnEditOfTheLoftsOwnStep() {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        val square = ed.squareHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.count = 3
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(12.0, 0.0))
        val triangle = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SEGMENT }, "the triangle: ${ed.statusHint}")

        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(-6.0, 0.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        val body = assertNotNull(ed.solids().singleOrNull(), "the loft is built and invalid, never refused: ${ed.statusHint}")
        val why = assertNotNull(ed.invalidReason(body), "…and it says why")
        assertTrue(why.contains("4 pieces") && why.contains("3"), "naming both counts: $why")
        assertTrue(why.contains("Match sections") && why.contains("Break"), "and both cures: $why")
        assertTrue(ed.statusHint.contains("nothing is drawn for it yet"), "the status line says so: ${ed.statusHint}")

        // …and the Match: one side of the square to one side of the triangle
        ed.setActiveSpace("station1")
        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(Vec2(0.0, -20.0))
        ed.setActiveSpace("station2")
        ed.click(Vec2(-6.0, 0.0))
        val healed = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the body is still there")
        assertNull(ed.invalidReason(healed), "and it heals: ${ed.statusHint}")
        assertManifold(meshOf(healed), "a square skinned to a triangle")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the Match built no second body")

        // the pair rides the loft's own step, by name
        val text = DocumentFormat.save(ed.doc)
        val step = assertNotNull(text.lines().firstOrNull { it.contains("tool ${Tools.LOFT_RULED}") }, text)
        assertTrue("match=" in step, "the correspondence is stored: $step")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "…and it round-trips byte-equal")
        assertTrue(
            text.lines().none { it.contains(Tools.MATCH_SECTIONS) },
            "the Match itself records no step of its own — it re-stamped the loft's: $text",
        )

        // one undo, and the skin is back to waiting for a pair — the body, its name and its step all kept
        ed.undo()
        val again = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertNotNull(ed.invalidReason(again), "the match is undone in one press: ${ed.statusHint}")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "and nothing else moved")
        assertNotNull(square)
        assertNotNull(triangle)
    }

    /**
     * **The stored pair is a reference, not a snapshot** — so a matched curve that is *renamed* keeps its
     * pair, and a matched curve that is *deleted* takes the skin with it and leaves a loadable file.
     *
     * The naming authority's own doing, and the reason the pairs are stored as element references: the writer
     * resolves them through the very map that declares every element ([Document.nameOf]), so the pair is
     * re-stamped by whatever moves a name — a user's label on top of it, a deleted step shifting every script
     * name after it — with nothing in this feature knowing about either.
     */
    @Test
    fun aRenamedMatchedCurveReStampsAndADeletedOneTakesTheSkin() {
        val ed = matched()
        val text = DocumentFormat.save(ed.doc)
        val pair = assertNotNull(text.lines().firstOrNull { it.contains("match=") }, text)
        val named =
            assertNotNull(pair.split(' ').firstOrNull { it.startsWith("match=") }, pair)
                .removePrefix("match=").split(',')
        assertEquals(2, named.size, "one stated pair, two names: $pair")
        val leg =
            assertNotNull(
                ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == named[0] },
                "the matched curve is named in the step: $pair",
            )
        assertManifold(meshOf(assertNotNull(ed.solids().lastOrNull())), "the matched skin")
        assertEquals("seamedge", ed.doc.nameElement(leg, "seamedge"), "the curve takes a name")
        val renamed = DocumentFormat.save(ed.doc)
        val stated = assertNotNull(renamed.lines().firstOrNull { it.contains("match=") }, renamed)
        assertTrue("seamedge" in renamed, "the name is recorded as its own step: " + renamed)
        assertTrue(
            stated.contains("match=" + ed.doc.nameOf(leg) + ","),
            "and the pair still names that very curve, through the naming authority: " + stated,
        )
        assertEquals(renamed, DocumentFormat.save(DocumentFormat.load(renamed)), "and the file still round-trips")

        // …and deleting the curve takes the section, the loft and the pair with it — the file stays loadable
        ed.setActiveSpace("station1")
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, -20.0))
        assertEquals(leg, ed.selection, "the matched curve is selected: ${ed.statusHint}")
        assertTrue(ed.deleteSelection(), "and deleted")
        val after = DocumentFormat.save(ed.doc)
        assertTrue("match=" !in after, "no orphan pair is left behind: $after")
        assertTrue(ed.doc.elements.none { it.kind == ElementKind.SOLID }, "the skin went with its section")
        assertEquals(after, DocumentFormat.save(DocumentFormat.load(after)), "and what is left loads")
    }

    /** A square skinned to a triangle, with the pair the loft asked for — the fixture the two tests share. */
    private fun matched(): Editor {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.count = 3
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(12.0, 0.0))
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(-6.0, 0.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(Vec2(0.0, -20.0))
        ed.setActiveSpace("station2")
        ed.click(Vec2(-6.0, 0.0))
        assertNull(
            (Evaluator().eval(assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID }).ref.node) as? EvalResult.Invalid)?.reason,
            "the matched skin builds: ${ed.statusHint}",
        )
        return ed
    }

    /**
     * **The first stated pair is the seam**: matching a corner to its neighbour turns the correspondence, and
     * stores — asserted here on the **square**, where the quarter turn it asks for is refused by name and the
     * pair is stored, replayed and byte-equal all the same.
     */
    @Test
    fun aStatedPairTwistsAnEqualCountSkinAndTheTwistIsStored() {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.squareHere(20.0)
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(0.0, -20.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        val body = ed.solids().single()
        assertClose(Geom3.volume(meshOf(body)), 180.0 * 1600.0, 1e-9, "an untwisted prism, exactly")

        // the bottom side of the near square to the *right* side of the far one — one piece round
        ed.setActiveSpace("station1")
        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(Vec2(0.0, -20.0))
        ed.setActiveSpace("station2")
        ed.click(Vec2(20.0, 0.0))
        val twisted = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        // **A quarter turn of four pieces is the one turn this correspondence cannot take** (session 82): the
        // polyhedron a ruled strip *is* folds back along every rail it shares, so the gesture refuses by name
        // rather than handing back a shell that encloses a third of what it draws. The pair is still stated,
        // still stored and still replayed — a refusal is a value (OP-3), and it heals at another vertex or on
        // a section with more pieces (`aStatedPairOneCornerRoundTwistsAHexagonalSkin`, below).
        val fold = assertNotNull((Evaluator().eval(twisted.ref.node) as? EvalResult.Invalid)?.reason, "the quarter turn is refused")
        assertTrue("folds this skin's shell back on itself" in fold, "and the fold is what it names: $fold")
        assertTrue("another vertex" in fold, "with the cure beside it: $fold")
        val text = DocumentFormat.save(ed.doc)
        assertTrue("match=" in text, "the stated twist is stored: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "byte-equal with the pair in it")
    }

    /**
     * **…and the same gesture on a section with more pieces is a body**: two hexagons, the pair stated one
     * corner round, is a sixth of a turn — the twisted antiprism, watertight and smaller than the prism.
     *
     * The pair with the square above and this one is the whole of the rule: what a stated pair may turn the
     * correspondence by is bounded by how many pieces the sections have, and a four-piece quarter turn is the
     * one that goes over it.
     */
    @Test
    fun aStatedPairOneCornerRoundTwistsAHexagonalSkin() {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.hexHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.hexHere(20.0)
        ed.setTool(Tools.LOFT_RULED)
        ed.click(hexSide(20.0, 0))
        ed.setActiveSpace("station1")
        ed.click(hexSide(20.0, 0))
        ed.key("Enter")
        val straight = Geom3.volume(meshOf(ed.solids().single()))
        assertClose(straight, 180.0 * 6.0 * 0.5 * 20.0 * 20.0 * kotlin.math.sin(PI / 3.0), 1e-9, "an untwisted hexagonal prism, exactly")

        ed.setActiveSpace("station1")
        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(hexSide(20.0, 0))
        ed.setActiveSpace("station2")
        ed.click(hexSide(20.0, 1))
        val twisted = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertNull((Evaluator().eval(twisted.ref.node) as? EvalResult.Invalid)?.reason, "a sixth of a turn is an ordinary skin: ${ed.statusHint}")
        assertManifold(meshOf(twisted), "the twisted hexagonal prism")
        assertTrue(Geom3.volume(meshOf(twisted)) < straight - 1000.0, "the twist is a smaller body: ${Geom3.volume(meshOf(twisted))}")
        val text = DocumentFormat.save(ed.doc)
        assertTrue("match=" in text, "the stated twist is stored: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "byte-equal with the pair in it")
    }

    // ---- 3. what the gesture refuses, by name ----

    /** A section that is not on a station is no section of a skin, and the refusal says what to do. */
    @Test
    fun aSectionThatIsNotOnAStationRefusesByName() {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.setActiveSpace("plan")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(200.0, -30.0))
        ed.click(Vec2(260.0, 30.0))
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(230.0, -30.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("does not stand across a run"), ed.statusHint)
        assertTrue(ed.statusHint.contains("*Station*"), "and it names the tool that makes one: ${ed.statusHint}")
    }

    /** Two sections on stations of two different runs are two skins, and it says so. */
    @Test
    fun sectionsOnTwoDifferentRunsRefuseNamingBoth() {
        val ed = Editor()
        val first = run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.setActiveSpace("plan")
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(300.0, 90.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 90.0))
        ed.click(Vec2(300.0, 90.0))
        ed.key("Enter")
        val second = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(second !== first)
        ed.setTool(Tools.STATION)
        ed.type("100")
        ed.click(Vec2(150.0, 90.0))
        ed.squareHere(10.0)
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(0.0, -10.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        ed.key("Enter")
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("one skin runs over stations of"), ed.statusHint)
    }

    /** A **ring** run has no first and no last section to cap, and that is the first slice's stated cut. */
    @Test
    fun aClosedRunRefusesInTheFirstSlice() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.type("100")
        ed.click(Vec2(0.0, 0.0))
        val ring = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.CIRCLE })
        ed.setTool(Tools.STATION)
        ed.type("50")
        ed.click(Vec2(100.0, 0.0))
        assertTrue(ed.doc.activeSpace.isStation, "a station on a ring is fine: ${ed.statusHint}")
        ed.squareHere(10.0)
        ed.setActiveSpace("plan")
        ed.setTool(Tools.STATION)
        ed.type("200")
        ed.click(Vec2(100.0, 0.0))
        ed.squareHere(8.0)
        ed.setTool(Tools.LOFT_RULED)
        ed.click(Vec2(0.0, -8.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -10.0))
        ed.key("Enter")
        assertTrue(ed.solids().isEmpty(), "nothing was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("closed run"), ed.statusHint)
        assertNotNull(ring)
    }

    /** *Match sections* with no loft between the two curves says so, and names the two rows that make one. */
    @Test
    fun matchingWithNoLoftBetweenTheSectionsRefusesByName() {
        val ed = Editor()
        run300(ed)
        ed.stationOn("60", Vec2(100.0, 0.0))
        ed.squareHere(20.0)
        ed.stationOn("240", Vec2(200.0, 0.0))
        ed.squareHere(10.0)
        ed.setTool(Tools.MATCH_SECTIONS)
        ed.click(Vec2(0.0, -10.0))
        ed.setActiveSpace("station1")
        ed.click(Vec2(0.0, -20.0))
        assertTrue(ed.statusHint.contains("no loft runs between"), ed.statusHint)
        assertTrue(ed.statusHint.contains("Loft (ruled)"), "and names the row that makes one: ${ed.statusHint}")
    }

    // ---- 4. the faces: picked in the round, sketched on, cut through ----

    /**
     * **A skin's faces are the body's own**: a strip and a cap both answer a click in the 3D view with a
     * stored address, a sketch opens on the cap, and a *Cut* drilled there takes material out of the skin.
     */
    @Test
    fun aSkinsCapIsPickedInThe3DViewAndDrilled() {
        requireEngine()
        val ed = frustum()
        val body = ed.solids().single()
        val before = Geom3.volume(meshOf(body))
        val feature = featureOf(body) as Feature3.Skin

        // the strip on the −Y flank, and the cap at the near end, each named by a ray
        val onStrip = assertNotNull(Section3.faceAt(feature, Vec3(150.0, -15.0, 0.0), Vec3.Y, 1.0).first, "a strip answers a ray")
        assertTrue(onStrip.patch.name is constructit.geom.FaceName.SkinBand, "…as a strip: ${onStrip.patch.name.label}")
        assertNotNull(onStrip.piece, "with a stored address")
        val onCap = assertNotNull(Section3.faceAt(feature, Vec3(60.0, 0.0, 0.0), Vec3.X, 1.0).first, "the near cap answers one")
        assertTrue(onCap.patch.name is constructit.geom.FaceName.SectionFace, "…as a section face: ${onCap.patch.name.label}")

        // …and the ordinary gesture: sketch on that cap from a camera that can see it, then drill
        val vp = view(ed, Camera3(target = Vec3(60.0, 0.0, 0.0), distance = 400.0, yaw = 3.0, pitch = 0.3))
        ed.setTool(Tools.SKETCH_ON_FACE)
        vp.clickWorld(Vec3(60.0, 0.0, 0.0))
        assertTrue(ed.doc.activeSpace.isFace, "the cap opened as a working plane: ${ed.statusHint}")
        assertEquals(onCap.piece, ed.doc.activeSpace.piece, "at the address the pick recorded")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        vp.clickPlane(Vec2(0.0, 0.0))
        ed.setTool(Tools.CUT)
        ed.type("30")
        vp.clickPlane(Vec2(5.0, 0.0))
        val drilled = assertNotNull(ed.solids().lastOrNull { it !== body }, "the bore was drilled: ${ed.statusHint}")
        val mesh = meshOf(drilled)
        assertManifold(mesh, "a skin drilled through its own cap")
        val took = before - Geom3.volume(mesh)
        assertClose(took, kotlin.math.PI * 25.0 * 30.0, tol = kotlin.math.PI * 25.0 * 30.0 * 0.02, msg = "the 5 mm bore, 30 deep")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the whole story replays byte-equal")
    }

    // ---- 5. the faired row ----

    /** **The faired row passes through its middle section** and is a different body from the ruled one. */
    @Test
    fun aFairedSkinOverThreeStationsPassesThroughTheMiddleOne() {
        fun build(row: String): Editor {
            val ed = Editor()
            run300(ed)
            ed.stationOn("30", Vec2(50.0, 0.0))
            ed.squareHere(10.0)
            ed.stationOn("150", Vec2(150.0, 0.0))
            ed.squareHere(25.0)
            ed.stationOn("270", Vec2(250.0, 0.0))
            ed.squareHere(10.0)
            ed.setTool(row)
            ed.click(Vec2(0.0, -10.0))
            ed.setActiveSpace("station1")
            ed.click(Vec2(0.0, -10.0))
            ed.setActiveSpace("station2")
            ed.click(Vec2(0.0, -25.0))
            ed.key("Enter")
            return ed
        }
        val ruled = build(Tools.LOFT_RULED)
        val faired = build(Tools.LOFT_FAIRED)
        val a = meshOf(ruled.solids().single())
        val b = meshOf(faired.solids().single())
        assertManifold(a, "the ruled barrel")
        assertManifold(b, "the faired barrel")
        for (x in listOf(30.0, 150.0, 270.0)) {
            val half = if (x == 150.0) 25.0 else 10.0
            assertTrue(
                b.vertices.any { (it - Vec3(x, -half, -half)).length() <= 1e-9 },
                "the faired skin passes through the station at $x",
            )
        }
        assertTrue(Geom3.volume(b) > Geom3.volume(a) + 1000.0, "and swells between them: ${Geom3.volume(b)} vs ${Geom3.volume(a)}")
        val text = DocumentFormat.save(faired.doc)
        assertTrue(text.lines().any { it.contains("tool ${Tools.LOFT_FAIRED}") }, "the row is the id: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "byte-equal")
    }

    /**
     * **A `match=` on a step whose tool carries none is refused at load**, never dropped — the size law's own
     * rule and its own reason: a file that says *matched* and builds *unmatched* is the one thing a load may
     * not do (OP-18).
     */
    @Test
    fun aMatchOnAToolThatCarriesNoneIsRefusedAtLoad() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(Vec2(20.0, 0.0))
        assertEquals(1, ed.solids().size, "the plate: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        val line = assertNotNull(text.lines().firstOrNull { it.contains("tool ${Tools.EXTRUDE}") }, text)
        val broken = text.replace(line, line.replace("tool ${Tools.EXTRUDE}", "tool ${Tools.EXTRUDE} match=e1,e2"))
        val threw =
            try {
                DocumentFormat.load(broken)
                null
            } catch (e: Exception) {
                e.message
            }
        assertTrue(assertNotNull(threw).contains("no correspondence"), "refused in its own words: $threw")
        assertEquals(2, SkinRow.entries.size, "two rows, one mechanism")
    }
}
