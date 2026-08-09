package constructit

import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Painter3
import constructit.editor.Scene3
import constructit.editor.Scene3Sync
import constructit.editor.Styles
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **Show hidden** — the view toggle that makes a hidden element findable again (OP-18's visibility
 * reversal, on the user's report: *"if you hide some elements, it's almost impossible to find them later on
 * to show them again"*).
 *
 * The whole feature is a **view** setting, [Editor.dimScaffolding]'s exact twin, and the first thing these
 * tests pin down is that it stays one: toggling it writes nothing, saves nothing and undoes nothing. What it
 * does change is the picture — a hidden element is drawn as a dashed grey ghost — and, while it is on, what a
 * click can reach, so the recorded *Show* step finally has something to click.
 *
 * The three rules it is built on, each with a test of its own here:
 * - **A ghost never takes a click from geometry that is really there** — visible wins, always.
 * - **No tool builds on a ghost**, and the refusal says which element it was.
 * - **Hidden by construction is not hidden by the user**: a welded alias never ghosts, because showing it
 *   would draw a second point on top of its master, and [constructit.editor.Document.setElementsVisible]
 *   refuses to show one.
 */
class ShowHiddenTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
        pointerDown(s, constructit.editor.PointerButton.PRIMARY, additive)
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

    /** Three circles of radius 10, at x = -40, 0, 40 — [VisibilityTest]'s scene, for the same reason. */
    private fun scene(): Editor {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("r", Quantity.mm(10.0))
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0))
        return ed
    }

    /** Hide the circle at [at] by clicking it — the gesture, so the recorded step is the real one. */
    private fun Editor.hideCircleAt(at: Vec2) {
        setTool(Tools.SELECT)
        click(at)
        assertEquals(1, setSelectionVisible(false), "the click must have found the circle to hide")
    }

    /** A 10-thick wall from (20,0) to (20,60), extruded 12 deep — [SolidToolTest]'s plainest body. */
    private fun solidEditor(): Editor {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("t", Quantity.mm(10.0))
        ed.setTool(Tools.WALL)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(21.0, 60.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("depth", Quantity.mm(12.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 30.0))
        return ed
    }

    /** A fixed three-quarter view, so what the painter draws does not depend on any shell default. */
    private fun threeQuarterView() =
        Camera3(target = Vec3(0.0, 15.0, 8.0), distance = 190.0, yaw = -1.05, pitch = 0.5, fovY = PI / 4.0)

    private fun svgOf(ed: Editor): String {
        val target = SvgDrawTarget()
        ed.render(target)
        return target.svg()
    }

    // ---- the picture ----

    @Test
    fun aHiddenElementIsDrawnOnlyWhileTheToggleIsOn() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()

        val off = svgOf(ed)
        assertFalse(off.contains(Styles.GHOST.stroke), "with the toggle off a hidden element is not drawn: $off")

        ed.showHidden = true
        val on = svgOf(ed)
        assertTrue(on.contains(Styles.GHOST.stroke), "the ghost reaches the output: $on")
        assertTrue(on.contains("stroke-dasharray"), "and it is dashed, which is what tells it from the dim grey")
        Golden.check("editor_show_hidden_ghost", on)
    }

    /**
     * A ghost and dimmed scaffolding are two different states, both toggles can be on at once, and the whole
     * point of the dash is that the two are then told apart without reading a panel.
     */
    @Test
    fun aGhostReadsDifferentlyFromDimmedScaffolding() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()
        ed.showHidden = true
        ed.dimScaffolding = true
        val svg = svgOf(ed)
        assertNotEquals(Styles.DIMMED.stroke, Styles.GHOST.stroke, "two greys would not be a distinction")
        assertTrue(Styles.DIMMED.dash == null && Styles.GHOST.dash != null, "the dash is the distinction")
        assertTrue(svg.contains(Styles.GHOST.stroke), "the hidden circle still ghosts under the dim: $svg")
    }

    /** A dash is written only where there is one, so every golden taken before it existed still holds. */
    @Test
    fun anUndashedStyleWritesNoDashAttribute() {
        val ed = scene()
        assertFalse(svgOf(ed).contains("stroke-dasharray"), "nothing in an ordinary drawing is dashed")
    }

    // ---- it is a view setting, and nothing else ----

    @Test
    fun togglingWritesNothingToTheDrawing() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        val before = DocumentFormat.save(ed.doc)

        ed.showHidden = true
        assertEquals(before, DocumentFormat.save(ed.doc), "turning it on records nothing")
        ed.showHidden = false
        assertEquals(before, DocumentFormat.save(ed.doc), "and neither does turning it off")

        // …and it is not an undo step either: the one undo available is still the hide
        ed.showHidden = true
        assertTrue(ed.undo(), "the hide undoes")
        assertTrue(ed.doc.elements.all { it.visible }, "and what comes back is the circle, not the toggle")
        assertTrue(ed.showHidden, "the toggle is untouched by undo — it is not in the drawing")
    }

    // ---- reachable while shown, and it speaks ----

    @Test
    fun aGhostIsPickableAndSaysItIsHidden() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()

        ed.click(Vec2(-40.0, 10.0))
        assertTrue(ed.selectedElements.isEmpty(), "with the toggle off a hidden element is unpickable, as before")

        ed.showHidden = true
        ed.click(Vec2(-40.0, 10.0))
        assertEquals(1, ed.selectedElements.size, "under the toggle the ghost takes the click")
        assertFalse(ed.selectedElements.single().visible, "and it is the hidden one")
        assertTrue(
            ed.statusLine.contains("hidden (Show brings it back)"),
            "the pick has to say what it found: ${ed.statusLine}",
        )
        assertTrue(
            ed.statusLine.contains(ed.doc.nameOf(ed.selectedElements.single())),
            "by name: ${ed.statusLine}",
        )
    }

    /**
     * Two elements on top of one another, one hidden: the visible one takes the click. A ghost is a picture
     * of something the user took out of the drawing, so it may never steal a click from live geometry.
     */
    @Test
    fun whatIsVisibleWinsOverAGhost() {
        val ed = Editor()
        ed.setTool(Tools.CIRCLE_R)
        ed.activeScalar = ed.doc.newParameter("r", Quantity.mm(10.0))
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(0.0, 0.0))
        val circles = ed.doc.elements.filter { it.kind == ElementKind.CIRCLE }
        assertEquals(2, circles.size, "two circles exactly on top of each other")

        ed.selectElement(circles[0])
        assertEquals(1, ed.setSelectionVisible(false))
        ed.clearSelection()
        ed.setTool(Tools.SELECT)
        ed.showHidden = true

        ed.click(Vec2(0.0, 10.0))
        assertEquals(circles[1], ed.selectedElements.single(), "the visible circle wins the click")
        assertFalse(ed.statusLine.contains("hidden"), "and nothing claims a hidden thing was found: ${ed.statusLine}")

        // …and the ghost is still reachable where nothing live is: hide the winner too and click again
        assertEquals(1, ed.setSelectionVisible(false))
        ed.clearSelection()
        ed.click(Vec2(0.0, 10.0))
        assertEquals(2, ed.ghostElements().size, "both are ghosts now")
        assertTrue(ed.selectedElements.single() in circles, "with nothing live there, the click reaches a ghost")
    }

    /** A marquee takes what is drawn — so under the toggle it takes the ghosts, and otherwise never does. */
    @Test
    fun aMarqueeTakesGhostsOnlyUnderTheToggle() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()

        ed.drag(Vec2(-60.0, -20.0), Vec2(60.0, 20.0))
        assertEquals(2, ed.selectedElements.count { it.kind == ElementKind.CIRCLE }, "the hidden one is not in the box")

        ed.showHidden = true
        ed.drag(Vec2(-60.0, -20.0), Vec2(60.0, 20.0))
        assertEquals(3, ed.selectedElements.count { it.kind == ElementKind.CIRCLE }, "under the toggle it is")
    }

    // ---- no tool builds on a ghost, and the refusal names it ----

    @Test
    fun aToolClickThatFindsOnlyAGhostIsRefusedByName() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()
        ed.showHidden = true

        val ghost = ed.ghostElements().single()
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(-40.0, 10.0))
        assertTrue(ed.toolPicks.isEmpty(), "a ghost does not fill a slot")
        assertTrue(
            ed.statusLine.contains(ed.doc.nameOf(ghost)) && ed.statusLine.contains("is hidden"),
            "and the refusal names it and says why: ${ed.statusLine}",
        )
        assertTrue(ed.statusLine.contains("Show it first"), "with the way out: ${ed.statusLine}")
    }

    // ---- hidden by construction never resurrects ----

    @Test
    fun aWeldedAliasNeverGhosts() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 0.0))
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(30.0, 0.0), Vec2(0.0, 0.0))
        val alias = ed.doc.elements.last { it.isPoint }
        assertFalse(alias.visible, "a welded alias hides by construction")
        assertTrue(ed.doc.hiddenByConstruction(alias))

        ed.showHidden = true
        assertTrue(ed.ghostElements().isEmpty(), "the toggle reveals what the *user* hid, and nothing else")
        assertFalse(svgOf(ed).contains(Styles.GHOST.stroke), "so no ghost is drawn for the alias")
        ed.selectElement(alias)
        assertEquals(0, ed.setSelectionVisible(true), "and Show still refuses it (VisibilityTest)")
        assertTrue(
            ed.doc.stateOf(alias)!!.contains("hidden by the construction"),
            "…and a sentence about it does not promise a button that will decline: ${ed.doc.stateOf(alias)}",
        )
    }

    // ---- the round trip: hide it, find it, show it ----

    @Test
    fun hideFindShowIsOneRecordedShowAndAByteEqualFile() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()
        val hidden = ed.doc.elements.single { !it.visible }

        ed.showHidden = true
        ed.click(Vec2(-40.0, 10.0))
        assertEquals(hidden, ed.selectedElements.single(), "found again through the toggle")
        assertEquals(1, ed.setSelectionVisible(true), "and shown by the recorded step")
        assertTrue(ed.doc.elements.all { it.visible })

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("hide ") }, "one hide: $text")
        assertEquals(1, text.lines().count { it.startsWith("show ") }, "and exactly one show: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")

        ed.showHidden = false
        assertEquals(text, DocumentFormat.save(ed.doc), "with the toggle in either state")
    }

    /**
     * **The user's own drawing.** Their rounded pillar hides two construction elements (`hide els=e25`,
     * `hide els=e24`) — exactly the situation the report is about: the circle that placed an arc is gone from
     * the drawing and there is nothing left to click.
     */
    @Test
    fun theUsersPillarFindsItsHiddenConstructionAgain() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(LiftedRunTest.ROUND_PILLAR_CIT))
        assertEquals(atThisVersion(LiftedRunTest.ROUND_PILLAR_CIT), DocumentFormat.save(ed.doc), "the fixture round trips as it came")

        ed.showHidden = true
        val ghosts = ed.ghostElements()
        assertEquals(
            setOf("e24", "e25"),
            ghosts.map { ed.doc.nameOf(it) }.toSortedSet().toSet(),
            "the two the file hid, and nothing else",
        )

        // …and they are drawn where they live, which is the sketch space the pillar's section was cut in
        ed.setActiveSpace("plane1")
        assertTrue(svgOf(ed).contains(Styles.GHOST.stroke), "the hidden circle ghosts in its own space")

        val circle = ghosts.single { ed.doc.nameOf(it) == "e25" }
        ed.selectElement(circle)
        assertTrue(ed.statusLine.contains("hidden (Show brings it back)"), "and it says so: ${ed.statusLine}")
        assertEquals(1, ed.setSelectionVisible(true))

        val text = DocumentFormat.save(ed.doc)
        assertEquals(1, text.lines().count { it.startsWith("show ") }, "one show step: $text")
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and the file still replays byte for byte")
    }

    // ---- the 3D view ----

    /** A hidden solid ghosts as its **wireframe**: no faces, so it cannot occlude the bodies that are there. */
    @Test
    fun aHiddenSolidGhostsAsAWireframeIn3D() {
        val ed = solidEditor()
        val solid = ed.doc.elements.single { it.kind == ElementKind.SOLID }
        assertEquals(1, Scene3.extract(ed.doc).solids.size)

        ed.selectElement(solid)
        assertEquals(1, ed.setSelectionVisible(false))
        assertTrue(Scene3.extract(ed.doc).solids.isEmpty(), "hidden, it leaves the 3D scene entirely")

        ed.showHidden = true
        val scene = Scene3.extract(ed.doc, ghosts = ed.ghostElements())
        val item = scene.solids.single()
        assertTrue(item.ghost, "under the toggle it comes back as a ghost")
        assertEquals(Scene3.GHOST_EDGE, item.edgeColor, "in the ghost's own colour, not the body's darkened")
        assertTrue(item.edges.isNotEmpty(), "and it has a wireframe to draw")

        val target = SvgDrawTarget()
        Painter3.render(scene, threeQuarterView(), target, 400.0, 300.0)
        val svg = target.svg()
        assertFalse(svg.contains("<polygon"), "no faces are painted for a ghost: it must occlude nothing")
        assertTrue(svg.contains(Scene3.GHOST_EDGE), "its edges are: $svg")
    }

    /** The upload gate has to notice a body becoming a ghost — same id, same mesh, an empty triangle section. */
    @Test
    fun theSceneSyncNoticesGhosting() {
        val ed = solidEditor()
        ed.showHidden = true

        val sync = Scene3Sync()
        assertTrue(sync.update(Scene3.extract(ed.doc, ghosts = ed.ghostElements())) { }, "the first scene uploads")
        assertFalse(sync.update(Scene3.extract(ed.doc, ghosts = ed.ghostElements())) { }, "an unchanged one does not")

        ed.selectElement(ed.doc.elements.single { it.kind == ElementKind.SOLID })
        ed.setSelectionVisible(false)
        assertTrue(
            sync.update(Scene3.extract(ed.doc, ghosts = ed.ghostElements())) { },
            "hiding it while the toggle is on drops every triangle, so it must upload again",
        )
    }

    /**
     * **The toggle may never take geometry off the screen.** A boolean's operand is not drawn while its
     * consumer is — the 3D view's material rule — and hiding the consumer brings the operand back. Ghosting
     * the consumer must not re-consume it: switching a *view* setting on would then remove a body that is
     * really there.
     */
    @Test
    fun ghostingAConsumerDoesNotSwallowItsOperandAgain() {
        val ed = solidEditor()
        // a second wall crossing the first, extruded as well — two overlapping bodies to fuse
        ed.setTool(Tools.WALL)
        ed.activeScalar = ed.doc.newParameter("t2", Quantity.mm(10.0))
        ed.click(Vec2(0.0, 30.0))
        ed.click(Vec2(40.0, 30.0))
        ed.finishPath()
        ed.activeScalar = ed.doc.newParameter("depth2", Quantity.mm(12.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(35.0, 25.0))
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "two bodies to fuse")

        ed.setTool(Tools.UNION)
        ed.click(Vec2(15.0, 30.0))
        ed.click(Vec2(35.0, 25.0))
        val union = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        assertEquals(3, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "the union is the third: ${ed.statusLine}")
        assertEquals(1, Scene3.extract(ed.doc).solids.size, "its operands are its material, so only it draws")

        ed.selectElement(union)
        assertEquals(1, ed.setSelectionVisible(false))
        val plain = Scene3.extract(ed.doc).solids
        assertEquals(2, plain.size, "hiding the consumer shows its operands again — the rule as it stands")

        ed.showHidden = true
        val ghosted = Scene3.extract(ed.doc, ghosts = ed.ghostElements()).solids
        assertEquals(3, ghosted.size, "and the ghost joins them rather than swallowing them")
        assertEquals(1, ghosted.count { it.ghost }, "exactly one of them is the ghost")
        assertTrue(ghosted.filter { !it.ghost }.map { it.elementId }.containsAll(plain.map { it.elementId }))
    }

    /**
     * **A refused ghost click consumes nothing** — the half-collected gesture is exactly where it was.
     *
     * The pick-preserving clause of the refusal, asked of a **repeating** slot (Outline gathers curve after
     * curve), which is where losing a pick would cost the most: the ghost click must behave precisely like
     * the pre-existing "hit nothing pickable" miss, which reports and keeps.
     */
    @Test
    fun aRefusedGhostClickLeavesARepeatingCollectorAlone() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()
        ed.showHidden = true

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(0.0, 10.0))
        ed.click(Vec2(40.0, 10.0))
        assertEquals(2, ed.toolPicks.size, "two curves collected")

        ed.click(Vec2(-40.0, 10.0))
        assertTrue(ed.statusLine.contains("is hidden"), "the ghost click refuses by name: ${ed.statusLine}")
        assertEquals(2, ed.toolPicks.size, "…and the collector is untouched")

        // …exactly as a click on empty space does, which is the behaviour it has to match
        ed.click(Vec2(0.0, 500.0))
        assertEquals(2, ed.toolPicks.size, "the plain miss keeps them too — one rule, two reports")
    }

    /** …and it leaves the **selection** alone as well: a refusal is a report, never an edit of the state. */
    @Test
    fun aRefusedGhostClickLeavesTheSelectionAlone() {
        val ed = scene()
        ed.hideCircleAt(Vec2(-40.0, 10.0))
        ed.clearSelection()
        ed.showHidden = true

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 10.0))
        val chosen = ed.selectedElements.single()

        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(-40.0, 10.0))
        assertTrue(ed.statusLine.contains("is hidden"), "refused: ${ed.statusLine}")
        assertEquals(chosen, ed.selectedElements.singleOrNull(), "the selection is what it was")
    }
}
