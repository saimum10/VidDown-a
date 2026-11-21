// A tiny Gradle build around the TeamNewPipe/nanojson sources (a Maven-only project), so NewPipeExtractor's single JitPack
// dependency can be built from the checkout made by scripts/fetch_newpipe.py instead of fetched from JitPack. See docs/newpipe.md.
// Coordinates match what the extractor asks for (com.github.TeamNewPipe:nanojson); the root settings substitute it explicitly.
plugins {
    `java-library`
}

group = "com.github.TeamNewPipe"
// Informational only: the root settings substitute the coordinate whatever version the extractor asks for.
version = file("../newpipe.resolved.json").takeIf { it.isFile }
    ?.let { ((groovy.json.JsonSlurper().parse(it) as Map<String, Any?>)["nanojson"] as? Map<String, Any?>)?.get("commit") as? String }
    ?: "unresolved"

sourceSets {
    main {
        java.srcDir("../nanojson/src/main/java")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
}
