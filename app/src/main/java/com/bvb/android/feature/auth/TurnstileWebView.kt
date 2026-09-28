package com.bvb.android.feature.auth

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bvb.android.BuildConfig

/**
 * Renders the Cloudflare Turnstile widget in a WebView and hands the solved
 * token back to Compose. The page is served from a data URL but declares the
 * backend origin via the base URL so Turnstile domain validation passes.
 * Only needed when the backend has Turnstile enabled.
 *
 * Turnstile tokens are single-use and expire after ~5 minutes, so:
 * - [onToken] is called with null when the token expires or errors out
 *   (the widget then refreshes itself and delivers a fresh token);
 * - bump [resetKey] after a failed submit to force a fresh token, like the
 *   web client does with turnstile.reset() after every login failure.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileWebView(
    siteKey: String,
    onToken: (String?) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Int = 0,
) {
    val theme = if (isSystemInDarkTheme()) "dark" else "light"
    AndroidView(
        modifier = modifier.fillMaxWidth().height(80.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
                setBackgroundColor(Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun postToken(token: String) {
                        post { onToken(token) }
                    }

                    @JavascriptInterface
                    fun postInvalid() {
                        post { onToken(null) }
                    }
                }, "AndroidBridge")

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
                      <style>
                        html, body { margin: 0; background: transparent; }
                        body {
                          display: flex;
                          justify-content: center;
                          align-items: center;
                          min-height: 100vh;
                        }
                      </style>
                    </head>
                    <body>
                      <div class="cf-turnstile"
                           data-sitekey="$siteKey"
                           data-theme="$theme"
                           data-refresh-expired="auto"
                           data-callback="onTurnstileSuccess"
                           data-expired-callback="onTurnstileInvalid"
                           data-error-callback="onTurnstileInvalid"></div>
                      <script>
                        function onTurnstileSuccess(token) {
                          AndroidBridge.postToken(token);
                        }
                        function onTurnstileInvalid() {
                          AndroidBridge.postInvalid();
                        }
                      </script>
                    </body>
                    </html>
                """.trimIndent()
                loadDataWithBaseURL(BuildConfig.BASE_URL, html, "text/html", "utf-8", null)
                tag = resetKey
            }
        },
        update = { webView ->
            // Tokens are single-use: after a failed submit the caller bumps
            // resetKey and the widget generates a fresh token.
            val lastKey = webView.tag as? Int ?: 0
            if (lastKey != resetKey) {
                webView.tag = resetKey
                webView.evaluateJavascript(
                    "if (window.turnstile) { turnstile.reset(); }",
                    null,
                )
            }
        },
    )
}
