package uk.benmarks.shoalcade

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The 3DS check screen (plan-3ds step 7, D151), from Settings or from a locked 3DS game's Download. Pre-check first;
 * then the run plays in [Game3dsActivity] (check mode) and comes back here with the measured seconds.
 */
class N3dsCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { HapticsGate { CheckScreen(onClose = { finish() }) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { N3dsCheck.store(ctx) }
    val phone = remember { N3dsCheck.phone(ctx) }
    val missing = remember { N3dsCheck.missing(phone) }
    val build = Build.FINGERPRINT
    val app = BuildConfig.VERSION_CODE.toLong()
    var saved by remember { mutableStateOf(N3dsCheck.read(store)) }
    var note by remember { mutableStateOf<String?>(null) }
    var askAnyway by remember { mutableStateOf(false) }

    val run = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val fps = r.data?.getIntArrayExtra(Game3dsActivity.RESULT_FPS)?.toList().orEmpty()
        val reason = r.data?.getStringExtra(Game3dsActivity.RESULT_REASON)
        if (r.resultCode == Activity.RESULT_OK && fps.isNotEmpty()) {
            val result = N3dsCheck.verdict(fps)
            N3dsCheck.save(store, result, build, app, System.currentTimeMillis())
            saved = N3dsCheck.read(store)
            note = "${result.label}. ${result.detail} ${N3dsCheck.speedLine(fps)}"
        } else {
            note = when (reason) {
                Game3dsActivity.REASON_HOT -> N3dsCheck.TOO_HOT
                Game3dsActivity.REASON_ERROR -> "The 3DS engine couldn't start on this phone. Nothing changed."
                else -> N3dsCheck.CANCELLED
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(N3dsCheck.TITLE) }, navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(N3dsCheck.INTRO, style = MaterialTheme.typography.bodyLarge)
            Text(N3dsCheck.status(saved, build, app), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (missing.isNotEmpty()) {
                Text("This phone can't run 3DS games:", style = MaterialTheme.typography.titleSmall)
                missing.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            } else {
                Button(onClick = { note = null; run.launch(Game3dsActivity.checkIntent(ctx)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (saved.result == null) N3dsCheck.START else N3dsCheck.AGAIN)
                }
            }
            if (!N3dsCheck.unlocked(saved) && (saved.result == N3dsCheck.Result.FAIL || missing.isNotEmpty())) {
                OutlinedButton(onClick = { askAnyway = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(N3dsCheck.ANYWAY)
                }
            }
        }
    }

    if (askAnyway) AlertDialog(
        onDismissRequest = { askAnyway = false },
        title = { Text(N3dsCheck.ANYWAY + "?") },
        text = { Text(N3dsCheck.ANYWAY_WARNING) },
        confirmButton = { TextButton(onClick = {
            N3dsCheck.unlockAnyway(store); saved = N3dsCheck.read(store); askAnyway = false
        }) { Text(N3dsCheck.ANYWAY) } },
        dismissButton = { TextButton(onClick = { askAnyway = false }) { Text("Keep it locked") } },
    )
}
