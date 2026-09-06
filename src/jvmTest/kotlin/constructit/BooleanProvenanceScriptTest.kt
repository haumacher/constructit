package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.resultOf
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.geom.EdgeGeom
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.MeshBool
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec3
import constructit.geom.Watertight
import constructit.l10n.contains
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **GitHub #36, script 3: a rounding on a body that add and subtract made** (OP-31, item 4).
 *
 * The reporter's own words are the whole of the case: *"The rounding input is solid e76 … This is especially
 * hard, since the target object is the result of add and subtract — but this is the only way to create the
 * base solid of such structure."* Until this slice the answer was a refusal — *"this solid is mesh-only (a
 * general boolean's result, OP-9), so its section has no faces to name"* — and every face of that body is a
 * **plane**: an extrusion's caps and walls, and the triangles of two pyramids. Nothing about it was
 * emergent except where the surfaces were trimmed, which is exactly computable.
 *
 * The file below is the reporter's, verbatim, down to the `param "r" = 5mm` they had armed for the rounding
 * they could not make. What is asserted of it is the whole chain: the fused body names its faces and its
 * creases, the creases are the exact lines two planes meet in, a fillet runs along one of them and takes out
 * the rolling ball's own figure, a sketch space opens on one of its faces, a level section through it closes
 * on the drawing's own outline — and the file itself is still a fixed point through save and load.
 */
class BooleanProvenanceScriptTest {
    private fun requireEngine() = assumeTrue(MeshBool.available, "the general boolean engine is not available: ${MeshBool.status}")

    private fun load(text: String): Editor {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(text))
        return ed
    }

    private fun el(
        ed: Editor,
        name: String,
    ): Element = assertNotNull(ed.doc.elements.firstOrNull { ed.doc.nameOf(it) == name }, "$name is in the drawing")

    private fun solidOf(
        ed: Editor,
        name: String,
    ): Solid3 {
        val ref = el(ed, name).ref as SolidRef
        val ev = Evaluator()
        assertTrue(ev.resultOf(ref) is EvalResult.Ok, "$name builds: ${(ev.resultOf(ref) as? EvalResult.Invalid)?.reason}")
        return ev.solid(ref)
    }

    /**
     * **The refusal is gone and a face list stands in its place** — every face of `e76` a plane, every crease
     * the straight line where two of those planes meet.
     */
    @Test
    fun theFusedBodyNamesEveryFaceItHasAndEveryCreaseBetweenThem() {
        requireEngine()
        val body = solidOf(load(SCRIPT3), "e76")
        assertManifold(body.mesh, "e76")
        val feature = assertNotNull(body.feature as? Feature3.MeshBoolean, "e76 is what a general boolean makes")
        assertNotNull(feature.provenance, "…and it carries its provenance: ${feature.provenanceRefusal?.render()}")

        val (faces, why) = Section3.faces(body.feature)
        assertNotNull(faces, "e76 names its faces: ${why?.render()}")
        assertTrue(faces.size > 20, "one slot per face of each operand: ${faces.size}")
        var surfaces = 0
        for (p in faces) {
            assertTrue(p.name is FaceName.BoolFace, "every face is a piece of an operand face: ${p.name}")
            if (p.plane == null) {
                // the only slot with no surface is one the boolean emptied — and it keeps its place, so no
                // address into this list ever moves (OP-17)
                assertTrue(assertNotNull(p.reason).contains("away wholly"), "an empty slot says why: ${p.reason?.render()}")
                continue
            }
            assertNull(p.reason, "a face that is a plane is not refused")
            assertTrue(p.outline.all { it is ProfileElement.Seg }, "…and every plane's outline is straight pieces")
            surfaces++
        }
        assertTrue(surfaces > 20, "and most of them still have a surface: $surfaces")

        val (edges, whyEdges) = Section3.edges(body.feature)
        assertNotNull(edges, "e76 names its edges: ${whyEdges?.render()}")
        for (e in edges) {
            val name = assertNotNull(e.name as? EdgeName.BoolCrease, "every edge is a crease of the boolean: ${e.name}")
            val g = assertNotNull(e.geom as? EdgeGeom.Straight, "two planes meet in a line: ${e.geom}")
            assertTrue((g.b - g.a).length() > 1e-6, "and the line has length")
            for (i in listOf(name.a, name.b)) {
                val plane = faces[i].plane ?: continue
                for (w in listOf(g.a, g.b)) assertClose(0.0, plane.distanceTo(w), tol = 1e-9, msg = "the crease lies in face $i")
            }
        }
    }

    /**
     * **The rounding the reporter could not make.** Crease #2 of the fused body is where the block's bottom
     * face meets the wall over its first boundary piece — 49.75 mm of straight, square corner — and a 5 mm
     * fillet along it takes out the rolling ball's own wedge, `r²(1 − π/4)` per millimetre, bracketed above
     * by the tessellation's own inscribed chords exactly as an extrusion's fillet is.
     */
    @Test
    fun aFilletRunsAlongOneOfItsCreasesAndTakesOutTheRollingBallsWedge() {
        requireEngine()
        val before = solidOf(load(SCRIPT3), "e76")
        val crease = assertNotNull(Section3.edges(before.feature).first)[EDGE].geom as EdgeGeom.Straight
        val length = (crease.b - crease.a).length()
        assertClose(49.75, length, tol = 1e-9, msg = "the crease is the block's own bottom edge")

        val ed = load(withFillet(EDGE))
        val rounded = solidOf(ed, "e77")
        assertManifold(rounded.mesh, "e76 with a 5 mm fillet on crease #$EDGE")
        val drop = Geom3.volume(before.mesh) - Geom3.volume(rounded.mesh)
        val exact = raspWedgeArea(5.0) * length
        val chords = raspWedgeAreaByChords(5.0) * length
        // The bracket is the blend's own ([EdgeBlendTest]'s model), with the general engine's **float32**
        // noise allowed for: the drop is the difference of two volumes of about 172,000 mm³ whose vertices
        // are float32, so a part in ten million of the body is the floor of what can be read at all.
        val noise = 1e-6 * Geom3.volume(before.mesh)
        assertTrue(drop >= exact - noise, "a fillet never takes out less than the ball does: $drop < $exact")
        assertTrue(drop <= chords + noise, "…nor more than its inscribed chords do: $drop > $chords")
    }

    /**
     * **A face space opens on a face of the fused body, and a level section through it closes on the drawing's
     * own outline.**
     *
     * The section is taken at `z = 5`, below everything the second pyramid subtracts (its apex is at
     * `z = 10`), so what the plane cuts there is the block's own footprint — and because the faces are named
     * and are the whole boundary, the section is **assembled from them** and comes out as exact segments
     * rather than as chords off the triangles.
     */
    @Test
    fun aFaceSpaceOpensOnItAndALevelSectionClosesOnTheOutline() {
        requireEngine()
        val ed = load(SCRIPT3)
        val body = solidOf(ed, "e76")

        // the address space of a body with no plan of its own is its face list itself
        // ([Section3.FACE_ADDRESS_CONVENTION]): piece 0 is face 0
        val (patch, why) = Section3.facePatchOfFootprintPiece(body.feature, 0)
        assertNotNull(patch, "a sketch space opens on face #1 of the fused body: ${why?.render()}")
        assertNotNull(patch.plane, "…on a real plane")
        assertTrue(patch.outline.isNotEmpty(), "…and the space draws the face's own outline")

        // and the same through the editor, which is what a `sketchspace` step does
        val ed2 = load(SCRIPT3 + "sketchspace \"fused\" el=e76 piece=0\n")
        assertTrue(ed2.doc.spaces.any { it.name == "fused" }, "the step opens the space: ${ed2.doc.loadNotes.map { it.render() }}")

        val level = Plane3(Vec3(0.0, 0.0, 5.0), Vec3.X, Vec3.Y)
        val section = Section3.sectionOf(body, level)
        assertNull(section.inputsRefusal, "the section names its inputs: ${section.inputsRefusal?.render()}")
        assertTrue(!section.approximated, "and it is exact, not chords off the triangles")
        assertTrue(section.drawn.isNotEmpty(), "…and it draws something")
        assertTrue(section.drawn.all { it is ProfileElement.Seg }, "the outline is straight pieces")

        // it closes: every piece hands over to another piece's start
        val segs = section.drawn.segments()
        for (s in segs) {
            assertTrue(segs.any { it !== s && (it.a - s.b).length() < 1e-6 }, "the section closes at ${s.b}")
        }
        // …and it encloses the block's own footprint, which is the bottom cap's area
        val cap = assertNotNull(Section3.faces(body.feature).first).first { it.name.let { n -> n is FaceName.BoolFace && n.of.let { o -> o is FaceName.BoolFace && o.of == FaceName.Cap(constructit.geom.SolidFace.BOTTOM) } } }
        assertClose(area(cap.outline.segments()), area(segs), tol = 1e-6, msg = "the section is the block's own footprint")
    }

    /** The file is still a fixed point: what it says now is what it says after a load and a save. */
    @Test
    fun theFileIsStillAFixedPoint() {
        requireEngine()
        val once = DocumentFormat.save(DocumentFormat.load(SCRIPT3))
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "save → load → save is a fixed point")
        val filleted = DocumentFormat.save(DocumentFormat.load(withFillet(EDGE)))
        assertEquals(filleted, DocumentFormat.save(DocumentFormat.load(filleted)), "…with the rounding on it too")
        assertTrue(filleted.contains("tool filletedge els=e76"), "and the rounding is a step of the file: $filleted")
    }

    /**
     * **The half of OP-9's sink rule that stands.** A boolean over a body that came out of a *file* has no
     * carrier to look anything up against, so it refuses — in exactly the words it always did.
     */
    @Test
    fun aBooleanOverAnImportedMeshStillRefusesInTheOldWords() {
        requireEngine()
        val block = solidOf(load(SCRIPT3), "e39")
        val imported = Solid3.of(Feature3.Imported("part.jt", openShell = Watertight.defect(block.mesh)), block.mesh)
        val bar = solidOf(load(SCRIPT3), "e58")
        val (out, why) = Geom3.combine(constructit.geom.BoolOp.UNION, imported, bar)
        assertNull(why, "the boolean itself is unaffected: ${why?.render()}")
        val feature = assertNotNull(assertNotNull(out).feature as? Feature3.MeshBoolean)
        assertNull(feature.provenance, "an imported operand has no faces to trace to")
        val (faces, refusal) = Section3.faces(feature)
        assertNull(faces, "so the result names none either")
        assertTrue(assertNotNull(refusal).contains("mesh-only"), "and says so in the old words: ${refusal?.render()}")
        assertTrue(assertNotNull(Section3.edges(feature).second).contains("mesh-only"), "edges too")
    }

    /** The area a closed polygon of straight pieces encloses. */
    private fun area(segs: List<constructit.geom.Segment>): Double {
        var a = 0.0
        for (s in segs) a += s.a.x * s.b.y - s.b.x * s.a.y
        return abs(a) / 2.0
    }

    private fun withFillet(edge: Int): String = SCRIPT3 + "tool filletedge els=e76 clicks=-40,-16.625 scalar=\"r\" signs=$edge -> e77,e78\n"

    companion object {
        /** Crease #2 of `e76`: the block's bottom face against the wall over its first boundary piece. */
        private const val EDGE = 2

        /** GitHub #36's script 3, verbatim. */
        val SCRIPT3: String = BooleanProvenanceScriptTest::class.java.getResource("/script3.cit")!!.readText()
    }
}
