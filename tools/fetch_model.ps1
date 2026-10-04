# Fetches the language model G-one bundles, and proves it is the right file.
#
#   powershell -ExecutionPolicy Bypass -File tools\fetch_model.ps1
#
# The model is not in the repository (1.1 GB). This downloads the official file at a pinned
# revision and checks its size and SHA-256, so every build from a clone ships the same model.
# If the file is already there and matches, nothing is downloaded.

$ErrorActionPreference = "Stop"

$Repo     = "Qwen/Qwen2.5-1.5B-Instruct-GGUF"
$Revision = "91cad51170dc346986eccefdc2dd33a9da36ead9"
$File     = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
$Bytes    = 1117320736
$Sha256   = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"

$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "app\src\main\assets\models\qwen.gguf"
$Url  = "https://huggingface.co/$Repo/resolve/$Revision/$File"

function Test-Model {
    if (-not (Test-Path $Dest)) { return $false }
    if ((Get-Item $Dest).Length -ne $Bytes) { return $false }
    return (Get-FileHash $Dest -Algorithm SHA256).Hash.ToLower() -eq $Sha256
}

if (Test-Model) {
    Write-Host "Model already present and verified: $Dest"
    exit 0
}

New-Item -ItemType Directory -Force (Split-Path -Parent $Dest) | Out-Null
$Partial = "$Dest.part"
Write-Host "Downloading $File ($([math]::Round($Bytes / 1MB)) MB) from $Repo at $Revision"
# curl.exe ships with Windows 10 and later; -C - resumes a download that was cut off.
& curl.exe -L --fail -C - -o $Partial $Url
if ($LASTEXITCODE -ne 0) { throw "Download failed (curl exit code $LASTEXITCODE). Run again to resume." }
Move-Item -Force $Partial $Dest

if (-not (Test-Model)) {
    throw "The downloaded file does not match the pinned size and SHA-256. It was left at $Dest; do not build with it."
}
Write-Host "Verified: $Dest"
