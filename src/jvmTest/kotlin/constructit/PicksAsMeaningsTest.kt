package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Section3
import constructit.geom.Vec2
import constructit.units.mm
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A geometry change upstream does not move a pick** (OP-32, slice 2) — the property the slice exists for.
 *
 * A file of version 11 stored each tool's gesture as the positions that were clicked, and several builds read the
 * position again on every load: which side of a line, which sector of an angle, which way along a line. Edit the
 * drawing so that the old click position falls on the other side — move a point, turn a line — and the next load
 * built a *different* drawing from the same file. Version 12 states what the click meant, once, so nothing a load
 * does depends on where anyone clicked.
 *
 * Each probe takes the same edit two ways: applied to the **version-11** file (the old click is read again, and the
 * pick moves — the hazard, shown to exist), and applied to the drawing **in the session** and then saved and loaded
 * as version 12 (the pick stays where it was, the drawing the user saw is the drawing that comes back). Edges and
 * faces of solids are named by their addresses, so the probe there is the counterfactual: a fresh click at the old
 * position names a different edge or face, and the stored pick does not follow it.
 */
class PicksAsMeaningsTest {
    private val v11 = File("src/jvmTest/resources/v11")

    private fun fixture(name: String): String = File(v11, "$name.cit").readText()

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

    private fun Document.el(name: String): Element = elements.first { nameOf(it) == name }

    private fun pointOf(
        doc: Document,
        name: String,
    ): Vec2 = assertNotNull((Evaluator().valueOf(doc.el(name).ref) as? PointValue)?.p, "$name is a point with a value")

    /** [doc]'s free point [name] moved to [to], in the session — what a drag writes (OP-13). */
    private fun move(
        doc: Document,
        name: String,
        to: Vec2,
    ) {
        val node = geometryNodeOf(doc.el(name)) as constructit.core.SourceNode
        node.value = PointValue(to)
    }

    /** The version-11 [text] with the free `point` feature that creates [name] standing at [to] instead. */
    private fun movedInFile(
        text: String,
        name: String,
        to: Vec2,
    ): String =
        mapFeatures(text) { f ->
            if (f.str("kind") == "point" && f.ids("creates") == listOf(name)) f.with("at", "[${to.x},${to.y}]") else f
        }

    /** The version-12 round trip of [doc]: what a save states, loaded back. */
    private fun reloaded(doc: Document): Document {
        val json = DocumentFormat.save(doc)
        for (f in saved(json).features) assertTrue(!f.containsKey("clicks") && !f.containsKey("click") && !f.containsKey("cells"), "no click is saved: $f")
        return DocumentFormat.load(json)
    }

    // ---- a choice: which side, which way, which sector ----

    @Test
    fun aPointAtADistanceKeepsItsDirectionWhenItsAnchorMovesPastTheClick() {
        // the click at (30, 0) said "towards +x" from the anchor e3 at the origin; move the anchor to (40, 0)
        val old = DocumentFormat.load(movedInFile(fixture("ptatdist"), "e3", Vec2(40.0, 0.0)))
        assertClose(pointOf(old, "e5").x, 25.0, 1e-9, "version 11 re-read the click, which is now behind the anchor: the point jumped to the other side")

        val doc = DocumentFormat.load(fixture("ptatdist"))
        assertClose(pointOf(doc, "e5").x, 15.0, 1e-9, "as drawn: 15 mm towards the click")
        move(doc, "e3", Vec2(40.0, 0.0))
        assertClose(pointOf(doc, "e5").x, 55.0, 1e-9, "in the session the direction is the node's, so the point follows its anchor")
        val back = reloaded(doc)
        assertClose(pointOf(back, "e5").x, 55.0, 1e-9, "and version 12 loads exactly that: the direction is stated, not re-read")
        assertEquals(listOf(1), saved(back).tool(Tools.POINT_AT_DIST).ints("signs"), "the direction, as the sign it is")
    }

    @Test
    fun aConcentricCircleKeepsItsSideWhenItsCircleShrinksInsideTheClick() {
        // the click at (5, 0) was inside the 30 mm circle, so the offset shrinks it; make the circle 3 mm and the
        // offset 1 mm, and the click is outside
        fun edited(text: String): String =
            mapFeaturesAndParams(movedInFile(text, "e2", Vec2(3.0, 0.0)), "d", "1 mm")
        val old = DocumentFormat.load(edited(fixture("concentric")))
        assertClose(radiusOf(old, "e4"), 4.0, 1e-9, "version 11 re-read the click, now outside the circle: the inner circle became an outer one")

        val doc = DocumentFormat.load(fixture("concentric"))
        assertClose(radiusOf(doc, "e4"), 20.0, 1e-9, "as drawn: 10 mm inside the 30 mm circle")
        move(doc, "e2", Vec2(3.0, 0.0))
        doc.setParameter(doc.scalars.first { it.name == "d" }, 1.0.mm)
        assertClose(radiusOf(doc, "e4"), 2.0, 1e-9, "in the session it stays the inner circle")
        val back = reloaded(doc)
        assertClose(radiusOf(back, "e4"), 2.0, 1e-9, "and version 12 loads the inner circle")
        assertEquals(listOf(-1), saved(back).tool(Tools.CONCENTRIC).ints("signs"))
    }

    @Test
    fun aParallelKeepsItsSideWhenItsLineMovesAcrossTheClick() {
        // e4 runs from e1 to the rider e3 and its two parallels e5 and e6 were each clicked to one side of it — the
        // drawing as it stands has both on one side, because version 11 re-read the clicks against a line that had
        // turned since. Move the centre e1 far to the right, and the line crosses the clicks again.
        val to = Vec2(60.0, 3.5)
        val old = DocumentFormat.load(movedInFile(fixture("parallelat-fillet-pointoncurve"), "e1", to))
        val doc = DocumentFormat.load(fixture("parallelat-fillet-pointoncurve"))
        val sideBefore = listOf("e5", "e6").map { sideOf(doc, "e4", it) }
        val sideOld = listOf("e5", "e6").map { sideOf(old, "e4", it) }
        assertNotEquals(sideBefore, sideOld, "version 11 re-read the clicks against the moved line: the parallels swapped sides")

        move(doc, "e1", to)
        assertEquals(sideBefore, listOf("e5", "e6").map { sideOf(doc, "e4", it) }, "in the session each parallel keeps its side")
        val back = reloaded(doc)
        assertEquals(sideBefore, listOf("e5", "e6").map { sideOf(back, "e4", it) }, "and version 12 loads them there")
    }

    @Test
    fun anAngularDimensionKeepsItsSectorWhenALineTurnsPastTheClick() {
        // e11 runs from e9 (0,-50) to e10 (100,50) and meets e8 (the x axis) at (50,0); the click (30,5) chose a
        // sector to the left of that crossing. Steepen e11 so that it crosses at (25,0): the click is now right of it.
        val to = Vec2(50.0, 50.0)

        fun edited(text: String) = movedInFile(movedInFile(text, "e9", Vec2(0.0, -50.0)), "e10", to)
        val old = DocumentFormat.load(edited(fixture("dimensions")))
        val doc = DocumentFormat.load(fixture("dimensions"))
        move(doc, "e10", to)
        val session = angleOf(doc, "e12")
        val oldAngle = angleOf(old, "e12")
        assertNotEquals(session, oldAngle, 1e-6, "version 11 re-read the click and measured a different angle ($oldAngle against $session)")
        val back = reloaded(doc)
        assertClose(angleOf(back, "e12"), session, 1e-9, "version 12 measures the sector the click chose")
        assertEquals(2, saved(back).tool(Tools.DIM_ANGULAR).ints("signs").size, "the sector, as two signs")
    }

    /**
     * **The Outline tracer's trims**: a chord across a circle traced into a boundary — the click on the circle chose
     * the arc *above* the chord. Lift the circle above the click and the chord with it, and version 11 re-read the
     * click — now below the centre — and traced the arc *below* the chord; version 12 states which way round each piece runs (`signs`, after one number per
     * joint).
     */
    @Test
    fun anOutlineKeepsTheArcItTracedWhenTheChordMovesPastTheClick() {
        val v11 =
            """
            {"format":"constructit","version":11,"nextId":{"e":10,"f":8},"parameters":[],"features":[
             {"id":"f1","kind":"point","at":[0,0],"creates":["e1"]},
             {"id":"f2","kind":"point","at":[30,0],"creates":["e2"]},
             {"id":"f3","kind":"tool","tool":"circle","pts":["e1","e2"],"clicks":[[0,0],[30,0]],"creates":["e3"]},
             {"id":"f4","kind":"point","at":[-40,10],"creates":["e4"]},
             {"id":"f5","kind":"point","at":[40,10],"creates":["e5"]},
             {"id":"f6","kind":"tool","tool":"line","pts":["e4","e5"],"clicks":[[-40,10],[40,10]],"creates":["e6"]},
             {"id":"f7","kind":"tool","tool":"outline","els":["e6","e3"],"clicks":[[0,10],[0,30]],"creates":["e7","e8","e9"]}
            ]}
            """.trimIndent()
        val moves = listOf("e1" to Vec2(0.0, 60.0), "e2" to Vec2(40.0, 60.0), "e4" to Vec2(-60.0, 40.0), "e5" to Vec2(60.0, 40.0))

        fun edited(text: String) = moves.fold(text) { t, (n, to) -> movedInFile(t, n, to) }
        val old = DocumentFormat.load(edited(v11))
        val doc = DocumentFormat.load(v11)
        val above = topOf(doc, "e9")
        assertTrue(above > 25.0, "as traced: the cap above the chord ($above)")
        for ((n, to) in moves) move(doc, n, to)
        assertClose(topOf(doc, "e9"), 100.0, 1e-9, "in the session the boundary keeps the arc above the chord, over the top of the lifted circle")
        assertTrue(topOf(old, "e9") < 45.0, "version 11 re-read the click, now below the centre, and traced the arc under the chord instead")
        val back = reloaded(doc)
        assertClose(topOf(back, "e9"), 100.0, 1e-9, "and version 12 traces the arc the click chose")
        assertEquals(listOf(0, 0, 0, 0), saved(back).tool(Tools.OUTLINE).ints("signs"), "two joints a chord and a circle have in their canonical order, the chord, and the arc clockwise from the one to the other")
    }

    // ---- a point on a curve ----

    @Test
    fun aRiderStaysAtItsParameterWhenItsHostTurnsAwayFromTheClick() {
        // the rider e5 was clicked at (30, 0) on the segment e1–e2; stand the segment upright
        val doc = DocumentFormat.load(fixture("ptonline-makerel"))
        move(doc, "e2", Vec2(0.0, 60.0))
        val session = pointOf(doc, "e5")
        val back = reloaded(doc)
        assertClose((pointOf(back, "e5") - session).length(), 0.0, 1e-9, "the rider comes back where its parameter puts it, not under the old click")
        val f = saved(DocumentFormat.save(back)).tool(Tools.POINT_ON_LINE)
        assertEquals(1, f.nums("dofs").size, "the curve and the parameter are all the file says: $f")
    }

    // ---- an edge of a solid ----

    @Test
    fun aFilletStaysOnItsEdgeWhenTheOutlineMovesUnderItsClick() {
        // GitHub #36: the first fillet was clicked at (-31.25, 11.32), beside the left wall of the L. Move that
        // wall to x = -40 and the same position lies right under the top wall instead.
        val doc = DocumentFormat.load(fixture("issue36-fillets"))
        val json = DocumentFormat.save(doc)
        val f11 = saved(json).features.first { it.str("id") == "f11" }
        assertEquals(13, f11.int("edge"), "the first fillet states its edge: $f11")
        val extrusion = doc.el("e13")
        val named = edgeName(doc, extrusion, 13)
        val moved =
            mapFeatures(json) { f ->
                if (f.str("kind") != "path") return@mapFeatures f
                val vs = f.arr("vertices").map { it as JsonObject }
                val first = vs[0].with("at", "[-40,15.375]")
                f.with("at", "[-40,-32.375]").with("vertices", (listOf(first) + vs.drop(1)).joinToString(",", "[", "]"))
            }
        val back = DocumentFormat.load(moved)
        assertEquals(named, edgeName(back, back.el("e13"), 13), "edge 13 is the same edge of the moved L")
        val body = back.el("e14")
        val v = Evaluator().valueOf(body.ref) as? SolidValue
        assertNotNull(v, "the twice-filleted L still builds: ${(Evaluator().eval(body.ref.node) as? EvalResult.Invalid)?.reason}")
        assertManifold(v.solid.mesh, "the moved L")
        assertEquals(13, saved(DocumentFormat.save(back)).features.first { it.str("id") == "f11" }.int("edge"), "and it is saved on edge 13 again")

        // the counterfactual: the old click, on the moved L, names a different edge
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(moved))
        ed.activeScalar = ed.doc.newParameter("r2", 1.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(-31.252365457721638, 11.31587462139538))
        val picked = saved(DocumentFormat.save(ed.doc)).tools(Tools.BLEND_EDGE).last()
        assertNotEquals(13, picked.int("edge"), "a click at the old position now names another edge: ${ed.statusHint}")
    }

    // ---- a face of a solid ----

    @Test
    fun aFaceRoundingStaysOnItsFaceWhenTheSweepTurnsUnderItsClick() {
        // a bar beside the X axis revolved a quarter turn; the click at (30, 25) lands on the start cap, which lies in
        // the plan. Turn the sweep's offset by -90 degrees and the cap lying in the plan is the *end* cap.
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
        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_FACE)
        ed.click(Vec2(30.0, 25.0))
        val json = DocumentFormat.save(ed.doc)
        val face = assertNotNull(saved(json).tool(Tools.BLEND_FACE).int("face"), "the face is stated: $json")
        val revolved = ed.doc.elements.first { it.kind == ElementKind.SOLID }
        val named = faceName(ed.doc, revolved, face)

        val turned = mapFeatures(json) { f -> if (f.str("tool") == Tools.REVOLVE) f.with("dofs", """[{"deg":-90}]""") else f }
        val back = DocumentFormat.load(turned)
        assertEquals(face, saved(DocumentFormat.save(back)).tool(Tools.BLEND_FACE).int("face"), "the rounding stays on its face")
        assertEquals(named, faceName(back, back.elements.first { it.kind == ElementKind.SOLID }, face), "the same named face")

        val again = Editor()
        again.replaceDocument(DocumentFormat.load(turned))
        again.activeScalar = again.doc.newParameter("r2", 1.0.mm)
        again.setTool(Tools.BLEND_FACE)
        again.click(Vec2(30.0, 25.0))
        val picked = saved(DocumentFormat.save(again.doc)).tools(Tools.BLEND_FACE).last()
        assertNotEquals(face, picked.int("face"), "a click at the old position now names another face: ${again.statusHint}")
    }

    // ---- a meaning that no longer resolves ----

    /**
     * **An edge an upstream change takes away leaves the fillet invalid, saying which edge, and undoing heals it**
     * (OP-3, OP-32 slice 2). A rounded hexagon — a pattern, an orbit of sides, an orbit of fillets, the outline traced
     * round them — extruded, and one rim of its top rounded: edge 24 + k of 36, and with it the whole tangent run of
     * the rim, edges 24 to 35, which the file records (`runs`). Re-stamp the pattern to three and the outline has six
     * pieces: the extrusion has eighteen edges, the run's are not among them, and the re-stamp's own reload builds the
     * rounding as what its file states — invalid, naming the first edge it cannot find — rather than failing to load
     * or putting the fillet on whatever edges have those numbers now. Undo is a reload of the snapshot before, and
     * the fillet is back.
     */
    @Test
    fun aFilletWhoseEdgeAnUpstreamChangeTakesAwayIsInvalidNamingItAndUndoHealsIt() {
        val ed = Editor()
        val loaded = DocumentFormat.load(fixture("outline-polygon-orbits"))
        val outline = loaded.elements.last { it.kind == ElementKind.OUTLINE }
        val withSolid =
            withEntries(
                DocumentFormat.save(loaded),
                parameters = listOf("""{"id":"f90","name":"depth","value":"10 mm"}"""),
                features = listOf("""{"id":"f91","kind":"tool","tool":"extrude","els":["${loaded.nameOf(outline)}"],"params":["f90"],"creates":["e90"]}"""),
            )
        ed.replaceDocument(DocumentFormat.load(withSolid))
        ed.activeScalar = ed.doc.newParameter("r", 2.0.mm)
        ed.setTool(Tools.BLEND_EDGE)
        ed.click(Vec2(-75.0, -43.30127018922191))
        val fillet = saved(DocumentFormat.save(ed.doc)).tools(Tools.BLEND_EDGE).single()
        val edge = assertNotNull(fillet.int("edge"), "the rounding states its edge: $fillet")
        assertTrue(edge >= 24, "a rim of the top cap: edge $edge of 36")
        val body = { ed.doc.elements.last { it.kind == ElementKind.SOLID } }
        assertTrue(Evaluator().eval(body().ref.node) is EvalResult.Ok, "the rounded prism builds")

        assertTrue(ed.setPatternCount(assertNotNull(ed.doc.patternNamed("P1")), 3), "re-stamped to three: ${ed.statusHint}")
        assertEquals(edge, saved(DocumentFormat.save(ed.doc)).tools(Tools.BLEND_EDGE).single().int("edge"), "the rounding keeps its address — never a re-pick")
        val why = (Evaluator().eval(body().ref.node) as? EvalResult.Invalid)?.reason
        assertNotNull(why, "the rounding cannot be built on a prism of eighteen edges")
        assertTrue(why.contains("edge #") && why.contains("(it has 18)"), "and it says which edge is missing: $why")
        assertTrue(ed.doc.invalidElements().any { it.element === body() && "edge #" in it.reason }, "the panel lists the rounded body as invalid, with that reason")
        // the invalid drawing is a drawing: it saves, and its save loads to the same invalid rounding
        val text = DocumentFormat.save(ed.doc)
        val again = DocumentFormat.load(text)
        assertSameFile(DocumentFormat.save(again), text, "the invalid rounding round-trips")

        assertTrue(ed.undo(), "undo")
        assertEquals(6, assertNotNull(ed.doc.patternNamed("P1")).count)
        val healed = Evaluator().eval(body().ref.node)
        assertTrue(healed is EvalResult.Ok, "undoing the change heals the rounding: ${(healed as? EvalResult.Invalid)?.reason}")
        assertManifold((healed.value as SolidValue).solid.mesh, "the healed prism")
    }

    /**
     * **A hand edit that names an edge the body never had is an invalid rounding, not a load error** (OP-3), and
     * so is a rounding chained onto a body that has no value when the file is read — the case that failed to load
     * before this slice, *"created 0 element(s) but the script declares 1"*.
     */
    @Test
    fun anEdgeTheBodyDoesNotHaveLoadsAsAnInvalidRoundingNamingIt() {
        val json = DocumentFormat.save(DocumentFormat.load(fixture("issue36-fillets")))
        val edited = mapFeatures(json) { f -> if (f.str("id") == "f11") f.with("edge", "40") else f }
        val doc = DocumentFormat.load(edited)
        val body = doc.el("e14")
        val why = (Evaluator().eval(body.ref.node) as? EvalResult.Invalid)?.reason
        assertNotNull(why, "the body cannot be rounded along an edge it does not have")
        assertTrue(why.contains("#41"), "and says which: $why")
        assertEquals(40, saved(DocumentFormat.save(doc)).features.first { it.str("id") == "f11" }.int("edge"), "the file's address is kept, not repaired")
        assertEquals(listOf("e14", "e15", "e16"), doc.elements.filter { it.kind == ElementKind.SOLID || it.kind == ElementKind.DRESSING }.map { doc.nameOf(it) }.filter { it != "e13" }, "both roundings stand, the second in the same dressing")
        // …and a face the body does not have, likewise
        val shell =
            DocumentFormat.save(DocumentFormat.load(fixture("shell-revolve"))).let { t ->
                mapFeatures(t) { f -> if (f.str("tool") == Tools.SHELL) f.with("face", "99") else f }
            }
        val hollow = DocumentFormat.load(shell).elements.last { it.kind == ElementKind.SOLID }
        val whyShell = (Evaluator().eval(hollow.ref.node) as? EvalResult.Invalid)?.reason
        assertNotNull(whyShell, "a shell open at a face the body does not have is invalid")
        assertTrue(whyShell.contains("#100"), "naming the face: $whyShell")
    }

    // ---- what the probes read ----

    /** The highest point an outline's boundary reaches — which side of a chord its arc lies on. */
    private fun topOf(
        doc: Document,
        name: String,
    ): Double {
        val loop = assertNotNull((Evaluator().valueOf(doc.el(name).ref) as? constructit.core.LoopValue)?.loop, "$name is a traced boundary with a value")
        return loop.elements.flatMap { e ->
            when (e) {
                is constructit.geom.ProfileElement.Seg -> listOf(e.segment.a.y, e.segment.b.y)
                is constructit.geom.ProfileElement.ArcE -> (0..64).map { i -> constructit.geom.GeomMath.arcPointAt(e.arc, e.arc.startAngle + constructit.geom.GeomMath.sweep(e.arc) * i / 64.0).y }
                else -> emptyList()
            }
        }.max()
    }

    private fun radiusOf(
        doc: Document,
        name: String,
    ): Double = assertNotNull((Evaluator().valueOf(doc.el(name).ref) as? constructit.core.CircleValue)?.circle?.radius, "$name has a value")

    /** Which side of the line [line] the parallel [other] stands on: the sign of its offset along the normal. */
    private fun sideOf(
        doc: Document,
        line: String,
        other: String,
    ): Int {
        val ev = Evaluator()
        val l = assertNotNull((ev.valueOf(doc.el(line).ref) as? constructit.core.SegmentValue)?.seg ?: (ev.valueOf(doc.el(line).ref) as? constructit.core.LineValue)?.line?.let { constructit.geom.Segment(it.origin, it.origin + it.dir) })
        val o = assertNotNull((ev.valueOf(doc.el(other).ref) as? constructit.core.LineValue)?.line, "$other is a line")
        val n = (l.b - l.a).normalized().perp()
        return if ((o.origin - l.a).dot(n) > 0) 1 else -1
    }

    private fun angleOf(
        doc: Document,
        name: String,
    ): Double {
        val m = doc.scalars.last()
        return assertNotNull(((Evaluator().eval(m.ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q?.value, "the measured angle of $name")
    }

    private fun edgeName(
        doc: Document,
        solid: Element,
        i: Int,
    ): Any? = ((Evaluator().valueOf(solid.ref) as? SolidValue)?.solid?.feature?.let { Section3.edges(it).first })?.getOrNull(i)?.name

    private fun faceName(
        doc: Document,
        solid: Element,
        i: Int,
    ): Any? = ((Evaluator().valueOf(solid.ref) as? SolidValue)?.solid?.feature?.let { Section3.faces(it).first })?.getOrNull(i)?.name

    /** [text] with the parameter [name] standing at [value]. */
    private fun mapFeaturesAndParams(
        text: String,
        name: String,
        value: String,
    ): String {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
        val ps = (root["parameters"] as kotlinx.serialization.json.JsonArray).map { p -> (p as JsonObject).let { if (it.str("name") == name) it.with("value", "\"$value\"") else it } }
        return JsonObject(root + ("parameters" to kotlinx.serialization.json.JsonArray(ps))).toString()
    }
}
