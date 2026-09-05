// The user's own DeepL front-end (https://github.com/haumacher/auto-translate) is a **published** plugin:
// `de.haumacher.auto-translate-arb` from the Gradle Plugin Portal, versioned in build.gradle.kts, so a
// checkout with nothing beside it — the CI runner — configures. `-Pautotranslate.path=<checkout>` swaps in a
// sibling composite instead, for developing the plugin against this repository; it is never the default,
// because a default that needs a second checkout is a build that fails everywhere but here (session 81).
pluginManagement {
    providers.gradleProperty("autotranslate.path").orNull?.let { includeBuild(it) }
}

rootProject.name = "constructit"

// The JT sibling library (https://github.com/haumacher/kotlinJT), checked out next to this repo by
// convention. A composite build rather than a published artifact, so changes on either side flow
// without a publish step — the two projects are developed together. `-Pkotlinjt.path=...` points a
// build at a different checkout — the escape hatch for gating this repo while the sibling's working
// tree is mid-edit (two live sessions, one composite: the coupling is wanted, the red tree is not).
includeBuild(providers.gradleProperty("kotlinjt.path").getOrElse("../kotlinJT"))
