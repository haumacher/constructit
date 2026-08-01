package constructit.editor

import kotlin.math.pow

/**
 * **Tier 1 of the appearance package: one material per solid** — five numbers, and nothing else.
 *
 * The modeler's job is to *assign* appearance, never to render it: lighting, shadows and reflections are
 * what the GLB export delegates to real PBR viewers, and what the in-app preview asks three.js for. So this
 * record is deliberately exactly the **metallic-roughness** vocabulary glTF 2.0 speaks natively — a base
 * colour, a roughness, a metalness — because a value that has to be *translated* into that vocabulary is a
 * value whose meaning depends on the translator.
 *
 * One appearance model with two consumers (the GLB writer and the preview), which is what makes *what the
 * preview shows what the exported GLB shows, by construction*. Tier 2 (textures by projection) and Tier 3
 * (per-face assignment) are queued elsewhere and add nothing here: a texture is an export-time projection
 * rule, and a per-face assignment needs the durable face names edit-in-3D's slice 2 introduces.
 *
 * A value, not a mutable object: setting a material replaces the record, so the document's map holds one
 * immutable answer per element and undo/replay restore it by putting the old one back.
 */
data class Appearance(
    /** Base colour as `#rrggbb` in **sRGB** — the same spelling every colour in this codebase uses. */
    val color: String = DEFAULT_COLOR,
    /** 0 = a mirror-smooth surface, 1 = fully diffuse. */
    val roughness: Double = DEFAULT_ROUGHNESS,
    /** 0 = a dielectric (plastic, wood), 1 = bare metal. */
    val metallic: Double = DEFAULT_METALLIC,
) {
    /**
     * The base colour as **linear** RGB in 0..1 — what glTF's `baseColorFactor` is defined to be, and what
     * three.js's working colour space is. The conversion belongs here rather than in either consumer,
     * because the one thing that must not differ between the export and the preview is this number.
     */
    fun linearRgb(): DoubleArray {
        val srgb = parseHex(color) ?: parseHex(DEFAULT_COLOR)!!
        return DoubleArray(3) { toLinear(srgb[it]) }
    }

    /** Roughness and metalness clamped to the range the spec defines them on. */
    val roughnessClamped: Double get() = roughness.coerceIn(0.0, 1.0)
    val metallicClamped: Double get() = metallic.coerceIn(0.0, 1.0)

    companion object {
        /**
         * The defaults, chosen to *read* rather than to be neutral: a light grey that shows form under any
         * environment, a roughness that is neither plastic-shiny nor chalk-flat, and a trace of metalness so
         * a highlight appears at all. A solid nobody has assigned a material to still has to look like an
         * object — which is why there is no "unset" state in this record.
         *
         * Deliberately *not* the 3D view's per-element palette colour ([Scene3.PALETTE]). That palette is
         * part identification — "the colour it is always drawn in", so a sibling going away cannot recolour
         * a part — and identification is not a material. An exported file carries what the user assigned,
         * and a light grey is the honest answer for what they have not.
         */
        const val DEFAULT_COLOR = "#c8c8c8"
        const val DEFAULT_ROUGHNESS = 0.6
        const val DEFAULT_METALLIC = 0.1

        val DEFAULT = Appearance()

        /** `#rrggbb` as three 0..1 sRGB channels, or null when it is not that. */
        fun parseHex(hex: String): DoubleArray? {
            if (hex.length != 7 || hex[0] != '#') return null
            val out = DoubleArray(3)
            for (i in 0..2) {
                val v = hex.substring(1 + i * 2, 3 + i * 2).toIntOrNull(16) ?: return null
                out[i] = v / 255.0
            }
            return out
        }

        /** The sRGB electro-optical transfer function, exactly as the sRGB and glTF specs state it. */
        fun toLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        /**
         * The inverse of [toLinear] — what an **import** needs, since a file states the linear number
         * [linearRgb] hands out and this record stores the sRGB spelling.
         *
         * Here rather than in the importer for the reason [linearRgb] is here: the one thing that must not
         * differ between what is written and what is read back is this curve, and a second copy of it
         * somewhere else is how two copies start to disagree.
         */
        fun fromLinear(c: Double): Double = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

        /** Three **linear** channels as `#rrggbb` — [linearRgb] read backwards, through [fromLinear]. */
        fun hexOfLinear(linear: DoubleArray): String = hexOf(DoubleArray(3) { fromLinear(linear[it].coerceIn(0.0, 1.0)) })

        /** Two lowercase hex digits — the spelling [hex] round-trips through. */
        private fun two(v: Int): String = if (v < 16) "0" + v.toString(16) else v.toString(16)

        /** Three 0..1 sRGB channels back as `#rrggbb`, so a colour picked as numbers can be stored. */
        fun hexOf(srgb: DoubleArray): String =
            "#" + (0..2).joinToString("") { two((srgb[it] * 255.0 + 0.5).toInt().coerceIn(0, 255)) }
    }
}
