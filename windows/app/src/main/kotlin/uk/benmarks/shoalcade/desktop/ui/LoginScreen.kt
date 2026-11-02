package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import uk.benmarks.shoalcade.desktop.AppModel
import uk.benmarks.shoalcade.desktop.ServerAddress

/**
 * Log in: Quick Connect first, username and password as the fallback. The phone's LoginScreen (MainActivity.kt
 * lines 68-147), same words and same calls, in Pocket's notice card (.notice) centred in the window.
 */
@Composable
fun LoginScreen(vm: AppModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var usePassword by remember { mutableStateOf(false) }
    val code = vm.quickConnectCode
    val density = LocalDensity.current
    val logo = remember { Brand.logo(density) }
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PocketButton(onClick = { about = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text("About Shoalcade") }
        Column(Modifier.padding(24.dp).widthIn(max = 520.dp).background(Pocket.Surface, RoundedCornerShape(Pocket.RL))
            .border(2.dp, Pocket.Ink, RoundedCornerShape(Pocket.RL)).padding(22.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(logo, contentDescription = null, modifier = Modifier.size(56.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Shoalcade", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Pocket.condensed))
                    Text("Log in with your Jellyfin account", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
            PocketTextField(server, { server = it }, label = { Text("Server (e.g. jellyfin.example.com)") },
                singleLine = true, enabled = code == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())

            if (code != null) {
                Spacer(Modifier.height(24.dp))
                Text("Your Quick Connect code", style = MaterialTheme.typography.titleMedium)
                Text(code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                    letterSpacing = MaterialTheme.typography.displayMedium.fontSize * 0.15f)
                Text("On a device already signed in to Jellyfin: open your profile → Quick Connect → enter this code.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Waiting for approval…", modifier = Modifier.padding(start = 12.dp))
                }
                Spacer(Modifier.height(16.dp))
                PocketButton(onClick = { vm.cancelQuickConnect() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                return@Column
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (!usePassword) {
                Spacer(Modifier.height(16.dp))
                PocketButton(primary = true, 
                    onClick = { error = null; vm.startQuickConnect(server) { error = it } },
                    enabled = !vm.loading && server.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log in with Quick Connect") }
                PocketButton(onClick = { error = null; usePassword = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Use username and password instead")
                }
                Text(ServerAddress.HTTPS_ONLY, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                return@Column
            }

            PocketTextField(user, { user = it }, label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            PocketTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            PocketButton(primary = true, 
                onClick = { error = null; vm.login(server, user, pw) { error = it } },
                enabled = !vm.loading && server.isNotBlank() && user.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.loading) "Logging in…" else "Log in") }
            PocketButton(onClick = { error = null; usePassword = false }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Use Quick Connect instead")
            }
            Text(ServerAddress.HTTPS_ONLY, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
