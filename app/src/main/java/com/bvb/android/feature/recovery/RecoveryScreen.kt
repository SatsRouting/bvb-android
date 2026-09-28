package com.bvb.android.feature.recovery

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bvb.android.core.network.ApiError
import com.bvb.android.core.network.ApiService
import com.bvb.android.data.model.OrderResponse
import com.bvb.android.data.model.PasswordRequest
import com.bvb.android.ui.components.AppSheet
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.FullScreenLoading
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.formatSats
import com.bvb.android.ui.components.passwordContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class RecoveryUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val orders: List<OrderResponse> = emptyList(),
    val selected: OrderResponse? = null,
    val recovering: Boolean = false,
    val recoverError: String? = null,
    val successMessage: String? = null,
)

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val api: ApiService,
    val biometric: com.bvb.android.core.security.BiometricUnlock,
) : ViewModel() {
    val uiState = MutableStateFlow(RecoveryUiState())

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        if (!silent) uiState.value = uiState.value.copy(refreshing = true)
        viewModelScope.launch {
            try {
                val page = api.listCorruptedOrders()
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = null, orders = page.items,
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    loading = false, refreshing = false, error = ApiError.messageOf(e),
                )
            }
        }
    }

    fun select(order: OrderResponse?) {
        uiState.value = uiState.value.copy(selected = order, recoverError = null)
    }

    fun recover(password: String) {
        val order = uiState.value.selected ?: return
        uiState.value = uiState.value.copy(recovering = true, recoverError = null)
        viewModelScope.launch {
            try {
                api.cancelOrder(order.id, PasswordRequest(password))
                uiState.value = uiState.value.copy(
                    recovering = false,
                    selected = null,
                    successMessage = "Funds recovered: they will arrive in your wallet shortly.",
                )
                load(silent = true)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(recovering = false, recoverError = ApiError.messageOf(e))
            }
        }
    }

    fun dismissSuccess() {
        uiState.value = uiState.value.copy(successMessage = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(viewModel: RecoveryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        FullScreenLoading()
        return
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.load() },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { item { ErrorBanner(it) } }
            state.successMessage?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth().clickable { viewModel.dismissSuccess() }) {
                        Text(
                            msg,
                            Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (state.orders.isEmpty()) {
                item {
                    Column {
                        Text("No orders need recovery", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "If a deposit cannot be processed normally, the order shows up " +
                                "here and you can recover the locked funds with your password.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.orders, key = { it.id }) { order ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.select(order) }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Order ${order.id.take(8)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                formatSats(order.fundingAmount.takeIf { it > 0 } ?: order.amount),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        order.fundingTxId?.let {
                            Text(
                                "TXID ${it.take(16)}…",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            order.createdAt.take(10),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap to recover funds",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    state.selected?.let { order ->
        var password by remember { mutableStateOf("") }
        AppSheet(
            title = "Recover funds",
            onDismiss = { viewModel.select(null) },
            dismissible = !state.recovering,
        ) {
            val bioAction = com.bvb.android.ui.components.rememberBiometricAction(
                viewModel.biometric, "Recover funds", "Authenticate to sign the refund",
            )
            Text(
                "Enter your wallet password to sign the refund of order " +
                    "${order.id.take(8)} back to your wallet.",
            )
            Spacer(Modifier.height(12.dp))
            state.recoverError?.let {
                ErrorBanner(it)
                Spacer(Modifier.height(8.dp))
            }
            if (bioAction != null) {
                LoadingButton(
                    onClick = { bioAction { viewModel.recover(it) } },
                    loading = state.recovering,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirm recovery") }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().passwordContentType(),
                )
                Spacer(Modifier.height(20.dp))
                LoadingButton(
                    onClick = { viewModel.recover(password) },
                    loading = state.recovering,
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirm recovery") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.select(null) },
                enabled = !state.recovering,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}
