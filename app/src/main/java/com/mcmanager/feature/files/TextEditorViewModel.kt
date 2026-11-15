package com.mcmanager.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.feature.config.YamlUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class EditorError { NONE, OUTSIDE, TOO_BIG, NOT_FOUND, READ_FAILED }

data class EditorState(
  val loading: Boolean = true,
  val error: EditorError = EditorError.NONE,
  val original: String = "",
  val saving: Boolean = false,
  val savedTick: Int = 0,
  val saveError: String? = null,
  /** Non-null when validation failed and the user must decide whether to save anyway. */
  val validationError: String? = null,
)

@HiltViewModel
class TextEditorViewModel @Inject constructor(
  private val servers: ServerRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(EditorState())
  val state: StateFlow<EditorState> = _state.asStateFlow()

  private var file: File? = null
  private var started = false

  fun init(serverId: Long, path: String) {
    if (started) return
    started = true
    viewModelScope.launch {
      val server = servers.get(serverId)
      val result = withContext(Dispatchers.IO) {
        if (server == null) return@withContext EditorState(loading = false, error = EditorError.NOT_FOUND)
        val f = FileOps.resolveInside(File(server.dir), path)
          ?: return@withContext EditorState(loading = false, error = EditorError.OUTSIDE)
        file = f
        when {
          !f.isFile -> EditorState(loading = false, error = EditorError.NOT_FOUND)
          f.length() > MAX_EDIT_BYTES -> EditorState(loading = false, error = EditorError.TOO_BIG)
          else -> try {
            EditorState(loading = false, original = f.readText(Charsets.UTF_8))
          } catch (e: Exception) {
            EditorState(loading = false, error = EditorError.READ_FAILED)
          }
        }
      }
      _state.value = result
    }
  }

  /** [force] skips validation. */
  fun save(text: String, force: Boolean) {
    val f = file ?: return
    viewModelScope.launch {
      if (!force) {
        val err = withContext(Dispatchers.Default) { validate(f.name, text) }
        if (err != null) {
          _state.update { it.copy(validationError = err) }
          return@launch
        }
      }
      _state.update { it.copy(saving = true, validationError = null, saveError = null) }
      try {
        withContext(Dispatchers.IO) { FileOps.atomicWrite(f, text) }
        _state.update { it.copy(saving = false, original = text, savedTick = it.savedTick + 1) }
      } catch (e: Exception) {
        _state.update { it.copy(saving = false, saveError = e.message ?: "error") }
      }
    }
  }

  fun dismissValidation() = _state.update { it.copy(validationError = null) }

  fun clearSaveError() = _state.update { it.copy(saveError = null) }

  private fun validate(name: String, text: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "json" -> try {
        Json.parseToJsonElement(text)
        null
      } catch (e: Exception) {
        e.message ?: "Invalid JSON"
      }
      "yml", "yaml" -> YamlUtil.validate(text)
      else -> null
    }
  }
}
