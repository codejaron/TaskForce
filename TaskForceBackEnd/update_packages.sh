#!/bin/bash

# ============================================================
# 更新 package 声明（根据文件实际位置自动推断）
# ============================================================

BASE_PATH="src/main/java"

echo "更新 package 声明..."

find $BASE_PATH -name "*.java" | while read file; do
    # 从路径推断 package
    dir=$(dirname "$file")
    package=$(echo "$dir" | sed "s|$BASE_PATH/||" | tr '/' '.')

    # 获取当前 package 声明
    current=$(grep "^package " "$file" | head -1)

    if [ -n "$current" ]; then
        # macOS 兼容
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s|^package .*|package ${package};|" "$file"
        else
            sed -i "s|^package .*|package ${package};|" "$file"
        fi
        echo "  $file -> $package"
    fi
done

echo "✓ package 声明更新完成"
