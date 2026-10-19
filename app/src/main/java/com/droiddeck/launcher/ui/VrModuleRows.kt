package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.VrModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VrModuleRows(uninstall: Boolean?, track: (String) -> Modifier = { Modifier }) {
    val context = LocalContext.current
    val vr by VrModule.state.collectAsState()
    val title = stringResource(R.string.vr_module_title)
    when {
        vr.busy -> {
            val stage = vr.stage ?: stringResource(R.string.common_working)
            SettingsRow(title, if (vr.percent >= 0) stringResource(R.string.setup_runtime_progress, stage, vr.percent) else stage) {}
            Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                if (vr.percent >= 0) LinearProgressIndicator(progress = { vr.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
        vr.installed -> {
            val size by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { VrModule.installedBytes(context) } }
            SettingsRow(title, stringResource(R.string.vr_module_installed, size?.let { FileUtils.sizeToString(it) } ?: "…")) {
                if (uninstall != null) UninstallButton(stringResource(R.string.vr_module_uninstall_title), stringResource(R.string.vr_module_uninstall_text),
                    enabled = uninstall, modifier = Modifier.testTag("vr-module-uninstall")) { VrModule.uninstall(context) }
            }
        }
        else -> {
            val download = FileUtils.sizeToString(BuildConfig.VR_MODULE_SIZE)
            SettingsRow(title, stringResource(if (vr.present) R.string.vr_module_update else R.string.vr_module_hint, download)) {
                PrimaryButton(stringResource(if (vr.present) R.string.common_update else R.string.common_download), compact = true,
                    modifier = track("vr-module").testTag("vr-module-download")) { VrModule.install(context) }
            }
        }
    }
    vr.error?.let { Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { Note(it) } }
}
