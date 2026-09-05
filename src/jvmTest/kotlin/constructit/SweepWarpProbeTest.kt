package constructit

import constructit.SectionFamilyFixture.Rect
import constructit.SectionFamilyFixture.click
import constructit.SectionFamilyFixture.meshOf
import constructit.SectionFamilyFixture.midOf
import constructit.SectionFamilyFixture.solids
import constructit.SectionFamilyFixture.straightRun
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Orchestrator's probe of the twist-warp refinement** with fixtures it was not written against: a twist composed
 * with the rigid size law, a twist law on a function family, and the mesh's determinism through the file.
 */
class SweepWarpProbeTest {
    private fun Editor.selectAt(where: Vec2) {
        setTool(Tools.SELECT)
        click(where)
    }

    /** A 30 x 10 rectangle swept 200 mm with the stated laws; returns the body. */
    private fun swept(
        vararg laws: Pair<String, String>,
        rigid: String? = null,
    ): Pair<Editor, Element> {
        val ed = Editor()
        val rect = Rect(ed, 30.0, 10.0)
        straightRun(ed, 200.0)
        ed.selectAt(rect.pick())
        for ((name, text) in laws) assertTrue(ed.setFamilyLaw(name, text), "$name = $text: ${ed.statusHint}")
        if (rigid != null) assertTrue(ed.setSectionLaw(rigid), "scale(t) = $rigid: ${ed.statusHint}")
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(200.0))
        ed.click(rect.pick())
        return ed to assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
    }

    /** Twist and a rigid scale together: V = A·L·∫(1 − t/2)² dt = 300·200·7/12 = 35 000, whatever the twist. */
    @Test
    fun aTwistUnderARigidScaleKeepsCavalierisVolume() {
        val (_, body) = swept("twist" to "45deg * t", rigid = "1 - t/2")
        val m = meshOf(body)
        assertManifold(m, "twisted, scaled")
        val v = Geom3.volume(m)
        assertClose(v / 35000.0, 1.0, tol = 2e-3, msg = "A·L·∫(1−t/2)²: $v")
        assertTrue(v <= 35000.0 + 1e-6, "the chords never add volume: $v")
    }

    /** A twist law on a function family: V = ∫ w(t)·h dt · L = 30·0.75·10·200 = 45 000. */
    @Test
    fun aTwistedFamilyHasTheVolumeItsLawsState() {
        val (ed, body) = swept("w" to "30mm * (1 - 0.5*t)", "twist" to "90deg * t")
        val m = meshOf(body)
        assertManifold(m, "twisted family")
        val v = Geom3.volume(m)
        assertClose(v / 45000.0, 1.0, tol = 2e-3, msg = "∫w·h·L: $v")
        // the same body from the file, bit for bit
        val saved = DocumentFormat.save(ed.doc)
        val again = DocumentFormat.load(saved)
        assertEquals(saved, DocumentFormat.save(again), "round trip")
        val back = again.elements.last { it === again.elements.last { e -> e.kind == constructit.editor.ElementKind.SOLID } }
        val m2 = meshOf(back)
        assertEquals(m.vertices, m2.vertices, "the reloaded twisted family has the same vertices")
        assertEquals(m.triangles, m2.triangles, "and the same triangles")
    }

    /** An untwisted sweep with a rigid law is unchanged in shape and exact: 300·200·7/12 with no turn at all. */
    @Test
    fun noTurnMeansNoExtraStations() {
        val (_, body) = swept(rigid = "1 - t/2")
        val m = meshOf(body)
        assertManifold(m, "scaled, untwisted")
        val (_, turned) = swept("twist" to "45deg * t", rigid = "1 - t/2")
        assertTrue(meshOf(turned).vertices.size > 4 * m.vertices.size, "the turn asks for many more stations than the scale alone: ${m.vertices.size} vs ${meshOf(turned).vertices.size}")
        assertClose(Geom3.volume(m) / 35000.0, 1.0, tol = 1e-9, msg = "a linear scale of a polygon is exact on two rings: ${Geom3.volume(m)}")
    }
}
