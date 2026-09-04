package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **An ortho vertex can be re-anchored, one coordinate at a time** — GitHub issue #23, OP-4 case (b) applied
 * to a path's coordinate chains instead of to a point literal.
 *
 * Reported on a closed rectilinear loop: the closing leg e12 changes length whenever the far side of the
 * figure is dragged, and *Make relative* refused to state that it should not — *"e10 is not a free point:
 * only a point that owns its coordinates can be re-anchored"*. The refusal was structural rather than
 * principled: a vertex publishes `pointXY(x, y)` through a re-pointable view, so `literalNode` finds no
 * point literal, while the freedom it actually owns is *one source per coordinate chain*.
 *
 * The user's own reading is the design: *"in an ortho path the y coordinate of e1 and e10 already depend on
 * each other — but additionally it is a valid requirement to also make the x coordinate dependent."* So each
 * axis is answered on its own — the y is left exactly as the loop's closure already states it, and the x is
 * re-parameterized onto the anchor with a signed offset that becomes that coordinate's degree of freedom
 * (draggable from either end of the chain, typeable as the coordinate, as the leg's length, or as itself).
 */
class OrthoVertexAnchorTest {
    /** The reported drawing, verbatim. */
    private val loop =
        """
constructit 3
orthostart -68.625,23.375 -> e1
orthovertex -68.625,68.875 -> e2,e3
orthovertex 13.625,68.875 -> e4,e5
orthovertex 13.625,55.875 -> e6,e7
orthovertex -48.375,55.875 -> e8,e9
orthovertex -48.375,23.375 -> e10,e11
orthoclose -> e12
""".trimStart()

    private fun loaded(script: String = loop): Editor = Editor().also { it.replaceDocument(DocumentFormat.load(script)) }

    private fun el(
        ed: Editor,
        n: Int,
    ): Element = ed.doc.elements[n - 1]

    private fun pos(el: Element): Vec2 = ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as PointValue).p

    /** The length of the closing leg e12 — the quantity the report is about. */
    private fun closingLeg(ed: Editor): Double = (pos(el(ed, 10)) - pos(el(ed, 1))).length()

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(from + (to - from) * 0.5))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /** Select whatever is at [world] and write [value] into the field labelled [label] — as the panel does. */
    private fun typeAt(
        ed: Editor,
        world: Vec2,
        label: String,
        value: Double,
    ): Boolean {
        ed.setTool(Tools.SELECT)
        ed.click(world)
        val i = ed.selectionFields().indexOfFirst { it.label == label }
        assertTrue(i >= 0, "no field '$label' — got ${ed.selectionFields().map { it.label }}")
        return ed.writeSelectionField(i, value)
    }

    private fun type(
        ed: Editor,
        el: Element,
        label: String,
        value: Double,
    ): Boolean = typeAt(ed, pos(el), label, value)

    private fun fieldLabels(
        ed: Editor,
        el: Element,
    ): List<String> {
        ed.setTool(Tools.SELECT)
        ed.click(pos(el))
        return ed.selectionFields().map { it.label }
    }

    /** The drawing survives being written, read and written again — byte for byte (OP-18). */
    private fun assertRoundTrips(
        ed: Editor,
        what: String,
    ) {
        val saved = DocumentFormat.save(ed.doc)
        val again = Editor()
        again.replaceDocument(DocumentFormat.load(saved))
        assertEquals(saved, DocumentFormat.save(again.doc), "$what is a fixed point of the file")
    }

    // ---- the report ----

    /**
     * **The report, closed.** e10 is anchored to e1 along x, and from then on dragging the vertical leg e3
     * across the drawing carries the whole bottom of the figure: e12 keeps the 20.25 mm it had.
     */
    @Test
    fun theClosingLegKeepsItsLengthWhenTheFarSideMoves() {
        val ed = loaded()
        val before = closingLeg(ed)
        assertClose(before, 20.25, 1e-9)

        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)), "e10 anchored to e1: ${ed.doc.note}")
        assertEquals(
            "e10 now follows e1 along x — the offset is its degree of freedom (drag it, type it, or give dx a formula)",
            ed.doc.takeNote(),
        )
        // nothing moved at the moment of the change (OP-4 case b)
        assertClose(pos(el(ed, 10)).x, -48.375, 1e-9)
        assertClose(closingLeg(ed), before, 1e-9)

        // drag leg e3 (the vertical leg at x = -68.625) 10 mm to the right
        ed.drag(Vec2(-68.625, 46.0), Vec2(-58.625, 46.0))
        assertClose(pos(el(ed, 1)).x, -58.625, 1e-9, "e1 followed the leg")
        assertClose(pos(el(ed, 10)).x, -38.375, 1e-9, "and e10 came with it")
        assertClose(closingLeg(ed), before, 1e-6, "e12 keeps its length")

        // the y axis was left exactly as the closure already states it: one relation, stated once
        assertEquals(1, ed.doc.orthoRelativeOf(el(ed, 10)).size)
        assertEquals(0, ed.doc.orthoRelativeOf(el(ed, 10)).single().axis)
    }

    /** The same through the tool, with the step in the journal — and one undo gives the free vertex back. */
    @Test
    fun theToolAnchorsTheVertexAndUndoFreesItAgain() {
        val ed = loaded()
        ed.setTool(Tools.MAKE_RELATIVE)
        ed.click(pos(el(ed, 10)))
        ed.click(pos(el(ed, 1)))
        assertTrue(ed.statusHint.contains("e10 now follows e1 along x"), "got: ${ed.statusHint}")
        assertTrue(DocumentFormat.save(ed.doc).contains("tool makerel"), "got:\n${DocumentFormat.save(ed.doc)}")

        ed.drag(Vec2(-68.625, 46.0), Vec2(-58.625, 46.0))
        assertClose(closingLeg(ed), 20.25, 1e-6)

        // undo the drag, then undo the anchoring: the vertex owns its x again, so the leg stretches
        assertTrue(ed.undo())
        assertTrue(ed.undo())
        assertFalse(DocumentFormat.save(ed.doc).contains("makerel"), "got:\n${DocumentFormat.save(ed.doc)}")
        assertTrue(ed.doc.orthoRelativeOf(el(ed, 10)).isEmpty())
        ed.drag(Vec2(-68.625, 46.0), Vec2(-58.625, 46.0))
        assertClose(closingLeg(ed), 10.25, 1e-6, "a free vertex lets the closing leg change again")
    }

    // ---- the offset is the degree of freedom (OP-13) ----

    /** Dragging the anchored vertex along x writes the **offset** — and leaves the anchor where it is. */
    @Test
    fun draggingTheAnchoredVertexWritesTheOffset() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        val offset = ed.doc.orthoRelativeOf(el(ed, 10)).single().offset
        assertClose(offset.literal.q.mm, 20.25, 1e-9)

        ed.drag(pos(el(ed, 10)), pos(el(ed, 10)) + Vec2(10.0, 0.0))
        assertClose(pos(el(ed, 10)).x, -38.375, 1e-9, "the vertex went where the cursor did")
        assertClose(pos(el(ed, 1)).x, -68.625, 1e-9, "and the anchor stayed put")
        assertClose(offset.literal.q.mm, 30.25, 1e-9, "the drag wrote the offset")
        assertRoundTrips(ed, "a dragged offset")
    }

    /** …and so does typing it, or the coordinate, or the leg's length: one DOF, whichever field reaches it. */
    @Test
    fun theOffsetAndTheCoordinateAndTheLegLengthAllWriteTheSameFreedom() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        val offset = ed.doc.orthoRelativeOf(el(ed, 10)).single().offset

        // the offset's own field, named after the anchor it is measured from
        assertEquals(
            listOf("x", "y", "leg length", "offset from e1 along x"),
            fieldLabels(ed, el(ed, 10)),
        )
        assertTrue(type(ed, el(ed, 10), "offset from e1 along x", 40.0))
        assertClose(pos(el(ed, 10)).x, -28.625, 1e-9)
        assertClose(closingLeg(ed), 40.0, 1e-9)

        // the coordinate itself — a driven coordinate is still typeable, because the offset is what it writes
        assertTrue(type(ed, el(ed, 10), "x", -8.625))
        assertClose(pos(el(ed, 10)).x, -8.625, 1e-9)
        assertClose(pos(el(ed, 1)).x, -68.625, 1e-9)
        assertClose(offset.literal.q.mm, 60.0, 1e-9)

        // and the horizontal leg e9, whose far end *is* this coordinate chain: the user's "fix the length of
        // an ortho leg", typed rather than asserted
        assertTrue(typeAt(ed, (pos(el(ed, 6)) + pos(el(ed, 8))) * 0.5, "length (move end)", 50.0))
        assertClose(pos(el(ed, 8)).x - pos(el(ed, 6)).x, -50.0, 1e-9)
        assertClose(pos(el(ed, 10)).x, -36.375, 1e-9, "e10 rides the same chain")
        assertRoundTrips(ed, "a typed offset")
    }

    /** Dragging the *leg* across itself writes the offset too — it is one coordinate chain, seen from a leg. */
    @Test
    fun draggingALegOnTheAnchoredChainWritesTheOffset() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        // leg e11 is the vertical leg at x = -48.375, between e8 and e10
        ed.drag(Vec2(-48.375, 40.0), Vec2(-38.375, 40.0))
        assertClose(pos(el(ed, 10)).x, -38.375, 1e-9)
        assertClose(pos(el(ed, 8)).x, -38.375, 1e-9)
        assertClose(pos(el(ed, 1)).x, -68.625, 1e-9, "the anchor did not move")
        assertClose(ed.doc.orthoRelativeOf(el(ed, 10)).single().offset.literal.q.mm, 30.25, 1e-9)
    }

    // ---- the inverse ----

    /** *Make absolute* hands the coordinate back where it stands — which is what makes this a conversion. */
    @Test
    fun makeAbsoluteGivesTheCoordinateBack() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        ed.drag(pos(el(ed, 10)), pos(el(ed, 10)) + Vec2(10.0, 0.0))
        val was = pos(el(ed, 10))

        assertTrue(ed.doc.makeAbsolute(el(ed, 10)), "freed: ${ed.doc.note}")
        assertEquals(
            "e10 keeps its position and owns its x again — drag it, or type it",
            ed.doc.takeNote(),
        )
        assertEquals(was, pos(el(ed, 10)), "nothing moved at the moment of the change")
        assertTrue(ed.doc.orthoRelativeOf(el(ed, 10)).isEmpty())

        // it is a freedom of its own again, so the far side no longer carries it
        ed.drag(Vec2(-68.625, 46.0), Vec2(-58.625, 46.0))
        assertClose(pos(el(ed, 10)).x, -38.375, 1e-9)
        assertClose(closingLeg(ed), 20.25, 1e-6)
        assertRoundTrips(ed, "a freed vertex")
    }

    /** Both steps together are a fixed point of the file, and the freed coordinate is restated (OP-18). */
    @Test
    fun theRelativeAndAbsoluteStepsSurviveTheFile() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        val saved = DocumentFormat.save(ed.doc)
        assertTrue(saved.contains("relative e10 e1 dofs=20.25mm"), "got:\n$saved")
        assertRoundTrips(ed, "the relative step")

        // drag it, free it, drag it again: the freed literal is state on the `absolute` step or it is lost
        ed.drag(pos(el(ed, 10)), pos(el(ed, 10)) + Vec2(10.0, 0.0))
        assertTrue(ed.doc.makeAbsolute(el(ed, 10)))
        ed.drag(pos(el(ed, 10)), pos(el(ed, 10)) + Vec2(5.0, 0.0))
        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.contains("absolute e10 dofs="), "got:\n$text")
        assertRoundTrips(ed, "the absolute step")

        val back = Editor()
        back.replaceDocument(DocumentFormat.load(text))
        assertClose(pos(el(back, 10)).x, pos(el(ed, 10)).x, 1e-9, "and the vertex reloads where it stood")
    }

    // ---- generic: any point may be the anchor, and both axes bind when both can ----

    /** A point **off the path** owns neither coordinate chain, so both axes are stated and the vertex rides it. */
    @Test
    fun aFreePointOffThePathAnchorsBothAxes() {
        val ed = loaded(loop + "point 0,0 -> e13\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 13)), "${ed.doc.note}")
        assertEquals(
            "e10 now follows e13 along x and y — the offsets are its degrees of freedom " +
                "(drag it, type them, or give dx and dy a formula)",
            ed.doc.takeNote(),
        )
        assertEquals(listOf(0, 1), ed.doc.orthoRelativeOf(el(ed, 10)).map { it.axis })
        val was = pos(el(ed, 10))

        // moving the anchor takes the vertex — and the whole coordinate chains it sits on — along
        ed.drag(Vec2(0.0, 0.0), Vec2(10.0, -5.0))
        assertClose(pos(el(ed, 10)).x, was.x + 10.0, 1e-9)
        assertClose(pos(el(ed, 10)).y, was.y - 5.0, 1e-9)
        assertRoundTrips(ed, "a vertex anchored on both axes")
    }

    /** Another **ortho vertex** is an anchor like any other: here only the y is free to be stated. */
    @Test
    fun anotherVertexOfTheSamePathAnchorsTheAxisThatIsStillFree() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 8)), "${ed.doc.note}")
        assertEquals(
            "e10 now follows e8 along y — the offset is its degree of freedom (drag it, type it, or give dy a formula)",
            ed.doc.takeNote(),
        )
        // leg e11's length is stated now: dragging the horizontal leg e9 carries the bottom of the figure
        val was = pos(el(ed, 10)).y - pos(el(ed, 8)).y
        ed.drag(Vec2(-20.0, 55.875), Vec2(-20.0, 45.875))
        assertClose(pos(el(ed, 8)).y, 45.875, 1e-9)
        assertClose(pos(el(ed, 10)).y - pos(el(ed, 8)).y, was, 1e-6, "leg e11 keeps its length")
        assertRoundTrips(ed, "a vertex anchored to a vertex")
    }

    /** A **rider** is a point too (OP-20), and anchoring to one is the same operation. */
    @Test
    fun aRiderCanBeTheAnchor() {
        val ed = loaded(loop + "pointoncurve e5 0,68.875 -> e13\n")
        val rider = ed.doc.elements.last()
        assertEquals(ElementKind.ON_CURVE, rider.kind)
        assertTrue(ed.doc.makeRelative(el(ed, 10), rider), "${ed.doc.note}")
        val was = pos(el(ed, 10))
        // sliding the rider along its host moves the vertex's x with it
        ed.drag(pos(rider), pos(rider) + Vec2(12.0, 0.0))
        assertClose(pos(el(ed, 10)).x, was.x + 12.0, 1e-6)
        assertRoundTrips(ed, "a vertex anchored to a rider")
    }

    // ---- what it refuses, and in what words (session 65: no route declines silently) ----

    /** One anchor at a time — freed before it is replaced, the polar form's own rule. */
    @Test
    fun aSecondAnchorRefusesByName() {
        val ed = loaded(loop + "point 0,0 -> e13\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        assertFalse(ed.doc.makeRelative(el(ed, 10), el(ed, 13)))
        assertEquals(
            "e10 already follows e1 — free it first (Make absolute), then anchor it",
            ed.doc.takeNote(),
        )
    }

    /** Nothing left to state — every axis already related, on both readings of the one test. */
    @Test
    fun anAnchorThatAlreadyFollowsRefusesByName() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        assertFalse(ed.doc.makeRelative(el(ed, 1), el(ed, 10)))
        assertEquals(
            "Can't anchor e1 to e10: e10's x already follows e1's, and e10's y already follows e1's — " +
                "there is nothing left to state. Pick an anchor whose coordinates e1's do not already follow.",
            ed.doc.takeNote(),
        )
    }

    /**
     * A coordinate another vertex's anchoring already took is named **for what took it** — an anchoring is
     * not a weld, and telling the user to free an end they never made would be the wrong true sentence.
     */
    @Test
    fun aCoordinateAnotherAnchoringAlreadyTookIsNamedByItsAnchor() {
        val ed = loaded(loop + "point 0,0 -> e13\n")
        // e10 takes e8's x chain, e6 takes e8's y chain — so e8 has neither axis left to state
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 13)), "${ed.doc.note}")
        assertTrue(ed.doc.makeRelative(el(ed, 6), el(ed, 13)), "${ed.doc.note}")
        assertFalse(ed.doc.makeRelative(el(ed, 8), el(ed, 13)))
        assertEquals(
            "Can't anchor e8 to e13: its x already follows e13, and its y already follows e13 — there is " +
                "nothing left to state. Pick an anchor whose coordinates e8's do not already follow.",
            ed.doc.takeNote(),
        )
    }

    /** A leg is not a point. */
    @Test
    fun aLegIsNotAnAnchor() {
        val ed = loaded()
        assertFalse(ed.doc.makeRelative(el(ed, 10), el(ed, 12)))
        assertEquals("e12 is not a point to anchor e10 to", ed.doc.takeNote())
    }

    /** A **placed** path holds the group's own coordinates, and an anchor's are the world's (OP-16). */
    @Test
    fun aPlacedPathRefusesByName() {
        val ed =
            loaded(
                loop + "point 0,0 -> e13\n" +
                    "group \"g\" els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12\n" +
                    "place \"g\" at=-27.5,46.125 angle=0deg\n",
            )
        assertFalse(ed.doc.makeRelative(el(ed, 10), el(ed, 13)))
        assertEquals(
            "e10 belongs to a placed group: its coordinates are the group's own while an anchor's are the " +
                "world's — take the path out of the group first, then anchor it",
            ed.doc.takeNote(),
        )
    }

    /** A vertex that follows nothing has nothing to give back. */
    @Test
    fun freeingAVertexThatOwnsItsCoordinatesRefusesByName() {
        val ed = loaded()
        assertFalse(ed.doc.makeAbsolute(el(ed, 10)))
        assertEquals(
            "e10 is derived by the construction, not a point holding its own coordinates",
            ed.doc.takeNote(),
        )
    }

    // ---- the offset is a **named parameter**: the report's third ask (OP-7) ----

    /**
     * *"…or bind it to a named parameter."* The offset is a panel row, so everything the panel can do to a
     * parameter it can do to this: give it a formula, wire it to another row, read it from an expression.
     */
    @Test
    fun theOffsetIsAPanelRowAndCanBeDrivenByAnotherParameter() {
        val ed = loaded(loop + "param \"gap\" = 12mm\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)), "${ed.doc.note}")
        val dx = assertNotNull(ed.doc.scalars.firstOrNull { it.name == "dx" }, "the offset has a row")
        assertTrue(dx.editable)

        // one formula, and the closing leg is twice the gap for ever
        assertTrue(ed.bindParameter(dx, "gap * 2"))
        assertClose(closingLeg(ed), 24.0, 1e-9)
        assertTrue(ed.setParameter(ed.doc.scalars.first { it.name == "gap" }, 20.0))
        assertClose(closingLeg(ed), 40.0, 1e-9, "the leg follows the formula")

        // …and the coordinate is not this vertex's freedom any more, so the drag and the field refuse the
        // same way, because they are one operation (OP-13)
        val was = pos(el(ed, 10))
        ed.drag(was, was + Vec2(10.0, 0.0))
        assertEquals(was, pos(el(ed, 10)), "a driven coordinate does not move under the cursor")
        assertFalse(type(ed, el(ed, 10), "x", 0.0), "and refuses to be typed")
        assertRoundTrips(ed, "an offset driven by a formula")
    }

    /**
     * With **both** offsets driven the corner has no freedom left, and the press says so by name rather than
     * promising a motion that cannot happen (session 65).
     */
    @Test
    fun aCornerWhoseBothOffsetsAreDrivenRefusesTheDragByName() {
        val ed = loaded(loop + "point 0,0 -> e13\nparam \"gap\" = 12mm\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 13)), "${ed.doc.note}")
        assertTrue(ed.bindParameter(ed.doc.scalars.first { it.name == "dx" }, "gap * 2"))
        assertTrue(ed.bindParameter(ed.doc.scalars.first { it.name == "dy" }, "gap * 3"))

        val was = pos(el(ed, 10))
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(was))
        ed.pointerUp(ed.camera.worldToScreen(was + Vec2(10.0, 10.0)))
        assertTrue(ed.statusHint.contains("no free direction"), "got: ${ed.statusHint}")
        assertEquals(was, pos(el(ed, 10)))
    }

    /** A row something else reads may not vanish: *Make absolute* refuses by name (the `retract` rule). */
    @Test
    fun freeingAVertexWhoseOffsetDrivesSomethingRefusesByName() {
        val ed = loaded(loop + "param \"gap\" = 12mm\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        val gap = ed.doc.scalars.first { it.name == "gap" }
        assertTrue(ed.doc.wireParameter(gap, ed.doc.scalars.first { it.name == "dx" }))

        assertFalse(ed.doc.makeAbsolute(el(ed, 10)))
        assertEquals(
            "Can't free e10: its offset dx drives gap — free that first, then free e10",
            ed.doc.takeNote(),
        )
        // free the reader, and it lets go
        assertTrue(ed.doc.unwireParameter(gap))
        assertTrue(ed.doc.makeAbsolute(el(ed, 10)), "${ed.doc.note}")
        assertNull(ed.doc.scalars.firstOrNull { it.name == "dx" }, "and the row goes with the freedom")
    }

    /** *Make absolute* takes the row back with the freedom it stood for. */
    @Test
    fun freeingTheVertexTakesTheOffsetRowWithIt() {
        val ed = loaded()
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)))
        assertNotNull(ed.doc.scalars.firstOrNull { it.name == "dx" })
        assertTrue(ed.doc.makeAbsolute(el(ed, 10)))
        assertNull(ed.doc.scalars.firstOrNull { it.name == "dx" })
    }

    // ---- a placement over an anchored path says what it cannot carry (OP-16) ----

    /**
     * A coordinate anchored to a point **outside** the group is pinned in world coordinates exactly as a
     * welded end is, so the capture leaves it and the placement *reports* the vertices that will not follow
     * rather than silently deforming them.
     */
    @Test
    fun placingAGroupOverAnAnchoredPathReportsWhatItCannotCarry() {
        val ed = loaded(loop + "point 0,0 -> e13\n")
        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 13)), "${ed.doc.note}")
        // a marquee over the loop only — the anchor at (0,0) lies outside its box
        ed.setTool(Tools.SELECT)
        ed.pointerDown(ed.camera.worldToScreen(Vec2(-80.0, 20.0)))
        ed.pointerMove(ed.camera.worldToScreen(Vec2(20.0, 75.0)))
        ed.pointerUp(ed.camera.worldToScreen(Vec2(20.0, 75.0)))
        val g = assertNotNull(ed.groupSelection("g"), "got: ${ed.statusHint}")
        // a path is captured whole or not at all (OP-16), so an anchored coordinate refuses the placement —
        // and the refusal names the coordinate and the cure rather than "it owns no degree of freedom"
        assertFalse(ed.placeGroup(g))
        assertTrue(
            ed.statusHint.contains("e10 follows e13 along x and y, so its path is not carried whole — free it (Make absolute) to place the group"),
            "got: ${ed.statusHint}",
        )
    }

    // ---- and the solid downstream follows, because it is downstream (OP-5) ----

    /** The extruded body is watertight before and after, and the anchoring travels through it. */
    @Test
    fun theSolidCutFromTheAnchoredLoopStaysManifold() {
        val ed =
            loaded(
                loop + "param \"h\" = 20mm\n" +
                    "tool extrude els=e11 clicks=-48.125,37.875 scalar=\"h\" -> e13\n",
            )
        val solid = ed.doc.elements.first { it.kind == ElementKind.SOLID }
        assertManifold(volumeOf(ed, solid), "the extruded loop")

        assertTrue(ed.doc.makeRelative(el(ed, 10), el(ed, 1)), "${ed.doc.note}")
        ed.drag(Vec2(-68.625, 46.0), Vec2(-58.625, 46.0))
        assertClose(closingLeg(ed), 20.25, 1e-6)
        assertManifold(volumeOf(ed, solid), "the extruded loop after the anchored drag")
        assertRoundTrips(ed, "an anchored loop with a solid on it")
    }

    private fun volumeOf(
        ed: Editor,
        el: Element,
    ) = assertNotNull((Evaluator().valueOf(el.ref) as? SolidValue)?.solid?.mesh, "${ed.doc.nameOf(el)} is a solid")
}
