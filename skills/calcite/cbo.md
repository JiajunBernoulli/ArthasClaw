# 使用 Arthas 观察 Calcite 优化规则命中

本文档介绍如何使用 Arthas 观察 CBO 优化过程中规则的命中情况。

## 1. 使用 stack 观察规则命中

`RelOptRuleCall.transformTo()` 是规则匹配成功后会调用的方法，通过观察这个方法可以了解哪些规则被命中。

### 观察所有规则命中

```bash
# 观察 transformTo 方法的调用栈，找出哪个规则被命中
stack org.apache.calcite.rel.rules.RelOptRuleCall transformTo '!javassist' -n 5
```

### 观察特定规则类

```bash
# 观察 FilterIntoJoin 规则
stack org.apache.calcite.rel.rules.CoreRules$FilterIntoJoin transformOn '!javassist'

# 观察 JoinPushThroughJoinRule
stack org.apache.calcite.rel.rules.JoinPushThroughJoinRule onMatch '!javassist'

# 观察 EnumerableJoinRule
stack org.apache.calcite.adapter.enumerable.EnumerableRules$EnumerableJoinRule onMatch '!javassist'
```

### 常用类路径参考

| 规则 | 类路径 |
|------|--------|
| FilterIntoJoin | `org.apache.calcite.rel.rules.CoreRules$FilterIntoJoin` |
| JoinPushThroughJoinRule | `org.apache.calcite.rel.rules.JoinPushThroughJoinRule` |
| EnumerableTableScanRule | `org.apache.calcite.adapter.enumerable.EnumerableRules$EnumerableTableScanRule` |
| EnumerableProjectRule | `org.apache.calcite.adapter.enumerable.EnumerableRules$EnumerableProjectRule` |
| EnumerableFilterRule | `org.apache.calcite.adapter.enumerable.EnumerableRules$EnumerableFilterRule` |
| EnumerableJoinRule | `org.apache.calcite.adapter.enumerable.EnumerableRules$EnumerableJoinRule` |

## 2. 使用 watch 观察 RelNode 特征

通过 `watch` 命令可以观察命中时 RelNode 的具体特征。

### 观察 transformTo 的输入输出

```bash
# 观察 transformTo 方法执行前后的 RelNode
watch org.apache.calcite.rel.rules.RelOptRuleCall transformTo "{params, returnObj}" -x 3
```

### 观察特定规则的 RelNode

```bash
# 观察 FilterIntoJoin 命中时的 RelNode
watch org.apache.calcite.rel.rules.CoreRules$FilterIntoJoin onMatch "{params[0].rel().explain()}" -x 2 -b

# 观察 JoinPushThroughJoinRule 命中时的左右输入
watch org.apache.calcite.rel.rules.JoinPushThroughJoinRule onMatch "{params[0].getLeft().explain(), params[0].getRight().explain()}" -x 2 -b
```

### 使用 explain 观察 RelNode 字符串

`RelNode.explain()` 可以将 RelNode 转换为字符串表示，便于观察计划结构：

```bash
# 在 transformTo 调用前观察当前 RelNode
watch org.apache.calcite.rel.rules.RelOptRuleCall transformTo "@org.apache.calcite.rel.RelNode@toString()" -x 1 -b

# 观察转换后的新 RelNode
watch org.apache.calcite.rel.rules.RelOptRuleCall transformTo "params[0].rel().explain()" -x 2
```

### 完整示例：观察 Join Reorder

```bash
# 观察 JoinPushThroughJoinRule 的完整调用
watch org.apache.calcite.rel.rules.JoinPushThroughJoinRule onMatch \
  "{methodName, params[0].getClass().getSimpleName(), params[0].getLeft().explain(), params[0].getRight().explain()}" \
  -x 3 -b
```

参数说明：
- `-x 3`: 展开层级
- `-b`: 在方法执行前观察
- `params[0]`: 第一个参数（通常是 RelOptRuleCall）

## 3. 常用 watch 表达式

```bash
# 观察当前 RelNode 的类型
params[0].getClass().getSimpleName()

# 观察 RelNode 的字符串表示
params[0].rel().explain()

# 观察 RelNode 的行数估算
params[0].rel().estimateRowCount()

# 观察 RelNode 的费用
params[0].rel().computeSelfCost(#planner)
```

## 4. 过滤条件示例

```bash
# 只观察 Enumerable 开头的规则
stack org.apache.calcite.adapter.enumerable.EnumerableRules onMatch '!javassist' -n 10

# 只观察 Join 相关的规则
watch org.apache.calcite.rel.rules.RelOptRuleCall transformTo "params[0].rel().explain()" 'params[0].rel().getClass().getName().contains("Join")' -x 2
```

## 5. 实用技巧

### 保存观察结果

```bash
# 将结果保存到文件
record > /tmp/arthas-record.txt
```

### 持续观察

```bash
# 持续观察，每秒打印一次
watch org.apache.calcite.rel.rules.RelOptRuleCall transformTo "params[0].rel().explain()" -x 2 -i 1000
```

### 条件触发

```bash
# 当 RelNode 包含特定内容时触发
watch org.apache.calcite.rel.rules.JoinPushThroughJoinRule onMatch "params[0].getLeft().explain()" 'params[0].getLeft().explain().contains("supplier")' -x 2 -b
```

## 6. 快速参考命令

```bash
# 查看所有 transformTo 调用
stack org.apache.calcite.rel.rules.RelOptRuleCall transformTo -n 1

# 查看规则匹配时的 RelNode
watch org.apache.calcite.rel.rules.RelOptRuleCall onMatch "{params[0].rel().explain()}" -x 2

# 查看 RelNode 费用
watch org.apache.calcite.rel.RelNode computeSelfCost "#result=params[0]"
```

