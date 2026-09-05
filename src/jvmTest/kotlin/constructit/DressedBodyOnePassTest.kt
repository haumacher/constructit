package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **One pass whatever the sizes and the kinds** (OP-30's recorded next step, part A).
 *
 * A dressing used to be a *chain* of passes: consecutive entries sharing a kind, a size node and a profile
 * node were one `Blend3.blended` call and the rest chained on top of them. That collapsed the reporter's own
 * file whole — his seven roundings all read the one parameter `r` — but for a dressing of several sizes it
 * left three things standing, and all three are the same missing fact: the pass could only say **one**
 * section for its whole target list.
 *
 * 1. one boolean per *pass* rather than one per **group**, so two roundings that share a corner but are
 *    declared either side of a third could never be one tool;
 * 2. no corner *looked for* between a fillet r₁ and a fillet r₂ that meet — they were in different levels,
 *    and the pair was only ever seen by [Blend3.chainPieces] from above;
 * 3. no exact **level section** through the result, because the second pass's outline corrections land on
 *    faces the first already corrected and the composition of two levels is not stated (the queued
 *    section-limit (b)).
 *
 * `Feature3.Blend` now carries a **section per target**, so a dressing is always one call: every band fresh
 * in one pass, every corner looked for among all of them, one stitched tool per group, and each face's
 * outline corrected once from every band that cut it.
 *
 * **What a corner between two different sections is has not changed, and nothing here invents a
 * variable-section one.** Two sections that are *congruent* where they meet make a corner — a crossing, a
 * pivot, a ball, [Blend3]'s own catalogue; a non-congruent pair of built-ins is left to the boolean to trim,
 * exactly as every pair was before session 79; and a pair where one side is a **drawn profile** is refused
 * by name. What is new is only that the pair is now seen in one pass instead of in two levels.
 */
class DressedBodyOnePassTest {
    /** GitHub #35's attached file, verbatim — seven roundings, all by the one parameter `r`. */
    private val reportedScript =
        """
constructit 5
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 41.999800864975384,15.375 -> e4,e5
orthovertex 41.999800864975384,-11.775083491926196 -> e6,e7
orthovertex -5.521648428788623,-11.775083491926196 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
param "r" = 5mm
tool filletedge els=e13 clicks=-42.670739764447546,-4.867038301721209 scalar="r" signs=12;-1;1;0;1 -> e14
tool filletedge els=e14 clicks=-31.533048614089623,14.504582242265968 scalar="r" signs=13;-1;1;0;1 -> e15
tool filletedge els=e15 clicks=-15.209120508301623,-22.09480593584297 scalar="r" signs=1;-1;1;0;1 -> e16
tool filletedge els=e16 clicks=-1.6336097588108203,35.97358839564461 scalar="r" signs=14;-1;1;0;1 -> e17
tool filletedge els=e17 clicks=-11.301657028615722,8.858099327956722 scalar="r" signs=2;-1;1;0;-1 -> e18
tool filletedge els=e18 clicks=56.88568755568988,21.122250050431774 scalar="r" signs=3;-1;1;0;1 -> e19
tool filletedge els=e19 clicks=52.78762484641989,32.49678119098172 scalar="r" signs=15;-1;1;0;1 -> e20
""".trimStart()

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** A 40 x 30 plate 20 deep, drawn and extruded by gestures — `DressedBodyTest`'s own fixture. */
    private fun plate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        return ed
    }

    /** Where each of the plate's four top rim edges is clicked. */
    private val rim = mapOf(8 to Vec2(20.0, 0.0), 9 to Vec2(40.0, 15.0), 10 to Vec2(20.0, 30.0), 11 to Vec2(0.0, 15.0))

    /** Roundings of the plate's rim, one gesture per `(edge, radius)` pair, each on the dressed body. */
    private fun dressed(
        gestures: List<Pair<Int, Double>>,
        tool: String = Tools.BLEND_EDGE,
    ): Editor {
        val ed = plate()
        for ((i, g) in gestures.withIndex()) {
            ed.activeScalar = ed.doc.newParameter("r$i", g.second.mm)
            ed.setTool(tool)
            ed.click(assertNotNull(rim[g.first], "rim edge ${g.first}"))
        }
        return ed
    }

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    private fun bodyOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun featureOf(el: Element): Feature3 = (Evaluator().valueOf(el.ref) as SolidValue).solid.feature

    private fun blendOf(doc: Document): Feature3.Blend = assertNotNull(featureOf(bodyOf(doc)) as? Feature3.Blend, "a dress-up feature")

    /**
     * **The chain, on this very build** — the plate's rim roundings written as one *solid per radius*, the
     * way every dressing of several sizes was built before this package.
     *
     * A pre-[DocumentFormat.DRESSED_BODY_VERSION] script whose intermediate bodies something else reads keeps
     * the chain it was written as (OP-30's migration is taken only where it is lossless), so the chained
     * answer is measured here rather than from a second checkout — the same device `DressedBodyTest` and
     * `BlendChainCostTest` already use.
     */
    private fun chainOf(gestures: List<Pair<Int, Double>>): String {
        val sb =
            StringBuilder(
                """
constructit 5
orthostart 0,0 -> e1
orthovertex 40,0 -> e2,e3
orthovertex 40,30 -> e4,e5
orthovertex 0,30 -> e6,e7
orthoclose -> e8
param "depth" = 20mm
tool extrude els=e3 clicks=20,0 scalar="depth" -> e9
""".trimStart(),
            )
        var prev = "e9"
        var n = 10
        val made = ArrayList<String>()
        for ((i, g) in gestures.withIndex()) {
            val at = assertNotNull(rim[g.first], "rim edge ${g.first}")
            sb.append("param \"r$i\" = ${g.second}mm\n")
            sb.append("tool filletedge els=$prev clicks=${at.x},${at.y} scalar=\"r$i\" signs=${g.first};-1;1;0;1 -> e$n\n")
            made.add("e$n")
            prev = "e$n"
            n++
        }
        // one `show` naming every intermediate is what keeps the chain a chain
        sb.append("show els=${made.dropLast(1).joinToString(",")}\n")
        return sb.toString()
    }

    // ---- 1. three sizes, one feature, and the very same body ----

    @Test
    fun threeSizesOnOneRimAreOneFeatureWithTheChainsOwnBody() {
        val ed = dressed(listOf(8 to 3.0, 9 to 4.0, 10 to 5.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the plate and one dressed body: ${ed.statusHint}")
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "a row per rounding")

        // **one** feature, three targets, three sections — not a chain of three
        val f = blendOf(ed.doc)
        assertEquals(listOf(8, 9, 10), f.targets, "three targets in the gestures' own order")
        assertEquals(listOf(3.0, 4.0, 5.0), f.sections.map { it.size }, "…each with its own section")
        assertTrue(f.base !is Feature3.Blend, "and nothing chained under it: ${f.base}")
        assertNull((Evaluator().eval(bodyOf(ed.doc).ref.node) as? EvalResult.Invalid)?.reason, "the body builds")

        // …and it is the body the chain built, to a part in a million
        val chain = DocumentFormat.load(chainOf(listOf(8 to 3.0, 9 to 4.0, 10 to 5.0)))
        assertEquals(4, chain.elements.count { it.kind == ElementKind.SOLID }, "the reference really is a chain: ${chain.loadNotes}")
        val chained = volumeOf(bodyOf(chain))
        val once = volumeOf(bodyOf(ed.doc))
        assertTrue(abs(once - chained) <= abs(chained) * 1e-6, "one pass = the chain's body: $once vs $chained")
        assertManifold(Evaluator().solid(bodyOf(ed.doc).ref as SolidRef).mesh, "three sizes in one pass")
    }

    /**
     * **One boolean per group, and a group is a fact about which bands share a corner** — never about the
     * order the gestures were made in.
     *
     * The measurement that separates the two readings: rim edges 8 and 9 **meet**, and they are rounded by
     * the same parameter, so they belong in one stitched tool. Edge 10 is opposite 8, shares a corner with
     * nothing here, and is rounded by a parameter of its own — and it is declared **between** the two. The
     * old grouping was by kind, size node *and adjacency in the entry list*, so this was three passes and
     * three booleans; one pass makes it two groups and two booleans, and the corner between 8 and 9 is built
     * rather than left to the engine.
     */
    @Test
    fun oneBooleanPerGroupAndNotPerPass() {
        val ed = dressed(listOf(8 to 3.0, 10 to 4.0, 9 to 3.0))
        val tip = bodyOf(ed.doc).ref as SolidRef
        // the body is counted on a **fresh** recompute, so the work is this evaluation's and not a memo's:
        // the plate's depth is retyped, which invalidates everything and leaves every radius where it was
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "depth" }, 22.0.mm)
        Blend3.resetDerivations()
        Geom3.resetCombines()
        val ev = Evaluator()
        assertTrue(ev.eval(tip.node) is EvalResult.Ok, "the body builds: ${ed.statusHint}")
        assertEquals(2, Geom3.combines, "the two that share a corner are one tool; the opposite edge is its own")
        assertEquals(1, Blend3.derivations, "…and one dressed list for the whole dressing, because there is no chain")
        assertManifold(ev.solid(tip).mesh, "two groups in one pass")

        // the same three roundings by **one** parameter are congruent all round, so 8–9 and 9–10 are both
        // built corners and the three bands are one stitched tool — one boolean, as they always were
        val shared = plate()
        val r = shared.doc.newParameter("r", 3.0.mm)
        for (at in listOf(rim[8]!!, rim[10]!!, rim[9]!!)) {
            shared.activeScalar = r
            shared.setTool(Tools.BLEND_EDGE)
            shared.click(at)
        }
        val one = bodyOf(shared.doc).ref as SolidRef
        shared.doc.setParameter(shared.doc.scalars.first { it.name == "depth" }, 22.0.mm)
        Geom3.resetCombines()
        val ev2 = Evaluator()
        assertTrue(ev2.eval(one.node) is EvalResult.Ok, "and so does the shared-radius body: ${shared.statusHint}")
        assertEquals(1, Geom3.combines, "three congruent bands joined at two corners are one group")
    }

    // ---- 2. two sizes that meet: built, flap-free, and the boolean's own answer ----

    /**
     * **Two fillets of different radii that meet at a corner.** The rule is the one this drawing has always
     * had and this package does not touch: the two sections are *not* congruent where they meet, so no
     * corner is built and the pair is left to the boolean to trim — which is what every pair did before
     * session 79 and what the chain did here. What one pass changes is that the pair is now **seen** in one
     * call, so the body is one feature and each of the two faces they cut has its outline corrected from
     * both bands at once.
     */
    @Test
    fun twoFilletsOfDifferentRadiiThatMeetAreTheBooleansOwnAnswer() {
        val ed = dressed(listOf(8 to 3.0, 9 to 5.0))
        val f = blendOf(ed.doc)
        assertEquals(listOf(8, 9), f.targets, "one feature with both roundings")
        assertEquals(listOf(3.0, 5.0), f.sections.map { it.size })

        val tip = bodyOf(ed.doc).ref as SolidRef
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "depth" }, 22.0.mm)
        Geom3.resetCombines()
        val ev = Evaluator()
        assertTrue(ev.eval(tip.node) is EvalResult.Ok, "the body builds: ${ed.statusHint}")
        assertEquals(2, Geom3.combines, "no corner between two radii that are not congruent — two groups, two tools")
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "depth" }, 20.0.mm)
        assertManifold(ev.solid(tip).mesh, "two radii meeting at a corner")

        // the chain's own answer for the same two gestures, on this build
        val chain = DocumentFormat.load(chainOf(listOf(8 to 3.0, 9 to 5.0)))
        assertEquals(3, chain.elements.count { it.kind == ElementKind.SOLID }, "the reference is a chain of two")
        val chained = volumeOf(bodyOf(chain))
        val once = volumeOf(bodyOf(ed.doc))
        assertTrue(abs(once - chained) <= abs(chained) * 1e-6, "the same body: $once vs $chained")
    }

    /**
     * **A fillet meeting a drawn profile is still refused by name** — `Blend3`'s mixed corner, asked of the
     * one-pass call directly, since a section per target is what makes such a pair statable at all.
     *
     * One pass builds no corner between sections that are not the same thing on the face they share, and it
     * does not silently leave a drawn profile's own corner to the boolean either: where one side of the pair
     * *is* a drawn profile there is no congruent reading to fall back on, so the pass refuses and names the
     * two gestures that work. Nothing here invents a variable-section corner.
     */
    @Test
    fun aFilletMeetingADrawnProfileIsStillRefusedByName() {
        val doc = DocumentFormat.load(chainOf(listOf(8 to 3.0)).lines().dropLast(4).joinToString("\n") + "\n")
        val body = (Evaluator().valueOf(doc.elements.last { it.kind == ElementKind.SOLID }.ref) as SolidValue).solid
        val top = assertNotNull(Section3.faces(body.feature).first, "the plate's faces").first { it.name.label == "the top face" }.name

        val fillet = BlendSection(BlendKind.FILLET, 4.0)
        val drawn = BlendSection(BlendKind.PROFILE, 0.0, listOf(ProfileElement.Seg(Segment(Vec2(0.0, 4.0), Vec2(4.0, 0.0)))))
        val cf = assertNotNull(Blend3.choicesFor(body, listOf(8), fillet, top).first, "the fillet's own choice")
        val cp = assertNotNull(Blend3.choicesFor(body, listOf(9), drawn, top).first, "the profile's own choice")

        // rim edges 8 and 9 meet at a corner, and the two sections are not the same thing on the top face
        val (out, why) = Blend3.blended(body, body, listOf(8, 9), listOf(fillet, drawn), listOf(cf[0], cp[0]))
        assertNull(out, "the pair cannot be stated")
        assertTrue("not the same section on that face" in assertNotNull(why), "…and it says so by name: $why")
        assertTrue("Give both edges the same" in why, "…with the gesture that works: $why")

        // …and two fillets of *different radii* at the same corner are no refusal at all: they overlap and
        // the boolean trims them, exactly as every pair did before session 79
        val bigger = BlendSection(BlendKind.FILLET, 6.0)
        val cb = assertNotNull(Blend3.choicesFor(body, listOf(9), bigger, top).first, "the second fillet's choice")
        val (both, whyBoth) = Blend3.blended(body, body, listOf(8, 9), listOf(fillet, bigger), listOf(cf[0], cb[0]))
        assertNotNull(both, "two radii at one corner are built: $whyBoth")
        assertManifold(both.mesh, "two radii at one corner")
    }

    // ---- 3. the #35 fixture is unchanged ----

    /**
     * **One node, one pass, the same counts.** The reporter's seven roundings all read his one parameter, so
     * the grouping this package removes never applied to them: what `BlendChainCostTest` and
     * `DressedBodyTest` assert about that file must read exactly the same afterwards, and this is that
     * assertion made from the other side.
     */
    @Test
    fun theReportersOneNodeFileIsUnchanged() {
        val doc = DocumentFormat.load(reportedScript)
        assertEquals(2, doc.elements.count { it.kind == ElementKind.SOLID }, "one dressed body")
        val f = blendOf(doc)
        assertEquals(7, f.targets.size, "seven targets in one pass")
        assertEquals(setOf(5.0), f.sections.map { it.size }.toSet(), "…all of them the one radius")
        val tip = bodyOf(doc).ref as SolidRef
        doc.setParameter(doc.scalars.first { it.name == "h" }, 22.0.mm)
        Blend3.resetDerivations()
        Geom3.resetCombines()
        val ev = Evaluator()
        assertTrue(ev.eval(tip.node) is EvalResult.Ok, "the body builds")
        assertEquals(2, Geom3.combines, "the subtracted group round the top and the united fill — two, as before")
        assertEquals(1, Blend3.derivations, "one dressed list, as before")
        assertManifold(ev.solid(tip).mesh, "the reporter's file")
    }

    // ---- 4. a level section through a dressing of two sizes ----

    /**
     * **A level section through a dressing of two sizes is exact** — and the queued section limit **(b)**
     * turns out not to exist (DESIGN.md, *level sections through a rounded body*).
     *
     * (b) was recorded as *"the second level's trims land on faces the first already corrected, and the
     * correction is not composed"*, with the chain named as the cause and this follow-up as the cure. The
     * follow-up is here, and the measurement says the composition was **already right**: a chain of two
     * whole-rim roundings and this one pass of the same two answer the same section, to the last bit, and
     * both are the analytic figure. So (b) is retired as *not reproducible* rather than as fixed — see the
     * companion test for what the *"two sizes"* symptom really was.
     *
     * The fixture is a plate whose **top** rim is rounded at 3 mm and whose **bottom** rim is rounded at
     * 5 mm: two closed chains, so every band is ended by a built corner and no cap is notched by a free end
     * (limit (a)). One [Feature3.Blend], eight targets, two sections — and a cut through each band.
     */
    @Test
    fun aLevelSectionThroughTwoSizesIsExact() {
        val doc = DocumentFormat.load(twoRims(6))
        val f = assertNotNull(featureOf(bodyOf(doc)) as? Feature3.Blend, "one dress-up feature")
        assertEquals(listOf(8, 9, 10, 11, 4, 5, 6, 7), f.targets, "the two rims' eight edges, one pass")
        assertEquals(List(4) { 3.0 } + List(4) { 5.0 }, f.sections.map { it.size }, "…with a section per target")
        assertTrue(f.base !is Feature3.Blend, "and no chain under it: ${f.base}")

        // the exact figure: at depth d under a rim, a fillet of radius r has taken this much off the side face
        fun cutBack(
            r: Double,
            d: Double,
        ): Double = r - kotlin.math.sqrt(r * r - (r - d) * (r - d))

        val top = cutBack(3.0, 2.0)
        assertClose(areaAt(f, 18.0), (40.0 - 2 * top) * (30.0 - 2 * top), 1e-9, "2 mm under the 3 mm rim")
        val bottom = cutBack(5.0, 2.0)
        assertClose(areaAt(f, 2.0), (40.0 - 2 * bottom) * (30.0 - 2 * bottom), 1e-9, "2 mm over the 5 mm rim")

        // …and the chain answered both the same, which is what retires (b) as a misdiagnosis rather than a bug
        val chain = DocumentFormat.load(twoRims(5))
        val g = assertNotNull(featureOf(bodyOf(chain)) as? Feature3.Blend, "the reference")
        assertTrue(g.base is Feature3.Blend, "the reference really is two levels")
        assertEquals(areaAt(f, 18.0), areaAt(g, 18.0), "the chain's own answer, to the last bit")
        assertEquals(areaAt(f, 2.0), areaAt(g, 2.0), "…at the other height too")
    }

    /**
     * **What the *"two sizes"* symptom really is: limit (a), one edge further along.** Two *adjacent* rim
     * edges rounded at different radii build **no** corner between them (they are not congruent), so each
     * band has a **free end** at the vertex where they meet, and a free end notches faces whose outlines
     * nobody corrects — which is limit (a) verbatim. The level section there refuses, in (a)'s own sentence,
     * **exactly as it did before this package and exactly as the chain still does**: one pass neither cures
     * it nor makes it worse, and the cure is (a)'s own (the end faces' analytic correction), still queued.
     */
    @Test
    fun twoAdjacentSizesStillMeetLimitAAndSayItInTheSameWords() {
        val onePass = assertNotNull(featureOf(bodyOf(DocumentFormat.load(fourRim(6)))) as? Feature3.Blend, "one pass")
        assertEquals(listOf(8, 9, 10, 11), onePass.targets, "all four rim edges in one pass")
        val (regions, why) = Section3.regionsOf(onePass, Plane3(Vec3(0.0, 0.0, 18.0), Vec3.X, Vec3.Y))
        assertNull(regions, "the section does not close")
        assertTrue("does not close into an area" in assertNotNull(why), "…and it is (a)'s own sentence: $why")

        val chain = assertNotNull(featureOf(bodyOf(DocumentFormat.load(fourRim(5)))) as? Feature3.Blend, "the chain")
        assertEquals(why, Section3.regionsOf(chain, Plane3(Vec3(0.0, 0.0, 18.0), Vec3.X, Vec3.Y)).second, "the chain says the same")
    }

    private fun areaAt(
        f: Feature3,
        z: Double,
    ): Double {
        val (regions, why) = Section3.regionsOf(f, Plane3(Vec3(0.0, 0.0, z), Vec3.X, Vec3.Y))
        val areas = assertNotNull(regions, "the section at z=$z closes into an area: $why")
        assertEquals(1, areas.size, "one area at z=$z")
        return areas.sumOf { r ->
            Geom3.polygonArea(Geom3.tessellateLoop(r.outer)) - r.holes.sumOf { Geom3.polygonArea(Geom3.tessellateLoop(it)) }
        }
    }

    /** The plate with its **top** rim rounded at 3 mm and its **bottom** rim at 5 mm — one pass at 6, a chain at 5. */
    private fun twoRims(version: Int): String =
        plateScript(version) +
            (
                if (version >= DocumentFormat.DRESSED_BODY_VERSION) {
                    """
param "r0" = 3mm
tool filletfaceedges els=e9 clicks=20,15 scalar="r0" signs=5 -> e10,e11
param "r1" = 5mm
tool filletfaceedges els=e10 clicks=20,15 scalar="r1" signs=4 -> e12
"""
                } else {
                    """
param "r0" = 3mm
tool filletfaceedges els=e9 clicks=20,15 scalar="r0" signs=5 -> e10
param "r1" = 5mm
tool filletfaceedges els=e10 clicks=20,15 scalar="r1" signs=4 -> e11
show els=e10
"""
                }
            ).trimStart()

    /** All four of the plate's top rim edges, at two radii on the two opposite pairs. */
    private fun fourRim(version: Int): String {
        val sb = StringBuilder(plateScript(version))
        var prev = "e9"
        var n = 10
        val made = ArrayList<String>()
        for ((i, g) in listOf(8 to 3.0, 9 to 5.0, 10 to 3.0, 11 to 5.0).withIndex()) {
            val at = rim[g.first]!!
            sb.append("param \"r$i\" = ${g.second}mm\n")
            val decl = if (version >= DocumentFormat.DRESSED_BODY_VERSION && i == 0) "e$n,e${n + 1}" else "e$n"
            sb.append("tool filletedge els=$prev clicks=${at.x},${at.y} scalar=\"r$i\" signs=${g.first};-1;1;0;1 -> $decl\n")
            made.add("e$n")
            if (version >= DocumentFormat.DRESSED_BODY_VERSION) {
                if (i == 0) {
                    prev = "e$n"
                    n += 2
                } else {
                    n++
                }
            } else {
                prev = "e$n"
                n++
            }
        }
        if (version < DocumentFormat.DRESSED_BODY_VERSION) sb.append("show els=${made.dropLast(1).joinToString(",")}\n")
        return sb.toString()
    }

    private fun plateScript(version: Int): String =
        """
constructit $version
orthostart 0,0 -> e1
orthovertex 40,0 -> e2,e3
orthovertex 40,30 -> e4,e5
orthovertex 0,30 -> e6,e7
orthoclose -> e8
param "depth" = 20mm
tool extrude els=e3 clicks=20,0 scalar="depth" -> e9
""".trimStart()
}
