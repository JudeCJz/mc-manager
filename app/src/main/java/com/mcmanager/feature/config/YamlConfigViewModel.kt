package com.mcmanager.feature.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ServerState
import com.mcmanager.feature.files.FileOps
import com.mcmanager.feature.files.MAX_EDIT_BYTES
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class YKind { SECTION, BOOL, NUM, STR, LIST, NULL, COMPLEX }

data class YNode(
  val path: List<String>,
  val depth: Int,
  val kind: YKind,
  val text: String,
  val intLike: Boolean = false,
) {
  val key: String get() = path.last()
  val pathKey: String get() = path.joinToString("\u001f")
  val display: String get() = path.joinToString(".")
}

enum class YMode { FORM, RAW }

data class YamlUi(
  val loading: Boolean = true,
  val missing: Boolean = false,
  val tooBig: Boolean = false,
  val mode: YMode = YMode.FORM,
  val rawText: String = "",
  val rawOriginal: String = "",
  val nodes: List<YNode> = emptyList(),
  val edits: Map<String, String> = emptyMap(),
  val serverState: ServerState = ServerState.STOPPED,
  val saving: Boolean = false,
  val showCommentWarning: Boolean = false,
  val rawError: String? = null,
  val parseError: String? = null,
) {
  val dirty: Boolean get() = rawText != rawOriginal || edits.isNotEmpty()
  val running: Boolean get() = serverState == ServerState.RUNNING || serverState == ServerState.STARTING

  fun leafError(n: YNode, text: String): Boolean = when (n.kind) {
    YKind.NUM -> if (n.intLike) text.trim().toLongOrNull() == null else text.trim().toDoubleOrNull() == null
    YKind.BOOL -> text.trim().lowercase() !in setOf("true", "false")
    else -> false
  }

  fun hasErrors(): Boolean {
    val byKey = nodes.associateBy { it.pathKey }
    return edits.any { (k, v) -> byKey[k]?.let { leafError(it, v) } == true }
  }
}

@HiltViewModel
class YamlConfigViewModel @Inject constructor(
  private val servers: ServerRepository,
  private val controller: ServerController,
) : ViewModel() {

  private val _state = MutableStateFlow(YamlUi())
  val state: StateFlow<YamlUi> = _state.asStateFlow()

  private val _events = Channel<PropsEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  private var serverId = -1L
  private var target: File? = null
  private var tree: MutableMap<String, Any?> = LinkedHashMap()
  private var formWarned = false

  fun init(id: Long, rel: String) {
    if (serverId == id) return
    serverId = id
    viewModelScope.launch {
      val server = servers.get(id)
      val f = server?.let { FileOps.resolveInside(File(it.dir), rel) }
      if (f == null) {
        _state.update { it.copy(loading = false, missing = true) }
        return@launch
      }
      target = f
      launch { controller.runtime(id).collectLatest { rt -> _state.update { it.copy(serverState = rt.state) } } }
      load()
    }
  }

  fun load() {
    val f = target ?: return
    viewModelScope.launch {
      val text = withContext(Dispatchers.IO) {
        when {
          !f.isFile -> null
          f.length() > MAX_EDIT_BYTES -> ""
          else -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
        }
      }
      when {
        text == null -> _state.update { it.copy(loading = false, missing = true) }
        text.isEmpty() && f.length() > MAX_EDIT_BYTES -> _state.update { it.copy(loading = false, tooBig = true) }
        else -> {
          _state.update { it.copy(loading = false, missing = false, rawText = text, rawOriginal = text, edits = emptyMap()) }
          parseTree(text)
        }
      }
    }
  }

  private suspend fun parseTree(text: String): Boolean {
    val res = withContext(Dispatchers.Default) { runCatching { YamlUtil.loadMap(text) } }
    val m = res.getOrNull()
    return if (m != null) {
      tree = m
      val nodes = withContext(Dispatchers.Default) { buildList { flatten(m, emptyList(), 0, this) } }
      _state.update { it.copy(nodes = nodes, parseError = null, edits = emptyMap()) }
      true
    } else {
      tree = LinkedHashMap()
      _state.update {
        it.copy(nodes = emptyList(), parseError = res.exceptionOrNull()?.message?.lineSequence()?.firstOrNull() ?: "Invalid YAML")
      }
      false
    }
  }

  private fun flatten(m: Map<*, *>, path: List<String>, depth: Int, out: MutableList<YNode>) {
    for ((k, v) in m) {
      val p = path + k.toString()
      when {
        v is Map<*, *> -> {
          out.add(YNode(p, depth, YKind.SECTION, v.size.toString()))
          flatten(v, p, depth + 1, out)
        }
        v is Boolean -> out.add(YNode(p, depth, YKind.BOOL, v.toString()))
        v is Int || v is Long || v is BigInteger -> out.add(YNode(p, depth, YKind.NUM, v.toString(), intLike = true))
        v is Number -> out.add(YNode(p, depth, YKind.NUM, v.toString()))
        v is String -> out.add(YNode(p, depth, YKind.STR, v))
        v == null -> out.add(YNode(p, depth, YKind.NULL, ""))
        v is List<*> -> {
          if (v.all { it == null || it is String || it is Number || it is Boolean }) {
            out.add(YNode(p, depth, YKind.LIST, v.joinToString(", ") { it.toString() }))
          } else {
            out.add(YNode(p, depth, YKind.COMPLEX, ""))
          }
        }
        else -> out.add(YNode(p, depth, YKind.STR, v.toString()))
      }
    }
  }

  fun editLeaf(n: YNode, text: String) {
    _state.update { s ->
      val e = s.edits.toMutableMap()
      if (text == n.text) e.remove(n.pathKey) else e[n.pathKey] = text
      s.copy(edits = e)
    }
  }

  fun editRaw(text: String) = _state.update { it.copy(rawText = text, rawError = null) }

  fun setMode(m: YMode) {
    val s = _state.value
    if (m == s.mode) return
    viewModelScope.launch {
      if (m == YMode.RAW) {
        if (s.edits.isNotEmpty()) {
          if (s.hasErrors()) return@launch _events.trySend(PropsEvent.Failed("Fix the highlighted values first")).let { }
          val dumped = withContext(Dispatchers.Default) { YamlUtil.dump(applyEdits(s)) }
          _state.update { it.copy(mode = m, rawText = dumped, edits = emptyMap()) }
        } else {
          _state.update { it.copy(mode = m) }
        }
      } else {
        val err = withContext(Dispatchers.Default) { YamlUtil.validate(s.rawText) }
        if (err != null) {
          _state.update { it.copy(rawError = err) }
          return@launch
        }
        if (parseTree(s.rawText)) _state.update { it.copy(mode = m) }
        else _state.update { it.copy(rawError = it.parseError) }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun applyEdits(s: YamlUi): Map<String, Any?> {
    val root = YamlUtil.deepCopy(tree) as MutableMap<String, Any?>
    val byKey = s.nodes.associateBy { it.pathKey }
    for ((k, text) in s.edits) {
      val node = byKey[k] ?: continue
      var m: MutableMap<String, Any?> = root
      for (seg in node.path.dropLast(1)) m = m[seg] as? MutableMap<String, Any?> ?: continue
      val old = m[node.key]
      m[node.key] = convert(old, node.kind, text)
    }
    return root
  }

  private fun scalar(sample: Any?, t: String): Any? = when (sample) {
    is Boolean -> t.toBooleanStrictOrNull() ?: t
    is Int -> t.toIntOrNull() ?: t.toLongOrNull() ?: t
    is Long -> t.toLongOrNull() ?: t
    is Number -> t.toDoubleOrNull() ?: t
    else -> t
  }

  private fun convert(old: Any?, kind: YKind, text: String): Any? = when (kind) {
    YKind.BOOL -> text.trim().lowercase() == "true"
    YKind.NUM -> when (old) {
      is Int -> text.trim().toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
      is Long -> text.trim().toLongOrNull()
      is BigInteger -> text.trim().toBigIntegerOrNull()
      else -> text.trim().toDoubleOrNull()
    }
    YKind.LIST -> {
      val sample = (old as? List<*>)?.firstOrNull()
      text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { scalar(sample, it) }.toMutableList()
    }
    YKind.NULL -> if (text.isBlank()) null else text
    else -> text
  }

  fun requestSave() {
    val s = _state.value
    if (s.mode == YMode.FORM && s.edits.isNotEmpty() && !formWarned) {
      _state.update { it.copy(showCommentWarning = true) }
    } else {
      doSave()
    }
  }

  fun confirmCommentWarning() {
    formWarned = true
    _state.update { it.copy(showCommentWarning = false) }
    doSave()
  }

  fun dismissCommentWarning() = _state.update { it.copy(showCommentWarning = false) }

  fun dismissRawError() = _state.update { it.copy(rawError = null) }

  private fun doSave() {
    val s = _state.value
    val f = target ?: return
    if (s.hasErrors() || s.saving) return
    viewModelScope.launch {
      _state.update { it.copy(saving = true) }
      try {
        val text: String
        if (s.mode == YMode.FORM) {
          text = if (s.edits.isEmpty()) s.rawText else withContext(Dispatchers.Default) { YamlUtil.dump(applyEdits(s)) }
        } else {
          val err = withContext(Dispatchers.Default) { YamlUtil.validate(s.rawText) }
          if (err != null) {
            _state.update { it.copy(saving = false, rawError = err) }
            return@launch
          }
          text = s.rawText
        }
        withContext(Dispatchers.IO) { FileOps.atomicWrite(f, text) }
        _state.update { it.copy(rawText = text, rawOriginal = text, edits = emptyMap()) }
        parseTree(text)
        _events.trySend(PropsEvent.Saved(restartNeeded = s.running))
      } catch (e: Exception) {
        _events.trySend(PropsEvent.Failed(e.message ?: "error"))
      }
      _state.update { it.copy(saving = false) }
    }
  }

  fun restart() {
    viewModelScope.launch { controller.restart(serverId) }
  }
}
