package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportNode
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.Jt
import constructit.geom.Feature3
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Region
import constructit.geom.Tri
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **An open shell, composed with everything that was already there** — the probe review of the import's
 * flagged bodies (the JT import note under OP-9, session 34).
 *
 * The delivery's own tests take one open shell through each consumer once. These take it *through the rest
 * of the app*: out of the writer and back in through the reader, across a save and a reload, and into the
 * silhouette in the one place where a shell's own rim genuinely changes the answer. Three claims of the
 * as-built note that nothing else asks: that JT writes a shell a reader recognises as one, that the hide the
 * import records on the raw literal survives the file (which is the whole of *"hide it to export the rest"*),
 * and that a hole in a **front-facing** face still yields a closed outline of what still projects.
 */
class OpenShellProbeTest {
    // ---- a box with one facet taken out, written to real JT bytes ----

    private val corners =
        listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(60.0, 0.0, 0.0),
            Vec3(60.0, 40.0, 0.0),
            Vec3(0.0, 40.0, 0.0),
            Vec3(0.0, 0.0, 20.0),
            Vec3(60.0, 0.0, 20.0),
            Vec3(60.0, 40.0, 20.0),
            Vec3(0.0, 40.0, 20.0),
        )

    /** 60 × 40 × 20, outward-wound: two triangles per face, the twelve of them a closed oriented solid. */
    private val boxTriangles =
        listOf(
            // bottom (-z), then top (+z): the two the plan sees, and the only ones with projected area
            Tri(0, 2, 1),
            Tri(0, 3, 2),
            Tri(4, 5, 6),
            Tri(4, 6, 7),
            // the four walls, edge-on to the plan: front (-y), right (+x), back (+y), left (-x)
            Tri(0, 1, 5),
            Tri(0, 5, 4),
            Tri(1, 2, 6),
            Tri(1, 6, 5),
            Tri(2, 3, 7),
            Tri(2, 7, 6),
            Tri(3, 0, 4),
            Tri(3, 4, 7),
        )

    /** The box with triangle [drop] removed — a shell whose hole is exactly where the caller wants it. */
    private fun crackedBox(drop: Int): Mesh3 = Mesh3(corners, boxTriangles.filterIndexed { i, _ -> i != drop })

    /** Those triangles as a JT file, written by the sibling's own writer — bytes, not a hand-built scene. */
    private fun jtBytes(
        mesh: Mesh3,
        name: String,
    ): ByteArray = Jt.write(ExportScene("probe", listOf(ExportNode(name, mesh, Appearance.DEFAULT))))

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun featureOf(
        ed: Editor,
        name: String,
    ): Feature3.Imported {
        val el = ed.doc.elements.last { it.kind == ElementKind.SOLID && ed.doc.userNameOf(it) == name }
        return Evaluator().solid(el.ref as SolidRef).feature as Feature3.Imported
    }

    private fun areaOf(region: Region): Double {
        val pts = region.outer.elements.filterIsInstance<ProfileElement.Seg>().map { it.segment.a }
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    // ---- the loop, closed on a body that is not a solid ----

    /**
     * **A shell survives the round trip as a shell.** JT writes one deliberately (a viewing and interchange
     * format has no business refusing a surface), and the note on the export says so — so the test that
     * matters is what a *reader* makes of those bytes: a fresh drawing must derive the same flag from the
     * triangles that made the trip, without a word about it having been stored anywhere.
     *
     * The composition is the point: the flag is not a fact the importer remembers about a file, it is a fact
     * about a mesh, and two independent passes over the same geometry have to agree.
     */
    @Test
    fun aJtRoundTripOfAnOpenShellComesBackAnOpenShell() {
        val ed = Editor()
        val first = ed.importFile(jtBytes(crackedBox(drop = 0), "gehaeuse"), "gehaeuse.jt")
        assertTrue(first.ok, first.message)
        assertEquals(listOf("gehaeuse"), first.openShells, "it came in flagged: ${first.message}")
        val there = ExportScene.extract(ed.doc, "p").nodes.single()

        // out again through JT — written, not refused, and the result says what it wrote
        val out = Exports.export(ed.doc, "again", ExportFormat.JT)
        assertTrue(out.ok, out.message)
        assertTrue(out.message.contains("gehaeuse is an open shell"), "with a note: ${out.message}")

        // ...and back into a drawing that knows nothing of the first
        val ed2 = Editor()
        val second = ed2.importFile(out.bytes!!, "again.jt")
        assertTrue(second.ok, second.message)
        assertEquals(1, second.openShells.size, "the reader derives the same answer: ${second.message}")
        val back = ExportScene.extract(ed2.doc, "p").nodes.single()
        assertEquals(there.mesh.triangleCount, back.mesh.triangleCount, "with every triangle it left with")
        assertNotNull(featureOf(ed2, second.bodies.single()).openShell, "and the body says so from its feature")

        // the consumers answer the same on the far side, which is what makes the flag worth deriving twice
        val print = Exports.export(ed2.doc, "p", ExportFormat.THREE_MF)
        assertFalse(print.ok, "printing still refuses it: ${print.message}")
        assertTrue(print.message.contains("open shell"), print.message)
    }

    // ---- the hide the import records, across a file ----

    /**
     * **"Hide it to export the rest" has to still be true after a reload.** An import hides the raw literal
     * its placement rides, in one recorded step, because a literal is the file's content in the file's own
     * coordinates and is nobody's output. While the placement is visible the export seam skips the literal
     * anyway, as that placement's material — so the *only* moment the hide does any work is when the
     * placement is hidden, which is exactly the moment the refusal's advice sends a user to.
     *
     * That makes the recorded step, not the runtime state, the thing under test: it must come back off the
     * file, or the advice is a lie in every drawing anyone saves.
     */
    @Test
    fun theImportsHideOfItsLiteralSurvivesTheFileSoHidingTheBodyExportsTheRest() {
        val ed = Editor()
        assertTrue(ed.importFile(jtBytes(crackedBox(drop = 0), "gehaeuse"), "gehaeuse.jt").ok)
        // a clean body beside it, so "the rest" is something
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(120.0, 0.0))
        ed.click(Vec2(160.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("d", constructit.units.Quantity.mm(10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(140.0, 0.0))
        assertEquals(2, ExportScene.extract(ed.doc, "p").nodes.size, "two bodies; the literal is not one of them")

        // through the file, and on the far side hide the shell — the advice the print refusal gives
        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
        assertEquals(2, ExportScene.extract(reloaded, "p").nodes.size, "the reloaded drawing has the same two")
        val refused = Exports.export(reloaded, "p", ExportFormat.THREE_MF)
        assertFalse(refused.ok, "and printing it refuses: ${refused.message}")
        assertTrue(refused.message.contains("hide it to export the rest"), refused.message)

        val shell = reloaded.elements.first { reloaded.userNameOf(it) == "gehaeuse" }
        assertEquals(1, reloaded.setElementsVisible(listOf(shell), false), "the body hides")
        val printed = Exports.export(reloaded, "p", ExportFormat.THREE_MF)
        assertTrue(printed.ok, "and the rest exports — the literal did not surface: ${printed.message}")
        assertTrue(printed.message.contains("1 solid"), printed.message)

        // and the decision is takeable back, from the far side of the file as much as from this one
        assertEquals(1, reloaded.setElementsVisible(listOf(shell), true))
        assertFalse(Exports.export(reloaded, "p", ExportFormat.THREE_MF).ok, "the shell is a body again")
    }

    // ---- the outline of a hole that faces the plan ----

    /**
     * **A hole in a face that faces the plan changes which loops there are, not whether there are any.**
     * The delivery's fixture cracks a *wall*, where the projection is unchanged and the silhouette has
     * nothing to prove. This one takes a facet out of the **top**, which is half the front-facing material
     * of the body — and the honest answer is the triangle that still projects: a closed loop, drawn and
     * pickable, of exactly the area that is left.
     *
     * The claim being probed is the one the as-built note rests the "no special case" argument on: the kept
     * edges bound the front-facing *chain*, and the boundary of a chain is a cycle whatever holes it has.
     */
    @Test
    fun theOutlineOfAShellMissingAFrontFacingFacetIsWhatStillProjects() {
        val plan = Plane3(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val whole = constructit.geom.Silhouette.of(Mesh3(corners, boxTriangles), plan)
        assertEquals(1, whole.size, "the closed box projects to one loop")
        assertClose(abs(areaOf(whole.single())), 60.0 * 40.0, 1e-9, "its whole rectangle")

        // triangle 3 is one of the two that make the top face — the half that still faces the plan is a
        // triangle, and its own rim is now part of the outline
        val outline = constructit.geom.Silhouette.of(crackedBox(drop = 3), plan)
        assertEquals(1, outline.size, "still one loop, and never empty")
        assertEquals(3, outline.single().outer.elements.size, "closed on three corners, not an open chain")
        assertClose(abs(areaOf(outline.single())), 60.0 * 40.0 / 2.0, 1e-9, "of exactly what still projects")

        // and that is what the drawing draws: the body imports, is flagged, and carries that plan
        val ed = Editor()
        val result = ed.importFile(jtBytes(crackedBox(drop = 3), "deckel"), "deckel.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("deckel"), result.openShells, result.message)
        val feature = featureOf(ed, "deckel")
        assertNotNull(feature.openShell)
        assertEquals(1, feature.plan.size, "the placed body's plan is the loop the silhouette found")
        assertClose(abs(areaOf(feature.plan.single())), 60.0 * 40.0 / 2.0, 1e-6, "at its placed position")

        // pickable by that plan, and the pick says what it picked — on the rim it still has, away from the
        // anchor point the placement put at the corner (a point wins a pick over the body it moves)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(40.0, 0.0))
        assertTrue(ed.selectionLabel().contains("deckel"), "the body is what the click found: ${ed.selectionLabel()}")
        assertTrue(ed.selectionLabel().contains("open shell"), "and it says what it is: ${ed.selectionLabel()}")
    }
}
