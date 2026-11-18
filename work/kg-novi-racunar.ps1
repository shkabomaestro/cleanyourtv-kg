# Jednom, kad update ide sa DRUGOG racunara od onog na kome je launcher prvi put instaliran.
#   powershell -ExecutionPolicy Bypass -File work\kg-novi-racunar.ps1 -TvHost <tv ip>
# Svaki racunar potpisuje aplikacije svojim kljucem, a Android ne pusta da se aplikacija potpisana jednim kljucem
# azurira aplikacijom potpisanom drugim (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Zato ova skripta:
#   1. sacuva sa sticka tvoje slike, radio stanice, muziku i IQAir kljuc (work\tv-backup\)
#   2. vrati fabricki Google TV pocetni ekran, da stick nijednog trenutka ne ostane bez pocetnog ekrana
#   3. obrise stari launcher i stari radio dodatak (samo ta dva)
#   4. pokrene kg-update.ps1 (novi dodatak, slike, launcher - on opet ugasi fabricki ekran - i tema)
#   5. vrati stanice, muziku i kljuc
# Posle ovoga, sledeci update-i sa ovog racunara idu obicnim kg-update.ps1.
param([Parameter(Mandatory = $true)][string]$TvHost)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "${TvHost}:5555"
$stock = 'com.google.android.tvlauncher'
$home_ = '/sdcard/Android/data/com.gathin.tvhome/files'
$radio = '/sdcard/Android/data/local.cleanyourtv.radio/files'
function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Sh($cmd) { "$(& $adb -s $dev shell $cmd)".Trim() }

Step 'Povezivanje'
& $adb connect $dev | Out-Host
$ErrorActionPreference = 'Continue'
for ($i = 0; $i -lt 40 -and "$(& $adb -s $dev get-state 2>$null)".Trim() -ne 'device'; $i++) {
    if ($i -eq 0) { Write-Host 'Na TV-u stikliraj "Always allow from this computer" i pritisni OK...' -ForegroundColor Yellow }
    Start-Sleep 3; & $adb connect $dev | Out-Null
}
$ErrorActionPreference = 'Stop'
if ("$(& $adb -s $dev get-state)".Trim() -ne 'device') { throw "Stick na $TvHost ne odgovara." }

# Skripte za launcher pricaju sa TV-om i svojim Python klijentom (tools\tv.py), sa svojim kljucem: TV pita jos jednom.
$env:TV_HOST = $TvHost
$ErrorActionPreference = 'Continue'
for ($i = 0; $i -lt 40; $i++) {
    $answer = "$(& tools\venv\Scripts\python.exe -I tools\tv.py 'echo spreman' 2>$null)"
    if ($answer -match 'spreman') { break }
    if ($i -eq 0) { Write-Host 'TV ce pitati jos jednom (drugi kljuc): stikliraj "Always allow" i pritisni OK...' -ForegroundColor Yellow }
    Start-Sleep 3
}
$ErrorActionPreference = 'Stop'
if ($answer -notmatch 'spreman') { throw 'tools\tv.py ne dobija odgovor od TV-a. Nista nije menjano.' }

Step '1/5 Cuvanje tvojih stvari sa sticka'
$bak = 'work\tv-backup'
New-Item -ItemType Directory -Force "$bak\launcher", "$bak\radio", 'work\kg-from-tv' | Out-Null
& $adb -s $dev pull "$home_/." "$bak\launcher" | Out-Host
& $adb -s $dev pull "$radio/." "$bak\radio" | Out-Host
Get-ChildItem "$bak\launcher\wallpapers" -Filter 'moja-*.jpg' -ErrorAction SilentlyContinue |
    Copy-Item -Destination 'work\kg-from-tv' -Force
Write-Host "Sacuvano u $bak"

Step '2/5 Fabricki pocetni ekran privremeno nazad'
Sh "pm enable --user 0 $stock" | Out-Host
$component = Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $stock | tail -n 1"
Sh "cmd package set-home-activity --user 0 $component" | Out-Host
$homeNow = (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1').Split('/')[0]
if ($homeNow -ne $stock) { throw "Pocetni ekran je '$homeNow', a ne $stock. Nista nije obrisano; javi ovo Claude-u." }
Write-Host "Pocetni ekran: $homeNow"

Step '3/5 Brisanje starog launchera i radio dodatka'
& $adb -s $dev uninstall com.gathin.tvhome | Out-Host
& $adb -s $dev uninstall local.cleanyourtv.radio | Out-Host

Step '4/5 Nova verzija'
powershell -ExecutionPolicy Bypass -File work\kg-update.ps1 -TvHost $TvHost
if ($LASTEXITCODE -ne 0) { throw 'kg-update.ps1 nije zavrsio. Fabricki pocetni ekran je ukljucen, stick radi; posalji Claude-u sliku ekrana.' }

Step '5/5 Vracanje stanica, muzike i kljuca'
if (Test-Path "$bak\launcher\stations.txt") { & $adb -s $dev push "$bak\launcher\stations.txt" "$home_/stations.txt" | Out-Host }
if (Test-Path "$bak\launcher\music") { Sh "mkdir -p $home_/music" | Out-Null; & $adb -s $dev push "$bak\launcher\music\." "$home_/music/" | Out-Host }
if (Test-Path "$bak\radio\iqair-key.txt") { Sh "mkdir -p $radio" | Out-Null; & $adb -s $dev push "$bak\radio\iqair-key.txt" "$radio/iqair-key.txt" | Out-Host }

Write-Host "`nGotovo: nova verzija je na sticku, potpisana ovim racunarom." -ForegroundColor Green
