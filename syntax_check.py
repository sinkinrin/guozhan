#!/usr/bin/env python3
"""
简单的Kotlin语法检查脚本
检查修改后的文件是否有明显的语法错误
"""

import os
import re

def check_kotlin_file(file_path):
    """检查Kotlin文件的基本语法"""
    errors = []
    
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            lines = content.split('\n')
            
        # 检查基本语法
        for i, line in enumerate(lines, 1):
            line = line.strip()
            
            # 检查未闭合的括号
            if line.count('(') != line.count(')'):
                if not line.endswith(',') and not line.endswith('{') and not line.endswith('->'):
                    errors.append(f"Line {i}: 可能的括号不匹配: {line}")
            
            # 检查未闭合的大括号
            if line.count('{') != line.count('}'):
                if not (line.endswith('{') or line.endswith('}')):
                    errors.append(f"Line {i}: 可能的大括号不匹配: {line}")
            
            # 检查导入语句
            if line.startswith('import ') and not re.match(r'^import\s+[\w.]+$', line):
                errors.append(f"Line {i}: 可能的导入语句错误: {line}")
                
    except Exception as e:
        errors.append(f"读取文件出错: {e}")
    
    return errors

def main():
    """主函数"""
    files_to_check = [
        'src/main/kotlin/cn/lcofficial/guozhan/economy/TaxSystem.kt',
        'src/main/kotlin/cn/lcofficial/guozhan/task/EconomyTasks.kt',
        'src/main/kotlin/cn/lcofficial/guozhan/command/GuozhanCommand.kt'
    ]
    
    all_errors = []
    
    for file_path in files_to_check:
        if os.path.exists(file_path):
            print(f"检查文件: {file_path}")
            errors = check_kotlin_file(file_path)
            if errors:
                all_errors.extend([f"{file_path}: {error}" for error in errors])
            else:
                print(f"  ✓ 语法检查通过")
        else:
            print(f"  ✗ 文件不存在: {file_path}")
    
    if all_errors:
        print("\n发现的问题:")
        for error in all_errors:
            print(f"  ✗ {error}")
        return 1
    else:
        print("\n✓ 所有文件语法检查通过")
        return 0

if __name__ == "__main__":
    exit(main())
