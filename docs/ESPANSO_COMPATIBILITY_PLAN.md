# Expandroid ↔ Espanso 兼容性改进实施计划

## 项目背景

Expandroid 是 Android 端的 espanso 兼容文本扩展器，通过 Android AccessibilityService 实现文本检测与替换。本计划旨在提升 Expandroid 与 espanso 桌面版配置的双向兼容性，使用户能在桌面和移动端无缝共享配置。

**当前技术栈**：.NET 9 MAUI + Blazor WebView + MudBlazor + YamlDotNet  
**Espanso 技术栈**：Rust workspace（15 个 crate），YAML 配置，JSON Schema 校验  
**目标**：最大化配置兼容性，同时尊重 Android 平台限制

---

## 现状分析

### 已支持的 Espanso 特性

| 特性 | 状态 | 说明 |
|------|------|------|
| `trigger` (单个) | ✅ | 完全支持 |
| `replace` | ✅ | 完全支持 |
| `vars` — `echo` | ✅ | 完全支持 |
| `vars` — `date` | ✅ 部分 | 有格式转换 `GetTheRealFormat`，但覆盖不完整 |
| `vars` — `clipboard` | ✅ | 完全支持 |
| `vars` — `random` | ✅ | 完全支持 |
| `form` + `form_fields` | ✅ 部分 | 支持 text/choice/list 字段，缺少 multiline |
| `global_vars` | ✅ | 完全支持 |
| `word` | ✅ | 完全支持 |

### 未支持的 Espanso 特性

| 特性 | 影响程度 | 平台限制 | 说明 |
|------|----------|----------|------|
| `triggers` (多触发词) | 高 | 无 | 模型只有单个 `Trigger`，导入时丢失 |
| `imports` (跨文件引用) | 高 | 无 | 导入时完全忽略 |
| `left_word` / `right_word` | 中 | 无 | 只有 `word` 布尔值，缺少精细词边界控制 |
| `choice` 变量类型 | 中 | 无 | form 中已支持 choice 字段，但独立变量未实现 |
| `propagate_case` / `uppercase_style` | 中 | 无 | 大小写传播未实现 |
| `regex` 触发器 | 中 | 平台限制 | AccessibilityService 基于文本变化检测，正则匹配可行但复杂 |
| `markdown` / `html` | 低 | 无 | 替换格式变体 |
| `image_path` | 低 | 平台限制 | Android 无障碍服务不支持图片注入 |
| `shell` / `script` 变量 | 低 | 平台限制 | Android 无桌面 shell 环境 |
| `label` / `search_terms` | 低 | 无 | 搜索 UI 相关，当前无搜索功能 |
| `force_clipboard` / `force_mode` | 低 | 平台限制 | Android 固定用 SetText，无注入模式选择 |
| `filter_title` / `filter_exec` | 低 | 平台限制 | App 特定配置，Android 可通过 packageName 实现 |
| `paragraph` | 低 | 无 | markdown 段落控制 |

### 日期格式转换问题

`Utils.GetTheRealFormat` 存在以下缺陷：

1. **替换顺序问题**：`%m` → `MM` 和 `%M` → `mm` 的替换会互相干扰（`%M` 替换后 `mm` 中的 `m` 可能被后续规则匹配）
2. **缺少格式说明符**：`%N`（纳秒）、`%Z`（时区名）、`%z`（时区偏移）、`%C`（世纪）、`%G`/`%V`/`%u`（ISO 周日期）等未覆盖
3. **`%j` 和 `%w` 直接求值**：`%j`（年中第几天）和 `%w`（星期几）在转换时直接取当前值写入格式字符串，而非保留为 .NET 的 `dayofyear` 等动态格式
4. **`GetOriginalFormat` 同样存在顺序问题**：导出时 `yyyy` → `%Y` 会先于 `dd` → `%d` 执行，但 `MMMM` → `%B` 会先于 `MMM` → `%b`，顺序依赖字符串长度需要从长到短替换

---

## 实施计划

> **实施状态**：所有阶段均已实现。以下标注各任务的实际完成情况。

### 阶段一：模型扩展与导入改进（高优先级）

#### 1.1 扩展 `Match` 模型支持 `triggers` 多触发词 ✅ 已完成

**目标**：导入 espanso YAML 时，`triggers: [":a", ":b"]` 能正确展开为多个条目。

**改动文件**：
- `src/Models/DictWrapper.cs` — 添加 `Triggers` 字段 ✅
- `src/Platforms/Android/ConfigImportReceiver.cs` — 导入时将 `triggers` 展开为多个 Match ✅
- `src/Pages/Index.razor` — 导入逻辑同步处理 ✅

**实施方案**：
```
DictWrapper.Match 添加:
  [YamlMember("triggers")]
  public List<string> Triggers { get; set; }

导入时:
  if (match.Triggers != null && match.Triggers.Count > 0)
      foreach (var t in match.Triggers)
          dict.Add(t, cloneOfMatch with Trigger = t);
  else if (match.Trigger != null)
      dict.Add(match.Trigger, match);
```

**验证**：导入包含 `triggers` 的 YAML 文件，确认每个触发词都能正确触发扩展。

#### 1.2 支持 `imports` 跨文件引用 ✅ 已完成（ConfigImportReceiver 除外）

**目标**：导入 espanso 配置时，递归解析 `imports` 字段引用的其他 YAML 文件。

**改动文件**：
- `src/Platforms/Android/ConfigImportReceiver.cs` — ⚠️ 未处理（通过 Intent 字符串接收配置，无文件路径上下文，属合理限制）
- `src/Pages/Index.razor` — 递归解析 imports ✅

**实施方案**：
- 导入单个 YAML 文件时，读取 `imports` 列表
- 对于每个 import 路径，尝试在同目录下解析并合并 matches 和 global_vars
- 设置最大递归深度（如 5）防止循环引用
- import 路径支持相对路径（相对于当前文件目录）

**限制**：Android 文件系统权限限制下，只能解析应用可访问的路径。

#### 1.3 完善 `left_word` / `right_word` 支持 ✅ 已完成

**目标**：支持 espanso 的精细词边界匹配。

**改动文件**：
- `src/Models/DictWrapper.cs` — 添加 `LeftWord` / `RightWord` 字段 ✅
- `src/Platforms/Android/Services/ExpanderAccessibilityService.cs` — 修改 `HandleTextExpansionAsync` 中的词边界检查 ✅（使用 `HashSet<char>` 单字符检查）

**实施方案**：
```
Match 模型添加:
  public bool LeftWord { get; set; } = false;
  public bool RightWord { get; set; } = false;

HandleTextExpansionAsync 中替换现有 word 逻辑:
  if (match.LeftWord || match.RightWord || match.Word)
      // left_word: 检查触发词前一个字符是否为分隔符
      // right_word: 检查触发词后一个字符是否为分隔符
      // word: 等价于 left_word && right_word
```

**验证**：测试 `:hello` 在 `say :hello world` 中触发（right_word=true），在 `say:hello` 中不触发。

---

### 阶段二：变量类型扩展（中优先级）

#### 2.1 支持 `choice` 变量类型 ✅ 已完成

**目标**：独立 `choice` 变量类型弹出选择列表，复用现有 form 的 choice UI 逻辑。

**改动文件**：
- `src/Models/AppSettings.cs` — 将 `choice` 加入 `SupportedList` ✅
- `src/Models/DictWrapper.cs` — `Params` 添加 `Values` 字段 ✅
- `src/Platforms/Android/Services/ExpanderAccessibilityService.cs` — `ParseItemAsync` 添加 `choice` case ✅（弹出浮动选择窗口 `ShowChoiceSelectionAsync`）

**实施方案**：
- `choice` 变量的 `params.values` 可以是换行分隔的字符串或数组
- 触发时弹出浮动选择窗口（复用现有 form 的 Spinner/ListView UI）
- 用户选择后将值替换到 `{{varname}}` 占位符

**UI 流程**：
1. 检测到 `choice` 变量 → 暂停扩展
2. 显示浮动窗口 + 选项列表
3. 用户选择 → 继续后续变量解析 → 完成扩展

#### 2.2 修复日期格式转换 ✅ 已完成

**目标**：`GetTheRealFormat` 正确覆盖所有常用 chrono 格式说明符，且替换顺序不产生冲突。

**改动文件**：
- `src/Models/Utils.cs` — 重写 `GetTheRealFormat` 和 `GetOriginalFormat` ✅

**实施方案**：
- 使用 token 化方式而非简单字符串替换：先用占位符标记所有 `%X` 模式，再逐个替换为 .NET 等价格式
- 补充缺失的格式说明符：`%C`（世纪）、`%G`/`%V`/`%u`（ISO 周日期）、`%Z`（时区名）、`%z`（偏移）、`%N`（纳秒，映射为 `fffffff`）
- 修复 `%j` 和 `%w`：映射为 .NET 的动态格式（`%j` → 暂无直接等价，需用自定义计算；`%w` → `dddd` 取首字母或数字）
- `GetOriginalFormat` 同样使用 token 化方式，按字符串长度从长到短替换

**补充格式映射表**：

| chrono | .NET | 说明 |
|--------|------|------|
| `%Y` | `yyyy` | 4位年份 |
| `%y` | `yy` | 2位年份（**当前缺失**） |
| `%m` | `MM` | 2位月份 |
| `%b` | `MMM` | 月份缩写 |
| `%B` | `MMMM` | 月份全名 |
| `%d` | `dd` | 2位日期 |
| `%e` | `d` | 日期（不补零） |
| `%a` | `ddd` | 星期缩写 |
| `%A` | `dddd` | 星期全名 |
| `%H` | `HH` | 24小时制 |
| `%I` | `hh` | 12小时制 |
| `%p` | `tt` | AM/PM |
| `%M` | `mm` | 分钟 |
| `%S` | `ss` | 秒 |
| `%y` | `yy` | 2位年份（**已添加**） |
| `%n` | `\n` | 换行（**已添加**） |
| `%t` | `\t` | 制表符（**已添加**） |
| `%%` | `%` | 百分号字面量（**已添加**） |
| `%N` | `fffffff` | 纳秒（**已添加**） |
| `%z` | `zzz` | 时区偏移（**已添加**） |
| `%Z` | 时区名 | 时区名称（**已添加**，直接求值） |
| `%C` | 世纪 | 世纪数（**已添加**，直接求值） |
| `%G` | ISO 年 | ISO 8601 年（**已添加**，直接求值） |
| `%V` | ISO 周 | ISO 8601 周数（**已添加**，直接求值） |
| `%u` | ISO 星期 | ISO 1-7（**已添加**，直接求值） |

---

### 阶段三：大小写传播（中优先级）

#### 3.1 实现 `propagate_case` / `uppercase_style` ✅ 已完成

**目标**：当触发词全大写或首字母大写时，替换文本也相应变换大小写。

**改动文件**：
- `src/Models/DictWrapper.cs` — 添加 `PropagateCase` / `UppercaseStyle` 字段 ✅
- `src/Platforms/Android/Services/ExpanderAccessibilityService.cs` — 在 `HandleTextExpansionAsync` 中替换前应用大小写变换 ✅

**实施方案**：
```
Match 模型添加:
  public bool PropagateCase { get; set; } = false;
  public string UppercaseStyle { get; set; }  // "uppercase" | "capitalize" | "capitalize_words"

HandleTextExpansionAsync 中:
  if (match.PropagateCase)
      // 检测触发词的大小写模式
      if (trigger.All(c => char.IsUpper(c) || !char.IsLetter(c)))
          // 全大写 → uppercase
          replace = ApplyUppercaseStyle(replace, match.UppercaseStyle ?? "uppercase");
      else if (trigger.Length > 0 && char.IsUpper(trigger[0]))
          // 首字母大写 → capitalize
          replace = ApplyUppercaseStyle(replace, "capitalize");
```

**验证**：`:hello` → "Hi there!"，`:HELLO` → "HI THERE!"，`:Hello` → "Hi There!"。

---

### 阶段四：导出改进与双向兼容（低优先级）

#### 4.1 导出为 espanso 兼容 YAML ✅ 已完成

**目标**：导出的 YAML 文件能被 espanso 桌面版直接导入使用。

**改动文件**：
- `src/Pages/Index.razor` — `ExportAsync` 中 YAML 序列化逻辑 ✅

**实施方案**：
- 导出时使用 espanso 的字段命名约定（`snake_case`，已通过 `UnderscoredNamingConvention` 实现）
- 确保导出的 `vars` 中 `type` 字段正确（当前 `Var.Type` 映射为 `type`，需验证 YamlDotNet 的命名约定处理）
- 日期格式导出时调用 `GetOriginalFormat` 转回 chrono 格式
- 添加 `yaml-language-server` schema 注释行

#### 4.2 导入时保留不支持的字段 ✅ 已完成

**目标**：导入 espanso 配置时，不支持的变量类型（如 `shell`、`script`）的 match 被跳过而非导致错误，同时保留原始信息以便用户参考。

**实施方案**：
- 在导入日志中记录被跳过的 match 及原因 ✅（`Index.razor` 显示摘要，`ConfigImportReceiver.cs` 输出 `Log.Warn`）
- 可选：在 UI 中显示导入摘要（成功 N 条，跳过 M 条，原因列表）✅

---

### 阶段五：正则触发器（探索性，低优先级）

#### 5.1 支持 `regex` 触发器 ✅ 已完成

**目标**：支持 espanso 的正则表达式触发器。

**改动文件**：
- `src/Models/DictWrapper.cs` — 添加 `Regex` 字段 ✅
- `src/Platforms/Android/Services/ExpanderAccessibilityService.cs` — 在 `HandleTextExpansionAsync` 中添加正则匹配逻辑 ✅

**实施方案**：
- 存储 regex 触发的 match 在单独的 `Dictionary<Regex, Match>` 中
- 在 `HandleTextExpansionAsync` 中，对文本末尾的 word 进行正则匹配
- 匹配成功后，用捕获组替换触发词部分

**限制**：
- AccessibilityService 只能获取完整文本，无法像 espanso 那样逐键检测
- 正则匹配性能需关注，建议限制正则复杂度
- `max_regex_buffer_size` 配置可用于限制匹配文本长度

---

## 实施优先级与时间线

| 阶段 | 任务 | 优先级 | 预计改动量 | 依赖 |
|------|------|--------|-----------|------|
| 1.1 | `triggers` 多触发词 | 高 | 小 | 无 |
| 1.2 | `imports` 跨文件引用 | 高 | 中 | 无 |
| 1.3 | `left_word` / `right_word` | 高 | 小 | 无 |
| 2.1 | `choice` 变量类型 | 中 | 中 | 无 |
| 2.2 | 日期格式转换修复 | 中 | 小 | 无 |
| 3.1 | `propagate_case` / `uppercase_style` | 中 | 中 | 1.1 |
| 4.1 | 导出为 espanso 兼容 YAML | 低 | 小 | 2.2 |
| 4.2 | 导入时保留不支持的字段 | 低 | 小 | 无 |
| 5.1 | `regex` 触发器 | 低 | 大 | 1.1, 1.3 |

---

## 验证策略

### 单元测试 ❌ 未实现
- `Utils.GetTheRealFormat` / `GetOriginalFormat`：覆盖所有 chrono 格式说明符的往返测试
- `DictWrapper` YAML 序列化/反序列化：使用 espanso 示例配置文件验证

### 集成测试 ❌ 未实现
- 导入 espanso 官方示例配置（`espanso/src/res/config/base.yml`）验证兼容性
- 导入包含 `triggers`、`imports`、`vars`（各类型）的完整配置文件
- 导出 YAML 后用 espanso 的 JSON Schema（`schemas/match.schema.json`）验证

### 手动测试
- 在 Android 设备上测试各变量类型的实际扩展行为
- 测试 `propagate_case` 在不同大小写触发词下的行为
- 测试 `left_word` / `right_word` 在各种文本上下文中的触发准确性

---

## 不在计划范围内

以下特性因 Android 平台限制或投入产出比过低，明确不在本次计划范围内：

- **`shell` / `script` 变量**：Android 无桌面 shell 环境，且安全限制使得执行任意脚本不现实
- **`image_path`**：AccessibilityService 的 `SetText` action 不支持图片注入
- **`force_clipboard` / `force_mode`**：Android 固定使用 `SetText`，无注入模式选择
- **`filter_title` / `filter_exec`**：可通过 `packageName` 实现 App 特定配置，但当前需求不足
- **`backend` / `paste_shortcut` 等桌面端配置**：与 Android 实现机制无关
