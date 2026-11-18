# Installs the November update on the Mi TV Stick in one go, from this repo's folder:
#   powershell -ExecutionPolicy Bypass -File work\kg-update.ps1 -TvHost <tv ip>
# 1. the radio add-on (it now also reads the news and the full weather for the launcher)  2. fifty new photos
# 3. the launcher (live weather, news ticker, Cyrillic at every start, photos in rounds)  4. the theme, pushed directly.
# -SkipPhotos leaves the photos as they are (for a second run after a build error, say).
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$SkipPhotos)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$py = 'tools\venv\Scripts\python.exe'
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what nije uspelo (kod $LASTEXITCODE)" } }

Step 'Povezivanje sa stickom'
& $adb connect "${TvHost}:5555" | Out-Host
$state = ''
$ErrorActionPreference = 'Continue'   # adb javlja "not found" na stderr dok TV ne odgovori: to nije greska skripte
for ($i = 0; $i -lt 40; $i++) {   # novi racunar: TV pita "Allow USB debugging?" - imas dva minuta da potvrdis
    $state = "$(& $adb -s "${TvHost}:5555" get-state 2>$null)".Trim()
    if ($state -eq 'device') { break }
    if ($i -eq 0) { Write-Host 'Na TV-u stikliraj "Always allow from this computer" i pritisni OK...' -ForegroundColor Yellow }
    Start-Sleep -Seconds 3
    & $adb connect "${TvHost}:5555" | Out-Null
}
$ErrorActionPreference = 'Stop'
if ($state -ne 'device') { throw "Stick na $TvHost ne odgovara (stanje: '$state'). Proveri IP adresu sticka (Settings > Network) i da je USB debugging ukljucen." }

Step '1/4 Radio dodatak (vesti i vreme)'
Push-Location addons\radio
.\gradlew.bat --no-daemon assembleRelease; Check 'Build radio dodatka'
Pop-Location
& $adb -s "${TvHost}:5555" install -r addons\radio\app\build\outputs\apk\release\app-release.apk | Out-Host; Check 'Instalacija radio dodatka'

if (-not $SkipPhotos) {
    Step '2/4 Pedeset novih fotografija (Wikimedia Commons, nekoliko minuta)'
    # Tvoje slike (moja-*.jpg) koje su vec na sticku ostaju, i kad ovaj racunar nema originale.
    $d = '/sdcard/Android/data/com.gathin.tvhome/files'
    New-Item -ItemType Directory -Force work\kg-from-tv | Out-Null
    foreach ($f in (& $adb -s "${TvHost}:5555" shell "ls $d/wallpapers/ 2>/dev/null") -split "`r?`n") {
        $f = $f.Trim()
        if ($f -like 'moja-*.jpg') { & $adb -s "${TvHost}:5555" pull "$d/wallpapers/$f" "work\kg-from-tv\$f" | Out-Host }
    }
    & $py -I work\kg_more.py; Check 'Izbor fotografija'
    & $py -I work\kg_grade.py; Check 'Obrada fotografija'
    & $py -I work\kg_keep_own.py; Check 'Cuvanje tvojih slika'
}

Step '3/4 Launcher'
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost $TvHost -HomeKeyService; Check 'Build i instalacija launchera'

Step '4/4 Tema na TV'
$d = '/sdcard/Android/data/com.gathin.tvhome/files'
& $adb -s "${TvHost}:5555" shell "mkdir -p $d/wallpapers; rm -f $d/wallpapers/*.jpg $d/wallpapers/captions.txt" | Out-Host
& $adb -s "${TvHost}:5555" push "themes\kragujevac\wallpapers\." "$d/wallpapers/" | Out-Host; Check 'Slanje fotografija'
& $adb -s "${TvHost}:5555" push themes\kragujevac\config.json "$d/config.json" | Out-Host; Check 'Slanje teme'
$env:TV_HOST = $TvHost
Push-Location tools; & venv\Scripts\python.exe -I theme_restart.py; Pop-Location

Write-Host "`nGotovo. Pregled novih slika: work\kragujevac-nove.jpg (broj slike koju ne zelis upisi u work\kg-more-skip.txt pa pokreni ponovo)." -ForegroundColor Green
