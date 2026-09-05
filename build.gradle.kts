import constructit.gradle.GenerateMessagesTask

plugins {
    kotlin("multiplatform") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    // OP-29: the user's own DeepL front-end (haumacher/auto-translate), the published plugin — 1.1.4 is the
    // first release with the ARB `description` sent to DeepL as context and with glossaries, which is what
    // the German this repository commits was made with. It contributes the `translateArb` task and nothing
    // else — see `translateArb { }` below and the note in CLAUDE.md: it is *not* part of the ordinary build,
    // because it spends DeepL characters. `-Pautotranslate.path=` in settings.gradle.kts swaps in a sibling
    // checkout for developing the plugin itself.
    id("de.haumacher.auto-translate-arb") version "1.1.4"
}

repositories {
    mavenCentral()
    // Manifold's JVM binding (OP-9) is published to Clojars by the clj-manifold3d project — the only
    // maintained JavaCPP wrapper of the C++ library; there is nothing equivalent on Maven Central.
    maven {
        url = uri("https://repo.clojars.org")
        content { includeGroup("org.clojars.cartesiantheatrics") }
    }
}

/**
 * The Manifold binding ships one jar per platform: the Java classes are the same in each, the bundled
 * native library is not. So the *host* jar is what gets resolved, and where no jar exists for the host
 * (Windows, arm64) the linux one is still enough to compile against — `MeshBool.available` then reports
 * false at runtime and the general-boolean path refuses with a reason instead of crashing (OP-3).
 */
val manifoldVersion = "2.0.3"
val manifoldClassifier =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val x64 = arch == "amd64" || arch == "x86_64"
        when {
            os.contains("linux") && x64 -> "linux-x86_64"
            os.contains("mac") && x64 -> "mac-TBB-x86_64"
            else -> "linux-x86_64"
        }
    }

/** LWJGL's native bundle for the host, or null where Manifold has no jar anyway (see above). */
val lwjglAssimpClassifier =
    when (manifoldClassifier) {
        "linux-x86_64" -> "natives-linux"
        "mac-TBB-x86_64" -> "natives-macos"
        else -> null
    }

/** The npm package holding the same engine compiled to WASM (OP-9), for the browser. */
val manifoldNpmVersion = "3.5.1"

/**
 * FormatJS's ICU MessageFormat engine (OP-29), for the browser. Pinned to the 10.x line deliberately: it
 * still publishes a CommonJS entry point, which is what a Kotlin/JS `@JsModule` import resolves against.
 */
val intlMessageFormatNpmVersion = "10.7.9"

/**
 * three.js, for the in-app realistic preview (`Preview3`, src/jsMain). Declared here so the version is one
 * fact, and **loaded by a dynamic `import('three')`** rather than a static one: webpack then splits it into a
 * chunk of its own, so the ~600 KB library rides only the sessions that open the preview panel and the main
 * bundle is unchanged. The Kotlin side is a hand-written external declaration of the dozen classes the preview
 * touches (src/jsMain/kotlin/constructit/three/THREE.kt), not a generated binding.
 */
val threeNpmVersion = "0.166.1"

/**
 * The browser half of the seam: **copy `manifold.js` and `manifold.wasm` out of the resolved npm package
 * into the app's own resources**, so they are served next to `index.html` and the app works offline.
 *
 * Why a copy rather than letting webpack bundle the import: the package's entry point is emscripten glue —
 * an ES module with a top-level `await` that finds its `.wasm` through `import.meta.url`. Re-bundling that
 * is where a Kotlin/JS webpack pipeline produces a mangled loader instead of a clear error, and none of it
 * is needed: the glue is already a browser-ready module, so `MeshBool` (src/jsMain) loads it with the
 * browser's own ESM loader from the app's origin. `npm()` still declares and pins the dependency, which is
 * what makes the version above the single source of truth.
 */
val manifoldWasm by tasks.registering(Copy::class) {
    dependsOn(tasks.named("kotlinNpmInstall"))
    from(rootProject.layout.buildDirectory.dir("js/node_modules/manifold-3d")) {
        include("manifold.js", "manifold.wasm")
    }
    into(layout.buildDirectory.dir("manifoldWasm"))
}

/**
 * OP-29's **languages**: the ARB bundles, and the two things the build does with them.
 *
 * `l10n/app_en.arb` is the source of truth and every `app_<lang>.arb` beside it a target the plugin wrote.
 * They live at the top of the repository rather than under `src/<target>/resources` on purpose: nothing reads them
 * at *runtime* — [generateMessages] compiles them into Kotlin — so shipping them inside the jar and the
 * browser distribution would be a copy of every string nobody ever opens. They are sources, and the
 * translator rewrites them in place (it stamps `x-translated` checksums onto the English one so the next
 * run only pays for what changed), which is a thing a build directory must never hold.
 */
val l10nDir = layout.projectDirectory.dir("l10n")

/**
 * The English ARB compiled to typed Kotlin (OP-29) — one function per key, the key's placeholders as its
 * parameters, every locale's pattern beside it. Wired into `commonMain` below, so an edit to an ARB
 * regenerates and recompiles; the generated sources are build output and are not committed.
 */
val generateMessages by tasks.registering(GenerateMessagesTask::class) {
    group = "build"
    description = "Compile l10n/app_*.arb into typed Kotlin message accessors (OP-29)"
    bundles.from(l10nDir.asFileTree.matching { include("app_*.arb") })
    outputDir.set(layout.buildDirectory.dir("generated/l10n/commonMain"))
}

/**
 * DeepL, through the user's own plugin (OP-29). **Not part of the ordinary build**: it costs characters, so
 * it is run by hand when the English bundle has changed, and the German it writes is reviewed and committed
 * like a golden. The API key is read from `deepl.apiKey` in `~/.gradle/gradle.properties`.
 */
translateArb {
    serverId = "deepl"
    sourceFile = l10nDir.file("app_en.arb").asFile
    targetLangs = listOf("de")
    // The terms of art DeepL cannot know (fillet → Verrundung, chamfer → Fase, …). One tab-separated file
    // per language pair; see l10n/glossary/en-de.tsv for why each line is there.
    glossaryDir = l10nDir.dir("glossary").asFile
}

kotlin {
    jvm()
    js(IR) {
        browser {
            binaries.executable()
            commonWebpackConfig {
                outputFileName = "constructit.js"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // the JT writer/reader (sibling project, substituted by the composite build in settings)
                implementation("de.haumacher.kotlinjt:kotlinJT:0.1.0-SNAPSHOT")
            }
            // the compiled ARB bundles (OP-29). A task provider rather than a path, so the dependency is
            // the build's own and every compilation waits for the generator.
            kotlin.srcDir(generateMessages)
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                // ICU MessageFormat's reference implementation (OP-29) — the JVM actual of `formatMessage`.
                // 13 MB of locale data, which is exactly why the browser gets FormatJS's 40 KB instead.
                implementation("com.ibm.icu:icu4j:75.1")
                // the general boolean engine (OP-9), behind the `MeshBool` expect/actual seam
                implementation("org.clojars.cartesiantheatrics:manifold3d:$manifoldVersion:$manifoldClassifier")
                // ...and the one library that jar links against but does not carry: `libmanifold.so` is
                // built with Manifold's optional assimp-based mesh IO (which this engine never calls), so
                // `libassimp.so.5` has to exist before it will load at all. Demanding a system package for
                // a code path we do not use would be a poor trade, and LWJGL publishes exactly that library
                // — with the right soname — for every platform. `MeshBool` preloads it from here; nothing
                // uses LWJGL's Java API, hence no transitive dependencies.
                lwjglAssimpClassifier?.let {
                    implementation("org.lwjgl:lwjgl-assimp:3.3.3:$it") { isTransitive = false }
                }
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
                implementation("com.microsoft.playwright:playwright:1.44.0")
            }
        }
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")
                // ICU MessageFormat in the browser (OP-29) — the JS actual of `formatMessage`. The same
                // syntax ICU4J reads on the JVM, which is what lets one ARB serve tests and shell alike.
                implementation(npm("intl-messageformat", intlMessageFormatNpmVersion))
                // the same engine as WASM (OP-9); loaded at startup, see `MeshBool` in src/jsMain
                implementation(npm("manifold-3d", manifoldNpmVersion))
                // the realistic preview's renderer, code-split by its dynamic import (see above)
                implementation(npm("three", threeNpmVersion))
            }
            // ...and its two files ride along as resources, so they land next to index.html
            resources.srcDir(manifoldWasm)
        }
    }
}

// `expect object MeshBool` (OP-9's seam) is an expect *class*, which is Beta and warns on every build.
// The seam is deliberately an object — one engine per platform, no instances — so the warning is
// acknowledged here rather than repeated 200 times.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // forward -De2e=1 to the test JVM so the (otherwise-skipped) browser E2E can opt in — and since the
    // E2E loads build/dist/js/productionExecutable by path, make that bundle a real input of the test task:
    // it is then built first and *current*, and a jsMain-only change re-runs the E2E instead of leaving the
    // test task up-to-date against yesterday's bundle (which `jvmTest jsBrowserDistribution -De2e=1` did).
    System.getProperty("e2e")?.let {
        systemProperty("e2e", it)
        inputs.files(tasks.named("jsBrowserDistribution"))
    }
    testLogging { events("passed", "failed", "skipped") }
}

// The compiled ARB bundles are generated Kotlin (OP-29) — machine-written, several thousand lines of it,
// and regenerated on every ARB edit. Linting it would say nothing about this repository's own style.
ktlint {
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}
