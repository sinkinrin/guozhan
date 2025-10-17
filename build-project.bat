@echo off
REM GuoZhan项目编译脚本
REM 设置Java环境并编译项目

echo ========================================
echo GuoZhan v1.3.19 编译脚本
echo ========================================

REM 设置Java环境
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo [1/4] 验证Java环境...
java -version
if %ERRORLEVEL% neq 0 (
    echo 错误: Java环境配置失败
    pause
    exit /b 1
)

echo.
echo [2/4] 清理旧构建文件...
gradlew.bat clean --no-daemon --console=plain
if %ERRORLEVEL% neq 0 (
    echo 错误: 清理失败
    pause
    exit /b 1
)

echo.
echo [3/4] 编译Kotlin代码...
gradlew.bat compileKotlin --no-daemon --console=plain
if %ERRORLEVEL% neq 0 (
    echo 错误: 编译失败
    pause
    exit /b 1
)

echo.
echo [4/4] 生成JAR文件...
gradlew.bat shadowJar --no-daemon --console=plain
if %ERRORLEVEL% neq 0 (
    echo 错误: JAR生成失败
    pause
    exit /b 1
)

echo.
echo ========================================
echo 编译完成！
echo ========================================

echo.
echo 验证构建结果...
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
echo 构建成功！
echo JAR文件位置: build\libs\Guozhan-1.0-SNAPSHOT.jar
echo ========================================

pause

