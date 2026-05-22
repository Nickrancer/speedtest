Add-Type -AssemblyName System.Drawing

$icons = @(
    @{size=48;  folder="mipmap-mdpi"},
    @{size=72;  folder="mipmap-hdpi"},
    @{size=96;  folder="mipmap-xhdpi"},
    @{size=144; folder="mipmap-xxhdpi"},
    @{size=192; folder="mipmap-xxxhdpi"}
)

foreach ($icon in $icons) {
    $size = $icon.size
    $folder = $icon.folder
    $dir = "d:\speedtest\app\src\main\res\$folder"

    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(10, 14, 26))  # Dark bg

    # Outer glow ring
    $penGlow = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(60, 0, 180, 255), [float]($size * 0.06))
    $margin = [int]($size * 0.08)
    $g.DrawEllipse($penGlow, $margin, $margin, $size - 2*$margin, $size - 2*$margin)

    # Blue outer ring
    $penRing = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(0, 153, 255), [float]($size * 0.04))
    $margin2 = [int]($size * 0.1)
    $g.DrawEllipse($penRing, $margin2, $margin2, $size - 2*$margin2, $size - 2*$margin2)

    # Speed arc (cyan, 150 to 300 degrees sweep)
    $penArc = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(0, 229, 255), [float]($size * 0.05))
    $penArc.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $penArc.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
    $margin3 = [int]($size * 0.15)
    $g.DrawArc($penArc, $margin3, $margin3, $size - 2*$margin3, $size - 2*$margin3, 150, 180)

    # Needle line
    $cx = $size / 2.0
    $cy = $size / 2.0
    $needleLen = $size * 0.32
    $angleRad = 240 * [Math]::PI / 180
    $ex = $cx + $needleLen * [Math]::Cos($angleRad)
    $ey = $cy + $needleLen * [Math]::Sin($angleRad)
    $penNeedle = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(0, 229, 255), [float]($size * 0.03))
    $penNeedle.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $penNeedle.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawLine($penNeedle, [float]$cx, [float]$cy, [float]$ex, [float]$ey)

    # Center dot
    $dotR = $size * 0.07
    $brushDot = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(0, 229, 255))
    $g.FillEllipse($brushDot, [float]($cx - $dotR), [float]($cy - $dotR), [float](2*$dotR), [float](2*$dotR))

    # Inner dark dot
    $dotR2 = $size * 0.035
    $brushInner = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(10, 14, 26))
    $g.FillEllipse($brushInner, [float]($cx - $dotR2), [float]($cy - $dotR2), [float](2*$dotR2), [float](2*$dotR2))

    $g.Dispose()

    # Save both normal and round icons
    $bmp.Save("$dir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save("$dir\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()

    Write-Host "Created $folder ($size x $size)"
}

Write-Host "All icons generated successfully!"
