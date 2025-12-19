package org.qxqx.qxdroid

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.si.ReadOutObject
import org.qxqx.qxdroid.si.SerialPortManager
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiReader
import org.qxqx.qxdroid.si.toSiRecCommand

@Composable
fun SIReaderPane(
    modifier: Modifier = Modifier,
    usbSerialPort: UsbSerialPort?,
    onConnectionStatusChange: (ConnectionStatus) -> Unit = {}
) {
    val readLog = remember { mutableStateListOf<ReadOutObject>() }
    val hexLog = remember { mutableStateListOf<String>() }
    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected")) }

    val siReader = remember {
        SiReader(
            sendSiFrame = { frame -> /* Will be set up below */ },
            onCardRead = { card -> logSiCard(card, readLog) }
        )
    }

    val serialPortManager = remember {
        SerialPortManager(
            onRawData = { data -> hexLog.add(bytesToHex(data)) },
            onDataFrame = { frame ->
                siReader.onDataFrame(frame)
                logDataFrame(frame, readLog)
            },
            onError = { e ->
                connectionStatus = ConnectionStatus.Disconnected("Error: ${e.message}")
                onConnectionStatusChange(connectionStatus)
            }
        )
    }

    // Update siReader's sendSiFrame callback
    LaunchedEffect(serialPortManager) {
        siReader.sendSiFrame = { frame -> serialPortManager.sendDataFrame(frame) }
    }

    // Handle USB port changes
    LaunchedEffect(usbSerialPort) {
        if (usbSerialPort != null && usbSerialPort.isOpen) {
            serialPortManager.start(usbSerialPort)
            connectionStatus = ConnectionStatus.Connected
        } else {
            serialPortManager.stop()
            if (connectionStatus !is ConnectionStatus.Disconnected) {
                connectionStatus = ConnectionStatus.Disconnected("Disconnected")
            }
        }
        onConnectionStatusChange(connectionStatus)
    }
    var isHexPaneExpanded by rememberSaveable { mutableStateOf(false) }
    val hexListState = rememberLazyListState()
    val readActivityDataState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(hexLog.size) {
        if (hexLog.isNotEmpty()) {
            coroutineScope.launch {
                hexListState.animateScrollToItem(hexLog.size - 1)
            }
        }
    }
    LaunchedEffect(readLog.size) {
        if (readLog.isNotEmpty()) {
            coroutineScope.launch {
                readActivityDataState.animateScrollToItem(readLog.size - 1)
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { 
                readLog.clear()
                hexLog.clear()
            }) {
                Text(text = "Clear Log")
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = if (isHexPaneExpanded) Modifier.weight(1f) else Modifier
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isHexPaneExpanded = !isHexPaneExpanded }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Hex Data",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (isHexPaneExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = if (isHexPaneExpanded) "Collapse" else "Expand"
                    )
                }
                HorizontalDivider()
                if (isHexPaneExpanded) {
                    LazyColumn(state = hexListState) {
                        items(hexLog) {
                            Text(text = it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Text(
                    "Card Readout",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                ReadActivityLog(log = readLog, listState = readActivityDataState)
            }
        }
    }
}

@Composable
fun ReadActivityLog(
    log: List<ReadOutObject>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    var expandedItemIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(log) { index, activity ->
            Column(
                modifier = Modifier
                    .background(if (index % 2 == 0) Color.Transparent else Color(0xFFF0F0F0))
            ) {
                when (activity) {
                    is ReadOutObject.CardReadObject -> {
                        val card = activity.card
                        val isExpanded = expandedItemIndex == index

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedItemIndex = if (isExpanded) {
                                        null // Collapse if already expanded
                                    } else {
                                        index // Expand this item
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "Card ${card.cardNumber}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "(${card.cardKind})"
                                )
                            }
                            Text(
                                text = "Start: ${timeToString(card.startTime)}, Finish: ${timeToString(card.finishTime)}, Check: ${timeToString(card.checkTime)}"
                            )

                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                card.punches.forEachIndexed { punchIndex, punch ->
                                    Text(
                                        text = "${punchIndex + 1}. Code: ${punch.code}, Time: ${timeToString(punch.time)}",
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                    is ReadOutObject.Command -> {
                        Text(
                            text = activity.command.toString(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

fun timeToString(time: UShort): String {
    val seconds = time.toInt()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, secs)
}

private fun logDataFrame(dataFrame: SiDataFrame, readLog: MutableList<ReadOutObject>) {
    val cmd = toSiRecCommand(dataFrame)
    if (cmd is SiCardDetected || cmd is SiCardRemoved) {
        readLog.add(ReadOutObject.Command(cmd))
    }
}

private fun logSiCard(card: SiCard, readLog: MutableList<ReadOutObject>) {
    readLog.add(ReadOutObject.CardReadObject(card))
}




