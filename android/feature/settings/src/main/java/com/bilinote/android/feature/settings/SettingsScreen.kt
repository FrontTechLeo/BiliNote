package com.bilinote.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("服务地址") })
        var tokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Access token（可选）") },
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { tokenVisible = !tokenVisible }) {
                    Text(if (tokenVisible) "隐藏" else "显示")
                }
            },
        )
        Button(onClick = { viewModel.testConnection(url, token) }, enabled = !state.isTestingConnection) {
            Text(if (state.isTestingConnection) "连接中…" else "测试连接")
        }
        state.connectionMessage?.let { Text(it) }
        state.tokenError?.let { Text(it) }
        state.errorMessage?.let { Text(it) }
        Button(onClick = { viewModel.save(url, token) }) {
            Text("保存")
        }
    }
}
