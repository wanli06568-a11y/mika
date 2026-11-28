package com.wangzan101.mika.voice.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.settings.normalizeVoiceSpeed
import com.wangzan101.mika.settings.ttsModel
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.MikaTextField
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceVoices
import com.wangzan101.mika.voice.model.settingsProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import com.wangzan101.mika.settings.ui.models.Page

/**
 * 设置 › 模型 › 声音模型:选服务商、模型、接口、音色、语速;打开朗读/自动朗读/主动说话。
 * 不在 [com.wangzan101.mika.core.model.ModelRole] 里;和语音转写、图片模型一样独立。
 */
@Composable
fun VoiceModelScreen(store: ProviderStore, onBack: () -> Unit, onOpenServices: (() -> Unit)? = null) {
    val context = LocalContext.current
    val settings = VoiceSettings.get(context)
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    if (voiceModels.tts.migrated) {
        MigratedVoiceModelContent(voiceModels, settings, store, onBack, onOpenServices)
    } else {
        VoiceModelContent(store, settings, onBack)
    }
}

@Composable
internal fun VoiceModelContent(store: ProviderStore, settings: VoiceSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val providers by store.providers.collectAsState()
    val choice = state.choice
    val player = remember { VoicePlayer(context) }
    val playback by player.state.collectAsState()
    var test by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player, choice?.providerId, choice?.model) { onDispose { player.stop() } }

    fun saveChoice(next: VoiceChoice) {
        settings.setChoice(next)
        test = null
    }

    val previewScope = key(choice?.providerId, choice?.model) { rememberCoroutineScope() }
    Page(tr(R.string.role_voice), onBack) {
        GroupCard {
            ListRow(
                null, tr(R.string.voice_enable), note = tr(R.string.voice_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        if (providers.isEmpty()) {
            FooterNote(tr(R.string.voice_no_providers))
        }

        choice?.let {
            GroupCard {
                InputRow(
                    tr(R.string.voice_name), choice.voice, onChange = { saveChoice(choice.copy(voice = it)) },
                    placeholder = "alloy / Kore", first = true,
                )
                NumericSettingRow(
                    label = tr(R.string.voice_speed),
                    value = choice.speed.toString(),
                    placeholder = "1.0",
                    keyboard = KeyboardType.Decimal,
                    parseValid = { it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v > 0f } != null },
                    onSave = { raw ->
                        raw.toFloatOrNull()?.takeIf { v -> v.isFinite() && v > 0f }?.let { v ->
                            saveChoice(choice.copy(speed = normalizeVoiceSpeed(v)))
                        }
                    },
                )
            }
            GroupLabel(tr(R.string.voice_protocol))
            GroupCard {
                RadioRow(
                    tr(R.string.voice_protocol_openai), choice.protocol == TtsProtocol.OpenAiSpeech, first = true,
                    onClick = { saveChoice(choice.copy(protocol = TtsProtocol.OpenAiSpeech)) },
                )
                RadioRow(
                    tr(R.string.voice_protocol_gemini), choice.protocol == TtsProtocol.GeminiTts,
                    onClick = { saveChoice(choice.copy(protocol = TtsProtocol.GeminiTts)) },
                )
                if (choice.protocol == TtsProtocol.OpenAiSpeech) {
                    ListRow(
                        null, tr(R.string.voice_streaming), note = tr(R.string.voice_streaming_note),
                        trailing = {
                            MikaSwitch(choice.streaming, onCheckedChange = {
                                settings.setChoice(choice.copy(streaming = it))
                                test = null
                            })
                        },
                    )
                }
            }
        }

        GroupCard {
            ListRow(
                null, tr(R.string.voice_auto_read), first = true,
                trailing = { MikaSwitch(state.autoRead, settings::setAutoRead) },
            )
            ListRow(
                null, tr(R.string.voice_speak_tool),
                trailing = { MikaSwitch(state.speakTool, settings::setSpeakTool) },
            )
            ListRow(
                null, tr(R.string.voice_save_enabled), note = tr(R.string.voice_save_note),
                trailing = { MikaSwitch(state.saveEnabled, settings::setSaveEnabled) },
            )
            ListRow(
                null, tr(R.string.voice_ask_before_save),
                trailing = { MikaSwitch(state.askBeforeSave, settings::setAskBeforeSave, enabled = state.saveEnabled) },
            )
            NumericSettingRow(
                label = tr(R.string.voice_max_chars),
                value = state.maxCharsPerSpeak.toString(),
                placeholder = "2000",
                keyboard = KeyboardType.Number,
                parseValid = { it.trim().toIntOrNull()?.let { n -> n > 0 } == true },
                onSave = { raw -> raw.trim().toIntOrNull()?.takeIf { it > 0 }?.let(settings::setMaxChars) },
            )
        }

        val isPlaying = playback is VoicePlayback.Loading || playback is VoicePlayback.Playing
        MikaButton(
            if (isPlaying) tr(R.string.chat_audio_stop) else tr(R.string.voice_test),
            onClick = {
                if (isPlaying) {
                    player.stop()
                    test = null
                    return@MikaButton
                }
                val choice = choice ?: return@MikaButton
                val caps = settings.capabilities() ?: return@MikaButton
                val tts = store.ttsModel(choice, caps) ?: return@MikaButton
                previewScope.launch {
                    test = tr(R.string.voice_testing)
                    try {
                        player.play(
                            tts,
                            TtsRequest(
                                voiceSample(context),
                                choice.voice.ifBlank { null },
                                choice.speed,
                                "wav",
                            ),
                            streaming = choice.streaming,
                        ) { test = tr(R.string.voice_test_ok) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        test = tr(R.string.voice_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            kind = if (isPlaying) com.wangzan101.mika.ui.components.ButtonKind.Secondary else com.wangzan101.mika.ui.components.ButtonKind.Primary,
            enabled = choice != null || isPlaying,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        playback.let { p ->
            val line = when (p) {
                VoicePlayback.Playing -> tr(R.string.voice_playing)
                VoicePlayback.Loading -> tr(R.string.voice_loading)
                is VoicePlayback.Error -> tr(R.string.voice_play_failed, p.message)
                VoicePlayback.Idle -> null
            }
            line?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        }
        test?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        FooterNote(tr(R.string.voice_footer), Modifier.padding(top = 10.dp))
    }
}

/** 迁移后只选择目录里声明了 TTS 的模型;音色、语速、流式等使用偏好仍由 [VoiceSettings] 保存。 */
@Composable
private fun MigratedVoiceModelContent(
    voiceModels: VoiceModelEnvironment,
    settings: VoiceSettings,
    store: ProviderStore,
    onBack: () -> Unit,
    onOpenServices: (() -> Unit)?,
) {
    val context = LocalContext.current
    val voiceVm = rememberVoiceCatalogViewModel(voiceModels)
    val providers by store.providers.collectAsState()
    var voicesOpen by remember { mutableStateOf(false) }
    val state by settings.state.collectAsState()
    val catalog by voiceModels.catalog.state.collectAsState()
    val tts = voiceModels.tts
    val selection = catalog.selections.tts
    val ttsModels = catalog.models.filter { it.tts != null }
    val selectedModel = selection?.let { ref ->
        ttsModels.firstOrNull { it.serviceId == ref.serviceId && it.id == ref.modelId }
    }
    val player = remember { VoicePlayer(context) }
    val playback by player.state.collectAsState()
    var test by remember { mutableStateOf<String?>(null) }
    var auditioningVoice by remember { mutableStateOf<String?>(null) }
    var auditionStarted by remember { mutableStateOf(false) }
    var auditionJob by remember { mutableStateOf<Job?>(null) }
    fun stopAudition() {
        auditionJob?.cancel()
        auditionJob = null
        auditioningVoice = null
        auditionStarted = false
        player.stop()
    }
    LaunchedEffect(playback) {
        if (auditioningVoice != null) {
            if (playback is VoicePlayback.Loading || playback is VoicePlayback.Playing) {
                auditionStarted = true
            } else if (auditionStarted && (playback is VoicePlayback.Idle || playback is VoicePlayback.Error)) {
                auditioningVoice = null
                auditionStarted = false
            }
        }
    }
    DisposableEffect(player, selection) {
        onDispose {
            auditionJob?.cancel()
            auditioningVoice = null
            auditionStarted = false
            player.stop()
        }
    }

    val previewScope = key(selection) { rememberCoroutineScope() }

    // 音色面板里的试听:和「测试」共用 [player],同一时间只播一条(新的替换旧的);
    // 面板关掉、换了模型、离开页面都停。合成和播放在 VoicePlayer 里挂起进行,不占主线程。
    fun audition(voice: String, locale: Locale? = null) {
        if (auditioningVoice == voice) {
            stopAudition()
            return
        }
        val ref = selection ?: return
        val protocol = selectedModel?.tts?.protocol ?: return
        val caps = settings.capabilities(protocol.settingsProtocol() ?: return)
        val model = try {
            tts.createTts(context, ref, caps)
        } catch (_: VoiceModelError) {
            return
        }
        auditionJob?.cancel()
        auditioningVoice = voice
        auditionStarted = false
        val request = TtsRequest(voiceSample(context, locale), voice, state.speed, "wav")
        test = null
        auditionJob = previewScope.launch {
            try {
                player.play(model, request, streaming = state.streaming)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (auditioningVoice == voice) {
                    auditioningVoice = null
                    auditionStarted = false
                }
            }
        }
    }
    Page(tr(R.string.role_voice), onBack) {
        GroupCard {
            ListRow(
                null, tr(R.string.voice_enable), note = tr(R.string.voice_enable_note), first = true,
                trailing = { MikaSwitch(state.enabled, settings::setEnabled) },
            )
        }

        val choices = voiceOptions(VoiceCapability.TTS, catalog, providers)
        if (choices.isEmpty()) {
            FooterNote(tr(R.string.voice_no_tts_models))
        } else {
            GroupLabel(tr(R.string.voice_choices_label))
            VoiceOptionList(
                choices, selection, tr(R.string.voice_try_tts), voiceVm,
                onPick = { choice ->
                    stopAudition()
                    selectReadAloud(voiceModels, settings, voiceVm.ensureInCatalog(choice))
                    test = null
                },
                onTest = { choice ->
                    stopAudition()
                    val useSelectedVoice = choice.ref == selection
                    val req = TtsRequest(
                        voiceSample(context),
                        if (useSelectedVoice) state.voice.ifBlank { null } else null,
                        if (useSelectedVoice) state.speed else 1f,
                    )
                    voiceVm.tryChoice(choice, tr(R.string.voice_try_sample), req)
                },
            )
        }
        VoiceFallbackSection(VoiceCapability.TTS, catalog, providers, voiceVm)
        onOpenServices?.let { VoiceMoreServices(it) }

        selectedModel?.let { model ->
            GroupCard {
                val pickable = if (model.tts?.protocol == com.wangzan101.mika.voice.model.TtsProtocol.AndroidSystem) {
                    SystemTtsRows(
                        voiceModels, settings, onChanged = { stopAudition(); test = null },
                        onAudition = { audition(it.name, it.locale) },
                        auditioningVoice = auditioningVoice,
                        onVoicesClosed = { stopAudition() },
                    )
                    true
                } else {
                    val voices = VoiceVoices.tts(model)
                    val pickable = voices.isNotEmpty() || model.tts != null
                    if (pickable) {
                        ListRow(null, tr(R.string.voice_voice), first = true, onClick = { voicesOpen = true },
                            modifier = Modifier.testTag("tts-voice")) {
                            RowValue(state.voice.ifBlank { tr(R.string.voice_voice_default) }.let { VoiceVoices.label(model, it) })
                            RowChevron()
                        }
                    }
                    if (voicesOpen) {
                        VoicePickerSheet(
                            VoiceVoices.withCurrent(voices, state.voice),
                            onPick = { stopAudition(); settings.setVoice(it); test = null },
                            onDismiss = { voicesOpen = false; stopAudition() },
                            model = model,
                            onAudition = { audition(it) },
                            auditioningVoice = auditioningVoice,
                        )
                    }
                    pickable
                }
                NumericSettingRow(
                    label = tr(R.string.voice_speed),
                    value = state.speed.toString(),
                    placeholder = "1.0",
                    keyboard = KeyboardType.Decimal,
                    first = !pickable,
                    parseValid = { it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v > 0f } != null },
                    onSave = { raw ->
                        raw.toFloatOrNull()?.takeIf { v -> v.isFinite() && v > 0f }?.let { v ->
                            settings.setSpeed(normalizeVoiceSpeed(v))
                        }
                    },
                )
                if (model.tts?.protocol == com.wangzan101.mika.voice.model.TtsProtocol.OpenAiSpeech) {
                    ListRow(
                        null, tr(R.string.voice_streaming), note = tr(R.string.voice_streaming_note),
                        trailing = {
                            MikaSwitch(state.streaming, onCheckedChange = {
                                settings.setStreaming(it)
                                test = null
                            })
                        },
                    )
                }
            }
        }

        GroupCard {
            ListRow(
                null, tr(R.string.voice_auto_read), first = true,
                trailing = { MikaSwitch(state.autoRead, settings::setAutoRead) },
            )
            ListRow(
                null, tr(R.string.voice_speak_tool),
                trailing = { MikaSwitch(state.speakTool, settings::setSpeakTool) },
            )
            ListRow(
                null, tr(R.string.voice_save_enabled), note = tr(R.string.voice_save_note),
                trailing = { MikaSwitch(state.saveEnabled, settings::setSaveEnabled) },
            )
            ListRow(
                null, tr(R.string.voice_ask_before_save),
                trailing = { MikaSwitch(state.askBeforeSave, settings::setAskBeforeSave, enabled = state.saveEnabled) },
            )
            NumericSettingRow(
                label = tr(R.string.voice_max_chars),
                value = state.maxCharsPerSpeak.toString(),
                placeholder = "2000",
                keyboard = KeyboardType.Number,
                parseValid = { it.trim().toIntOrNull()?.let { n -> n > 0 } == true },
                onSave = { raw -> raw.trim().toIntOrNull()?.takeIf { it > 0 }?.let(settings::setMaxChars) },
            )
        }

        val isPlaying = playback is VoicePlayback.Loading || playback is VoicePlayback.Playing
        MikaButton(
            if (isPlaying) tr(R.string.chat_audio_stop) else tr(R.string.voice_test),
            onClick = {
                if (isPlaying) {
                    player.stop()
                    test = null
                    return@MikaButton
                }
                val ref = selection ?: return@MikaButton
                val protocol = selectedModel?.tts?.protocol ?: return@MikaButton
                val caps = settings.capabilities(protocol.settingsProtocol() ?: return@MikaButton)
                val model = try {
                    tts.createTts(context, ref, caps)
                } catch (e: VoiceModelError) {
                    test = e.detail
                    return@MikaButton
                }
                previewScope.launch {
                    test = tr(R.string.voice_testing)
                    try {
                        player.play(
                            model,
                            TtsRequest(
                                voiceSample(context),
                                state.voice.ifBlank { null },
                                state.speed,
                                "wav",
                            ),
                            streaming = state.streaming,
                        ) { test = tr(R.string.voice_test_ok) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        test = tr(R.string.voice_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            },
            kind = if (isPlaying) com.wangzan101.mika.ui.components.ButtonKind.Secondary else com.wangzan101.mika.ui.components.ButtonKind.Primary,
            enabled = selectedModel != null || isPlaying,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        playback.let { p ->
            val line = when (p) {
                VoicePlayback.Playing -> tr(R.string.voice_playing)
                VoicePlayback.Loading -> tr(R.string.voice_loading)
                is VoicePlayback.Error -> tr(R.string.voice_play_failed, p.message)
                VoicePlayback.Idle -> null
            }
            line?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        }
        test?.let { FooterNote(it, Modifier.padding(top = 8.dp)) }
        FooterNote(tr(R.string.voice_footer), Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun NumericSettingRow(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType,
    first: Boolean = false,
    parseValid: (String) -> Boolean,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    ListRow(null, label, first = first, onClick = { editing = true }) {
        RowValue(value)
        RowChevron()
    }
    if (editing) {
        var draft by remember(value) { mutableStateOf(value) }
        MikaDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                MikaTextField(
                    draft,
                    { draft = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(draft)
                        editing = false
                    },
                    enabled = parseValid(draft),
                ) {
                    Text(tr(R.string.voice_result_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text(tr(R.string.cancel))
                }
            },
        )
    }
}
