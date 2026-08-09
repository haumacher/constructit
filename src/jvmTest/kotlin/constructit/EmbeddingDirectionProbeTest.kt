package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Picks
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Mesh3
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
 * **The probe review of the directional clearance** (OP-9/OP-26, session 59) — the support-function criterion
 * composed with what already existed.
 *
 * The delivery's own suite proves the overlap-interval bottleneck and the direction-aware clearance against
 * purpose-built loops. These ask two harder questions. First, the user's second report, verbatim: a foundation
 * profile *edited taller* made the swept body vanish, because a reach measured in every direction charged the
 * profile's height against a clearance that only its width approaches — the fixture is their exact script and
 * must simply build. Second, whether the criterion reads the section **as swept rather than as drawn**: a roll
 * is a parameter, so a section drawn reaching inward refuses, must *heal* when the roll turns it outward, and
 * must refuse again when the roll comes back — the same node all along (OP-3).
 */
class EmbeddingDirectionProbeTest {
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

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

    // ---- 1. the user's report: an outline edited taller keeps its body ----

    /**
     * **The user's second script, verbatim** — the first one's foundation profile edited taller (`e22` raised
     * from 10.055 mm to 22.138 mm), which made the body vanish: the profile then reached 24.938 mm *in its
     * tallest direction*, and a clearance of 45.181 mm between the border's legs was charged 2 × 24.938 as if
     * the section were a ball. Height does not approach a leg that passes beside you; under the
     * support-function clearance this drawing builds, stands on the ground, and round-trips byte-equal.
     */
    @Test
    fun theTallerFoundationKeepsItsBody() {
        val doc = DocumentFormat.load(TALLER_CIT)
        assertTrue(doc.loadNotes.isEmpty(), "nothing about the file is ambiguous: ${doc.loadNotes}")
        val solid = doc.elements.last { it.kind == ElementKind.SOLID }
        assertNull(whyInvalid(solid), "the taller foundation is a body again")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the taller foundation")
        assertTrue(Geom3.volume(mesh) > 0.0, "…the right way out")
        assertClose(mesh.vertices.minOf { it.z }, 0.0, 1e-9, "sitting on the ground, where it was drawn")
        assertEquals(atThisVersion(TALLER_CIT), DocumentFormat.save(doc), "save -> load -> save is byte-equal")
    }

    // ---- 2. the criterion reads the section as swept, not as drawn ----

    /**
     * **A roll turns the section's reach with it, and the clearance follows** — refuse, heal, refuse again on
     * one node, with the roll as the only thing moving (OP-3).
     *
     * A 200 × 100 plan loop's long legs pass 100 mm apart. The section reaches 55 mm *inward* and 5 mm outward,
     * so riding both legs it asks 110 mm of a 100 mm gap and is refused by name. A half-turn of the roll swaps
     * the two reaches — 5 inward, 55 outward, asking 10 — and the same node is a manifold body; turning the
     * roll back refuses again in the same words. A criterion that read the *drawn* section instead of the
     * *rolled* one would get every step of this wrong.
     */
    @Test
    fun aRollTurnsTheReachAndTheClearanceFollows() {
        val doc = Document()
        val corners =
            listOf(
                doc.freePoint(0.0.mm, 0.0.mm),
                doc.freePoint(200.0.mm, 0.0.mm),
                doc.freePoint(200.0.mm, 100.0.mm),
                doc.freePoint(0.0.mm, 100.0.mm),
            ).map { assertNotNull(doc.elementFor(it)) }
        runElements(doc, Tools.CURVE3, corners + corners.first())
        val run = doc.elements.last { it.kind == ElementKind.SPACE_CURVE }

        val h1 = doc.freePoint(100.0.mm, (-80.0).mm)
        val h2 = doc.freePoint(100.0.mm, 180.0.mm)
        doc.runTool(
            assertNotNull(Tools.byId(Tools.LINE)),
            Picks(listOf(h1, h2), emptyList(), Vec2(0.0, 0.0), listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0))),
            emptyList(),
        )
        val hinge = doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(doc.createDatumSpace(hinge, null, "cut"), "the datum stands on the line")
        doc.activeSpace = assertNotNull(doc.spaceNamed("cut"))

        // the crossing at the loop's bottom leg, and which way "into the loop" points in the plane's own u
        val plane = planeOf(doc, "cut")
        val a = plane.toLocal(Vec3(100.0, 0.0, 0.0))
        val s = if (plane.toLocal(Vec3(100.0, 50.0, 0.0)).x > a.x) 1.0 else -1.0

        // a section reaching 55 mm inward and 5 mm outward, drawn beside that crossing, 5…25 mm up
        val uIn = a.x + 55.0 * s
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

        // swept with the roll at zero: riding both legs it asks 110 mm of a 100 mm gap
        val roll = doc.newParameter("roll", Quantity.deg(0.0))
        val solid = assertNotNull(doc.sweepAlongCurve(run, section, roll.ref), "the sweep step is taken: ${doc.note}")
        val why = assertNotNull(whyInvalid(solid), "reaching 55 mm inward, the ring folds and must refuse")
        assertTrue(why.contains("cut into itself"), "…by name: $why")

        // a half-turn of the roll swaps the reaches, and the same node is a body
        doc.setParameter(roll, Quantity.deg(180.0))
        assertNull(whyInvalid(solid), "turned outward, the same node heals")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the ring reaching outward")
        assertTrue(Geom3.volume(mesh) > 0.0, "…the right way out: ${Geom3.volume(mesh)}")

        // and back — the refusal returns in the same words
        doc.setParameter(roll, Quantity.deg(0.0))
        assertEquals(why, whyInvalid(solid), "turned inward again, the same refusal in the same words")
    }

    companion object {
        val TALLER_CIT =
            """
            constructit 2
            orthostart -36,21.680818540354267 -> e1
            orthovertex 15.395828291524595,21.680818540354267 -> e2,e3
            orthovertex 15.395828291524595,-23.5 -> e4,e5
            orthovertex -36,-23.5 -> e6,e7
            orthoclose -> e8
            param "r" = 200mm
            tool extrude els=e7 clicks=-6.75,-23.5 scalar="r" -> e9
            tool perpbis pts=e1,e2 clicks=-35,32.25;22.25,32.25 -> e10
            param "angle" = 90deg
            sketchspace "plane1" line=e10 angle="angle"
            sectioninput "plane1" el=e9 edge=3 -> e11
            tool keypoints els=e11 clicks=31.667210440456785,44.143556280587276 -> e12,e13
            sectioninput "plane1" el=e9 edge=4 -> e14
            tool keypoints els=e14 clicks=14.179445350734108,0.032626427406199025 -> e15,e16
            tool line pts=e16,e15 clicks=-24.189233278955943,-0.4893964110929853;30.623164763458416,0.2936378466557912 -> e17
            pointoncurve e17 54.37520391517131,0 dofs=33.16070346923052mm -> e18
            tool segment pts=e15,e18 clicks=31.928221859706376,-0.4893964110929853;54.375203915171305,0.5546492659053834 -> e19
            pointoncurve e11 31.5,24.30668841761826 dofs=-17.880689101491953mm -> e20
            tool perp pts=e18 els=e17 clicks=83.08646003262645,0.032626427406199025;54.636215334420896,0.5546492659053834 -> e21
            pointoncurve e21 54.37520391517131,14.38825448613377 dofs=22.138495364771998mm -> e22
            tool segment pts=e18,e22 clicks=55.68026101141926,-0.7504078303425775;53.592169657422524,14.38825448613377 -> e23
            tool segment pts=e22,e20 clicks=53.592169657422524,14.38825448613377;30.36215334420882,24.567699836867863 -> e24
            tool segment pts=e20,e15 clicks=32.45024469820556,22.479608482871125;32.45024469820556,0.032626427406199025 -> e25
            tool outline els=e25,e24,e23,e19 clicks=32.18923327895597,9.951060358890702;41.585644371941285,19.869494290375204;54.37520391517131,7.194127243066885;42.937601957585656,0 -> e26,e27,e28,e29,e30
            space "plan"
            tool curve3 els=e6,e4,e2,e1,e6 clicks=-35.75,-24.5;21.75,-23.75;22,31.5;-36.25,30.25;-36,-25 -> e31
            space "plane1"
            tool makerel els=e18,e15 clicks=43.18857981128731,0.2107641895467509;31.205913150809764,0.5235088309397611 dofs=11.479884928876253mm
            space "plan"
            name e31 "border"
            space "plane1"
            space "plan"
            space "plane1"
            tool sweep els=e31,e30 clicks=16.52403765212233,3.9251743660926763;28.373922071722976,13.443325346523295 signs=1 dofs=0deg;0deg -> e32
            """.trimIndent() + "\n"
    }
}
