# Kotlin External FIR Support

![Build](https://github.com/Mr3zee/kotlin-plugins/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/26480-kotlin-external-fir-support.svg)](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/26480-kotlin-external-fir-support.svg)](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support)

<!-- Plugin description -->

Run Kotlin compiler plugins in the IDE.

## TLDR
- Automatic Plugin Resolution and Safe Loading
- Exception Analysis
- Diagnostics & Management UI
- Manual Control
- **No Additional Configuration Required**

## Guides

Check out our advanced usage guide: [GUIDE.md](https://github.com/Mr3zee/kotlin-plugins/blob/main/GUIDE.md)

If you are a compiler plugin developer, check out our [plugin development guide](https://github.com/Mr3zee/kotlin-plugins/blob/main/PLUGIN_AUTHORS.md).

## Use Any Kotlin Compiler Plugin Inside IDE

By default, the IDE cannot safely run external Kotlin compiler plugins 
that are not bundled with the compiler itself (like `kotlinx-rpc`) 
because their version often conflicts with the IDE's internal Kotlin
compiler. This incompatibility can lead to exceptions that freeze your UI, syntax
highlighting, and code analysis.

**This plugin enables you to use these compiler plugins safely in your IDE**

1. **It enables plugins:** KEFS intercepts requests for plugins and automatically finds, downloads, and
   loads an IDE-compatible version in the background.
2. **It improves stability:** Even if a compatible plugin breaks and throws an exception, KEFS detects it,
   notifies you via a banner, and provides actions (like disabling the plugin) to restore your IDE's functionality
   immediately.

This solves the core instability problem and allows you to benefit from your compiler
plugins' IDE-specific features, like custom diagnostics, all within the editor.

**No Additional Configuration Required!**.

## Core Features

### 1. Automatic Plugin Resolution

KEFS automatically detects the compiler plugins used in your project. 
It then finds and downloads a compatible version from a
configurable list of Maven repositories (including local ones).

* **Flexible Version Matching:** Configure how the plugin finds compatible versions with strategies like
  `Exact`, `Same Major`, or `Latest`.
* **Background Caching:** All downloads and resolutions happen in the background to never block your
  UI. Resolved plugins are cached locally for instant access.
* **Local Development Hot-Reload:** Actively watches local Maven repositories for
  changes. When you re-publish a new version of your plugin locally, KEFS detects the file change
  and reloads it in the IDE automatically.

### 2. Exception Analysis

Compiler plugins should *never* throw exceptions, as this can freeze IDE analysis.
KEFS actively monitors the IDE for exceptions thrown by loaded compiler plugins.

When a failure is detected, KEFS provides immediate, actionable solutions:

* **Editor Banner:** A non-intrusive banner appears at the top of your editor, identifying the failing
  plugin.
* **Disable Plugin:** Instantly disable the failing plugin from the banner to restore your IDE's
  highlighting and analysis.
* **Auto-Disable:** A "set it and forget it" option that will automatically disable any plugin that throws
  an exception, notifying you with a small pop-up.

### 3. Diagnostics & Management UI

KEFS provides a full suite of UI tools to give you complete visibility and control over your compiler plugins.

* **"Kotlin Plugins Diagnostics" Tool Window:** A dedicated window that shows you every detected plugin and
  its status.
    * See which plugins were successfully loaded, which failed (e.g., `Failed To Fetch`, `Not Found`), and
      which haven't been requested yet.
    * Inspect detailed error messages for fetch/resolution failures.
    * View detailed reports for runtime exceptions, including stack traces, to help debug plugin
      failures.
* **Settings Page (<kbd>Tools</kbd> > <kbd>Kotlin Plugins</kbd>):** A new settings panel to manage all plugin
  behavior.
    * **Artifacts:** Configure your plugin "bundles" (e.g. if a plugin consists of multiple jars), set
      their version-matching strategy, and assign them to specific repositories, or use the default.
    * **Repositories:** Add, edit, and remove remote (URL) or local (file path) Maven
      repositories.
    * **General:** Toggle the Exception Analyzer on/off and manage the "auto-disable" feature.

### 4. Manual Control

Quickly manage the plugin's state from the `Find Action` (Ctrl/Cmd+Shift+A) menu:

* **Update Plugins:** Forces a check for new plugin versions in your repositories.
* **Refresh Plugins:** Clears the in-memory state and re-runs resolution.
* **Clear Caches:** Deletes all downloaded plugin jars from the disk cache.
* **Copy Kotlin IDE Version:** Copies the IDE's internal Kotlin compiler version to the clipboard.

## Support

If you encounter any issues, [report](https://github.com/Mr3zee/kotlin-plugins/issues) them on GitHub.

If any exceptions are thrown, you can use the built-in <kbd>Report to the 3rd Party</kbd> action to send a bug report to us.

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Kotlin External FIR Support"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/Mr3zee/kotlin-plugins/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
