package constructit

import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes of the graph format (OP-32) composed with what was there before it: a file whose identities run against
 * its references, and a re-stamp (OP-23), which is the one load that creates more or fewer elements than the file
 * declares.
 */
class GraphFormatProbeTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    /**
     * **References decide the order, not identities.** A point renumbered past the segment that reads it — the
     * shape a file takes once a refactoring has rerouted a feature onto something newer than itself — still loads
     * first, and is the same drawing.
     */
    @Test
    fun aFeatureNewerThanWhatReadsItIsStillBuiltFirst() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        ed.activeScalar = ed.doc.newParameter("r", 7.0.mm)
        ed.setTool(Tools.CIRCLE_R)
        ed.click(Vec2(40.0, 10.0))
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 10.0))
        val json = DocumentFormat.save(ed.doc)
        val first = saved(json).kind("point").first().str("id")
        // the first point and the parameter both take identities newer than everything that reads them
        val moved =
            mapFeatures(json) { if (it.str("id") == first) it.with("id", "\"f90\"") else it }
                .replace("\"f${ed.doc.scalars.single().serial}\"", "\"f91\"")
        val back = DocumentFormat.load(moved)
        assertSameDrawing(ed.doc, back, what = "renumbered")
        val again = DocumentFormat.save(back)
        assertTrue(saved(again).features.any { it.str("id") == "f90" }, "the identities are the file's: $again")
        assertEquals("""{"e":5,"f":92}""", saved(again).root["nextId"].toString(), "and the high-water mark is past them")
        assertEquals(again, DocumentFormat.save(DocumentFormat.load(again)), "a fixed point from there")
    }

    private fun ring(
        k: Int,
        n: Int,
    ): Vec2 = Vec2(100.0 * cos(2 * PI * k / n), 100.0 * sin(2 * PI * k / n))

    /**
     * **A re-stamp keeps every identity it can and spends new ones for the rest** (OP-23 × OP-32): the members a
     * bigger count adds take numbers nothing ever had, the ones a smaller count removes stay retired, and a member
     * that survives keeps its name through both.
     */
    @Test
    fun aRestampKeepsTheIdentitiesItCanAndNeverReusesOne() {
        val ed = Editor()
        ed.count = 6
        ed.setTool(Tools.PATTERN_CIRCULAR)
        ed.click(Vec2(0.0, 0.0))
        ed.click(ring(0, 6))
        ed.setTool(Tools.SEGMENT)
        ed.click(ring(0, 6))
        ed.click(ring(1, 6))
        val pattern = ed.doc.patterns.single()
        val members6 = pattern.ring.members.map { ed.doc.nameOf(it) }
        val segments6 = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.map { ed.doc.nameOf(it) }
        val high6 = saved(ed.doc).root["nextId"].toString()

        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 8), ed.statusHint)
        val members8 = ed.doc.patterns.single().ring.members.map { ed.doc.nameOf(it) }
        assertEquals(members6, members8.take(6), "the six members keep their names")
        val ever = (members6 + segments6).map { it.drop(1).toInt() }.max()
        assertTrue(members8.drop(6).all { it.drop(1).toInt() > ever }, "the two new ones take numbers nothing had: $members8 after $members6")
        val segments8 = ed.doc.elements.filter { it.kind == ElementKind.SEGMENT }.map { ed.doc.nameOf(it) }
        assertEquals(segments6, segments8.take(6), "and so do the six sides: $segments8")
        assertTrue(high6 != saved(ed.doc).root["nextId"].toString(), "the high-water mark moved")
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "the re-stamped file is a fixed point")

        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 5), ed.statusHint)
        val members5 = ed.doc.patterns.single().ring.members.map { ed.doc.nameOf(it) }
        assertEquals(members6.take(5), members5, "the five that remain keep theirs")
        assertTrue(ed.setPatternCount(ed.doc.patterns.single(), 7), ed.statusHint)
        val members7 = ed.doc.patterns.single().ring.members.map { ed.doc.nameOf(it) }
        assertTrue(
            members7.drop(5).none { it in members6 + members8 },
            "growing again spends new numbers rather than the retired ones: $members7 against $members6 / $members8",
        )
        val last = DocumentFormat.save(ed.doc)
        assertEquals(last, DocumentFormat.save(DocumentFormat.load(last)))
    }
}
