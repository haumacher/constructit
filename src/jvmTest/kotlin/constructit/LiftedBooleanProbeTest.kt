package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.BoolOp
import constructit.geom.Geom3
import constructit.geom.MeshBool
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the lifted run, one op further** — the swept foundation as a boolean operand.
 *
 * The delivery proves the ring itself: on the ground, hugging the fillets, its drawing a true section. The
 * user's next gesture is as predictable as gravity — fuse the foundation with the pillar it hugs — and it is
 * also the adversarial case for a mesh boolean: the ring was constructed *in place*, so its inner face lies
 * **on** the pillar's side wall, coincident band to coincident wall, arcs against arcs. A union that survives
 * that and stays watertight is the composition the lift was built for.
 */
class LiftedBooleanProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun planOutline(doc: Document): Element = doc.elements.last { it.kind == ElementKind.OUTLINE && it.space == Document.PLAN_SPACE }

    private fun section(doc: Document): Element = doc.elements.last { it.kind == ElementKind.OUTLINE && it.space == "plane1" }

    @Test
    fun theFoundationFusesWithThePillarItHugs() {
        assumeTrue(MeshBool.available, "a union of a swept ring and a prism needs the general boolean engine (OP-9): ${MeshBool.status}")
        val doc = DocumentFormat.load(LiftedRunTest.ROUND_PILLAR_CIT)
        val pillar = doc.elements.first { it.kind == ElementKind.SOLID }
        doc.activeSpace = assertNotNull(doc.spaceNamed("plane1"))
        val ring = assertNotNull(doc.sweepAlongCurve(planOutline(doc), section(doc)), "the sweep the lift exists for: ${doc.note}")
        assertNull(whyInvalid(ring), "the ring is a body: ${doc.note}")
        val vPillar = Geom3.volume(meshOf(pillar))
        val vRing = Geom3.volume(meshOf(ring))
        assertTrue(vPillar > 0.0 && vRing > 0.0, "both operands the right way out: $vPillar, $vRing")

        val union = assertNotNull(doc.combineSolids(pillar, ring, BoolOp.UNION), "the union is taken: ${doc.note}")
        assertNull(whyInvalid(union), "…and it builds: ${doc.note}")
        val mesh = meshOf(union)
        assertManifold(mesh, "pillar and foundation fused along their shared wall")
        val vUnion = Geom3.volume(mesh)
        // the ring stands against the wall, so the two bodies share a face and next to no interior: the union
        // holds within a band that catches a lost operand at one end and double-counted material at the other
        assertTrue(vUnion > vPillar && vUnion > vRing, "nothing was lost: $vUnion of $vPillar + $vRing")
        assertTrue(vUnion <= (vPillar + vRing) * 1.01, "nothing was counted twice: $vUnion of ${vPillar + vRing}")
        assertTrue(vUnion >= (vPillar + vRing) * 0.90, "…and the overlap is the sliver it should be: $vUnion")
    }
}
