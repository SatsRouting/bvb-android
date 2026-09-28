package com.bvb.android.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.bvb.android.AppViewModel
import com.bvb.android.R
import com.bvb.android.core.security.promptBiometric
import com.bvb.android.ui.components.ErrorBanner
import com.bvb.android.ui.components.LoadingButton
import com.bvb.android.ui.components.passwordContentType

/**
 * Full-screen gate shown after a cold start when biometric unlock is enrolled.
 * The JWT on disk is not enough: the user must unlock with biometrics or the
 * account password before the rest of the app is reachable.
 */
@Composable
fun AppLockScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var showPasswordForm by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }
    var promptNonce by remember { mutableStateOf(0) }

    val biometricReady = remember { viewModel.biometric.decryptCipher() != null }

    fun tryBiometric() {
        if (activity == null) {
            showPasswordForm = true
            return
        }
        val cipher = viewModel.biometric.decryptCipher()
        if (cipher == null) {
            // Key was invalidated (new fingerprint enrolled). Fall back to password.
            showPasswordForm = true
            error = "Biometric unlock expired. Enter your password."
            return
        }
        error = null
        promptBiometric(
            activity = activity,
            cipher = cipher,
            title = "Unlock BVB",
            subtitle = "Confirm your identity to restore your session",
            negativeText = "Use password",
            onSuccess = { authed ->
                val recovered = viewModel.biometric.recover(authed)
                if (recovered == null) {
                    showPasswordForm = true
                    error = "Could not restore the session. Enter your password."
                    return@promptBiometric
                }
                unlocking = true
                viewModel.unlockSession(recovered) { ok ->
                    unlocking = false
                    if (!ok) {
                        showPasswordForm = true
                        error = "Could not restore the session. Enter your password."
                    }
                }
            },
            onError = {
                // Cancel / "Use password" / back: stay on this screen.
                showPasswordForm = true
            },
        )
    }

    LaunchedEffect(promptNonce) {
        if (!showPasswordForm && biometricReady) tryBiometric()
        if (!biometricReady) showPasswordForm = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(
                if (isSystemInDarkTheme()) R.drawable.logo_bvb_p2p_light else R.drawable.logo_bvb_p2p_dark
            ),
            contentDescription = "Bitcoin Voucher Bot P2P",
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Text("Session locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlock with your fingerprint, face, or device PIN/pattern — or enter your password to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        error?.let {
            ErrorBanner(it)
            Spacer(Modifier.height(16.dp))
        }

        if (showPasswordForm) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().passwordContentType(),
            )
            Spacer(Modifier.height(16.dp))
            LoadingButton(
                onClick = {
                    unlocking = true
                    error = null
                    viewModel.unlockSession(password) { ok ->
                        unlocking = false
                        if (!ok) error = "Wrong password"
                    }
                },
                loading = unlocking,
                enabled = password.length >= 8,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
            if (biometricReady) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        showPasswordForm = false
                        promptNonce++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use device unlock") }
            }
        } else {
            OutlinedButton(
                onClick = { tryBiometric() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showPasswordForm = true }) {
                Text("Use password")
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { viewModel.logout() }) {
            Text("Log out")
        }
    }
    }
}
