package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.CornerSlot
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **An appended slot of a dressed body holds still** (OP-30's rail decision, read for every curve — OP-31,
 * slice 5b).
 *
 * *The defect.* A dressed body's edge list used to be three runs — every entry's two rails, then every corner
 * curve, then every run-in crease — and its face list two: every band, then every corner patch. The rails
 * were slot-stable because a tombstone keeps them ([Feature3.Blend.absent]); nothing else was. So the moment
 * a rounding was **added** to the dressing or **taken off** it, the curves after it re-packed and a stored
 * `signs=` named a *different* curve — the exact thing OP-30's own note forbids of a rail (*"an entry
 * addressing a rail would then silently round a different edge because some other rounding was deleted"*).
 * Slice 5b made it an everyday defect by giving a free end's notch curve an address of its own: a plate with
 * two rounded rims, the first rim's notch rounded, the first rim's entry deleted — and the rounding stayed
 * **valid**, silently rounding the *other* rim's notch curve which had slid into the freed slot.
 *
 * *The rule, and it is stated in one place* ([Blend3]'s own note at `entryOwning`): the base's list, then
 * **one block per entry in the entry's own order** — two rails and `2 × bands` free-end notch slots for the
 * edges, its bands and two flat-end slots for the faces — and then, after every block, the curves two or more
 * entries make **together**, each in the order of the latest entry that takes part in it. Every count in a
 * block is a function of that entry alone, so a tombstone keeps it and a new entry only appends.
 *
 * This asserts the rule in both directions and on both lists, for two fixtures: the plate with two rims (the
 * probe's own shape) and the L-block's three-bevel pivot, which is the corner-carrying one.
 */
class DressedAddressStabilityTest {
    private val L = LBlock()

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun body(ed: Editor): Element = ed.doc.elements.last { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element) = el.ref as SolidRef

    private fun edgesOf(ref: SolidRef) = assertNotNull(Section3.edges(Evaluator().solid(ref).feature).first, "it names its edges")

    private fun facesOf(ref: SolidRef) = assertNotNull(Section3.faces(Evaluator().solid(ref).feature).first, "it names its faces")

    /** The names of every slot a dressing appends — everything past the base body's own list. */
    private fun appendedEdges(
        ref: SolidRef,
        base: Int,
    ): List<EdgeName> = edgesOf(ref).drop(base).map { it.name }

    private fun appendedFaces(
        ref: SolidRef,
        base: Int,
    ): List<FaceName> = facesOf(ref).drop(base).map { it.name }

    // ---- the fixtures ----

    /** A 60 × 40 × 20 plate whose top rim edges [rims] are rounded at 4 mm, in one dressing. */
    private fun plate(vararg rims: Int): String {
        val sb =
            StringBuilder(
                """constructit ${DocumentFormat.VERSION}
orthostart 0,0 -> e1
orthovertex 60,0 -> e2,e3
orthovertex 60,40 -> e4,e5
orthovertex 0,40 -> e6,e7
orthoclose -> e8
param "h" = 20mm
tool extrude els=e7 clicks=30,20 scalar="h" -> e9
param "r" = 4mm
""",
            )
        for ((i, rim) in rims.withIndex()) {
            val on = if (i == 0) "e9" else "e10"
            val made = if (i == 0) "e10,e11" else "e${11 + i}"
            sb.append("tool filletedge els=$on clicks=30,${if (i == 0) 0 else 40} scalar=\"r\" signs=$rim;-1;1;0;1 -> $made\n")
        }
        return sb.toString()
    }

    /** The L-block with [edges] bevelled at 4 mm in one dressing — the pivot fixture, corner curves and all. */
    private fun bevels(edges: List<Int>): SolidRef {
        val (stages, why) = L.run(edges.map { Rounding(it, BlendKind.CHAMFER, 4.0) }, Route.ONE_PASS)
        return assertNotNull(stages, why).last()
    }

    // ---- 1. adding an entry moves nothing that was there ----

    /**
     * **A third rounding added to the dressing appends and moves nothing.** Every slot the two-entry body
     * had names the same curve — and the same face — in the three-entry one, at the same index.
     */
    @Test
    fun addingAnEntryOnlyAppends() {
        val two = bevels(listOf(13, 14))
        val three = bevels(listOf(13, 14, 2))
        val base = assertNotNull(Section3.edges(Evaluator().solid(L.base).feature).first, "the block's edges").size
        val baseFaces = assertNotNull(Section3.faces(Evaluator().solid(L.base).feature).first, "the block's faces").size
        val was = appendedEdges(two, base)
        val now = appendedEdges(three, base)
        assertTrue(now.size > was.size, "the third rounding brings slots of its own: ${was.size} then ${now.size}")
        // the two blocks that were there keep every slot; only the corner curves, which two entries make
        // together, move — and they move **after** every block, which is where the rule puts them
        val fixed = was.filter { it is EdgeName.BlendRail || it is EdgeName.BlendNotch }
        assertEquals(fixed, now.take(fixed.size), "every rail and notch slot of the first two entries is where it was")
        for (i in fixed.indices) assertEquals(was[i], now[i], "appended slot $i names the same curve")
        val wasF = appendedFaces(two, baseFaces).filter { it is FaceName.BlendBand || it is FaceName.BlendCap }
        val nowF = appendedFaces(three, baseFaces)
        assertEquals(wasF, nowF.take(wasF.size), "every band and flat-end slot is where it was")
    }

    /** The same on the plate, where the second rim's own notch curves are the addresses at risk. */
    @Test
    fun addingASecondRimMovesNoNotchSlot() {
        val one = load(plate(8))
        val two = load(plate(8, 10))
        val base = 12
        val was = appendedEdges(refOf(body(one)), base)
        val now = appendedEdges(refOf(body(two)), base)
        assertEquals(4, was.size, "one entry: two rails and two notch slots")
        assertEquals(8, now.size, "two entries: two blocks of four")
        assertEquals(was, now.take(was.size), "the first rim's block is untouched by the second rim")
        assertEquals(
            appendedFaces(refOf(body(one)), 6),
            appendedFaces(refOf(body(two)), 6).take(appendedFaces(refOf(body(one)), 6).size),
            "…and so is its band",
        )
    }

    // ---- 2. removing an entry leaves every other slot where it was, and its own slots speak ----

    /**
     * **Taking the first rim off leaves the second rim's slots where they were, and the first's say why.**
     * This is the probe's own case, asserted on the lists rather than on a volume.
     */
    @Test
    fun removingAnEntryKeepsEverySlotAndTheGoneOnesSpeak() {
        val ed = load(plate(8, 10))
        val base = 12
        val before = appendedEdges(refOf(body(ed)), base)
        val facesBefore = appendedFaces(refOf(body(ed)), 6)
        ed.selectElement(ed.doc.elements.filter { it.kind == ElementKind.DRESSING }[0])
        assertTrue(ed.deleteSelection(), "the first rim's rounding comes off: ${ed.statusHint}")
        val after = appendedEdges(refOf(body(ed)), base)
        assertEquals(before, after, "every appended slot still names the curve it named")
        assertEquals(facesBefore, appendedFaces(refOf(body(ed)), 6), "…and every appended face too")
        // …and the removed entry's own slots are refusals that name the rounding that is gone
        val edges = edgesOf(refOf(body(ed)))
        for (i in base until base + 4) {
            val why = assertNotNull(edges[i].reason, "slot $i (${edges[i].name}) is a tombstone").render()
            assertTrue("was removed; nothing stands here" in why, "…and says so: '$why'")
        }
        for (i in base + 4 until base + 8) {
            assertEquals(null, edges[i].reason?.render(), "the second rim's slot $i is a crease of this body")
        }
        // a rounding addressed to a tombstoned slot is refused by name rather than built on a slid curve
        val (choices, whyChoice) = Blend3.choicesFor(Evaluator().solid(refOf(body(ed))), listOf(base + 2), BlendSection(BlendKind.FILLET, 1.0))
        assertTrue(choices == null, "a rounding of a slot that is gone may not build")
        assertTrue("was removed" in assertNotNull(whyChoice, "…and it says why").render(), "${whyChoice?.render()}")
    }

    /** The same on the pivot fixture, where the middle entry of three comes off. */
    @Test
    fun removingTheMiddleBevelKeepsTheOthersSlots() {
        val text = DocumentFormat.save(loadedBevels())
        val ed = load(text)
        val base = L.block.count
        val before = appendedEdges(refOf(body(ed)), base)
        val entries = ed.doc.elements.filter { it.kind == ElementKind.DRESSING }
        assertEquals(3, entries.size, "three roundings in one dressing")
        ed.selectElement(entries[1])
        assertTrue(ed.deleteSelection(), "the middle rounding comes off: ${ed.statusHint}")
        val after = appendedEdges(refOf(body(ed)), base)
        // every **block** slot is where it was; the corner curves the removed entry took part in are the one
        // class that goes with it, which is the exposure the rule names rather than hides
        val blocks = before.indices.filter { before[it] is EdgeName.BlendRail || before[it] is EdgeName.BlendNotch }
        for (i in blocks) assertEquals(before[i], after.getOrNull(i), "appended slot $i names the curve it named")
        assertEquals(blocks.size, after.count { it is EdgeName.BlendRail || it is EdgeName.BlendNotch }, "no block slot was dropped")
        assertTrue(Evaluator().eval(refOf(body(ed)).ref().node) !is EvalResult.Invalid, "and the body still builds")
    }

    private fun SolidRef.ref(): SolidRef = this

    /** The three-bevel pivot as a **document**, so the entries are rows an editor can delete. */
    private fun loadedBevels(): constructit.editor.Document {
        val sb =
            StringBuilder(
                """constructit ${DocumentFormat.VERSION}
orthostart ${L.plan[0].x},${L.plan[0].y} -> e1
""",
            )
        var n = 2
        for (i in 1 until L.plan.size) {
            sb.append("orthovertex ${L.plan[i].x},${L.plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = ${L.height}mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-48.125,37.875 scalar=\"h\" -> e$n\n")
        val solid = "e$n"
        sb.append("param \"c\" = 4mm\n")
        var next = n + 1
        for ((i, edge) in listOf(2, 13, 14).withIndex()) {
            val made = if (i == 0) "e$next,e${next + 1}" else "e$next"
            sb.append("tool chamferedge els=${if (i == 0) solid else "e${n + 1}"} clicks=0,0 scalar=\"c\" signs=$edge;-1;1;0;1 -> $made\n")
            next += if (i == 0) 2 else 1
        }
        return DocumentFormat.load(sb.toString())
    }

    // ---- 3. the file, and the version that says the numbering moved ----

    /**
     * **A file written before the regrouping is mapped, once, and told.** The reporter's own script addresses
     * a rail of the second entry of a three-entry dressing; under the block rule that rail stands two slots
     * further on, so the load maps it by name and says so rather than rounding a different curve (OP-18).
     */
    @Test
    fun anOlderFilesAppendedAddressIsMappedAndSaidOnce() {
        assertEquals(8, DocumentFormat.GROUPED_SLOT_VERSION, "the version that says the slots are one block per entry")
        assertTrue(DocumentFormat.VERSION >= DocumentFormat.GROUPED_SLOT_VERSION, "…and this build writes that numbering or a later one")
        val doc = DocumentFormat.load(script2)
        assertTrue(doc.loadNotes.any { "one block per rounding" in it }, "the load says the numbering moved: ${doc.loadNotes}")
        // …and once written at this version nothing is said again, and the body is the same one
        val once = DocumentFormat.save(doc)
        val again = DocumentFormat.load(once)
        assertTrue(again.loadNotes.none { "one block per rounding" in it }, "said once only: ${again.loadNotes}")
        val a = Geom3.volume(Evaluator().solid(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh)
        val b = Geom3.volume(Evaluator().solid(again.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).mesh)
        assertClose(a, b, abs(a) * 1e-9, "the mapped file and the re-saved one are one body")
        assertEquals(once, DocumentFormat.save(again), "…and the file is a fixed point of save")
    }

    // ---- 4. a corner's own slot count, recorded (OP-31, slice 5g) ----

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** The 40 × 30 × 20 plate, drawn and extruded by gestures — `DressedBodyTombstoneTest`'s own fixture. */
    private fun plateBody(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        return ed
    }

    /** Where a click lands on each of the plate's four top-rim edges, in the order 8, 9, 10, 11. */
    private val rimClicks = listOf(Vec2(20.0, 0.0), Vec2(40.0, 15.0), Vec2(20.0, 30.0), Vec2(0.0, 15.0))

    /** The plate with its first [n] rim edges rounded at one size — [n] congruent crossings in a row. */
    private fun rims(
        n: Int,
        tool: String = Tools.BLEND_EDGE,
    ): Editor {
        val ed = plateBody()
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        for (i in 0 until n) {
            ed.setTool(tool)
            ed.click(rimClicks[i])
        }
        return ed
    }

    private fun volumeOf(el: Element): Double {
        val mesh = Evaluator().solid(refOf(el)).mesh
        assertManifold(mesh, "the dressed body")
        return Geom3.volume(mesh)
    }

    /**
     * **A corner the edit unmakes keeps its slots, and the corners after it do not move** — the defect this
     * slice closes, on the four-cornered plate.
     *
     * Four rim roundings of one radius make four crossings, and the mitres of the last two are listed after
     * the mitres of the first two. Take the second rounding off and both of *its* corners are gone: without
     * the record the two that remain slide up into the freed slots, so a `filletedge` step addressing the
     * corner between rims 10 and 11 would silently round the corner between rims 8 and 11 instead. With it
     * every slot still names the curve it named, and the two that are gone say so.
     */
    @Test
    fun aCornerUnmadeByARemovalKeepsItsSlotsAndMovesNothingAfterIt() {
        val ed = rims(4)
        val base = 12
        val before = appendedEdges(refOf(body(ed)), base)
        val mitres = before.indices.filter { before[it] is EdgeName.BlendMitre }
        assertEquals(4, mitres.size, "four crossings, one mitre each: $before")
        val whole = volumeOf(body(ed))

        ed.selectElement(ed.doc.elements.filter { it.kind == ElementKind.DRESSING }[1])
        assertTrue(ed.deleteSelection(), "the second rim's rounding comes off: ${ed.statusHint}")
        val after = appendedEdges(refOf(body(ed)), base)
        assertEquals(before, after, "every appended slot still names the curve it named")
        assertTrue(volumeOf(body(ed)) > whole, "…and the body really did lose that rounding")

        // the two corners the removed rounding took part in are the two that are gone, each in its own slot
        val edges = edgesOf(refOf(body(ed)))
        for (i in mitres) {
            val name = edges[base + i].name as EdgeName.BlendMitre
            val why = edges[base + i].reason?.render()
            if (9 in name.edges) {
                assertTrue(why != null && "no curve of this body any more" in why, "$name is gone and says so: $why")
            } else {
                assertEquals(null, why, "$name is a crease of this body still")
            }
        }
    }

    /**
     * **A corner *re-turned* by a third rounding keeps its slot too, and the corner that replaces it
     * appends.** The L-block's two bevels meet at an inside corner; bevelling the upright between them makes
     * a different corner of three edges, so the pair's own corner rail and corner patch are no curve and no
     * surface of this body any more. They keep their indices with a reason, which is what a step holding one
     * of those addresses is owed (OP-3), and the three rails of the new corner go after every block.
     */
    @Test
    fun aCornerReTurnedByAThirdRoundingKeepsItsSlotAndTheNewOneAppends() {
        val ed = load(DocumentFormat.save(DocumentFormat.load(twoBevels)))
        val base = L.block.count
        val was = appendedEdges(refOf(body(ed)), base)
        val wasF = appendedFaces(refOf(body(ed)), L.block.faces.size)
        val rail = was.indexOf(EdgeName.BlendCornerRail(listOf(13, 14), 0))
        val patch = wasF.indexOf(FaceName.BlendCorner(listOf(13, 14), 0))
        assertTrue(rail >= 0 && patch >= 0, "the pair's corner puts a rail and a patch on the body: $was / $wasF")
        volumeOf(body(ed))

        // the third bevel, **added to the file** — the upright between the two, which turns their corner
        // into a corner of three edges
        val third = "tool chamferedge els=e14 clicks=0,0 scalar=\"c\" signs=2;-1;1;0;-1 -> e17\n"
        val grown = load(DocumentFormat.save(ed.doc) + third)
        val now = appendedEdges(refOf(body(grown)), base)
        val nowF = appendedFaces(refOf(body(grown)), L.block.faces.size)
        assertEquals(was[rail], now.getOrNull(rail), "the pair's corner rail is where it was")
        assertEquals(wasF[patch], nowF.getOrNull(patch), "…and so is its patch")
        val edges = edgesOf(refOf(body(grown)))
        val why = assertNotNull(edges[base + rail].reason, "…and it is a tombstone now").render()
        assertTrue("no curve of this body any more" in why, "…that says why: '$why'")
        assertTrue(
            now.drop(rail + 1).any { it == EdgeName.BlendCornerRail(listOf(2, 13, 14), 0) },
            "the corner of three edges appends after it: $now",
        )
        volumeOf(body(grown))

        // …and the record is in the file, so the same body comes back out of it
        val text = DocumentFormat.save(grown.doc)
        assertTrue("slots=" in text, "the record is written down:\n$text")
        val again = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(again), "the file round-trips byte-equal")
        assertEquals(now, appendedEdges(again.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef, base), "…and lays the same slots out")
    }

    /**
     * **A step that addresses a corner curve builds the same body after another rounding is added to the
     * dressing below it** — the defect as a user meets it, on GitHub #36's own script 2.
     *
     * Its three bevels make a corner of three edges, and a rounding of that corner's own **rail** is a
     * dressing of its own (a corner curve is no edge of the base — OP-30's rail decision). A fourth,
     * unrelated rounding is then added to the dressing below, in the file, exactly as a user adds one. Its
     * block goes in ahead of the corner's slots, so without the record slot 30 would be the *first rail of
     * the fourth rounding's own band* and the step would silently round that. With the record the corner's
     * slots stand at the end of the block they were stated in, the step names the curve it named, and where
     * the fourth rounding stands in the file makes no difference to the body at all.
     */
    @Test
    fun aStepAddressingACornerCurveSurvivesAnotherRoundingInTheDressing() {
        val three = DocumentFormat.save(DocumentFormat.load(script2.lines().filter { "r2" !in it }.joinToString("\n") + "\n"))
        assertTrue("slots=2:r2.13.14-0" in three, "the three bevels record their corner's slots:\n$three")
        val rail = EdgeName.BlendCornerRail(listOf(2, 13, 14), 0)

        fun dressed(doc: constructit.editor.Document): Element = doc.elements.filter { it.kind == ElementKind.SOLID }.let { it[it.size - 2] }

        fun tip(doc: constructit.editor.Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }
        val at = 30
        // …the rounding of the corner rail, and an **unrelated** fourth bevel on an upright no rounding of
        // this dressing touches, whose own choices the load scores (an address with no signs after it)
        val roundIt = "param \"r2\" = 1mm\ntool filletedge els=e14 clicks=0,0 scalar=\"r2\" signs=$at -> e18,e19\n"
        val fourth = "tool chamferedge els=e14 clicks=0,0 scalar=\"r\" signs=4 -> e20\n"

        val plain = DocumentFormat.load(three + roundIt)
        assertEquals(rail, edgesOf(refOf(dressed(plain))).getOrNull(at)?.name, "the step addresses the corner's own rail")
        val alone = volumeOf(tip(plain))

        val grown = DocumentFormat.load(three + fourth + roundIt)
        assertEquals(rail, edgesOf(refOf(dressed(grown))).getOrNull(at)?.name, "…and still does, with a fourth rounding ahead of it")
        val a = volumeOf(tip(grown))
        assertTrue(a < alone, "the fourth rounding really did take material off: $a against $alone")
        val b = volumeOf(tip(DocumentFormat.load(three + roundIt + fourth)))
        assertClose(a, b, abs(a) * 1e-9, "and where the fourth rounding stands in the file makes no difference to the body")
        val text = DocumentFormat.save(grown)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the file round-trips byte-equal")
    }

    /**
     * **A corner whose *kind* changes under a parameter keeps its slot.** Three rim roundings on three
     * parameters of their own make two crossings; dropping the first to 3 mm makes that pair incongruent, so
     * the corner between them is no crossing any more and the drawing states the crease the boolean's trim
     * leaves instead. The slot is the same slot either way, which is what a stored address needs — and the
     * body really did change, which is what says the case was exercised.
     */
    @Test
    fun aCornerWhoseKindChangesUnderAParameterKeepsItsSlot() {
        val ed = plateBody()
        for (i in 0 until 3) {
            ed.activeScalar = ed.doc.newParameter("r$i", 4.0.mm)
            ed.setTool(Tools.BLEND_EDGE)
            ed.click(rimClicks[i])
        }
        val before = appendedEdges(refOf(body(ed)), 12)
        val congruent = volumeOf(body(ed))
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "r0" }, 3.0.mm)
        val after = appendedEdges(refOf(body(ed)), 12)
        assertEquals(before, after, "every appended slot names what it named")
        val edges = edgesOf(refOf(body(ed)))
        val at = 12 + before.indexOf(EdgeName.BlendMitre(listOf(8, 9), 0))
        assertTrue(edges[at].reason == null, "the crease between the two rims is a crease of this body still")
        assertTrue(volumeOf(body(ed)) > congruent, "…and the smaller first rounding really did take less away")
        // …and the record is not touched by a *value*: it is structure, decided when the gesture ran (OP-21)
        assertTrue("slots=1:m8.9-0;2:m9.10-0" in DocumentFormat.save(ed.doc), "the record is the gesture's:\n${DocumentFormat.save(ed.doc)}")
    }

    /**
     * **Add, undo, redo is one body** — the record travels with the journal like every other piece of
     * structure, so an undo that reloads the drawing lays exactly the same slots out.
     */
    @Test
    fun addingARoundingAndUndoingAndRedoingItIsOneBody() {
        val ed = rims(2)
        val two = DocumentFormat.save(ed.doc)
        val slots = appendedEdges(refOf(body(ed)), 12)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(rimClicks[2])
        val three = DocumentFormat.save(ed.doc)
        val v = volumeOf(body(ed))
        val grown = appendedEdges(refOf(body(ed)), 12)
        assertEquals(slots, grown.take(slots.size), "the third rounding appends and moves nothing")

        assertTrue(ed.undo(), "the third rounding is one undo step")
        assertEquals(two, DocumentFormat.save(ed.doc), "…and the drawing is the two-rounding one again")
        assertEquals(slots, appendedEdges(refOf(body(ed)), 12), "…with the slots it had")
        assertTrue(ed.redo(), "and it comes back")
        assertEquals(three, DocumentFormat.save(ed.doc), "…as the very file it was")
        assertEquals(grown, appendedEdges(refOf(body(ed)), 12), "…with the very slots it had")
        assertClose(v, volumeOf(body(ed)), abs(v) * 1e-12, "…and it is one body")
    }

    /**
     * **A file written before the record has it taken from the body as that file draws it** (OP-18) — the
     * migration, which moves nothing at all, is said once, and is a fixed point of save from then on.
     */
    @Test
    fun anOlderFileHasItsCornerSlotsRecordedOnceAndMovesNothing() {
        assertEquals(9, DocumentFormat.CORNER_SLOT_VERSION, "the version that records a corner's slots")
        assertEquals(DocumentFormat.CORNER_SLOT_VERSION, DocumentFormat.VERSION, "…and this build writes it")
        val old = DocumentFormat.save(rims(3).doc).replace("constructit 9", "constructit 8").replace(Regex(" slots=\\S+"), "")
        val doc = DocumentFormat.load(old)
        assertTrue(doc.loadNotes.any { "numbered from a record" in it }, "the load says the record was taken: ${doc.loadNotes}")
        // the layout is the one that file was written against: the shared curves after every block, which is
        // what the migration reproduces by recording them all into the last one
        val edges = appendedEdges(doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef, 12)
        assertEquals(EdgeName.BlendMitre(listOf(8, 9), 0), edges[12], "the first mitre is where the older numbering had it")
        assertEquals(EdgeName.BlendMitre(listOf(9, 10), 0), edges[13], "…and so is the second")
        val once = DocumentFormat.save(doc)
        assertTrue("slots=2:m8.9-0;2:m9.10-0" in once, "…and the record says so, in the last block:\n$once")
        val again = DocumentFormat.load(once)
        assertTrue(again.loadNotes.none { "numbered from a record" in it }, "said once only: ${again.loadNotes}")
        assertEquals(once, DocumentFormat.save(again), "and the file is a fixed point of save")
    }

    /**
     * **A file at version 8 whose step addresses a corner curve loads unmoved, is told once, and is saved
     * with the record.** The migration takes the record from the body as *that* file draws it — the shared
     * curves after every block, which is where version 8 put them — so the address it holds means exactly
     * what it meant, and the body is the same body to the last bit.
     */
    @Test
    fun aVersionEightFileAddressingACornerCurveLoadsUnmovedAndIsSavedWithTheRecord() {
        assertEquals(9, DocumentFormat.CORNER_SLOT_VERSION, "the version that records a corner's slots")
        assertEquals(DocumentFormat.CORNER_SLOT_VERSION, DocumentFormat.VERSION, "…and this build writes it")

        val doc = DocumentFormat.load(cornerRoundedAtEight)
        assertTrue(doc.loadNotes.any { "numbered from a record" in it }, "the load says the record was taken: ${doc.loadNotes}")
        val solids = doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(
            EdgeName.BlendCornerRail(listOf(2, 13, 14), 0),
            edgesOf(refOf(solids[solids.size - 2])).getOrNull(30)?.name,
            "slot 30 is the corner rail it was when the file was written",
        )
        // …and it is the very body the same drawing makes with the record written down
        val a = volumeOf(solids.last())
        val nine = DocumentFormat.load(cornerRoundedAtNine)
        assertTrue(nine.loadNotes.none { "numbered from a record" in it }, "a file at the version says nothing: ${nine.loadNotes}")
        assertClose(a, volumeOf(nine.elements.last { it.kind == ElementKind.SOLID }), abs(a) * 1e-9, "and it is the same body")

        val once = DocumentFormat.save(doc)
        assertTrue(once.startsWith("constructit ${DocumentFormat.CORNER_SLOT_VERSION}\n"), "it is saved at the version that records:\n$once")
        assertTrue("slots=2:r2.13.14-0;2:r2.13.14-1;2:r2.13.14-2" in once, "…with the record the body states:\n$once")
        val again = DocumentFormat.load(once)
        assertTrue(again.loadNotes.none { "numbered from a record" in it }, "said once only: ${again.loadNotes}")
        assertEquals(once, DocumentFormat.save(again), "and the file is a fixed point of save")
    }

    /**
     * The L-block's three bevels with the **corner rail of their corner rounded** — the address this slice is
     * about — written by hand at version 8, which is the last version that states no record. Frozen, because
     * an in-build round trip proves nothing across builds (OP-18): this is a file such a build wrote.
     */
    private val cornerRoundedAtEight =
        """
constructit ${DocumentFormat.GROUPED_SLOT_VERSION}
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 4mm
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
param "r2" = 1mm
tool filletedge els=e14 clicks=0,0 scalar="r2" signs=30 -> e18,e19
""".trimStart()

    /** The same drawing at version 9, where the dressing states the record its corner's slots stand by. */
    private val cornerRoundedAtNine =
        cornerRoundedAtEight
            .replace("constructit ${DocumentFormat.GROUPED_SLOT_VERSION}", "constructit ${DocumentFormat.CORNER_SLOT_VERSION}")
            .replace(
                "signs=2;-1;1;0;-1 ->",
                "signs=2;-1;1;0;-1 slots=2:r2.13.14-0;2:r2.13.14-1;2:r2.13.14-2;2:f2.13.14-0;2:f2.13.14-1;2:f2.13.14-2 ->",
            )

    /**
     * **Every curve and surface a corner appends is one the record can hold** — the closed vocabulary
     * [CornerSlot] is, asserted over the corners the L-block's pairs and triples make rather than assumed.
     *
     * The record is a closed vocabulary on purpose: a record that could not *write* a slot would be a record
     * that silently loses one, and a lost slot is an address that moves. So the day a corner puts a new kind
     * of curve on the body is the day this fails, which is what keeps the gap from opening unnoticed.
     */
    @Test
    fun everyCurveACornerAppendsIsOneTheRecordCanHold() {
        var corners = 0
        for (edges in listOf(listOf(13, 14), listOf(2, 13), listOf(2, 14), listOf(2, 13, 14), listOf(12, 13), listOf(12, 13, 14))) {
            for (kind in listOf(BlendKind.CHAMFER, BlendKind.FILLET)) {
                val (stages, _) = L.run(edges.map { Rounding(it, kind, 4.0) }, Route.ONE_PASS)
                val ref = stages?.lastOrNull() ?: continue
                if (Evaluator().eval(ref.node) is EvalResult.Invalid) continue
                val base = L.block.count
                for (name in appendedEdges(ref, base)) {
                    if (name is EdgeName.BlendRail || name is EdgeName.BlendNotch) continue
                    assertNotNull(CornerSlot.of(name), "$edges/$kind: the record can hold $name")
                    corners++
                }
                for (name in appendedFaces(ref, L.block.faces.size)) {
                    if (name is FaceName.BlendBand || name is FaceName.BlendCap) continue
                    assertNotNull(CornerSlot.of(name), "$edges/$kind: the record can hold $name")
                    corners++
                }
            }
        }
        assertTrue(corners > 10, "and the corners were really there to be asked about: $corners")
    }

    /** The L-block with the two bevels that meet at its inside corner, as a file this build has written. */
    private val twoBevels =
        """
constructit ${DocumentFormat.VERSION}
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "c" = 4mm
tool chamferedge els=e13 clicks=-36.10499303384047,0.8875707537249014 scalar="c" signs=13;-1;1;0;1 -> e14,e15
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="c" signs=14;-1;1;0;1 -> e16
""".trimStart()

    /** GitHub #36's script 2, verbatim — its last step addresses rail 20 of the older numbering. */
    private val script2 =
        """
constructit 6
orthostart -26.875,-32.375 -> e1
orthovertex -26.875,15.375 -> e2,e3
orthovertex 61.875,15.375 -> e4,e5
orthovertex 61.875,0.375 -> e6,e7
orthovertex -5.521648428788623,0.375 -> e8,e9
orthovertex -5.521648428788623,-32.375 -> e10,e11
orthoclose -> e12
param "h" = 20mm
tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
param "r" = 4mm
tool chamferedge els=e13 clicks=-12.581664043342087,5.018353790754986 scalar="r" signs=2;-1;1;0;-1 -> e14,e15
tool chamferedge els=e14 clicks=-36.10499303384047,0.8875707537249014 scalar="r" signs=13;-1;1;0;1 -> e16
tool chamferedge els=e14 clicks=-6.480180051294639,24.048959979858537 scalar="r" signs=14;-1;1;0;1 -> e17
param "r2" = 4mm
tool filletedge els=e14 clicks=-33.91557367038956,-1.7134784580017737 scalar="r2" signs=20;-1;1;0;1 -> e18,e19
""".trimStart()
}
