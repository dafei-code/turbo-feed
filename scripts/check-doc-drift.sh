#!/usr/bin/env bash
# 文档漂移检查（M5）：把「文档里说过的话」变成构建期可验证的事实。
#
# 为什么需要它：本仓库的唯一文档来源是 docs/，文档之间靠相对链接串起来。
# 一次「把 ADR 从 docs/architecture/ 迁到 docs/adr/」就能造出一批 404 链接，
# 而 404 不会报错——读者点开是空页面，然后开始不信任整份文档。
# 同类问题还有：README 声称「模块文档与 Maven 模块一一对应」，实际缺了两份。
#
# 四项检查，每项都对应一次真实发生过的漂移：
#   ① 相对链接必须指向真实存在的文件（捕获文件搬迁/重命名）；
#   ② 根 pom 的每个 module 必须有 docs/modules/<module>.md（捕获新增模块忘写文档）；
#   ③ README.md 必须提及每个 Maven 模块（捕获模块树漂移）；
#   ④ docs/adr/ 下每份 ADR 必须登记在 docs/adr/README.md 索引（捕获「写了忘了登记」）。
#
# 用法：scripts/check-doc-drift.sh     （CI 会跑；提交前建议手动跑一次）
#
# ⚠️ 实现注意：错误用临时文件收集，**不要**包进 $( )。macOS 自带 bash 3.2 在命令替换
# 内部解析 case 的 `)` 有缺陷，会报 `syntax error near unexpected token ';;'`——
# 这不是脚本逻辑问题，改结构才能绕开。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

fail=0
err=$(mktemp)
trap 'rm -f "$err"' EXIT

note() { printf '%s\n' "$*"; }
bad()  { printf '  ✗ %s\n' "$*" >> "$err"; }

SEC_BEFORE=0
section()     { SEC_BEFORE=$(wc -c < "$err"); note "— $1 —"; }
section_end() {
    after=$(wc -c < "$err")
    if [ "$after" -gt "$SEC_BEFORE" ]; then
        tail -c +$((SEC_BEFORE + 1)) "$err"
        fail=1
    else
        note "  clean"
    fi
}

# 所有需要检查的 Markdown（排除构建产物与会话目录）
#
# ⚠️ docs/changelog/ 排除在**链接检查**之外，两条理由缺一不可：
#   ① 项目规则「changelog 是历史快照，不回头改」——修链接就等于改历史记录；
#   ② 其中若干条指向仓外的 `turbo-feed-*.html` 设计稿（在仓库上一级目录），
#      本来就不该由仓库内的相对链接可达。
# 这两点决定了：把 changelog 纳入检查的结果只能是「要么永久红、要么破例改历史」，
# 两者都比「明确排除 + 在这里写清原因」更糟。模块文档与 ADR 索引检查不受影响。
md_files() {
    find . -name '*.md' \
        -not -path './.git/*' \
        -not -path '*/target/*' \
        -not -path './.workbuddy/*' \
        -not -path './node_modules/*' \
        -not -path './docs/changelog/*'
}

# ── ① 相对链接可达性 ──────────────────────────────────────────────────────
section "文档相对链接"
md_files | while IFS= read -r md; do
    dir=$(dirname "$md")
    # 提取 ](...) 的目标；跳过外链、邮件、纯锚点
    grep -o -E '\]\([^)]+\)' "$md" 2>/dev/null \
        | sed -E 's/^\]\(//; s/\)$//' \
        | while IFS= read -r target; do
            case "$target" in
                http://*|https://*|mailto:*|\#*) continue ;;
            esac
            p=${target%%#*}          # 去锚点
            p=${p%%\?*}              # 去查询串
            [ -z "$p" ] && continue
            if [ ! -e "$dir/$p" ]; then
                printf '  ✗ %s → %s（目标不存在）\n' "$md" "$target" >> "$err"
            fi
        done
done
section_end

# ── ②③ Maven 模块 ⇄ 模块文档 ⇄ README ────────────────────────────────────
section "模块文档一一对应"
mods=$(grep -o -E '<module>[^<]+</module>' pom.xml | sed -E 's|</?module>||g')
for m in $mods; do
    [ -f "docs/modules/$m.md" ] || bad "docs/modules/$m.md 缺失（根 pom 里有 $m 模块）"
    grep -q "$m" README.md 2>/dev/null || bad "README.md 未提及模块 $m（模块树已漂移）"
done
section_end

# ── ④ ADR 索引完整性 ─────────────────────────────────────────────────────
section "ADR 索引"
for f in docs/adr/[0-9][0-9][0-9][0-9]-*.md; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    grep -q "($base)" docs/adr/README.md 2>/dev/null || bad "ADR 未登记在 docs/adr/README.md 索引: $base"
done
section_end

# ── 汇总 ────────────────────────────────────────────────────────────────
echo
if [ $fail -ne 0 ]; then
    echo "文档漂移检查未通过。修复：补链接 / 补模块文档 / 更新 README 与 ADR 索引。"
    exit 1
fi
echo "文档漂移检查通过。"
