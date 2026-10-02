$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cli = Join-Path $repo 'build\install\fnord-dedup2\bin\fnord-dedup2.bat'
$groovy = Join-Path $repo 'build\install\fnord-dedup2\bin\fnord-dedup2-groovy.bat'
$mergeScript = Join-Path $repo 'build\install\fnord-dedup2\scripts\merge-database.groovy'
$work = Join-Path $env:TEMP ('fnord-windows-smoke-' + [Guid]::NewGuid().ToString('N'))

function Invoke-Fnord([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed with exit code $LASTEXITCODE" }
}

try {
    New-Item -ItemType Directory -Path $work | Out-Null
    $a = New-Item -ItemType Directory -Path (Join-Path $work 'Root A') | Select-Object -ExpandProperty FullName
    $b = New-Item -ItemType Directory -Path (Join-Path $work 'Root B') | Select-Object -ExpandProperty FullName
    New-Item -ItemType Directory -Path (Join-Path $a 'folder one') | Out-Null
    [IO.File]::WriteAllText((Join-Path $a 'folder one\one.txt'),'same')
    [IO.File]::WriteAllText((Join-Path $a 'two.txt'),'same')
    [IO.File]::WriteAllText((Join-Path $b 'copy.txt'),'same')
    $db = Join-Path $work 'core scans.duckdb'

    Invoke-Fnord $cli @('--db',$db,'--memory-limit','128MB','--database-threads','1','scan','--name','Windows A','--root',$a,'--discover-only','--quiet') | Out-Null
    Invoke-Fnord $cli @('--db',$db,'--memory-limit','128MB','--database-threads','1','scan','--name','Windows B','--root',$b,'--discover-only','--quiet') | Out-Null

    $scans = @((& $cli --db $db list | Out-String | ConvertFrom-Json))
    if ($LASTEXITCODE -ne 0) { throw 'list failed' }
    foreach ($scan in $scans) { if ($scan.root -match '\\') { throw "Persisted root contains Windows separator: $($scan.root)" } }

    $cross = Join-Path $work 'cross.jsonl'
    & $cli --db $db --memory-limit 128MB --database-threads 1 --quiet cross-duplicates --scan 'Windows A' --scan 'Windows B' --format jsonl 1> $cross 2> (Join-Path $work 'cross.err')
    if ($LASTEXITCODE -ne 0) { throw "cross-duplicates failed: $LASTEXITCODE" }
    $rows = @(Get-Content -LiteralPath $cross | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    if ($rows.Count -ne 3) { throw "Expected 3 cross-scan observations, got $($rows.Count)" }
    if ($rows | Where-Object { $_.path -match '\\' }) { throw 'Cross-scan output contains native separators' }

    $dups = Join-Path $work 'dups.jsonl'
    & $cli --db $db duplicates --name 'Windows A' --allow-partial --format jsonl 1> $dups
    if ($LASTEXITCODE -ne 0) { throw 'duplicates failed' }
    if (@(Get-Content $dups | Where-Object { $_.Trim() }).Count -ne 2) { throw 'Expected two intra-scan duplicate observations' }

    $srcRoot = New-Item -ItemType Directory -Path (Join-Path $work 'merge source root') | Select-Object -ExpandProperty FullName
    $dstRoot = New-Item -ItemType Directory -Path (Join-Path $work 'merge destination root') | Select-Object -ExpandProperty FullName
    [IO.File]::WriteAllText((Join-Path $srcRoot 'source.txt'),'source')
    [IO.File]::WriteAllText((Join-Path $dstRoot 'destination.txt'),'destination')
    $srcDb = Join-Path $work 'source db.duckdb'
    $dstDb = Join-Path $work 'destination db.duckdb'
    Invoke-Fnord $cli @('--db',$srcDb,'scan','--name','Imported','--root',$srcRoot,'--discover-only','--quiet') | Out-Null
    Invoke-Fnord $cli @('--db',$dstDb,'scan','--name','Existing','--root',$dstRoot,'--discover-only','--quiet') | Out-Null
    $mergeOut = & $groovy $mergeScript --source $srcDb --destination $dstDb --memory-limit 128MB --database-threads 1 --quiet | Out-String | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $mergeOut.status -ne 'IMPORTED') { throw 'database merge failed' }
    $merged = @((& $cli --db $dstDb list | Out-String | ConvertFrom-Json))
    if ($LASTEXITCODE -ne 0 -or $merged.Count -ne 2) { throw 'merged database did not reopen with two scans' }

    Write-Host 'Windows core smoke PASS'
}
finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
