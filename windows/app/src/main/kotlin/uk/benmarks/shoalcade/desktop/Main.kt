package uk.benmarks.shoalcade.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import uk.benmarks.shoalcade.BuildConfig
import uk.benmarks.shoalcade.desktop.ui.Brand
import uk.benmarks.shoalcade.desktop.ui.LibraryScreen
import uk.benmarks.shoalcade.desktop.ui.LoginScreen
import uk.benmarks.shoalcade.desktop.ui.ShoalcadeTheme
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** "Shoalcade", or "Shoalcade Dev" for the Dev build (D152). */
val APP_NAME = if (BuildConfig.DEBUG) "Shoalcade Dev" else "Shoalcade"

fun main() {
    val paths = Paths.forThisUser()
    DesktopStorage.init(paths)
    Log.to(java.io.File(paths.logsDir, "shoalcade.log"))
    Log.i("$APP_NAME ${BuildConfig.VERSION_NAME} starting; data in ${paths.dataDir}")
    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 800.dp))
        val density = LocalDensity.current
        val icon = remember { Brand.logo(density) }
        var model: AppModel? = null
        val closeScope = rememberCoroutineScope()
        // D184: on exit, waiting saves get one more go (a few seconds at most). A game still open is closed first.
        val onClose: () -> Unit = {
            val vm = model
            when {
                vm == null -> exitApplication()
                vm.playing != null -> vm.message = "Close the game first (Close Game), so its save is kept."
                vm.exiting -> {}
                else -> closeScope.launch { vm.beforeExit(); exitApplication() }
            }
        }
        Window(onCloseRequest = onClose, title = APP_NAME, icon = icon, state = state) {
            window.minimumSize = Dimension(720, 520)
            val scope = rememberCoroutineScope()
            val vm = remember { AppModel(scope).also { model = it } }
            // Back to the front = the phone's "return to the app": one light server check (no polling).
            DisposableEffect(window) {
                val l = object : WindowAdapter() { override fun windowGainedFocus(e: WindowEvent?) = vm.checkConnection() }
                window.addWindowFocusListener(l)
                onDispose { window.removeWindowFocusListener(l) }
            }
            ShoalcadeTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (vm.session == null) LoginScreen(vm) else LibraryScreen(vm)
                }
            }
        }
    }
}
