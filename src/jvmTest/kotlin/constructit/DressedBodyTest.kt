package constructit

import constructit.core.EvalResult
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
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **One dressed body, many roundings** (OP-30, GitHub #35).
 *
 * *"Each fillet creates a new 3D object — this may be part of the performance problem and also is
 * inconvenient. I have no chance to remove a fillet from some edge except the very last one added. All
 * fillet operation should better be explicit in the element list and all fillet operations targeting the
 * same body should be applied at once and only produce a single filleted result object?"*
 *
 * What is asserted here is that sentence, clause by clause: one solid element however many roundings; a row
 * per rounding under it, each with its own step, its own size and its own Delete; every rounding that shares
 * a size parameter applied in **one** `Blend3.blended` pass; the reporter's own file re-stated as that body
 * with the same volume to the last part in a million; and the file a fixed point from the first save on.
 */
class DressedBodyTest {
    /** The reporter's own file, verbatim (GitHub #35) — seven roundings, all by the one parameter `r`. */
    private val reported =
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

    /**
     * The very same file with one line added: a `show` that names every intermediate body of the chain.
     *
     * That is all it takes to make the chain **impure** in [DocumentFormat]'s sense — something other than
     * the next rounding reads each intermediate — so the migration declines it and the file loads as the
     * chain it was written as. Which is how this test measures *the chain's own answer* on this very build,
     * with no second checkout: the chain path is untouched by OP-30.
     */
    private val reportedAsChain =
        reported.trimEnd() + "\nshow els=e14,e15,e16,e17,e18,e19,e20\n"

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    private fun bodyOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun entriesOf(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.DRESSING }

    private fun featureOf(el: Element): Feature3 = (Evaluator().valueOf(el.ref) as constructit.core.SolidValue).solid.feature

    // ---- 1. the reporter's file ----

    @Test
    fun theReportedChainLoadsAsOneDressedBodyWithSevenEntries() {
        val doc = DocumentFormat.load(reported)
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size, "the extrusion and one dressed body, not eight solids: ${solids.size}")
        assertEquals(7, entriesOf(doc).size, "one row per rounding")

        // …and it is **one pass**: seven fresh pieces in one `Blend3.blended`, because all seven round by
        // the one parameter `r` the reporter shared between them (equality by sharing, OP-30)
        val f = featureOf(bodyOf(doc))
        assertTrue(f is Feature3.Blend, "the dressed body is a dress-up feature: $f")
        assertEquals(7, (f as Feature3.Blend).targets.size, "…with all seven roundings in one pass")
        assertTrue(f.base !is Feature3.Blend, "…and nothing chained under it")

        // the migration says so, once
        assertEquals(1, doc.loadNotes.size, "one load note: ${doc.loadNotes}")
        assertTrue("one dressed body" in doc.loadNotes.first(), "…and it says what changed: ${doc.loadNotes.first()}")
    }

    @Test
    fun theMigratedBodyIsTheChainsBodyAndTheFileIsAFixedPoint() {
        val chain = DocumentFormat.load(reportedAsChain)
        assertEquals(8, chain.elements.count { it.kind == ElementKind.SOLID }, "the impure chain stays a chain")
        assertTrue(chain.loadNotes.isEmpty(), "…and says nothing about a migration: ${chain.loadNotes}")
        val chained = volumeOf(bodyOf(chain))

        val dressed = volumeOf(bodyOf(DocumentFormat.load(reported)))
        assertTrue(abs(dressed - chained) <= abs(chained) * 1e-6, "one pass = the chain's body: $dressed vs $chained")

        val once = DocumentFormat.save(DocumentFormat.load(reported))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is a fixed point")
        assertTrue(once.startsWith("constructit ${DocumentFormat.VERSION}"), "…at the new version: ${once.lines().first()}")
        // the first rounding declares the body *and* its own entry; every later one names the body
        assertEquals(1, once.lines().count { it.contains("tool filletedge els=e13") }, "one rounding on the base:\n$once")
        assertEquals(6, once.lines().count { it.contains("tool filletedge els=e14") }, "six more on the body:\n$once")
        assertTrue(once.lines().any { it.endsWith("-> e14,e15") }, "the body and its first rounding:\n$once")
        assertTrue(DocumentFormat.load(once).loadNotes.isEmpty(), "and the migrated file needs no note of its own")
    }

    // ---- 2. through the editor ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** A 40 x 30 plate 20 deep, drawn and extruded by gestures. */
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

    /** Roundings of the plate's top rim, made one after another **on the dressed body**, one per radius. */
    private fun threeRoundings(
        radii: List<Double>,
        tool: String = Tools.BLEND_EDGE,
    ): Editor {
        val ed = plate()
        val where = listOf(Vec2(20.0, 0.0), Vec2(40.0, 15.0), Vec2(20.0, 30.0))
        for ((i, v) in radii.withIndex()) {
            ed.activeScalar = ed.doc.newParameter("r$i", v.mm)
            ed.setTool(tool)
            ed.click(where[i])
        }
        return ed
    }

    @Test
    fun threeGesturesOnTheDressedBodyAreOneSolidAndThreeEntries() {
        val ed = threeRoundings(listOf(3.0, 3.0, 3.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the plate and its dressing: ${ed.statusHint}")
        val entries = entriesOf(ed.doc)
        assertEquals(3, entries.size, "a row per rounding: ${ed.statusHint}")
        for (e in entries) assertEquals(ed.doc.dressingWith(e)?.body, bodyOf(ed.doc), "…each a child of the one body")
        assertNull((Evaluator().eval(bodyOf(ed.doc).ref.node) as? EvalResult.Invalid)?.reason, "and the body builds")

        // three different parameters of the same value are three passes; three uses of one parameter is one
        val shared = plate()
        val r = shared.doc.newParameter("r", 3.0.mm)
        for (at in listOf(Vec2(20.0, 0.0), Vec2(40.0, 15.0), Vec2(20.0, 30.0))) {
            shared.activeScalar = r
            shared.setTool(Tools.BLEND_EDGE)
            shared.click(at)
        }
        val one = featureOf(bodyOf(shared.doc))
        assertTrue(one is Feature3.Blend && one.targets.size == 3 && one.base !is Feature3.Blend, "one shared radius, one pass: $one")
        val three = featureOf(bodyOf(ed.doc))
        assertTrue(three is Feature3.Blend && three.base is Feature3.Blend, "three radii of their own, three passes: $three")

        // …and both are the same body, to boolean noise: sharing a parameter changes what is computed, never what is built
        assertClose(volumeOf(bodyOf(shared.doc)), volumeOf(bodyOf(ed.doc)), abs(volumeOf(bodyOf(ed.doc))) * 1e-9, "the same three roundings")
    }

    // ---- 3. removing one ----

    @Test
    fun theMiddleRoundingComesOffAndOneUndoPutsItBack() {
        val ed = threeRoundings(listOf(3.0, 4.0, 5.0))
        val all = volumeOf(bodyOf(ed.doc))
        val middle = entriesOf(ed.doc)[1]
        ed.selectElement(middle)
        assertTrue(ed.deleteSelection(), "the middle rounding comes off: ${ed.statusHint}")
        assertEquals(2, entriesOf(ed.doc).size, "two roundings left")
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "…still one dressed body")
        val two = volumeOf(bodyOf(ed.doc))
        assertTrue(two > all, "the body with one rounding fewer has more material: $two vs $all")

        // …and it is exactly the body those two roundings make on their own
        val fresh = plate()
        val rs = listOf(3.0, 5.0).mapIndexed { i, v -> fresh.doc.newParameter("r$i", v.mm) }
        for ((i, at) in listOf(Vec2(20.0, 0.0), Vec2(20.0, 30.0)).withIndex()) {
            fresh.activeScalar = rs[i]
            fresh.setTool(Tools.BLEND_EDGE)
            fresh.click(at)
        }
        assertClose(two, volumeOf(bodyOf(fresh.doc)), abs(two) * 1e-9, "the two remaining roundings' own body")

        assertTrue(ed.undo(), "one undo")
        assertEquals(3, entriesOf(ed.doc).size, "all three back")
        assertClose(volumeOf(bodyOf(ed.doc)), all, abs(all) * 1e-9, "and the body is what it was")
    }

    @Test
    fun theFirstRoundingComesOffToo() {
        val ed = threeRoundings(listOf(3.0, 4.0, 5.0))
        val first = entriesOf(ed.doc).first()
        ed.selectElement(first)
        assertTrue(ed.deleteSelection(), "the *first* rounding comes off as well: ${ed.statusHint}")
        assertEquals(2, entriesOf(ed.doc).size, "two roundings left")
        // the body kept its identity — the extrusion and the one dressing, exactly as before
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "one dressed body over one extrusion")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(2, text.lines().count { it.startsWith("tool filletedge") }, "two steps left:\n$text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the file is a fixed point")
    }

    @Test
    fun theOnlyRoundingIsNotRemovedOnItsOwnAndSaysWhy() {
        val ed = threeRoundings(listOf(3.0))
        ed.selectElement(entriesOf(ed.doc).single())
        assertTrue(!ed.deleteSelection(), "refused")
        assertTrue("is the only rounding" in assertNotNull(ed.statusHint), "…by name: ${ed.statusHint}")
        assertEquals(1, entriesOf(ed.doc).size, "and nothing happened")
    }

    /**
     * **The rail decision** (OP-30): a rounding of a **rail** — an edge that exists only because of an
     * earlier rounding — is *not* an entry of the dressing, because a rail's index depends on the entry
     * order and an entry that addressed one would silently round a different edge as soon as a *different*
     * rounding was removed. It is a dressing of its own standing on the first, which is what a chain of
     * roundings always was; and while one stands there, removing an entry under it is refused **by name**.
     */
    @Test
    fun aRoundingOfARailIsADressingOfItsOwnAndHoldsTheOneUnderItInPlace() {
        val ed = threeRoundings(listOf(3.0, 4.0), Tools.CHAMFER_EDGE)
        val body = bodyOf(ed.doc)
        val baseEdges =
            assertNotNull(Section3.edges(featureOf(ed.doc.elements.first { it.kind == ElementKind.SOLID })).first).size
        val edges = assertNotNull(Section3.edges(featureOf(body)).first)
        val rail = edges.indices.last { edges[it].name is constructit.geom.EdgeName.BlendRail }
        assertTrue(rail >= baseEdges, "a rail stands past the base's own edges")

        // the pick, stated as the file states it: the address in `signs=`, the choices scored on the load
        val text =
            DocumentFormat.save(ed.doc).trimEnd() + "\n" +
                "param \"rr\" = 1mm\n" +
                "tool chamferedge els=${ed.doc.nameOf(body)} clicks=0,0 scalar=\"rr\" signs=$rail -> eRail,eRailEntry\n"
        val doc = DocumentFormat.load(text)
        assertEquals(3, doc.elements.count { it.kind == ElementKind.SOLID }, "a dressing of its own, not an entry:\n$text")
        assertEquals(3, entriesOf(doc).size, "…with a rounding row of its own")

        val back = Editor()
        back.replaceDocument(doc)
        // …and it **follows** the dressing under it: the body it stands on is one re-pointable view, so
        // retyping that dressing's own size re-cuts the rail's body too (OP-30's binding rule)
        val chained = back.doc.elements.last { it.kind == ElementKind.SOLID }
        val one = volumeOf(chained)
        back.doc.setParameter(back.doc.scalars.first { it.name == "r0" }, 5.0.mm)
        assertTrue(abs(volumeOf(chained) - one) > abs(one) * 1e-9, "the rail's body followed: ${volumeOf(chained)} vs $one")
        back.doc.setParameter(back.doc.scalars.first { it.name == "r0" }, 3.0.mm)
        assertClose(volumeOf(chained), one, abs(one) * 1e-9, "…and came back with it")
        // …and taking one out from under it is refused by name while it stands there
        val first = entriesOf(back.doc).first()
        val why = assertNotNull(back.doc.entryRemovalRefusal(first), "the entry under the rail is held in place")
        assertTrue("is rounded on a rail of" in why, "…by name: $why")
        back.selectElement(first)
        assertTrue(!back.deleteSelection(), "and the removal is refused: ${back.statusHint}")
        assertEquals(3, entriesOf(back.doc).size, "nothing moved")
    }

    /**
     * **Two roundings at once** — the bulk form of the same edit, asserted where it is reachable: the panel
     * selects one row at a time, so the Editor's own bulk path is a guard rather than a gesture, and what is
     * exercised here is the journal edit under it. The *last* rounding goes first, so the one that shares its
     * step with the body is re-stamped once, at the end, onto a rounding that is still there. Taking **all**
     * of them is refused, because it is the roundings that make it that body.
     */
    @Test
    fun twoRoundingsComeOffTogetherAndAllOfThemIsRefused() {
        val ed = threeRoundings(listOf(3.0, 4.0, 5.0))
        val all = entriesOf(ed.doc)
        assertTrue(
            "would be left with no rounding" in assertNotNull(ed.doc.entriesEmptyingRefusal(all)),
            "all three at once is refused by name: ${ed.doc.entriesEmptyingRefusal(all)}",
        )
        assertNull(ed.doc.entriesEmptyingRefusal(all.take(2)), "…and two of the three are not")

        // last first, which is the order [Editor.deleteSelection] sorts them into
        assertEquals(listOf(0, 1, 2), all.map { ed.doc.dressEntryIndex(it) }, "the entries are in order")
        assertTrue(ed.doc.journalWithoutEntry(all[1]), "the middle one out of the journal")
        assertTrue(ed.doc.journalWithoutEntry(all[0]), "…then the first, which re-stamps the body's own step")
        val text = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(text)
        assertEquals(1, entriesOf(back).size, "one rounding left: $text")
        assertEquals(2, back.elements.count { it.kind == ElementKind.SOLID }, "…on the one body it always was")
        assertEquals(text, DocumentFormat.save(back), "and the file is a fixed point")
    }

    // ---- 4. changing one entry's size ----

    @Test
    fun oneEntrysSizeIsRetypedAndTheOthersDoNotMove() {
        val ed = threeRoundings(listOf(3.0, 4.0, 5.0))
        val before = volumeOf(bodyOf(ed.doc))
        val r1 = ed.doc.scalars.first { it.name == "r1" }
        ed.doc.setParameter(r1, 6.0.mm)
        val after = volumeOf(bodyOf(ed.doc))
        assertTrue(after < before, "a bigger rounding takes more material: $after vs $before")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("param \"r1\" = 6mm"), "the file records the change:\n$text")
        assertTrue(text.contains("param \"r0\" = 3mm") && text.contains("param \"r2\" = 5mm"), "…and the others are untouched:\n$text")
        assertClose(volumeOf(bodyOf(DocumentFormat.load(text))), after, abs(after) * 1e-9, "and it reloads the same body")
    }

    // ---- 5. the mesh tier is untouched ----

    @Test
    fun aRoundingOnAFusedBodyIsStillTheMeshTier() {
        val ed = plate()
        // a pad fused onto the plate's right end: an ordinary boolean stands between the addressed body and
        // the drawing's tip, so there is no face list for a rounding to extend (OP-9's sink rule)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, 5.0))
        ed.click(Vec2(60.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, 5.0))
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        val fused = bodyOf(ed.doc)

        ed.activeScalar = ed.doc.newParameter("rf", 2.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(15.0, 0.0))
        val tip = bodyOf(ed.doc)
        assertTrue(tip !== fused, "the rounding made a body of its own: ${ed.statusHint}")
        assertTrue(featureOf(tip) is Feature3.MeshBoolean, "…and it is today's mesh tier: ${featureOf(tip)}")
        assertEquals(0, entriesOf(ed.doc).size, "a mesh-tier rounding is no entry of a dressing")
        assertTrue("with a fillet of 2 mm along" in assertNotNull(ed.statusHint), "…in today's words: ${ed.statusHint}")
        assertTrue(assertNotNull(Section3.structuralRefusal(featureOf(tip))).contains("mesh-only"), "…and it says so as it always did")
    }

    // ---- 5b. everything else about the drawing is unchanged ----

    /**
     * **A rounding is a row, not a body**: it exports nothing, it undoes and redoes with its gesture, the
     * whole dressing goes with the body it dresses, and the save backstop (OP-27) has nothing to complain
     * about at any point along the way.
     */
    @Test
    fun theDressingIsAnOrdinaryMemberOfTheDrawing() {
        val ed = threeRoundings(listOf(3.0, 4.0))
        // the export seam sees **one** body — an entry has no triangles of its own
        val scene = constructit.exchange.ExportScene.extract(ed.doc, "dressed")
        assertEquals(1, scene.nodes.size, "one body to export: ${scene.nodes.map { it.name }}")
        assertNull(scene.refusal, "and nothing is refused")
        assertNull(DocumentFormat.saveFile(ed.doc).refusal, "every element was created by a step (OP-27)")

        // undo and redo take the two gestures back and put them there again, byte for byte
        val text = DocumentFormat.save(ed.doc)
        assertTrue(ed.undo() && ed.undo(), "two roundings, two undos")
        assertEquals(0, entriesOf(ed.doc).size, "…and the dressing is gone")
        assertTrue(ed.redo() && ed.redo(), "two redos")
        assertEquals(text, DocumentFormat.save(ed.doc), "and the file is exactly what it was")

        // deleting the base takes the dressing and every one of its roundings with it
        val base = ed.doc.elements.first { it.kind == ElementKind.SOLID }
        ed.clearSelection()
        ed.selectElement(base)
        assertTrue(ed.deleteSelection(), "the base goes: ${ed.statusHint}")
        assertEquals(0, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "no body left")
        assertEquals(0, entriesOf(ed.doc).size, "…and no rounding either")
    }

    // ---- 6. an entry is structure, not a picture ----

    @Test
    fun anEntryRefusesToBeHiddenAndSaysWhatToDoInstead() {
        val ed = threeRoundings(listOf(3.0, 4.0))
        val entry = entriesOf(ed.doc)[1]
        ed.clearSelection()
        ed.selectElement(entry)
        assertEquals(0, ed.setSelectionVisible(false), "nothing was hidden")
        assertTrue(entry.visible, "the row is still there")
        val why = assertNotNull(ed.doc.hideRefusal(entry))
        assertTrue("not a picture" in why && "Press Delete" in why, "and it says why and what to do: $why")
        // …and the gesture says it out loud rather than leaving "Nothing to hide" to read as a bug
        assertEquals(why, ed.statusHint, "the status line carries the refusal: ${ed.statusHint}")
        assertTrue(DocumentFormat.save(ed.doc).lines().none { it.startsWith("hide ") }, "and nothing was recorded")
    }
}
