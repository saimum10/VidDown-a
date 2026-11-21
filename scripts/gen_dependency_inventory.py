#!/usr/bin/env python3
"""Generate docs/dependencies.md from gradle/libs.versions.toml and native/bundle.lock.json.

Versions and the native-bundle facts are read from those files; only the licence/source metadata below is maintained by hand.
A test fails when a catalog entry has no metadata or when the committed file is stale:  python3 scripts/gen_dependency_inventory.py
"""
import json
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APACHE = "Apache-2.0"
# catalog key -> (display name, source, licence, scope)
META = {
    "androidx-core-ktx": ("AndroidX Core KTX", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-webkit": ("AndroidX WebKit (switches off the X-Requested-With header of the in-app browser)", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "desugar-jdk-libs-nio": ("Android core library desugaring (desugar_jdk_libs_nio, as the extractor requires)", "https://maven.google.com (AOSP)", "GPL-2.0-with-classpath-exception (OpenJDK-derived) / Apache-2.0: see the artifact's own LICENSE", "build + shipped (java.time etc. for API < 26)"),
    "newpipe-extractor": ("NewPipeExtractor (latest release, fetched and built from source as a composite build at build time; no binary)", "https://github.com/TeamNewPipe/NewPipeExtractor", "GPL-3.0-or-later", "shipped (NewPipe engine)"),
    "okhttp": ("OkHttp (Direct HTTP engine; brings Okio)", "https://github.com/square/okhttp", APACHE, "shipped"),
    "androidx-activity-compose": ("AndroidX Activity Compose", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-compose-bom": ("Jetpack Compose BOM", "https://maven.google.com (AOSP)", APACHE, "build (versions only)"),
    "androidx-compose-foundation": ("Compose Foundation", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-compose-material3": ("Compose Material 3", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-compose-ui": ("Compose UI", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-compose-ui-test": ("Compose UI test (JUnit4)", "https://maven.google.com (AOSP)", APACHE, "test only"),
    "androidx-compose-ui-test-manifest": ("Compose UI test manifest", "https://maven.google.com (AOSP)", APACHE, "debug/test only"),
    "androidx-compose-ui-tooling": ("Compose UI tooling", "https://maven.google.com (AOSP)", APACHE, "debug only"),
    "androidx-compose-ui-tooling-preview": ("Compose UI tooling preview", "https://maven.google.com (AOSP)", APACHE, "shipped (annotations)"),
    "androidx-datastore-preferences": ("AndroidX DataStore Preferences", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-lifecycle-runtime-compose": ("AndroidX Lifecycle Runtime Compose", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-lifecycle-viewmodel-compose": ("AndroidX Lifecycle ViewModel Compose", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-navigation-compose": ("AndroidX Navigation Compose", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "androidx-test-core": ("AndroidX Test Core", "https://maven.google.com (AOSP)", APACHE, "test only"),
    "androidx-test-ext-junit": ("AndroidX Test JUnit ext", "https://maven.google.com (AOSP)", APACHE, "test only"),
    "androidx-test-runner": ("AndroidX Test Runner", "https://maven.google.com (AOSP)", APACHE, "test only"),
    "junit": ("JUnit 4", "https://github.com/junit-team/junit4", "EPL-1.0", "test only"),
    "kotlinx-coroutines-android": ("kotlinx.coroutines Android", "https://github.com/Kotlin/kotlinx.coroutines", APACHE, "shipped"),
    "kotlinx-coroutines-core": ("kotlinx.coroutines Core", "https://github.com/Kotlin/kotlinx.coroutines", APACHE, "shipped"),
    "room-compiler": ("Room compiler (KSP processor)", "https://maven.google.com (AOSP)", APACHE, "build only"),
    "room-ktx": ("Room KTX", "https://maven.google.com (AOSP)", APACHE, "shipped"),
    "room-runtime": ("Room runtime", "https://maven.google.com (AOSP)", APACHE, "shipped"),
}
PLUGINS = {
    "android-application": ("Android Gradle Plugin (application)", "https://maven.google.com (AOSP)", APACHE),
    "android-library": ("Android Gradle Plugin (library)", "https://maven.google.com (AOSP)", APACHE),
    "compose": ("Kotlin Compose compiler plugin", "https://github.com/JetBrains/kotlin", APACHE),
    "kotlin-jvm": ("Kotlin Gradle plugin (JVM)", "https://github.com/JetBrains/kotlin", APACHE),
    "ksp": ("Kotlin Symbol Processing", "https://github.com/google/ksp", APACHE),
    "room": ("Room Gradle plugin", "https://maven.google.com (AOSP)", APACHE),
}


def load_catalog() -> dict:
    return tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))


def resolve(entry: dict, versions: dict) -> str:
    """Version of a catalog entry: `version.ref` points into [versions]; no version means the Compose BOM decides."""
    ref = entry.get("version", {}).get("ref") if isinstance(entry.get("version"), dict) else entry.get("version.ref")
    if ref:
        return versions[ref]
    if isinstance(entry.get("version"), str):
        return entry["version"]
    return "managed by the Compose BOM"


def missing_metadata(catalog: dict) -> list:
    return sorted(set(catalog["libraries"]) - set(META)) + sorted(set(catalog["plugins"]) - set(PLUGINS))


def render(catalog: dict, lock: dict) -> str:
    v = catalog["versions"]
    out = ["# Dependency inventory", "",
           "GENERATED by `python3 scripts/gen_dependency_inventory.py` from `gradle/libs.versions.toml` and `native/bundle.lock.json`. Do not edit by hand.",
           "Licence and source columns are maintained in the script. Versions are what the build asks for; they have not been confirmed by a build in the authoring sandbox.",
           "No dependency is modified. Apache-2.0 components require their NOTICE/licence text to be shipped (see THIRD_PARTY_NOTICES.md).", "",
           "## Libraries", "", "| Name | Version | Source | Licence | Used in |", "|---|---|---|---|---|"]
    for key in sorted(catalog["libraries"]):
        name, source, lic, scope = META[key]
        out.append(f"| {name} | {resolve(catalog['libraries'][key], v)} | {source} | {lic} | {scope} |")
    out += ["| Kotlin standard library | with Kotlin " + v["kotlin"] + " | https://github.com/JetBrains/kotlin | " + APACHE + " | shipped |", "",
            "## Build plugins (never shipped)", "", "| Name | Version | Source | Licence |", "|---|---|---|---|"]
    for key in sorted(catalog["plugins"]):
        name, source, lic = PLUGINS[key]
        out.append(f"| {name} | {resolve(catalog['plugins'][key], v)} | {source} | {lic} |")
    out += ["", "## Native bundle (designated, exclusive source of CPython, yt-dlp, FFmpeg and the Python runtime)", "",
            f"* Repository: {lock['repository']}",
            "* Every tool is released on its own (`<tool>-v<version>`); the desktop-only NewPipe helper of that repository is not used by the Android app.", ""]
    tools = lock.get("tools", {})
    if not tools:
        out += ["**UNPINNED**: no tool release is pinned yet (`python3 scripts/update_native_bundle.py propose`). The component list below is carried over from the previous pin.", ""]
    else:
        out += ["| Tool | Release tag | Tag commit | Build commit |", "|---|---|---|---|"]
        for tool, t in tools.items():
            out.append(f"| {tool} | `{t['release_tag']}` | `{t['release_tag_commit']}` | `{t['build_commit']}` |")
        out.append("")
    out += ["| Component | Tool | Version | Licence | Licence verified | Notes |", "|---|---|---|---|---|---|"]
    for c in lock["components"]:
        out.append(f"| {c['name']} | {c.get('tool', '')} | {c['version']} | {c['license']} | {'yes' if c.get('license_verified') else '**NO**'} | {c.get('notes', '')} |")
    if tools:
        out += ["", "| Tool | ABI | Artifact (original path) | Size | SHA-256 |", "|---|---|---|---|---|"]
        for tool, t in tools.items():
            entries = sorted(t["abis"].items()) if "abis" in t else [("any", t["artifact"])]
            for abi, e in entries:
                out.append(f"| {tool} | {abi} | {e.get('original_repository_path', e['artifact'])} | {e['size']} | `{e['sha256']}` |")
    out += ["", "| ABI | Runtime verification (the three tools together) |", "|---|---|"]
    for abi, e in sorted(lock["abis"].items()):
        out.append(f"| {abi} | {e['verification']['runtime_on_device_or_emulator']} |")
    out += ["", "SHA-256 values are calculated locally and equal the repository's published `SHA256SUMS.txt`; they are not independently authenticated.",
            "", "## Built from source (pinned git submodules, composite builds)", "",
            "* NewPipeExtractor (GPL-3.0-or-later; the newest stable release, fetched at build time, exact tag in About and BUILD_INFO) and, for it, TeamNewPipe/nanojson (Apache-2.0 per the licence header of 14 of its 15 main source files; the repository has no LICENSE file, see docs/newpipe.md). Nothing is pinned: see scripts/fetch_newpipe.py. The extractor's own dependencies (jsoup, jsr305, protobuf-javalite, rhino) come from Maven Central.",
            "", "## Planned, not integrated", "",
            "* yt-dlp, CPython, FFmpeg: only through the native bundle above (Phase 7).", ""]
    return "\n".join(out)


def main() -> int:
    catalog = load_catalog()
    missing = missing_metadata(catalog)
    if missing:
        print(f"ERROR: no metadata for {missing}", file=sys.stderr)
        return 2
    lock = json.loads((ROOT / "native/bundle.lock.json").read_text(encoding="utf-8"))
    (ROOT / "docs/dependencies.md").write_text(render(catalog, lock), encoding="utf-8")
    print("wrote docs/dependencies.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
