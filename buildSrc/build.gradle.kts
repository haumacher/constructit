// The build's own code (OP-29): the ARB→Kotlin message generator. `buildSrc` rather than an included
// plugin build because the generator is *this* build's, not a product: one task class, no plugin id, no
// publication, and no second `settings.gradle.kts` to keep in step with the composite include.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // Groovy's JSON reader ships inside Gradle itself, so reading the ARB costs the build no external
    // dependency and no network — which matters for a task that runs in every ordinary build.
    implementation(localGroovy())
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The root build's `check` cannot reach into `buildSrc`, so the generator's own tests are pinned to the
// artifact the root build *does* consume: no jar without a green generator. Cached, so it costs one run.
tasks.named("jar") {
    dependsOn(tasks.named("test"))
}
