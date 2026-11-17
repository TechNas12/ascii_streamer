Write-Host "Building ASCII Video Streamer..." -ForegroundColor Cyan
& "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd" clean package -DskipTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nBuild successful! Run .\run.bat or .\run.ps1 to start." -ForegroundColor Green
} else {
    Write-Host "`nBuild failed. Check the error output above." -ForegroundColor Red
}
