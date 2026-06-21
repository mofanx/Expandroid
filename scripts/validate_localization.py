#!/usr/bin/env python3
"""
简单的本地化验证脚本
检查资源文件完整性和硬编码文本
"""

import os
import re
import sys
from pathlib import Path

# Windows CI 默认 cp1252 编码无法输出 emoji，强制 UTF-8
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

def get_culture_name(file):
    """从资源文件名提取 culture 名称"""
    stem = file.stem
    if stem == "AppResources":
        return "default"
    return stem.replace("AppResources", "")


def check_resource_files(project_root):
    """检查资源文件完整性"""
    print("1️⃣  检查资源文件完整性...")
    print("-" * 30)
    
    resources_dir = project_root / "src" / "Resources"
    if not resources_dir.exists():
        print("❌ 资源目录不存在")
        return False
    
    resx_files = list(resources_dir.glob("*.resx"))
    
    print(f"找到 {len(resx_files)} 个资源文件")
    
    if len(resx_files) < 2:
        print("⚠️  资源文件少于2个")
        return False
    
    # 提取所有资源键
    resource_keys = {}
    for file in resx_files:
        culture = get_culture_name(file)
        
        with open(file, 'r', encoding='utf-8') as f:
            content = f.read()
            keys = re.findall(r'data name="([^"]+)"', content)
            resource_keys[culture] = sorted(set(keys))
    
    # 检查键的一致性
    if "default" in resource_keys:
        default_keys = resource_keys["default"]
        for culture, keys in resource_keys.items():
            if culture == "default":
                continue
            
            missing = set(default_keys) - set(keys)
            extra = set(keys) - set(default_keys)
            if missing:
                print(f"❌ 语言 {culture} 缺少 {len(missing)} 个资源键:")
                for key in sorted(missing):
                    print(f"   - {key}")
                return False
            if extra:
                print(f"⚠️  语言 {culture} 多出 {len(extra)} 个资源键:")
                for key in sorted(extra):
                    print(f"   - {key}")
            if not missing and not extra:
                print(f"✅ 语言 {culture} 资源键完整")
    
    return True

def check_hardcoded_text(project_root):
    """检查硬编码文本"""
    print("\n2️⃣  检查硬编码文本...")
    print("-" * 30)
    
    # 技术术语白名单
    technical_terms = {
        'Exception', 'Error', 'Warning', 'Debug', 'Release',
        'string', 'int', 'bool', 'void', 'return', 'if', 'else',
        'class', 'public', 'private', 'static', 'async', 'await',
        'json', 'xml', 'html', 'css', 'sql', 'http', 'https',
        'true', 'false', 'null', 'DateTime', 'string.Empty',
        'Preferences', 'CultureInfo', 'ResourceManager',
        'Expandroid', 'AccessibilityService',
        'Json', 'Yml', 'OK',
        'Roboto', 'Helvetica', 'Arial', 'sans-serif',
    }
    
    # 需要检查的文件
    source_files = []
    src_dir = project_root / "src"
    for pattern in ["*.razor", "*.cs"]:
        source_files.extend(src_dir.rglob(pattern))
    
    # 排除不需要检查的文件
    source_files = [
        f for f in source_files 
        if not any(exclude in str(f) for exclude in ['bin', 'obj', 'Designer.cs'])
    ]
    
    found_issues = False
    
    # 硬编码文本模式
    patterns = [
        r'"([A-Z][a-z]{4,} [a-z]+[^"]*)"',  # "Hello world"
        r'"([A-Z][a-z]{8,})"',                # "Welcome"
        r'"([A-Z][a-z]{3,}!)"',                # "Yes!"
    ]
    
    for file in source_files:
        try:
            with open(file, 'r', encoding='utf-8') as f:
                lines = f.readlines()
                
            for line_num, line in enumerate(lines, 1):
                # 跳过注释行（// 开头，忽略前导空白）
                stripped = line.lstrip()
                if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
                    continue
                # 移除行内注释部分
                if '//' in line:
                    line = line[:line.index('//')]
                # 移除已本地化的调用片段，只检查剩余部分
                cleaned_line = re.sub(r'localizationService\.GetString\s*\([^)]*\)', '', line)
                
                # 检查硬编码模式
                for pattern in patterns:
                    matches = re.findall(pattern, cleaned_line)
                    for match in matches:
                        # 排除技术术语
                        if not any(term.lower() in match.lower() for term in technical_terms):
                            print(f"⚠️  {file.relative_to(project_root)}:{line_num}: \"{match}\"")
                            found_issues = True
        except Exception as e:
            print(f"⚠️  无法读取文件 {file}: {e}")
    
    if not found_issues:
        print("✅ 未发现明显的硬编码文本")
        return True
    else:
        print("\n提示：某些技术术语是正常的，请手动确认上述结果")
        return False

def print_statistics(project_root):
    """打印统计信息"""
    print("\n3️⃣  资源键统计...")
    print("-" * 30)
    
    resources_dir = project_root / "src" / "Resources"
    resx_files = list(resources_dir.glob("*.resx"))
    
    for file in sorted(resx_files):
        culture = get_culture_name(file)
        with open(file, 'r', encoding='utf-8') as f:
            content = f.read()
            count = len(re.findall(r'data name=', content))
            print(f"{culture}: {count} 个资源键")

def main():
    print("🔍 本地化验证工具")
    print("=" * 30)
    print()
    
    # 从脚本位置推导项目根目录
    project_root = Path(__file__).resolve().parent.parent
    os.chdir(project_root)
    
    results = []
    
    # 检查资源文件
    results.append(check_resource_files(project_root))
    
    # 检查硬编码文本
    results.append(check_hardcoded_text(project_root))
    
    # 打印统计
    print_statistics(project_root)
    
    print("\n" + "=" * 30)
    if all(results):
        print("✅ 验证完成，未发现严重问题")
        return 0
    else:
        print("❌ 验证完成，发现需要修复的问题")
        return 1

if __name__ == "__main__":
    sys.exit(main())
