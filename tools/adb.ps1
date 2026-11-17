$ErrorActionPreference = "Stop"

function Find-AndroidSdk {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += $env:ANDROID_SDK_ROOT.Trim('"')
    }
    if ($env:ANDROID_HOME) {
        $candidates += $env:ANDROID_HOME.Trim('"')
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $adb = Join-Path $candidate "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $adb -PathType Leaf) {
            return $adb
        }
    }
    return $null
}

function Get-AdbCommand([string[]] $Arguments) {
    $index = 0
    while ($index -lt $Arguments.Count) {
        $argument = $Arguments[$index]
        if ($argument -in @("-s", "-H", "-P", "-L", "-t")) {
            $index += 2
            continue
        }
        if ($argument.StartsWith("-")) {
            $index++
            continue
        }
        return $argument
    }
    return ""
}

function Has-SerialSelector([string[]] $Arguments) {
    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        if ($Arguments[$index] -eq "-s") {
            return ($index + 1 -lt $Arguments.Count) -and
                (-not [string]::IsNullOrWhiteSpace($Arguments[$index + 1])) -and
                (-not $Arguments[$index + 1].StartsWith("-"))
        }
    }
    return $false
}

$adbExecutable = Find-AndroidSdk
if (-not $adbExecutable) {
    [Console]::Error.WriteLine(
        "Android SDK com platform-tools\adb.exe não encontrado. Configure ANDROID_SDK_ROOT/ANDROID_HOME ou instale o SDK manualmente; este script não instala ferramentas."
    )
    exit 1
}

$adbCommand = Get-AdbCommand $args
$globalCommands = @("devices", "version", "help", "--help", "start-server", "kill-server")
if ($adbCommand -and $adbCommand -notin $globalCommands -and -not (Has-SerialSelector $args)) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $deviceOutput = & $adbExecutable devices 2>&1
    $deviceExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($deviceExitCode -ne 0) {
        [Console]::Error.WriteLine("Não foi possível consultar os dispositivos ADB para validar a seleção.")
        exit $deviceExitCode
    }

    $connectedDevices = @(
        $deviceOutput | Where-Object {
            "$_" -match '^\S+\s+(device|offline|unauthorized|authorizing|connecting|bootloader|recovery)(?:\s|$)'
        }
    )
    if ($connectedDevices.Count -gt 1) {
        [Console]::Error.WriteLine(
            "Há vários aparelhos/emuladores ADB. Selecione explicitamente um com -s SERIAL antes do comando."
        )
        exit 2
    }
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $adbExecutable @args
$adbExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
exit $adbExitCode
