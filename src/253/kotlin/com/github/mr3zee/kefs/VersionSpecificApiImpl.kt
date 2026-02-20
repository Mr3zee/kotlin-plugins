package com.github.mr3zee.kefs

import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor

@Suppress("UnstableApiUsage")
internal object VersionSpecificApiImpl : VersionSpecificApi {
    override suspend fun EelDescriptor.toEelApiVs(): EelApi {
        return toEelApi()
    }
}
