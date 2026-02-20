package com.github.mr3zee.kefs

import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor

@Suppress("UnstableApiUsage")
internal interface VersionSpecificApi {
    suspend fun EelDescriptor.toEelApiVs(): EelApi
}

internal inline fun <T> vsApi(body: VersionSpecificApi.() -> T): T = with(VersionSpecificApiImpl) { body() }
