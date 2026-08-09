package constructit

import constructit.core.Evaluator
import constructit.core.Path3Value
import constructit.core.PlaneValue
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.editor.ElementKind
import constructit.editor.Tools
import constructit.geom.Mesh3
import constructit.geom.Pierce3
import constructit.geom.Plane3
import constructit.geom.Vec2
import constructit.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The probe review of the in-place sweep** — the new reading composed with what already existed, through the
 * real gestures.
 *
 * The delivery's own suite proves the reading against the report's pillar and against synthetic two-crossing
 * drawings. These ask whether the mechanism is *general*: a section drawn in the one plane the run itself
 * defines — a **station** (OP-26, step 4), whose axes are by construction the moving frame's own — must sweep
 * to exactly the body the station's frame promises; and a run that is genuinely three-dimensional — a
 * **helix** — crossing the section's plane four times must have the nearest crossing scored once, named in the
 * status line, written into the step, and ridden with the drawing as a true section of the coil.
 */
class InPlaceSweepProbeTest {
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
    private fun meshOf(el: Element): Mesh3 = Evaluator().solid(el.ref as SolidRef).mesh

    private fun planeOf(
        doc: Document,
        space: String,
    ): Plane3 = (Evaluator().valueOf(assertNotNull(doc.spaceNamed(space)?.plane)) as PlaneValue).plane

    private fun lastSolid(doc: Document): Element =
        assertNotNull(doc.elements.lastOrNull { it.kind == ElementKind.SOLID }, "a solid was built")

    /** How far [p] stands from the boundary of [mesh] — the closest point of the closest triangle. */
    private fun distanceToSurface(
        p: Vec3,
        mesh: Mesh3,
    ): Double =
        mesh.triangles.minOf { t ->
            (closestOnTriangle(p, mesh.vertices[t.a], mesh.vertices[t.b], mesh.vertices[t.c]) - p).length()
        }

    /** The closest point of triangle (a, b, c) to [p] — barycentric, with the six degenerate regions clamped. */
    private fun closestOnTriangle(
        p: Vec3,
        a: Vec3,
        b: Vec3,
        c: Vec3,
    ): Vec3 {
        val ab = b - a
        val ac = c - a
        val ap = p - a
        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return a
        val bp = p - b
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return b
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) return a + ab * (d1 / (d1 - d3))
        val cp = p - c
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return c
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) return a + ac * (d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) return b + (c - b) * ((d4 - d3) / ((d4 - d3) + (d5 - d6)))
        val denom = 1.0 / (va + vb + vc)
        return a + ab * (vb * denom) + ac * (vc * denom)
    }

    /**
     * **A section drawn on a station of the run sweeps to the body the station's own frame promises.**
     *
     * The station is the one plane whose axes *are* the moving frame's at its distance (OP-26, step 4), and its
     * origin stands on the run — so the crossing the in-place reading finds is the station's own origin, the
     * anchor it subtracts is `(0, 0)`, and the seed it states is the frame the transport would have carried
     * there anyway. Everything must therefore agree to the last bit: any sign slip in the crossing's sense, any
     * mirror taken where none is due, any seed rotated against the transport shows up as the drawing standing
     * off the body it swept.
     *
     * The run bends **before** the station (an L, station on the second leg), so agreeing with the station's
     * frame is *not* agreeing with the frame at the run's start — the case a start-seeded reading would get
     * wrong. The rectangle is off-origin and asymmetric about both axes, so a mirror in either direction moves
     * it and is caught by the corners.
     */
    @Test
    fun aSectionDrawnOnAStationOfTheRunSweepsInPlace() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(150.0, -150.0))
        ed.setTool(Tools.CURVE3)
        ed.click(Vec2(0.0, 0.0))
        ed.click(Vec2(150.0, 0.0))
        ed.click(Vec2(150.0, -150.0))
        ed.key("Enter")
        val run = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        // a station 200 mm along — 50 mm down the second leg, past the bend
        ed.setTool(Tools.STATION)
        ed.type("200")
        ed.click(Vec2(150.0, -50.0))
        assertTrue(ed.doc.activeSpace.isStation, "the station opened: ${ed.statusHint}")
        val station = ed.doc.activeSpace.name

        // the section, drawn in the station's plane, off its origin and asymmetric about both axes
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(5.0, -5.0))
        ed.click(Vec2(45.0, 25.0))

        // the sweep, through the real cross-space gesture: the run picked in the plan, the area on the station
        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(75.0, 0.0))
        ed.setActiveSpace(station)
        ed.click(Vec2(25.0, -5.0))

        val solid = lastSolid(ed.doc)
        val mesh = meshOf(solid)
        assertManifold(mesh, "the section swept from its own station")
        // through the editor the choice speaks in the status line
        val note = ed.statusHint
        assertTrue(note.contains("riding where"), "the choice speaks: $note")
        assertTrue(note.contains("pierces $station"), "…and names the plane: $note")

        // the drawn rectangle is a section of the body — every corner on the surface, exactly
        val plane = planeOf(ed.doc, station)
        for (c in listOf(Vec2(5.0, -5.0), Vec2(45.0, -5.0), Vec2(45.0, 25.0), Vec2(5.0, 25.0))) {
            val off = distanceToSurface(plane.toWorld(c), mesh)
            assertTrue(off <= 1e-9, "corner $c is a point of the body on a straight leg: $off mm off")
        }
        // the station's u is the plan's normal, so the rectangle's u extent is the body's height — everywhere,
        // because the transported frame keeps it on a planar run, through the bend included
        val zs = mesh.vertices.map { it.z }
        assertClose(zs.min(), 5.0, 1e-9, "the body stands exactly where the drawing does")
        assertClose(zs.max(), 45.0, 1e-9, "…and exactly as tall")

        // the choice is in the step, and the drawing round-trips byte-equal
        val script = DocumentFormat.save(ed.doc)
        val step = script.lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(step.contains("signs=0"), "the one crossing is written down: $step")
        val back = DocumentFormat.load(script)
        assertEquals(script, DocumentFormat.save(back), "save -> load -> save is byte-equal")
        assertTrue(back.loadNotes.isEmpty(), "nothing about it is ambiguous: ${back.loadNotes}")
        val zsBack = meshOf(lastSolid(back)).vertices.map { it.z }
        assertClose(zsBack.min(), zs.min(), 1e-12, "and it comes back standing where it stood")
        assertClose(zsBack.max(), zs.max(), 1e-12, "…exactly")
    }

    /**
     * **A helix crossing the section's plane four times rides the crossing nearest the drawing** — the
     * in-place reading on a run that lies in no plane at all, which is what stress-tests the seeded transport
     * honestly (OP-26's own reason for building the helix early).
     *
     * Two turns of an 80 mm coil cross the vertical plane `y = 10` four times; the section is drawn beside the
     * third crossing, up on the second turn — so the choice is a genuine scoring among four, the pierce is
     * mid-coil, and the section rides a station whose frame no start-seeded reading reaches. The drawn corners
     * are asserted on the body at the mesh's own scale.
     */
    @Test
    fun aHelixRidesTheCrossingNearestTheDrawing() {
        val ed = Editor()
        ed.setTool(Tools.POINT)
        ed.click(Vec2(0.0, 0.0))
        ed.setTool(Tools.HELIX)
        ed.type("80")
        ed.type("60")
        ed.type("2")
        ed.click(Vec2(0.0, 0.0))
        val coil = assertNotNull(ed.doc.elements.lastOrNull { it.kind == ElementKind.SPACE_CURVE }, ed.statusHint)

        // a vertical plane along y = 10 — the coil pierces it twice per turn
        ed.setTool(Tools.LINE)
        ed.click(Vec2(-100.0, 10.0))
        ed.click(Vec2(100.0, 10.0))
        val line = ed.doc.elements.last { it.kind == ElementKind.LINE }
        assertNotNull(ed.doc.createDatumSpace(line, null, "wall"), "the wall stands on the line")

        val plane = planeOf(ed.doc, "wall")
        val path = (Evaluator().valueOf(coil.ref) as Path3Value).path
        val hits = Pierce3.crossings(path, plane)
        assertEquals(4, hits.size, "two turns cross the wall four times")
        // the third crossing, in the wall's own coordinates — where the section is about to be drawn
        val d = hits[2].at - plane.origin
        val at = Vec2(d.dot(plane.u), d.dot(plane.v))

        // the section: a rectangle beside that crossing, asymmetric about it in both axes — sized against the
        // embedding guard, which is watching the new reading exactly as it should: on a tighter coil a section
        // reaching 21.9 mm was refused by name here ("the run passes within 29.788 mm of itself … thin the
        // section, or open the run out"), OP-9 holding mid-probe
        ed.setTool(Tools.RECTANGLE)
        ed.click(Vec2(at.x - 8.0, at.y - 6.0))
        ed.click(Vec2(at.x + 16.0, at.y + 10.0))

        ed.setActiveSpace(Document.PLAN_SPACE)
        ed.setTool(Tools.SWEEP)
        ed.click(Vec2(0.0, -80.0))
        ed.setActiveSpace("wall")
        // on the bottom edge, midway — the corner points stand 12 mm off, outside the pick magnet's ten pixels
        ed.click(Vec2(at.x + 4.0, at.y - 6.0))

        val solid = lastSolid(ed.doc)
        val mesh = meshOf(solid)
        assertManifold(mesh, "the section swept along the coil")
        val note = ed.statusHint
        assertTrue(note.contains("crossing 3 of 4"), "the scoring among four is named: $note")
        assertTrue(note.contains("nearest the section"), "…and why: $note")

        // the drawing is a section of the coil's body, at the mesh's own scale
        for (c in listOf(
            Vec2(at.x - 8.0, at.y - 6.0),
            Vec2(at.x + 16.0, at.y - 6.0),
            Vec2(at.x + 16.0, at.y + 10.0),
            Vec2(at.x - 8.0, at.y + 10.0),
        )) {
            val off = distanceToSurface(plane.toWorld(c), mesh)
            assertTrue(off <= 0.5, "corner $c is a point of the body: $off mm off")
        }

        // the scored index is in the step, and the drawing round-trips byte-equal
        val script = DocumentFormat.save(ed.doc)
        val step = script.lineSequence().last { it.startsWith("tool sweep") }
        assertTrue(step.contains("signs=2"), "the crossing it chose is written down: $step")
        assertEquals(script, DocumentFormat.save(DocumentFormat.load(script)), "save -> load -> save is byte-equal")
    }
}
