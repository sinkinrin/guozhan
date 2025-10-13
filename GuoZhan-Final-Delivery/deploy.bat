@echo off
chcp 65001 >nul
title GuoZhan 插件快速部署脚本

echo ========================================
echo    GuoZhan 插件快速部署脚本 v1.0
echo ========================================
echo.

:: 检查管理员权限
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [错误] 请以管理员身份运行此脚本
    pause
    exit /b 1
)

:: 设置变量
set "SCRIPT_DIR=%~dp0"
set "SERVER_DIR="
set "BACKUP_DIR=%SCRIPT_DIR%backup_%date:~0,4%%date:~5,2%%date:~8,2%"

echo [信息] 脚本目录: %SCRIPT_DIR%
echo.

:: 获取服务器目录
:GET_SERVER_DIR
set /p SERVER_DIR="请输入Folia服务器目录路径: "
if "%SERVER_DIR%"=="" (
    echo [错误] 服务器目录不能为空
    goto GET_SERVER_DIR
)

:: 检查服务器目录
if not exist "%SERVER_DIR%" (
    echo [错误] 服务器目录不存在: %SERVER_DIR%
    goto GET_SERVER_DIR
)

if not exist "%SERVER_DIR%\plugins" (
    echo [错误] 这不是一个有效的Minecraft服务器目录（缺少plugins文件夹）
    goto GET_SERVER_DIR
)

echo [信息] 服务器目录: %SERVER_DIR%
echo.

:: 检查Folia服务器
set "FOLIA_JAR="
for %%f in ("%SERVER_DIR%\folia*.jar") do set "FOLIA_JAR=%%f"
if "%FOLIA_JAR%"=="" (
    echo [警告] 未检测到Folia服务器JAR文件
    echo [警告] 请确保您使用的是Folia服务器而非Paper/Spigot
    echo.
)

:: 选择部署模式
echo 请选择部署模式:
echo 1. 生产环境部署（推荐）
echo 2. 测试环境部署
echo 3. 自定义部署
echo.
set /p DEPLOY_MODE="请输入选择 (1-3): "

if "%DEPLOY_MODE%"=="1" (
    set "CONFIG_SOURCE=examples\production-config.yml"
    set "DEPLOY_TYPE=生产环境"
) else if "%DEPLOY_MODE%"=="2" (
    set "CONFIG_SOURCE=examples\test-config.yml"
    set "DEPLOY_TYPE=测试环境"
) else if "%DEPLOY_MODE%"=="3" (
    set "CONFIG_SOURCE=config\config.yml"
    set "DEPLOY_TYPE=自定义"
) else (
    echo [错误] 无效的选择
    pause
    exit /b 1
)

echo [信息] 部署模式: %DEPLOY_TYPE%
echo.

:: 检查现有安装
if exist "%SERVER_DIR%\plugins\GuoZhan*.jar" (
    echo [警告] 检测到现有的GuoZhan插件
    set /p BACKUP_CHOICE="是否备份现有配置? (Y/N): "
    if /i "%BACKUP_CHOICE%"=="Y" (
        echo [信息] 创建备份目录: %BACKUP_DIR%
        mkdir "%BACKUP_DIR%" 2>nul
        
        if exist "%SERVER_DIR%\plugins\GuoZhan" (
            echo [信息] 备份现有配置...
            xcopy "%SERVER_DIR%\plugins\GuoZhan" "%BACKUP_DIR%\GuoZhan\" /E /I /Q
        )
        
        echo [信息] 备份现有插件JAR...
        copy "%SERVER_DIR%\plugins\GuoZhan*.jar" "%BACKUP_DIR%\" >nul 2>&1
    )
    
    echo [信息] 删除现有插件...
    del "%SERVER_DIR%\plugins\GuoZhan*.jar" >nul 2>&1
    if exist "%SERVER_DIR%\plugins\GuoZhan" (
        rmdir /s /q "%SERVER_DIR%\plugins\GuoZhan" 2>nul
    )
)

:: 开始部署
echo ========================================
echo 开始部署 GuoZhan 插件...
echo ========================================
echo.

:: 1. 复制插件JAR文件
echo [1/6] 复制插件文件...
copy "%SCRIPT_DIR%plugin\GuoZhan-v1.3.18.jar" "%SERVER_DIR%\plugins\" >nul
if %errorLevel% neq 0 (
    echo [错误] 复制插件文件失败
    pause
    exit /b 1
)
echo [完成] 插件文件已复制

:: 2. 创建插件配置目录
echo [2/6] 创建配置目录...
mkdir "%SERVER_DIR%\plugins\GuoZhan" 2>nul
echo [完成] 配置目录已创建

:: 3. 复制配置文件
echo [3/6] 复制配置文件...
copy "%SCRIPT_DIR%config\plugin.yml" "%SERVER_DIR%\plugins\GuoZhan\" >nul
copy "%SCRIPT_DIR%config\message.yml" "%SERVER_DIR%\plugins\GuoZhan\" >nul
copy "%SCRIPT_DIR%config\technology.yml" "%SERVER_DIR%\plugins\GuoZhan\" >nul
copy "%SCRIPT_DIR%config\diplomacy.yml" "%SERVER_DIR%\plugins\GuoZhan\" >nul

:: 复制主配置文件
copy "%SCRIPT_DIR%%CONFIG_SOURCE%" "%SERVER_DIR%\plugins\GuoZhan\config.yml" >nul
if %errorLevel% neq 0 (
    echo [错误] 复制配置文件失败
    pause
    exit /b 1
)
echo [完成] 配置文件已复制

:: 4. 数据库设置
echo [4/6] 数据库设置...
if "%DEPLOY_MODE%"=="1" (
    echo [信息] 生产环境需要配置MySQL数据库
    echo [信息] 请编辑 %SERVER_DIR%\plugins\GuoZhan\config.yml 中的数据库配置
    echo [信息] 然后运行 database\init.sql 脚本初始化数据库
) else (
    echo [信息] 测试环境将使用SQLite数据库，无需额外配置
)
echo [完成] 数据库设置完成

:: 5. 权限配置提示
echo [5/6] 权限配置...
echo [信息] 请根据 examples\permissions.yml 配置玩家权限
echo [信息] 推荐使用LuckPerms权限插件
echo [完成] 权限配置说明已提供

:: 6. 最终检查
echo [6/6] 最终检查...
if exist "%SERVER_DIR%\plugins\GuoZhan-v1.3.18.jar" (
    echo [✓] 插件文件存在
) else (
    echo [✗] 插件文件缺失
)

if exist "%SERVER_DIR%\plugins\GuoZhan\config.yml" (
    echo [✓] 配置文件存在
) else (
    echo [✗] 配置文件缺失
)

echo [完成] 部署检查完成

:: 部署完成
echo.
echo ========================================
echo 部署完成！
echo ========================================
echo.
echo 部署信息:
echo - 插件版本: GuoZhan v1.3.18
echo - 部署类型: %DEPLOY_TYPE%
echo - 服务器目录: %SERVER_DIR%
if exist "%BACKUP_DIR%" (
    echo - 备份目录: %BACKUP_DIR%
)
echo.

:: 下一步提示
echo 下一步操作:
echo.
if "%DEPLOY_MODE%"=="1" (
    echo 1. 配置MySQL数据库:
    echo    - 编辑 plugins\GuoZhan\config.yml 中的数据库配置
    echo    - 运行 database\init.sql 初始化数据库
    echo.
)
echo 2. 配置权限系统:
echo    - 安装LuckPerms权限插件（推荐）
echo    - 参考 examples\permissions.yml 配置权限
echo.
echo 3. 启动服务器:
echo    - 确保使用Folia服务器（不是Paper/Spigot）
echo    - 启动服务器并检查插件是否正常加载
echo.
echo 4. 验证安装:
echo    - 使用 /plugins 命令检查插件状态
echo    - 使用 /u help 测试基础功能
echo.

:: 询问是否查看文档
set /p VIEW_DOCS="是否打开用户文档? (Y/N): "
if /i "%VIEW_DOCS%"=="Y" (
    if exist "%SCRIPT_DIR%docs\README.md" (
        start "" "%SCRIPT_DIR%docs\README.md"
    )
)

:: 询问是否启动服务器
if exist "%FOLIA_JAR%" (
    set /p START_SERVER="是否立即启动服务器? (Y/N): "
    if /i "%START_SERVER%"=="Y" (
        echo [信息] 启动服务器...
        cd /d "%SERVER_DIR%"
        start "Folia Server" java -Xmx4G -Xms2G -jar "%FOLIA_JAR%" nogui
    )
)

echo.
echo 感谢使用 GuoZhan 插件！
echo 如有问题请查看 docs\FAQ.md 或 docs\ADMIN_GUIDE.md
echo.
pause
