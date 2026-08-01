rootProject.name = "constructit"

// The JT sibling library (https://github.com/haumacher/kotlinJT), checked out next to this repo by
// convention. A composite build rather than a published artifact, so changes on either side flow
// without a publish step — the two projects are developed together.
includeBuild("../kotlinJT")
