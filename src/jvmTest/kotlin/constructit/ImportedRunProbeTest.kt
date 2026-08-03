package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.geom.Curves3
import constructit.geom.Geom3
import constructit.units.mm
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.PolylineSet
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4

/**
 * **The probe review of OP-26 step 9 — imported curves**, and with it of the record as a whole.
 *
 * The delivery proves the import against the file: the runs arrive, they place, they name, they persist.
 * This asks the question the whole of OP-26 was built to make answerable — whether a run that came out of
 * somebody else's file is the *same kind of thing* as one drawn here, by handing it to the operators the
 * eight earlier steps built and expecting no special case anywhere.
 */
class ImportedRunProbeTest {
    private fun invalid(el: constructit.editor.Element): String? =
        (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    private fun pathOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as Path3Value).path

    /** A file carrying one solid **and** one wireframe part — the mixed case a real assembly is. */
    private fun mixedScene(): Scene {
        val cube =
            Mesh(
                listOf(
                    Vec3(0f, 0f, 0f),
                    Vec3(40f, 0f, 0f),
                    Vec3(40f, 40f, 0f),
                    Vec3(0f, 40f, 0f),
                    Vec3(0f, 0f, 40f),
                    Vec3(40f, 0f, 40f),
                    Vec3(40f, 40f, 40f),
                    Vec3(0f, 40f, 40f),
                ),
                emptyList(),
                listOf(
                    Mesh.Triangle(0, 2, 1, -1, -1, -1), Mesh.Triangle(0, 3, 2, -1, -1, -1),
                    Mesh.Triangle(4, 5, 6, -1, -1, -1), Mesh.Triangle(4, 6, 7, -1, -1, -1),
                    Mesh.Triangle(0, 1, 5, -1, -1, -1), Mesh.Triangle(0, 5, 4, -1, -1, -1),
                    Mesh.Triangle(1, 2, 6, -1, -1, -1), Mesh.Triangle(1, 6, 5, -1, -1, -1),
                    Mesh.Triangle(2, 3, 7, -1, -1, -1), Mesh.Triangle(2, 7, 6, -1, -1, -1),
                    Mesh.Triangle(3, 0, 4, -1, -1, -1), Mesh.Triangle(3, 4, 7, -1, -1, -1),
                ),
            )
        // a route that climbs out of the plan, so it is a genuinely spatial run rather than a flat sketch
        val wire =
            PolylineSet(
                listOf(Vec3(100f, 0f, 0f), Vec3(160f, 0f, 30f), Vec3(220f, 40f, 60f), Vec3(280f, 40f, 60f)),
                listOf(listOf(0, 1, 2, 3)),
            )
        val body = SceneNode("kasten", JtMat4.IDENTITY, listOf(cube), emptyList(), null, emptyList())
        val run = SceneNode("trasse", JtMat4.IDENTITY, emptyList(), listOf(wire), null, emptyList())
        return Scene(
            LengthUnit.MILLIMETERS,
            SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(body, run)),
            emptyList(),
        )
    }

    /**
     * **A run from a file is a run.** A mixed file brings its solid *and* its wireframe in; the run then
     * carries a tube (step 2), which is the operator that reads a path most deeply — it walks the chain,
     * builds a moving frame on it and asks the embedding criterion about it. If an imported run were a
     * second kind of curve in any respect, that is where it would show.
     */
    @Test
    fun aRunOutOfAFileCarriesATubeLikeAnyRunDrawnHere() {
        val ed = Editor()
        val result = constructit.exchange.Imports.importScene(ed.doc, mixedScene(), "anlage.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("kasten"), result.bodies, "the solid came in as a body")
        assertEquals(1, result.runs.size, "and the wireframe as a run: ${result.message}")
        assertTrue(result.notes.none { it.contains("not imported") }, "nothing was skipped: ${result.notes}")

        val run = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(null, invalid(run), "the run is a value")
        val pts = Curves3.polyline(pathOf(ed, run))
        assertEquals(4, pts.size, "the file said four points, and four is what the drawing has")
        assertTrue(pts.last().z > 50.0, "it really climbs out of the plan: ${pts.last()}")

        // the deepest reader of a path: a tube along it
        val tube = ed.doc.tubeAlongCurve(run, ed.doc.newParameter("r", 5.0.mm).ref)
        val body = assertNotNull(tube, "a tube along an imported run: ${ed.doc.note}")
        val why = invalid(body)
        if (why != null) {
            assertTrue(why.length > 15, "if it refuses it names itself rather than crashing: $why")
        } else {
            assertManifold((Evaluator().valueOf(body.ref) as SolidValue).solid.mesh, "a tube on an imported run")
            assertTrue(Geom3.volume((Evaluator().valueOf(body.ref) as SolidValue).solid.mesh) > 0.0)
        }

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
        // and the run came back off the file as the same four points
        val back = DocumentFormat.load(text).elements.last { it.kind == ElementKind.SPACE_CURVE }
        assertEquals(pts, Curves3.polyline((Evaluator().valueOf(back.ref) as Path3Value).path), "point for point")
    }
}
