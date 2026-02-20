# Developer Guide

KEFS is a powerful tool for *developing* compiler plugins too.

## 0. Why would need it?

Core problem is explained [here](GUIDE.md#0-problem-statement), here is tldr for plugin developers:

Compiler API is not stable. This means that your plugin compiled with 2.0.0 Kotlin probably will
not work with Kotlin 2.2.20 due to binary incompatible changes.
When running a regular build, for example, this will result in a build failure, 
with a error which may be confusing for a user.

Here is the fun part:
IDE runs a Kotlin complier under the hood to provide syntax highlighting, auto-completions.
It **can** also render symbols and diagnostics created by a compiler plugin.
This makes the IDE DX much better - no red code, no error only on build, etc.
If your plugin generated user-facing code or/and diagnostics - you want that to work properly.

Now the catch and the reason there is this guide:

\- What version of Kotlin complier does IDE run?
\- It's [complicated](#21-kotlin-compiler-version-location)

\- How do I know it is compatible?
\- It's [complicated](#23-compile-against-specific-kotlin-compiler-version)

\- Is it even possible and do I really need it?
\- **That's easy!** Yes and yes! You want your users to have good DX,
that's why you created a compiler plugin in the first place! 
Red code and broken diagnostics in IDE is not good DX.
KEFS and the guide below will help you make your users happy\*.

\* It's still not easy to do and maintain. 
We are thinking on how we can make process easier for plugin authors. 
KEFS is the first step.

## 1. Plugin Development

> Use the [Template](https://github.com/Kotlin/compiler-plugin-template) to get started.
> It is updated with the latest compiler plugin API and the latest enhancements for KEFS features.

There are certain criteria that must be met for a plugin to be observed by KEFS:

* The plugin must be published to a Maven repository, local or remote.
* If a plugin consists of multiple artifacts, they must be published to the same repository
  and described as a single plugin in [settings](GUIDE.md#1-configuring-plugins-settings)
* If a plugin uses external dependencies,
  they must be packed in the jar(s) as the plugin artifact AND
  be postprocessed by the [shadow jar](https://gradleup.com/shadow/configuration/relocation/):
  dependencies must be relocated with a unique package name.
  This is vital for KEFS to be able to properly analyze possible exceptions thrown by the plugin.
  Otherwise, KEFS may produce annoying false-positive diagnostics.
* The plugin must follow a strict versioning schema:
    * Artifact version must be in a form of `<kotlin-version>-<lib-version>`,
      where `<kotlin-version>` is the Kotlin **compiler** version it was compiled against
      and `<lib-version>` is a version of the library.
    * Both `<kotlin-version>` and `<lib-version>` must be follow [Semantic Versioning](https://semver.org/).

Artifacts example:

```
# Used in a user project
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-cli:2.2.20-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2:2.2.20-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-backend:2.2.20-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-common:2.2.20-0.10.0

# Consumed by KEFS
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-cli:2.2.0-ij251-78-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-k2:2.2.0-ij251-78-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-backend:2.2.0-ij251-78-0.10.0
org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-common:2.2.0-ij251-78-0.10.0
```

See also: details about [Version Matching](GUIDE.md#version-matching) strategies. 

## 2. Publishing Compatible Versions

KEFS defines compatibility by the `<kotlin-version>` part in the artifact version.
It must match the version of the Kotlin compiler inside a user's current IDE.

Figuring out the exact version of the Kotlin compiler inside a user's IDE 
is [not trivial](#21-kotlin-compiler-version-location).
And it must be done before publication.

These Kotlin versions are published to this repository: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies

Note that some dependencies change their name, like:
```
# Stable coordinates
org.jetbrains.kotlin:kotlinx-serialization-compiler-plugin

# IDE version coordinates
org.jetbrains.kotlin:kotlinx-serialization-compiler-plugin-for-ide
```

From here, different approaches may be taken to figure out the versions your plugin needs to be compiled against.
I'll describe the abstract idea, which some tools that may help in this may come later (though, this is not guaranteed).

### 2.1. Kotlin Compiler Version Location

The Kotlin compiler version of an IDE can be found here:
https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/util/project-model-updater/resources/model.properties.

It is the `kotlincVersion` property.

### 2.2. Listing Kotlin Compiler Versions for Several IDEA Versions

All IDEA versions can be found here: https://www.jetbrains.com/updates/updates.xml
Look for the `<product name="IntelliJ IDEA">` and there you can look into three main sections:

* IC-IU-RELEASE-licensing-RELEASE - stable releases
* IC-IU-EAP-licensing-RELEASE - RC releases
* IC-IU-EAP-licensing-EAP - EAP releases

All versions have `fullNumber="<build-number>"` attribute, which is the build number of the release.
This number now can be used to detect the Kotlin compiler version bundled with the release:

```
https://raw.githubusercontent.com/JetBrains/intellij-community/refs/tags/idea/<build-number>/plugins/kotlin/util/project-model-updater/resources/model.properties
```

For example:
https://raw.githubusercontent.com/JetBrains/intellij-community/refs/tags/idea/253.28086.51/plugins/kotlin/util/project-model-updater/resources/model.properties

Select a number of IDEA versions you want to support and find the `kotlincVersion` property for each of them.
Note they may be the same for different IDEA versions.

### 2.3. Compile Against Specific Kotlin Compiler Version

You will need to compile your plugin against all versions of the Kotlin compiler that you support.
This includes stable versions of Kotlin and the discussed above "IDE versions".

That will mean that for different versions and will need to have slightly different code in your plugin,
because of the different unstable APIs.

I recommend using some templating engine to generate the code for different Kotlin compiler versions.

You can take a look at how kotlinx-rpc solves it:
 * Custom Gradle plugin: https://github.com/Kotlin/kotlinx-rpc/blob/main/gradle-conventions/src/main/kotlin/util/csm/template.kt
 * Docs: https://github.com/Kotlin/kotlinx-rpc/blob/main/docs/environment.md#how-to-work-with-the-compiler-plugin

Some public solution for this problem may come later (though, this is not guaranteed).

## 3. Plugin "Hot-Reload" in KEFS

KEFS is a powerful tool for *developing* compiler plugins too. 
By combining **Local Repositories** and **File Watching**, you can create a "hot-reload" workflow.

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
    * When done and saved the settings, `.idea/kotlin-plugins.xml` will be created in your project.
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

## 4. Replacement patterns
If you develop your plugin and want to use KEFS in the same repo you might encounter a problem:

When the plugin is loaded in the project – it is using the path the jar in `build` directory.
But it is not a valid Maven repository path, so KEFS will miss it on request.

More than that, this jar can have a different name from the one that you publish, 
even to a local repository.

### Example: 

You have the following project structure:
```
.
├── settings.gradle.kts
├── build.gradle.kts
├── build
│   └── repo # this is a local maven repository
│       └── ... (org/example/prefix-compiler-plugin/<version>/prefix-compiler-plugin-<version>.jar)
├── compiler-plugin
│ ├── build.gradle.kts
│ ├── settings.gradle.kts
│ ├── build
│ │   └── libs
│ │       └── compiler-plugin-1.0.0.jar # this is jar used in the project build
│ └── src
│     └── main
│         ├── kotlin
│         │   └── ...
└── compiler-plugin-tests
    ├── build.gradle.kts
    └── src
        ├── test
        │ └── kotlin
        │     └── ...
        ├── test-gen
        │   └── ...
        └── testData
            ├── box
            └── diagnostics
```

Here, `compiler-plugin` is an *included build*, and `compiler-plugin-1.0.0.jar` is a jar produced by the module
and used in the `compiler-plugin-tests` module. 
Path to this jar is `build/libs/compiler-plugin-1.0.0.jar`, which is not a valid Maven repository path.

More to that, you decided to publish your plugin with the prefix `prefix-` in its artifact coordinates:
`org.example:prefix-compiler-plugin:2.2.20-1.0.0`.

In this case, you can tell KEFS to use these replacement patterns:
 - Detection: `<artifact-id>`
 - Search: `prefix-<artifact-id>`

Note, that is assumed that the artifact id is `compiler-plugin` in this case.

Read more about how to set up replacement patterns in [KEFS Guide](GUIDE.md#6-advanced-use-case-replacement-patterns).

## 5. Troubleshooting

If you encounter issues while developing or testing your plugin with KEFS, refer to the [Troubleshooting](GUIDE.md#7-troubleshooting) section in the main guide.
