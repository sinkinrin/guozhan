# GuoZhan 插件安装指南

## 🚀 一键安装脚本

### Linux/macOS 安装脚本

```bash
#!/bin/bash
# GuoZhan 插件安装脚本

echo "=== GuoZhan 插件安装向导 ==="

# 检查 Java 版本
echo "检查 Java 版本..."
java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$java_version" -lt 21 ]; then
    echo "❌ 错误: 需要 Java 21 或更高版本，当前版本: $java_version"
    exit 1
fi
echo "✅ Java 版本检查通过: $java_version"

# 选择环境类型
echo ""
echo "请选择环境类型:"
echo "1) 测试环境 (便于测试，自动发放资源)"
echo "2) 正式环境 (生产服务器，平衡游戏)"
read -p "请输入选择 (1 或 2): " env_choice

case $env_choice in
    1)
        config_file="config-test.yml"
        echo "✅ 选择了测试环境配置"
        ;;
    2)
        config_file="config-production.yml"
        echo "✅ 选择了正式环境配置"
        ;;
    *)
        echo "❌ 无效选择，退出安装"
        exit 1
        ;;
esac

# 检查服务器目录
read -p "请输入服务器目录路径: " server_path
if [ ! -d "$server_path" ]; then
    echo "❌ 错误: 服务器目录不存在: $server_path"
    exit 1
fi

if [ ! -d "$server_path/plugins" ]; then
    echo "创建 plugins 目录..."
    mkdir -p "$server_path/plugins"
fi

# 安装插件
echo ""
echo "安装 GuoZhan 插件..."
cp plugin/Guozhan-1.0-SNAPSHOT.jar "$server_path/plugins/"
echo "✅ 插件文件已复制"

# 安装配置文件
echo "安装配置文件..."
mkdir -p "$server_path/plugins/Guozhan"
cp "config/$config_file" "$server_path/plugins/Guozhan/config.yml"
echo "✅ 配置文件已安装: $config_file"

# 检查依赖插件
echo ""
echo "检查依赖插件..."

# 检查 PlaceholderAPI
if [ ! -f "$server_path/plugins/PlaceholderAPI"*.jar ]; then
    echo "⚠️  警告: 未找到 PlaceholderAPI 插件"
    echo "   请从 https://www.spigotmc.org/resources/placeholderapi.6245/ 下载"
fi

# 检查 squaremap
if [ ! -f "$server_path/plugins/squaremap"*.jar ]; then
    echo "⚠️  警告: 未找到 squaremap 插件"
    echo "   请从 https://github.com/jpenilla/squaremap 下载"
fi

echo ""
echo "=== 安装完成 ==="
echo "✅ GuoZhan 插件已安装到: $server_path/plugins/"
echo "✅ 配置文件: $server_path/plugins/Guozhan/config.yml"
echo ""
echo "下一步:"
echo "1. 确保安装了 PlaceholderAPI 和 squaremap 插件"
echo "2. 启动 Folia 服务器"
echo "3. 检查插件是否正常加载"
echo ""
echo "启动命令示例:"
echo "cd $server_path"
echo "java -Xmx4G -Xms2G -XX:+UseG1GC -jar folia-*.jar --nogui"
```

### Windows 安装脚本 (PowerShell)

```powershell
# GuoZhan 插件安装脚本 (Windows)

Write-Host "=== GuoZhan 插件安装向导 ===" -ForegroundColor Green

# 检查 Java 版本
Write-Host "检查 Java 版本..." -ForegroundColor Yellow
try {
    $javaVersion = java -version 2>&1 | Select-String "version" | ForEach-Object { $_.ToString().Split('"')[1] }
    $majorVersion = [int]($javaVersion.Split('.')[0])
    if ($majorVersion -lt 21) {
        Write-Host "❌ 错误: 需要 Java 21 或更高版本，当前版本: $majorVersion" -ForegroundColor Red
        exit 1
    }
    Write-Host "✅ Java 版本检查通过: $majorVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ 错误: 无法检测 Java 版本，请确保 Java 已安装并在 PATH 中" -ForegroundColor Red
    exit 1
}

# 选择环境类型
Write-Host ""
Write-Host "请选择环境类型:" -ForegroundColor Yellow
Write-Host "1) 测试环境 (便于测试，自动发放资源)"
Write-Host "2) 正式环境 (生产服务器，平衡游戏)"
$envChoice = Read-Host "请输入选择 (1 或 2)"

switch ($envChoice) {
    "1" {
        $configFile = "config-test.yml"
        Write-Host "✅ 选择了测试环境配置" -ForegroundColor Green
    }
    "2" {
        $configFile = "config-production.yml"
        Write-Host "✅ 选择了正式环境配置" -ForegroundColor Green
    }
    default {
        Write-Host "❌ 无效选择，退出安装" -ForegroundColor Red
        exit 1
    }
}

# 检查服务器目录
$serverPath = Read-Host "请输入服务器目录路径"
if (-not (Test-Path $serverPath)) {
    Write-Host "❌ 错误: 服务器目录不存在: $serverPath" -ForegroundColor Red
    exit 1
}

$pluginsPath = Join-Path $serverPath "plugins"
if (-not (Test-Path $pluginsPath)) {
    Write-Host "创建 plugins 目录..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $pluginsPath -Force
}

# 安装插件
Write-Host ""
Write-Host "安装 GuoZhan 插件..." -ForegroundColor Yellow
Copy-Item "plugin\Guozhan-1.0-SNAPSHOT.jar" $pluginsPath
Write-Host "✅ 插件文件已复制" -ForegroundColor Green

# 安装配置文件
Write-Host "安装配置文件..." -ForegroundColor Yellow
$guozhanConfigPath = Join-Path $pluginsPath "Guozhan"
if (-not (Test-Path $guozhanConfigPath)) {
    New-Item -ItemType Directory -Path $guozhanConfigPath -Force
}
Copy-Item "config\$configFile" (Join-Path $guozhanConfigPath "config.yml")
Write-Host "✅ 配置文件已安装: $configFile" -ForegroundColor Green

# 检查依赖插件
Write-Host ""
Write-Host "检查依赖插件..." -ForegroundColor Yellow

# 检查 PlaceholderAPI
if (-not (Get-ChildItem $pluginsPath -Name "PlaceholderAPI*.jar")) {
    Write-Host "⚠️  警告: 未找到 PlaceholderAPI 插件" -ForegroundColor Yellow
    Write-Host "   请从 https://www.spigotmc.org/resources/placeholderapi.6245/ 下载"
}

# 检查 squaremap
if (-not (Get-ChildItem $pluginsPath -Name "squaremap*.jar")) {
    Write-Host "⚠️  警告: 未找到 squaremap 插件" -ForegroundColor Yellow
    Write-Host "   请从 https://github.com/jpenilla/squaremap 下载"
}

Write-Host ""
Write-Host "=== 安装完成 ===" -ForegroundColor Green
Write-Host "✅ GuoZhan 插件已安装到: $pluginsPath"
Write-Host "✅ 配置文件: $(Join-Path $guozhanConfigPath 'config.yml')"
Write-Host ""
Write-Host "下一步:" -ForegroundColor Yellow
Write-Host "1. 确保安装了 PlaceholderAPI 和 squaremap 插件"
Write-Host "2. 启动 Folia 服务器"
Write-Host "3. 检查插件是否正常加载"
Write-Host ""
Write-Host "启动命令示例:" -ForegroundColor Cyan
Write-Host "cd $serverPath"
Write-Host "java -Xmx4G -Xms2G -XX:+UseG1GC -jar folia-*.jar --nogui"
```

---

## 📋 手动安装步骤

### 1. 环境准备

1. **安装 Java 21+**
   ```bash
   # Ubuntu/Debian
   sudo apt update
   sudo apt install openjdk-21-jdk
   
   # CentOS/RHEL
   sudo yum install java-21-openjdk
   
   # Windows
   # 下载并安装 Oracle JDK 21 或 OpenJDK 21
   ```

2. **下载 Folia 服务端**
   ```bash
   wget https://api.papermc.io/v2/projects/folia/versions/1.21.5/builds/12/downloads/folia-1.21.5-12.jar
   ```

### 2. 插件安装

1. **复制插件文件**
   ```bash
   cp plugin/Guozhan-1.0-SNAPSHOT.jar /path/to/server/plugins/
   ```

2. **选择并复制配置文件**
   ```bash
   # 测试环境
   cp config/config-test.yml /path/to/server/plugins/Guozhan/config.yml
   
   # 或正式环境
   cp config/config-production.yml /path/to/server/plugins/Guozhan/config.yml
   ```

3. **安装依赖插件**
   - 下载 PlaceholderAPI
   - 下载 squaremap
   - （可选）下载 ProtocolLib

### 3. 启动验证

1. **启动服务器**
   ```bash
   java -Xmx4G -Xms2G -XX:+UseG1GC -jar folia-1.21.5-12.jar --nogui
   ```

2. **检查插件加载**
   ```bash
   # 在服务器控制台执行
   plugins
   
   # 应该看到
   [22:23:58] [Server thread/INFO]: Plugins (4): Guozhan, PlaceholderAPI, ProtocolLib, squaremap
   ```

3. **验证功能**
   ```bash
   # 检查插件状态
   guozhan status
   
   # 检查依赖
   guozhan dependencies
   ```

---

## ⚠️ 常见问题

### 安装失败

1. **"不支持的服务端"**
   - 确保使用 Folia 而非 Paper/Spigot

2. **"Java 版本过低"**
   - 升级到 Java 21 或更高版本

3. **"配置文件错误"**
   - 检查 YAML 语法
   - 使用提供的配置文件模板

### 运行时问题

1. **插件无法加载**
   - 检查依赖插件是否安装
   - 查看服务器日志

2. **功能异常**
   - 检查配置文件设置
   - 确认数据库连接正常

---

## 📞 获取帮助

如果安装过程中遇到问题：

1. **查看文档**
   - `README.md` - 基本说明
   - `docs/ADMIN_GUIDE.md` - 管理员指南
   - `docs/配置差异说明.md` - 配置详解

2. **检查日志**
   - 服务器日志：`logs/latest.log`
   - 查找 `[国战]` 标识的日志

3. **验证环境**
   - Java 版本：`java -version`
   - 服务端类型：确认为 Folia
   - 插件依赖：确认已安装

---

**安装完成后，请参考 `README.md` 了解基本使用方法！** 🎉
