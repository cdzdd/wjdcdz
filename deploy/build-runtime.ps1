# Build a credential-free Linux runtime context from a committed source version.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskDirty = & git status --porcelain --untracked-files=normal
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Git source version.' }
    if ($taskDirty) { throw 'Commit source changes before building a release package.' }

    Push-Location (Join-Path $taskRoot 'server')
    try {
        & mvn -B '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }
    } finally { Pop-Location }

    Push-Location (Join-Path $taskRoot 'web')
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
    } finally { Pop-Location }

    & python (Join-Path $PSScriptRoot 'package-runtime.py')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime archive packaging failed.' }
} finally { Pop-Location }
