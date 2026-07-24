package constructit

import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Numeric closeness assertion for geometry (base units: mm, rad). */
fun assertClose(actual: Double, expected: Double, tol: Double = 1e-6, msg: String = "") {
    assertTrue(abs(actual - expected) <= tol, "expected $expected but was $actual. $msg")
}

/**
 * SVG golden support. On first run (file missing) the golden is written and the check passes;
 * commit the file after inspection. Subsequent runs assert byte-equality (canonical serializer).
 * Delete the file to regenerate.
 */
object Golden {
    private val dir = File("src/test/resources/golden")

    fun check(name: String, svg: String) {
        val file = File(dir, "$name.svg")
        if (!file.exists()) {
            dir.mkdirs()
            file.writeText(svg)
            println("[golden] wrote ${file.path} (inspect & commit)")
            return
        }
        assertEquals(file.readText(), svg, "SVG golden mismatch for '$name' (delete ${file.path} to regenerate)")
    }
}
