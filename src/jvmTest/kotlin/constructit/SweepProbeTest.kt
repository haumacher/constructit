package constructit

import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.exchange.ExportScene
import constructit.geom.Geom3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of OP-26 step 2** — the sweep composed with the rest of the drawing.
 *
 * The delivery's own suite proves the frame and the mesh against themselves: transport, tessellation, the
 * mitre, the refusals. These ask what happens when a swept solid has to be an *ordinary* solid among the
 * others: carried by a path that spans two spaces, hollow, shared by two bodies at once, and standing next
 * to a body that arrived from outside.
 */
class SweepProbeTest {
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

    private fun solids(ed: Editor) = ed.doc.elements.filter { it.kind == ElementKind.SOLID }

    private fun meshOf(
        ed: Editor,
        el: constructit.editor.Element,
    ) = (Evaluator().valueOf(el.ref) as SolidValue).solid.mesh

    /** Three points and the curve through them, in the active space. */
    private fun curveThrough(
        ed: Editor,
        vararg at: Vec2,
    ) {
        ed.setTool(Tools.POINT)
        at.forEach { ed.click(it) }
        ed.setTool(Tools.CURVE3)
        at.forEach { ed.click(it) }
        ed.key("Enter")
    }

    // ---- a path that spans two spaces ----

    /**
     * **A run that leaves the plan and climbs a datum is still one run.** The frame's start reference and
     * the footprint's projection are both read off *the curve's own space* — one statement about which
     * space a run belongs to — and a path whose points live in two spaces is exactly the case where that
     * statement has to be unambiguous rather than merely true. The tube must build, be watertight, and be
     * the solid the drawing thinks it is.
     */
    @Test
    fun aTubeAlongACurveThatSpansTwoSpacesIsOneWatertightSolid() {
        val ed = Editor()
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(200.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(100.0, 0.0))
        assertTrue(ed.doc.activeSpace.isDatum)
        val datum = ed.doc.activeSpace.name
        ed.setTool(Tools.POINT)
        ed.click(Vec2(60.0, 80.0))

        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.setTool(Tools.POINT)
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(20.0, 0.0))

        // the datum pick first, the plan picks last, so the curve is stamped into the plan and is
        // addressable there — the space a run belongs to is the space it was finished in
        ed.setTool(Tools.CURVE3)
        assertTrue(ed.setActiveSpace(datum))
        ed.click(Vec2(60.0, 80.0))
        assertTrue(ed.setActiveSpace(Document.PLAN_SPACE))
        ed.click(Vec2(-60.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.key("Enter")
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.SPACE_CURVE }, "the run was built")

        // through the *tool*, so the step is recorded and the body survives the file
        ed.setTool(Tools.TUBE)
        ed.type("6")
        ed.click(Vec2(-20.0, 0.0))
        val tube = assertNotNull(solids(ed).lastOrNull(), "the tube was built: ${ed.statusHint}")
        val mesh = meshOf(ed, tube)
        assertManifold(mesh, "a tube along a two-space run")
        assertTrue(Geom3.volume(mesh) > 0.0)
        // it really left the plan: the run climbs onto the datum, so the body does
        assertTrue(mesh.vertices.maxOf { it.z } > 20.0, "the tube follows the run up off the plan")

        val text = DocumentFormat.save(ed.doc)
        val reloaded = DocumentFormat.load(text)
        assertEquals(text, DocumentFormat.save(reloaded), "save → load → save is byte-equal")
        val back = ExportScene.extract(reloaded, "p").nodes.single()
        assertEquals(mesh.vertices, back.mesh.vertices, "every vertex came back bit-identical")
    }

    // ---- a swept solid in the 2D view ----

    /**
     * **A swept solid is pickable in the plan by its own footprint, and orbiting it costs nothing.** Two
     * things that are only true if the sweep joined the drawing properly rather than merely producing a
     * mesh: its plan hint is real geometry a click can find (so it fills a `SOLID` slot like any other
     * body), and it enters session 35's upload gate without making the view do work per frame or per hover.
     */
    @Test
    fun aSweptSolidIsPickableInThePlanAndCostsAnOrbitNothing() {
        val ed = Editor()
        curveThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(200.0, 0.0))
        val curve = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val tube = assertNotNull(ed.doc.tubeAlongCurve(curve, ed.doc.newParameter("r", 15.0.mm).ref))
        assertManifold(meshOf(ed, tube), "the tube")

        // the footprint is geometry, not decoration: a click on it in the plan reaches the body
        ed.setTool(Tools.SELECT)
        ed.click(Vec2(100.0, 15.0))
        assertTrue(
            ed.selectionLabel().contains("solid"),
            "a click on the tube's plan footprint found the solid: ${ed.selectionLabel()}",
        )

        // and the view's per-frame contract still holds with a swept body in it (session 35)
        val proj =
            constructit.editor.PlanePerspective(
                constructit.geom.Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
                constructit.editor.Viewport3().camera,
                900.0,
                700.0,
            )
        val rec = CountingTarget()
        ed.pointing = proj
        ed.draw(rec, 900.0, 700.0)
        assertTrue(rec.points > 4, "the frame drew the run and its body: ${rec.points} points")
        assertEquals(1, proj.matrixBuilds, "one view-projection matrix for the whole frame")

        val sync = constructit.editor.Scene3Sync()
        sync.update(constructit.editor.Scene3.extract(ed.doc)) { }
        assertEquals(1, sync.uploads, "the first look uploads")
        repeat(8) { sync.update(constructit.editor.Scene3.extract(ed.doc)) { } }
        assertEquals(1, sync.uploads, "an unchanged drawing never again — an orbit is free")
        ed.doc.setParameter(ed.doc.scalars.first { it.name == "r" }, 22.0.mm)
        sync.update(constructit.editor.Scene3.extract(ed.doc)) { }
        assertEquals(2, sync.uploads, "...while a fatter tube really is new vertex data")
    }

    /** Counts what a frame emits, so the assertions above are about work rather than about a clock. */
    private class CountingTarget : constructit.editor.DrawTarget {
        var points = 0

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: constructit.editor.Style,
        ) {
            this.points += points.size
        }

        override fun polygon(
            points: List<Vec2>,
            style: constructit.editor.Style,
        ) {
            this.points += points.size
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: constructit.editor.Style,
        ) = Unit

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) = Unit

        override fun text(
            at: Vec2,
            text: String,
            style: constructit.editor.Style,
            anchor: constructit.editor.TextAnchor,
        ) = Unit

        override fun end() = Unit
    }

    // ---- one path, two bodies ----

    /**
     * **Two tubes on one route follow it together, because they share its node.** Sharing *is* equality
     * here, so a route that is edited once moves everything built on it — the property the whole DAG exists
     * for, asked of a feature that produces a mesh rather than a curve.
     */
    @Test
    fun twoTubesOnOneRouteBothFollowWhenTheRouteIsEdited() {
        val ed = Editor()
        curveThrough(ed, Vec2(0.0, 0.0), Vec2(100.0, 60.0), Vec2(200.0, 0.0))
        val curve = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val thin = assertNotNull(ed.doc.tubeAlongCurve(curve, ed.doc.newParameter("r1", 4.0.mm).ref))
        val fat = assertNotNull(ed.doc.tubeAlongCurve(curve, ed.doc.newParameter("r2", 9.0.mm).ref))
        assertManifold(meshOf(ed, thin), "the thin tube")
        assertManifold(meshOf(ed, fat), "the fat tube")
        val thinBefore = Geom3.volume(meshOf(ed, thin))
        val fatBefore = Geom3.volume(meshOf(ed, fat))
        val peakBefore = meshOf(ed, thin).vertices.maxOf { it.y }

        // move the route's middle point — one edit, and both bodies are downstream of it
        ed.drag(Vec2(100.0, 60.0), Vec2(100.0, 140.0))
        val peakAfter = meshOf(ed, thin).vertices.maxOf { it.y }
        assertTrue(peakAfter > peakBefore + 50.0, "the thin tube followed the route: $peakBefore -> $peakAfter")
        assertTrue(meshOf(ed, fat).vertices.maxOf { it.y } > peakBefore + 50.0, "and so did the fat one")
        assertManifold(meshOf(ed, thin), "still watertight after the edit")
        assertManifold(meshOf(ed, fat), "and so is the other")
        // a longer route means more material, and the two kept their own radii
        assertTrue(Geom3.volume(meshOf(ed, thin)) > thinBefore, "the thin tube grew with the run")
        assertTrue(Geom3.volume(meshOf(ed, fat)) > fatBefore, "and the fat one")
        assertTrue(Geom3.volume(meshOf(ed, fat)) > Geom3.volume(meshOf(ed, thin)) * 3.0, "still the fatter one")

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save → load → save is byte-equal")
    }

    // ---- a swept solid among the others ----

    /**
     * **A tube is an ordinary boolean operand — and an open shell is still not one.** Sessions 34 and 37
     * meeting: a swept solid unions with a constructed one and stays watertight, while a boolean against an
     * **imported open shell** refuses by name whichever side the swept body is on. A new way to make solids
     * must not become a new way around a refusal.
     */
    @Test
    fun aTubeUnionsWithAConstructedSolidAndIsStillRefusedAgainstAnOpenShell() {
        val ed = Editor()
        assertTrue(ed.importFile(crackedBoxBytes(), "gehaeuse.jt").ok)
        curveThrough(ed, Vec2(-200.0, 0.0), Vec2(-120.0, 40.0), Vec2(-40.0, 0.0))
        val curve = ed.doc.elements.last { it.kind == ElementKind.SPACE_CURVE }
        val tube = assertNotNull(ed.doc.tubeAlongCurve(curve, ed.doc.newParameter("r", 8.0.mm).ref))
        assertManifold(meshOf(ed, tube), "the tube")

        // a constructed block that overlaps the tube's start, and a union with it
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(-220.0, -20.0))
        ed.click(Vec2(-180.0, 20.0))
        ed.activeScalar = ed.doc.newParameter("h", 30.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(-200.0, -20.0))
        val block = solids(ed).last()
        val fused = assertNotNull(ed.doc.combineSolids(tube, block, constructit.geom.BoolOp.UNION), "${ed.doc.note}")
        assertManifold(meshOf(ed, fused), "a tube fused with a block is one watertight solid")

        // ...and the open shell is still refused, with the swept body as the *other* operand
        val shell = ed.doc.elements.last { ed.doc.userNameOf(it) == "gehaeuse" }
        assertEquals(null, ed.doc.combineSolids(fused, shell, constructit.geom.BoolOp.SUBTRACT), "refused")
        assertTrue(ed.doc.note?.contains("open shell") == true, "by name: ${ed.doc.note}")
        assertEquals(null, ed.doc.combineSolids(shell, fused, constructit.geom.BoolOp.SUBTRACT), "either way round")
        assertFalse(ed.doc.note.isNullOrEmpty(), "and it says so both times")
    }

    /** A 60 × 40 × 20 box with one bottom facet removed, as JT bytes — the session-34 open shell. */
    private fun crackedBoxBytes(): ByteArray {
        val corners =
            listOf(
                Vec3(0.0, 0.0, 0.0),
                Vec3(60.0, 0.0, 0.0),
                Vec3(60.0, 40.0, 0.0),
                Vec3(0.0, 40.0, 0.0),
                Vec3(0.0, 0.0, 20.0),
                Vec3(60.0, 0.0, 20.0),
                Vec3(60.0, 40.0, 20.0),
                Vec3(0.0, 40.0, 20.0),
            )
        val tris =
            listOf(
                constructit.geom.Tri(0, 3, 2),
                constructit.geom.Tri(4, 5, 6),
                constructit.geom.Tri(4, 6, 7),
                constructit.geom.Tri(0, 1, 5),
                constructit.geom.Tri(0, 5, 4),
                constructit.geom.Tri(1, 2, 6),
                constructit.geom.Tri(1, 6, 5),
                constructit.geom.Tri(2, 3, 7),
                constructit.geom.Tri(2, 7, 6),
                constructit.geom.Tri(3, 0, 4),
                constructit.geom.Tri(3, 4, 7),
            )
        return constructit.exchange.Jt.write(
            constructit.exchange.ExportScene(
                "probe",
                listOf(
                    constructit.exchange.ExportNode(
                        "gehaeuse",
                        constructit.geom.Mesh3(corners, tris),
                        constructit.editor.Appearance.DEFAULT,
                    ),
                ),
            ),
        )
    }
}
