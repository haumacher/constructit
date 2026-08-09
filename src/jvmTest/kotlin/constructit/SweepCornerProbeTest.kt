package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.Quantity
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The probe review of the corner-fold refusal** — the criterion composed with the roll, on the shape the
 * global term can never see.
 *
 * A triangle's three legs all touch, so it has no non-neighbouring pair for the clearance term to find a
 * bottleneck at: the corner check is the *only* thing standing between an inward-reaching section and a folded
 * band there. And the roll is what proves the check reads the section **as swept** rather than as drawn — the
 * same live half-turn that vetted the clearance term (session 59's probe) must flip a corner refusal into a
 * manifold body and back, on one node, with the roll the only thing moving (OP-3).
 */
class SweepCornerProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element) = Evaluator().solid(el.ref as SolidRef).mesh

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    private fun runElements(
        doc: Document,
        id: String,
        picks: List<Element>,
    ) = doc.runTool(
        assertNotNull(Tools.byId(id)),
        Picks(emptyList(), picks, Vec2(0.0, 0.0), picks.map { Vec2(0.0, 0.0) }),
        emptyList(),
    )

    @Test
    fun aCornerRefusalTurnsWithTheRollOnTheShapeTheClearanceCannotSee() {
        val doc = Document()
        val corners =
            listOf(
                doc.freePoint(0.0.mm, 0.0.mm),
                doc.freePoint(300.0.mm, 0.0.mm),
                doc.freePoint(150.0.mm, 260.0.mm),
            ).map { assertNotNull(doc.elementFor(it)) }
        runElements(doc, Tools.CURVE3, corners + corners.first())
        val run = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }

        // a vertical plane across the triangle at y = 130 — it crosses two legs, mid-leg, transversally
        val h1 = doc.freePoint((-100.0).mm, 130.0.mm)
        val h2 = doc.freePoint(400.0.mm, 130.0.mm)
        doc.runTool(
            assertNotNull(Tools.byId(Tools.LINE)),
            Picks(listOf(h1, h2), emptyList(), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0))),
            emptyList(),
        )
        val hinge = doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"), "the datum stands on the line")
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))

        // the crossing on the left leg, and which way "into the triangle" points in the plane's own u
        val plane = planeOf(doc, "cut")
        val a = plane.toLocal(Vec3(75.0, 130.0, 0.0))
        val s = if (plane.toLocal(Vec3(150.0, 130.0, 0.0)).x > a.x) 1.0 else -1.0

        // a section reaching 120 mm into the triangle and 5 mm out of it — the queue entry's own inward
        // stand-off, at which each far corner eats ~208 mm of a 300 mm leg
        val uIn = a.x + 120.0 * s
        val uOut = a.x - 5.0 * s
        val c1 = doc.freePoint(uOut.mm, 5.0.mm)
        val c2 = doc.freePoint(uIn.mm, 5.0.mm)
        val c3 = doc.freePoint(uIn.mm, 25.0.mm)
        val c4 = doc.freePoint(uOut.mm, 25.0.mm)
        for ((p, q) in listOf(c1 to c2, c2 to c3, c3 to c4, c4 to c1)) {
            doc.runTool(
                assertNotNull(Tools.byId(Tools.SEGMENT)),
                Picks(listOf(p, q), emptyList(), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0))),
                emptyList(),
            )
        }
        val sides = doc.elements.filter { it.kind == ElementKind.SEGMENT }.takeLast(4)
        runElements(doc, Tools.OUTLINE, sides)
        val section = doc.elements.last { it.kind == ElementKind.OUTLINE }

        // swept in place with the roll at zero: only the corners can refuse this — the legs all touch
        val roll = doc.newParameter("roll", Quantity.deg(0.0))
        val solid = assertNotNull(doc.sweepAlongCurve(run, section, roll.ref), "the sweep step is taken: ${doc.note}")
        val why = assertNotNull(whyInvalid(solid), "reaching 120 mm into a triangle, the mitres must fold and refuse")
        assertTrue(why.contains("corner"), "…named as the corner it is: $why")

        // a half-turn of the roll swings the reach out of the triangle, and the same node is a body
        doc.setParameter(roll, Quantity.deg(180.0))
        assertNull(whyInvalid(solid), "turned outward, the same node heals: ${whyInvalid(solid)}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the band riding outside the triangle")
        assertTrue(Geom3.volume(mesh) > 0.0, "…the right way out: ${Geom3.volume(mesh)}")

        // and back — the same refusal in the same words
        doc.setParameter(roll, Quantity.deg(0.0))
        assertEquals(why, whyInvalid(solid), "turned inward again, the corner speaks in the same words")
    }
}
