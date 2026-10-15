# Deterministic Android density/launcher packaging of the approved raster masters.
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskIcon = [System.Drawing.Image]::FromFile((Join-Path $taskRoot 'branding\logo-master.png'))
$taskBanner = [System.Drawing.Image]::FromFile((Join-Path $taskRoot 'branding\tv-banner-master.png'))
function Export-Png($image, [int]$width, [int]$height, [string]$path, [double]$inset = 0) {
    $bitmap = New-Object System.Drawing.Bitmap $width,$height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $rect = New-Object System.Drawing.RectangleF ([single]($width*$inset)),([single]($height*$inset)),([single]($width*(1-2*$inset))),([single]($height*(1-2*$inset)))
        $graphics.DrawImage($image, $rect)
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    foreach ($bucket in @(@('mdpi',108),@('hdpi',162),@('xhdpi',216),@('xxhdpi',324),@('xxxhdpi',432))) {
        # Source has transparent padding; this places the actual ring in the
        # adaptive-icon safe zone, without redrawing the approved artwork.
        Export-Png $taskIcon $bucket[1] $bucket[1] (Join-Path $taskRoot "app\src\main\res\mipmap-$($bucket[0])\ic_launcher_foreground.png") 0.07
    }
    Export-Png $taskIcon 512 512 (Join-Path $taskRoot 'app\src\main\res\drawable-nodpi\logo_drtech_512.png')
    Export-Png $taskBanner 320 180 (Join-Path $taskRoot 'app\src\main\res\drawable-xhdpi\tv_banner.png')
    Write-Host 'Updated launcher densities, About logo and named TV banner.'
} finally { $taskIcon.Dispose(); $taskBanner.Dispose() }
