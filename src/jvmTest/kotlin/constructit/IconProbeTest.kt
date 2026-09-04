package constructit

import constructit.editor.Tools
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Probe review of the icons package (GitHub issue #22): with 129 glyphs built from shared bases
 * (`coil`, `cutBody`, the modifier marks), the likeliest silent failure is two tools rendering the
 * **identical** string — a family member whose modifier was forgotten, or a copy-paste that never
 * diverged. Distinctness is the palette's whole contract: a glyph that cannot be told from another
 * is worse than the text row it replaced.
 */
class IconProbeTest {
    @Test
    fun noTwoToolsShareAGlyph() {
        val byIcon = Tools.all.filter { it.icon != null }.groupBy { it.icon }
        val twins = byIcon.filterValues { it.size > 1 }.values.map { row -> row.map { it.id } }
        assertTrue(twins.isEmpty(), "these tools render the identical glyph and cannot be told apart: $twins")
    }
}
