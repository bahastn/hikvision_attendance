# Start the InsightFace sidecar (Windows / PowerShell).
#
# First time only:
#   python -m venv .venv
#   .\.venv\Scripts\Activate.ps1
#   pip install -r requirements.txt
#
# Then just run:  .\run.ps1

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

if (Test-Path ".\.venv\Scripts\Activate.ps1") {
    . .\.venv\Scripts\Activate.ps1
}

$env:INSIGHTFACE_MODEL = if ($env:INSIGHTFACE_MODEL) { $env:INSIGHTFACE_MODEL } else { "buffalo_l" }

uvicorn app:app --host 127.0.0.1 --port 8099
