package com.github.mr3zee.intellijcompilerpluginswap

import com.intellij.openapi.externalSystem.ui.ExternalSystemIconProvider
import com.intellij.openapi.util.IconLoader

object KotlinPluginsIcons {
    val RefreshChanges = IconLoader.getIcon("/icons/refreshPlugins.svg", javaClass)
}

class KotlinPluginsExternalIconsProvider : ExternalSystemIconProvider {
    override val reloadIcon = KotlinPluginsIcons.RefreshChanges
}
