param([switch]$Disable)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SettingsFile.ps1')
$runtime = $PSScriptRoot
$file = Join-Path $runtime 'connection.json'
$originalSettings = [IO.File]::ReadAllText($file)
$settings = $originalSettings | ConvertFrom-Json
$url = 'ws://127.0.0.1:18475/'
$taskName = 'Codex Usage shared app server'
# Changing a running desktop transport would interrupt tasks and retain old writer locks.
$desktop = Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath -match 'OpenAI\.Codex_.*\\app\\ChatGPT\.exe$'
}
if ($desktop) { throw 'Please finish your tasks and exit Codex Desktop first, then run this script again. No settings were changed.' }
$journal = Join-Path $runtime 'remote-journal.json'
if (Test-Path -LiteralPath $journal) {
    $entries = (Get-Content -LiteralPath $journal -Raw | ConvertFrom-Json).PSObject.Properties.Value
    if ($entries | Where-Object { $_.status -in @('accepted','running','approval','input_required','queued') }) {
        throw 'A phone task is still active. Finish or stop it before switching the native server.'
    }
}
function Get-SharedProperty($value, [string]$name) {
    if ($null -eq $value) { return $null }
    $property = $value.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Test-SharedSupervisor($process, $root) {
    $commandLine = Get-SharedProperty $process 'CommandLine'
    if ((Get-SharedProperty $process 'Name') -ne 'node.exe' -or -not $commandLine) { return $false }
    foreach ($name in @('shared-server.cjs', 'shared-start-if-needed.cjs')) {
        $scriptPath = [regex]::Escape((Join-Path $root $name))
        if ($commandLine -match ('(?i)(?:^|["\s])' + $scriptPath + '(?:["\s]|$)')) { return $true }
    }
    return $false
}
function Test-SharedNative($process, $health, $executable, $url, $relayMode) {
    $commandLine = Get-SharedProperty $process 'CommandLine'
    $processExe = Get-SharedProperty $process 'ExecutablePath'
    if (-not $commandLine -or -not $processExe) { return $false }
    if ($relayMode) {
        $port = 0
        $nativeExe = Get-SharedProperty $health 'nativeExecutable'
        if (-not [int]::TryParse([string](Get-SharedProperty $health 'nativePort'), [ref]$port) -or $port -lt 1 -or $port -gt 65535 -or
            -not $nativeExe -or $processExe -ne $nativeExe) { return $false }
        return $commandLine -match ([regex]::Escape(('ws://127.0.0.1:' + $port)) + '(?=[/"\s]|$)')
    }
    return $executable -and $processExe -eq $executable -and $commandLine.Contains($url.TrimEnd('/'))
}
function Test-SharedOwnership($listeners, $health, $executable, $url, $root) {
    if (-not @($listeners).Count -or $null -eq $listeners) { return $false }
    $mode = Get-SharedProperty $health 'mode'
    $relayMode = $mode -eq 'relay'
    if (-not $relayMode -and $mode -and $mode -notin @('reused', 'legacy')) { return $false }
    $listenerPid = 0; $nativePid = 0
    $listenerValue = if ($relayMode) { Get-SharedProperty $health 'relayPid' } else { Get-SharedProperty $health 'nativePid' }
    if (-not [int]::TryParse([string]$listenerValue, [ref]$listenerPid) -or $listenerPid -le 0 -or
        -not [int]::TryParse([string](Get-SharedProperty $health 'nativePid'), [ref]$nativePid) -or $nativePid -le 0 -or
        ($listeners | Where-Object { (Get-SharedProperty $_ 'OwningProcess') -ne $listenerPid -or (Get-SharedProperty $_ 'LocalAddress') -ne '127.0.0.1' })) { return $false }
    if ($relayMode) {
        if ($listenerPid -ne (Get-SharedProperty $health 'supervisorPid')) { return $false }
        $supervisor = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listenerPid) -ErrorAction SilentlyContinue
        if (-not (Test-SharedSupervisor $supervisor $root)) { return $false }
    }
    $native = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $nativePid) -ErrorAction SilentlyContinue
    return Test-SharedNative $native $health $executable $url $relayMode
}
function Stop-SharedOwned($health, $settings, $url, $runtime) {
    $mode = Get-SharedProperty $health 'mode'
    foreach ($value in @((Get-SharedProperty $health 'supervisorPid'), (Get-SharedProperty $health 'nativePid'))) {
        $processId = 0
        if (-not [int]::TryParse([string]$value, [ref]$processId) -or $processId -le 0) { continue }
        $owned = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $processId) -ErrorAction SilentlyContinue
        $ownedPid = 0
        if (-not $owned -or -not [int]::TryParse([string](Get-SharedProperty $owned 'ProcessId'), [ref]$ownedPid) -or $ownedPid -ne $processId) { continue }
        $native = (Test-SharedNative $owned $health (Get-SharedProperty $settings 'codexExecutable') $url ($mode -eq 'relay')) -or
            (Test-SharedNative $owned $health (Get-SharedProperty $health 'nativeExecutable') $url $false)
        $supervisor = Test-SharedSupervisor $owned $runtime
        if ($native -or $supervisor) { Stop-Process -Id $ownedPid -ErrorAction SilentlyContinue }
    }
}
if ($Disable) {
    if ($settings.appServerUrl -and $settings.appServerUrl -ne $url) { throw 'This script does not own the configured shared server. No settings were changed.' }
    $settings.PSObject.Properties.Remove('appServerUrl')
    Write-SettingsFileAtomically $file ($settings | ConvertTo-Json -Depth 8) -ExpectedText $originalSettings
    if ([Environment]::GetEnvironmentVariable('CODEX_APP_SERVER_WS_URL','User') -eq $url) {
        [Environment]::SetEnvironmentVariable('CODEX_APP_SERVER_WS_URL',$null,'User')
    }
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    $healthFile = Join-Path $runtime 'shared-server-health.json'
    if (Test-Path -LiteralPath $healthFile) {
        $health = Get-Content -LiteralPath $healthFile -Raw -Encoding UTF8 | ConvertFrom-Json
        Stop-SharedOwned $health $settings $url $runtime
    }
    Write-Host 'Shared transport disabled. Launch Codex normally after signing out and back in, or after clearing CODEX_APP_SERVER_WS_URL in your terminal.'
    return
}
if (-not $settings.remoteEnabled) { throw 'Pair this computer with Codex Usage and enable remote conversations first.' }
if ($env:CODEX_APP_SERVER_FORCE_CLI -eq '1') { throw 'CODEX_APP_SERVER_FORCE_CLI overrides shared connections. Review this existing setting before enabling shared conversations.' }
$existingUserUrl = [Environment]::GetEnvironmentVariable('CODEX_APP_SERVER_WS_URL', 'User')
if (($existingUserUrl -and $existingUserUrl -ne $url) -or ($env:CODEX_APP_SERVER_WS_URL -and $env:CODEX_APP_SERVER_WS_URL -ne $url) -or
    ($settings.appServerUrl -and $settings.appServerUrl -ne $url)) {
    throw 'Another native server is already configured. No desktop transport or paired server was overwritten.'
}
$node = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
if (-not $node) { throw 'Node.js 22 or newer is required for shared conversations.' }
$version = & $node --version
if ($version -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) { throw 'Node.js 22 or newer is required for shared conversations.' }
$nativeEncoded = & $node (Join-Path $runtime 'native-runtime.cjs') --resolve-base64 ([string]$settings.codexExecutable)
if ($LASTEXITCODE -ne 0 -or -not $nativeEncoded) {
    throw 'No native Codex runtime is available. Update or install Codex Desktop, then retry. No settings were changed.'
}
$nativeExe = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($nativeEncoded))
if (-not (Test-Path -LiteralPath $nativeExe)) { throw 'The discovered native Codex runtime is missing. No settings were changed.' }
$package = Get-AppxPackage 'OpenAI.Codex' | Select-Object -First 1
if (-not $package) { throw 'The installed Codex Desktop package was not found.' }
$desktopExe = Join-Path $package.InstallLocation 'app/ChatGPT.exe'
if (-not (Test-Path -LiteralPath $desktopExe)) { throw 'The installed desktop executable was not found. No settings were changed.' }
$launcher = Get-ChildItem -LiteralPath $runtime -Filter 'NotificationLauncher-*.exe' | Select-Object -First 1
if (-not $launcher) { throw 'Run the current Codex Usage computer installer first.' }
$listeners = Get-NetTCPConnection -LocalPort 18475 -State Listen -ErrorAction SilentlyContinue
if ($listeners) {
    $healthPath = Join-Path $runtime 'shared-server-health.json'
    if (-not (Test-Path -LiteralPath $healthPath)) { throw 'The shared-server port is already used by another program.' }
    $health = Get-Content -LiteralPath $healthPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not (Test-SharedOwnership @($listeners) $health $nativeExe $url $runtime)) {
        throw 'The existing listener does not match the paired native server.'
    }
}
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $launcher.FullName -Argument ('"' + $node + '" "' + (Join-Path $runtime 'shared-server.cjs') + '"')
$triggers = @((New-ScheduledTaskTrigger -AtLogOn -User $user), (New-ScheduledTaskTrigger -Once -At ([DateTime]::Now.AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1)))
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$taskSettings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $triggers -Principal $principal -Settings $taskSettings -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
$ready = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    try { $health = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18475/readyz' -TimeoutSec 2; $ready = $health.StatusCode -eq 200 } catch { }
    if ($ready) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'The shared native server did not become ready. Desktop and phone settings have not been switched.' }
$health = Get-Content -LiteralPath (Join-Path $runtime 'shared-server-health.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$listeners = @(Get-NetTCPConnection -LocalPort 18475 -State Listen -ErrorAction Stop)
if (-not (Test-SharedOwnership $listeners $health $nativeExe $url $runtime)) {
    throw 'The listening process failed ownership verification. Desktop and phone settings have not been switched.'
}
$settings | Add-Member -NotePropertyName appServerUrl -NotePropertyValue $url -Force
$settings | Add-Member -NotePropertyName codexExecutable -NotePropertyValue $nativeExe -Force
Write-SettingsFileAtomically $file ($settings | ConvertTo-Json -Depth 8) -ExpectedText $originalSettings
[Environment]::SetEnvironmentVariable('CODEX_APP_SERVER_WS_URL',$url,'User')
$env:CODEX_APP_SERVER_WS_URL = $url
$env:CODEX_HOME = $settings.codexHome
# The private desktop transport switch exists in the installed desktop build.
# Recheck it when updating Codex; the app-server WebSocket API is experimental.
Start-Process -FilePath $desktopExe -WindowStyle Normal
Write-Host 'Codex Desktop and Codex Usage now use one local app-server. The server starts at Windows logon without a terminal. Existing conversation IDs and authentication are preserved.'
