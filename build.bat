@echo off
echo Building ASCII Video Streamer...
call "%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd" clean package -DskipTests
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===========================================
    echo Build successful! Run .\run.bat to start.
    echo ===========================================
) else (
    echo.
    echo Build failed. Please check the error output above.
)
pause
