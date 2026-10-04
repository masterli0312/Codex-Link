param([string]$Endpoint, [string]$CodexDirectory, [switch]$Uninstall, [switch]$NoTest)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SettingsFile.ps1')
if (-not $CodexDirectory) {
    $CodexDirectory = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
}
$runtime = Join-Path $CodexDirectory 'codex-usage-notifications'
$configFile = Join-Path $CodexDirectory 'config.toml'
$connectionFile = Join-Path $runtime 'connection.json'
$scriptPath = Join-Path $runtime 'notify.cjs'
$taskName = 'Codex Usage notification retry'
$remoteTaskName = 'Codex Usage remote conversations'
if ($Uninstall -and (Test-Path -LiteralPath $connectionFile)) {
    $installed = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($installed.appServerUrl) { throw 'Exit Codex Desktop and run Enable-SharedConversations.ps1 -Disable before uninstalling the shared native server.' }
}

function Test-RemoteBridgeProcess($BridgeProcess, [string]$RuntimeDirectory) {
    if (-not $BridgeProcess -or $BridgeProcess.Name -ne 'node.exe') { return $false }
    $script = [IO.Path]::GetFullPath((Join-Path $RuntimeDirectory 'remote.cjs'))
    $command = ([string]$BridgeProcess.CommandLine).Replace('/', '\')
    return $command.IndexOf(('"' + $script + '"'), [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $command -match ('(?i)(?:^|\s)' + [regex]::Escape($script) + '(?=\s|$)')
}

function Stop-OwnRemoteBridge([string]$RuntimeDirectory) {
    # Task Scheduler may stop the windowless parent while leaving Node running.
    # A lock PID alone is insufficient: confirm both the image and exact script.
    $lease = Join-Path $RuntimeDirectory 'remote.lock'
    if (-not (Test-Path -LiteralPath $lease)) { return }
    $bridgeProcessId = 0
    if (-not [int]::TryParse([IO.File]::ReadAllText($lease).Trim(), [ref]$bridgeProcessId) -or $bridgeProcessId -le 0) { return }
    $bridgeProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $bridgeProcessId" -ErrorAction SilentlyContinue
    if (Test-RemoteBridgeProcess $bridgeProcess $RuntimeDirectory) {
        Stop-Process -Id $bridgeProcessId -ErrorAction SilentlyContinue
    }
}

function Read-Notify([string]$Text) {
    $match = [regex]::Match($Text, '(?m)^notify\s*=\s*(\[[^\r\n]*\])\s*$')
    if ($match.Success) {
        # TOML literal Windows paths are not JSON strings; parse only a closed string array.
        $body = $match.Groups[1].Value.Substring(1, $match.Groups[1].Value.Length - 2)
        $tokens = [regex]::Matches($body, '(?:''[^'']*''|"(?:[^"\\\r\n]|\\.)*")')
        $values = @(); $offset = 0
        foreach ($token in $tokens) {
            $gap = $body.Substring($offset, $token.Index - $offset)
            if (($offset -eq 0 -and $gap -notmatch '^\s*$') -or ($offset -gt 0 -and $gap -notmatch '^\s*,\s*$')) {
                throw 'Existing notify syntax needs manual review; config has not been changed.'
            }
            $value = $token.Value
            if ($value.StartsWith("'")) { $values += $value.Substring(1, $value.Length - 2) }
            else {
                $content = $value.Substring(1, $value.Length - 2)
                $decoded = [Text.StringBuilder]::new()
                for ($position = 0; $position -lt $content.Length; $position++) {
                    $character = $content[$position]
                    if ($character -ne '\') { [void]$decoded.Append($character); continue }
                    $position++; if ($position -ge $content.Length) { throw 'Invalid TOML escape; config has not been changed.' }
                    $escape = [string]$content[$position]
                    $simple = @{ 'b' = [char]8; 't' = [char]9; 'n' = [char]10; 'f' = [char]12; 'r' = [char]13; '"' = '"'; '\' = '\' }
                    if ($escape -cin @('b', 't', 'n', 'f', 'r', '"', '\')) { [void]$decoded.Append($simple[$escape]); continue }
                    if ($escape -cne 'u' -and $escape -cne 'U') { throw 'Unsupported TOML escape; config has not been changed.' }
                    $length = if ($escape -ceq 'u') { 4 } else { 8 }
                    if ($position + $length -ge $content.Length) { throw 'Incomplete Unicode escape; config has not been changed.' }
                    $digits = $content.Substring($position + 1, $length)
                    if ($digits -notmatch '^[0-9A-Fa-f]+$') { throw 'Invalid Unicode escape; config has not been changed.' }
                    $code = [Convert]::ToInt32($digits, 16)
                    [void]$decoded.Append([char]::ConvertFromUtf32($code)); $position += $length
                }
                $values += $decoded.ToString()
            }
            $offset = $token.Index + $token.Length
        }
        if ($body.Substring($offset) -notmatch '^\s*,?\s*$' -or ($tokens.Count -eq 0 -and $body -notmatch '^\s*$')) {
            throw 'Existing notify syntax needs manual review; config has not been changed.'
        }
        return ,@($values)
    }
    if ($Text -match '(?m)^notify\s*=') { throw 'Existing notify syntax needs manual review; config has not been changed.' }
    return ,@()
}

function Set-Notify([string]$Text, [array]$Command) {
    $line = if ($Command.Count) { 'notify = ' + (ConvertTo-Json -InputObject $Command -Compress) } else { '' }
    if ($Text -match '(?m)^notify\s*=') { return [regex]::Replace($Text, '(?m)^notify\s*=\s*\[[^\r\n]+\]\s*$', [Text.RegularExpressions.MatchEvaluator]{ param($m) $line }) }
    if (-not $line) { return $Text }
    return $line + "`r`n" + $Text
}

function Get-WrappedNotify([array]$Command) {
    if ($Command.Count -lt 4 -or [IO.Path]::GetFileName([string]$Command[0]) -ne 'codex-computer-use.exe' -or $Command[1] -ne 'turn-ended') { return ,@() }
    $index = [Array]::IndexOf($Command, '--previous-notify')
    if ($index -lt 0 -or $index + 1 -ge $Command.Count) { return ,@() }
    try { $parsed = $Command[$index + 1] | ConvertFrom-Json; return ,([object[]]$parsed) } catch { return ,@() }
}

function Test-OwnNotify([array]$Command, [string]$NotifyScript, [int]$Depth = 0) {
    $expected = [IO.Path]::GetFullPath($NotifyScript)
    foreach ($argument in $Command) {
        try {
            if ([string]::Equals([IO.Path]::GetFullPath([string]$argument), $expected, [StringComparison]::OrdinalIgnoreCase)) { return $true }
        } catch { }
    }
    if ($Depth -lt 4) {
        $wrapped = Get-WrappedNotify $Command
        if ($wrapped.Count) { return (Test-OwnNotify $wrapped $NotifyScript ($Depth + 1)) }
    }
    return $false
}

function Get-PreservedNotify([array]$Command, [string]$NotifyScript, [array]$SavedOriginal) {
    $wrapped = Get-WrappedNotify $Command
    if ($wrapped.Count -and (Test-OwnNotify $wrapped $NotifyScript)) {
        # Keep the current computer-use handler, removing only its old relay chain.
        $index = [Array]::IndexOf($Command, '--previous-notify')
        return ,@($Command | Select-Object -Index @(0..($Command.Count - 1) | Where-Object { $_ -ne $index -and $_ -ne ($index + 1) }))
    }
    if (Test-OwnNotify $SavedOriginal $NotifyScript) {
        # An older connection file may already contain the same recursive wrapper.
        $savedWrapped = Get-WrappedNotify $SavedOriginal
        if ($savedWrapped.Count) {
            $index = [Array]::IndexOf($SavedOriginal, '--previous-notify')
            return ,@($SavedOriginal | Select-Object -Index @(0..($SavedOriginal.Count - 1) | Where-Object { $_ -ne $index -and $_ -ne ($index + 1) }))
        }
        return ,@()
    }
    return ,@($SavedOriginal)
}

$oldText = if (Test-Path -LiteralPath $configFile) { [IO.File]::ReadAllText($configFile) } else { '' }
$oldNotify = Read-Notify $oldText
if ($Uninstall) {
    if (Test-Path -LiteralPath $connectionFile) {
        $disabled = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $disabled | Add-Member -NotePropertyName remoteEnabled -NotePropertyValue $false -Force
        Write-SettingsFileAtomically $connectionFile ($disabled | ConvertTo-Json -Depth 5)
    }
    Stop-ScheduledTask -TaskName $remoteTaskName -ErrorAction SilentlyContinue
    Stop-OwnRemoteBridge $runtime
    Unregister-ScheduledTask -TaskName $remoteTaskName -Confirm:$false -ErrorAction SilentlyContinue
    if ((Test-Path -LiteralPath $connectionFile) -and (Test-OwnNotify $oldNotify $scriptPath)) {
        $saved = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
        Write-SettingsFileAtomically $configFile (Set-Notify $oldText @($saved.previousNotify))
    }
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host 'Notifications disabled. Original Codex callback restored. Restart Codex.'
    exit
}

if (-not $Endpoint) {
    $pairingFile = Join-Path $PSScriptRoot 'pairing.json'
    if (-not (Test-Path -LiteralPath $pairingFile)) { throw 'Extract the setup ZIP shared by Codex Usage first.' }
    $pairing = Get-Content -LiteralPath $pairingFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $Endpoint = $pairing.endpoint
}
$contentKey = if ($pairing -and $pairing.contentKey) { [string]$pairing.contentKey } else { '' }
$remoteEnabled = [bool]($pairing -and $pairing.remoteEnabled)
$pairedHostId = if ($pairing -and $pairing.remoteHostId) { [string]$pairing.remoteHostId } else { '' }
if ($pairedHostId -and $pairedHostId -notmatch '^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$') { throw 'Invalid computer pairing identity.' }
if ($remoteEnabled -and -not $contentKey) { throw 'Remote conversations require encrypted-content pairing.' }
$codexExe = ''
if ($contentKey -and ($contentKey -notmatch '^[A-Za-z0-9+/]{43}=$' -or [Convert]::FromBase64String($contentKey).Length -ne 32)) { throw 'Invalid encrypted-content pairing key.' }
$uri = [Uri]$Endpoint
if ($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -notmatch '^/[A-Za-z0-9_-]{1,128}$') { throw 'Invalid pairing address.' }
$node = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
if (-not $node) {
    $bundledRuntime = Join-Path $env:LOCALAPPDATA 'OpenAI/Codex/runtimes/cua_node'
    if (Test-Path -LiteralPath $bundledRuntime) {
        $node = Get-ChildItem -LiteralPath $bundledRuntime -Directory | Sort-Object LastWriteTime -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin/node.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
}
if (-not $node) { throw 'Install Node.js 18 or newer from nodejs.org, then run setup.cmd again.' }
$nodeVersion = & $node --version
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 18) {
    throw 'Node.js 18 or newer is required.'
}
if ($remoteEnabled) {
    $preferredNative = ''
    if (Test-Path -LiteralPath $connectionFile) {
        $existingRuntime = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $preferredNative = [string]$existingRuntime.codexExecutable
    }
    $nativeEncoded = & $node (Join-Path $PSScriptRoot 'native-runtime.cjs') --resolve-base64 $preferredNative
    if ($LASTEXITCODE -ne 0 -or -not $nativeEncoded) {
        throw 'Install or update Codex Desktop (or a native Codex CLI), then run setup again. Existing pairing settings have not been changed.'
    }
    $codexExe = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($nativeEncoded))
    if (-not (Test-Path -LiteralPath $codexExe)) { throw 'The discovered native Codex runtime is missing. No settings were changed.' }
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$launcherSource = Join-Path $PSScriptRoot 'NotificationLauncher.cs'
if (-not (Test-Path -LiteralPath $launcherSource)) { throw 'Extract the latest setup ZIP shared by Codex Usage first.' }
$sourceHash = (Get-FileHash -LiteralPath $launcherSource -Algorithm SHA256).Hash.Substring(0,16)
$launcherPath = Join-Path $runtime ('NotificationLauncher-' + $sourceHash + '.exe')
if (-not (Test-Path -LiteralPath $launcherPath)) {
    $compiler = @('Microsoft.NET/Framework64/v4.0.30319/csc.exe', 'Microsoft.NET/Framework/v4.0.30319/csc.exe') |
        ForEach-Object { Join-Path $env:WINDIR $_ } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $compiler) { throw 'The Windows .NET Framework compiler is unavailable. Existing Codex settings have not been changed.' }
    & $compiler /nologo /target:winexe ('/out:' + $launcherPath) $launcherSource
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the windowless launcher. Existing Codex settings have not been changed.' }
}
$previousNotify = $oldNotify
if (Test-OwnNotify $oldNotify $scriptPath) {
    if (-not (Test-Path -LiteralPath $connectionFile)) { throw 'Existing notification setup is incomplete; config has not been changed.' }
    $savedConnection = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $previousNotify = Get-PreservedNotify $oldNotify $scriptPath @($savedConnection.previousNotify)
}
if (Test-Path -LiteralPath $configFile) {
    Copy-Item -LiteralPath $configFile -Destination ($configFile + '.before-task-notifications-' + [DateTime]::Now.ToString('yyyyMMddHHmmssfff'))
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'notify.cjs') -Destination $scriptPath -Force
if (Test-Path -LiteralPath $connectionFile) {
    $stopping = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($stopping.remoteEnabled) {
        $stopping | Add-Member -NotePropertyName remoteEnabled -NotePropertyValue $false -Force
        Write-SettingsFileAtomically $connectionFile ($stopping | ConvertTo-Json -Depth 5)
        Start-Sleep -Seconds 3
    }
}
Stop-ScheduledTask -TaskName $remoteTaskName -ErrorAction SilentlyContinue
Stop-OwnRemoteBridge $runtime
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'task-content.cjs') -Destination (Join-Path $runtime 'task-content.cjs') -Force
foreach ($name in @('remote.cjs', 'remote-core.cjs', 'codex-client.cjs', 'native-runtime.cjs', 'conversation-library.cjs', 'conversation-images.cjs', 'live-watch.cjs', 'remote-activity.cjs', 'remote-goal.cjs', 'remote-options.cjs', 'remote-stream.cjs', 'remote-attachments.cjs', 'shared-server.cjs', 'Enable-SharedConversations.ps1', 'SettingsFile.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $runtime $name) -Force
}
$monitorEnabledAt = [DateTime]::UtcNow.ToString('o')
if (Test-Path -LiteralPath $connectionFile) {
    $savedConnection = Get-Content -LiteralPath $connectionFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($savedConnection.endpoint -eq $Endpoint -and $savedConnection.monitorEnabledAt) { $monitorEnabledAt = $savedConnection.monitorEnabledAt }
}
$settings = @{ endpoint = $Endpoint; previousNotify = @($previousNotify); codexHome = [IO.Path]::GetFullPath($CodexDirectory); monitorEnabledAt = $monitorEnabledAt }
$settings.remoteEnabled = $remoteEnabled
$settings.remoteHostId = if ($pairedHostId) { $pairedHostId } elseif ($savedConnection -and $savedConnection.remoteHostId) { $savedConnection.remoteHostId } else { [Guid]::NewGuid().ToString() }
if ($codexExe) { $settings.codexExecutable = $codexExe }
if ($contentKey) { $settings.contentKey = $contentKey }
if ($savedConnection -and $savedConnection.appServerUrl) { $settings.appServerUrl = $savedConnection.appServerUrl }
Write-SettingsFileAtomically $connectionFile ($settings | ConvertTo-Json -Depth 5)
$newText = Set-Notify $oldText @($launcherPath, $node, $scriptPath)
Write-SettingsFileAtomically $configFile $newText

# Flush the metadata-only outbox after transient network failures, without an open terminal.
try {
    $action = New-ScheduledTaskAction -Execute $launcherPath -Argument ('"' + $node + '" "' + $scriptPath + '" --flush')
    $trigger = New-ScheduledTaskTrigger -Once -At ([DateTime]::Now.AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $taskSettings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $taskSettings -Force | Out-Null
} catch { Write-Host 'Automatic retry registration failed. Pending messages will retry at the next Codex completion.' }
Stop-ScheduledTask -TaskName $remoteTaskName -ErrorAction SilentlyContinue
if ($remoteEnabled) {
    $remoteAction = New-ScheduledTaskAction -Execute $launcherPath -Argument ('"' + $node + '" "' + (Join-Path $runtime 'remote.cjs') + '"')
    $remoteUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    # An all-users logon trigger requires elevation; pairing runs as the current user.
    $remoteTriggers = @((New-ScheduledTaskTrigger -AtLogOn -User $remoteUser), (New-ScheduledTaskTrigger -Once -At ([DateTime]::Now.AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1)))
    $remoteSettings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
    $remotePrincipal = New-ScheduledTaskPrincipal -UserId $remoteUser -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $remoteTaskName -Action $remoteAction -Trigger $remoteTriggers -Principal $remotePrincipal -Settings $remoteSettings -Force -ErrorAction Stop | Out-Null
    Start-ScheduledTask -TaskName $remoteTaskName -ErrorAction Stop
    Write-Host 'Remote conversations enabled. Keep this computer on and online. Continue completed threads from new phone notifications.'
} else { Unregister-ScheduledTask -TaskName $remoteTaskName -Confirm:$false -ErrorAction SilentlyContinue }
Write-Host 'Installed. Your original Codex callback is preserved. Restart Codex to activate task notifications.'
if (-not $NoTest) {
    & $launcherPath $node $scriptPath --test
    if ($LASTEXITCODE -eq 0) { Write-Host 'Pairing test sent. Check Codex Usage on your phone.' }
    else { Write-Host 'Pairing test could not be sent. Check your proxy/network; queued messages are kept for retry.' }
}
