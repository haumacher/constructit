package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.dsl.PointRef
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.Camera
import constructit.editor.Dependencies
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.SlotKind
import constructit.editor.Tools
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Vec2
import constructit.units.mm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The point of the section that rides the run** — the anchored sweep (OP-26, step 2; GitHub issue #15).
 *
 * The user cut a worm thread: the section drawn **in place** at the shaft's surface, 5.4 mm off the plan's
 * origin because that is where the material is, and a helix of radius 5.274 mm whose start they put at the
 * section. The sweep read the area with its *own origin* on the path, so the section orbited 5.4 mm out from
 * a coil that bends every 5.279 mm, and the node refused by name — correctly. The refusal was right and the
 * contract was too narrow.
 *
 * What this pins:
 *
 * - the **anchor** is an optional third pick, a point of the section's own plane, and the section is read
 *   relative to it — an input node, so dragging the point moves the body;
 * - **nothing** changes without it: the same two clicks, the same node, the same file, the same solid;
 * - the picks survive a **change of sketch plane**, because the run and the section legitimately live in two
 *   (`crossSpace`, the loft's own declaration);
 * - the reach-vs-bend refusal names *where* the bend is in words, since the offender is almost always the
 *   bend the run starts with and "0 mm along the path" is the one figure a reader discounts.
 */
class SweepAnchorTest {
    // ---- helpers ----

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
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    /** Why [el] is invalid, or null when it is not — the reason a refusal states (OP-3). */
    private fun whyInvalid(el: Element): String? = (Evaluator().eval(el.ref.node) as? EvalResult.Invalid)?.reason

    /** The element the user gave [name] to (OP-7), or the one the script calls that. */
    private fun named(
        doc: Document,
        name: String,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull { doc.userNameOf(it) == name } ?: doc.elements.firstOrNull { doc.nameOf(it) == name },
            "the drawing has $name",
        )

    /** The point element of [space] standing at [at] — how a test picks an anchor by the coordinates it means. */
    private fun pointAt(
        doc: Document,
        space: String,
        at: Vec2,
        tol: Double = 1e-6,
    ): Element =
        assertNotNull(
            doc.elements.firstOrNull {
                it.isPoint && it.space == space && (positionOf(it)?.let { p -> (p - at).length() < tol } ?: false)
            },
            "a point of $space at $at",
        )

    private fun positionOf(el: Element): Vec2? =
        ((Evaluator().eval(el.ref.node) as? EvalResult.Ok)?.value as? constructit.core.PointValue)?.p

    @Suppress("UNCHECKED_CAST")
    private fun refOf(el: Element): PointRef = el.ref as PointRef

    /**
     * How far every point of [mesh] stands **off the coil's own radius**, at most — the exact reading of "it
     * rides the coil", and the one that needs no tolerance for tessellation: a point within `d` of the spine
     * is within `d` of a point that is exactly [Curve3Element.Helix3.radius] from the axis, so its own
     * distance to the axis is in `radius ± d` by the triangle inequality alone.
     */
    private fun offTheCoilsRadius(
        mesh: Mesh3,
        coil: constructit.geom.Curve3Element.Helix3,
    ): List<Double> =
        mesh.vertices.map { v ->
            val d = v - coil.origin
            abs((d - coil.axis * d.dot(coil.axis)).length() - coil.radius)
        }

    private fun coilOf(el: Element): constructit.geom.Curve3Element.Helix3 =
        assertNotNull(
            pathOf(el).elements.filterIsInstance<constructit.geom.Curve3Element.Helix3>().singleOrNull(),
            "the run is one analytic coil",
        )

    private fun pathOf(el: Element): constructit.geom.Path3 =
        ((Evaluator().eval(el.ref.node) as EvalResult.Ok).value as constructit.core.Path3Value).path

    /** A mesh's extent, in the three readings these tests measure a swept body by. */
    private class Bounds(mesh: Mesh3) {
        val zs = mesh.vertices.map { it.z }
        val radial = mesh.vertices.map { sqrt(it.x * it.x + it.y * it.y) }
    }

    // ---- 1. the user's own drawing, verbatim ----

    /**
     * **The user's script loads as it was written** — the fixture rule (OP-18): a file is a permanent load
     * test, and everything below is asserted against the drawing they actually had.
     */
    @Test
    fun theUsersScriptLoadsWithNothingAmbiguous() {
        val doc = DocumentFormat.load(WORM_CIT)
        // its two filleted ortho corners are now the path's own corner radii (GitHub #25) and the load says
        // so; nothing else about this file is ambiguous
        assertTrue(doc.loadNotes.all { it.contains("rounds the corner of") }, "got: ${doc.loadNotes}")
        assertEquals("worm", doc.userNameOf(named(doc, "worm")), "the section is there under its name")
        assertEquals(ElementKind.OUTLINE, named(doc, "worm").kind, "as the closed outline it is")
        assertEquals(ElementKind.SPACE_CURVE, named(doc, "thread").kind, "and the coil is a curve in space")
    }

    /**
     * **The worm rides the coil the moment the anchor is stated** — the issue, closed.
     *
     * The anchor is a point of the plan at the coordinates the user put the coil's start at (5.274, 0.039):
     * from there the section is read *relative to that point*, so what travels is a 0.39 × 0.34 mm thread
     * form about a 5.279 mm bend instead of a section orbiting 5.4 mm out from it.
     */
    @Test
    fun theUsersWormRidesItsCoilOnceTheAnchorIsStated() {
        val doc = DocumentFormat.load(WORM_CIT)
        val worm = named(doc, "worm")
        val thread = named(doc, "thread")
        doc.activeSpace = assertNotNull(doc.spaceNamed(Document.PLAN_SPACE))
        val anchor = doc.freePoint(5.274061990212072.mm, 0.03915171288743882.mm)

        val solid = assertNotNull(doc.sweepAlongCurve(thread, worm, null, null, anchor), "the sweep was built: ${doc.note}")
        assertNull(whyInvalid(solid), "and the solid is valid")
        assertTrue(doc.note!!.contains("riding on"), "the note says what it rides on: ${doc.note}")

        val mesh = meshOf(solid)
        assertManifold(mesh, "the user's worm thread")
        // The section runs x = 5…5.390, y = 0.068…0.412 in the plan; read from (5.274, 0.039) it becomes
        // −0.274…0.116 by 0.029…0.373, whose furthest corner stands sqrt(0.274² + 0.373²) = 0.4626 mm out.
        // So the whole body lies inside that tube about the coil — which is the *feature*, stated as a number.
        val coil = coilOf(thread)
        assertClose(coil.radius, 5.274, 1e-3, "the coil the user drew")
        val off = offTheCoilsRadius(mesh, coil)
        assertTrue(off.max() <= 0.4627, "the thread hugs its coil: nothing stands further than ${off.max()} mm off its radius")
        assertTrue(off.max() > 0.2, "…and it really is that section, not something small: ${off.max()} mm")
    }

    /**
     * **…and with the section's own origin riding the run, the drawing is carried rather than refused** — the
     * local criterion corrected to the direction it was always about (OP-26; the same correction session 59
     * made to the global term, made here to the local one).
     *
     * **The reversal, and the old rationale kept.** This reading used to be refused in the words *"the
     * profile's reach from the path (5.399 mm) is larger than the bend the run starts with (radius
     * 5.279 mm)"*. The arithmetic was right and the model behind it was not, exactly as it was not for the
     * global term: `κ·reach ≥ 1` asks whether the section's greatest distance from the run **in any
     * direction** outgrows the bend, and what a fold actually turns on is what the section reaches **towards
     * the centre of that bend** — the profile is carried by `γ + w.x·ref + w.y·bi`, whose derivative along a
     * rotation-minimizing frame is `t·(1 − κ·(w·N))`. A thread form drawn at the shaft's surface stands 5.4 mm
     * from the coil's *centre line* and reaches barely a third of a millimetre towards the centre of its own
     * curvature, so it cannot turn inside out and it is a body.
     *
     * The origin reading is *stated* here (`pierce = -1`) because since the **in-place** sweep it is no longer
     * what a picked-nothing gesture on this drawing gets ([theSameWormWithNoPickNowRidesWhereTheCoilGoesThroughTheDrawing]);
     * every file that ever recorded it keeps it ([aFileWithNoAnchorLoadsWithTheOldReading]).
     */
    @Test
    fun theSameWormWithNoAnchorIsCarriedBecauseItReachesAwayFromTheBend() {
        val doc = DocumentFormat.load(WORM_CIT)
        val solid =
            assertNotNull(
                doc.sweepAlongCurve(named(doc, "thread"), named(doc, "worm"), pierce = -1),
                "the sweep is built: ${doc.note}",
            )
        assertNull(whyInvalid(solid), "and a section standing outside its bend is a body: ${whyInvalid(solid)}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the worm form read from the plan's own origin")
        assertTrue(Geom3.volume(mesh) > 0.0, "and it is a solid the right way out: ${Geom3.volume(mesh)} mm^3")

        // **And the old sentence stays where it is still true**: a *disc* of the same 5.4 mm reach on the same
        // coil reaches that far in *every* direction, including straight into the bend, and is refused in the
        // very words this drawing used to get — which is what makes the correction a change of measurement
        // rather than a weakening of the criterion.
        val disc =
            assertNotNull(
                doc.tubeAlongCurve(named(doc, "thread"), doc.newParameter("wire", 5.399.mm).ref),
                "the tube gesture builds — this is a value's business",
            )
        val why = assertNotNull(whyInvalid(disc), "5.399 mm in *every* direction outgrows a 5.279 mm bend")
        assertTrue(why.contains("the tube's radius (5.399 mm)"), "named by its reach: $why")
        assertTrue(why.contains("is larger than the bend"), "against the bend: $why")
        assertTrue(why.contains("5.279"), "and the bend it measured against: $why")
        assertTrue(why.contains("pass through itself"), "and what would go wrong: $why")
    }

    /**
     * **The refusal names the place in words.** The bend that refuses a section is the one the run *starts*
     * with here — as it is for every coil — and "0 mm along the path" is exactly the figure a reader takes
     * for nowhere. So the sentence says which bend it is, and keeps the measurement beside the radius.
     *
     * Asserted on a **disc**, since the correction above made the worm's own thread form a body: a tube's
     * radius reaches into the bend by exactly its radius, which is the case the ball model always described
     * correctly and the case whose every message is byte-identical to what it was.
     */
    @Test
    fun theRefusalNamesWhichBendItIs() {
        val doc = DocumentFormat.load(WORM_CIT)
        val disc = assertNotNull(doc.tubeAlongCurve(named(doc, "thread"), doc.newParameter("wire", 5.399.mm).ref))
        val why = assertNotNull(whyInvalid(disc))
        assertTrue(why.contains("the bend the run starts with"), "the place is named readably: $why")
        assertTrue(why.contains("0 mm along the path"), "with the measurement kept beside the radius: $why")
        assertTrue(!why.contains("the bend 0 mm along"), "and the old unreadable lead is gone: $why")
    }

    /**
     * **What a picked-nothing gesture on the user's own drawing does now** — the in-place sweep, and the honest
     * consequence of it on a drawing that was not drawn in the plane's crossing.
     *
     * The coil is parented to a vertical datum and winds about an axis lying **in** the plan, so it goes
     * through the plan — the plane the thread form is drawn in — twice per turn. With nothing picked the
     * section therefore rides the nearer of those two crossings, and the status line says which one.
     *
     * **Reversed in session 59, and the old rationale is worth keeping.** Session 58 recorded this gesture as
     * a correct *global* refusal: *"that crossing stands 0.287 mm past the top of the section, so the form
     * reaches 0.688 mm from the run there and a 1 mm pitch has no room for two of them"*. The arithmetic was
     * right and the model behind it was not — `2 × reach` asks whether two **balls** of that radius overlap,
     * and a worm's thread form is not a ball. It reaches 0.688 mm *along its own coil*, radially into the
     * shaft, and hardly at all across to the turn above; the turns clear each other, which is what a thread
     * is. Since the criterion now measures what the two sections reach **towards each other**
     * ([Embedding], the support in the approach's direction), GitHub #15's own drawing is a body with no pick
     * at all. The second half of this test keeps the old sentence where it is still true: a *disc* of the very
     * same reach on the very same coil is refused, in the very same words.
     *
     * The pick keeps its whole argument regardless: a default that reads *where the run goes through the
     * drawing* is right whenever the drawing is at the crossing, and a stated anchor is what says so when it is
     * not ([theUsersWormRidesItsCoilOnceTheAnchorIsStated] — the same drawing, by one click).
     */
    @Test
    fun theSameWormWithNoPickNowRidesWhereTheCoilGoesThroughTheDrawing() {
        val doc = DocumentFormat.load(WORM_CIT)
        val solid = assertNotNull(doc.sweepAlongCurve(named(doc, "thread"), named(doc, "worm")))
        val note = assertNotNull(doc.note)
        assertTrue(note.contains("riding where"), "the choice speaks: $note")
        assertTrue(note.contains("crossing 1 of 2, the one nearest the section"), "and says which crossing: $note")
        assertTrue(note.contains("pick a point of the section"), "and names the alternative: $note")
        assertNull(whyInvalid(solid), "a thread form lying along its own coil is a body: ${doc.note}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the worm thread swept from where its coil goes through the drawing")
        assertTrue(Geom3.volume(mesh) > 0.0, "and it is a solid the right way out: ${Geom3.volume(mesh)} mm^3")

        // **And the difference is the direction, not the size.** A *disc* reaching the same 0.688 mm from this
        // coil does not fit between turns 1 mm apart and is refused in the global term's own words — which is
        // exactly the sentence this drawing used to get, and the reason it no longer does: a thread form
        // reaches along its own coil, not across to the turn above.
        val disc =
            assertNotNull(
                doc.tubeAlongCurve(named(doc, "thread"), doc.newParameter("wire", 0.688.mm).ref),
                "the tube gesture builds — this is a value's business",
            )
        val why = assertNotNull(whyInvalid(disc), "0.688 mm in *every* direction does not fit a 1 mm pitch")
        assertTrue(why.contains("the run passes within 1 mm of itself"), "refused globally, by the run's own clearance: $why")
        assertTrue(why.contains("needs 1.376 mm between them"), "and by what a disc of that reach needs: $why")
    }

    // ---- 2. the analytic case: a stated corner, and the shell it must lie in ----

    /**
     * A coil of **radius 30 mm, pitch 10 mm, 2 turns** about the plan's origin, and a **2 × 2 mm** square
     * drawn at (30, 5) — in place, where the part is. Its lower-left corner is picked as the anchor, so the
     * section is read as [0, 2] × [0, 2] from the run.
     *
     * Every point of the body is then at most `sqrt(2² + 2²) = 2.828 mm` from the spine, which is what the
     * bounds assert: the radial distance from the axis lies inside 30 ± 2.829 and the rise inside
     * (0 − 2.829) … (20 + 2.829), where 20 mm is two turns of 10. And the assertion is not vacuous — the
     * body genuinely reaches out to 32 mm and up to 20 mm, which is asserted too.
     */
    @Test
    fun aSectionAnchoredAtItsCornerRidesTheCoilInsideTheShellThatCornerImplies() {
        val ed = coilAndSquare()
        val solid = anchoredSweep(ed)
        assertNull(whyInvalid(solid), "the anchored sweep is valid: ${ed.statusHint}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the coiled square section")

        val reach = sqrt(8.0)
        val b = Bounds(mesh)
        assertTrue(b.radial.min() >= 30.0 - reach - 1e-6, "nothing reaches inside 30 - 2.828 mm: ${b.radial.min()}")
        assertTrue(b.radial.max() <= 30.0 + reach + 1e-6, "and nothing outside 30 + 2.828 mm: ${b.radial.max()}")
        assertTrue(b.zs.min() >= -reach - 1e-6, "nor below the start by more than the reach: ${b.zs.min()}")
        assertTrue(b.zs.max() <= 20.0 + reach + 1e-6, "nor above two turns by more than the reach: ${b.zs.max()}")
        // …and it fills that shell rather than sitting somewhere small inside it
        assertTrue(b.radial.max() > 31.9, "the section really stands 2 mm out from the coil: ${b.radial.max()}")
        assertTrue(b.zs.max() > 19.0 && b.zs.min() < 1.0, "and it runs the whole rise: ${b.zs.min()}..${b.zs.max()}")
    }

    /**
     * **The same coil carries the same square when its own origin rides the run, and it is a different body
     * from the anchored one** — the anchor's whole argument, now made by the two shapes rather than by a
     * refusal.
     *
     * **The reversal, with the old rationale on the record.** This used to refuse, in the words *"the
     * profile's reach from the path (32.757 mm) is larger than the bend part-way along it (radius
     * 30.084 mm)"*: `sqrt(32² + 7²)` against `(30² + (10/2π)²)/30`. Both numbers are still what they were and
     * the comparison between them is the one the criterion no longer makes — the ball model measures the
     * section's greatest distance from the run **in any direction**, and what folds a sweep is what the
     * section reaches **towards the centre of the bend** ([Embedding], the local term corrected as the global
     * one was in session 59). Read from the plan's origin the square stands ~31 mm along the frame's reference
     * — which on this coil is very nearly the axis direction — so it swings out on a 31 mm lever arm as the
     * rotation-minimizing frame precesses, and the most of that arm that ever points *into* the bend is 52 % of
     * it. The body is therefore embedded, and it is a wildly different body from the anchored one: 14…37 mm
     * from the axis instead of 27…32, and standing 30 mm above the coil instead of on it.
     */
    @Test
    fun theSameCoilCarriesTheSquareWhoseOwnOriginRidesTheRunAsADifferentBody() {
        val ed = coilAndSquare()
        val solid = unanchoredSweep(ed)
        assertNull(whyInvalid(solid), "it is carried: ${ed.statusHint}")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the square read from the plan's own origin")
        assertTrue(Geom3.volume(mesh) > 0.0, "and it is a solid the right way out: ${Geom3.volume(mesh)} mm^3")
        val b = 10.0 / (2.0 * PI)
        assertClose(sqrt(32.0 * 32.0 + 7.0 * 7.0), 32.757, 1e-3, "the reach the unanchored section has")
        assertClose((900.0 + b * b) / 30.0, 30.084, 1e-3, "against the coil's own radius of curvature")

        // …and it is emphatically not the anchored body: the lever arm carries it 30 mm above its own coil
        val far = Bounds(mesh)
        assertTrue(far.zs.min() > 29.0, "the section rides 30 mm up the frame's reference: ${far.zs.min()}")
        assertTrue(far.radial.min() < 20.0, "and the arm swings it well inside the coil: ${far.radial.min()}")
        val near = Bounds(meshOf(anchoredSweep(coilAndSquare())))
        assertTrue(near.zs.min() < 1.0, "where the anchored one sits on the coil: ${near.zs.min()}")
        assertTrue(near.radial.min() > 27.0, "and hugs its radius: ${near.radial.min()}")
    }

    /**
     * **…and the section that really does reach into the bend is refused, at the same numbers as ever.**
     *
     * The companion to the reversal above, and the reason it is a change of *measurement* rather than a
     * weakening: the same coil, the same 2 × 2 square, drawn 32 mm to the *other* side of the plan's origin so
     * that what it reaches is towards the centre of curvature instead of away from it. `κ·h(N)` is then over
     * one at the very first station, and the refusal says so in the words it always used.
     */
    @Test
    fun aSectionReachingIntoTheBendIsRefusedAtTheSameNumbers() {
        val ed = Editor()
        ed.camera = Camera(-800.0, 500.0, 40.0)
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        // the square below the origin: read from there, it stands 32…34 mm along −bi, which at the start of
        // this coil points radially inward — straight at the centre of its own curvature
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(5.0, -34.0))
        ed.click(Vec2(7.0, -32.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(5.0, -33.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep is built: ${ed.statusHint}")
        val why = assertNotNull(whyInvalid(solid), "a section reaching into the bend folds through itself")
        // 33.954 rather than a flat 34: the frame's second axis is perpendicular to the *tangent*, which on a
        // rising coil is a pitch angle off the radial direction, so 34 mm along it reaches 34·cos(3°) inwards
        assertTrue(why.contains("the profile's reach into the bend (33.954 mm)"), "what it reaches inwards is named: $why")
        assertTrue(why.contains("the bend the run starts with"), "at the station it happens: $why")
        assertTrue(why.contains("radius 30.084 mm"), "and the bend it is measured against: $why")
        assertTrue(why.contains("pass through itself"), "and the consequence: $why")
    }

    /**
     * **The anchor is live**, which is the whole argument for a point over a number: it is an input node of
     * the sweep, so dragging it — or retyping its coordinates — moves the swept body by recompute, with no
     * step re-run and nothing rebuilt (OP-21).
     */
    @Test
    fun draggingTheAnchorMovesTheSweptBody() {
        // a **straight** run, so the frame is one constant map and the body's move can be asserted exactly:
        // along +X the profile's own (x, y) reads as world (+Z, −Y), as [SweepToolTest] states
        val ed = straightRunAndBar()
        // an anchor of its own, off the section: drag a *corner* of the section instead and the section goes
        // with it — the same sharing rule seen from the other side, and the body then correctly stays put
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, -105.0))
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(10.0, -105.0))
        ed.click(Vec2(30.0, -105.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep rides that point: ${ed.statusHint}")
        // fetched *after* the gesture: a checkpointed edit may hand back a fresh document
        val anchor = pointAt(ed.doc, Document.PLAN_SPACE, Vec2(10.0, -105.0))
        val before = Bounds(meshOf(solid))
        assertClose(before.zs.min(), 10.0, 1e-9, "the section is read from 10 mm inside its own edge")
        assertClose(before.zs.max(), 30.0, 1e-9, "…so the bar stands 10..30 mm off the run")

        // move the anchor 1 mm along the section's x: the whole body moves 1 mm the other way, exactly
        assertNotNull(anchor.handle, "the anchor is an ordinary draggable point").drag(Vec2(11.0, -105.0), Evaluator())
        val after = Bounds(meshOf(solid))
        assertClose(after.zs.min(), 9.0, 1e-9, "the body followed the anchor")
        assertClose(after.zs.max(), 29.0, 1e-9, "…as a whole, so it moved rather than grew")
        assertClose(constructit.geom.Geom3.volume(meshOf(solid)), 20.0 * 10.0 * 120.0, 1e-6, "and it is the same bar it was")

        // …and the same through the panel: retyping the coordinate is the same write
        val fields = assertNotNull(anchor.handle).fields()
        assertNotNull(fields.firstOrNull { it.label == "x" }, "the point's x is a field").write(10.0.mm)
        assertClose(Bounds(meshOf(solid)).zs.min(), 10.0, 1e-9, "and retyping it puts the body back")
    }

    // ---- 3. the gesture ----

    /**
     * **Two clicks still build a sweep, and the third click is the anchor.** No Enter, no keystroke, nothing
     * to skip: a click that hits no point *is* the profile's click ([SlotKind.OPTIONAL_POINT]), which is what
     * makes the option cost the plain gesture nothing.
     */
    @Test
    fun twoClicksSweepAndAThirdClickOnAPointStatesTheAnchor() {
        val plain = coilAndSquare()
        plain.setTool(Tools.SWEEP)
        plain.click(Vec2(30.0, 0.0))
        // the square's own edge, away from its corners: the optional slot takes nothing and the area takes it
        plain.click(Vec2(31.0, 5.0))
        val one = assertNotNull(plain.solids().lastOrNull(), "two clicks built it: ${plain.statusHint}")
        assertTrue(!plain.statusHint.contains("riding on"), "with no anchor, because none was clicked: ${plain.statusHint}")

        val anchored = coilAndSquare()
        anchored.setTool(Tools.SWEEP)
        anchored.click(Vec2(30.0, 0.0))
        anchored.click(Vec2(30.0, 5.0)) // the corner point — the optional slot takes it
        assertTrue(anchored.solids().isEmpty(), "the anchor click alone builds nothing: ${anchored.statusHint}")
        // …and it says so, because an optional pick costs no required slot and would otherwise be invisible
        val corner = pointAt(anchored.doc, Document.PLAN_SPACE, Vec2(30.0, 5.0))
        assertTrue(
            anchored.statusHint.startsWith("Point of the section to ride the run: ${anchored.doc.nameOf(corner)}."),
            "the status names what the optional pick took: ${anchored.statusHint}",
        )
        anchored.click(Vec2(31.0, 5.0))
        val two = assertNotNull(anchored.solids().lastOrNull(), "and the third click built it: ${anchored.statusHint}")
        assertTrue(anchored.statusHint.contains("riding on"), "the note names the anchor: ${anchored.statusHint}")

        // the two are different bodies, which is the whole point: the unanchored one reads the square from
        // the plan's own origin and swings it out on a 31 mm arm, the anchored one rides the coil
        assertNull(whyInvalid(one), "both are bodies: ${whyInvalid(one)}")
        assertNull(whyInvalid(two), "and the anchored one is a solid")
        assertTrue(Bounds(meshOf(one)).zs.min() > 29.0, "the unanchored one rides 30 mm off the coil")
        assertClose(Bounds(meshOf(two)).radial.max(), 32.0, 1e-2, "the anchored one rides the coil")
    }

    /**
     * **A click nearer the section's edge than to any point of it is the section's click** — the canvas's own
     * law (nearest wins; a point wins a *tie*, because a corner stands exactly on the outline that ends
     * there), and the reason the optional pick does not quietly eat clicks meant for the area.
     *
     * Asserted at the everyday zoom, where the pick tolerance is 2.5 mm and a corner 1 mm away is therefore
     * well *within* range: it still loses, because the edge is nearer. The corner clicked dead-on wins.
     */
    @Test
    fun aClickNearerTheEdgeThanToAnyCornerIsTheSectionsClick() {
        val ed = straightRunAndBar()
        assertClose(ed.pickToleranceAt(Vec2(0.0, 0.0)), 2.5, 1e-9, "the everyday tolerance, in millimetres")
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        // 1 mm along the bottom edge from the corner at (20, -105): inside the tolerance, but not the nearest
        ed.click(Vec2(21.0, -105.0))
        val plain = assertNotNull(ed.solids().lastOrNull(), "the section took it: ${ed.statusHint}")
        assertTrue(!ed.statusHint.contains("riding on"), "so no anchor was taken: ${ed.statusHint}")
        assertClose(Bounds(meshOf(plain)).zs.min(), 20.0, 1e-9, "and the body is the unanchored one")

        // …and the same corner clicked dead-on is the anchor
        val ed2 = straightRunAndBar()
        ed2.setTool(Tools.SWEEP)
        ed2.click(Vec2(60.0, 0.0))
        ed2.click(Vec2(20.0, -105.0))
        ed2.click(Vec2(30.0, -105.0))
        val riding = assertNotNull(ed2.solids().lastOrNull(), "the anchored one: ${ed2.statusHint}")
        assertTrue(ed2.statusHint.contains("riding on"), "riding on that corner: ${ed2.statusHint}")
        assertClose(Bounds(meshOf(riding)).zs.min(), 0.0, 1e-9, "so the section starts on the run itself")
        assertClose(Bounds(meshOf(riding)).zs.max(), 20.0, 1e-9, "and reaches 20 mm out — its own width")
    }

    /**
     * **The anchor is shared, not copied**: the click landed on a point the drawing already had, so that node
     * is the sweep's input — asserted by identity, and by the step naming it.
     */
    @Test
    fun theAnchorClickSharesThePointItLandsOn() {
        val ed = coilAndSquare()
        val corner = pointAt(ed.doc, Document.PLAN_SPACE, Vec2(30.0, 5.0))
        val solid = anchoredSweep(ed)
        val step = assertNotNull(ed.doc.creatingStep(solid), "the sweep has a step")
        val text = DocumentFormat.save(ed.doc)
        assertTrue(
            text.lines().any { it.startsWith("tool sweep ") && it.contains("pts=${ed.doc.nameOf(corner)}") },
            "the step names the very point that was clicked: ${text.lines().first { it.startsWith("tool sweep") }}",
        )
        assertEquals("tool", step.kind, "and it is an ordinary tool step")
        val inputs = Dependencies.inputsOf(ed.doc, solid)
        val anchorRow = assertNotNull(inputs.firstOrNull { it.element === corner }, "the anchor is listed among the inputs: ${inputs.map { ed.doc.nameOf(it.element) }}")
        assertEquals("point of the section to ride the run", anchorRow.role, "under the tool's own word for that slot")
    }

    /**
     * **The picks survive a change of sketch plane** (`crossSpace`, the loft's own declaration): the run and
     * the section legitimately live in two — a coil on a datum, a section in the plan — and a tool that could
     * not span them could not sweep the user's worm at all.
     */
    @Test
    fun theSweepKeepsItsPicksAcrossASketchPlaneSwitch() {
        val ed = Editor()
        // a straight run in the plan, so nothing about this test is about curvature
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.key("Enter")
        val route = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE })

        // …and a section on a datum plane hinged on a segment through the same two points
        ed.setTool(Tools.SEGMENT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.setTool(Tools.SKETCH_PLANE)
        ed.type("90")
        ed.click(Vec2(60.0, 0.0))
        assertTrue(!ed.activeSpace.isPlan, "the view switched to the datum: ${ed.statusHint}")
        val datum = ed.activeSpace.name
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(60.0, 40.0))

        // the gesture: the run in the plan, then across to the datum for the anchor and the section
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        assertTrue(ed.setActiveSpace(datum), "switch planes mid-gesture")
        assertTrue(ed.statusHint.contains("kept"), "the tool says its picks survived: ${ed.statusHint}")
        ed.click(Vec2(40.0, 20.0))
        ed.click(Vec2(50.0, 20.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the cross-plane sweep was built: ${ed.statusHint}")
        assertTrue(ed.statusHint.contains("riding on"), "anchored, across the switch: ${ed.statusHint}")
        assertEquals(route.space, solid.space, "and it is at home in the run's space, as a sweep always is")
        assertManifold(meshOf(solid), "the cross-plane anchored sweep")
        // the section is read from its anchor: a 20 x 20 square whose corner rides the run
        assertClose(constructit.geom.Geom3.volume(meshOf(solid)), 20.0 * 20.0 * 120.0, 1e-6, "a bar of exactly that section")
        val save = DocumentFormat.save(ed.doc)
        assertEquals(save, DocumentFormat.save(DocumentFormat.load(save)), "save -> load -> save is byte-equal")
    }

    /** **One undo takes the whole anchored gesture** — three clicks, one transaction (OP-27). */
    @Test
    fun oneUndoTakesTheWholeAnchoredSweep() {
        val ed = coilAndSquare()
        val before = ed.doc.elements.size
        anchoredSweep(ed)
        assertEquals(1, ed.solids().size, "the sweep is there")
        assertTrue(ed.undo(), "one undo")
        assertEquals(0, ed.solids().size, "and the solid is gone")
        assertEquals(before, ed.doc.elements.size, "with nothing else left behind: ${ed.doc.elements.size} vs $before")
    }

    // ---- 4. the file ----

    /**
     * **`save → load → save` is byte-equal with an anchored sweep in it**, and the anchored step restates
     * nothing that is not recorded: the anchor rides the ordinary `pts=` argument, so a new step *kind* was
     * not needed and neither was a version bump (OP-18) — nothing a previous build ever wrote changed meaning.
     */
    @Test
    fun anAnchoredSweepRoundTripsByteEqual() {
        val ed = coilAndSquare()
        anchoredSweep(ed)
        val once = DocumentFormat.save(ed.doc)
        assertTrue(once.startsWith("${DocumentFormat.HEADER}\n"), "the anchored sweep owes no bump of its own: ${once.lines().first()}")
        val again = DocumentFormat.save(DocumentFormat.load(once))
        assertEquals(once, again, "save -> load -> save is byte-equal")
        val back = DocumentFormat.load(once)
        assertTrue(back.loadNotes.isEmpty(), "and the load decided nothing: ${back.loadNotes}")
        val solid = assertNotNull(back.elements.lastOrNull { it.kind == ElementKind.SOLID })
        assertNull(whyInvalid(solid), "the reloaded body is the same valid solid")
        val was = Bounds(meshOf(assertNotNull(ed.solids().lastOrNull())))
        val now = Bounds(meshOf(solid))
        assertClose(now.radial.max(), was.radial.max(), 1e-12, "riding the coil exactly as it did")
        assertClose(now.radial.min(), was.radial.min(), 1e-12, "on the inside too")
        assertClose(now.zs.max(), was.zs.max(), 1e-12, "and rising exactly as far")
    }

    /**
     * **A file written before the anchor existed means exactly what it always meant.** Written by hand in the
     * shape the old build wrote — a `tool sweep` step with `els=` and no `pts=` — it loads with no note, and
     * the section is read with its *own origin* on the run: the eccentric bar of the old contract.
     */
    @Test
    fun aFileWithNoAnchorLoadsWithTheOldReading() {
        val doc = DocumentFormat.load(OLD_SWEEP_CIT)
        assertTrue(doc.loadNotes.isEmpty(), "no migration, nothing to decide: ${doc.loadNotes}")
        val solid = assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "the sweep loaded")
        assertNull(whyInvalid(solid), "and it is valid")
        val mesh = meshOf(solid)
        assertManifold(mesh, "the old unanchored sweep")
        // the route runs along +X in the plan, so the frame's own (x, y) maps to world (+Z, -Y): a 20 x 10
        // rectangle centred 30 mm up the space's x axis stands 20..40 mm off the run in +Z — unchanged
        val b = Bounds(mesh)
        assertClose(b.zs.min(), 20.0, 1e-9, "the section's own coordinates still put its lower edge 20 mm off the run")
        assertClose(b.zs.max(), 40.0, 1e-9, "and its upper edge 40 mm off it")
        assertEquals(DocumentFormat.save(doc), DocumentFormat.save(DocumentFormat.load(DocumentFormat.save(doc))), "and it re-saves stably")
        // …and no anchor was invented for it: the step it re-saves has no point argument at all
        assertTrue(
            DocumentFormat.save(doc).lines().first { it.startsWith("tool sweep") }.let { !it.contains("pts=") },
            "the re-saved step states no anchor: ${DocumentFormat.save(doc).lines().first { it.startsWith("tool sweep") }}",
        )
    }

    // ---- 5. what the anchor is, and what it is not ----

    /**
     * **An anchor drawn in another plane is refused by name**, with the alternative said out loud: the two are
     * subtracted, and coordinates of two different planes have no difference. A refusal, not a guess.
     */
    @Test
    fun anAnchorFromAnotherPlaneIsRefusedByName() {
        val doc = DocumentFormat.load(WORM_CIT)
        // the coil's own start point, which lives on plane1 — the very point the coordinates were taken from
        val onPlane1 = pointAt(doc, "plane1", Vec2(5.274061990212072, 0.03915171288743882))
        val before = doc.elements.size
        val solid = doc.sweepAlongCurve(named(doc, "thread"), named(doc, "worm"), null, null, refOf(onPlane1))
        assertNull(solid, "nothing is built")
        val why = assertNotNull(doc.note, "and it says why")
        assertTrue(why.contains(doc.nameOf(onPlane1)), "naming the point: $why")
        assertTrue(why.contains("plane1") && why.contains("plan"), "and both planes: $why")
        assertTrue(why.contains("place one there") || why.contains("leave it out"), "and the way out: $why")
        assertEquals(before, doc.elements.size, "with nothing left behind")
    }

    /**
     * **A point in space is refused by name as an anchor, on every route in** — the backstop behind the
     * gesture's own reading (the optional slot declines a candidate it cannot use, so a click on a rider's dot
     * goes to the section instead). Reached here through the document, which is how a replay, the DSL and any
     * later route reach it — and where a `Point3Ref` would otherwise slip into a `PointRef` input through a
     * cast that cannot fail, since `PointRef` is `Ref<PointValue>` and its type argument is erased.
     */
    @Test
    fun aPointInSpaceIsRefusedAsAnAnchorByName() {
        val ed = straightRunAndBar()
        // a height point (OP-25) over a plan point: a point in space whose plan image is its base's own dot
        ed.setTool(Tools.POINT)
        ed.click(Vec2(10.0, -105.0))
        ed.setTool(Tools.HEIGHT_POINT)
        ed.type("15")
        ed.click(Vec2(10.0, -105.0))
        val lifted = assertNotNull(ed.doc.elements.lastOrNull { it.inSpace }, "the height point: ${ed.statusHint}")
        val route = assertNotNull(ed.doc.elements.firstOrNull { it.kind == ElementKind.SPACE_CURVE }, "the run")
        // any leg of the closed rectangle is the section, exactly as the click's own pick is
        val bar = assertNotNull(ed.doc.elements.firstOrNull { it.kind == ElementKind.SEGMENT }, "a leg of the section")
        val before = ed.doc.elements.size

        assertNull(ed.doc.sweepAlongCurve(route, bar, null, null, refOf(lifted)), "nothing is built")
        val why = assertNotNull(ed.doc.note, "and it says why")
        assertTrue(why.contains(ed.doc.nameOf(lifted)), "naming the point: $why")
        assertTrue(why.contains("point in space"), "and what it is: $why")
        assertTrue(why.contains("place a point in plan") && why.contains("leave it out"), "and both ways out: $why")
        assertEquals(before, ed.doc.elements.size, "with nothing left behind")

        // …and the gesture never offers one: clicking the lifted point's plan dot is the section's click, so
        // the sweep completes as the unanchored reading it always was
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(60.0, 0.0))
        ed.click(Vec2(30.0, -105.0))
        val solid = assertNotNull(ed.solids().lastOrNull(), "the sweep completed: ${ed.statusHint}")
        assertTrue(!ed.statusHint.contains("riding on"), "unanchored: ${ed.statusHint}")
        assertClose(Bounds(meshOf(solid)).zs.min(), 20.0, 1e-9, "the section's own origin rode the run")
    }

    /**
     * **The tube gained nothing, and that is a decision.** A round section is centred on the path by
     * definition — its radius *is* its reach — so there is no off-origin reading to correct and no third pick
     * to offer. Asserted on the table, where the decision lives.
     */
    @Test
    fun theTubeNeedsNoAnchorAndDoesNotOfferOne() {
        val tube = assertNotNull(Tools.byId(Tools.TUBE))
        assertEquals(listOf(SlotKind.PATH3), tube.slots, "one pick, as before")
        val sweep = assertNotNull(Tools.byId(Tools.SWEEP))
        assertEquals(listOf(SlotKind.PATH3, SlotKind.OPTIONAL_POINT, SlotKind.AREA), sweep.slots, "and the sweep's optional pick sits between its two")
        assertTrue(sweep.crossSpace, "which may be picked in another plane than the run")
        assertTrue(sweep.help.contains("ride the run"), "and the help says what the point is: ${sweep.help}")
        assertTrue(sweep.help.contains("from where it is drawn"), "and what leaving it out means now: ${sweep.help}")
        assertTrue(sweep.help.contains("area's own origin rides the run"), "…and when that is all it can mean: ${sweep.help}")
        // the one structural promise [SlotKind.OPTIONAL_POINT] makes: never last, or nothing could skip it
        for (def in Tools.all) {
            assertTrue(
                !Tools.isOptionalSlot(def.slots.lastOrNull()),
                "${def.id} ends with an optional slot, which no click could skip",
            )
        }
    }

    // ---- the fixtures ----

    /**
     * A coil of radius 30 mm, pitch 10 mm, two turns about the plan's origin, and a 2 × 2 mm square drawn
     * **in place** at (30, 5) — through the ordinary gestures, on a camera zoomed in far enough that a 2 mm
     * square's corners and edges are separate clicks (the pick tolerance is 10 px).
     */
    private fun coilAndSquare(): Editor {
        val ed = Editor()
        ed.camera = Camera(-800.0, 500.0, 40.0)
        ed.setTool(Tools.HELIX)
        ed.type("30")
        ed.type("10")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, "the coil: ${ed.statusHint}")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(32.0, 7.0))
        return ed
    }

    /**
     * A straight run along +X in the plan and a 20 × 10 rectangle drawn well off the origin — the fixture
     * whose frame is constant, so every claim about *where* the body went is exact.
     */
    private fun straightRunAndBar(): Editor {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(120.0, 0.0))
        ed.key("Enter")
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(20.0, -105.0))
        ed.click(Vec2(40.0, -95.0))
        return ed
    }

    /** The three-click gesture: the coil, the square's lower-left corner, then the square. */
    private fun anchoredSweep(ed: Editor): Element {
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(30.0, 5.0))
        ed.click(Vec2(31.0, 5.0))
        return assertNotNull(ed.solids().lastOrNull(), "the anchored sweep: ${ed.statusHint}")
    }

    /** …and the two-click one, which is the gesture that existed before. */
    private fun unanchoredSweep(ed: Editor): Element {
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(30.0, 0.0))
        ed.click(Vec2(31.0, 5.0))
        return assertNotNull(ed.solids().lastOrNull(), "the unanchored sweep: ${ed.statusHint}")
    }

    companion object {
        /**
         * A drawing in the shape a build **before** the anchor wrote it: a `tool sweep` step with two element
         * picks and no `pts=` at all. Kept as a permanent load test, because an in-build round trip proves
         * nothing across builds (OP-18).
         */
        val OLD_SWEEP_CIT =
            """
            constructit 2
            point 0,0 -> e1
            point 120,0 -> e2
            tool curve3 els=e1,e2 clicks=0,0;120,0 -> e3
            orthostart 20,-105 -> e4
            orthovertex 40,-105 -> e5,e6
            orthovertex 40,-95 -> e7,e8
            orthovertex 20,-95 -> e9,e10
            orthoclose -> e11
            tool sweep els=e3,e6 clicks=60,0;30,-105 -> e12
            """.trimIndent() + "\n"

        /** The user's own script from GitHub issue #15, verbatim as they pasted it. */
        val WORM_CIT =
            """
            constructit 2
            param "r" = 20mm
            param "d" = 4mm
            param "s" = 5mm
            point 0,0 -> e1
            orthostart 0,0 -> e2
            weldortho e2 e1
            orthovertex 0,55 -> e3,e4
            tool parallelat els=e4 clicks=-17,8;26.25,8.5 scalar="d" -> e5
            tool perp pts=e2 els=e5 clicks=22.539412499999983,-3.0633699999999893;-17.32071000000002,-10.31066499999999 -> e6
            tool parallelat els=e6 clicks=88.94275293749996,-10.948024332499983;119.48889347999996,54.96733157500002 scalar="s" -> e7
            tool line pts=e2,e3 clicks=-15.557201550000032,-13.091613142499984;-13.94950994250003,57.110920385000014 -> e8
            tool intersect els=e5,e7 clicks=23.027397029999968,49.60835955000002;55.71712638249997,19.062219007500016 -> e9
            tool circleR pts=e9 clicks=23.563294232499967,20.134013412500018 scalar="r" -> e10
            tool intersect els=e8,e10 clicks=-18.772584765000033,235.02879161500002;4.806892144999968,219.4877727425 -> e11,e12
            tool circleR pts=e12 clicks=-17.164893157500032,213.59290351500002 scalar="r" -> e13
            tool intersect els=e8,e13 clicks=-17.60968783557502,430.122168185125;-51.97677543190002,412.61440657945 -> e14,e15
            tool intersect els=e5,e6 clicks=23.241755910999977,0.8577910385750537;58.90571473737498,-10.814050031874945 -> e16
            param "s2" = 1mm
            tool concentric els=e13 clicks=106.24151463419997,371.11452721785;163.3038487564,379.54419021317506 scalar="s2" -> e17
            tool parallelat els=e5 clicks=22.453653208524944,-88.56429870247442;102.03438777977483,-80.31148178397443 scalar="s2" -> e18
            tool intersect els=e18,e6 clicks=24.037626963756434,-5.468285542611265;28.599840944609255,-10.682244377871633 -> e19
            tool intersect els=e17,e18 clicks=32.872390545725395,20.746340823558917;23.89279477388809,14.373724469351801 -> e20,e21
            tool intersect els=e17,e8 clicks=-22.182596666542665,417.49460574293346;-16.11800072381616,421.3432916296638 -> e22,e23
            hide els=e21
            hide els=e10
            tool arccs pts=e12,e9,e15 clicks=-17.523044034741428,237.7041419193154;24.812834962758394,39.95807419681622;-18.594838439741427,435.9861068443146 -> e24
            tool arccs pts=e12,e20,e23 clicks=-17.523044034741428,235.0246559068154;33.387190202758354,31.383718956816256;-18.058941237241427,443.4886676793145 -> e25
            tool segment pts=e9,e16 clicks=22.133348950258405,42.10166300681621;25.88462936775839,-9.88036563568357 -> e26
            tool segment pts=e20,e19 clicks=33.92308740525836,30.84782175431626;34.99488181025835,-9.88036563568357 -> e27
            tool segment pts=e19,e16 clicks=34.45898460775835,-12.023954445683561;25.34873216525839,-9.344468433183572 -> e28
            tool segment pts=e23,e15 clicks=-17.523044034741428,446.1681536918145;-17.523044034741428,437.59379845181456 -> e29
            tool outline els=e28,e27,e25,e29,e24,e26 clicks=29.100012582758374,-10.416262838183567;33.387190202758354,7.268344844316359;191.23472835459603,260.8908799790167;-17.249999999999993,440.70917942265424;181.7372306010248,255.8109718236959;22.750000000000014,14.75 -> e30,e31,e32,e33,e34,e35,e36
            param "a" = 360deg
            tool revolve els=e36,e8 clicks=85.07761038251773,51.99563831311234;-17.627458245676575,501.6539639878118 scalar="a" -> e37
            param "offset" = 0.2mm
            tool parallelat els=e6 clicks=7.699691132442241,0.022707371445662528;7.725072531940934,1.037963351393366 scalar="offset" -> e38
            param "angle" = 90deg
            sketchspace "plane1" line=e38 angle="angle"
            space "plan"
            param "f" = 0.1mm
            orthostart 5,0.06786480898911855 -> e39
            attachortho e39 e27
            orthovertex 5.390389817016633,0.06786480898911855 -> e40,e41
            orthovertex 5.390389817016633,0.41180564951125387 -> e42,e43
            orthovertex 5,0.41180564951125387 -> e44,e45
            attachortho e44 e27
            orthostart 5,0.41180564951125387 -> e46
            weldortho e46 e44
            orthodiscard
            tool fillet els=e41,e43 clicks=5.436560748379836,0.42342954909532965;5.596969926764663,0.6774107482046376 scalar="f" signs=-1;1 -> e47
            tool fillet els=e43,e45 clicks=5.596969926764663,0.8511873581215326;5.33630501188932,0.9915453892082554 scalar="f" signs=-1;1 -> e48
            tool keypoints els=e47 clicks=5.250474857646745,0.4627894997526411 -> e49,e50,e51
            tool keypoints els=e48 clicks=5.24518550751573,0.6346933790106211 -> e52,e53,e54
            tool segment pts=e46,e54 clicks=4.995263713825283,0.6703964923949708;5.168489930616016,0.6690741548622171 -> e55
            tool segment pts=e53,e51 clicks=5.283533295965587,0.5738658525039513;5.282210958432834,0.5381627391196016 -> e56
            tool segment pts=e50,e39 clicks=5.18435798100906,0.437665086630321;4.993941376292529,0.4389874241630747 -> e57
            tool segment pts=e39,e46 clicks=5.001875401489051,0.437665086630321;5.000553063956297,0.665107142263956 -> e58
            hide els=e45
            hide els=e43
            hide els=e41
            hide els=e49,e52
            space "plane1"
            param "delta" = 1mm
            point 0,0 -> e59
            point 5.274061990212072,0.03915171288743882 -> e60
            tool helixpt els=e59,e60 clicks=-0.013050570962479609,-0.013050570962479609;4.985318107667211,0.013050570962479609 scalar="delta" -> e61
            space "plan"
            tool outline els=e57,e47,e56,e48,e55,e58 clicks=5.126399831163981,0.06393038400059936;5.352766829512932,0.09033986714131022;5.390389817016633,0.23983522925018622;5.361100495135288,0.3825163276299086;5.145194908508317,0.41180564951125387;5,0.23983522925018622 -> e62,e63,e64,e65,e66,e67,e68
            name e68 "worm"
            space "plane1"
            name e61 "thread"
            """.trimIndent() + "\n"
    }
}
