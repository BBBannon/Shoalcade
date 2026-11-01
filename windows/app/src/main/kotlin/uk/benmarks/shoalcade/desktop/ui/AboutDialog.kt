package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.desktop.APP_NAME
import uk.benmarks.shoalcade.desktop.Log
import uk.benmarks.shoalcade.desktop.WinLicences
import java.awt.Desktop
import java.io.File

/**
 * About Shoalcade (Windows): version, the GPL notice, and every part shipped with its licence and exact source link
 * (as the phone's AboutDialog). The links are selectable text, so they can be copied. "Licence texts" opens the
 * installed licences folder, which also holds SOURCE.txt's neighbour LICENSE.txt.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var showLicence by remember { mutableStateOf(false) }
    val install = remember { WinLicences.installDir() }
    val licenceText = remember(showLicence) {
        if (!showLicence) "" else runCatching { File(install, "LICENSE.txt").readText() }
            .getOrDefault("The licence text is in the licences folder next to Shoalcade.exe, and at https://www.gnu.org/licenses/gpl-3.0.txt")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 760.dp),
        title = { Text(if (showLicence) "GNU GPL v3" else "$APP_NAME ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (showLicence) {
                    SelectionContainer { Text(licenceText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
                    return@Column
                }
                Text("Free software under the GNU General Public License v3. You may share and change it under those terms. It comes with no warranty.")
                Spacer(Modifier.height(8.dp))
                Text("Not an official Jellyfin project. No games, BIOS, firmware or keys are included.")
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column {
                        Text("Source code: ${WinLicences.appSource()}", fontWeight = FontWeight.Bold)
                        if (install != null) Text("Where every part's source is: SOURCE.txt in ${install.path}",
                            style = MaterialTheme.typography.bodySmall)
                        Section("Emulator cores", WinLicences.cores)
                        Section("Game player", WinLicences.player)
                        Section("App", WinLicences.app)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                if (install != null) TextButton(onClick = { openFolder(File(install, "licences")) }) { Text("Licence texts") }
                TextButton(onClick = { showLicence = !showLicence }) { Text(if (showLicence) "Back" else "GPL v3 text") }
            }
        },
    )
}

@Composable
private fun Section(title: String, parts: List<WinLicences.Part>) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    parts.forEach { p ->
        Column(Modifier.padding(top = 6.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            Text("${p.use}. Licence: ${p.licence}", style = MaterialTheme.typography.bodySmall)
            Text(p.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun openFolder(dir: File) {
    runCatching { Desktop.getDesktop().open(dir) }.onFailure { Log.w("About: could not open ${dir.path}: ${it.message}") }
}
