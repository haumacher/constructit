package constructit

import constructit.editor.DocumentFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A saved drawing read back as the **graph** it states (OP-32) — what a test asserts *the file says*, by field
 * rather than by the spelling of a line. Parsed by the same reference implementation the reader uses.
 */
class Saved(val text: String) {
    val root: JsonObject = Json.parseToJsonElement(text).jsonObject

    val version: Int get() = root.getValue("version").jsonPrimitive.content.toInt()

    val features: List<JsonObject> get() = (root["features"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    val parameters: List<JsonObject> get() = (root["parameters"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    /** The elements the file states hidden (`"view": {"hidden": […]}`) — what is, not how it came to be (OP-32). */
    val hidden: List<String> get() = (root["view"] as? JsonObject)?.ids("hidden") ?: emptyList()

    val names: Map<String, String> get() = (root["names"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    val materials: Map<String, JsonObject> get() = (root["materials"] as? JsonObject)?.mapValues { it.value.jsonObject } ?: emptyMap()

    /** Every feature of [kind]. */
    fun kind(kind: String): List<JsonObject> = features.filter { it.str("kind") == kind }

    /** Every application of the tool [id] — a `tool` feature, or an `orbit` riding it. */
    fun tools(id: String): List<JsonObject> = features.filter { it.str("kind") == "tool" && it.str("tool") == id }

    fun hasTool(id: String): Boolean = tools(id).isNotEmpty()

    /** The one application of [id]; fails when there is none or more than one. */
    fun tool(id: String): JsonObject = tools(id).single()

    fun param(name: String): JsonObject? = parameters.firstOrNull { it.str("name") == name }

    /** The feature that creates element [e] (`"e7"`), or — for a path — the path. */
    fun creatorOf(e: String): JsonObject? =
        features.firstOrNull { f ->
            f.ids("creates").contains(e) ||
                (f["vertices"] as? JsonArray)?.any { v -> v.jsonObject.ids("creates").contains(e) } == true ||
                (f["close"] as? JsonObject)?.ids("creates")?.contains(e) == true
        }

    override fun toString(): String = text
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

/** A list of identities or texts: `"creates": ["e1","e2"]`. */
fun JsonObject.ids(key: String): List<String> = (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: emptyList()

/** A list of integers: `"signs": [1,-1]`. */
fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content.toInt() } ?: emptyList()

/**
 * A pick's **address and the choices it was scored with**, as its build reads them: the `edge` or `face` it names on
 * its solid first, then `signs` (OP-32 slice 2 — the file states the address under its own name, the build has
 * always read it as the first of its choices).
 */
fun JsonObject.addressed(): List<Int> = listOfNotNull(int("edge") ?: int("face")) + ints("signs")

/**
 * The same, **as the build in [doc] read it** (OP-32 slice 3): since version 13 the file states the address by
 * name (`"edge": "e11|top"`), and the index it resolved to on the body is the build's — read back from the
 * document the file was saved from, so a test of *which edge* still speaks the body's own numbers.
 */
fun JsonObject.addressed(doc: constructit.editor.Document): List<Int> {
    val fid = str("id")!!.removePrefix("f").toInt()
    return doc.storedSigns(doc.journal.first { it.fid == fid })
}

/** The pick's address **by name** — the key the file states (`"e11|top"`), or null. */
fun JsonObject.addressKey(): String? = (this["edge"] as? JsonPrimitive ?: this["face"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A list of numbers or quantities, each as its plain number (`{"mm": 2}` -> 2.0). */
fun JsonObject.nums(key: String): List<Double> = (this[key] as? JsonArray)?.map { numberOf(it) } ?: emptyList()

/** A number or a quantity's number. */
fun numberOf(v: JsonElement): Double =
    when (v) {
        is JsonObject -> v.values.single().jsonPrimitive.content.toDouble()
        else -> v.jsonPrimitive.content.toDouble()
    }

/** The position a field states: `[x, y]`. */
fun JsonObject.pos(key: String): Pair<Double, Double>? = (this[key] as? JsonArray)?.let { it[0].jsonPrimitive.content.toDouble() to it[1].jsonPrimitive.content.toDouble() }

fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

/** [text] parsed as a saved drawing. */
fun saved(text: String): Saved = Saved(text)

/** The document saved and parsed. */
fun saved(doc: constructit.editor.Document): Saved = Saved(DocumentFormat.save(doc))

/**
 * **The same drawing**, element by element (OP-32): the same element names, each of the same kind and
 * visibility and with the same value — every number of a value to [tol], a solid by its volume and its bounds.
 * What the corpus test and every import test mean by "the journal load and the graph load are one drawing".
 */
fun assertSameDrawing(
    expected: constructit.editor.Document,
    actual: constructit.editor.Document,
    tol: Double = 1e-9,
    what: String = "",
) {
    val e = expected.elements.associateBy { expected.nameOf(it) }
    val a = actual.elements.associateBy { actual.nameOf(it) }
    kotlin.test.assertEquals(e.keys.sorted(), a.keys.sorted(), "$what: the same elements under the same names")
    val ev1 = constructit.core.Evaluator()
    val ev2 = constructit.core.Evaluator()
    val number = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
    for ((name, x) in e) {
        val y = a.getValue(name)
        kotlin.test.assertEquals(x.kind, y.kind, "$what: $name is the same kind")
        kotlin.test.assertEquals(x.visible, y.visible, "$what: $name is as visible")
        val vx = ev1.eval(x.ref.node)
        val vy = ev2.eval(y.ref.node)
        if (vx !is constructit.core.EvalResult.Ok || vy !is constructit.core.EvalResult.Ok) {
            kotlin.test.assertEquals(vx is constructit.core.EvalResult.Ok, vy is constructit.core.EvalResult.Ok, "$what: $name is as valid ($vx / $vy)")
            continue
        }
        val sx = vx.value
        val sy = vy.value
        if (sx is constructit.core.SolidValue && sy is constructit.core.SolidValue) {
            val mx = sx.solid.mesh
            val my = sy.solid.mesh
            val volX = constructit.geom.Geom3.volume(mx)
            assertClose(constructit.geom.Geom3.volume(my), volX, tol * maxOf(1.0, kotlin.math.abs(volX)), "$what: $name has the same volume")
            val bx = constructit.geom.Geom3.bounds(mx)
            val by = constructit.geom.Geom3.bounds(my)
            if (bx != null && by != null) {
                assertClose(by.first.x, bx.first.x, 1e-6, "$what: $name bounds")
                assertClose(by.second.z, bx.second.z, 1e-6, "$what: $name bounds")
            }
            continue
        }
        // identity hashes (`Expr$Apply@1a2b3c`) are no part of a value — two loads build two expression trees
        val hash = Regex("@[0-9a-f]+")
        val tx = hash.replace(sx.toString(), "@")
        val ty = hash.replace(sy.toString(), "@")
        if (tx == ty) continue
        kotlin.test.assertEquals(number.replace(tx, "#"), number.replace(ty, "#"), "$what: $name has the same value")
        val nx = number.findAll(tx).map { it.value.toDouble() }.toList()
        val ny = number.findAll(ty).map { it.value.toDouble() }.toList()
        for (i in nx.indices) assertClose(ny[i], nx[i], tol * maxOf(1.0, kotlin.math.abs(nx[i])), "$what: $name number $i")
    }
    val ps = expected.scalars.associateBy { it.name }
    val qs = actual.scalars.associateBy { it.name }
    kotlin.test.assertEquals(ps.keys.sorted(), qs.keys.sorted(), "$what: the same scalars")
    for ((n, s) in ps) {
        val x = (ev1.eval(s.ref.node) as? constructit.core.EvalResult.Ok)?.value?.toString()
        val y = (ev2.eval(qs.getValue(n).ref.node) as? constructit.core.EvalResult.Ok)?.value?.toString()
        if (x == y) continue
        kotlin.test.assertEquals(x?.let { number.replace(it, "#") }, y?.let { number.replace(it, "#") }, "$what: scalar $n")
        val nx = number.findAll(x ?: "").map { it.value.toDouble() }.toList()
        val ny = number.findAll(y ?: "").map { it.value.toDouble() }.toList()
        for (i in nx.indices) assertClose(ny[i], nx[i], tol * maxOf(1.0, kotlin.math.abs(nx[i])), "$what: scalar $n")
    }
}

/**
 * [json] — a saved drawing — with [features] appended, each one feature object in the file's own spelling
 * (OP-32). What a test that used to append a journal line to a save does now: the file's order means nothing,
 * so where they go in the list is immaterial, and the load orders them by what they reference.
 */
fun withFeatures(
    json: String,
    vararg features: String,
): String {
    val root = Json.parseToJsonElement(json).jsonObject
    val list = (root["features"] as? JsonArray).orEmpty() + features.map { Json.parseToJsonElement(it) }
    return JsonObject(root + ("features" to JsonArray(list))).toString()
}

/** [json] with every feature passed through [f] — the edit a test makes to one feature of a saved file. */
fun mapFeatures(
    json: String,
    f: (JsonObject) -> JsonObject,
): String {
    val root = Json.parseToJsonElement(json).jsonObject
    return JsonObject(root + ("features" to JsonArray((root["features"] as JsonArray).map { f(it.jsonObject) }))).toString()
}

/** [o] with [key] set to [value] (a JSON literal). */
fun JsonObject.with(
    key: String,
    value: String,
): JsonObject = JsonObject(this + (key to Json.parseToJsonElement(value)))

/** [o] without [key]. */
fun JsonObject.without(key: String): JsonObject = JsonObject(this - key)

/** [json] with [parameters] and [features] added — each one object in the file's own spelling (OP-32). */
fun withEntries(
    json: String,
    parameters: List<String> = emptyList(),
    features: List<String> = emptyList(),
): String {
    val root = Json.parseToJsonElement(json).jsonObject
    val ps = (root["parameters"] as? JsonArray).orEmpty() + parameters.map { Json.parseToJsonElement(it) }
    val fs = (root["features"] as? JsonArray).orEmpty() + features.map { Json.parseToJsonElement(it) }
    return JsonObject(root + ("parameters" to JsonArray(ps)) + ("features" to JsonArray(fs))).toString()
}

/**
 * A dressing's corner record in its compact spelling, `2:m8.9-0;2:m9.10-0` — the entry block, the kind's letter
 * (`m` mitre, `r` rail, `f` patch), the base edges and the piece — read from the typed `slots` field (OP-31's
 * record as OP-32 writes it), so a test can state a whole record in one string.
 */
fun slotsOf(f: JsonObject): String =
    f.arr("slots").joinToString(";") { s ->
        val o = s.jsonObject
        val letter =
            when (o.str("kind")) {
                "mitre" -> "m"
                "rail" -> "r"
                else -> "f"
            }
        "${o.str("entry")}:$letter${o.ids("edges").joinToString(".")}-${o.str("piece")}"
    }

/** Every corner record a saved drawing states, one per dressing, in the compact spelling of [slotsOf]. */
fun Saved.slotRecords(): List<String> = features.filter { it.containsKey("slots") }.map { slotsOf(it) }

/**
 * Every corner record, **as the document [doc] lays it out** — each base edge by its index, in [slotsOf]'s compact
 * spelling (OP-32 slice 3: the file names the edges by key, and the index form is the dressing's own).
 */
fun Saved.slotRecords(doc: constructit.editor.Document): List<String> =
    features.filter { it.containsKey("slots") }.mapNotNull { f ->
        val fid = f.str("id")!!.removePrefix("f").toInt()
        doc.dressingSlotsOf(doc.journal.first { it.fid == fid })?.let { DocumentFormat.encodeSlots(it) }
    }

/** The features that are a rounding's tombstone (OP-30): `removed` says how many band slots it keeps. */
fun Saved.tombstones(): List<JsonObject> = features.filter { it.containsKey("removed") }

/** The formula a parameter states (`"formula": {"id": …, "text": …}`), or null for a literal or a wire. */
fun Saved.formulaOf(name: String): String? = (param(name)?.get("formula") as? JsonObject)?.str("text")

/** The name of the parameter [name] is wired to, or null. */
fun Saved.wireOf(name: String): String? =
    (param(name)?.get("wire") as? JsonObject)?.str("to")?.let { to -> parameters.firstOrNull { it.str("id") == to }?.str("name") }

/** The value a parameter states, as the expression text a user would type (`"20 mm"`). */
fun Saved.valueOf(name: String): String? = param(name)?.str("value")

/**
 * A saved drawing **without its high-water marks** (`nextId`) — what two saves are compared by when one of them
 * comes after an edit that was taken back by another edit rather than by undo (an ungroup, a delete): the
 * drawing is the same, while the identities the edit spent stay spent, which is the point of them (OP-32).
 */
fun contentOf(json: String): String = JsonObject(Json.parseToJsonElement(json).jsonObject - "nextId").toString()

/** The group a saved drawing states under [name]. */
fun Saved.group(name: String): JsonObject? = kind("group").firstOrNull { it.str("name") == name }

/** Every element identity the file declares, in the order its features state them (a path's vertices in turn). */
fun Saved.declared(): List<String> =
    features.flatMap { f ->
        f.ids("creates") + f.arr("vertices").flatMap { it.jsonObject.ids("creates") } +
            ((f["close"] as? JsonObject)?.ids("creates") ?: emptyList())
    }

/** The placement of the group [name] (its `place` object — the frame's id, origin and angle), or null. */
fun Saved.placeOf(name: String): JsonObject? = group(name)?.get("place") as? JsonObject

/** Whether any group of the file is placed. */
fun Saved.anyPlaced(): Boolean = kind("group").any { it.containsKey("place") }

/** The `orbit` features riding the pattern named [pattern] with the tool [tool]. */
fun Saved.orbits(
    pattern: String,
    tool: String,
): List<JsonObject> {
    val p = kind("pattern").firstOrNull { it.str("name") == pattern }?.str("id")
    return kind("orbit").filter { it.str("pattern") == p && it.str("tool") == tool }
}

/** A pick list (`pts`/`els`) spelled as the journal did — `e2@0@1,e5` — so a test can state a composed address in one string. */
fun JsonObject.picks(key: String): String =
    arr(key).joinToString(",") { item ->
        if (item is JsonObject) item.str("el") + item.ints("at").joinToString("") { "@$it" } else (item as JsonPrimitive).content
    }

/** The identities of the parameters named [names], in that order — what a feature's `params` states. */
fun Saved.paramIds(vararg names: String): List<String> = names.map { n -> param(n)?.str("id") ?: "?$n" }

/** Every `dofs` list the file states, as its JSON (`[{"deg":180},{"deg":0}]`). */
fun Saved.dofs(): List<String> = features.mapNotNull { it["dofs"]?.toString() }
