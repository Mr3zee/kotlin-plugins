<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Kotlin External FIR Support Changelog

## [Unreleased]

## [0.2.0] - 2026-01-14

### Added

- Onboarding guide: https://github.com/Mr3zee/kotlin-plugins/blob/main/GUIDE.md
- For compiler plugin developers guide: https://github.com/Mr3zee/kotlin-plugins/blob/main/PLUGIN_AUTHORS.md
- **Plugin Replacement Engine:** Added the core mechanism to intercept Kotlin compiler plugin requests within the
  IDE. This allows KEFS to replace a project's plugin version (e.g. built for Kotlin `2.2.20`) with a
  version compatible with the IDE's internal compiler (e.g., `2.2.0-ij251-78`).
- **Plugin Bundles:** Added support for "bundles", which group multiple artifacts (e.g., `cli`, `k2`,
  `backend`, `common`) to be treated as a single compiler plugin.
- **Version Resolution Strategies:** Implemented three strategies for finding compatible plugin
  versions:
    * **Exact:** The library version must be identical to the one requested.
    * **Same Major:** Finds the latest version with the same major version number.
    * **Latest:** Finds the latest available version of the plugin.
- **Maven Repository Support:** Added support for configuring Maven repositories on a per-plugin
  basis. Two types of repositories are supported:
    * **Remote:** A repository specified by a URL.
    * **Local:** A local directory on the file system structured as a Maven repository.
- **Background Processing & Caching:** All plugin resolution, network access, and indexing now run in the
  background to ensure the UI and analysis remain responsive.
- **Local Disk Cache:** Resolved plugins are now cached locally in `$USER_HOME/.kotlinPlugins` to provide
  fast, on-demand access without repeated downloads.
- **Periodic Updates:** Implemented a background "Actualization" job that runs every 2 minutes to check for
  new versions of plugins.
- **Checksum Verification:** KEFS now calculates and compares MD5 checksums to avoid re-downloading jars
  that are already present and are up to date.
- **File Watching & Hot-Reload:** Added file watchers for all cached jars. For
  local repositories, KEFS uses symlinks to watch the original file, enabling automatic hot-reloading of a plugin in the
  IDE when it's re-published locally.
- **Project-Level Configuration:** All plugin and repository settings are now stored in the project's
  `.idea/kotlin-plugins.xml` file.
- **User Actions:** Added three new actions available from the "Find Action" (Ctrl/Cmd+Shift+A)
  menu:
    * `Kotlin FIR External Support: Update Plugins`
    * `Kotlin FIR External Support: Refresh Plugins`
    * `Kotlin FIR External Support: Clear Caches`
- **Project Sync Hook:** KEFS now hooks into project reloads (like a Gradle sync) to clear its state and
  adapt to configuration changes.
- **Runtime Exception Tracking:** Added a new feature to analyze all exceptions reported to the IDE's root
  logger.
- **Fault Detection:** KEFS analyzes the class FQ names of all loaded plugin jars. 
  If an exception's stack trace matches a class from a known plugin, KEFS identifies that plugin as the source of the
  failure.
- **Editor Banner Notification:** When a plugin throws an exception, a banner now appears at the top of the
  editor. This banner allows you to:
    * **Disable the plugin:** Immediately stops the failing plugin and restores IDE analysis.
    * **Auto-disable:** Enable a setting to automatically disable any plugin that throws an exception.
    * **Open diagnostics:** Jump to the new diagnostics tool window.
- **Auto-Disable:** Added an "Automatically disable throwing plugins" setting. When enabled, KEFS will
  disable failing plugins and show a balloon notification.
- **Global Toggle:** Exception Analysis can be enabled or disabled in the workspace
  settings.
- **"Kotlin Plugins Diagnostics" Tool Window:** Added a new tool window to provide a complete real-time
  overview of all configured compiler plugins and their artifacts.
- **Status & Error Reporting:** The diagnostics window shows the runtime status for all plugin
  versions:
    * **Success:** Plugin loaded successfully.
    * **Partial Success:** Some artifacts in a bundle loaded, but not all.
    * **Failed To Fetch / Not Found:** Shows detailed error messages if a plugin could not be
      resolved.
    * **Exception in runtime:** Shows a detailed report for any plugin that failed during
      execution.
    * **Skipped:** The plugin has not been requested by the project yet.
    * **Disabled:** The user disabled the plugin.
- **Exception & Jar Inspection:** The diagnostics panel can display the full list of analyzed classes in a
  jar.
- **Settings Page:** Added a new settings page under `Tools > Kotlin Plugins`.
    * **General Tab:** Toggle the Exception Analyzer and auto-disable feature.
    * **Artifacts Tab:** Manage all Maven repositories and Kotlin plugin configurations.
- **Confirmation Dialog:** Added a confirmation dialog for the "Clear Caches" action.

## [0.1.2] - 2025-04-17

### Fixed

- Rewrite file download lock
- Fix the infinite download cycle

## [0.1.1] - 2025-04-11

### Fixed

- File downloading race condition

## [0.1.0] - 2025-02-21

### Added

- `Clear 'Kotlin FIR External Support' Cache` IDE Action
- 251 IDE Versions Support

### Fixed

- Project-specific version resolution fixed
- "Container 'ProjectImpl@ services' was disposed" exception

## [0.0.2]

### Fixed

- Exception for disposed projects

## [0.0.1]

### Added

- Initial implementation
- Initial scaffold created
  from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

[Unreleased]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.0.2...v0.1.0
[0.0.2]: https://github.com/Mr3zee/kotlin-plugins/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/Mr3zee/kotlin-plugins/commits/v0.0.1
