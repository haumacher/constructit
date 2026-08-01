package constructit

import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Painter3
import constructit.editor.Scene3
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.Glb
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Appearance, Tier 1: a material per solid** — assigned through the panel's own route, recorded as a step,
 * restated on every save, and read by both consumers.
 *
 * The persistence shape is the element-rename pattern verbatim (`Document.nameElement`): one step per dressed
 * solid, created on the first assignment and **restated** from then on, so re-picking a colour never grows the
 * file. That choice needs **no format version bump**, and the reason is the versioning doctrine's own test
 * (OP-18): a bump is owed when a *stored literal changes meaning*, and a step kind that never existed before
 * cannot have meant something else. Which is exactly why the pre-materials fixture below is a permanent test:
 * a drawing written before this feature loads with the defaults and says nothing about it.
 */
class MaterialTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    private fun Editor.solid(): Element = doc.elements.single { it.kind == ElementKind.SOLID }

    private fun pyramid(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        ed.type("90")
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(50.0, 50.0))
        return ed
    }

    /**
     * **A drawing written before materials existed**, verbatim: it loads, every solid wears the default, and
     * the load says nothing — there is nothing to say. Kept permanently, because this is the assertion that
     * catches the day somebody makes the material step required, or changes what a missing one means.
     *
     * The step declares **two** creations since OP-25: *Extrude to point* raises a height point over the apex
     * it was clicked at and then lofts to it, so the apex is a first-class point of the drawing. Written into
     * the fixture rather than migrated on load, on the user's standing call — there is no release, so the
     * rule simply changes.
     */
    private val preMaterials =
        """
        constructit 2
        orthostart 0,0 -> e1
        orthovertex 100,0 -> e2,e3
        orthovertex 100,100 -> e4,e5
        orthovertex 0,100 -> e6,e7
        orthoclose -> e8
        param "height" = 90mm
        point 50,50 -> e9
        tool extrudepoint pts=e9 els=e3 clicks=30,0;50,50 scalar="height" signs=0;0 -> e10,e11
        """.trimIndent() + "\n"

    @Test
    fun aPreMaterialsDrawingLoadsWithTheDefaultsAndNoNotes() {
        val doc = DocumentFormat.load(preMaterials)
        val solid = doc.elements.single { it.kind == ElementKind.SOLID }
        assertNull(doc.assignedMaterial(solid), "nothing was assigned")
        assertEquals(Appearance.DEFAULT, doc.materialOf(solid), "so it wears the default")
        assertEquals(Appearance("#c8c8c8", 0.6, 0.1), Appearance.DEFAULT, "...and the default is what it always was")
        assertTrue(doc.loadNotes.isEmpty(), "a drawing that predates a feature has nothing to be told: ${doc.loadNotes}")
        // ...and it saves back byte-identically, which is what "the reader was not edited" means
        assertEquals(preMaterials, DocumentFormat.save(doc), "save -> load -> save on a pre-materials file")
    }

    /** Assigning through the panel route: one step, one undo, and the row shows what it actually took. */
    @Test
    fun assigningAMaterialRecordsOneStepAndIsUndoable() {
        val ed = pyramid()
        val solid = ed.solid()
        val steps = ed.doc.journal.size
        val took = ed.setMaterial(solid, Appearance("#B87333", roughness = 0.35, metallic = 0.9))
        assertEquals(Appearance("#b87333", 0.35, 0.9), took, "the colour comes back in the file's own spelling")
        assertEquals(steps + 1, ed.doc.journal.size, "one step")
        assertTrue(ed.statusHint.contains("#b87333"), "the status line says what it now is: ${ed.statusHint}")

        // re-picking is a restatement, not a second step
        ed.setMaterial(solid, Appearance("#333333", roughness = 0.2, metallic = 0.0))
        assertEquals(steps + 1, ed.doc.journal.size, "still one step")
        assertEquals(Appearance("#333333", 0.2, 0.0), ed.doc.materialOf(solid))

        ed.undo()
        assertEquals(Appearance("#b87333", 0.35, 0.9), ed.doc.materialOf(solid), "undo puts the previous one back")
        ed.undo()
        assertNull(ed.doc.assignedMaterial(ed.solid()), "...and again, back to the default")
        assertEquals(steps, ed.doc.journal.size)
    }

    /** save -> load -> save is byte-equal with a material on board, and the value comes back exactly. */
    @Test
    fun theMaterialSurvivesSaveAndLoadByteEqual() {
        val ed = pyramid()
        ed.setMaterial(ed.solid(), Appearance("#b87333", roughness = 0.35, metallic = 0.9))
        val once = DocumentFormat.save(ed.doc)
        assertTrue(
            once.lines().any { it.startsWith("material ") && it.contains("color=#b87333") && it.contains("rough=0.35") },
            "the step states all three numbers: $once",
        )
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save -> load -> save must be byte-equal")
        val solid = back.elements.single { it.kind == ElementKind.SOLID }
        assertEquals(Appearance("#b87333", 0.35, 0.9), back.materialOf(solid))
    }

    /** Clearing it drops the step outright — there is nothing left for it to say. */
    @Test
    fun clearingAMaterialDropsTheStep() {
        val ed = pyramid()
        ed.setMaterial(ed.solid(), Appearance("#b87333"))
        assertTrue(DocumentFormat.save(ed.doc).contains("material "))
        ed.setMaterial(ed.solid(), null)
        assertNull(ed.doc.assignedMaterial(ed.solid()))
        assertTrue(!DocumentFormat.save(ed.doc).contains("material "), DocumentFormat.save(ed.doc))
    }

    /** Deleting the solid takes its material step with it — the ordinary reference rule, nothing special. */
    @Test
    fun deletingTheSolidTakesItsMaterialStep() {
        val ed = pyramid()
        ed.setMaterial(ed.solid(), Appearance("#b87333"))
        ed.selectElement(ed.solid())
        assertTrue(ed.deleteSelection())
        assertTrue(!DocumentFormat.save(ed.doc).contains("material "), DocumentFormat.save(ed.doc))
    }

    /** Only solids can carry one, and the refusal says why rather than doing nothing. */
    @Test
    fun onlyASolidCanCarryAMaterial() {
        val ed = pyramid()
        val leg = ed.doc.elements.first { it.kind == ElementKind.SEGMENT }
        assertNull(ed.setMaterial(leg, Appearance("#ff0000")))
        assertTrue(ed.statusHint.contains("not a solid"), ed.statusHint)
        assertTrue(!ed.doc.canSetMaterial(leg))
        assertTrue(ed.doc.canSetMaterial(ed.solid()))
    }

    /** The assigned numbers are clamped to the range the PBR model defines them on, on the way in. */
    @Test
    fun theNumbersAreClampedWhereTheyAreAssigned() {
        val ed = pyramid()
        val took = ed.setMaterial(ed.solid(), Appearance("#ffffff", roughness = 4.0, metallic = -1.0))
        assertEquals(Appearance("#ffffff", 1.0, 0.0), took)
    }

    /** ...and the GLB shows it: the assigned colour, linearized, in the exported material. */
    @Test
    fun theExportedGlbReflectsTheAssignedMaterial() {
        val ed = pyramid()
        ed.setMaterial(ed.solid(), Appearance("#b87333", roughness = 0.35, metallic = 0.9))
        val json = String(Glb.write(ExportScene.extract(ed.doc, "copper")), Charsets.UTF_8)
        val rgb = Appearance("#b87333").linearRgb()
        assertTrue(json.contains(""""metallicFactor":0.9,"roughnessFactor":0.35"""), json.substringAfter("\"materials\""))
        assertTrue(
            json.contains(""""baseColorFactor":[${Glb.num(rgb[0])},${Glb.num(rgb[1])},${Glb.num(rgb[2])},1]"""),
            json.substringAfter("\"materials\""),
        )
    }

    /**
     * **The 3D construction view shades the assigned colour** (GitHub issue #8), and keeps the identification
     * palette for a solid nobody has dressed — so a choice is visible where the modelling happens without
     * making every undressed body the same grey.
     */
    @Test
    fun theConstructionViewShadesTheAssignedColourAndPalettesTheRest() {
        val ed = pyramid()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size)

        // undressed: both wear their palette entries, and they are told apart
        val before = Scene3.extract(ed.doc)
        assertEquals(solids.map { Scene3.colorFor(it.id) }, before.solids.map { it.color }, "the palette is the default")
        assertTrue(before.solids[0].color != before.solids[1].color, "two undressed bodies stay distinguishable")

        // dress one: it carries its own colour, the other is untouched
        ed.setMaterial(solids[0], Appearance("#b87333", roughness = 0.35, metallic = 0.9))
        val dressed = Scene3.extract(ed.doc)
        assertEquals("#b87333", dressed.solids[0].color, "the assigned base colour is what the view shades")
        assertEquals(Scene3.colorFor(solids[1].id), dressed.solids[1].color, "the undressed sibling keeps its palette entry")
        // ...and the feature edges follow, because an edge is that colour darkened — one authority, not two
        assertEquals(Painter3.shade("#b87333", Scene3.EDGE_SHADE), dressed.solids[0].edgeColor)

        // an *edit* is followed on the next extraction: the scene is a value, so there is no cache to miss it
        ed.setMaterial(solids[0], Appearance("#123456"))
        assertEquals("#123456", Scene3.extract(ed.doc).solids[0].color)

        // ...and clearing it hands the body back to the palette
        ed.setMaterial(solids[0], null)
        assertEquals(Scene3.colorFor(solids[0].id), Scene3.extract(ed.doc).solids[0].color)
    }

    /**
     * The view takes the **colour and nothing else**: roughness and metalness are stated as not applying to a
     * flat-shaded technical view (see `Scene3.colorOf`), so two solids that differ only in those two numbers
     * are drawn identically. Pinned, because "we deliberately did not implement it" is only a decision if
     * something notices when it quietly changes.
     */
    @Test
    fun roughnessAndMetalnessDoNotReachTheConstructionView() {
        val ed = pyramid()
        val solid = ed.solid()
        ed.setMaterial(solid, Appearance("#b87333", roughness = 0.0, metallic = 1.0))
        val metal = Scene3.extract(ed.doc).solids.single()
        ed.setMaterial(solid, Appearance("#b87333", roughness = 1.0, metallic = 0.0))
        val plastic = Scene3.extract(ed.doc).solids.single()
        assertEquals(metal.color, plastic.color)
        assertEquals(metal.edgeColor, plastic.edgeColor)
    }

    /** A material assigned to one body is not worn by another: the record is per element. */
    @Test
    fun aMaterialBelongsToOneBody() {
        val ed = pyramid()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(200.0, 50.0))
        ed.activeScalar = ed.doc.newParameter("h", constructit.units.Quantity.mm(20.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(175.0, 0.0))
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        assertEquals(2, solids.size)
        ed.setMaterial(solids[0], Appearance("#b87333"))
        assertEquals("#b87333", ed.doc.materialOf(solids[0]).color)
        assertEquals(Appearance.DEFAULT_COLOR, ed.doc.materialOf(solids[1]).color)
        val scene = ExportScene.extract(ed.doc, "two")
        assertEquals(listOf("#b87333", Appearance.DEFAULT_COLOR), scene.nodes.map { it.material.color })
    }
}
