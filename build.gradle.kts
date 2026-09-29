import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    `java-library`
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.tricatch"
version = "0.1.0"

application {
    mainClass.set("tricatch.oe.proxy.standalone.OeProxyMain")
    applicationName = "oe-proxy"
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    // sources/javadoc jars are produced by the vanniktech publish plugin (see mavenPublishing below)
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
    }
    isFailOnError = false
}

repositories {
    mavenCentral()
}

// slf4j-simple, for the standalone CLI's console output only - the library itself stays on
// slf4j-api alone (see the class-level comment on OeProxyMain), since the embedding application
// brings its own logback binding. Not extended from any runtimeElements/apiElements configuration,
// so it never appears in the published POM.
val standaloneRuntime by configurations.creating

dependencies {
    api("org.slf4j:slf4j-api:2.0.18")
    implementation("org.yaml:snakeyaml:2.5")
    api("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")
    api("io.github.tricatch:gotpache-keytool:0.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcutil-jdk18on:1.85")
    implementation("io.github.azagniotov:ant-style-path-matcher:1.0.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    standaloneRuntime("org.slf4j:slf4j-simple:2.0.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// simplelogger.properties lives here, not in src/main/resources, so it never ends up on the
// published library jar's classpath - it configures slf4j-simple, which only the standalone
// CLI (run/installDist) ever has on its classpath.
val standaloneResourcesDir = file("src/standalone/resources")

tasks.named<JavaExec>("run") {
    classpath = classpath.plus(standaloneRuntime).plus(files(standaloneResourcesDir))
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = classpath!!.plus(standaloneRuntime).plus(files(standaloneResourcesDir))
}

distributions {
    main {
        contents {
            from(standaloneRuntime) {
                into("lib")
                // slf4j-simple transitively pulls slf4j-api, already present in lib/ via the
                // library's own runtimeClasspath (api("org.slf4j:slf4j-api")) - keep that copy.
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
            // Copied as a "resources" subdirectory of lib/ so its name matches the classpath
            // entry ($APP_HOME/lib/resources) that startScripts generates for the
            // standaloneResourcesDir FileCollection entry above.
            from(standaloneResourcesDir) {
                into("lib/resources")
            }
        }
    }
}

// Self-contained runnable jar for the standalone CLI (`java -jar ... run routes.yml` / `... ca`)
// with every runtime dependency bundled, on top of the plain library jar (task `jar`) that stays
// dependency-free and is the one published to Maven. Built from runtimeClasspath plus the
// standalone-only slf4j-simple binding and its simplelogger.properties, so the fat jar never
// needs anything else on the classpath.
tasks.shadowJar {
    archiveClassifier = "all"
    configurations = listOf(project.configurations.runtimeClasspath.get(), standaloneRuntime)
    from(standaloneResourcesDir)
    manifest {
        attributes["Main-Class"] = "tricatch.oe.proxy.standalone.OeProxyMain"
        attributes["Implementation-Version"] = project.version
    }
    mergeServiceFiles()
    // Signature files from signed dependency jars (BouncyCastle) can't be verified once other
    // jars' classes are merged in - a signed-jar JVM would refuse to load them otherwise.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Shadow 8.x otherwise adds a shadowRuntimeElements variant to the java component, which would
// make the published Gradle module metadata / POM advertise the fat jar alongside the thin one.
// Only the thin jar (task `jar`) is meant for Maven - keep the published component describing it
// alone.
(components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(
    configurations["shadowRuntimeElements"]
) {
    skip()
}

// Shadow's ShadowApplicationPlugin (auto-applied because both `application` and `shadow` are
// present) registers its own "shadow" distribution (shadowDistZip/shadowDistTar, wired through
// startShadowScripts/installShadowDist) and - like the `application` plugin does for the main
// distribution - the Distribution plugin publishes its zip/tar as "archives" configuration
// artifacts, which is what `assemble` transitively depends on. startShadowScripts still
// configures itself through the legacy `CreateStartScripts.mainClassName` Groovy ConventionMapping
// API, which Gradle 9 no longer exposes, so merely realizing it (to compute shadowDistZip/Tar's
// task dependencies) throws ("You can't map a property that does not exist:
// propertyName=mainClassName"). We don't need that distribution at all - the fat jar is consumed
// directly via `java -jar`, not through a generated shadow start script - so drop its "oe-proxy-
// shadow" zip/tar artifacts from "archives" here, before `assemble` ever has to realize them.
configurations.getByName("archives").artifacts.removeIf { it.name == "${project.name}-shadow" }

mavenPublishing {
    publishToMavenCentral()
    // Sign only when a key is configured, so publishToMavenLocal works on a machine without one.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("io.github.tricatch", "oe-proxy", version.toString())

    pom {
        name = "oe-proxy"
        description = "Lightweight Java reverse proxy that terminates HTTPS for many virtual hosts on one port using per-domain certificates signed by your own root CA. Embed it as a library or run it standalone."
        url = "https://github.com/tricatch/oeProxy"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "tricatch"
                name = "tricatch"
                url = "https://github.com/tricatch"
            }
        }
        scm {
            url = "https://github.com/tricatch/oeProxy"
            connection = "scm:git:https://github.com/tricatch/oeProxy.git"
            developerConnection = "scm:git:https://github.com/tricatch/oeProxy.git"
        }
    }
}

// Signing is skipped above when no key is configured, which keeps publishToMavenLocal usable
// anywhere - but an unsigned upload to Central would only be rejected later by Portal validation.
// Fail fast instead when a Central publish task is actually scheduled without a signing key.
gradle.taskGraph.whenReady {
    val publishesToCentral = allTasks.any { it.name.contains("MavenCentral") }
    if (publishesToCentral && !providers.gradleProperty("signingInMemoryKey").isPresent) {
        throw GradleException("signingInMemoryKey is not set in ~/.gradle/gradle.properties - Maven Central requires signed artifacts")
    }
}
