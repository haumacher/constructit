package constructit.editor

import constructit.core.EvalResult
import constructit.core.Evaluator
import constructit.core.PointValue
import constructit.core.ScalarValue
import constructit.dsl.PointRef
import constructit.geom.Vec2
import constructit.units.Dimension
import constructit.units.Quantity

/** An argument of a recorded construction [Step]. */
sealed interface Arg {
    class El(val el: Element) : Arg

    class Sc(val entry: ScalarEntry) : Arg

    class Pos(val p: Vec2) : Arg

    class Num(val q: Quantity) : Arg

    class Text(val s: String) : Arg

    class Els(val els: List<Element>) : Arg

    class Positions(val ps: List<Vec2>) : Arg

    /** `key=value`, so a step with several optional parts stays readable. */
    class Keyed(val key: String, val value: Arg) : Arg
}

/**
 * One recorded construction step, and the elements it created (in order).
 *
 * Recording *steps* rather than nodes is what makes the whole synthetic layer — handles, styles, path
 * and wall bookkeeping — free: replaying a step runs the same code that built it, so all of that is
 * reconstructed rather than stored.
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
 * - **Handles, styles, path/wall structure.** All synthetic: created by the same methods that create
 *   the geometry, hence recreated by replay.
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
        for (step in doc.journal) {
            val args = restate(step, ev, present).joinToString(" ") { encode(it, names) }
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
        step: Step,
        ev: Evaluator,
        present: Set<Element>,
    ): List<Arg> {
        fun posOf(el: Element): Vec2? = ((ev.eval(el.ref.node) as? EvalResult.Ok)?.value as? PointValue)?.p
        // A step whose creations have since been *removed* — by a join collapsing a jog — keeps its
        // recorded literals. Re-reading a deleted vertex would describe the state after the edit that
        // deleted it, whereas replay has to rebuild the geometry that existed before, so the later join
        // has something to collapse. What survives the join is described by the steps that still own it.
        if (step.creates.any { it !in present }) return step.args
        return when (step.kind) {
            "param" -> {
                val e = (step.args[0] as Arg.Sc).entry
                listOf(step.args[0], Arg.Text("="), Arg.Num(value(e, ev)))
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
            // a slider's position lives in a hidden parameter the click created; re-read it from the
            // point itself so the slider lands where it is now, not where it was first placed
            "tool" -> {
                val onCurve = step.creates.singleOrNull { it.kind == ElementKind.ON_CURVE }
                val here = onCurve?.let { posOf(it) }
                if (here == null) {
                    step.args
                } else {
                    step.args.map { arg ->
                        val v = (arg as? Arg.Keyed)?.value
                        if (arg is Arg.Keyed && v is Arg.Positions) Arg.Keyed(arg.key, Arg.Positions(v.ps.dropLast(1) + here)) else arg
                    }
                }
            }
            else -> step.args
        }
    }

    private fun value(
        e: ScalarEntry,
        ev: Evaluator,
    ): Quantity = ((ev.eval(e.ref.node) as? EvalResult.Ok)?.value as? ScalarValue)?.q ?: Quantity.mm(0.0)

    private fun encode(
        arg: Arg,
        names: Map<String, String>,
    ): String =
        when (arg) {
            is Arg.El -> names[arg.el.id] ?: "?${arg.el.id}"
            is Arg.Els -> arg.els.joinToString(",") { names[it.id] ?: "?${it.id}" }
            is Arg.Sc -> quote(arg.entry.name)
            is Arg.Pos -> pos(arg.p)
            is Arg.Positions -> arg.ps.joinToString(";") { pos(it) }
            is Arg.Num -> num(arg.q)
            is Arg.Text -> arg.s
            is Arg.Keyed -> "${arg.key}=" + encode(arg.value, names)
        }

    private fun pos(p: Vec2) = "${trim(p.x)},${trim(p.y)}"

    private fun num(q: Quantity): String =
        when (q.dim) {
            Dimension.LENGTH -> "${trim(q.mm)}mm"
            Dimension.ANGLE -> "${trim(q.deg)}deg"
            else -> trim(q.value)
        }

    /** Round-trippable decimal: enough digits to reload identically, no trailing noise. */
    private fun trim(v: Double): String {
        val r = (if (v == 0.0) 0.0 else v).toString() // normalise -0.0, which reads as noise
        return if (r.endsWith(".0")) r.dropLast(2) else r
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
            "orthojoin" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.joinCollapsedLeg(path, i)
            }
            "orthobreak" -> {
                val (path, i) = doc.legOf(el(1)) ?: throw LoadError("'${words[1]}' is not an ortho segment")
                doc.breakOrthoLeg(path, i, parsePos(words[2]), parsePos(words[3]))
            }
            "orthodiscard" -> doc.discardOrthoPath(currentPath(doc))
            "wall" -> doc.buildWall(currentPath(doc), scalar(1).ref)
            "opening" -> doc.addOpeningAtRecorded(parsePos(words[1]), scalar(2).ref, parseNum(words[3]))
            "weld" -> doc.weld(el(1), el(2))
            "attach" -> doc.attachToCurve(el(1), el(2))
            "weldortho" -> doc.weldOrthoEndpointToPoint(el(1), el(2))
            "attachortho" -> doc.attachOrthoEndpointToCurve(el(1), el(2))
            "unweld" -> doc.unweld(el(1))
            "wire" -> doc.wireParameter(scalar(1), scalar(3))
            "intersectnear" -> doc.intersectNear(el(1), el(2), parsePos(words[3]))
            "pointoncurve" -> doc.pointOnCurve(el(1), parsePos(words[2]))
            "tool" -> applyTool(doc, words, byName)
            else -> throw LoadError("unknown step '$kind'")
        }
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
        val tool = Tools.byId(words[1]) ?: throw LoadError("unknown tool '${words[1]}'")
        var points = emptyList<PointRef>()
        var elements = emptyList<Element>()
        var clicks = emptyList<Vec2>()
        var scalar: ScalarEntry? = null
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
                "scalar" -> {
                    val name = unquote(v)
                    scalar = doc.scalars.firstOrNull { it.name == name } ?: throw LoadError("unknown scalar '$name'")
                }
                else -> throw LoadError("unknown tool argument '$key'")
            }
        }
        val at = clicks.lastOrNull() ?: Vec2(0.0, 0.0)
        val picks = Picks(points, elements, at, clicks)
        // replay through the same recorder the click used, so the reloaded document can be saved again
        doc.recordingTool(tool.id, picks, scalar) { tool.build(doc, picks, scalar?.ref) }
    }

    private fun unquote(s: String) = s.removeSurrounding("\"")

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
