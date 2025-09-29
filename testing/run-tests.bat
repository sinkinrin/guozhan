@echo off
REM GuoZhan项目Folia测试运行脚本（Windows版本）
REM 此脚本使用正确的Folia测试方法，而不是Docker

echo ========================================
echo GuoZhan Folia测试运行脚本 (Windows)
echo ========================================

REM 检查Java版本
:check_java
echo [步骤] 检查Java环境...

java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] Java未安装或不在PATH中
    exit /b 1
)

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION_STRING=%%g
)
set JAVA_VERSION_STRING=%JAVA_VERSION_STRING:"=%
for /f "delims=." %%a in ("%JAVA_VERSION_STRING%") do set JAVA_VERSION=%%a

if %JAVA_VERSION% lss 21 (
    echo [错误] 需要Java 21或更高版本，当前版本: %JAVA_VERSION%
    exit /b 1
)

echo [成功] Java版本检查通过: %JAVA_VERSION%
exit /b 0

REM 清理旧的测试环境
:cleanup_old_tests
echo [步骤] 清理旧的测试环境...

REM 停止可能运行的测试服务器
taskkill /f /im java.exe /fi "WINDOWTITLE eq *folia*test*" >nul 2>&1

REM 清理旧的运行目录
if exist "run\folia-test" (
    rmdir /s /q "run\folia-test"
    echo [成功] 清理旧的Folia测试目录
)

REM 清理Gradle缓存
call gradlew clean >nul 2>&1
echo [成功] 清理Gradle构建缓存
exit /b 0

REM 运行单元测试
:run_unit_tests
echo [步骤] 运行单元测试（MockBukkit）...

echo 正在执行单元测试...
call gradlew test --tests "*unit*" --info
if %ERRORLEVEL% equ 0 (
    echo [成功] 单元测试通过
    exit /b 0
) else (
    echo [错误] 单元测试失败
    exit /b 1
)

REM 构建插件
:build_plugin
echo [步骤] 构建GuoZhan插件...

call gradlew shadowJar
if %ERRORLEVEL% equ 0 (
    echo [成功] 插件构建成功
    
    if exist "build\libs\Guozhan-1.0-SNAPSHOT.jar" (
        for %%A in (build\libs\Guozhan-1.0-SNAPSHOT.jar) do (
            echo [成功] JAR文件生成: %%~zA bytes
        )
    ) else (
        echo [错误] JAR文件未找到
        exit /b 1
    )
) else (
    echo [错误] 插件构建失败
    exit /b 1
)
exit /b 0

REM 启动Folia测试服务器
:start_folia_server
echo [步骤] 启动Folia测试服务器...

REM 复制测试配置
if not exist "run\folia-test\plugins\Guozhan" mkdir "run\folia-test\plugins\Guozhan"
copy "testing\folia-test-config.yml" "run\folia-test\plugins\Guozhan\config.yml" >nul

echo [警告] 正在启动Folia服务器，这可能需要几分钟...
echo [警告] 服务器启动后，请在另一个终端运行集成测试

REM 启动Folia服务器（后台运行）
start "Folia Test Server" /min cmd /c "gradlew runFolia"

echo 等待服务器启动...

REM 等待服务器启动
for /l %%i in (1,1,60) do (
    if exist "run\folia-test\logs\latest.log" (
        findstr /c:"Done" "run\folia-test\logs\latest.log" >nul 2>&1
        if !ERRORLEVEL! equ 0 (
            echo [成功] Folia服务器启动完成
            exit /b 0
        )
    )
    echo|set /p="."
    timeout /t 2 >nul
)

echo.
echo [错误] Folia服务器启动超时
taskkill /f /im java.exe /fi "WINDOWTITLE eq *Folia Test Server*" >nul 2>&1
exit /b 1

REM 运行集成测试
:run_integration_tests
echo [步骤] 运行Folia集成测试...

REM 检查服务器是否运行
tasklist /fi "WINDOWTITLE eq *Folia Test Server*" | findstr /i java >nul
if %ERRORLEVEL% neq 0 (
    echo [错误] Folia测试服务器未运行
    echo [警告] 请先运行: %0 start-server
    exit /b 1
)

echo 正在执行Folia集成测试...
call gradlew test --tests "*integration*" --info
if %ERRORLEVEL% equ 0 (
    echo [成功] 集成测试通过
    exit /b 0
) else (
    echo [错误] 集成测试失败
    exit /b 1
)

REM 停止Folia服务器
:stop_folia_server
echo [步骤] 停止Folia测试服务器...

tasklist /fi "WINDOWTITLE eq *Folia Test Server*" | findstr /i java >nul
if %ERRORLEVEL% equ 0 (
    taskkill /f /im java.exe /fi "WINDOWTITLE eq *Folia Test Server*"
    timeout /t 3 >nul
    echo [成功] Folia服务器已停止
) else (
    echo [警告] Folia服务器未运行
)
exit /b 0

REM 生成测试报告
:generate_test_report
echo [步骤] 生成测试报告...

call gradlew jacocoTestReport
if %ERRORLEVEL% equ 0 (
    echo [成功] 测试报告生成完成
    
    if exist "build\reports\jacoco\test\html\index.html" (
        echo 测试覆盖率报告: build\reports\jacoco\test\html\index.html
    )
    
    if exist "build\reports\tests\test\index.html" (
        echo 测试结果报告: build\reports\tests\test\index.html
    )
) else (
    echo [警告] 测试报告生成失败
)
exit /b 0

REM 显示帮助信息
:show_help
echo 用法: %0 [命令]
echo.
echo 命令:
echo   unit-tests      - 仅运行单元测试
echo   build          - 构建插件
echo   start-server   - 启动Folia测试服务器
echo   integration    - 运行集成测试（需要服务器运行）
echo   stop-server    - 停止Folia测试服务器
echo   full-test      - 运行完整测试流程
echo   report         - 生成测试报告
echo   clean          - 清理测试环境
echo   help           - 显示此帮助信息
echo.
echo 示例:
echo   %0 full-test   # 运行完整测试
echo   %0 start-server ^&^& %0 integration  # 分步运行集成测试
exit /b 0

REM 主函数
:main
set COMMAND=%1
if "%COMMAND%"=="" set COMMAND=full-test

if "%COMMAND%"=="unit-tests" (
    call :check_java
    call :cleanup_old_tests
    call :run_unit_tests
) else if "%COMMAND%"=="build" (
    call :check_java
    call :build_plugin
) else if "%COMMAND%"=="start-server" (
    call :check_java
    call :cleanup_old_tests
    call :build_plugin
    call :start_folia_server
) else if "%COMMAND%"=="integration" (
    call :check_java
    call :run_integration_tests
) else if "%COMMAND%"=="stop-server" (
    call :stop_folia_server
) else if "%COMMAND%"=="full-test" (
    call :check_java
    call :cleanup_old_tests
    
    echo.
    echo === 第1阶段: 单元测试 ===
    call :run_unit_tests
    if !ERRORLEVEL! neq 0 (
        echo [错误] 单元测试失败，停止测试流程
        exit /b 1
    )
    
    echo.
    echo === 第2阶段: 构建插件 ===
    call :build_plugin
    if !ERRORLEVEL! neq 0 (
        echo [错误] 插件构建失败，停止测试流程
        exit /b 1
    )
    
    echo.
    echo === 第3阶段: Folia集成测试 ===
    echo [警告] 集成测试需要手动运行，因为需要真实的Folia环境
    echo [警告] 请运行以下命令：
    echo [警告]   1. %0 start-server  # 启动Folia服务器
    echo [警告]   2. %0 integration   # 在另一个终端运行集成测试
    echo [警告]   3. %0 stop-server   # 停止服务器
    
    echo.
    echo === 第4阶段: 生成报告 ===
    call :generate_test_report
    
    echo [成功] 测试流程完成！
) else if "%COMMAND%"=="report" (
    call :generate_test_report
) else if "%COMMAND%"=="clean" (
    call :cleanup_old_tests
    call :stop_folia_server
) else if "%COMMAND%"=="help" (
    call :show_help
) else if "%COMMAND%"=="-h" (
    call :show_help
) else if "%COMMAND%"=="--help" (
    call :show_help
) else (
    echo [错误] 未知命令: %COMMAND%
    call :show_help
    exit /b 1
)

exit /b 0

REM 执行主函数
call :main %*
