pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VidDown"
include(":app", ":browser", ":core", ":data", ":download", ":engine-api", ":engine-direct", ":engine-newpipe", ":engine-ytdlp")

// NewPipeExtractor is NOT stored in this repository and NOT pinned: every build uses the LATEST stable release.
// scripts/fetch_newpipe.py (needs git + Python 3) finds the newest vX.Y.Z tag of TeamNewPipe/NewPipeExtractor, clones it into
// third_party/NewPipeExtractor and the nanojson commit it names into third_party/nanojson, and records the exact result in
// third_party/newpipe.resolved.json. Both are then compiled as composite builds, so no binary repository (and not JitPack) is contacted.
//   -PnewpipeRef=v0.26.5   (or env NEWPIPE_REF)     build a specific tag/branch/commit instead of the latest release
//   -PnewpipeUpdate=never  (or env NEWPIPE_UPDATE)  do not ask the network when sources are already on disk (offline work)
// See docs/newpipe.md.
run {
    val ref = providers.gradleProperty("newpipeRef").orNull ?: System.getenv("NEWPIPE_REF") ?: "latest"
    val update = providers.gradleProperty("newpipeUpdate").orNull ?: System.getenv("NEWPIPE_UPDATE") ?: "auto"
    val script = file("scripts/fetch_newpipe.py")
    var exitCode = -1
    var launchError: java.io.IOException? = null
    for (python in listOf("python3", "python")) {
        try {
            val process = ProcessBuilder(python, script.absolutePath, "ensure", "--ref", ref, "--update", update)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { println("[newpipe] $it") }
            exitCode = process.waitFor()
            launchError = null
            break
        } catch (e: java.io.IOException) {
            launchError = e
        }
    }
    if (launchError != null) {
        throw GradleException("Python 3 was not found (tried python3 and python). It is needed to fetch the latest NewPipeExtractor: " +
            "install Python 3.11+ or run scripts/fetch_newpipe.py yourself.", launchError)
    }
    if (exitCode != 0) {
        throw GradleException("Fetching NewPipeExtractor failed (exit code $exitCode, details above). " +
            "Offline? Run once online, then build with NEWPIPE_UPDATE=never.")
    }
}
val newpipeSources = listOf("third_party/NewPipeExtractor", "third_party/nanojson")
newpipeSources.forEach { dir ->
    if (!file("$dir/.git").exists()) {
        throw GradleException("$dir is missing after scripts/fetch_newpipe.py ran. Run: python3 scripts/fetch_newpipe.py ensure")
    }
}
includeBuild("third_party/nanojson-build") {
    dependencySubstitution {
        substitute(module("com.github.TeamNewPipe:nanojson")).using(project(":"))
    }
}
includeBuild("third_party/NewPipeExtractor") {
    dependencySubstitution {
        substitute(module("net.newpipe:extractor")).using(project(":extractor"))
    }
}
