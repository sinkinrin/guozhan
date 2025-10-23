#!/usr/bin/env python3
"""
GuoZhan v1.3.51 科技研发系统数据库错误修复 - 语法检查脚本
检查修复后的代码是否存在明显的语法错误
"""

import os
import re
import sys

def check_kotlin_syntax(file_path):
    """检查Kotlin文件的基本语法"""
    errors = []
    warnings = []
    
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
        lines = content.split('\n')
    
    # 检查基本语法
    brace_count = 0
    paren_count = 0
    bracket_count = 0
    
    for i, line in enumerate(lines, 1):
        line = line.strip()
        
        # 跳过注释行
        if line.startswith('//') or line.startswith('*') or line.startswith('/*'):
            continue
            
        # 检查括号匹配
        brace_count += line.count('{') - line.count('}')
        paren_count += line.count('(') - line.count(')')
        bracket_count += line.count('[') - line.count(']')
        
        # 检查常见语法错误
        if line.endswith(',') and not line.startswith('//'):
            if not any(keyword in line for keyword in ['enum', 'data class', 'listOf', 'mapOf', 'arrayOf']):
                warnings.append(f"第{i}行: 可能的多余逗号: {line}")
        
        # 检查未闭合的字符串
        if line.count('"') % 2 != 0 and not line.strip().endswith('\\'):
            errors.append(f"第{i}行: 未闭合的字符串: {line}")
        
        # 检查方法定义
        if 'fun ' in line and not line.strip().startswith('//'):
            if '(' in line and ')' in line:
                # 检查方法参数
                param_part = line[line.find('('):line.rfind(')')+1]
                if param_part.count('(') != param_part.count(')'):
                    errors.append(f"第{i}行: 方法参数括号不匹配: {line}")
    
    # 检查整体括号匹配
    if brace_count != 0:
        errors.append(f"大括号不匹配: {brace_count}")
    if paren_count != 0:
        errors.append(f"圆括号不匹配: {paren_count}")
    if bracket_count != 0:
        errors.append(f"方括号不匹配: {bracket_count}")
    
    return errors, warnings

def check_method_completeness(file_path):
    """检查方法的完整性"""
    errors = []
    
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查新增的方法是否都有实现
    new_methods = [
        'performDatabaseHealthCheck',
        'cleanupOrphanedBackupTables', 
        'validateTableStructure',
        'validateForeignKeyConstraints',
        'attemptAutoRepair',
        'rebuildTechnologyTableSafely',
        'validateTechnologyDatabaseIntegrity',
        'handleBackupTableError',
        'handleForeignKeyError', 
        'handleMissingTableError',
        'startResearchRetry'
    ]
    
    for method in new_methods:
        if f'fun {method}(' in content or f'private fun {method}(' in content:
            # 检查方法是否有实现体
            method_pattern = rf'fun {method}\([^)]*\)[^{{]*\{{'
            if not re.search(method_pattern, content):
                errors.append(f"方法 {method} 声明了但没有实现体")
        else:
            errors.append(f"缺少方法: {method}")
    
    return errors

def check_imports(file_path):
    """检查import语句"""
    warnings = []
    
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
        lines = content.split('\n')
    
    imports = []
    for line in lines:
        if line.strip().startswith('import '):
            imports.append(line.strip())
    
    # 检查是否有重复的import
    seen = set()
    for imp in imports:
        if imp in seen:
            warnings.append(f"重复的import: {imp}")
        seen.add(imp)
    
    return warnings

def main():
    """主函数"""
    print("🔧 GuoZhan v1.3.51 科技研发系统数据库错误修复 - 语法检查")
    print("=" * 60)
    
    # 检查TechnologyManager.kt文件
    tech_manager_path = "src/main/kotlin/cn/lcofficial/guozhan/manager/TechnologyManager.kt"
    
    if not os.path.exists(tech_manager_path):
        print(f"❌ 文件不存在: {tech_manager_path}")
        return 1
    
    print(f"📁 检查文件: {tech_manager_path}")
    print()
    
    # 语法检查
    print("🔍 执行语法检查...")
    syntax_errors, syntax_warnings = check_kotlin_syntax(tech_manager_path)
    
    if syntax_errors:
        print("❌ 发现语法错误:")
        for error in syntax_errors:
            print(f"  - {error}")
    else:
        print("✅ 语法检查通过")
    
    if syntax_warnings:
        print("⚠️ 语法警告:")
        for warning in syntax_warnings:
            print(f"  - {warning}")
    
    print()
    
    # 方法完整性检查
    print("🔍 检查方法完整性...")
    method_errors = check_method_completeness(tech_manager_path)
    
    if method_errors:
        print("❌ 方法完整性错误:")
        for error in method_errors:
            print(f"  - {error}")
    else:
        print("✅ 方法完整性检查通过")
    
    print()
    
    # Import检查
    print("🔍 检查import语句...")
    import_warnings = check_imports(tech_manager_path)
    
    if import_warnings:
        print("⚠️ Import警告:")
        for warning in import_warnings:
            print(f"  - {warning}")
    else:
        print("✅ Import检查通过")
    
    print()
    
    # 总结
    total_errors = len(syntax_errors) + len(method_errors)
    total_warnings = len(syntax_warnings) + len(import_warnings)
    
    print("📊 检查结果总结:")
    print(f"  - 错误: {total_errors}")
    print(f"  - 警告: {total_warnings}")
    
    if total_errors == 0:
        print("✅ 所有检查通过！代码修复成功。")
        return 0
    else:
        print("❌ 发现错误，需要修复。")
        return 1

if __name__ == "__main__":
    sys.exit(main())
