package constructit

import constructit.core.Evaluator
import constructit.dsl.SolidRef
import constructit.dsl.solid
import constructit.editor.DocumentFormat
import constructit.editor.Editor
import constructit.editor.ElementKind
import constructit.editor.Scene3
import constructit.geom.Geom3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The reported drawing of GitHub issue #1, verbatim: an extrude on a face built *into* the material.**
 *
 * The user drew a profile on a side face of an extruded plate and extruded it, expecting a boss standing on the
 * face; what appeared was a "glitch" on the face and nothing else. The solid was there and the right size — it
 * was **buried inside the part**, sharing its base plane with the face, so all that was visible was the two
 * surfaces z-fighting. The cause: a face space's plane is the face's plane *flipped* (its normal points into
 * the material — the frame that fixes what the drawing's `v` means, OP-17), and a plain *Extrude* inherited
 * that direction, so every boss was a wart inside the material and only *Cut* was reachable.
 *
 * **Which way an operation builds belongs to the operation**, not to the space: *Cut* sweeps inward, *Extrude*
 * sweeps outward. The drawn coordinates are untouched by the fix — they could not be: reversing a right-handed
 * frame's normal mirrors `v` ([constructit.geom.Plane3.flipped]), which would move every face-space drawing
 * ever saved. So the space keeps its frame and the *extrude* starts its sweep `depth` behind the face instead.
 *
 * The file needs no version bump: the depth still means what it meant (how far the feature reaches), and the
 * direction was never stated in a file at all. What changes for an existing drawing is that the buried wart
 * comes back where the user drew it — this file's own regression, below.
 */
class FaceExtrudeOutwardTest {
    @Suppress("UNCHECKED_CAST")
    @Test
    fun theReportedWartStandsOutOfThePlateInsteadOfInsideIt() {
        val doc = DocumentFormat.load(ISSUE1)
        assertTrue(doc.loadNotes.isEmpty(), "nothing about this file is ambiguous: ${doc.loadNotes}")
        val plate = doc.elements.first { it.kind == ElementKind.SOLID }
        val wart = doc.elements.last { it.kind == ElementKind.SOLID }
        assertEquals("face1", wart.space, "the wart was drawn on the plate's face")

        val pb = assertNotNull(Geom3.bounds(Evaluator().solid(plate.ref as SolidRef).mesh))
        assertClose(pb.second.y, 40.0, tol = 1e-9, msg = "the plate's material ends at the face, y = 40")

        val mesh = Evaluator().solid(wart.ref as SolidRef).mesh
        val wb = assertNotNull(Geom3.bounds(mesh))
        assertClose(wb.first.y, 40.0, tol = 1e-9, msg = "the boss starts on the face...")
        assertClose(wb.second.y, 60.0, tol = 1e-9, msg = "...and stands its 20 mm depth *outside* the plate")
        assertManifold(mesh, "the boss")
        assertClose(Geom3.volume(mesh), 2848.242403545959, tol = 1e-6, msg = "the same solid as before, moved out")

        // both solids are drawn: neither is the other's material (OP-14's scaffolding rule)
        val drawn = Scene3.extract(doc).solids.map { it.elementId }
        assertTrue(drawn.contains(plate.id) && drawn.contains(wart.id), "the plate and its boss: $drawn")

        // …and the file is unchanged by the fix: the direction was never in it
        assertEquals(atThisVersion(ISSUE1), DocumentFormat.save(doc), "the reported file re-saves byte for byte")
    }

    /**
     * The other half of the same file, and the other reported wave (#25): its ortho path was drawn in the face
     * space, so the path's **corners** belong there too — they used to be stamped with the plan, where their
     * coordinates mean nothing.
     */
    @Test
    fun theFaceSpacesOwnPathIsWhollyInTheFaceSpace() {
        val doc = DocumentFormat.load(ISSUE1)
        val face = doc.elements.filter { it.space == "face1" }
        assertTrue(face.any { it.kind == ElementKind.ON_CURVE }, "the path's corner is in the face space: ${face.map { it.kind }}")
        assertTrue(face.any { it.kind == ElementKind.SEGMENT }, "with the geometry drawn beside it")
        // and the plan holds exactly what was drawn there: the plate, its outline and its own path — every
        // element the script declares after `sketchspace` belongs to the face
        val plan = doc.elements.filter { it.space == constructit.editor.Document.PLAN_SPACE }
        assertTrue(plan.isNotEmpty(), "the plan drawing is still the plan's")
        val fromFaceOn = doc.elements.dropWhile { doc.nameOf(it) != "e29" }
        assertTrue(fromFaceOn.isNotEmpty() && fromFaceOn.all { it.space == "face1" }, "got: ${fromFaceOn.map { doc.nameOf(it) + ":" + it.space }}")
    }

    /** And the editor shows it: switching to the face view draws the boss's own footprint there. */
    @Test
    fun theViewTheFileEndsInIsTheFaceItWasDrawnOn() {
        val ed = Editor()
        ed.replaceDocument(DocumentFormat.load(ISSUE1))
        assertEquals("face1", ed.activeSpace.name, "a load leaves you in the space the script ends in (OP-17)")
        assertEquals("", ed.statusHint, "and a file with nothing to report says nothing")
    }

    private companion object {
        val ISSUE1 =
            """
constructit 2
orthostart -72.5,-14.25 -> e1
orthovertex -72.5,40 -> e2,e3
orthovertex 0,40 -> e4,e5
orthovertex 0,10 -> e6,e7
orthovertex -35.75,10 -> e8,e9
orthovertex -35.75,-14.25 -> e10,e11
orthoclose -> e12
param "r" = 10mm
tool fillet els=e12,e11 clicks=-45.75,-14.75;-36.25,-3.5 scalar="r" signs=1;-1 -> e13
tool fillet els=e9,e7 clicks=-15,10;-0.75,21.5 scalar="r" signs=1;-1 -> e14
tool fillet els=e5,e7 clicks=-6.5,39.5;-0.5,32.5 scalar="r" signs=-1;1 -> e15
tool fillet els=e11,e9 clicks=-35.5,2.5;-28.25,10.5 scalar="r" signs=1;-1 -> e16
tool outline els=e3,e5,e15,e7,e14,e9,e16,e11,e13,e12 clicks=-73,14.25;-37.25,39.5;-2.9289321881345227,37.071067811865476;0.0000000000000017763568394002505,25;-2.9289321881345227,12.928932188134524;-17.875,10;-32.821067811865476,7.071067811865477;-35.75,-2.1249999999999996;-38.678932188134524,-11.321067811865476;-59.125,-14.25 -> e17,e18,e19,e20,e21,e22,e23,e24,e25,e26,e27
param "h" = 100mm
tool extrude els=e27 clicks=-51.5,40.5 scalar="h" -> e28
sketchspace "face1" el=e28 piece=8
orthostart -18.39080459770115,34.23645320197045 -> e29
point -6.4367816091954,44.87684729064039 -> e30
point 1.444991789819376,53.80952380952381 -> e31
tool segment pts=e30,e31 clicks=-6.83087027914614,46.71592775041051;2.7586206896551744,52.364532019704434 -> e32
point 11.82266009852217,45.53366174055829 -> e33
point 1.8390804597701162,37.25779967159277 -> e34
tool segment pts=e33,e34 clicks=12.610837438423644,43.56321839080459;-0.26272577996715896,38.44006568144499 -> e35
tool segment pts=e34,e30 clicks=-0.26272577996715896,38.44006568144499;-6.699507389162562,46.847290640394085 -> e36
tool segment pts=e31,e33 clicks=2.889983579638752,52.23316912972085;12.479474548440066,43.95730706075533 -> e37
param "f" = 5mm
tool fillet els=e32,e37 clicks=-1.182266009852217,50.52545155993432;5.385878489326764,50.52545155993432 scalar="f" signs=-1;1 -> e38
tool fillet els=e36,e35 clicks=-2.233169129720853,40.935960591133004;6.568144499178985,41.198686371100166 scalar="f" signs=1;-1 -> e39
tool outline els=e36,e39,e35,e37,e38,e32 clicks=-3.940886699507388,42.77504105090312;1.7077175697865385,39.228243021346465;8.512576162711113,42.78977637271491;8.493446652179472,48.18860410916069;1.610796607507531,51.915333630719516;-4.069587329771487,47.559667473987496 -> e40,e41,e42,e43,e44,e45,e46
param "d" = 20mm
tool extrude els=e46 clicks=8.144499178981938,48.423645320197046 scalar="d" -> e47
""".trimStart()
    }
}
