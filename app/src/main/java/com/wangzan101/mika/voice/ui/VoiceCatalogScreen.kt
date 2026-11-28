package com.wangzan101.mika.voice.ui

import androidx.compose.foundation.background
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.model.RealtimeBinding
import com.wangzan101.mika.voice.model.RealtimeProtocol
import com.wangzan101.mika.voice.model.SttBinding
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.TtsBinding
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceAccountManager
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceCredentials
import com.wangzan101.mika.voice.model.VoiceModel
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelProbe
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceService
import com.wangzan101.mika.voice.model.VoiceVerification
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.edit

/**
 * 设置 › 模型 › 语音模型:统一管理语音转文字、文字转语音和实时通话的模型。
 * 服务商、账号、每个能力各自的协议与接口在此声明;语音输入、朗读和通话设置页只负责选择与使用偏好。
 */

/** Resource id for a built-in system model's localized name, or null for user-defined models. */
internal fun systemModelDisplayNameRes(model: VoiceModel): Int? = when {
    model.serviceId == SttSelectionController.SYSTEM_STT_MODEL.serviceId &&
        model.id == SttSelectionController.SYSTEM_STT_MODEL.id -> R.string.voice_catalog_system_stt_name
    model.serviceId == TtsSelectionController.SYSTEM_TTS_MODEL.serviceId &&
        model.id == TtsSelectionController.SYSTEM_TTS_MODEL.id -> R.string.voice_catalog_system_tts_name
    else -> null
}

/** Display name for a catalog model; the built-in system models render a localized resource. */
@Composable
internal fun voiceModelDisplayName(model: VoiceModel): String =
    systemModelDisplayNameRes(model)?.let { stringResource(it) } ?: model.displayName

@Composable
fun VoiceCatalogScreen(voiceModels: VoiceModelEnvironment, onBack: () -> Unit) {
    val viewModel = rememberVoiceCatalogViewModel(voiceModels)
    VoiceCatalogContent(viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceCatalogContent(viewModel: VoiceCatalogViewModel, onBack: () -> Unit) {
    val snapshot by viewModel.snapshot.collectAsState()
    val probes by viewModel.probes.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VoiceModel?>(null) }
    var deleting by remember { mutableStateOf<VoiceModel?>(null) }
    var confirmingRepair by remember { mutableStateOf(false) }

    SubPage(stringResource(R.string.voice_catalog_title), onBack) {
        FooterNote(stringResource(R.string.voice_catalog_sub))

        viewModel.migrationError?.let { error ->
            Spacer(Modifier.height(8.dp))
            StatusPill(stringResource(R.string.voice_catalog_migration_failed), PillTone.Warn, Modifier.testTag("voice-catalog-migration-error"))
            FooterNote(error.detail)
        }

        if (snapshot.error != null) {
            Spacer(Modifier.height(12.dp))
            StatusPill(stringResource(R.string.voice_catalog_read_failed), PillTone.Warn, Modifier.testTag("voice-catalog-read-failed"))
            Spacer(Modifier.height(12.dp))
            MikaButton(
                stringResource(R.string.voice_catalog_repair),
                onClick = { confirmingRepair = true },
                modifier = Modifier.fillMaxWidth().testTag("voice-catalog-repair"),
            )
            return@SubPage
        }

        Spacer(Modifier.height(8.dp))
        MikaButton(
            stringResource(R.string.voice_catalog_add),
            onClick = {
                editing = null
                sheetOpen = true
            },
            modifier = Modifier.fillMaxWidth().testTag("voice-catalog-add"),
        )

        // 本机语音包由「离线语音包」页管(下载、删除),不在这里当普通服务编辑
        val services = snapshot.services.filter { it.id != OnDeviceCatalog.SERVICE_ID }
        val groups = snapshot.models.groupBy { it.serviceId }
        if (services.isEmpty() && snapshot.models.none { it.serviceId != OnDeviceCatalog.SERVICE_ID }) {
            Spacer(Modifier.height(20.dp))
            FooterNote(stringResource(R.string.voice_catalog_empty), Modifier.testTag("voice-catalog-empty"))
        }

        services.forEach { service ->
            val models = groups[service.id].orEmpty()
            ServiceGroup(
                service = service,
                models = models,
                snapshot = snapshot,
                onEdit = { model ->
                    editing = model
                    sheetOpen = true
                },
                onDelete = { model -> deleting = model },
                onTest = { ref, request -> viewModel.test(ref, request) },
                onFinish = { ref -> viewModel.finishStt(ref) },
                onStop = { ref -> viewModel.stopProbe(ref) },
                probeState = { ref -> viewModel.probeFingerprint(ref)?.let { probes[it] } ?: VoiceProbeUi.Idle },
                probeTranscript = { ref -> viewModel.probeFingerprint(ref)?.let { transcripts[it] }.orEmpty() },
            )
        }
    }

    if (sheetOpen) {
        VoiceCatalogSheet(
            viewModel = viewModel,
            editing = editing,
            onDismiss = { sheetOpen = false },
        )
    }

    deleting?.let { model ->
        MikaDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.voice_catalog_delete_model_title)) },
            text = { Text(model.displayName) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT))
                        deleting = null
                    },
                    modifier = Modifier.testTag("voice-catalog-delete-confirm"),
                ) { Text(stringResource(R.string.voice_catalog_delete)) }
            },
        )
    }

    if (confirmingRepair) {
        MikaDialog(
            onDismissRequest = { confirmingRepair = false },
            title = { Text(stringResource(R.string.voice_catalog_repair_title)) },
            text = { Text(stringResource(R.string.voice_catalog_repair_body)) },
            dismissButton = { TextButton(onClick = { confirmingRepair = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRepair = false
                        scope.launch { viewModel.repairCorruptCatalog() }
                    },
                    modifier = Modifier.testTag("voice-catalog-repair-confirm"),
                ) { Text(stringResource(R.string.voice_catalog_repair)) }
            },
        )
    }
}

private fun VoiceCatalogViewModel.test(ref: VoiceModelRef, request: TtsRequest?) {
    when (ref.capability) {
        VoiceCapability.STT -> verifyStt(ref)
        VoiceCapability.TTS -> verifyTts(ref, requireNotNull(request) { "TTS test requires sample text" })
        VoiceCapability.REALTIME -> verifyRealtime(ref)
    }
}

@Composable
private fun ServiceGroup(
    service: VoiceService,
    models: List<VoiceModel>,
    snapshot: VoiceCatalogSnapshot,
    onEdit: (VoiceModel) -> Unit,
    onDelete: (VoiceModel) -> Unit,
    onTest: (VoiceModelRef, TtsRequest?) -> Unit,
    onFinish: (VoiceModelRef) -> Unit,
    onStop: (VoiceModelRef) -> Unit,
    probeState: (VoiceModelRef) -> VoiceProbeUi,
    probeTranscript: (VoiceModelRef) -> String,
) {
    val system = service.id == SttSelectionController.SYSTEM_STT_SERVICE.id
    GroupCard(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(service.name, Modifier.weight(1f), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.ink)
            Text(
                accountLabel(service.account),
                style = MikaType.Hint,
                color = MikaTheme.colors.ink3,
                modifier = Modifier.testTag("voice-service-account-${service.id}"),
            )
        }
        models.forEach { model ->
            ModelRow(
                model = model,
                system = system && (model.id == SttSelectionController.SYSTEM_STT_MODEL.id || model.id == TtsSelectionController.SYSTEM_TTS_MODEL.id),
                snapshot = snapshot,
                onEdit = { onEdit(model) },
                onDelete = { onDelete(model) },
                onTest = onTest,
                onFinish = onFinish,
                onStop = onStop,
                probeState = probeState,
                probeTranscript = probeTranscript,
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: VoiceModel,
    system: Boolean,
    snapshot: VoiceCatalogSnapshot,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: (VoiceModelRef, TtsRequest?) -> Unit,
    onFinish: (VoiceModelRef) -> Unit,
    onStop: (VoiceModelRef) -> Unit,
    probeState: (VoiceModelRef) -> VoiceProbeUi,
    probeTranscript: (VoiceModelRef) -> String,
) {
    val c = MikaTheme.colors
    val ttsSampleText = stringResource(R.string.voice_catalog_tts_sample)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("voice-model-${model.id}"),
    ) {
        HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = c.hair)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(voiceModelDisplayName(model), style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    Text(model.id, style = MikaType.Hint, color = c.ink3)
                }
                if (!system) {
                    TextButton(onClick = onEdit, modifier = Modifier.testTag("voice-model-edit-${model.id}")) {
                        Text(stringResource(R.string.voice_catalog_edit))
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("voice-model-delete-${model.id}")) {
                        Text(stringResource(R.string.voice_catalog_delete))
                    }
                }
            }

            Column(
                Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                model.stt?.let { binding ->
                    val ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.STT)
                    CapabilityRow(
                        label = stringResource(R.string.voice_catalog_cap_stt),
                        protocol = sttProtocolLabel(binding.protocol),
                        ui = probeState(ref),
                        transcript = probeTranscript(ref),
                        verified = snapshot.verified(ref),
                        testLabel = stringResource(R.string.voice_catalog_test),
                        tag = "voice-cap-stt-${model.id}",
                        onTest = { onTest(ref, null) },
                        onFinish = { onFinish(ref) },
                        onStop = { onStop(ref) },
                    )
                }
                model.tts?.let { binding ->
                    val ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.TTS)
                    CapabilityRow(
                        label = stringResource(R.string.voice_catalog_cap_tts),
                        protocol = ttsProtocolLabel(binding.protocol),
                        ui = probeState(ref),
                        transcript = "",
                        verified = snapshot.verified(ref),
                        testLabel = stringResource(R.string.voice_catalog_try_tts),
                        tag = "voice-cap-tts-${model.id}",
                        onTest = { onTest(ref, TtsRequest(ttsSampleText)) },
                        onFinish = {},
                        onStop = { onStop(ref) },
                    )
                }
                model.realtime?.let { binding ->
                    val ref = VoiceModelRef(model.serviceId, model.id, VoiceCapability.REALTIME)
                    CapabilityRow(
                        label = stringResource(R.string.voice_catalog_cap_realtime),
                        protocol = realtimeProtocolLabel(binding.protocol),
                        ui = probeState(ref),
                        transcript = "",
                        verified = snapshot.verified(ref),
                        testLabel = stringResource(R.string.voice_catalog_test_realtime),
                        tag = "voice-cap-rt-${model.id}",
                        onTest = { onTest(ref, null) },
                        onFinish = {},
                        onStop = { onStop(ref) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    protocol: String,
    ui: VoiceProbeUi,
    transcript: String,
    verified: Boolean,
    testLabel: String,
    tag: String,
    onTest: () -> Unit,
    onFinish: () -> Unit,
    onStop: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MikaType.Caption.copy(fontWeight = FontWeight.Medium), color = c.ink2)
                    Text("·", style = MikaType.Hint, color = c.ink3)
                    Text(
                        protocol,
                        style = MikaType.Hint,
                        color = c.ink3,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                when (ui) {
                    VoiceProbeUi.Idle -> StatusPill(
                        stringResource(if (verified) R.string.voice_catalog_verified else R.string.voice_catalog_unverified),
                        if (verified) PillTone.Ok else PillTone.Warn,
                    )
                    VoiceProbeUi.Running -> StatusPill(stringResource(R.string.voice_catalog_testing), PillTone.Warn)
                    is VoiceProbeUi.Listening -> if (ui.converting) {
                        StatusPill(stringResource(R.string.voice_catalog_testing), PillTone.Warn)
                    }
                    VoiceProbeUi.Playing -> StatusPill(stringResource(R.string.voice_playing), PillTone.Ok)
                    is VoiceProbeUi.Failed -> Text(voiceErrorText(ui.error), style = MikaType.Hint, color = c.bad)
                    VoiceProbeUi.Verified -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusPill(stringResource(R.string.voice_catalog_verified), PillTone.Ok)
                        if (transcript.isNotBlank()) {
                            Text(
                                "“$transcript”",
                                style = MikaType.Hint,
                                color = c.ink2,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            MikaButton(
                text = if (ui.isActive) stringResource(R.string.chat_audio_stop) else testLabel,
                onClick = if (ui.isActive) onStop else onTest,
                kind = if (ui.isActive) ButtonKind.Secondary else ButtonKind.Tonal,
                size = ButtonSize.Small,
            )
        }
        if (ui is VoiceProbeUi.Listening && !ui.converting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.fill, MikaShapes.Button)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceWave(ui.level, flat = false, reduceMotion = MikaTheme.reduceMotion, color = c.accent)
                    Text(
                        ui.transcript.ifBlank { stringResource(R.string.voice_catalog_listening) },
                        style = MikaType.Hint,
                        color = c.ink2,
                    )
                }
                if (ui.canFinish) {
                    MikaButton(
                        text = stringResource(R.string.voice_result_done),
                        onClick = onFinish,
                        kind = ButtonKind.Primary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

private fun VoiceCatalogSnapshot.verified(ref: VoiceModelRef): Boolean =
    verifications.any { it.ref == ref && it.scope.isEmpty() && it.status == VoiceVerification.Verified }

@Composable
private fun accountLabel(account: VoiceAccount): String = when (account) {
    is VoiceAccount.ExistingProvider -> account.providerId
    is VoiceAccount.VoiceOwned -> account.secretId
    VoiceAccount.None -> stringResource(R.string.voice_catalog_account_none)
}

// ---------- add / edit sheet ----------

private enum class AccountSource { Existing, Owned, None }

private class CatalogForm {
    var error by mutableStateOf(false)
    var accountSource by mutableStateOf(AccountSource.None)
    var serviceName by mutableStateOf("")
    var providerId by mutableStateOf("")
    var secretId by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var appId by mutableStateOf("")
    var accessKey by mutableStateOf("")
    var modelId by mutableStateOf("")
    var displayName by mutableStateOf("")
    var sttOn by mutableStateOf(false)
    var sttProtocol by mutableStateOf(SttProtocol.AudioTranscriptions)
    var sttEndpoint by mutableStateOf("")
    var ttsOn by mutableStateOf(false)
    var ttsProtocol by mutableStateOf(TtsProtocol.OpenAiSpeech)
    var ttsEndpoint by mutableStateOf("")
    var realtimeOn by mutableStateOf(false)
    var realtimeProtocol by mutableStateOf(RealtimeProtocol.VolcDuplex)
    var realtimeEndpoint by mutableStateOf("")
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCatalogSheet(
    viewModel: VoiceCatalogViewModel,
    editing: VoiceModel?,
    onDismiss: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val existingService = editing?.let { model ->
        snapshot.services.firstOrNull { it.id == model.serviceId }
    }
    val form = remember(editing) {
        CatalogForm().apply {
            existingService?.let { service ->
                accountSource = when (service.account) {
                    is VoiceAccount.ExistingProvider -> AccountSource.Existing
                    is VoiceAccount.VoiceOwned -> AccountSource.Owned
                    VoiceAccount.None -> AccountSource.None
                }
                serviceName = service.name
                providerId = (service.account as? VoiceAccount.ExistingProvider)?.providerId.orEmpty()
                secretId = (service.account as? VoiceAccount.VoiceOwned)?.secretId.orEmpty()
            }
            editing?.let { model ->
                modelId = model.id
                displayName = model.displayName
                sttOn = model.stt != null
                sttProtocol = model.stt?.protocol ?: SttProtocol.AudioTranscriptions
                sttEndpoint = model.stt?.endpoint.orEmpty()
                ttsOn = model.tts != null
                ttsProtocol = model.tts?.protocol ?: TtsProtocol.OpenAiSpeech
                ttsEndpoint = model.tts?.endpoint.orEmpty()
                realtimeOn = model.realtime != null
                realtimeProtocol = model.realtime?.protocol ?: RealtimeProtocol.VolcDuplex
                realtimeEndpoint = model.realtime?.endpoint.orEmpty()
            }
        }
    }
    val c = MikaTheme.colors
    val ttsSampleText = stringResource(R.string.voice_catalog_tts_sample)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("voice-catalog-sheet"),
        ) {
            Text(
                stringResource(if (editing == null) R.string.voice_catalog_add else R.string.voice_catalog_edit),
                style = MikaType.Title, color = c.ink,
            )

            if (editing == null) {
                GroupLabel(stringResource(R.string.voice_catalog_account))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_existing), form.accountSource == AccountSource.Existing,
                        { form.accountSource = AccountSource.Existing }, Modifier.testTag("voice-catalog-account-existing"),
                    )
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_owned), form.accountSource == AccountSource.Owned,
                        { form.accountSource = AccountSource.Owned }, Modifier.testTag("voice-catalog-account-owned"),
                    )
                    SelectChip(
                        stringResource(R.string.voice_catalog_account_none), form.accountSource == AccountSource.None,
                        { form.accountSource = AccountSource.None }, Modifier.testTag("voice-catalog-account-none"),
                    )
                }
                when (form.accountSource) {
                    AccountSource.Existing -> SheetField(stringResource(R.string.voice_catalog_provider_id), form.providerId, { form.providerId = it }, tag = "voice-catalog-provider-id")
                    AccountSource.Owned -> {
                        SheetField(stringResource(R.string.voice_catalog_account_owned), form.secretId, { form.secretId = it }, tag = "voice-catalog-owned-name")
                        SheetField(stringResource(R.string.voice_catalog_api_key), form.apiKey, { form.apiKey = it }, tag = "voice-catalog-api-key", secret = true)
                        SheetField(stringResource(R.string.voice_catalog_app_id), form.appId, { form.appId = it }, tag = "voice-catalog-app-id")
                        SheetField(stringResource(R.string.voice_catalog_access_key), form.accessKey, { form.accessKey = it }, tag = "voice-catalog-access-key", secret = true)
                    }
                    AccountSource.None -> SheetField(stringResource(R.string.voice_catalog_service_name), form.serviceName, { form.serviceName = it }, tag = "voice-catalog-service-name")
                }
            } else {
                Text(existingService?.name.orEmpty(), style = MikaType.Body, color = c.ink2)
            }

            if (editing == null) {
                SheetField(stringResource(R.string.voice_catalog_model_id), form.modelId, { form.modelId = it }, tag = "voice-catalog-model-id")
            }
            SheetField(stringResource(R.string.voice_catalog_display_name), form.displayName, { form.displayName = it }, tag = "voice-catalog-display-name")

            GroupLabel(stringResource(R.string.voice_catalog_capabilities))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.sttOn, { form.sttOn = it }, Modifier.testTag("voice-catalog-cap-stt"))
                Text(stringResource(R.string.voice_catalog_cap_stt), style = MikaType.Body, color = c.ink)
            }
            if (form.sttOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_stt_protocol),
                    // 本机离线识别只由「离线语音包」页登记,不能手填成自建服务的协议
                    SttProtocol.entries.filter { it != SttProtocol.OnDevice }.map { it to sttProtocolLabel(it) },
                    form.sttProtocol.name,
                    { name -> form.sttProtocol = SttProtocol.valueOf(name) },
                    "voice-catalog-stt-protocol",
                )
                if (form.sttProtocol != SttProtocol.AndroidSystem) {
                    SheetField(stringResource(R.string.voice_catalog_endpoint), form.sttEndpoint, { form.sttEndpoint = it }, tag = "voice-catalog-stt-endpoint")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.ttsOn, { form.ttsOn = it }, Modifier.testTag("voice-catalog-cap-tts"))
                Text(stringResource(R.string.voice_catalog_cap_tts), style = MikaType.Body, color = c.ink)
            }
            if (form.ttsOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_tts_protocol),
                    TtsProtocol.entries.map { it to ttsProtocolLabel(it) },
                    form.ttsProtocol.name,
                    { name -> form.ttsProtocol = TtsProtocol.valueOf(name) },
                    "voice-catalog-tts-protocol",
                )
                if (form.ttsProtocol != TtsProtocol.AndroidSystem) {
                    SheetField(stringResource(R.string.voice_catalog_endpoint), form.ttsEndpoint, { form.ttsEndpoint = it }, tag = "voice-catalog-tts-endpoint")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaSwitch(form.realtimeOn, { form.realtimeOn = it }, Modifier.testTag("voice-catalog-cap-rt"))
                Text(stringResource(R.string.voice_catalog_cap_realtime), style = MikaType.Body, color = c.ink)
            }
            if (form.realtimeOn) {
                ProtocolBlock(
                    stringResource(R.string.voice_catalog_realtime_protocol),
                    RealtimeProtocol.entries.map { it to realtimeProtocolLabel(it) },
                    form.realtimeProtocol.name,
                    { name -> form.realtimeProtocol = RealtimeProtocol.valueOf(name) },
                    "voice-catalog-rt-protocol",
                )
                SheetField(stringResource(R.string.voice_catalog_endpoint), form.realtimeEndpoint, { form.realtimeEndpoint = it }, tag = "voice-catalog-rt-endpoint")
            }

            Spacer(Modifier.height(20.dp))
            if (form.error) {
                Text(
                    stringResource(R.string.voice_catalog_incomplete),
                    style = MikaType.Hint, color = c.bad,
                    modifier = Modifier.testTag("voice-catalog-incomplete"),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(
                    stringResource(R.string.cancel), onDismiss, Modifier.weight(1f),
                    kind = ButtonKind.Secondary, size = com.wangzan101.mika.ui.components.ButtonSize.Large,
                )
                MikaButton(
                    stringResource(R.string.voice_catalog_save_unverified),
                    { if (save(form, editing, existingService, viewModel, test = false, sampleText = ttsSampleText)) onDismiss() },
                    Modifier.weight(1f).testTag("voice-catalog-save-unverified"),
                )
            }
            Spacer(Modifier.height(8.dp))
            MikaButton(
                stringResource(R.string.voice_catalog_save_test),
                { if (save(form, editing, existingService, viewModel, test = true, sampleText = ttsSampleText)) onDismiss() },
                Modifier.fillMaxWidth().testTag("voice-catalog-save-test"),
                kind = ButtonKind.Tonal,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtocolBlock(
    title: String,
    options: List<Pair<Enum<*>, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    tag: String,
) {
    GroupLabel(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, label) ->
            SelectChip(
                label,
                option.name == selected,
                { onSelect(option.name) },
                Modifier.testTag("$tag-${option.name}"),
                small = true,
            )
        }
    }
}

/*
 * 表单只露出协议和接口地址。编辑时协议没换,就在原绑定上只改地址,表单之外的参数
 * (计费方案、地域、工作空间、识别语言、音色)原样保留;换了协议,旧协议的参数不再适用,从头建。
 */
internal fun editedSttBinding(saved: SttBinding?, protocol: SttProtocol, endpoint: String): SttBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else SttBinding(protocol, endpoint = endpoint)

internal fun editedTtsBinding(saved: TtsBinding?, protocol: TtsProtocol, endpoint: String): TtsBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else TtsBinding(protocol, endpoint = endpoint)

internal fun editedRealtimeBinding(saved: RealtimeBinding?, protocol: RealtimeProtocol, endpoint: String): RealtimeBinding =
    if (saved?.protocol == protocol) saved.copy(endpoint = endpoint) else RealtimeBinding(protocol, endpoint = endpoint)

private fun save(
    form: CatalogForm,
    editing: VoiceModel?,
    existingService: VoiceService?,
    viewModel: VoiceCatalogViewModel,
    test: Boolean,
    sampleText: String,
): Boolean {
    form.error = false
    if (editing == null) {
        val accountBlank = when (form.accountSource) {
            AccountSource.Existing -> form.providerId.isBlank()
            AccountSource.Owned -> form.secretId.isBlank()
            AccountSource.None -> form.serviceName.isBlank()
        }
        if (accountBlank) {
            form.error = true
            return false
        }
    }
    val service = existingService ?: when (form.accountSource) {
        AccountSource.Existing -> VoiceService(
            "provider:${form.providerId.trim()}", form.providerId.trim(), VoiceAccount.ExistingProvider(form.providerId.trim()),
        )
        AccountSource.Owned -> VoiceService(
            "owned:${form.secretId.trim()}", form.secretId.trim(), VoiceAccount.VoiceOwned(form.secretId.trim()),
        )
        AccountSource.None -> VoiceService(
            "local:${form.serviceName.trim()}", form.serviceName.trim(), VoiceAccount.None,
        )
    }
    val credentials = if (form.accountSource == AccountSource.Owned && editing == null) {
        VoiceCredentials(apiKey = form.apiKey, appId = form.appId, accessKey = form.accessKey)
            .takeIf { it.configured }
    } else null
    val model = VoiceModel(
        serviceId = service.id,
        id = if (editing == null) form.modelId.trim() else editing.id,
        displayName = form.displayName.trim().ifBlank { if (editing == null) form.modelId.trim() else editing.displayName },
        stt = if (form.sttOn) editedSttBinding(editing?.stt, form.sttProtocol, form.sttEndpoint.trim()) else null,
        tts = if (form.ttsOn) editedTtsBinding(editing?.tts, form.ttsProtocol, form.ttsEndpoint.trim()) else null,
        realtime = if (form.realtimeOn) {
            editedRealtimeBinding(editing?.realtime, form.realtimeProtocol, form.realtimeEndpoint.trim())
        } else null,
    )
    if (model.displayName.isBlank() || model.id.isBlank()) {
        form.error = true
        return false
    }
    viewModel.saveService(service, credentials)
    viewModel.saveUnverified(model)
    if (test) {
        model.capabilities.forEach { capability ->
            val ref = VoiceModelRef(service.id, model.id, capability)
            viewModel.test(ref, if (capability == VoiceCapability.TTS) TtsRequest(sampleText) else null)
        }
    }
    return true
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    secret: Boolean = false,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MikaType.Hint, color = c.ink3)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(tag),
            singleLine = true,
            textStyle = MikaType.Body.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(stringResource(R.string.voice_catalog_model_id_hint), style = MikaType.Body, color = c.ink3)
                inner()
            },
        )
    }
}

// ---------- protocol display labels ----------

@Composable
private fun sttProtocolLabel(protocol: SttProtocol): String = vendorProtocolName(protocol.name) ?: stringResource(
    when (protocol) {
        SttProtocol.AndroidSystem -> R.string.voice_catalog_protocol_stt_android_system
        SttProtocol.AudioTranscriptions -> R.string.voice_catalog_protocol_stt_audio_transcriptions
        SttProtocol.ChatInputAudio -> R.string.voice_catalog_protocol_stt_chat_input_audio
        SttProtocol.GeminiInline -> R.string.voice_catalog_protocol_stt_gemini_inline
        SttProtocol.DoubaoStreaming -> R.string.voice_catalog_protocol_stt_doubao_streaming
        SttProtocol.FunAsrStreaming -> R.string.voice_catalog_protocol_stt_funasr_streaming
        SttProtocol.BailianStreaming -> R.string.voice_catalog_protocol_stt_bailian_streaming
        else -> R.string.voice_catalog_protocol_stt_audio_transcriptions
    },
)

@Composable
private fun ttsProtocolLabel(protocol: TtsProtocol): String = vendorProtocolName(protocol.name) ?: stringResource(
    when (protocol) {
        TtsProtocol.AndroidSystem -> R.string.voice_catalog_protocol_tts_android_system
        TtsProtocol.OpenAiSpeech -> R.string.voice_catalog_protocol_tts_openai_speech
        TtsProtocol.GeminiTts -> R.string.voice_catalog_protocol_tts_gemini_tts
        else -> R.string.voice_catalog_protocol_tts_openai_speech
    },
)

/** Vendor-specific protocols are labelled by the vendor's own (untranslated) product name. */
private fun vendorProtocolName(name: String): String? = when (name) {
    "ElevenLabsUpload", "ElevenLabsSpeech" -> "ElevenLabs"
    "DeepgramUpload", "DeepgramSpeak" -> "Deepgram"
    "AzureShortAudio", "AzureSpeech" -> "Azure Speech"
    "TencentSentence", "TencentVoice" -> "Tencent Cloud"
    "XfyunDictation", "XfyunSynthesis" -> "iFlytek"
    "VolcSpeech" -> "Volcano Engine TTS"
    "BailianQwenTts" -> "Qwen TTS"
    "MiniMaxT2a" -> "MiniMax T2A"
    else -> null
}

@Composable
private fun realtimeProtocolLabel(protocol: RealtimeProtocol): String = stringResource(
    when (protocol) {
        RealtimeProtocol.VolcDuplex -> R.string.voice_catalog_protocol_realtime_volc_duplex
        RealtimeProtocol.OpenAiRealtime -> R.string.voice_catalog_protocol_realtime_openai_realtime
    },
)
