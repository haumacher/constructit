package constructit

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.SolidValue
import constructit.editor.Document
import constructit.editor.DocumentFormat
import constructit.editor.ElementKind
import constructit.geom.Geom3
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every version-11 file stays readable for ever, as the drawing it was** (OP-18's *Versioning & migration*, OP-32
 * slice 2) — the permanent cross-build load test of the first graph format.
 *
 * The fixtures in `src/jvmTest/resources/v11/` were **written by the build that wrote version 11** (session 89,
 * before slice 2 changed anything): every one of them stores its picks as the gesture's `clicks`, an orbit's as
 * `cells`, an `intersectnear`'s and a `pointoncurve`'s as a `click`. Beside each one is its **drawing** as that build
 * loaded it — every element's name, kind, visibility and value, every solid's volume and bounds, every scalar —
 * captured on the same build (`<name>.drawing`). So the assertion is not *this build agrees with itself* but *this
 * build reads the old file as the old build did*: the migration resolves each click once, against the geometry as
 * it stands at that point of the dependency order — exactly what the old load did — and the drawing is the same.
 *
 * Then the re-save is the current version, carries no click at all, and is a fixed point.
 */
class V11CorpusTest {
    private val dir = File("src/jvmTest/resources/v11")

    private fun fixtures(): List<File> = dir.listFiles { f -> f.name.endsWith(".cit") }.orEmpty().sortedBy { it.name }

    @Test
    fun everyVersion11FixtureLoadsAsTheDrawingItsOwnBuildLoaded() {
        val files = fixtures()
        assertTrue(files.size >= 30, "the v11 corpus is in place (${files.size} files)")
        val failures = ArrayList<String>()
        for (f in files) {
            try {
                val text = f.readText()
                assertEquals(11, saved(text).version, "${f.name} is a version-11 file")
                val doc = DocumentFormat.load(text)
                val print = fingerprint(doc)
                val golden = File(dir, f.name.removeSuffix(".cit") + ".drawing")
                if (!golden.exists()) {
                    golden.writeText(print)
                    println("[v11] wrote ${golden.path}")
                    continue
                }
                assertSameFingerprint(golden.readText(), print, f.name)
                // …and saved, it is the current version and states no click anywhere (OP-32 slice 2)
                val json = DocumentFormat.save(doc)
                val s = saved(json)
                assertEquals(DocumentFormat.VERSION, s.version, "${f.name}: re-saved at the current version")
                for (feature in s.features) {
                    for (key in listOf("clicks", "click", "cells")) {
                        assertTrue(!feature.containsKey(key), "${f.name}: ${feature.str("id")} states no '$key' any more:\n$json")
                    }
                }
                // …which loads as the same drawing again, and is a fixed point from then on
                val back = DocumentFormat.load(json)
                assertSameFingerprint(golden.readText(), fingerprint(back), "${f.name} (re-saved)")
                assertSameFile(DocumentFormat.save(back), json, "${f.name}: save -> load -> save")
            } catch (e: Throwable) {
                failures.add("${f.name}: ${e.message?.take(600)}")
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${files.size} v11 files do not load as they did:\n" + failures.joinToString("\n\n"))
    }

    /**
     * **What the user saw is what comes back, after an edit too** (OP-32 slice 2's whole point, asked of every
     * fixture at once): each version-11 drawing, every free point of it moved in the session, saved as version 12
     * and loaded, is the drawing the session showed — element for element. A build that still read a position on a
     * load would read the gesture's click no longer, and would score its choice from nothing; one whose choice was
     * re-read from the click would have scored it against geometry the edit had moved. Either shows here as a
     * difference between the session and its own reload.
     */
    @Test
    fun everyVersion11FixtureEditedInTheSessionReloadsAsTheSessionShowedIt() {
        val failures = ArrayList<String>()
        var movedAll = 0
        for (f in fixtures()) {
            try {
                val doc = DocumentFormat.load(f.readText())
                val moved =
                    doc.elements.filter { it.kind == ElementKind.POINT }.mapNotNull { el ->
                        val node = geometryNodeOf(el) as? constructit.core.SourceNode ?: return@mapNotNull null
                        val p = (node.value as? constructit.core.PointValue)?.p ?: return@mapNotNull null
                        node.value = constructit.core.PointValue(p + constructit.geom.Vec2(400.0, -300.0))
                        el
                    }
                movedAll += moved.size
                val session = fingerprint(doc)
                val back = DocumentFormat.load(DocumentFormat.save(doc))
                assertSameFingerprint(session, fingerprint(back), "${f.name} with ${moved.size} points moved")
            } catch (e: Throwable) {
                failures.add("${f.name}: ${e.message?.take(600)}")
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${fixtures().size} edited drawings do not reload as edited:\n" + failures.joinToString("\n\n"))
        assertTrue(movedAll >= 50, "the edit is an edit: $movedAll free points moved")
    }

    companion object {
        /**
         * A drawing as text: one line per element by name — kind, visibility and value (a solid by its volume and
         * bounds) — then one per scalar. What two builds are compared by.
         */
        fun fingerprint(doc: Document): String {
            val ev = Evaluator()
            val hash = Regex("@[0-9a-f]+")
            val out = StringBuilder()
            for (el in doc.elements.sortedBy { doc.nameOf(it) }) {
                val r = ev.eval(el.ref.node)
                val v =
                    when {
                        r !is EvalResult.Ok -> "INVALID"
                        r.value is SolidValue && el.kind == ElementKind.SOLID -> {
                            val m = (r.value as SolidValue).solid.mesh
                            val b = Geom3.bounds(m)
                            "solid volume=${Geom3.volume(m)} bounds=${b?.first} ${b?.second}"
                        }
                        else -> hash.replace(r.value.toString(), "@")
                    }
                out.append(doc.nameOf(el)).append(' ').append(el.kind).append(' ').append(el.visible).append(' ').append(v).append('\n')
            }
            for (s in doc.scalars.sortedBy { it.name }) {
                val r = ev.eval(s.ref.node)
                out.append("scalar ").append(s.name).append(' ').append(if (r is EvalResult.Ok) hash.replace(r.value.toString(), "@") else "INVALID").append('\n')
            }
            return out.toString()
        }

        /** Two fingerprints are the same drawing: the same words, and every number to 1e-9 (a volume to the engine's own re-evaluation noise). */
        fun assertSameFingerprint(
            expected: String,
            actual: String,
            what: String,
        ) {
            val e = expected.lines()
            val a = actual.lines()
            assertEquals(e.map { it.substringBefore(' ') }, a.map { it.substringBefore(' ') }, "$what: the same elements and scalars")
            val number = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
            for (i in e.indices) {
                if (e[i] == a[i]) continue
                assertEquals(number.replace(e[i], "#"), number.replace(a[i], "#"), "$what: line ${i + 1}")
                val ne = number.findAll(e[i]).map { it.value.toDouble() }.toList()
                val na = number.findAll(a[i]).map { it.value.toDouble() }.toList()
                val solid = e[i].contains(" solid volume=")
                for (k in ne.indices) {
                    val tol = if (solid) maxOf(1e-6, reevaluationTol(ne[k]) * 10) else 1e-9 * maxOf(1.0, kotlin.math.abs(ne[k]))
                    assertClose(na[k], ne[k], tol, "$what: line ${i + 1} (${e[i].take(60)}), number ${k + 1}")
                }
            }
        }
    }
}
