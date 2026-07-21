package com.xmoyi.nainaisv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.xmoyi.nainaisv.caregiver.CaregiverScreen
import com.xmoyi.nainaisv.player.GrandmaScreen
import com.xmoyi.nainaisv.ui.theme.NaiNaiTheme
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as NaiNaiApplication).container

        setContent {
            NaiNaiTheme {
                val setupComplete by container.settings.setupComplete.collectAsStateWithLifecycle(initialValue = false)
                val scope = rememberCoroutineScope()
                var mode by remember { mutableStateOf(ScreenMode.GRANDMA) }
                var askPin by remember { mutableStateOf(false) }
                var pin by remember { mutableStateOf("") }
                var pinError by remember { mutableStateOf(false) }

                LaunchedEffect(setupComplete) {
                    if (!setupComplete) mode = ScreenMode.CAREGIVER
                }

                // 奶奶模式隐藏状态栏和导航栏，画面不被系统图标打扰；家属模式恢复正常。
                LaunchedEffect(setupComplete, mode) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (setupComplete && mode == ScreenMode.GRANDMA) {
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                if (!setupComplete || mode == ScreenMode.CAREGIVER) {
                    if (setupComplete) {
                        // 家属页按返回键回到奶奶模式，而不是直接退出应用。
                        BackHandler { mode = ScreenMode.GRANDMA }
                    }
                    CaregiverScreen(
                        onboarding = !setupComplete,
                        onExit = { mode = ScreenMode.GRANDMA },
                    )
                } else {
                    GrandmaScreen(onOpenCaregiver = { askPin = true })
                }

                if (askPin) {
                    AlertDialog(
                        onDismissRequest = { askPin = false; pin = ""; pinError = false },
                        title = { Text("家属验证") },
                        text = {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { pin = it.filter(Char::isDigit).take(8); pinError = false },
                                label = { Text(if (pinError) "PIN 不正确" else "输入家属 PIN") },
                                isError = pinError,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch {
                                    if (container.settings.verifyPin(pin)) {
                                        askPin = false
                                        pin = ""
                                        mode = ScreenMode.CAREGIVER
                                    } else {
                                        pinError = true
                                    }
                                }
                            }) { Text("进入") }
                        },
                        dismissButton = {
                            Button(onClick = { askPin = false; pin = ""; pinError = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
    }
}

private enum class ScreenMode { GRANDMA, CAREGIVER }
