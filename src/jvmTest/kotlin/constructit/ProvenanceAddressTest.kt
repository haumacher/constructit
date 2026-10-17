package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.dsl.valueOf
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.Element
import constructit.geom.Feature3
import constructit.geom.Geom3
import constructit.geom.Provenance
import constructit.geom.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Addresses that survive a change of topology** (OP-32, slice 3a) — a rounding's edge is named by what made it
 * (`e11|top`: the face swept from leg e11 against the top cap), so inserting a vertex upstream, which renumbers
 * every edge after it, leaves every rounding on the edge it was put on; an edge whose faces are gone makes the
 * rounding invalid naming it, and undo heals it.
 */
class ProvenanceAddressTest {
    companion object {
        /** GitHub #36's first script, verbatim. */
        val ISSUE36 =
            """
            constructit 6
            orthostart -26.875,-32.375 -> e1
            orthovertex -26.875,15.375 -> e2,e3
            orthovertex 61.875,15.375 -> e4,e5
            orthovertex 61.875,0.375 -> e6,e7
            orthovertex -5.521648428788623,0.375 -> e8,e9
            orthovertex -5.521648428788623,-32.375 -> e10,e11
            orthoclose -> e12
            param "h" = 20mm
            tool extrude els=e11 clicks=-48.125,37.875 scalar="h" -> e13
            hide els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
            show els=e1,e2,e3,e4,e5,e6,e7,e8,e9,e10,e11,e12,e13
            param "r" = 4mm
            tool filletedge els=e13 clicks=-31.252365457721638,11.31587462139538 scalar="r" signs=13;-1;1;0;1 -> e14,e15
            tool filletedge els=e14 clicks=-15.659687663642714,14.554138077719443 scalar="r" signs=2;-1;1;0;-1 -> e16
            """.trimIndent() + "\n"

        fun named(
            doc: Document,
            name: String,
        ): Element = doc.elements.first { doc.nameOf(it) == name }

        fun solid(
            doc: Document,
            name: String,
        ) = (Evaluator().eval(named(doc, name).ref.node) as? EvalResult.Ok)?.value as? SolidValue

        fun volume(
            doc: Document,
            name: String,
        ): Double = Geom3.volume(solid(doc, name)!!.solid.mesh)

        /** The keys of the edges a dressed body rounds, as its pass resolved them. */
        fun roundedKeys(
            doc: Document,
            name: String,
        ): Set<String> {
            val f = solid(doc, name)!!.solid.feature as Feature3.Blend
            val keys = Provenance.edgeKeys(f.base)!!
            // a tombstone keeps its position in the list and rounds nothing (OP-30)
            return f.targets.withIndex().filter { (i, _) -> i !in f.absent.keys }.map { (_, t) -> keys[t] }.toSet()
        }
    }

    @Test
    fun aVertexInsertedUpstreamLeavesEveryRoundingOnItsEdge() {
        val ed = Editor(DocumentFormat.load(DocumentFormat.save(DocumentFormat.load(ISSUE36))))
        val doc0 = ed.doc
        assertEquals(setOf("e11|top", "e11|e9"), roundedKeys(doc0, "e14"))
        val removed0 = volume(doc0, "e13") - volume(doc0, "e14")
        val v13 = volume(doc0, "e13")
        val before = DocumentFormat.save(doc0)
        // insert a vertex into the top leg e5 and pull the right half up: two vertices and a jog appear upstream,
        // and every edge index after the broken leg moves
        val path = doc0.orthoPaths.single()
        val leg = doc0.legOf(named(doc0, "e5"))!!.second
        assertTrue(doc0.breakOrthoLeg(path, leg, Vec2(20.0, 15.375), Vec2(20.0, 20.0)), "the top leg breaks")
        ed.checkpoint()
        val doc = ed.doc
        val added = (61.875 - 20.0) * (20.0 - 15.375) * 20.0
        assertClose(volume(doc, "e13"), v13 + added, 1e-6, "the extrusion grew by the step")
        assertEquals(setOf("e11|top", "e11|e9"), roundedKeys(doc, "e14"), "both roundings stay on their edges")
        assertClose(volume(doc, "e13") - volume(doc, "e14"), removed0, 1e-6 * v13, "the roundings take exactly what they took")
        assertManifold(solid(doc, "e14")!!.solid.mesh, "e14 after the insert")
        // the file says the same, and loads as the session shows
        val saved = DocumentFormat.save(doc)
        assertTrue(saved.contains("\"edge\":\"e11|top\"") && saved.contains("\"edge\":\"e11|e9\""), saved)
        val back = DocumentFormat.load(saved)
        V11CorpusTest.assertSameFingerprint(V11CorpusTest.fingerprint(doc), V11CorpusTest.fingerprint(back), "the session and its reload")
        assertSameFile(DocumentFormat.save(back), saved, "save -> load -> save")
        // one undo is the file before the insert, exactly
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo restores the previous file")
    }

    /**
     * **A vertex taken out again, the path re-closed** — the other direction of the same change of topology: the top
     * leg broken (two vertices and a jog of no length inserted), then the jog joined away, which takes the two
     * vertices out and leaves one closed leg where there were three. Every index after it moves back; neither
     * rounding moves at all.
     */
    @Test
    fun verticesTakenOutAgainLeaveEveryRoundingOnItsEdge() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        ed.checkpoint()
        val removed0 = volume(ed.doc, "e13") - volume(ed.doc, "e14")
        val path = ed.doc.orthoPaths.single()
        val leg = ed.doc.legOf(named(ed.doc, "e5"))!!.second
        assertTrue(ed.doc.breakOrthoLeg(path, leg, Vec2(20.0, 15.375), Vec2(20.0, 15.375)), "the top leg breaks, its jog of no length")
        ed.checkpoint()
        assertEquals(8, path.legCount, "two vertices more")
        assertEquals(setOf("e11|top", "e11|e9"), roundedKeys(ed.doc, "e14"))
        val before = DocumentFormat.save(ed.doc)
        assertTrue(ed.doc.canJoinLeg(path, leg + 1, 1e-6), "the jog can be joined away")
        assertNotNull(ed.doc.joinCollapsedLeg(path, leg + 1), "joined: the two vertices go and the path closes over one leg")
        ed.checkpoint()
        assertEquals(6, path.legCount, "the L has its six legs again")
        assertTrue(path.closed, "and is closed")
        assertEquals(setOf("e11|top", "e11|e9"), roundedKeys(ed.doc, "e14"), "both roundings stay on their edges")
        assertClose(volume(ed.doc, "e13") - volume(ed.doc, "e14"), removed0, 1e-6 * volume(ed.doc, "e13"), "taking what they took")
        assertManifold(solid(ed.doc, "e14")!!.solid.mesh, "the re-closed L")
        val text = DocumentFormat.save(ed.doc)
        assertSameFile(DocumentFormat.save(DocumentFormat.load(text)), text, "save -> load -> save")
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc), "one undo is the file before the join")
    }

    /** A sketch space on a side face of the L is named by the leg that face is swept from, and stays on it. */
    @Test
    fun aFaceSpaceStaysOnItsFaceWhenAVertexIsInsertedUpstream() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        val doc0 = ed.doc
        val extrusion = named(doc0, "e13")
        val face = Provenance.faceKeys(solid(doc0, "e13")!!.solid.feature)!!.indexOf("e11")
        val space = assertNotNull(doc0.createFaceSpace(extrusion, face), "a space on the face swept from leg e11")
        doc0.switchSpace(Document.PLAN_SPACE, record = true)
        ed.checkpoint()
        assertTrue(saved(doc0).kind("sketchspace").single().str("piece") == "e11", "the file states the face by its leg")
        val planeX = { d: Document -> (Evaluator().valueOf(d.spaceNamed(space.name)!!.plane!!) as constructit.core.PlaneValue).plane.origin.x }
        assertClose(planeX(doc0), -5.521648428788623, 1e-9, "the face of leg e11 stands at its x")
        val path = doc0.orthoPaths.single()
        assertTrue(doc0.breakOrthoLeg(path, doc0.legOf(named(doc0, "e5"))!!.second, Vec2(20.0, 15.375), Vec2(20.0, 20.0)))
        ed.checkpoint()
        assertClose(planeX(ed.doc), -5.521648428788623, 1e-9, "…and still does with two vertices more upstream")
        val back = DocumentFormat.load(DocumentFormat.save(ed.doc))
        assertClose(planeX(back), -5.521648428788623, 1e-9, "…and so does its reload")
    }

    @Test
    fun anEdgeWhoseFaceIsGoneMakesTheRoundingInvalidNamingItAndUndoHealsIt() {
        val ed = Editor(DocumentFormat.load(ISSUE36))
        val doc0 = ed.doc
        val before = DocumentFormat.save(doc0)
        // break the leg e9 the second rounding runs along: its face is replaced by two new ones and a jog
        val path = doc0.orthoPaths.single()
        val leg = doc0.legOf(named(doc0, "e9"))!!.second
        assertTrue(doc0.breakOrthoLeg(path, leg, Vec2(30.0, 0.375), Vec2(30.0, -5.0)))
        ed.checkpoint()
        val r = Evaluator().eval(named(ed.doc, "e14").ref.node)
        assertTrue(r is EvalResult.Invalid, "the dressed body cannot be built: $r")
        val why = r.why.render()
        assertTrue(why.contains("e11|e9"), "the reason names the edge that is gone: $why")
        // …saved and loaded it is the same invalid feature, saying the same
        val back = DocumentFormat.load(DocumentFormat.save(ed.doc))
        val r2 = Evaluator().eval(named(back, "e14").ref.node)
        assertTrue(r2 is EvalResult.Invalid && r2.why.render().contains("e11|e9"), "reloaded: $r2")
        assertSameFile(DocumentFormat.save(back), DocumentFormat.save(ed.doc), "an invalid rounding round-trips")
        // …and undo heals it
        assertTrue(ed.undo())
        assertEquals(before, DocumentFormat.save(ed.doc))
        assertTrue(solid(ed.doc, "e14") != null, "healed")
    }
}
