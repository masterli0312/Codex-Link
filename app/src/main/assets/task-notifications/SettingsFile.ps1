# MoveFileEx replaces a name in one operation. File.Replace uses multiple name
# changes on Windows, which can briefly expose ENOENT/EBUSY to Node readers.
if (-not ('CodexUsage.SettingsFileNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace CodexUsage {
    public static class SettingsFileNative {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool MoveFileEx(string existing, string destination, uint flags);
    }
}
'@
}
# Atomic replacement lets the background relay read either complete revision.
# Failed or stale writes never leave truncated credentials or temporary copies.
function Write-SettingsFileAtomically([string]$Path, [string]$Text, [string]$ExpectedText) {
    $target = [IO.Path]::GetFullPath($Path)
    $temporary = Join-Path ([IO.Path]::GetDirectoryName($target)) ([IO.Path]::GetFileName($target) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, $Text, [Text.UTF8Encoding]::new($false))
        $deadline = [DateTime]::UtcNow.AddSeconds(2)
        while ($true) {
            if ($PSBoundParameters.ContainsKey('ExpectedText') -and
                (-not [IO.File]::Exists($target) -or [IO.File]::ReadAllText($target) -ne $ExpectedText)) {
                throw 'Settings changed during setup. No configuration was overwritten; run setup again.'
            }
            if ([CodexUsage.SettingsFileNative]::MoveFileEx($temporary, $target, 9)) { break }
            $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            # A reader/antivirus can briefly deny name replacement. Leave the old
            # complete file in place and retry only the writer, with a fixed bound.
            if ($code -notin @(5, 32, 33) -or [DateTime]::UtcNow -ge $deadline) {
                throw [ComponentModel.Win32Exception]::new($code)
            }
            Start-Sleep -Milliseconds 10
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
