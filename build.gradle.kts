// The Uchiyomi extension for Mihon / Tachiyomi / Tachimanga.
//
// One module, built the way Mihon's loader expects (see ExtensionLoader.kt in mihonapp/mihon): a tiny APK
// declaring the `tachiyomi.extension` feature, a SourceFactory named in the manifest, and a versionName
// whose prefix is the extensions-lib version it was compiled against. Everything the extension calls at
// runtime -- OkHttp, kotlinx.serialization, the preference classes -- is provided by the host app, so those
// are `compileOnly`: shipping a second copy would bloat the APK and could shadow the host's.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.artifact.ScopedArtifact
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

// AGP 9 compiles Kotlin itself; the Kotlin Gradle plugin only has to be on the build classpath (the
// serialization compiler plugin needs it), not applied. Applying kotlin-android on top is an error there.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Bump `versionCode` on every release. It is the whole of what "newer" means to an extension store.
 * versionName MUST be "<lib>.<code>": Mihon reads the lib version from the prefix when the manifest lacks
 * `tachiyomix.extensionLib`, and stores show the name verbatim.
 */
val extLib = "1.6"
val extVersionCode = 4
val extVersionName = "$extLib.$extVersionCode"

/** The pinned source id: md5("uchiyomi/all/1") truncated the way HttpSource.generateId does. Frozen forever. */
val sourceId = 8683375824843625513L

android {
    namespace = "eu.kanade.tachiyomi.extension.all.uchiyomi"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.uchiyomi"
        minSdk = 26
        targetSdk = 36
        versionCode = extVersionCode
        versionName = extVersionName
        // Read by Uchiyomi.kt at runtime, so one constant governs the manifest, the store index and the code.
        buildConfigField("long", "SOURCE_ID", "${sourceId}L")
        buildConfigField("String", "EXT_LIB", "\"$extLib\"")
        manifestPlaceholders["extLib"] = extLib
    }

    signingConfigs {
        create("release") {
            // Present on CI (decoded from a secret) and on a maintainer's machine; absent on a contributor's,
            // where the debug key is fine for a local build that nobody installs from a store.
            val ks = rootProject.file("signingkey.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (rootProject.file("signingkey.jks").exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Everything the host app provides at runtime. tachiyomix brings the androidx.preference stubs with it;
    // Injekt (how an extension reaches the host's preference store) and rxjava (the type of the legacy
    // fetch* members R8 still has to see) are runtime-scoped in its POM, so they are named here.
    compileOnly(libs.tachiyomix)
    compileOnly(libs.okhttp)
    compileOnly(libs.serialization.json)
    compileOnly(libs.coroutines.core)
    compileOnly(libs.injekt)
    compileOnly(libs.rxjava)

    // The tests never construct the source (its stubs throw outside a host); they exercise Mapping and the
    // DTOs against captured responses, which needs only the serialization runtime and the model constants.
    testImplementation(libs.junit)
    testImplementation(libs.tachiyomix)
    testImplementation(libs.serialization.json)
}

/**
 * The JVM flavour of the extension, for Suwayomi (and any other host that runs extensions on a real JVM).
 *
 * Suwayomi converts an uploaded APK back to JVM bytecode with dex2jar, and on its JDK 25 that conversion
 * produced classes without stack-map frames -- the verifier refused them. The fix is the one keiyoushi
 * settled on: publish a jar built straight from the compiled classes, which carry correct frames, next to
 * the APK, and point the store's `jarUrl` at it. Contents mirror theirs: the merged manifest as text XML,
 * the program classes (unminified -- size does not matter on a server and there is nothing to hide),
 * and the APK's resources so the icon can be read.
 */
abstract class CreateExtensionJarTask : DefaultTask() {
    @get:InputFiles abstract val jars: ListProperty<RegularFile>
    @get:InputFiles abstract val dirs: ListProperty<Directory>
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction
    fun create() {
        val apk = apkDir.get().asFile.walkTopDown().first { it.extension == "apk" }
        val written = HashSet<String>()
        fun entry(name: String) = ZipEntry(name).apply { time = 0L }
        JarOutputStream(outputJar.get().asFile.outputStream().buffered()).use { out ->
            out.putNextEntry(entry("AndroidManifest.xml"))
            manifestFile.get().asFile.inputStream().use { it.copyTo(out) }
            out.closeEntry()
            dirs.get().forEach { dir ->
                val root = dir.asFile
                root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val name = f.relativeTo(root).invariantSeparatorsPath
                    if (written.add(name)) {
                        out.putNextEntry(entry(name)); f.inputStream().use { it.copyTo(out) }; out.closeEntry()
                    }
                }
            }
            jars.get().forEach { jar ->
                JarFile(jar.asFile).use { src ->
                    src.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }.forEach { e ->
                        if (written.add(e.name)) {
                            out.putNextEntry(entry(e.name)); src.getInputStream(e).use { it.copyTo(out) }; out.closeEntry()
                        }
                    }
                }
            }
            JarFile(apk).use { src ->
                src.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.startsWith("res/") || it.name == "resources.arsc") }
                    .sortedBy { it.name }
                    .forEach { e ->
                        out.putNextEntry(entry(e.name)); src.getInputStream(e).use { it.copyTo(out) }; out.closeEntry()
                    }
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val task = tasks.register<CreateExtensionJarTask>("createReleaseExtensionJar") {
            manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            outputJar.set(layout.buildDirectory.file("outputs/jar/tachiyomi-all.uchiyomi-v$extVersionName.jar"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toGet(ScopedArtifact.CLASSES, CreateExtensionJarTask::jars, CreateExtensionJarTask::dirs)
    }
}

/** Writes the facts the publish script needs, so the index is generated from the build rather than retyped. */
tasks.register("sourceInfo") {
    val out = layout.buildDirectory.file("source-info.properties")
    outputs.file(out)
    doLast {
        Properties().apply {
            setProperty("packageName", "eu.kanade.tachiyomi.extension.all.uchiyomi")
            setProperty("name", "Uchiyomi")
            setProperty("lang", "all")
            setProperty("versionCode", extVersionCode.toString())
            setProperty("versionName", extVersionName)
            setProperty("extLib", extLib)
            setProperty("sourceId", sourceId.toString())
            // AGP names the file app-release.apk; scripts/publish-repo.py copies it into the store under this name.
            setProperty("apk", "tachiyomi-all.uchiyomi-v$extVersionName.apk")
        }.store(out.get().asFile.writer(), "generated by ./gradlew sourceInfo")
    }
}
