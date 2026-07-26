package constructit

import constructit.core.CircleValue
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SegmentValue
import constructit.dsl.valueOf
import constructit.editor.CreateMode
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.PointerButton
import constructit.editor.ToolCategory
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **User-defined macros — OP-6's UI half.** Record a sub-construction, designate its inputs, get a
 * palette tool; clicking through the tool's slots builds an *instance*.
 *
 * The property that makes this the paradigm's headline feature, and what these tests are really about,
 * is **edit-propagation**: an instance is a path-addressed *view* over the definition's own subgraph, not
 * a copy of its geometry, so dragging an internal point of the original moves every instance. Purity
 * falls out of the same structure — an instance has exactly the degrees of freedom its arguments have.
 */
class MacroToolTest {
    private fun Editor.click(
        world: Vec2,
        additive: Boolean = false,
    ) {
        val s = camera.worldToScreen(world)
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

    /** Rubber-band everything in the box — the natural "select all" gesture (OP-16). */
    private fun Editor.marquee(
        from: Vec2,
        to: Vec2,
    ) = drag(from, to)

    private fun point(
        doc: Document,
        id: String,
    ): Vec2 = (Evaluator().valueOf(doc.elements.first { it.id == id }.ref) as PointValue).p

    private fun seg(el: Element) = (Evaluator().valueOf(el.ref) as SegmentValue).seg

    private fun circle(el: Element) = (Evaluator().valueOf(el.ref) as CircleValue).circle

    private fun pointOf(el: Element) = (Evaluator().valueOf(el.ref) as PointValue).p

    private fun assertAt(
        actual: Vec2,
        x: Double,
        y: Double,
        msg: String = "",
    ) {
        assertClose(actual.x, x, msg = "x: $msg")
        assertClose(actual.y, y, msg = "y: $msg")
    }

    private fun of(
        inst: constructit.editor.MacroInstance,
        kind: ElementKind,
    ) = inst.elements.first { it.kind == kind }

    /**
     * The construction every test records: two free points, the segment between them, its midpoint and a
     * circle of parameter `r` there. Five elements, two free points, one parameter — the smallest thing
     * that has an anchor, a second point, derived geometry and a scalar.
     */
    private fun recorded(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0)) // e1 — the anchor
        ed.click(Vec2(40.0, 0.0)) // e2
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // e3
        ed.setTool(Tools.MIDPOINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 0.0)) // e4
        ed.activeScalar = ed.doc.newParameter("r", 5.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(20.0, 0.0)) // e5, centred on the midpoint
        ed.setTool(Tools.SELECT)
        return ed
    }

    /** Select all five, then make a tool of them with the candidates the dialog offers ticked. */
    private fun makeWidget(
        ed: Editor,
        name: String = "widget",
        untick: List<String> = emptyList(),
    ): String {
        ed.marquee(Vec2(-40.0, -40.0), Vec2(80.0, 40.0))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL), "the dialog opens over a selection")
        d.name = name
        untick.forEach { label -> d.candidates.first { it.label == label }.checked = false }
        assertTrue(ed.confirmCreate(), "the tool is created")
        return ed.doc.macros.last().toolId
    }

    /** Place an instance: pick the scalars in the panel, then click the point slots. */
    private fun instantiate(
        ed: Editor,
        toolId: String,
        scalars: List<String>,
        at: List<Vec2>,
    ) {
        ed.setTool(toolId)
        scalars.forEach { n -> ed.activeScalar = ed.doc.scalars.first { it.name == n } }
        at.forEach { ed.click(it) }
    }

    // ---- the dialog: candidate derivation and the two defaults (OP-16, commonMain) ----

    @Test
    fun theDialogDerivesCandidatesFromTheClosureAndDefaultsPerMode() {
        val ed = recorded()
        ed.marquee(Vec2(-40.0, -40.0), Vec2(80.0, 40.0))

        val tool = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        assertEquals(listOf("e1", "e2", "r"), tool.candidates.map { it.label }, "free points, then parameters")
        assertTrue(tool.candidates.all { it.checked }, "make-tool default: every free source is an input")
        assertTrue(tool.ready)

        val group = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        assertEquals(listOf("e1", "e2"), group.candidates.map { it.label }, "a group takes elements, not parameters")
        assertTrue(group.candidates.none { it.checked }, "group default: a plain named set")
        assertTrue(group.ready)
        ed.cancelCreate()
        assertNull(ed.createDialog)
    }

    /**
     * The same closure question, answered the group way (OP-16): a free point the selection only *uses*
     * is a candidate either way — an input port for a tool, a member for a group.
     */
    @Test
    fun aTickedCandidateJoinsTheGroupInsteadOfBecomingAnInput() {
        val ed = recorded()
        ed.selectElement(ed.doc.elements.first { it.id == "e4" }) // the midpoint alone
        val d = assertNotNull(ed.beginCreate(CreateMode.GROUP))
        assertEquals(listOf("e1", "e2"), d.candidates.map { it.label }, "its ancestors are the candidates")
        d.name = "pair"
        d.toggle(0)
        assertTrue(ed.confirmCreate())
        val g = ed.doc.groups.single()
        assertEquals(listOf("e4", "e1"), ed.doc.groupMembers(g).map { it.id }, "the ticked ancestor joined it")
    }

    @Test
    fun aToolNeedsAPointToPlaceInstancesBy() {
        val ed = recorded()
        ed.marquee(Vec2(-40.0, -40.0), Vec2(80.0, 40.0))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        d.candidates.filter { it.isPoint }.forEach { it.checked = false }
        assertFalse(d.ready)
        assertFalse(ed.confirmCreate(), "with no point input an instance would have nowhere to go")
        assertTrue(ed.statusHint.contains("Tick at least one point"), "got: ${ed.statusHint}")
        assertTrue(ed.doc.macros.isEmpty())
    }

    // ---- instances ----

    /** The headline gesture: two instances, at different spots, with different radii. */
    @Test
    fun instancesAreBuiltWhereTheyAreClickedWithTheirOwnScalars() {
        val ed = recorded()
        val tool = makeWidget(ed)
        assertEquals("macro:widget", tool)
        val def = ed.doc.macros.single()
        assertEquals(ToolCategory.CUSTOM, def.tool.category, "it lands in the palette's Custom category")
        assertEquals(2, def.tool.slots.size, "two point slots, in the order they were ticked")
        assertEquals(listOf("r"), def.tool.scalars.map { it.name })
        assertEquals(def.toolId, ed.toolId, "and it is the active tool right away")

        ed.doc.newParameter("r2", 8.0.mm)
        instantiate(ed, tool, listOf("r2"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        val first = ed.doc.macroInstances.single()
        assertEquals(3, first.elements.size, "one element per definition element except the two inputs")
        assertEquals(
            listOf(ElementKind.SEGMENT, ElementKind.DERIVED_POINT, ElementKind.CIRCLE),
            first.elements.map { it.kind },
            "in definition order, with the internal point derived (it has no freedom of its own)",
        )
        assertAt(seg(first.elements[0]).a, 100.0, 0.0)
        assertAt(seg(first.elements[0]).b, 140.0, 0.0)
        assertAt(pointOf(first.elements[1]), 120.0, 0.0)
        assertAt(circle(first.elements[2]).center, 120.0, 0.0)
        assertClose(circle(first.elements[2]).radius, 8.0, msg = "its own radius parameter")

        // a second instance elsewhere, back on the original radius
        instantiate(ed, tool, listOf("r"), listOf(Vec2(0.0, 100.0), Vec2(30.0, 140.0)))
        val second = ed.doc.macroInstances.last()
        assertAt(seg(second.elements[0]).a, 0.0, 100.0)
        assertAt(seg(second.elements[0]).b, 30.0, 140.0)
        assertClose(circle(second.elements[2]).radius, 5.0)
        assertEquals(2, ed.doc.macroInstances.size)
    }

    /**
     * **Edit-propagation** (OP-6), the property the whole design rests on: `e2` is left *unticked*, so it
     * is an internal captured source rather than an input. Dragging it on the original therefore moves
     * every instance by the same amount — an instance reads the definition's node, it does not hold a
     * copy of its value.
     */
    @Test
    fun editingTheDefinitionUpdatesEveryInstance() {
        val ed = recorded()
        val tool = makeWidget(ed, untick = listOf("e2"))
        assertEquals(1, ed.doc.macros.single().tool.slots.size, "only the anchor is clicked now")

        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0)))
        instantiate(ed, tool, listOf("r"), listOf(Vec2(0.0, 100.0)))
        val a = ed.doc.macroInstances[0]
        val b = ed.doc.macroInstances[1]
        // the captured second point is stamped relative to the anchor
        assertAt(seg(of(a, ElementKind.SEGMENT)).b, 140.0, 0.0)
        assertAt(seg(of(b, ElementKind.SEGMENT)).b, 40.0, 100.0)

        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(40.0, 0.0), Vec2(40.0, 25.0)) // the *original's* internal point
        assertClose(point(ed.doc, "e2").y, 25.0, msg = "the original moved")
        assertAt(seg(of(a, ElementKind.SEGMENT)).b, 140.0, 25.0, "instance a followed")
        assertAt(seg(of(b, ElementKind.SEGMENT)).b, 40.0, 125.0, "instance b followed identically")

        // the derived geometry inside the instances followed too — nothing was re-run to make it
        assertClose(circle(of(a, ElementKind.CIRCLE)).center.y, 12.5)
        assertClose(circle(of(b, ElementKind.CIRCLE)).center.y, 112.5)
    }

    /** A captured *parameter* is shared by every instance in the same way (OP-6). */
    @Test
    fun editingACapturedParameterUpdatesEveryInstance() {
        val ed = recorded()
        val tool = makeWidget(ed, untick = listOf("r"))
        assertTrue(ed.doc.macros.single().tool.scalars.isEmpty(), "the radius is captured, not asked for")
        instantiate(ed, tool, emptyList(), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        instantiate(ed, tool, emptyList(), listOf(Vec2(0.0, 100.0), Vec2(40.0, 100.0)))

        ed.doc.setParameter(ed.doc.scalars.first { it.name == "r" }, 11.0.mm)
        for (inst in ed.doc.macroInstances) {
            assertClose(circle(of(inst, ElementKind.CIRCLE)).radius, 11.0)
        }
    }

    /** Dragging an instance's *input* point moves that instance only — its arguments are its whole DOF. */
    @Test
    fun draggingAnInstanceInputMovesThatInstanceAlone() {
        val ed = recorded()
        val tool = makeWidget(ed)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        instantiate(ed, tool, listOf("r"), listOf(Vec2(0.0, 100.0), Vec2(40.0, 100.0)))
        val a = ed.doc.macroInstances[0]
        val b = ed.doc.macroInstances[1]

        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(100.0, 0.0), Vec2(100.0, -30.0))
        assertAt(seg(of(a, ElementKind.SEGMENT)).a, 100.0, -30.0)
        assertAt(seg(of(a, ElementKind.SEGMENT)).b, 140.0, 0.0, "the other input is untouched")
        assertAt(seg(of(b, ElementKind.SEGMENT)).a, 0.0, 100.0, "and the other instance is untouched")
        assertClose(point(ed.doc, "e1").x, 0.0, msg = "the definition is untouched")
    }

    /**
     * Purity (OP-6): an instance's elements are not individually draggable. A handle would be a second
     * way to reach a value the definition owns, and there is no per-instance override.
     */
    @Test
    fun instanceElementsHaveNoDegreesOfFreedomOfTheirOwn() {
        val ed = recorded()
        val tool = makeWidget(ed)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        val inst = ed.doc.macroInstances.single()
        assertTrue(inst.elements.all { it.handle == null }, "no handle")
        assertTrue(inst.elements.none { it.draggable }, "so nothing to drag")

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(120.0, 0.0)) // the instance's midpoint
        assertTrue(ed.selectionFields().isEmpty(), "and nothing to type either")
    }

    /**
     * Nesting composes path-ids (OP-6): a tool made from a selection that *contains an instance* works
     * like any other, because an instance's nodes are ordinary nodes — and an edit of the innermost
     * definition still reaches the outermost instance.
     */
    @Test
    fun aToolCanBeMadeFromASelectionContainingAnInstance() {
        val ed = recorded()
        val inner = makeWidget(ed, name = "inner", untick = listOf("e2"))
        instantiate(ed, inner, listOf("r"), listOf(Vec2(100.0, 0.0)))

        // group the instance with its own anchor point and make *that* a tool
        ed.setTool(Tools.SELECT)
        ed.marquee(Vec2(80.0, -30.0), Vec2(160.0, 30.0))
        val d = assertNotNull(ed.beginCreate(CreateMode.TOOL))
        // a macro is a transparent group (OP-6), so the inner definition's own free points are in the
        // closure too — offered, but *not* ticked: the anchor defaults to a point the selection owns
        assertEquals(
            1,
            d.candidates.count { it.isPoint && it.checked },
            "only the instance's own argument point is an input by default; got: " +
                d.candidates.filter { it.isPoint }.joinToString { "${it.label}=${it.checked}" },
        )
        d.name = "outer"
        assertTrue(ed.confirmCreate(), "an instance can be part of a definition")
        val outer = ed.doc.macros.last().toolId

        instantiate(ed, outer, listOf("r"), listOf(Vec2(0.0, 200.0)))
        val nested = ed.doc.macroInstances.last()
        assertAt(seg(of(nested, ElementKind.SEGMENT)).a, 0.0, 200.0)
        assertAt(seg(of(nested, ElementKind.SEGMENT)).b, 40.0, 200.0)

        // an edit of the *innermost* definition propagates through both levels
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(40.0, 0.0), Vec2(40.0, 10.0))
        assertAt(seg(of(nested, ElementKind.SEGMENT)).b, 40.0, 210.0, "through two levels of instancing")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and it round-trips")
    }

    // ---- persistence ----

    @Test
    fun aDefinitionAndItsInstancesSurviveSaveLoadSaveByteIdentically() {
        val ed = recorded()
        val tool = makeWidget(ed)
        ed.doc.newParameter("r2", 8.0.mm)
        instantiate(ed, tool, listOf("r2"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        instantiate(ed, tool, listOf("r"), listOf(Vec2(0.0, 100.0), Vec2(40.0, 100.0)))

        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.contains("macrodef \"widget\" els=e1,e2,e3,e4,e5 pts=e1,e2 scalar=\"r\""), "got:\n$once")
        assertTrue(once.contains("tool macro:widget pts=e6,e7"), "an instance is an ordinary tool step; got:\n$once")

        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be identical")

        // the tool is part of the file, so the palette has it again
        assertEquals("widget", back.macros.single().name)
        assertNotNull(back.toolDef("macro:widget"), "the custom tool is back in the registry")
        assertTrue(back.toolDefs.any { it.id == "macro:widget" })

        // and the instances are valid geometry, not just steps
        assertEquals(2, back.macroInstances.size)
        assertAt(seg(back.macroInstances[0].elements[0]).a, 100.0, 0.0)
        assertClose(circle(back.macroInstances[0].elements[2]).radius, 8.0)
        assertAt(seg(back.macroInstances[1].elements[0]).a, 0.0, 100.0)
    }

    /** Instantiating is one operation, so one undo takes the whole instance and nothing else. */
    @Test
    fun definingAToolAndInstantiatingItAreOneUndoStepEach() {
        val ed = recorded()
        val tool = makeWidget(ed)
        val beforeInstance = DocumentFormat.save(ed.doc)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        val elements = ed.doc.elements.size
        assertEquals(1, ed.doc.macroInstances.size)

        assertTrue(ed.undo())
        assertEquals(beforeInstance, DocumentFormat.save(ed.doc), "one undo removes the instance")
        assertTrue(ed.doc.macroInstances.isEmpty())
        assertTrue(ed.doc.elements.size < elements)
        assertNotNull(ed.doc.toolDef(tool), "the tool itself is still there")

        assertTrue(ed.redo())
        assertEquals(1, ed.doc.macroInstances.size)

        // and declaring the tool is its own single step
        assertTrue(ed.undo())
        assertTrue(ed.undo())
        assertTrue(ed.doc.macros.isEmpty(), "one more undo un-declares the tool")
        assertNull(ed.doc.toolDef(tool))
    }

    // ---- the delete rule ----

    /**
     * **The rule: deleting a definition with live instances is refused, naming them.** Cascading was
     * rejected — an instance's step names the *tool*, not the definition's elements, so taking the
     * instances too would delete work the user never selected — and so was leaving them behind: an
     * instance's element count is structural (OP-18), so an orphan would be a file that no longer loads,
     * which is not the same thing as OP-3 invalidity.
     */
    @Test
    fun deletingADefinitionWithInstancesIsRefusedAndNamesThem() {
        val ed = recorded()
        val tool = makeWidget(ed)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        val before = DocumentFormat.save(ed.doc)

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 0.0)) // the definition's midpoint
        assertFalse(ed.deleteSelection(), "refused")
        assertTrue(ed.statusHint.contains("defines tool widget"), "got: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("instance"), "got: ${ed.statusHint}")
        assertEquals(before, DocumentFormat.save(ed.doc), "and nothing changed")

        // removing the tool itself is refused for the same reason, in the same words
        assertFalse(ed.deleteMacro(ed.doc.macros.single()))
        assertTrue(ed.statusHint.contains("still use it"), "got: ${ed.statusHint}")

        // delete the instance first, and both then work
        ed.click(Vec2(120.0, 0.0)) // an instance element
        assertTrue(ed.deleteSelection())
        assertTrue(ed.doc.macroInstances.isEmpty())
        assertTrue(ed.deleteMacro(ed.doc.macros.single()), "the tool goes once nothing instantiates it")
        assertTrue(ed.doc.macros.isEmpty())
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("macrodef"))
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "the script still round-trips")
    }

    /** Selecting the instances too is not a silent loss, so the whole lot goes in one operation. */
    @Test
    fun aDefinitionGoesWhenItsInstancesAreSelectedWithIt() {
        val ed = recorded()
        val tool = makeWidget(ed)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(20.0, 0.0)) // the definition's midpoint
        ed.click(Vec2(120.0, 0.0), additive = true) // and the instance's
        assertTrue(ed.deleteSelection(), "asked for both, so both go")
        assertTrue(ed.doc.macroInstances.isEmpty())
        assertTrue(ed.doc.macros.isEmpty(), "the definition went with its last element")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }

    /**
     * A rectilinear run holds its position in **shared coordinate scalars** rather than in point values
     * (OP-19/OP-20), which is the other kind of source an instance has to translate. It needs a free
     * point in the selection to be the anchor — the path's own vertices are derived — and given one, the
     * whole run stamps and still follows an edit of the original.
     */
    @Test
    fun aToolMadeFromAnOrthoRunTranslatesItsSharedCoordinates() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0)) // the anchor, and what the run starts on
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0)) // snaps onto the free point, so the run hangs off it
        ed.click(Vec2(30.0, 2.0)) // -> (30,0)
        ed.click(Vec2(28.0, 20.0)) // -> (30,20)
        ed.finishPath()
        ed.setTool(Tools.SELECT)

        val tool = makeWidget(ed, name = "stub")
        assertEquals(1, ed.doc.macros.single().tool.slots.size, "the free point is the only candidate")
        instantiate(ed, tool, emptyList(), listOf(Vec2(100.0, 100.0)))
        val inst = ed.doc.macroInstances.single()
        val legs = inst.elements.filter { it.kind == ElementKind.SEGMENT }
        assertEquals(2, legs.size)
        assertAt(seg(legs[0]).a, 100.0, 100.0)
        assertAt(seg(legs[0]).b, 130.0, 100.0, "the horizontal leg's own coordinate travelled with it")
        assertAt(seg(legs[1]).b, 130.0, 120.0, "and so did the vertical one's")

        // and it is still a view: pulling the original's corner reshapes the instance identically
        ed.setTool(Tools.SELECT)
        ed.drag(Vec2(30.0, 20.0), Vec2(45.0, 20.0))
        assertAt(seg(legs[0]).b, 145.0, 100.0, "the instance followed the definition's edit")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "and it round-trips")
    }

    /**
     * A definition **names** its elements, so the two topology edits that *replace* path elements — an
     * ortho break, and the join that flattens a jog — are refused on one. Left unguarded they would leave
     * the definition (and every instance's element count) describing geometry that is gone, which is a
     * file that no longer loads rather than something OP-3 could hide.
     */
    @Test
    fun aTopologyEditThatWouldRetireADefinitionElementIsRefused() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.ORTHO_PATH)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(60.0, 2.0))
        ed.finishPath()
        ed.setTool(Tools.SELECT)
        makeWidget(ed, name = "stub")
        val before = DocumentFormat.save(ed.doc)

        ed.setTool(Tools.BREAK_LEG)
        ed.click(Vec2(30.0, 0.0))
        assertTrue(ed.statusHint.contains("part of a tool's definition"), "got: ${ed.statusHint}")
        assertEquals(before, DocumentFormat.save(ed.doc), "the break did not happen")
        assertEquals(before, DocumentFormat.save(DocumentFormat.load(before)), "and the script still loads")
    }

    /** Deleting an instance is ordinary: its `tool` step goes, with the elements it created. */
    @Test
    fun anInstanceDeletesLikeAnyOtherToolApplication() {
        val ed = recorded()
        val tool = makeWidget(ed)
        instantiate(ed, tool, listOf("r"), listOf(Vec2(100.0, 0.0), Vec2(140.0, 0.0)))
        instantiate(ed, tool, listOf("r"), listOf(Vec2(0.0, 100.0), Vec2(40.0, 100.0)))

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(120.0, 0.0))
        assertTrue(ed.deleteSelection())
        assertEquals(1, ed.doc.macroInstances.size, "the other instance is untouched")
        assertClose(point(ed.doc, "e1").x, 0.0, msg = "and so is the definition")
        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)))
    }
}
