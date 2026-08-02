package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Appearance
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportFormat
import constructit.exchange.ExportScene
import constructit.exchange.Exports
import constructit.exchange.Imports
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec2
import de.haumacher.kotlinjt.scene.Color
import de.haumacher.kotlinjt.scene.LengthUnit
import de.haumacher.kotlinjt.scene.LodPolicy
import de.haumacher.kotlinjt.scene.Material
import de.haumacher.kotlinjt.scene.Mesh
import de.haumacher.kotlinjt.scene.Scene
import de.haumacher.kotlinjt.scene.SceneNode
import de.haumacher.kotlinjt.scene.Vec3
import de.haumacher.kotlinjt.scene.readScene
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import de.haumacher.kotlinjt.scene.Mat4 as JtMat4

/**
 * **The JT import — bytes into a drawing, and the loop closed both ways.**
 *
 * The export test asserts that what this app writes says what the drawing says. This one asserts the other
 * direction twice over: a part *we* wrote comes back as the same body (so the two adapters agree), and a
 * file **Siemens NX wrote** comes in whole (so the library is validated against bytes we did not produce).
 *
 * What an imported body is, in one line, because every assertion here rests on it: a frozen mesh literal
 * with a **parametric placement** — so the geometry is exactly what the file said and the position is
 * ordinary nodes the drawing can move.
 */
class JtImportTest {
    private fun Editor.click(world: Vec2) {
        val s = camera.worldToScreen(world)
        pointerMove(s)
        pointerDown(s)
        pointerUp(s)
    }

    private fun Editor.drag(
        from: Vec2,
        to: Vec2,
    ) {
        setTool(Tools.SELECT)
        pointerDown(camera.worldToScreen(from))
        pointerMove(camera.worldToScreen(to))
        pointerUp(camera.worldToScreen(to))
    }

    private fun Editor.type(digits: String) {
        for (c in digits) key(c.toString())
        key("Enter")
    }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(
        ed: Editor,
        index: Int = -1,
    ): Mesh3 {
        val solids = ed.doc.elements.filter { it.kind == ElementKind.SOLID }
        val el = if (index < 0) solids.last() else solids[index]
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    /** The one body a drawing shows — what the 3D view, the preview and every export agree on. */
    private fun onlyBody(ed: Editor) = ExportScene.extract(ed.doc, "probe").nodes.single()

    /**
     * A 100 × 100 × 40 plate with a Ø30 bore through it — a part with a hole, made entirely by gestures, so
     * what the file carries is what a person would have drawn.
     */
    private fun drilledPlate(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("t", constructit.units.Quantity.mm(40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(50.0, 0.0))
        ed.setTool(Tools.CIRCLE_R)
        ed.type("15")
        ed.click(Vec2(50.0, 50.0))
        // the bore goes **through**, so it is part of what the part projects to — which is what makes the
        // plan of an imported copy of it worth asserting
        ed.activeScalar = ed.doc.scalars.first { it.name == "t" }
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(65.0, 50.0))
        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(50.0, 0.0))
        ed.click(Vec2(65.0, 50.0))
        return ed
    }

    /** Where the anchor point of the newest placement stands right now. */
    private fun anchorAt(ed: Editor): constructit.geom.Vec2 {
        val el = ed.doc.elements.last { it.kind == ElementKind.POINT }
        return (Evaluator().eval(el.ref.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.PointValue).p }
    }

    private val fixture = File("/home/haui/devel/kotlinJT/fixtures/nist-mtc-crada-assembly.jt")

    // ---- the round trip: out through the writer, back through the reader ----

    /**
     * **The acceptance both ways.** A drilled, renamed, dressed part exported to JT and imported back into a
     * *fresh* drawing: the same volume, the same name through the naming authority, the Tier-1 colour and
     * roughness — and metalness 0, because JT's material is Phong and has no such concept (the export's own
     * recorded loss, asserted here from the other side).
     *
     * The volume is the strong statement: it is recomputed from the triangles that made the round trip, so it
     * says the coordinates and the winding survived, not merely that a mesh arrived.
     */
    @Test
    fun aDrilledRenamedDressedPartComesBackAsTheSameBody() {
        val ed = drilledPlate()
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        val volume = Geom3.volume(meshOf(ed))
        assertEquals("korpus", ed.doc.nameElement(part, "korpus"))
        assertNotNull(ed.setMaterial(part, Appearance("#b87333", roughness = 0.35, metallic = 0.9)))
        val jt = Exports.export(ed.doc, "probe", ExportFormat.JT)
        assertTrue(jt.ok, jt.message)

        val fresh = Editor()
        val result = fresh.importFile(jt.bytes!!, "probe.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("korpus"), result.bodies, "the name rode through the file and back into the drawing")
        assertEquals(emptyList(), result.refusals, "a part this app wrote passes its own watertight gate")
        assertEquals(emptyList(), result.notes, "silence means success, on the way in too")

        val body = onlyBody(fresh)
        assertEquals("korpus", body.name, "one body, named by the naming authority (OP-18)")
        assertClose(Geom3.volume(body.mesh), volume, tol = 1.0, msg = "the same body, through float32 vertices")
        assertNull(Imports.openShellDefect(body.mesh), "and it is a closed solid in the drawing too")

        // Tier 1: the colour survives the linear/sRGB round trip, the roughness the Phong one, and
        // metalness is 0 — JT's limit, recorded on the writer and asserted here
        val material = fresh.doc.materialOf(fresh.doc.elements.first { fresh.doc.userNameOf(it) == "korpus" })
        assertEquals("#b87333", material.color, "the base colour came back through linear RGB unchanged")
        assertClose(material.roughness, 0.35, 1e-4, "roughness → shininess → roughness")
        assertEquals(0.0, material.metallic, "metalness is not JT's concept: it comes back 0, and that is the format's")

        // ...and the drawing is in millimetres, which is the only unit this engine has
        assertEquals(constructit.exchange.LengthUnit.MILLIMETRE, ExportScene.extract(fresh.doc, "probe").unit)
    }

    /** An imported body is a solid like any other: it re-exports, through every writer. */
    @Test
    fun anImportedBodyGoesOutAgainThroughEveryWriter() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val fresh = Editor()
        assertTrue(fresh.importFile(jt.bytes!!, "probe.jt").ok)

        for (format in listOf(ExportFormat.GLB, ExportFormat.STL, ExportFormat.THREE_MF, ExportFormat.JT)) {
            val out = Exports.export(fresh.doc, "again", format)
            assertTrue(out.ok, "${format.label}: ${out.message}")
            assertTrue(out.bytes!!.size > 100, "${format.label} wrote something")
            assertTrue(out.message.contains("1 solid"), "one body, not the literal beside it: ${out.message}")
        }
        // the strongest of the four: 3MF *checks* the mesh is manifold before writing a byte (OP-9), so an
        // imported body passing it is the watertight guarantee holding after a file round trip
        val back = readScene(Exports.export(fresh.doc, "again", ExportFormat.JT).bytes!!, LodPolicy.FINEST_ONLY)
        assertEquals(1, back.root.children.size, "one named part goes out again")
    }

    // ---- placement: the parametric half, generic over solids ----

    /**
     * **The placement is live.** Drag the anchor point and the body follows; retype the angle and it turns —
     * and the volume never changes, which is what "rigid" means.
     */
    @Test
    fun anImportedBodyFollowsItsPointAndItsAngle() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        val before = onlyBody(ed).mesh
        val volume = Geom3.volume(before)
        val centre = centroid(before)
        val from = anchorAt(ed)

        // drag the anchor point the import created — the body is its dependent, so it follows
        ed.drag(from, Vec2(from.x + 25.0, from.y - 12.0))
        val to = anchorAt(ed)
        assertTrue(abs(to.x - from.x) > 1.0, "the drag actually moved the anchor")
        val moved = onlyBody(ed).mesh
        assertClose(Geom3.volume(moved), volume, 1e-6, "a move changes no volume")
        assertClose(centroid(moved).x, centre.x + (to.x - from.x), 1e-6, "the body followed its point in x")
        assertClose(centroid(moved).y, centre.y + (to.y - from.y), 1e-6, "...and in y")
        assertClose(centroid(moved).z, centre.z, 1e-6, "...and nowhere else")

        // the angle: an ordinary parameter, retyped — a quarter turn about the anchor
        val turn = ed.doc.scalars.single { it.name.startsWith("place-angle") }
        ed.doc.setParameter(turn, constructit.units.Quantity.deg(90.0))
        val turned = onlyBody(ed).mesh
        assertClose(Geom3.volume(turned), volume, 1e-6, "a turn changes no volume either")
        assertClose(centroid(turned).x, to.x - centre.y, 1e-6, "the body turned about its anchor")
        assertClose(centroid(turned).y, to.y + centre.x, 1e-6)
    }

    /**
     * **The genericity proof.** The same placement, on a solid the kernel *constructed* — and it keeps its
     * analytic feature, so nothing in the honesty ledger degrades: the placed plate is still an extrusion,
     * and a sketch space can still be opened on its face.
     */
    @Test
    fun placingAConstructedSolidWorksIdenticallyAndKeepsItsFeature() {
        val ed = drilledPlate()
        val part = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        val volume = Geom3.volume(meshOf(ed))
        val centre = centroid(meshOf(ed))

        ed.setTool(Tools.PLACE_SOLID)
        ed.type("30")
        ed.click(Vec2(50.0, 0.0)) // the part, by its footprint hint
        ed.click(Vec2(-80.0, -60.0)) // an empty spot: the point it is placed at
        val placed = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()
        assertTrue(placed !== part, "a placement makes a new solid, exactly as a boolean does")

        val body = onlyBody(ed)
        val at = anchorAt(ed)
        val rad = Math.toRadians(30.0)
        assertClose(Geom3.volume(body.mesh), volume, 1e-6, "placing a constructed solid changes no volume")
        assertClose(
            centroid(body.mesh).x,
            at.x + centre.x * kotlin.math.cos(rad) - centre.y * kotlin.math.sin(rad),
            1e-6,
            "and it landed at the picked point, turned by the typed angle",
        )
        assertClose(
            centroid(body.mesh).y,
            at.y + centre.x * kotlin.math.sin(rad) + centre.y * kotlin.math.cos(rad),
            1e-6,
        )

        // an extrusion placed is still an extrusion (here a prism, after the bore) — the *feature* moved,
        // not just the triangles, which is what keeps a placed part sketchable; a placement that sank the
        // solid to a mesh could not say this
        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(placed.ref as SolidRef).feature
        assertTrue(
            feature is constructit.geom.Feature3.Prism || feature is constructit.geom.Feature3.Extrusion,
            "a rigid placement preserves the analytic feature, it does not sink to a mesh: $feature",
        )

        // ...and the placement is parametric here too: the same anchor drag moves the constructed body
        val was = centroid(body.mesh)
        ed.drag(at, Vec2(at.x - 40.0, at.y + 7.0))
        val now = anchorAt(ed)
        assertClose(Geom3.volume(onlyBody(ed).mesh), volume, 1e-6)
        assertClose(centroid(onlyBody(ed).mesh).x, was.x + (now.x - at.x), 1e-6, "the constructed body followed its point")
        assertClose(centroid(onlyBody(ed).mesh).y, was.y + (now.y - at.y), 1e-6)
    }

    // ---- the plan of a mesh-only body: what it draws, and what a click can reach ----

    /**
     * **An imported body has a plan, and it is the outline of what it projects** — the mesh-only footprint
     * OP-9 and OP-17 both parked. Exact, not approximated: a 100 × 100 plate's silhouette in the plan is the
     * square it occupies, in four segments, because the collinear facet edges along each side are one line.
     */
    @Test
    fun anImportedBodyProjectsAnOutlineIntoThePlan() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)

        @Suppress("UNCHECKED_CAST")
        val placed = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
        val plan = placed.feature.footprint
        assertTrue(plan.isNotEmpty(), "a body a user can see must have a plan to be seen by")
        val outer = plan.maxByOrNull { abs(areaOf(it)) }!!
        assertEquals(4, outer.outer.elements.size, "the plate's outline is four straight runs, not one per facet")
        assertClose(abs(areaOf(outer)), 100.0 * 100.0, 1e-6, "and it is the square the plate occupies")
        // the Ø30 bore projects too — a second loop, wound the other way, exactly as a hole is
        val bore = plan.filter { it !== outer }
        assertTrue(bore.isNotEmpty(), "the bore is in the plan as well")
        assertClose(bore.sumOf { abs(areaOf(it)) }, Math.PI * 15.0 * 15.0, 40.0, "the bore's outline, in chords")

        // the raw literal beside it draws nothing, deliberately: it is the file's content in the file's own
        // coordinates, not a body in a space, so there is no plane to project it into
        @Suppress("UNCHECKED_CAST")
        val literal = Evaluator().solid(ed.doc.elements.first { it.kind == ElementKind.SOLID }.ref as SolidRef)
        assertEquals(emptyList(), literal.feature.footprint, "the literal has no space to be drawn in")
    }

    /** The plan is drawn — a user who imports a file sees the body, not only its anchor point. */
    @Test
    fun theImportedBodyIsDrawnInThePlan() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        ed.canvasW = 320.0
        ed.canvasH = 240.0
        // the plate spans 0..100 in both axes, so the view is put on its centre rather than on the origin
        ed.camera = constructit.editor.Camera(160.0 - 50.0 * 1.6, 120.0 + 50.0 * 1.6, 1.6)
        val target = constructit.editor.SvgDrawTarget()
        ed.render(target)
        Golden.check("imported_body_plan", target.svg())
    }

    /**
     * **Pickable for a `SOLID` slot, by clicking its outline** — which is the same rule a constructed
     * footprint is picked by, so the pick cycle stays one rule. Asserted through the gesture that needs it
     * most: a boolean *between an imported body and a constructed one*, both picked by clicks.
     */
    @Test
    fun anImportedBodyFillsASolidSlotByClicking() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        val imported = Geom3.volume(onlyBody(ed).mesh)

        // a constructed block overlapping the imported plate's corner by 20 x 20 x 10
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(80.0, 80.0))
        ed.click(Vec2(140.0, 140.0))
        ed.activeScalar = ed.doc.newParameter("d", constructit.units.Quantity.mm(10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(110.0, 80.0))

        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(50.0, 0.0)) // the imported plate, by its projected outline
        ed.click(Vec2(110.0, 140.0)) // the constructed block, by its footprint
        val cut = ed.doc.elements.filter { it.kind == ElementKind.SOLID }.last()

        @Suppress("UNCHECKED_CAST")
        val mesh = Evaluator().solid(cut.ref as SolidRef).mesh
        assertManifold(mesh, "an imported body with a constructed bite out of it")
        assertClose(Geom3.volume(mesh), imported - 20.0 * 20.0 * 10.0, 2.0, "the overlap came out of the imported body")
    }

    /** ...and the same for re-placing one: *Place solid* reaches an imported body by clicking it. */
    @Test
    fun anImportedBodyIsRePlacedByClicking() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        val before = onlyBody(ed).mesh
        val volume = Geom3.volume(before)
        val centre = centroid(before)

        ed.setTool(Tools.PLACE_SOLID)
        ed.type("90")
        ed.click(Vec2(50.0, 0.0)) // the imported body, by its outline
        ed.click(Vec2(-70.0, -70.0)) // where it should sit
        val at = anchorAt(ed)
        val body = onlyBody(ed)
        assertClose(Geom3.volume(body.mesh), volume, 1e-6, "a re-placement changes no volume")
        assertClose(centroid(body.mesh).x, at.x - centre.y, 1e-6, "the body turned a quarter about the new point")
        assertClose(centroid(body.mesh).y, at.y + centre.x, 1e-6)
        // ...and the re-placed body has a plan of its own, so it stays pickable in turn
        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef).feature
        assertTrue(feature.footprint.isNotEmpty(), "a placed placement is drawn and pickable like any other body")
    }

    /**
     * **The anchor point wins the tie with the body under it** — the pick cycle's own rule (*a point cannot
     * dodge, a curve can be clicked elsewhere*), which now has to hold where an imported body's outline runs
     * through its own anchor.
     */
    @Test
    fun theAnchorPointOutranksTheBodyItMoves() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        val anchor = ed.doc.elements.single { it.kind == ElementKind.POINT }
        // the anchor sits at (0,0), which is exactly a corner of the imported plate's outline
        assertClose(anchorAt(ed).x, 0.0, 1e-9)
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.selectedElements.singleOrNull() === anchor, "the point takes the click, not the outline under it")
        // and one more click at the same place cycles onward to the body, so nothing is unreachable
        ed.click(Vec2(0.0, 0.0))
        assertTrue(ed.selectedElements.singleOrNull()?.kind == ElementKind.SOLID, "the cycle reaches the body next")
    }

    // ---- the file the drawing writes: the mesh is a literal in it, and it round-trips exactly ----

    /** Save, load, save: the same bytes, and the mesh that came back is the mesh that went in. */
    @Test
    fun theEmbeddedMeshSurvivesSaveAndLoadUnchanged() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        val mesh = onlyBody(ed).mesh

        val text = DocumentFormat.save(ed.doc)
        assertTrue(text.lines().any { it.startsWith("import ") }, "the body is one `import` step")
        assertTrue(text.contains("mesh="), "and the step carries the extracted mesh, never the file's bytes")
        assertFalse(text.contains(".jt bytes"), "nothing of the original file is stored")

        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
        val back = ExportScene.extract(reloaded, "probe").nodes.single().mesh
        assertEquals(mesh.vertices, back.vertices, "every vertex came back bit-identical")
        assertEquals(mesh.triangles, back.triangles, "and every triangle")
    }

    /** An import is **one** checkpoint: one undo takes every body a file brought with it. */
    @Test
    fun undoRemovesEverythingOneImportBrought() {
        val jt = Exports.export(drilledPlate().doc, "probe", ExportFormat.JT)
        val ed = Editor()
        val before = ed.doc.elements.size
        assertTrue(ed.importFile(jt.bytes!!, "probe.jt").ok)
        assertTrue(ed.doc.elements.size > before, "something arrived")
        assertTrue(ed.canUndo)
        assertTrue(ed.undo())
        assertEquals(before, ed.doc.elements.size, "one undo, and the whole import is gone")
        assertTrue(ed.doc.elements.none { it.kind == ElementKind.SOLID })
    }

    // ---- the Siemens fixture: bytes we did not produce ----

    /**
     * **A real file, written by NX** — 36 bodies over 269 000 triangles, every one of them either watertight
     * or refused by name, every one named, and the whole assembly surviving save → load → save unchanged.
     *
     * Assertions are deliberately about the *whole* and about a *named part*, not about every body: the
     * import is not subset, the checking is.
     */
    @Test
    fun theSiemensAssemblyImportsWhole() {
        if (!fixture.isFile) return // the sibling's committed fixture; nothing to say without it
        val ed = Editor()
        val result = ed.importFile(fixture.readBytes(), fixture.name)
        assertTrue(result.ok, result.message)
        assertTrue(result.bodies.size > 0, "a real assembly has bodies")
        assertEquals(36, result.bodies.size, "one body per geometry-bearing path — instances included")
        assertEquals(emptyList(), result.refusals, "every body NX tessellated passes the watertight gate")
        // the five wireframe-only parts are skipped and *named*, never silently dropped
        assertEquals(5, result.notes.count { it.contains("wireframe only") }, result.notes.toString())

        val scene = ExportScene.extract(ed.doc, "nist")
        assertEquals(36, scene.nodes.size, "each body is one export node; the literals beside them are material")
        assertTrue(scene.nodes.all { it.name.isNotEmpty() }, "every body carries the file's name for it")
        assertTrue(scene.triangleCount > 100_000, "the finest LOD came in: ${scene.triangleCount} triangles")
        val volume = scene.nodes.sumOf { Geom3.volume(it.mesh) }
        assertTrue(volume > 0.0, "the assembly encloses positive volume ($volume mm³)")
        assertTrue(scene.nodes.all { Geom3.volume(it.mesh) > 0.0 }, "and so does every body of it")
        // ten instances of one nut are ten bodies at ten places, uniquified by the naming authority
        val nuts = scene.nodes.filter { it.name.startsWith("90591A141") }
        assertEquals(10, nuts.size, "the shared part is instanced ten times, and each instance is placed")
        assertEquals(10, nuts.map { centroid(it.mesh) }.distinct().size, "each at its own place")

        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal on a real assembly")
        val again = ExportScene.extract(reloaded, "nist")
        assertEquals(scene.nodes.map { it.name }, again.nodes.map { it.name })
        for ((a, b) in scene.nodes.zip(again.nodes)) {
            assertEquals(a.mesh.vertices, b.mesh.vertices, "${a.name}: every vertex bit-identical after a reload")
            assertEquals(a.mesh.triangles, b.mesh.triangles, "${a.name}: and every triangle")
        }
    }

    /** Millimetres are declared in that file, so nothing is scaled — and the numbers say so. */
    @Test
    fun theSiemensAssemblyArrivesInMillimetres() {
        if (!fixture.isFile) return
        val scene = readScene(fixture.readBytes(), LodPolicy.FINEST_ONLY)
        assertEquals(LengthUnit.MILLIMETERS, scene.units)
        val ed = Editor()
        assertTrue(ed.importFile(fixture.readBytes(), fixture.name).ok)
        val body = ExportScene.extract(ed.doc, "nist").nodes.first { it.name.startsWith("NIST-mtc-crada-box") }
        // the box is the assembly's largest part; a few tens of millimetres across, not metres and not microns
        val span = spanOf(body.mesh)
        assertTrue(span in 20.0..500.0, "the box is $span mm across — millimetres, unconverted")
    }

    // ---- open shells: imported, flagged, and answered for by each consumer (the user's design) ----

    /**
     * A 100 × 100 × 40 plate with **one wall facet missing** — geometry that is "not ideal" rather than
     * obviously not a solid — written to real JT bytes through the sibling's own writer.
     */
    private fun crackedPlateBytes(): ByteArray {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(100.0, 100.0))
        ed.activeScalar = ed.doc.newParameter("t", constructit.units.Quantity.mm(40.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(50.0, 0.0))
        val whole = ExportScene.extract(ed.doc, "plate").nodes.single().mesh
        // a *side* facet: its three corners do not share one z, so the top face stays whole and the body
        // still projects to its full square — a crack in a wall, which is what a real "not ideal" file has
        val wall = whole.triangles.first { t -> setOf(whole.vertices[t.a].z, whole.vertices[t.b].z, whole.vertices[t.c].z).size > 1 }
        val cracked = constructit.geom.Mesh3(whole.vertices, whole.triangles.filter { it !== wall })
        assertNotNull(Imports.openShellDefect(cracked), "the fixture really is an open shell")
        return constructit.exchange.Jt.write(
            ExportScene("plate", listOf(constructit.exchange.ExportNode("rohteil", cracked, Appearance.DEFAULT))),
        )
    }

    /**
     * **An open shell imports.** It arrives, it is placed, it is drawn and pickable, it survives the file —
     * and it is flagged, by name, in the one line the status bar shows. The reversal of the old
     * watertight-or-refused gate at the import boundary, in the user's own framing: refusing it is right for
     * printing and useless for re-engineering or arranging.
     */
    @Test
    fun anOpenShellImportsPlacesDrawsAndSaysSo() {
        val ed = Editor()
        val result = ed.importFile(crackedPlateBytes(), "rohteil.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("rohteil"), result.bodies, "it came in like any other body")
        assertEquals(emptyList(), result.refusals, "and it is not a refusal")
        assertEquals(listOf("rohteil"), result.openShells, "it is flagged")
        assertTrue(result.message.contains("rohteil is an open shell"), result.message)
        assertTrue(result.message.contains("display and arrangement only"), result.message)

        // it is a body of the drawing: visible, placed, measurable, and drawn with a plan of its own
        val body = ExportScene.extract(ed.doc, "probe").nodes.single()
        assertEquals("rohteil", body.name)
        assertTrue(body.mesh.triangleCount > 0)
        val placed = ed.doc.elements.last { it.kind == ElementKind.SOLID }

        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(placed.ref as SolidRef).feature as constructit.geom.Feature3.Imported
        assertNotNull(feature.openShell, "the flag rode the placement")
        assertTrue(feature.plan.isNotEmpty(), "and the body has a plan, so it is drawn and pickable")
        // the crack is in a wall, so what the body projects to is still its whole square
        val outer = feature.plan.maxByOrNull { abs(areaOf(it)) }!!
        assertClose(abs(areaOf(outer)), 100.0 * 100.0, 1e-6, "the plan is the square the plate occupies")

        // the state is said wherever the element is named — the inspector header and the pick-cycle line
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(50.0, 0.0))
        assertTrue(ed.selectionLabel().contains("open shell"), "the panel says what it is: ${ed.selectionLabel()}")

        // ...and it is pickable and draggable exactly like a closed one
        val from = anchorAt(ed)
        ed.drag(from, Vec2(from.x + 30.0, from.y + 10.0))
        val moved = ExportScene.extract(ed.doc, "probe").nodes.single()
        assertTrue(abs(anchorAt(ed).x - from.x) > 1.0, "the anchor moved")
        assertClose(
            centroid(moved.mesh).x,
            centroid(body.mesh).x + (anchorAt(ed).x - from.x),
            1e-6,
            "and the body followed it",
        )
    }

    /** The flag is **derived**, so a reload re-derives it from the same triangles — and the file is stable. */
    @Test
    fun theOpenShellFlagSurvivesAReloadBecauseItIsDerived() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedPlateBytes(), "rohteil.jt").ok)
        val text = DocumentFormat.save(ed.doc)
        assertFalse(text.contains("shell"), "nothing about the flag is recorded — it is a function of the mesh")
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")

        @Suppress("UNCHECKED_CAST")
        val feature =
            Evaluator().solid(reloaded.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)
                .feature as constructit.geom.Feature3.Imported
        assertNotNull(feature.openShell, "and the reloaded body says the same thing, from the same triangles")
    }

    /**
     * **The two print formats refuse the whole export and name the body; hiding it exports the rest.**
     * Printing is the one goal the user's own framing keeps the old rule for — a slicer needs an inside.
     */
    @Test
    fun printingRefusesAnOpenShellByNameAndHidingItExportsTheRest() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedPlateBytes(), "rohteil.jt").ok)
        // a second, clean body beside it, so "the rest" is a real thing
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(200.0, 0.0))
        ed.click(Vec2(240.0, 40.0))
        ed.activeScalar = ed.doc.newParameter("d", constructit.units.Quantity.mm(10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(220.0, 0.0))
        assertEquals(2, ExportScene.extract(ed.doc, "p").nodes.size)

        for (format in listOf(ExportFormat.THREE_MF, ExportFormat.STL)) {
            val out = Exports.export(ed.doc, "p", format)
            assertFalse(out.ok, "${format.label} must refuse: ${out.message}")
            assertTrue(out.message.contains("rohteil"), "by name: ${out.message}")
            assertTrue(out.message.contains("open shell"), out.message)
            assertTrue(out.message.contains("hide it"), "with the way out: ${out.message}")
        }
        // GLB and JT write it — with a note, because a file that quietly claims to be a solid is the surprise
        for (format in listOf(ExportFormat.GLB, ExportFormat.JT)) {
            val out = Exports.export(ed.doc, "p", format)
            assertTrue(out.ok, "${format.label}: ${out.message}")
            assertTrue(out.message.contains("rohteil is an open shell"), "with a note: ${out.message}")
        }

        // hide the shell, and both print formats export the rest
        val shell = ed.doc.elements.first { ed.doc.userNameOf(it) == "rohteil" }
        assertEquals(1, ed.doc.setElementsVisible(listOf(shell), false))
        for (format in listOf(ExportFormat.THREE_MF, ExportFormat.STL)) {
            val out = Exports.export(ed.doc, "p", format)
            assertTrue(out.ok, "${format.label} after hiding: ${out.message}")
            assertTrue(out.message.contains("1 solid"), out.message)
            assertTrue(out.bytes!!.size > 100)
        }
    }

    /**
     * **A boolean refuses an open shell, by name** — a boolean asks what is *inside* each solid, and a
     * surface that does not close has no inside. The gesture says so and builds nothing.
     */
    @Test
    fun aBooleanAgainstAnOpenShellRefusesByName() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedPlateBytes(), "rohteil.jt").ok)
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(80.0, 80.0))
        ed.click(Vec2(140.0, 140.0))
        ed.activeScalar = ed.doc.newParameter("d", constructit.units.Quantity.mm(10.0))
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(110.0, 80.0))
        val before = ed.doc.elements.count { it.kind == ElementKind.SOLID }

        ed.setTool(Tools.SUBTRACT)
        ed.click(Vec2(50.0, 0.0)) // the open shell
        ed.click(Vec2(110.0, 140.0)) // the clean block
        assertEquals(before, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "nothing was built")
        assertTrue(ed.statusHint.contains("open shell"), "and the gesture said why: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("rohteil"), "by name: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("watertight"), ed.statusHint)

        // the node refuses too, so a route that skips the gesture cannot build a silently wrong boolean
        @Suppress("UNCHECKED_CAST")
        val shell = ed.doc.elements.last { it.kind == ElementKind.SOLID && ed.doc.userNameOf(it) == "rohteil" }.ref as SolidRef

        @Suppress("UNCHECKED_CAST")
        val block = ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef
        val direct = ed.doc.cx.subtract(shell, block)
        val result = Evaluator().eval(direct.node)
        assertTrue(result is constructit.core.EvalResult.Invalid, "the node is invalid, not silently wrong")
        assertTrue((result as constructit.core.EvalResult.Invalid).reason.contains("open shell"), result.reason)
    }

    /**
     * **An outline that cannot close is said by being open.** The chains a silhouette walks close for every
     * consistently-wound mesh, shell or solid — the boundary of a chain is a cycle. What breaks that is an
     * **inconsistently wound** mesh, and there the run is drawn as the polyline it traced rather than
     * discarded: an outline silently missing part of a body is the one outcome forbidden.
     */
    @Test
    fun anInconsistentlyWoundMeshDrawsOpenChainsRatherThanNothing() {
        val plane =
            constructit.geom.Plane3(
                constructit.geom.Vec3(0.0, 0.0, 0.0),
                constructit.geom.Vec3(1.0, 0.0, 0.0),
                constructit.geom.Vec3(0.0, 1.0, 0.0),
            )
        // two triangles of one square, the second wound the same way round its shared edge as the first —
        // so that edge is claimed twice in the same direction and the outline no longer balances
        val v =
            listOf(
                constructit.geom.Vec3(0.0, 0.0, 0.0),
                constructit.geom.Vec3(10.0, 0.0, 0.0),
                constructit.geom.Vec3(10.0, 10.0, 0.0),
                constructit.geom.Vec3(0.0, 10.0, 0.0),
            )
        val mesh =
            constructit.geom.Mesh3(
                v,
                listOf(constructit.geom.Tri(0, 1, 2), constructit.geom.Tri(0, 2, 3), constructit.geom.Tri(0, 2, 1)),
            )
        val plan = constructit.geom.Silhouette.of(mesh, plane)
        assertTrue(plan.isNotEmpty(), "never silently empty")
        assertTrue(plan.all { it.outer.elements.isNotEmpty() }, "and never an empty chain")
        // an open chain has one fewer segment than it has corners; a closed ring has as many as it has
        val pieces = plan.sumOf { it.outer.elements.size }
        assertTrue(pieces > 0, "the outline it could trace is drawn: $pieces pieces")
        // the whole thing is finite and deterministic — the same mesh twice is the same outline
        assertEquals(
            constructit.geom.Silhouette.of(mesh, plane).map { r -> r.outer.elements.size },
            plan.map { r -> r.outer.elements.size },
        )
    }

    /**
     * **An open shell draws its section context like any other imported body.** A working plane's context is
     * every ancestor solid's section (GitHub #9), and an imported body's section takes the *mesh* route — one
     * segment per cut triangle, chained nowhere — so an open shell needs no case of its own: what it cuts to
     * is simply the segments it has. Its inputs are refused for the reason every imported body's are.
     */
    @Test
    fun anOpenShellStillDrawsItsSectionContextAndOffersNoInputs() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedPlateBytes(), "rohteil.jt").ok)
        // a datum plane across the body, hinged on a segment drawn beside it
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(-20.0, 50.0))
        ed.click(Vec2(120.0, 50.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(50.0, 50.0))
        val space = ed.doc.activeSpace
        assertTrue(space.isDatum, "a datum plane was opened")
        val sections = ed.doc.spaceSections(space, Evaluator())
        val ours = sections.firstOrNull { ed.doc.userNameOf(it.first) == "rohteil" }
        assertNotNull(ours, "the open shell is an ancestor and it is cut: ${sections.size} section(s)")
        assertTrue(ours.second.drawn.isNotEmpty(), "and it draws — the mesh route needs no closed surface")
        assertTrue(ours.second.edges.isEmpty(), "...while offering no construction inputs, as every import does")
        assertNotNull(ours.second.inputsRefusal, "with the reason named: ${ours.second.inputsRefusal}")
    }

    // ---- the refusals, and that they speak ----

    /** Bytes that are not a JT file at all: refused, with what the reader had to say about them. */
    @Test
    fun garbageBytesAreRefusedWithAReason() {
        val ed = Editor()
        for (bytes in listOf(ByteArray(0), ByteArray(64) { it.toByte() }, "not a JT file, sorry".encodeToByteArray())) {
            val result = ed.importFile(bytes, "junk.jt")
            assertFalse(result.ok, "a non-JT file is not an import")
            assertTrue(result.refusal!!.contains("not a readable JT file"), result.refusal!!)
            assertTrue(ed.doc.elements.isEmpty(), "and nothing was left behind in the drawing")
        }
    }

    /** A truncated real file: the same refusal, with the reader's own words. */
    @Test
    fun aTruncatedFileIsRefusedWithAReason() {
        if (!fixture.isFile) return
        val ed = Editor()
        val result = ed.importFile(fixture.readBytes().copyOf(4096), fixture.name)
        assertFalse(result.ok, result.message)
        assertTrue(ed.doc.elements.isEmpty())
    }

    /**
     * **A file that declares no unit is refused, with that as the reason** — the whole file, because a length
     * with no unit is not a length.
     *
     * Asserted at the scene seam rather than on bytes, and that is not a shortcut: the sibling library's
     * *writer* refuses to write a file with `LengthUnit.UNSPECIFIED` (the same rule, from the other side), so
     * there is no way to produce such bytes with this toolchain. `Imports.importScene` is exactly the half
     * this project owns, and it is the half the refusal lives in.
     */
    @Test
    fun aFileWithNoDeclaredUnitIsRefusedForThatReason() {
        val ed = Editor()
        val result = Imports.importScene(ed.doc, sceneOf(LengthUnit.UNSPECIFIED), "unitless.jt")
        assertFalse(result.ok)
        assertTrue(result.refusal!!.contains("declares no measurement unit"), result.refusal!!)
        assertTrue(result.refusal!!.contains("JT_PROP_MEASUREMENT_UNITS"), "and it says where to fix it")
        assertTrue(ed.doc.elements.isEmpty(), "nothing was imported at any scale")

        // the same scene *with* a unit imports, which is what makes the refusal about the unit and not
        // about the geometry
        assertTrue(Imports.importScene(Editor().doc, sceneOf(LengthUnit.MILLIMETERS), "mm.jt").ok)
    }

    /** A unit that is not millimetres is honoured: an inch cube arrives 25.4 mm across. */
    @Test
    fun aFileInInchesIsScaledToMillimetres() {
        val ed = Editor()
        assertTrue(Imports.importScene(ed.doc, sceneOf(LengthUnit.INCHES), "inch.jt").ok)
        val body = ExportScene.extract(ed.doc, "probe").nodes.single()
        assertClose(spanOf(body.mesh), 25.4, 1e-4, "a unit cube in inches is 25.4 mm across")
        assertClose(Geom3.volume(body.mesh), 25.4 * 25.4 * 25.4, 1e-3)
    }

    /**
     * **A mixed file: the closed body and the open shell both come in, and only the shell is flagged** —
     * the reversal of the old watertight-or-refused gate at the import boundary (the user's design,
     * session 34). Every body of a file is in the drawing; what an open one *cannot do* is the consumers'
     * business, and each of them says so in its own words.
     */
    @Test
    fun anOpenShellComesInFlaggedBesideTheClosedBodiesOfTheSameFile() {
        val ed = Editor()
        val open = SceneNode("lid", JtMat4.IDENTITY, listOf(openBox()), emptyList(), null, emptyList())
        val closed = SceneNode("cube", JtMat4.IDENTITY, listOf(cubeMesh()), emptyList(), null, emptyList())
        val scene =
            Scene(
                LengthUnit.MILLIMETERS,
                SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(open, closed)),
                emptyList(),
            )
        val result = Imports.importScene(ed.doc, scene, "half.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("lid", "cube"), result.bodies, "both bodies came in, in file order")
        assertEquals(emptyList(), result.refusals, "an open shell is no longer a refusal")
        assertEquals(listOf("lid"), result.openShells, "and only the open one is flagged")
        assertTrue(result.message.contains("lid is an open shell"), "the message names it: ${result.message}")
        assertTrue(result.message.contains("3MF/STL"), "...and says what it cannot do: ${result.message}")

        // both are in the drawing, both are drawn and pickable, and each answers for itself
        val bodies = ExportScene.extract(ed.doc, "probe").nodes
        assertEquals(listOf("lid", "cube"), bodies.map { it.name })
        assertTrue(bodies.all { it.mesh.triangleCount > 0 })
        assertNotNull(Imports.openShellDefect(bodies[0].mesh), "the lid is a shell")
        assertNull(Imports.openShellDefect(bodies[1].mesh), "the cube is a solid")
    }

    /** A wireframe-only part is skipped and named — never silently dropped. */
    @Test
    fun aWireframeOnlyPartIsSkippedAndNamed() {
        val ed = Editor()
        val wire =
            SceneNode(
                "centreline",
                JtMat4.IDENTITY,
                emptyList(),
                listOf(de.haumacher.kotlinjt.scene.PolylineSet(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f)), listOf(listOf(0, 1)))),
                null,
                emptyList(),
            )
        val cube = SceneNode("cube", JtMat4.IDENTITY, listOf(cubeMesh()), emptyList(), null, emptyList())
        val scene =
            Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(wire, cube)), emptyList())
        val result = Imports.importScene(ed.doc, scene, "wire.jt")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("cube"), result.bodies)
        assertEquals(listOf("centreline is wireframe only (no triangles) — not imported"), result.notes)
    }

    /**
     * **Unnamed parts get a stand-in that tells them apart** — a position in the *file*, not in the result.
     *
     * A real KUKA robot file (`KR360L240-1.jt`, written by NetAllied's writer from a CATIA `.cgr`) leaves
     * every one of its 17 shape nodes unnamed and holds five wireframe-only parts among eleven meshes. The
     * stand-in used to count the bodies *taken*, so every skipped part read the same number and all five
     * notes said `body12` — five different parts of one file under one name, in the very message whose job
     * is to say which part was not imported.
     */
    @Test
    fun unnamedPartsAreNumberedByPositionInTheFileSoSkippedOnesStayDistinct() {
        fun wire(at: Float) =
            SceneNode(
                "",
                JtMat4.IDENTITY,
                emptyList(),
                listOf(de.haumacher.kotlinjt.scene.PolylineSet(listOf(Vec3(at, 0f, 0f), Vec3(at + 1f, 0f, 0f)), listOf(listOf(0, 1)))),
                null,
                emptyList(),
            )

        fun body() = SceneNode("", JtMat4.IDENTITY, listOf(cubeMesh()), emptyList(), null, emptyList())
        // meshes and wireframe interleaved, exactly as a real assembly mixes them
        val kids = listOf(body(), wire(0f), wire(10f), body(), wire(20f))
        val scene =
            Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, kids), emptyList())
        val result = Imports.importScene(Editor().doc, scene, "robot.jt")
        assertTrue(result.ok, result.message)

        val skipped = result.notes.filter { it.contains("wireframe only") }
        assertEquals(3, skipped.size, "all three are named, never silently dropped")
        assertEquals(3, skipped.distinct().size, "and no two of them share a name: $skipped")
        // the number is the node's place among the file's geometry-bearing nodes, so it interleaves
        assertEquals(
            listOf("body2 is wireframe only (no triangles) — not imported", "body3 is wireframe only (no triangles) — not imported", "body5 is wireframe only (no triangles) — not imported"),
            skipped,
        )
        assertEquals(listOf("body1", "body4"), result.bodies, "and the bodies keep their own places")
    }

    /**
     * **A transform that is not a placement is baked into the vertices, and said so** — a scale is not a
     * rigid motion, so it cannot become one, and approximating it as one would move somebody's geometry.
     */
    @Test
    fun aNonRigidTransformIsBakedAndNamed() {
        val ed = Editor()
        val doubled =
            JtMat4(
                listOf(
                    2.0, 0.0, 0.0, 0.0,
                    0.0, 2.0, 0.0, 0.0,
                    0.0, 0.0, 2.0, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        val cube = SceneNode("cube", doubled, listOf(cubeMesh()), emptyList(), null, emptyList())
        val scene = Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(cube)), emptyList())
        val result = Imports.importScene(ed.doc, scene, "scaled.jt")
        assertTrue(result.ok, result.message)
        assertEquals(1, result.notes.size, result.notes.toString())
        assertTrue(result.notes.single().contains("scales, shears or mirrors it"), result.notes.single())
        // baked *correctly*: the doubled cube is 2 mm across and encloses 8 mm³
        val body = ExportScene.extract(ed.doc, "probe").nodes.single()
        assertClose(spanOf(body.mesh), 2.0, 1e-9)
        assertClose(Geom3.volume(body.mesh), 8.0, 1e-9)
    }

    /**
     * **A rigid file pose lands in the live nodes where it can, and in the body's own frame where it
     * cannot** — the placement's editable form is a point and a turn in a plane, which reaches a plan
     * placement whole and a tilt not at all.
     */
    @Test
    fun aPlanPlacementBecomesTheLiveNodesAndATiltDoesNot() {
        // a quarter turn about Z, then a shift in the plan: the whole pose is state
        val quarter =
            JtMat4(
                listOf(
                    0.0, 1.0, 0.0, 0.0,
                    -1.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    12.0, -5.0, 0.0, 1.0,
                ),
            )
        val ed = Editor()
        val cube = SceneNode("cube", quarter, listOf(cubeMesh()), emptyList(), null, emptyList())
        assertTrue(
            Imports.importScene(
                ed.doc,
                Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(cube)), emptyList()),
                "turned.jt",
            ).ok,
        )
        val anchor = ed.doc.elements.single { it.kind == ElementKind.POINT }
        assertClose(ed.doc.restatedPosition(anchor, ed.doc.journal.size, Evaluator())!!.x, 12.0, 1e-9, "the shift is the anchor point")
        assertClose(ed.doc.restatedPosition(anchor, ed.doc.journal.size, Evaluator())!!.y, -5.0, 1e-9)
        val turn = ed.doc.scalars.single { it.name.startsWith("place-angle") }
        assertClose(
            (Evaluator().eval(turn.ref.node) as constructit.core.EvalResult.Ok).let { (it.value as constructit.core.ScalarValue).q.deg },
            90.0,
            1e-9,
            "the turn about Z is the angle parameter — state restated as a value",
        )
        assertFalse(DocumentFormat.save(ed.doc).contains("pose="), "and nothing is left over to record as a pose")

        // a tilt: what the plan-space anchor cannot say stays with the literal, verbatim
        val tilted =
            JtMat4(
                listOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, -1.0, 0.0, 0.0,
                    13.0, -7.0, 40.0, 1.0,
                ),
            )
        val ed2 = Editor()
        val lifted = SceneNode("cube", tilted, listOf(cubeMesh()), emptyList(), null, emptyList())
        assertTrue(
            Imports.importScene(
                ed2.doc,
                Scene(LengthUnit.MILLIMETERS, SceneNode("asm", JtMat4.IDENTITY, emptyList(), emptyList(), null, listOf(lifted)), emptyList()),
                "tilted.jt",
            ).ok,
        )
        assertTrue(DocumentFormat.save(ed2.doc).contains("pose="), "the part the anchor cannot say is recorded as the pose")
        // the *in-plane shift* still lands in the anchor, so the point a user drags sits under its body
        assertClose(anchorAt(ed2).x, 13.0, 1e-9, "the shift is the anchor point even when the rest cannot be")
        assertClose(anchorAt(ed2).y, -7.0, 1e-9)
        // either way the body is where the file put it — which is the whole point of splitting the pose
        val body = ExportScene.extract(ed2.doc, "probe").nodes.single()
        assertClose(centroid(body.mesh).x, 13.5, 1e-9, "the unit cube landed where the file put it")
        assertClose(centroid(body.mesh).y, -7.5, 1e-9)
        assertClose(centroid(body.mesh).z, 40.5, 1e-9, "40 mm up, tilted onto its side — the residual's doing")
        // and it is still parametric: dragging the anchor moves the tilted body too
        val was = anchorAt(ed2)
        ed2.drag(was, Vec2(was.x + 9.0, was.y))
        val now = anchorAt(ed2)
        assertTrue(abs(now.x - was.x) > 1.0, "the drag actually moved the anchor")
        assertClose(
            centroid(ExportScene.extract(ed2.doc, "probe").nodes.single().mesh).x,
            centroid(body.mesh).x + (now.x - was.x),
            1e-9,
        )
    }

    // ---- fixtures made by hand, so a scene can say exactly one thing ----

    /** A 1×1×1 cube at the origin, watertight, as the library's dual-indexed mesh. */
    private fun cubeMesh(): Mesh {
        val p =
            listOf(
                Vec3(0f, 0f, 0f),
                Vec3(1f, 0f, 0f),
                Vec3(1f, 1f, 0f),
                Vec3(0f, 1f, 0f),
                Vec3(0f, 0f, 1f),
                Vec3(1f, 0f, 1f),
                Vec3(1f, 1f, 1f),
                Vec3(0f, 1f, 1f),
            )
        val faces =
            listOf(
                // outward-wound, counter-clockwise seen from outside
                intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),
                intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
                intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
                intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
                intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
                intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
            )
        return Mesh(p, emptyList(), faces.map { Mesh.Triangle(it[0], it[1], it[2], -1, -1, -1) })
    }

    /**
     * A 1×1×1 box with **one triangle missing** — the shape the user's case is about: geometry that is
     * "not ideal", a real body with a hole in its surface, rather than a scrap that is obviously not a solid.
     */
    private fun openBox(): Mesh {
        val whole = cubeMesh()
        return Mesh(whole.positions, whole.normals, whole.triangles.drop(1))
    }

    /** A single square: a surface with a boundary all the way round — the simplest open shell there is. */
    private fun openSquare(): Mesh {
        val p = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 1f, 0f))
        return Mesh(p, emptyList(), listOf(Mesh.Triangle(0, 1, 2, -1, -1, -1), Mesh.Triangle(0, 2, 3, -1, -1, -1)))
    }

    /** One cube, one node, one declared [unit] — the smallest scene that says something about units. */
    private fun sceneOf(unit: LengthUnit): Scene =
        Scene(
            unit,
            SceneNode(
                "asm",
                JtMat4.IDENTITY,
                emptyList(),
                emptyList(),
                null,
                listOf(SceneNode("cube", JtMat4.IDENTITY, listOf(cubeMesh()), emptyList(), Material(Color(0.5f, 0.5f, 0.5f, 1f), 0.5f, 0f), emptyList())),
            ),
            emptyList(),
        )

    /** Twice-the-signed-area over two, for a loop of straight pieces — what says which loop is the outline. */
    private fun areaOf(region: constructit.geom.Region): Double {
        val pts = region.outer.elements.filterIsInstance<constructit.geom.ProfileElement.Seg>().map { it.segment.a }
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    /** The centre of a mesh's bounding box — a cheap, exact statement about *where* a body is. */
    private fun centroid(mesh: Mesh3): constructit.geom.Vec3 {
        val xs = mesh.vertices.map { it.x }
        val ys = mesh.vertices.map { it.y }
        val zs = mesh.vertices.map { it.z }
        return constructit.geom.Vec3(
            (xs.min() + xs.max()) / 2.0,
            (ys.min() + ys.max()) / 2.0,
            (zs.min() + zs.max()) / 2.0,
        )
    }

    /** The largest side of a mesh's bounding box — what says which unit its numbers are in. */
    private fun spanOf(mesh: Mesh3): Double {
        val xs = mesh.vertices.map { it.x }
        val ys = mesh.vertices.map { it.y }
        val zs = mesh.vertices.map { it.z }
        return maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min())
    }

    private fun assertClose(
        actual: Double,
        expected: Double,
        tol: Double,
        msg: String = "",
    ) = assertTrue(abs(actual - expected) <= tol, "expected $expected but was $actual. $msg")
}
