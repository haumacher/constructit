package constructit.geom

/**
 * **Addresses that survive a change of topology** (OP-32, slice 3) — a face or an edge of a solid named by what
 * *generated* it, never by where it stands in a list.
 *
 * The structural names ([FaceName], [EdgeName]) already say what a face is a face *of* (OP-8), but several of
 * them say it by **position**: `Side(piece)` is the face over the piece at index `piece` of the profile, a band's
 * `edge` is an index into its base's edge list. Insert a vertex upstream and every one of those numbers after it
 * moves, so an address stored as an index — slice 2's `"edge": 13` — rounds a different edge. A **key** replaces
 * each positional part by the identity it stands for:
 *
 * - a face swept from a profile piece is named by the **curve that piece is a trim of** — the drawing's own
 *   element identity, carried on the loop as [Loop.tags] (`e7`); the caps are `top`/`bottom`, a revolution's ends
 *   `start`/`end`;
 * - a face a feature *appends* is named by what it was appended for, recursively in these same keys: a rounding's
 *   band along an edge is `band(<that edge's key>)`, a corner patch `corner(<keys>)`, a band's flat end
 *   `cap(<key>,start)`, a shell's inner wall `inner(<face key>)`, a boolean's face `a(<operand face key>)` /
 *   `b(…)` (with the piece where the boolean cut the face in two);
 * - an **edge** is the unordered pair of the keys of the two faces it lies between (`e5|top`), the one fact that
 *   makes it an edge (OP-8's own note on [EdgeName]) — with an ordinal only where the same two faces meet more
 *   than once (`e3|e5^1`), and one of its own on a face key that repeats (`e3~1`).
 *
 * Where a feature has **no generator to name** — a loop no curve of the drawing made (a polygon ring, a slab of
 * the prismatic algebra), a loft's band, a skin's strip — the key keeps the position, marked by `#` (`side#3`,
 * `loft#0.2`): a stated limit, not a silent fallback, and one that moves exactly as the index always did.
 *
 * A key is a **pure function of the feature** — the same body gives the same keys on every pass — so it can be
 * resolved where values are: at evaluation time, by the node that consumes the address (OP-3: an address that
 * does not resolve makes that node invalid, naming the key, and heals the pass it resolves again).
 */
object Provenance {
    /** The keys of one feature's faces and edges, computed once and on demand. */
    class Names(val feature: Feature3) {
        private val faces: List<FacePatch>? by lazy { Section3.faces(feature).first }
        private val edges: List<SolidEdge>? by lazy { Section3.edges(feature).first }

        /** The names one level down, for the keys that speak of the base a dressing or a shell stands on. */
        private val base: Names? by lazy {
            when (feature) {
                is Feature3.Blend -> Names(feature.base)
                is Feature3.Shell -> Names(feature.base)
                else -> null
            }
        }

        /** Each operand's face keys, for a general boolean's result faces (carried on its provenance). */
        private val operandKeys: List<List<String>?>? by lazy { (feature as? Feature3.MeshBoolean)?.provenance?.operandKeys }

        /** What generated each boundary piece of the profile the body's side faces are swept from. */
        private val sideTags: List<String?> by lazy {
            Section3.undressed(feature).footprint.flatMap { r ->
                (listOf(r.outer) + r.holes).flatMap { loop -> loop.tags ?: List(loop.elements.size) { null } }
            }
        }

        /** The key of every face, in the face list's own order — null where the body names no faces. */
        val faceKeys: List<String>? by lazy { faces?.let { fs -> ordinals(fs.map { key(it.name) }, '~') } }

        /** The key of every edge, in the edge list's own order — null where the body names no edges. */
        val edgeKeys: List<String>? by lazy {
            val es = edges ?: return@lazy null
            val byName = HashMap<FaceName, String>()
            val fk = faceKeys
            faces?.forEachIndexed { i, f -> if (fk != null) byName.getOrPut(f.name) { fk[i] } }
            ordinals(es.map { e -> pair(byName[e.between.a] ?: key(e.between.a), byName[e.between.b] ?: key(e.between.b)) }, '^')
        }

        private fun baseEdge(i: Int): String = base?.edgeKeys?.getOrNull(i) ?: "edge#$i"

        private fun baseFace(i: Int): String = base?.faceKeys?.getOrNull(i) ?: "face#$i"

        private fun piece(p: Int): String = if (p == 0) "" else ".$p"

        /** The key of a face of this feature called [n]. */
        fun key(n: FaceName): String =
            when (n) {
                is FaceName.Side -> sideTags.getOrNull(n.piece) ?: "side#${n.piece}"
                is FaceName.Cap -> if (n.which == SolidFace.TOP) "top" else "bottom"
                is FaceName.RevolveCap -> if (n.which == SolidFace.TOP) "end" else "start"
                is FaceName.Band -> "loft#${n.band}.${n.rail}"
                is FaceName.SectionFace -> "section#${n.section}"
                is FaceName.SkinBand -> "skin#${n.interval}.${n.strip}"
                is FaceName.BlendBand -> "band(${baseEdge(n.edge)})${piece(n.piece)}"
                is FaceName.BlendCorner -> "corner(${n.edges.map { baseEdge(it) }.sorted().joinToString(",")})${piece(n.piece)}"
                is FaceName.BlendCap -> "cap(${baseEdge(n.edge)},${if (n.atStart) "start" else "end"})"
                is FaceName.ShellInner -> "inner(${baseFace(n.face)})"
                is FaceName.BoolFace -> {
                    val of = operandKeys?.getOrNull(n.operand)?.getOrNull(n.face) ?: "face#${n.face}"
                    "${if (n.operand == 0) "a" else "b"}($of)${piece(n.piece)}"
                }
            }
    }

    /** The two face keys an edge lies between, as one unordered key. */
    fun pair(
        a: String,
        b: String,
    ): String = if (a <= b) "$a|$b" else "$b|$a"

    /**
     * [keys] with an ordinal on every key that occurs more than once, in list order — `k~0`, `k~1` for a face,
     * `k^0`, `k^1` for an edge: marks of their own, so an ordinal is never mistaken for a position (`side#3`) or
     * for the other kind's when [canonical] takes a key apart.
     */
    private fun ordinals(
        keys: List<String>,
        mark: Char,
    ): List<String> {
        val count = HashMap<String, Int>()
        for (k in keys) count[k] = (count[k] ?: 0) + 1
        val seen = HashMap<String, Int>()
        return keys.map { k ->
            if ((count[k] ?: 0) < 2) {
                k
            } else {
                val n = seen[k] ?: 0
                seen[k] = n + 1
                "$k$mark$n"
            }
        }
    }

    /**
     * **[key] in its one spelling** — every unordered part in order: the two faces of an edge, the edges of a corner.
     * A key is built in this spelling ([pair], [Names.key]); a key stated otherwise — renamed identities, a hand
     * edit — means the same edge or face, and resolves as it ([edgeIndex], [faceIndex]).
     */
    fun canonical(key: String): String {
        val ord = Regex("\\^\\d+$").find(key)?.value ?: ""
        val base = key.dropLast(ord.length)
        val parts = splitTop(base, '|')
        return if (parts.size == 2) parts.map { canonicalFace(it) }.sorted().joinToString("|") + ord else canonicalFace(base) + ord
    }

    private fun canonicalFace(f: String): String {
        val open = f.indexOf('(')
        if (open < 0) return f
        var depth = 0
        var close = -1
        for (i in open until f.length) {
            if (f[i] == '(') depth++
            if (f[i] == ')') {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
        if (close < 0) return f
        val name = f.substring(0, open)
        val args = splitTop(f.substring(open + 1, close), ',')
        val inner =
            when (name) {
                "corner" -> args.map { canonical(it) }.sorted()
                "cap" -> listOf(canonical(args[0])) + args.drop(1)
                else -> args.map { canonical(it) }
            }
        return name + "(" + inner.joinToString(",") + ")" + f.substring(close + 1)
    }

    /** [s] split at every [sep] that stands outside any parenthesis. */
    private fun splitTop(
        s: String,
        sep: Char,
    ): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        for ((i, c) in s.withIndex()) {
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                c == sep && depth == 0 -> {
                    out.add(s.substring(from, i))
                    from = i + 1
                }
            }
        }
        out.add(s.substring(from))
        return out
    }

    /** The key of every face of [feature], or null where it names none. */
    fun faceKeys(feature: Feature3): List<String>? = Names(feature).faceKeys

    /** The key of every edge of [feature], or null where it names none. */
    fun edgeKeys(feature: Feature3): List<String>? = Names(feature).edgeKeys

    /** The index of the edge keyed [key] on [feature], or null where it has none. */
    fun edgeIndex(
        feature: Feature3,
        key: String,
    ): Int? = edgeKeys(feature)?.indexOf(canonical(key))?.takeIf { it >= 0 }

    /** The index of the face keyed [key] on [feature], or null where it has none. */
    fun faceIndex(
        feature: Feature3,
        key: String,
    ): Int? = faceKeys(feature)?.indexOf(canonical(key))?.takeIf { it >= 0 }

    /**
     * Whether [key] states a position rather than a generator anywhere in it — the stated limit (`side#3`): such
     * an address moves under a change of topology exactly as an index does.
     */
    fun positional(key: String): Boolean = key.contains('#') && Regex("(^|[(|,])(side|loft|section|skin|edge|face)#").containsMatchIn(key)
}

/**
 * **One recorded shared slot, by key** (OP-32, slice 3) — a [CornerSlot] whose base edges are named by their
 * [Provenance] keys rather than by their index, so the record a dressing states survives a vertex inserted
 * upstream exactly as its roundings do.
 */
data class KeyedSlot(val kind: CornerSlotKind, val edges: List<String>, val piece: Int)

/**
 * **A dressing's shared-slot record, by key** (OP-32, slice 3) — [CornerSlots] with every base edge named by its
 * key. The record the file states and the document keeps; the index form is derived from it, at evaluation time,
 * against the base as it stands ([resolve]).
 */
data class KeyedCornerSlots(
    val edges: List<List<KeyedSlot>>,
    val faces: List<List<KeyedSlot>>,
) {
    val isEmpty: Boolean get() = edges.all { it.isEmpty() } && faces.all { it.isEmpty() }

    /**
     * The index form against a base whose edges are keyed [index] — a slot naming an edge the base no longer has
     * is **dropped** from the record: the corner it stood for is gone with that edge, and a corner the layout
     * finds afresh appends, which is what an unrecorded slot always did ([CornerSlots]).
     */
    fun resolve(index: Map<String, Int>): CornerSlots {
        fun one(s: KeyedSlot): CornerSlot? = CornerSlot(s.kind, s.edges.map { index[it] ?: return null }, s.piece)
        return CornerSlots(edges.map { row -> row.mapNotNull { one(it) } }, faces.map { row -> row.mapNotNull { one(it) } })
    }

    companion object {
        val NONE = KeyedCornerSlots(emptyList(), emptyList())

        /** [slots] keyed against a base whose edges are keyed [keys] — an index past the list keeps its position. */
        fun of(
            slots: CornerSlots,
            keys: List<String>?,
        ): KeyedCornerSlots {
            fun one(s: CornerSlot) = KeyedSlot(s.kind, s.edges.map { keys?.getOrNull(it) ?: "edge#$it" }, s.piece)
            return KeyedCornerSlots(slots.edges.map { row -> row.map { one(it) } }, slots.faces.map { row -> row.map { one(it) } })
        }
    }
}
