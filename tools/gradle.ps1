$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot

function Test-Jdk21([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $false
    }

    $java = Join-Path $Path "bin\java.exe"
    $javac = Join-Path $Path "bin\javac.exe"
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
        -not (Test-Path -LiteralPath $javac -PathType Leaf)) {
        return $false
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $java
    $process.StartInfo.Arguments = "-version"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    if (-not $process.Start()) {
        return $false
    }

    $standardError = $process.StandardError.ReadToEnd()
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    $versionOutput = $standardError + $standardOutput
    return $versionOutput -match 'version "21(?:[.\-"]|$)'
}

function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME.Trim('"')
    }
    if ($env:ProgramFiles) {
        $candidates += (Join-Path $env:ProgramFiles "Java\jdk-21")
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Jdk21 $candidate) {
            return $candidate
        }
    }
    return $null
}

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
        $requiredFiles = @(
            "platform-tools\adb.exe",
            "platforms\android-36\android.jar",
            "build-tools\36.0.0\aapt2.exe"
        )
        $hasRequiredFiles = $true
        foreach ($relativePath in $requiredFiles) {
            if (-not (Test-Path -LiteralPath (Join-Path $candidate $relativePath) -PathType Leaf)) {
                $hasRequiredFiles = $false
                break
            }
        }
        if ($hasRequiredFiles) {
            return $candidate
        }
    }
    return $null
}

$jdkPath = Find-Jdk21
if (-not $jdkPath) {
    [Console]::Error.WriteLine(
        "JDK 21 não encontrado. Configure JAVA_HOME para um JDK 21 do Windows ou instale-o manualmente; este script não instala ferramentas."
    )
    exit 1
}

$sdkPath = Find-AndroidSdk
if (-not $sdkPath) {
    [Console]::Error.WriteLine(
        "Android SDK do Windows com API 36, Build Tools 36.0.0 e platform-tools não encontrado. Configure ANDROID_SDK_ROOT/ANDROID_HOME ou instale o SDK manualmente; este script não instala ferramentas."
    )
    exit 1
}

$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    [Console]::Error.WriteLine("Gradle Wrapper ausente: $gradleWrapper")
    exit 1
}

$env:JAVA_HOME = $jdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:ANDROID_HOME = $sdkPath
$env:PATH = "$(Join-Path $jdkPath 'bin');$env:PATH"

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
Push-Location $projectRoot
try {
    & $gradleWrapper @args
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $ErrorActionPreference = $previousErrorActionPreference
}
exit $gradleExitCode
