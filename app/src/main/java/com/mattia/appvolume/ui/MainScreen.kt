package com.mattia.appvolume.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mattia.appvolume.AudioNames
import com.mattia.appvolume.shizuku.ShizukuState
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val serviceUid by viewModel.serviceUid.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val toneRunning by viewModel.testToneRunning.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("AppVolume – spike", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                ShizukuCard(shizuku, serviceUid, viewModel::requestPermission, viewModel::restartService)
            }
            item {
                TestToneCard(toneRunning, viewModel::toggleTestTone)
            }
            item {
                SectionTitle("App con audio (${apps.size})")
            }
            if (apps.isEmpty()) {
                item { Text("Nessun player attivo.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(apps, key = { it.uid }) { app ->
                AppCard(
                    app = app,
                    onLevel = { level, persist -> viewModel.setLevel(app, level, persist) },
                    onToggleMute = { viewModel.toggleMute(app) },
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Log player (${log.size})")
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = viewModel::clearLog) { Text("Svuota") }
                }
            }
            // Più recenti in alto.
            items(log.asReversed()) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ShizukuCard(
    state: ShizukuState,
    serviceUid: Int?,
    onRequestPermission: () -> Unit,
    onRestartService: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Shizuku")
            when (state) {
                ShizukuState.NotRunning -> Text("Non attivo: avvia Shizuku e riapri l'app.")
                ShizukuState.PermissionMissing -> {
                    Text("Permesso mancante.")
                    Button(onClick = onRequestPermission) { Text("Concedi permesso") }
                }
                is ShizukuState.Ready -> {
                    val mode = when (state.shizukuUid) {
                        0 -> "root"
                        2000 -> "shell (adb)"
                        else -> "uid ${state.shizukuUid}"
                    }
                    Text("OK – Shizuku gira come $mode")
                    if (state.service == null) {
                        Text("Collegamento allo UserService in corso…")
                    } else {
                        Text("UserService collegato, uid ${serviceUid ?: "?"}")
                    }
                    OutlinedButton(onClick = onRestartService) { Text("Riavvia servizio") }
                }
            }
        }
    }
}

@Composable
private fun TestToneCard(running: Boolean, onToggle: (recreate: Boolean) -> Unit) {
    var recreate by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Tono di prova (AudioTrack)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ricrea il player ogni 2 s", Modifier.weight(1f))
                Switch(checked = recreate, onCheckedChange = { recreate = it }, enabled = !running)
            }
            FilledTonalButton(onClick = { onToggle(recreate) }) {
                Text(if (running) "Ferma tono" else "Avvia tono 440 Hz")
            }
        }
    }
}

@Composable
private fun AppCard(
    app: AppAudio,
    onLevel: (level: Int, persist: Boolean) -> Unit,
    onToggleMute: () -> Unit,
) {
    var level by remember(app.packageName, app.setting.level) {
        mutableFloatStateOf(app.setting.level.toFloat())
    }
    val muted = app.setting.muted
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (muted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${app.packageName} · uid ${app.uid}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = level,
                    onValueChange = {
                        level = it
                        onLevel(it.roundToInt(), false)
                    },
                    onValueChangeFinished = { onLevel(level.roundToInt(), true) },
                    valueRange = 0f..100f,
                    enabled = !muted,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("${level.roundToInt()}", modifier = Modifier.width(32.dp))
                OutlinedButton(onClick = onToggleMute) { Text(if (muted) "Riattiva" else "Muto") }
            }
            for (p in app.players) {
                val applied = if (p.appliedVolume < 0f) "mai" else "%.2f".format(p.appliedVolume)
                Text(
                    "piid ${p.piid} · ${AudioNames.playerType(p.playerType)} · " +
                        "${AudioNames.playerState(p.playerState)} · usage ${p.usage} · " +
                        "vol $applied · ${p.lastResult.ifEmpty { "—" }}" +
                        if (p.firstApplyDelayMs >= 0) " · 1° set dopo ${p.firstApplyDelayMs} ms" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
