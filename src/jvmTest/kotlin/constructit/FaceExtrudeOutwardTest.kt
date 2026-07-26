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
        assertEquals(ISSUE1, DocumentFormat.save(doc), "the reported file re-saves byte for byte")
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
orthostart 12.859195402298852,65.76354679802955 -> e29
point 24.8132183908046,55.12315270935961 -> e30
point 32.694991789819376,46.19047619047619 -> e31
tool segment pts=e30,e31 clicks=24.41912972085386,53.28407224958949;34.008620689655174,47.635467980295566 -> e32
point 43.07266009852217,54.46633825944171 -> e33
point 33.089080459770116,62.74220032840723 -> e34
tool segment pts=e33,e34 clicks=43.860837438423644,56.43678160919541;30.98727422003284,61.55993431855501 -> e35
tool segment pts=e34,e30 clicks=30.98727422003284,61.55993431855501;24.550492610837438,53.152709359605915 -> e36
tool segment pts=e31,e33 clicks=34.13998357963875,47.76683087027915;43.729474548440066,56.04269293924467 -> e37
param "f" = 5mm
tool fillet els=e32,e37 clicks=30.067733990147783,49.47454844006568;36.635878489326764,49.47454844006568 scalar="f" signs=-1;1 -> e38
tool fillet els=e36,e35 clicks=29.016830870279147,59.064039408866996;37.818144499178985,58.801313628899834 scalar="f" signs=1;-1 -> e39
tool outline els=e36,e39,e35,e37,e38,e32 clicks=27.309113300492612,57.22495894909688;32.95771756978654,60.771756978653535;39.76257616271111,57.21022362728509;39.74344665217947,51.81139589083931;32.86079660750753,48.084666369280484;27.180412670228513,52.440332526012504 -> e40,e41,e42,e43,e44,e45,e46
param "d" = 20mm
tool extrude els=e46 clicks=39.39449917898194,51.576354679802954 scalar="d" -> e47
""".trimStart()
    }
}
