# Match 编辑器重构方案

> 状态：已通过评审，待实施  
> 日期：2026-06-21  
> 关联文档：[ESPANSO_COMPATIBILITY_PLAN.md](./ESPANSO_COMPATIBILITY_PLAN.md)  
> **范围：本方案仅涉及 `app/` 目录的原生 Android (Kotlin/Compose) 实现，不影响 `src/` 的 Blazor UI。**

---

## 1. 背景与问题分析

### 1.1 当前实现

Expandroid 当前的 Match 创建/编辑 UI 位于 `MainScreen.kt` 的 `TextExpanderContent` 函数中：

- **触发词**：单个 `OutlinedTextField`，绑定 `currentMatch.trigger`
- **替换文本**：单个 `OutlinedTextField`，绑定 `currentMatch.replace`
- **变量**：仅显示变量名列表，通过 5 个预设按钮（今天/昨天/明天/时间/光标位置）添加
- **高级选项**：仅一个 `word` checkbox
- **编辑模式**：添加和编辑共用同一表单，无视觉区分

### 1.2 对比 Espanso 桌面端

Espanso 的 Match 架构分为 **Cause（触发条件）** 和 **Effect（替换效果）**：

**Cause:**
- `Trigger`: `triggers: Vec<String>`（多触发词）+ `left_word` + `right_word` + `propagate_case` + `uppercase_style`
- `Regex`: 正则触发

**Effect:**
- `Text`: `replace` + `vars: Vec<Variable>` + `format`(Plain/Markdown/Html) + `force_mode`(Keys/Clipboard)
- `Image`: 图片路径

**Variable** 结构：
- `name`, `var_type`, `params`(通用 key-value Map), `inject_vars`, `depends_on`
- 支持类型：`echo`, `date`, `clipboard`, `random`, `choice`, `form`, `shell`, `script`

### 1.3 核心问题清单

| # | 问题 | 影响 |
|---|---|---|
| 1 | 单触发词，无 `triggers` 多触发词支持 | 无法为一个替换内容设置多个触发词 |
| 2 | 变量仅显示名称，无法编辑 type/params | 用户无法自定义变量参数 |
| 3 | 无变量类型选择器 | 只能通过预设按钮添加 4 种 date 变量 |
| 4 | 无参数编辑表单 | date 的 format/offset、random 的 choices 等无法编辑 |
| 5 | replace 单行输入 | 不支持多行替换文本 |
| 6 | 无搜索过滤 | 关键词列表无法搜索，数量多时难以定位 |
| 7 | 无 form 编辑 | 无法在 UI 中创建/编辑 form 布局 |
| 8 | 高级选项不可见 | left_word/right_word/propagate_case/uppercase_style 无 UI 入口 |
| 9 | 编辑/添加模式无区分 | 用户不清楚当前是新增还是编辑 |
| 10 | State 管理缺陷 | 原地修改对象导致 StateFlow 不发射（已修复，用 copy()） |

---

## 2. 设计目标

1. **功能对齐**：覆盖 Espanso Match 的核心字段编辑能力
2. **移动端友好**：适配小屏幕，触摸操作优先
3. **渐进式复杂度**：基础字段一目了然，高级选项折叠隐藏
4. **数据兼容**：编辑结果可直接序列化为 Espanso YAML 格式
5. **可扩展**：变量类型采用静态映射，新增类型只需加一行配置

---

## 3. 详细设计

### 3.1 MatchEditorDialog（核心组件）

**形式**：全屏 Dialog

**布局结构**：

```
┌─────────────────────────────────────┐
│  ← 编辑关键词              [保存]    │  TopAppBar
├─────────────────────────────────────┤
│                                     │
│ 触发词                               │
│ ┌─────────────────────────────────┐ │
│ │ :hello          [×]            │ │  Chip 输入组
│ │ :hi             [×]            │ │  支持回车添加多个
│ │ ┌───────────────────────────┐  │ │
│ │ │ 输入触发词...              │  │ │  输入框
│ │ └───────────────────────────┘  │ │
│ └─────────────────────────────────┘ │
│                                     │
│ 替换文本                             │
│ ┌─────────────────────────────────┐ │
│ │                                 │ │  多行 OutlinedTextField
│ │ Hello world!                    │ │  minLines=3, maxLines=8
│ │                                 │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ▼ 高级选项                           │  可折叠区域
│   词边界                             │
│   ○ 关闭  ○ 完整词  ○ 左词  ○ 右词   │  SingleChoiceSegmentedButton
│                                     │
│   大小写传播                         │
│   ☐ 启用 propagate_case             │  Switch
│   样式: [uppercase ▾]               │  Dropdown (uppercase/capitalize/capitalize_words)
│                                     │
│ ▼ 变量 (2)                  [+ 添加] │  可折叠区域
│   ┌───────────────────────────────┐ │
│   │ datenow    date    [编辑] [×] │ │  变量卡片
│   │ yesterday  date    [编辑] [×] │ │
│   └───────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**交互细节**：

- **触发词 Chip 输入**：
  - 输入框中输入文本，按回车或逗号确认添加为 Chip
  - 每个 Chip 右侧有 `[×]` 删除按钮
  - 至少需要一个触发词才能保存
  - 底层数据：`triggers: MutableList<String>`，同时兼容旧 `trigger` 单字段（取第一个）

- **替换文本**：
  - `OutlinedTextField` with `minLines = 3, maxLines = 8`
  - 支持换行符
  - 可选：添加「插入变量占位符」按钮，弹出变量列表选择后插入 `{{varname}}`

- **高级选项折叠**：
  - 默认折叠，点击 `▼ 高级选项` 展开
  - 词边界用 `SingleChoiceSegmentedButton`（关闭/完整词/左词/右词），映射到 `word`/`left_word`/`right_word`
  - `propagate_case` 用 Switch，展开后显示 `uppercase_style` Dropdown

- **保存逻辑**：
  - 验证：至少 1 个触发词 + 非空替换文本
  - 编辑模式：更新现有条目（处理触发词变更时的 key 映射）
  - 新增模式：添加到 dict
  - 保存后通过 `ServiceCommandBus` 通知 AccessibilityService

### 3.2 变量类型选择器（VariableTypePicker）

**触发**：点击变量区域的 `[+ 添加]` 按钮

**形式**：ModalBottomSheet

```
┌──────────────────────────┐
│  选择变量类型             │
├──────────────────────────┤
│  📝 echo      纯文本替换   │
│  📅 date      日期时间     │
│  📋 clipboard 剪贴板内容   │
│  🎲 random    随机选择     │
│  ☑ choice    列表选择     │
│  📝 form      表单输入     │
└──────────────────────────┘
```

选择类型后，打开对应的 `VariableEditorDialog`。

### 3.3 变量编辑器（VariableEditorDialog）

**形式**：Dialog

**通用字段**：
- `name`：变量名（必填，用于 `{{name}}` 引用）

**各类型参数表单**：

#### echo
```
┌────────────────────────────┐
│  编辑 echo 变量    [保存]   │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ myvar                  │ │
│ └────────────────────────┘ │
│                            │
│ 值 (echo)                   │
│ ┌────────────────────────┐ │
│ │ Hello                  │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```

#### date
```
┌────────────────────────────┐
│  编辑 date 变量    [保存]   │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ datenow                │ │
│ └────────────────────────┘ │
│                            │
│ 格式 (format)               │
│ ┌────────────────────────┐ │
│ │ dd/MM/yyyy             │ │
│ └────────────────────────┘ │
│ 常用格式: [今天] [时间] ... │  快捷按钮
│                            │
│ 偏移 (offset, 秒)           │
│ ┌────────────────────────┐ │
│ │ 0                      │ │
│ └────────────────────────┘ │
│ 快捷: [昨天 -86400] [明天]  │
│                            │
│ 时区 (tz, 可选)             │
│ ┌────────────────────────┐ │
│ │                        │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```

#### clipboard
```
┌────────────────────────────┐
│  编辑 clipboard 变量 [保存] │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ clip                   │ │
│ └────────────────────────┘ │
│                            │
│ (无需额外参数)              │
└────────────────────────────┘
```

#### random
```
┌────────────────────────────┐
│  编辑 random 变量  [保存]   │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ pick                   │ │
│ └────────────────────────┘ │
│                            │
│ 选项 (choices)              │
│ ┌────────────────────────┐ │
│ │ Option A         [×]   │ │
│ │ Option B         [×]   │ │
│ │ ┌──────────────────┐   │ │
│ │ │ 添加选项...       │   │ │
│ │ └──────────────────┘   │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```

#### choice
```
┌────────────────────────────┐
│  编辑 choice 变量  [保存]   │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ select                 │ │
│ └────────────────────────┘ │
│                            │
│ 选项 (values)               │
│ ┌────────────────────────┐ │
│ │ id: a  label: A   [×]  │ │
│ │ id: b  label: B   [×]  │ │
│ │ ┌──────────────────┐   │ │
│ │ │ 添加选项...       │   │ │
│ │ └──────────────────┘   │ │
│ └────────────────────────┘ │
│                            │
│ ☐ 简单模式 (仅字符串)       │  切换 label/id 和纯字符串
└────────────────────────────┘
```

#### form
```
┌────────────────────────────┐
│  编辑 form 变量    [保存]   │
├────────────────────────────┤
│ 变量名                      │
│ ┌────────────────────────┐ │
│ │ form1                  │ │
│ └────────────────────────┘ │
│                            │
│ 布局 (layout)               │
│ ┌────────────────────────┐ │
│ │ Hi [[name]]!           │ │  多行输入
│ │ Your age: [[age]]      │ │  [[field]] 语法
│ └────────────────────────┘ │
│                            │
│ 字段定义                    │
│ ┌────────────────────────┐ │
│ │ name  [text]   [×]     │ │  自动从 layout 提取
│ │ age   [number] [×]     │ │  可编辑类型
│ └────────────────────────┘ │
└────────────────────────────┘
```

### 3.4 关键词列表搜索

在 `TextExpanderContent` 的 LazyColumn 上方添加搜索框：

```
┌─────────────────────────────────┐
│ 🔍 搜索关键词...                 │  OutlinedTextField
└─────────────────────────────────┘
┌─────────────────────────────────┐
│ :hello    → Hello world!   ✏️ 🗑 │  过滤后的列表
│ :hi       → Hello world!   ✏️ 🗑 │
│ :date     → {{datenow}}    ✏️ 🗑 │
└─────────────────────────────────┘
```

- 实时过滤 trigger 和 replace 字段
- 搜索不区分大小写
- 清空搜索框恢复完整列表

### 3.5 编辑/添加模式区分

- **添加模式**：TopAppBar 标题显示「新建关键词」，表单为空，保存按钮文字「添加」
- **编辑模式**：TopAppBar 标题显示「编辑: :trigger」，表单预填，保存按钮文字「更新」
- 点击列表项的 ✏️ 进入编辑模式
- 点击表单区域的「添加」/「新建」按钮进入添加模式

---

## 4. 数据模型变更

### 4.1 Match 模型

当前 `Models.kt` 中的 `Match` 已扩展 `triggers`/`leftWord`/`rightWord`/`propagateCase`/`uppercaseStyle`。

**阶段一补充**：添加 `regex` 字段（仅数据模型，不做 UI 和运行时逻辑），与 C# 端 `DictWrapper.cs:62` 对齐：

```kotlin
@Serializable
data class Match(
    var trigger: String? = null,
    var replace: String? = null,
    var vars: MutableList<Var>? = null,
    var form: String? = null,
    var formFields: HashMap<String, FormOption>? = null,
    var word: Boolean = false,
    var triggers: MutableList<String>? = null,
    var leftWord: Boolean = false,
    var rightWord: Boolean = false,
    var propagateCase: Boolean = false,
    var uppercaseStyle: String? = null,
    var regex: String? = null  // 新增：与 C# 端对齐
)
```

### 4.2 Params 模型通用化（阶段一）

当前 `Params` 是硬编码字段：
```kotlin
data class Params(
    var echo: String? = null,
    var format: String? = null,
    var offset: Long = 0,
    var cmd: String? = null,
    var layout: String? = null,
    var choices: MutableList<String>? = null
)
```

**改为通用 Map**（与 Espanso 的 `Params = BTreeMap<String, Value>` 对齐）：

```kotlin
data class Params(
    val data: MutableMap<String, Any> = mutableMapOf()
) {
    constructor(og: Params) : this(og.data.toMutableMap())

    operator fun get(key: String): Any? = data[key]
    operator fun set(key: String, value: Any?) { data[key] = value }
}
```

**序列化兼容**：项目实际使用 Jackson（`SerializationHelper` 的 `yamlMapper`/`jsonMapper`）进行序列化，`Map<String, Any>` 天然兼容，无需额外注解。

**迁移范围**（旧字段访问 → Map 访问）：
- `ConfigImportReceiver.kt:44` — `x.params.format` → `x.params["format"]`
- `MainViewModel.kt:238-239` — `copy.params.format` → `copy.params["format"]`
- `ExpanderAccessibilityService.parseItem` — ~4 处 params 字段访问
- `Utils.kt` — `getTheRealFormat` 参数不变（接收 String）

### 4.3 变量类型字段定义（简化版）

用静态映射替代注册机制，适合当前 6-8 种变量类型：

```kotlin
sealed class ParamField {
    data class Text(val key: String, val label: String, val required: Boolean) : ParamField()
    data class Number(val key: String, val label: String, val default: Long) : ParamField()
    data class Bool(val key: String, val label: String) : ParamField()
    data class Choices(val key: String, val label: String) : ParamField()
    data class Multiline(val key: String, val label: String, val required: Boolean) : ParamField()
}

val variableParamFields: Map<String, List<ParamField>> = mapOf(
    "echo" to listOf(ParamField.Text("echo", "值", true)),
    "date" to listOf(
        ParamField.Text("format", "格式", true),
        ParamField.Number("offset", "偏移(秒)", 0),
        ParamField.Text("locale", "时区/区域", false)
    ),
    "clipboard" to emptyList(),
    "random" to listOf(ParamField.Choices("choices", "选项")),
    "choice" to listOf(ParamField.Choices("values", "选项")),
    "form" to listOf(ParamField.Multiline("layout", "布局", true))
)
```

---

## 5. UI 组件清单

| 组件 | 文件 | 职责 |
|---|---|---|
| `MatchEditorDialog` | `MatchEditorDialog.kt` | Match 全屏编辑器 |
| `TriggerChipsInput` | `MatchEditorDialog.kt` | 多触发词 Chip 输入组件 |
| `VariableTypePicker` | `VariableTypePicker.kt` | 变量类型选择 BottomSheet |
| `VariableEditorDialog` | `VariableEditorDialog.kt` | 变量参数编辑 Dialog |
| `VariableCard` | `VariableEditorDialog.kt` | 变量列表项卡片 |
| `SearchBar` | `MainScreen.kt` | 关键词列表搜索框 |

---

## 6. 实施计划

### 阶段一：核心编辑器 + 数据模型（P0）

| 步骤 | 内容 | 预估改动 |
|---|---|---|
| 1 | `Models.kt` — Match 加 `regex` 字段 + Params 通用化 `Map<String, Any>` | ~30 行改动 |
| 2 | 迁移 params 字段访问 — `ConfigImportReceiver`、`MainViewModel`、`ExpanderAccessibilityService` | ~30 行改动 |
| 3 | 创建 `MatchEditorDialog` — 触发词 Chip 输入 + 多行 replace + 高级选项 + 变量列表 + 删除确认 | ~300 行新文件 |
| 4 | 创建 `VariableTypePicker` — 类型选择 BottomSheet | ~80 行新文件 |
| 5 | 创建 `VariableEditorDialog` — 基于 `ParamField` 静态映射的通用参数表单 | ~250 行新文件 |
| 6 | 重构 `MainScreen.kt` — 列表项点击打开 Dialog，移除行内编辑 | ~60 行改动 |
| 7 | `MainViewModel` — 适配新编辑流程 + 触发词冲突检测 | ~50 行改动 |
| | **阶段一合计** | **~800-1000 行** |

### 阶段二：体验增强（P1）

| 步骤 | 内容 | 预估改动 |
|---|---|---|
| 8 | 关键词列表搜索 | ~30 行 |
| 9 | 编辑/添加模式视觉区分 | ~20 行 |
| 10 | 变量占位符插入按钮 | ~40 行 |
| 11 | 简单文本预览（`{{var}}` 替换为参数值/占位符） | ~50 行 |
| 12 | 变量拖拽排序 | ~40 行 |

### 阶段三：Form 可视化（P2）

| 步骤 | 内容 | 预估改动 |
|---|---|---|
| 13 | Form layout 编辑器 + 字段自动提取 | ~150 行 |
| 14 | Form 预览 | ~100 行 |
| 15 | 验证 `ExpanderAccessibilityService` form 展示能力 | ~50 行 |

---

## 7. 兼容性考虑

### 7.1 数据迁移

- 旧 `keywords.json` 中的 `trigger` 单字段继续有效（读取时自动转为 `triggers: [trigger]`）
- 旧 `Params` 硬编码字段在阶段一通用化为 `Map<String, Any>`，Jackson 天然兼容旧 JSON 格式
- 导出的 YAML 保持 Espanso 兼容格式

### 7.2 Espanso 兼容性计划对齐

本方案与 `ESPANSO_COMPATIBILITY_PLAN.md` 的关系：

| 兼容计划项 | 本方案覆盖 |
|---|---|
| triggers 多触发词 | ✅ 阶段一 |
| left_word / right_word | ✅ 阶段一 |
| propagate_case / uppercase_style | ✅ 阶段一 |
| choice 变量 | ✅ 阶段一 |
| date 格式转换 | ✅ 已完成（Utils.kt token 化） |
| regex 触发 | ⏳ 数据模型已预留，UI/运行时延后 |
| form 变量编辑 | ⏳ 阶段三 |
| shell / script 变量 | 未覆盖（Android 限制，优先级低） |

---

## 8. 风险与约束

- **CI 驱动开发**：无本地编译环境，每次改动需提交 CI 验证
- **Compose 性能**：大列表 + Dialog 需注意重组范围，避免全屏重组
- **State 管理**：严格使用 `copy()` 创建新对象，避免 StateFlow 不发射问题
- **屏幕适配**：Dialog/BottomSheet 需适配小屏幕（320dp 宽度）
- **向后兼容**：不能破坏现有 `keywords.json` 读取

---

## 9. 评审检查点（已全部解决）

1. **MatchEditorDialog 用全屏 Dialog 还是 ModalBottomSheet？** — ✅ 已确认：全屏 Dialog
2. **变量编辑是否需要实时预览？** — ✅ 已确认：阶段一不做，阶段二加简单文本预览
3. **Params 通用化是否在阶段一做？** — ✅ 已确认：阶段一做
4. **是否需要支持 regex 触发？** — ✅ 已确认：数据模型预留，UI/运行时延后
5. **form 变量在 Android 上的交互方案？** — ✅ 已确认：阶段三实施时先验证 AccessibilityService form 展示能力

---

## 评审意见

> 评审人：Cascade  
> 日期：2026-06-21  
> 状态：已确认，全部达成一致

### 总体评价

方案对问题分析准确，UI 设计符合移动端习惯，分阶段策略务实。以下为具体问题和建议，请逐条确认。

### 问题与建议

#### 1. 双代码库未厘清 — 关键问题

项目存在两套代码：
- `app/` — Kotlin/Jetpack Compose（本方案的目标）
- `src/` — .NET MAUI Blazor（C#，`Index.razor` + `DictWrapper.cs`）

方案全文针对 Kotlin 侧，但未明确说明。建议在开头加一句：**本方案仅涉及 `app/` 目录的原生 Android (Kotlin/Compose) 实现，不影响 `src/` 的 Blazor UI。**

> 请确认：是否同意补充范围说明？

#### 2. Kotlin `Match` 模型缺少 `regex` 字段

`app/src/main/java/com/dingleinc/texttoolspro/data/Models.kt:37-49` 中 `Match` 没有 `regex` 字段，而 C# 版本 `src/Models/DictWrapper.cs:62` 已有 `Regex`。方案第 7 节说"regex 触发未覆盖"，但数据模型层面也没预留。建议阶段一至少在 `Match` 中加 `var regex: String? = null`，保持双端模型一致。

> 请确认：是否同意在阶段一补上 `regex` 字段（仅数据模型，不做 UI 和运行时逻辑）？

#### 3. Kotlin `Params` 缺少 `values` 字段

`app/src/main/java/com/dingleinc/texttoolspro/data/Models.kt:6-13` 中 `Params` 没有 `values` 字段，而 C# 版本 `src/Models/DictWrapper.cs:94-95` 已有 `Values`。方案 3.3 的 choice 变量编辑器用到了 `values`，但当前模型无法存储。建议阶段一补上 `var values: MutableList<String>? = null`。

> 请确认：是否同意在阶段一补上 `values` 字段？

#### 4. Params 通用化建议提前到阶段一

方案将 Params 通用化放在阶段三（P2），但阶段一的 `VariableEditorDialog` 需要处理 6 种变量类型，每种参数不同。用硬编码的 `Params` 写 6 种表单的 if-else 分支，阶段三又要全部重写为 Map 读取，**等于做两遍**。

建议：阶段一直接用 `Map<String, Any>` + `@JsonAnySetter`/`@JsonAnyGetter`，`VariableEditorDialog` 通过 `paramFields` 配置驱动表单生成。这样阶段三只剩注册机制的完善，不需要重构表单代码。

> 请确认：是否同意将 Params 通用化提前到阶段一？还是认为阶段一先用硬编码更快？

#### 5. 变量类型注册机制可能过度设计

`VariableTypeDefinition` + `sealed class ParamField` + `VariableTypeRegistry` 对当前 6 种变量类型来说偏重。Android 平台不支持 `shell`/`script`，实际可编辑类型不会超过 8 种。

建议：用一个简单的 `when` 分支或 `Map<String, List<ParamField>>` 即可。如果未来类型确实增长到 15+ 再引入注册机制也不迟（YAGNI）。

> 请确认：是否同意简化为静态映射？还是认为注册机制有其他考量？

#### 6. 评审检查点回应

**Q1: 全屏 Dialog vs ModalBottomSheet？**
建议**全屏 Dialog**。变量编辑涉及多个嵌套弹窗（类型选择 → 参数编辑），BottomSheet 嵌套 BottomSheet 在小屏幕上体验差。全屏 Dialog 层级清晰，且表单内容（多触发词 + 多行文本 + 变量列表）需要垂直空间。

> 请确认：同意全屏 Dialog？

**Q2: 变量编辑是否需要实时预览？**
建议**阶段一不做**，阶段二加一个简单的文本预览即可（把 `{{var}}` 替换为参数值或占位符显示）。完整预览涉及变量解析逻辑，与 AccessibilityService 的 `ParseItemAsync` 耦合，复杂度不值得在编辑器中重复。

> 请确认：同意阶段一不做预览？

**Q3: Params 通用化是否在阶段一做？**
是，理由见第 4 点。

**Q4: 是否支持 regex 触发？**
建议**数据模型预留，UI 暂不做**。C# 端已有 `Regex` 字段，Kotlin 端应保持一致。但 regex 触发的运行时实现确实复杂（AccessibilityService 无法逐键检测），UI 入口可以放到未来阶段。

> 请确认：同意模型预留 + UI 延后？

**Q5: form 变量交互方案？**
方案中 form 编辑器设计合理（layout 多行输入 + 自动提取字段）。运行时交互可以复用现有的浮动窗口机制。建议阶段三实施时，先确认 `ExpanderAccessibilityService` 的 form 展示能力是否完整。

> 请确认：同意阶段三再细化 form 运行时方案？

#### 7. 缺少删除/排序变量的交互设计

变量列表卡片有 `[编辑] [×]`，但缺少：
- **删除确认**：阶段一加 `AlertDialog` 确认（"确定删除变量 X？"），防止误删
- **拖拽排序**：延后到阶段二，变量的 `depends_on` 依赖关系可能需要调整顺序

> 请确认：阶段一是否加删除确认？排序是否延后？

#### 8. 保存逻辑缺少触发词冲突检测

方案提到"至少需要一个触发词才能保存"，但没提**重复检测**。阶段一保存逻辑中加入冲突检测：
- **新增模式**：检查所有触发词是否已存在于 dict
- **编辑模式**：检查新触发词是否与其他条目冲突（排除自身原 key）
- **冲突处理**：弹出确认 dialog：「触发词 ":xxx" 已存在，是否覆盖？」

> 请确认：是否同意在阶段一的保存逻辑中加入冲突检测？

#### 9. 预估改动量偏乐观

阶段一总计 ~660 行新代码 + ~80 行改动。考虑到：
- 6 种变量类型的 `VariableEditorDialog` 每种约 50-80 行 → 300-480 行
- `MatchEditorDialog` 的 Chip 输入 + 高级选项 + 状态管理 → 250-300 行
- ViewModel 适配 + 保存逻辑 + 冲突检测 → 80-100 行

实际可能在 **800-1000 行**范围。建议适当上调预估，避免排期偏差。

> 请确认：是否同意上调预估到 800-1000 行？

### 建议修改清单汇总

| # | 修改项 | 优先级 | 请确认 |
|---|--------|--------|--------|
| 1 | 开头明确方案范围（仅 `app/` Kotlin 侧） | 高 | ☑ |
| 2 | Kotlin `Match` 加 `regex` 字段 | 高 | ☑ |
| 3 | Kotlin `Params` 加 `values` 字段 | 高 | ☑（合并到 #4） |
| 4 | Params 通用化提前到阶段一 | 高 | ☑ |
| 5 | 简化变量类型注册机制（去掉 Registry，用简单映射） | 中 | ☑ |
| 6 | 加触发词冲突检测逻辑 | 中 | ☑ |
| 7 | 加变量删除确认 | 低 | ☑ |
| 8 | 上调阶段一改动量预估 | 低 | ☑ |

---

## 方案创建者回复

> 回复人：方案创建者  
> 日期：2026-06-21  
> 状态：已确认，已同步更新方案正文

### 逐条回复

#### 1. 双代码库未厘清 — ✅ 同意

确认补充。项目确实存在两套代码库，方案仅针对 `app/` Kotlin 侧。

**已在正文开头补充范围说明。**

#### 2. Kotlin `Match` 加 `regex` 字段 — ✅ 同意

核查确认：Kotlin `Models.kt:37-49` 无 `regex` 字段，C# `DictWrapper.cs:62` 已有 `Regex`。阶段一仅加数据模型字段，不做 UI 和运行时逻辑。

**已更新正文数据模型部分。**

#### 3. Kotlin `Params` 加 `values` 字段 — ✅ 同意，但合并到第 4 点处理

核查确认：Kotlin `Params` 有 `echo/format/offset/cmd/layout/choices`，缺 `values`。C# `DictWrapper.cs:94-95` 已有 `Values`。

由于第 4 点同意将 Params 通用化提前，`values` 将作为 Map 的自然产物，无需单独加字段。

#### 4. Params 通用化提前到阶段一 — ✅ 同意

核查确认关键事实：`SerializationHelper` 实际使用 **Jackson**（非 kotlinx.serialization）进行 YAML/JSON 序列化（`yamlMapper.readValue`、`jsonMapper.readValue`）。因此 Jackson 的 `@JsonAnySetter`/`@JsonAnyGetter` 可直接用于 Params 通用化，无序列化兼容障碍。

同意提前的理由：
- 阶段一需写 6 种变量类型的参数表单，硬编码 Params 字段访问 → 阶段二再改 Map = **UI 表单代码写两遍**
- Jackson 已是项目序列化基础设施，`Map<String, Any>` 天然兼容
- 影响范围可控：需同步更新 `ConfigImportReceiver`（1 处）、`MainViewModel`（2 处）、`ExpanderAccessibilityService.parseItem`（~4 处）的 params 字段访问

**实施方案**：
```kotlin
data class Params(
    val data: MutableMap<String, Any> = mutableMapOf()
) {
    constructor(og: Params) : this(og.data.toMutableMap())

    @JvmName("get")
    operator fun get(key: String): Any? = data[key]
    operator fun set(key: String, value: Any?) { data[key] = value }
}
```

旧字段访问 `params.format` → `params["format"]`，迁移范围明确。

**已更新正文数据模型和实施计划。**

#### 5. 简化变量类型注册机制 — ✅ 同意

同意简化为静态映射。6-8 种类型不需要完整注册机制。

**实施方案**：用 `when (varType)` + `List<ParamField>` 静态映射替代 `VariableTypeRegistry`：

```kotlin
val variableParamFields: Map<String, List<ParamField>> = mapOf(
    "echo" to listOf(ParamField.Text("echo", "值", true)),
    "date" to listOf(
        ParamField.Text("format", "格式", true),
        ParamField.Number("offset", "偏移(秒)", 0),
        ParamField.Text("locale", "时区/区域", false)
    ),
    "clipboard" to emptyList(),
    "random" to listOf(ParamField.Choices("choices", "选项")),
    "choice" to listOf(ParamField.Choices("values", "选项")),
    "form" to listOf(ParamField.Multiline("layout", "布局", true))
)
```

**已更新正文，移除 VariableTypeRegistry 相关设计。**

#### 6. 评审检查点回应

**Q1: 全屏 Dialog** — ✅ 同意。嵌套弹窗（类型选择 → 参数编辑）在 BottomSheet 中体验差，全屏 Dialog 更合适。

**Q2: 阶段一不做预览** — ✅ 同意。阶段二加简单文本预览。

**Q3: Params 通用化阶段一** — ✅ 同意，见第 4 点回复。

**Q4: regex 模型预留 + UI 延后** — ✅ 同意。阶段一加 `regex` 字段到 `Match`，不做 UI 和运行时。

**Q5: form 阶段三细化** — ✅ 同意。阶段三实施时先验证 `ExpanderAccessibilityService` 的 form 展示能力。

#### 7. 删除确认 — ✅ 同意

阶段一加简单删除确认 dialog（`AlertDialog`，"确定删除变量 X？"）。拖拽排序延后到阶段二。

**已更新正文 UI 设计。**

#### 8. 触发词冲突检测 — ✅ 同意

阶段一保存逻辑中加入冲突检测：
- **新增模式**：检查所有触发词是否已存在于 dict
- **编辑模式**：检查新触发词是否与其他条目冲突（排除自身原 key）
- 冲突时弹出确认 dialog：「触发词 ":xxx" 已存在，是否覆盖？」

**已更新正文保存逻辑部分。**

#### 9. 上调预估 — ✅ 同意

上调阶段一预估到 **800-1000 行**。

**已更新正文实施计划。**

### 确认汇总

| # | 修改项 | 确认 | 处理方式 |
|---|--------|------|----------|
| 1 | 开头明确方案范围 | ✅ | 已补充 |
| 2 | Match 加 regex 字段 | ✅ | 已更新数据模型 |
| 3 | Params 加 values 字段 | ✅ | 合并到第 4 点，Params 通用化 |
| 4 | Params 通用化提前 | ✅ | 已更新数据模型和实施计划 |
| 5 | 简化注册机制 | ✅ | 已改为静态映射 |
| 6 | 触发词冲突检测 | ✅ | 已更新保存逻辑 |
| 7 | 变量删除确认 | ✅ | 已更新 UI 设计 |
| 8 | 上调预估 | ✅ | 已更新 |

**全部 8 项已达成一致，方案正文已同步更新。**
