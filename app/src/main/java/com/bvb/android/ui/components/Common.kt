package com.bvb.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bvb.android.core.security.BiometricUnlock
import com.bvb.android.core.security.promptBiometricRecover
import com.bvb.android.ui.theme.SuccessGreen
import com.bvb.android.ui.theme.WarningAmber
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Marks a field as a password for autofill services. Without an explicit
 * hint, password managers (e.g. Bitwarden) fall back to heuristics and can
 * dump the username into an unrelated nearby field.
 */
fun Modifier.passwordContentType(): Modifier =
    this.semantics { contentType = androidx.compose.ui.autofill.ContentType.Password }

/**
 * Explicitly excludes a field from autofill. Needed on free-text fields that
 * live next to a password field: otherwise password managers heuristically
 * treat them as the "username" field and fill them with the avatar ID.
 */
fun Modifier.noAutofill(): Modifier =
    this.semantics { contentDataType = androidx.compose.ui.autofill.ContentDataType.None }

@Composable
fun FullScreenLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Unwraps ContextWrappers (theme wrappers, dialog windows) to the hosting Activity. */
fun android.content.Context.findFragmentActivity(): androidx.fragment.app.FragmentActivity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is androidx.fragment.app.FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Returns a trigger for the "authenticate, then act" flow, or null when the App
 * lock is not enrolled / the device can't authenticate / the host is not a
 * FragmentActivity — in which case callers fall back to the manual password
 * field. When non-null, invoking the returned lambda opens the biometric prompt
 * and, on success, hands the recovered password to [onPassword] (which should
 * run the exact same action as the typed password).
 *
 * Inside a ModalBottomSheet/Dialog LocalContext.current is a ContextWrapper,
 * not the Activity, so the base-context chain is walked to find it.
 */
@Composable
fun rememberBiometricAction(
    biometric: BiometricUnlock,
    title: String,
    subtitle: String,
): ((onPassword: (String) -> Unit) -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context.findFragmentActivity()
    if (activity == null || !biometric.isEnabled || !biometric.canAuthenticate()) return null
    return { onPassword ->
        promptBiometricRecover(
            activity = activity,
            biometric = biometric,
            title = title,
            subtitle = subtitle,
            onPassword = onPassword,
            onError = { msg -> AppSnackbar.show(msg) },
        )
    }
}

/**
 * Standard bottom sheet used across the app for confirmations and forms,
 * replacing the old AlertDialog popups. Set [dismissible] to false to keep
 * the sheet open while an action is in progress.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible },
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding(),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Three pulsing dots, used as the in-button loading indicator. */
@Composable
fun LoadingDots(
    color: Color = LocalContentColor.current,
    dotSize: Dp = 7.dp,
) {
    val transition = rememberInfiniteTransition(label = "loadingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(450),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * Content wrapper for buttons with a loading state: the label stays measured
 * (so the button never changes size) but is hidden while pulsing dots fade
 * in on top. The button keeps its normal colors instead of turning grey.
 */
@Composable
private fun LoadingButtonContent(loading: Boolean, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = if (loading) 0f else 1f }) { content() }
        androidx.compose.animation.AnimatedVisibility(
            visible = loading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) { LoadingDots() }
    }
}

@Composable
fun LoadingButton(
    onClick: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable () -> Unit,
) {
    Button(
        onClick = { if (!loading) onClick() },
        // While loading the button stays visually enabled (no grey flash);
        // the guarded onClick prevents double submissions.
        enabled = enabled || loading,
        colors = colors,
        modifier = modifier,
    ) { LoadingButtonContent(loading, content) }
}

@Composable
fun LoadingOutlinedButton(
    onClick: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled || loading,
        colors = colors,
        modifier = modifier,
    ) { LoadingButtonContent(loading, content) }
}

@Composable
fun LoadingTextButton(
    onClick: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled || loading,
        modifier = modifier,
    ) { LoadingButtonContent(loading, content) }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onError,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "open", "funded", "completed", "resolved", "claimed" -> SuccessGreen
        "pending", "matched", "payment_sent", "in_progress" -> WarningAmber
        "disputed", "penalized", "corrupted" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.replace('_', ' ').uppercase(),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Robot avatar served by the backend, like the web app's AvatarIcon.
 * Images are deterministic and immutable (long-lived cache headers), and we
 * request 2x pixels for high-DPI screens.
 */
@Composable
fun AvatarImage(avatarId: String, sizeDp: Int, modifier: Modifier = Modifier) {
    if (avatarId.isEmpty()) return
    val requestPx = (sizeDp * 2).coerceIn(32, 512)
    coil.compose.AsyncImage(
        model = "${com.bvb.android.BuildConfig.BASE_URL}/api/avatar/$avatarId.png?size=$requestPx&v=3",
        contentDescription = "Avatar $avatarId",
        modifier = modifier.size(sizeDp.dp),
    )
}

@Composable
fun QrCode(content: String, sizeDp: Int = 240, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code",
            modifier = Modifier.size(sizeDp.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp),
        )
    }
}

fun formatSats(sats: Long): String = "%,d sat".format(sats)

/** dd MMM yyyy, HH:mm in the device timezone. */
fun formatDate(iso: String): String = try {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
} catch (e: Exception) {
    iso
}

/**
 * Same icon assets as the web app's PaymentMethodIcon. Normalized so that
 * legacy values saved by early app versions ("SEPA Instant") still match.
 */
private fun paymentMethodIconRes(method: String): Int = when (method.lowercase().replace(" ", "")) {
    "amazon" -> com.bvb.android.R.drawable.pm_amazon
    "paysend" -> com.bvb.android.R.drawable.pm_paysend
    "wise" -> com.bvb.android.R.drawable.pm_wise
    "revolut" -> com.bvb.android.R.drawable.pm_revolut
    "yuh" -> com.bvb.android.R.drawable.pm_yuh
    "zen" -> com.bvb.android.R.drawable.pm_zen
    "usdt" -> com.bvb.android.R.drawable.pm_usdt
    "usdtliquid" -> com.bvb.android.R.drawable.pm_usdtliquid
    "sepa" -> com.bvb.android.R.drawable.pm_sepa
    "instantsepa", "sepainstant" -> com.bvb.android.R.drawable.pm_instantsepa
    else -> com.bvb.android.R.drawable.pm_instantsepa
}

/**
 * Payment method icon strip: small rounded squares with a white background,
 * same assets as the web PaymentMethodIcon. Methods beyond maxVisible are
 * silently dropped.
 */
@Composable
fun PaymentMethodIcons(
    methods: List<String>,
    sizeDp: Int = 24,
    maxVisible: Int = 0,
    modifier: Modifier = Modifier,
) {
    val visible = if (maxVisible > 0) methods.take(maxVisible) else methods
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        visible.forEachIndexed { index, method ->
            if (index > 0) Spacer(Modifier.size(4.dp))
            Image(
                painter = painterResource(paymentMethodIconRes(method)),
                contentDescription = paymentMethodLabel(method),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(sizeDp.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            )
        }
    }
}

/** Same labels as the web app's PaymentMethodIcon. */
fun paymentMethodLabel(method: String): String = when (method) {
    "amazon" -> "Amazon"
    "paysend" -> "Paysend"
    "wise" -> "Wise"
    "revolut" -> "Revolut"
    "yuh" -> "Yuh"
    "zen" -> "Zen"
    "usdt" -> "USDT"
    "usdtliquid" -> "USDT Liquid"
    "sepa" -> "SEPA"
    "instantsepa" -> "InstantSepa"
    else -> method
}

private const val LBTC_ASSET_ID =
    "6f0279e9ed041c3d710a9f57d0c02928416460c4b722ae3457a11eec381c526d"

/** Same link building as the web app's liquidExplorerUrl(). */
fun liquidExplorerUrl(
    txid: String,
    amount: Long? = null,
    valueBlinder: String? = null,
    assetBlinder: String? = null,
    blindingData: String? = null,
): String {
    if (!blindingData.isNullOrEmpty()) {
        return "https://blockstream.info/liquid/tx/$txid#blinded=$blindingData"
    }
    if (amount != null && !valueBlinder.isNullOrEmpty() && !assetBlinder.isNullOrEmpty()) {
        return "https://blockstream.info/liquid/tx/$txid#blinded=$amount,$LBTC_ASSET_ID,$valueBlinder,$assetBlinder"
    }
    return "https://blockstream.info/liquid/tx/$txid"
}

/**
 * Chain-aware explorer link, mirroring the web app: Bitcoin on-chain settlements
 * point to mempool.space, Liquid keeps the blinded blockstream.info links.
 */
fun explorerTxUrl(
    settlementChain: String?,
    txid: String,
    amount: Long? = null,
    valueBlinder: String? = null,
    assetBlinder: String? = null,
    blindingData: String? = null,
): String {
    if (settlementChain == "bitcoin") {
        return "https://mempool.space/tx/$txid"
    }
    return liquidExplorerUrl(txid, amount, valueBlinder, assetBlinder, blindingData)
}

/** Opens a URL in the default browser, ignoring devices without one. */
fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        )
    } catch (e: Exception) {
        // No browser installed; nothing sensible to do.
    }
}

fun formatFiat(amount: Double, currency: String): String = "%,.2f %s".format(amount, currency)

fun formatCountdown(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "expired"
    val d = totalSeconds / 86400
    val h = (totalSeconds % 86400) / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m ${s}s"
        else -> "${m}m ${s}s"
    }
}

/**
 * Public reputation display, like the web app's ReputationStars: 0..5 stars
 * based on the trust level computed by the reputation engine.
 */
@Composable
fun ReputationStars(level: Int, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val lvl = level.coerceIn(0, 5)
    val filledColor = Color(0xFFF5A623)
    Row(modifier) {
        repeat(5) { i ->
            Text(
                if (i < lvl) "★" else "☆",
                color = if (i < lvl) filledColor else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = fontSize,
                lineHeight = fontSize,
            )
        }
    }
}

/** Compact single-unit label like the web CircularTimer: 6d, 24h, 40m, 22s. */
fun formatTimeCompact(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds % 60}s"
    }
}

/** Same 168h/12h/24h/96h timelocks as the web app's config/timelock.js. */
fun totalTimelockSeconds(status: String): Long = when (status) {
    "pending" -> 10L * 60
    "open", "in_progress" -> 168L * 3600
    "matched" -> 12L * 3600
    "funded" -> 24L * 3600
    "payment_sent" -> 96L * 3600
    else -> 0L
}

/**
 * Live-ticking circular countdown, like the web app's CircularTimer:
 * green ring that shrinks clockwise and turns red under 14% of the total
 * time, with a compact remaining-time label in the middle.
 */
@Composable
fun CircularTimer(
    remainingSeconds: Long,
    totalSeconds: Long,
    sizeDp: Int = 40,
    strokeWidthDp: Int = 3,
    modifier: Modifier = Modifier,
) {
    var timeLeft by androidx.compose.runtime.remember(remainingSeconds) {
        androidx.compose.runtime.mutableLongStateOf(remainingSeconds)
    }
    androidx.compose.runtime.LaunchedEffect(remainingSeconds) {
        while (timeLeft > 0) {
            kotlinx.coroutines.delay(1000)
            timeLeft--
        }
    }
    val fraction = if (totalSeconds > 0) {
        (timeLeft.toFloat() / totalSeconds).coerceIn(0f, 1f)
    } else 0f
    val ringColor = if (fraction > 0.14f) SuccessGreen else MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidthDp.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
        Text(
            formatTimeCompact(timeLeft),
            style = MaterialTheme.typography.labelSmall,
            fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
    }
}
