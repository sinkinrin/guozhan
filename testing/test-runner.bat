@echo off
REM GuoZhan Test Runner for Windows

echo ========================================
echo GuoZhan Test Runner (Windows)
echo ========================================

set COMMAND=%1
if "%COMMAND%"=="" set COMMAND=help

if "%COMMAND%"=="unit-tests" goto unit_tests
if "%COMMAND%"=="build" goto build_plugin
if "%COMMAND%"=="help" goto show_help
goto show_help

:unit_tests
echo [Step] Running unit tests...
call gradlew test --tests "*unit*"
if %ERRORLEVEL% equ 0 (
    echo [Success] Unit tests passed
) else (
    echo [Error] Unit tests failed
)
goto end

:build_plugin
echo [Step] Building plugin...
call gradlew shadowJar
if %ERRORLEVEL% equ 0 (
    echo [Success] Plugin build successful
    if exist "build\libs\Guozhan-1.0-SNAPSHOT.jar" (
        echo [Success] JAR file generated
    )
) else (
    echo [Error] Plugin build failed
)
goto end

:show_help
echo Usage: %0 [command]
echo.
echo Commands:
echo   unit-tests  - Run unit tests
echo   build       - Build plugin
echo   help        - Show help
echo.
goto end

:end
echo Script execution completed
