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
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.l10n.contains
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A slot kept for the life of the dressing** (OP-30's recorded next step, part B).
 *
 * Removing a rounding used to close the list up behind it: the dressed face and edge lists are the base's
 * own slots, then one band (two rails) per rounded edge **in entry order**, so taking entry 2 off slid every
 * band and rail of entry 3 up by one. That is the index instability OP-17 forbids, and while anything held
 * such an address — a rounding chained on a **rail**, the one pick the rail decision keeps out of the
 * dressing for exactly this reason — the removal was refused by name (`Document.entryRemovalRefusal`).
 *
 * A removed rounding now leaves a **tombstone**: its target stays in the feature's list marked absent
 * ([Feature3.Blend.absent]), contributing its band and rail slots with a reason and **no surface** — which
 * is exactly what an edge a rounding *consumed* already contributes — and no geometry whatever. So nothing
 * after it renumbers, the refusal is gone, and an address *into* a tombstone is invalid with a sentence that
 * names the rounding that went and the gesture that works (OP-3).
 *
 * **A tombstone is structure, so the file states it** (OP-18, OP-21): the rounding's own step stays in the
 * journal, which is what says where in the entry order the slot is; it declares one name fewer (none where a
 * living entry declares its row, the body alone where a living *first* rounding declares the body and its
 * row); and it carries one new optional `tool` argument, `removed=<bands>`. No existing literal means
 * anything new, so no version is owed — `law=`, `laws=` and `match=` arrived on that row the same way.
 *
 * The element list shows no tombstone: it is the *addresses'* business, not the user's.
 */
class DressedBodyTombstoneTest {
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

    /** Three roundings of the plate's top rim — edges 8, 9 and 10 — at three radii of their own. */
    private fun threeRoundings(
        radii: List<Double> = listOf(3.0, 4.0, 5.0),
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

    private fun bodyOf(doc: Document): Element = doc.elements.last { it.kind == ElementKind.SOLID }

    private fun entriesOf(doc: Document): List<Element> = doc.elements.filter { it.kind == ElementKind.DRESSING }

    private fun featureOf(el: Element): Feature3 = (Evaluator().valueOf(el.ref) as SolidValue).solid.feature

    @Suppress("UNCHECKED_CAST")
    private fun volumeOf(el: Element): Double = Geom3.volume(Evaluator().solid(el.ref as SolidRef).mesh)

    /** Which slot of the dressed face list the band of base edge [target] stands in. */
    private fun bandSlot(
        el: Element,
        target: Int,
    ): Int = assertNotNull(Section3.faces(featureOf(el)).first, "the dressed faces").indexOfFirst { (it.name as? FaceName.BlendBand)?.edge == target }

    /** Which slot of the dressed edge list the first rail of base edge [target] stands in. */
    private fun railSlot(
        el: Element,
        target: Int,
    ): Int =
        assertNotNull(Section3.edges(featureOf(el)).first, "the dressed edges")
            .indexOfFirst { (it.name as? EdgeName.BlendRail)?.edge == target }

    // ---- 1. the slot stays where it was ----

    /**
     * **The third rounding's band and rails keep their numbers when the second is taken off**, which is the
     * whole of what a tombstone is for. The removed rounding's own band and rails are still there too, with
     * a reason and no surface — the very shape an edge a rounding consumed already has.
     */
    @Test
    fun theSlotsAfterARemovedRoundingDoNotMove() {
        val ed = threeRoundings()
        val body = bodyOf(ed.doc)
        val before = bandSlot(body, 10) to railSlot(body, 10)
        assertTrue(before.first >= 0 && before.second >= 0, "the third rounding's band and rails are there")

        ed.selectElement(entriesOf(ed.doc)[1])
        assertTrue(ed.deleteSelection(), "the middle rounding comes off: ${ed.statusHint}")
        assertEquals(2, entriesOf(ed.doc).size, "two rows left")

        assertEquals(before, bandSlot(body, 10) to railSlot(body, 10), "the third rounding kept its slots")

        // …and the removed one's own slots are kept, with the sentence a consumed edge already carries
        val faces = assertNotNull(Section3.faces(featureOf(body)).first, "the dressed faces")
        val tomb = assertNotNull(faces.firstOrNull { (it.name as? FaceName.BlendBand)?.edge == 9 }, "the tombstoned band is still a slot")
        assertNull(tomb.plane, "…with no surface")
        assertNull(tomb.surface, "…and no analytic reading")
        assertTrue("was removed; nothing stands here" in assertNotNull(tomb.reason), "…and a reason: ${tomb.reason}")
        val edges = assertNotNull(Section3.edges(featureOf(body)).first, "the dressed edges")
        val rail = assertNotNull(edges.firstOrNull { (it.name as? EdgeName.BlendRail)?.edge == 9 }, "the tombstoned rails too")
        assertTrue("was removed; nothing stands here" in assertNotNull(rail.reason), "…with the same reason: ${rail.reason}")
        // …and the base edge it rounded is a crease of the body again, because nothing rounded it away
        assertNull(edges[9].reason, "the base edge is sharp again: ${edges[9].reason}")

        // the tombstone is not a row of the element list: it is the addresses' business, not the user's
        assertEquals(2, ed.doc.listedElements().count { it.kind == ElementKind.DRESSING }, "no row for a rounding that is gone")
        assertNull((Evaluator().eval(body.ref.node) as? EvalResult.Invalid)?.reason, "and the body builds")
    }

    /**
     * **A sketch space placed on the third rounding's band still addresses that band afterwards.** The
     * face-address convention records a footprint boundary piece for a *base* face, but a band is reached by
     * its own index, so this is the address the tombstone exists to hold still.
     */
    @Test
    fun anAddressTakenBeforeTheRemovalStillNamesTheSameBand() {
        val ed = threeRoundings()
        val body = bodyOf(ed.doc)
        val slot = bandSlot(body, 10)
        val named = assertNotNull(Section3.faces(featureOf(body)).first, "the faces")[slot].name

        ed.selectElement(entriesOf(ed.doc)[1])
        assertTrue(ed.deleteSelection(), "the middle rounding comes off: ${ed.statusHint}")

        val after = assertNotNull(Section3.faces(featureOf(body)).first, "the faces after")
        assertEquals(named, after[slot].name, "slot $slot still names the same band")
        assertNotNull(after[slot].surface, "…and it is still the band's own surface")
        assertTrue("was removed" !in after[slot].reason, "…and not a tombstone: ${after[slot].reason}")
    }

    // ---- 2. a rounding chained on the removed entry's own rail ----

    /**
     * **A rounding standing on the *removed* entry's rail is invalid with a reason that names it** — the
     * case that used to be refused outright, now answered the way OP-3 answers everything: the address is
     * still there, it just names nothing, and the sentence says which rounding went and what to do instead.
     */
    @Test
    fun aRoundingOnATombstonedRailIsInvalidWithAReasonThatNamesIt() {
        // chamfers, because a **fillet**'s rails leave their faces tangentially and there is no crease
        // there to break — the rail decision's own fixture (`DressedBodyTest`) uses the same tool
        val ed = threeRoundings(listOf(3.0, 4.0), Tools.CHAMFER_EDGE)
        val body = bodyOf(ed.doc)
        val rail = railSlot(body, 9)
        assertTrue(rail >= 0, "the second rounding has a rail")

        // a chamfer of that rail, as the file states it: the address in `signs=`, the choices scored on load
        val text =
            DocumentFormat.save(ed.doc).trimEnd() + "\n" +
                "param \"rr\" = 1mm\n" +
                "tool chamferedge els=${ed.doc.nameOf(body)} clicks=0,0 scalar=\"rr\" signs=$rail -> eRail,eRailEntry\n"
        val back = Editor()
        back.replaceDocument(DocumentFormat.load(text))
        assertEquals(3, back.doc.elements.count { it.kind == ElementKind.SOLID }, "a dressing of its own on the rail:\n$text")
        val chained = back.doc.elements.last { it.kind == ElementKind.SOLID }
        assertNull((Evaluator().eval(chained.ref.node) as? EvalResult.Invalid)?.reason, "…and it builds to begin with")

        // now take off the rounding that rail belongs to
        val second = entriesOf(back.doc).first { back.doc.dressEntryIndex(it) == 1 }
        back.selectElement(second)
        assertTrue(back.deleteSelection(), "the removal is no longer refused: ${back.statusHint}")

        val why = assertNotNull((Evaluator().eval(chained.ref.node) as? EvalResult.Invalid)?.reason, "the rail's rounding is now invalid")
        assertTrue("was removed; nothing stands here" in why, "…and it names the rounding that went: $why")
        assertTrue("Round" in why && "itself instead" in why, "…with the gesture that works: $why")
        // …and the *body* it stands on is fine: only the address into the tombstone is not
        assertNull((Evaluator().eval(back.doc.elements.first { it.id == bodyOf(ed.doc).id }.ref.node) as? EvalResult.Invalid)?.reason, "the dressed body still builds")
    }

    // ---- 3. the file, undo, and deleting the body ----

    /**
     * **`save → load → save` is a fixed point with a tombstone in it**, and the loaded drawing is the very
     * same body: the row states the slot, the reader takes it verbatim, and nothing is worked out again.
     */
    @Test
    fun aTombstoneSurvivesSaveAndLoadAndTheFileIsAFixedPoint() {
        val ed = threeRoundings()
        ed.selectElement(entriesOf(ed.doc)[1])
        assertTrue(ed.deleteSelection(), "the middle rounding off: ${ed.statusHint}")
        val v = volumeOf(bodyOf(ed.doc))

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { "removed=" in it }, "the tombstone is written:\n$text")
        assertTrue(
            text.lines().first { "removed=" in it }.substringAfter("->", "").isBlank(),
            "…and it declares no name:\n$text",
        )
        val back = DocumentFormat.load(text)
        assertEquals(2, entriesOf(back).size, "two rows come back, not three")
        assertEquals(2, back.elements.count { it.kind == ElementKind.SOLID }, "…on the one body")
        assertClose(volumeOf(bodyOf(back)), v, abs(v) * 1e-9, "the same body")
        assertEquals(text, DocumentFormat.save(back), "and the file is a fixed point")

        // the slot came back too, in the very place it stood
        val f = assertNotNull(featureOf(bodyOf(back)) as? Feature3.Blend, "one dress-up feature")
        assertEquals(listOf(8, 9, 10), f.targets, "the tombstone keeps its target in place")
        assertEquals(mapOf(1 to 1), f.absent, "…marked absent, with the one band slot it held")
    }

    /** **Undo puts the rounding back**, and the tombstone goes with it — one step, as every edit is. */
    @Test
    fun undoPutsTheRoundingBackAndTheTombstoneGoes() {
        val ed = threeRoundings()
        val all = volumeOf(bodyOf(ed.doc))
        ed.selectElement(entriesOf(ed.doc)[1])
        assertTrue(ed.deleteSelection(), "the middle rounding off: ${ed.statusHint}")
        assertTrue("removed=" in DocumentFormat.save(ed.doc), "there is a tombstone")

        assertTrue(ed.undo(), "one undo")
        assertEquals(3, entriesOf(ed.doc).size, "all three rows back")
        assertTrue("removed=" !in DocumentFormat.save(ed.doc), "and no tombstone left:\n${DocumentFormat.save(ed.doc)}")
        assertClose(volumeOf(bodyOf(ed.doc)), all, abs(all) * 1e-9, "the body is what it was")
    }

    /**
     * **The first rounding's tombstone is still the step that makes the body** — it makes it *undressed*,
     * and the entries after it dress it. The declaration count is still the structure, one name fewer.
     */
    @Test
    fun theFirstRoundingsTombstoneStillDeclaresTheBody() {
        val ed = threeRoundings()
        val body = bodyOf(ed.doc)
        val slot = bandSlot(body, 10)
        ed.selectElement(entriesOf(ed.doc).first())
        assertTrue(ed.deleteSelection(), "the first rounding off: ${ed.statusHint}")
        assertEquals(slot, bandSlot(body, 10), "the third rounding kept its band slot")

        val text = DocumentFormat.save(ed.doc)
        val row = text.lines().first { "removed=" in it }
        assertTrue(row.substringAfter("-> ").trim().let { it.isNotEmpty() && !it.contains(',') }, "one name — the body: $row")
        val back = DocumentFormat.load(text)
        assertEquals(2, entriesOf(back).size, "two roundings")
        assertEquals(2, back.elements.count { it.kind == ElementKind.SOLID }, "…on the one body it always was")
        assertEquals(text, DocumentFormat.save(back), "and the file is a fixed point")
    }

    /**
     * **Every rounding of a dressing tombstoned but one, and the body is still the body.** Two removals in a
     * row leave two slots standing, and what is left builds — including the case where the first rounding's
     * step is the one that makes the body.
     */
    @Test
    fun twoTombstonesInARowStillLeaveABodyThatBuilds() {
        val ed = threeRoundings()
        for (i in listOf(1, 0)) {
            val el = entriesOf(ed.doc).first { ed.doc.dressEntryIndex(it) == i }
            ed.selectElement(el)
            assertTrue(ed.deleteSelection(), "rounding $i off: ${ed.statusHint}")
        }
        assertEquals(1, entriesOf(ed.doc).size, "one rounding left")
        val f = assertNotNull(featureOf(bodyOf(ed.doc)) as? Feature3.Blend, "one dress-up feature")
        assertEquals(mapOf(0 to 1, 1 to 1), f.absent, "two tombstones, both in place")
        assertNull((Evaluator().eval(bodyOf(ed.doc).ref.node) as? EvalResult.Invalid)?.reason, "and the body builds")

        // …and it is the body that one rounding makes on its own
        val fresh = plate()
        fresh.activeScalar = fresh.doc.newParameter("r", 5.0.mm)
        fresh.setTool(Tools.BLEND_EDGE)
        fresh.click(Vec2(20.0, 30.0))
        val v = volumeOf(bodyOf(ed.doc))
        assertClose(v, volumeOf(bodyOf(fresh.doc)), abs(v) * 1e-9, "the one remaining rounding's own body")
    }

    /** **Deleting the dressed body takes its tombstones with it** — they are its steps and nothing else's. */
    @Test
    fun deletingTheBodyTakesTheTombstonesWithIt() {
        val ed = threeRoundings()
        ed.selectElement(entriesOf(ed.doc)[1])
        assertTrue(ed.deleteSelection(), "the middle rounding off: ${ed.statusHint}")

        ed.selectElement(bodyOf(ed.doc))
        assertTrue(ed.deleteSelection(), "the body goes: ${ed.statusHint}")
        val text = DocumentFormat.save(ed.doc)
        assertTrue("filletedge" !in text, "no rounding step is left at all:\n$text")
        assertTrue("removed=" !in text, "…and no tombstone either:\n$text")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the extrusion stands alone")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the file is a fixed point")
    }
}
