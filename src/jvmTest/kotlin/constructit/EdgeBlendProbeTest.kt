package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.FaceName
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Section3
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Probe review of slice 2 of the session-71 edge-blend package — compositions the delivery never saw.
 *
 * The two questions: does the feature chain stay **one chain when an ordinary boolean stands between two
 * blends** — a fillet, then a union with a second pad, then a chamfer must all land on one body, because a
 * sequential cut that resurrects the sharp corner or ignores the pad has forked the chain (the recorded
 * probe classic); and does the **face-chain gesture speak** when the chain contains a piece the blend
 * refuses — a revolve cap whose profile was 2D-filleted carries a torus band, and nothing may decline it
 * silently.
 */
class EdgeBlendProbeTest {
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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    private fun volumeOf(el: Element): Double {
        val mesh = meshOf(el)
        assertManifold(mesh, "a probed body")
        return Geom3.volume(mesh)
    }

    private fun roundTrips(
        ed: Editor,
        msg: String,
    ): String {
        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), msg)
        return once
    }

    /**
     * **Fillet → union → chamfer is one body, not three readings of one.** A 40 × 30 × 20 plate takes a
     * quarter-round on its rim piece along `x = 0`; a 25 × 50 pad overlapping it fuses on; then a chamfer on
     * the plate's rim piece along `y = 0` must cut the fused body — the chamfered result carries the fillet
     * AND the pad, and its numbers say so. A fork — the chamfer landing on the pre-union plate, or the union
     * resurrecting the sharp corner — cannot pass the arithmetic.
     */
    @Test
    fun aChamferAfterAUnionAfterAFilletIsStillOneChain() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        val plate = ed.solids().single()
        assertClose(volumeOf(plate), 24000.0, tol = 1.0, msg = "the plate")

        // the fillet, r = 4, on the rim piece along x = 0 (length 30)
        ed.activeScalar = ed.doc.newParameter("r", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(0.0, 15.0))
        assertEquals(2, ed.solids().size, "the fillet built one body: ${ed.statusHint}")
        val filleted = ed.solids().last()
        val removed1 = 24000.0 - volumeOf(filleted)
        val quarterRound = (1 - PI / 4) * 4.0 * 4.0 * 30.0
        assertTrue(
            removed1 in quarterRound..(quarterRound * 1.25),
            "the quarter-round came off within the chord model's band: $removed1 vs $quarterRound",
        )

        // the pad, overlapping the plate's right end, fused on
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, -10.0))
        ed.click(Vec2(60.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, -10.0))
        assertEquals(3, ed.solids().size, "the pad stands alone first: ${ed.statusHint}")
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        assertEquals(4, ed.solids().size, "the union is one new body: ${ed.statusHint}")
        val union = ed.solids().last()
        // plate 24000 − fillet + pad 25000 − overlap 5·30·20
        val unionExpected = 24000.0 - removed1 + 25000.0 - 3000.0
        assertClose(volumeOf(union), unionExpected, tol = 25.0, msg = "the fused body keeps the fillet")

        // the chamfer, c = 5, on the plate's rim piece along y = 0 (length 40) — after the union
        ed.activeScalar = ed.doc.newParameter("c", 5.0.mm)
        ed.setTool(Tools.CHAMFER_EDGE)
        ed.click(Vec2(15.0, 0.0))
        val final = ed.solids().last()
        val removed2 = volumeOf(union) - volumeOf(final)
        // c²L/2 = 500, less the 14.10 mm³ the fillet already took where the two rims share the corner at
        // (0, 0): ∫₀⁴ (4 − √(8d − d²))·(5 − d) dd — a shared vertex is never double-counted
        assertClose(
            removed2,
            485.90,
            tol = 2.0,
            msg = "the chamfer cut the body as it stands — pad, fillet and all: ${ed.statusHint}",
        )
        assertTrue(
            volumeOf(final) > unionExpected - 550.0,
            "nothing forked: a chamfer of the pre-union plate would read ~${24000.0 - removed1 - 500.0}",
        )

        roundTrips(ed, "the whole chain — fillet, union, chamfer — round-trips byte-equal")
    }

    /**
     * **A face chain containing a piece the blend refuses must speak.** The profile corner is 2D-filleted
     * before the revolve, so the cap boundary carries an arc piece whose band is a **torus** — the recorded
     * cut. One click on "Fillet the edges of a face" over that cap may refuse whole or break what it honestly
     * can, but either way the status line names the piece it would not take, and whatever body exists is
     * watertight and survives its file.
     */
    @Test
    fun aFaceChainWithARefusablePieceSaysWhatItWouldNotTake() {
        val ed = Editor()
        // hand-drawn segments, so the outline tracer owns the loop and the fillet joint is a handover (OP-14)
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(60.0, 15.0))
        ed.click(Vec2(60.0, 15.0))
        ed.click(Vec2(60.0, 25.0))
        ed.click(Vec2(60.0, 25.0))
        ed.click(Vec2(0.0, 25.0))
        ed.click(Vec2(0.0, 25.0))
        ed.click(Vec2(0.0, 15.0))
        ed.activeScalar = ed.doc.newParameter("f", 3.0.mm)
        ed.setTool(Tools.FILLET)
        ed.click(Vec2(55.0, 25.0))
        ed.click(Vec2(60.0, 20.0))
        assertTrue(
            ed.doc.elements.any { it.kind == ElementKind.ARC },
            "the 2D fillet took before the revolve: ${ed.statusHint}",
        )
        // the rounded shoulder first, then its neighbour — follow takes the rest (the OP-14 idiom)
        ed.setTool(Tools.OUTLINE)
        ed.click(Vec2(59.12, 24.12))
        ed.click(Vec2(30.0, 25.0))
        ed.key("Enter")
        assertEquals(
            1,
            ed.doc.elements.count { it.kind == ElementKind.OUTLINE },
            "auto-closed from two picks: ${ed.statusHint}",
        )
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(30.0, 15.0))
        ed.click(Vec2(10.0, 0.0))
        assertEquals(1, ed.solids().size, "the quarter ring with a rounded shoulder: ${ed.statusHint}")
        val ring = ed.solids().single()
        val before = volumeOf(ring)

        // ground truth from slice 1: the rounded shoulder rides into the cap, so the cap names 5 edges
        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(ring.ref as SolidRef).feature
        val (capEdges, whyCap) = Section3.edgesOfFace(feature, FaceName.RevolveCap(SolidFace.BOTTOM))
        assertEquals(null, whyCap, "the cap names its edges")
        assertEquals(5, assertNotNull(capEdges).size, "four straights and the fillet arc")

        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 25.0))

        val said = assertNotNull(ed.statusHint, "the gesture spoke")
        assertTrue(
            listOf("torus", "curved", "conic").any { it in said },
            "and it names what it would not take: $said",
        )
        for (s in ed.solids()) {
            assertManifold(meshOf(s), "every body stands watertight after the gesture")
        }
        val after = volumeOf(ed.solids().last())
        assertTrue(after <= before, "whatever was taken was taken, never added: $after vs $before")

        roundTrips(ed, "the drawing survives its file, refused piece and all")
    }
}
