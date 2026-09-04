package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SourceNode
import constructit.dsl.valueOf
import constructit.editor.CreateMode
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.INCLUDE_CLOSURE_LABEL
import constructit.editor.PointerButton
import constructit.editor.Tools
import constructit.editor.writableMaster
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The group-creation refusal speaks the drawing's own language** (OP-16 × OP-18), from a user report:
 *
 * > "Can't place base: n2, oc1, oc2, n4, oc4, n7, oc9, n10 are also used by e3, e5, e7, … e31 — include them
 * > in the group, or this group cannot move independently"
 * >
 * > "completely annoying … almost impossible to do so…???"
 *
 * Four things were wrong with that message and none of them was the *decision* behind it (a base built from
 * the pillar's own section genuinely cannot move independently — the framed-by-default rule stands):
 *
 * - the group **had been created**, flat, and the message never said so, so it read as total failure;
 * - `n2`, `oc1`, … are raw internal node ids — an ortho vertex and the shared coordinates behind it — which
 *   OP-18 says the user never sees: the file's script-local name is the only name there is;
 * - the consumer list enumerated the whole drawing;
 * - and "include them in the group" asked for a hand-pick of dozens of elements with no way to do it.
 *
 * The fixture below is the reported shape and reproduces the reported ids **exactly**, which is what makes
 * these assertions a regression rather than a rewording exercise.
 */
class GroupCreationWordsTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s, PointerButton.PRIMARY, additive)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.marquee(
        a: Vec2,
        b: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(a))
        pointerMove(camera.worldToScreen(b))
        pointerUp(camera.worldToScreen(b))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    /**
     * The reported drawing: a **pillar** (a plan rectangle extruded), a **datum space** cutting it, and a
     * **molding** built from that section — key points of the section, midpoints and curves over them.
     *
     * The base is the pillar's plan outline: it is what a marquee over the foot takes, and it is what the
     * whole rest of the drawing is built on, which is precisely why the frame is refused.
     */
    private fun pillarSectionAndMolding(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("200")
        ed.click(Vec2(20.0, 0.0))

        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("100")
        ed.click(Vec2(20.0, 20.0))

        // the molding, built from the pillar's section: its four edges' key points, a midpoint and a
        // diagonal over them
        ed.setTool(Tools.KEY_POINTS)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(20.0, 40.0))
        ed.click(Vec2(0.0, 20.0))
        ed.click(Vec2(40.0, 20.0))
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 40.0))

        ed.doc.switchSpace("plan")
        return ed
    }

    /** Marquee the base in the plan and open the create dialog over it. */
    private fun Editor.groupTheBase(): constructit.editor.CreateDialog {
        marquee(Vec2(-20.0, -20.0), Vec2(60.0, 60.0))
        assertEquals(9, selectionCount, "the marquee takes the base's outline and the pillar on it")
        return assertNotNull(beginCreate(CreateMode.GROUP)).also { it.name = "base" }
    }

    /** The raw node ids the analysis works in — `n2`, `oc1`, … — none of which may reach the user. */
    private fun rawIds(ed: Editor): List<String> =
        ed.doc.orthoPaths.flatMap { p ->
            p.vertices.flatMap { v ->
                listOfNotNull(v.ref.node.id, writableMaster(v.corner.xNode)?.id, writableMaster(v.corner.yNode)?.id)
            }
        }.distinct()

    /** Every element name the drawing declares (OP-18), which is the only vocabulary a message may use. */
    private fun names(ed: Editor): Set<String> = ed.doc.elements.mapTo(HashSet()) { ed.doc.nameOf(it) }

    // ---- 1. the reported message, rewritten ----

    @Test
    fun theRefusedFrameLeadsWithWhatSucceededAndNamesTheDrawing() {
        val ed = pillarSectionAndMolding()

        // the fixture really is the reported one: these are the ids the old message printed. (Four of them
        // moved along when a fillet gained the machinery to supersede a corner — GitHub #25: a segment
        // spends one more id on the re-pointable view it is trimmed behind, and an ortho vertex two more on
        // its corner's own radius and setback. Which raw ids a drawing happens to hold is exactly the
        // incidental vocabulary this test exists to keep *out* of the message.)
        val raw = rawIds(ed)
        assertTrue(raw.containsAll(listOf("n2", "oc1", "oc2", "n4", "oc6", "n8", "oc13", "n12")), "got: $raw")

        val d = ed.groupTheBase()
        assertTrue(d.framed, "the frame is the default (OP-16, session 13) — that decision is not what changed")
        assertTrue(ed.confirmCreate(), "the group is made either way")

        val g = ed.doc.groups.single()
        assertEquals("base", g.name)
        assertFalse(g.placed, "the frame is honestly refused: the drawing is built on this outline")

        val s = ed.statusHint
        assertTrue(s.startsWith("Grouped 9 elements as base"), "it leads with what succeeded: $s")
        assertTrue(s.contains("— flat:"), "and states the outcome as a kind of group: $s")

        // not one internal id, of any kind
        for (id in raw) assertFalse(Regex("(?<![\\w.])${Regex.escape(id)}(?![\\w.])").containsMatchIn(s), "$id leaked: $s")
        assertFalse(Regex("\\bn\\d+\\b").containsMatchIn(s), "a node id leaked: $s")
        assertFalse(Regex("\\boc\\d+\\b").containsMatchIn(s), "an ortho-coordinate id leaked: $s")

        // and every name it *does* use is one the drawing declares
        val used = Regex("\\be\\d+\\b").findAll(s).map { it.value }.toList()
        assertTrue(used.isNotEmpty(), "it still names things: $s")
        assertTrue(names(ed).containsAll(used), "used $used, drawing has ${names(ed)}")

        // the eight raw ids are four corners of the base — named once each, not eight times
        assertTrue(s.contains("e1, e2, e4, e6 are also used by"), "the shared positions are the corners: $s")

        // the 17 consumers are summarized rather than enumerated
        assertTrue(s.contains("e10, e11, e12 and 11 more of the drawing"), "the consumers are summarized: $s")
        assertFalse(s.contains("e23"), "so the tail of the drawing is counted, not listed: $s")

        // …and it says what to do about it, in the words the dialog uses
        assertTrue(s.contains(INCLUDE_CLOSURE_LABEL), "the way through is named: $s")
        assertTrue(s.contains("or leave it flat"), "and so is the other honest answer: $s")
    }

    /** The failed-frame creation is **one** gesture, hence one undo — the group and nothing else. */
    @Test
    fun aRefusedFrameIsStillOneUndoStep() {
        val ed = pillarSectionAndMolding()
        val before = DocumentFormat.save(ed.doc)
        ed.groupTheBase()
        assertTrue(ed.confirmCreate())
        assertEquals(1, ed.doc.groups.size)

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo removes the whole gesture")
        assertTrue(ed.doc.groups.isEmpty())
    }

    // ---- 2. the one-click closure ----

    @Test
    fun theClosureIsOfferedWithACountAndMakesTheGroupPlaceable() {
        val ed = pillarSectionAndMolding()
        val d = ed.groupTheBase()

        // offered before anything is created, with the size of the decision
        assertTrue(d.hasClosure)
        assertEquals(INCLUDE_CLOSURE_LABEL, d.closureLabel)
        val extra = d.closure.map { ed.doc.nameOf(it) }
        assertEquals(14, extra.size, "everything built on the base: $extra")
        assertTrue(d.closureNote.contains("+ 14 elements"), "the count is stated first: ${d.closureNote}")
        assertTrue(d.closureNote.contains("that is the whole drawing"), "and honestly: ${d.closureNote}")

        // one click, and the membership is exactly the closure
        assertTrue(d.includeClosure())
        assertTrue(d.closureTaken)
        assertEquals(9 + 14, d.members.size)
        assertEquals(ed.doc.elements.size, d.members.size, "which here is the drawing")
        assertFalse(d.hasClosure.not(), "the tick stays visible once taken")
        assertTrue(d.closure.isEmpty(), "and there is nothing more to take")

        val positions = ed.doc.elements.associate { it.id to (Evaluator().valueOf(it.ref) as? PointValue)?.p }
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertEquals(23, ed.doc.groupMembers(g).size)
        assertTrue(g.placed, "and now the frame works: ${ed.statusHint}")
        assertTrue(ed.statusHint.startsWith("Grouped 23 elements as base"), "got: ${ed.statusHint}")

        // placing moved nothing — the retrofit is world-invariant (OP-16)
        for ((id, p) in positions) {
            if (p == null) continue
            val now = (Evaluator().valueOf(ed.doc.elements.first { it.id == id }.ref) as PointValue).p
            assertClose(now.x, p.x, 1e-9, "$id x")
            assertClose(now.y, p.y, 1e-9, "$id y")
        }

        // …and the frame carries the whole figure rigidly
        ed.click(Vec2(0.0, -80.0))
        ed.click(Vec2(0.0, 0.0))
        assertEquals(g, ed.selectedFrame(), "a member click reaches the placed group: ${ed.statusHint}")
        ed.drag(Vec2(0.0, 0.0), Vec2(30.0, 25.0))
        // …including the molding on the datum plane, which follows because the *section* it is built from
        // follows: the plane itself is untouched (a sketch space's origin is the space's own freedom and is
        // never captured — see [Document.isSpaceAnchor]), so the section of a pillar that moved lands moved.
        for ((id, p) in positions) {
            if (p == null) continue
            val now = (Evaluator().valueOf(ed.doc.elements.first { it.id == id }.ref) as PointValue).p
            assertClose(now.x, p.x + 30.0, 1e-6, "$id x follows the frame")
            assertClose(now.y, p.y + 25.0, 1e-6, "$id y follows the frame")
        }
        assertTrue(ed.doc.elements.any { ed.doc.spaceOf(it).name != "plan" }, "…and the datum space was exercised")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save is byte-equal")
    }

    /**
     * **A sketch space's origin is the space's own freedom, never the group's** (OP-17 × OP-16, found by the
     * reviewer's probe on the user's own file).
     *
     * `plane1.origin` is an unbound `PointValue` source that no element displays, so the capture claimed it for
     * whatever group's closure reached it — and then a frame drag slid the whole sketch on that plane sideways
     * in u/v. Nothing restates that anchor, so replay rebuilt it at (0, 0) and the file reloaded to a different
     * drawing: a rider's `dofs=` came back a frame-delta out. It is now excluded, and the anchor stands still.
     */
    @Test
    fun aPlacedGroupNeverCapturesASketchSpacesOrigin() {
        val ed = pillarSectionAndMolding()
        val d = ed.groupTheBase()
        assertTrue(d.includeClosure())
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertTrue(g.placed, "got: ${ed.statusHint}")

        val anchors = ed.doc.spaces.mapNotNull { it.originAnchor?.node }
        assertTrue(anchors.isNotEmpty(), "the datum space has one")
        assertTrue(g.captures.none { c -> anchors.any { it === c.original } }, "and the frame took none of them")
        for (a in anchors) {
            assertTrue((a as SourceNode).boundTo == null, "an anchor is not bound onto a frame")
            val at = (Evaluator().eval(a) as EvalResult.Ok).value as PointValue
            assertClose(at.p.x, 0.0, 1e-9, "the anchor stands at its plane's own origin")
            assertClose(at.p.y, 0.0, 1e-9)
        }

        // and it still stands there once the frame has moved — which is what makes the file reload the same
        ed.click(Vec2(0.0, -80.0))
        ed.click(Vec2(0.0, 0.0))
        ed.drag(Vec2(0.0, 0.0), Vec2(30.0, 25.0))
        for (a in anchors) {
            val at = (Evaluator().eval(a) as EvalResult.Ok).value as PointValue
            assertClose(at.p.x, 0.0, 1e-9, "a frame drag does not move a plane's origin")
            assertClose(at.p.y, 0.0, 1e-9)
        }
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save is byte-equal")
    }

    /** Creating **with** the closure is one gesture too: one undo removes the group and its frame. */
    @Test
    fun creatingWithTheClosureIsOneUndoStep() {
        val ed = pillarSectionAndMolding()
        val before = DocumentFormat.save(ed.doc)
        val d = ed.groupTheBase()
        assertTrue(d.includeClosure())
        assertTrue(ed.confirmCreate())
        val after = DocumentFormat.save(ed.doc)
        assertTrue(after.lines().any { it.startsWith("group \"base\"") })
        assertTrue(after.lines().any { it.startsWith("place \"base\"") })

        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo removes the group *and* its frame")
        assertTrue(ed.doc.groups.isEmpty())
        assertTrue(ed.redo())
        assertEquals(after, DocumentFormat.save(ed.doc))
    }

    // ---- 3. the other two sites that print the same analysis ----

    /** The panel's Place button gives the same sentence, prefixed by the refusal it is. */
    @Test
    fun theStandalonePlaceRefusalUsesTheSameWords() {
        val ed = pillarSectionAndMolding()
        val d = ed.groupTheBase()
        d.framed = false
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertFalse(g.placed)

        assertFalse(ed.placeGroup(g), "the panel route refuses for the same reason")
        val s = ed.statusHint
        assertTrue(s.startsWith("Can't place base: e1, e2, e4, e6 are also used by"), "got: $s")
        assertTrue(s.contains("e10, e11, e12 and 11 more of the drawing"), "summarized here too: $s")
        assertTrue(s.contains("cannot move independently"), "got: $s")
        assertTrue(s.contains(INCLUDE_CLOSURE_LABEL), "and names the way through: $s")
        assertFalse(Regex("\\bn\\d+\\b|\\boc\\d+\\b").containsMatchIn(s), "no internal id: $s")
    }

    /** `placementWarnings` — asked at creation time — is held to the same vocabulary. */
    @Test
    fun placementWarningsNameTheDrawingAndSummarize() {
        val ed = pillarSectionAndMolding()
        val d = ed.groupTheBase()
        d.framed = false
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()

        val warnings = ed.doc.placementWarnings(g)
        assertTrue(warnings.isNotEmpty(), "this group cannot move independently, and says so")
        val all = warnings.joinToString(" | ")
        assertFalse(Regex("\\bn\\d+\\b|\\boc\\d+\\b").containsMatchIn(all), "no internal id: $all")
        for (id in rawIds(ed)) {
            assertFalse(Regex("(?<![\\w.])${Regex.escape(id)}(?![\\w.])").containsMatchIn(all), "$id leaked: $all")
        }
        val used = Regex("\\be\\d+\\b").findAll(all).map { it.value }.toList()
        assertTrue(used.isNotEmpty() && names(ed).containsAll(used), "used $used in: $all")
        assertTrue(all.contains("and 11 more of the drawing"), "and the flood is a count: $all")

        // the dialog carries them at creation time, in the same words (OP-16's honest-failure rule)
        assertEquals(warnings, d.warnings)
    }
}
