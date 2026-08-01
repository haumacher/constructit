package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A plane's input geometry is every solid built before it** (GitHub #9, the user's design: *"a plane should
 * use all intersections with ancestor solids (created 'before' themselves) as input geometry"*), and the tool
 * that follows from it: *Plane at height* — a parallel plane that needs **no pick at all**, only a number.
 *
 * What these tests hold to:
 *
 * - the plane is created without a single solid being clicked, and both solids' sections are immediately
 *   pickable inputs (`Document.spaceAncestors` is the one enumeration behind context, picks and origins);
 * - a solid built **after** the plane never appears in it — the recorded-not-discovered rule, and what makes
 *   the plane → sketch → solid → cut chain acyclic by construction;
 * - a pick still *records* which solid it took its member from (`sectioninput … el=`), so replay
 *   re-discovers nothing and the whole chain saves byte-equal;
 * - the hinged plane (*Sketch plane*, line + angle) gains the same context, which is what makes it useful on
 *   a line that belongs to no solid at all;
 * - a face space keeps its **face** as input geometry (the user's stated exception) *and* shows what else
 *   crosses that plane.
 */
class PlaneAncestorTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun solidOf(el: Element) = Evaluator().solid(el.ref as SolidRef)

    private fun roundTrips(ed: Editor): Document {
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal:\n$once")
        return back
    }

    /** A box from (x0, y0) to (x1, y1), [h] tall, by clicking — the ordinary two-gesture prism. */
    private fun Editor.box(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        h: String,
    ): Element {
        setTool(Tools.RECTANGLE)
        click(Vec2(x0, y0))
        click(Vec2(x1, y1))
        setTool(Tools.EXTRUDE)
        type(h)
        click(Vec2((x0 + x1) / 2, y0))
        return solids().last()
    }

    /** Two 40 × 40 towers 100 tall, side by side, with a 20 mm gap between them. */
    private fun twoTowers(): Editor {
        val ed = Editor()
        ed.box(0.0, 0.0, 40.0, 40.0, "100")
        ed.box(60.0, 0.0, 100.0, 40.0, "100")
        return ed
    }

    // ---- 1. the headline: a plane at a height, and both solids are its inputs ----

    /**
     * *Plane at height* with two towers standing: type 50, click once, and **both** sections are there to
     * click — no solid was ever picked, and the step records none.
     */
    @Test
    fun aPlaneAtAHeightTakesEveryAncestorSolidAsItsInputGeometry() {
        val ed = twoTowers()
        val (a, b) = ed.solids()

        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("50")
        // a click that only says "now" — deliberately in empty space, far from either tower
        ed.click(Vec2(200.0, 200.0))

        val space = ed.doc.activeSpace
        assertTrue(space.parallel, "a plane at a height is its own kind of space: ${ed.statusHint}")
        assertTrue(space.isDatum, "...and a datum in every other respect")
        assertFalse(space.isFace, "...but not a face of anything")
        assertEquals(50.0, ed.doc.spaceOffsetMm(space), "the height it was given")

        val sections = ed.doc.spaceSections(space, Evaluator())
        assertEquals(2, sections.size, "both towers are cut: ${sections.map { ed.doc.nameOf(it.first) }}")
        assertEquals(setOf(a, b), sections.map { it.first }.toSet(), "and they are the two that exist")
        assertTrue(ed.statusHint.contains("cuts 2 solids"), "the note says what is on offer: ${ed.statusHint}")

        // the sections are where the towers are, in the plane's own (u, v) — the same axes as the plan
        val cornersA = sections.first { it.first === a }.second.corners.mapNotNull { it.at }
        val cornersB = sections.first { it.first === b }.second.corners.mapNotNull { it.at }
        assertEquals(4, cornersA.size, "a square section: $cornersA")
        assertEquals(4, cornersB.size, "a square section: $cornersB")
        assertTrue(cornersA.any { (it - Vec2(0.0, 0.0)).length() < 1e-6 }, "the first tower's corner: $cornersA")
        assertTrue(cornersB.any { (it - Vec2(60.0, 0.0)).length() < 1e-6 }, "the second tower's corner: $cornersB")

        // no solid was clicked, and none is named as an input in the step — only the part a Cut would take
        val script = DocumentFormat.save(ed.doc)
        val step = script.lines().first { it.startsWith("sketchspace") }
        assertFalse(step.contains("line="), "no hinge line: $step")
        assertFalse(step.contains(" el="), "and no picked face: $step")
        assertTrue(step.contains("offset="), "its whole description is a height: $step")
    }

    /**
     * The chain the user asked for: corners of **both** sections materialize as inputs, a drill goes through
     * one of the towers, and the whole thing replays byte-equal — every pick recorded by the solid it came
     * from.
     */
    @Test
    fun bothSectionsMaterializeAsInputsAndTheChainReplaysExactly() {
        val ed = twoTowers()
        val (a, b) = ed.solids()
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("50")
        ed.click(Vec2(200.0, 200.0))
        val space = ed.doc.activeSpace

        // a segment spanning the gap: one endpoint on the first tower's section corner, one on the second's
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(40.0, 0.0))
        ed.click(Vec2(60.0, 0.0))
        val taken = ed.doc.elements.filter { it.kind == ElementKind.DERIVED_POINT }
        assertEquals(2, taken.size, "two section corners were materialized: ${ed.statusHint}")

        val script0 = DocumentFormat.save(ed.doc)
        val inputs = script0.lines().filter { it.startsWith("sectioninput") }
        assertEquals(2, inputs.size, "each pick recorded its own step:\n$script0")
        assertTrue(
            inputs.count { it.contains(" el=") } == 1,
            "the one that is not the space's own part names its solid — the other is the anchor:\n$inputs",
        )

        // ...and a drill into the part this plane resolved at creation (the newest solid it passes through)
        assertEquals(b, space.anchor, "the newest solid the plane cuts is the part a Cut here takes")
        ed.setTool(Tools.CIRCLE_R)
        ed.type("5")
        ed.click(Vec2(80.0, 20.0))
        ed.setTool(Tools.CUT)
        ed.type("60")
        // on the circle's outline, not its centre: the centre point would win the pick
        ed.click(Vec2(85.0, 20.0))
        val drilled = ed.solids().last()
        assertTrue(drilled !== a && drilled !== b, "the cut made a new solid: ${ed.statusHint}")
        assertManifold(solidOf(drilled).mesh, "the drilled tower")
        // 40 x 40 x 100 less a r=5 bore 50 deep (the plane is at 50, the cut runs 60 down and leaves the part)
        val ideal = 40.0 * 40.0 * 100.0 - kotlin.math.PI * 25.0 * 50.0
        val volume = Geom3.volume(solidOf(drilled).mesh)
        assertTrue(volume in (ideal - 1.0)..(ideal + 60.0), "a through-bore of the lower half: $volume vs $ideal")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the whole chain replays byte-equal:\n$once")

        // and the reloaded document has the same plane with the same two ancestors
        val back = DocumentFormat.load(once)
        val space2 = assertNotNull(back.spaceNamed(space.name), "the plane came back")
        assertEquals(2, back.spaceSections(space2, Evaluator()).size, "with both its sections")
    }

    /**
     * **Ancestors only.** A tower built *after* the plane never becomes its input geometry, however the model
     * is edited afterwards — which is not a limitation but the acyclicity argument: a plane can only ever
     * depend on solids whose own inputs were fixed before its plane node existed, so plane → sketch → solid →
     * cut chains cannot close a loop.
     */
    @Test
    fun aSolidBuiltAfterThePlaneNeverBecomesItsContext() {
        val ed = twoTowers()
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("50")
        ed.click(Vec2(200.0, 200.0))
        val space = ed.doc.activeSpace
        assertEquals(2, ed.doc.spaceSections(space, Evaluator()).size, "two to start with")

        // a third tower, drawn in the plan afterwards, straight through this plane's height
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        val c = ed.box(120.0, 0.0, 160.0, 40.0, "100")
        assertEquals(3, ed.solids().size, "it was built: ${ed.statusHint}")

        assertTrue(ed.setActiveSpace(space.name))
        val after = ed.doc.spaceSections(space, Evaluator())
        assertEquals(2, after.size, "the plane keeps the two it was born with: ${after.map { ed.doc.nameOf(it.first) }}")
        assertFalse(after.any { it.first === c }, "the newcomer is not retro-fitted into an older plane")
        assertFalse(
            ed.doc.spaceAncestors(space).any { it === c },
            "and it is not an ancestor either — that is what keeps the graph acyclic",
        )
    }

    /**
     * A solid **consumed** among the ancestors draws no section of its own: the same output-not-material rule
     * the 3D view uses, so a fused pair is one section and not three coincident ones.
     */
    @Test
    fun anOperandConsumedAmongTheAncestorsIsNotDrawnTwice() {
        val ed = twoTowers()
        val (a, b) = ed.solids()
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 0.0))
        ed.click(Vec2(80.0, 0.0))
        assertEquals(3, ed.solids().size, "the union exists: ${ed.statusHint}")

        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("50")
        ed.click(Vec2(200.0, 200.0))
        val sections = ed.doc.spaceSections(ed.doc.activeSpace, Evaluator())
        assertEquals(1, sections.size, "only the output: ${sections.map { ed.doc.nameOf(it.first) }}")
        assertFalse(sections.any { it.first === a || it.first === b }, "its operands are material, not outputs")
    }

    /**
     * The **origin** follows the same enumeration: a corner of *any* section on the plane can anchor it, not
     * only the space's own part — and the step records which solid it indexes into (`spaceorigin … el=`), so
     * a replay never re-scores it.
     */
    @Test
    fun theOriginCanBeAnchoredOnAnyAncestorsCorner() {
        val ed = twoTowers()
        val (a, b) = ed.solids()
        ed.setTool(Tools.PLANE_AT_HEIGHT)
        ed.type("50")
        ed.click(Vec2(200.0, 200.0))
        val space = ed.doc.activeSpace
        assertEquals(b, space.anchor, "the part is the newer tower")

        // anchor the origin on a corner of the *other* tower — the one this plane is not a part of
        ed.setTool(Tools.SPACE_ORIGIN)
        ed.click(Vec2(40.0, 40.0))
        assertEquals(a, space.originSolid, "the corner came from the first tower: ${ed.statusHint}")
        assertNotNull(space.originCorner, "and it is a corner index, never a position")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(a)), "the note names whose corner: ${ed.statusHint}")

        // the drawing's coordinates now start there: that corner is the origin
        val moved = ed.doc.spaceSections(space, Evaluator()).first { it.first === a }.second.corners.mapNotNull { it.at }
        assertTrue(moved.any { it.length() < 1e-6 }, "the anchored corner sits at (0, 0): $moved")

        // the gesture records the corner it materialized (`sectioninput … el=e9`) and then the tool step that
        // consumed it — so which solid the index belongs to is in the file, and replay re-scores nothing
        val script = DocumentFormat.save(ed.doc)
        val input = script.lines().first { it.startsWith("sectioninput") }
        assertTrue(input.contains(" el="), "the corner names the solid it is a corner of: $input")
        val back = roundTrips(ed)
        val space2 = assertNotNull(back.spaceNamed(space.name), "the plane came back")
        assertEquals(space.originCorner, space2.originCorner, "on the same corner")
        assertEquals(back.nameOf(a), space2.originSolid?.let { back.nameOf(it) }, "of the same solid")
    }

    // ---- 2. the hinged plane inherits the same context ----

    /**
     * *Sketch plane* (line + angle) on a line that belongs to **no solid at all** — which used to mean a
     * plane with no input geometry whatever. It now carries the section of everything it cuts, which is the
     * user's *"this makes the plane-from-line-and-angle useful"*.
     */
    @Test
    fun aHingedPlaneOnAFreeLineStillCarriesTheSectionsItCuts() {
        val ed = Editor()
        val tower = ed.box(0.0, 0.0, 40.0, 40.0, "100")

        // a free segment across the tower, part of no solid's construction
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(20.0, -30.0))
        ed.click(Vec2(20.0, 70.0))

        ed.setTool(Tools.SKETCH_PLANE)
        ed.click(Vec2(20.0, 50.0))
        val space = ed.doc.activeSpace
        assertTrue(space.isDatum, "a datum plane: ${ed.statusHint}")
        assertEquals(null, space.anchor, "its hinge belongs to no solid, so it names no part")

        val sections = ed.doc.spaceSections(space, Evaluator())
        assertEquals(1, sections.size, "and yet it has context: the tower it cuts")
        assertEquals(tower, sections.single().first, "which is the tower")
        val corners = sections.single().second.corners.mapNotNull { it.at }
        assertEquals(4, corners.size, "a 40 x 100 rectangle: $corners")
        assertTrue(ed.statusHint.contains("curve"), "the note offers them: ${ed.statusHint}")

        // and one of its edges is a real input, recorded by the solid it came from
        ed.setTool(Tools.POINT_ON_LINE)
        ed.click(Vec2(0.0, 50.0))
        val script = DocumentFormat.save(ed.doc)
        val input = script.lines().firstOrNull { it.startsWith("sectioninput") }
        assertNotNull(input, "the edge was materialized as an input:\n$script")
        assertTrue(input.contains(" el="), "and it names the solid, the plane having no anchor: $input")
        roundTrips(ed)
    }

    // ---- 3. the face exception ----

    /**
     * A face space keeps **the face itself** as input geometry (not an intersection — the user's stated
     * exception) *and* shows an ancestor that crosses the same plane.
     */
    @Test
    fun aFaceSpaceKeepsItsFaceAndGainsWhateverElseCrossesIt() {
        val ed = Editor()
        // the crossing solid first, so it is an ancestor of the face space; it straddles y = 0 well clear
        // of where the face edge is picked
        val crossing = ed.box(60.0, -20.0, 80.0, 20.0, "30")
        val plate = ed.box(0.0, 0.0, 40.0, 40.0, "100")

        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(20.0, 0.0))
        val space = ed.doc.activeSpace
        assertTrue(space.isFace, "a face space: ${ed.statusHint}")
        assertEquals(plate, space.anchor, "of the plate")

        val sections = ed.doc.spaceSections(space, Evaluator())
        assertEquals(2, sections.size, "the face, and what else the plane cuts: ${sections.map { ed.doc.nameOf(it.first) }}")
        assertEquals(plate, sections.first().first, "the face itself comes first — it is what this space *is*")

        // the face is still exactly the face: 40 wide about its own middle, 100 tall
        val face = assertNotNull(ed.doc.faceOutline(space, Evaluator()), "the face outline is unchanged")
        assertEquals(4, face.size, "a rectangle: $face")
        assertTrue(face.any { (it - Vec2(-20.0, 0.0)).length() < 1e-6 }, "u about the picked edge's middle: $face")
        assertTrue(face.any { (it - Vec2(20.0, 100.0)).length() < 1e-6 }, "and the full height: $face")

        // ...and the crossing solid's section is beside it, at u = 60..80 in the face's own coordinates
        val other = sections.first { it.first === crossing }.second.corners.mapNotNull { it.at }
        assertTrue(other.any { kotlin.math.abs(it.x - 40.0) < 1e-6 }, "the crossing body sits along +u: $other")
    }

    // ---- 4. the silent success that started the issue ----

    /** *Section* now says what it made, of what, at what height, and where it lies (OP-3's speaking rule). */
    @Test
    fun theSectionToolSpeaksWhenItSucceeds() {
        val ed = Editor()
        val tower = ed.box(0.0, 0.0, 40.0, 40.0, "100")
        ed.setTool(Tools.SECTION)
        ed.type("40")
        ed.click(Vec2(20.0, 0.0))

        val area = ed.doc.elements.last { it.kind == ElementKind.AREA }
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(area)), "it names what it made: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains(ed.doc.nameOf(tower)), "and what of: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("40"), "and at what height: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("the plan"), "and where it lies: ${ed.statusHint}")
    }
}
