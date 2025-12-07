package org.qxqx.qxdroid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.fromColorLong

sealed class ConnectionStatus() {
    object Connected : ConnectionStatus() {
        override fun toString(): String {
            return "Connected"
        }
        override fun color(): Color {
            return Color(0xFF006400)
        }
    }
    data class Connecting(val progress: String) : ConnectionStatus() {
        override fun toString(): String {
            return "Connecting to $progress"
        }
        override fun color(): Color {
            return Color(0xFFFFA500) // orange
        }
    }
    data class Disconnected(val error: String) : ConnectionStatus() {
        override fun toString(): String {
            if (error.isEmpty()) {
                return "Disconnected"
            }
            return error
        }
        override fun color(): Color {
            if (error.isEmpty()) {
                return Color.Gray
            }
            return Color.Red
        }
    }
    abstract fun color(): Color
}
