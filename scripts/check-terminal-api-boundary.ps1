$violations = Get-ChildItem src/main/kotlin -Recurse -Filter *.kt |
    Where-Object { $_.FullName -notmatch 'terminal[\\/]idea262' } |
    Select-String 'com\.intellij\.terminal\.frontend|org\.jetbrains\.plugins\.terminal\.view'

if ($violations) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Terminal API boundary check passed."
