package com.github.mr3zee.kotlinPlugins

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

/**
 * Message bundle for all user‑facing strings in the plugin.
 * See: https://plugins.jetbrains.com/docs/intellij/internationalization.html
 */
private const val BUNDLE: String = "messages.KotlinPluginsBundle"

internal object KotlinPluginsBundle {
  private val INSTANCE: DynamicBundle = DynamicBundle(KotlinPluginsBundle.javaClass, BUNDLE)

  @JvmStatic
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
    INSTANCE.getMessage(key, *params)
}
