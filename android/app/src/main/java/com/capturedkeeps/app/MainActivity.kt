package com.capturedkeeps.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.capturedkeeps.app.ui.*
import com.capturedkeeps.core.domain.Role
import com.capturedkeeps.core.domain.Staff
import com.capturedkeeps.data.login
import kotlinx.coroutines.delay

private sealed interface Nav {
    data object Loading : Nav
    data object Setup : Nav
    data object Kiosk : Nav
    data object Login : Nav
    data class StaffMenu(val login: com.capturedkeeps.data.Login) : Nav
}

/** Staff screens close themselves after this long untouched, so the booth is never left open. */
private const val STAFF_IDLE_MS = 3 * 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // booth screen never sleeps
        val app = application as App
        setContent {
            CapturedKeepsTheme {
                var nav by remember { mutableStateOf<Nav>(Nav.Loading) }
                var touched by remember { mutableLongStateOf(0L) }
                val actions = remember(nav) { Actions(app.scope) }
                LaunchedEffect(Unit) { nav = if (app.store.needsSetup()) Nav.Setup else Nav.Kiosk }
                LaunchedEffect(nav, touched) {
                    if (nav is Nav.Login || nav is Nav.StaffMenu) { delay(STAFF_IDLE_MS); nav = Nav.Kiosk }
                }

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touched = System.nanoTime() } }
                }) {
                    when (val n = nav) {
                        Nav.Loading -> {}
                        Nav.Setup -> SetupScreen(actions.error) { name, password, pin ->
                            actions.run {
                                app.store.saveStaff(Staff(name = name, role = Role.OWNER), pin = pin, password = password)
                                nav = Nav.Kiosk
                            }
                        }
                        Nav.Kiosk -> KioskScreen(app.kiosk, app.scope, filesDir, onStaff = { nav = Nav.Login })
                        Nav.Login -> {
                            BackHandler { nav = Nav.Kiosk }
                            LoginScreen(actions.error, onBack = { nav = Nav.Kiosk },
                                onSubmit = { secret -> actions.run { nav = Nav.StaffMenu(app.gate.login(app.store, secret)) } })
                        }
                        is Nav.StaffMenu -> StaffArea(app.store, n.login, app.scope, onExit = { nav = Nav.Kiosk })
                    }
                }
            }
        }
    }
}
