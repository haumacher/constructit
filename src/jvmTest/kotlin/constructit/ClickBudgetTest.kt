package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.ScalarEntry
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Usability measured as a number: what a whole workflow costs in user actions.**
 *
 * Four end-to-end workflows are scripted through the ordinary gesture surface and *counted*. The point
 * is not the geometry (other suites assert that) but the **budget**: a ceiling per workflow that fails
 * loudly if an interaction regresses, and drops when one improves.
 *
 * The unit is a **user action**, counted by [Budget]:
 *
 * | action | count | why |
 * |---|---|---|
 * | a click (press + release at one place) | 1 | |
 * | a drag (press, move, release) | 1 | |
 * | a keyboard entry (a typed number + Enter, a shortcut key, Esc) | 1 | |
 * | a tool switch | 1 | a palette click or a shortcut key — the same cost either way |
 * | picking a parameter row in the panel | 1 | |
 * | creating a parameter in the panel | 3 | the name field, the value field, the Add button |
 *
 * The one weight worth defending is the last: a panel parameter creation is **three** interactions, not
 * one, and weighting it 1 would hide exactly the friction the typed-scalar mechanism removes.
 */
class ClickBudgetTest {
    /**
     * A counting wrapper over the editor: every method is one user action, so a workflow's cost is
     * whatever the script did, never a hand count.
     */
    private class Budget(val name: String) {
        val ed = Editor()
        var clicks = 0
        var drags = 0
        var keys = 0
        var picks = 0
        var switches = 0
        var params = 0

        val actions: Int get() = clicks + drags + keys + picks + switches + params * PARAM_ACTIONS

        fun click(world: Vec2) {
            clicks++
            val s = ed.camera.worldToScreen(world)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }

        fun drag(
            from: Vec2,
            to: Vec2,
        ) {
            drags++
            ed.pointerDown(ed.camera.worldToScreen(from))
            ed.pointerMove(ed.camera.worldToScreen(to))
            ed.pointerUp(ed.camera.worldToScreen(to))
        }

        /**
         * A tool switch — one action either way, taken through the tool's **shortcut** when it has one
         * (which is also what keeps the key table honest: these workflows drive it).
         */
        fun tool(id: String) {
            switches++
            val key = Tools.shortcutOf(id)
            if (key != null) assertTrue(ed.key(key.toString()), "the shortcut $key should arm $id") else ed.setTool(id)
            assertEquals(id, ed.toolId, "the tool should be armed")
        }

        /** One keyboard entry: the characters of a value, then Enter. */
        fun type(value: String) {
            keys++
            value.forEach { assertTrue(ed.key(it.toString()), "the editor should take the digit '$it' of '$value'") }
            assertTrue(ed.key("Enter"), "the editor should take Enter after '$value'")
        }

        /** One keyboard entry that is a single key (Enter to finish, Esc to cancel). */
        fun press(key: String) {
            keys++
            ed.key(key)
        }

        /** Creating a parameter in the panel: name, value, Add — three interactions. */
        fun param(
            name: String,
            value: Quantity,
        ): ScalarEntry {
            params++
            val e = ed.doc.newParameter(name, value)
            ed.activeScalar = e
            ed.checkpoint()
            return e
        }

        /** Clicking a parameter row in the panel, which is how a tool's scalar slot is filled. */
        fun pick(name: String) {
            picks++
            ed.activeScalar = ed.doc.scalars.first { it.name == name }
        }

        /** Typing a new value into a parameter row. */
        fun set(
            name: String,
            value: Quantity,
        ) {
            keys++
            ed.doc.setParameter(ed.doc.scalars.first { it.name == name }, value)
            ed.checkpoint()
        }

        /** The structural count field (a polygon's sides, an array's copies) — one typed field. */
        fun count(n: Int) {
            keys++
            ed.count = n
        }

        fun solids(): List<Element> = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

        /** The measured row, printed so a run of this suite *is* the budget table. */
        fun report(ceiling: Int) {
            println(
                "click-budget $name: ${actions.toString().padStart(3)} actions " +
                    "(clicks $clicks, drags $drags, keys $keys, tool switches $switches, " +
                    "panel picks $picks, panel parameters $params) — ceiling $ceiling",
            )
            assertTrue(
                actions <= ceiling,
                "$name cost $actions user actions, over its ceiling of $ceiling — " +
                    "an interaction regressed, or the ceiling needs re-agreeing",
            )
        }

        companion object {
            const val PARAM_ACTIONS = 3
        }
    }

    // ---- W1 mechanical: a rounded-rect plate, a bolt circle, extruded, with a counterbore subtracted ----

    /**
     * The plate: 120 x 80, corner radius 8, 10 thick; four M8-ish holes on a 60 pitch circle; a
     * counterbore concentric with one of them, subtracted 4 deep.
     */
    @Test
    fun w1MechanicalPlate() {
        val b = Budget("W1 mechanical")

        // the plate: a rounded rectangle whose corner radius is typed into the flow
        b.tool(Tools.ROUNDED_RECT)
        b.type("8")
        b.click(Vec2(-60.0, -40.0))
        b.click(Vec2(60.0, 40.0))

        // the plate solid — the rounded rectangle already bounds an area, so no boundary tracing
        b.tool(Tools.EXTRUDE)
        b.type("10")
        b.click(Vec2(0.0, 40.0)) // any piece of the plate's boundary

        // the bolt circle: one hole, then three more round the centre
        b.tool(Tools.CIRCLE_R)
        b.type("4")
        b.click(Vec2(30.0, 0.0))
        b.count(4)
        b.tool(Tools.ARRAY_CIRCULAR)
        b.click(Vec2(34.0, 0.0)) // the hole, by its boundary
        b.click(Vec2(0.0, 0.0)) // the pattern centre

        // the counterbore: concentric with the first hole *by construction* — the click snaps onto its centre
        b.tool(Tools.CIRCLE_R)
        b.type("8")
        b.click(Vec2(30.0, 0.0))

        // ...and the pocket it cuts: a circle is a boundary by itself
        b.tool(Tools.EXTRUDE)
        b.type("4")
        b.click(Vec2(38.0, 0.0)) // the counterbore circle, by its boundary
        b.tool(Tools.SUBTRACT)
        b.click(Vec2(0.0, 40.0)) // the plate solid, by its footprint
        b.click(Vec2(38.0, 0.0)) // the counterbore cylinder

        val result = b.solids().last()
        val mesh = b.meshOf(result)
        assertManifold(mesh, "plate with a counterbore")
        val plate = 120.0 * 80.0 - (4 - kotlin.math.PI) * 8.0 * 8.0
        assertClose(Geom3.volume(mesh), plate * 10.0 - kotlin.math.PI * 64.0 * 4.0, tol = 20.0)
        assertEquals(4, b.ed.doc.elements.count { it.kind == ElementKind.CIRCLE } - 1, "four bolt holes")

        b.report(ceiling = 28)
    }

    // ---- W2 architect: a closed wall ring with a door and a window, extruded, its openings cut ----

    @Test
    fun w2ArchitectStorey() {
        val b = Budget("W2 architect")
        val onBand = Vec2(-150.0, 2000.0)

        b.tool(Tools.WALL)
        b.type("300") // the thickness, straight into the flow
        b.click(Vec2(0.0, 0.0))
        b.click(Vec2(6000.0, 20.0))
        b.click(Vec2(5980.0, 4000.0))
        b.click(Vec2(20.0, 4000.0))
        b.click(Vec2(0.0, 0.0)) // back on the start: the ring closes and the footprint is built

        b.tool(Tools.EXTRUDE)
        b.type("3000")
        b.click(onBand)

        b.tool(Tools.OPENING)
        b.type("900")
        b.click(Vec2(2000.0, 0.0))

        // the tool stays armed, so the window is a new width and one click
        b.type("1200")
        b.click(Vec2(4000.0, 0.0))
        b.set(b.ed.doc.scalars.last { it.name.startsWith("sill") }.name, Quantity.mm(900.0))

        b.tool(Tools.CUT_OPENINGS)
        b.click(onBand)

        val storey = b.solids().last()
        val mesh = b.meshOf(storey)
        assertManifold(mesh, "storey with openings")
        val band = 2.0 * 300.0 * (6000.0 + 4000.0)
        val door = 900.0 * 300.0 * 2100.0
        val window = 1200.0 * 300.0 * (2100.0 - 900.0)
        assertClose(Geom3.volume(mesh), band * 3000.0 - door - window, tol = 1e-6)

        b.report(ceiling = 22)
    }

    // ---- W3 macro: record a five-element construction as a tool, then stamp it three times ----

    @Test
    fun w3RecordAMacroAndStampIt() {
        val b = Budget("W3 macro")

        // the definition: two points, the segment between them, its midpoint, a circle of r there
        b.tool(Tools.POINT)
        b.click(Vec2(0.0, 0.0))
        b.click(Vec2(40.0, 0.0))
        b.tool(Tools.SEGMENT)
        b.click(Vec2(0.0, 0.0))
        b.click(Vec2(40.0, 0.0))
        b.tool(Tools.MIDPOINT)
        b.click(Vec2(0.0, 0.0))
        b.click(Vec2(40.0, 0.0))
        b.tool(Tools.CIRCLE_R)
        b.type("5")
        b.click(Vec2(20.0, 0.0))

        // declare it a tool: select everything, open the shared create dialog, name it, confirm
        b.tool(Tools.SELECT)
        b.drag(Vec2(-30.0, -30.0), Vec2(70.0, 30.0))
        b.clicks++ // the "Make tool" button
        val dialog = requireNotNull(b.ed.beginCreate(constructit.editor.CreateMode.TOOL))
        b.keys++ // the name field
        dialog.name = "bracket"
        b.clicks++ // Create
        assertTrue(b.ed.confirmCreate(), "the tool is declared")
        assertEquals(5, b.ed.doc.elements.count { it.id.startsWith("e") } - 0, "five elements are the definition")

        // three instances — the tool is armed by declaring it, so a stamp is just its slots
        for (y in listOf(60.0, 100.0, 140.0)) {
            b.click(Vec2(0.0, y))
            b.click(Vec2(40.0, y))
        }
        assertEquals(3, b.ed.doc.instancesOf(b.ed.doc.macros.single()).size, "three instances")

        b.report(ceiling = 27)
    }

    // ---- W4 drawing: a bracket outline plus three dimensions, ready to print ----

    @Test
    fun w4DimensionedDrawing() {
        val b = Budget("W4 drawing")

        b.tool(Tools.ROUNDED_RECT)
        b.type("8")
        b.click(Vec2(-60.0, -40.0))
        b.click(Vec2(60.0, 40.0))

        b.tool(Tools.OUTLINE)
        b.click(Vec2(0.0, 40.0))
        b.click(Vec2(57.66, 37.66))
        b.click(Vec2(60.0, 0.0))
        b.click(Vec2(57.66, -37.66))
        b.click(Vec2(0.0, -40.0))
        b.click(Vec2(-57.66, -37.66))
        b.click(Vec2(-60.0, 0.0))
        b.click(Vec2(-57.66, 37.66))
        b.click(Vec2(0.0, 40.0))

        // a bore, and a brace at an angle to the base
        b.tool(Tools.CIRCLE_R)
        b.type("10")
        b.click(Vec2(-20.0, 0.0))
        b.tool(Tools.SEGMENT)
        b.click(Vec2(10.0, -20.0))
        b.click(Vec2(50.0, 20.0))

        // the three dimensions
        b.tool(Tools.DIM_LINEAR)
        b.click(Vec2(-60.0, -40.0)) // the plate's two driving corners
        b.click(Vec2(60.0, 40.0))
        b.click(Vec2(0.0, -60.0)) // where the dimension line sits
        b.tool(Tools.DIM_RADIAL)
        b.click(Vec2(-30.0, 0.0)) // the bore
        b.click(Vec2(-40.0, 20.0))
        b.tool(Tools.DIM_ANGULAR)
        b.click(Vec2(0.0, -40.0)) // the base edge
        b.click(Vec2(30.0, 0.0)) // the brace
        b.click(Vec2(20.0, -25.0)) // the sector meant

        assertEquals(3, b.ed.doc.elements.count { it.kind == ElementKind.DIMENSION }, "three dimensions")
        assertEquals(1, b.ed.doc.elements.count { it.kind == ElementKind.OUTLINE }, "one traced boundary")
        assertTrue(
            b.ed.doc.elements.filter { it.kind == ElementKind.DIMENSION }.all { it.annotation?.graphic(Evaluator()) != null },
            "every dimension draws its graphic, so the sheet is printable",
        )

        b.report(ceiling = 36)
    }
}
