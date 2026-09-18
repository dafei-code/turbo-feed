# 0029 信用扣分/恢复等级滞后一档修正（P0-8）

- 日期：2026-09-17
- 范围：`tf-gateway`（`AccountCreditRepository`）
- 关联：[内容安全后续方案](../../turbo-feed-内容安全后续方案.html) 阶段一 · P0-8

## 1. 动机

`AccountCreditRepository.deduct()` / `restore()` 的 `UPDATE` 语句里，等级 `level`
用 `CASE` 表达式在 `credit_score` 赋值**之后**计算。MySQL 的 `UPDATE` 按列从左到右求值，
`CASE` 读到的是**已扣分/已加分的当前行值**（新值），于是判级基准被错用：

- 实测：扣分前 100 分（L2），扣 20 后应得 80 分（仍 ≥80 → L2），
  但旧实现先 `credit_score = GREATEST(0, 100-20) = 80` 再 `CASE WHEN 80 < 60 ... WHEN 80 < 80 ...`
  → 落入 `ELSE 1`（L1），**等级滞后一档**。

后果：用户信用等级比真实分低一档，流量池分配、先审后放判定被压低，且不可自愈
（下次扣分仍在错误基准上叠加）。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `AccountCreditRepository.java` | `deduct()`：`level = CASE` 从分数赋值**之前**引用 `credit_score - 20` 判级；`restore()`：`level = CASE` 前置引用 `LEAST(100, credit_score + 10)`。注释同步（原"已知缺陷"改为"已修正 changelog 0029"）|

判级规则（`level`：0=<60，1=60~79，2=≥80）保持不变，仅修正求值顺序使其基于
**原始分 ± 变动量**而非变动后的新值。

## 3. 验证

- 逻辑核对：以 100 分扣 20 → `CASE WHEN 100-20<60(否) WHEN 100-20<80(否) → ELSE 2`（L2），正确。
- 边界：60 分扣 20 → `CASE WHEN 40<60 → 0`（L0），正确；80 分加 10 → `LEAST(100,90)=90 ≥80 → 2`（L2），正确。
- 编译：`mvn -pl tf-gateway -am compile` 通过（exit 0）。

## 4. 遗留

无。建议后续补单测覆盖「临界分 ±变动」的等级边界（60/80 上下各一档）。
