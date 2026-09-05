package constructit

import constructit.core.Evaluator
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Format
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.l10n.L10n
import constructit.l10n.readDecimal
import constructit.units.Dimension
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of OP-29 slice 3** on what the delivery never saw: a dimension drawn in a German session
 * reads with a comma and the same annotation re-reads with a point; the panel's own spelling round-trips odd
 * figures (three decimals, a negative, an integer) in both languages and refuses the ambiguous ones; an area and a
 * volume carry their units in German; and the writer keeps the point on a drawing made entirely under `de`.
 */
class NumbersInLanguageProbeTest {
    @AfterTest
    fun english() {
        L10n.locale = "en"
    }

    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    @Test
    fun aDimensionDrawnInGermanReadsWithACommaAndWithAPointOffOneAnnotation() {
        L10n.locale = "de"
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(12.5, 0.0))
        val pts = ed.doc.elements.filter { it.kind == ElementKind.POINT }
        assertEquals(2, pts.size, ed.statusHint)
        ed.setTool(Tools.DIM_LINEAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(12.5, 0.0))
        ed.click(Vec2(6.0, 5.0))
        val dim = ed.doc.elements.last { it.annotation != null }
        val de = dim.annotation!!.label(Evaluator())
        L10n.locale = "en"
        val en = dim.annotation!!.label(Evaluator())
        assertTrue("12,5" in de && "mm" in de, "German reads a comma: $de")
        assertTrue("12.5" in en, "English a point: $en")
        // the file carries the point whatever the session speaks
        val saved = DocumentFormat.save(ed.doc)
        assertTrue("12.5" in saved && "12,5" !in saved, "the file is format: $saved")
        assertEquals(saved, DocumentFormat.save(DocumentFormat.load(saved)), "and a fixed point")
    }

    @Test
    fun thePanelsSpellingRoundTripsOddFiguresAndRefusesTheAmbiguousOnes() {
        for ((locale, sep) in listOf("de" to ',', "en" to '.')) {
            for (x in listOf(0.125, -3.5, 7.0, 1234.75)) {
                val shown = Format.display(x, locale)
                assertTrue(shown.count { it == sep } <= 1 && shown.none { it == (if (sep == ',') '.' else ',') }, "$locale shows $x as $shown")
                assertEquals(x, Format.read(shown, locale), "$locale reads its own spelling back: $shown")
            }
            // grouping is never written, so a grouped figure is refused rather than guessed
            assertNull(Format.read("1.234,5", locale), "$locale refuses a grouped figure")
            assertNull(Format.read(if (locale == "de") "1.5" else "1,5", locale), "$locale refuses the other separator")
        }
        assertEquals(2.5, readDecimal("2,5", ','))
        assertNull(readDecimal("2,5", '.'))
    }

    @Test
    fun anAreaAndAVolumeCarryTheirUnitsInGerman() {
        val area = Format.quantityMsg(Quantity(1234.5, Dimension.AREA))
        val volume = Format.quantityMsg(Quantity(0.25, Dimension.VOLUME))
        val de = area.render("de")
        val en = area.render("en")
        assertTrue("1234,5" in de, "a German area: $de")
        assertTrue("1234.5" in en, "an English area: $en")
        assertTrue(de.substringAfter("1234,5").isNotBlank(), "with a unit after it: $de")
        assertTrue("0,25" in volume.render("de") && "0.25" in volume.render("en"), "a volume both ways: ${volume.render("de")} / ${volume.render("en")}")
        assertTrue('{' !in de && '{' !in volume.render("de"), "every placeholder rendered")
    }

    @Test
    fun aParameterTypedWithACommaDrivesTheGeometry() {
        L10n.locale = "de"
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(30.0, 20.0))
        val d = ed.doc.newParameter("d", 1.0.mm)
        val typed = Format.read("7,5", "de")!!
        ed.doc.setParameter(d, typed.mm)
        ed.activeScalar = d
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(15.0, 0.0))
        val solid = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val mesh = Evaluator().solid(solid.ref as constructit.dsl.SolidRef).mesh
        assertEquals(7.5, mesh.vertices.maxOf { it.z }, 1e-9, "the typed comma became 7.5 mm of depth")
        assertTrue("7.5mm" in DocumentFormat.save(ed.doc), "and the file says 7.5mm")
        assertTrue("7,5" in Format.display(7.5, "de"), "while the panel shows 7,5")
    }
}
