package com.wangzan101.mika.voice.ui

import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.model.MigrationResult
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceCatalogStore
import com.wangzan101.mika.voice.model.VoiceCredentials
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceProbeState
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.voice.model.toError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.providerVoiceUsage

internal enum class SttSilenceDecision {
    Continue,
    NoSpeechTimeout,
    FinishSpeech,
}

internal object SttSilencePolicy {
    const val SPEECH_LEVEL_THRESHOLD = 0.08f
    const val NO_SPEECH_TIMEOUT_MS = 6_000L
    const val SILENCE_AFTER_SPEECH_MS = 1_600L
    const val MIN_RECORDING_MS = 900L
    const val HARD_TIMEOUT_MS = 15_000L

    /**
     * 多久判一次静音。录音电平是 StateFlow,电平不变就不再发事件(比如麦克风被系统静音、一直是 0),
     * 只靠电平事件判断时超时永远到不了,所以按固定间隔判。
     */
    const val TICK_MS = 200L

    fun evaluate(
        heardSpeech: Boolean,
        silenceElapsedMs: Long,
        totalElapsedMs: Long,
    ): SttSilenceDecision = when {
        !heardSpeech && totalElapsedMs >= NO_SPEECH_TIMEOUT_MS -> SttSilenceDecision.NoSpeechTimeout
        (heardSpeech && silenceElapsedMs >= SILENCE_AFTER_SPEECH_MS && totalElapsedMs >= MIN_RECORDING_MS) ||
            totalElapsedMs >= HARD_TIMEOUT_MS -> SttSilenceDecision.FinishSpeech
        else -> SttSilenceDecision.Continue
    }
}

/** A voice probe's outcome; only [Success] is gated on an unchanged configuration fingerprint. */
sealed interface ProbeResult {
    data object Success : ProbeResult
    data class Failed(val error: VoiceModelError) : ProbeResult
}

/** Per-fingerprint probe progress shown by the catalog page. */
sealed interface VoiceProbeUi {
    data object Idle : VoiceProbeUi
    data object Running : VoiceProbeUi
    data class Listening(
        val transcript: String,
        val level: Float = 0f,
        val canFinish: Boolean = false,
        val converting: Boolean = false,
    ) : VoiceProbeUi
    data object Playing : VoiceProbeUi
    data class Failed(val error: VoiceModelError) : VoiceProbeUi
    data object Verified : VoiceProbeUi

    val isActive: Boolean
        get() = this is Running || this is Listening || this is Playing
}

/**
 * Catalog page state and actions: CRUD with explicit per-capability protocols, save-unverified, type-specific
 * probes and account rules. Probe state is keyed by the configuration fingerprint captured when the probe began;
 * a probe that finishes after the configuration changed cannot mark a stale configuration as verified.
 */
class VoiceCatalogViewModel(
    private val environment: VoiceModelEnvironment,
    private val manager: VoiceAccountManager,
    private val probe: VoiceModelProbe,
    private val scope: CoroutineScope,
    private val playAudio: suspend (ByteArray, String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val playAudioWithStatus: (suspend (ByteArray, String, () -> Unit) -> Unit)? = null,
    private val onStopAudio: () -> Unit = {},
) {
    val catalog: VoiceCatalogStore = environment.catalog
    val snapshot: StateFlow<VoiceCatalogSnapshot> = catalog.state

    /** The migration failure, if the one-time legacy migration did not complete. */
    val migrationError: VoiceModelError? get() = environment.migrationError

    /** Clears a corrupt snapshot and re-runs the legacy migration from retained rollback data. */
    suspend fun repairCorruptCatalog(): MigrationResult = withContext(io) { environment.repairCorruptCatalog() }

    private val _probes = MutableStateFlow<Map<String, VoiceProbeUi>>(emptyMap())
    val probes: StateFlow<Map<String, VoiceProbeUi>> = _probes.asStateFlow()
    private val _transcripts = MutableStateFlow<Map<String, String>>(emptyMap())
    val transcripts: StateFlow<Map<String, String>> = _transcripts.asStateFlow()
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeSttRuns = mutableMapOf<String, SttRun>()

    /**
     * 每次测试的身份。同一配置连点两次「测试」时,旧测试被取消后收尾是异步跑的,
     * 收尾只在登记的还是自己时才动登记表和状态,不会把新测试的任务、录音删掉。
     */
    private val probeTokens = mutableMapOf<String, Any>()

    /** 一次识别测试的录音会话;结束录音(静音自动结束、点「完成」)只发生一次。 */
    private class SttRun(val session: SpeechSession) {
        var released = false
            private set

        fun finish() {
            if (released) return
            released = true
            session.release()
        }

        private var abandoned = false

        fun abandon() {
            released = true
            if (abandoned) return
            abandoned = true
            runCatching { session.cancel() }
        }
    }

    // ---------- accounts, models and services ----------

    fun saveService(service: VoiceService, credentials: VoiceCredentials? = null) {
        manager.saveVoiceAccount(service, credentials)
    }

    fun upsertModel(model: VoiceModel) {
        catalog.upsertModel(model)
    }

    /** Save without probing; the model stays unverified until a type-specific test succeeds. */
    fun saveUnverified(model: VoiceModel) {
        catalog.upsertModel(model)
    }

    fun deleteModel(ref: VoiceModelRef) {
        if (ref == SttSelectionController.SYSTEM_STT_REF) return
        if (ref == TtsSelectionController.SYSTEM_TTS_REF) return
        catalog.deleteModel(ref)
    }

    fun deleteService(serviceId: String) {
        if (serviceId == SttSelectionController.SYSTEM_STT_SERVICE.id) return
        catalog.deleteService(serviceId)
    }

    fun providerVoiceUsage(providerId: String): List<VoiceModelRef> = manager.providerVoiceUsage(providerId)

    /** Edits one capability's endpoint; also invalidates that capability's verification in the catalog. */
    fun updateEndpoint(ref: VoiceModelRef, endpoint: String) {
        val model = catalog.requireModel(ref)
        val updated = when (ref.capability) {
            VoiceCapability.STT -> model.copy(stt = model.stt?.copy(endpoint = endpoint))
            VoiceCapability.TTS -> model.copy(tts = model.tts?.copy(endpoint = endpoint))
            VoiceCapability.REALTIME -> model.copy(realtime = model.realtime?.copy(endpoint = endpoint))
        }
        catalog.upsertModel(updated)
    }

    /** The current (non-decrypting) configuration fingerprint for [ref], or null while the catalog is unreadable. */
    fun probeFingerprint(ref: VoiceModelRef): String? = runCatching { probe.connection(ref) }.getOrNull()

    /** Current probe progress for [ref], looked up by its configuration fingerprint. */
    fun probeState(ref: VoiceModelRef): VoiceProbeUi {
        val fingerprint = probeFingerprint(ref) ?: return VoiceProbeUi.Idle
        return _probes.value[fingerprint] ?: VoiceProbeUi.Idle
    }

    // ---------- probes ----------

    /**
     * Starts an async probe, capturing the current configuration fingerprint. Completing the returned deferred
     * with [ProbeResult.Success] verifies the model only while that fingerprint still matches; cancellation and
     * late completions leave the verification unchanged.
     */
    fun probeAsync(ref: VoiceModelRef): CompletableDeferred<ProbeResult> = startProbe(ref).first

    private fun startProbe(ref: VoiceModelRef): Pair<CompletableDeferred<ProbeResult>, String> {
        val fingerprint = probe.connection(ref)
        stopOtherProbes(except = fingerprint)
        stopFingerprint(fingerprint)
        setProbe(fingerprint, VoiceProbeUi.Running)
        val token = Any()
        probeTokens[fingerprint] = token
        val pending = CompletableDeferred<ProbeResult>()
        pending.invokeOnCompletion {
            if (probeTokens[fingerprint] !== token) return@invokeOnCompletion
            probeTokens.remove(fingerprint)
            activeJobs.remove(fingerprint)
            if (pending.isCancelled) return@invokeOnCompletion
            when (val result = pending.getCompleted()) {
                is ProbeResult.Success -> {
                    val stillCurrent = runCatching { probe.connection(ref) == fingerprint }.getOrDefault(false)
                    if (stillCurrent) catalog.markVerification(ref, VoiceVerification.Verified)
                    setProbe(fingerprint, if (stillCurrent) VoiceProbeUi.Verified else VoiceProbeUi.Idle)
                }
                is ProbeResult.Failed -> setProbe(fingerprint, VoiceProbeUi.Failed(result.error))
            }
        }
        return pending to fingerprint
    }

    private fun stopOtherProbes(except: String?) {
        val toStop = activeJobs.keys.filter { it != except }
        toStop.forEach { fp ->
            stopFingerprint(fp)
            if (_probes.value[fp]?.isActive == true) setProbe(fp, VoiceProbeUi.Idle)
        }
        onStopAudio()
    }

    /** 当场放掉这个配置正在进行的测试:录音立即取消(麦克风马上释放),任务取消,收尾不再回写状态。 */
    private fun stopFingerprint(fingerprint: String) {
        probeTokens.remove(fingerprint)
        activeSttRuns.remove(fingerprint)?.abandon()
        activeJobs.remove(fingerprint)?.cancel()
    }

    /** Stops all active probes (e.g. when leaving the screen so mic and audio always release). */
    fun stopAllProbes() {
        stopOtherProbes(except = null)
    }

    /** Stops an in-progress STT capture, TTS synthesis/playback, or Realtime connection probe for [ref]. */
    fun stopProbe(ref: VoiceModelRef) {
        val fingerprint = probeFingerprint(ref)
        if (fingerprint != null) {
            stopFingerprint(fingerprint)
            _transcripts.update { it - fingerprint }
            if (_probes.value[fingerprint]?.isActive == true) setProbe(fingerprint, VoiceProbeUi.Idle)
        }
        onStopAudio()
    }

    /** Ends an active non-live STT recording early so it transcribes immediately. */
    fun finishStt(ref: VoiceModelRef) {
        val fingerprint = probeFingerprint(ref) ?: return
        val run = activeSttRuns[fingerprint] ?: return
        if (run.released) return
        val current = _probes.value[fingerprint] as? VoiceProbeUi.Listening
        if (current != null) setProbe(fingerprint, current.copy(level = 0f, canFinish = false, converting = true))
        run.finish()
    }

    private fun setProbe(fingerprint: String, state: VoiceProbeUi) {
        _probes.update { it + (fingerprint to state) }
    }

    /** Holds a real microphone capture, drives live waveform levels, and finishes automatically on pause or Done. */
    fun verifyStt(ref: VoiceModelRef) {
        val (pending, fingerprint) = startProbe(ref)
        val job = scope.launch {
            val session = try {
                probe.openSttSession(ref)
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
                return@launch
            }
            val run = SttRun(session)
            if (!isActive) {
                run.abandon()
                pending.cancel()
                return@launch
            }
            activeSttRuns[fingerprint] = run
            var currentTranscript = ""
            var currentLevel = 0f
            fun publish() = setProbe(
                fingerprint,
                VoiceProbeUi.Listening(
                    transcript = currentTranscript,
                    level = if (run.released) 0f else currentLevel,
                    canFinish = !session.live && !run.released,
                    converting = run.released,
                ),
            )
            // 非实时识别要等 release() 才出结果:按固定间隔判静音,没说话就超时报错,说完停顿就自动结束录音。
            // 计时用累计的间隔而不是墙钟,测试里虚拟时间也能推进。
            var heardSpeech = false
            var loud = false
            val silence = if (session.live) null else launch {
                var elapsedMs = 0L
                var lastLoudMs = 0L
                while (!run.released) {
                    delay(SttSilencePolicy.TICK_MS)
                    elapsedMs += SttSilencePolicy.TICK_MS
                    if (loud) lastLoudMs = elapsedMs
                    if (run.released) break
                    when (SttSilencePolicy.evaluate(heardSpeech, elapsedMs - lastLoudMs, elapsedMs)) {
                        SttSilenceDecision.NoSpeechTimeout -> {
                            run.abandon()
                            _transcripts.update { it - fingerprint }
                            pending.complete(ProbeResult.Failed(VoiceModelError.NoSpeechDetected()))
                        }
                        SttSilenceDecision.FinishSpeech -> {
                            run.finish()
                            publish()
                        }
                        SttSilenceDecision.Continue -> Unit
                    }
                }
            }
            try {
                session.events.collect { event ->
                    when (event) {
                        is SpeechEvent.Level -> {
                            currentLevel = event.value
                            loud = event.value >= SttSilencePolicy.SPEECH_LEVEL_THRESHOLD
                            if (loud) heardSpeech = true
                            publish()
                        }
                        is SpeechEvent.Partial -> {
                            currentTranscript = event.stable + event.tail
                            publish()
                        }
                        SpeechEvent.Converting -> {
                            run.finish()
                            publish()
                        }
                        is SpeechEvent.Final -> {
                            if (event.text.isBlank()) {
                                _transcripts.update { it - fingerprint }
                                pending.complete(ProbeResult.Failed(VoiceModelError.NoSpeechDetected()))
                            } else {
                                _transcripts.update { it + (fingerprint to event.text) }
                                pending.complete(ProbeResult.Success)
                            }
                        }
                        is SpeechEvent.Failed -> {
                            _transcripts.update { it - fingerprint }
                            pending.complete(ProbeResult.Failed(event.why.toError()))
                        }
                    }
                }
            } catch (e: CancellationException) {
                run.abandon()
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
            } finally {
                silence?.cancel()
                activeSttRuns.remove(fingerprint, run)
                runCatching { session.release() }
            }
        }
        activeJobs[fingerprint] = job
    }

    /** Synthesizes once, then plays through the injected player before reporting success. */
    fun verifyTts(ref: VoiceModelRef, request: TtsRequest) {
        val (pending, fingerprint) = startProbe(ref)
        val job = scope.launch {
            val response = try {
                withContext(io) { probe.verifyTts(ref, request) }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
                return@launch
            }
            try {
                if (playAudioWithStatus != null) {
                    withContext(io) {
                        playAudioWithStatus(response.bytes, response.mime) {
                            setProbe(fingerprint, VoiceProbeUi.Playing)
                        }
                    }
                } else {
                    setProbe(fingerprint, VoiceProbeUi.Playing)
                    withContext(io) { playAudio(response.bytes, response.mime) }
                }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(
                    ProbeResult.Failed(
                        VoiceModelError.Protocol(
                            com.wangzan101.mika.voice.model.VoiceErrorCategory.SYSTEM_UNAVAILABLE,
                            "Playback failed: ${e.message.orEmpty()}",
                        ),
                    ),
                )
                return@launch
            }
            pending.complete(ProbeResult.Success)
        }
        activeJobs[fingerprint] = job
    }

    /** Records a Realtime handshake outcome; success is gated on an unchanged fingerprint like every probe. */
    fun verifyRealtime(ref: VoiceModelRef) {
        val (pending, fingerprint) = startProbe(ref)
        val job = scope.launch {
            try {
                withContext(io) { probe.verifyRealtimeConnection(ref) }
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: Exception) {
                pending.complete(ProbeResult.Failed(VoiceModelError.from(e)))
                return@launch
            }
            pending.complete(ProbeResult.Success)
        }
        activeJobs[fingerprint] = job
    }
}
