package constructit

import constructit.editor.DocumentFormat
import constructit.geom.Frames3
import constructit.geom.Geom3
import constructit.geom.Mesh3
import constructit.geom.Path3
import constructit.geom.ProfileElement
import constructit.geom.Segment
import constructit.geom.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The straight pieces of a plan drawing (`Document.planOf`), which emits [ProfileElement]s since walls
 * gained curved carriers (the OP-21 extension). On a rectilinear wall this is the whole plan.
 */
fun List<ProfileElement>.segments(): List<Segment> = filterIsInstance<ProfileElement.Seg>().map { it.segment }

/** Numeric closeness assertion for geometry (base units: mm, rad). */
fun assertClose(
    actual: Double,
    expected: Double,
    tol: Double = 1e-6,
    msg: String = "",
) {
    assertTrue(abs(actual - expected) <= tol, "expected $expected but was $actual. $msg")
}

/**
 * Watertightness, the hard requirement for a solid (OP-2): the mesh must be a **closed, oriented
 * 2-manifold**, so it can be printed and its volume means something.
 *
 * Three checks, all of them structural rather than approximate:
 * - no degenerate triangle (repeated corner, or zero area);
 * - every directed edge occurs exactly once, and its reverse exactly once — which is closedness
 *   ("every edge has two faces") and consistent orientation ("they disagree on its direction") in one
 *   statement;
 * - positive signed volume, i.e. the consistent orientation is the *outward* one.
 *
 * Run on every solid in every test: a mesh is a sink (OP-9), so a defect here is invisible until
 * something downstream — a slicer — refuses the part.
 */
fun assertManifold(
    mesh: Mesh3,
    what: String = "solid",
) {
    assertTrue(mesh.triangles.isNotEmpty(), "$what has no triangles")
    for ((i, t) in mesh.triangles.withIndex()) {
        assertTrue(t.a != t.b && t.b != t.c && t.a != t.c, "$what triangle $i repeats a corner: $t")
        val a = mesh.vertices[t.a]
        val b = mesh.vertices[t.b]
        val c = mesh.vertices[t.c]
        val area = (b - a).cross(c - a).length() / 2.0
        assertTrue(area > 1e-12, "$what triangle $i is degenerate (area $area mm^2)")
    }
    val counts = HashMap<Pair<Int, Int>, Int>()
    for (t in mesh.triangles) {
        for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
            counts[e] = (counts[e] ?: 0) + 1
        }
    }
    // Iterate the triangles, not the map: the checks are order-independent, but the *message* should be.
    for ((i, t) in mesh.triangles.withIndex()) {
        for (e in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
            val fwd = counts[e] ?: 0
            val back = counts[e.second to e.first] ?: 0
            assertEquals(1, fwd, "$what edge ${e.first}->${e.second} (triangle $i) is used $fwd times, expected 1")
            assertEquals(
                1,
                back,
                "$what edge ${e.first}->${e.second} (triangle $i) has $back opposite uses, expected 1 (open or inconsistently wound)",
            )
        }
    }
    val vol = Geom3.volume(mesh)
    assertTrue(vol > 0.0, "$what encloses no positive volume ($vol mm^3) — is it wound inside out?")
}

/**
 * Two construction scripts as **the same construction**: identical token for token, with the numbers
 * compared numerically instead of textually.
 *
 * Deliberately not a byte comparison, and only for one purpose — comparing the *same* gestures made through
 * two different projections (edit-in-3D slice 1). Both routes resolve a click to the same plane point by
 * different arithmetic — the canvas by a similarity whose scale is a power of two, the 3D view by a
 * perspective divide — so they agree to about 1e-14 mm and not to the last bit of a double. [tol] defaults to
 * 1e-9 mm: a million times below the three decimals the panel reads numbers at, and far below
 * `GeomMath.TESS_TOL_MM`, the tolerance the geometry itself is built with. Everything else is compared
 * exactly, which is what "the same document" means — the same steps in the same order, the same element ids,
 * the same references, the same names.
 */
fun assertSameConstruction(
    expected: String,
    actual: String,
    tol: Double = 1e-9,
) {
    val number = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
    assertEquals(
        number.replace(expected, "#"),
        number.replace(actual, "#"),
        "the two flows must record the same steps, ids and names",
    )
    val want = number.findAll(expected).map { it.value.toDouble() }.toList()
    val got = number.findAll(actual).map { it.value.toDouble() }.toList()
    assertEquals(want.size, got.size, "the same numbers in the same places")
    for (i in want.indices) assertClose(got[i], want[i], tol, "number $i of the script")
}

/**
 * An **older file** as this build writes it back: byte-identical but for the header line, which a save brings
 * up to [DocumentFormat.VERSION] (OP-18, *Versioning & migration*).
 *
 * Every fixture written by an earlier build is kept verbatim — that is what makes it a load test at all, and an
 * in-build round trip proves nothing across builds. So a re-save is asserted against *this*, not against a
 * fixture edited to say the current version: what is being pinned is that the only thing a version bump changes
 * about an old drawing is the line that says which version it is.
 */
fun atThisVersion(text: String): String =
    text.replaceFirst(Regex("^constructit \\d+"), "constructit ${DocumentFormat.VERSION}")

/**
 * SVG golden support. On first run (file missing) the golden is written and the check passes;
 * commit the file after inspection. Subsequent runs assert byte-equality (canonical serializer).
 * Delete the file to regenerate.
 */
object Golden {
    private val dir = File("src/jvmTest/resources/golden")

    fun check(
        name: String,
        svg: String,
    ) {
        val file = File(dir, "$name.svg")
        if (!file.exists()) {
            dir.mkdirs()
            file.writeText(svg)
            println("[golden] wrote ${file.path} (inspect & commit)")
            return
        }
        assertEquals(file.readText(), svg, "SVG golden mismatch for '$name' (delete ${file.path} to regenerate)")
    }

    /**
     * The same discipline for a **binary** golden — the exported GLB.
     *
     * Byte-equality is the only assertion that can notice a writer change nobody meant: a reordered JSON key,
     * a normal computed a hair differently, a padding byte gone missing. It is only possible because the
     * writers are deterministic by design (fixed key order, one canonical number format, no clock, no hash
     * iteration), so a mismatch here is always a real change rather than noise.
     */
    fun checkBytes(
        name: String,
        bytes: ByteArray,
    ) {
        val file = File(dir, name)
        if (!file.exists()) {
            dir.mkdirs()
            file.writeBytes(bytes)
            println("[golden] wrote ${file.path} (inspect & commit)")
            return
        }
        val want = file.readBytes()
        assertEquals(want.size, bytes.size, "golden '$name' has ${want.size} bytes, output has ${bytes.size}")
        val at = want.indices.firstOrNull { want[it] != bytes[it] }
        assertTrue(
            at == null,
            "binary golden mismatch for '$name' at byte $at (${want.getOrNull(at ?: 0)} vs ${bytes.getOrNull(at ?: 0)}) " +
                "— delete ${file.path} to regenerate",
        )
    }
}

/**
 * **The frame introduces no rotation about the tangent, station by station** — the defining property of
 * parallel transport (OP-26), asserted directly: the reference direction turns by *exactly* as much as the
 * tangent does between two stations and never more.
 *
 * That subsumes a flip test and is stronger than it. A Frenet frame at an inflection turns its normal by π
 * while the tangent barely moves, so it fails this at the one station that matters; and where the tangent
 * itself turns less than a right angle (which is everywhere on a sampled smooth curve) the consecutive
 * references are additionally asserted to keep a **positive** dot product, so nothing about the section is
 * ever turned inside out.
 *
 * It lives here rather than in one suite because it is the claim *every* curve in space has to answer, and
 * step 3's helix is the first path lying in no plane — which is the case that tests it honestly.
 */
fun assertTransportNeverFlips(
    path: Path3,
    up: Vec3,
    reach: Double,
) {
    val (frame, why) = Frames3.along(path, up, reach = reach)
    val f = kotlin.test.assertNotNull(frame, why)
    assertTrue(f.stations.size >= 4, "the path is sampled into stations to look at: ${f.stations.size}")
    for (i in 1 until f.stations.size) {
        val a = f.stations[i - 1]
        val b = f.stations[i]
        val turnedTangent = angleBetween3(a.tangent, b.tangent)
        val turnedRef = angleBetween3(a.ref, b.ref)
        assertTrue(
            turnedRef <= turnedTangent + 1e-9,
            "the frame turned $turnedRef rad between stations ${i - 1} and $i while the tangent turned " +
                "$turnedTangent — that is a rotation about the tangent, which transport must not introduce",
        )
        if (turnedTangent < kotlin.math.PI / 2.0 - 1e-9) {
            val d = a.ref.dot(b.ref)
            assertTrue(d > 0.0, "the frame flipped between stations ${i - 1} and $i (dot $d) — that is the Frenet defect")
        }
        assertClose(b.ref.dot(b.tangent), 0.0, 1e-9, "and it stays perpendicular to the tangent")
    }
}

/** The unsigned angle between two directions, by atan2 of cross and dot — stable at both ends. */
fun angleBetween3(
    a: Vec3,
    b: Vec3,
): Double = kotlin.math.atan2(a.cross(b).length(), a.dot(b))
