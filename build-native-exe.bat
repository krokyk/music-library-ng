@echo off
setlocal

set "GRAALVM_HOME=d:\dev\java\graalvm-jdk-21.0.7+8.1"
set "SKIP_FRONTEND=true"

if not exist "%GRAALVM_HOME%\bin\java.exe" (
    echo GraalVM was not found at:
    echo   %GRAALVM_HOME%
    echo.
    echo Expected Java executable:
    echo   %GRAALVM_HOME%\bin\java.exe
    echo.
    pause
    exit /b 1
)

set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if /I "%SKIP_FRONTEND%"=="true" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-native-exe.ps1" -SkipFrontend %*
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-native-exe.ps1" %*
)
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

if not "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo Native executable build failed with exit code %BUILD_EXIT_CODE%.
    pause
)

exit /b %BUILD_EXIT_CODE%
