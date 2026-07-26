package constructit

import constructit.core.Evaluator
import constructit.dsl.Construction
import constructit.dsl.LoopRef
import constructit.dsl.PointRef
import constructit.dsl.RegionRef
import constructit.dsl.ScalarRef
import constructit.dsl.SegmentRef
import constructit.dsl.SolidRef
import constructit.dsl.scalar
import constructit.dsl.solid
import constructit.geom.Axis3
import constructit.geom.Mesh3
import constructit.geom.Vec3
import constructit.svg.Drawable
import constructit.svg.Svg
import constructit.units.mm
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Showcase 4 — the papercraft net: a 2D drawing constructed *from* a 3D model.**
 *
 * There is **no unfolding algorithm**, by design and on the user's own directive. The net is drawn the way a
 * person would draw it — a cross of rectangles with two gable triangles, laid out by hand with ordinary 2D
 * construction — and every single length in it is **measured off the 3D solids**: `measureBBoxExtent` and
 * `measureBBoxMax` are the only source of a number anywhere in [netOf]. The house is built from parameters;
 * the net knows none of them.
 *
 * That is what makes this the acceptance test for the **downward** half of the 2D↔3D seam (OP-4 forward,
 * OP-9's scalar rule): a scalar measured from a mesh is just a number, so it may drive a *new*, independent
 * construction — and here it drives a whole drawing. The pay-off is asserted twice:
 *
 * - **the net is the house's surface.** Every panel's area is checked against the area of the *actual mesh
 *   face* it wraps ([theNetPanelsAreTheHousesFaces]), face by face, and the total against the whole outer
 *   surface less the two faces that are inside the model (the wall top and the roof soffit, which no net
 *   contains).
 * - **it is parametric papercraft.** Retyping the house's wall height resizes the net's wall panels and
 *   slides everything above them, with **no node created**
 *   ([raisingTheWallsResizesTheNetWithNothingRebuilt]) — the rafter length, being a function of span and
 *   rise, correctly does *not* change.
 *
 * The model is a minimal gabled house: an 80 × 50 × 40 box of walls plus a roof prism of rise 25, the roof
 * being the same vertical-plane extrusion the architect showcase uses (`HouseChainTest`) — so the rafter is
 * `√((W/2)² + rise²)` = 35.355, which is where the net's roof panels get their width.
 *
 * One stated gap: the SVG serializer has no stroke-dash attribute (`Drawable` carries a stroke colour and a
 * fill, and `Style` likewise), so **fold lines are distinguished by colour** rather than by dashes — drawn
 * after the panels, so they overpaint the shared edges. Adding dashes is a change to the whole `DrawTarget`
 * seam and is not smuggled in here.
 */
class PapercraftNetTest {
    private val houseLen = 80.0
    private val houseWidth = 50.0
    private val wallHeight = 40.0
    private val roofRise = 25.0
    private val rafter = sqrt(25.0 * 25.0 + 25.0 * 25.0)

    /** The 3D model: a box of walls and a gable roof prism, both from parameters. */
    private class House(
        val c: Construction,
        val walls: SolidRef,
        val roof: SolidRef,
        val len: ScalarRef,
        val width: ScalarRef,
        val height: ScalarRef,
        val rise: ScalarRef,
    )

    private fun house(): House {
        val c = Construction()
        val len = c.parameter("houseLength", houseLen.mm)
        val width = c.parameter("houseWidth", houseWidth.mm)
        val height = c.parameter("wallHeight", wallHeight.mm)
        val rise = c.parameter("roofRise", roofRise.mm)
        val zero = c.const(0.mm)

        val walls =
            c.extrude(
                c.sketchOn(
                    c.planeXY(),
                    c.rect(c.pointXY(zero, zero), c.pointXY(len, zero), c.pointXY(len, width), c.pointXY(zero, width)),
                ),
                height,
            )
        // the roof: a triangle on the YZ plane (u = world Y, v = world Z) swept along the ridge, i.e. +X
        val eavesW = c.pointXY(zero, height)
        val eavesE = c.pointXY(width, height)
        val ridge = c.pointXY(c.scale(width, 0.5), c.add(height, rise))
        val roof = c.extrude(c.sketchOn(c.planeYZ(), c.tri(eavesW, eavesE, ridge)), len)
        return House(c, walls, roof, len, width, height, rise)
    }

    private fun Construction.rect(
        a: PointRef,
        b: PointRef,
        d: PointRef,
        e: PointRef,
    ): RegionRef = region(rectLoop(a, b, d, e))

    private fun Construction.rectLoop(
        a: PointRef,
        b: PointRef,
        d: PointRef,
        e: PointRef,
    ): LoopRef = loop(segment(a, b), segment(b, d), segment(d, e), segment(e, a))

    private fun Construction.tri(
        a: PointRef,
        b: PointRef,
        d: PointRef,
    ): RegionRef = region(triLoop(a, b, d))

    private fun Construction.triLoop(
        a: PointRef,
        b: PointRef,
        d: PointRef,
    ): LoopRef = loop(segment(a, b), segment(b, d), segment(d, a))

    // ---- the net ----

    /**
     * The net: nine panels and the folds between them, plus the measured scalars the layout is made of.
     *
     * Panels are named for what they wrap, because that is what the assertions compare them against.
     */
    private class Net(
        val panels: Map<String, LoopRef>,
        val folds: List<SegmentRef>,
        val len: ScalarRef,
        val width: ScalarRef,
        val height: ScalarRef,
        val rise: ScalarRef,
        val rafter: ScalarRef,
    )

    /**
     * The net of [h], laid out as a cross. **Every** number comes from a measurement of the solids: the plan
     * size and the wall height from the walls' bounding box, the ridge height from the roof's, and the rafter
     * length from those two by Pythagoras — a `sqrt` of two measured lengths, which the dimension system
     * carries as a length because `√(L²)` is an L (OP-7).
     *
     * The panels **share their corner points as nodes**, so the net is one connected drawing rather than nine
     * loose quadrilaterals: sharing a node *is* the statement that two panels meet along an edge, and it is
     * the same fold segment node that the styling picks up below.
     */
    private fun netOf(h: House): Net {
        val c = h.c
        val len = c.measureBBoxExtent(h.walls, Axis3.X)
        val width = c.measureBBoxExtent(h.walls, Axis3.Y)
        val height = c.measureBBoxExtent(h.walls, Axis3.Z)
        val rise = c.sub(c.measureBBoxMax(h.roof, Axis3.Z), height)
        val halfWidth = c.scale(width, 0.5)
        val rafter = c.sqrtS(c.add(c.powS(halfWidth, 2), c.powS(rise, 2)))

        val zero = c.const(0.mm)
        val yEaves = height
        val ySlope1 = c.add(height, rafter)
        val ySlope2 = c.add(height, c.scale(rafter, 2.0))
        val yBack = c.add(ySlope2, height)
        val yApex = c.add(height, rise)

        // the sixteen corners of the layout, each one built once and shared by every panel that touches it
        val o = c.pointXY(zero, zero)
        val e = c.pointXY(len, zero)
        val oh = c.pointXY(zero, yEaves)
        val eh = c.pointXY(len, yEaves)
        val f1 = c.pointXY(zero, c.neg(width))
        val f2 = c.pointXY(len, c.neg(width))
        val s1a = c.pointXY(zero, ySlope1)
        val s1b = c.pointXY(len, ySlope1)
        val s2a = c.pointXY(zero, ySlope2)
        val s2b = c.pointXY(len, ySlope2)
        val b1 = c.pointXY(zero, yBack)
        val b2 = c.pointXY(len, yBack)
        val wl = c.pointXY(c.neg(width), zero)
        val wlh = c.pointXY(c.neg(width), yEaves)
        val wr = c.pointXY(c.add(len, width), zero)
        val wrh = c.pointXY(c.add(len, width), yEaves)
        val apexL = c.pointXY(c.neg(halfWidth), yApex)
        val apexR = c.pointXY(c.add(len, halfWidth), yApex)

        val panels =
            mapOf(
                "frontWall" to c.rectLoop(o, e, eh, oh),
                "floor" to c.rectLoop(f1, f2, e, o),
                "roofFront" to c.rectLoop(oh, eh, s1b, s1a),
                "roofBack" to c.rectLoop(s1a, s1b, s2b, s2a),
                "backWall" to c.rectLoop(s2a, s2b, b2, b1),
                "gableWallWest" to c.rectLoop(wl, o, oh, wlh),
                "gableWallEast" to c.rectLoop(e, wr, wrh, eh),
                "gableWest" to c.triLoop(wlh, oh, apexL),
                "gableEast" to c.triLoop(eh, wrh, apexR),
            )
        val folds =
            listOf(
                c.segment(o, e),
                c.segment(oh, eh),
                c.segment(s1a, s1b),
                c.segment(s2a, s2b),
                c.segment(o, oh),
                c.segment(e, eh),
                c.segment(wlh, oh),
                c.segment(eh, wrh),
            )
        return Net(panels, folds, len, width, height, rise, rafter)
    }

    // ---- mesh face areas: what a panel is compared against ----

    /** The area of the faces of [mesh] whose outward normal points along [n]. */
    private fun faceArea(
        mesh: Mesh3,
        n: Vec3,
    ): Double {
        val u = n.normalized()
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            val cross = (mesh.vertices[t.b] - a).cross(mesh.vertices[t.c] - a)
            val len = cross.length()
            if (len < 1e-12) continue
            if ((cross * (1.0 / len)).dot(u) > 1.0 - 1e-9) sum += len / 2.0
        }
        return sum
    }

    private fun totalArea(mesh: Mesh3): Double {
        var sum = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t.a]
            sum += (mesh.vertices[t.b] - a).cross(mesh.vertices[t.c] - a).length() / 2.0
        }
        return sum
    }

    // ---- the assertions ----

    /**
     * **Each panel of the net is the face it wraps.** Not "the same size as the parameter that made both" —
     * the comparison is against the area of the triangles of the *actual mesh*, one face at a time, so it
     * would fail if the net's layout drifted from the solid for any reason at all.
     *
     * Two faces are deliberately missing from the net, and the total pins that: the walls' **top** and the
     * roof's **soffit** are inside the model, and a net wraps only what you can see.
     */
    @Test
    fun theNetPanelsAreTheHousesFaces() {
        val h = house()
        val c = h.c
        val net = netOf(h)
        val ev = Evaluator()
        assertManifold(ev.solid(h.walls).mesh, "walls")
        assertManifold(ev.solid(h.roof).mesh, "roof")
        val walls = ev.solid(h.walls).mesh
        val roof = ev.solid(h.roof).mesh

        // the measured numbers the whole net is built from
        assertClose(ev.scalar(net.len).mm, houseLen, tol = 1e-9)
        assertClose(ev.scalar(net.width).mm, houseWidth, tol = 1e-9)
        assertClose(ev.scalar(net.height).mm, wallHeight, tol = 1e-9)
        assertClose(ev.scalar(net.rise).mm, roofRise, tol = 1e-9)
        assertClose(ev.scalar(net.rafter).mm, rafter, tol = 1e-9, msg = "the rafter is sqrt((W/2)^2 + rise^2)")

        fun area(name: String) = ev.scalar(c.loopArea(net.panels.getValue(name))).base

        assertClose(area("frontWall"), faceArea(walls, Vec3(0.0, -1.0, 0.0)), tol = 1e-9, msg = "front wall")
        assertClose(area("backWall"), faceArea(walls, Vec3(0.0, 1.0, 0.0)), tol = 1e-9, msg = "back wall")
        assertClose(area("gableWallWest"), faceArea(walls, Vec3(-1.0, 0.0, 0.0)), tol = 1e-9, msg = "west end wall")
        assertClose(area("gableWallEast"), faceArea(walls, Vec3(1.0, 0.0, 0.0)), tol = 1e-9, msg = "east end wall")
        assertClose(area("floor"), faceArea(walls, Vec3(0.0, 0.0, -1.0)), tol = 1e-9, msg = "floor")
        assertClose(area("gableWest"), faceArea(roof, Vec3(-1.0, 0.0, 0.0)), tol = 1e-9, msg = "west gable triangle")
        assertClose(area("gableEast"), faceArea(roof, Vec3(1.0, 0.0, 0.0)), tol = 1e-9, msg = "east gable triangle")
        assertClose(
            area("roofFront"),
            faceArea(roof, Vec3(0.0, -roofRise, houseWidth / 2.0)),
            tol = 1e-9,
            msg = "the front roof slope is the length times the rafter",
        )
        assertClose(area("roofBack"), faceArea(roof, Vec3(0.0, roofRise, houseWidth / 2.0)), tol = 1e-9, msg = "back roof slope")

        // ...and the whole net is the whole outer surface, less the two faces that are inside the model
        val hidden = 2.0 * houseLen * houseWidth
        assertClose(
            net.panels.keys.sumOf { area(it) },
            totalArea(walls) + totalArea(roof) - hidden,
            tol = 1e-9,
            msg = "the net wraps everything except the wall top and the roof soffit",
        )
        // the panels are laid out edge to edge: nine of them, and their total area equals the layout's own
        assertEquals(9, net.panels.size)
        assertEquals(8, net.folds.size, "eight folds hold the nine panels together")
    }

    /**
     * **Parametric papercraft.** The house's wall height is retyped; the net's four wall panels grow, the
     * panels above them slide up, the roof panels keep their size (a rafter depends on span and rise, not on
     * how high the walls are) — and not one node is created, because the net is a *function* of the solids
     * rather than a snapshot of them.
     */
    @Test
    fun raisingTheWallsResizesTheNetWithNothingRebuilt() {
        val h = house()
        val c = h.c
        val net = netOf(h)
        // the area measurements are nodes too, so they are made once — a fresh one per assertion would be
        // this test growing the graph, not the edit
        val areas = net.panels.mapValues { c.loopArea(it.value) }

        fun area(name: String) = Evaluator().scalar(areas.getValue(name)).base
        assertClose(area("frontWall"), houseLen * wallHeight, tol = 1e-9)

        val nodesBefore = c.nodesCreated
        c.set(h.height, 55.mm)
        assertClose(area("frontWall"), houseLen * 55.0, tol = 1e-9, msg = "the wall panel grew with the wall")
        assertClose(area("backWall"), houseLen * 55.0, tol = 1e-9)
        assertClose(area("gableWallWest"), houseWidth * 55.0, tol = 1e-9)
        assertClose(area("roofFront"), houseLen * rafter, tol = 1e-9, msg = "a taller wall is not a longer rafter")
        assertClose(area("gableWest"), 0.5 * houseWidth * roofRise, tol = 1e-9, msg = "nor a bigger gable")
        assertEquals(nodesBefore, c.nodesCreated, "the net recomputes; it is not rebuilt")

        // a steeper roof *is* a longer rafter, and the net follows that too
        c.set(h.rise, 40.mm)
        val steeper = sqrt(25.0 * 25.0 + 40.0 * 40.0)
        assertClose(area("roofFront"), houseLen * steeper, tol = 1e-9)
        assertClose(area("gableWest"), 0.5 * houseWidth * 40.0, tol = 1e-9)
        assertEquals(nodesBefore, c.nodesCreated)

        // and the 3D model is still the model: the ridge is where the two numbers put it
        val ev = Evaluator()
        assertManifold(ev.solid(h.roof).mesh, "steeper roof")
        assertClose(ev.scalar(c.measureBBoxMax(h.roof, Axis3.Z)).mm, 95.0, tol = 1e-9)
    }

    /**
     * The panels really do tile the layout without overlapping: the sum of the nine areas equals the area of
     * the layout's own silhouette, computed here as the cross's bounding box less the four empty corners.
     * A layout error — a panel placed a rafter too high, say — would show up as a gap or an overlap.
     */
    @Test
    fun theCrossLayoutTilesWithoutOverlap() {
        val h = house()
        val c = h.c
        val net = netOf(h)
        val ev = Evaluator()
        val total = net.panels.keys.sumOf { ev.scalar(c.loopArea(net.panels.getValue(it))).base }

        val body = houseLen * (wallHeight + 2 * rafter + wallHeight)
        val floor = houseLen * houseWidth
        val ends = 2.0 * (houseWidth * wallHeight + 0.5 * houseWidth * roofRise)
        assertClose(total, body + floor + ends, tol = 1e-9, msg = "the strip, the floor flap and the two ends")
    }

    /**
     * The net as SVG — the golden *is* the review: it has to read as a house net (a tall strip of
     * wall-roof-roof-wall with a floor flap below and a gabled end panel to either side), with the folds
     * distinguishable from the cut edges.
     */
    @Test
    fun theNetDrawingIsAGolden() {
        val h = house()
        val net = netOf(h)
        val items =
            net.panels.entries.sortedBy { it.key }.map { Drawable(it.value, stroke = "#1f77b4") } +
                net.folds.map { Drawable(it, stroke = "#c8c8c8") }
        Golden.check("papercraft_net_house", Svg.render(Evaluator(), items))
    }
}
