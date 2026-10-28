package uk.benmarks.shoalcade

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Everything shipped in the APK, its licence and where its exact source lives.
 * GPL-3.0 §6 / MPL-2.0 §3.2: people who get the APK must be told where the source is.
 * Keep in step with docs/licences.md when a core is updated.
 */
object Licences {
    const val APP_SOURCE = "https://github.com/BBBannon/Shoalcade"

    data class Part(val name: String, val use: String, val licence: String, val source: String)

    val parts = listOf(
        Part("LibretroDroid 0.14.0", "Emulator front-end", "GPL-3.0", "https://github.com/Swordfish90/LibretroDroid/tree/0.14.0"),
        Part("mGBA (0.11-212-7a12d6d)", "GB, GBC and GBA", "MPL-2.0", "https://github.com/mgba-emu/mgba/tree/7a12d6d"),
        Part("melonDS DS 1.4.0", "Nintendo DS", "GPL-3.0", "https://github.com/JesseTG/melonds-ds/tree/v1.4.0"),
        Part("PPSSPP (commit 902e5cc)", "PSP, plus its support files (fonts, compatibility list)", "GPL-2.0-or-later (PSPSDK parts: BSD)", "https://github.com/hrydgard/ppsspp/tree/902e5ccd8c1400b4387b73942491cded655e5f9c"),
        Part("PCSX ReARMed (r26l-119-gc881679)", "PlayStation (PS1)", "GPL-2.0-or-later (parts: libchdr and zstd BSD, libretro-common MIT)", "https://github.com/libretro/pcsx_rearmed/tree/c8816799b50388e61cfe237fe2cdbb7d8175f20a"),
        Part("gpSP (v1.1.0-5819380)", "GBA link play", "GPL-2.0-or-later", "https://github.com/libretro/gpsp/tree/5819380"),
        Part("AndroidX, Jetpack Compose, Kotlin", "App framework", "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support"),
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var showLicence by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val licenceText = remember(showLicence) {
        if (!showLicence) "" else runCatching { ctx.assets.open("LICENSE.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("Licence text missing. See https://www.gnu.org/licenses/gpl-3.0.txt")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showLicence) "GNU GPL v3" else "Shoalcade $version") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (showLicence) {
                    Text(licenceText, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                    return@Column
                }
                Text("Free software under the GNU General Public License v3. You may share and change it under those terms. It comes with no warranty.")
                Spacer(Modifier.height(8.dp))
                Text("Source code: ${Licences.APP_SOURCE}")
                Spacer(Modifier.height(8.dp))
                Text("Not made by or linked to the Jellyfin project or Nintendo. No games, BIOS or firmware are included: play your own.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Included software", fontWeight = FontWeight.Bold)
                Licences.parts.forEach { p ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("${p.name} · ${p.licence}", fontWeight = FontWeight.SemiBold)
                        Text(p.use, style = MaterialTheme.typography.bodySmall)
                        Text(p.source, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { showLicence = !showLicence }) { Text(if (showLicence) "Back" else "Full licence") }
        },
    )
}
