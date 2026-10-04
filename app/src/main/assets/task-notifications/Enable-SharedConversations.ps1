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
        foreach ($processId in @($health.nativePid, $health.supervisorPid)) {
            $owned = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$processId) -ErrorAction SilentlyContinue
            if (-not $owned) { continue }
            $native = ($owned.ExecutablePath -eq $settings.codexExecutable -or $owned.ExecutablePath -eq $health.nativeExecutable) -and $owned.CommandLine.Contains($url.TrimEnd('/'))
            $supervisor = $owned.Name -eq 'node.exe' -and $owned.CommandLine.Contains((Join-Path $runtime 'shared-server.cjs'))
            if ($native -or $supervisor) { Stop-Process -Id $owned.ProcessId -ErrorAction SilentlyContinue }
        }
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
    if ($listeners | Where-Object { $_.OwningProcess -ne $health.nativePid -or $_.LocalAddress -ne '127.0.0.1' }) {
        throw 'The shared-server port is already used by another program.'
    }
    $owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$health.nativePid)
    if ($owner.ExecutablePath -ne $nativeExe -or -not $owner.CommandLine.Contains($url.TrimEnd('/'))) {
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
$owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$health.nativePid)
if (-not $listeners.Count -or ($listeners | Where-Object { $_.OwningProcess -ne $health.nativePid -or $_.LocalAddress -ne '127.0.0.1' }) -or
    $owner.ExecutablePath -ne $nativeExe -or -not $owner.CommandLine.Contains($url.TrimEnd('/'))) {
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
