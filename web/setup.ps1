# Layan.OS - pemasang satu-arahan untuk Windows.
# Muat turun Android TV Debloit ke %USERPROFILE%\AndroidTvDebloit, kemudian jalankan setup penuh.
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/Dynoz769/AndroidTvDebloit/main/web/setup.ps1))) -Jenama sony -Ip 192.168.1.20

param(
    [string]$Jenama = '',
    [string]$Ip = ''
)

$ErrorActionPreference = 'Stop'
$Repo   = 'Dynoz769/AndroidTvDebloit'
$Branch = if ($env:LAYAN_BRANCH) { $env:LAYAN_BRANCH } else { 'main' }
$Dest   = Join-Path $env:USERPROFILE 'AndroidTvDebloit'
$Tmp    = Join-Path ([IO.Path]::GetTempPath()) ('atvd-' + [guid]::NewGuid())

[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

Write-Host 'Memuat turun Android TV Debloit...'
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
try {
    $zip = Join-Path $Tmp 'kit.zip'
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/archive/refs/heads/$Branch.zip" -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $Tmp -Force
    $src = (Get-ChildItem -LiteralPath $Tmp -Directory | Select-Object -First 1).FullName

    $lists = Join-Path $Dest 'senarai'
    New-Item -ItemType Directory -Force -Path $lists | Out-Null
    # Senarai sendiri dan folder backup\ pengguna dikekalkan.
    $mine = Join-Path $lists 'saya.txt'
    $keep = $null
    if (Test-Path -LiteralPath $mine) { $keep = Get-Content -LiteralPath $mine -Raw }
    Copy-Item -Path (Join-Path $src 'debloat.ps1'), (Join-Path $src 'debloat.bat') -Destination $Dest -Force
    Copy-Item -Path (Join-Path $src 'senarai\*.txt') -Destination $lists -Force
    if ($null -ne $keep) { Set-Content -LiteralPath $mine -Value $keep -NoNewline }
} finally {
    Remove-Item -LiteralPath $Tmp -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "Dipasang di $Dest"

# Proses baru dengan Bypass, kerana Windows biasanya menyekat fail .ps1.
$run = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Dest 'debloat.ps1'), '-Auto')
if ($Jenama) { $run += @('-Jenama', $Jenama) }
if ($Ip) { $run += @('-Ip', $Ip) }
& powershell.exe @run
