plugins {
    kotlin("multiplatform") version "1.9.24"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
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
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
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
                // the same engine as WASM (OP-9); loaded at startup, see `MeshBool` in src/jsMain
                implementation(npm("manifold-3d", manifoldNpmVersion))
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
    // forward -De2e=1 to the test JVM so the (otherwise-skipped) browser E2E can opt in
    System.getProperty("e2e")?.let { systemProperty("e2e", it) }
    testLogging { events("passed", "failed", "skipped") }
}
