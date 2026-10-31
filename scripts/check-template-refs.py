#!/usr/bin/env python3
"""模板标识符静态检查。

W9 踩过一个坑：聊天页模板写了 `members.length`，而 setup 里只有 `chat.members`。
Vue 渲染函数在 `undefined.length` 上抛 TypeError 后不再重渲染，页面永久冻结在
第 0 帧——不报错、不白屏，只是「状态全对、画面全错」。

这类错误的共同点是：**模板引用了 setup 里不存在的名字**。它在编译期合法
（模板表达式是运行时代码），在任何 lint 默认规则下也不报，只有真的渲染才炸。
所以单独写这一道检查：把每个 .vue 的模板标识符与 <script setup> 的顶层声明
对照，差集就是嫌疑名单。

用法：python scripts/check-template-refs.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGETS = [ROOT / "frontend-user/src", ROOT / "frontend-admin/src"]

# JS 内置与模板里合法出现但不来自 setup 的名字
BUILTIN = {
    "true", "false", "null", "undefined", "new", "typeof", "instanceof", "in",
    "of", "return", "if", "else", "function", "await", "async", "delete",
    "void", "this", "Math", "Date", "JSON", "Object", "Array", "String",
    "Number", "Boolean", "parseInt", "parseFloat", "isNaN", "encodeURIComponent",
    "decodeURIComponent", "console", "window", "document", "NaN", "Infinity",
    "RegExp", "Promise", "Set", "Map", "Error", "Intl", "crypto",
    # Vue 模板全局
    "$event", "$refs", "$slots", "$props", "$attrs", "$emit", "key", "index",
}

# 属性名不是变量：这些 attribute 的值里出现的东西才需要检查
BIND_ATTRS = re.compile(
    r'(?:^|\s)(?::|v-bind:|@|v-on:|v-if|v-else-if|v-show|v-model(?::[\w-]+)?|'
    r'v-for|v-html|v-text|slot-scope|#[\w-]+)\s*=?\s*"([^"]*)"', re.M)


def strip_strings(expr: str) -> str:
    """去掉字符串字面量与模板字符串，避免把文案里的单词当变量。"""
    expr = re.sub(r"'[^']*'", "''", expr)
    expr = re.sub(r'"[^"]*"', '""', expr)
    expr = re.sub(r"`[^`]*`", "``", expr)
    return expr


def template_idents(tpl: str) -> set:
    """模板里出现的所有标识符（已剔除 v-for 局部变量与 slot props）。"""
    chunks = []
    # {{ }} 插值
    chunks += re.findall(r"\{\{(.*?)\}\}", tpl, re.S)
    # 所有绑定/指令属性值
    chunks += BIND_ATTRS.findall(tpl)

    local = set()
    # v-for="(a, b) in c" / v-for="a in c"
    for m in re.finditer(r'v-for\s*=\s*"\(?\s*([^")]+?)\s*\)?\s+(?:in|of)\s+([^"]+)"', tpl):
        head = m.group(1)
        for part in head.split(","):
            part = part.strip()
            if part:
                local.add(part)
        chunks.append(m.group(2))
    # slot props: v-slot="{ row, $index }" 或 #default="{ row }"
    for m in re.finditer(r'(?:v-slot(?::[\w-]+)?|#[\w-]+)\s*=\s*"\{([^}]*)\}"', tpl):
        for part in m.group(1).split(","):
            name = part.split(":")[0].strip()
            if name:
                local.add(name)

    found = set()
    for c in chunks:
        c = strip_strings(c)
        # 标识符：前面不是 . 也不是字母数字（排除属性访问 obj.foo 里的 foo）
        for ident in re.findall(r"(?<![.\w$])([A-Za-z_$][\w$]*)", c):
            found.add(ident)
    return {x for x in found - local - BUILTIN}


def script_decls(script: str) -> set:
    """<script setup> 里对模板可见的顶层名字。"""
    names = set()
    names |= set(re.findall(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)", script))
    names |= set(re.findall(r"\bfunction\s+([A-Za-z_$][\w$]*)", script))
    names |= set(re.findall(r"\bclass\s+([A-Za-z_$][\w$]*)", script))

    # defineProps 声明的键：<script setup> 里 props 在模板中直接可用，
    # 不需要（也常常没有）从 defineProps 解构出来。
    for m in re.finditer(r"defineProps\s*\(\s*\{(.*?)\}\s*\)", script, re.S):
        for key in re.findall(r"^\s*([A-Za-z_$][\w$]*)\s*:", m.group(1), re.M):
            names.add(key)

    # import 进来的名字：import { a, b as c } from ... / import d from ...
    for m in re.finditer(r"import\s+([^;]+?)\s+from\s+['\"]", script, re.S):
        body = m.group(1)
        for braced in re.findall(r"\{([^}]*)\}", body):
            for piece in braced.split(","):
                piece = piece.strip()
                if not piece:
                    continue
                alias = re.split(r"\s+as\s+", piece)
                names.add(alias[-1].strip())
    return names


def check_file(path: Path) -> list:
    src = path.read_text(encoding="utf-8")
    tpl_m = re.search(r"<template>(.*)</template>", src, re.S)
    if not tpl_m:
        return []
    # 只取最后一个 <script setup>（<script> 与 <script setup> 同时存在时以 setup 为准）
    setup = ""
    for m in re.finditer(r"<script\s+setup[^>]*>(.*?)</script>", src, re.S):
        setup = m.group(1)
    if not setup:
        return []

    idents = template_idents(tpl_m.group(1))
    decls = script_decls(setup)

    # 模板里以 kebab-case 引用 props 是允许的；camelCase 声明也算数
    normalized = set(decls)
    for d in list(decls):
        normalized.add(d.replace("_", "-"))

    missing = sorted(i for i in idents if i not in normalized)
    return [(path, i) for i in missing]


def main() -> int:
    problems = []
    files = 0
    for base in TARGETS:
        for path in sorted(base.rglob("*.vue")):
            files += 1
            problems += check_file(path)

    print(f"检查 {files} 个 .vue 文件\n")
    if not problems:
        print("✅ 模板引用的标识符全部在 setup 中有声明")
        return 0

    print(f"⚠️  发现 {len(problems)} 处模板引用了 setup 中不存在的名字：\n")
    for path, ident in problems:
        rel = path.relative_to(ROOT)
        print(f"  {rel}: `{ident}`")
    print("\n注意：v-for 局部变量与 slot props 已自动排除；")
    print("      若某条确认是误报（如全局注册组件名），请在上方名单核对后忽略。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
