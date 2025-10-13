#!/bin/bash

# GuoZhan 插件快速部署脚本 for Linux
# 版本: 1.0

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_info() {
    echo -e "${BLUE}[信息]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[完成]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[警告]${NC} $1"
}

print_error() {
    echo -e "${RED}[错误]${NC} $1"
}

# 脚本开始
echo "========================================"
echo "   GuoZhan 插件快速部署脚本 v1.0"
echo "========================================"
echo

# 获取脚本目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${SCRIPT_DIR}/backup_$(date +%Y%m%d_%H%M%S)"

print_info "脚本目录: $SCRIPT_DIR"
echo

# 检查必需文件
if [[ ! -f "$SCRIPT_DIR/plugin/GuoZhan-v1.3.18.jar" ]]; then
    print_error "插件文件不存在: $SCRIPT_DIR/plugin/GuoZhan-v1.3.18.jar"
    exit 1
fi

# 获取服务器目录
while true; do
    read -p "请输入Folia服务器目录路径: " SERVER_DIR
    
    if [[ -z "$SERVER_DIR" ]]; then
        print_error "服务器目录不能为空"
        continue
    fi
    
    if [[ ! -d "$SERVER_DIR" ]]; then
        print_error "服务器目录不存在: $SERVER_DIR"
        continue
    fi
    
    if [[ ! -d "$SERVER_DIR/plugins" ]]; then
        print_error "这不是一个有效的Minecraft服务器目录（缺少plugins文件夹）"
        continue
    fi
    
    break
done

print_info "服务器目录: $SERVER_DIR"
echo

# 检查Folia服务器
FOLIA_JAR=$(find "$SERVER_DIR" -name "folia*.jar" -type f | head -n 1)
if [[ -z "$FOLIA_JAR" ]]; then
    print_warning "未检测到Folia服务器JAR文件"
    print_warning "请确保您使用的是Folia服务器而非Paper/Spigot"
    echo
fi

# 选择部署模式
echo "请选择部署模式:"
echo "1. 生产环境部署（推荐）"
echo "2. 测试环境部署"
echo "3. 自定义部署"
echo

while true; do
    read -p "请输入选择 (1-3): " DEPLOY_MODE
    
    case $DEPLOY_MODE in
        1)
            CONFIG_SOURCE="examples/production-config.yml"
            DEPLOY_TYPE="生产环境"
            break
            ;;
        2)
            CONFIG_SOURCE="examples/test-config.yml"
            DEPLOY_TYPE="测试环境"
            break
            ;;
        3)
            CONFIG_SOURCE="config/config.yml"
            DEPLOY_TYPE="自定义"
            break
            ;;
        *)
            print_error "无效的选择"
            ;;
    esac
done

print_info "部署模式: $DEPLOY_TYPE"
echo

# 检查现有安装
if ls "$SERVER_DIR/plugins/GuoZhan"*.jar 1> /dev/null 2>&1; then
    print_warning "检测到现有的GuoZhan插件"
    read -p "是否备份现有配置? (y/N): " BACKUP_CHOICE
    
    if [[ "$BACKUP_CHOICE" =~ ^[Yy]$ ]]; then
        print_info "创建备份目录: $BACKUP_DIR"
        mkdir -p "$BACKUP_DIR"
        
        if [[ -d "$SERVER_DIR/plugins/GuoZhan" ]]; then
            print_info "备份现有配置..."
            cp -r "$SERVER_DIR/plugins/GuoZhan" "$BACKUP_DIR/"
        fi
        
        print_info "备份现有插件JAR..."
        cp "$SERVER_DIR/plugins/GuoZhan"*.jar "$BACKUP_DIR/" 2>/dev/null || true
    fi
    
    print_info "删除现有插件..."
    rm -f "$SERVER_DIR/plugins/GuoZhan"*.jar
    rm -rf "$SERVER_DIR/plugins/GuoZhan" 2>/dev/null || true
fi

# 开始部署
echo "========================================"
echo "开始部署 GuoZhan 插件..."
echo "========================================"
echo

# 1. 复制插件JAR文件
print_info "[1/6] 复制插件文件..."
cp "$SCRIPT_DIR/plugin/GuoZhan-v1.3.18.jar" "$SERVER_DIR/plugins/"
print_success "插件文件已复制"

# 2. 创建插件配置目录
print_info "[2/6] 创建配置目录..."
mkdir -p "$SERVER_DIR/plugins/GuoZhan"
print_success "配置目录已创建"

# 3. 复制配置文件
print_info "[3/6] 复制配置文件..."
cp "$SCRIPT_DIR/config/plugin.yml" "$SERVER_DIR/plugins/GuoZhan/"
cp "$SCRIPT_DIR/config/message.yml" "$SERVER_DIR/plugins/GuoZhan/"
cp "$SCRIPT_DIR/config/technology.yml" "$SERVER_DIR/plugins/GuoZhan/"
cp "$SCRIPT_DIR/config/diplomacy.yml" "$SERVER_DIR/plugins/GuoZhan/"

# 复制主配置文件
cp "$SCRIPT_DIR/$CONFIG_SOURCE" "$SERVER_DIR/plugins/GuoZhan/config.yml"
print_success "配置文件已复制"

# 4. 数据库设置
print_info "[4/6] 数据库设置..."
if [[ "$DEPLOY_MODE" == "1" ]]; then
    print_info "生产环境需要配置MySQL数据库"
    print_info "请编辑 $SERVER_DIR/plugins/GuoZhan/config.yml 中的数据库配置"
    print_info "然后运行 database/init.sql 脚本初始化数据库"
else
    print_info "测试环境将使用SQLite数据库，无需额外配置"
fi
print_success "数据库设置完成"

# 5. 权限配置提示
print_info "[5/6] 权限配置..."
print_info "请根据 examples/permissions.yml 配置玩家权限"
print_info "推荐使用LuckPerms权限插件"
print_success "权限配置说明已提供"

# 6. 设置文件权限
print_info "[6/6] 设置文件权限..."
chmod 644 "$SERVER_DIR/plugins/GuoZhan-v1.3.18.jar"
chmod -R 644 "$SERVER_DIR/plugins/GuoZhan/"
chmod 755 "$SERVER_DIR/plugins/GuoZhan"
print_success "文件权限已设置"

# 最终检查
echo
print_info "最终检查..."
if [[ -f "$SERVER_DIR/plugins/GuoZhan-v1.3.18.jar" ]]; then
    echo -e "${GREEN}[✓]${NC} 插件文件存在"
else
    echo -e "${RED}[✗]${NC} 插件文件缺失"
fi

if [[ -f "$SERVER_DIR/plugins/GuoZhan/config.yml" ]]; then
    echo -e "${GREEN}[✓]${NC} 配置文件存在"
else
    echo -e "${RED}[✗]${NC} 配置文件缺失"
fi

print_success "部署检查完成"

# 部署完成
echo
echo "========================================"
echo "部署完成！"
echo "========================================"
echo
echo "部署信息:"
echo "- 插件版本: GuoZhan v1.3.18"
echo "- 部署类型: $DEPLOY_TYPE"
echo "- 服务器目录: $SERVER_DIR"
if [[ -d "$BACKUP_DIR" ]]; then
    echo "- 备份目录: $BACKUP_DIR"
fi
echo

# 下一步提示
echo "下一步操作:"
echo

if [[ "$DEPLOY_MODE" == "1" ]]; then
    echo "1. 配置MySQL数据库:"
    echo "   - 编辑 plugins/GuoZhan/config.yml 中的数据库配置"
    echo "   - 运行 database/init.sql 初始化数据库"
    echo
fi

echo "2. 配置权限系统:"
echo "   - 安装LuckPerms权限插件（推荐）"
echo "   - 参考 examples/permissions.yml 配置权限"
echo

echo "3. 启动服务器:"
echo "   - 确保使用Folia服务器（不是Paper/Spigot）"
echo "   - 启动服务器并检查插件是否正常加载"
echo

echo "4. 验证安装:"
echo "   - 使用 /plugins 命令检查插件状态"
echo "   - 使用 /u help 测试基础功能"
echo

# 询问是否查看文档
read -p "是否打开用户文档? (y/N): " VIEW_DOCS
if [[ "$VIEW_DOCS" =~ ^[Yy]$ ]]; then
    if [[ -f "$SCRIPT_DIR/docs/README.md" ]]; then
        if command -v xdg-open > /dev/null; then
            xdg-open "$SCRIPT_DIR/docs/README.md"
        elif command -v open > /dev/null; then
            open "$SCRIPT_DIR/docs/README.md"
        else
            print_info "请手动查看文档: $SCRIPT_DIR/docs/README.md"
        fi
    fi
fi

# 询问是否启动服务器
if [[ -n "$FOLIA_JAR" ]]; then
    read -p "是否立即启动服务器? (y/N): " START_SERVER
    if [[ "$START_SERVER" =~ ^[Yy]$ ]]; then
        print_info "启动服务器..."
        cd "$SERVER_DIR"
        
        # 检查Java版本
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
        if [[ "$JAVA_VERSION" -lt 21 ]]; then
            print_warning "检测到Java版本低于21，可能导致兼容性问题"
        fi
        
        # 启动服务器
        java -Xmx4G -Xms2G -jar "$(basename "$FOLIA_JAR")" nogui &
        print_info "服务器已在后台启动"
    fi
fi

echo
echo "感谢使用 GuoZhan 插件！"
echo "如有问题请查看 docs/FAQ.md 或 docs/ADMIN_GUIDE.md"
echo

# 创建快速启动脚本
cat > "$SERVER_DIR/start-guozhan.sh" << 'EOF'
#!/bin/bash
# GuoZhan 服务器快速启动脚本

cd "$(dirname "$0")"

# 检查Java版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 21 ]]; then
    echo "警告: Java版本低于21，可能导致兼容性问题"
fi

# 查找Folia JAR文件
FOLIA_JAR=$(find . -name "folia*.jar" -type f | head -n 1)
if [[ -z "$FOLIA_JAR" ]]; then
    echo "错误: 未找到Folia服务器JAR文件"
    exit 1
fi

echo "启动Folia服务器: $FOLIA_JAR"
java -Xmx4G -Xms2G \
     -XX:+UseG1GC \
     -XX:+ParallelRefProcEnabled \
     -XX:MaxGCPauseMillis=200 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+DisableExplicitGC \
     -XX:+AlwaysPreTouch \
     -XX:G1NewSizePercent=30 \
     -XX:G1MaxNewSizePercent=40 \
     -XX:G1HeapRegionSize=8M \
     -XX:G1ReservePercent=20 \
     -XX:G1HeapWastePercent=5 \
     -XX:G1MixedGCCountTarget=4 \
     -XX:InitiatingHeapOccupancyPercent=15 \
     -XX:G1MixedGCLiveThresholdPercent=90 \
     -XX:G1RSetUpdatingPauseTimePercent=5 \
     -XX:SurvivorRatio=32 \
     -XX:+PerfDisableSharedMem \
     -XX:MaxTenuringThreshold=1 \
     -jar "$FOLIA_JAR" nogui
EOF

chmod +x "$SERVER_DIR/start-guozhan.sh"
print_success "已创建快速启动脚本: $SERVER_DIR/start-guozhan.sh"
