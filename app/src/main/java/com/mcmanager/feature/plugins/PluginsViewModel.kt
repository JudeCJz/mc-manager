package com.mcmanager.feature.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A string resource (or raw text) that the UI resolves with a Context. */
data class UiText(val res: Int = 0, val args: List<Any> = emptyList(), val raw: String? = null)

enum class SortMode { RELEVANCE, DOWNLOADS, UPDATED }

data class BrowseItem(
  val source: Source,
  /** Modrinth project id, or Hangar slug. */
  val id: String,
  val title: String,
  val author: String,
  val description: String,
  val downloads: Long,
  val iconUrl: String?,
)

data class DetailState(
  val item: BrowseItem,
  val body: String? = null,
  val versions: List<VersionOption> = emptyList(),
  val loading: Boolean = true,
  val error: String? = null,
)

data class PluginsUiState(
  val server: ServerEntity? = null,
  val installedLoading: Boolean = true,
  val installed: List<InstalledItem> = emptyList(),
  val updates: Map<String, MrVersion> = emptyMap(),
  val checkingUpdates: Boolean = false,
  val updatesChecked: Boolean = false,
  val source: Source = Source.MODRINTH,
  val query: String = "",
  val sort: SortMode = SortMode.DOWNLOADS,
  val browseItems: List<BrowseItem> = emptyList(),
  val browseLoading: Boolean = false,
  val browseEnd: Boolean = false,
  val browseError: String? = null,
  val detail: DetailState? = null,
  val planning: Boolean = false,
  val plan: InstallPlan? = null,
  /** Title shown while downloading; null when idle. */
  val busyTitle: String? = null,
  val busyStep: StepProgress? = null,
) {
  val hangarAvailable: Boolean
    get() = server?.engine == Engine.PAPER || server?.engine == Engine.PURPUR
}

@OptIn(FlowPreview::class)
@HiltViewModel
class PluginsViewModel @Inject constructor(
  savedState: SavedStateHandle,
  private val servers: ServerRepository,
  private val controller: ServerController,
  private val repo: PluginRepository,
  private val modrinth: ModrinthApi,
  private val hangar: HangarApi,
  val icons: IconLoader,
) : ViewModel() {

  private val serverId: Long = savedState.get<Long>("id") ?: savedState.get<String>("id")?.toLongOrNull() ?: 0L

  private val _state = MutableStateFlow(PluginsUiState())
  val state: StateFlow<PluginsUiState> = _state.asStateFlow()

  private val _messages = Channel<UiText>(Channel.BUFFERED)
  val messages: Flow<UiText> = _messages.receiveAsFlow()

  private data class BrowseKey(val source: Source, val query: String, val sort: SortMode, val mc: String)

  private val browseKey = MutableStateFlow<BrowseKey?>(null)
  private var browseJob: Job? = null
  private var started = false
  private val pageSize = 20

  init {
    viewModelScope.launch {
      servers.observe(serverId).filterNotNull().collect { s ->
        val prev = _state.value.server
        _state.update { it.copy(server = s) }
        if (!started) {
          started = true
          refreshInstalled()
          pushKey()
        } else if (prev?.mcVersion != s.mcVersion || prev.engine != s.engine) {
          refreshInstalled()
          pushKey()
        }
      }
    }
    viewModelScope.launch {
      browseKey.filterNotNull().debounce(350).distinctUntilChanged().collect { reloadBrowse() }
    }
  }

  private fun pushKey() {
    val s = _state.value
    val server = s.server ?: return
    if (Compat.folderFor(server.engine) == null) return
    browseKey.value = BrowseKey(s.source, s.query, s.sort, server.mcVersion)
  }

  private fun say(res: Int, vararg args: Any) {
    _messages.trySend(UiText(res, args.toList()))
  }

  private fun sayError(t: Throwable) {
    _messages.trySend(UiText(R.string.plugins_error, listOf(t.message ?: t.javaClass.simpleName)))
  }

  private fun restartNote() {
    val running = controller.runtime(serverId).value.state
    if (running == ServerState.RUNNING || running == ServerState.STARTING) say(R.string.plugins_restart_to_load)
  }

  // ---- installed -----------------------------------------------------------------------

  fun refreshInstalled() {
    val server = _state.value.server ?: return
    viewModelScope.launch {
      _state.update { it.copy(installedLoading = true) }
      try {
        val list = repo.list(server)
        _state.update { it.copy(installed = list, installedLoading = false) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { it.copy(installedLoading = false) }
        sayError(e)
      }
    }
  }

  fun setEnabled(item: InstalledItem, enabled: Boolean) {
    val server = _state.value.server ?: return
    viewModelScope.launch {
      try {
        repo.setEnabled(server, item, enabled)
        refreshInstalled()
        restartNote()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        sayError(e)
      }
    }
  }

  fun delete(item: InstalledItem) {
    val server = _state.value.server ?: return
    viewModelScope.launch {
      try {
        repo.delete(server, item)
        _state.update { it.copy(updates = it.updates - item.baseName) }
        refreshInstalled()
        restartNote()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        sayError(e)
      }
    }
  }

  fun checkUpdates() {
    val server = _state.value.server ?: return
    if (_state.value.checkingUpdates) return
    viewModelScope.launch {
      _state.update { it.copy(checkingUpdates = true) }
      try {
        val items = repo.list(server)
        val found = repo.checkUpdates(server, items)
        val fresh = repo.list(server)
        _state.update { it.copy(installed = fresh, updates = found, checkingUpdates = false, updatesChecked = true) }
        if (found.isEmpty()) say(R.string.plugins_all_up_to_date)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { it.copy(checkingUpdates = false) }
        sayError(e)
      }
    }
  }

  fun updateItem(item: InstalledItem) {
    val newer = _state.value.updates[item.baseName] ?: return
    updateAll(listOf(item to newer))
  }

  fun updateAllPending() {
    val s = _state.value
    updateAll(s.installed.mapNotNull { i -> s.updates[i.baseName]?.let { i to it } })
  }

  private fun updateAll(pairs: List<Pair<InstalledItem, MrVersion>>) {
    val server = _state.value.server ?: return
    if (pairs.isEmpty() || _state.value.busyTitle != null) return
    viewModelScope.launch {
      try {
        for ((item, newer) in pairs) {
          _state.update { it.copy(busyTitle = item.displayName, busyStep = null) }
          repo.update(server, item, newer) { p -> _state.update { it.copy(busyStep = p) } }
          _state.update { it.copy(updates = it.updates - item.baseName) }
        }
        restartNote()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        sayError(e)
      } finally {
        _state.update { it.copy(busyTitle = null, busyStep = null) }
        refreshInstalled()
      }
    }
  }

  // ---- browse --------------------------------------------------------------------------

  fun setSource(s: Source) {
    if (s == _state.value.source) return
    _state.update { it.copy(source = s, browseItems = emptyList(), browseEnd = false, browseLoading = true) }
    pushKey()
  }

  fun setQuery(q: String) {
    _state.update { it.copy(query = q) }
    pushKey()
  }

  fun setSort(s: SortMode) {
    if (s == _state.value.sort) return
    _state.update { it.copy(sort = s, browseItems = emptyList(), browseEnd = false, browseLoading = true) }
    pushKey()
  }

  private fun reloadBrowse() {
    browseJob?.cancel()
    _state.update { it.copy(browseItems = emptyList(), browseEnd = false, browseError = null) }
    loadPage()
  }

  fun loadMore() {
    val s = _state.value
    if (s.browseLoading || s.browseEnd || s.server == null) return
    loadPage()
  }

  private fun loadPage() {
    val s0 = _state.value
    val server = s0.server ?: return
    val offset = s0.browseItems.size
    browseJob?.cancel()
    browseJob = viewModelScope.launch {
      _state.update { it.copy(browseLoading = true, browseError = null) }
      try {
        val (items, total) = fetchPage(server, s0.source, s0.query, s0.sort, offset)
        _state.update {
          val merged = it.browseItems + items
          it.copy(
            browseItems = merged,
            browseLoading = false,
            browseEnd = items.size < pageSize || (total != null && merged.size >= total),
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { it.copy(browseLoading = false, browseError = e.message ?: e.javaClass.simpleName, browseEnd = true) }
      }
    }
  }

  private suspend fun fetchPage(
    server: ServerEntity,
    source: Source,
    query: String,
    sort: SortMode,
    offset: Int,
  ): Pair<List<BrowseItem>, Int?> = when (source) {
    Source.MODRINTH -> {
      val r = modrinth.search(
        query = query.trim(),
        projectType = Compat.modrinthProjectType(server.engine),
        categories = Compat.modrinthLoaders(server.engine),
        mcVersion = server.mcVersion,
        index = when (sort) {
          SortMode.RELEVANCE -> "relevance"
          SortMode.DOWNLOADS -> "downloads"
          SortMode.UPDATED -> "updated"
        },
        offset = offset,
        limit = pageSize,
      )
      r.hits.map {
        BrowseItem(Source.MODRINTH, it.projectId, it.title, it.author, it.description, it.downloads, it.iconUrl)
      } to r.totalHits
    }
    Source.HANGAR -> {
      val r = hangar.search(
        query = query.trim(),
        platform = "PAPER",
        mcVersion = server.mcVersion,
        sort = when (sort) {
          SortMode.RELEVANCE -> null
          SortMode.DOWNLOADS -> "-downloads"
          SortMode.UPDATED -> "-updated"
        },
        offset = offset,
        limit = pageSize,
      )
      r.result.map {
        BrowseItem(
          Source.HANGAR, it.namespace.slug, it.name, it.namespace.owner, it.description,
          it.stats.downloads, it.avatarUrl,
        )
      } to r.pagination.count
    }
  }

  // ---- detail + install ------------------------------------------------------------------

  fun openDetail(item: BrowseItem) {
    val server = _state.value.server ?: return
    _state.update { it.copy(detail = DetailState(item)) }
    viewModelScope.launch {
      try {
        val body = runCatching {
          when (item.source) {
            Source.MODRINTH -> plainText(modrinth.project(item.id).body)
            Source.HANGAR -> item.description
          }
        }.getOrNull()
        _state.update { st -> st.copy(detail = st.detail?.takeIf { it.item == item }?.copy(body = body)) }
        val versions = repo.versionsFor(server, item.source, item.id, item.title)
        _state.update { st -> st.copy(detail = st.detail?.takeIf { it.item == item }?.copy(versions = versions, loading = false)) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { st ->
          st.copy(detail = st.detail?.takeIf { it.item == item }?.copy(loading = false, error = e.message ?: e.javaClass.simpleName))
        }
      }
    }
  }

  fun closeDetail() {
    _state.update { it.copy(detail = null) }
  }

  fun requestInstall(option: VersionOption) {
    val server = _state.value.server ?: return
    if (!option.downloadable) return
    viewModelScope.launch {
      _state.update { it.copy(planning = true) }
      try {
        val plan = repo.plan(server, option, _state.value.installed)
        _state.update { it.copy(planning = false, plan = plan) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { it.copy(planning = false) }
        sayError(e)
      }
    }
  }

  fun cancelPlan() {
    _state.update { it.copy(plan = null) }
  }

  fun confirmInstall() {
    val server = _state.value.server ?: return
    val plan = _state.value.plan ?: return
    _state.update { it.copy(plan = null, busyTitle = plan.main.projectTitle, busyStep = null) }
    viewModelScope.launch {
      try {
        repo.install(server, plan) { p -> _state.update { it.copy(busyStep = p) } }
        say(R.string.plugins_installed_ok, plan.main.projectTitle)
        restartNote()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        sayError(e)
      } finally {
        _state.update { it.copy(busyTitle = null, busyStep = null) }
        refreshInstalled()
      }
    }
  }

  fun bestOf(list: List<VersionOption>): VersionOption? = repo.best(list)

  /** Is this project already in the sidecar index? */
  fun isInstalled(option: VersionOption): Boolean =
    _state.value.installed.any { i ->
      i.index?.let { it.projectId == option.projectId && it.source == option.source.name } == true
    }
}

/** Rough Markdown/HTML -> plain text, trimmed. */
internal fun plainText(md: String, max: Int = 1500): String {
  var t = md
  t = t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
  t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
  t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
  t = t.replace(Regex("<[^>]+>"), "")
  t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
  t = t.replace(Regex("[*_`>~]{1,3}"), "")
  t = t.replace(Regex("\\n{3,}"), "\n\n").trim()
  return if (t.length > max) t.take(max).trimEnd() + "…" else t
}
