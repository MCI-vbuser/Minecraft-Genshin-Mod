# Fetch URLs listed in miss.log through the USB-connected phone (which has working
# network), and write them into the proxy cache in the exact wire format the proxy
# replays. Run this on the PC; the phone is only needed while fetching.
param(
    [string]$Adb = 'D:\BtBridge\tools\platform-tools\adb.exe',
    [string]$CacheDir = 'C:\Users\ASUS\Desktop\mdk\tools\cnproxy\cache',
    [string]$ListFile = 'C:\Users\ASUS\Desktop\mdk\tools\cnproxy\cache\miss.log'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
if (-not (Test-Path $ListFile)) { throw "list not found: $ListFile" }

function Get-Sha1([string]$s) {
    $sha = [System.Security.Cryptography.SHA1]::Create()
    $h = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($s))
    ($h | ForEach-Object { $_.ToString('x2') }) -join ''
}

$lines = Get-Content $ListFile | Where-Object { $_ -match '\S' } | Select-Object -Unique
$total = @($lines).Count
$n = 0
$saved = 0
$failed = 0

foreach ($line in $lines) {
    $n++
    $p = $line.Trim() -split '\s+'
    if ($p.Count -lt 3) { continue }
    $method = $p[0].ToUpperInvariant()
    $hostName = $p[1]
    $urlPath = $p[2]

    $key = "$hostName $method $urlPath"
    $file = Join-Path $CacheDir ((Get-Sha1 $key) + '.bin')
    if (Test-Path $file) { continue }

    $url = "https://$hostName$urlPath"
    $tmp = [IO.Path]::GetTempFileName()
    try {
        if ($method -eq 'HEAD') {
            $cmdLine = '"' + $Adb + '" exec-out curl -sS -I --max-time 60 "' + $url + '" > "' + $tmp + '"'
        } else {
            $cmdLine = '"' + $Adb + '" exec-out curl -sS -i --max-time 90 "' + $url + '" > "' + $tmp + '"'
        }
        & cmd.exe /c $cmdLine 2>$null | Out-Null

        $bytes = [IO.File]::ReadAllBytes($tmp)
        if ($bytes.Length -lt 17) { $failed++; Write-Host ("[{0}/{1}] EMPTY  {2}" -f $n, $total, $url); continue }

        $probeLen = [Math]::Min(16384, $bytes.Length)
        $probe = [Text.Encoding]::ASCII.GetString($bytes, 0, $probeLen)
        $m = [regex]::Match($probe, "\r?\n\r?\n")
        if (-not $m.Success) { $failed++; Write-Host ("[{0}/{1}] NOHEAD {2}" -f $n, $total, $url); continue }
        $idx = $m.Index
        $sepLen = $m.Length

        $headText = [Text.Encoding]::ASCII.GetString($bytes, 0, $idx)
        $headText = [regex]::Replace($headText, "\x1b\[[0-9;]*m", '')

        $bodyLen = $bytes.Length - ($idx + $sepLen)
        $body = New-Object byte[] $bodyLen
        if ($bodyLen -gt 0) { [Array]::Copy($bytes, $idx + $sepLen, $body, 0, $bodyLen) }

        $headLines = $headText -split "\r?\n"
        $status = $headLines[0].Trim()
        $keep = New-Object System.Collections.Generic.List[string]
        for ($j = 1; $j -lt $headLines.Count; $j++) {
            $h = $headLines[$j]
            if ($h.Trim().Length -eq 0) { continue }
            if ($h -match '^(?i)(transfer-encoding|content-length|connection|keep-alive):') { continue }
            $keep.Add($h)
        }

        if ($method -eq 'HEAD') {
            $newHead = $status + "`r`n" + ($keep -join "`r`n") + "`r`nConnection: close`r`n`r`n"
            $payload = [Text.Encoding]::ASCII.GetBytes($newHead)
        } else {
            $newHead = $status + "`r`n" + ($keep -join "`r`n") + "`r`nContent-Length: $bodyLen`r`nConnection: close`r`n`r`n"
            $hBytes = [Text.Encoding]::ASCII.GetBytes($newHead)
            $payload = New-Object byte[] ($hBytes.Length + $bodyLen)
            [Array]::Copy($hBytes, 0, $payload, 0, $hBytes.Length)
            if ($bodyLen -gt 0) { [Array]::Copy($body, 0, $payload, $hBytes.Length, $bodyLen) }
        }

        [IO.File]::WriteAllBytes($file, $payload)
        $saved++
        Write-Host ("[{0}/{1}] {2,-8} {3}" -f $n, $total, $status, $url)
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ""
Write-Host ("done: saved={0}, failed={1}, total={2}" -f $saved, $failed, $total)
