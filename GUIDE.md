# Advanced Usage Guide: Kotlin External FIR Support (KEFS)

This guide provides a deep dive into all the user-facing settings, diagnostics, and workflows for the KEFS plugin.

> Some UI pictures may be outdated in details, core functionality remains.

## 1. Configuring Plugins (Settings)

All configuration is managed under <kbd>Tools</kbd> > <kbd>Kotlin Plugins</kbd>.

### General Settings

This tab controls the plugin's high-level behavior, primarily related to stability and exception
handling.

![settings_general.png](.github/pics/settings_general.png)

* **Kotlin Compiler IDE version:** Displays the current Kotlin version used by your IDE. This is
  the version your plugins *must* be compatible with.
* **Enable Kotlin plugin exception analyzer:** This is the plugin's core stability
  feature. When enabled, KEFS monitors the IDE for any exceptions thrown by a compiler
  plugin.
* **Automatically disable throwing plugins:** If the analyzer is enabled, this setting tells KEFS to
  automatically disable any plugin that throws an exception. You will be notified by a
  balloon pop-up.
* **Show confirmation dialog when clearing caches:** Toggles the confirmation pop-up for the "Clear Caches"
  action.

### Artifacts Settings

This is the main configuration panel where you define your repositories and link them to your compiler
plugins.

![settings_artifacts.png](.github/pics/settings_artifacts.png)

> Each change to these settings will reload the KEFS state. 
> This is an inexpensive operation when all plugins are already loaded, but the IDE will need to 
> lazily re-request all plugins. 

**Maven Repositories**
This section lists all repositories KEFS will search for compatible plugin jars.

* **Adding Repositories:** You can add two types of repositories:
    * **Remote (URL):** A standard remote repository like Maven Central or a private one.
    * **Local (File path):** A local directory on your file system that is structured as a Maven
      repository (e.g., your `build/repo` or `~/.m2/repository`). This is essential for local plugin
      development.
* **Editing Repositories:** You can edit any repository you've added. Default
  repositories (which are bundled with KEFS) cannot be edited.

Adding a new repository:

![settings_artifacts_repo_edit.png](.github/pics/settings_artifacts_repo_edit.png)

Editing the default repository:

![settings_artifacts_repo_edit_default.png](.github/pics/settings_artifacts_repo_edit_default.png)

**Kotlin Plugins**
This section defines the "bundles" for each compiler plugin. A bundle can consist of one or more artifacts
that work together.

* **Adding/Editing Plugins:** When you add or edit a plugin, you configure the following:
    * **Name:** A unique, human-readable name for the bundle (e.g., `kotlinx-rpc`).
    * **Coordinates:** The Maven coordinates (`groupId:artifactId`) for *each* artifact in the bundle, one
      per line. **Do not include the version.**
    * **Version matching:** This strategy tells KEFS how to find a compatible version:
        * **Exact:** The replacement plugin's library version must be identical to the one requested by the
          project.
        * **Same Major:** Finds the latest available plugin version that shares the same major version
          number.
        * **Latest:** Finds the absolute latest available version of the plugin, regardless of the project's
          version.
    * **Repositories:** A list of repositories from the defined
      above. KEFS will only search for this plugin in the selected repositories.
    * **Enable this plugin in the project:** A master on/off switch for this plugin bundle.
    * **Ignore plugin exceptions:** Disables the exception analyzer *only* for this specific
      plugin.

Adding a new plugin:

![settings_artifacts_plugin_edit.png](.github/pics/settings_artifacts_plugin_edit.png)

Editing the default plugin:

![settings_artifacts_plugin_edit_default.png](.github/pics/settings_artifacts_plugin_edit_default.png)

#### Storing Settings

When done and saved the settings, `.idea/kotlinPlugins.xml` will be created in your project. 
Add it to your VCS so that other developers can use the same workflow.

#### Version Patters

It is important to understand the version matching strategies.
Each plugin artifact must follow the same version pattern that KEFS understands:
```
<groupId>:<artifactId>:<kotlinVersion>-<libraryVersion>
```
Examples: 
```
# Artifact used in the project
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2:2.2.20-0.10.0

# Artifact that KEFS will find in a repository
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2:2.2.0-ij251-78-0.10.0 
```

See more how-to's in the [Developer guide](PLUGIN_AUTHORS.md).

---

## 2. Diagnosing Plugin State (Tool Window)

The <kbd>Kotlin Plugins Diagnostics</kbd> tool window is your primary view into what KEFS is doing. It shows the
real-time status of all configured plugin bundles and their artifacts.

The UI is split into a tree view of plugins on the left and a details panel on the right.

### Plugin Statuses

* **Successfully Loaded:** The plugin, all its artifacts versions were found and
  loaded successfully. The details pane will show information about the loaded jar and a link to its cache
  location.
  ![tools_success.png](.github/pics/tools_success.png)
    * You can also see a list of all classes that KEFS analyzed from the jar, which are used for exception
      matching.
      ![tools_jar_content.png](.github/pics/tools_jar_content.png)
* **Plugin is Not Requested:** The plugin is configured in settings but has not yet been requested by the
  IDE. Resolution is lazy and happens on demand.
  ![tools_skipped.png](.github/pics/tools_skipped.png)
* **Artifact is Disabled:** The plugin is disabled in the settings. You can enable it
  directly from the "details" pane.
  ![tools_diabled.png](.github/pics/tools_diabled.png)
* **Partial Resolve:** This means some artifacts in a bundle were found, but others
  were not. The entire plugin will be skipped until all parts are loaded successfully.
  ![tools_partial.png](.github/pics/tools_partial.png)
* **Failed to Fetch:** An error occurred during resolution (e.g. a network error, a file-already-exists
  exception). The details pane will show the error log.
  ![tools_failed_to_fetch.png](.github/pics/tools_failed_to_fetch.png)
* **Not Found:** The plugin or artifact was not found in any of the specified repositories with a
  compatible version. The details pane will show the resolution log.
  ![tools_not_found.png](.github/pics/tools_not_found.png)
* ** Exception in Runtime:** The plugin threw an exception during runtime. See the
  [Handling Runtime Exceptions](#3-handling-runtime-exceptions) section for more details.
  ![tools_exception.png](.github/pics/tools_exception.png) 

---

## 3. Handling Runtime Exceptions

This is a key workflow for ensuring IDE stability. When the Exception Analyzer is enabled and a plugin
throws an error during runtime, KEFS will catch it.

* **Banner Notification:** A banner will appear at the top of your editor, identifying the plugin that
  failed.
  ![notification_banner.png](.github/pics/notification_banner.png)
    * **Disable:** Immediately disables the failing plugin, which should restore your IDE's highlighting and analysis.
    * **Auto-disable:** Enables the "Automatically disable throwing plugins" setting for the future.
    * **Open diagnostics:** Opens the diagnostics tool window directly to the exception report.
* **Auto-Disable Notification:** If you have "Auto-disable" on, a balloon notification will appear when
  a plugin is automatically disabled.
  ![notification_ballon.png](.github/pics/notification_ballon.png)
* **Exception Report (in Tool Window):** The diagnostics window provides a full report.
  ![exception_overview.png](.github/pics/exception_overview.png)
    * It shows the exception(s) that occurred.
    * It provides an "Action suggestion", such as creating a failure report.
    * It shows the analyzed classes from the jar.
    * It provides a **full, highlighted stack trace**, pointing to the exact class and line within the
      plugin that caused the failure.
      ![exception_stacktrace.png](.github/pics/exception_stacktrace.png)
    * This exception information is preserved even after the plugin is disabled, allowing you to debug the
      issue.

---

## 4. Manual Actions

You can manually control KEFS using the <kbd>Find Action</kbd> (Ctrl/Cmd+Shift+A) menu.

![actions.png](.github/pics/actions.png)

* **Kotlin FIR External Support: Update Plugins:**
    * **What it does:** Immediately triggers the "Actualization" process for all plugins. This is
      useful if you just published a new version to a remote repository and want the IDE to pick it up without waiting
      for the 2-minute cycle.
* **Kotlin FIR External Support: Refresh Plugins:**
    * **What it does:** Clears the entire in-memory state of all plugins and re-runs the entire resolution
      and indexing process from scratch. This is a heavier action, useful if the plugin's state seems
      corrupt. It does not affect the local disk cache and preserves detected exceptions.
* **Kotlin FIR External Support: Clear Caches:**
    * **What it does:** Deletes all downloaded plugin jars from the local disk cache (
      `$USER_HOME/.kotlinPlugins/<kotlin-ide-version>`) for the current IDE's Kotlin version. 
      This will force KEFS to re-download
      all plugins.

---

## 5. Advanced Use Case: Plugin "Hot-Reload" for Developers

KEFS is a powerful tool for *developing* compiler plugins. By combining **Local Repositories** and **File Watching**,
you can create a "hot-reload" workflow.

1. **In your compiler plugin project:** Set up your build script to publish your plugin artifacts to a local directory (
   e.g., `build/repo`).
2. **In KEFS Settings:**
    * Go to <kbd>Tools</kbd> > <kbd>Kotlin Plugins</kbd> > <kbd>Artifacts</kbd>.
    * Add a new **Maven Repository**.
    * Select `Local (File path)`.
    * Set the `Path` to your plugin's local output directory (e.g.,
      `/path/to/your-plugin/build/repo`).
    * Go to your **Kotlin Plugins** settings and add a new plugin bundle.
    * Ensure your plugin bundle is configured with the correct coordinates and that it is linked to the new local
      repository you just added.
    * When done and saved the settings, `.idea/kotlinPlugins.xml` will be created in your project. 
      Add it to your VCS so that other developers can use the same workflow.
3. **Run your build:** Publish your plugin to the local repo.
4. **Work in the IDE:** KEFS will find, load, and cache your local plugin 
   (you may need to trigger "Update" manually in some cases).
5. **Make a change:** Go back to your compiler plugin code, make a change, and **re-publish** it to the local repo.
6. **See it update:** KEFS's file watchers will detect that the jar file in the local repository has
   changed. It will automatically invalidate the old version, load the new jar, and trigger
   an update in the Kotlin IDE plugin.

Your changes to the plugin will be reflected in the IDE's analysis and diagnostics almost immediately, without you
needing to restart the IDE.

See more details in the [Developer guide](PLUGIN_AUTHORS.md).
