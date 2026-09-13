package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoftPart
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.FacePatch
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Loop
import constructit.geom.Plane3
import constructit.geom.Region
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adversarial probe of slice 5p** (OP-31), written after the delivery and never seen by it: is a free end's
 * own cap a face of the body in every sense the drawing has — a named face with the fillet's *own* area, a
 * plane a sketch space opens on and holds through a later rounding that closes the other end with a corner,
 * through undo and through the file — and does the body's **volume** agree with the drawing, so that the
 * triangle the neighbouring face keeps past the cap (slice 5p's step) is material the tool did not take?
 */
class BlendFreeEndCapProbeTest {
    private var ids = 0

    private fun polygon(
        cx: Construction,
        pts: List<Vec2>,
    ): RegionRef {
        val ps = pts.map { cx.freePoint("P${ids++}", it.x.mm, it.y.mm) }
        return cx.region(cx.loop(*ps.indices.map { cx.segment(ps[it], ps[(it + 1) % ps.size]) }.toTypedArray()))
    }

    private fun round(
        cx: Construction,
        on: SolidRef,
        address: List<Int>,
        size: Double,
    ): SolidRef {
        val body = Evaluator().solid(on)
        val (choices, why) = Blend3.choicesFor(body, address, BlendSection(BlendKind.FILLET, size))
        val ref = cx.blendAll(on, cx.planeXY(), listOf(Construction.BlendRun(BlendKind.FILLET, cx.const(size.mm), null, address, assertNotNull(choices, "the edges round: ${why?.render()}"))))
        val r = Evaluator().eval(ref.node)
        assertTrue(r !is EvalResult.Invalid, "the rounding builds: ${(r as? EvalResult.Invalid)?.why?.render()}")
        return ref
    }

    /** The edges of [s] lying wholly at height [h], in the edge list's order. */
    private fun edgesAt(
        s: Solid3,
        h: Double,
    ): List<Int> {
        val es = assertNotNull(Section3.edges(s.feature).first, "it names its edges")
        return es.indices.filter { i ->
            val p = Blend3.edgePath(es[i]).first ?: return@filter false
            val a = p.start ?: return@filter false
            val b = p.end ?: return@filter false
            abs(a.z - h) < 1e-9 && abs(b.z - h) < 1e-9
        }
    }

    private fun areaOf(outline: List<constructit.geom.ProfileElement>): Double = abs(GeomMath.signedArea(Loop(outline)))

    private fun regionArea(regions: List<Region>): Double =
        regions.sumOf { r -> abs(GeomMath.signedArea(r.outer)) - r.holes.sumOf { abs(GeomMath.signedArea(it)) } }

    /** The world centroid of a face's tessellated outline — where the face *is*, whatever its plane's origin. */
    private fun centroid(f: FacePatch): Vec3 {
        val plane = assertNotNull(f.plane, "a flat face has a plane")
        val pts = f.outline.flatMap { GeomMath.tessellatePiece(it) }
        return pts.fold(Vec3(0.0, 0.0, 0.0)) { a, q -> a + plane.toWorld(q) } * (1.0 / pts.size)
    }

    /** The two flat-end slots of the rounding along [edge], as the body states them. */
    private fun capsOf(
        feature: Feature3,
        edge: Int,
    ): Pair<FacePatch, FacePatch> {
        val faces = assertNotNull(Section3.faces(feature).first, "it names its faces")
        val start = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendCap(edge, true) }, "the start slot exists: ${faces.map { it.name }}")
        val end = assertNotNull(faces.firstOrNull { it.name == FaceName.BlendCap(edge, false) }, "the end slot exists")
        return start to end
    }

    /**
     * The area of a fillet's own end section — a ball of radius [r] rolled into a crease of dihedral [alpha]:
     * the kite between the crease point, the two tangencies and the centre (`t·r`, with the setback
     * `t = r / tan(α/2)`) less the sector the arc bounds (`½·r²·(π − α)`).
     */
    private fun capArea(
        r: Double,
        alpha: Double,
    ): Double = r * r / tan(alpha / 2) - 0.5 * r * r * (PI - alpha)

    /**
     * **A hexagonal prism's cap is a face with the fillet's own area, at both ends, and the body lost exactly
     * that section swept along the crease.** The dihedral at a prism's top rim is a right angle, so the cap
     * is `r²(1 − π/4)`; the strip the band takes is that section times the crease — and *only* that, because
     * the triangle the neighbouring wall keeps past the cap is the body's, not the tool's.
     */
    @Test
    fun aHexagonsCapsAreTheFilletsOwnSectionAndTheVolumeAgrees() {
        val cx = Construction()
        val n = 6
        val radius = 30.0
        val pts = (0 until n).map { Vec2(radius * cos(2 * PI * it / n), radius * sin(2 * PI * it / n)) }
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), polygon(cx, pts)), cx.const(20.mm))
        val plain = Evaluator().solid(base)
        val edge = edgesAt(plain, 20.0).first()
        val r = 3.0
        val rounded = Evaluator().solid(round(cx, base, listOf(edge), r))
        assertManifold(rounded.mesh, "the hexagon with one rounded top edge")
        val (start, end) = capsOf(rounded.feature, edge)
        val expected = capArea(r, PI / 2)
        val crease = assertNotNull(Blend3.edgePath(assertNotNull(Section3.edges(plain.feature).first)[edge]).first, "the crease is a path")
        val dir = (assertNotNull(crease.end) - assertNotNull(crease.start))
        for ((which, cap) in listOf("start" to start, "end" to end)) {
            assertTrue(cap.reason == null, "the $which cap is a face and not a tombstone: ${cap.reason?.render()}")
            val plane = assertNotNull(cap.plane, "…a plane")
            assertClose(areaOf(cap.outline), expected, 1e-9, "…with the fillet's own area at the $which")
            // square to its crease: the plane's normal is the crease's own direction
            val cross = plane.normal.normalized().cross(dir.normalized())
            assertClose(cross.length(), 0.0, 1e-9, "the $which cap stands square to the crease")
            // and it is a face the drawing addresses and a sketch space opens on
            val at = assertNotNull(Section3.addressOfFace(rounded.feature, cap.name), "the $which cap has an address")
            val (patch, why) = Section3.facePatchOfFootprintPiece(rounded.feature, at)
            assertEquals(cap.name, assertNotNull(patch, "…which opens: ${why?.render()}").name, "…on that very face")
        }
        // the two caps stand at the two ends of the crease, not somewhere else
        assertClose((centroid(start) - assertNotNull(crease.start)).length(), (centroid(end) - assertNotNull(crease.end)).length(), 1e-6, "the caps sit symmetrically at the two ends")
        assertTrue((centroid(start) - assertNotNull(crease.start)).length() < r, "the start cap sits at the crease's start")
        // volume: the plain prism less the fillet's section swept along the crease. The band's arc is
        // tessellated and a chord stands on the *body's* side of its arc (the centre is in the body), so the
        // tool takes a hair **more** than the figure — a seven-chord quarter arc's sagitta area, ~3 % of the
        // section — and never less
        val taken = Geom3.volume(plain.mesh) - Geom3.volume(rounded.mesh)
        val figure = expected * dir.length()
        assertTrue(taken >= figure - 1e-9 && taken <= 1.05 * figure, "the tool took the fillet's own section along the crease, plus its chords' sagitta and nothing else: $taken vs $figure")
    }

    /**
     * **A frustum's cap has the fillet's area in the crease's own dihedral** — the leaning wall makes the
     * dihedral `90° + β`, the setback longer and the sector smaller, and the volume the band takes is that
     * section along the 60 mm crease.
     */
    @Test
    fun aFrustumsCapIsTheFilletInItsOwnDihedral() {
        val cx = Construction()
        val lo = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), Vec2(0.0, 100.0))
        val hi = listOf(Vec2(20.0, 20.0), Vec2(80.0, 20.0), Vec2(80.0, 80.0), Vec2(20.0, 80.0))
        val base =
            cx.loft(
                listOf(
                    LoftPart.Area(cx.sketchOn(cx.planeXY(), polygon(cx, lo))),
                    LoftPart.Area(cx.sketchOn(cx.planeOffset(cx.planeXY(), cx.const(60.mm)), polygon(cx, hi))),
                ),
            )
        val plain = Evaluator().solid(base)
        assertManifold(plain.mesh, "the frustum")
        val edge = edgesAt(plain, 60.0).first()
        val r = 4.0
        val rounded = Evaluator().solid(round(cx, base, listOf(edge), r))
        assertManifold(rounded.mesh, "the frustum with one rounded top edge")
        val beta = atan(20.0 / 60.0)
        val expected = capArea(r, PI / 2 + beta)
        val (start, end) = capsOf(rounded.feature, edge)
        for ((which, cap) in listOf("start" to start, "end" to end)) {
            assertTrue(cap.reason == null && cap.plane != null, "the $which cap is a face: ${cap.reason?.render()}")
            assertClose(areaOf(cap.outline), expected, 1e-9, "…with the fillet's area in the dihedral 90° + β at the $which")
        }
        val taken = Geom3.volume(plain.mesh) - Geom3.volume(rounded.mesh)
        val figure = expected * 60.0
        assertTrue(taken >= figure - 1e-9 && taken <= 1.05 * figure, "the band takes its own section along the 60 mm crease plus its chords' sagitta: $taken vs $figure")
        // …and the level section, read one more way than the delivery did: at the cap's own tangency height
        // on the wall the section is exactly the plain one (the strip is nothing there), a hair above it is not
        val t = r / tan((PI / 2 + beta) / 2)
        val zTangent = 60.0 - t * cos(beta)
        val plainArea = regionArea(assertNotNull(Section3.regionsOf(plain.feature, Plane3(Vec3(0.0, 0.0, zTangent), Vec3.X, Vec3.Y)).first))
        val atTangent = regionArea(assertNotNull(Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, zTangent), Vec3.X, Vec3.Y)).first, "closes at the tangency"))
        assertClose(atTangent, plainArea, 1e-9, "at the wall's tangency the band takes nothing")
        val above = regionArea(assertNotNull(Section3.regionsOf(rounded.feature, Plane3(Vec3(0.0, 0.0, zTangent + 0.5), Vec3.X, Vec3.Y)).first, "closes above the tangency"))
        val plainAbove = regionArea(assertNotNull(Section3.regionsOf(plain.feature, Plane3(Vec3(0.0, 0.0, zTangent + 0.5), Vec3.X, Vec3.Y)).first))
        assertTrue(above < plainAbove - 1e-6, "…and a hair above it, it does: $above vs $plainAbove")
    }

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

    @Suppress("UNCHECKED_CAST")
    private fun bodyOf(ed: Editor): Solid3 = Evaluator().solid(ed.doc.elements.last { it.kind == ElementKind.SOLID }.ref as SolidRef)

    private fun midOf(
        i: Int,
        n: Int,
        radius: Double,
    ): Vec2 {
        val a = 2 * PI * i / n
        val b = 2 * PI * (i + 1) / n
        return Vec2(radius * (cos(a) + cos(b)) / 2.0, radius * (sin(a) + sin(b)) / 2.0)
    }

    /** The face the space named [name] stands on now. */
    private fun spaceFace(
        ed: Editor,
        name: String,
    ): FacePatch {
        val space = assertNotNull(ed.doc.spaces.firstOrNull { it.name == name }, "the space $name exists: ${ed.doc.spaces.map { it.name }}")
        val on = assertNotNull(space.anchor, "…on a solid")

        @Suppress("UNCHECKED_CAST")
        val feature = Evaluator().solid(on.ref as SolidRef).feature
        val (patch, why) = Section3.facePatchOfFootprintPiece(feature, space.piece)
        return assertNotNull(patch, "…and its address is a face: ${why?.render()}")
    }

    /**
     * **A corner takes a cap away, the other cap holds still under it, and undo gives it back.** By gestures:
     * a pentagonal prism, one top edge rounded, a sketch space opened on the cap at its *far* end; then the
     * neighbouring top edge rounded too, so the shared vertex is a corner and the near cap is a tombstone
     * that says so — while the far cap is the same face at the same address, the space still stands on it,
     * the level section closes, the file is a fixed point, and one undo makes the near cap a face again.
     */
    @Test
    fun aCornerTombstonesTheNearCapWhileTheFarCapAndItsSpaceHoldStill() {
        val n = 5
        val radius = 30.0
        val ed = Editor()
        ed.count = n
        ed.setTool(Tools.POLYGON)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(radius, 0.0))
        ed.setTool(Tools.OUTLINE)
        for (i in 0 until n) ed.click(midOf(i, n, radius))
        ed.key("Enter")
        ed.setTool(Tools.EXTRUDE)
        ed.type("20")
        ed.click(midOf(0, n, radius))
        assertTrue(ed.doc.elements.any { it.kind == ElementKind.SOLID }, "the pentagon extrudes: ${ed.statusHint}")
        val plain = bodyOf(ed)

        // first rounding: the top edge above side 0
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("3")
        ed.click(midOf(0, n, radius))
        assertEquals(1, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "one rounding row: ${ed.statusHint}")
        val solids = ed.doc.elements.count { it.kind == ElementKind.SOLID }
        val one = bodyOf(ed)
        assertManifold(one.mesh, "one rounded edge")
        val faces1 = assertNotNull(Section3.faces(one.feature).first, "it names its faces")
        val caps1 = faces1.filter { it.name is FaceName.BlendCap }
        assertEquals(2, caps1.size, "one entry, two flat-end slots: ${faces1.map { it.name }}")
        assertTrue(caps1.all { it.reason == null && it.plane != null }, "…both faces at a free end: ${caps1.map { it.reason?.render() }}")
        // the vertex sides 0 and 1 share is at 72°; the far cap is the one away from it
        val shared = Vec3(radius * cos(2 * PI / n), radius * sin(2 * PI / n), 20.0)
        val far = caps1.maxBy { (centroid(it) - shared).length() }
        val near = caps1.minBy { (centroid(it) - shared).length() }
        assertTrue((centroid(near) - shared).length() < 3.0 && (centroid(far) - shared).length() > 30.0, "the two caps are at the two ends")
        val farArea = areaOf(far.outline)
        assertClose(farArea, capArea(3.0, PI / 2), 1e-9, "the far cap is the fillet's own section")

        // a sketch space on the far cap, by the address the body gives it
        val body = ed.doc.elements.last { it.kind == ElementKind.SOLID }
        val farAt = assertNotNull(Section3.addressOfFace(one.feature, far.name), "the far cap has an address")
        assertNotNull(ed.doc.createFaceSpace(body, farAt, "farcap"), "a sketch space opens on the far cap: ${ed.doc.note}")
        // a direct document edit is committed the way the editor's own face click commits it, so that the
        // undo below steps over the *second rounding* and not over this
        ed.checkpoint()
        assertEquals(far.name, spaceFace(ed, "farcap").name, "…and stands on it")
        assertTrue(ed.setActiveSpace(constructit.editor.Document.PLAN_SPACE), "back to the plan")

        // second rounding: the neighbouring top edge, so the shared vertex becomes a corner
        ed.setTool(Tools.BLEND_EDGE)
        ed.type("3")
        ed.click(midOf(1, n, radius))
        val two = bodyOf(ed)
        // **one dressed body, many roundings** (OP-30): the second rounding is a second *row* of the same
        // dressing — no new solid, and the hint says "rounding 2 … applied with the others in one pass"
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "two rounding rows: ${ed.statusHint}")
        assertEquals(solids, ed.doc.elements.count { it.kind == ElementKind.SOLID }, "…on the same dressed body: ${ed.statusHint}")
        assertManifold(two.mesh, "two rounded edges meeting at a corner")
        assertTrue(Geom3.volume(two.mesh) < Geom3.volume(one.mesh) - 1.0, "the second rounding took material: ${ed.statusHint}")
        val faces2 = assertNotNull(Section3.faces(two.feature).first, "it names its faces")
        val nearNow = assertNotNull(faces2.firstOrNull { it.name == near.name }, "the near slot is still listed")
        val farNow = assertNotNull(faces2.firstOrNull { it.name == far.name }, "the far slot is still listed")
        val words = assertNotNull(nearNow.reason, "the near cap is a tombstone now — a corner closes that end").render()
        assertTrue("corner" in words, "…and says so: $words")
        assertTrue(farNow.reason == null && farNow.plane != null, "the far cap is still a face: ${farNow.reason?.render()}")
        assertClose(areaOf(farNow.outline), farArea, 1e-9, "…with the same area")
        assertEquals(farAt, Section3.addressOfFace(two.feature, far.name), "…at the same address — the entry's own block did not move")
        assertEquals(far.name, spaceFace(ed, "farcap").name, "…and the space still stands on it")
        // at a *convex* vertex the two like bands meet across a mitre — a curve in the edge list (slice 5a's
        // ellipse), not a corner face; the corner face is the inside corner's pivot
        val edges2 = assertNotNull(Section3.edges(two.feature).first, "it names its edges")
        val mitre = assertNotNull(edges2.firstOrNull { it.name is constructit.geom.EdgeName.BlendMitre }, "the shared vertex is a mitre between the two bands: ${edges2.map { it.name }}")
        assertEquals(setOf((near.name as FaceName.BlendCap).edge, (faces2.first { it.name is FaceName.BlendBand && (it.name as FaceName.BlendBand).edge != (near.name as FaceName.BlendCap).edge }.name as FaceName.BlendBand).edge), (mitre.name as constructit.geom.EdgeName.BlendMitre).edges.toSet(), "…between exactly those two roundings")
        val (regions, why) = Section3.regionsOf(two.feature, Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y))
        assertEquals(1, assertNotNull(regions, "the level section through both bands closes: ${why?.render()}").size, "one region")
        val plainArea = regionArea(assertNotNull(Section3.regionsOf(plain.feature, Plane3(Vec3(0.0, 0.0, 19.5), Vec3.X, Vec3.Y)).first))
        assertTrue(regionArea(regions) < plainArea, "…and takes material")

        // the file
        val once = DocumentFormat.save(ed.doc)
        val back = DocumentFormat.load(once)
        assertEquals(once, DocumentFormat.save(back), "save → load → save is byte-equal:\n$once")
        val ed2 = Editor()
        ed2.replaceDocument(back)
        assertEquals(emptyList(), ed2.doc.loadNotes.map { it.toString() }, "a file at this version says nothing on load")
        assertEquals(far.name, spaceFace(ed2, "farcap").name, "the reloaded space stands on the far cap")
        assertClose(areaOf(spaceFace(ed2, "farcap").outline), farArea, 1e-9, "…with its area")

        // undo the second rounding: the near cap is a face again, the space is still there
        assertTrue(ed.undo(), "undo")
        val again = bodyOf(ed)
        val faces3 = assertNotNull(Section3.faces(again.feature).first, "it names its faces")
        val nearAgain = assertNotNull(faces3.firstOrNull { it.name == near.name }, "the near slot")
        assertTrue(nearAgain.reason == null && nearAgain.plane != null, "the near cap is a face again: ${nearAgain.reason?.render()}")
        assertClose(areaOf(nearAgain.outline), capArea(3.0, PI / 2), 1e-9, "…with the fillet's own section")
        assertEquals(far.name, spaceFace(ed, "farcap").name, "the space survived the undo")
        // the same body, re-evaluated: the general boolean is deterministic only to the float32 snap
        // between two evaluations (queued as (5q)), so two readings of one body agree to 1e-7 of it, not to a bit
        val v1 = Geom3.volume(one.mesh)
        assertClose(Geom3.volume(again.mesh), v1, 1e-7 * v1, "…and the body is the one-rounding body again")
    }
}
