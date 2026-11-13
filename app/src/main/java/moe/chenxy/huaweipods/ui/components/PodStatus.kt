package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PodStatus(
    batteryParams: BatteryParams,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showCase: Boolean = true
) {
    val dividerColor = MiuixTheme.colorScheme.dividerLine
    val dividerHeight = if (compact) 40.dp else 56.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BatteryColumn(
            label = stringResource(R.string.batt_left_pod),
            pod = batteryParams.left,
            modifier = Modifier.weight(1f),
            compact = compact
        )
        Box(
            modifier = Modifier
                .width(0.5.dp)
                .height(dividerHeight)
                .background(dividerColor)
        )
        BatteryColumn(
            label = stringResource(R.string.batt_right_pod),
            pod = batteryParams.right,
            modifier = Modifier.weight(1f),
            compact = compact
        )
        if (showCase) {
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(dividerHeight)
                    .background(dividerColor)
            )
            BatteryColumn(
                label = stringResource(R.string.pod_case),
                pod = batteryParams.case,
                modifier = Modifier.weight(1f),
                compact = compact
            )
        }
    }
}

@Composable
private fun BatteryColumn(
    label: String,
    pod: PodParams?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isConnected = pod != null && pod.isConnected
    val level = pod?.battery ?: 0

    var lastKnownLevel by remember { mutableIntStateOf(100) }
    if (level > 0) {
        lastKnownLevel = level
    }

    val displayLevel = if (isConnected) "$level%" else "-"
    val iconLevel = if (isConnected) level else lastKnownLevel

    // Pad short labels (左/右) to match width of longest label (耳机盒) using ideographic spaces
    val paddedLabel = if (label.length < 3) label.padEnd(3, '\u3000') else label

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.padding(vertical = if (compact) 2.dp else 4.dp)
        ) {
            Text(
                text = paddedLabel,
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayLevel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Image(
                painter = themedPainterResource(
                    getBatteryIconRes(iconLevel, if (isConnected) pod.isCharging else false)
                ),
                contentDescription = "$label $displayLevel",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun getBatteryIconRes(level: Int, isCharging: Boolean): Int {
    val index = when {
        level <= 10 -> 1
        level <= 20 -> 2
        level <= 30 -> 3
        level <= 40 -> 4
        level <= 50 -> 5
        level <= 60 -> 6
        level <= 70 -> 7
        level <= 80 -> 8
        level <= 90 -> 9
        else -> 10
    }
    return if (isCharging) {
        when (index) {
            1 -> R.drawable.charge_1
            2 -> R.drawable.charge_2
            3 -> R.drawable.charge_3
            4 -> R.drawable.charge_4
            5 -> R.drawable.charge_5
            6 -> R.drawable.charge_6
            7 -> R.drawable.charge_7
            8 -> R.drawable.charge_8
            9 -> R.drawable.charge_9
            else -> R.drawable.charge_10
        }
    } else {
        when (index) {
            1 -> R.drawable.common_1
            2 -> R.drawable.common_2
            3 -> R.drawable.common_3
            4 -> R.drawable.common_4
            5 -> R.drawable.common_5
            6 -> R.drawable.common_6
            7 -> R.drawable.common_7
            8 -> R.drawable.common_8
            9 -> R.drawable.common_9
            else -> R.drawable.common_10
        }
    }
}

/**
 * Loads a drawable resource respecting the app's theme override.
 * When the app theme differs from the system night mode, creates a configuration context
 * with the correct night mode to load the appropriate drawable variant (e.g. drawable-night-nodpi).
 */
@Composable
@SuppressLint("LocalContextConfigurationRead")
private fun themedPainterResource(@androidx.annotation.DrawableRes id: Int): Painter {
    val context = LocalContext.current
    val themeConfig = LocalConfiguration.current
    val sysNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val themeNightMode = themeConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

    return if (sysNightMode == themeNightMode) {
        painterResource(id)
    } else {
        val themedResources = remember(context, themeNightMode) {
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or themeNightMode
                }
            ).resources
        }
        remember(id, themeNightMode) {
            BitmapPainter(
                (themedResources.getDrawable(id, null) as BitmapDrawable).bitmap.asImageBitmap()
            )
        }
    }
}

@Composable
fun CompactBatteryRow(
    batteryParams: BatteryParams,
    modifier: Modifier = Modifier,
    showCase: Boolean = true,
) {
    val contentColor = MiuixTheme.colorScheme.onBackground
    val cutoutColor = MiuixTheme.colorScheme.background
    val chargingColor = MiuixTheme.colorScheme.primary
    val badgeBgColor = MiuixTheme.colorScheme.onSurface
    val badgeTextColor = MiuixTheme.colorScheme.background

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showCase) {
            val casePod = batteryParams.case
            val isConnected = casePod != null && casePod.isConnected
            val level = casePod?.battery ?: 0
            val isCharging = casePod?.isCharging == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            ) {
                CaseBatteryIcon(
                    color = if (isCharging) chargingColor else contentColor,
                    cutoutColor = cutoutColor,
                )
                Text(
                    text = if (isConnected && level > 0) "$level%" else if (isConnected) "$level%" else "-",
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (isCharging) {
                    Spacer(modifier = Modifier.width(2.dp))
                    ChargingIndicator()
                }
            }
        }

        // Left earbud
        val leftPod = batteryParams.left
        val isLeftConnected = leftPod != null && leftPod.isConnected
        val leftLevel = leftPod?.battery ?: 0
        val isLeftCharging = leftPod?.isCharging == true
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        ) {
            EarbudBadge(
                letter = "L",
                bgColor = if (isLeftCharging) chargingColor else badgeBgColor,
                textColor = if (isLeftCharging) MiuixTheme.colorScheme.onPrimary else badgeTextColor,
            )
            Text(
                text = if (isLeftConnected && leftLevel > 0) "$leftLevel%" else if (isLeftConnected) "$leftLevel%" else "-",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            if (isLeftCharging) {
                Spacer(modifier = Modifier.width(2.dp))
                ChargingIndicator()
            }
        }

        // Right earbud
        val rightPod = batteryParams.right
        val isRightConnected = rightPod != null && rightPod.isConnected
        val rightLevel = rightPod?.battery ?: 0
        val isRightCharging = rightPod?.isCharging == true
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        ) {
            EarbudBadge(
                letter = "R",
                bgColor = if (isRightCharging) chargingColor else badgeBgColor,
                textColor = if (isRightCharging) MiuixTheme.colorScheme.onPrimary else badgeTextColor,
            )
            Text(
                text = if (isRightConnected && rightLevel > 0) "$rightLevel%" else if (isRightConnected) "$rightLevel%" else "-",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            if (isRightCharging) {
                Spacer(modifier = Modifier.width(2.dp))
                ChargingIndicator()
            }
        }
    }
}

@Composable
private fun CaseBatteryIcon(
    color: Color,
    cutoutColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 17.dp, height = 12.dp)) {
        val corner = 3.5.dp.toPx()
        val seamThickness = 1.2.dp.toPx()
        val lidHeight = size.height * 0.40f
        val baseY = lidHeight + seamThickness
        val baseHeight = size.height - baseY

        // Filled top lid
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, 0f),
            size = Size(size.width, lidHeight),
            cornerRadius = CornerRadius(corner, corner),
        )

        // Filled bottom base
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, baseY),
            size = Size(size.width, baseHeight),
            cornerRadius = CornerRadius(corner, corner),
        )

        // Center cutout LED dot on bottom base
        val ledRadius = 1.0.dp.toPx()
        val ledY = baseY + baseHeight * 0.46f
        drawCircle(
            color = cutoutColor,
            radius = ledRadius,
            center = Offset(size.width / 2f, ledY),
        )
    }
}

@Composable
private fun EarbudBadge(
    letter: String,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .background(bgColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = textColor,
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChargingIndicator(
    modifier: Modifier = Modifier,
) {
    val chargingColor = MiuixTheme.colorScheme.primary
    Canvas(modifier = modifier.size(width = 8.dp, height = 12.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.6f, 0f)
            lineTo(0f, size.height * 0.55f)
            lineTo(size.width * 0.45f, size.height * 0.55f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width, size.height * 0.45f)
            lineTo(size.width * 0.55f, size.height * 0.45f)
            close()
        }
        drawPath(path, color = chargingColor)
    }
}
