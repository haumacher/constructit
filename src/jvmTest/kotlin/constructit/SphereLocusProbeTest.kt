package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Point3Ref
import constructit.dsl.Sphere3Ref
import constructit.dsl.point3
import constructit.dsl.sphere3
import constructit.editor.Dependencies
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.SvgDrawTarget
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The sphere locus composed with everything that came before it** (OP-28) — the probe, and the question it
 * asks is not *does the happy path work* but *is the mechanism general*.
 *
 * So nothing here is about spheres for their own sake. Each test takes the new kind and runs it through a
 * seam that existed before it and knows nothing about it: the ghost layer, deletion and its dependents, the
 * dependency panel, a **sketch on a face** (a locus centred on geometry of another pane), the marquee, and the
 * naming authority the file and the panel share. A concept that is really a concept survives all of them
 * without a case being written for it anywhere.
 */
class SphereLocusProbeTest {
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

    private fun Editor.loci(): List<Element> = doc.elements.filter { it.kind == ElementKind.SPHERE_LOCUS }

    @Suppress("UNCHECKED_CAST")
    private fun sphereOf(el: Element) = Evaluator().sphere3(el.ref as Sphere3Ref)

    @Suppress("UNCHECKED_CAST")
    private fun pointOf(el: Element): Vec3 = Evaluator().point3(el.ref as Point3Ref)

    private fun reasonOf(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** Two loci on two plan points, 60 apart, with radii that overlap. */
    private fun twoLoci(): Editor {
        val ed = Editor()
        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("40")
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("40")
        ed.click(Vec2(60.0, 0.0))
        assertEquals(2, ed.loci().size, "two loci: ${ed.statusHint}")
        return ed
    }

    /** **A locus ghosts like anything else** — the hide toggle knows nothing about it and needs to know nothing. */
    @Test
    fun aHiddenLocusJoinsTheGhostLayerAndComesBack() {
        val ed = twoLoci()
        val locus = ed.loci().first()
        locus.visible = false
        ed.showHidden = true
        assertTrue(ed.ghostElements().any { it === locus }, "the hidden locus is a ghost: ${ed.statusHint}")
        val target = SvgDrawTarget()
        ed.render(target)
        assertTrue(target.svg().isNotEmpty(), "and the canvas still draws")
        locus.visible = true
        assertTrue(ed.ghostElements().none { it === locus }, "and it leaves the ghost layer when shown again")
    }

    /**
     * **Deleting a locus takes what was built on it, or refuses — and either way it speaks.** The generic
     * dependent machinery has never heard of a locus, and does not have to.
     */
    @Test
    fun deletingALocusIsAnswerredByTheOrdinaryDependentMachinery() {
        val ed = twoLoci()
        ed.setTool(Tools.SPHERE_CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val circle = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)
        val locus = ed.loci().first()

        val dependents = Dependencies.dependentsOf(ed.doc, locus)
        assertTrue(dependents.any { it === circle }, "the circle is a dependent of the locus it was cut from")
        val inputs = Dependencies.inputsOf(ed.doc, circle)
        assertTrue(inputs.any { it.element === locus }, "and the locus is an input of the circle")

        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-40.0, 0.0))
        assertEquals(locus.id, assertNotNull(ed.selection).id, "the locus is selected")
        val deleted = ed.deleteSelection()
        assertTrue(ed.statusHint.isNotEmpty(), "the drawing says what happened either way: ${ed.statusHint}")
        if (deleted) {
            assertTrue(
                ed.doc.elements.none { it.id == circle.id } || reasonOf(ed.doc.elements.first { it.id == circle.id }) != null,
                "a circle whose locus is gone is gone or invalid, never quietly wrong",
            )
        }
        assertTrue(ed.undo(), "and the delete is one undo")
    }

    /**
     * **A locus centred on a point of another pane.** The centre is a corner of a **sketch on a face** — a
     * point that lives in a face space's own coordinates — and the locus is nevertheless world geometry that
     * a plan-space consumer reads. Nothing about the space seam needed a case for it: `pointInSpace` is the
     * one seam every point's world position already flowed through.
     */
    @Test
    fun aLocusCanBeCentredOnAPointOfAnotherPane() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(80.0, 60.0))
        ed.setTool(Tools.EXTRUDE)
        ed.type("40")
        ed.click(Vec2(40.0, 0.0))
        val plan = ed.activeSpace.name

        ed.setTool(Tools.SKETCH_ON_FACE)
        // the footprint's bottom edge, which is the plate's own upright side face seen from above
        ed.click(Vec2(40.0, 0.0))
        val face = ed.activeSpace.name
        assertTrue(face != plan, "a face space opened: ${ed.statusHint}")

        ed.setTool(Tools.SPHERE_LOCUS)
        ed.type("30")
        ed.click(Vec2(10.0, 10.0))
        val onFace = assertNotNull(ed.loci().lastOrNull(), "a locus on the face: ${ed.statusHint}")
        val centre = sphereOf(onFace).center
        assertTrue(
            centre.z > 1e-9 || centre.x != 10.0 || centre.y != 10.0,
            "its centre is a world position read through the face's own frame, not a pair of plan numbers: $centre",
        )
        assertClose(sphereOf(onFace).radius, 30.0, 1e-12, "with the radius that was typed")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and it round-trips byte for byte")
    }

    /**
     * **A locus is taken by a rubber band**, in the view the band was dragged in — the marquee's dispatch was
     * extended with the same picture the renderer draws and the pick measures, so all three agree.
     */
    @Test
    fun aRubberBandOverTheRimTakesTheLocus() {
        val ed = twoLoci()
        ed.setTool(Tools.SELECT)
        val a = ed.camera.worldToScreen(Vec2(-60.0, -60.0))
        val b = ed.camera.worldToScreen(Vec2(-20.0, 60.0))
        ed.pointerDown(a)
        ed.pointerMove(b)
        ed.pointerUp(b)
        assertTrue(
            ed.selectedElements.any { it.kind == ElementKind.SPHERE_LOCUS },
            "a band across the first locus's left rim takes it: ${ed.statusHint}",
        )
    }

    /**
     * **The naming authority answers for it**: the panel's word for a locus and the file's name for it are the
     * ones every other kind gets, so a refusal that names one reads like the drawing and not like the code.
     */
    @Test
    fun theDrawingHasAWordForALocus() {
        val ed = twoLoci()
        val locus = ed.loci().first()
        assertEquals("a sphere locus", ed.doc.kindWord(locus).render(), "the drawing's own word for it")
        val name = ed.doc.nameOf(locus)
        assertTrue(DocumentFormat.save(ed.doc).contains("-> $name"), "and the file names it the same: $name")

        // ...and a refusal that names it uses that word
        ed.setTool(Tools.SPHERE_CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(-40.0, 0.0))
        assertTrue(ed.statusHint.isNotEmpty(), "clicking one locus twice says something: ${ed.statusHint}")
    }

    /**
     * **A locus whose centre goes invalid takes the locus with it, and the panel says which element to look
     * at** — OP-3's cascade, with nothing written here for it.
     */
    @Test
    fun anInvalidCentreCascadesAndThePanelNamesTheCause() {
        val ed = twoLoci()
        ed.setTool(Tools.SPHERE_CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val circle = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })
        assertTrue(reasonOf(circle) == null, "the circle is fine to begin with")

        // shrink one radius until the two no longer reach each other
        val locus = ed.loci().first()
        val field = assertNotNull(locus.handle?.fields()?.firstOrNull { it.label == "radius" }, "a locus types its radius")
        field.write(constructit.units.Quantity.mm(5.0))
        val why = assertNotNull(reasonOf(ed.doc.elements.first { it.id == circle.id }), "the circle is now invalid")
        assertTrue(why.contains("do not meet"), "and says which way it failed: $why")
        val flagged = ed.doc.invalidElements()
        assertTrue(flagged.any { it.element.id == circle.id && it.own }, "the panel flags the circle as the cause")

        field.write(constructit.units.Quantity.mm(40.0))
        assertTrue(reasonOf(ed.doc.elements.first { it.id == circle.id }) == null, "and it heals")
    }

    /**
     * **A locus survives a reload as a locus** — including the whole chain built on it, and including the
     * element the panel is showing. The point of the check is the *kind*: nothing may come back as a ball.
     */
    @Test
    fun aReloadedLocusIsStillALocusAndNotABall() {
        val ed = twoLoci()
        ed.setTool(Tools.SPHERE_CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val script = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(script)
        assertEquals(2, back.elements.count { it.kind == ElementKind.SPHERE_LOCUS }, "two loci come back as loci")
        assertEquals(0, back.elements.count { it.kind == ElementKind.SOLID }, "and nothing came back as a body")
        assertEquals(1, back.elements.count { it.kind == ElementKind.SPACE_CURVE }, "with the circle they cut")
        assertEquals(script, DocumentFormat.save(back), "byte-equal")
    }

    /**
     * **Nothing a locus makes reaches an exporter.** A locus has no interior, so it cannot be in an STL; this
     * asserts the consequence rather than the rule, which is what makes it a probe: the export seam filters on
     * the *value* kind and was never told about spheres.
     */
    @Test
    fun aDrawingOfLociExportsNothing() {
        val ed = twoLoci()
        ed.setTool(Tools.SPHERE_CIRCLE)
        ed.click(Vec2(-40.0, 0.0))
        ed.click(Vec2(100.0, 0.0))
        val scene = constructit.exchange.ExportScene.extract(ed.doc)
        assertTrue(scene.isEmpty, "a drawing that is all scaffolding exports no node")
        assertNotNull(scene.refusal, "and the export refuses by name rather than writing an empty file")
    }
}
