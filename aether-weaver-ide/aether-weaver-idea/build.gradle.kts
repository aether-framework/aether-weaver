plugins {
    id("java")
    id("checkstyle")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "de.splatgames.aether.weaver"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // intellijIdea(…), not intellijIdeaCommunity(…). The Community artefact stopped being
        // published separately at 2025.3; the Gradle plugin says so itself if you use the old one.
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // The Plugin Verifier itself. `untilBuild` is deliberately unset, which means the plugin
        // claims compatibility with every future IDE — a claim only the verifier can check, and
        // only if it is here to be run.
        pluginVerifier()
    }
    // The framework's own selector grammar and diagnostic catalogue. Reimplementing either in
    // the plugin would guarantee that the IDE and the build eventually disagree about what a
    // selector means, which is the one thing this plugin exists to prevent.
    implementation("de.splatgames.aether.weaver:aether-weaver-api:${providers.gradleProperty("aetherWeaverVersion").get()}")

    // The engine, for one reason: the four injection points that name an operation inside a
    // method are matched against the instruction stream, and the ordinal that disambiguates them is
    // counted there too. The plugin reads the compiled target with the engine's own ModelViews and
    // then asks the engine's own PointResolver whether what it is about to write selects the
    // operation the user picked. A second implementation of that matching would agree today and
    // disagree later, and the disagreement would surface as AW1043 on generated code.
    //
    // It costs nothing to carry: the engine's only dependencies are this API and the JetBrains
    // annotations. No ASM — the Java 25 ClassFile API is what both sides read bytecode with, and
    // the IDE runs on JBR 25.
    implementation("de.splatgames.aether.weaver:aether-weaver-engine:${providers.gradleProperty("aetherWeaverVersion").get()}")

    // JUnit 4, not 5. BasePlatformTestCase descends from junit.framework.TestCase, so the
    // platform's fixture tests are JUnit 3/4 in shape whatever the rest of the world uses.
    testImplementation("junit:junit:4.13.2")

    // The annotation processor, on the *test* classpath only. ProcessorCrossCheckTest runs one
    // corpus of deliberately wrong weaves through the processor and through this plugin's
    // inspections and compares the diagnostic codes. Two implementations of one rule drift, and
    // this is the only thing in either build that would notice.
    testImplementation("de.splatgames.aether.weaver:aether-weaver-processor:${providers.gradleProperty("aetherWeaverVersion").get()}")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// Being outside the Maven reactor means nothing here was covered by the build that covers
// everything else. The same checkstyle.xml and the same tool version are used deliberately: two
// configurations would drift, and the drift would show up as a rule that holds in one half of the
// project and not the other.
checkstyle {
    toolVersion = "10.21.1"
    configFile = file("../../build-config/checkstyle.xml")
    isIgnoreFailures = false
}

// The sample is compiled by `compileSample` rather than by a source set, so `checkstyleMain` and
// `checkstyleTest` never see it. It is real published usage and is held to the same rules.
val checkstyleSample by tasks.registering(Checkstyle::class) {
    source = fileTree("sample/src")
    include("**/*.java")
    // Two corpus files declare a dozen package-private classes each, one per diagnostic being
    // demonstrated, and no type that shares the file's name. OuterTypeFilename is right about
    // them and wrong about what they are for; splitting them into twenty files would make the
    // corpus harder to read to satisfy a rule about file naming. Excluded whole, because the
    // shared checkstyle.xml has no SuppressionFilter and adding one would change the Maven build.
    exclude("**/com/acme/payments/Reported.java", "**/com/acme/payments/ReportedExtensions.java")
    classpath = files()
    configFile = file("../../build-config/checkstyle.xml")
}

// Resolves every {@link} and parses the HTML, which no test in this project does. `missing` is
// deliberately excluded: JavadocCoverageTest owns completeness and reports it per member, and
// leaving it in here would keep the gate red until the last file is written while saying less.
tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        // doclint reports as warnings, and `isFailOnError` only fails on errors — without this the
        // task prints every broken link and then succeeds, which is worse than having no gate at
        // all because the build claims the links were checked.
        addBooleanOption("Werror", true)
    }
    isFailOnError = true
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            // untilBuild is deliberately unset: the plugin stays compatible with future releases,
            // and the Plugin Verifier in CI is what tells us before users do.
            untilBuild = provider { null }
        }
    }

    // The other half of leaving `untilBuild` unset. Saying "compatible with everything after 262"
    // is a promise, and `recommended()` is what checks it: it resolves the IDE releases the plugin
    // claims to support and runs the verifier against each. Without this the promise is untested,
    // and users find out at their next IDE update.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// The sample project's classpath: the published API, resolved exactly as the plugin resolves it.
val sampleApi: Configuration by configurations.creating

dependencies {
    sampleApi("de.splatgames.aether.weaver:aether-weaver-api:${providers.gradleProperty("aetherWeaverVersion").get()}")
}

/**
 * Compiles the sample against the published API.
 *
 * The sample's whole value is that it is real usage, and real usage that nobody compiles decays
 * into wishful usage. This fails the build the moment an annotation the sample writes stops
 * existing — which is exactly the disagreement a hand-written stub could never surface.
 */
val compileSample by tasks.registering(JavaCompile::class) {
    source = fileTree("sample/src/main/java")
    classpath = sampleApi
    destinationDirectory = layout.buildDirectory.dir("sample-classes")
    options.compilerArgs.add("-proc:none")
    options.encoding = "UTF-8"
}

/**
 * Fails if the sample's POM and the plugin's own API version have drifted apart.
 *
 * They are declared in two files because the sample is a standalone Maven project on purpose —
 * it resolves the API the way a user's project does. Two declarations drift, and this one drifts
 * silently: the sample would simply resolve a different API than the plugin was built against, and
 * the mismatch would look like the plugin misbehaving.
 */
val checkSampleVersion by tasks.registering {
    val pom = layout.projectDirectory.file("sample/pom.xml").asFile
    val expected = providers.gradleProperty("aetherWeaverVersion")
    doLast {
        val declared = Regex("<aether\\.weaver\\.version>(.*?)</aether\\.weaver\\.version>")
            .find(pom.readText())?.groupValues?.get(1)
            ?: throw GradleException("sample/pom.xml declares no <aether.weaver.version>")
        if (declared != expected.get()) {
            throw GradleException(
                "sample/pom.xml pins aether-weaver $declared but this build uses ${expected.get()}; " +
                    "the sample would resolve a different API than the plugin was built against")
        }
    }
}

tasks.check {
    dependsOn(compileSample, checkSampleVersion, checkstyleSample, tasks.javadoc)
}

tasks.runIde {
    // Opens the sample project, so trying the plugin needs no setup at all. RunIdeTask extends
    // JavaExec, and a path argument is how IDEA is told which project to open — the same thing the
    // `idea <dir>` launcher does.
    dependsOn(checkSampleVersion)
    args(layout.projectDirectory.dir("sample").asFile.absolutePath)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
    options.encoding = "UTF-8"
}
