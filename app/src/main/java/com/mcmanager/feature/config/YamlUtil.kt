package com.mcmanager.feature.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object YamlUtil {
  private fun loader() = Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = 16 * 1024 * 1024 }))

  /** Returns an error message, or null when [text] is valid YAML. */
  fun validate(text: String): String? = try {
    loader().loadAll(text).toList()
    null
  } catch (e: Exception) {
    e.message?.lineSequence()?.take(4)?.joinToString("\n") ?: "Invalid YAML"
  }

  /** Loads the first document; null/empty documents become an empty map. Throws on invalid YAML or non-map root. */
  @Suppress("UNCHECKED_CAST")
  fun loadMap(text: String): MutableMap<String, Any?> {
    val obj = loader().load<Any?>(text) ?: return LinkedHashMap()
    require(obj is Map<*, *>) { "Root is not a map" }
    return deepCopy(obj) as MutableMap<String, Any?>
  }

  fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> {
      val m = LinkedHashMap<String, Any?>()
      for ((k, x) in v) m[k.toString()] = deepCopy(x)
      m
    }
    is List<*> -> v.map { deepCopy(it) }.toMutableList()
    else -> v
  }

  fun dump(root: Map<String, Any?>): String {
    val o = DumperOptions().apply {
      defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
      indent = 2
      indicatorIndent = 0
      width = 200
      isPrettyFlow = false
    }
    return Yaml(o).dump(root)
  }
}
