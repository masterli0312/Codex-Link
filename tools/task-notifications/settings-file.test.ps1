$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../app/src/main/assets/task-notifications/SettingsFile.ps1')
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$directory = [IO.Path]::GetFullPath((Join-Path $tempBase ('CodexUsageSettings-' + [Guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($directory)
$file = Join-Path $directory 'connection.json'
$reader = $null
try {
    $one = '{"revision":1,"fixture":"' + ('a' * 262144) + '"}'
    $two = '{"revision":2,"fixture":"' + ('b' * 262144) + '"}'
    Write-SettingsFileAtomically $file $one
    $start = [Diagnostics.ProcessStartInfo]::new((Get-Command node.exe).Source,
        ('"' + (Join-Path $PSScriptRoot 'settings-reader-fixture.cjs') + '" "' + $file + '"'))
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $reader = [Diagnostics.Process]::Start($start)
    if ($reader.StandardOutput.ReadLine() -ne 'READY') { throw 'Fixture reader did not start' }
    for ($index = 0; $index -lt 250; $index++) {
        Write-SettingsFileAtomically $file $(if ($index % 2) { $one } else { $two })
    }
    $reader.WaitForExit()
    $result = $reader.StandardOutput.ReadToEnd().Trim() | ConvertFrom-Json
    if ($reader.ExitCode -ne 0 -or $result.invalid -ne 0 -or $result.reads -lt 1) {
        throw ('A concurrent reader could not read complete settings: ' + ($result | ConvertTo-Json -Compress))
    }
    $current = [IO.File]::ReadAllText($file)
    $rejected = $false
    try { Write-SettingsFileAtomically $file '{"revision":3}' -ExpectedText '{"stale":true}' }
    catch { $rejected = $true }
    if (-not $rejected -or [IO.File]::ReadAllText($file) -ne $current) { throw 'A stale settings update overwrote current configuration' }
    # A persistent sharing denial must fail in a bounded time and leave the
    # original usable, rather than truncating it or looping forever.
    $held = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $rejected = $false
    try { Write-SettingsFileAtomically $file '{"revision":3}' }
    catch { $rejected = $true }
    finally { $held.Dispose() }
    if (-not $rejected -or $timer.Elapsed.TotalSeconds -gt 5 -or [IO.File]::ReadAllText($file) -ne $current) {
        throw 'A locked settings file was overwritten or replacement retries were not bounded'
    }
    if ((Get-ChildItem -LiteralPath $directory -Filter '*.tmp').Count -ne 0) { throw 'Temporary credential files were left behind' }
    $unicode = '{"fixture":"' + [char]0x4e2d + [char]0x6587 + '"}'
    Write-SettingsFileAtomically $file $unicode -ExpectedText $current
    if ([IO.File]::ReadAllText($file) -ne $unicode) { throw 'Unicode settings changed' }
    Write-Output ('Concurrent atomic settings updates passed; ' + $result.reads + ' complete reads, 0 invalid reads; stale/locked update and UTF-8 checks passed')
} finally {
    if ($reader -and -not $reader.HasExited) { $reader.Kill(); $reader.WaitForExit() }
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Path]::GetFileName($resolved).StartsWith('CodexUsageSettings-')) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
