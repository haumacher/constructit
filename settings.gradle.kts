rootProject.name = "constructit"

// The JT sibling library (https://github.com/haumacher/kotlinJT), checked out next to this repo by
// convention. A composite build rather than a published artifact, so changes on either side flow
// without a publish step — the two projects are developed together. `-Pkotlinjt.path=...` points a
// build at a different checkout — the escape hatch for gating this repo while the sibling's working
// tree is mid-edit (two live sessions, one composite: the coupling is wanted, the red tree is not).
includeBuild(providers.gradleProperty("kotlinjt.path").getOrElse("../kotlinJT"))
