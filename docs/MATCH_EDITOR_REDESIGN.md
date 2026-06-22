# Match 编辑器重构方案

> 状态：已通过评审，待实施（v2 修订版）
> 日期：2026-06-22（v2 修订）
> 关联文档：[ESPANSO_COMPATIBILITY_PLAN.md](./ESPANSO_COMPATIBILITY_PLAN.md)
> 适用范围：`app/` 目录的原生 Android (Kotlin/Jetpack Compose) 实现

---

## 1. 背景与问题分析

### 1.1 当前实现

Expandroid 当前的 Match 创建/编辑 UI 位于 `MainScreen.kt` 的 `TextExpanderContent` 函数中：

- **触发词**：单个 `OutlinedTextField`，绑定 `currentMatch.trigger`
- **替换文本**：单个 `OutlinedTextField`，绑定 `currentMatch.replace`
- **变量**：仅显示变量名列表，通过 5 个预设按钮（今天/昨天/明天/时间/光标位置）添加
- **高级选项**：仅一个 `word` checkbox
- **编辑模式**：添加和编辑共用同一表单，无视觉区分
- **列表**：`LazyColumn` + `MatchCard`，无搜索过滤

### 1.2 对比 Espanso 桌面端

Espanso 的 Match 架构分为 **Cause（触发条件）** 和 **Effect（替换效果）**：

**Cause**（`espanso-config/src/matches/mod.rs:81-87`）：
- `Trigger`: `triggers: Vec<String>`（多触发词）+ `left_word` + `right_word` + `propagate_case` + `uppercase_style`
- `Regex`: 正则触发

**Effect**（`espanso-config/src/matches/group/loader/yaml/mod.rs:213-305`）：
- `Text`: `replace` + `vars: Vec<Variable>` + `format`(Plain/Markdown/Html) + `force_mode`
- `Image`: 图片路径（Android 不支持）

**Variable** 结构（`espanso-config/src/matches/mod.rs:199-206`）：
- `name`, `var_type`, `params: BTreeMap<String, Value>`, `inject_vars`, `depends_on`
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

### 1.4 已完成的基础工作

以下功能在兼容性增强阶段已完成，本方案不再重复设计：

| 功能 | 实现文件 | 状态 |
|---|---|---|
| `Match.regex` 字段 | `Models.kt:57` | ✅ 已完成 |
| `Params.values` 字段 + 自定义反序列化 | `Models.kt:18-19`, `ValuesDeserializer` | ✅ 已完成 |
| regex 触发运行时匹配 | `ExpanderAccessibilityService.kt` regexDict | ✅ 已完成 |
| choice 变量交互选择 UI | `ExpanderAccessibilityService.kt` showChoiceForMatch | ✅ 已完成 |
| YAML 导入/导出 | `MainViewModel.kt` importConfig/exportConfig | ✅ 已完成 |
| 日期格式完整转换 | `Utils.kt` getTheRealFormat/getOriginalFormat | ✅ 已完成 |

---

## 2. 设计目标

1. **功能对齐**：覆盖 Espanso Match 的核心字段编辑能力
2. **移动端优先**：减少操作步骤，触摸操作优先，避免嵌套弹窗过深
3. **渐进式复杂度**：基础字段一目了然，高级选项折叠隐藏
4. **数据兼容**：编辑结果可直接序列化为 Espanso YAML 格式
5. **可扩展**：变量类型采用静态映射，新增类型只需加一行配置

---

## 3. 详细设计

### 3.1 MatchEditorDialog（核心组件）

**形式**：全屏 Dialog（`Dialog` with `DialogProperties(usePlatformDefaultWidth = false)`）

**移动端优化要点**：
- 全屏利用有限屏幕空间，避免 BottomSheet 嵌套问题
- 触发词输入用 Chip 组件，回车/逗号自动添加，减少手动操作
- 替换文本默认 3 行，自动扩展，支持多行
- 高级选项默认折叠，减少认知负担
- 变量列表内联在编辑器中，无需跳转

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
  - **触发词冲突检测**：检查新触发词是否已存在于 dict（编辑模式排除自身原 key），冲突时弹出确认 dialog
  - 保存后通过 `ServiceCommandBus` 通知 AccessibilityService

### 3.2 变量类型选择器（VariableTypePicker）

**触发**：点击变量区域的 `[+ 添加]` 按钮

**形式**：ModalBottomSheet（单层，不嵌套）

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

**形式**：Dialog（非全屏，居中弹窗）

**移动端优化**：每种变量类型只展示必要字段，用快捷按钮减少手动输入

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
│ 常用格式快捷按钮:            │
│ [日期] [时间] [日期时间]     │  一键填入常用格式
│                            │
│ 偏移 (offset, 秒)           │
│ ┌────────────────────────┐ │
│ │ 0                      │ │
│ └────────────────────────┘ │
│ 快捷: [昨天] [明天]          │  一键填入 ±86400
│                            │
│ 时区 (tz, 可选)             │
│ ┌────────────────────────┐ │
│ │                        │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```

> **注**：`format` 字段在 UI 中使用 Java DateTimeFormatter 格式（如 `dd/MM/yyyy`），
> 保存时通过 `Utils.getOriginalFormat()` 转换为 chrono 格式（如 `%d/%m/%Y`）存储，
> 以保持与 Espanso YAML 兼容。导入时通过 `Utils.getTheRealFormat()` 反向转换。
> espanso date 扩展支持 `format`/`offset`/`locale`/`tz` 四个参数
> （`espanso-render/src/extension/date.rs:60-81`）。

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
│ │ Option A         [×]   │ │  简单模式：纯字符串
│ │ Option B         [×]   │ │
│ │ ┌──────────────────┐   │ │
│ │ │ 添加选项...       │   │ │
│ │ └──────────────────┘   │ │
│ └────────────────────────┘ │
│                            │
│ ☐ 高级模式 (id + label)     │  切换为对象数组格式
└────────────────────────────┘
```

> **Espanso 兼容说明**：espanso 的 `choice` 变量 `values` 支持三种格式
> （`espanso-render/src/extension/choice.rs:64-110`）：
> 1. 换行分隔字符串 → 每行同时作为 id 和 label
> 2. 字符串数组 → 每个元素同时作为 id 和 label
> 3. 对象数组 `[{id: "a", label: "A"}]` → id 和 label 独立
>
> 当前 `ValuesDeserializer` 已支持格式 1 和 2。
> 高级模式（格式 3）在阶段一先跳过，阶段二补充。

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

> **注**：espanso v2.1.0+ 使用 `[[field]]` 语法（非 `{{field}}`）。
> form 变量在内部被转换为 `replace` + `vars` 结构（`mod.rs:242-300`），
> `layout` 作为 params 的一个字段，`fields` 作为嵌套 `Params` 对象。

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
- 点击 FAB（浮动按钮）进入添加模式

### 3.6 移动端交互优化设计

| 优化点 | 方案 | 理由 |
|---|---|---|
| **减少弹窗层级** | MatchEditor（全屏）→ VariableTypePicker（BottomSheet）→ VariableEditor（Dialog），最多 3 层 | 避免 BottomSheet 嵌套 BottomSheet |
| **快捷按钮** | date 变量提供「日期」「时间」格式快捷按钮，random/choice 提供示例选项 | 减少手动输入格式字符串的负担 |
| **Chip 自动添加** | 触发词输入回车/逗号自动添加 Chip，无需点按钮 | 手机端减少点击次数 |
| **FAB 添加入口** | 列表页右下角浮动按钮添加新关键词 | 随时可见，无需滚动到顶部 |
| **默认折叠高级选项** | 高级选项默认折叠，大多数用户只需触发词+替换文本 | 减少视觉噪音 |
| **变量删除确认** | 删除变量时弹出 AlertDialog 确认 | 防止误删 |

---

## 4. 数据模型变更

### 4.1 Match 模型

当前 `Models.kt` 中的 `Match` 已包含所有必要字段：

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
    var regex: String? = null  // ✅ 已完成
)
```

**阶段一补充**：添加 `label` 和 `searchTerms` 字段（仅数据模型，用于导入兼容和搜索功能）：

```kotlin
    var label: String? = null,           // espanso YAMLMatch.label
    var searchTerms: MutableList<String>? = null  // espanso YAMLMatch.search_terms
```

> espanso 的 `YAMLMatch` 支持 `label` 和 `search_terms` 字段
> （`espanso-config/src/matches/group/loader/yaml/parse.rs:64,121`）。
> 当前导入时会丢失这两个字段，添加后可保留并在搜索功能中利用。

### 4.2 Params 模型通用化（阶段一）

当前 `Params` 是硬编码字段：
```kotlin
data class Params(
    var echo: String? = null,
    var format: String? = null,
    var offset: Long = 0,
    var cmd: String? = null,
    var layout: String? = null,
    var choices: MutableList<String>? = null,
    var values: MutableList<String>? = null  // ✅ 已完成，含自定义反序列化
)
```

**改为通用 Map**（与 Espanso 的 `Params = BTreeMap<String, Value>` 对齐，`espanso-config/src/matches/mod.rs:221`）：

```kotlin
data class Params(
    val data: MutableMap<String, Any> = mutableMapOf()
) {
    constructor(og: Params) : this(og.data.toMutableMap())

    operator fun get(key: String): Any? = data[key]
    operator fun set(key: String, value: Any?) { data[key] = value }
}
```

**序列化兼容注意事项**：
- 项目使用 Jackson（`SerializationHelper` 的 `yamlMapper`/`jsonMapper`）进行 YAML/JSON 序列化，`Map<String, Any>` 天然兼容
- `Params` 标注了 `@Serializable`（kotlinx.serialization），但 `Map<String, Any>` **不被 kotlinx.serialization 支持**（`Any` 不是 `@Serializable`）。需要移除 `@Serializable` 注解或提供自定义序列化器
- **建议**：先通过 CI 验证 `@Serializable` 移除后 JSON 读写是否正常（Jackson 不依赖 `@Serializable`），确认后再全面迁移

**类型安全访问扩展函数**（避免 NPE）：
```kotlin
fun Params.string(key: String): String? = (data[key] as? String)
fun Params.long(key: String): Long = (data[key] as? Long) ?: (data[key] as? Int)?.toLong() ?: 0L
fun Params.stringList(key: String): MutableList<String>? = (data[key] as? MutableList<String>)
```

**迁移范围**（旧字段访问 → Map 访问）：
- `ConfigImportReceiver.kt:44` — `x.params.format` → `x.params.string("format")`
- `MainViewModel.kt:240-241,295-296,301-302,349-350,362-363` — `copy.params.format` → `copy.params.string("format")`
- `ExpanderAccessibilityService.kt:554,556,571,617,645` — 5 处 params 字段访问

### 4.3 Var 模型补充

当前 `Var` 缺少 `inject_vars` 和 `depends_on` 字段。espanso 的 `YAMLVariable`
（`espanso-config/src/matches/group/loader/yaml/parse.rs:125-139`）包含这两个字段。

**阶段一补充**（仅数据模型，不做 UI）：

```kotlin
@Serializable
data class Var(
    var name: String? = null,
    var type: String? = null,
    var params: Params = Params(),
    var injectVars: Boolean = true,           // espanso 默认 true
    var dependsOn: MutableList<String>? = null
)
```

> `inject_vars` 控制变量值中是否递归解析嵌套的 `{{var}}` 占位符。
> `depends_on` 声明变量依赖关系，影响求值顺序。

### 4.4 变量类型字段定义（简化版）

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
        ParamField.Text("locale", "区域", false),
        ParamField.Text("tz", "时区", false)
    ),
    "clipboard" to emptyList(),
    "random" to listOf(ParamField.Choices("choices", "选项")),
    "choice" to listOf(ParamField.Choices("values", "选项")),
    "form" to listOf(ParamField.Multiline("layout", "布局", true))
)
```

> **与 espanso 源码对齐**：
> - `echo` 扩展读取 `params.get("echo")`（`echo.rs`）
> - `date` 扩展读取 `format`/`offset`/`locale`/`tz`（`date.rs:60-81`）
> - `random` 扩展读取 `params.get("choices")` 为 `Value::Array`（`random.rs:44`）
> - `choice` 扩展读取 `params.get("values")` 为字符串或数组（`choice.rs:64-110`）
> - `form` 扩展读取 `params.get("layout")` 和 `params.get("fields")`（`form.rs:60-70`）

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

| 步骤 | 内容 | 预估改动 | 状态 |
|---|---|---|---|
| 1 | `Models.kt` — Match 加 `label`/`searchTerms` + Var 加 `injectVars`/`dependsOn` | ~20 行改动 | 待实施 |
| 2 | `Models.kt` — Params 通用化 `Map<String, Any>` + 移除 `@Serializable` + 类型安全扩展函数 | ~40 行改动 | 待实施 |
| 3 | 迁移 params 字段访问 — `ConfigImportReceiver`（1 处）、`MainViewModel`（6 处）、`ExpanderAccessibilityService`（5 处） | ~40 行改动 | 待实施 |
| 4 | 创建 `MatchEditorDialog` — 触发词 Chip 输入 + 多行 replace + 高级选项 + 变量列表 + 删除确认 | ~300 行新文件 | 待实施 |
| 5 | 创建 `VariableTypePicker` — 类型选择 BottomSheet | ~80 行新文件 | 待实施 |
| 6 | 创建 `VariableEditorDialog` — 基于 `ParamField` 静态映射的通用参数表单 + 快捷按钮 | ~250 行新文件 | 待实施 |
| 7 | 重构 `MainScreen.kt` — 列表项点击打开 Dialog + FAB 添加入口 + 移除行内编辑 | ~80 行改动 | 待实施 |
| 8 | `MainViewModel` — 适配新编辑流程 + 触发词冲突检测 | ~60 行改动 | 待实施 |
| | **阶段一合计** | **~870 行** | |

### 阶段二：体验增强（P1）

| 步骤 | 内容 | 预估改动 |
|---|---|---|
| 9 | 关键词列表搜索 | ~30 行 |
| 10 | 编辑/添加模式视觉区分 | ~20 行 |
| 11 | 变量占位符插入按钮 | ~40 行 |
| 12 | 简单文本预览（`{{var}}` 替换为参数值/占位符） | ~50 行 |
| 13 | 变量拖拽排序 | ~40 行 |
| 14 | choice 变量高级模式（id + label 对象数组） | ~60 行 |

### 阶段三：Form 可视化（P2）

| 步骤 | 内容 | 预估改动 |
|---|---|---|
| 15 | Form layout 编辑器 + 字段自动提取 | ~150 行 |
| 16 | Form 预览 | ~100 行 |
| 17 | 验证 `ExpanderAccessibilityService` form 展示能力 | ~50 行 |

---

## 7. 兼容性考虑

### 7.1 数据迁移

- 旧 `keywords.json` 中的 `trigger` 单字段继续有效（读取时自动转为 `triggers: [trigger]`）
- 旧 `Params` 硬编码字段在阶段一通用化为 `Map<String, Any>`，Jackson 天然兼容旧 JSON 格式
- 导出的 YAML 保持 Espanso 兼容格式

### 7.2 Espanso 兼容性计划对齐

本方案与 `ESPANSO_COMPATIBILITY_PLAN.md` 的关系：

| 兼容计划项 | 本方案覆盖 | 当前状态 |
|---|---|---|
| triggers 多触发词 | ✅ 阶段一 | 数据模型已有，待 UI |
| left_word / right_word | ✅ 阶段一 | 数据模型已有，待 UI |
| propagate_case / uppercase_style | ✅ 阶段一 | 数据模型已有，待 UI |
| choice 变量 | ✅ 阶段一 | 运行时已完成，待编辑器 UI |
| date 格式转换 | ✅ 已完成 | `Utils.kt` 已完整实现 |
| regex 触发 | ✅ 已完成 | 数据模型 + 运行时匹配已完成 |
| form 变量编辑 | ⏳ 阶段三 | 运行时已有，编辑器待实施 |
| YAML 导入/导出 | ✅ 已完成 | `MainViewModel` 已实现 |
| shell / script 变量 | 未覆盖 | Android 限制，优先级低 |

---

## 8. 风险与约束

- **CI 驱动开发**：无本地编译环境，每次改动需提交 CI 验证
- **`@Serializable` 兼容性**：Params 通用化为 `Map<String, Any>` 后，kotlinx.serialization 不支持 `Any` 类型。需移除 `@Serializable` 注解并验证 Jackson JSON 读写正常
- **Compose 性能**：大列表 + Dialog 需注意重组范围，避免全屏重组
- **State 管理**：严格使用 `copy()` 创建新对象，避免 StateFlow 不发射问题
- **屏幕适配**：Dialog/BottomSheet 需适配小屏幕（320dp 宽度）
- **向后兼容**：不能破坏现有 `keywords.json` 读取

---

## 9. 评审检查点（已全部解决）

1. **MatchEditorDialog 用全屏 Dialog 还是 ModalBottomSheet？** — ✅ 已确认：全屏 Dialog
2. **变量编辑是否需要实时预览？** — ✅ 已确认：阶段一不做，阶段二加简单文本预览
3. **Params 通用化是否在阶段一做？** — ✅ 已确认：阶段一做
4. **是否需要支持 regex 触发？** — ✅ 已确认：已完成（数据模型 + 运行时匹配）
5. **form 变量在 Android 上的交互方案？** — ✅ 已确认：阶段三实施时先验证 AccessibilityService form 展示能力

---

## v1 评审记录（归档）

> v1 评审于 2026-06-21 完成，共 8 项修改建议，全部达成一致。
> v1 评审意见和回复已归档，以下为 v2 修订摘要。

### v2 修订内容（2026-06-22）

基于 espanso 源码对照评估和当前项目实际状态，v2 做了以下修订：

| # | 修订项 | 说明 |
|---|--------|------|
| 1 | 移除所有 `src/` C# 引用 | 方案仅针对 `app/` Kotlin 侧，不再引用 C# 代码 |
| 2 | 更新 `regex`/`values` 状态 | 标记为已完成（数据模型 + 运行时 + 导入/导出） |
| 3 | 新增 §1.4 已完成基础工作 | 列出 6 项已完成功能，避免重复开发 |
| 4 | 新增 §3.6 移动端交互优化设计 | FAB、快捷按钮、Chip 自动添加、默认折叠等 |
| 5 | `Match` 模型补充 `label`/`searchTerms` | 与 espanso `YAMLMatch` 对齐（`parse.rs:64,121`） |
| 6 | `Var` 模型补充 `injectVars`/`dependsOn` | 与 espanso `YAMLVariable` 对齐（`parse.rs:135,138`） |
| 7 | date 参数补充 `tz` 字段 | espanso date 扩展支持 `tz`（`date.rs:75`） |
| 8 | choice 编辑器改为简单模式优先 | 默认纯字符串，高级模式（id+label）延后到阶段二 |
| 9 | Params 通用化增加 `@Serializable` 风险说明 | `Map<String, Any>` 不被 kotlinx.serialization 支持 |
| 10 | Params 通用化增加类型安全扩展函数 | `string()`/`long()`/`stringList()` 避免 NPE |
| 11 | 迁移范围精确到行号 | ConfigImportReceiver 1 处、MainViewModel 6 处、Service 5 处 |
| 12 | 阶段二新增 choice 高级模式步骤 | 对象数组格式 `[{id,label}]` 支持 |
| 13 | 兼容性对齐表更新当前状态 | regex/YAML 导入导出标记为已完成 |
| 14 | 风险约束新增 `@Serializable` 兼容性 | Params 通用化的构建阻塞风险 |
| 15 | 添加 espanso 源码引用 | 所有设计决策标注对应 espanso 源码位置 |
