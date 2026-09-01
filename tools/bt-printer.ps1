<#
    A Bluetooth thermal printer, made out of a laptop.

    Windows can advertise the Serial Port Profile - the same profile every
    cheap ESC/POS printer speaks - and hand whatever arrives to a COM port. So
    the phone does not need to know it is not talking to the JK-5802H: it looks
    the printer up by the standard SPP service, opens an RFCOMM socket, and
    writes. Everything the folder watcher cannot see is exercised here - the
    connect, whether the link is really being kept open between prints, how
    long a write takes over the air.

    Each job is written to captures/sim/bt as a -print.bin, which is exactly
    what the gateway itself saves. printer-sim.mjs watches that folder and the
    phone's own, and shows a page that arrived both ways once.

    Setup, once, in Windows:
      Settings > Bluetooth > pair the phone
      Settings > Bluetooth > More Bluetooth settings > COM Ports > Add
        > Incoming (the device initiates the connection)
    Then run this, and choose the laptop as the printer in the gateway app.

    Usage:  powershell -ExecutionPolicy Bypass -File tools/bt-printer.ps1
            ... -Port COM5      (only needed if there are several)
#>
[CmdletBinding()]
param(
    [string] $Port = '',
    [string] $Out = '',
    [int]    $IdleMs = 1200
)

$ErrorActionPreference = 'Stop'

if (-not $Out) {
    $Out = Join-Path (Split-Path -Parent $PSScriptRoot) 'captures\sim\bt'
}
if (-not (Test-Path $Out)) {
    New-Item -ItemType Directory -Force -Path $Out | Out-Null
}

function Resolve-Port {
    param([string] $Wanted)

    $names = [System.IO.Ports.SerialPort]::GetPortNames()
    if ($Wanted) {
        if ($names -contains $Wanted) { return $Wanted }
        throw "$Wanted is not present. Ports found: $($names -join ', ')"
    }
    if ($names.Count -eq 0) {
        throw @'
No COM ports. Add an incoming Bluetooth COM port first:
  Settings > Bluetooth & devices > Devices > More Bluetooth settings
  > COM Ports tab > Add > Incoming (the device initiates the connection)
'@
    }
    if ($names.Count -eq 1) { return $names[0] }
    throw "Several ports ($($names -join ', ')). Pass -Port COMx to pick one."
}

# A print always ends with a cut, and the gateway now holds the link open
# between jobs - so waiting for the connection to drop would join every sale
# of the shift into one enormous page. The cut is what separates them.
function Test-EndsWithCut {
    param([byte[]] $Tail)

    if ($Tail.Length -ge 3 -and
        $Tail[$Tail.Length - 3] -eq 0x1D -and $Tail[$Tail.Length - 2] -eq 0x56) {
        return $true
    }
    return ($Tail.Length -ge 4 -and
            $Tail[$Tail.Length - 4] -eq 0x1D -and $Tail[$Tail.Length - 3] -eq 0x56)
}

function Save-Job {
    param([System.IO.MemoryStream] $Buffer, [string] $Folder)

    $bytes = $Buffer.ToArray()
    if ($bytes.Length -eq 0) { return }

    $name = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-print.bin'
    $path = Join-Path $Folder $name
    [System.IO.File]::WriteAllBytes($path, $bytes)
    Write-Host ("  received {0}  {1:N1} KB" -f $name, ($bytes.Length / 1KB)) `
        -ForegroundColor Green
    $Buffer.SetLength(0)
}

$portName = Resolve-Port $Port
Write-Host "Bluetooth printer on $portName" -ForegroundColor Cyan
Write-Host "writing jobs to $Out"
Write-Host "choose this laptop as the printer in the gateway app, then print`n"

while ($true) {
    $serial = New-Object System.IO.Ports.SerialPort $portName, 115200, 'None', 8, 'One'
    # Short read timeout so the idle fallback and Ctrl+C both stay responsive;
    # over RFCOMM the baud rate above is decoration, the link runs at its own
    # speed.
    $serial.ReadTimeout = 250

    try {
        $serial.Open()
    } catch {
        Write-Host "waiting for $portName to come free... ($($_.Exception.Message))" `
            -ForegroundColor DarkYellow
        Start-Sleep -Seconds 2
        continue
    }

    $buffer = New-Object System.IO.MemoryStream
    $tail = New-Object System.Collections.Generic.List[byte]
    $lastByteAt = $null
    $chunk = New-Object byte[] 4096

    try {
        while ($serial.IsOpen) {
            $read = 0
            try {
                $read = $serial.Read($chunk, 0, $chunk.Length)
            } catch [System.TimeoutException] {
                $read = 0
            }

            if ($read -gt 0) {
                if ($null -eq $lastByteAt) {
                    Write-Host "  printing..." -ForegroundColor DarkGray
                }
                $buffer.Write($chunk, 0, $read)
                $lastByteAt = [DateTime]::UtcNow

                # Only the last few bytes ever matter, and raster data can
                # contain anything, so the cut is looked for at the tail only.
                for ($i = [Math]::Max(0, $read - 4); $i -lt $read; $i++) {
                    $tail.Add($chunk[$i])
                }
                while ($tail.Count -gt 4) { $tail.RemoveAt(0) }

                if (Test-EndsWithCut $tail.ToArray()) {
                    Save-Job $buffer $Out
                    $tail.Clear()
                    $lastByteAt = $null
                }
            } elseif ($null -ne $lastByteAt -and
                      ([DateTime]::UtcNow - $lastByteAt).TotalMilliseconds -gt $IdleMs) {
                # A stream that carried no cut. Still paper, so still a job.
                Save-Job $buffer $Out
                $tail.Clear()
                $lastByteAt = $null
            }
        }
    } catch {
        Write-Host "link dropped: $($_.Exception.Message)" -ForegroundColor DarkYellow
    } finally {
        # Whatever arrived before the link went is still a page worth seeing.
        Save-Job $buffer $Out
        if ($serial.IsOpen) { $serial.Close() }
        $serial.Dispose()
    }

    Write-Host "reopening $portName..." -ForegroundColor DarkGray
}
