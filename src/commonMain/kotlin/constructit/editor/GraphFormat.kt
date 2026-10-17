package constructit.editor

import constructit.core.Evaluator
import constructit.expr.ExprParser
import constructit.expr.refNames
import constructit.geom.CornerSlot
import constructit.geom.CornerSlotKind
import constructit.geom.CornerSlots
import constructit.l10n.Messages
import constructit.units.Dimension
import constructit.units.Quantity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **The document is a graph of features, not a journal** (OP-32) — the file format since version 11.
 *
 * The user's decision, in his words: *"It started with a simple 'recording of what has been done'. But this is not
 * a valid solution for a construction tool. Construction must be edited and refactored. Doing this on a journal of
 * actions is error prone. The file format should better describe what exists. It should materialize in a
 * well-supported container format such as json."* So the file is one JSON object:
 *
 * ```
 * { "format": "constructit", "version": 11, "nextId": {"e": 21, "f": 40},
 *   "parameters": [ {"id":"f1","name":"r","value":"20 mm"}, … ],
 *   "features":   [ {"id":"f2","kind":"point","at":[0,0],"creates":["e1"]}, … ],
 *   "names":      { "e5": "bore-axis" },
 *   "materials":  { "e9": {"color":"#b0b4ba","rough":0.5,"metal":0} },
 *   "view":       [ {"id":"f31","hide":["e3"]} ] }
 * ```
 *
 * **What exists, each under an identity of its own.** An element is `e<n>` and is named so everywhere
 * ([Document.nameOf]); everything else the file states — a feature, a parameter, a sketch space, a pattern, a
 * group, a binding, a visibility decision — is `f<n>`. Both are given once and never again in this document, and
 * both high-water marks are stored (`nextId`), so they survive a save, a load and an undo. A feature states the
 * elements it creates (`creates`) and the scalars (`scalars`); every reference names an identity — an element, a
 * scalar, a space, a pattern, a macro. **User names exist only where an expression reads them**: a parameter's
 * `name`, and an element's entry in `names`, which is what `P.x` resolves through (OP-7). A group's, a pattern's
 * and a space's names are labels and nothing refers to them.
 *
 * **Order carries no meaning.** The writer emits every list by identity, so the bytes are canonical; the reader
 * derives the order to build in from the references, topologically, and falls back on the identity — the order the
 * features were made in — wherever no reference decides. A reference cycle or an identity nothing creates is a load
 * error naming the feature. So shuffling the features of a file is the same drawing and saves to the same bytes.
 *
 * **Edit actions are folded into what they edit.** A path is one `path` feature with its vertex list (each vertex
 * keeps its own identity, because a weld or a snap may have happened to it while it was the path's end); a
 * parameter's formula or wire is part of the parameter; a placement is part of its group; a name and a material
 * are attributes; a space switch is the `space` of the feature drawn there. What stays a feature of its own is
 * what relates two existing things — a weld, an attach, a break of a leg — because folding it into either side
 * would make that side depend on the other although nothing in the drawing does.
 *
 * **What OP-18 decided and this keeps**: no nodes, no handles, no styles, no structure that a build derives —
 * every feature is rebuilt by running the same [ToolDef.build] that made it, through the journal reader's own
 * replay engine ([DocumentFormat.runRow]); values are restated as current values; choices (`signs`, a branch, a
 * side) are stored verbatim and never re-scored; numbers are written in the one canonical format — shortest
 * round-trip, never scientific ([DocumentFormat.trim]); and a stored literal's meaning is frozen, so a change of
 * meaning is a version bump plus a migration.
 *
 * **What it does not do yet** (queued, OP-32 slices 2 and 3): a pick that is stored as a *click* stays a click
 * (`clicks`, `click`, `cells`) — turning each slot kind's click into its meaning is slice 2 — and the editor
 * still edits through its in-memory journal; refactoring the graph itself (replace an input, reroute a
 * dependent) is slice 3.
 */
internal object GraphFormat {
    /** The library's compact encoder: every value of the file is written by it, never by hand. */
    private val json = Json

    // ---- the vocabulary: one schema per feature kind, read in both directions ----

    /** What a field holds — and so how a value is written to JSON and handed back to the replay engine. */
    enum class T { EL, ELS, REFS, POS, POSITIONS, NUM, NUMS, TEXT, INT, INTS, LABEL, SC, SCS, SLOTS, FLAG, NUM_OR_LABEL, TOOL, PATTERN, SPACE }

    /**
     * One field of a feature: its JSON name, the step argument it is (`key` for a `key=value` argument, null
     * for a positional one) and its type. [expr] marks a text the user typed as an expression, whose names
     * are references the order must respect.
     */
    class Field(val json: String, val key: String?, val type: T, val expr: Boolean = false)

    private fun p(
        json: String,
        type: T,
        expr: Boolean = false,
    ) = Field(json, null, type, expr)

    private fun k(
        key: String,
        type: T,
        json: String = key,
        expr: Boolean = false,
    ) = Field(json, key, type, expr)

    /**
     * Every feature kind that is written field by field — the step kinds the journal reader replays, each
     * with what its arguments are. A kind missing here is either folded into something else (a path, a
     * parameter, a group, a name, a material, a visibility decision, a space switch) or a programming error.
     */
    val SCHEMA: Map<String, List<Field>> =
        mapOf(
            "point" to listOf(p("at", T.POS)),
            "breakarc" to listOf(p("arc", T.EL), p("angle", T.NUM), p("sweep", T.TEXT)),
            "breakellipse" to listOf(p("conic", T.EL), p("angle", T.NUM), p("sweep", T.TEXT)),
            "opening" to listOf(p("wall", T.EL), k("leg", T.INT), k("pos", T.NUM), k("width", T.SC), k("sill", T.NUM), k("head", T.NUM)),
            "sketchspace" to
                listOf(
                    p("name", T.LABEL), k("el", T.EL), k("piece", T.INT), k("line", T.EL), k("angle", T.SC), k("offset", T.SC),
                    k("path", T.EL), k("at", T.SC, json = "along"), k("wire", T.EL), k("part", T.EL),
                ),
            "sectioninput" to listOf(p("plane", T.SPACE), k("el", T.EL), k("edge", T.INT), k("corner", T.INT)),
            "spaceorigin" to listOf(p("plane", T.SPACE), k("el", T.EL), k("corner", T.INT), k("dx", T.SC), k("dy", T.SC)),
            "import" to listOf(k("src", T.LABEL), k("pose", T.NUMS), k("mesh", T.TEXT)),
            "importcurve" to listOf(k("src", T.LABEL), k("pose", T.NUMS), k("path", T.TEXT)),
            "wiresketch" to listOf(p("wire", T.EL), k("pts", T.POSITIONS), k("closed", T.FLAG)),
            "weld" to listOf(p("point", T.EL), p("onto", T.EL)),
            "attach" to listOf(p("point", T.EL), p("curve", T.EL), k("dofs", T.NUMS)),
            "weldortho" to listOf(p("vertex", T.EL), p("onto", T.EL)),
            "attachortho" to listOf(p("vertex", T.EL), p("curve", T.EL), k("dofs", T.NUMS)),
            "unweld" to listOf(p("point", T.EL), k("dofs", T.NUMS)),
            "funccurve" to listOf(p("x", T.LABEL, expr = true), p("y", T.LABEL, expr = true), k("from", T.NUM_OR_LABEL, expr = true), k("to", T.NUM_OR_LABEL, expr = true)),
            "intersectnear" to listOf(p("a", T.EL), p("b", T.EL), p("click", T.POS), k("signs", T.INTS)),
            "pointoncurve" to listOf(p("curve", T.EL), p("click", T.POS), k("dofs", T.NUMS)),
            "relative" to listOf(p("point", T.EL), p("base", T.EL), k("dofs", T.NUMS)),
            "absolute" to listOf(p("point", T.EL), k("dofs", T.NUMS)),
            "projectplane" to listOf(p("source", T.EL)),
            "orthojoin" to listOf(p("leg", T.EL)),
            "orthobreak" to listOf(p("leg", T.EL), p("at", T.POS), p("open", T.POS)),
            "tool" to
                listOf(
                    p("tool", T.TOOL), k("pts", T.ELS), k("els", T.ELS), k("clicks", T.POSITIONS), k("scalar", T.SCS, json = "params"),
                    k("count", T.INT), k("law", T.LABEL, expr = true), k("laws", T.LABEL, expr = true), k("match", T.ELS),
                    k("signs", T.INTS), k("removed", T.INT), k("slots", T.SLOTS), k("dofs", T.NUMS),
                ),
            "pattern" to listOf(p("name", T.LABEL), p("shape", T.TEXT), k("ref", T.EL), k("centre", T.EL), k("to", T.EL), k("count", T.INT)),
            "orbit" to
                listOf(
                    p("pattern", T.PATTERN), p("tool", T.TOOL), k("pts", T.REFS), k("els", T.REFS), k("part", T.TEXT), k("cells", T.POSITIONS),
                    k("scalar", T.SCS, json = "params"), k("count", T.INT), k("signs", T.INTS), k("dofs", T.NUMS),
                ),
            "macrodef" to listOf(p("name", T.LABEL), k("els", T.ELS), k("pts", T.ELS), k("scalar", T.SCS, json = "params")),
            "wall" to listOf(p("thickness", T.SC), p("justify", T.TEXT)),
        )

    // ---- writing ----

    fun write(doc: Document): String = layout(Writer(doc).root())

    /** One save: the document's journal read as the graph it describes (OP-32). */
    private class Writer(val doc: Document) {
        val ev = Evaluator()
        val present = doc.elements.toHashSet()
        val declared = HashSet<String>()
        val declaredScalars = HashSet<ScalarEntry>()

        /** Which feature declares each space, pattern and macro, by the name the steps use for them. */
        val spaceIds = HashMap<String, Int>()
        val patternIds = HashMap<String, Int>()
        val macroIds = HashMap<String, Int>()

        init {
            for (s in doc.journal) {
                s.creates.forEach { declared.add(it.id) }
                declaredScalars.addAll(s.createsScalars)
                val label = (s.args.firstOrNull() as? Arg.Label)?.s
                when (s.kind) {
                    "sketchspace" -> label?.let { spaceIds[it] = s.fid }
                    "pattern" -> label?.let { patternIds[it] = s.fid }
                    "macrodef" -> label?.let { macroIds[it] = s.fid }
                }
            }
        }

        fun fid(id: Int): JsonPrimitive = JsonPrimitive("f$id")

        fun el(e: Element): JsonPrimitive = if (e.serial > 0 && e.id in declared) JsonPrimitive("e${e.serial}") else JsonPrimitive("?${e.id}")

        fun sc(e: ScalarEntry): JsonPrimitive = if (e.serial > 0 && e in declaredScalars) fid(e.serial) else JsonPrimitive("?${e.name}")

        fun root(): JsonObject {
            val params = ArrayList<Pair<Int, LinkedHashMap<String, JsonElement>>>()
            val paramOf = HashMap<ScalarEntry, LinkedHashMap<String, JsonElement>>()
            val features = ArrayList<Pair<Int, LinkedHashMap<String, JsonElement>>>()
            val paths = HashMap<OrthoPath, LinkedHashMap<String, JsonElement>>()
            val pathVertices = HashMap<OrthoPath, ArrayList<JsonElement>>()
            val groups = HashMap<Group, LinkedHashMap<String, JsonElement>>()
            val lastBinding = LinkedHashMap<ScalarEntry, Pair<Step, List<Arg>>>()
            val names = ArrayList<Pair<Int, String>>()
            val materials = ArrayList<Pair<Int, JsonElement>>()
            // the space each step was made in — the journal's own rule (OP-17): a step belongs to the last space
            // named before it, and a `sketchspace` step names the space it creates for the steps after it
            var space: String = Document.PLAN_SPACE
            for ((index, step) in doc.journal.withIndex()) {
                val args = DocumentFormat.restate(doc, step, index, ev, present, declared)
                val here = space.takeIf { it != Document.PLAN_SPACE }?.let { n -> spaceIds[n]?.let { fid(it) } ?: JsonPrimitive("?$n") }
                when (step.kind) {
                    "space" -> space = (args.firstOrNull() as? Arg.Label)?.s ?: space
                    // implied by the path a vertex is added to: nothing to state
                    "orthoresume" -> {}
                    "param" -> {
                        val e = step.createsScalars.firstOrNull() ?: (args[0] as Arg.Sc).entry
                        val m = linkedMapOf<String, JsonElement>("id" to fid(step.fid), "name" to JsonPrimitive(e.name), "value" to JsonPrimitive(valueText((args[2] as Arg.Num).q)))
                        params.add(step.fid to m)
                        paramOf[e] = m
                    }
                    // a binding is the **state** of the scalar it binds, so only the last one counts (OP-32): an
                    // `unbind` after it leaves a literal, which the scalar's own feature restates
                    "bind", "wire", "unbind" -> lastBinding[(args[0] as Arg.Sc).entry] = step to args
                    "name" -> {
                        val e = (args[0] as Arg.El).el
                        (args.getOrNull(1) as? Arg.Label)?.let { if (e.serial > 0) names.add(e.serial to it.s) }
                    }
                    "material" -> {
                        val e = (args[0] as Arg.El).el
                        if (e.serial > 0) materials.add(e.serial to material(args))
                    }
                    // visibility is stated as what *is* (`view.hidden`, below), never as the gestures that made it
                    "hide", "show" -> {}
                    "group" -> {
                        val g = doc.groupDeclaredBy(step)
                        val members = ((args.firstOrNull { it is Arg.Keyed && it.key == "els" } as? Arg.Keyed)?.value as? Arg.Els)?.els.orEmpty()
                        val m =
                            linkedMapOf<String, JsonElement>(
                                "id" to fid(step.fid),
                                "kind" to JsonPrimitive("group"),
                                "name" to JsonPrimitive((args.firstOrNull() as? Arg.Label)?.s ?: ""),
                                "members" to JsonArray(members.map { el(it) }),
                            )
                        if (g != null) groups[g] = m
                        features.add(step.fid to m)
                    }
                    // a placement is **part of its group** (OP-32): the frame the group stands in, under the
                    // identity the placement had, so it is replayed where it was made
                    "place" -> {
                        val g = doc.groupPlacedBy(step) ?: doc.groups.firstOrNull { it.name == (args.firstOrNull() as? Arg.Label)?.s }
                        val m = g?.let { groups[it] } ?: error("a placement whose group no step declares")
                        val at = (args.firstOrNull { it is Arg.Keyed && it.key == "at" } as? Arg.Keyed)?.value as? Arg.Pos
                        val angle = (args.firstOrNull { it is Arg.Keyed && it.key == "angle" } as? Arg.Keyed)?.value as? Arg.Num
                        val place = linkedMapOf<String, JsonElement>("id" to fid(step.fid))
                        here?.let { place["space"] = it }
                        at?.let { place["at"] = pos(it.p) }
                        angle?.let { place["angle"] = quantity(it.q) }
                        m["place"] = JsonObject(place)
                    }
                    // **an ortho path is one feature with its vertex list** (OP-32). Each vertex keeps the identity
                    // of the step that added it, so it is rebuilt where it was added: a weld or a snap made while it
                    // was the path's end is still made while it is the path's end.
                    "orthostart" -> {
                        val path = step.path ?: error("an ortho start with no path")
                        val m = linkedMapOf<String, JsonElement>("id" to fid(step.fid), "kind" to JsonPrimitive("path"))
                        here?.let { m["space"] = it }
                        m["at"] = pos((args[0] as Arg.Pos).p)
                        m["creates"] = JsonArray(step.creates.map { el(it) })
                        paths[path] = m
                        pathVertices[path] = ArrayList()
                        features.add(step.fid to m)
                    }
                    "orthovertex", "orthoprepend" -> {
                        val path = step.path ?: error("an ortho vertex with no path")
                        val v = linkedMapOf<String, JsonElement>("id" to fid(step.fid))
                        // a vertex states its space only where it differs from its path's — `null` for the plan
                        if (here != (paths[path]?.get("space"))) v["space"] = here ?: JsonNull
                        v["at"] = pos((args[0] as Arg.Pos).p)
                        if (step.kind == "orthoprepend") v["prepend"] = JsonPrimitive(true)
                        v["creates"] = JsonArray(step.creates.map { el(it) })
                        (pathVertices[path] ?: error("a vertex of a path no step started")).add(JsonObject(v))
                    }
                    "orthoclose", "orthodiscard" -> {
                        val path = step.path ?: error("an ortho close with no path")
                        val m = paths[path] ?: error("a close of a path no step started")
                        val c = linkedMapOf<String, JsonElement>("id" to fid(step.fid))
                        if (step.creates.isNotEmpty()) c["creates"] = JsonArray(step.creates.map { el(it) })
                        m[if (step.kind == "orthoclose") "close" else "discard"] = JsonObject(c)
                    }
                    "wall" -> {
                        val m = feature(step, args, here)
                        val start = doc.journal.firstOrNull { it.kind == "orthostart" && it.path === step.path && step.path != null }
                        val ordered = LinkedHashMap<String, JsonElement>()
                        for ((key, v) in m) {
                            ordered[key] = v
                            if (key == "kind") ordered["path"] = start?.let { fid(it.fid) } ?: JsonPrimitive("?path")
                        }
                        features.add(step.fid to ordered)
                    }
                    else -> {
                        features.add(step.fid to feature(step, args, here))
                        if (step.kind == "sketchspace") space = (args.firstOrNull() as? Arg.Label)?.s ?: space
                    }
                }
            }
            for ((path, m) in paths) {
                val vs = pathVertices[path].orEmpty()
                if (vs.isEmpty()) continue
                // the vertex list sits after the start and before the close, wherever the close was recorded
                val close = m.remove("close")
                val discard = m.remove("discard")
                m["vertices"] = JsonArray(vs)
                close?.let { m["close"] = it }
                discard?.let { m["discard"] = it }
            }
            for ((e, pair) in lastBinding) {
                val (step, args) = pair
                if (step.kind == "unbind") continue
                val binding = linkedMapOf<String, JsonElement>("id" to fid(step.fid))
                if (step.kind == "bind") binding["text"] = JsonPrimitive((args[2] as Arg.Label).s) else binding["to"] = sc((args[2] as Arg.Sc).entry)
                val param = paramOf[e]
                if (param != null) {
                    param[if (step.kind == "bind") "formula" else "wire"] = JsonObject(binding)
                } else {
                    // a scalar some other feature made (an opening's position, an offset): a binding of what
                    // that feature owns, so a feature of its own that names it
                    val m = linkedMapOf<String, JsonElement>("id" to fid(step.fid), "kind" to JsonPrimitive(step.kind), "scalar" to sc(e))
                    if (step.kind == "bind") m["formula"] = binding["text"]!! else m["to"] = binding["to"]!!
                    features.add(step.fid to m)
                }
            }
            val root = linkedMapOf<String, JsonElement>()
            root["format"] = JsonPrimitive(DocumentFormat.MAGIC)
            root["version"] = JsonPrimitive(DocumentFormat.VERSION)
            root["nextId"] = JsonObject(linkedMapOf("e" to JsonPrimitive(doc.elementCounter + 1), "f" to JsonPrimitive(doc.featureCounter + 1)))
            root["parameters"] = JsonArray(params.sortedBy { it.first }.map { JsonObject(it.second) })
            root["features"] = JsonArray(features.sortedBy { it.first }.map { JsonObject(it.second) })
            if (names.isNotEmpty()) root["names"] = JsonObject(LinkedHashMap(names.sortedBy { it.first }.associate { "e${it.first}" to JsonPrimitive(it.second) }))
            if (materials.isNotEmpty()) root["materials"] = JsonObject(LinkedHashMap(materials.sortedBy { it.first }.associate { "e${it.first}" to it.second }))
            // **the view states what is** (OP-32): the elements hidden now, each once, by identity — what a user
            // hid, never what a construction hides by itself (a welded alias, a boundary's duplicate marker),
            // which the replay hides again. Toggling leaves nothing but the outcome, and a hide undone by a show
            // leaves nothing at all.
            val hidden =
                doc.elements
                    .filter { !it.visible && it.serial > 0 && it.id in declared && !doc.hiddenByConstruction(it) }
                    .sortedBy { it.serial }
            if (hidden.isNotEmpty()) root["view"] = JsonObject(linkedMapOf("hidden" to JsonArray(hidden.map { el(it) })))
            return JsonObject(root)
        }

        fun material(args: List<Arg>): JsonElement {
            fun keyed(k: String) = (args.firstOrNull { it is Arg.Keyed && it.key == k } as? Arg.Keyed)?.value
            val m = linkedMapOf<String, JsonElement>()
            (keyed("color") as? Arg.Text)?.let { m["color"] = JsonPrimitive(it.s) }
            (keyed("rough") as? Arg.Num)?.let { m["rough"] = number(it.q.value) }
            (keyed("metal") as? Arg.Num)?.let { m["metal"] = number(it.q.value) }
            return JsonObject(m)
        }

        /** A step written field by field, through its [SCHEMA] entry. */
        fun feature(
            step: Step,
            args: List<Arg>,
            space: JsonPrimitive?,
        ): LinkedHashMap<String, JsonElement> {
            val schema = SCHEMA[step.kind] ?: error("no file schema for step kind '${step.kind}'")
            val m = linkedMapOf<String, JsonElement>("id" to fid(step.fid), "kind" to JsonPrimitive(step.kind))
            space?.let { m["space"] = it }
            val positional = args.filter { it !is Arg.Keyed }
            val keyed = args.filterIsInstance<Arg.Keyed>()
            val posFields = schema.filter { it.key == null }
            check(positional.size <= posFields.size) { "step '${step.kind}' has ${positional.size} positional arguments, its schema ${posFields.size}" }
            val values = HashMap<Field, Arg>()
            positional.forEachIndexed { i, a -> values[posFields[i]] = a }
            for (a in keyed) {
                val f = schema.firstOrNull { it.key == a.key } ?: error("step '${step.kind}' has an argument '${a.key}' its schema does not know")
                values[f] = a.value
            }
            for (f in schema) values[f]?.let { put(m, f, it) }
            // the decisions its build took from what the user sees, in the order taken (OP-32): `null` where the
            // decision was the picked body itself, or the picked edge alone — and nothing at all where every one was
            if (step.tips.any { it != null }) m["tips"] = JsonArray(step.tips.map { t -> t?.let { el(it) } ?: JsonNull })
            if (step.runs.any { it != null }) m["runs"] = JsonArray(step.runs.map { r -> r?.let { JsonArray(it.map { i -> JsonPrimitive(i) }) } ?: JsonNull })
            if (step.creates.isNotEmpty()) m["creates"] = JsonArray(step.creates.map { el(it) })
            if (step.createsScalars.isNotEmpty()) m["scalars"] = JsonArray(step.createsScalars.map { sc(it) })
            return m
        }

        fun put(
            m: LinkedHashMap<String, JsonElement>,
            f: Field,
            a: Arg,
        ) {
            when (f.type) {
                T.TOOL -> {
                    val id = (a as Arg.Text).s
                    if (id.startsWith(MACRO_TOOL_PREFIX)) {
                        m[f.json] = JsonPrimitive("macro")
                        val name = id.removePrefix(MACRO_TOOL_PREFIX)
                        m["macro"] = macroIds[name]?.let { fid(it) } ?: JsonPrimitive("?$name")
                    } else {
                        m[f.json] = JsonPrimitive(id)
                    }
                }
                else -> m[f.json] = value(f.type, a)
            }
        }

        fun value(
            t: T,
            a: Arg,
        ): JsonElement =
            when (t) {
                T.EL -> el((a as Arg.El).el)
                T.ELS, T.REFS ->
                    when (a) {
                        is Arg.Els -> JsonArray(a.els.map { el(it) })
                        is Arg.Refs -> JsonArray(a.items.map { ref(it) })
                        else -> JsonArray(listOf(ref(a)))
                    }
                T.POS -> pos((a as Arg.Pos).p)
                T.POSITIONS -> JsonArray((a as Arg.Positions).ps.map { pos(it) })
                T.NUM -> quantity((a as Arg.Num).q)
                T.NUMS -> JsonArray((a as Arg.Nums).qs.map { quantity(it) })
                T.TEXT, T.LABEL -> JsonPrimitive(text(a))
                T.INT -> JsonPrimitive(text(a).toInt())
                T.INTS -> JsonArray(text(a).split(';').filter { it.isNotEmpty() }.map { JsonPrimitive(it.toInt()) })
                T.SC -> sc((a as Arg.Sc).entry)
                T.SCS ->
                    when (a) {
                        is Arg.Scs -> JsonArray(a.entries.map { sc(it) })
                        else -> JsonArray(listOf(sc((a as Arg.Sc).entry)))
                    }
                T.SLOTS -> slots(DocumentFormat.parseSlots(text(a)))
                T.FLAG -> JsonPrimitive(text(a) == "1")
                T.NUM_OR_LABEL ->
                    when (a) {
                        is Arg.Num -> quantity(a.q)
                        is Arg.Label -> JsonPrimitive(a.s)
                        else -> text(a).toDoubleOrNull()?.let { number(it) } ?: JsonPrimitive(text(a))
                    }
                T.PATTERN -> text(a).let { n -> patternIds[n]?.let { fid(it) } ?: JsonPrimitive("?$n") }
                T.SPACE -> text(a).let { n -> spaceIds[n]?.let { fid(it) } ?: JsonPrimitive("?$n") }
                T.TOOL -> JsonPrimitive(text(a))
            }

        fun ref(a: Arg): JsonElement =
            when (a) {
                is Arg.El -> el(a.el)
                // a member of a replicated gesture's orbit (OP-23): the anchor, and one index per nesting level
                is Arg.Member -> JsonObject(linkedMapOf("el" to el(a.el), "at" to JsonArray(a.offsets.map { JsonPrimitive(it) })))
                else -> error("not a reference: $a")
            }

        fun text(a: Arg): String =
            when (a) {
                is Arg.Text -> a.s
                is Arg.Label -> a.s
                else -> error("not a text: $a")
            }

        /** A dressing's corner record (OP-31, slice 5g) as typed fields rather than its packed spelling. */
        fun slots(s: CornerSlots): JsonElement {
            val out = ArrayList<JsonElement>()

            fun one(
                k: Int,
                slot: CornerSlot,
            ) = JsonObject(
                linkedMapOf(
                    "entry" to JsonPrimitive(k),
                    "kind" to JsonPrimitive(slot.kind.name.lowercase()),
                    "edges" to JsonArray(slot.edges.map { JsonPrimitive(it) }),
                    "piece" to JsonPrimitive(slot.piece),
                ),
            )
            for ((k, block) in s.edges.withIndex()) for (slot in block) out.add(one(k, slot))
            for ((k, block) in s.faces.withIndex()) for (slot in block) out.add(one(k, slot))
            return JsonArray(out)
        }
    }

    /** A number in the file's one canonical spelling — shortest round-trip, never scientific (OP-18). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun number(v: Double): JsonElement = JsonUnquotedLiteral(DocumentFormat.trim(v))

    private fun pos(p: constructit.geom.Vec2): JsonElement = JsonArray(listOf(number(p.x), number(p.y)))

    /**
     * A quantity as a typed value: a length `{"mm": 12.5}`, an angle `{"deg": 30}`, a plain number bare. The
     * unit is the key, so a list of a tool's freedoms that mixes lengths and angles stays readable on its own.
     */
    private fun quantity(q: Quantity): JsonElement =
        when (q.dim) {
            Dimension.LENGTH -> JsonObject(linkedMapOf("mm" to number(q.mm)))
            Dimension.ANGLE -> JsonObject(linkedMapOf("deg" to number(q.deg)))
            else -> number(q.value)
        }

    /** A parameter's value as the expression a user would type for it (`"20 mm"`, `"30 deg"`, `"3"`). */
    private fun valueText(q: Quantity): String =
        when (q.dim) {
            Dimension.LENGTH -> "${DocumentFormat.trim(q.mm)} mm"
            Dimension.ANGLE -> "${DocumentFormat.trim(q.deg)} deg"
            else -> DocumentFormat.trim(q.value)
        }

    /**
     * The file's layout: the top level one key to a line, and every parameter, feature, name and material
     * **one line of its own** (the view, a set, is one line) — so a diff of two saves shows exactly the features that changed, one line each.
     * Every token is the library's; this chooses only where the line breaks go.
     */
    private fun layout(root: JsonObject): String {
        val out = StringBuilder("{\n")
        val keys = root.keys.toList()
        for ((i, key) in keys.withIndex()) {
            val v = root.getValue(key)
            out.append("  ").append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(key))).append(": ")
            if (v is JsonArray && v.isNotEmpty()) {
                out.append("[\n")
                v.forEachIndexed { j, item ->
                    out.append("    ").append(json.encodeToString(JsonElement.serializer(), item))
                    out.append(if (j < v.size - 1) ",\n" else "\n")
                }
                out.append("  ]")
            } else if (v is JsonObject && (key == "names" || key == "materials") && v.isNotEmpty()) {
                out.append("{\n")
                v.entries.forEachIndexed { j, (k2, item) ->
                    out.append("    ").append(json.encodeToString(JsonElement.serializer(), JsonPrimitive(k2))).append(": ")
                    out.append(json.encodeToString(JsonElement.serializer(), item))
                    out.append(if (j < v.size - 1) ",\n" else "\n")
                }
                out.append("  }")
            } else {
                out.append(json.encodeToString(JsonElement.serializer(), v))
            }
            out.append(if (i < keys.size - 1) ",\n" else "\n")
        }
        return out.append("}\n").toString()
    }

    // ---- reading ----

    /** How many parameters and features a graph file states. */
    fun count(text: String): Int {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return 0
        return listOf("parameters", "features").sumOf { (root[it] as? JsonArray)?.size ?: 0 }
    }

    /**
     * Read a graph file into a fresh document — or, with [restamp], the same drawing with one count rewritten
     * (OP-23), returning what that re-stamp had to leave behind.
     */
    fun read(
        text: String,
        restamp: DocumentFormat.Restamp?,
    ): Pair<Document, List<String>> {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                throw DocumentFormat.LoadError(Messages.refusalFileNotJson(why = e.message ?: e.toString()))
            } ?: throw DocumentFormat.LoadError(Messages.refusalFileNotDrawing())
        if ((root["format"] as? JsonPrimitive)?.content != DocumentFormat.MAGIC) throw DocumentFormat.LoadError(Messages.refusalFileNotDrawing())
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw DocumentFormat.LoadError(Messages.refusalFileNotDrawing())
        if (version > DocumentFormat.VERSION) {
            throw DocumentFormat.LoadError(Messages.refusalFileNewer(version = version, max = DocumentFormat.VERSION))
        }
        if (version < DocumentFormat.GRAPH_VERSION) throw DocumentFormat.LoadError(Messages.refusalFileNotDrawing())
        return Reader(root, version, restamp).run()
    }

    /** One replay part — a feature, or one part of a folded one that keeps an identity of its own. */
    private class Part(
        val id: Int,
        /** How a load error names it: its identity and what it is (`f12 (tool circle)`). */
        val label: String,
        /** The feature that holds it in the file — the part itself, or the path, parameter or group it belongs to. */
        val obj: JsonObject,
        /** Whether it is built in a sketch space (and so switches to [space] first), and which: null = the plan. */
        val placed: Boolean,
        val space: Int?,
        val declared: List<String>,
        val declaredScalars: List<Int>,
        /** Identities it reads: elements (`e5`), and everything else (`f12`). */
        val refs: List<String>,
        /** Names an expression of it reads — a parameter's or an element's (OP-7). */
        val names: List<String>,
        /**
         * Parts it must follow although it names nothing of theirs: the previous vertex of its own path, and
         * for a wall the last vertex its path had when the wall was built.
         */
        val after: List<Int>,
        val words: (Reader) -> List<String>,
        val before: (Reader) -> Unit = {},
        /** The decisions the feature states (OP-32): tips by identity, runs by edge address. */
        val tips: List<String?> = emptyList(),
        val runs: List<List<Int>?> = emptyList(),
    ) {
        /** Further parts to follow, found once every part is known — a wall's last vertex. */
        val later = ArrayList<Int>()
    }

    private class Reader(val root: JsonObject, val version: Int, val restamp: DocumentFormat.Restamp?) {
        val doc = Document()
        val parts = ArrayList<Part>()

        // what the file states about the identities that are not elements — read up front, because a name a
        // word needs (a space's, a pattern's) is the file's and not something the replay discovers
        val spaceName = HashMap<Int, String>()
        val patternName = HashMap<Int, String>()
        val macroName = HashMap<Int, String>()
        val paramName = HashMap<Int, String>()
        val userNames = HashMap<String, String>()

        /** The elements the file states hidden — set as each is created, so every later build sees the view. */
        val hidden = HashSet<String>()
        val materials = HashMap<String, JsonObject>()

        // what the replay has built so far, by identity
        val byName = HashMap<String, Element>()
        val scalarById = HashMap<Int, ScalarEntry>()
        val pathById = HashMap<Int, OrthoPath>()

        /** Each path's parts by identity, and each wall's path — what orders a wall after the vertices it was built over. */
        val pathParts = HashMap<Int, MutableList<Int>>()
        val wallPath = HashMap<Int, Int>()

        fun run(): Pair<Document, List<String>> {
            collect()
            val order = sorted()
            doc.replayingVersion = version
            doc.dressingJoins = true
            try {
                for (u in order) replay(u)
            } finally {
                doc.replayingVersion = null
                doc.dressingJoins = true
                doc.dressingDeclares = -1
                doc.dressingRemoved = -1
                doc.dressingSlots = CornerSlots.NONE
            }
            finishIdentities()
            doc.recordSlotsOfEveryDressing(say = false)
            doc.publishLoadNotes()
            return doc to (restamp?.notes ?: emptyList())
        }

        // ---- reading the file into parts ----

        fun malformed(
            label: String,
            why: String,
        ): Nothing = throw DocumentFormat.LoadError(Messages.refusalFileMalformed(feature = label, why = why))

        fun idOf(
            v: JsonElement?,
            label: String,
        ): Int {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed(label, "an identity is missing")
            if (!s.startsWith("f")) malformed(label, "'$s' is not a feature identity")
            return s.drop(1).toIntOrNull() ?: malformed(label, "'$s' is not a feature identity")
        }

        fun strings(v: JsonElement?): List<String> = (v as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "?" } ?: emptyList()

        fun str(
            o: JsonObject,
            key: String,
        ): String? = (o[key] as? JsonPrimitive)?.content

        fun collect() {
            for (p in root["parameters"] as? JsonArray ?: JsonArray(emptyList())) {
                val o = p as? JsonObject ?: malformed("parameters", "an entry is not an object")
                val id = idOf(o["id"], "parameters")
                paramName[id] = str(o, "name") ?: malformed("f$id", "a parameter needs a name")
            }
            (root["names"] as? JsonObject)?.forEach { (e, n) -> userNames[e] = (n as? JsonPrimitive)?.content ?: malformed(e, "a name is not a text") }
            (root["materials"] as? JsonObject)?.forEach { (e, m) -> materials[e] = m as? JsonObject ?: malformed(e, "a material is not an object") }
            val features = root["features"] as? JsonArray ?: JsonArray(emptyList())
            for (f in features) {
                val o = f as? JsonObject ?: malformed("features", "an entry is not an object")
                val id = idOf(o["id"], "features")
                val name = str(o, "name")
                when (str(o, "kind")) {
                    "sketchspace" -> spaceName[id] = name ?: malformed("f$id", "a sketch space needs a name")
                    "pattern" -> patternName[id] = name ?: malformed("f$id", "a pattern needs a name")
                    "macrodef" -> macroName[id] = name ?: malformed("f$id", "a macro needs a name")
                }
            }
            for (p in root["parameters"] as? JsonArray ?: JsonArray(emptyList())) parameter(p as JsonObject)
            for (f in features) feature(f as JsonObject)
            (root["view"] as? JsonObject)?.let { view ->
                hidden.addAll(strings(view["hidden"] ?: malformed("view", "the view states which elements are hidden")))
            }
        }

        fun parameter(o: JsonObject) {
            val id = idOf(o["id"], "parameters")
            val name = paramName.getValue(id)
            val value = str(o, "value") ?: malformed("f$id", "a parameter needs a value")
            parts.add(Part(id, "f$id ($name)", o, false, null, emptyList(), listOf(id), emptyList(), emptyList(), emptyList(), { listOf("param", DocumentFormat.quote(name), "=", value.replace(" ", "")) }))
            binding(o, id, "f$id ($name)")
        }

        /** A parameter's or a scalar's formula or wire — a part of its own, replayed where it was stated. */
        fun binding(
            o: JsonObject,
            scalar: Int,
            label: String,
        ) {
            (o["formula"] as? JsonObject)?.let { b ->
                val id = idOf(b["id"], label)
                val text = str(b, "text") ?: malformed(label, "a formula needs a text")
                parts.add(Part(id, "f$id ($label)", o, false, null, emptyList(), emptyList(), listOf("f$scalar"), exprNames(text), emptyList(), { r -> listOf("bind", DocumentFormat.quote(r.scalarName(scalar)), "=", DocumentFormat.quote(text)) }))
            }
            (o["wire"] as? JsonObject)?.let { b ->
                val id = idOf(b["id"], label)
                val to = idOf(b["to"], label)
                parts.add(Part(id, "f$id ($label)", o, false, null, emptyList(), emptyList(), listOf("f$scalar", "f$to"), emptyList(), emptyList(), { r -> listOf("wire", DocumentFormat.quote(r.scalarName(scalar)), "=", DocumentFormat.quote(r.scalarName(to))) }))
            }
        }

        fun spaceOf(
            o: JsonObject,
            label: String,
        ): Int? = o["space"]?.takeIf { it !is JsonNull }?.let { idOf(it, label) }

        fun feature(o: JsonObject) {
            val id = idOf(o["id"], "features")
            val kind = str(o, "kind") ?: malformed("f$id", "a feature needs a kind")
            val label = "f$id ($kind${str(o, "tool")?.let { " $it" } ?: ""})"
            val space = spaceOf(o, label)
            when (kind) {
                "path" -> path(o, id, space)
                "group" -> group(o, id, label)
                "bind", "wire" -> {
                    val scalar = idOf(o["scalar"], label)
                    if (kind == "bind") {
                        val text = str(o, "formula") ?: malformed(label, "a formula needs a text")
                        parts.add(Part(id, label, o, false, null, emptyList(), emptyList(), listOf("f$scalar"), exprNames(text), emptyList(), { r -> listOf("bind", DocumentFormat.quote(r.scalarName(scalar)), "=", DocumentFormat.quote(text)) }))
                    } else {
                        val to = idOf(o["to"], label)
                        parts.add(Part(id, label, o, false, null, emptyList(), emptyList(), listOf("f$scalar", "f$to"), emptyList(), emptyList(), { r -> listOf("wire", DocumentFormat.quote(r.scalarName(scalar)), "=", DocumentFormat.quote(r.scalarName(to))) }))
                    }
                }
                "wall" -> {
                    val pathId = idOf(o["path"], label)
                    wallPath[id] = pathId
                    val schema = SCHEMA.getValue("wall")
                    parts.add(
                        Part(
                            id, label, o, true, space, strings(o["creates"]), strings(o["scalars"]).map { idOf(JsonPrimitive(it), label) },
                            refsOf(o, schema, label) + "f$pathId", emptyList(), emptyList(),
                            words = { r -> r.wordsOf("wall", o, schema) },
                            before = { r -> r.doc.currentOrthoPath = r.pathById[pathId] },
                        ),
                    )
                }
                else -> {
                    val schema = SCHEMA[kind] ?: malformed(label, "'$kind' is no kind of feature this build knows")
                    val names = schema.filter { it.expr }.flatMap { f -> exprTexts(f, o[f.json]) }.flatMap { exprNames(it) }
                    val tips = (o["tips"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
                    val runs =
                        (o["runs"] as? JsonArray)?.map { r ->
                            (r as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: malformed(label, "a run is a list of edge addresses") }
                        } ?: emptyList()
                    parts.add(
                        Part(
                            id, label, o, true, space, strings(o["creates"]), strings(o["scalars"]).map { idOf(JsonPrimitive(it), label) },
                            refsOf(o, schema, label) + tips.filterNotNull(), names, emptyList(),
                            words = { r -> r.wordsOf(kind, o, schema) },
                            tips = tips,
                            runs = runs,
                        ),
                    )
                }
            }
        }

        /**
         * A path's parts: its start, every vertex added to it, its close or discard — each under its own
         * identity, each after the one before it, and each with the path made current first (the replay
         * engine's "current path" is a fact the file states rather than one it leaves to the order).
         */
        fun path(
            o: JsonObject,
            id: Int,
            space: Int?,
        ) {
            val label = "f$id (path)"
            val at = o["at"] ?: malformed(label, "a path needs a start")
            parts.add(Part(id, label, o, true, space, strings(o["creates"]), emptyList(), emptyList(), emptyList(), emptyList(), { r -> listOf("orthostart", r.posWord(at)) }))
            val own = pathParts.getOrPut(id) { ArrayList() }
            own.add(id)
            var prev = id
            for (v in o["vertices"] as? JsonArray ?: JsonArray(emptyList())) {
                val vo = v as? JsonObject ?: malformed(label, "a vertex is not an object")
                val vid = idOf(vo["id"], label)
                val vat = vo["at"] ?: malformed(label, "a vertex needs a position")
                val word = if ((vo["prepend"] as? JsonPrimitive)?.booleanOrNull == true) "orthoprepend" else "orthovertex"
                parts.add(
                    Part(
                        vid, "f$vid ($label)", o, true, if (vo.containsKey("space")) spaceOf(vo, label) else space, strings(vo["creates"]),
                        emptyList(), emptyList(), emptyList(), listOf(prev),
                        words = { r -> listOf(word, r.posWord(vat)) },
                        before = { r -> r.doc.currentOrthoPath = r.pathById[id] },
                    ),
                )
                prev = vid
                own.add(vid)
            }
            for (end in listOf("close", "discard")) {
                val eo = o[end] as? JsonObject ?: continue
                val eid = idOf(eo["id"], label)
                val word = if (end == "close") "orthoclose" else "orthodiscard"
                parts.add(
                    Part(
                        eid, "f$eid ($label $end)", o, true, space, strings(eo["creates"]), emptyList(), emptyList(), emptyList(), listOf(prev),
                        words = { listOf(word) },
                        before = { r -> r.doc.currentOrthoPath = r.pathById[id] },
                    ),
                )
            }
        }

        fun group(
            o: JsonObject,
            id: Int,
            label: String,
        ) {
            val name = str(o, "name") ?: malformed(label, "a group needs a name")
            val members = strings(o["members"])
            parts.add(Part(id, label, o, false, null, emptyList(), emptyList(), members, emptyList(), emptyList(), { listOf("group", DocumentFormat.quote(name), "els=" + members.joinToString(",")) }))
            val place = o["place"] as? JsonObject ?: return
            val pid = idOf(place["id"], label)
            val words = ArrayList<(Reader) -> String>()
            place["at"]?.let { at -> words.add { r -> "at=" + r.posWord(at) } }
            place["angle"]?.let { a -> words.add { r -> "angle=" + r.quantityWord(a) } }
            parts.add(
                Part(
                    pid, "f$pid ($label place)", o, true, spaceOf(place, label), emptyList(), emptyList(), emptyList(), emptyList(), listOf(id),
                    words = { r -> listOf("place", DocumentFormat.quote(name)) + words.map { it(r) } },
                ),
            )
        }

        /** The identities a schema-written feature reads. */
        fun refsOf(
            o: JsonObject,
            schema: List<Field>,
            label: String,
        ): List<String> {
            val out = ArrayList<String>()
            o["space"]?.let { out.add((it as JsonPrimitive).content) }
            for (f in schema) {
                val v = o[f.json] ?: continue
                when (f.type) {
                    T.EL, T.SC, T.PATTERN, T.SPACE -> out.add((v as? JsonPrimitive)?.content ?: malformed(label, "'${f.json}' is not an identity"))
                    T.ELS, T.SCS -> out.addAll(strings(v))
                    T.REFS ->
                        for (item in v as? JsonArray ?: malformed(label, "'${f.json}' is not a list")) {
                            out.add(((item as? JsonObject)?.get("el") ?: item).let { (it as? JsonPrimitive)?.content ?: "?" })
                        }
                    T.TOOL -> o["macro"]?.let { out.add((it as JsonPrimitive).content) }
                    else -> {}
                }
            }
            return out
        }

        /** The texts of an expression field — a plain number end of a domain is no text. */
        fun exprTexts(
            f: Field,
            v: JsonElement?,
        ): List<String> {
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            // a family's laws: `name = formula; …`, and both sides are references
            if (f.json == "laws") return s.split(';').flatMap { it.split('=') }.map { it.trim() }.filter { it.isNotEmpty() }
            return listOf(s)
        }

        // ---- words: a feature handed to the replay engine as the row it would have been ----

        fun scalarName(id: Int): String = scalarById[id]?.name ?: paramName[id] ?: "?f$id"

        fun content(v: JsonElement): String = (v as? JsonPrimitive)?.content ?: throw DocumentFormat.LoadError("not a value: $v")

        fun posWord(v: JsonElement): String {
            val a = v as? JsonArray ?: throw DocumentFormat.LoadError("a position is two numbers: $v")
            if (a.size != 2) throw DocumentFormat.LoadError("a position is two numbers: $v")
            return content(a[0]) + "," + content(a[1])
        }

        fun quantityWord(v: JsonElement): String =
            when (v) {
                is JsonObject -> {
                    val (unit, n) = v.entries.singleOrNull() ?: throw DocumentFormat.LoadError("a quantity has one unit: $v")
                    if (unit != "mm" && unit != "deg") throw DocumentFormat.LoadError("unknown unit '$unit'")
                    content(n) + unit
                }
                else -> content(v)
            }

        fun word(
            f: Field,
            v: JsonElement,
            o: JsonObject,
        ): String? =
            when (f.type) {
                T.EL, T.TEXT, T.INT -> content(v)
                T.ELS -> strings(v).joinToString(",")
                T.REFS ->
                    (v as JsonArray).joinToString(",") { item ->
                        if (item is JsonObject) {
                            content(item["el"]!!) + (item["at"] as? JsonArray).orEmpty().joinToString("") { "@" + content(it) }
                        } else {
                            content(item)
                        }
                    }
                T.POS -> posWord(v)
                T.POSITIONS -> (v as JsonArray).joinToString(";") { posWord(it) }
                T.NUM -> quantityWord(v)
                T.NUMS -> (v as JsonArray).joinToString(";") { quantityWord(it) }
                T.INTS -> (v as JsonArray).joinToString(";") { content(it) }
                T.LABEL -> DocumentFormat.quote(content(v))
                T.SC -> DocumentFormat.quote(scalarName(content(v).removePrefix("f").toIntOrNull() ?: -1))
                T.SCS -> strings(v).joinToString(",") { DocumentFormat.quote(scalarName(it.removePrefix("f").toIntOrNull() ?: -1)) }
                T.SLOTS -> slotsWord(v)
                T.FLAG -> if ((v as? JsonPrimitive)?.booleanOrNull == true) "1" else null
                T.NUM_OR_LABEL -> if ((v as? JsonPrimitive)?.isString == true) DocumentFormat.quote(content(v)) else quantityWord(v)
                T.TOOL ->
                    content(v).let { id ->
                        if (id == "macro") {
                            MACRO_TOOL_PREFIX + (macroName[content(o["macro"] ?: JsonNull).removePrefix("f").toIntOrNull() ?: -1] ?: "?")
                        } else {
                            id
                        }
                    }
                T.PATTERN -> DocumentFormat.quote(patternName[content(v).removePrefix("f").toIntOrNull() ?: -1] ?: "?")
                T.SPACE -> DocumentFormat.quote(spaceName[content(v).removePrefix("f").toIntOrNull() ?: -1] ?: "?")
            }

        fun slotsWord(v: JsonElement): String {
            val edges = HashMap<Int, MutableList<CornerSlot>>()
            val faces = HashMap<Int, MutableList<CornerSlot>>()
            for (item in v as JsonArray) {
                val o = item as JsonObject
                val k = content(o["entry"]!!).toInt()
                val kind = CornerSlotKind.valueOf(content(o["kind"]!!).uppercase())
                val slot = CornerSlot(kind, strings(o["edges"]).map { it.toInt() }, content(o["piece"]!!).toInt())
                (if (kind == CornerSlotKind.PATCH) faces else edges).getOrPut(k) { ArrayList() }.add(slot)
            }
            val n = ((edges.keys + faces.keys).maxOrNull() ?: -1) + 1
            return DocumentFormat.encodeSlots(CornerSlots((0 until n).map { edges[it] ?: emptyList() }, (0 until n).map { faces[it] ?: emptyList() }))
        }

        fun wordsOf(
            kind: String,
            o: JsonObject,
            schema: List<Field>,
        ): List<String> {
            val out = arrayListOf(kind)
            for (f in schema) {
                var v = o[f.json] ?: continue
                // the one literal a re-stamp rewrites (OP-23): the count of the pattern being re-stamped, or — one
                // level in — the count of the ride whose own feature this is (#18)
                if (f.json == "count" && restamp != null && isTarget(kind, o)) v = JsonPrimitive(restamp.count)
                val w = word(f, v, o) ?: continue
                out.add(if (f.key == null) w else "${f.key}=$w")
            }
            return out
        }

        fun isTarget(
            kind: String,
            o: JsonObject,
        ): Boolean {
            val r = restamp ?: return false
            return if (r.step >= 0) {
                idOf(o["id"], kind) == r.step
            } else {
                kind == "pattern" && str(o, "name") == r.pattern
            }
        }

        // ---- the order ----

        /**
         * The parts in the order the references decide (OP-32): a part after every part that creates something
         * it reads, and — where nothing decides — by identity, which is the order the features were made in. A
         * reference nothing creates and a cycle are load errors that name the feature.
         */
        fun sorted(): List<Part> {
            val byId = HashMap<Int, Part>()
            for (u in parts) {
                val other = byId[u.id]
                if (other != null) throw DocumentFormat.LoadError(Messages.refusalFileDuplicate(ref = "f${u.id}", feature = u.label, other = other.label))
                byId[u.id] = u
            }
            // who creates each identity: the elements a part declares, the scalars, and the part itself
            val creator = HashMap<String, Int>()
            for (u in parts) creator["f${u.id}"] = u.id
            for (u in parts) {
                for (ref in u.declared + u.declaredScalars.map { "f$it" }) {
                    creator[ref]?.let { if (it != u.id) throw DocumentFormat.LoadError(Messages.refusalFileDuplicate(ref = ref, feature = u.label, other = byId.getValue(it).label)) }
                    creator[ref] = u.id
                }
            }
            // a wall follows the last vertex its path had when it was built: the vertices before it by identity
            for ((wall, path) in wallPath) pathParts[path]?.filter { it < wall }?.maxOrNull()?.let { byId[wall]?.later?.add(it) }
            // the names an expression reads, resolved to the part that gives them: a parameter, or an element
            val nameGiver = HashMap<String, Int>()
            for ((id, n) in paramName) nameGiver[n] = id
            for ((e, n) in userNames) creator[e]?.let { nameGiver[n] = it }
            for (u in parts) {
                if (u.placed && u.space != null && u.space !in spaceName) {
                    throw DocumentFormat.LoadError(Messages.refusalFileMalformed(feature = u.label, why = "f${u.space} is no sketch space"))
                }
            }
            val deps = HashMap<Int, MutableSet<Int>>()
            for (u in parts) {
                val d = HashSet<Int>()
                for (r in u.refs) {
                    val c = creator[r] ?: throw DocumentFormat.LoadError(Messages.refusalFileDangling(feature = u.label, ref = r))
                    if (c != u.id) d.add(c)
                }
                for (n in u.names) nameGiver[n.substringBefore('.')]?.let { if (it != u.id) d.add(it) }
                d.addAll(u.after)
                d.addAll(u.later)
                if (u.placed && u.space != null) creator["f${u.space}"]?.let { if (it != u.id) d.add(it) }
                deps[u.id] = d
            }
            // Kahn's walk, the lowest identity first among the ready ones
            val waiting = HashMap<Int, Int>()
            val dependents = HashMap<Int, MutableList<Int>>()
            for ((id, d) in deps) {
                waiting[id] = d.size
                for (x in d) dependents.getOrPut(x) { ArrayList() }.add(id)
            }
            val ready = ArrayList<Int>()

            fun insert(id: Int) {
                val at = ready.binarySearch(id).let { if (it < 0) -it - 1 else it }
                ready.add(at, id)
            }
            for ((id, n) in waiting) if (n == 0) insert(id)
            val out = ArrayList<Part>(parts.size)
            while (ready.isNotEmpty()) {
                val id = ready.removeAt(0)
                out.add(byId.getValue(id))
                for (x in dependents[id].orEmpty()) {
                    val n = waiting.getValue(x) - 1
                    waiting[x] = n
                    if (n == 0) insert(x)
                }
            }
            if (out.size < parts.size) {
                val left = parts.filter { waiting.getValue(it.id) > 0 }.map { it.id }.toHashSet()
                // follow the dependencies inside what is left until one repeats: that is the cycle
                var at = left.minOrNull()!!
                val seen = ArrayList<Int>()
                while (at !in seen) {
                    seen.add(at)
                    at = deps.getValue(at).first { it in left }
                }
                val cycle = seen.drop(seen.indexOf(at)) + at
                throw DocumentFormat.LoadError(
                    Messages.refusalFileCycle(feature = byId.getValue(at).label, chain = cycle.joinToString(" → ") { byId.getValue(it).label }),
                )
            }
            return out
        }

        // ---- the replay ----

        fun replay(u: Part) {
            if (u.placed) {
                val want = u.space?.let { spaceName[it] } ?: Document.PLAN_SPACE
                if (doc.activeSpace.name != want) {
                    if (!doc.switchSpace(want, record = true)) {
                        if (restamp != null) {
                            restamp.notes.add("dropped ${u.label} — its sketch space is gone")
                            return
                        }
                        throw DocumentFormat.LoadError(Messages.refusalFileInFeature(feature = u.label, why = "unknown sketch space '$want'"))
                    }
                }
            }
            u.before(this)
            // the decisions the feature states, read back by its build instead of being taken again (OP-32)
            doc.tape = Document.Tape(u.tips.map { t -> t?.let { byName[it] } }, u.runs)
            val words =
                try {
                    u.words(this)
                } catch (e: DocumentFormat.LoadError) {
                    throw DocumentFormat.LoadError(Messages.refusalFileInFeature(feature = u.label, why = e.message ?: ""))
                } catch (e: RuntimeException) {
                    throw DocumentFormat.LoadError(Messages.refusalFileMalformed(feature = u.label, why = e.message ?: e.toString()))
                }
            // the identities this part was given are handed out again in the same order (OP-32), so a note the
            // replay writes names what the file names
            doc.featureCounter = u.id - 1
            u.declared.firstOrNull()?.removePrefix("e")?.toIntOrNull()?.let { doc.elementCounter = it - 1 }
            val journalBefore = doc.journal.size
            val created =
                DocumentFormat.runRow(
                    doc,
                    words,
                    u.declared.size,
                    byName,
                    restamp,
                    where = { why -> Messages.refusalFileInFeature(feature = u.label, why = why) },
                    countError = { n -> Messages.refusalFileCount(feature = u.label, created = n, declared = u.declared.size) },
                ).also { doc.tape = null } ?: return
            val step = doc.journal.drop(journalBefore).lastOrNull { it.kind !in DocumentFormat.UNNUMBERED }
            step?.fid = u.id
            if (step?.kind == "orthostart") step.path?.let { pathById[u.id] = it }
            // positional, so an identity keeps meaning the same *copy*: what a bigger count adds takes a fresh
            // identity at the end, and what a smaller one removes leaves its identity unmapped for the drop rule
            val elastic = restamp?.resized == true
            val shift = if (elastic && restamp?.alignFromEnd == true) created.size - u.declared.size else 0
            created.forEach { it.serial = 0 }
            u.declared.forEachIndexed { i, n ->
                created.getOrNull(i + shift)?.let { el ->
                    el.serial = n.removePrefix("e").toIntOrNull() ?: 0
                    byName[n] = el
                    if (n in hidden) el.visible = false
                    userNames[n]?.let { doc.nameElement(el, it) }
                    materials[n]?.let { m -> doc.setMaterial(el, appearance(m)) }
                }
            }
            val scalars = step?.createsScalars.orEmpty()
            scalars.forEach { it.serial = 0 }
            u.declaredScalars.forEachIndexed { i, sid ->
                scalars.getOrNull(i)?.let {
                    it.serial = sid
                    scalarById[sid] = it
                }
            }
        }

        fun appearance(m: JsonObject): Appearance =
            Appearance(
                color = (m["color"] as? JsonPrimitive)?.content ?: Appearance.DEFAULT_COLOR,
                roughness = (m["rough"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Appearance.DEFAULT_ROUGHNESS,
                metallic = (m["metal"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: Appearance.DEFAULT_METALLIC,
            )

        /**
         * The counters the file states, and an identity for whatever the replay made that the file could not
         * name — the members a bigger re-stamp count adds (OP-23). Never a number the file already used.
         */
        fun finishIdentities() {
            val next = root["nextId"] as? JsonObject
            var e = (next?.get("e") as? JsonPrimitive)?.intOrNull?.minus(1) ?: 0
            var f = (next?.get("f") as? JsonPrimitive)?.intOrNull?.minus(1) ?: 0
            for (s in doc.journal) {
                e = maxOf(e, s.creates.maxOfOrNull { it.serial } ?: 0)
                f = maxOf(f, s.fid, s.createsScalars.maxOfOrNull { it.serial } ?: 0)
            }
            for (s in doc.journal) {
                for (el in s.creates) if (el.serial == 0) el.serial = ++e
                if (s.fid == 0 && s.kind !in DocumentFormat.UNNUMBERED) s.fid = ++f
                for (sc in s.createsScalars) if (sc.serial == 0) sc.serial = if (s.kind == "param") s.fid else ++f
            }
            doc.elementCounter = e
            doc.featureCounter = f
        }
    }

    /** The names a typed expression reads — what orders it after the parameter or element that carries them. */
    private fun exprNames(text: String): List<String> =
        try {
            ExprParser.parse(text).refNames()
        } catch (e: Exception) {
            emptyList()
        }
}
