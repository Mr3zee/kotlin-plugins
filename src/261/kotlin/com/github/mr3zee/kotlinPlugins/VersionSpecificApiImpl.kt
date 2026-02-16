package com.github.mr3zee.kotlinPlugins

import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.toEelApi

@Suppress("UnstableApiUsage")
internal object VersionSpecificApiImpl : VersionSpecificApi {
    override suspend fun EelDescriptor.toEelApiVs(): EelApi {
        return toEelApi()
    }
}
