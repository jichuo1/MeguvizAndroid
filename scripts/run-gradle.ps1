param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTmp = Join-Path $projectRoot 'build\tmp'
New-Item -ItemType Directory -Path $taskTmp -Force | Out-Null
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw 'Set JAVA_HOME to JDK 25 or 27 before invoking the build.'
}
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$taskTmp -Djava.io.tmpdir=$taskTmp"
$env:TEMP = $taskTmp
$env:TMP = $taskTmp
Push-Location $projectRoot
try { & .\gradlew.bat @GradleArgs --console=plain --no-daemon; exit $LASTEXITCODE }
finally { Pop-Location }
