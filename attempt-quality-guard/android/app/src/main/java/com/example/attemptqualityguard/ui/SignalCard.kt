package com.example.attemptqualityguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.attemptqualityguard.model.SignalStatus

/** One live signal row: label, PASS/FAIL/WAITING pill, and an optional detail line. */
@Composable
fun SignalCard(
    label: String,
    status: SignalStatus,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusPill(status)
        }
    }
}

@Composable
private fun StatusPill(status: SignalStatus) {
    val (text, color) = when (status) {
        SignalStatus.WAITING -> "Waiting" to Color(0xFF616161)
        SignalStatus.PASS -> "Pass" to Color(0xFF1B5E20)
        SignalStatus.FAIL -> "Fail" to Color(0xFFB71C1C)
        SignalStatus.UNKNOWN -> "Unknown" to Color(0xFFE65100)
    }
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(color = color, shape = RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
