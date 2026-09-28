package com.bvb.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bvb.android.ui.theme.SuccessGreen

/** mm:ss, like the web app's formatTimeMmSs. */
fun formatTimeMmSs(seconds: Long): String =
    "%02d:%02d".format((seconds / 60).coerceAtLeast(0), (seconds % 60).coerceAtLeast(0))

/** Centered spinner + text, the web app's "waiting-payment" block. */
@Composable
fun WaitingSpinner(text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Big green icon + title, the web app's payment-confirmed header. */
@Composable
fun StepIconHeader(icon: ImageVector, title: String) {
    Spacer(Modifier.height(24.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = SuccessGreen)
    }
}

@Composable
fun SmallCenteredNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
fun PaymentSummaryRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Fee breakdown card matching the web app's "order-payment-summary":
 * deposit, service fee, derived network fee and highlighted total.
 */
@Composable
fun PaymentSummaryCard(
    depositLabel: String,
    depositSat: Long,
    serviceFeeSat: Long,
    totalSat: Long,
) {
    val networkFee = (totalSat - depositSat - serviceFeeSat).coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        PaymentSummaryRow(depositLabel, formatSats(depositSat))
        PaymentSummaryRow("Service Fee", formatSats(serviceFeeSat))
        PaymentSummaryRow("Network Fee", formatSats(networkFee))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        PaymentSummaryRow("Total Invoice", formatSats(totalSat), highlight = true)
    }
}
