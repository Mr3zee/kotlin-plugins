# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Kotlin External FIR Support (KEFS)** — an IntelliJ IDEA plugin that enables safe loading and management of external Kotlin compiler (FIR) plugins inside the IDE. It intercepts IDE requests for compiler plugins, finds/downloads IDE-compatible versions from Maven repositories, caches them locally, and monitors for runtime exceptions.

Plugin ID: `com.github.mr3zee.kotlinPlugins`

## Build & Development Commands

```bash
# Build the plugin (produces zip in build/distributions/)
./gradlew buildPlugin

# Run tests
./gradlew check

# Run a single test (JUnit 4)
./gradlew test --tests "com.github.mr3zee.kefs.MyPluginTest.testVersionComparison"

# Run the plugin in a sandbox IDE instance (K2 mode enabled)
./gradlew runIde --stacktrace

# Verify plugin compatibility with multiple IDE versions
./gradlew verifyPlugin

# Run for a specific IDE major version (platformVersion and kotlin.lang are auto-resolved from the IDE version map in gradle.properties)
./gradlew runIde -PpluginIdeVersionMajor=253 --stacktrace
```

Requires **JDK 21** (configured via Gradle toolchain). Uses **Gradle 9.2.0** with configuration cache and build cache enabled.

## Architecture

### Core Flow

1. **`KefsProvider`** implements `KotlinBundledFirCompilerPluginProvider` — the entry point called by the Kotlin IDE plugin when it needs a compiler plugin JAR. It matches requested JARs against configured plugin descriptors (via path/artifact-id matching or custom replacement patterns) and delegates to `KefsStorage`.

2. **`KefsStorage`** (project-level service) manages the full plugin lifecycle:
   - In-memory cache (`pluginsCache`) maps plugin names → artifact keys → `ArtifactState`
   - Lifecycle cache prevents redundant lookups within a single IDE analysis pass
   - Orchestrates JAR resolution: checks disk cache first, then triggers `KefsJarLocator`
   - Runs file watchers (via `WatchService`) on both cached and original (local repo) JAR directories for hot-reload
   - Handles cache invalidation with a debounce mechanism (workaround for KTIJ-37664)
   - Manages periodic auto-update via configurable interval

3. **`KefsJarLocator`** handles artifact resolution:
   - Fetches Maven `maven-metadata.xml` from remote URLs or local paths
   - Applies version matching (Exact/Same Major/Latest) across artifact bundles
   - Downloads JARs with checksum verification (MD5)
   - Supports `for-ide` classifier fallback
   - Local repository JARs are copied and symlinked for change tracking

4. **`KefsSettings`** (persistent project-level service, stored in `.idea/kotlin-plugins.xml`) holds repositories and plugin descriptors. Default state is loaded from `defaults.xml` via `KefsDefaultStateLoader`.

5. **`KefsExceptionAnalyzerService`** monitors IDE exceptions and matches stack traces against loaded plugin classes (analyzed by `KefsJarAnalyzer`) to identify which plugin caused a crash.

### Requested, Resolved, and Kotlin versions
'Version' word of an artifact can relate to different things.
All compiler plugins have a Kotlin and a Library version. By default artifact looks like this:
```
<group-id>:<artifact-id>-<kotlin-version>-<lib-version>
```
Example:
```
org.jetbrains.kotlinx:compiler-plugin-k2-2.2.0-ij251-78-0.10.2.jar
```
Here `2.2.0-ij251-78` is the Kotlin version and `0.10.2` is the Library version.

Library version can be requested or resolved:
- Requested – the version that comes from `KotlinBundledFirCompilerPluginProvider`
- Resolved – the version found by `KefsJarLocator` using the version matching strategy.

Kotlin version can be project version or IDE version:
- Project version – the one comes from `KotlinBundledFirCompilerPluginProvider`, same place as a Requested Library version.
- IDE Version – the one from `KotlinVersionService` and for which we look for artifacts.

### Key Domain Types (in `versions.kt`)

- `RequestedVersion` / `ResolvedVersion` — inline value classes distinguishing user-project library version from the resolved IDE-compatible library version
- `JarId` — identifies a loaded JAR (plugin name + maven ID + requested version); equality ignores resolved version
- `VersionedKotlinPluginDescriptor` — a plugin descriptor bound to a specific requested version
- `MatchFilter` — version matching strategy + requested version, used by `getMatching()`

### IDE-Version-Specific Code

The plugin supports multiple IntelliJ platform versions. `VersionSpecificApi` defines the interface; each `src/{251,252,253,261}/kotlin/.../VersionSpecificApiImpl.kt` provides the implementation. The active source set is selected at build time based on `pluginSinceBuild` in `gradle.properties`.

### Multi-Version Release

`pluginIdeVersionMajor` in `gradle.properties` controls version-specific builds. When set (e.g., `251`), it auto-populates `sinceBuild`, `untilBuild`, version suffix, `platformVersion`, and Kotlin compiler version from the `ide.<major>.*` map in `gradle.properties`. The CI release dispatcher (`release-dispatcher.yml`) reads the IDE versions from this map and triggers parallel release jobs.

### Adding a New IDE Major Version

When adding support for a new IDE major (e.g., `262`):

1. **`gradle.properties`** — add three map entries:
   ```properties
   ide.262.platformVersion = <full-build-number>
   ide.262.kotlinLang = <kotlin-version>
   ide.262.serializationVersion = <serialization-version>
   ```
   **Finding the platform version:** Browse the IntelliJ releases Maven repository index at `https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml` (follows redirects) and pick the latest `<major>.xxxxx.xx` build. For EAP/snapshot versions, use `<major>-EAP-SNAPSHOT`.

2. **`src/<major>/kotlin/.../VersionSpecificApiImpl.kt`** — create a new version-specific source directory implementing `VersionSpecificApi`. Copy from the nearest existing version and adapt to any API changes.

3. **Update `pluginSinceBuild`/`platformVersion`** in `gradle.properties` if this becomes the new default development target.

No changes needed in GitHub workflows — `release-dispatcher.yml` auto-discovers IDE versions from the `ide.*.platformVersion` entries in `gradle.properties`.

## Key Configuration

- `gradle.properties`: `platformVersion` sets the default target IntelliJ version; `ide.<major>.platformVersion`/`ide.<major>.kotlinLang` map auto-resolves versions per IDE major; `pluginSinceBuild`/`pluginUntilBuild` set compatibility range
- `gradle/libs.versions.toml`: dependency version catalog
- `src/main/resources/defaults.xml`: bundled default repositories and plugin descriptors
- `src/main/resources/META-INF/plugin.xml`: extension points, actions, and service declarations

## Documentation

All user-facing doc files must be kept in sync when making changes to actions, settings, features, or naming:

- `README.md`: plugin overview, feature descriptions, installation instructions
- `CHANGELOG.md`: release history and change descriptions
- `GUIDE.md`: advanced usage guide with action names, settings, troubleshooting steps
- `PLUGIN_AUTHORS.md`: developer guide for compiler plugin authors integrating with KEFS

Source of truth for action text: `src/main/resources/messages/KefsBundle.properties`

## Code Conventions

- All production source is under `com.github.mr3zee.kefs` package
- Kotlin `explicitApi()` mode is enabled — all public declarations need explicit visibility modifiers
- Most classes are `internal`
- Uses `kotlinx-serialization` for JSON (jar metadata) and `jsoup` for XML parsing (Maven metadata)
- IntelliJ platform services use constructor injection with `CoroutineScope` for structured concurrency