package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Blend3
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshCanon
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A single-edge pick runs along the tangent-continuous run** — GitHub issue #29.
 *
 * *"The extruded version of segment e11 is 3D-filleted. However, this results in an awkward result, since
 * segment e11 is linked to a fillet in the 2D base construction (arc e6). … I would expect that the fillets
 * are 'smoothly' joined together — as if I rounded all the edges with a rasp."*
 *
 * The reporter's own script is the fixture, verbatim. What it shows is the addressing question: the pick took
 * exactly one edge, because `e11`'s rim and the arc's rim were **not on record** as tangent — `e10` and `e11`
 * are fresh segments drawn from the arc's key points, not the fillet's own legs. Two things answer it, both
 * structural: a segment whose ends both lie on a tangent leg by construction **inherits** that tangency, and
 * a single-edge address then stands for the whole run of edges tangent-continuous through it.
 */
class BlendRunTangencyTest {
    /** The reporter's script for #29, verbatim. */
    private val issue29 =
        """
constructit 3
point -61.6061297403065,-10.474592995769466 -> e1
point 16.658941297601118,27.98155575420435 -> e2
tool segment pts=e1,e2 clicks=-71.375,24.375;-9.625,84.375 -> e3
point 43.04371547563214,-34.63299257776767 -> e4
tool segment pts=e2,e4 clicks=-9.625,84.375;27.125,0.875 -> e5
param "r" = 5mm
tool fillet els=e3,e5 clicks=-27.125,66.875;-2.875,70.875 scalar="r" signs=-1;1 -> e6
tool keypoints els=e6 clicks=-11.375,77.875 -> e7,e8,e9
tool segment pts=e8,e1 clicks=-17.625,76.125;-71.625,24.125 -> e10
tool segment pts=e9,e4 clicks=-4.375,73.375;27.625,1.125 -> e11
hide els=e3
hide els=e5
hide els=e2
hide els=e7
tool segment pts=e1,e4 clicks=-70.625,23.875;27.625,-0.375 -> e12
param "h" = 20mm
tool outline els=e12,e10,e6,e11 clicks=-45.625,18.375;-54.375,41.125;-11.065544035971813,76.9875382494712;11.649132871223834,36.03785456470231 -> e13,e14,e15,e16,e17
tool extrude els=e17 clicks=-40.125,17.375 scalar="h" -> e18
tool filletedge els=e18 clicks=6.353621791250703,46.69554776203246 scalar="r" signs=8;-1;1;0;1 -> e19
show els=e2
""".trimStart()

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun el(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "$name is in the drawing")

    /** Where the drawing's point [name] stands — the run's own lengths are read off these, not off the mesh. */
    private fun pt(
        ed: Editor,
        name: String,
    ): Vec2 = assertNotNull(Evaluator().valueOf(el(ed, name).ref) as? constructit.core.PointValue, "$name is a point").p

    @Suppress("UNCHECKED_CAST")
    private fun blendFeature(
        ed: Editor,
        name: String,
    ): Feature3.Blend {
        val solid = Evaluator().solid(el(ed, name).ref as SolidRef)
        return assertNotNull(solid.feature as? Feature3.Blend, "$name is a blend feature, not a mesh sink")
    }

    /**
     * The report, as a count: the one pick at `signs=8` takes **three** edges — the rim over `e11`, the arc's
     * own rim, and the rim over `e10` — because the drawing states that those three pieces hand over
     * tangentially.
     */
    @Test
    fun theSinglePickTakesTheWholeRaspedRun() {
        val ed = load(issue29)
        val blend = blendFeature(ed, "e19")
        assertEquals(3, blend.targets.size, "the rasped run: e11's rim, the arc's rim, e10's rim")
        assertTrue(8 in blend.targets, "the picked edge is in it")
        val mesh = Evaluator().solid(el(ed, "e19").ref as SolidRef).mesh
        assertManifold(mesh, "e19")
    }

    /**
     * **The reporter's own radius is the ball standing still, and it builds** (session 82; family 3 of GitHub
     * #33's by-product, met on this very drawing).
     *
     * The drawing uses one parameter for two things — `r` is the 2D fillet's radius *and* the rasp's — which
     * is what sharing a node **means** in this build, and what a user means by *"round everything at 5"*. The
     * middle piece of the run is that fillet's own arc, so the rasp rolls a ball of radius `r` along a rim of
     * radius `r`: the ball's centre runs on a circle of radius `R − r = 0`, so **the ball stands still**, and
     * the band it leaves is that one ball's own surface — the degenerate torus with its hole closed to a
     * point, which is a sphere patch over the arc's angle. It is an ordinary body, it is in this kernel's own
     * catalogue (the Turn's pole, the Vertex's ball), and every rounded-rectangle plate with a spherical
     * corner is one.
     *
     * What it needed was for the band to be **constructed as the surface of revolution it is**
     * ([Blend3]'s `revolvedBand`): a crease that is one circular arc turns about a fixed axis, and where the
     * general sweep carries the run as chords — and so places a section point that lands *on* the axis within
     * a chord's own error of it, a ring a few tenths of a millimetre across at the run's ends — a revolve puts
     * the pole on the axis exactly and drops the quads that collapse with it. Ten micron-scale flaps before,
     * none after, and the production gate is silent on it.
     *
     * The figure is asserted rather than the fact of building: the two straight pieces of the run lose
     * `r²(1 − π/4)` per millimetre and the arc loses the ball's own `φ·r³/6`, bracketed above by the same
     * arithmetic over the tessellation's **own** inscribed chords, so the bracket moves with the tolerance
     * rather than being a percentage.
     */
    @Test
    fun theReportersOwnRadiusIsTheBallStandingStillAndBuilds() {
        val ed = load(issue29)
        val mesh = Evaluator().solid(el(ed, "e19").ref as SolidRef).mesh
        assertManifold(mesh, "the reporter's rasped run")
        assertNull(MeshCanon.fault(mesh), "and the production gate is silent on it")

        val plate = Evaluator().solid(el(ed, "e18").ref as SolidRef).mesh
        val took = Geom3.volume(plate) - Geom3.volume(mesh)
        val arc = assertNotNull(Evaluator().valueOf(el(ed, "e6").ref) as? constructit.core.ArcValue).arc
        val phi = abs(constructit.geom.GeomMath.sweep(arc))
        val r = arc.radius
        assertClose(r, 5.0, tol = 1e-9, msg = "the fillet's own arc is the rasp's own radius — one parameter, two uses")
        // the run's two straight pieces are the outline's own segments, from the arc's key points to the
        // corners the run stops at, so their lengths are the drawing's and not the mesh's
        val legs =
            (pt(ed, "e8") - pt(ed, "e1")).length() + (pt(ed, "e9") - pt(ed, "e4")).length()
        val exact = raspWedgeArea(r) * legs + phi * raspBallMoment(r)
        val chords = raspWedgeAreaByChords(r) * legs + phi * raspBallMomentByChords(r)
        assertTrue(took >= exact - 1e-6, "at least the closed form takes: $took against $exact")
        assertTrue(took <= chords + 1e-6, "and no more than the inscribed chords do: $took against $chords ($exact exact)")

        // …and the band over the arc really is the ball's own surface: one pole on the top face, every
        // vertex of the patch its own radius from the ball's centre
        val axis = arc.center
        assertTrue(
            mesh.vertices.any { (it - Vec3(axis.x, axis.y, 20.0)).length() < 1e-3 },
            "the band closes on the arc's own centre — one pole, on the top face",
        )
        val ball = Vec3(axis.x, axis.y, 20.0 - r)
        val patch = mesh.vertices.filter { it.z > 20.0 - r + 1e-6 && it.z < 20.0 - 1e-6 && (Vec2(it.x, it.y) - axis).length() < r + 0.1 }
        assertTrue(patch.size >= 8, "the sphere patch is meshed: ${patch.size} vertices")
        for (v in patch) assertClose((v - ball).length(), r, tol = 0.05, msg = "on the ball's own surface: $v")

        assertEquals(
            DocumentFormat.save(ed.doc),
            DocumentFormat.save(DocumentFormat.load(DocumentFormat.save(ed.doc))),
            "and the drawing is a fixed point of save",
        )
    }

    /**
     * **A rasp *wider* than the rim it runs along is still refused, in the words it always was.** The ball
     * would have to reach past the arc's own axis, which is a surface that passes through itself; the
     * boundary is now exactly the rim's radius rather than a tessellation's distance short of it.
     */
    @Test
    fun aRaspWiderThanTheRimItRunsAlongIsRefused() {
        // the same drawing with the rasp given its own radius, one micron over the rim's
        val wider =
            issue29
                .replace("param \"h\" = 20mm", "param \"h\" = 20mm\nparam \"r3\" = 5.001mm")
                .replace("scalar=\"r\" signs=8;-1;1;0;1", "scalar=\"r3\" signs=8;-1;1;0;1")
        val ed = load(wider)
        val why =
            assertNotNull(
                (Evaluator().eval(el(ed, "e19").ref.node) as? EvalResult.Invalid)?.reason,
                "a ball wider than the rim is not a body",
            )
        assertTrue("is larger than the bend" in why, "the bend is named: $why")
        assertTrue("pass through itself" in why, "and the consequence, in the words it always had: $why")
        assertTrue("radius 5 mm" in why, "…measured against the rim's own radius: $why")
    }

    /** The three edges really are the run: consecutive cap pieces of one cap, meeting end to end. */
    @Test
    fun theRunIsThreeCapPiecesOfOneCapMeetingEndToEnd() {
        val ed = load(issue29)
        val base = Evaluator().solid(el(ed, "e18").ref as SolidRef).feature
        val edges = assertNotNull(Section3.edges(base).first, "the plate names its edges")
        val run = blendFeature(ed, "e19").targets.sorted()
        for (i in 0 until run.size - 1) {
            assertNotNull(
                Blend3.sharedEnd(edges[run[i]], edges[run[i + 1]]),
                "edge #${run[i]} and #${run[i + 1]} meet end to end",
            )
        }
        assertEquals(
            1,
            run.map { edges[it].between }.flatMap { listOf(it.a, it.b) }.groupingBy { it }.eachCount().count { it.value == run.size },
            "all three bound one and the same face — the cap the rim runs round",
        )
    }

    /**
     * The tangency is **inherited, and on record**: nothing measured it. The joints the drawing now holds say
     * that `e10` and `e11` hand over to the arc `e6` tangentially, although neither was a leg of the fillet.
     */
    @Test
    fun theFreshSegmentsInheritTheLegsTangency() {
        val ed = load(issue29)
        val arc = el(ed, "e6")
        for (name in listOf("e10", "e11")) {
            val seg = el(ed, name)
            val j =
                assertNotNull(
                    ed.doc.joints.firstOrNull { (it.a === seg && it.b === arc) || (it.a === arc && it.b === seg) },
                    "$name has a registered joint with the arc",
                )
            assertTrue(j.tangent, "and it is a *tangent* handover")
        }
        // the superseded legs are hidden, so their own tangencies are not what the run reads
        assertTrue(!el(ed, "e3").visible && !el(ed, "e5").visible, "the reporter hid the legs the fillet trimmed")
    }

    /**
     * The band is **smooth across both joints**: the blend's own two rails run on with no kink, so the
     * surface normal of the band agrees on either side of each joint.
     */
    @Test
    fun theBandIsTangentContinuousAcrossTheJoints() {
        val ed = load(issue29)
        val mesh = Evaluator().solid(el(ed, "e19").ref as SolidRef).mesh
        assertManifold(mesh, "e19")
        // the two joints, in the world: the arc's tangencies raised to the top cap (z = 20)
        val arc = assertNotNull(Evaluator().valueOf(el(ed, "e6").ref) as? constructit.core.ArcValue).arc
        for (t in listOf(constructit.geom.GeomMath.arcStart(arc), constructit.geom.GeomMath.arcEnd(arc))) {
            val at = Vec3(t.x, t.y, 20.0)
            val near = mesh.triangles.filter { tri -> listOf(tri.a, tri.b, tri.c).any { (mesh.vertices[it] - at).length() < 6.0 } }
            assertTrue(near.size > 2, "the band is meshed round the joint at $at")
            // no triangle *stands on* the joint of the two rails as a crack would: the band's own vertices
            // there are shared, which is what makes the two wedges one ribbon
            val standing =
                near.count { tri -> listOf(tri.a, tri.b, tri.c).count { (mesh.vertices[it] - at).length() < 1e-9 } > 0 }
            assertTrue(standing == 0 || standing >= 2, "the joint is a shared vertex, not a seam: $standing")
        }
    }

    /**
     * The file replays to the three-edge run, and the **deliberate semantic change is spoken**: the reporter's
     * step named one edge and now names the run through it, so the two edges it gained have no recorded
     * choices — they are scored once on the load, the load says so by name, and from then on the file is a
     * fixed point.
     *
     * Nothing about the format moved: the same step, the same tool id, the same picked address first in
     * `signs=`, and the choice the click made for *that* edge is taken verbatim rather than re-decided
     * (OP-18). Hence no version bump — the bytes are read the new way unambiguously.
     */
    @Test
    fun theScriptReplaysToTheRunAndSaysWhatChanged() {
        val doc = DocumentFormat.load(issue29)
        val once = DocumentFormat.save(doc)
        assertTrue(
            doc.loadNotes.any { it.contains("3 edges of the tangent-continuous run") },
            "the load names the change: ${doc.loadNotes}",
        )
        // the picked address and *its own* four choices are exactly the ones the file carried
        assertTrue(once.contains("tool filletedge els=e18"), once)
        assertTrue(once.contains("signs=8;-1;1;0;1;"), "the address and the click's own choice, verbatim: $once")
        assertEquals(3, blendFeature(load(once), "e19").targets.size, "and it replays to the run")
        // ...and from here the file is a fixed point, choices and all
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save -> load -> save is a fixed point")
        // every other line is untouched
        assertEquals(
            atThisVersion(issue29).lines().filter { !it.startsWith("tool filletedge") },
            once.lines().filter { !it.startsWith("tool filletedge") },
            "only the blend step's own choices grew",
        )
    }

    /** An edge with no tangent neighbour on record still takes exactly one — every old file replays as it did. */
    @Test
    fun anEdgeWithNoTangentNeighbourStillTakesOne() {
        val sharp =
            """
constructit 3
orthostart -20,-20 -> e1
orthovertex -20,20 -> e2,e3
orthovertex 30,20 -> e4,e5
orthovertex 30,-20 -> e6,e7
orthoclose -> e8
param "h" = 10mm
tool extrude els=e8 clicks=0,-20 scalar="h" -> e9
param "r" = 2mm
tool filletedge els=e9 clicks=0,-20 scalar="r" signs=8;-1;1;0;1 -> e10
""".trimStart()
        val ed = load(sharp)
        assertEquals(1, blendFeature(ed, "e10").targets.size, "a sharp-cornered rim blends one edge, as always")
        assertEquals(ElementKind.SOLID, el(ed, "e10").kind)
        assertManifold(Evaluator().solid(el(ed, "e10").ref as SolidRef).mesh, "e10")
    }
}
