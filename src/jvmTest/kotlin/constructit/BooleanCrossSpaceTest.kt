package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.ProfileElement
import constructit.geom.Segment
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A boolean whose operands live in two sketch spaces** (OP-22, closing session 16's parked cut) — the
 * user's column and its foundation.
 *
 * The report: a column (a circle extruded in the plan) and a foundation (a profile revolved on the upright
 * datum `plane1`) could not be united at all. A solid was pickable **only** through its footprint hint,
 * which is drawn in the space its own sketch was drawn in, and one canvas shows one space — so in the plan
 * the column picked and the foundation could not, and on `plane1` neither of the user's clicks landed
 * anywhere. The engine was never the problem: `combineSolids` on the two elements produces a watertight
 * `MeshBoolean`. What was missing was the *gesture*, which is exactly what session 16 recorded as parked:
 * *"a two-operand boolean whose operands live in two different spaces has no gesture, because one canvas
 * shows one space"*.
 *
 * Three pieces, one gesture, and none of them is new geometry:
 *
 * - the three boolean rows declare `crossSpace` (the loft's and the sweep's own declaration), so a pick
 *   made in one space survives the switch to the other and the status line says it did;
 * - a `SOLID` slot click falls back from the footprint to the **section** the working plane draws of the
 *   body (`Document.sectionSolidNear`, the route the intersection-curve tool already takes) — so what is
 *   *visible* is what is *pickable*, in both directions. Footprint first: it is the solid's own geometry,
 *   and a face space's part outline names the **tip** of its feature chain, which a section pick must not
 *   overrule;
 * - a `SOLID` slot's **miss** says how a solid is clicked instead of "That click hit nothing pickable".
 *
 * What the section route *records* is the solid itself (`els=`), never the section member it was reached
 * through — so replay re-picks nothing and cannot re-decide anything (contrast `sectioninput`, which
 * materializes one named member of an ordered set and must therefore record `el=` **and** `edge=n`).
 */
class BooleanCrossSpaceTest {
    // ---- the user's drawing, verbatim ----

    /**
     * The user's script as pasted: a column extruded 200 mm from a circle in the plan, an upright datum
     * `plane1` hinged on the line through the circle's two points, and a foundation revolved through 360°
     * from a profile drawn on that datum against the column's own section.
     */
    private val COLUMN_AND_FOUNDATION_CIT =
        """constructit 2
point -15.607700000000058,18.169875000000026 -> e1
point 35.70620318639833,21.673418033372624 -> e2
tool circle pts=e1,e2 clicks=-21.39369681360091,17.64714303337267;31.679928186398378,43.26889303337233 -> e3
tool makerel els=e2,e1 clicks=29.8498031863984,43.63491803337232;-20.66164681360092,19.111243033372652 dofs=51.433369265582435mm;3.9059038349535347deg
param "h" = 200mm
tool extrude els=e3 clicks=-51.04172181360052,-27.00790696662673 scalar="h" -> e4
tool line pts=e1,e2 clicks=-18.46549681360095,16.18304303337269;36.80427818639831,22.03944303337261 -> e5
param "angle" = 90deg
sketchspace "plane1" line=e5 angle="angle"
sectioninput "plane1" el=e4 edge=2 -> e6
tool keypoints els=e6 clicks=-22.411388062047237,199.62124110929852 -> e7,e8
sectioninput "plane1" el=e4 edge=1 -> e9
tool keypoints els=e9 clicks=-5.967668649322933,0.4695282218597204 -> e10,e11
tool segment pts=e8,e10 clicks=38.66528404235733,199.36022969004895;36.316181269111,-0.31350603588905607 -> e12
tool line pts=e11,e10 clicks=-65.47827223822995,-0.31350603588905607;38.14326120385814,1.7745853181076812 -> e13
pointoncurve e13 67.63755157906206,-0.0000000000000000000000000000003717034216222244 dofs=57.96070865810204mm -> e14
pointoncurve e12 37.099619050190974,34.92303556280589 dofs=-49.8511061066917mm -> e15
tool arccs pts=e10,e14,e15 clicks=37.88224978460855,-1.0965402936378326;67.63755157906205,1.7745853181076812;38.40427262310774,34.923035562805886 -> e16
param "h2" = 9mm
tool fillet els=e12,e16 clicks=37.09921552685977,44.580458075040795;46.2346152005955,21.350441761827092 scalar="h2" signs=1;1;1 -> e17
tool keypoints els=e17 clicks=38.40427262310774,26.83168156606853 -> e18,e19,e20
tool arccs pts=e10,e14,e20 clicks=38.92629546160692,-0.8355288743882404;62.417323194070214,0.4695282218597204;43.62450100809958,22.39448743882546 -> e21
hide els=e16
hide els=e18
tool segment pts=e19,e10 clicks=37.09921552685977,29.963818597063632;37.621238365358955,-0.8355288743882404 -> e22
tool segment pts=e10,e14 clicks=37.621238365358955,-0.5745174551386483;62.15631177482062,-0.8355288743882404 -> e23
hide els=e13
tool outline els=e23,e21,e17,e22 clicks=53.28192352033449,-0.31350603588905607;56.936083389828774,12.998076345840143;38.908053085182765,25.808008318186666;37.621238365358955,15.347179119086473 -> e24,e25,e26,e27,e28
param "h3" = 360deg
tool perpbis pts=e7,e8 clicks=-66.52231791522831,199.88225252854812;37.360226946109364,199.36022969004895 -> e29
tool revolve els=e28,e29 clicks=50.410797908588975,18.74032756933117;-14.581045484559473,25.526624469820568 scalar="h3" -> e30
"""

    private val DATUM = "plane1"

    // ---- helpers ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** The element the file calls [name] — the one name a user sees (OP-18). */
    private fun named(
        doc: Document,
        name: String,
    ): Element = assertNotNull(doc.elements.firstOrNull { doc.nameOf(it) == name }, "the drawing has $name")

    private fun column(doc: Document): Element = named(doc, "e4")

    private fun foundation(doc: Document): Element = named(doc, "e30")

    private fun loaded(): Editor = Editor(DocumentFormat.load(COLUMN_AND_FOUNDATION_CIT))

    /**
     * The pieces `space` draws as [solid]'s section, in the space's own (u, v) — the geometry a section pick
     * measures against, read off the document rather than written down here.
     */
    private fun sectionDrawn(
        doc: Document,
        space: String,
        solid: Element,
    ): List<ProfileElement> {
        val sp = assertNotNull(doc.spaceNamed(space), "the drawing has $space")
        val sections = doc.spaceSections(sp, Evaluator())
        return assertNotNull(sections.firstOrNull { it.first === solid }, "$space cuts ${doc.nameOf(solid)}").second.drawn
    }

    /** The straight pieces of a drawn section that stand upright in the plane's own (u, v). */
    private fun uprights(drawn: List<ProfileElement>): List<Segment> =
        drawn.filterIsInstance<ProfileElement.Seg>().map { it.segment }.filter { abs(it.a.x - it.b.x) < 1e-9 }

    private fun midpoint(s: Segment): Vec2 = Vec2((s.a.x + s.b.x) / 2.0, (s.a.y + s.b.y) / 2.0)

    /** Where the column's section is clicked on the datum: the middle of one of the rectangle's two sides. */
    private fun columnSideAim(
        doc: Document,
        which: Int,
    ): Vec2 {
        val ups = uprights(sectionDrawn(doc, DATUM, column(doc)))
        assertEquals(2, ups.size, "the column's section on $DATUM is a rectangle with two sides (OP-15's axis-parallel cut)")
        return midpoint(ups.sortedBy { it.a.x }[which])
    }

    /** The boundary pieces of [el]'s **footprint hint** — the other drawing a solid is picked by (OP-17). */
    private fun footprintPieces(el: Element): List<ProfileElement> {
        val v = assertNotNull((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? SolidValue, "a solid with a value")
        return v.solid.feature.footprint.flatMap { r -> r.outer.elements + r.holes.flatMap { it.elements } }
    }

    private fun footprintEdges(
        doc: Document,
        el: Element,
    ): List<Segment> {
        assertNotNull(doc.nameOf(el))
        return footprintPieces(el).filterIsInstance<ProfileElement.Seg>().map { it.segment }
    }

    /** Where the foundation is clicked on the datum: on its own footprint, which *is* its revolve profile there. */
    private fun foundationAim(doc: Document): Vec2 =
        midpoint(
            assertNotNull(
                footprintEdges(doc, foundation(doc)).firstOrNull { abs(it.a.y - it.b.y) < 1e-9 },
                "the foundation's profile has a horizontal edge to click",
            ),
        )

    /** Where the column is clicked in the plan: on its footprint circle, at the far end of its own radius. */
    private fun columnPlanAim(doc: Document): Vec2 {
        val circle =
            assertNotNull(
                footprintPieces(column(doc)).filterIsInstance<ProfileElement.CircleE>().firstOrNull(),
                "the column's footprint is its circle",
            ).circle
        return Vec2(circle.center.x + circle.radius, circle.center.y)
    }

    // ---- 1. the cross-space gesture ----

    /**
     * **The user's gesture, and it works**: arm *Union* in the plan, click the column's footprint, switch to
     * the upright datum — the pick is kept and says so — and click the foundation there. One solid, watertight,
     * bigger than either operand and smaller than the two of them apart, which is what a fusion of two
     * overlapping bodies is.
     */
    @Test
    fun aColumnInThePlanUnitesWithAFoundationOnTheDatum() {
        val ed = loaded()
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE), "start in the plan")
        ed.setTool(Tools.UNION)
        ed.click(columnPlanAim(ed.doc))
        assertEquals(2, ed.solids().size, "nothing is built by the first pick")

        assertTrue(ed.setActiveSpace(DATUM), "switch planes mid-gesture")
        assertTrue(ed.statusHint.contains("1 pick kept across the switch"), "the picks survive, and it says so: ${ed.statusHint}")

        ed.click(foundationAim(ed.doc))
        val union = assertNotNull(ed.solids().lastOrNull(), "the union was built: ${ed.statusHint}")
        assertEquals(3, ed.solids().size, "exactly one solid was added")
        assertNull(whyInvalid(union), "and it is valid")
        val mesh = meshOf(union)
        assertManifold(mesh, "the column fused with its foundation")

        val vc = Geom3.volume(meshOf(column(ed.doc)))
        val vf = Geom3.volume(meshOf(foundation(ed.doc)))
        val vu = Geom3.volume(mesh)
        assertTrue(vu > vc && vu > vf, "the fusion holds both bodies ($vu vs $vc and $vf)")
        assertTrue(vu < vc + vf, "and they overlap, so it is less than the two apart ($vu vs ${vc + vf})")
    }

    /** **One undo takes the whole union** — two clicks in two spaces, one transaction (OP-27). */
    @Test
    fun oneUndoTakesTheWholeCrossSpaceUnion() {
        val ed = loaded()
        val before = ed.doc.elements.size
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.UNION)
        ed.click(columnPlanAim(ed.doc))
        ed.setActiveSpace(DATUM)
        ed.click(foundationAim(ed.doc))
        assertEquals(3, ed.solids().size, "the union is there")

        assertTrue(ed.undo(), "one undo")
        assertEquals(2, ed.solids().size, "and the union is gone")
        assertEquals(before, ed.doc.elements.size, "with nothing else left behind: ${ed.doc.elements.size} vs $before")
    }

    // ---- 2. the one-space gesture: a solid picked through its drawn section ----

    /**
     * **The same union from the datum alone**: the foundation by its own footprint there, then the column by
     * the **section** the datum cuts through it — the upright side of the rectangle OP-15's axis-parallel cut
     * put on the drawing. The aim point is read off the section value, so this test knows where the drawing
     * is rather than remembering where it once was.
     */
    @Test
    fun theColumnIsPickedByTheSectionTheDatumDrawsOfIt() {
        for (side in 0..1) {
            val ed = loaded()
            assertTrue(ed.setActiveSpace(DATUM), "sketch on the datum")
            ed.setTool(Tools.UNION)
            ed.click(foundationAim(ed.doc))
            ed.click(columnSideAim(ed.doc, side))
            val union = assertNotNull(ed.solids().lastOrNull(), "side $side built the union: ${ed.statusHint}")
            assertEquals(3, ed.solids().size, "exactly one solid was added")
            assertNull(whyInvalid(union), "and it is valid")
            assertManifold(meshOf(union), "the union picked through the column's section (side $side)")
            assertTrue(
                ed.statusHint.contains(ed.doc.nameOf(column(ed.doc))) && ed.statusHint.contains(ed.doc.nameOf(foundation(ed.doc))),
                "and it names both bodies: ${ed.statusHint}",
            )
        }
    }

    /**
     * **The footprint wins the click it shares with a section.** The foundation's profile has an edge standing
     * exactly on the column's section side (it was drawn against it), so one click is within reach of both
     * drawings — and it takes the body whose *own* geometry is there. That precedence is what keeps a face
     * space's part pick the tip of its feature chain ([Document.partOutlineOf]) rather than whichever ancestor
     * the plane happens to cut nearest.
     */
    @Test
    fun aFootprintWinsTheClickItSharesWithASection() {
        val ed = loaded()
        ed.setActiveSpace(DATUM)
        val shared = assertNotNull(footprintEdges(ed.doc, foundation(ed.doc)).firstOrNull { abs(it.a.x - it.b.x) < 1e-9 }, "the foundation's profile has an upright edge")
        val ups = uprights(sectionDrawn(ed.doc, DATUM, column(ed.doc)))
        assertTrue(
            ups.any { abs(it.a.x - shared.a.x) < 1e-6 },
            "the foundation was drawn against the column's section, so the two share that line",
        )
        ed.setTool(Tools.UNION)
        ed.click(midpoint(shared))
        // one pick made, and it is the foundation: the tool is still waiting for its second solid
        assertEquals(2, ed.solids().size, "nothing built yet")
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.click(columnPlanAim(ed.doc))
        val union = assertNotNull(ed.solids().lastOrNull(), "the second pick completed it: ${ed.statusHint}")
        assertEquals(
            "${ed.doc.nameOf(union)} is ${ed.doc.nameOf(foundation(ed.doc))} fused with ${ed.doc.nameOf(column(ed.doc))}" +
                " — a solid, shown in the 3D view",
            ed.statusHint,
            "the shared click took the footprint's body, not the section's",
        )
    }

    // ---- 3. the refusals ----

    /**
     * **One solid reached through two different drawings is still one solid**: its footprint in the plan and
     * its section on the datum are two pictures of the same body, and the boolean refuses **by name** rather
     * than building a degenerate fusion of a thing with itself.
     */
    @Test
    fun theSameSolidThroughTwoDrawingsRefusesByName() {
        val ed = loaded()
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.UNION)
        ed.click(columnPlanAim(ed.doc))
        ed.setActiveSpace(DATUM)
        ed.click(columnSideAim(ed.doc, 0))
        assertEquals(2, ed.solids().size, "nothing was built")
        assertEquals(
            "${ed.doc.nameOf(column(ed.doc))} cannot be combined with itself — click two different solids",
            ed.statusHint,
            "and the refusal names the element",
        )
    }

    /**
     * **A solid slot's miss says how a solid is clicked.** The generic "That click hit nothing pickable" is
     * exactly the sentence that left the user with no way forward: the two routes into a body are the one
     * thing the canvas cannot show.
     */
    @Test
    fun aSolidSlotMissSaysHowASolidIsClicked() {
        val ed = loaded()
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.UNION)
        ed.click(Vec2(-400.0, -400.0))
        assertEquals(2, ed.solids().size, "nothing was built")
        assertTrue(ed.statusHint.startsWith("That click hit no solid — "), "it names what it wanted: ${ed.statusHint}")
        assertTrue(
            ed.statusHint.contains(
                "a solid is clicked by its footprint in the space it was sketched in, " +
                    "by its section where a working plane cuts it, or on the body itself in the 3D view",
            ),
            "and says all three routes into a body: ${ed.statusHint}",
        )
        assertTrue(
            ed.statusHint.contains("Switch the sketch plane — the picks are kept — and click it there."),
            "and offers the switch, which this tool really does keep picks across: ${ed.statusHint}",
        )
        assertTrue(!ed.statusHint.contains("hit nothing pickable"), "the generic sentence is gone from this slot")
    }

    /** *Subtract*'s miss speaks in **its** word for the slot, because a refusal is said in the tool's own terms. */
    @Test
    fun subtractsMissNamesTheSlotItWasWaitingFor() {
        val ed = loaded()
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(-400.0, -400.0))
        assertTrue(ed.statusHint.startsWith("That click hit no kept solid — "), "the first slot is the kept one: ${ed.statusHint}")
    }

    // ---- 4. the tool table ----

    /** All three booleans span spaces, because a solid is a body rather than a drawing (OP-22). */
    @Test
    fun theThreeBooleanRowsSpanSpaces() {
        for (id in listOf(Tools.UNION, Tools.SUBTRACT, Tools.INTERSECT_SOLIDS)) {
            val tool = assertNotNull(Tools.all.firstOrNull { it.id == id }, "the tool table has $id")
            assertTrue(tool.crossSpace, "$id keeps its picks across a change of sketch plane")
        }
    }

    // ---- 5. the file ----

    /**
     * **The recorded step is the solid, not the drawing it was reached through.** A section pick writes the
     * ordinary `els=` argument — the same one a footprint pick writes — so a replay hands the elements back
     * verbatim and re-picks nothing: there is no scored choice here to come back different (contrast a
     * `sectioninput` step, which materializes one member of an ordered set and therefore records `el=` and
     * `edge=n`). No new step kind, no version bump.
     */
    @Test
    fun theCrossSpaceUnionRoundTripsByteEqualAndReplaysIdentically() {
        val ed = loaded()
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.UNION)
        ed.click(columnPlanAim(ed.doc))
        ed.setActiveSpace(DATUM)
        ed.click(foundationAim(ed.doc))
        val union = assertNotNull(ed.solids().lastOrNull())
        val volume = Geom3.volume(meshOf(union))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.startsWith("constructit 2\n"), "the header is untouched: ${once.lines().first()}")
        val step = assertNotNull(once.lines().firstOrNull { it.startsWith("tool union ") }, "the union is one step")
        assertTrue(
            step.contains("els=${ed.doc.nameOf(column(ed.doc))},${ed.doc.nameOf(foundation(ed.doc))}"),
            "and it names the two solids, in pick order: $step",
        )
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")

        val back = DocumentFormat.load(once)
        assertTrue(back.loadNotes.isEmpty(), "and the load decided nothing: ${back.loadNotes}")
        val replayed = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the union replayed")
        assertNull(whyInvalid(replayed), "valid after replay")
        assertManifold(meshOf(replayed), "the replayed union")
        assertClose(Geom3.volume(meshOf(replayed)), volume, 1e-6, "the same body, exactly")
    }

    /** The same, for the gesture made **on the datum alone** — the section pick is what is being replayed here. */
    @Test
    fun theSectionPickedUnionRoundTripsByteEqual() {
        val ed = loaded()
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.UNION)
        ed.click(foundationAim(ed.doc))
        ed.click(columnSideAim(ed.doc, 0))
        val volume = Geom3.volume(meshOf(assertNotNull(ed.solids().lastOrNull())))

        val once = DocumentFormat.save(ed.doc)
        val step = assertNotNull(once.lines().firstOrNull { it.startsWith("tool union ") })
        assertTrue(
            step.contains("els=${ed.doc.nameOf(foundation(ed.doc))},${ed.doc.nameOf(column(ed.doc))}"),
            "the section pick recorded the solid itself: $step",
        )
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is byte-equal")
        val back = DocumentFormat.load(once)
        val replayed = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertClose(Geom3.volume(meshOf(replayed)), volume, 1e-6, "and the replay is the same body")
    }

    // ---- 6. the limit this package does not close, pinned ----

    /**
     * **A general boolean's own result still has no plan to click** — OP-9's open point, not a new one, and
     * measured here so the design record's claim is a reading rather than a guess. `Feature3.MeshBoolean`
     * carries no footprint by the sink rule, and a working plane sections only its **ancestors**, so a body
     * fused after every plane in the drawing is drawn in neither of the two pictures a `SOLID` slot reads.
     * What it does do is **say so**: the miss names both routes instead of shrugging.
     */
    @Test
    fun theFusedBodyItselfHasNoPlanToClickYet() {
        val ed = loaded()
        ed.setActiveSpace(DATUM)
        ed.setTool(Tools.UNION)
        ed.click(foundationAim(ed.doc))
        ed.click(columnSideAim(ed.doc, 0))
        val union = assertNotNull(ed.solids().lastOrNull())
        assertEquals(emptyList(), footprintPieces(union), "a mesh boolean has no plan (OP-9's sink rule)")
        val sections = ed.doc.spaceSections(assertNotNull(ed.doc.spaceNamed(DATUM)), Evaluator())
        assertTrue(sections.none { it.first === union }, "and the plane sections only its ancestors, which this is not")

        // so a click in the middle of where the fused body stands reaches nothing, and says how a solid is clicked
        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(50.0, 20.0))
        assertTrue(ed.statusHint.startsWith("That click hit no kept solid — "), "and the miss speaks: ${ed.statusHint}")
    }

    // ---- 7. the report's own starting point ----

    /**
     * **The drawing loads clean and the engine was never the problem** — the half of the report that had to be
     * checked before anything was built: `combineSolids` on the two elements directly gives a valid,
     * watertight body. What was broken was the way in.
     */
    @Test
    fun theEngineFusesTheTwoBodiesDirectly() {
        val doc = DocumentFormat.load(COLUMN_AND_FOUNDATION_CIT)
        assertEquals(emptyList(), doc.loadNotes, "the user's file loads clean")
        val union = assertNotNull(doc.combineSolids(column(doc), foundation(doc), BoolOp.UNION), doc.takeNote())
        assertNull(whyInvalid(union), "valid")
        assertManifold(meshOf(union), "the direct fusion")
    }
}
