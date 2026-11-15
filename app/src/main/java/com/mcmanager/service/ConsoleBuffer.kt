package com.mcmanager.service

import com.mcmanager.core.model.ConsoleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Ring buffer of console lines; the published list is updated at most every ~100 ms. */
class ConsoleBuffer(scope: CoroutineScope, private val cap: () -> Int) {
  private val lock = Any()
  private val lines = ArrayDeque<ConsoleLine>()
  private var seq = 0L
  private val _flow = MutableStateFlow<List<ConsoleLine>>(emptyList())
  val flow: StateFlow<List<ConsoleLine>> = _flow.asStateFlow()
  private val trigger = Channel<Unit>(Channel.CONFLATED)

  init {
    scope.launch {
      for (u in trigger) {
        publish()
        delay(100)
      }
    }
  }

  fun add(text: String, kind: ConsoleLine.Kind, time: Long = System.currentTimeMillis()) {
    synchronized(lock) {
      lines.addLast(ConsoleLine(++seq, time, text, kind))
      val max = cap().coerceAtLeast(50)
      while (lines.size > max) lines.removeFirst()
    }
    trigger.trySend(Unit)
  }

  private fun publish() {
    val snapshot = synchronized(lock) { lines.toList() }
    _flow.value = snapshot
  }
}
