#!/bin/bash
# GuoZhan项目快速测试脚本
# 用于快速构建和验证插件

echo "========================================"
echo "GuoZhan项目快速测试脚本"
echo "========================================"

echo
echo "[1/4] 清理旧构建文件..."
./gradlew clean
if [ $? -ne 0 ]; then
    echo "错误: 清理失败"
    exit 1
fi

echo
echo "[2/4] 编译Kotlin代码..."
./gradlew compileKotlin
if [ $? -ne 0 ]; then
    echo "错误: 编译失败"
    exit 1
fi

echo
echo "[3/4] 生成插件JAR文件..."
./gradlew shadowJar
if [ $? -ne 0 ]; then
    echo "错误: JAR生成失败"
    exit 1
fi

echo
echo "[4/4] 验证构建结果..."
if [ -f "build/libs/Guozhan-1.0-SNAPSHOT.jar" ]; then
    echo "✓ JAR文件生成成功"
    ls -la build/libs/Guozhan-1.0-SNAPSHOT.jar
else
    echo "✗ JAR文件未找到"
    exit 1
fi

echo
echo "========================================"
echo "构建完成！"
echo "JAR文件位置: build/libs/Guozhan-1.0-SNAPSHOT.jar"
echo "文件大小: $(du -h build/libs/Guozhan-1.0-SNAPSHOT.jar | cut -f1)"
echo "========================================"

echo
echo "下一步:"
echo "1. 将JAR文件复制到Folia服务器的plugins目录"
echo "2. 配置数据库连接"
echo "3. 重启服务器"
echo "4. 检查插件是否正常加载"

# 验证JAR文件内容
echo
echo "JAR文件内容验证:"
jar tf build/libs/Guozhan-1.0-SNAPSHOT.jar | grep -E "(plugin\.yml|cn/lcofficial/guozhan/Guozhan\.class)" | head -5
