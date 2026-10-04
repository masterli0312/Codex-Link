$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../app/src/main/assets/task-notifications/Install.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Installer syntax error' }
foreach ($name in @('Read-Notify', 'Get-WrappedNotify', 'Test-OwnNotify', 'Get-PreservedNotify')) {
    $function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    Invoke-Expression $function.Extent.Text
}
$literal = Read-Notify "notify = ['C:\Users\fixture\node.exe', 'C:\Users\fixture\notify.cjs',]"
if ($literal.Count -ne 2 -or $literal[0] -ne 'C:\Users\fixture\node.exe') { throw 'Literal path parsing failed' }
$basic = Read-Notify 'notify = ["C:\\Users\\fixture\\node.exe", "escaped\tvalue"]'
if ($basic[0] -ne $literal[0] -or $basic[1] -ne "escaped`tvalue") { throw 'Basic string parsing failed' }
if ((Read-Notify 'notify = []').Count -ne 0) { throw 'Empty parsing failed' }
if ((Read-Notify 'notify = ["\U0001F600"]')[0] -ne [char]::ConvertFromUtf32(0x1F600)) { throw 'Unicode parsing failed' }
if ((Read-Notify 'notify = ["C:\\codex\\config.toml"]')[0] -ne 'C:\codex\config.toml') { throw 'Escaped paths rejected' }
if ((Read-Notify 'notify = ["\\U0001F600"]')[0] -ne '\U0001F600') { throw 'Literal escaped Unicode changed' }
foreach ($bad in @('notify = [arbitrary.exe]', 'notify = ["a" "b"]', 'notify = [,]', 'notify = ["a", false]', 'notify = ["\z"]', 'notify = ["\uD800"]')) {
    $rejected = $false; try { Read-Notify $bad | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed array accepted' }
}
$own = 'C:\fixture\notify.cjs'
$saved = @('codex-computer-use.exe', 'turn-ended', '--previous-notify', '["node.exe","C:\\fixture\\notify.cjs"]')
$preserved = Get-PreservedNotify @('node.exe', $own) $own $saved
if ($preserved.Count -ne 2 -or $preserved[0] -ne 'codex-computer-use.exe') { throw 'Recursive saved hook retained' }
Write-Output 'Installer parser and callback preservation passed'
