package constructit

import constructit.SectionFamilyFixture.Rect
import constructit.SectionFamilyFixture.click
import constructit.SectionFamilyFixture.meshOf
import constructit.SectionFamilyFixture.midOf
import constructit.SectionFamilyFixture.solids
import constructit.SectionFamilyFixture.straightRun
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.exchange.Stl
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshQuality
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.geom.Xform3
import constructit.geom.movedBy
import constructit.units.mm
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The function-family section composed with what was already there** (OP-26, session 79).
 *
 * The claim the design's F7 makes is that a family travels as **values**: `SweepProfile.Family` is samples and
 * rings and nothing else, so a placement, a plan hint, a boolean and an export all work with no 2D subgraph
 * to re-enter (OP-9's self-contained feature). That is a claim about *other* features, so it is asserted
 * against them rather than against the family's own arithmetic.
 */
class SectionFamilyProbeTest {
    private fun solidOf(el: Element) =
        @Suppress("UNCHECKED_CAST")
        Evaluator().solid(el.ref as SolidRef)

    /** A 20 × 10 rectangle swept 100 mm with one law on its width — the probes' shared body. */
    private fun tapered(ed: Editor): Element {
        val rect = Rect(ed, 20.0, 10.0)
        straightRun(ed, 100.0)
        ed.setTool(Tools.SELECT)
        ed.click(rect.pick())
        assertTrue(ed.setFamilyLaw("w", "20mm * (1 - 0.5*t)"), ed.statusHint)
        ed.setTool(Tools.SWEEP)
        ed.click(midOf(100.0))
        ed.click(rect.pick())
        val body = assertNotNull(ed.solids().lastOrNull(), "the sweep was built: ${ed.statusHint}")
        // …and the row is **cleared**, exactly as a user clears it: an armed law is a tool option and
        // survives `resetPicks`, so leaving it armed makes the *next* tool refuse by name (which is the
        // recorded behaviour of the rigid law too, and is asserted in SectionFamilyToolTest)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(-600.0, 600.0))
        assertTrue(ed.setFamilyLaw("w", ""), ed.statusHint)
        return body
    }

    /**
     * **A placed family is still the same body, and its plan hint is still its own taper.**
     *
     * The placement turns the mesh (a family has one level, so it is turned once), and the *feature* travels
     * with it — path, profile and twist law alike — so the footprint hint can be re-derived on the other side
     * of the motion from the family's own values, with nothing to re-evaluate.
     */
    @Test
    fun aPlacedFamilyKeepsItsVolumeAndItsOwnPlanHint() {
        val ed = Editor()
        val body = tapered(ed)
        val live = solidOf(body)
        assertManifold(live.mesh, "the family before the motion")
        val was = Geom3.volume(live.mesh)

        val a = 0.7
        val turn =
            Xform3.frame(
                Vec3(40.0, -25.0, 12.0),
                Vec3(cos(a), sin(a), 0.0),
                Vec3(-sin(a), cos(a), 0.0),
                Vec3(0.0, 0.0, 1.0),
            )
        val (moved, why) = live.movedBy(turn)
        assertNotNull(moved, "a family can be placed: $why")
        assertManifold(moved.mesh, "the placed family")
        assertClose(Geom3.volume(moved.mesh), was, 1e-6, msg = "a rigid motion changes no volume")

        val feature = assertNotNull(moved.feature as? Feature3.Sweep, "the placed body is still a sweep")
        val hint = Geom3.sweptPlan(feature, Plane3(Vec3.ZERO, Vec3.X, Vec3.Y))
        assertTrue(hint.isNotEmpty(), "and its plan hint is re-derived from the family's own values")

        // …and a family has **one** mesh level on the far side of the motion too, for its own reason
        assertTrue(
            moved.meshAt(MeshQuality.COARSE) === moved.meshAt(MeshQuality.FINE),
            "the placed family has one level, exactly as the family it was placed from",
        )
    }

    /**
     * **A family is an ordinary boolean operand.** A block with the tapered body taken out of it is
     * watertight and the volume it loses is the volume the family states — which is what *"an ordinary solid,
     * with everything that implies"* has to mean for the newest kind of section too.
     *
     * The block is built **first**, so the click at the run's own middle finds the family (the later element
     * wins where two footprints overlap) and the click at the block's far edge finds the block.
     */
    @Test
    fun aFamilyIsAnOrdinaryBooleanOperand() {
        val ed = Editor()

        // a block the whole run passes through, drawn well clear of the section's own sketch
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-20.0, -350.0))
        ed.click(Vec2(120.0, -270.0))
        ed.activeScalar = ed.doc.newParameter("depth", 60.0.mm)
        ed.setTool(Tools.EXTRUDE)
        // an ortho rectangle is extruded by clicking its **boundary**, not its inside
        ed.click(Vec2(60.0, -350.0))
        val block = assertNotNull(ed.solids().lastOrNull(), "the block was built: ${ed.statusHint}")
        val blockVolume = Geom3.volume(meshOf(block))

        val body = tapered(ed)
        val taperedVolume = Geom3.volume(meshOf(body))

        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(60.0, -350.0))
        // …and the family, where the canvas draws it: the run's own middle
        ed.click(midOf(100.0))
        val cut =
            assertNotNull(
                ed.solids().lastOrNull { it !== body && it !== block },
                "the subtraction was built: ${ed.statusHint}",
            )
        assertManifold(meshOf(cut), "a block with a tapered family taken out of it")
        assertClose(
            Geom3.volume(meshOf(cut)),
            blockVolume - taperedVolume,
            1e-3,
            msg = "the block loses exactly the family's own volume",
        )
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "and the whole thing round-trips")
    }

    /**
     * **A family reaches the export seam like any other solid.** `ExportScene` sees a mesh and nothing else,
     * so what an exporter writes has the family's own triangles in it — one facet of fifty bytes each, after
     * the eighty-four an STL header and count take.
     */
    @Test
    fun aFamilyReachesTheExportSeamLikeAnyOtherSolid() {
        val ed = Editor()
        val body = tapered(ed)
        val scene = ExportScene.extract(ed.doc)
        assertTrue(scene.nodes.isNotEmpty(), "the family is a node of the exported scene")
        val bytes = Stl.write(scene)
        val triangles = meshOf(body).triangles.size
        assertEquals(triangles, scene.triangleCount, "and the scene counts the family's own triangles")
        assertEquals(84 + 50 * triangles, bytes.size, "the STL carries the family's own triangles and nothing else")
    }
}
