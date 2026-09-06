package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Section3
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
        assertEquals(DocumentFormat.GROUPED_SLOT_VERSION, DocumentFormat.VERSION, "…and this build writes it")
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
