package com.github.mr3zee.kefs

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

/**
 * Message bundle for all user‑facing strings in the plugin.
 * See: https://plugins.jetbrains.com/docs/intellij/internationalization.html
 */
private const val BUNDLE: String = "messages.KefsBundle"

internal object KefsBundle {
  private val INSTANCE: DynamicBundle = DynamicBundle(KefsBundle.javaClass, BUNDLE)

  @JvmStatic
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
    INSTANCE.getMessage(key, *params)
}
