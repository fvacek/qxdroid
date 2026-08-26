package org.qxqx.qxdroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.qxqx.qxdroid.shv.ShvViewModel

@Composable
fun ShvPane(
    viewModel: ShvViewModel,
    modifier: Modifier = Modifier
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectionParams by viewModel.connectionParams.collectAsState()
    val httpPostParams by viewModel.httpPostParams.collectAsState()
    val postQxEnabled by viewModel.postQxEnabled.collectAsState()

    var host by remember { mutableStateOf(connectionParams.host) }
    var port by remember { mutableStateOf(connectionParams.port) }
    var user by remember { mutableStateOf(connectionParams.user) }
    var password by remember { mutableStateOf(connectionParams.password) }
    var apiToken by remember { mutableStateOf(connectionParams.apiToken) }
    var postUrl by remember { mutableStateOf(httpPostParams.url) }
    var postEnabled by remember { mutableStateOf(httpPostParams.enabled) }

    LaunchedEffect(connectionParams) {
        host = connectionParams.host
        port = connectionParams.port
        user = connectionParams.user
        password = connectionParams.password
        apiToken = connectionParams.apiToken
    }

    LaunchedEffect(httpPostParams) {
        postUrl = httpPostParams.url
        postEnabled = httpPostParams.enabled
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = postEnabled,
                onCheckedChange = {
                    postEnabled = it
                    viewModel.saveHttpPostParams(HttpPostParams(postUrl, it))
                }
            )
            Text("Post to HTTP server")
        }
        OutlinedTextField(
            value = postUrl,
            onValueChange = {
                postUrl = it
                viewModel.saveHttpPostParams(HttpPostParams(it, postEnabled))
            },
            label = { Text("URL") },
            placeholder = { Text("https://example.com/readout") },
            modifier = Modifier.fillMaxWidth(),
            enabled = postEnabled,
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = postQxEnabled,
                onCheckedChange = { viewModel.savePostQxEnabled(it) }
            )
            Text("Post to Qx server")
        }
        if (postQxEnabled) {
            Text(
                text = connectionStatus.toString(),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(connectionStatus.color())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (connectionStatus !is ConnectionStatus.Connected) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                modifier = Modifier.fillMaxWidth(),
                enabled = postQxEnabled
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                enabled = postQxEnabled
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User") },
                modifier = Modifier.fillMaxWidth(),
                enabled = postQxEnabled
            )
            PasswordTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = postQxEnabled
            )
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                label = { Text("API token") },
                modifier = Modifier.fillMaxWidth(),
                enabled = postQxEnabled
            )
            Button(
                onClick = {
                    val params = ShvConnectionParams(host, port, user, password, apiToken)
                    viewModel.connect(params)
                },
                modifier = Modifier.align(Alignment.End),
                enabled = postQxEnabled
            ) {
                Text("Connect")
            }
        } else {
            Button(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.align(Alignment.End),
                enabled = postQxEnabled
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
    enabled: Boolean = true,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (passwordVisible)
            VisualTransformation.None
        else
            PasswordVisualTransformation(),
        trailingIcon = {
            val icon = if (passwordVisible)
                Icons.Default.Visibility
            else
                Icons.Default.VisibilityOff

            val description = if (passwordVisible) "Hide password" else "Show password"

            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(imageVector = icon, contentDescription = description)
            }
        }
    )
}
