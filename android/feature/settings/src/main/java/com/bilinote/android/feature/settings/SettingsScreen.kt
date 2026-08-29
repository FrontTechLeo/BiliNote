package com.bilinote.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var draftsInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoaded) {
        if (state.isLoaded && !draftsInitialized) {
            url = state.backendUrl
            token = state.accessToken
            draftsInitialized = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Text(
            "配置 BiliNote 后端服务，保存后即可使用笔记功能",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("服务连接", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务地址") },
            supportingText = { Text("用于连接 BiliNote 后端 API") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
        )

        Text("访问凭证", style = MaterialTheme.typography.titleMedium)
        var tokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access token（可选）") },
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                    Text(if (tokenVisible) "隐藏" else "显示")
                }
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.testConnection(url, token) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isTestingConnection,
            ) {
                Text(if (state.isTestingConnection) "连接中…" else "测试连接")
            }
            Button(
                onClick = { viewModel.save(url, token) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.connectionMessage?.let { Text(it) }
            state.tokenError?.let { Text(it) }
            state.errorMessage?.let { Text(it) }
        }
    }
}
