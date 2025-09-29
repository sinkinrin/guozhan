@echo off
REM 简化的GuoZhan测试脚本

echo ========================================
echo GuoZhan 简化测试脚本 (Windows)
echo ========================================

set COMMAND=%1
if "%COMMAND%"=="" set COMMAND=help

if "%COMMAND%"=="unit-tests" goto unit_tests
if "%COMMAND%"=="build" goto build_plugin
if "%COMMAND%"=="help" goto show_help
goto show_help

:unit_tests
echo [步骤] 运行单元测试...
call gradlew test --tests "*unit*"
if %ERRORLEVEL% equ 0 (
    echo [成功] 单元测试通过
) else (
    echo [错误] 单元测试失败
)
goto end

:build_plugin
echo [步骤] 构建插件...
call gradlew shadowJar
if %ERRORLEVEL% equ 0 (
    echo [成功] 插件构建成功
    if exist "build\libs\Guozhan-1.0-SNAPSHOT.jar" (
        echo [成功] JAR文件已生成
    )
) else (
    echo [错误] 插件构建失败
)
goto end

:show_help
echo 用法: %0 [命令]
echo.
echo 命令:
echo   unit-tests  - 运行单元测试
echo   build       - 构建插件
echo   help        - 显示帮助
echo.
goto end

:end
echo 脚本执行完成
