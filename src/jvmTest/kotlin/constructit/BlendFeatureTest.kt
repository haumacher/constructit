package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.Construction
import constructit.dsl.RegionRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.EdgeName
import constructit.geom.FaceName
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Mesh3
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Revolve3
import constructit.geom.Section3
import constructit.geom.Segment
import constructit.geom.SolidFace
import constructit.geom.Vec2
import constructit.geom.Vec3
import constructit.l10n.contains
import constructit.units.deg
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **`Feature3.Blend` — the dress-up feature** (session 71, slice 3): a blended body that still has an
 * address space.
 *
 * Slice 2 could round an edge and the result was a `Feature3.MeshBoolean` — it drew, measured, printed and
 * picked, and everything OP-8's provenance buys died with it: no face to sketch on, no section input, no
 * further edge to name. What this file asserts is the cure and not the plumbing: the dressed face list is
 * the base's **at the base's own indices** with the outlines corrected where the blend cut into them, one
 * band appended per edge; a working plane's section of a dressed part offers the inputs the undressed one
 * did; a sketch space opens on the **trimmed** cap of a blended partial revolve and a Cut drilled from it
 * lands on the dressed body; and blends chain, the second one addressing the first one's extended list.
 *
 * The numbers are the same machinist's numbers slice 2 asserts, because the mesh is deliberately the same
 * mesh: the decision recorded in DESIGN.md is that the *feature* answers faces analytically while the
 * triangles stay slice 2's sweep-and-boolean, the mesh being a sink (OP-9).
 */
class BlendFeatureTest {
    // ---- fixtures ----

    private fun Construction.rect(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        tag: String,
    ): RegionRef {
        val p0 = freePoint("${tag}0", x0.mm, y0.mm)
        val p1 = freePoint("${tag}1", x1.mm, y0.mm)
        val p2 = freePoint("${tag}2", x1.mm, y1.mm)
        val p3 = freePoint("${tag}3", x0.mm, y1.mm)
        return region(loop(segment(p0, p1), segment(p1, p2), segment(p2, p3), segment(p3, p0)))
    }

    /** The motivating body: a 10 x 60 bar beside the X axis, revolved a quarter turn. */
    private fun Construction.turnedBar(deg: Double): SolidRef {
        val o = freePoint("axisO", 0.mm, 0.mm)
        val axis = direction(o, freePoint("axisX", 1.mm, 0.mm))
        return revolve(sketchOn(planeXY(), rect(0.0, 15.0, 60.0, 25.0, "b")), o, axis, parameter("sweep", deg.deg))
    }

    private fun blend(
        cx: Construction,
        ev: Evaluator,
        base: SolidRef,
        size: Double,
        kind: BlendKind,
        whole: Boolean,
        address: Int,
    ): SolidRef {
        val body = ev.solid(base)
        val (targets, whyT) = Blend3.targets(body.feature, whole, address)
        assertNotNull(targets, whyT?.render())
        val (choices, why) = Blend3.choicesFor(body, targets, BlendSection(kind, size))
        assertNotNull(choices, why?.render())
        return cx.blend(base, base, cx.planeXY(), cx.const(size.mm), kind, whole, address, choices)
    }

    private fun edgeIndex(
        ev: Evaluator,
        ref: SolidRef,
        name: EdgeName,
    ): Int {
        val edges = assertNotNull(Section3.edges(ev.solid(ref).feature).first, "the solid names its edges")
        val i = edges.indexOfFirst { it.name == name }
        assertTrue(i >= 0, "the solid has ${name.label}")
        return i
    }

    private fun volume(
        ev: Evaluator,
        ref: SolidRef,
        what: String,
    ): Double {
        val r = ev.eval(ref.node)
        assertTrue(r is EvalResult.Ok, "$what: ${(r as? EvalResult.Invalid)?.reason}")
        val mesh = ev.solid(ref).mesh
        assertManifold(mesh, what)
        return Geom3.volume(mesh)
    }

    // ---- 1: the face list extends the base's, and the trim is analytic ----

    /**
     * **The base's faces keep their indices, the trim is exact, and one band appends.**
     *
     * The fillet runs along profile edge #2 of the high-angle cap — the radial run at `s = 60`, whose two
     * faces are the cap (a plane) and the flat annulus band (a plane), so the crease is plane against
     * plane at 90° and every number here is the machinist's. The cap loses a 2 mm strip along that edge,
     * which is *the whole outline stepping in from `s = 60` to `s = 58`*; the annulus loses a 2 mm strip
     * along the same edge, which in its own frame is a straight offset re-joined to the two rings it runs
     * between — so its corners land on the circles of radius 25 and 15 at `x = 2`, at `√(625−4)` and
     * `√(225−4)`. Nothing here is sampled and nothing is fitted.
     */
    @Test
    fun theDressedFaceListKeepsEveryBaseIndexAndAppendsOneBandPerEdge() {
        val cx = Construction()
        val base = cx.turnedBar(90.0)
        val ev = Evaluator()
        val baseFaces = assertNotNull(Section3.faces(ev.solid(base).feature).first)
        val i = edgeIndex(ev, base, EdgeName.RevolveCapPiece(SolidFace.TOP, 1))
        val r = 2.0
        val rounded = blend(cx, ev, base, r, BlendKind.FILLET, whole = false, address = i)

        val dressed = Evaluator().solid(rounded).feature
        assertTrue(dressed is Feature3.Blend, "the blend is a feature of its own, not a mesh boolean: $dressed")
        val faces = assertNotNull(Section3.faces(dressed).first, Section3.faces(dressed).second?.render())
        assertEquals(baseFaces.size + 1, faces.size, "one band appended per blended edge")
        assertEquals(baseFaces.map { it.name }, faces.dropLast(1).map { it.name }, "every base face keeps its index")

        // the cap: the whole boundary stepped in by the radius along the blended edge
        val cap = faces[baseFaces.indexOfFirst { it.name == FaceName.RevolveCap(SolidFace.TOP) }]
        val corners = cap.outline.map { GeomMath.startOf(it) }
        assertTrue(
            corners.any { (it - Vec2(58.0, 15.0)).length() <= 1e-9 } && corners.any { (it - Vec2(58.0, 25.0)).length() <= 1e-9 },
            "the cap's blended piece steps in from s = 60 to s = 58: $corners",
        )
        assertTrue(corners.none { it.x > 58.0 + 1e-9 }, "and nothing of it is left beyond the tangency: $corners")

        // the flat annulus band beside it: the same strip, re-joined onto the two rings it runs between
        val annulus = faces[1]
        assertEquals(FaceName.Side(1), annulus.name, "face #2 is the band over profile piece #2")
        val ring = annulus.outline.map { GeomMath.startOf(it) }
        assertTrue(
            ring.any { (it - Vec2(2.0, sqrt(625.0 - 4.0))).length() <= 1e-9 },
            "the offset meets the outer ring at r = 25: $ring",
        )
        assertTrue(
            ring.any { (it - Vec2(2.0, sqrt(225.0 - 4.0))).length() <= 1e-9 },
            "…and the inner one at r = 15: $ring",
        )

        // the band itself: a straight edge carries the fillet arc, so the surface is a cylinder of the radius
        val band = faces.last()
        assertEquals(FaceName.BlendBand(i), band.name, "the band is named by the edge it rounds")
        assertEquals(Revolve3.Band.Cylinder(r, 0.0, 10.0), assertNotNull(band.surface).band, "a straight-edge fillet is a cylinder band")
        assertNull(band.plane, "and it is not a plane")
        val why = assertNotNull(band.reason, "so it says so, in the blend's own words")
        assertTrue("rounded band" in why && "cylinder" in why, "$why")
    }

    /** The edge list is the same law: consumed edges keep their index and say so, rails append. */
    @Test
    fun aConsumedEdgeKeepsItsIndexAndTheRailsAppend() {
        val cx = Construction()
        val base = cx.turnedBar(90.0)
        val ev = Evaluator()
        val baseEdges = assertNotNull(Section3.edges(ev.solid(base).feature).first)
        val i = edgeIndex(ev, base, EdgeName.RevolveCapPiece(SolidFace.TOP, 1))
        val rounded = blend(cx, ev, base, 2.0, BlendKind.FILLET, whole = false, address = i)
        val dressed = Evaluator().solid(rounded).feature
        val edges = assertNotNull(Section3.edges(dressed).first)

        assertEquals(baseEdges.size + 2, edges.size, "two tangent rails appended, nothing removed")
        assertEquals(baseEdges.map { it.name }, edges.dropLast(2).map { it.name }, "no base edge renumbered")
        val gone = assertNotNull(edges[i].reason, "the blended edge is flagged rather than dropped")
        assertTrue("rounded away" in gone && "rounded band along edge #${i + 1}" in gone, "$gone")
        assertEquals(EdgeName.BlendRail(i, 0), edges[baseEdges.size].name)
        assertEquals(EdgeName.BlendRail(i, 1), edges[baseEdges.size + 1].name)
        for (k in baseEdges.indices) if (k != i) assertNull(edges[k].reason, "${edges[k].name.label} is untouched")

        // …and building on an edge that is gone is refused in exactly those words
        val (_, whyAgain) = Blend3.choicesFor(Evaluator().solid(rounded), listOf(i), BlendSection(BlendKind.FILLET, 1.0))
        assertEquals(gone, whyAgain, "a second blend on the consumed edge declines by the flag")
    }

    /** One click, four edges of a cap chain, four bands — and the volume slice 2 already pinned. */
    @Test
    fun aWholeCapChainAppendsOneBandPerPiece() {
        val cx = Construction()
        val base = cx.turnedBar(90.0)
        val ev = Evaluator()
        val before = volume(ev, base, "the quarter tube")
        val faces = assertNotNull(Section3.faces(ev.solid(base).feature).first)
        val f = faces.indexOfFirst { it.name == FaceName.RevolveCap(SolidFace.TOP) }
        val rounded = blend(cx, ev, base, 2.0, BlendKind.FILLET, whole = true, address = f)
        val after = volume(Evaluator(), rounded, "the rounded quarter tube")
        assertTrue(after < before, "four convex edges lose material: $after vs $before")

        val dressed = Evaluator().solid(rounded).feature
        val dressedFaces = assertNotNull(Section3.faces(dressed).first, Section3.faces(dressed).second?.render())
        assertEquals(faces.size + 4, dressedFaces.size, "one band per piece of the chain")
        assertEquals(faces.map { it.name }, dressedFaces.take(faces.size).map { it.name }, "and no base index moved")
        // the cap is gone from *every* side by the radius: its outline no longer reaches any of its old corners
        val cap = dressedFaces[f]
        val corners = cap.outline.map { GeomMath.startOf(it) }
        assertTrue(corners.none { (it - Vec2(60.0, 25.0)).length() <= 1e-6 }, "the sharp cap corner is gone: $corners")
    }

    // ---- 2: a working plane's section of a dressed part offers the inputs the base's did ----

    /**
     * **The payoff, measured**: a plane through a chamfered plate names every face the unchamfered one
     * named, at the same indices, with the same words — plus the bevel itself, which is a face of the body
     * and therefore an input like any other.
     *
     * A `Feature3.MeshBoolean` here answered *"this solid is mesh-only … its curves draw as chords and
     * cannot be used as construction inputs"*, so a chamfer cost the drawing every anchor on the part.
     */
    @Test
    fun aSectionOfABlendedExtrusionOffersTheInputsTheUnblendedOneDid() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val ev = Evaluator()
        val i = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0))
        val bevelled = blend(cx, ev, base, 4.0, BlendKind.CHAMFER, whole = false, address = i)
        // a plane across the plate at x = 20: its (u, v) are world (y, z)
        val cut = Plane3(Vec3(20.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        val plain = Section3.sectionOf(ev.solid(base), cut)
        val dressed = Section3.sectionOf(Evaluator().solid(bevelled), cut)

        assertNull(plain.inputsRefusal, "the plate offers inputs")
        assertNull(dressed.inputsRefusal, "and so does the chamfered plate — that is the whole of slice 3")
        assertEquals(
            plain.edges.map { it.provenance },
            dressed.edges.dropLast(1).map { it.provenance },
            "every face of the base is named at its own index",
        )
        // the top face's cut is the same segment, shortened by the setback
        assertEquals(seg(0.0, 20.0, 30.0, 20.0), plain.edges[5].curve, "the plain top face runs the full width")
        assertEquals(seg(4.0, 20.0, 30.0, 20.0), dressed.edges[5].curve, "the dressed one starts at the setback")
        // …and the bevel itself is a named input
        assertEquals(seg(0.0, 16.0, 4.0, 20.0), dressed.edges.last().curve, "the bevel is a face of the body")
        assertTrue("rounded band" in dressed.edges.last().provenance, "${dressed.edges.last().provenance}")

        // corners: the consumed edge says why it is not one, and the two rails are
        val consumed = dressed.corners[i]
        assertNull(consumed.at, "the rounded-away edge is not a corner of this body")
        assertTrue("rounded away" in assertNotNull(consumed.reason), "${consumed.reason!!}")
        val rails = dressed.corners.takeLast(2).mapNotNull { it.at }
        assertEquals(2, rails.size, "both tangent rails cross the plane")
        assertTrue(rails.any { (it - Vec2(4.0, 20.0)).length() <= 1e-9 } && rails.any { (it - Vec2(0.0, 16.0)).length() <= 1e-9 }, "$rails")
    }

    /** A tiny helper so the expected segments above read as coordinates rather than as constructors. */
    private fun seg(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): ProfileElement = ProfileElement.Seg(Segment(Vec2(x0, y0), Vec2(x1, y1)))

    // ---- 3: sketch on the trimmed cap, and drill a Cut from it ----

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

    private fun Editor.solids(): List<Element> = doc.elements.filter { it.kind == ElementKind.SOLID }

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(el: Element): Mesh3 {
        val r = Evaluator().eval(el.ref.node)
        assertTrue(r is EvalResult.Ok, "a solid with a value, not ${(r as? EvalResult.Invalid)?.reason}")
        return Evaluator().solid(el.ref as SolidRef).mesh
    }

    private fun volumeOf(el: Element): Double {
        val mesh = meshOf(el)
        assertManifold(mesh, "a dressed body")
        return Geom3.volume(mesh)
    }

    private fun quarterTube(): Editor {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 15.0))
        ed.click(Vec2(60.0, 25.0))
        ed.setTool(Tools.LINE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.REVOLVE)
        ed.type("90")
        ed.click(Vec2(30.0, 15.0))
        ed.click(Vec2(10.0, 0.0))
        assertEquals(1, ed.solids().size, "the quarter tube: ${ed.statusHint}")
        return ed
    }

    /**
     * **The acceptance, in one story**: round the cap edges of a partial revolve, open a sketch space on
     * that cap — which is now *trimmed* — and drill a Cut from it into the dressed body. The volumes add
     * up, everything is watertight, and the whole thing survives its file byte for byte.
     *
     * Every step of this was impossible after slice 2's blend: `Geom3.facePlane` refused a mesh boolean by
     * name, so there was no space to open and no body for a face-space cut to reach.
     */
    @Test
    fun aBlendedCapCarriesASketchAndACutDrilledFromIt() {
        val ed = quarterTube()
        val plain = volumeOf(ed.solids().single())
        assertClose(plain, PI * (625.0 - 225.0) * 60.0 / 4.0, 30.0, "the quarter tube (chords on both cylinders)")

        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 25.0))
        assertEquals(2, ed.solids().size, "the blend built one body: ${ed.statusHint}")
        val dressedEl = ed.solids().last()
        val dressed = volumeOf(dressedEl)
        assertTrue(dressed < plain, "four convex edges lose material")

        // the cap of the dressed body opens as a space — clicking inside the profile, clear of every edge
        ed.setTool(Tools.SKETCH_ON_FACE)
        ed.click(Vec2(30.0, 20.0))
        assertTrue(!ed.activeSpace.isPlan, "the cap opened as a space: ${ed.statusHint}")
        val anchor = assertNotNull(ed.activeSpace.anchor, "the space hangs on a body")
        val feature = (Evaluator().valueOf(anchor.ref) as? SolidValue)?.solid?.feature
        assertTrue(feature is Feature3.Blend, "…and that body is the dressed one: $feature")

        // **The space's reference outline is the trimmed cap**, and every one of its corners is analytic.
        // The cap face reads `(r, s)`. Its two radial edges stand against flat annulus bands, so the strip
        // the fillet takes there is exactly the radius: `s` runs 2..58 instead of 0..60. Its two axial
        // edges stand against **cylinders**, and there the tangency is not the radius at all: the arc's
        // centre sits 2 mm off the cap plane at radius `R ± 2`, so the tangency lands at `√((R ± 2)² − 2²)`
        // — 16.882 at the bore and 22.913 at the rim. Nothing about those numbers was sampled.
        val patch = assertNotNull(Section3.facePatchOfFootprintPiece(feature, assertNotNull(ed.activeSpace.piece)).first)
        val corners = patch.outline.map { GeomMath.startOf(it) }
        val bore = sqrt(17.0 * 17.0 - 4.0)
        val rim = sqrt(23.0 * 23.0 - 4.0)
        for (c in corners) {
            assertClose(if (c.x < 20.0) bore else rim, c.x, 1e-9, "a cap corner stands at the cylinder's own tangency: $corners")
            assertClose(if (c.y < 30.0) 2.0 else 58.0, c.y, 1e-9, "…and at the radius along the flat bands: $corners")
        }

        // …and a Cut drilled from it lands on the dressed body
        ed.setTool(Tools.CIRCLE_R)
        ed.type("2")
        ed.click(Vec2(20.0, 30.0))
        ed.setTool(Tools.CUT)
        ed.type("8")
        ed.click(Vec2(22.0, 30.0))
        val part = ed.solids().last()
        assertTrue(part !== dressedEl, "the cut built a body: ${ed.statusHint}")
        val bored = volumeOf(part)
        assertClose(dressed - bored, PI * 4.0 * 8.0, PI * 4.0 * 8.0 * 0.02, "the bore came out of the dressed part")

        val once = DocumentFormat.save(ed.doc)
        assertEquals(once, DocumentFormat.save(DocumentFormat.load(once)), "blend + face space + cut round-trips byte-equal")
        val back = DocumentFormat.load(once)
        assertClose(volumeOf(back.elements.last { it.kind == ElementKind.SOLID }), bored, 1e-6, "and reloads to the same body")
    }

    // ---- 4: blends chain, the second addressing the first's extended list ----

    /**
     * **A blend of a blend is one chain, and its addresses are the dressed list's.** The second fillet's
     * base is the first `Feature3.Blend`, so the list it indexes into is the one with the first band and
     * its two rails already in it — and the result extends *that*, which is what makes a chain of dress-up
     * features an ordinary construction rather than a special case.
     */
    @Test
    fun aBlendOfABlendExtendsTheExtendedList() {
        val cx = Construction()
        val base = cx.extrude(cx.sketchOn(cx.planeXY(), cx.rect(0.0, 0.0, 40.0, 30.0, "p")), cx.const(20.mm))
        val ev = Evaluator()
        val plain = volume(ev, base, "the plate")
        val first = blend(cx, ev, base, 4.0, BlendKind.FILLET, whole = false, address = edgeIndex(ev, base, EdgeName.CapPiece(SolidFace.TOP, 0)))
        val ev2 = Evaluator()
        val once = volume(ev2, first, "the plate with one rim rounded")
        val f1 = ev2.solid(first).feature
        val faces1 = assertNotNull(Section3.faces(f1).first)
        val edges1 = assertNotNull(Section3.edges(f1).first)

        // the second address is an index into **this** list, which already carries the first band's rails
        val j = edgeIndex(ev2, first, EdgeName.CapPiece(SolidFace.TOP, 2))
        val second = blend(cx, ev2, first, 3.0, BlendKind.FILLET, whole = false, address = j)
        val twice = volume(Evaluator(), second, "both rims rounded")

        val f2 = Evaluator().solid(second).feature
        assertTrue(f2 is Feature3.Blend && f2.base === f1, "the second blend's base is the first blend")
        val faces2 = assertNotNull(Section3.faces(f2).first, Section3.faces(f2).second?.render())
        val edges2 = assertNotNull(Section3.edges(f2).first)
        assertEquals(faces1.size + 1, faces2.size, "one more band")
        assertEquals(faces1.map { it.name }, faces2.dropLast(1).map { it.name }, "and every index of the *dressed* list survives")
        assertEquals(edges1.size + 2, edges2.size, "two more rails")
        assertNotNull(edges2[j].reason, "the second blended edge is flagged in its turn")
        assertEquals(2, edges2.count { it.reason != null }, "both rounded-away edges are flagged, and only those")

        // the two rims are opposite, so nothing is taken twice
        val exact = (1.0 - PI / 4.0) * (16.0 + 9.0) * 40.0
        assertClose(plain - twice, exact, exact * 0.04, "two quarter-rounds, radius 4 and radius 3, along 40 mm each")
        assertTrue(once > twice, "and the second one took its own")
    }

    /** The same chain through the gesture, so the file carries it. */
    @Test
    fun aChainedBlendRoundTripsThroughTheGesture() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r1", 4.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(20.0, 0.0))
        ed.activeScalar = ed.doc.newParameter("r2", 3.0.mm)
        ed.setTool(Tools.CHAMFER_EDGE)
        ed.click(Vec2(20.0, 30.0))
        // **One dressed body with two entries** (OP-30). They round by different parameters and by different
        // *kinds*, and since OP-30's next step they are still **one pass**: `Feature3.Blend` carries a
        // section per target, so a fillet and a chamfer of the same dressing are one feature and one call.
        assertEquals(2, ed.solids().size, "fillet then chamfer, one dressed body: ${ed.statusHint}")
        assertEquals(2, ed.doc.elements.count { it.kind == ElementKind.DRESSING }, "…with a row per rounding")
        val v = volumeOf(ed.solids().last())

        val text = DocumentFormat.save(ed.doc)
        assertEquals(text, DocumentFormat.save(DocumentFormat.load(text)), "save -> load -> save byte-equal")
        val back = DocumentFormat.load(text)
        assertClose(volumeOf(back.elements.last { it.kind == ElementKind.SOLID }), v, 1e-6, "and the same body comes back")
        val f = (Evaluator().valueOf(back.elements.last { it.kind == ElementKind.SOLID }.ref) as SolidValue).solid.feature
        assertTrue(f is Feature3.Blend && f.base !is Feature3.Blend, "one dress-up feature, not a chain of two: $f")
        assertEquals(
            listOf(BlendKind.FILLET, BlendKind.CHAMFER),
            f.sections.map { it.kind },
            "…with a section per target, in the gestures' own order",
        )
    }

    // ---- 5: the mesh tier, kept and stated ----

    /**
     * **A blend over an ordinary boolean stays the mesh tier, and says so.** The body being cut is not the
     * body being addressed — a union's own faces are emergent (OP-9's sink rule) — so there is no face
     * list to extend and the result is a `Feature3.MeshBoolean` with a silhouette plan, exactly as slice 2
     * left it. That is a stated limit and not an oversight: the tool help carries the same sentence.
     */
    @Test
    fun aBlendOverAUnionStaysTheMeshTierAndSaysSo() {
        val ed = Editor()
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(40.0, 30.0))
        ed.activeScalar = ed.doc.newParameter("depth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(20.0, 0.0))
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(35.0, 5.0))
        ed.click(Vec2(60.0, 25.0))
        ed.activeScalar = ed.doc.newParameter("padDepth", 20.0.mm)
        ed.setTool(Tools.EXTRUDE)
        ed.click(Vec2(47.5, 5.0))
        ed.setTool(Tools.UNION)
        ed.click(Vec2(20.0, 30.0))
        ed.click(Vec2(60.0, 15.0))
        val fused = volumeOf(ed.solids().last())

        ed.activeScalar = ed.doc.newParameter("c", 5.0.mm)
        ed.setTool(Tools.CHAMFER_EDGE)
        ed.click(Vec2(15.0, 0.0))
        val el = ed.solids().last()
        val f = (Evaluator().valueOf(el.ref) as SolidValue).solid.feature
        assertTrue(f is Feature3.MeshBoolean, "a blend over a union has no face list to extend: $f")
        assertTrue(f.plan.isNotEmpty(), "it keeps the silhouette plan, so it is still drawn and clickable")
        val why = assertNotNull(Section3.structuralRefusal(f))
        assertTrue("mesh-only" in why, "and its section says so in Section3's own words: $why")
        // the cut itself is unchanged — the whole 40 mm rim is still there to bevel
        assertClose(fused - volumeOf(el), 5.0 * 5.0 / 2.0 * 40.0, 1.0, "a 45 degree bevel of setback 5 along 40 mm")
        assertTrue(
            assertNotNull(Tools.byId(Tools.CHAMFER_EDGE)).help.contains("fused"),
            "and the help states when the mesh tier applies: ${assertNotNull(Tools.byId(Tools.CHAMFER_EDGE)).help}",
        )
    }
}
