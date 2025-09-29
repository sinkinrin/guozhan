#!/bin/bash
# GuoZhan项目Folia测试运行脚本
# 此脚本使用正确的Folia测试方法，而不是Docker

set -e

echo "========================================"
echo "GuoZhan Folia测试运行脚本"
echo "========================================"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 函数定义
print_step() {
    echo -e "${BLUE}[步骤]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[成功]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[警告]${NC} $1"
}

print_error() {
    echo -e "${RED}[错误]${NC} $1"
}

# 检查Java版本
check_java() {
    print_step "检查Java环境..."
    
    if ! command -v java &> /dev/null; then
        print_error "Java未安装或不在PATH中"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        print_error "需要Java 21或更高版本，当前版本: $JAVA_VERSION"
        exit 1
    fi
    
    print_success "Java版本检查通过: $JAVA_VERSION"
}

# 清理旧的测试环境
cleanup_old_tests() {
    print_step "清理旧的测试环境..."
    
    # 停止可能运行的测试服务器
    pkill -f "folia.*test" || true
    
    # 清理旧的运行目录
    if [ -d "run/folia-test" ]; then
        rm -rf run/folia-test
        print_success "清理旧的Folia测试目录"
    fi
    
    # 清理Gradle缓存
    ./gradlew clean > /dev/null 2>&1
    print_success "清理Gradle构建缓存"
}

# 运行单元测试
run_unit_tests() {
    print_step "运行单元测试（MockBukkit）..."
    
    echo "正在执行单元测试..."
    if ./gradlew test --tests "*unit*" --info; then
        print_success "单元测试通过"
        return 0
    else
        print_error "单元测试失败"
        return 1
    fi
}

# 构建插件
build_plugin() {
    print_step "构建GuoZhan插件..."
    
    if ./gradlew shadowJar; then
        print_success "插件构建成功"
        
        # 检查JAR文件
        if [ -f "build/libs/Guozhan-1.0-SNAPSHOT.jar" ]; then
            JAR_SIZE=$(du -h build/libs/Guozhan-1.0-SNAPSHOT.jar | cut -f1)
            print_success "JAR文件生成: $JAR_SIZE"
        else
            print_error "JAR文件未找到"
            return 1
        fi
    else
        print_error "插件构建失败"
        return 1
    fi
}

# 启动Folia测试服务器
start_folia_server() {
    print_step "启动Folia测试服务器..."
    
    # 复制测试配置
    mkdir -p run/folia-test/plugins/Guozhan
    cp testing/folia-test-config.yml run/folia-test/plugins/Guozhan/config.yml
    
    print_warning "正在启动Folia服务器，这可能需要几分钟..."
    print_warning "服务器启动后，请在另一个终端运行集成测试"
    
    # 启动Folia服务器（后台运行）
    ./gradlew runFolia &
    FOLIA_PID=$!
    
    echo "Folia服务器PID: $FOLIA_PID"
    echo "等待服务器启动..."
    
    # 等待服务器启动
    for i in {1..60}; do
        if [ -f "run/folia-test/logs/latest.log" ]; then
            if grep -q "Done" run/folia-test/logs/latest.log 2>/dev/null; then
                print_success "Folia服务器启动完成"
                return 0
            fi
        fi
        echo -n "."
        sleep 2
    done
    
    print_error "Folia服务器启动超时"
    kill $FOLIA_PID 2>/dev/null || true
    return 1
}

# 运行集成测试
run_integration_tests() {
    print_step "运行Folia集成测试..."
    
    # 检查服务器是否运行
    if ! pgrep -f "folia.*test" > /dev/null; then
        print_error "Folia测试服务器未运行"
        print_warning "请先运行: $0 start-server"
        return 1
    fi
    
    echo "正在执行Folia集成测试..."
    if ./gradlew test --tests "*integration*" --info; then
        print_success "集成测试通过"
        return 0
    else
        print_error "集成测试失败"
        return 1
    fi
}

# 停止Folia服务器
stop_folia_server() {
    print_step "停止Folia测试服务器..."
    
    if pgrep -f "folia.*test" > /dev/null; then
        pkill -f "folia.*test"
        sleep 3
        print_success "Folia服务器已停止"
    else
        print_warning "Folia服务器未运行"
    fi
}

# 生成测试报告
generate_test_report() {
    print_step "生成测试报告..."
    
    if ./gradlew jacocoTestReport; then
        print_success "测试报告生成完成"
        
        if [ -f "build/reports/jacoco/test/html/index.html" ]; then
            echo "测试覆盖率报告: build/reports/jacoco/test/html/index.html"
        fi
        
        if [ -f "build/reports/tests/test/index.html" ]; then
            echo "测试结果报告: build/reports/tests/test/index.html"
        fi
    else
        print_warning "测试报告生成失败"
    fi
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [命令]"
    echo ""
    echo "命令:"
    echo "  unit-tests      - 仅运行单元测试"
    echo "  build          - 构建插件"
    echo "  start-server   - 启动Folia测试服务器"
    echo "  integration    - 运行集成测试（需要服务器运行）"
    echo "  stop-server    - 停止Folia测试服务器"
    echo "  full-test      - 运行完整测试流程"
    echo "  report         - 生成测试报告"
    echo "  clean          - 清理测试环境"
    echo "  help           - 显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0 full-test   # 运行完整测试"
    echo "  $0 start-server && $0 integration  # 分步运行集成测试"
}

# 主函数
main() {
    case "${1:-full-test}" in
        "unit-tests")
            check_java
            cleanup_old_tests
            run_unit_tests
            ;;
        "build")
            check_java
            build_plugin
            ;;
        "start-server")
            check_java
            cleanup_old_tests
            build_plugin
            start_folia_server
            ;;
        "integration")
            check_java
            run_integration_tests
            ;;
        "stop-server")
            stop_folia_server
            ;;
        "full-test")
            check_java
            cleanup_old_tests
            
            echo ""
            echo "=== 第1阶段: 单元测试 ==="
            if ! run_unit_tests; then
                print_error "单元测试失败，停止测试流程"
                exit 1
            fi
            
            echo ""
            echo "=== 第2阶段: 构建插件 ==="
            if ! build_plugin; then
                print_error "插件构建失败，停止测试流程"
                exit 1
            fi
            
            echo ""
            echo "=== 第3阶段: Folia集成测试 ==="
            print_warning "集成测试需要手动运行，因为需要真实的Folia环境"
            print_warning "请运行以下命令："
            print_warning "  1. $0 start-server  # 启动Folia服务器"
            print_warning "  2. $0 integration   # 在另一个终端运行集成测试"
            print_warning "  3. $0 stop-server   # 停止服务器"
            
            echo ""
            echo "=== 第4阶段: 生成报告 ==="
            generate_test_report
            
            print_success "测试流程完成！"
            ;;
        "report")
            generate_test_report
            ;;
        "clean")
            cleanup_old_tests
            stop_folia_server
            ;;
        "help"|"-h"|"--help")
            show_help
            ;;
        *)
            print_error "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"
