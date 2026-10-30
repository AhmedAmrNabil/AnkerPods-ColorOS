# ColorOS 钩子签名核对脚本（可复现）
#
# 用途：拿模块源码里的反射目标，逐条对实机固件 dump 核对"类还在不在、成员还在不在"。
# 固件 dump 不随仓库分发（`.local/recon/dex/*_classes.txt`，见 .gitignore），
# 生成方式见 docs/coloros17-hook-signature-matrix-20261030.md §3。
#
# 用法：
#   .\tools\audit-signatures.ps1 -RepoRoot . -DexDumpDir .local\recon\dex
#
# 输出：每个宿主类的成员命中情况 + 类是否存在。未命中的字面量需要人工过滤
#（action 字符串、ComponentName、本模块自己的类都会落在未命中里）。

param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$DexDumpDir = (Join-Path $RepoRoot '.local\recon\dex')
)

$ErrorActionPreference = 'Stop'

# 宿主类 -> (相对源码文件, dump 文件名)
$pairs = @(
    @{ Cls = 'com.oplus.mydevices.bluetooth.BlueToothCardView';                      Src = 'hook\ColorOsDeviceSpaceHook.kt';        Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.oplus.mydevices.quickapp.data.QuickCardDeviceDataConverter';       Src = 'hook\ColorOsDeviceSpaceHook.kt';        Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData';       Src = 'hook\ColorOsDeviceSpaceHook.kt';        Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.oplus.mydevices.domain.entities.device.BatteryInfo';              Src = 'hook\ColorOsDeviceSpaceHook.kt';        Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.oplus.mydevices.bluetooth.PluginPanelHostActivity';               Src = 'hook\ColorOsDeviceSpaceHook.kt';        Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment'; Src = 'hook\ColorOsMyDevicesHook.kt';         Dump = 'mydevices_classes.txt' },
    @{ Cls = 'com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity';    Src = 'hook\ColorOsAccessoryFrameworkHook.kt'; Dump = 'accessory_classes.txt' },
    @{ Cls = 'com.oplus.pantaconnect.data.BaseViewData';                            Src = 'hook\ColorOsAccessoryFrameworkHook.kt'; Dump = 'accessory_classes.txt' },
    @{ Cls = 'com.oplus.wirelesssettings.api.BluetoothNative';                      Src = 'hook\ColorOsWirelessSettingsHook.kt';   Dump = 'wirelesssettings_classes.txt' },
    @{ Cls = 'com.android.bluetooth.a2dp.A2dpService';                              Src = 'hook\HeadsetStateDispatcher.kt';        Dump = 'bluetooth_classes.txt' }
)

if (-not (Test-Path $DexDumpDir)) {
    throw "固件 dump 目录不存在：$DexDumpDir（先按文档生成）"
}

# 类名 -> (dump 文件, 起始行)
$index = @{}
foreach ($dump in Get-ChildItem $DexDumpDir -Filter '*_classes.txt') {
    $line = 0
    foreach ($text in [System.IO.File]::ReadLines($dump.FullName)) {
        $line++
        if ($text -like '===== CLASS L*;*') {
            $key = ($text -split ' ')[2].TrimStart('L').TrimEnd(';').Replace('/', '.')
            if (-not $index.ContainsKey($key)) { $index[$key] = @($dump.Name, $line) }
        }
    }
}

$failures = 0
foreach ($pair in $pairs) {
    $hit = $index[$pair.Cls]
    if (-not $hit) {
        Write-Output "MISSING  $($pair.Cls)  <- 固件里没有这个类"
        $failures++
        continue
    }

    $sourcePath = Join-Path $RepoRoot ('app\src\main\java\moe\chenxy\huaweipods\' + $pair.Src)
    $literals = [System.Collections.Generic.HashSet[string]]::new()
    if (Test-Path $sourcePath) {
        $text = [System.IO.File]::ReadAllText($sourcePath)
        foreach ($m in [regex]::Matches($text, '"([A-Za-z_][A-Za-z0-9_]{2,40})"')) {
            [void]$literals.Add($m.Groups[1].Value)
        }
    }

    # 收集该类块的成员名
    $members = [System.Collections.Generic.HashSet[string]]::new()
    $dumpPath = Join-Path $DexDumpDir $hit[0]
    $start = [int]$hit[1]
    $line = 0
    foreach ($text in [System.IO.File]::ReadLines($dumpPath)) {
        $line++
        if ($line -lt $start) { continue }
        if ($line -gt $start -and $text -like '===== CLASS L*;*') { break }
        $trimmed = $text.Trim()
        if ($trimmed -like 'METHOD(*) *(*') {
            [void]$members.Add((($trimmed -split ' ')[2] -split '\(')[0])
        } elseif ($trimmed -like 'IFIELD *' -or $trimmed -like 'SFIELD *') {
            [void]$members.Add(($trimmed -split ' ')[3])
        }
    }

    $matched = @($literals | Where-Object { $members.Contains($_) } | Sort-Object)
    Write-Output "OK       $($pair.Cls)  [$($hit[0]):$($hit[1])]"
    Write-Output "         members: $($matched -join ', ')"
}

Write-Output ''
Write-Output "类不存在数量: $failures"
