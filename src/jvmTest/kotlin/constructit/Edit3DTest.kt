package constructit

import constructit.core.Evaluator
import constructit.editor.Camera3
import constructit.editor.DocumentFormat
import constructit.editor.DrawTarget
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.PlanePerspective
import constructit.editor.Scene3
import constructit.editor.SceneRenderer
import constructit.editor.Style
import constructit.editor.SvgDrawTarget
import constructit.editor.TextAnchor
import constructit.editor.Tools
import constructit.editor.Viewport3
import constructit.geom.Arc
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.Ray3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Editing in the 3D view, on the active working plane** (edit-in-3D slice 1).
 *
 * The feature is a *projection seam*, not a new controller, and this is where that claim is made good: every
 * test here drives the ordinary headless [Editor] — the same `pointerDown/Move/Up` the whole suite uses —
 * with the only difference being who turned a screen position into plane coordinates ([Editor.pointing]).
 *
 * Four things are pinned, in the order they are built on each other: the **ray seam** round-trips for every
 * camera pose and plane and *refuses* rather than inventing coordinates where it has no answer; the same
 * gestures through the 3D projection build the same **document** as through the 2D camera; the sketch
 * **renders** on the plane with arcs tessellated in plane space and projected vertex by vertex; and the
 * **pick tolerance** stays 10 screen pixels by following the local scale. The modifier gate that keeps the
 * camera reachable mid-tool is at the end.
 */
class Edit3DTest {
    // ---- the fixtures the whole file shares ----

    private val wPx = 800.0
    private val hPx = 600.0

    /** The plan, a 30° hinged datum through y = 30, and a datum parallel to the plan at offset 45. */
    private fun planes(): List<Pair<String, Plane3>> {
        val c = cos(30.0 * PI / 180.0)
        val s = sin(30.0 * PI / 180.0)
        return listOf(
            "the plan (world XY)" to Plane3(Vec3.ZERO, Vec3.X, Vec3.Y),
            "a 30 deg datum hinged on y = 30" to Plane3(Vec3(0.0, 30.0, 0.0), Vec3.X, Vec3(0.0, c, s)),
            "a datum parallel to the plan at offset 45" to Plane3(Vec3(0.0, 0.0, 45.0), Vec3.X, Vec3.Y),
        )
    }

    private fun poses(): List<Pair<String, Camera3>> =
        listOf(
            "the default pose" to Camera3(target = Vec3(50.0, 50.0, 20.0), distance = 400.0),
            "a steep pitch" to Camera3(target = Vec3(50.0, 50.0, 20.0), distance = 400.0, pitch = Camera3.MAX_PITCH * 0.98),
            "a rolled yaw" to Camera3(target = Vec3(50.0, 50.0, 20.0), distance = 400.0, yaw = 2.1, pitch = 0.4),
            "zoomed in" to Camera3(target = Vec3(50.0, 50.0, 20.0), distance = 60.0, yaw = -1.0, pitch = 0.8),
        )

    private fun proj(
        plane: Plane3,
        cam: Camera3,
    ) = PlanePerspective(plane, cam, wPx, hPx)

    // ---- 1. the ray seam ----

    /**
     * **`toPlane(toScreen(p)) == p`**, for a battery of camera poses × planes × plane points: the ray seam is
     * the exact inverse of the projection the same view draws with, which is what makes "you clicked where
     * you pointed" a property of one type rather than an agreement between two conversions.
     *
     * Points with no image (behind the eye plane at a steep pose) are skipped rather than asserted — that is
     * the *other* half of the contract, checked in [aRayThatMissesThePlaneIsARefusalNotANaN] — but a pose
     * that produced no image at all would make this test vacuous, so the count is asserted too.
     */
    @Test
    fun theRaySeamRoundTripsThroughEveryPoseAndPlane() {
        var checked = 0
        for ((poseName, cam) in poses()) {
            for ((planeName, plane) in planes()) {
                val p = proj(plane, cam)
                var hits = 0
                for (u in -80..80 step 40) {
                    for (v in -80..80 step 40) {
                        val at = Vec2(u.toDouble(), v.toDouble())
                        val screen = p.toScreen(at) ?: continue
                        val back = assertNotNull(p.toPlane(screen), "$poseName on $planeName: $at projected to $screen but the ray missed")
                        assertClose(back.x, at.x, 1e-9, "$poseName on $planeName: u of $at")
                        assertClose(back.y, at.y, 1e-9, "$poseName on $planeName: v of $at")
                        hits++
                        checked++
                    }
                }
                assertTrue(hits > 0, "$poseName on $planeName projected nothing at all")
            }
        }
        assertTrue(checked > 100, "the battery should be a battery; only $checked round trips")
    }

    /**
     * A ray that never reaches the plane is a **refusal with a reason**, never a coordinate.
     *
     * The fixture is the exact degenerate case: an eye 60 mm above the plan looking horizontally, so the
     * plan's horizon is the screen's own middle. Above it the ray is parallel or points away; below it the
     * plane is there. A click on the horizon must leave the drawing alone and say why — a NaN or a
     * ten-kilometre coordinate would place geometry where nobody pointed.
     */
    @Test
    fun aRayThatMissesThePlaneIsARefusalNotANaN() {
        val cam = Camera3(target = Vec3(0.0, 0.0, 60.0), distance = 200.0, pitch = 0.0)
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val p = proj(plan, cam)
        val horizon = Vec2(wPx / 2.0, hPx / 2.0)
        assertNull(p.toPlane(horizon), "the horizontal ray runs parallel to the plan")
        assertNull(p.toPlane(Vec2(wPx / 2.0, hPx * 0.2)), "and above it, the plane is behind the eye")
        assertNotNull(p.toPlane(Vec2(wPx / 2.0, hPx * 0.9)), "below the horizon the plan is reachable")

        // …and the ray itself, so the refusal is traced to the geometry and not to the projection's bookkeeping
        val ray = cam.unproject(horizon, wPx, hPx)
        assertClose(ray.dir.z, 0.0, 1e-12, "the central ray of a level camera is horizontal")
        assertNull(Geom3.rayPlane(ray, plan), "a horizontal ray never meets the horizontal plane")

        val ed = Editor()
        ed.pointing = p
        ed.setTool(Tools.POINT)
        ed.pointerDown(horizon)
        ed.pointerUp(horizon)
        assertEquals(0, ed.doc.elements.size, "a gesture with no plane coordinates builds nothing")
        assertTrue(ed.statusHint.contains("misses the working plane"), "and it says so: ${ed.statusHint}")

        val below = Vec2(wPx / 2.0, hPx * 0.9)
        ed.pointerDown(below)
        ed.pointerUp(below)
        assertEquals(1, ed.doc.elements.size, "…while the very same tool works where the plane is: ${ed.statusHint}")
        val at = assertNotNull(ed.doc.elements.single().let { (Evaluator().eval(it.ref.node) as? constructit.core.EvalResult.Ok)?.value })
        val point = (at as constructit.core.PointValue).p
        assertTrue(point.x.isFinite() && point.y.isFinite(), "and never a NaN: $point")
    }

    // ---- 2. the same document, drawn in either view ----

    /**
     * **The acceptance construction, clicked entirely in the 3D view, is byte-identical to the same clicks in
     * the 2D one**: the pyramid (a 100 × 100 plan square extruded to an apex 90 mm up), a datum parallel to
     * the plan at offset 45, and a segment between two corners of the section that datum cuts — the
     * section-inputs feature composed with the ray seam, on a plane that is not the plan.
     *
     * Driven through [Viewport3], i.e. the production router, so what is asserted is the real path: the
     * projection is re-read per event, which is what makes the switch to the datum's plane mid-flow work at
     * all. The 3D camera is deliberately left where it is between the two halves, so the datum's clicks go
     * through a genuinely different projection from the plan's.
     *
     * "Identical" is asserted by [assertSameConstruction] rather than by comparing bytes, for the one reason
     * given there: the two flows reach the same plane point by different floating-point routes and so agree
     * to about 1e-14 mm rather than to the last bit. Everything a construction *is* — the steps, their order,
     * the element ids, every reference and every name — is compared exactly.
     */
    @Test
    fun theSameConstructionByClickingIn3DAndIn2D() {
        val in2d = Editor()
        val in3d = Editor()
        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(50.0, 50.0, 30.0), distance = 250.0, yaw = -1.0, pitch = 1.0),
                widthPx = wPx,
                heightPx = hPx,
            )
        vp.editor = in3d
        vp.shown = true

        fun both(
            tool: String,
            digits: List<String> = emptyList(),
            clicks: List<Vec2> = emptyList(),
        ) {
            for (ed in listOf(in2d, in3d)) {
                ed.setTool(tool)
                for (d in digits) {
                    for (c in d) ed.key(c.toString())
                    ed.key("Enter")
                }
            }
            for (at in clicks) {
                val s2 = in2d.camera.worldToScreen(at)
                in2d.pointerDown(s2)
                in2d.pointerUp(s2)
                val p = assertNotNull(vp.projection(), "the 3D view has a working plane at every step")
                val s3 = assertNotNull(p.toScreen(at), "$at has an image in the 3D view")
                vp.pointerDown(s3)
                vp.pointerUp(s3)
            }
        }

        both(Tools.RECTANGLE, clicks = listOf(Vec2(0.0, 0.0), Vec2(100.0, 100.0)))
        both(Tools.EXTRUDE_TO_POINT, digits = listOf("90"), clicks = listOf(Vec2(30.0, 0.0), Vec2(50.0, 50.0)))
        assertEquals(1, in3d.doc.elements.count { it.kind == ElementKind.SOLID }, "the pyramid, clicked in 3D: ${in3d.statusHint}")

        both(Tools.SKETCH_PLANE, digits = listOf("0", "45"), clicks = listOf(Vec2(30.0, 0.0)))
        assertTrue(in3d.activeSpace.isDatum, "the 3D flow switched to the datum too: ${in3d.statusHint}")
        assertEquals(in2d.activeSpace.name, in3d.activeSpace.name)

        both(Tools.SEGMENT, clicks = listOf(Vec2(25.0, 25.0), Vec2(75.0, 25.0)))
        val seg = in3d.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val ends = assertNotNull(Evaluator().eval(seg.ref.node).let { (it as? constructit.core.EvalResult.Ok)?.value } as? constructit.core.SegmentValue)
        assertClose(ends.seg.a.x, 25.0, 1e-9, "the 3D click landed on the section's corner, not near it")
        assertClose(ends.seg.b.x, 75.0, 1e-9)

        assertSameConstruction(DocumentFormat.save(in2d.doc), DocumentFormat.save(in3d.doc))
        // the 3D flow did not merely record the same script — it recorded one that reloads
        val reloaded = DocumentFormat.load(DocumentFormat.save(in3d.doc))
        assertEquals(DocumentFormat.save(in3d.doc), DocumentFormat.save(reloaded), "save -> load -> save")
    }

    // ---- 3. the sketch drawn on the plane, in the 3D view ----

    /**
     * **An arc is tessellated in plane space and projected vertex by vertex** — and the chord count is the
     * arc's own, not the canvas'.
     *
     * The case is one where the difference is *visible*: a 600 mm arc seen from 200 mm away spans depth, so
     * the canvas' fixed 64-steps-per-turn policy (exact for a similarity, where equal angular steps are equal
     * screen steps) leaves the near end of the curve visibly polygonal. Both deviations are measured in
     * pixels against the true projected curve, and the assertion is that the one the 3D view uses is under a
     * pixel while the screen-space one is not.
     */
    @Test
    fun anArcIsTessellatedInPlaneSpaceAndProjectedPerVertex() {
        val ed = Editor()
        ed.setTool(Tools.ARC_CS)
        for (at in listOf(Vec2(0.0, 0.0), Vec2(400.0, 0.0), Vec2(0.0, 400.0))) {
            val s = ed.camera.worldToScreen(at)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        val arcEl = ed.doc.elements.last { it.kind == ElementKind.ARC }
        val arc =
            assertNotNull(
                (Evaluator().eval(arcEl.ref.node) as? constructit.core.EvalResult.Ok)?.value as? constructit.core.ArcValue,
            ).arc

        // an eye near the middle of the arc's own span: the near end is a fifth of the far end's distance, so
        // the projection is emphatically not a similarity anywhere along the curve
        val cam = Camera3(target = Vec3(200.0, 200.0, 0.0), distance = 300.0, yaw = PI / 4.0, pitch = 0.5)
        val p = proj(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), cam)
        ed.pointing = p
        val cap = Capture()
        ed.render(cap)

        // the polyline the arc became: the only one with more than two vertices (the three placed points are
        // dots, and nothing else in this drawing is a curve)
        val drawn = assertNotNull(cap.polylines.map { it.first }.maxByOrNull { it.size }, "the arc should have been drawn")
        val expected = p.arcPoints(arc).map { assertNotNull(p.toScreen(it)) }
        assertEquals(expected.size, drawn.size, "as many screen vertices as plane-space samples")
        for (i in expected.indices) {
            assertClose(drawn[i].x, expected[i].x, 1e-9, "vertex $i is the perspective projection of the plane sample")
            assertClose(drawn[i].y, expected[i].y, 1e-9)
        }

        // …and the count is the *arc's* own, at the tessellation tolerance in plane space — which is what makes
        // the drawing honest under perspective, where the canvas' fixed 64-per-turn is a statement about
        // screen steps and this arc's near end gets far more pixels per radian than its far end.
        assertTrue(
            p.arcPoints(arc).size > SceneRenderer.tessellate(arc).size,
            "the projection asks for more chords (${p.arcPoints(arc).size}) than the canvas does (${SceneRenderer.tessellate(arc).size})",
        )
        val fineMm = deviationMm(arc, p.arcPoints(arc))
        val coarseMm = deviationMm(arc, SceneRenderer.tessellate(arc))
        assertTrue(fineMm <= GeomMath.TESS_TOL_MM * 1.05, "the plane-space count meets the plane-space tolerance ($fineMm mm)")
        assertTrue(coarseMm > GeomMath.TESS_TOL_MM * 10.0, "while the screen-space count is $coarseMm mm out — 20x the tolerance")

        // in the units the eye reads it in, at this pose: the coarse polyline leaves the curve by more than a
        // pixel, so this is a case where the difference is *visible* and not merely arithmetical
        val finePx = deviationPx(arc, p, p.arcPoints(arc))
        val coarsePx = deviationPx(arc, p, SceneRenderer.tessellate(arc))
        assertTrue(finePx < 1.0, "the drawn curve stays inside a pixel of the true one ($finePx px)")
        assertTrue(coarsePx > 1.0, "while the canvas' count would show as a polygon here ($coarsePx px)")
    }

    /**
     * **A live tool preview is drawn in the 3D view too**, and projected the same way — the whole point of
     * previews being the renderer's business (`ToolDef.preview`) rather than the canvas'.
     *
     * Two shapes, because they take the two different routes through the projection: a segment's preview is a
     * polyline (per-vertex projection), and a circle's is the one primitive whose *shape* the projection
     * decides — a circle on a plane seen obliquely is an ellipse, so it is emitted as a projected ring rather
     * than as the target's own circle.
     */
    @Test
    fun theArmedToolsPreviewIsProjectedOntoThePlane() {
        val cam = Camera3(target = Vec3(50.0, 50.0, 0.0), distance = 300.0, yaw = -1.1, pitch = 0.6)
        val p = proj(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), cam)
        val ed = Editor()
        ed.pointing = p

        val from = Vec2(0.0, 0.0)
        val to = Vec2(80.0, 40.0)
        ed.setTool(Tools.SEGMENT)
        val a = assertNotNull(p.toScreen(from))
        ed.pointerDown(a)
        ed.pointerUp(a)
        val b = assertNotNull(p.toScreen(to))
        ed.pointerMove(b)
        val segCap = Capture()
        ed.render(segCap)
        val previewSeg =
            assertNotNull(
                segCap.polylines.firstOrNull { it.second.stroke == PREVIEW_COLOR && it.first.size == 2 },
                "the growing segment should be previewed: ${segCap.polylines.map { it.second.stroke }}",
            ).first
        assertClose(previewSeg[0].x, a.x, 1e-9, "the preview starts at the projection of the placed point")
        assertClose(previewSeg[0].y, a.y, 1e-9)
        assertClose(previewSeg[1].x, b.x, 1e-9, "…and ends under the cursor, which is where the plane point is")
        assertClose(previewSeg[1].y, b.y, 1e-9)

        val circleEd = Editor()
        circleEd.pointing = p
        circleEd.setTool(Tools.CIRCLE)
        circleEd.pointerDown(a)
        circleEd.pointerUp(a)
        circleEd.pointerMove(b)
        val circCap = Capture()
        circleEd.render(circCap)
        assertTrue(circCap.circles.none { it.third.stroke == PREVIEW_COLOR }, "a previewed circle is not a screen circle here")
        val ring =
            assertNotNull(
                circCap.polylines.filter { it.second.stroke == PREVIEW_COLOR }.maxByOrNull { it.first.size },
                "it is a projected ring",
            ).first
        assertTrue(ring.size >= 32, "sampled finely enough to read as a curve (${ring.size} vertices)")
        val radius = (to - from).length()
        val samples = GeomMath.sampleCircle(constructit.geom.Circle(from, radius), ring.size - 1, ccw = true)
        for (i in ring.indices) {
            val want = assertNotNull(p.toScreen(samples[i]))
            assertClose(ring[i].x, want.x, 1e-9, "ring vertex $i")
            assertClose(ring[i].y, want.y, 1e-9)
        }

        // …and this is the case where treating the projection as a similarity is *visibly* wrong: the screen
        // circle the canvas would draw (the projected centre, the radius times the local scale there) is tens
        // of pixels from the ellipse the circle really projects to.
        val centre = assertNotNull(p.toScreen(from))
        val asIfSimilar = radius * p.scaleAt(from)
        val worst = ring.maxOf { abs((it - centre).length() - asIfSimilar) }
        assertTrue(worst > 10.0, "a screen circle would be $worst px out — which is why the shape is the projection's to decide")
    }

    /**
     * The **closed-form local scale** agrees with finite differences of the projection it describes.
     *
     * `PlanePerspective.scaleAt` is analytic — the isotropic (geometric-mean) scale of the plane→screen map —
     * and it is the number a pick tolerance is divided by, so it has to be a statement about *that* map and
     * not a plausible-looking formula beside it. Measured as the square root of the area a unit square of
     * plane covers on screen, which is what "isotropic" means.
     */
    @Test
    fun theLocalScaleIsTheProjectionsOwnAreaScale() {
        for ((poseName, cam) in poses()) {
            for ((planeName, plane) in planes()) {
                val p = proj(plane, cam)
                for (at in listOf(Vec2(0.0, 0.0), Vec2(60.0, -20.0), Vec2(-40.0, 70.0))) {
                    val h = 1e-4
                    val o = p.toScreen(at) ?: continue
                    val du = (p.toScreen(at + Vec2(h, 0.0)) ?: continue) - o
                    val dv = (p.toScreen(at + Vec2(0.0, h)) ?: continue) - o
                    val measured = kotlin.math.sqrt(abs(du.cross(dv)) / (h * h))
                    val closed = p.scaleAt(at)
                    if (p.scaleClampedAt(at)) continue
                    assertClose(closed, measured, measured * 1e-5, "$poseName on $planeName at $at")
                }
            }
        }
    }

    // ---- 4. the pick tolerance, in pixels, through a scale that varies ----

    /**
     * **The tolerance is 10 screen pixels wherever the cursor is** — which under perspective means a
     * plane-space radius that changes with distance, and the rule is what keeps the *pixels* constant.
     *
     * Two statements, both measured against [Editor.pickToleranceAt] rather than against a constant of this
     * test's own: (1) a click 6 px off a segment hits at both camera distances, because 6 px is 6 px; (2) a
     * click at a fixed *millimetre* offset chosen between the two computed tolerances misses from close up
     * and hits from far away — the local-scale rule deciding, in the only direction it can.
     */
    @Test
    fun thePickToleranceFollowsTheLocalScale() {
        val plan = Plane3(Vec3.ZERO, Vec3.X, Vec3.Y)
        val on = Vec2(50.0, 0.0)

        fun editorAt(distance: Double): Editor {
            val ed = Editor()
            ed.setTool(Tools.SEGMENT)
            for (at in listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0))) {
                val s = ed.camera.worldToScreen(at)
                ed.pointerDown(s)
                ed.pointerUp(s)
            }
            ed.setTool(Tools.SELECT)
            ed.pointing = PlanePerspective(plan, Camera3(target = Vec3(50.0, 0.0, 0.0), distance = distance, pitch = 0.9), wPx, hPx)
            return ed
        }

        fun clickAt(
            ed: Editor,
            at: Vec2,
        ): Boolean {
            val p = assertNotNull(ed.pointing)
            val s = assertNotNull(p.toScreen(at))
            ed.pointerDown(s)
            ed.pointerUp(s)
            return ed.selection?.kind == ElementKind.SEGMENT
        }

        val near = editorAt(200.0)
        val far = editorAt(1200.0)
        val tolNear = near.pickToleranceAt(on)
        val tolFar = far.pickToleranceAt(on)
        assertTrue(tolFar > tolNear * 3.0, "four times as far is a much coarser plane tolerance: $tolNear vs $tolFar mm")

        // (1) six pixels off, in each view's own millimetres: a hit in both, because the rule is in pixels
        for (ed in listOf(near, far)) {
            val sixPx = 6.0 / ed.tolPx * ed.pickToleranceAt(on)
            assertTrue(clickAt(ed, on + Vec2(0.0, sixPx)), "6 px off the segment ($sixPx mm here) must hit: ${ed.statusHint}")
        }

        // (2) one millimetre offset, between the two tolerances: the scale alone decides
        val between = (tolNear + tolFar) / 2.0
        assertTrue(between > tolNear && between < tolFar, "the offset has to straddle the two tolerances")
        assertTrue(!clickAt(editorAt(200.0), on + Vec2(0.0, between)), "$between mm is outside the near view's $tolNear mm")
        assertTrue(clickAt(editorAt(1200.0), on + Vec2(0.0, between)), "…and inside the far view's $tolFar mm")
    }

    /**
     * A plane so nearly edge-on that an honest tolerance would be metres wide is **clamped, and said out
     * loud** — the one case where "the rule is in pixels" has to stop, because the pixels have run out.
     */
    @Test
    fun aDegenerateScaleIsClampedAndReported() {
        val cam = Camera3(target = Vec3(0.0, 0.0, 60.0), distance = 300.0, pitch = 0.002)
        val p = proj(Plane3(Vec3.ZERO, Vec3.X, Vec3.Y), cam)
        // just below the horizon: the plan is reachable, but at a grazing angle
        val screen = Vec2(wPx / 2.0, hPx / 2.0 + 1.0)
        val at = assertNotNull(p.toPlane(screen), "the ray does reach the plane")
        assertTrue(p.scaleClampedAt(at), "…at a scale the projection has to clamp: ${p.scaleAt(at)} px/mm")
        assertClose(p.scaleAt(at), p.nominalScale / PlanePerspective.MAX_TOLERANCE_FACTOR, 1e-12, "clamped to the stated floor")

        val ed = Editor()
        ed.pointing = p
        ed.setTool(Tools.SELECT)
        ed.pointerDown(screen)
        ed.pointerUp(screen)
        assertTrue(ed.statusHint.contains("too far away or too edge-on"), "and it says so: ${ed.statusHint}")
    }

    // ---- 5. the modifier gate ----

    private fun armed(tool: String = Tools.RECTANGLE): Pair<Editor, Viewport3> {
        val ed = Editor()
        val vp = Viewport3(camera = Camera3(target = Vec3(50.0, 50.0, 0.0), distance = 300.0), widthPx = wPx, heightPx = hPx)
        vp.editor = ed
        vp.shown = true
        ed.setTool(tool)
        return ed to vp
    }

    private fun Viewport3.at(world: Vec2): Vec2 = assertNotNull(assertNotNull(projection()).toScreen(world))

    /**
     * **The gate**: with a tool armed a plain drag is the tool's, with the modifier it is the camera's, and
     * with no tool armed the view is the read-only one it always was.
     */
    @Test
    fun theModifierDecidesWhoOwnsTheDrag() {
        val (ed, vp) = armed(Tools.POINT)
        assertTrue(vp.editing(), "a tool armed on the plan makes this an editing view")
        val yaw = vp.camera.yaw
        val a = vp.at(Vec2(0.0, 0.0))
        vp.pointerDown(a)
        vp.pointerUp(a)
        assertEquals(1, ed.doc.elements.size, "the plain click reached the tool: ${ed.statusHint}")
        assertClose(vp.camera.yaw, yaw, 1e-12, "and left the camera alone")

        vp.cameraModifier = true
        val b = vp.at(Vec2(60.0, 60.0))
        vp.pointerDown(b)
        vp.pointerMove(Vec2(b.x + 80.0, b.y - 30.0))
        vp.pointerUp(Vec2(b.x + 80.0, b.y - 30.0))
        assertTrue(abs(vp.camera.yaw - yaw) > 1e-9, "the modifier gave the drag to the camera")
        assertEquals(1, ed.doc.elements.size, "…and nothing was drawn by it")

        val (plain, view) = armed(Tools.SELECT)
        assertTrue(!view.editing(), "SELECT leaves this the read-only view it has always been")
        val c = Vec2(400.0, 300.0)
        view.pointerDown(c)
        view.pointerMove(Vec2(c.x + 60.0, c.y))
        view.pointerUp(Vec2(c.x + 60.0, c.y))
        assertClose(view.camera.yaw, Camera3().yaw - 60.0 * Viewport3.ORBIT_RAD_PER_PX, 1e-12, "the drag orbited")
        assertEquals(0, plain.doc.elements.size, "and selected nothing, which is this view's standing cut")
    }

    /**
     * **Mid-gesture semantics, both ways**: a drag belongs to whoever owned it at the press, so letting the
     * modifier go halfway through an orbit finishes the orbit rather than teleporting geometry to the cursor,
     * and pressing it halfway through the tool's drag leaves that drag alone.
     */
    @Test
    fun crossingTheModifierMidGestureDoesNotChangeHands() {
        val (ed, vp) = armed(Tools.POINT)
        vp.cameraModifier = true
        val a = vp.at(Vec2(0.0, 0.0))
        vp.pointerDown(a)
        vp.cameraModifier = false // released mid-orbit
        vp.pointerMove(Vec2(a.x + 50.0, a.y))
        vp.pointerUp(Vec2(a.x + 50.0, a.y))
        assertEquals(0, ed.doc.elements.size, "the orbit finished as an orbit; no point was placed")
        val turned = vp.camera.yaw

        // …and the other way round: the tool keeps a drag the modifier joins late
        val b = vp.at(Vec2(20.0, 20.0))
        vp.pointerDown(b)
        vp.cameraModifier = true
        vp.pointerMove(Vec2(b.x + 40.0, b.y))
        vp.pointerUp(Vec2(b.x + 40.0, b.y))
        assertEquals(1, ed.doc.elements.size, "the tool kept the gesture it was given: ${ed.statusHint}")
        assertClose(vp.camera.yaw, turned, 1e-12, "and the camera did not move for it")
    }

    /**
     * **The tool survives the detour**: an orbit in the middle of a two-click gesture leaves the collected
     * pick where it was, and the second click — through the camera the orbit left behind — completes the very
     * same segment.
     *
     * This is what "returns to the tool cleanly" means in the model rather than in the pixels: the tool's
     * state is the editor's, the camera's is the view's, and the projection is re-read per event.
     */
    @Test
    fun aToolContinuesAcrossAnOrbit() {
        val (ed, vp) = armed(Tools.SEGMENT)
        val from = Vec2(0.0, 0.0)
        val to = Vec2(80.0, 20.0)
        val a = vp.at(from)
        vp.pointerDown(a)
        vp.pointerUp(a)

        vp.cameraModifier = true
        val grab = Vec2(700.0, 100.0)
        vp.pointerDown(grab)
        vp.pointerMove(Vec2(grab.x + 120.0, grab.y + 40.0))
        vp.pointerUp(Vec2(grab.x + 120.0, grab.y + 40.0))
        vp.cameraModifier = false

        // the second click goes through the *new* pose, and the plane coordinates are still the plane's
        val b = vp.at(to)
        vp.pointerDown(b)
        vp.pointerUp(b)
        val seg = ed.doc.elements.last { it.kind == ElementKind.SEGMENT }
        val ends =
            assertNotNull(
                (Evaluator().eval(seg.ref.node) as? constructit.core.EvalResult.Ok)?.value as? constructit.core.SegmentValue,
            ).seg
        assertClose(ends.a.x, from.x, 1e-6, "the first click's point is where it was put")
        assertClose(ends.a.y, from.y, 1e-6)
        assertClose(ends.b.x, to.x, 1e-6, "and the second landed through the orbited camera")
        assertClose(ends.b.y, to.y, 1e-6)
    }

    /**
     * The **read-only** view is untouched by all of this: with no editor attached at all, the gestures are the
     * ones `Viewport3Test` has always driven, and [Viewport3.render] draws the solids and nothing else.
     */
    @Test
    fun aViewportWithNoEditorIsTheReadOnlyOne() {
        val vp = Viewport3(camera = Camera3(distance = 200.0, yaw = 0.0, pitch = 0.0), widthPx = wPx, heightPx = hPx)
        assertTrue(!vp.editing())
        assertNull(vp.projection())
        vp.pointerDown(Vec2(100.0, 100.0))
        vp.pointerMove(Vec2(160.0, 100.0))
        vp.pointerUp(Vec2(160.0, 100.0))
        assertClose(vp.camera.yaw, -60.0 * Viewport3.ORBIT_RAD_PER_PX, 1e-12, "it orbits, exactly as before")
        val cap = Capture()
        vp.render(Scene3(emptyList(), emptyList()), cap)
        assertEquals(0, cap.polylines.size, "an empty scene with no sketch draws nothing")
    }

    /**
     * **The composed editing view, as an SVG golden**: a pyramid shaded by the painter's projector, with the
     * plan's own drawing — the square it was extruded from, a circle, and the growing segment the armed tool is
     * previewing — laid on the working plane inside the same picture.
     *
     * One artifact for the whole slice, in the discipline `Painter3Test` established: the projection maths, the
     * scene extraction *and* the sketch layer go through the very same code the browser runs (which splits the
     * two layers across two canvases only because the platform makes that cheap), so a change that moves this
     * file moves the browser too. Human-inspectable, which is the point — perspective drawing is the kind of
     * thing that is either obviously right or obviously wrong on sight.
     */
    @Test
    fun theEditingViewIsAGolden() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        for (at in listOf(Vec2(0.0, 0.0), Vec2(100.0, 100.0))) {
            val s = ed.camera.worldToScreen(at)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        ed.setTool(Tools.EXTRUDE_TO_POINT)
        for (c in "90") ed.key(c.toString())
        ed.key("Enter")
        for (at in listOf(Vec2(30.0, 0.0), Vec2(50.0, 50.0))) {
            val s = ed.camera.worldToScreen(at)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }
        ed.setTool(Tools.CIRCLE)
        for (at in listOf(Vec2(50.0, 50.0), Vec2(85.0, 50.0))) {
            val s = ed.camera.worldToScreen(at)
            ed.pointerDown(s)
            ed.pointerUp(s)
        }

        val vp =
            Viewport3(
                camera = Camera3(target = Vec3(50.0, 50.0, 25.0), distance = 300.0, yaw = -1.05, pitch = 0.55, fovY = PI / 4.0),
                widthPx = 640.0,
                heightPx = 420.0,
            )
        vp.editor = ed
        vp.shown = true
        // an armed tool with one point in, hovering: the preview belongs in this picture, since it is drawn by
        // the renderer and therefore appears in whichever view is showing
        ed.setTool(Tools.SEGMENT)
        val a = vp.at(Vec2(0.0, 100.0))
        vp.pointerDown(a)
        vp.pointerUp(a)
        vp.pointerMove(vp.at(Vec2(100.0, 0.0)))

        val svg = SvgDrawTarget()
        vp.render(Scene3.extract(ed.doc), svg)
        Golden.check("edit3d-pyramid-with-its-plan-sketch", svg.svg())
    }

    // ---- helpers ----

    /**
     * The greatest screen distance between the polyline through [samples] and the arc it stands for, both
     * projected — how far the drawing is from the curve, in the units the eye reads it in.
     */
    private fun deviationPx(
        arc: Arc,
        p: PlanePerspective,
        samples: List<Vec2>,
    ): Double {
        val poly = samples.mapNotNull { p.toScreen(it) }
        var worst = 0.0
        for (t in GeomMath.sampleArc(arc, 2000)) {
            val q = p.toScreen(t) ?: continue
            var best = Double.MAX_VALUE
            for (i in 0 until poly.size - 1) best = kotlin.math.min(best, distanceToSegment(q, poly[i], poly[i + 1]))
            worst = max(worst, best)
        }
        return worst
    }

    /** The same measurement in **plane space**: how far the chords stray from the arc, in millimetres. */
    private fun deviationMm(
        arc: Arc,
        samples: List<Vec2>,
    ): Double {
        var worst = 0.0
        for (t in GeomMath.sampleArc(arc, 2000)) {
            var best = Double.MAX_VALUE
            for (i in 0 until samples.size - 1) best = kotlin.math.min(best, distanceToSegment(t, samples[i], samples[i + 1]))
            worst = max(worst, best)
        }
        return worst
    }

    private fun distanceToSegment(
        q: Vec2,
        a: Vec2,
        b: Vec2,
    ): Double {
        val d = b - a
        val len2 = d.dot(d)
        if (len2 < 1e-18) return (q - a).length()
        val t = ((q - a).dot(d) / len2).coerceIn(0.0, 1.0)
        return (q - (a + d * t)).length()
    }

    /** `SceneRenderer`'s preview colour — the one thing a capture has to recognise a preview by. */
    private val PREVIEW_COLOR = "#ff7f0e"

    /** A [DrawTarget] that records instead of drawing: what a projection test can make assertions about. */
    private class Capture : DrawTarget {
        val polylines = ArrayList<Pair<List<Vec2>, Style>>()
        val circles = ArrayList<Triple<Vec2, Double, Style>>()
        val dots = ArrayList<Vec2>()

        override fun begin(
            widthPx: Double,
            heightPx: Double,
        ) = Unit

        override fun polyline(
            points: List<Vec2>,
            style: Style,
        ) {
            polylines.add(points to style)
        }

        override fun polygon(
            points: List<Vec2>,
            style: Style,
        ) {
            polylines.add(points to style)
        }

        override fun circle(
            center: Vec2,
            radiusPx: Double,
            style: Style,
        ) {
            circles.add(Triple(center, radiusPx, style))
        }

        override fun dot(
            center: Vec2,
            radiusPx: Double,
            color: String,
        ) {
            dots.add(center)
        }

        override fun text(
            at: Vec2,
            text: String,
            style: Style,
            anchor: TextAnchor,
        ) = Unit

        override fun end() = Unit
    }

    /** Kept honest: [Ray3] is the seam's own type, so a test that never mentions it is testing something else. */
    @Test
    fun theRayIsAWorldRayFromTheEye() {
        val cam = Camera3(target = Vec3(10.0, 20.0, 30.0), distance = 250.0, yaw = 0.7, pitch = 0.4)
        val ray: Ray3 = cam.unproject(Vec2(wPx / 2.0, hPx / 2.0), wPx, hPx)
        assertClose((ray.origin - cam.eye).length(), 0.0, 1e-12, "it starts at the eye")
        assertClose(ray.dir.length(), 1.0, 1e-12, "with a unit direction, so t is millimetres")
        val f = cam.forward()
        assertClose(ray.dir.x, f.x, 1e-12, "and the central ray is the viewing direction")
        assertClose(ray.dir.y, f.y, 1e-12)
        assertClose(ray.dir.z, f.z, 1e-12)
        // …and a point down the central ray projects back to the centre of the screen
        val mid = assertNotNull(cam.project(ray.at(120.0), wPx, hPx))
        assertClose(mid.x, wPx / 2.0, 1e-9)
        assertClose(mid.y, hPx / 2.0, 1e-9)
    }
}
