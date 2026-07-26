package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.OrthoCornerHandle
import constructit.editor.Tools
import constructit.editor.writableMaster
import constructit.geom.Vec2
import constructit.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A corner may meet two junctions, one per coordinate** (OP-20, GitHub issue #4).
 *
 * Reported on the drawing below — a closed rectangle with a *T-web*: one branch that turns once between the
 * left wall and the bottom wall, and one straight run between the top wall and the bottom wall. The corner
 * where the turning branch bends "cannot move freely (only one direction) and snaps back to its original
 * location", and so did others.
 *
 * The mechanism, found by dragging every corner and every leg of the fixture and printing what moved:
 *
 * - the branch's **first** end attaches to the left wall, so a junction there owns its y — and the whole
 *   horizontal leg's y, since the corner at the far end is bound to it;
 * - the branch's **second** end attaches to the bottom wall with *two* free coordinates (its own y, and the
 *   x it shares with the bend), so that is an ordinary junction too, and it owns the branch's x;
 * - the bend is therefore driven by **two different junctions, one per coordinate** — and
 *   `OrthoCornerHandle` held a single `junction`, taking whichever the x lookup found first. Dragging handed
 *   that one junction the whole cursor, so the y half of the gesture was dropped: the corner tracked the
 *   cursor's x, ignored its y, and came back to where it started whenever the x did. Two DOF, one reachable.
 *
 * Rider compensation was not involved (`riderAnchors()` is empty on this drawing — every host is an
 * axis-aligned leg, so no rider is registered), nor was the pick pile: the status line named the right
 * corner throughout. What gave it away is that **typing** the same y worked: the panel field asks
 * `junctionOf` per coordinate, the drag did not. Now both do, per axis.
 */
class OrthoWebFreedomTest {
    /** The reported drawing, verbatim. */
    private val web =
        """
constructit 2
orthostart -36.5,77 -> e1
orthovertex 13.5,77 -> e2,e3
orthovertex 13.5,27 -> e4,e5
orthovertex -36.5,27 -> e6,e7
orthoclose -> e8
orthostart -36.5,46.75 -> e9
attachortho e9 e8
orthovertex -16.5,46.75 -> e10,e11
orthovertex -16.5,27 -> e12,e13
attachortho e12 e7
orthostart -2.5,77 -> e14
attachortho e14 e3
orthovertex -2.5,27 -> e15,e16
attachortho e15 e7
""".trimStart()

    private fun load(): Editor = Editor().also { it.replaceDocument(DocumentFormat.load(web)) }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun at(
        ed: Editor,
        path: Int,
        v: Int,
    ): Vec2 =
        ed.doc.orthoPaths[path].vertices[v].let {
            ((Evaluator().eval(it.ref.node) as EvalResult.Ok).value as PointValue).p
        }

    /** Every vertex of every path, as one string — the drawing's fingerprint. */
    private fun shape(ed: Editor): String =
        ed.doc.orthoPaths.withIndex().joinToString(" | ") { (pi, p) ->
            "p$pi:" + p.vertices.indices.joinToString(",") { vi -> at(ed, pi, vi).let { "(${it.x},${it.y})" } }
        }

    private fun cornerNamed(
        ed: Editor,
        name: String,
    ): Element = ed.doc.elements.first { ed.doc.nameOf(it) == name }

    /** Where the midpoint of leg [li] of path [pi] currently is — where a leg drag must grab it. */
    private fun legMid(
        ed: Editor,
        pi: Int,
        li: Int,
    ): Vec2 {
        val p = ed.doc.orthoPaths[pi]
        val a = at(ed, pi, li)
        val b = at(ed, pi, (li + 1) % p.vertices.size)
        return Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
    }

    /**
     * Which world axes a drag of the element at [grab] actually moves the drawing on: "x", "y", "xy" or "" —
     * the OP-20 contract in one word. Each axis is tried on its own, from a freshly loaded fixture, so no
     * gesture can hide behind another.
     */
    private fun freeAxes(
        grab: (Editor) -> Vec2,
        push: Double = 9.0,
    ): String {
        var out = ""
        for ((axis, delta) in listOf("x" to Vec2(push, 0.0), "y" to Vec2(0.0, push))) {
            val ed = load()
            val from = grab(ed)
            val before = shape(ed)
            ed.drag(from, from + delta)
            if (shape(ed) != before) out += axis
        }
        return out
    }

    /**
     * **The report, pinned.** Every corner of the web moves on exactly the axes its freedom allows — and the
     * bend between the two junctions moves on **both**, which is what it could not do.
     *
     * The driven cases are not failures but the honest answer, and each names what determines it:
     * a junction riding a wall owns the one coordinate that wall leaves free, and a *determined* meeting owns
     * nothing at all.
     */
    @Test
    fun everyCornerMovesOnExactlyTheAxesItsFreedomAllows() {
        val expected =
            listOf(
                Triple(0, 0, "xy") to "a rectangle corner owns both coordinates",
                Triple(0, 1, "xy") to "as does every other one",
                Triple(0, 2, "xy") to "including the far diagonal",
                Triple(0, 3, "xy") to "and the one before the closing leg",
                Triple(1, 0, "y") to "the branch's first end rides the vertical left wall, which fixes its x",
                Triple(1, 1, "xy") to "the bend is driven by two junctions — x by the one on the bottom wall, y by the one on the left wall",
                Triple(1, 2, "x") to "the branch's second end rides the horizontal bottom wall, which fixes its y",
                Triple(2, 0, "x") to "the straight run's first end rides the horizontal top wall",
                Triple(2, 1, "x") to "and its second end is a determined meeting on the bottom wall: only the run's x is left",
            )
        for ((spec, why) in expected) {
            val (pi, vi, axes) = spec
            val start = at(load(), pi, vi)
            assertEquals(axes, freeAxes({ at(it, pi, vi) }), "corner p$pi v$vi at $start — $why")
        }
    }

    /**
     * The bend in detail: each axis goes to **its own** junction, and the junction carries its whole run with
     * it. Handing one junction the whole cursor moved one axis and left the other where it was.
     */
    @Test
    fun theBendDelegatesEachAxisToItsOwnJunction() {
        // up: the junction on the *left* wall slides, so the whole horizontal leg rises — both its ends
        val up = load()
        up.drag(Vec2(-16.5, 46.75), Vec2(-16.5, 56.75))
        assertClose(at(up, 1, 1).y, 56.75, 1e-9, "the bend rose")
        assertClose(at(up, 1, 0).y, 56.75, 1e-9, "and took the leg's other end with it, along the left wall")
        assertClose(at(up, 1, 0).x, -36.5, 1e-9, "which still rides that wall")
        assertClose(at(up, 1, 2).y, 27.0, 1e-9, "the far end stays on the bottom wall")
        assertClose(at(up, 1, 1).x, -16.5, 1e-9, "and nothing moved sideways")
        assertEquals(
            "p0:(-36.5,77.0),(13.5,77.0),(13.5,27.0),(-36.5,27.0) | p2:(-2.5,77.0),(-2.5,27.0)",
            shape(up).split(" | ").let { "${it[0]} | ${it[2]}" },
            "and the rectangle and the other run are untouched",
        )

        // sideways: the junction on the *bottom* wall slides, so the vertical leg travels along it
        val over = load()
        over.drag(Vec2(-16.5, 46.75), Vec2(-6.5, 46.75))
        assertClose(at(over, 1, 1).x, -6.5, 1e-9, "the bend moved sideways")
        assertClose(at(over, 1, 2).x, -6.5, 1e-9, "and the far end slid along the bottom wall with it")
        assertClose(at(over, 1, 2).y, 27.0, 1e-9, "staying on it")
        assertClose(at(over, 1, 1).y, 46.75, 1e-9, "and nothing moved vertically")

        // and diagonally, both at once — the gesture the report was made with
        val both = load()
        both.drag(Vec2(-16.5, 46.75), Vec2(-6.5, 56.75))
        assertClose(at(both, 1, 1).x, -6.5, 1e-9)
        assertClose(at(both, 1, 1).y, 56.75, 1e-9)
        assertEquals(
            "p1:(-36.5,56.75),(-6.5,56.75),(-6.5,27.0)",
            shape(both).split(" | ")[1],
            "the whole branch followed, and it is still rectilinear",
        )
        // a drag is one checkpoint, and the file restates where the corner now is (OP-18)
        val saved = DocumentFormat.save(both.doc)
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and it round-trips")
    }

    /**
     * **Typing reaches exactly as far as dragging, and refuses exactly as far** (OP-13, OP-20). The panel is
     * where the fault was visible: typing the bend's y worked while dragging it did nothing, because the field
     * asks `junctionOf` per coordinate. It is also where the *other* half showed up — a `y` offered on a
     * junction riding a horizontal wall, whose write did nothing at all.
     */
    @Test
    fun typingACoordinateReachesAndRefusesExactlyAsFarAsDraggingIt() {
        val expected =
            mapOf(
                "e9" to setOf("y"),
                "e10" to setOf("x", "y"),
                "e12" to setOf("x"),
                "e14" to setOf("x"),
                "e15" to setOf("x"),
            )
        for ((name, axes) in expected) {
            val ed = load()
            val el = cornerNamed(ed, name)
            ed.selectElement(el)
            val writable =
                ed.selectionFields().filter { it.label in setOf("x", "y") && it.writable }.map { it.label }.toSet()
            assertEquals(axes, writable, "$name's writable coordinates must be the ones a drag moves")
        }

        // …and a write that is offered lands exactly, through the junction that owns it
        val ed = load()
        val bend = cornerNamed(ed, "e10")
        ed.selectElement(bend)
        ed.selectionFields().first { it.label == "y" }.write(Quantity.mm(60.0))
        assertClose(at(ed, 1, 1).y, 60.0, 1e-9, "typed y")
        assertClose(at(ed, 1, 0).y, 60.0, 1e-9, "carried the leg, exactly as the drag does")
        ed.selectionFields().first { it.label == "x" }.write(Quantity.mm(-9.0))
        assertClose(at(ed, 1, 1).x, -9.0, 1e-9, "typed x")
        assertClose(at(ed, 1, 2).x, -9.0, 1e-9, "and slid the far end along the bottom wall")
    }

    /** Every corner's coordinates are either its own or somebody's — never silently nobody's. */
    @Test
    fun everyDrivenCoordinateNamesTheFreedomThatMovesIt() {
        val ed = load()
        var twoJunctions = 0
        for (el in ed.doc.elements) {
            val h = el.handle as? OrthoCornerHandle ?: continue
            val jx = ed.doc.junctionOf(h.xNode)
            val jy = ed.doc.junctionOf(h.yNode)
            if (jx != null && jy != null && jx !== jy) twoJunctions++
            // a junction registered against a coordinate must be able to place *something*, or it would be a
            // freedom in name only — and the panel would offer a field whose write does nothing
            for ((axis, junction) in listOf(0 to jx, 1 to jy)) {
                val node = if (axis == 0) h.xNode else h.yNode
                if (writableMaster(node) != null || junction == null) continue
                assertTrue(
                    junction.placeable(0) || junction.placeable(1),
                    "${ed.doc.nameOf(el)}'s junction can place nothing at all",
                )
            }
        }
        assertEquals(1, twoJunctions, "exactly one corner of this web meets two junctions — the bend")
        assertEquals(0, ed.doc.riderAnchors().size, "every host here is axis-aligned, so no rider is compensated")
    }

    /**
     * **A leg has one degree of freedom and it is across itself** — asserted for every leg of the web,
     * including the three whose coordinate belongs to a junction rather than to the leg (OP-20's delegation
     * from the leg's side).
     */
    @Test
    fun everyLegDragsAcrossItselfAndOnlyAcrossItself() {
        val expected =
            listOf(
                Triple(0, 0, "y") to "the top wall",
                Triple(0, 1, "x") to "the right wall",
                Triple(0, 2, "y") to "the bottom wall",
                Triple(0, 3, "x") to "the left (closing) wall",
                Triple(1, 0, "y") to "the branch's horizontal leg — its y is the junction's on the left wall",
                Triple(1, 1, "x") to "the branch's vertical leg — its x is the junction's on the bottom wall",
                Triple(2, 0, "x") to "the straight run — its x is the junction's on the top wall",
            )
        for ((spec, what) in expected) {
            val (pi, li, axes) = spec
            assertEquals(axes, freeAxes({ legMid(it, pi, li) }, push = 8.0), "leg $li of path $pi ($what)")
        }
    }

    /**
     * A leg drag through a junction lands the leg **under the cursor**, not at a projection of it — and takes
     * only what shares its coordinate.
     */
    @Test
    fun draggingTheBranchesLegThroughItsJunctionLandsItUnderTheCursor() {
        val ed = load()
        ed.drag(Vec2(-26.5, 46.75), Vec2(-26.5, 36.75))
        assertClose(at(ed, 1, 0).y, 36.75, 1e-9, "the leg landed exactly where the cursor left it")
        assertClose(at(ed, 1, 1).y, 36.75, 1e-9, "at both ends")
        assertClose(at(ed, 1, 0).x, -36.5, 1e-9, "still on the left wall")
        assertClose(at(ed, 1, 2).y, 27.0, 1e-9, "and the far end still on the bottom wall")
    }

    /** The fixture itself replays to itself: nothing here depends on a load-time repair. */
    @Test
    fun theReportedFixtureRoundTrips() {
        val ed = load()
        assertEquals(web, DocumentFormat.save(ed.doc), "the file must replay to itself")
    }
}
