# Kotlin External FIR Support

![Build](https://github.com/Mr3zee/kotlin-plugins/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/26480-kotlin-external-fir-support.svg)](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/26480-kotlin-external-fir-support.svg)](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support)

<!-- Plugin description -->
# Description
This plugin allows the use of external Kotlin compiler plugins in a stable manner.

Currently, only [kotlinx-rpc](https://github.com/Kotlin/kotlinx.rpc) is supported.

## Troubleshooting
If any of the FIR plugins throws errors, they will appear as exceptions in the `Kotlin` plugin.
If you see a stacktrace for an exception thrown by `com.github.mr3zee.kotlinPlugin.*`, 
try invoking IDE Action `Clear 'Kotlin FIR External Support' Cache`. 
That may help with the problem.

Alternatively, delete the `.kotlinPlugins` directory, located in your user home directory.
If the problem persists, please report it.

If you encounter any issues, [report](https://github.com/Mr3zee/kotlin-plugins/issues) them on the GitHub.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Kotlin Plugins"</kbd> >
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
