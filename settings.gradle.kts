// The user's own DeepL front-end (https://github.com/haumacher/auto-translate), checked out next to this
// repo by the same convention the JT sibling below follows — and a *plugin* composite, because that is
// what `plugins { id("de.haumacher.auto-translate-arb") }` resolves against.
//
// A composite rather than the Gradle Plugin Portal, and the reason is a gap rather than a preference: the
// portal's newest publication of this plugin is **1.1.1**, while the plugin's own README documents 1.1.4.
// Everything OP-29's design asks of it landed after 1.1.1 — the ARB `description` sent to DeepL as
// *context*, DeepL glossaries, and the re-translate-on-checksum-mismatch fix — so the published one cannot
// produce the German this repository commits. `-Pautotranslate.path=...` points a build at another
// checkout, exactly as the JT sibling's escape hatch does.
pluginManagement {
    includeBuild(providers.gradleProperty("autotranslate.path").getOrElse("../auto-translate"))
}

rootProject.name = "constructit"

// The JT sibling library (https://github.com/haumacher/kotlinJT), checked out next to this repo by
// convention. A composite build rather than a published artifact, so changes on either side flow
// without a publish step — the two projects are developed together. `-Pkotlinjt.path=...` points a
// build at a different checkout — the escape hatch for gating this repo while the sibling's working
// tree is mid-edit (two live sessions, one composite: the coupling is wanted, the red tree is not).
includeBuild(providers.gradleProperty("kotlinjt.path").getOrElse("../kotlinJT"))
