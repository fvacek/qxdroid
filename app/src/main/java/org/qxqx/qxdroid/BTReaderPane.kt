package org.qxqx.qxdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.bt.BtleReaderViewModel

@Composable
fun BTReaderPane(
    viewModel: BtleReaderViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val readListState = rememberLazyListState()
    val bluetoothPermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val hasBluetoothPermissions = bluetoothPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) viewModel.startScan()
    }

    LaunchedEffect(viewModel.readOutLog.size) {
        if (viewModel.readOutLog.isNotEmpty()) {
            coroutineScope.launch { readListState.animateScrollToItem(viewModel.readOutLog.lastIndex) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = viewModel.connectionStatus.toString(),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(viewModel.connectionStatus.color())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = {
                if (hasBluetoothPermissions) viewModel.startScan() else permissionLauncher.launch(bluetoothPermissions)
            }) {
                Text("Scan for Reader BT")
            }
            OutlinedButton(onClick = viewModel::disconnect) {
                Text("Disconnect")
            }
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, viewModel.hexLog.joinToString("\n"))
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(intent, null))
            }) {
                Text("Share Hex")
            }
            OutlinedButton(onClick = viewModel::clearLogs) {
                Text("Clear Log")
            }
        }

        if (!hasBluetoothPermissions) {
            Text(
                text = "Bluetooth scan and connection permission are required.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (viewModel.devices.isNotEmpty()) {
            Text(
                text = "Discovered Readers",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(viewModel.devices.size) { index ->
                    val device = viewModel.devices[index]
                    OutlinedButton(
                        onClick = { viewModel.connect(device) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(device.displayName)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text(
            text = "Card Readout",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        ReadActivityLog(
            log = viewModel.readOutLog,
            listState = readListState,
            modifier = Modifier.weight(1f),
        )
    }
}
