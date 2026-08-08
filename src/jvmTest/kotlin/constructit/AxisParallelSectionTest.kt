package constructit

import constructit.core.Evaluator
import constructit.editor.DocumentFormat
import constructit.geom.ProfileElement
import constructit.geom.Vec2
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A plane parallel to an extrusion's axis draws the curved side it cuts** (OP-17's context, OP-15's
 * exactness — the user's report of session 55): a vertical working plane through a column showed the
 * column's top and bottom chords and *not its sides*, because the axis-parallel cut of a cylindrical face
 * fell between the perpendicular reading (an arc), the inclined reading (an ellipse) and the sampled strip
 * (whose rulings are all parallel to such a plane, so no crossing is transverse). The section is exact: one
 * upright ruling per crossing of the plane's trace with the boundary piece.
 */
class AxisParallelSectionTest {
    /** The user's script, verbatim — a column (extruded circle) and a vertical plane through its axis. */
    private val COLUMN_CIT = """constructit 2
point -15.607700000000058,18.169875000000026 -> e1
point 35.70620318639833,21.673418033372624 -> e2
tool circle pts=e1,e2 clicks=-21.39369681360091,17.64714303337267;31.679928186398378,43.26889303337233 -> e3
tool makerel els=e2,e1 clicks=29.8498031863984,43.63491803337232;-20.66164681360092,19.111243033372652 dofs=51.433369265582435mm;3.9059038349535347deg
param "h" = 200mm
tool extrude els=e3 clicks=-51.04172181360052,-27.00790696662673 scalar="h" -> e4
tool line pts=e1,e2 clicks=-18.46549681360095,16.18304303337269;36.80427818639831,22.03944303337261 -> e5
param "angle" = 90deg
sketchspace "plane1" line=e5 angle="angle"
"""

    private fun segs(pieces: List<ProfileElement>): List<Pair<Vec2, Vec2>> =
        pieces.filterIsInstance<ProfileElement.Seg>().map { it.segment.a to it.segment.b }

    /**
     * **The column shows all four sides of its section.** The plane passes through the axis, so the section
     * is the full rectangle: the two cap chords the drawing already had, and the two uprights this fix adds —
     * standing exactly at the chord's own ends, 200 mm tall, and exact (no piece is chords-for-a-curve).
     */
    @Test
    fun aVerticalPlaneThroughAColumnDrawsItsSides() {
        val doc = DocumentFormat.load(COLUMN_CIT)
        assertEquals(emptyList(), doc.loadNotes, "the fixture loads clean")
        val plane1 = assertNotNull(doc.spaceNamed("plane1"))
        val ev = Evaluator()
        val sections = doc.spaceSections(plane1, ev)
        assertEquals(1, sections.size, "the column is cut")
        val section = sections.single().second
        assertTrue(!section.approximated, "an axis-parallel cut of a cylinder is exact (OP-15)")

        val pieces = segs(section.drawn)
        assertEquals(4, pieces.size, "two chords and two sides: ${section.drawn}")
        val horizontals = pieces.filter { abs(it.first.y - it.second.y) < 1e-9 }
        val uprights = pieces.filter { abs(it.first.x - it.second.x) < 1e-9 }
        assertEquals(2, horizontals.size, "the two cap chords")
        assertEquals(2, uprights.size, "and the two sides of the column")

        // the sides stand exactly at the chord's ends and run the full height
        val chord = horizontals.first { abs(it.first.y) < 1e-9 }
        val ends = listOf(chord.first.x, chord.second.x).sorted()
        for (u in uprights) {
            val vs = listOf(u.first.y, u.second.y).sorted()
            assertClose(vs[0], 0.0, 1e-9, "a side starts on the base")
            assertClose(vs[1], 200.0, 1e-9, "and rises the extrusion's own height")
            assertTrue(ends.any { abs(it - u.first.x) < 1e-9 }, "standing at a chord end: ${u.first.x} vs $ends")
        }
        // and the chord is the diameter the trace through the centre implies
        assertClose(ends[1] - ends[0], 2.0 * 51.433369265582435, 1e-9, "the chord is a full diameter")
    }

    /**
     * **An off-centre vertical plane cuts the same column in a narrower rectangle** — the crossings are a
     * line against the circle, not a special-cased diameter: at 21.83 mm beside the centre the half-chord is
     * `√(r² − d²)`, and the sides stand exactly there.
     */
    @Test
    fun anOffCentreVerticalPlaneCutsTheChordItImplies() {
        val off = """constructit 2
point -15.607700000000058,18.169875000000026 -> e1
point 35.70620318639833,21.673418033372624 -> e2
tool circle pts=e1,e2 clicks=-21.39369681360091,17.64714303337267;31.679928186398378,43.26889303337233 -> e3
param "h" = 200mm
tool extrude els=e3 clicks=-51.04172181360052,-27.00790696662673 scalar="h" -> e4
point -100,40 -> e5
point 100,40 -> e6
tool line pts=e5,e6 clicks=-100,40;100,40 -> e7
param "angle" = 90deg
sketchspace "plane1" line=e7 angle="angle"
"""
        val doc = DocumentFormat.load(off)
        assertEquals(emptyList(), doc.loadNotes)
        val plane1 = assertNotNull(doc.spaceNamed("plane1"))
        val section = doc.spaceSections(plane1, Evaluator()).single().second
        val pieces = segs(section.drawn)
        assertEquals(4, pieces.size, "two chords and two sides: ${section.drawn}")
        val uprights = pieces.filter { abs(it.first.x - it.second.x) < 1e-9 }
        assertEquals(2, uprights.size)
        val r = 51.433369265582435
        val d = 40.0 - 18.169875000000026
        val half = sqrt(r * r - d * d)
        val xs = uprights.map { it.first.x }.sorted()
        assertClose(xs[1] - xs[0], 2.0 * half, 1e-9, "the sides stand a half-chord either side of the centre")
    }

    /** **A vertical plane that misses the column draws no section of it** — nothing, not a wrong something. */
    @Test
    fun aVerticalPlaneBesideTheColumnCutsNothing() {
        val miss = """constructit 2
point -15.607700000000058,18.169875000000026 -> e1
point 35.70620318639833,21.673418033372624 -> e2
tool circle pts=e1,e2 clicks=-21.39369681360091,17.64714303337267;31.679928186398378,43.26889303337233 -> e3
param "h" = 200mm
tool extrude els=e3 clicks=-51.04172181360052,-27.00790696662673 scalar="h" -> e4
point -100,120 -> e5
point 100,120 -> e6
tool line pts=e5,e6 clicks=-100,120;100,120 -> e7
param "angle" = 90deg
sketchspace "plane1" line=e7 angle="angle"
"""
        val doc = DocumentFormat.load(miss)
        val plane1 = assertNotNull(doc.spaceNamed("plane1"))
        assertEquals(0, doc.spaceSections(plane1, Evaluator()).size, "the plane misses the column")
    }
}
