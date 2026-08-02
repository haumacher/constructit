package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.Exports
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of the unbounded chain and the cut (session 41).**
 *
 * The delivery proves the operator against blocks: the halves, the sign, the margin, the refusals. These
 * ask whether it behaves as an ordinary member of the kernel — whether a **new way to remove material**
 * has become a new way round a refusal that already exists, whether it takes the newest kind of solid as
 * happily as the oldest, and whether its own result is an ordinary solid that can be cut again.
 */
class ChainCutProbeTest {
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

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    private fun invalidity(el: constructit.editor.Element): String? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** A chain drawn through the given plan points, ending in rays. */
    private fun chain(
        ed: Editor,
        vararg at: Vec2,
    ): constructit.editor.Element {
        ed.setTool(Tools.POINT)
        at.forEach { ed.click(it) }
        ed.setTool(Tools.CHAIN)
        at.forEach { ed.click(it) }
        ed.key("Enter")
        return ed.doc.elements.last { it.kind == ElementKind.CHAIN }
    }

    // ---- a new way to remove material is not a way round an old refusal ----

    /**
     * **An imported open shell cannot be cut either.** A cut is a boolean by another name — it asks what is
     * *inside* the target — so a surface that does not close has no answer here for exactly the reason it
     * has none for a union (session 34). The risk this probes is specific: the dispatch that decides which
     * boolean engine runs was extracted during this work, and an operand rule that travels with one caller
     * and not the other is precisely how a refusal quietly stops applying.
     */
    @Test
    fun anImportedOpenShellIsRefusedByTheCutAsWellAsByTheBooleans() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedBoxBytes(), "gehaeuse.jt").ok)
        val shell = ed.doc.elements.last { ed.doc.userNameOf(it) == "gehaeuse" }
        val c = chain(ed, Vec2(-40.0, 20.0), Vec2(100.0, 20.0))

        // the gesture: nothing dead is built, and it says why, by name
        val cut = ed.doc.cutByChain(shell, c, signs = listOf(1))
        if (cut == null) {
            assertTrue(ed.doc.note?.contains("open shell") == true, "the gesture refuses by name: ${ed.doc.note}")
        } else {
            val why = assertNotNull(invalidity(cut), "…or the node refuses it")
            assertTrue(why.contains("open shell"), "by name: $why")
        }
    }

    // ---- the newest solid maker meets the newest solid remover ----

    /**
     * **A swept solid is an ordinary cut target, and a cut result is an ordinary operand.** Two claims that
     * only hold if the cut joined the kernel rather than being bolted to prisms: a tube — a mesh-featured
     * body from session 37 — is cut watertight, and the half that survives is itself unioned with another
     * solid and exported.
     */
    @Test
    fun aSweptTubeIsCutWatertightAndTheHalfThatSurvivesStillCombines() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        listOf(Vec2(-120.0, 0.0), Vec2(0.0, 70.0), Vec2(120.0, 0.0)).forEach { ed.click(it) }
        ed.setTool(Tools.CURVE3)
        listOf(Vec2(-120.0, 0.0), Vec2(0.0, 70.0), Vec2(120.0, 0.0)).forEach { ed.click(it) }
        ed.key("Enter")
        val route = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val tube = assertNotNull(ed.doc.tubeAlongCurve(route, ed.doc.newParameter("r", 12.0.mm).ref), "${ed.doc.note}")
        val whole = Geom3.volume(meshOf(ed, tube))
        assertManifold(meshOf(ed, tube), "the tube")

        // a straight chain across the run, keeping one side
        val c = chain(ed, Vec2(-200.0, 35.0), Vec2(200.0, 35.0))
        val half = assertNotNull(ed.doc.cutByChain(tube, c, signs = listOf(1)), "the tube was cut: ${ed.doc.note}")
        assertEquals(null, invalidity(half), "and the cut is a real body")
        assertManifold(meshOf(ed, half), "a cut tube is still watertight")
        val kept = Geom3.volume(meshOf(ed, half))
        assertTrue(kept > 0.0 && kept < whole, "it really removed material: $kept of $whole")

        // the survivor is an ordinary operand and an ordinary export
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-20.0, 60.0))
        ed.click(Vec2(20.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("h", 25.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(0.0, 60.0))
        val block = solids(ed).last()
        val fused = assertNotNull(ed.doc.combineSolids(half, block, constructit.geom.BoolOp.UNION), "${ed.doc.note}")
        assertManifold(meshOf(ed, fused), "a cut tube fused with a block")
        for (format in ExportFormat.entries) {
            assertTrue(Exports.export(ed.doc, "probe", format).ok, "${format.label} writes it")
        }
    }

    // ---- a cut is a solid, so it can be cut ----

    /**
     * **Two cuts in sequence, which is how a real part is shaped.** The second chain has to see the first
     * cut's *result* as an ordinary target — a body whose bounding extent has changed, which is exactly what
     * the derived bound must be re-derived from rather than remembered. A stepped corner taken off a block
     * in two statements, surviving the file.
     */
    @Test
    fun aCutResultIsItselfAnOrdinaryTargetForASecondCut() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("h", 40.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(50.0, 0.0))
        val block = solids(ed).last()
        val whole = Geom3.volume(meshOf(ed, block))

        // the first runs +x, so its left is y > 80 and −1 keeps y < 80
        val first = chain(ed, Vec2(-50.0, 80.0), Vec2(150.0, 80.0))
        val once = assertNotNull(ed.doc.cutByChain(block, first, signs = listOf(-1)), "first cut: ${ed.doc.note}")
        assertManifold(meshOf(ed, once), "after one cut")
        val afterOne = Geom3.volume(meshOf(ed, once))
        assertTrue(afterOne < whole, "material came off: $afterOne of $whole")

        // +1 is the left of the chain's direction of travel: this one runs +y, so its left is x < 70
        val second = chain(ed, Vec2(70.0, -50.0), Vec2(70.0, 150.0))
        val twice = assertNotNull(ed.doc.cutByChain(once, second, signs = listOf(1)), "second cut: ${ed.doc.note}")
        assertManifold(meshOf(ed, twice), "after two cuts")
        val afterTwo = Geom3.volume(meshOf(ed, twice))
        assertTrue(afterTwo < afterOne, "and more came off: $afterTwo of $afterOne")
        // a 70 x 80 x 40 corner of a 100 x 100 x 40 block
        assertClose(afterTwo, 70.0 * 80.0 * 40.0, 70.0 * 80.0 * 40.0 * 0.01, "the corner that is left")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    /** A 60 × 40 × 20 box with one bottom facet removed, as JT bytes — the session-34 open shell. */
    private fun crackedBoxBytes(): ByteArray {
        val corners =
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
        val tris =
            listOf(
                constructit.geom.Tri(0, 3, 2),
                constructit.geom.Tri(4, 5, 6),
                constructit.geom.Tri(4, 6, 7),
                constructit.geom.Tri(0, 1, 5),
                constructit.geom.Tri(0, 5, 4),
                constructit.geom.Tri(1, 2, 6),
                constructit.geom.Tri(1, 6, 5),
                constructit.geom.Tri(2, 3, 7),
                constructit.geom.Tri(2, 7, 6),
                constructit.geom.Tri(3, 0, 4),
                constructit.geom.Tri(3, 4, 7),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "probe",
                listOf(
                    constructit.exchange.ExportNode(
                        "gehaeuse",
                        constructit.geom.Mesh3(corners, tris),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            ),
        )
    }
}
