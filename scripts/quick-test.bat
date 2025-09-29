@echo off
REM GuoZhan项目快速测试脚本
REM 用于快速构建和验证插件

echo ========================================
echo GuoZhan项目快速测试脚本
echo ========================================

echo.
echo [1/4] 清理旧构建文件...
call gradlew clean
if %ERRORLEVEL% neq 0 (
    echo 错误: 清理失败
    pause
    exit /b 1
)

echo.
echo [2/4] 编译Kotlin代码...
call gradlew compileKotlin
if %ERRORLEVEL% neq 0 (
    echo 错误: 编译失败
    pause
    exit /b 1
)

echo.
echo [3/4] 生成插件JAR文件...
call gradlew shadowJar
if %ERRORLEVEL% neq 0 (
    echo 错误: JAR生成失败
    pause
    exit /b 1
)

echo.
echo [4/4] 验证构建结果...
if exist "build\libs\Guozhan-1.0-SNAPSHOT.jar" (
    echo ✓ JAR文件生成成功
    dir build\libs\Guozhan-1.0-SNAPSHOT.jar
) else (
    echo ✗ JAR文件未找到
    pause
    exit /b 1
)

echo.
echo ========================================
echo 构建完成！
echo JAR文件位置: build\libs\Guozhan-1.0-SNAPSHOT.jar
echo 文件大小: 
for %%A in (build\libs\Guozhan-1.0-SNAPSHOT.jar) do echo %%~zA bytes
echo ========================================

echo.
echo 下一步:
echo 1. 将JAR文件复制到Folia服务器的plugins目录
echo 2. 配置数据库连接
echo 3. 重启服务器
echo 4. 检查插件是否正常加载

pause
