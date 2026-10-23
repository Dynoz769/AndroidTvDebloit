# Android TV Debloit - cuci bloatware & tukar home screen Android TV / Google TV
# melalui ADB dari komputer Windows. Jalankan melalui debloat.bat.
#
# Semua perubahan direkod dalam folder backup\ dan boleh di-undo.
#
#   debloat.ps1                                      menu penuh
#   debloat.ps1 -Auto [-Jenama sony] [-Ip 192.168.1.20]
#                                                    setup penuh sekali jalan

param(
    [switch]$Auto,
    [string]$Jenama = '',
    [string]$Ip = ''
)

$ErrorActionPreference = 'Continue'

$Dir        = Split-Path -Parent $MyInvocation.MyCommand.Path
$ListDir    = Join-Path $Dir 'senarai'
$BackupDir  = Join-Path $Dir 'backup'
$IpFile     = Join-Path $Dir '.tv-ip'

$LauncherPkg      = 'io.github.dynoz769.debloithome'
$LauncherActivity = "$LauncherPkg/.HomeActivity"
$LauncherApk      = Join-Path $Dir 'DebloitHome.apk'
$LauncherUrl      = 'https://github.com/Dynoz769/AndroidTvDebloit/releases/latest/download/DebloitHome.apk'

$script:Adb        = ''
$script:Serial     = ''
$script:Brand      = ''
$script:Model      = ''
$script:AndroidVer = ''
$script:ForceBrand = $Jenama
$script:PresetIp   = $Ip

# ---------------------------------------------------------------- utiliti

function Say([string]$Text = '') { Write-Host $Text }
function Ok([string]$Text)   { Write-Host "  [OK]     $Text" -ForegroundColor Green }
function Fail([string]$Text) { Write-Host "  [GAGAL]  $Text" -ForegroundColor Red }
function Info([string]$Text) { Write-Host "  [-]      $Text" -ForegroundColor DarkGray }

function Ask-Yes([string]$Question) {
    $reply = Read-Host "$Question (y/n)"
    return ($reply -match '^[yY]')
}

function Last-Line([string]$Text) {
    $lines = @($Text -split "`n" | Where-Object { $_.Trim() })
    if ($lines.Count -eq 0) { return '' }
    return $lines[-1]
}

# Baca fail senarai, pulangkan objek { Pkg, Desc } untuk setiap baris sah.
function Read-List([string]$Path) {
    $result = @()
    if (-not (Test-Path -LiteralPath $Path)) { return $result }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $parts = $line -split '#', 2
        $pkg = $parts[0] -replace '\s', ''
        if (-not $pkg) { continue }
        $desc = ''
        if ($parts.Count -gt 1) { $desc = $parts[1].Trim() }
        $result += [pscustomobject]@{ Pkg = $pkg; Desc = $desc }
    }
    return $result
}

# App teras yang tak boleh disentuh walaupun ada dalam senarai.
function Test-Protected([string]$Pkg) {
    $patterns = @(
        'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
        'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf',
        'com.android.shell', 'com.android.bluetooth', 'com.android.phone',
        'com.android.packageinstaller', 'com.google.android.packageinstaller',
        'com.android.providers.*', 'com.android.inputmethod.*', 'com.google.android.inputmethod.*',
        'com.android.networkstack*', 'com.google.android.networkstack*',
        'com.google.android.tv.remote.service', 'com.google.android.tvsetup',
        $LauncherPkg
    )
    foreach ($p in $patterns) { if ($Pkg -like $p) { return $true } }
    return $false
}

# ---------------------------------------------------------------- ADB

function Find-Adb {
    if ($script:Adb) { return $true }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $script:Adb = $cmd.Path; return $true }
    $local = Join-Path $Dir 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $local) { $script:Adb = $local; return $true }

    Say 'ADB belum ada. Muat turun platform-tools rasmi dari Google (~10 MB)...'
    $zip = Join-Path $Dir 'platform-tools.zip'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive -LiteralPath $zip -DestinationPath $Dir -Force
    } catch {
        Fail "Muat turun ADB gagal: $($_.Exception.Message)"
        return $false
    } finally {
        Remove-Item -LiteralPath $zip -ErrorAction SilentlyContinue
    }
    $script:Adb = $local
    Ok 'ADB siap dipasang dalam folder platform-tools\'
    return $true
}

function Invoke-Adb([string[]]$AdbArgs) {
    $out = & $script:Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
    return (($out -join "`n") -replace "`r", '')
}

function Tv([string]$Command) { return Invoke-Adb @('-s', $script:Serial, 'shell', $Command) }

function Get-Prop([string]$Name) { return (Tv "getprop $Name").Trim() }

function Get-Packages([string]$Flags = '') {
    return @((Tv ("pm list packages $Flags").Trim()) -split "`n" |
        ForEach-Object { ($_ -replace '^package:', '').Trim() } |
        Where-Object { $_ })
}

# Pakej yang dibuang untuk user ini tapi masih ada dalam sistem.
function Get-RemovedPackages {
    $installed = Get-Packages
    return @(Get-Packages '-u' | Where-Object { $installed -notcontains $_ })
}

function Test-GoogleTv { return ((Get-Packages) -contains 'com.google.android.apps.tv.launcherx') }

function Load-TvInfo {
    $maker = Get-Prop 'ro.product.manufacturer'
    $brand = Get-Prop 'ro.product.brand'
    $script:Brand = $maker
    if ($brand.ToLower() -ne $maker.ToLower()) { $script:Brand = "$maker $brand" }
    $script:Model      = Get-Prop 'ro.product.model'
    $script:AndroidVer = Get-Prop 'ro.build.version.release'
}

function Connect-Adb([string]$Address) {
    $out = Invoke-Adb @('connect', $Address)
    if ($out -match 'connected to') { return $true }
    Fail $out
    return $false
}

# Untuk TV yang guna "Wireless debugging" (Android 11+ / Google TV baru).
function Pair-Tv {
    Say 'Di TV: Developer options > Wireless debugging > Pair device with pairing code'
    $addr = (Read-Host 'IP:port untuk pairing (contoh 192.168.1.20:37123)') -replace '\s', ''
    $code = (Read-Host 'Kod pairing 6 digit') -replace '\s', ''
    $out = Invoke-Adb @('pair', $addr, $code)
    if ($out -match 'Successfully paired') { Ok 'Pairing berjaya'; return $true }
    Fail $out
    return $false
}

function Connect-Tv {
    if (-not (Find-Adb)) { return $false }
    $last = ''
    if (Test-Path -LiteralPath $IpFile) { $last = (Get-Content -LiteralPath $IpFile -Raw).Trim() }
    Say ''
    Say 'Cari IP TV di: Settings > Network & Internet > (Wi-Fi/Ethernet anda) > IP address'
    if ($script:PresetIp) {
        $ip = $script:PresetIp
        $script:PresetIp = ''
        Say "Alamat IP TV: $ip"
    } elseif ($last) {
        $ip = Read-Host "Alamat IP TV [$last]"
        if (-not $ip) { $ip = $last }
    } else {
        $ip = Read-Host 'Alamat IP TV (contoh 192.168.1.20)'
    }
    $ip = $ip -replace '\s', ''
    if (-not $ip) { Fail 'Alamat IP kosong.'; return $false }
    if ($ip -notmatch ':') { $ip = "${ip}:5555" }
    Set-Content -LiteralPath $IpFile -Value $ip -Encoding ASCII

    if (-not (Connect-Adb $ip)) {
        Say "  Pastikan: TV & komputer guna Wi-Fi sama, dan 'USB debugging' sudah"
        Say '  dihidupkan dalam Developer options TV.'
        if (-not (Ask-Yes "TV anda hanya ada 'Wireless debugging' (perlu kod pairing)? Cuba pairing")) { return $false }
        if (-not (Pair-Tv)) { return $false }
        $ip = (Read-Host 'IP:port di skrin utama Wireless debugging (contoh 192.168.1.20:41235)') -replace '\s', ''
        Set-Content -LiteralPath $IpFile -Value $ip -Encoding ASCII
        if (-not (Connect-Adb $ip)) { return $false }
    }

    for ($i = 1; $i -le 45; $i++) {
        $state = (Invoke-Adb @('-s', $ip, 'get-state')).Trim()
        if ($state -eq 'device') {
            $script:Serial = $ip
            Load-TvInfo
            Ok "Bersambung: $($script:Brand) / $($script:Model) (Android $($script:AndroidVer))"
            if (Test-GoogleTv) { Info 'Jenis: Google TV' } else { Info 'Jenis: Android TV' }
            return $true
        }
        if ($state -match 'unauthorized' -and $i -eq 1) {
            Say "  >> Lihat skrin TV: tick 'Always allow' dan tekan 'Allow' / 'Benarkan'."
        }
        Start-Sleep -Seconds 2
    }
    Fail "TV tak benarkan sambungan. Cuba lagi dan tekan 'Allow' di TV."
    return $false
}

function Require-Tv {
    if ($script:Serial -and (Invoke-Adb @('-s', $script:Serial, 'get-state')).Trim() -eq 'device') { return $true }
    $script:Serial = ''
    return (Connect-Tv)
}

# ---------------------------------------------------------------- matikan / pulihkan

function New-Backup([string]$Kind) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $f = Join-Path $BackupDir ("{0}_{1}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Kind)
    $header = @(
        '# Backup Android TV Debloit',
        "# Jenis: $Kind",
        "# TV: $($script:Brand) / $($script:Model) ($($script:Serial))",
        "# Tarikh: $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    )
    Set-Content -LiteralPath $f -Value $header -Encoding ASCII
    return $f
}

function Disable-Pkg([string]$Pkg, [string]$BackupFile) {
    $out = Tv "pm disable-user --user 0 $Pkg"
    if ($out -match 'new state: disabled') {
        Add-Content -LiteralPath $BackupFile -Value "disable $Pkg" -Encoding ASCII
        Ok "$Pkg (dimatikan)"; return $true
    }
    # Sesetengah app sistem tak boleh dimatikan; buang untuk user ini sahaja
    # (fail asal kekal dalam sistem, boleh dipulihkan).
    $out = Tv "pm uninstall -k --user 0 $Pkg"
    if ($out -match 'Success') {
        Add-Content -LiteralPath $BackupFile -Value "uninstall $Pkg" -Encoding ASCII
        Ok "$Pkg (disembunyikan dari sistem)"; return $true
    }
    Fail "${Pkg}: $(Last-Line $out)"
    return $false
}

function Restore-Pkg([string]$Action, [string]$Pkg) {
    $out = ''
    if ($Action -eq 'disable') {
        $out = Tv "pm enable $Pkg"
        if ($out -match 'new state: (enabled|default)') { Ok "$Pkg (dihidupkan)"; return $true }
    } elseif ($Action -eq 'uninstall') {
        $out = Tv "cmd package install-existing $Pkg"
        if ($out -match 'installed|Success') { Ok "$Pkg (dipulihkan)"; return $true }
        $out = Tv "pm install-existing $Pkg"
        if ($out -match 'installed|Success') { Ok "$Pkg (dipulihkan)"; return $true }
    }
    Fail "${Pkg}: $(Last-Line $out)"
    return $false
}

# Pulihkan satu fail backup, dari bawah ke atas.
function Restore-Backup([string]$File) {
    $lines = @(Get-Content -LiteralPath $File)
    [array]::Reverse($lines)
    $errors = 0
    foreach ($line in $lines) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ge 2 -and ($parts[0] -eq 'disable' -or $parts[0] -eq 'uninstall')) {
            if (-not (Restore-Pkg $parts[0] $parts[1])) { $errors++ }
        }
    }
    if ($errors -eq 0) {
        $done = Join-Path $BackupDir 'dipulihkan'
        New-Item -ItemType Directory -Force -Path $done | Out-Null
        Move-Item -LiteralPath $File -Destination $done -Force
    } else {
        Say "  $errors app gagal dipulihkan. Fail backup dikekalkan: $File"
    }
}

function Get-Backups {
    if (-not (Test-Path -LiteralPath $BackupDir)) { return @() }
    return @(Get-ChildItem -LiteralPath $BackupDir -Filter '*.txt' | Sort-Object LastWriteTime -Descending)
}

# ---------------------------------------------------------------- menu

function Do-Debloat([switch]$Auto) {
    if (-not (Require-Tv)) { return }
    $files = @(Join-Path $ListDir 'umum.txt')
    $brandLc = "$($script:Brand) $($script:ForceBrand)".ToLower()
    foreach ($f in Get-ChildItem -LiteralPath $ListDir -Filter 'jenama-*.txt' -ErrorAction SilentlyContinue) {
        $key = $f.BaseName -replace '^jenama-', ''
        if ($brandLc.Contains($key.ToLower())) { $files += $f.FullName; Info "Guna senarai jenama: $key" }
    }
    $files += (Join-Path $ListDir 'saya.txt')
    if (-not $Auto -and (Ask-Yes 'Termasuk senarai TAMBAHAN (YouTube Kids, YouTube Music, Ambient mode, Meet)?')) {
        $files += (Join-Path $ListDir 'tambahan.txt')
    }

    $enabled = Get-Packages '-e'
    $items = @()
    $seen = @{}
    foreach ($f in $files) {
        foreach ($entry in @(Read-List $f)) {
            if ($seen.ContainsKey($entry.Pkg)) { continue }
            $seen[$entry.Pkg] = $true
            if (Test-Protected $entry.Pkg) { Info "$($entry.Pkg) dilindungi, dilangkau"; continue }
            if ($enabled -contains $entry.Pkg) { $items += $entry }
        }
    }

    if ($items.Count -eq 0) {
        Say ''; Say 'Tiada bloatware dalam senarai yang masih aktif di TV ini.'
        Say "Guna menu 'Imbas senarai app' untuk cari lagi."
        return
    }

    Say ''; Say "App yang akan dimatikan ($($items.Count)):"
    for ($i = 0; $i -lt $items.Count; $i++) {
        Say ('  {0,2}. {1,-48} {2}' -f ($i + 1), $items[$i].Pkg, $items[$i].Desc)
    }
    Say ''
    $reply = 'y'
    if (-not $Auto) { $reply = Read-Host 'Teruskan? [y] ya semua / [p] pilih satu-satu / [n] batal' }
    $chosen = @()
    if ($reply -match '^[yY]') {
        $chosen = $items
    } elseif ($reply -match '^[pP]') {
        foreach ($item in $items) {
            if (Ask-Yes "  Matikan $($item.Pkg) ($($item.Desc))?") { $chosen += $item }
        }
    } else {
        Say 'Dibatalkan.'; return
    }
    if ($chosen.Count -eq 0) { Say 'Tiada app dipilih.'; return }

    $bfile = New-Backup 'debloat'
    foreach ($item in $chosen) { Disable-Pkg $item.Pkg $bfile | Out-Null }
    Say ''; Say "Siap. Backup disimpan: backup\$(Split-Path -Leaf $bfile)"
    if (-not $Auto) { Say "Nak undo? Pilih menu 'Undo'." }
}

function Do-Undo {
    if (-not (Require-Tv)) { return }
    $files = @(Get-Backups)
    if ($files.Count -eq 0) { Say 'Tiada backup untuk dipulihkan.'; return }
    Say ''; Say 'Backup yang ada (terbaru di atas):'
    for ($i = 0; $i -lt $files.Count; $i++) {
        $content = @(Get-Content -LiteralPath $files[$i].FullName)
        $tv = ($content | Where-Object { $_ -like '# TV:*' } | Select-Object -First 1) -replace '^# TV: ', ''
        $count = @($content | Where-Object { $_ -match '^(disable|uninstall) ' }).Count
        Say ('  {0}. {1}  [{2}, {3} app]' -f ($i + 1), $files[$i].Name, $tv, $count)
    }
    $reply = Read-Host "Pilih nombor backup untuk dipulihkan [1], atau 'a' untuk semua"
    if (-not $reply) { $reply = '1' }
    if ($reply -eq 'a' -or $reply -eq 'A') {
        foreach ($f in $files) { Say "> $($f.Name)"; Restore-Backup $f.FullName }
    } elseif ($reply -match '^\d+$' -and [int]$reply -ge 1 -and [int]$reply -le $files.Count) {
        Restore-Backup $files[[int]$reply - 1].FullName
    } else {
        Say 'Pilihan tak sah.'; return
    }
    Say 'Siap.'
}

function Do-InstallLauncher([switch]$Auto) {
    if (-not (Require-Tv)) { return }
    if (-not (Test-Path -LiteralPath $LauncherApk)) {
        Say 'Muat turun DebloitHome.apk dari GitHub...'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -UseBasicParsing -Uri $LauncherUrl -OutFile $LauncherApk
        } catch {
            Remove-Item -LiteralPath $LauncherApk -ErrorAction SilentlyContinue
            Fail 'Muat turun gagal. Letak fail DebloitHome.apk dalam folder ini secara manual.'
            return
        }
    }
    Say 'Memasang home screen baru ke TV...'
    $out = Invoke-Adb @('-s', $script:Serial, 'install', '-r', $LauncherApk)
    if ($out -match 'Success') { Ok 'LayanTv dipasang' } else { Fail (Last-Line $out); return }

    $enabled = Get-Packages '-e'
    $stock = @(Read-List (Join-Path $ListDir 'launcher-asal.txt') | Where-Object { $enabled -contains $_.Pkg })
    if ($stock.Count -gt 0) {
        Say ''; Say 'Home screen asal yang dijumpai:'
        foreach ($s in $stock) { Say ('   - {0,-44} {1}' -f $s.Pkg, $s.Desc) }
        Say 'Supaya butang Home pada remote terus buka home baru, home asal perlu dimatikan.'
        Say "(Boleh dipulihkan bila-bila masa dengan menu 'Buang home screen baru'.)"
        if ($Auto -or (Ask-Yes 'Matikan home screen asal? (disyorkan)')) {
            $bfile = New-Backup 'launcher'
            foreach ($s in $stock) { Disable-Pkg $s.Pkg $bfile | Out-Null }
        }
    }

    $out = Tv "cmd package set-home-activity $LauncherActivity"
    if ($out -match 'Success') { Ok 'LayanTv ditetapkan sebagai home' }
    else { Info 'Pilih LayanTv jika TV tanya home mana nak guna.' }
    Tv "am start -n $LauncherActivity --ez intro true" | Out-Null
    Say ''; Say 'Siap! Tengok TV - intro LayanTv sedang dimainkan.'
}

function Do-Intro {
    if (-not (Require-Tv)) { return }
    if ((Get-Packages) -notcontains $LauncherPkg) {
        Fail 'Home screen baru belum dipasang. Pilih menu 3 dulu.'
        return
    }
    Tv "am start -n $LauncherActivity --ez intro true" | Out-Null
    Ok 'Intro LayanTv dimainkan di TV.'
}

function Do-RemoveLauncher {
    if (-not (Require-Tv)) { return }
    $found = $false
    foreach ($f in Get-Backups) {
        if (Select-String -LiteralPath $f.FullName -Pattern '^# Jenis: launcher' -Quiet) {
            $found = $true
            Say "> Pulihkan home asal dari $($f.Name)"
            Restore-Backup $f.FullName
        }
    }
    if (-not $found) { Info 'Tiada home asal yang perlu dipulihkan.' }

    if ((Get-Packages) -contains $LauncherPkg) {
        if ((Tv "pm uninstall $LauncherPkg") -match 'Success') { Ok 'LayanTv dibuang' }
        else { Fail 'Gagal buang LayanTv' }
    }
    Tv 'input keyevent KEYCODE_HOME' | Out-Null
    Say 'Siap. TV kembali guna home screen asal.'
}

function Do-Scan {
    if (-not (Require-Tv)) { return }
    $gtv = 'tidak'
    if (Test-GoogleTv) { $gtv = 'ya' }
    $name = $script:Model -replace '[^A-Za-z0-9._-]', '_'
    $out = Join-Path $Dir ("imbas-{0}-{1}.txt" -f $name, (Get-Date -Format 'yyyyMMdd-HHmm'))
    $lines = @(
        '# Android TV Debloit - hasil imbas',
        "# Jenama: $($script:Brand) | Model: $($script:Model) | Android: $($script:AndroidVer) | Google TV: $gtv",
        '', '## App sistem yang aktif'
    )
    $lines += (Get-Packages '-s -e' | Sort-Object)
    $lines += @('', '## App pihak ketiga (dipasang)')
    $lines += (Get-Packages '-3' | Sort-Object)
    $lines += @('', '## App yang sudah dimatikan')
    $lines += (Get-Packages '-d' | Sort-Object)
    $lines += @('', '## App yang disembunyikan (uninstall --user 0)')
    $lines += (Get-RemovedPackages | Sort-Object)
    Set-Content -LiteralPath $out -Value $lines -Encoding UTF8
    Ok "Disimpan: $(Split-Path -Leaf $out)"
    Say ''
    Say 'Langkah seterusnya: hantar fail ini kepada AI (contoh Claude) dan minta:'
    Say '  "Senaraikan bloatware dalam TV ini yang selamat dimatikan,'
    Say '   dalam format: nama.pakej   # penerangan"'
    Say "Kemudian salin jawapan ke dalam senarai\saya.txt dan jalankan menu 'Cuci bloatware'."
}

function Do-Emergency {
    if (-not (Require-Tv)) { return }
    Say ''
    Say 'Pulih kecemasan: hidupkan semula SEMUA app yang ada dalam senarai\ dan backup\,'
    Say 'walaupun fail backup sudah hilang atau rosak.'
    if (-not (Ask-Yes 'Teruskan?')) { return }
    $disabled = Get-Packages '-d'
    $removed  = Get-RemovedPackages

    $pkgs = @()
    foreach ($f in Get-ChildItem -LiteralPath $ListDir -Filter '*.txt') { $pkgs += @(Read-List $f.FullName | ForEach-Object { $_.Pkg }) }
    $backupFiles = @()
    foreach ($d in @($BackupDir, (Join-Path $BackupDir 'dipulihkan'))) {
        if (Test-Path -LiteralPath $d) { $backupFiles += @(Get-ChildItem -LiteralPath $d -Filter '*.txt') }
    }
    foreach ($f in $backupFiles) {
        foreach ($line in Get-Content -LiteralPath $f.FullName) {
            $parts = $line.Trim() -split '\s+'
            if ($parts.Count -ge 2 -and ($parts[0] -eq 'disable' -or $parts[0] -eq 'uninstall')) { $pkgs += $parts[1] }
        }
    }

    $count = 0
    foreach ($pkg in ($pkgs | Select-Object -Unique)) {
        if ($disabled -contains $pkg)    { Restore-Pkg 'disable' $pkg | Out-Null; $count++ }
        elseif ($removed -contains $pkg) { Restore-Pkg 'uninstall' $pkg | Out-Null; $count++ }
    }
    Say "Siap. $count app diperiksa & dipulihkan."
}

# Setup penuh sekali jalan, untuk arahan dari laman web.
function Do-Auto {
    Say ''
    Say '================== Setup LayanTv ==================='
    Say ' Skrip ini akan:'
    Say '   1. Sambung ke TV anda'
    Say '   2. Matikan bloatware & app iklan'
    Say '   3. Pasang home screen baru + intro LayanTv'
    Say ' Semua boleh di-undo dari menu penuh.'
    Say '===================================================='
    if (-not (Ask-Yes 'Mula sekarang?')) { Show-Menu; return }
    while (-not (Connect-Tv)) {
        if (-not (Ask-Yes 'Cuba sambung lagi?')) { Say 'Dibatalkan.'; return }
    }
    Do-Debloat -Auto
    Do-InstallLauncher -Auto
    Say ''
    Say '===================================================='
    Say ' Setup selesai! Selamat menonton dengan LayanTv.'
    Say ' Nak undo atau ubah? Klik dua kali fail ini:'
    Say "   $(Join-Path $Dir 'debloat.bat')"
    Say '===================================================='
}

function Show-Menu {
    while ($true) {
        Say ''
        Say '================ Android TV Debloit ================'
        if ($script:Serial) { Say " TV: $($script:Brand) / $($script:Model) ($($script:Serial))" } else { Say ' TV: belum bersambung' }
        Say '----------------------------------------------------'
        Say '  1) Sambung ke TV'
        Say '  2) Cuci bloatware'
        Say '  3) Pasang home screen baru (gaya Apple TV)'
        Say '  4) Undo - pulihkan dari backup'
        Say '  5) Buang home screen baru & kembali ke asal'
        Say '  6) Imbas senarai app (untuk tanya AI)'
        Say '  7) Pulih kecemasan'
        Say '  8) Main intro LayanTv'
        Say '  0) Keluar'
        $choice = Read-Host 'Pilihan'
        switch ($choice) {
            '1' { $script:Serial = ''; Connect-Tv | Out-Null }
            '2' { Do-Debloat }
            '3' { Do-InstallLauncher }
            '4' { Do-Undo }
            '5' { Do-RemoveLauncher }
            '6' { Do-Scan }
            '7' { Do-Emergency }
            '8' { Do-Intro }
            '0' { exit 0 }
            default { Say 'Pilihan tak sah.' }
        }
    }
}

if ($Auto) { Do-Auto } else { Show-Menu }
