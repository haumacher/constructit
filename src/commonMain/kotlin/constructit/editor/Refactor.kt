package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.l10n.Msg
import constructit.l10n.Msgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Refactoring the construction on its graph** (OP-32, slice 3): replace an input of a feature, delete a feature
 * and reroute what read it onto its own input. Both are **edits of the file the drawing is** — the graph of
 * features with every reference an identity — followed by a load of the edited graph, so the result is exactly
 * what that file means: never a journal rewritten line by line, never a document patched in place.
 *
 * What makes it safe is what slices 1–3 put in the file: a reference is an identity, so re-pointing one is a
 * one-string edit and the load re-orders by references; a pick is a meaning, so a rounding moved onto another
 * body asks *that* body for the edge it names ([constructit.geom.Provenance]) and is invalid, naming it, where it
 * has none — never re-picked by position. The features the edit touched are rebuilt **as a gesture would build
 * them now** ([GraphFormat.Refactor]): whether a rounding joins a dressing or makes a body, which body is a chain's
 * tip, is decided by the inputs they now have.
 *
 * Headless and pure: the [Editor] adopts the document an operation returns and checkpoints it, which is what makes
 * each one **one undo step**.
 */
object Refactor {
    /** One input of a feature: which pick list, at which position, filled into which slot, naming which element. */
    class Input(
        val field: String,
        val position: Int,
        val slot: SlotKind,
        /** The tool's own word for that slot. */
        val role: String,
        val element: String,
    )

    /** What an operation made: the document to adopt, or why it refused; and what to say about it. */
    class Outcome(
        val doc: Document?,
        val note: Msg,
    )

    private fun root(doc: Document): JsonObject = Json.parseToJsonElement(DocumentFormat.save(doc)) as JsonObject

    private fun featuresOf(root: JsonObject): List<JsonObject> = (root["features"] as? JsonArray)?.map { it as JsonObject } ?: emptyList()

    private fun fidOf(o: JsonObject): Int = (o["id"] as? JsonPrimitive)?.content?.removePrefix("f")?.toIntOrNull() ?: -1

    private fun strings(v: JsonElement?): List<String> = (v as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "?" } ?: emptyList()

    /**
     * The inputs of the tool feature [o], in its tool's slot order (OP-32): point slots read `pts`, every other
     * picking slot reads `els`, a side picks nothing, and a face-part tool's part comes first as a solid — the
     * very mapping a replicated gesture reads its picks by ([Document.replicationOf]).
     */
    fun inputs(
        doc: Document,
        o: JsonObject,
    ): List<Input> {
        if ((o["kind"] as? JsonPrimitive)?.content != "tool") return emptyList()
        return inputs(doc.toolDef((o["tool"] as? JsonPrimitive)?.content ?: return emptyList()), strings(o["pts"]), strings(o["els"]))
    }

    /** The same, read off the step itself — what the panel asks on every repaint, without writing the file. */
    fun inputsOf(
        doc: Document,
        el: Element,
    ): List<Input> {
        val step = doc.creatingStep(el)?.takeIf { it.kind == "tool" } ?: return emptyList()

        fun picks(key: String): List<String> =
            ((step.args.firstOrNull { it is Arg.Keyed && it.key == key } as? Arg.Keyed)?.value as? Arg.Els)?.els.orEmpty().map { doc.nameOf(it) }
        return inputs(doc.toolDef((step.args.firstOrNull() as? Arg.Text)?.s ?: return emptyList()), picks("pts"), picks("els"))
    }

    private fun inputs(
        tool: ToolDef?,
        pts: List<String>,
        els: List<String>,
    ): List<Input> {
        if (tool == null) return emptyList()
        val out = ArrayList<Input>()
        var pi = 0
        var ei = 0
        if (tool.facePartOperand && els.isNotEmpty()) out.add(Input("els", ei, SlotKind.SOLID, Tools.roleOfKind(SlotKind.SOLID), els[ei++]))
        for ((i, slot) in tool.slots.withIndex()) {
            val repeats = tool.repeating && i == tool.slots.lastIndex
            when (slot) {
                SlotKind.SIDE -> {}
                SlotKind.PLACE_POINT, SlotKind.POINT, SlotKind.OPTIONAL_POINT ->
                    while (pi < pts.size) {
                        out.add(Input("pts", pi, slot, tool.roleOf(i), pts[pi++]))
                        if (!repeats) break
                    }
                else ->
                    while (ei < els.size) {
                        out.add(Input("els", ei, slot, tool.roleOf(i), els[ei++]))
                        if (!repeats) break
                    }
            }
        }
        return out
    }

    /** Every element identity the file states, by the kind it is now — what a touched feature's identities keep. */
    private fun kinds(doc: Document): Map<String, ElementKind> = doc.elements.filter { it.serial > 0 }.associate { "e${it.serial}" to it.kind }

    /**
     * **Replace input [index] of the feature that made [made] with [by]** (OP-32, slice 3). Refused, by name and
     * changing nothing, where no tool made [made], where [by] is no element its slot takes ([accepts]), where it is
     * the input already, and where [by] is built from what the feature makes — the edit would close a circle.
     */
    fun replaceInput(
        doc: Document,
        made: Element,
        index: Int,
        by: Element,
        accepts: (SlotKind, Element) -> Boolean,
    ): Outcome {
        val step = doc.creatingStep(made) ?: return Outcome(null, Msgs.statusRefactorNoInputs(name = doc.nameOf(made)))
        val root = root(doc)
        val f = featuresOf(root).firstOrNull { fidOf(it) == step.fid } ?: return Outcome(null, Msgs.statusRefactorNoInputs(name = doc.nameOf(made)))
        val feature = "f${step.fid}"
        val input = inputs(doc, f).getOrNull(index) ?: return Outcome(null, Msgs.statusRefactorNoInputs(name = doc.nameOf(made)))
        val byName = doc.nameOf(by)
        if (by.serial <= 0) return Outcome(null, Msgs.statusRefactorNotInFile(name = byName))
        if (byName == input.element) return Outcome(null, Msgs.statusRefactorSame(name = byName, role = input.role, feature = feature))
        if (!accepts(input.slot, by)) {
            return Outcome(null, Msgs.statusRefactorSlotRefused(name = byName, role = input.role, feature = feature, slot = Tools.roleOfKind(input.slot)))
        }
        // a circle: the new input is made by this feature, or by something built from what it makes
        val downstream = doc.dependentSteps(hashSetOf(step))
        val maker = doc.creatingStep(by)
        if (maker != null && (maker === step || maker in downstream)) {
            return Outcome(null, Msgs.statusRefactorCycle(name = byName, feature = feature))
        }
        val edited =
            edit(root) { o ->
                if (fidOf(o) != step.fid) return@edit o
                val list = strings(o[input.field]).toMutableList()
                list[input.position] = byName
                // the tip of a chain was decided from the old input: the rebuild decides it again (OP-32)
                rebuilt(o, input.field to JsonArray(list.map { JsonPrimitive(it) }), drop = setOf("tips"))
            }
        val (out, why) = reload(edited, setOf(step.fid), kinds(doc))
        if (out == null) return Outcome(null, why ?: Msg.EMPTY)
        return Outcome(out, Msgs.statusRefactorReplaced(feature = feature, new = byName, old = input.element, role = input.role, clause = invalidClause(out, step.fid)))
    }

    /**
     * **Delete the feature that made [el] and reroute what read it onto its input** (OP-32, slice 3) — offered
     * beside the delete that takes the dependents with it, never in its place.
     *
     * Every reference to an element the feature made is re-pointed to the **first input of the same kind**, and
     * where the reader is a tool, one its slot takes ([accepts]). Refused by name, changing nothing, where a reader
     * has nothing to take instead — a slot no input fits, a value the feature computed (nothing else gives it).
     */
    fun deleteRerouting(
        doc: Document,
        el: Element,
        accepts: (SlotKind, Element) -> Boolean,
    ): Outcome {
        val step = doc.creatingStep(el) ?: return Outcome(null, Msgs.statusRefactorNoInputs(name = doc.nameOf(el)))
        val root = root(doc)
        val all = featuresOf(root)
        val f = all.firstOrNull { fidOf(it) == step.fid } ?: return Outcome(null, Msgs.statusRefactorNotTool(name = doc.nameOf(el)))
        val feature = "f${step.fid}"
        if ((f["kind"] as? JsonPrimitive)?.content != "tool") return Outcome(null, Msgs.statusRefactorNotTool(name = doc.nameOf(el)))
        val inputs = inputs(doc, f).mapNotNull { i -> doc.elements.firstOrNull { "e${it.serial}" == i.element } }
        // **a dressed body is one feature with its roundings** (OP-30): the roundings that joined it are lines of
        // the body being deleted, never readers of it to be rerouted — rerouting each onto the base would fork one
        // part into as many bodies as it had roundings
        val dressing = doc.dressingOf(el)?.takeIf { d -> step.creates.any { it === d.body } }
        val joiners = dressing?.entries?.mapNotNull { e -> e.el?.let { doc.creatingStep(it) } }?.filter { it !== step }?.map { it.fid }?.toSet() ?: emptySet()
        // …and so are the roundings already taken off it, whose slots it kept (OP-30's tombstones)
        val tombs = dressing?.let { d -> all.filter { it.containsKey("removed") && strings(it["els"]).contains(doc.nameOf(d.body)) }.map { fidOf(it) } } ?: emptyList()
        val dropped = joiners + tombs + step.fid
        val made = all.filter { fidOf(it) in dropped }.flatMap { strings(it["creates"]) }.toSet()
        val madeScalars = all.filter { fidOf(it) in dropped }.flatMap { strings(it["scalars"]) }.toSet()
        val kindOf = kinds(doc)
        // a parameter's formula may read a value the feature makes, by its identity
        for (p in (root["parameters"] as? JsonArray)?.map { it as JsonObject }.orEmpty()) {
            val wire = (p["wire"] as? JsonObject)?.get("to")?.let { (it as? JsonPrimitive)?.content }
            if (wire != null && wire in madeScalars) {
                return Outcome(null, Msgs.statusRefactorRerouteScalar(feature = feature, dependent = (p["name"] as? JsonPrimitive)?.content ?: "?", scalar = wire))
            }
        }
        val touched = HashSet<Int>()
        val replacement = HashMap<Int, Map<String, String>>()
        for (d in all) {
            val id = fidOf(d)
            if (id in dropped) continue
            val reads = references(d)
            reads.firstOrNull { it in madeScalars }?.let { sc ->
                return Outcome(null, Msgs.statusRefactorRerouteScalar(feature = feature, dependent = "f$id", scalar = sc))
            }
            val hits = reads.filter { it in made }.distinct()
            if (hits.isEmpty()) continue
            val dInputs = inputs(doc, d)
            val map = HashMap<String, String>()
            for (x in hits) {
                val slot = dInputs.firstOrNull { it.element == x }?.slot
                val r =
                    inputs.firstOrNull { cand -> cand.kind == kindOf[x] && (slot == null || accepts(slot, cand)) }
                        ?: inputs.firstOrNull { cand -> slot != null && accepts(slot, cand) }
                        ?: return Outcome(
                            null,
                            Msgs.statusRefactorRerouteRefused(feature = feature, dependent = "f$id", element = x, slot = slot?.let { Tools.roleOfKind(it) } ?: kindOf[x]?.name?.lowercase() ?: "?"),
                        )
                map[x] = doc.nameOf(r)
            }
            touched.add(id)
            replacement[id] = map
        }
        val edited =
            edit(root, drop = dropped, gone = made) { o ->
                val map = replacement[fidOf(o)] ?: return@edit o
                renamed(o, map)
            }
        val (out, why) = reload(edited, touched, kindOf)
        if (out == null) return Outcome(null, why ?: Msg.EMPTY)
        val clause = touched.sorted().firstNotNullOfOrNull { invalidClause(out, it).takeIf { c -> c != Msg.EMPTY } } ?: Msg.EMPTY
        return Outcome(out, Msgs.statusRefactorRerouted(feature = feature, count = touched.size, clause = clause))
    }

    /** Every identity a feature reads — any string of it that names an element or a feature, wherever it stands. */
    private fun references(o: JsonObject): List<String> {
        val out = ArrayList<String>()

        fun walk(
            key: String?,
            v: JsonElement,
        ) {
            when (v) {
                is JsonObject -> v.forEach { (k, x) -> walk(k, x) }
                is JsonArray -> v.forEach { walk(key, it) }
                is JsonPrimitive -> if (v.isString && key != "id" && key != "creates" && key != "scalars" && ID.matches(v.content)) out.add(v.content)
            }
        }
        o.forEach { (k, v) -> if (k != "id") walk(k, v) }
        return out
    }

    private val ID = Regex("[ef]\\d+")

    /** [o] with every reference named in [map] re-pointed; the tip a chain was decided from is decided again. */
    private fun renamed(
        o: JsonObject,
        map: Map<String, String>,
    ): JsonObject {
        fun walk(
            key: String?,
            v: JsonElement,
        ): JsonElement =
            when (v) {
                is JsonObject -> JsonObject(LinkedHashMap(v.mapValues { (k, x) -> walk(k, x) }))
                is JsonArray -> JsonArray(v.map { walk(key, it) })
                is JsonPrimitive -> if (v.isString && key != "creates" && key != "id") map[v.content]?.let { JsonPrimitive(it) } ?: v else v
                else -> v
            }
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in o) if (k != "tips") out[k] = walk(k, v)
        return JsonObject(out)
    }

    /** [o] with [set] put in place (its key kept where it stood) and the keys [drop] removed. */
    private fun rebuilt(
        o: JsonObject,
        set: Pair<String, JsonElement>,
        drop: Set<String>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in o) if (k !in drop) out[k] = if (k == set.first) set.second else v
        return JsonObject(out)
    }

    /**
     * The file [root] with every feature passed through [each], the feature [drop] removed and the elements
     * [gone] taken out of the view, the names and the materials.
     */
    private fun edit(
        root: JsonObject,
        drop: Set<Int> = emptySet(),
        gone: Set<String> = emptySet(),
        each: (JsonObject) -> JsonObject,
    ): String {
        // a feature that followed a dropped one (`after`, OP-32 slice 3d) follows what that one followed instead
        val all = (root["features"] as? JsonArray)?.map { it as JsonObject }.orEmpty()
        val droppedAfter = all.filter { fidOf(it) in drop }.associate { fidOf(it) to strings(it["after"]) }

        fun through(
            ids: List<String>,
            seen: Set<String> = emptySet(),
        ): List<String> =
            ids.flatMap { id ->
                val f = id.removePrefix("f").toIntOrNull()
                if (f != null && f in drop && id !in seen) through(droppedAfter[f].orEmpty(), seen + id) else listOf(id)
            }.filter { it.removePrefix("f").toIntOrNull() !in drop }.distinct()

        fun followed(o: JsonObject): JsonObject {
            if (drop.isEmpty()) return o
            val m = LinkedHashMap<String, JsonElement>()
            for ((k, v) in o) {
                when {
                    k == "after" -> through(strings(v)).takeIf { it.isNotEmpty() }?.let { m[k] = JsonArray(it.map { a -> JsonPrimitive(a) }) }
                    k == "vertices" && v is JsonArray -> m[k] = JsonArray(v.map { followed(it as JsonObject) })
                    (k == "close" || k == "discard") && v is JsonObject -> m[k] = followed(v)
                    else -> m[k] = v
                }
            }
            return JsonObject(m)
        }
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in root) {
            out[k] =
                when (k) {
                    "features" -> JsonArray((v as JsonArray).map { it as JsonObject }.filter { fidOf(it) !in drop }.map { followed(each(it)) })
                    "names", "materials" -> JsonObject(LinkedHashMap((v as JsonObject).filterKeys { it !in gone }))
                    "view" -> {
                        val hidden = strings((v as JsonObject)["hidden"]).filter { it !in gone }
                        JsonObject(LinkedHashMap(v.toMap() + ("hidden" to JsonArray(hidden.map { JsonPrimitive(it) }))))
                    }
                    else -> v
                }
        }
        if ((out["view"] as? JsonObject)?.let { strings(it["hidden"]).isEmpty() } == true) out.remove("view")
        return Json.encodeToString(JsonElement.serializer(), JsonObject(out))
    }

    /** The edited file, loaded with the [touched] features rebuilt afresh — or the load's refusal, by name. */
    private fun reload(
        text: String,
        touched: Set<Int>,
        kinds: Map<String, ElementKind>,
    ): Pair<Document?, Msg?> =
        try {
            GraphFormat.read(text, null, GraphFormat.Refactor(touched, kinds)).first to null
        } catch (e: DocumentFormat.LoadError) {
            null to Msgs.statusRefactorFailed(why = Msg.text(e.message ?: ""))
        }

    /** What to add when the feature [fid] cannot be built after the edit: the reason of the first thing it made that is invalid. */
    private fun invalidClause(
        doc: Document,
        fid: Int,
    ): Msg {
        val step = doc.journal.firstOrNull { it.fid == fid } ?: return Msg.EMPTY
        val ev = Evaluator()
        val why = step.creates.firstNotNullOfOrNull { (ev.eval(it.ref.node) as? EvalResult.Invalid)?.why } ?: return Msg.EMPTY
        return Msgs.statusRefactorButInvalid(name = doc.nameOf(step.creates.first()), why = why)
    }
}
