package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.geom.Curves3
import constructit.geom.Embedding
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.MeshQuality
import constructit.geom.Path3
import constructit.geom.SweepProfile
import constructit.geom.Vec3
import constructit.units.mm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub #20 — *self-intersection artifacts on a tube along a path with sharp corners*.
 *
 * The user's drawing, verbatim: two Béziers mirrored about a line into a closed outline with two sharp
 * corners on the mirror axis, and a 10 mm tube run round it. It built **watertight and positively volumed**
 * — 116781 mm³ — and folded through itself at both corners, which is why nothing refused and why this
 * ranked with the silent-wrong-output family rather than with cosmetics.
 *
 * **What was wrong, and it is not the mesh.** The mitre is the trim two *straight* tubes make of each other:
 * the corner's ring is pushed back along its own tangent, and the curtain between where the ring is built
 * and where it is pushed to lies exactly **on** a straight leg's surface — which is why a mitred polyline is
 * exact and why session 65's leg criterion (*"a corner mitres away only as much run as there is"*) was the
 * only question a polyline could raise. Where the leg **bends** inside the trimmed span, that curtain leaves
 * the wall and cuts across the run behind it. Session 65 parked exactly this case as "the mesh speaking",
 * because the artefact shows up in the picture when the trim reaches past the next station; it is not — a
 * finer spine draws the same fold with more triangles, and a coarser one merely hides it.
 *
 * So the criterion gained a second term ([Embedding.cornerFold]'s bend half): over the run the mitre eats,
 * ask the **analytic** curve how far it wanders off the straight line that cut is made on, and refuse when
 * that is deeper than the tolerance the picture itself is built to. The sampling is the criterion's own and
 * fixed, so no refusal here can appear because a drawing was meshed more finely — the session-65 law, kept.
 *
 * The ground truth this fixture is anchored on is not the refusal: it is a **triangle-against-triangle
 * self-intersection count** on the built mesh ([selfIntersections]), asserted at both corners. That is what
 * makes "a smaller radius builds and is fold-free" a statement about the body rather than about the message.
 */
class TubeCornerBendTest {
    /** The user's script, exactly as they pasted it into GitHub #20. */
    private val script =
        """
constructit 3
point -29.5,63.75 -> e1
point -29.248632469206647,-26.68662580237365 -> e2
tool line pts=e1,e2 clicks=-29.5,72.75;-30.25,-45.25 -> e3
point -16.838819421248974,101.75657905911535 -> e4
point 14.236841881769294,74.2631581182307 -> e5
point 12.75,31.25 -> e6
tool bezier pts=e1,e4,e5,e6 clicks=-29.5,73.5;-19,90;40.5,23.25;12.75,31.25 -> e7
point 11.25,-66.75 -> e8
point 12.442618571818494,22.357698716930418 -> e9
tool bezier pts=e2,e8,e9,e6 clicks=-29,-46.25;-21.25,-58.5;32.25,7.5;13.25,31.5 -> e10
tool mirror els=e7,e3 clicks=6.5,61;-29.75,32.25 -> e11
tool mirror els=e10,e3 clicks=8,0;-31.25,3.25 -> e12
tool outline els=e10,e7,e11,e12 clicks=-9.75,-32.25;8.25,53.75;-54.716280317515725,73.85199682047906;-56.18987199601717,-26.202702146523773 -> e13,e14,e15,e16,e17
param "radius" = 10mm
tool tube els=e17 clicks=-8.75,-30.75 scalar="radius" dofs=0deg;0deg -> e18
tool line pts=e5,e6 clicks=13.925844051820176,74.56669092476368;13.156106874474117,30.948250875153644 -> e19
attach e9 e19
""".trimStart()

    private fun tubeOf(doc: Document): Element = doc.elements.first { it.kind == ElementKind.SOLID }

    private fun refusalOf(doc: Document): String? = (Evaluator().eval(tubeOf(doc).ref.node) as? EvalResult.Invalid)?.reason

    @Suppress("UNCHECKED_CAST")
    private fun meshOf(doc: Document): Mesh3 = Evaluator().solid(tubeOf(doc).ref as SolidRef).mesh

    /** The run the tube is swept along, taken off the feature's own input rather than rebuilt. */
    private fun pathOf(doc: Document): Path3 {
        val ev = Evaluator()
        for (n in tubeOf(doc).ref.node.inputs) {
            ((ev.eval(n) as? EvalResult.Ok)?.value as? constructit.core.Path3Value)?.let { return it.path }
        }
        error("the tube has no path input")
    }

    /** Where the run turns discontinuously, in world coordinates — the two corners the report is about. */
    private fun cornersOf(
        path: Path3,
        reach: Double,
    ): List<Vec3> {
        val (frame, why) = Frames3.along(path, Vec3.Z, reach = reach)
        return assertNotNull(frame, why).stations.filter { it.corner }.map { it.at }
    }

    // ---- the ground truth: does the surface actually pass through itself? ----

    /** Möller–Trumbore: does the open segment `a→b` pierce the triangle's interior? */
    private fun pierces(
        a: Vec3,
        b: Vec3,
        p0: Vec3,
        p1: Vec3,
        p2: Vec3,
    ): Boolean {
        val e1 = p1 - p0
        val e2 = p2 - p0
        val d = b - a
        val h = d.cross(e2)
        val det = e1.dot(h)
        if (abs(det) < 1e-12) return false
        val inv = 1.0 / det
        val s = a - p0
        val u = inv * s.dot(h)
        if (u < 1e-7 || u > 1.0 - 1e-7) return false
        val q = s.cross(e1)
        val v = inv * d.dot(q)
        if (v < 1e-7 || u + v > 1.0 - 1e-7) return false
        val t = inv * e2.dot(q)
        return t > 1e-7 && t < 1.0 - 1e-7
    }

    /**
     * How many pairs of the mesh's triangles **within [radius] of [near]** cut through each other — the
     * fold, counted rather than eyeballed. Edge-against-triangle both ways, with the shared edges of
     * neighbouring triangles excluded by the open-interval test above.
     */
    private fun selfIntersections(
        mesh: Mesh3,
        near: Vec3,
        radius: Double,
    ): Int {
        val tris =
            mesh.triangles
                .map { Triple(mesh.vertices[it.a], mesh.vertices[it.b], mesh.vertices[it.c]) }
                .filter { (p0, p1, p2) ->
                    (p0 - near).length() < radius || (p1 - near).length() < radius || (p2 - near).length() < radius
                }
        var hits = 0
        for (x in tris.indices) {
            for (y in x + 1 until tris.size) {
                val (a0, a1, a2) = tris[x]
                val (b0, b1, b2) = tris[y]
                val hit =
                    pierces(a0, a1, b0, b1, b2) || pierces(a1, a2, b0, b1, b2) || pierces(a2, a0, b0, b1, b2) ||
                        pierces(b0, b1, a0, a1, a2) || pierces(b1, b2, a0, a1, a2) || pierces(b2, b0, a0, a1, a2)
                if (hit) hits++
            }
        }
        return hits
    }

    // ---- 1. the report ----

    /**
     * **The user's drawing is refused, and the refusal names the corner, both sizes and the way out.**
     *
     * The numbers are the curve's own: the corner at the seam of the closed run mitres 9.948 mm of run —
     * 10 mm of tube against a half-turn whose tangent is 0.99 — and the Bézier leg wanders 0.827 mm off the
     * line that cut is made on within that. Twenty times the tolerance the picture is drawn to, and the fold
     * is there in the triangles.
     */
    @Test
    fun theUsersTubeIsRefusedAtTheCornerItFoldsAt() {
        val doc = DocumentFormat.load(script)
        val why = assertNotNull(refusalOf(doc), "the user's tube must not build: it folds")
        assertTrue(why.contains("the corner 0 mm along the path"), "the refusal names the corner: $why")
        assertTrue(why.contains("mitres 9.948 mm of run"), "…and how much run the mitre eats: $why")
        assertTrue(why.contains("bends 0.827 mm off the straight line that cut is made on"), "…and the bend: $why")
        assertTrue(why.contains("the sweep would fold back on itself"), "…and what that does: $why")
        assertTrue(
            why.contains("thin the section, move it towards the outside of the turn, or open the corners out"),
            "…and the way out: $why",
        )
    }

    /**
     * …and the refused drawing still saves, reloads and **refuses in the same words** (OP-18): a refusal is a
     * property of the values, so a body nobody can build is still a drawing with every step in it.
     *
     * **Byte equality is claimed now**, and the sentence that stood here is retired with its own history: it
     * read *"byte equality is deliberately not claimed here and the reason is not this fix: the script ends with
     * `attach e9 e19`, so that point's coordinates are recomputed on load and drift by one unit in the last
     * place each time round — the ULP-creep item parked in DESIGN.md's small batch"*. That item is closed. The
     * `attach` step restates the rider's own parameter, so the position it derives on load is bit-identical to
     * the one that was written and there is no drift left to settle. The fixture is a file written before that
     * argument existed, so its **first** save adds it — and re-derives the stored foot one last time, which is
     * the only number in the whole drawing that moves and moves by 1e-14 mm. From that save on the text is a
     * fixed point, which is exactly the claim declined before.
     */
    @Test
    fun theRefusedDrawingStillRoundTrips() {
        val doc = DocumentFormat.load(script)
        val once = DocumentFormat.save(doc)
        assertTrue(once.lines().any { it.startsWith("tool tube") }, "the refused tube is in the file: $once")
        val again = DocumentFormat.load(once)
        assertEquals(refusalOf(doc), refusalOf(again), "the reloaded drawing refuses in the same words")
        assertEquals(once, DocumentFormat.save(again), "…and writes itself back byte for byte, attachment and all")
        val moved = atThisVersion(script).lines().zip(once.lines()).filter { it.first != it.second }
        assertEquals(
            listOf("attach e9 e19", "point 12.442618571818494,22.357698716930418 -> e9"),
            moved.map { it.first }.sortedBy { it.first() },
            "the first save touches the attach — which now states its freedom — and the foot it re-derived once",
        )
        assertTrue(moved.single { it.first.startsWith("attach") }.second.startsWith("attach e9 e19 dofs="), "$once")

        // **and the stored file still means what it meant.** The claim the fix owes a file written by an
        // earlier build: `e9` comes back where that file put it — the recorded position itself, since a script
        // with no `dofs=` still places the rider by projecting it (OP-18). Asserted against the literal in the
        // paste above, so no golden from another build is needed to make the statement.
        for (d in listOf(doc, again)) {
            val e9 = d.elements.first { d.nameOf(it) == "e9" }
            val at = ((Evaluator().eval(e9.ref.node) as EvalResult.Ok).value as constructit.core.PointValue).p
            assertTrue(
                (at - constructit.geom.Vec2(12.442618571818494, 22.357698716930418)).length() < 1e-9,
                "the file's own attached point is where the file says, to a nanometre: $at",
            )
        }
    }

    /**
     * **The fold the refusal is about is really there** — asserted on the triangles of the body the old
     * criterion let through, at both of the run's corners.
     */
    @Test
    fun theBodyTheOldCriterionAcceptedCutsThroughItselfAtBothCorners() {
        val doc = DocumentFormat.load(script)
        val path = pathOf(doc)
        val (solid, why) = Geom3.sweep(path, Vec3.Z, SweepProfile.Round(10.0), tolMm = 0.02)
        // The sweep refuses now, so the folded body is built the one way that goes round the criterion:
        // straight through the shell builder the sweep itself uses, on the very frame and section it would
        // have used. What is asserted below is therefore the geometry the report showed, not a re-creation.
        assertNull(solid, "the refusal is the fix; the body below is the one it used to hand back: $why")
        val profile = SweepProfile.Round(10.0)
        val tess = assertNotNull(Geom3.tessellateRegion(profile.region, 0.02).first, "the section tessellates")
        val (frame, noFrame) = Frames3.along(path, Vec3.Z, reach = 10.0)
        val shells =
            assertNotNull(
                Geom3
                    .sweptShells(
                        listOf(tess),
                        assertNotNull(frame, noFrame).stations,
                        closed = true,
                        regions = listOf(profile.region),
                    ) { st, p -> st.place(p) }.first,
                "the shells the sweep would have emitted",
            )
        val mesh = shells(MeshQuality.FINE)
        assertManifold(mesh, "the folded tube — watertight, which is exactly why nothing refused", foldsBackOnItself = true)
        assertTrue(Geom3.volume(mesh) > 0.0, "…and positively volumed")
        for (c in cornersOf(path, 10.0)) {
            assertTrue(selfIntersections(mesh, c, 60.0) > 0, "the surface cuts through itself at the corner at $c")
        }
    }

    // ---- 2. the healing path ----

    /**
     * **Type a smaller radius and the body appears** (OP-3) — and it is fold-free where it counts: no two
     * triangles near either corner cut through each other.
     */
    @Test
    fun aSmallerRadiusBuildsAndIsFoldFree() {
        val doc = DocumentFormat.load(script)
        doc.setParameter(doc.scalars.single { it.name == "radius" }, 0.5.mm)
        assertNull(refusalOf(doc), "half a millimetre of tube mitres too little run to reach into the bend")
        val mesh = meshOf(doc)
        assertManifold(mesh, "the healed tube")
        assertTrue(Geom3.volume(mesh) > 0.0, "…the right way out")
        for (c in cornersOf(pathOf(doc), 0.5)) {
            assertEquals(0, selfIntersections(mesh, c, 10.0), "nothing folds at the corner at $c")
        }
        // …and back up again, which is the other half of OP-3
        doc.setParameter(doc.scalars.single { it.name == "radius" }, 10.0.mm)
        assertNotNull(refusalOf(doc), "and the fold comes back with the radius")
    }

    // ---- 3. the law: the refusal speaks about the curve, not about the mesh ----

    /**
     * **A finer mesh does not refuse more, and a coarser one does not refuse less.** The bend term reads the
     * analytic pieces at its own fixed resolution, so the same drawing gets the same words at four
     * tessellation tolerances spanning two decades — which is the session-65 law this term had to be built
     * to obey, since the artefact it names is the one that parked note called "the mesh speaking".
     */
    @Test
    fun theRefusalIsTheSameHoweverFinelyTheDrawingIsMeshed() {
        val path = pathOf(DocumentFormat.load(script))
        val words =
            listOf(0.2, 0.05, 0.02, 0.005).map { tol ->
                assertNotNull(Geom3.sweep(path, Vec3.Z, SweepProfile.Round(10.0), tolMm = tol).second, "refused at tol $tol")
            }
        assertEquals(1, words.toSet().size, "one refusal, four meshes: $words")
    }

    /**
     * **A mitred polyline is untouched**: a segment does not wander off its own tangent, so the bend term is
     * silent on every straight-legged corner and session 65's leg term keeps its fixtures word for word.
     */
    @Test
    fun aMitredPolylineHasNoBendForTheTermToFind() {
        val corner =
            Path3(
                Curves3.straightThrough(
                    listOf(Vec3(-200.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(0.0, 200.0, 0.0)),
                    false,
                ),
            )
        val (frame, why) = Frames3.along(corner, Vec3.Z, reach = 20.0)
        assertNull(Embedding.cornerFold(assertNotNull(frame, why), 20.0), "a right-angled elbow of straights mitres exactly")
        val solid = assertNotNull(Geom3.sweep(corner, Vec3.Z, SweepProfile.Round(20.0)).first, "…and builds")
        assertManifold(solid.mesh, "the mitred elbow")
    }
}
