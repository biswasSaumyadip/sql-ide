<#
    Captures the SQL IDE window to a PNG, optionally resizing it first.

    Run with Windows PowerShell (powershell.exe), not pwsh: System.Drawing and
    System.Windows.Forms ship with the former by default.

    Usage:
      powershell -File capture-window.ps1 -Out shot.png
      powershell -File capture-window.ps1 -Out small.png -Width 660 -Height 480
#>
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$TitleLike = 'SQL IDE',
    [int]$Width = 0,
    [int]$Height = 0,
    [int]$X = 80,
    [int]$Y = 60,
    [switch]$Maximize,
    [string]$SendKeys = '',
    [int]$SettleMs = 1200
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win {
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int t, bool repaint);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$deadline = (Get-Date).AddSeconds(30)
do {
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*$TitleLike*" } | Select-Object -First 1
    if ($proc) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

if (-not $proc) { Write-Error "No window matching '$TitleLike'."; exit 1 }
$handle = $proc.MainWindowHandle

# A minimized window has no client pixels to print.
if ([Win]::IsIconic($handle)) { [Win]::ShowWindow($handle, 9) | Out-Null; Start-Sleep -Milliseconds 600 }

if ($Maximize) {
    [Win]::ShowWindow($handle, 3) | Out-Null
} elseif ($Width -gt 0 -and $Height -gt 0) {
    [Win]::ShowWindow($handle, 9) | Out-Null
    Start-Sleep -Milliseconds 300
    [Win]::MoveWindow($handle, $X, $Y, $Width, $Height, $true) | Out-Null
}

[Win]::SetForegroundWindow($handle) | Out-Null
Start-Sleep -Milliseconds $SettleMs

if ($SendKeys -ne '') {
    [System.Windows.Forms.SendKeys]::SendWait($SendKeys)
    Start-Sleep -Milliseconds $SettleMs
}

$rect = New-Object Win+RECT
[Win]::GetWindowRect($handle, [ref]$rect) | Out-Null
$w = $rect.Right - $rect.Left
$h = $rect.Bottom - $rect.Top

$bitmap = New-Object System.Drawing.Bitmap($w, $h)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$dc = $graphics.GetHdc()
# Flag 2 = PW_RENDERFULLCONTENT, required for hardware-composited surfaces.
[Win]::PrintWindow($handle, $dc, 2) | Out-Null
$graphics.ReleaseHdc($dc)
$graphics.Dispose()
$bitmap.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()

Write-Output "Captured ${w}x${h} to $Out"
