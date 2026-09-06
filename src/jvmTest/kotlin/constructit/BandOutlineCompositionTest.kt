package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.geom.Blend3
import constructit.geom.BlendKind
import constructit.geom.BlendSection
import constructit.geom.FaceName
import constructit.geom.FacePatch
import constructit.geom.Geom3
import constructit.geom.GeomMath
import constructit.geom.Plane3
import constructit.geom.ProfileElement
import constructit.geom.Section3
import constructit.geom.Solid3
import constructit.geom.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **One composition, stated once** (OP-31, slice 5d — the drawing's composition gaps).
 *
 * A face's outline is its undressed one with, **in this order**: every *strip* a neighbouring band trimmed
 * off it, composed down the chain as it always was; then every *splice* — a free end's cap, a walk's
 * tangency curve, a pivot's cap chain — taken **against that trimmed outline**, each junction solved on the
 * boundary as it now stands. Nothing here is about volume: every body in this file is the very body the
 * build made before the slice, to the last bit, and what changed is only what the drawing says about it.
 *
 * The three things that stood in the way, each a whole case and each measured rather than guessed:
 *
 * 1. **A splice that stands past the corner it is spliced at.** A band that ends free closes on a cap
 *    standing in the plane square to its crease, and the body has a face in that plane — but not always
 *    *under* the cap. At the L's own **reflex** plan corner the band carves into the leg beside it and the
 *    wall it leaves was interior material a moment ago, so the face **grows** by the wedge instead of losing
 *    its corner to it. Session 81's notch could only bite, and [Blend3][constructit.geom.Blend3] refused
 *    the extension because its junction does not stand on the ring piece's own span.
 * 2. **A sampled run that ends at a sample.** A band's cut is exact at every ruling and chords between, and
 *    where a neighbouring rounding has taken part of the band away the ruling simply stops short of the
 *    cutting plane. Ending the drawn curve at the last *sample* that still reached left it up to a whole
 *    step — 0.08 mm on the L-block — from the neighbour's own exact cut, and the loop did not close.
 * 3. **A bevelled vertex' apex.** Three bevels meeting at a convex vertex close on three planar triangles,
 *    each lying **in one of the three bevel planes**. So the apex is no new surface: it is those three
 *    bands, each running on to a point instead of ending square across.
 */
class BandOutlineCompositionTest {
    private val L = LBlock()

    /** The top edge that ends at the L's own **reflex** plan corner — the free end this slice is about. */
    private val topAtReflex = 13

    /** The bottom edge of the very face that free end's cap stands in. */
    private val bottomOfThatFace = 8

    /** The concave upright at the reflex corner: a **fill**, and item 2's one-ended pivot with a band. */
    private val concaveUpright = 2

    /** One convex box vertex of the L, and its three edges. */
    private val vertexEdges = listOf(0, 12, 17)

    private val vertexAt = Vec3(-26.875, -32.375, 20.0)

    // ---- the plumbing ----

    private fun body(vararg entries: Rounding): Solid3 {
        val (stages, why) = L.run(entries.toList(), Route.ONE_PASS)
        val solid = Evaluator().solid(assertNotNull(stages, why).last())
        assertManifold(solid.mesh, entries.joinToString("+"))
        return solid
    }

    private fun faces(solid: Solid3): List<FacePatch> = assertNotNull(Section3.faces(solid.feature).first, "it names its faces")

    /** The face over one boundary piece of the plan, found by the plane it stands in rather than by index. */
    private fun faceAt(
        solid: Solid3,
        origin: Vec3,
    ): FacePatch =
        assertNotNull(
            faces(solid).firstOrNull { it.plane != null && (it.plane!!.origin - origin).length() <= 1e-9 },
            "the face standing at $origin",
        )

    /** The area a closed outline encloses, by the shoelace over its own tessellation. */
    private fun areaOf(outline: List<ProfileElement>): Double {
        val pts = outline.flatMap { GeomMath.tessellatePiece(it) }
        var a = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2.0
    }

    /** Whether an outline closes on itself, piece to piece and end to start. */
    private fun closes(outline: List<ProfileElement>): Boolean {
        if (outline.isEmpty()) return false
        for (i in outline.indices) {
            val next = outline[(i + 1) % outline.size]
            if ((GeomMath.endOf(outline[i]) - GeomMath.startOf(next)).length() > 1e-6) return false
        }
        return true
    }

    private fun closesAt(
        solid: Solid3,
        z: Double,
    ): Int {
        val plane = Plane3(Vec3(0.0, 0.0, z), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val (regions, why) = Section3.regionsOf(solid.feature, plane)
        assertNotNull(regions, "the level section at z = $z closes: ${why?.render()}")
        return regions.size
    }

    /**
     * The same drawing written as a **script**, so the body reaches the round-trip through the file rather
     * than through the DSL — the L's ortho path, extruded, then one rounding gesture per entry.
     */
    private fun script(vararg entries: Rounding): String {
        val sb = StringBuilder("constructit 6\n")
        sb.append("orthostart ${L.plan[0].x},${L.plan[0].y} -> e1\n")
        var n = 2
        for (i in 1 until L.plan.size) {
            sb.append("orthovertex ${L.plan[i].x},${L.plan[i].y} -> e$n,e${n + 1}\n")
            n += 2
        }
        sb.append("orthoclose -> e$n\n")
        n++
        sb.append("param \"h\" = ${L.height}mm\n")
        sb.append("tool extrude els=e${n - 2} clicks=-48.125,37.875 scalar=\"h\" -> e$n\n")
        var next = n + 1
        var on = "e$n"
        for ((k, g) in entries.withIndex()) {
            val tool = if (g.kind == BlendKind.CHAMFER) "chamferedge" else "filletedge"
            // **the choice is scored the way a live click scores it and then written down verbatim** (OP-1):
            // a stored sign is never re-scored on replay, so the file must carry the one the gesture made
            val (choices, why) = Blend3.choicesFor(L.block.solid, listOf(g.edge), BlendSection(g.kind, g.size))
            val choice = assertNotNull(choices, why?.render()).first()
            sb.append("param \"r$k\" = ${g.size}mm\n")
            sb.append("tool $tool els=$on clicks=0,0 scalar=\"r$k\" signs=${g.edge};${choice.signs().joinToString(";")} -> e$next")
            // the first rounding of a plain body declares **two** elements — the dressed body and its own
            // entry — and every one after it declares only the entry it adds (OP-30)
            if (k == 0) {
                sb.append(",e${next + 1}")
                on = "e$next"
                next += 2
            } else {
                next += 1
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun solidOfScript(s: String): SolidRef =
        DocumentFormat.load(s).elements.last { it.kind == constructit.editor.ElementKind.SOLID }.ref as SolidRef

    // ---- (a) the free end's cap that stands past the corner of the face it ends in ----

    /**
     * **A free end's cap standing past a corner grows the face it stands in, by exactly its own wedge.**
     *
     * The L's top edge #14 ends at the plan's **reflex** corner. Its band carves the corner out of the lower
     * leg, and the wall the removal leaves in the plane of the long face was interior material before —
     * so that face gains the wedge rather than losing it. The outline says so: the rectangle's corner is
     * replaced by a run **out past** it and the blend's own arc back, and the area is the plain face plus
     * `r²(1 − π/4)` to the last bits.
     */
    @Test
    fun aFreeEndsCapPastACornerGrowsTheFaceItStandsIn() {
        val solid = body(Rounding(topAtReflex, BlendKind.FILLET, 4.0))
        assertClose(Geom3.volume(solid.mesh), 40496.35810717499, 1e-6, "the body itself has not moved")
        val face = faceAt(solid, Vec3(-5.521648428788623, 0.375, 0.0))
        assertEquals(null, face.reason?.render(), "the face is stated")
        assertTrue(closes(face.outline), "…and its outline closes: ${face.outline.size} pieces")
        assertEquals(5, face.outline.size, "the rectangle with its corner replaced by the cap: ${face.outline.size}")
        val arc = assertNotNull(face.outline.filterIsInstance<ProfileElement.ArcE>().singleOrNull(), "the cap's own arc")
        assertClose(arc.arc.radius, 4.0, 1e-9, "the arc is the rounding's own")
        // it stands **past** the face's corner, which is the whole of what this case is
        assertClose(GeomMath.startOf(arc).x, -4.0, 1e-9, "the cap reaches a whole setback past the corner")
        assertClose(GeomMath.endOf(arc).y, L.height - 4.0, 1e-9, "…and lands on the face's own edge a setback down")
        // the outline's area is measured over the arc's own inscribed chords, so the figure it is compared
        // with is the **chorded** wedge and not the exact one — the same distinction every bracket in the
        // matrix draws (OP-15), and the two differ here by 0.08 mm²
        val plain = 67.39664842878862 * L.height
        assertClose(
            areaOf(face.outline),
            plain + Figures.wedgeAreaByChords(4.0, BlendKind.FILLET),
            1e-9,
            "the face has grown by exactly the wedge",
        )
        // and the level section through the cap's own height closes, where it used to refuse by name
        for (z in listOf(19.9, 18.0, 16.1)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    /**
     * **The strip composes down the chain and the splice is taken against what it left.**
     *
     * The same free end, with the bottom edge of the very face its cap stands in rounded at 3 mm. That
     * rounding takes a strip off the face; the cap is spliced afterwards, at the corner of the **trimmed**
     * ring, and both survive: the trimmed piece stands 3 mm in, the cap's arc is where it was, and the
     * boundary between them is the trimmed piece re-cut to meet it.
     */
    @Test
    fun aStripComposesDownTheChainAndTheSpliceIsTakenAgainstIt() {
        val solid = body(Rounding(topAtReflex, BlendKind.FILLET, 4.0), Rounding(bottomOfThatFace, BlendKind.FILLET, 3.0))
        assertClose(Geom3.volume(solid.mesh), 40362.20096439735, 1e-6, "the body itself has not moved")
        val face = faceAt(solid, Vec3(-5.521648428788623, 0.375, 0.0))
        assertEquals(null, face.reason?.render(), "the face is stated")
        assertTrue(closes(face.outline), "…and its outline closes")
        assertEquals(5, face.outline.size, "the strip and the cap, both of them")
        val strip = assertNotNull(face.outline.firstOrNull { abs(GeomMath.startOf(it).y - 3.0) <= 1e-9 }, "the trimmed piece")
        assertClose(GeomMath.endOf(strip).y, 3.0, 1e-9, "the strip stands a whole 3 mm in")
        val arc = assertNotNull(face.outline.filterIsInstance<ProfileElement.ArcE>().singleOrNull(), "the cap's own arc")
        assertClose(GeomMath.startOf(arc).x, -4.0, 1e-9, "the cap still reaches past the corner")
        assertClose(GeomMath.endOf(arc).y, L.height - 4.0, 1e-9, "…and lands where it did")
        val plain = 67.39664842878862 * L.height
        assertClose(
            areaOf(face.outline),
            plain + Figures.wedgeAreaByChords(4.0, BlendKind.FILLET) - 3.0 * 67.39664842878862,
            1e-9,
            "the face is the plain one, plus the cap's wedge, less the strip",
        )
        for (z in listOf(19.9, 18.0, 16.1, 1.0)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    /**
     * **A splice wholly inside a strip contributes nothing, and provably so.**
     *
     * A 4 mm round on the L's corner upright ends free on the bottom face and notches its corner; a 4 mm
     * round on the bottom edge beside it takes a 4 mm strip off that same face. The notch's own section lies
     * inside the box of its two setbacks, so a strip as deep as either setback leaves it nothing to cut: the
     * splice is **dropped** rather than drawn, and the bottom face is the plain ring with its two pieces
     * stepped in.
     */
    @Test
    fun aSpliceWhollyInsideAStripContributesNothing() {
        val solid = body(Rounding(0, BlendKind.FILLET, 4.0), Rounding(6, BlendKind.FILLET, 4.0))
        assertClose(Geom3.volume(solid.mesh), 40472.49102263197, 1e-6, "the body itself has not moved")
        val bottom = faceAt(solid, Vec3(0.0, 0.0, 0.0))
        assertEquals(null, bottom.reason?.render(), "the bottom face is stated")
        assertTrue(closes(bottom.outline), "…and closes")
        assertEquals(6, bottom.outline.size, "the plan's own six pieces and nothing spliced in")
        assertTrue(bottom.outline.none { it is ProfileElement.ArcE }, "the notch took nothing at all")
        for (z in listOf(2.1, 1.0)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    /**
     * **…and where it straddles the strip's edge it is trimmed to what is left.**
     *
     * The same pair with the strip only 2 mm deep: the 4 mm notch now reaches past it, and what survives is
     * the arc from where it crosses the stepped piece to where it lands on the other. Both stations are
     * exact — `√(4² − 2²)` along the stepped piece, the whole setback down the other — so nothing here is
     * fitted and the face closes on seven pieces.
     */
    @Test
    fun andStraddlingTheStripsEdgeTheSpliceIsTrimmedToWhatIsLeft() {
        val solid = body(Rounding(0, BlendKind.FILLET, 4.0), Rounding(6, BlendKind.FILLET, 2.0))
        assertClose(Geom3.volume(solid.mesh), 40524.208930656394, 1e-6, "the body itself has not moved")
        val bottom = faceAt(solid, Vec3(0.0, 0.0, 0.0))
        assertEquals(null, bottom.reason?.render(), "the bottom face is stated")
        assertTrue(closes(bottom.outline), "…and closes")
        assertEquals(7, bottom.outline.size, "the six pieces with what is left of the notch between two of them")
        val arc = assertNotNull(bottom.outline.filterIsInstance<ProfileElement.ArcE>().singleOrNull(), "what is left of the notch")
        assertClose(arc.arc.radius, 4.0, 1e-9, "still the rounding's own arc")
        assertClose(GeomMath.startOf(arc).x, -22.875 - sqrt(4.0 * 4.0 - 2.0 * 2.0), 1e-9, "it begins where the 2 mm strip crosses it")
        assertClose(GeomMath.startOf(arc).y, 32.375 - 2.0, 1e-9, "…on the stepped piece itself")
        assertClose(GeomMath.endOf(arc).x, -26.875, 1e-9, "and ends on the untouched piece beside it")
        assertClose(GeomMath.endOf(arc).y, 32.375 - 4.0, 1e-9, "…a whole setback along it")
        for (z in listOf(1.5, 1.0)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    // ---- (c) the bevelled vertex' apex ----

    /**
     * **Three bevels at a convex vertex come to a point, and the point belongs to the three bands.**
     *
     * The apex is the meeting of the three bevel planes, and each of the three triangles that close the
     * corner lies **in** one of them — so no face is added, no slot moves and no address changes: each band
     * simply runs on to the apex instead of ending square across. Its outline is five pieces, the apex is
     * the same point read from all three, and the triangle each of them gains has area `c²√2/4` exactly.
     */
    @Test
    fun theBevelledVertexApexIsTheThreeBandsRunningOnToAPoint() {
        val c = 4.0
        val solid = body(*vertexEdges.map { Rounding(it, BlendKind.CHAMFER, c) }.toTypedArray())
        assertClose(Geom3.volume(solid.mesh), 39946.61848068237, 1e-6, "the body itself has not moved")
        assertTrue(Section3.facesAreWholeBoundary(solid.feature), "the faces are the whole boundary there")
        val bands = faces(solid).filter { it.name is FaceName.BlendBand }
        assertEquals(3, bands.size, "one band per bevel")
        // the apex, in the world, is half a setback in along each of the vertex' own three face normals
        val apex = Vec3(vertexAt.x + c / 2.0, vertexAt.y + c / 2.0, vertexAt.z - c / 2.0)
        for (band in bands) {
            assertEquals(null, band.reason?.render(), "${band.name.label.render()} is a plane and is stated")
            val plane = assertNotNull(band.plane, "…with a plane of its own")
            assertTrue(closes(band.outline), "…whose outline closes")
            assertEquals(5, band.outline.size, "…on five pieces: two rails, one end, and the apex' two")
            val tip =
                assertNotNull(
                    band.outline.map { GeomMath.startOf(it) }.firstOrNull { (plane.toWorld(it) - apex).length() <= 1e-9 },
                    "${band.name.label.render()} runs on to the vertex' own apex",
                )
            // the triangle the apex adds: the two pieces that meet at it, and their far ends
            val at = band.outline.indexOfFirst { (GeomMath.startOf(it) - tip).length() <= 1e-12 }
            val from = GeomMath.startOf(band.outline[(at + band.outline.size - 1) % band.outline.size])
            val to = GeomMath.endOf(band.outline[at])
            val area = abs((from - tip).x * (to - tip).y - (from - tip).y * (to - tip).x) / 2.0
            assertClose(area, c * c * sqrt(2.0) / 4.0, 1e-9, "the apex triangle is exact")
        }
        // and a level section through the apex — between the setback and the vertex — closes
        for (z in listOf(19.9, 19.0, 18.0, 17.0, 16.1)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    // ---- (d) item 2's one-ended pivot, bevelled ----

    /**
     * **The bevelled one-ended pivot sections through its own corner**, where only the rounded one did.
     *
     * A bevel on the top edge and a bevel on the concave upright it runs out at is item 2's mixed-sign pair:
     * the fill turns about the band and is capped by the third face. Its turning leg is a **cone** and its
     * cut is sampled, and the run used to lose the very station where the leg hands over to the slide beside
     * it — so the section closed above the corner and below it, and refused through it.
     */
    @Test
    fun theBevelledOneEndedPivotSectionsThroughItsOwnCorner() {
        val solid = body(Rounding(topAtReflex, BlendKind.CHAMFER, 4.0), Rounding(concaveUpright, BlendKind.CHAMFER, 4.0))
        assertClose(Geom3.volume(solid.mesh), 40520.35733350528, 1e-6, "the body itself has not moved")
        for (f in faces(solid)) {
            val reason = f.reason?.render() ?: ""
            assertTrue("Exception" !in reason, "${f.name.label.render()} carries a fault where a reason should be: '$reason'")
        }
        for (z in listOf(19.9, 18.0, 17.0, 16.5, 10.0)) assertEquals(1, closesAt(solid, z), "one area at z = $z")
    }

    // ---- (e) nothing moved, and every one of them is still a file ----

    /**
     * **Every body in this file round-trips byte-equal**, which is the whole of what a drawing slice owes
     * the format: not one of these corrections is recorded anywhere, so save∘load is still the fixed point
     * it was (OP-18).
     */
    @Test
    fun everyOneOfTheseBodiesRoundTripsByteEqual() {
        val cases =
            listOf(
                "the free end past a corner" to arrayOf(Rounding(topAtReflex, BlendKind.FILLET, 4.0)),
                "the strip and the splice" to
                    arrayOf(Rounding(topAtReflex, BlendKind.FILLET, 4.0), Rounding(bottomOfThatFace, BlendKind.FILLET, 3.0)),
                "the splice inside the strip" to arrayOf(Rounding(0, BlendKind.FILLET, 4.0), Rounding(6, BlendKind.FILLET, 4.0)),
                "the splice across the strip" to arrayOf(Rounding(0, BlendKind.FILLET, 4.0), Rounding(6, BlendKind.FILLET, 2.0)),
                "the bevelled vertex" to vertexEdges.map { Rounding(it, BlendKind.CHAMFER, 4.0) }.toTypedArray(),
                "the bevelled pivot" to
                    arrayOf(Rounding(topAtReflex, BlendKind.CHAMFER, 4.0), Rounding(concaveUpright, BlendKind.CHAMFER, 4.0)),
            )
        for ((what, entries) in cases) {
            val text = script(*entries)
            val once = DocumentFormat.save(DocumentFormat.load(text))
            val twice = DocumentFormat.save(DocumentFormat.load(once))
            assertEquals(once, twice, "$what is a fixed point:\n$once")
            val solid = Evaluator().solid(solidOfScript(once))
            assertManifold(solid.mesh, what)
            assertClose(
                Geom3.volume(solid.mesh),
                Geom3.volume(body(*entries).mesh),
                1e-9,
                "$what reaches the same body through the file as through the DSL",
            )
        }
    }

    /**
     * **And the drawing is the only thing that moved.** Every fixture above states its own volume against
     * the number the build gave before this slice; this states the rule they are all instances of — the
     * face list is read from the same feature the mesh is built from, and reading it cannot change it.
     */
    @Test
    fun readingTheFaceListDoesNotMoveTheBody() {
        val entries = arrayOf(Rounding(topAtReflex, BlendKind.FILLET, 4.0), Rounding(bottomOfThatFace, BlendKind.FILLET, 3.0))
        val solid = body(*entries)
        val before = Geom3.volume(solid.mesh)
        faces(solid)
        closesAt(solid, 18.0)
        Section3.edges(solid.feature)
        assertClose(Geom3.volume(solid.mesh), before, 0.0, "asking what the faces are does not move them")
    }

    /**
     * **A sketch space opens on the grown face, with the composed outline.**
     *
     * The face the cap grew is one of the base's own, so its address has not moved by a byte (OP-18) — and
     * what opens there is the outline as it now stands, cap and all, rather than the rectangle the base had.
     */
    @Test
    fun aSketchSpaceOnTheGrownFaceCarriesTheComposedOutline() {
        val solid = body(Rounding(topAtReflex, BlendKind.FILLET, 4.0))
        val face = faceAt(solid, Vec3(-5.521648428788623, 0.375, 0.0))
        val at = assertNotNull(Section3.addressOfFace(solid.feature, face.name), "the face has an address")
        val space = assertNotNull(Section3.facePatchOfFootprintPiece(solid.feature, at).first, "the face takes a space")
        assertEquals(5, space.outline.size, "the space carries the composed outline")
        val plain = 67.39664842878862 * L.height
        assertClose(
            areaOf(space.outline),
            plain + Figures.wedgeAreaByChords(4.0, BlendKind.FILLET),
            1e-9,
            "…and its area is the grown face's",
        )
        assertTrue(space.outline.any { it is ProfileElement.ArcE }, "…the cap's arc included")
    }
}
