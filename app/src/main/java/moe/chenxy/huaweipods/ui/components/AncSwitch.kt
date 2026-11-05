package moe.chenxy.huaweipods.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.pods.HuaweiAncLevel
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.ancLevelOptions
import moe.chenxy.huaweipods.pods.isNoiseCancellation
import moe.chenxy.huaweipods.pods.supportsAncDirectionDial
import moe.chenxy.huaweipods.pods.supportsDiscreteAncLevels
import moe.chenxy.huaweipods.pods.supportsSoundcoreAnc
import moe.chenxy.huaweipods.pods.supportsTransparency
import moe.chenxy.huaweipods.pods.defaultTransparencySubMode
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreStateManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    deviceRoute: HuaweiDeviceRoute = HuaweiDeviceRoute.HUAWEI_FREEBUDS3,
    compact: Boolean = false,
    huaweiAncLevel: Int = 0,
    onHuaweiAncLevelChange: ((Int) -> Unit)? = null,
    isAdaptive: Boolean = false,
    onAncModeSelect: ((NoiseControlMode, Boolean, Int?) -> Unit)? = null,
    showTitle: Boolean = true,
) {
    val verticalPadding = if (!showTitle) 0.dp else if (compact) 8.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
    ) {
        if (deviceRoute.supportsSoundcoreAnc) {
            SoundcoreNoiseControlSection(
                ancStatus = ancStatus,
                isAdaptive = isAdaptive,
                onModeSelect = { mode, adaptive, subMode ->
                    if (onAncModeSelect != null) {
                        onAncModeSelect(mode, adaptive, subMode)
                    } else {
                        onAncModeChange(mode)
                    }
                },
                huaweiAncLevel = huaweiAncLevel,
                onHuaweiAncLevelChange = onHuaweiAncLevelChange,
                compact = compact,
                maxManualLevel = if (deviceRoute == HuaweiDeviceRoute.SOUNDCORE_LIBERTY_3_PRO) 3 else 5,
                showTitle = showTitle,
            )
        } else {
            if (deviceRoute.supportsTransparency) {
                HuaweiNoiseControlSelector(
                    selectedMode = ancStatus,
                    onModeChange = onAncModeChange,
                    compact = compact,
                    showTitle = showTitle,
                )
            } else {
                HuaweiAncSimpleHeader(
                    enabled = ancStatus.isNoiseCancellation(),
                    onToggle = {
                        onAncModeChange(
                            if (ancStatus.isNoiseCancellation()) {
                                NoiseControlMode.OFF
                            } else {
                                NoiseControlMode.NOISE_CANCELLATION
                            },
                        )
                    },
                    compact = compact,
                )
            }

            if (
                ancStatus.isNoiseCancellation() &&
                onHuaweiAncLevelChange != null &&
                deviceRoute.supportsDiscreteAncLevels
            ) {
            HuaweiAncSubModeSelector(
                title = stringResource(R.string.anc_level_title),
                values = deviceRoute.ancLevelOptions.map { option ->
                    val label = when (option.level) {
                        HuaweiAncLevel.ADAPTIVE -> stringResource(
                            if (deviceRoute == HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5) {
                                R.string.freebuds_pro5_anc_level_adaptive
                            } else {
                                R.string.anc_level_adaptive
                            },
                        )
                        HuaweiAncLevel.LIGHT -> stringResource(R.string.anc_level_light)
                        HuaweiAncLevel.BALANCED -> stringResource(R.string.anc_level_balanced)
                        HuaweiAncLevel.DEEP -> stringResource(R.string.anc_level_deep)
                    }
                    option.protocolValue to label
                },
                selectedValue = huaweiAncLevel,
                onValueChange = onHuaweiAncLevelChange,
                compact = compact,
                modifier = Modifier.padding(top = if (compact) 8.dp else 14.dp),
            )
        } else if (
            ancStatus == NoiseControlMode.TRANSPARENCY &&
            deviceRoute != HuaweiDeviceRoute.HUAWEI_FREEBUDS7 &&
            onHuaweiAncLevelChange != null &&
            deviceRoute.supportsTransparency
        ) {
            val standardValue = deviceRoute.defaultTransparencySubMode ?: 0xFF
            val standard = standardValue to stringResource(R.string.transparency_standard)
            val voice = 0x01 to stringResource(R.string.transparency_voice)
            val values = if (deviceRoute == HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5) {
                listOf(
                    standard,
                    voice,
                    0x04 to stringResource(R.string.transparency_adaptive),
                )
            } else {
                listOf(standard, voice)
            }
            HuaweiAncSubModeSelector(
                title = stringResource(R.string.transparency_level_title),
                values = values,
                selectedValue = huaweiAncLevel,
                onValueChange = onHuaweiAncLevelChange,
                compact = compact,
                modifier = Modifier.padding(top = if (compact) 8.dp else 14.dp),
            )
        } else if (
            ancStatus.isNoiseCancellation() &&
            onHuaweiAncLevelChange != null &&
            deviceRoute.supportsAncDirectionDial
        ) {
            HuaweiAncLevelDial(
                level = huaweiAncLevel.coerceIn(0, 8),
                onLevelChange = onHuaweiAncLevelChange,
                compact = compact,
                modifier = Modifier.padding(top = if (compact) 8.dp else 14.dp)
            )
        }
    }
}
}

@Composable
private fun HuaweiNoiseControlSelector(
    selectedMode: NoiseControlMode,
    onModeChange: (NoiseControlMode) -> Unit,
    compact: Boolean,
    showTitle: Boolean = true,
) {
    if (showTitle) {
        Text(
            text = stringResource(R.string.noise_control_title),
            fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 2.dp else 4.dp,
            ),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        listOf(
            NoiseControlMode.TRANSPARENCY to R.string.transparency_mode,
            NoiseControlMode.NOISE_CANCELLATION to R.string.noise_cancellation_title,
            NoiseControlMode.OFF to R.string.off,
        ).forEach { (mode, labelRes) ->
            HuaweiAncChoice(
                label = stringResource(labelRes),
                selected = selectedMode == mode,
                onClick = { onModeChange(mode) },
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SoundcoreNoiseControlSection(
    ancStatus: NoiseControlMode,
    isAdaptive: Boolean,
    onModeSelect: (NoiseControlMode, Boolean, Int?) -> Unit,
    huaweiAncLevel: Int,
    onHuaweiAncLevelChange: ((Int) -> Unit)?,
    compact: Boolean,
    maxManualLevel: Int,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    var savedManualLevel by remember {
        mutableIntStateOf(huaweiAncLevel.takeIf { it in 1..maxManualLevel } ?: maxManualLevel)
    }
    var sliderFloat by remember {
        mutableFloatStateOf((huaweiAncLevel.takeIf { it in 1..maxManualLevel } ?: maxManualLevel).toFloat())
    }

    LaunchedEffect(huaweiAncLevel) {
        if (huaweiAncLevel in 1..maxManualLevel) {
            savedManualLevel = huaweiAncLevel
            sliderFloat = huaweiAncLevel.toFloat()
        }
    }

    val isOffSelected = ancStatus == NoiseControlMode.OFF
    val isCancellingSelected = ancStatus == NoiseControlMode.NOISE_CANCELLATION && !isAdaptive
    val isTransparencySelected = ancStatus == NoiseControlMode.TRANSPARENCY
    val isAdaptiveSelected = ancStatus == NoiseControlMode.NOISE_CANCELLATION && isAdaptive

    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.noise_control_title),
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 14.dp,
                    vertical = if (compact) 2.dp else 4.dp,
                ),
            )
        }

        val buttonsHorizontalPadding = if (!showTitle) 0.dp else if (compact) 6.dp else 10.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = buttonsHorizontalPadding,
                    vertical = if (compact) 8.dp else 12.dp,
                ),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Top,
        ) {
            // 1. Noise cancelling (Manual)
            AncRoundButton(
                iconRes = R.drawable.ic_mydevices_anc_cancelling,
                label = stringResource(R.string.anc_mode_cancelling),
                selected = isCancellingSelected,
                onClick = {
                    onModeSelect(NoiseControlMode.NOISE_CANCELLATION, false, savedManualLevel)
                },
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            // 2. Adaptive
            AncRoundButton(
                iconRes = R.drawable.ic_mydevices_anc_adaptive,
                label = stringResource(R.string.anc_mode_adaptive),
                selected = isAdaptiveSelected,
                onClick = { onModeSelect(NoiseControlMode.NOISE_CANCELLATION, true, null) },
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            // 3. Off
            AncRoundButton(
                iconRes = R.drawable.ic_mydevices_anc_off,
                label = stringResource(R.string.anc_mode_off),
                selected = isOffSelected,
                onClick = { onModeSelect(NoiseControlMode.OFF, false, null) },
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            // 4. Transparency
            AncRoundButton(
                iconRes = R.drawable.ic_mydevices_anc_transparency,
                label = stringResource(R.string.anc_mode_transparency),
                selected = isTransparencySelected,
                onClick = { onModeSelect(NoiseControlMode.TRANSPARENCY, false, null) },
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }

        val hasSubControl = isCancellingSelected || isAdaptiveSelected || isTransparencySelected
        if (hasSubControl) {
            val horizontalPadding = if (!showTitle) 4.dp else if (compact) 12.dp else 24.dp
            val unifiedSubControlHeight = 62.dp
            val unifiedVerticalPadding = if (!showTitle) 4.dp else 4.dp

            if (isCancellingSelected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = unifiedSubControlHeight)
                        .padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = unifiedVerticalPadding,
                            bottom = unifiedVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.soundcore_anc_manual_level),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.headline1,
                        )
                        Text(
                            text = "$savedManualLevel/$maxManualLevel",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }

                    Slider(
                        value = sliderFloat,
                        onValueChange = { newFloat ->
                            sliderFloat = newFloat
                            val rounded = newFloat.roundToInt().coerceIn(1, maxManualLevel)
                            if (rounded != savedManualLevel) {
                                savedManualLevel = rounded
                                onHuaweiAncLevelChange?.invoke(rounded)
                            }
                        },
                        onValueChangeFinished = {
                            val rounded = sliderFloat.roundToInt().coerceIn(1, maxManualLevel)
                            sliderFloat = rounded.toFloat()
                            if (rounded != savedManualLevel) {
                                savedManualLevel = rounded
                                onHuaweiAncLevelChange?.invoke(rounded)
                            }
                        },
                        valueRange = 1f..maxManualLevel.toFloat(),
                        steps = if (maxManualLevel > 2) maxManualLevel - 2 else 0,
                        showKeyPoints = true,
                        keyPoints = (1..maxManualLevel).map { it.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (isAdaptiveSelected) {
                var envDetection by remember { mutableStateOf(SoundcoreStateManager.environmentDetection) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = unifiedSubControlHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Switch) {
                            val target = !envDetection
                            envDetection = target
                            SoundcoreBackend.setEnvironmentDetection(target)
                        }
                        .padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = unifiedVerticalPadding,
                            bottom = unifiedVerticalPadding,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.soundcore_environment_detection),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.headline1,
                        )
                        Text(
                            text = stringResource(R.string.soundcore_environment_detection_summary),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = envDetection,
                        onCheckedChange = { target ->
                            envDetection = target
                            SoundcoreBackend.setEnvironmentDetection(target)
                        }
                    )
                }
            } else if (isTransparencySelected) {
                var transMode by remember { mutableStateOf(SoundcoreStateManager.transparencyMode) }
                val pillBg = MiuixTheme.colorScheme.secondary
                val primaryColor = MiuixTheme.colorScheme.primary
                val transVerticalPadding = if (!showTitle) 8.dp else 14.dp
                val checkSlotSize = 18.dp

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = transVerticalPadding,
                            bottom = transVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.soundcore_transparency_mode),
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.headline1,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Option 0: Full Transparent
                        val isFull = transMode == 0
                        val interaction0 = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(pillBg)
                                .pressable(interactionSource = interaction0, indication = SinkFeedback())
                                .clickable(
                                    interactionSource = interaction0,
                                    indication = null,
                                    role = Role.RadioButton,
                                ) {
                                    transMode = 0
                                    SoundcoreBackend.setTransparencyMode(0)
                                }
                                .padding(start = 10.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Box(
                                modifier = Modifier.size(checkSlotSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                RadioButton(
                                    selected = isFull,
                                    onClick = null,
                                    modifier = Modifier.scale(0.7f),
                                    colors = RadioButtonDefaults.radioButtonColors(
                                        selectedColor = primaryColor,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.soundcore_transparency_full),
                                color = if (isFull) primaryColor else MiuixTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = if (isFull) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Option 1: Vocal Mode
                        val isVocal = transMode == 1
                        val interaction1 = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(pillBg)
                                .pressable(interactionSource = interaction1, indication = SinkFeedback())
                                .clickable(
                                    interactionSource = interaction1,
                                    indication = null,
                                    role = Role.RadioButton,
                                ) {
                                    transMode = 1
                                    SoundcoreBackend.setTransparencyMode(1)
                                }
                                .padding(start = 10.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Box(
                                modifier = Modifier.size(checkSlotSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                RadioButton(
                                    selected = isVocal,
                                    onClick = null,
                                    modifier = Modifier.scale(0.7f),
                                    colors = RadioButtonDefaults.radioButtonColors(
                                        selectedColor = primaryColor,
                                    ),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.soundcore_transparency_vocal),
                                color = if (isVocal) primaryColor else MiuixTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = if (isVocal) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AncRoundButton(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val primary = MiuixTheme.colorScheme.primary
    val circleSize = if (compact) 44.dp else 56.dp
    val iconSize = if (compact) 22.dp else 26.dp
    val isDark = isSystemInDarkTheme()
    val textColor = if (selected) {
        if (isDark) Color.White else Color.Black
    } else {
        if (isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
    }

    Column(
        modifier = modifier
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .background(
                    color = if (selected) primary else if (isDark) MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f) else Color.White,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = if (selected) Color.White else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
        Text(
            text = label,
            fontSize = if (compact) 10.sp else 11.5.sp,
            lineHeight = if (compact) 12.sp else 13.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun HuaweiAncSubModeSelector(
    title: String,
    values: List<Pair<Int, String>>,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp),
        )
        values.chunked(2).forEachIndexed { rowIndex, rowValues ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 8.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp,
                        top = if (rowIndex == 0) 4.dp else 6.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                rowValues.forEach { (value, label) ->
                    HuaweiAncChoice(
                        label = label,
                        selected = selectedValue == value,
                        onClick = { onValueChange(value) },
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HuaweiAncChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val primary = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .background(
                color = if (selected) {
                    primary
                } else {
                    MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f)
                },
                shape = RoundedCornerShape(if (compact) 9.dp else 11.dp),
            )
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(
                horizontal = if (compact) 6.dp else 10.dp,
                vertical = if (compact) 8.dp else 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Normal,
            color = if (selected) Color.White else MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun HuaweiAncSimpleHeader(
    enabled: Boolean,
    onToggle: () -> Unit,
    compact: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val primary = MiuixTheme.colorScheme.primary
    val trackColor = if (enabled) primary else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.16f)
    val thumbOffset = if (enabled) 22.dp else 2.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 2.dp else 4.dp)
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.noise_cancellation_title),
            fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 26.dp)
                .background(trackColor, RoundedCornerShape(13.dp))
                .padding(2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .size(22.dp)
                    .background(Color.White, RoundedCornerShape(11.dp))
            )
        }
    }
}
@Composable
private fun HuaweiAncLevelDial(
    level: Int,
    onLevelChange: (Int) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MiuixTheme.colorScheme.primary
    val tickColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.28f)
    val diskColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.035f)
    val ringColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    val dialSize = if (compact) 116.dp else 188.dp
    var displayedLevel by remember { mutableIntStateOf(level.coerceIn(0, HUAWEI_ANC_LEVEL_LAST)) }
    var sentLevel by remember { mutableIntStateOf(level.coerceIn(0, HUAWEI_ANC_LEVEL_LAST)) }

    LaunchedEffect(level) {
        val safeLevel = level.coerceIn(0, HUAWEI_ANC_LEVEL_LAST)
        displayedLevel = safeLevel
        sentLevel = safeLevel
    }

    fun updateLevel(nextLevel: Int) {
        val safeLevel = nextLevel.coerceIn(0, HUAWEI_ANC_LEVEL_LAST)
        displayedLevel = safeLevel
        if (safeLevel != sentLevel) {
            sentLevel = safeLevel
            onLevelChange(safeLevel)
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        ComposeCanvas(
            modifier = Modifier
                .size(dialSize)
                .pointerInput(onLevelChange) {
                    detectTapGestures { position ->
                        updateLevel(position.toHuaweiAncLevel(size.width.toFloat(), size.height.toFloat()))
                    }
                }
                .pointerInput(onLevelChange) {
                    detectDragGestures(
                        onDragStart = { position ->
                            updateLevel(position.toHuaweiAncLevel(size.width.toFloat(), size.height.toFloat()))
                        },
                        onDrag = { change, _ ->
                            updateLevel(change.position.toHuaweiAncLevel(size.width.toFloat(), size.height.toFloat()))
                        }
                    )
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.34f
            val outerTickRadius = radius + 19.dp.toPx()
            val innerTickRadius = radius + 8.dp.toPx()
            val selectedTick = displayedLevel.toDialTick()

            drawCircle(
                color = diskColor,
                radius = radius * 1.08f,
                center = center
            )
            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = radius * 0.72f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            repeat(HUAWEI_ANC_DIAL_TICKS) { tick ->
                val major = tick % HUAWEI_ANC_TICKS_PER_LEVEL == 0
                val highlighted = circularDistance(tick, selectedTick, HUAWEI_ANC_DIAL_TICKS) <= 2
                val angle = Math.toRadians(tick * HUAWEI_ANC_DIAL_TICK_DEGREES.toDouble())
                val start = center.pointOnCircle(if (major) innerTickRadius - 3.dp.toPx() else innerTickRadius, angle)
                val end = center.pointOnCircle(outerTickRadius, angle)
                drawLine(
                    color = if (highlighted) primary else tickColor,
                    start = start,
                    end = end,
                    strokeWidth = if (highlighted) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            val knobAngle = Math.toRadians(displayedLevel.toDialDegrees().toDouble())
            val knobCenter = center.pointOnCircle(radius * 0.86f, knobAngle)
            val knobRadius = if (compact) 9.dp.toPx() else 15.dp.toPx()
            drawCircle(
                color = primary.copy(alpha = 0.16f),
                radius = knobRadius * 1.35f,
                center = knobCenter
            )
            drawCircle(
                color = primary,
                radius = knobRadius,
                center = knobCenter
            )
        }
    }
}
private const val HUAWEI_ANC_LEVEL_LAST = 8
private const val HUAWEI_ANC_DIAL_TICKS = 72
private const val HUAWEI_ANC_TICKS_PER_LEVEL = 8
private const val HUAWEI_ANC_DIAL_TICK_DEGREES = 5f
private const val HUAWEI_ANC_DIAL_START_DEGREES = 70f

private fun Int.toDialDegrees(): Float = HUAWEI_ANC_DIAL_START_DEGREES + (this * 360f / (HUAWEI_ANC_LEVEL_LAST + 1))

private fun Int.toDialTick(): Int = ((toDialDegrees() / HUAWEI_ANC_DIAL_TICK_DEGREES).roundToInt()) % HUAWEI_ANC_DIAL_TICKS

private fun Offset.toHuaweiAncLevel(width: Float, height: Float): Int {
    val dx = x - width / 2f
    val dy = y - height / 2f
    val degrees = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
    val normalized = (degrees - HUAWEI_ANC_DIAL_START_DEGREES + 360f) % 360f
    return ((normalized / (360f / (HUAWEI_ANC_LEVEL_LAST + 1))).roundToInt()) % (HUAWEI_ANC_LEVEL_LAST + 1)
}
private fun Offset.pointOnCircle(radius: Float, radians: Double): Offset {
    return Offset(
        x = x + cos(radians).toFloat() * radius,
        y = y + sin(radians).toFloat() * radius
    )
}

private fun circularDistance(a: Int, b: Int, modulo: Int): Int {
    val distance = abs(a - b)
    return min(distance, modulo - distance)
}
