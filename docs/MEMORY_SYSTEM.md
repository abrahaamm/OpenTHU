# OpenTHU 记忆系统实现说明

本文档说明当前 OpenTHU 记忆系统的实现逻辑，覆盖 Android 本地运行时记忆、Agent-Core 服务端任务记忆、上下文注入、UI 可视化、搜索偏好过滤和调试方法。

## 目标

记忆系统用于让 Agent 在后续对话和工具规划中参考用户近期目标、稳定偏好和历史任务反馈。

当前系统分为两条链路：

1. Android 本地运行时记忆：由 Kotlin 端维护，保存在 Android 本机，用于真实 App 使用时的短期 / 中期 / 长期上下文。
2. Agent-Core 服务端任务记忆：由 Python Agent-Core 维护，记录每次任务规划和执行摘要，用于服务端下次规划时召回历史任务。

两者会在 Agent-Core 请求中合并为 `conversation_context.memory_context`，供规划器、总结器和部分工具使用。

## 数据模型

Android 本地记忆使用 `MemoryRecord`：

```kotlin
data class MemoryRecord(
  val id: String,
  val scope: String,
  val key: String,
  val value: String,
  val weight: Int,
  val updatedAtEpochMs: Long,
)
```

字段含义：

- `id`：记录唯一 ID。
- `scope`：记忆范围，目前支持 `short`、`mid`、`long`。
- `key`：记忆类型，例如 `latest_goal`、`campus_focus`、`manual_preference`。
- `value`：记忆正文。
- `weight`：基础权重，越高越容易进入上下文。
- `updatedAtEpochMs`：更新时间，用于排序和衰减。

## 三类记忆

### 短期记忆

短期记忆用于保存用户最近输入目标。

当前写入规则：

- 用户在对话页发送消息后，`OpenCrayRuntime.planGoalInternal()` 会调用 `recordGoalMemory(displayGoal)`。
- `recordGoalMemory()` 调用 `MemoryManager.updateFromGoal()`。
- `updateFromGoal()` 总是写入一条：

```text
scope = short
key = latest_goal
value = 用户输入
weight = 100
```

用途：

- 让当前几轮对话和任务规划知道用户刚刚关心什么。
- 在规划页“记忆”面板中明显展示最近目标。

### 中期记忆

中期记忆用于保存近期校园事务焦点和动作反馈。

当前写入来源：

- `MemoryManager.updateFromGoal()` 检测到课程、课表、考试、DDL、作业、日历、提醒等关键词时，会写入：

```text
scope = mid
key = campus_focus
weight = 70
```

- 用户对动作进行忽略、稍后提醒等反馈时，`OpenCrayRuntime` 会调用 `memoryManager.recordActionFeedback()`，写入：

```text
scope = mid
key = action_feedback_<feedback>
value = <action id 前缀>:<action title>
weight = ignore 时 75，否则 60
```

用途：

- 帮助 Agent 判断近期任务方向，例如作业、DDL、课程、提醒。
- 让后续动作规划参考用户对动作的接受或忽略反馈。

### 长期记忆

长期记忆用于保存稳定偏好。

写入来源有两类：

1. 用户在设置页“记忆”中手动添加偏好。
2. `MemoryManager.updateFromGoal()` 检测到负向偏好关键词时自动写入。

手动偏好写入为：

```text
scope = long
key = manual_preference
weight = 90
```

自动负向偏好写入为：

```text
scope = long
key = negative_preference
weight = 86
```

用途：

- 参与后续规划和回答。
- 生成搜索排除关键词，例如用户保存“我不喜欢足球比赛，不要给我推送”后，Android 会尝试生成 `exclude_keywords`，传给 Agent-Core 和搜索工具。

## 写入链路

### 用户发送消息

核心路径：

```text
OpenCrayComposeApp
  -> MainViewModel.sendChatMessage()
  -> OpenCrayRuntime.planGoal()
  -> OpenCrayRuntime.planGoalInternal()
  -> chatRepository.sendMessage(displayGoal)
  -> recordGoalMemory(displayGoal)
  -> MemoryManager.updateFromGoal()
  -> runtimeRepository.replaceSnapshot(memoryRecords = updated)
  -> SharedPreferencesRuntimeMemoryStore.save()
```

关键点：

- 写入发生在消息进入运行时后，早于 Agent-Core stream / plan-only / 本地 fallback 分支。
- 因此真实使用时，即使 Agent-Core 网络失败，也应能看到短期记忆写入。

### 设置页手动添加偏好

核心路径：

```text
SettingsScreen.MemorySettings
  -> MainViewModel.addPreference()
  -> OpenCrayRuntime.addManualPreference()
  -> MemoryManager.addManualPreference()
  -> runtimeRepository.replaceSnapshot()
  -> SharedPreferencesRuntimeMemoryStore.save()
```

用户可以在设置页：

- 添加长期偏好。
- 编辑长期偏好。
- 删除长期偏好。
- 清空全部记忆。

## 持久化

Android 本地记忆通过 `SharedPreferencesRuntimeMemoryStore` 持久化。

存储位置：

```text
SharedPreferences name = openthu_runtime_memory
key = memory_records
```

保存格式是 JSON 数组，每条记录包含：

```json
{
  "id": "...",
  "scope": "short",
  "key": "latest_goal",
  "value": "...",
  "weight": 100,
  "updated_at_epoch_ms": 1780000000000
}
```

`FakeRuntimeRepository.replaceSnapshot()` 会比较新旧 `memoryRecords`。如果记忆变化，则调用 `memoryStore.save(next.memoryRecords)`。

## 记忆容量

`MemoryManager` 会对记录去重并截断。

当前规则：

- 去重键：`scope:key:value`。
- 最大保留数：`100`。
- 新记录插入列表头部。

注意：如果同一条 `scope:key:value` 重复写入，会保留最新插入的那条。

## TTL 和权重衰减

Android 端在构建发送给 Agent-Core 的记忆上下文时，会对记忆做 TTL 过滤和权重衰减。

配置读取自 `openthu_settings`：

```text
memory_long_ttl  默认 365 天
memory_mid_ttl   默认 30 天
memory_short_ttl 默认 7 天
memory_half_life 默认 30 天
```

这些设置可以在设置页“记忆”中修改。

排序逻辑在 `OpenCrayRuntime.rankedMemoryEntries()`：

1. 过滤空值。
2. 按 scope TTL 过滤过期记录。
3. 根据半衰期计算衰减权重：

```text
effectiveWeight = weight * 0.5 ^ (ageDays / halfLifeDays)
```

4. 加上 scope 优先级：

```text
long  = 3
mid   = 2
short = 1
score = effectiveWeight + scopeRank * 20
```

5. 按 `score`、`effectiveWeight`、`updatedAtEpochMs` 排序。
6. 截断到当前模型上下文配置允许的 `memoryEntryLimit`。

## 上下文窗口策略

`ContextWindowManager.profileFor(model)` 会根据模型设置上下文预算。

相关字段：

- `memoryReserveTokens`：为记忆预留的 token。
- `memoryEntryLimit`：最多注入多少条记忆。
- `memoryValueCharLimit`：单条记忆 value 最大字符数。
- `memorySummaryCharLimit`：记忆 summary 最大字符数。

示例：

- `moonshot-v1-8k`：最多注入 4 条记忆。
- `moonshot-v1-32k`：最多注入 8 条记忆。
- `gpt-4.1`：最多注入 16 条记忆。

## Android 到 Agent-Core 的记忆注入

Android 在构造 Agent-Core session 时调用：

```text
OpenCrayRuntime.buildGatewaySession()
  -> buildGatewayMemoryContext(profile)
```

注入字段：

```json
{
  "memory_context": {
    "summary": "...",
    "policy": {
      "long_ttl_days": 365,
      "mid_ttl_days": 30,
      "short_ttl_days": 7,
      "half_life_days": 30
    },
    "exclude_keywords": ["足球", "世界杯"],
    "hard_constraints": {
      "exclude_keywords": ["足球", "世界杯"],
      "source": "long_memory"
    },
    "entries": [
      {
        "scope": "long",
        "key": "manual_preference",
        "value": "...",
        "weight": 90,
        "effective_weight": 88,
        "age_days": 0.12,
        "updated_at_epoch_ms": 1780000000000
      }
    ]
  },
  "search_exclude_keywords": ["足球", "世界杯"]
}
```

其中：

- `memory_context.summary` 给规划器和总结器作为自然语言摘要。
- `memory_context.entries` 给 Agent-Core 结构化读取。
- `exclude_keywords` 和 `search_exclude_keywords` 用于搜索过滤。
- `hard_constraints` 表示这些约束优先级高于普通偏好。

## Agent-Core 服务端记忆

Agent-Core 自身也有任务记忆文件，默认：

```text
agent/langgraph/memory_store.json
```

每次任务运行后，`OpenTHULangGraphAgent._memory_update()` 会追加任务摘要：

```json
{
  "ts": "...",
  "user_id": "android_user",
  "task_id": "...",
  "task_status": "completed",
  "objective": "...",
  "entities": [],
  "planned_skill_count": 3,
  "success_count": 2,
  "failure_count": 1,
  "blocked_count": 0
}
```

Agent-Core 在构造 `conversation_context` 时会合并两类记忆：

1. `session.memory_context`：Android 发来的本地记忆。
2. `memory_store.json`：服务端历史任务记忆。

合并逻辑：

```text
_compact_session_memory_context()
_compact_stored_memory_context()
_merge_memory_context()
```

最终放入：

```text
conversation_context.memory_context
```

供 planner、summary 和部分过滤逻辑使用。

## 搜索偏好过滤

当前搜索工具 `agent/langgraph/skills/search_skills.py` 会读取：

- `search_exclude_keywords`
- `exclude_keywords`
- `memory_context.exclude_keywords`
- `memory_context.hard_constraints.exclude_keywords`

然后过滤搜索结果。

过滤范围：

- 标题。
- 摘要。
- URL。

如果命中排除关键词，该结果会被移除，并在返回数据中写入：

```json
{
  "exclude_keywords": ["足球", "世界杯"],
  "warnings": [
    "Filtered 2 search result(s) by memory exclude keywords: 足球, 世界杯"
  ]
}
```

这解决了“长期偏好里明确不喜欢某类内容，但 search 仍然返回相关结果”的问题。

## UI 可视化

### 规划页

规划页顶部统计包含：

```text
任务 / 动作 / 提醒 / 记忆
```

点击“记忆”会展开 `MemoryInspectorSection`。

显示内容：

- 系统记忆总数。
- 短期记忆。
- 中期记忆。
- 长期偏好。

每条记录展示：

- `key`
- `weight`
- 相对更新时间
- `value`

这用于确认真实对话后是否产生了短期记忆。

### 设置页

设置页“记忆”只重点维护长期偏好：

- 添加长期偏好。
- 编辑长期偏好。
- 删除长期偏好。
- 清除全部记忆。
- 修改 TTL 和半衰期。

短期 / 中期记忆主要在规划页查看，不在设置页逐条维护。

## 当前已知风险

### 1. 中文关键词编码风险

当前仓库中部分 Kotlin / Python 文件存在中文字符串 mojibake 的历史问题。

如果 `MemoryManager.kt` 中的 `NEGATIVE_PREFERENCE_KEYWORDS` 或 `CAMPUS_FOCUS_KEYWORDS` 不是正常 UTF-8 中文，则会导致：

- 自动识别负向偏好失败。
- 自动写入 `campus_focus` 失败。
- `memorySearchExcludeKeywords()` 无法从中文长期偏好中提取正确排除词。

判断方法：

- 打开 `app/src/main/java/ai/opencray/app/memory/MemoryManager.kt`。
- 确认关键词是 `不要`、`不喜欢`、`作业`、`课程` 等正常中文。
- 如果看到 `涓嶈...` 这类文本，需要修复编码。

### 2. 记忆不等于强规则

普通 `memory_context.summary` 是给模型参考的软上下文，不保证模型一定遵守。

目前只有 `exclude_keywords` / `hard_constraints` / `search_exclude_keywords` 这类字段会进入工具层过滤，属于更硬的约束。

### 3. 服务端记忆和 Android 本地记忆不是同一个存储

Android 本地记忆保存在 App SharedPreferences。

Agent-Core 服务端任务记忆保存在 `agent/langgraph/memory_store.json`。

二者通过请求 session 临时合并，不会自动互相覆盖。

## 调试方法

### 检查 Android 是否写入短期记忆

步骤：

1. 打开 App。
2. 在对话页发送一条消息。
3. 进入规划页。
4. 点击顶部“记忆”。
5. 查看“短期记忆”是否出现 `latest_goal`。

如果没有：

- 检查 `OpenCrayRuntime.planGoalInternal()` 是否调用 `recordGoalMemory(displayGoal)`。
- 检查 `FakeRuntimeRepository.replaceSnapshot()` 是否触发 `memoryStore.save()`。

### 检查 Agent-Core 是否收到记忆

打开 Agent-Core debug 日志，搜索：

```text
memory_context
search_exclude_keywords
conversation_context
```

也可以在服务端临时打印 `payload.session` 或 planner 输入中的 `conversation_context.memory_context`。

### 检查搜索过滤是否生效

当长期偏好含有“不要足球 / 不喜欢世界杯”等内容时，搜索工具结果中应出现：

```json
"exclude_keywords": [...]
```

如果被过滤，应出现：

```text
Filtered N search result(s) by memory exclude keywords
```

### 检查 TTL 配置

设置页中确认：

- 长期记忆 TTL：默认 365。
- 中期记忆 TTL：默认 30。
- 短期记忆 TTL：默认 7。
- 记忆半衰期：默认 30。

如果短期记忆太快消失，检查 `memory_short_ttl` 是否被设为 0 或很小。

## 推荐后续改进

1. 修复所有 Kotlin / Python 文件中的 mojibake 中文字符串，避免关键词匹配失效。
2. 为 `MemoryManager.updateFromGoal()` 添加单元测试，覆盖短期写入、负向偏好写入、校园焦点写入。
3. 为 `buildGatewayMemoryContext()` 添加测试，验证 TTL、半衰期、排序和 `exclude_keywords`。
4. 将 `exclude_keywords` 提取逻辑从硬编码词表升级为可配置规则。
5. 在规划页记忆面板中增加“复制记忆上下文”按钮，便于调试 Agent-Core 输入。
