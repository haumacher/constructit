package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.FrameValue
import constructit.core.ScalarValue
import constructit.core.SourceNode
import constructit.dsl.PointRef
import constructit.geom.Justification
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/** An argument of a recorded construction [Step]. */
sealed interface Arg {
    class El(val el: Element) : Arg

    class Sc(val entry: ScalarEntry) : Arg

    /**
     * Several scalars in one argument, in the order the tool consumes them (x, then y). A list of one
     * encodes exactly as [Sc] does, so the step text of every single-scalar tool is unchanged by this.
     */
    class Scs(val entries: List<ScalarEntry>) : Arg

    class Pos(val p: Vec2) : Arg

    class Num(val q: Quantity) : Arg

    class Text(val s: String) : Arg

    /** A quoted user-given name (a group's), so it stays one word and cannot read as a keyword. */
    class Label(val s: String) : Arg

    class Els(val els: List<Element>) : Arg

    class Positions(val ps: List<Vec2>) : Arg

    /** Several numbers in one argument — a tool's own degrees of freedom, in the order it created them. */
    class Nums(val qs: List<Quantity>) : Arg

    /** `key=value`, so a step with several optional parts stays readable. */
    class Keyed(val key: String, val value: Arg) : Arg
}

/**
 * One recorded construction step, and the elements it created (in order).
 *
 * Recording *steps* rather than nodes is what makes the whole synthetic layer — handles, styles, path
 * and thick-path bookkeeping — free: replaying a step runs the same code that built it, so all of that
 * is reconstructed rather than stored.
 */
class Step(val kind: String, val args: List<Arg>) {
    val creates = ArrayList<Element>()

    /** Scalars this step introduced (a parameter, a measurement) — a dependency unit for delete too. */
    val createsScalars = ArrayList<ScalarEntry>()
}

/**
 * Reads and writes a document as a **construction script**: the drawing *is* a construction (OP-5),
 * so the file is the sequence of steps that built it, and loading replays them.
 *
 * What is deliberately *not* in the file:
 * - **Nodes.** A step rebuilds its own sub-graph, so no op needs a name or a rebuild path.
 * - **Handles, styles, path and thick-path structure.** All synthetic: created by the same methods that
 *   create the geometry, hence recreated by replay.
 * - **A separate values section.** Instead, a step's positional literals are written as the *current*
 *   value of what that step introduced, so the script always describes the drawing as it is now. That
 *   keeps the file purely a construction, with no naming scheme for internal nodes leaking into it.
 *
 * Elements are referred to by script-local names (`e1`, `e2`, …) declared by the step that creates
 * them, so the format does not depend on runtime id generation. A count mismatch on load is an error
 * rather than a silently different drawing.
 */
object DocumentFormat {
    const val HEADER = "constructit 1"

    // ---- writing ----

    fun save(doc: Document): String {
        val ev = Evaluator()
        val present = doc.elements.toHashSet()
        val names = HashMap<String, String>() // element id -> script name
        val out = StringBuilder(HEADER).append('\n')
        for ((index, step) in doc.journal.withIndex()) {
            val args = restate(doc, step, index, ev, present, names).joinToString(" ") { encode(it, names) }
            val created = step.creates.map { el -> "e${names.size + 1}".also { names[el.id] = it } }
            out.append(step.kind)
            if (args.isNotEmpty()) out.append(' ').append(args)
            if (created.isNotEmpty()) out.append(" -> ").append(created.joinToString(","))
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * A step's literals as they are *now*, not as they were typed.
     *
     * A drag or a typed value changes a source node that some step introduced, so re-reading the live
     * value here is what lets the script carry current state without a separate values section. Only
     * literals that are state are replaced; a literal that encodes a *choice* — which side of a line,
     * which intersection branch — is kept verbatim, since replay must make the same choice.
     */
    private fun restate(
        doc: Document,
        step: Step,
        stepIndex: Int,
        ev: Evaluator,
        present: Set<Element>,
        names: Map<String, String>,
    ): List<Arg> {
        // where this element is *as this step replays*: its world position, or — for a vertex of a path a
        // later `place` step captures — the position it has before that capture (OP-16, see
        // [Document.restatedPosition]). One rule for points and path vertices alike.
        fun posOf(el: Element): Vec2? = doc.restatedPosition(el, stepIndex, ev)
        // A step whose creations have since been *removed* — by a join collapsing a jog — keeps its
        // recorded literals. Re-reading a deleted vertex would describe the state after the edit that
        // deleted it, whereas replay has to rebuild the geometry that existed before, so the later join
        // has something to collapse. What survives the join is described by the steps that still own it.
        if (step.creates.any { it !in present }) return step.args
        return when (step.kind) {
            // membership is state (OP-16): a member whose creating step is gone is simply not written,
            // so a delete leaves a consistent group and one whose members are all gone leaves no step
            // at all — [Document.dependentSteps] drops it. Naming, not presence, is the test: a script
            // can only refer to what an earlier step declared.
            // hide/show follow the same rule for the same reason (OP-18's visibility reversal — see
            // [Document.setElementsVisible]): the step states which elements are hidden *now*, so a member
            // the script no longer declares is simply not written, and a step left with none is dropped by
            // [Document.dependentSteps] before it can be
            "group", "hide", "show" ->
                step.args.map { arg ->
                    val els = (arg as? Arg.Keyed)?.value as? Arg.Els
                    if (arg is Arg.Keyed && els != null) Arg.Keyed(arg.key, Arg.Els(els.els.filter { it.id in names })) else arg
                }
            // a placement's frame is state (OP-16 step 2): the origin and angle are re-read from the frame
            // source, so a dragged or typed group comes back where it now is. The members' own steps are
            // replayed *before* this one retrofits them, and each restates the position that retrofit
            // expects — a free point's world position, a captured path vertex's pre-capture one — so the
            // script needs no local coordinates in it at all (see [Document.restatedPosition]).
            "place" -> {
                val g = doc.groups.firstOrNull { it.name == (step.args.firstOrNull() as? Arg.Label)?.s }
                val f = g?.frameNode?.let { (ev.eval(it) as? EvalResult.Ok)?.value as? FrameValue }
                if (f == null) {
                    step.args
                } else {
                    listOf(step.args[0], Arg.Keyed("at", Arg.Pos(f.origin)), Arg.Keyed("angle", Arg.Num(Quantity.rad(f.angle))))
                }
            }
            "param" -> {
                val e = (step.args[0] as Arg.Sc).entry
                listOf(step.args[0], Arg.Text("="), Arg.Num(value(e, ev)))
            }
            // a rider's position along its host is **state**: it is dragged, typed, and compensated while the
            // host turns (OP-20). What the step restates is therefore the rider's own parameter (`dofs=`,
            // the same seam a dimension's placement uses) and not the click, since *which* curve and which
            // side of it are choices replay must repeat — see [Document.pointOnCurve].
            // Asked of the document rather than read off the handle, because a rider's parameter and what its
            // *handle* writes are two different nodes once the position has been re-anchored to a base of the
            // same carrier (OP-4 case b): the handle then writes the offset, while the step must restate the
            // position along the carrier that replaying it reproduces — see [Document.restatedRiderParam].
            "pointoncurve" -> {
                val q = step.creates.singleOrNull()?.let { doc.restatedRiderParam(it, ev) }
                if (q == null) step.args else step.args + Arg.Keyed("dofs", Arg.Nums(listOf(q)))
            }
            // the same rule for a re-parameterization recorded on its own rather than through a tool
            // (OP-4 case b): the offset is state, so it is restated — one distance and one angle for a polar
            // offset, one signed distance for a rider measured along its carrier
            "relative" -> {
                val dofs = relativeDofs(doc, step, ev)
                if (dofs.isEmpty()) step.args else step.args + Arg.Keyed("dofs", Arg.Nums(dofs))
            }
            "point", "orthostart", "orthovertex", "orthoprepend" ->
                step.creates.firstOrNull()?.let { posOf(it) }?.let { listOf(Arg.Pos(it)) } ?: step.args
            // both of a break's positions are state: where the leg was split, and how far the jog has
            // since been pulled open
            "orthobreak" -> {
                val m = step.creates.getOrNull(0)?.let { posOf(it) }
                val n = step.creates.getOrNull(1)?.let { posOf(it) }
                if (m == null || n == null) step.args else listOf(step.args[0], Arg.Pos(m), Arg.Pos(n))
            }
            // an arc break's split **angle** is state (the rider slides round the carrier), so it is restated
            // from the rider the step created; the arc it names and the `ccw` it stored are choices, kept
            // verbatim — see [Document.breakArc]
            "breakarc" -> {
                val q = step.creates.firstOrNull()?.let { doc.restatedRiderParam(it, ev) }
                if (q == null) step.args else listOf(step.args[0], Arg.Num(q), step.args[2])
            }
            // an interval feature's position and carried heights live in the parameters the step created,
            // in that order (see [Document.addInterval]). They are state, so a value typed in the panel
            // comes back on reload — which is the whole point of recording the interval as a description
            // rather than as the click that placed it (OP-21).
            //
            // Matched to the keys **positionally**, not by the parameters' names: a panel name is the
            // user's (OP-7) and a renamed `pos` must not quietly stop restating its position. The `width=`
            // key names a scalar the step did not create, so it is written by name as usual.
            "opening" -> {
                val created = listOf("pos", "sill", "head").zip(step.createsScalars).toMap()
                step.args.map { arg ->
                    val own = if (arg is Arg.Keyed) created[arg.key] else null
                    if (arg is Arg.Keyed && own != null) Arg.Keyed(arg.key, Arg.Num(value(own, ev))) else arg
                }
            }
            "tool" -> {
                // **State is restated as a value, never as a rewritten click** — one rule for every tool, and
                // the clicks always stay verbatim because what a click encodes is a *choice* (which curve,
                // which side, which sector) that replay must repeat (see this object's header).
                //
                // Three kinds of state ride the one `dofs=` argument, and a step owns exactly one of them:
                // - a **rider's own parameter** (`Document.restatedRiderParam`), for the point-on-line and
                //   point-on-circle tools. This used to be restated by *rewriting the last click* to the
                //   rider's current position, which is wrong twice over: replay re-projects that position onto
                //   the geometry as it stands **before** the placement that turns the group (so a turned figure
                //   came back with its rider somewhere else, and `save → load → save` was not byte-equal), and
                //   the position of a rider that sits at a turned origin prints as `-6.12E-16`. The
                //   `pointoncurve` step has restated its parameter since session 9; this is the same rule,
                //   without the special case.
                // - a **dimension's placement** (OP-13), which is dragged and typed.
                // - a **re-parameterization's offset** (OP-4 case b) — for a tool that creates nothing at all.
                val riderDof = step.creates.singleOrNull { it.kind == ElementKind.ON_CURVE }?.let { doc.restatedRiderParam(it, ev) }
                val dofs =
                    listOfNotNull(riderDof) +
                        step.creates.mapNotNull { it.annotation }.flatMap { it.dofValues() } +
                        relativeDofs(doc, step, ev)
                if (dofs.isEmpty()) step.args else step.args + Arg.Keyed("dofs", Arg.Nums(dofs))
            }
            else -> step.args
        }
    }

    private fun value(
        e: ScalarEntry,
        ev: Evaluator,
    ): Quantity = ((ev.eval(e.ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: Quantity.mm(0.0)

    /**
     * The re-parameterization state a `relative` / `tool makerel` step restates: the offsets of the elements
     * **that step itself** re-anchored (OP-4 case b).
     *
     * Deliberately ownership rather than reference. A step that merely *uses* a relative point — a circle
     * through it — would otherwise carry that point's distance and angle as its own `dofs=`, and in a chain
     * (a rider measured from a base that is itself measured from something) the base's offset would join the
     * list replay consumes positionally.
     */
    private fun relativeDofs(
        doc: Document,
        step: Step,
        ev: Evaluator,
    ): List<Quantity> = doc.reparamDofs(step, ev)

    private fun scalarValue(
        node: SourceNode,
        ev: Evaluator,
    ): Quantity? = ((ev.eval(node) as? EvalResult.Ok)?.value as? ScalarValue)?.q

    private fun encode(
        arg: Arg,
        names: Map<String, String>,
    ): String =
        when (arg) {
            is Arg.El -> names[arg.el.id] ?: "?${arg.el.id}"
            is Arg.Els -> arg.els.joinToString(",") { names[it.id] ?: "?${it.id}" }
            is Arg.Sc -> quote(arg.entry.name)
            is Arg.Scs -> arg.entries.joinToString(",") { quote(it.name) }
            is Arg.Pos -> pos(arg.p)
            is Arg.Positions -> arg.ps.joinToString(";") { pos(it) }
            is Arg.Nums -> arg.qs.joinToString(";") { num(it) }
            is Arg.Num -> num(arg.q)
            is Arg.Text -> arg.s
            is Arg.Label -> quote(arg.s)
            is Arg.Keyed -> "${arg.key}=" + encode(arg.value, names)
        }

    private fun pos(p: Vec2) = "${trim(p.x)},${trim(p.y)}"

    private fun num(q: Quantity): String =
        when (q.dim) {
            Dimension.LENGTH -> "${trim(q.mm)}mm"
            Dimension.ANGLE -> "${trim(q.deg)}deg"
            else -> trim(q.value)
        }

    /**
     * **The one canonical number format of the file**: a plain decimal that reloads to the *same double*,
     * with no trailing noise and — the part that had to be added — **never in scientific notation**.
     *
     * `Double.toString` gives the shortest representation that round-trips, which is why it is the basis; but
     * for a very small or very large magnitude it gives `-6.123233995736766E-16`, and a script full of `E-16`
     * is not a canonical serialization (the rule the SVG goldens follow for the same reason). A turned placed
     * group produces exactly such a value — a coordinate that is zero up to the rounding of `sin 90°` — so this
     * stopped being cosmetic.
     *
     * The exponent is therefore expanded by **moving the decimal point in the digits themselves**: a pure
     * string transformation, so not one bit of the value is lost and the reloaded double is bit-identical.
     * Deliberately not fixed-precision rounding, which would be stable but would quietly *move* the drawing on
     * every reload.
     */
    private fun trim(v: Double): String {
        val r = (if (v == 0.0) 0.0 else v).toString() // normalise -0.0, which reads as noise
        val plain = if (r.contains('E') || r.contains('e')) expand(r) else r
        return if (plain.endsWith(".0")) plain.dropLast(2) else plain
    }

    /** `-6.12E-16` -> `-0.000000000000000612`: the same digits, the point moved by the exponent. */
    private fun expand(s: String): String {
        val sign = if (s.startsWith("-")) "-" else ""
        val body = s.removePrefix("-").removePrefix("+")
        val e = body.indexOfFirst { it == 'E' || it == 'e' }
        val exp = body.substring(e + 1).removePrefix("+").toInt()
        val mantissa = body.substring(0, e)
        val dot = mantissa.indexOf('.')
        val digits = if (dot < 0) mantissa else mantissa.removeRange(dot, dot + 1)
        // where the point sits in [digits] after applying the exponent
        val point = (if (dot < 0) mantissa.length else dot) + exp
        val out =
            when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length)
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }
        return sign + out.trimEnd('.')
    }

    private fun quote(s: String) = "\"" + s.replace("\"", "'") + "\""

    // ---- reading ----

    class LoadError(message: String) : Exception(message)

    fun load(text: String): Document {
        val doc = Document()
        replay(doc, text)
        return doc
    }

    /** Replay [text] into [doc]; throws [LoadError] on a malformed or inconsistent script. */
    fun replay(
        doc: Document,
        text: String,
    ) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val head = lines.firstOrNull() ?: throw LoadError("empty document")
        if (head != HEADER) throw LoadError("unsupported format: '$head' (expected '$HEADER')")
        val byName = HashMap<String, Element>()
        for ((lineNo, line) in lines.drop(1).withIndex()) {
            val (body, declared) = split(line)
            val words = body.split(' ').filter { it.isNotEmpty() }
            val before = doc.elements.toHashSet()
            try {
                apply(doc, words, byName)
            } catch (e: LoadError) {
                throw LoadError("line ${lineNo + 2}: ${e.message} in '$line'")
            } catch (e: Exception) {
                throw LoadError("line ${lineNo + 2}: ${e.message ?: e.toString()} in '$line'")
            }
            val created = doc.elements.filter { it !in before }
            if (created.size != declared.size) {
                throw LoadError(
                    "line ${lineNo + 2}: '${words.firstOrNull()}' created ${created.size} element(s) " +
                        "but the script declares ${declared.size} (${declared.joinToString(",")}) — the file was " +
                        "written by a different version",
                )
            }
            declared.forEachIndexed { i, n -> byName[n] = created[i] }
        }
    }

    private fun split(line: String): Pair<String, List<String>> {
        val i = line.indexOf("->")
        if (i < 0) return line to emptyList()
        return line.substring(0, i).trim() to line.substring(i + 2).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun apply(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        fun el(i: Int): Element = byName[words[i]] ?: throw LoadError("unknown element '${words.getOrNull(i)}'")

        fun scalar(i: Int): ScalarEntry {
            val name = unquote(words.getOrElse(i) { throw LoadError("missing scalar name") })
            return doc.scalars.firstOrNull { it.name == name } ?: throw LoadError("unknown scalar '$name'")
        }

        when (val kind = words.firstOrNull() ?: throw LoadError("empty step")) {
            "param" -> doc.newParameter(unquote(words[1]), quantity(words[3]))
            "point" -> parsePos(words[1]).let { doc.freePoint(it.x.let(Quantity::mm), it.y.let(Quantity::mm)) }
            "orthostart" -> doc.startOrthoPath(parsePos(words[1]))
            "orthovertex" -> doc.addOrthoVertex(currentPath(doc), parsePos(words[1]))
            "orthoprepend" -> doc.prependOrthoVertex(currentPath(doc), parsePos(words[1]))
            "orthoresume" -> {
                val (path, atEnd) = doc.resumableEnd(el(1)) ?: throw LoadError("'${words[1]}' is not an open path end")
                doc.resumeOrthoPath(path, atEnd)
            }
            "orthoclose" -> doc.closeOrthoPath(currentPath(doc))
            "orthojoin" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.joinCollapsedLeg(path, i)
            }
            "orthobreak" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.breakOrthoLeg(path, i, parsePos(words[2]), parsePos(words[3]))
            }
            "orthodiscard" -> doc.discardOrthoPath(currentPath(doc))
            // splitting an arc: the carrier it names, the angle it splits at (state), the sweep it keeps (OP-1)
            "breakarc" -> doc.breakArc(el(1), quantity(words[2]), words.getOrNull(3) != "cw")
            // the step kinds keep their user-facing names; what they carry is the generic thick path and
            // its interval features (OP-21) — a pure description, never the geometry it computes
            "wall" -> doc.buildThickPath(currentPath(doc), scalar(1).ref, justification(words.getOrNull(2)))
            "opening" -> applyInterval(doc, words, el(1))
            // sketch spaces (OP-17). `sketchspace` *declares* one — the solid and the boundary-piece index
            // its plane is derived from (OP-8) — and makes it current, exactly as `orthostart` does for a
            // path; `space` switches back. Both create nothing, so they declare nothing, and steps belong
            // to the last space named — the ordering rule the writer relies on ([Document.noteSpace]).
            "sketchspace" -> applySketchSpace(doc, words, byName)
            "space" ->
                if (!doc.switchSpace(unquote(words.getOrElse(1) { throw LoadError("space is missing a name") }), record = true)) {
                    throw LoadError("unknown sketch space '${unquote(words[1])}'")
                }
            "weld" -> doc.weld(el(1), el(2))
            "attach" -> doc.attachToCurve(el(1), el(2))
            "weldortho" -> doc.weldOrthoEndpointToPoint(el(1), el(2))
            "attachortho" -> doc.attachOrthoEndpointToCurve(el(1), el(2))
            "unweld" -> doc.unweld(el(1))
            "wire" -> doc.wireParameter(scalar(1), scalar(3))
            "intersectnear" -> doc.intersectNear(el(1), el(2), parsePos(words[3]))
            // `dofs=` is the rider's own parameter, restated (see [restate]); a script written before it was
            // recorded simply has none, and the click position places the rider as it always did
            "pointoncurve" -> doc.pointOnCurve(el(1), parsePos(words[2]), keyedNums(words, "dofs").firstOrNull())
            "relative" -> doc.makeRelative(el(1), el(2), keyedNums(words, "dofs"))
            "tool" -> applyTool(doc, words, byName)
            "macrodef" -> applyMacroDef(doc, words, byName)
            "group" -> applyGroup(doc, words, byName)
            // visibility as a recorded decision (OP-18's reversal): one step per gesture, whole selection
            "hide", "show" -> doc.setElementsVisible(visibilityMembers(words, byName), kind == "show")
            "place" -> applyPlace(doc, words)
            else -> throw LoadError("unknown step '$kind'")
        }
    }

    /**
     * Replay a **sketch space on a face** (OP-17): `sketchspace "name" el=e7 piece=2`.
     *
     * The step carries the *description* of the frame — which solid, which boundary piece — and never the
     * frame itself, so the plane is re-derived on load and a part edited since comes back with its faces
     * where they now are. The piece index is a **discrete choice** and is therefore recorded verbatim
     * (OP-18), not re-derived from the click that made it: the click was a position, and a position that
     * lands on one edge today can land on its neighbour after an edit.
     */
    private fun applySketchSpace(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("sketchspace is missing a name") })
        var solid: Element? = null
        var piece = -1
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "el" -> solid = byName[v] ?: throw LoadError("unknown element '$v'")
                "piece" -> piece = v.toIntOrNull() ?: throw LoadError("malformed piece index '$v'")
                else -> throw LoadError("unknown sketchspace argument '${w.substringBefore('=')}'")
            }
        }
        val on = solid ?: throw LoadError("sketchspace is missing 'el='")
        doc.createFaceSpace(on, piece, named = name)
            ?: throw LoadError("'${words.getOrNull(2)}' has no planar side face #${piece + 1}")
    }

    /**
     * Replay an interval feature onto the thick path whose footprint is [footprint] (OP-21). The step
     * carries the *description* — which leg, how far along, how wide, sill and head — so nothing about
     * it has to be re-derived from a click, and the values a user has since typed come straight back.
     */
    private fun applyInterval(
        doc: Document,
        words: List<String>,
        footprint: Element,
    ) {
        val tp = doc.thickPathOf(footprint) ?: throw LoadError("'${words[1]}' is not a thick path's footprint")
        val keyed = HashMap<String, String>()
        for (w in words.drop(2)) {
            val key = w.substringBefore('=')
            if (!w.contains('=')) throw LoadError("malformed interval argument '$w'")
            keyed[key] = w.substringAfter('=')
        }

        fun need(key: String): String = keyed[key] ?: throw LoadError("interval is missing '$key='")
        val widthName = unquote(need("width"))
        val width = doc.scalars.firstOrNull { it.name == widthName } ?: throw LoadError("unknown scalar '$widthName'")
        val leg = need("leg").toIntOrNull() ?: throw LoadError("malformed leg index '${need("leg")}'")
        doc.addInterval(tp, leg, quantity(need("pos")), width.ref, quantity(need("sill")), quantity(need("head")))
            ?: throw LoadError("leg $leg is not a leg of '${words[1]}'")
    }

    /**
     * Replay a **macro definition** (OP-6): `macrodef "name" els=… pts=… scalar=…`.
     *
     * The step declares nothing but a designation over what earlier steps built — which elements are the
     * definition, which of their free points are the input slots (the first is the anchor) and which
     * panel scalars are the scalar inputs. So the custom **tool** is part of the file: replaying this
     * re-registers it, and the instance steps that follow (ordinary `tool macro:name …` steps) find it
     * because a definition is always recorded before any instance of it.
     */
    private fun applyMacroDef(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("macrodef is missing a name") })
        var members = emptyList<Element>()
        var points = emptyList<Element>()
        var scalars = emptyList<ScalarEntry>()

        fun els(v: String) = v.split(',').filter { it.isNotEmpty() }.map { byName[it] ?: throw LoadError("unknown element '$it'") }
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "els" -> members = els(v)
                "pts" -> points = els(v)
                "scalar" ->
                    scalars =
                        scalarNames(v).map { n ->
                            doc.scalars.firstOrNull { it.name == n } ?: throw LoadError("unknown scalar '$n'")
                        }
                else -> throw LoadError("unknown macrodef argument '${w.substringBefore('=')}'")
            }
        }
        doc.defineMacro(name, members, points, scalars)
            ?: throw LoadError("macro '$name' has no elements, or an input point that is not free")
    }

    /**
     * The elements a `hide` / `show` step names. A load error when one is unknown, like every other
     * element reference: a visibility step that silently applied to nothing would reopen the drawing
     * looking different from the file, which is exactly what recording it was for.
     */
    private fun visibilityMembers(
        words: List<String>,
        byName: Map<String, Element>,
    ): List<Element> {
        val arg = words.drop(1).firstOrNull { it.startsWith("els=") } ?: throw LoadError("${words[0]} is missing 'els='")
        return arg.removePrefix("els=").split(',').filter { it.isNotEmpty() }
            .map { byName[it] ?: throw LoadError("unknown element '$it'") }
    }

    /**
     * Replay a flat group (OP-16): membership by element name, so nothing about the group depends on
     * runtime ids and a member the script no longer declares is simply not in it.
     */
    private fun applyGroup(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        val arg = words.drop(2).firstOrNull { it.startsWith("els=") } ?: throw LoadError("group is missing 'els='")
        val members =
            arg.removePrefix("els=").split(',').filter { it.isNotEmpty() }
                .map { byName[it] ?: throw LoadError("unknown element '$it'") }
        doc.createGroup(unquote(words.getOrElse(1) { throw LoadError("group is missing a name") }), members)
            ?: throw LoadError("group '${unquote(words[1])}' has no members, or one of them is already grouped")
    }

    /**
     * Replay a placement (OP-16 step 2): the group's frame is restored at the recorded origin and angle,
     * and the retrofit re-runs over the members' free points — which the earlier steps have just put back
     * at their **world** positions, so the locals come out the same and the geometry is unchanged.
     */
    private fun applyPlace(
        doc: Document,
        words: List<String>,
    ) {
        val name = unquote(words.getOrElse(1) { throw LoadError("place is missing a group name") })
        val g = doc.groups.firstOrNull { it.name == name } ?: throw LoadError("unknown group '$name'")
        var at = Vec2(0.0, 0.0)
        var angle = 0.0
        for (w in words.drop(2)) {
            val v = w.substringAfter('=', "")
            when (w.substringBefore('=')) {
                "at" -> at = parsePos(v)
                "angle" -> angle = quantity(v).base
                else -> throw LoadError("unknown place argument '${w.substringBefore('=')}'")
            }
        }
        doc.placeGroup(g, at, angle) ?: throw LoadError("group '$name' cannot be placed")
    }

    /** A thick path's justification, defaulting to centred for a script written before it was recorded. */
    private fun justification(word: String?): Justification =
        when (word) {
            null -> Justification.CENTER
            else ->
                Justification.entries.firstOrNull { it.name.lowercase() == word }
                    ?: throw LoadError("unknown justification '$word'")
        }

    private fun currentPath(doc: Document): OrthoPath =
        doc.currentOrthoPath ?: doc.orthoPaths.lastOrNull() ?: throw LoadError("no path is being drawn")

    /**
     * Replay a tool application through the very same [ToolDef.build] the click ran, so every tool —
     * present and future — round-trips without a per-tool case here.
     */
    private fun applyTool(
        doc: Document,
        words: List<String>,
        byName: Map<String, Element>,
    ) {
        // the document's registry, not the static one: a user-defined macro is a tool too (OP-6), and its
        // `macrodef` step has already been replayed by the time any instance of it is
        val tool = doc.toolDef(words[1]) ?: throw LoadError("unknown tool '${words[1]}'")
        var points = emptyList<PointRef>()
        var elements = emptyList<Element>()
        var clicks = emptyList<Vec2>()
        var dofs = emptyList<Quantity>()
        var scalars = emptyList<ScalarEntry>()
        var count = 0
        for (w in words.drop(2)) {
            val key = w.substringBefore('=')
            val v = w.substringAfter('=', "")
            when (key) {
                "pts" ->
                    points =
                        v.split(',').filter { it.isNotEmpty() }.map {
                            @Suppress("UNCHECKED_CAST")
                            (byName[it] ?: throw LoadError("unknown element '$it'")).ref as PointRef
                        }
                "els" -> elements = v.split(',').filter { it.isNotEmpty() }.map { byName[it] ?: throw LoadError("unknown element '$it'") }
                "clicks" -> clicks = v.split(';').filter { it.isNotEmpty() }.map { parsePos(it) }
                "dofs" -> dofs = v.split(';').filter { it.isNotEmpty() }.map { quantity(it) }
                // an ordered list, so a two-scalar tool needs no key of its own; names are quoted, hence
                // split on the quotes rather than on the comma (a name may contain one)
                "scalar" ->
                    scalars =
                        scalarNames(v).map { name ->
                            doc.scalars.firstOrNull { it.name == name } ?: throw LoadError("unknown scalar '$name'")
                        }
                // structural (OP-18): how many copies/vertices the tool built, replayed verbatim
                "count" -> count = v.toIntOrNull() ?: throw LoadError("malformed count '$v'")
                else -> throw LoadError("unknown tool argument '$key'")
            }
        }
        val at = clicks.lastOrNull() ?: Vec2(0.0, 0.0)
        val picks = Picks(points, elements, at, clicks, dofs, count)
        // replay through the same recorder the click used, so the reloaded document can be saved again
        doc.recordingTool(tool.id, picks, scalars) { tool.build(doc, picks, scalars.map { it.ref }) }
    }

    /** The scalar names in a `scalar=` argument: `"a","b"` -> [a, b]. Names are quoted, so the quotes and
     *  not the comma delimit them — a name may contain a comma. A bare word is accepted defensively. */
    private fun scalarNames(v: String): List<String> =
        if (v.startsWith("\"")) Regex("\"([^\"]*)\"").findAll(v).map { it.groupValues[1] }.toList() else listOf(v)

    private fun unquote(s: String) = s.removeSurrounding("\"")

    /** The `key=a;b` numbers of a step, empty when it carries none (an older script, a tool with no DOF). */
    private fun keyedNums(
        words: List<String>,
        key: String,
    ): List<Quantity> =
        words.firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.split(';')?.filter { it.isNotEmpty() }?.map { quantity(it) }
            ?: emptyList()

    private fun parsePos(s: String): Vec2 {
        val parts = s.split(',')
        if (parts.size != 2) throw LoadError("malformed position '$s'")
        return Vec2(parseNum(parts[0]), parseNum(parts[1]))
    }

    private fun parseNum(s: String): Double =
        s.removeSuffix("mm").removeSuffix("deg").toDoubleOrNull() ?: throw LoadError("malformed number '$s'")

    private fun quantity(s: String): Quantity =
        when {
            s.endsWith("deg") -> Quantity.deg(parseNum(s))
            s.endsWith("mm") -> Quantity.mm(parseNum(s))
            else -> Quantity.number(parseNum(s))
        }
}
