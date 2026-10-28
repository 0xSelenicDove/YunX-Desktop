package com.yunx.app.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubDeviceAuth
import com.yunx.app.data.network.GitHubTokenStore
import com.yunx.app.data.network.TokenCheck
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
fun GitHubSignInDialog(onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val auth = remember { GitHubDeviceAuth() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<GitHubDeviceAuth.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }

    fun save(token: String) {
        if (GitHubTokenStore.setToken(token)) onSignedIn()
        else error = "无法加密保存登录信息，请检查钥匙串或存储设置后重试"
    }

    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text("登录 GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在浏览器中登录 GitHub 并确认验证码。仅用于浏览公开仓库和提高 API 限额，不申请仓库写入权限。",
                    style = MaterialTheme.typography.bodyMedium)
                code?.let { device ->
                    Text("请在 GitHub 输入以下验证码：")
                    SelectionContainer {
                        Text(device.userCode, style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text("等待你在 GitHub 完成授权；验证码约 ${device.expiresInSeconds / 60} 分钟内有效。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { DesktopActions.openUrl(GitHubDeviceAuth.VERIFICATION_URL) }) {
                        Text("重新打开 GitHub")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("登录凭证会加密保存在本机；过期后可重新登录。", style = MaterialTheme.typography.bodySmall)
                if (!busy) TextButton(onClick = { manual = !manual; error = null }) {
                    Text(if (manual) "返回 GitHub 登录" else "高级：使用 Personal Access Token")
                }
                if (manual) {
                    Text("公开仓库无需额外 scope。私有仓库请使用仅授权所需仓库、只读权限的细粒度 Token。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it; error = null },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        label = { Text("Personal Access Token") }, visualTransformation = PasswordVisualTransformation())
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && (!manual || tokenInput.isNotBlank()), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    code = null
                    try {
                        if (manual) {
                            val token = tokenInput.trim()
                            when (GitHubApi(clientProvider = { auth.client }).validateToken(token)) {
                                is TokenCheck.Valid -> save(token)
                                TokenCheck.Invalid -> error = "Token 无效或已过期"
                                TokenCheck.Unknown -> error = "无法校验 Token，请检查网络后重试"
                            }
                        } else {
                            val device = auth.requestCode()
                            code = device
                            DesktopActions.openUrl(GitHubDeviceAuth.VERIFICATION_URL)
                            save(auth.awaitToken(device))
                        }
                    } catch (e: TimeoutCancellationException) {
                        error = "登录验证码已过期，请重新登录"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = if (e is java.io.IOException) e.message else "GitHub 登录失败，请重试"
                    } finally {
                        busy = false
                        code = null
                    }
                }
            }) { Text(if (manual) "验证并保存" else "使用 GitHub 登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
