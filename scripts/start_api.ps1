$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$candidates = @()

if ($env:VIRTUAL_ENV) {
    $candidates += Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"
}
$candidates += Join-Path $projectRoot ".venv\Scripts\python.exe"
$candidates += Join-Path $HOME "Proyecto IA\.venv\Scripts\python.exe"

$python = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $python) {
    throw "No se encontro el entorno virtual de Python 3.12."
}

Set-Location $projectRoot
Write-Host "API de Asistencia IA iniciada en el puerto 8000."
$wifiAddress = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi" -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "169.254.*" } |
    Select-Object -First 1 -ExpandProperty IPAddress
if ($wifiAddress) {
    Write-Host "Direccion para la aplicacion: http://${wifiAddress}:8000"
}
Write-Host "Mantenga esta ventana abierta mientras utiliza la aplicacion."
Write-Host "Para detenerla, presione Ctrl+C."
& $python -m asistencia_ia.api --host 0.0.0.0 --port 8000
