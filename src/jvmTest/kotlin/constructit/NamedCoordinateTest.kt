package constructit

import constructit.core.CircleValue
import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.scalar
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
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
 * **Named coordinates** — `r = P.x / 2` (the session-76 entry, item a; the scalar half's first cut, closed).
 *
 * The mechanism is the **naming authority extended to a coordinate** (OP-7/OP-18) and not one line of new
 * expression machinery: a dotted reference resolves through the same name lookup, reads the point's
 * coordinate as a length through an ordinary accessor node, and is **re-stamped** on rename like every other
 * mention of a name. What is asserted here:
 *
 * 1. a derived radius follows the point — dragging `P` recomputes the formula by plain recompute;
 * 2. a rename of the point re-stamps the stored text, **through the file**;
 * 3. the **precedence** rule: a dotted name is always a coordinate, and a row whose own name carries a dot
 *    stays unspellable and says so with the cure;
 * 4. a **cycle through geometry** is refused by name, over the same `dependsOn` walk a scalar cycle is;
 * 5. `save → load → save` is byte-equal with the user's own text in it.
 */
class NamedCoordinateTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.selectAt(world: Vec2) {
        setTool(Tools.SELECT)
        click(world)
    }

    private fun scalarNamed(
        ed: Editor,
        name: String,
    ): ScalarEntry = assertNotNull(ed.doc.scalars.firstOrNull { it.name == name }, "the panel has a scalar named '$name'")

    private fun radiusOf(el: Element): Double =
        assertNotNull(Evaluator().valueOf(el.ref) as? CircleValue, "the circle is built").circle.radius

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
        return once
    }

    /** A free point named `P` at (40, 15), plus a circle at the origin whose radius is the parameter `r`. */
    private fun pointAndCircle(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(40.0, 15.0))
        val p = ed.doc.elements.last { it.kind == ElementKind.POINT }
        assertEquals("P", ed.doc.nameElement(p, "P"), "the point carries a name of its own (OP-7)")
        ed.activeScalar = ed.doc.newParameter("r", 10.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        ed.checkpoint()
        return ed
    }

    private fun pointNamed(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.userNameOf(it) == name }, "a point named '$name'")

    // ---- 1. a coordinate drives geometry, and the point drags it ----

    @Test
    fun aRadiusReadsAPointsCoordinateAndFollowsTheDrag() {
        val ed = pointAndCircle()
        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        val r = scalarNamed(ed, "r")

        assertTrue(ed.doc.bindParameter(r, "P.x / 2"), "the coordinate is a value like any other: ${ed.doc.note}")
        assertClose(radiusOf(circle), 20.0, tol = 1e-9, msg = "40 / 2")

        // dragging the point recomputes the formula — one direction, an ordinary DAG edge
        ed.doc.moveFreePoint(pointNamed(ed, "P"), Vec2(90.0, 15.0))
        assertClose(radiusOf(circle), 45.0, tol = 1e-9, msg = "90 / 2 — the circle followed P")
        assertEquals("P.x / 2", ed.doc.expressionOf(r), "and the drawing says what it is")

        // ...and `.y` is the other coordinate, read the same way
        assertTrue(ed.doc.bindParameter(r, "P.y + 1mm"))
        assertClose(radiusOf(circle), 16.0, tol = 1e-9, msg = "15 + 1")
    }

    /** Nothing writes a coordinate: what is derived refuses the typed number in the formula's own words. */
    @Test
    fun aDerivedRadiusStillRefusesATypedNumberAndNamesTheFormula() {
        val ed = pointAndCircle()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "P.x / 2"))
        assertFalse(ed.setParameter(r, 5.0), "r is derived")
        assertTrue(ed.statusHint.contains("P.x / 2"), "and the refusal quotes the formula: ${ed.statusHint}")
    }

    // ---- 2. a rename re-stamps, through the file ----

    @Test
    fun renamingThePointRestampsTheFormulaAndTheFile() {
        val ed = pointAndCircle()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "P.x / 2 + 1mm"))

        assertEquals("hinge", ed.doc.nameElement(pointNamed(ed, "P"), "hinge"), "the point is renamed")
        assertEquals("hinge.x / 2 + 1mm", ed.doc.expressionOf(r), "the formula reads the new name, spacing intact")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 21.0, tol = 1e-9, msg = "nothing moved")

        val text = roundTrips(ed, "the re-stamped file round-trips byte for byte")
        assertTrue(text.contains("bind \"r\" = \"hinge.x / 2 + 1mm\""), "the file says it too:\n$text")
        assertFalse(text.contains("\"P.x"), "and the old name is nowhere in it")
    }

    /**
     * A name an expression **spells** cannot be taken away: clearing it would leave the formula live and the
     * *file* unloadable — the scalar half's own probe lesson. Refused by name, with the cure.
     */
    @Test
    fun clearingThePointsNameIsRefusedWhileAFormulaSpellsIt() {
        val ed = pointAndCircle()
        assertTrue(ed.doc.bindParameter(scalarNamed(ed, "r"), "P.x / 2"))
        val p = pointNamed(ed, "P")
        assertNull(ed.doc.nameElement(p, ""), "the name cannot go")
        val why = assertNotNull(ed.doc.note, "and it says why")
        assertTrue(why.contains("r"), "naming what reads it: $why")
        assertEquals("P", ed.doc.userNameOf(p), "the name is untouched")
        roundTrips(ed, "so the file is still the file")
    }

    // ---- 3. the precedence rule, and the refusals ----

    /**
     * **The collision case, and the rule.** A row *may* be called `wall.x` (only spaces and quotes are
     * normalised out of a name), and a point *may* be called `wall`. A dotted name is then ambiguous only if
     * the drawing is allowed to decide it — which is exactly what is refused here: the dot always reads a
     * coordinate, so the text means the same thing in every drawing, and the dotted row stays unspellable
     * exactly as a hyphenated one is (with the cure). It costs nothing stored: a dot has never parsed.
     */
    @Test
    fun aDottedNameIsAlwaysACoordinateAndADottedRowSaysSo() {
        val ed = pointAndCircle()
        val r = scalarNamed(ed, "r")
        val shadow = ed.doc.newParameter("wall.x", 3.0.mm)
        assertEquals("wall.x", shadow.name, "a dot survives the name normalisation (only spaces and quotes do not)")
        assertEquals("wall", ed.doc.nameElement(pointNamed(ed, "P"), "wall"), "and a point may be called 'wall'")

        // the coordinate wins — the reading is a property of the text, never of what the drawing carries
        assertTrue(ed.doc.bindParameter(r, "wall.x / 2"), "read as the point's x: ${ed.doc.note}")
        assertClose(radiusOf(ed.doc.elements.single { it.kind == ElementKind.CIRCLE }), 20.0, tol = 1e-9, msg = "40 / 2, not 3 / 2")

        // ...and the row of that name is named as unspellable, with the cure, when nothing else can be meant
        val ed2 = Editor()
        val t = ed2.doc.newParameter("t", 10.0.mm)
        ed2.doc.newParameter("wall.x", 3.0.mm)
        assertFalse(ed2.doc.bindParameter(t, "wall.x / 2"), "no point is called 'wall'")
        val why = assertNotNull(ed2.doc.note)
        assertTrue(why.contains("wall.x") && why.contains("rename"), "naming it and the cure: $why")
    }

    /** The other two ways a dotted name misses: no such point, and no such coordinate. */
    @Test
    fun aCoordinateThatIsNotThereIsRefusedByName() {
        val ed = pointAndCircle()
        val r = scalarNamed(ed, "r")

        assertFalse(ed.doc.bindParameter(r, "Q.x"), "nothing is named Q")
        assertTrue(assertNotNull(ed.doc.note).contains("no point named 'Q'"), "by name: ${ed.doc.note}")

        assertFalse(ed.doc.bindParameter(r, "P.z"), "a point of the plane has no z")
        val why = assertNotNull(ed.doc.note)
        assertTrue(why.contains(".x") && why.contains(".y"), "naming what there is: $why")

        // a *line* has no coordinate to read, and the refusal says what it is instead
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(10.0, 10.0))
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        assertEquals("edge", ed.doc.nameElement(seg, "edge"))
        assertFalse(ed.doc.bindParameter(r, "edge.x"), "a segment is not a point")
        assertTrue(assertNotNull(ed.doc.note).contains("edge"), "naming it: ${ed.doc.note}")

        assertNull(ed.doc.expressionOf(r), "and nothing was rewired by any of them")
    }

    /**
     * A point **in space** refuses by name, in the sentence every route that reads plane coordinates speaks
     * (OP-17): its coordinates are in *world* space while a `Vec2` here means "in some plane's own", so
     * answering `.x` would mix two frames silently. A `.z` with a stated space is the future extension.
     */
    @Test
    fun aPointInSpaceRefusesTheCoordinateByName() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        for (c in "90") ed.key(c.toString())
        ed.key("Enter")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        val apex = ed.doc.elements.single { it.kind == ElementKind.HEIGHT_POINT }
        assertEquals("apex", ed.doc.nameElement(apex, "apex"), "it carries a name like anything else")

        val t = ed.doc.newParameter("t", 10.0.mm)
        assertFalse(ed.doc.bindParameter(t, "apex.x / 2"), "but not a plane coordinate")
        val why = assertNotNull(ed.doc.note)
        assertTrue(why.contains("point in space"), "saying what it is: $why")
    }

    // ---- 4. a cycle through geometry ----

    /**
     * **The cycle the coordinate makes reachable**: a rider on a circle of radius `r` has a position that is
     * a function of `r`, so `r = P.x / 2` would close a loop through *geometry*. It is refused by name at
     * bind time, over the very `dependsOn` walk a scalar cycle is refused with — no new rule.
     */
    @Test
    fun aCoordinateOfAPointTheValuePlacesIsRefusedAsACycle() {
        val ed = Editor()
        ed.activeScalar = ed.doc.newParameter("r", 30.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(0.0, 0.0))
        val circle = ed.doc.elements.single { it.kind == ElementKind.CIRCLE }
        ed.setTool(Tools.POINT_ON_CIRCLE)
        ed.click(Vec2(30.0, 0.0))
        val rider = ed.doc.elements.last { it.kind == ElementKind.ON_CURVE }
        assertEquals("P", ed.doc.nameElement(rider, "P"), "the rider carries a name")
        assertTrue(Evaluator().valueOf(rider.ref) != null, "and it rides the circle")

        val r = scalarNamed(ed, "r")
        assertFalse(ed.doc.bindParameter(r, "P.x / 2"), "P is placed by r, so r may not follow P")
        val why = assertNotNull(ed.doc.note, "the refusal speaks")
        assertTrue(why.contains("P.x") && why.contains("r"), "naming both ends: $why")
        assertNull(ed.doc.expressionOf(r), "and nothing was rewired")
        assertClose(Evaluator().scalar(r.ref).mm, 30.0, tol = 1e-9, msg = "r is what it was")
        assertEquals(30.0, (Evaluator().valueOf(circle.ref) as CircleValue).circle.radius, "the circle is untouched")
    }

    // ---- 5. the file, and the delete cascade ----

    /** Deleting the point takes the formula that spells it with it — a `bind` step naming nothing cannot load. */
    @Test
    fun deletingThePointTakesTheFormulaWithIt() {
        val ed = pointAndCircle()
        val r = scalarNamed(ed, "r")
        assertTrue(ed.doc.bindParameter(r, "P.x / 2"))
        assertEquals(1, ed.doc.journal.count { it.kind == "bind" }, "one bind step")

        ed.selectAt(Vec2(40.0, 15.0))
        assertEquals(ElementKind.POINT, ed.selection?.kind, "the point is selected")
        assertTrue(ed.deleteSelection(), ed.statusHint)
        assertEquals(0, ed.doc.journal.count { it.kind == "bind" }, "and it went with the point it read")
        assertTrue((Evaluator().eval(r.ref.node) as? EvalResult.Ok) != null, "r is an ordinary number again")
        roundTrips(ed, "and the file still loads")
    }

    /** A function curve reads a coordinate too — one resolution, both consumers. */
    @Test
    fun aFunctionCurveReadsACoordinateAndSurvivesTheFile() {
        val ed = pointAndCircle()
        val curve = assertNotNull(ed.addFunctionCurve("P.x * cos(t)", "P.x * sin(t)", 0.0, 1.0), ed.statusHint)
        assertEquals(ElementKind.FUNC_CURVE, curve.kind)
        assertNotNull(Evaluator().valueOf(curve.ref), "it is built")

        assertEquals("axle", ed.doc.nameElement(pointNamed(ed, "P"), "axle"), "renamed")
        val text = roundTrips(ed, "the curve's texts round-trip")
        assertTrue(text.contains("\"axle.x * cos(t)\""), "re-stamped in the file:\n$text")
    }
}
