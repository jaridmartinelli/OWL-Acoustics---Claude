package com.owlacoustics.roomplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.persistence.RoomSession
import com.owlacoustics.domain.persistence.SaveOutcome
import com.owlacoustics.domain.persistence.SessionState
import com.owlacoustics.domain.placement.PlacementOptimizer
import com.owlacoustics.domain.workflow.AnalysisBundle
import com.owlacoustics.domain.workflow.AnalysisRunner
import com.owlacoustics.domain.workflow.RESULT_ANALYSIS
import com.owlacoustics.domain.workflow.RESULT_PLACEMENT
import com.owlacoustics.domain.workflow.RESULT_RECOMMENDATIONS
import com.owlacoustics.roomplanner.data.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JobProgress(val label: String, val fraction: Double?)

class RoomViewModel(private val c: AppContainer, val roomId: String) : ViewModel() {
    val session: RoomSession?
    val loadError: String?

    init {
        val (s, err) = c.session(roomId)
        session = s; loadError = err
    }

    val state: StateFlow<SessionState>? = session?.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _job = MutableStateFlow<JobProgress?>(null)
    val job: StateFlow<JobProgress?> = _job
    private var running: Job? = null

    fun say(msg: String) { _messages.tryEmit(msg) }

    private fun report(o: SaveOutcome?) {
        when (o) {
            is SaveOutcome.Failed -> say("Not saved: ${o.reason}")
            is SaveOutcome.Blocked -> say(o.reason)
            else -> {}
        }
    }

    /** Applies a validated command against the latest durable model; rejections are explained, never applied. */
    fun edit(onDone: (() -> Unit)? = null, f: (RoomModel) -> EditResult) {
        val s = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val (r, o) = s.edit(f)
            if (r is EditResult.Rejected) { if (r.reason != "No change.") say(r.reason) } else report(o)
            if (onDone != null) withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun undo() { val s = session ?: return; viewModelScope.launch(Dispatchers.IO) { report(s.undo()) } }
    fun redo() { val s = session ?: return; viewModelScope.launch(Dispatchers.IO) { report(s.redo()) } }
    fun retry() { val s = session ?: return; viewModelScope.launch(Dispatchers.IO) { report(s.retry()) } }
    fun discard() { val s = session ?: return; viewModelScope.launch(Dispatchers.IO) { s.discard() } }
    fun rename(name: String) { val s = session ?: return; viewModelScope.launch(Dispatchers.IO) { report(s.rename(name)) } }

    fun cancelJob() { running?.cancel(); running = null; _job.value = null }

    /** Background analysis bound to the inputs it started from; late or obsolete results are discarded. */
    fun runAnalysis() = runJob("Running supported analysis…") { m ->
        RESULT_ANALYSIS to AnalysisRunner.encode(AnalysisRunner.analyze(m))
    }

    fun runPlacement() = runJob("Searching placements…") { m ->
        RESULT_PLACEMENT to AnalysisRunner.encode(PlacementOptimizer.optimize(m) { f -> _job.value = JobProgress("Searching placements…", f) })
    }

    fun runRecommendations(analysis: AnalysisBundle, placement: PlacementOptimizer.Result?) = runJob("Preparing recommendations…") { m ->
        RESULT_RECOMMENDATIONS to AnalysisRunner.encode(AnalysisRunner.recommend(m, analysis, placement))
    }

    private fun runJob(label: String, work: (RoomModel) -> Pair<String, String>) {
        val s = session ?: return
        running?.cancel()
        val ticket = s.ticket()
        val model = s.state.value.model
        _job.value = JobProgress(label, null)
        running = viewModelScope.launch {
            try {
                val (kind, payload) = withContext(Dispatchers.Default) { work(model) }
                val accepted = withContext(Dispatchers.IO) { s.acceptResult(ticket, kind, payload) }
                if (!accepted) say("The room changed while this was running, so the result was discarded. Run it again for the current room.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Could not finish: ${e.message}")
            } finally {
                _job.value = null
            }
        }
    }

    class Factory(private val c: AppContainer, private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RoomViewModel(c, id) as T
    }
}
