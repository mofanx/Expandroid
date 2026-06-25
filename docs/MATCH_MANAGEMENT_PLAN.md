# Match Management UI — 实施计划与进度

## 概述

为 Expandroid 应用增加结构化的匹配项管理界面，包括全局变量管理、文件管理和同步改进。

## 当前进度

### 阶段 1：全局变量管理（核心功能） — ✅ 已完成

| 任务 | 状态 | 说明 |
|------|------|------|
| 1.1 新增全局变量管理页面 `Pages/GlobalVars.razor` | ✅ | 列表、搜索、添加、编辑、删除 |
| 1.2 导航入口（Settings + TopBar + BottomNav） | ✅ | 设置→功能→全局变量，路由 `/globalvars` |
| 1.3 本地化字符串（en + zh） | ✅ | 9 个新 key（`Update` 复用已有 key） |
| 1.4 保存/同步集成 | ✅ | 保存到 `global.json`，通知 AC 服务，触发同步推送 |
| 1.5 同步推送前预览文件列表，警告覆盖 | ⏳ 待做 | 中等优先级，`SyncSettings.razor` 已有 `GetFileList` 展示远程文件，需补充覆盖警告 UI |

### 阶段 2：文件管理（需重构数据模型） — ⏳ 待做

阶段 2 分为 4 个子阶段，按依赖关系排序：

#### 2A. 数据模型重构（P0 基础）

| 任务 | 状态 | 说明 |
|------|------|------|
| 2A.1 `Match` 类增加 `SourceFile` 字段 | ⏳ | `src/Models/DictWrapper.cs`，同步更新拷贝构造函数 |
| 2A.2 `Triggers` 展开时保留 `SourceFile` | ⏳ | `YamlWorkspace.MergeGroupIntoDict` 中 clone 需继承 SourceFile |
| 2A.3 `DictWrapper` → `MatchGroup` 适配 | ⏳ | 导入路径（`ImportService`）将 `DictWrapper.Matches` 转换时设置 `SourceFile` |
| 2A.4 `AppSettings` 增加 `DataFormatVersion` | ⏳ | 用于迁移判断，当前版本 = 1（旧格式），目标 = 2 |

#### 2B. 本地存储迁移（P1 数据安全）

| 任务 | 状态 | 说明 |
|------|------|------|
| 2B.1 定义新本地存储格式 | ⏳ | 从 `keywords.json`（扁平 dict）改为 `keywords/` 目录，按 `SourceFile` 分组 |
| 2B.2 迁移逻辑：v1 → v2 | ⏳ | 读取旧 `keywords.json`，所有 match 的 `SourceFile` 设为 `null`（标记为"未分类"） |
| 2B.3 迁移时备份旧文件 | ⏳ | 复制 `keywords.json` → `keywords.json.bak`，迁移成功后保留备份不删除 |
| 2B.4 迁移失败回滚 | ⏳ | 若新格式写入失败，恢复 `keywords.json.bak` 并将 `DataFormatVersion` 回退为 1 |
| 2B.5 启动时自动迁移 | ⏳ | `MauiProgram.cs` 或 `Index.razor.OnInitializedAsync` 中检查版本并执行 |

#### 2C. 同步推送统一与增量更新（P0 核心）

| 任务 | 状态 | 说明 |
|------|------|------|
| 2C.1 统一三条推送路径的文件结构 | ⏳ | CloudFolder、WebDAV、Git 均输出多文件结构（详见下方设计） |
| 2C.2 `WriteToFolderAsync` 改为增量更新 | ⏳ | 按 `SourceFile` 分组写入，不再先删后写（详见下方算法） |
| 2C.3 WebDAV 推送改为多文件 | ⏳ | `PushWebDavAsync` 从单 `espansogo.yml` 改为按 SourceFile 上传多个 YAML |
| 2C.4 Git 推送改为多文件 | ⏳ | `GitSyncService.PushAsync` 从单文件改为按 SourceFile 写入 `match/` 目录 |
| 2C.5 SAF 兼容性验证 | ⏳ | 确认 `SafManager` 支持增量写入（不删除已有文件、按需创建新文件） |

#### 2D. 文件管理 UI（P2）

| 任务 | 状态 | 说明 |
|------|------|------|
| 2D.1 新增文件管理页面 `Pages/Files.razor` | ⏳ | 文件列表、按文件浏览 match（详见下方 UI 设计） |
| 2D.2 文件操作功能 | ⏳ | 创建/重命名/删除 YAML 文件，match 在文件间移动 |
| 2D.3 新建 match 时选择目标文件 | ⏳ | `Index.razor` 添加 match 时增加 `SourceFile` 选择器 |
| 2D.4 本地化字符串 | ⏳ | 文件管理相关 key（en + zh） |
| 2D.5 路由与导航集成 | ⏳ | `/files` 路由，TopBar 标题，Settings 入口，BottomNav 直达（详见 UI/UX 改进） |

### 阶段 2D UI 设计：文件管理

#### 数据来源

文件列表来自本地 `dict` 中每个 `Match` 的 `SourceFile` 字段。
同步拉取后，`YamlWorkspace.ReadFromFolderWithImportsAsync` 解析远程 YAML 时，
`ParseYaml` 已将 `sourceFile` 参数设置到 `MatchGroup.SourceFile`（`YamlWorkspace.cs:148`），
`MergeGroupIntoDict` 将其展开到 dict 中。UI 层只需按 `SourceFile` 分组即可得到文件列表。

```
同步拉取 (Pull)                 本地存储                       UI 展示
┌─────────────┐            ┌──────────────┐              ┌──────────────┐
│ 远程 YAML    │──→ 解析为  │ dict          │──→ 按         │ Files.razor  │
│ base.yml     │    Match[] │  ":hello" →   │   SourceFile │  base.yml    │
│ emoji.yml    │    (含     │    {SourceFile│   分组       │  emoji.yml   │
│ work.yml     │    Source- │    ="emoji.yml│              │  work.yml    │
│ ...          │    File)   │   "}          │              │  ...         │
└─────────────┘            └──────────────┘              └──────────────┘
```

#### 页面结构：`Pages/Files.razor`

**文件列表视图（`/files`）：**

```
┌─────────────────────────────────────────────────┐
│  TopBar: "文件" / "Files"                        │
├─────────────────────────────────────────────────┤
│                                                  │
│  ┌─ 文件列表 (MudList) ─────────────────────┐   │
│  │                                           │   │
│  │  📄 base.yml                     12 items │   │
│  │  📄 emoji.yml                     8 items │   │
│  │  📄 work.yml                      5 items │   │
│  │  📄 misc.yml                      3 items │   │
│  │  📄 (未分类)                      2 items │   │
│  │                                           │   │
│  └───────────────────────────────────────────┘   │
│                                                  │
│  [+ 新建文件]                                     │
│                                                  │
└─────────────────────────────────────────────────┘
```

- 每行使用 `MudListItem`，左侧文件图标，右侧 match 数量 `MudChip`
- `SourceFile = null` 的 match 归入"未分类"组（本地化 key: `Uncategorized`）
- 文件按名称字母序排列，"未分类"始终排在最后
- 点击文件行 → 导航到 `/files/{filename}`

**文件详情视图（`/files/{filename}`）：**

```
┌─────────────────────────────────────────────────┐
│  TopBar: "emoji.yml" (← 返回)                   │
├─────────────────────────────────────────────────┤
│                                                  │
│  🔍 搜索 (在该文件范围内)                         │
│                                                  │
│  ┌─ MatchCard (复用) ───────────────────────┐   │
│  │ :smile          😄                        │   │
│  │                       [✏️] [🗑️] [📁→]     │   │
│  └───────────────────────────────────────────┘   │
│  ┌─ MatchCard ──────────────────────────────┐   │
│  │ :heart          ❤️                        │   │
│  │                       [✏️] [🗑️] [📁→]     │   │
│  └───────────────────────────────────────────┘   │
│  ...                                             │
│                                                  │
│  ┌─ 文件操作 ───────────────────────────────┐   │
│  │  [✏️ 重命名文件]  [🗑️ 删除文件]           │   │
│  └───────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

- 复用 `MatchCard` 组件展示每个 match
- 搜索框过滤范围限定在当前文件的 match 内
- `MatchCard` 新增"移动到其他文件"按钮 `[📁→]`

#### 交互流程

**1. 浏览文件列表**
- 进入 `/files`，从 `dict` 按 `SourceFile` 分组，统计每个文件的 match 数量
- 点击文件名 → 导航到 `/files/{filename}`

**2. 按文件浏览 match**
- 展示该文件下所有 match（复用 `MatchCard`）
- 支持搜索过滤（范围：当前文件）

**3. 创建新文件**
- 点击"新建文件" → `MudDialog` 弹窗输入文件名
- 验证：文件名必须以 `.yml` 或 `.yaml` 结尾，不能与已有文件重名
- 创建后：在本地 `keywords/` 目录创建空 YAML 文件（含 `matches: []`）
- 新文件自动出现在文件列表中

**4. 重命名文件**
- 点击"重命名" → `MudDialog` 弹窗输入新文件名
- 验证：同创建规则
- 执行：将该文件下所有 match 的 `SourceFile` 更新为新文件名
- 本地 `keywords/` 目录中重命名文件
- 下次推送时增量更新会自动处理远程文件

**5. 删除文件**
- 点击"删除" → 确认弹窗（警告：该文件下所有 match 将被删除）
- 执行：从 `dict` 中移除该文件的所有 match
- 删除本地 `keywords/` 目录中的文件
- 下次推送时远程文件也会被删除（增量更新逻辑中处理）

**6. match 在文件间移动**
- `MatchCard` 上新增 `[📁→]` 按钮 → 弹出文件选择器（`MudSelect` 列出所有文件）
- 选择目标文件后，更新该 match 的 `SourceFile`
- UI 即时刷新：从原文件列表消失，出现在目标文件列表中
- 需要保存（`SaveDictAsync`）后才持久化

**7. 新建/编辑 match 时选择目标文件**
- `Index.razor` 和 `Files.razor` 的 match 编辑器中新增 `MudSelect` 选择 `SourceFile`
- 选择器始终可见，用户可随时修改文件归属
- 选项列表 = 当前所有文件 + "未分类"（`null`）

**`SourceFile` 默认值优先级（从高到低）：**

```
1. 文件详情页上下文     → 用户在 /files/emoji.yml 页面添加 → 默认 emoji.yml
2. 上次选择（持久化）   → Preferences.Get("last_source_file")
3. 第一个已有文件       → 文件列表按字母序的第一个（如 base.yml）
4. fallback            → "base.yml"（自动创建）
```

| 入口 | 默认 SourceFile | 说明 |
|------|----------------|------|
| 首页添加（`/`） | 上次选择的文件 | 首次使用时取第一个已有文件 |
| 文件详情页添加（`/files/{name}`） | 当前文件 | 用户可能想放到其他文件，可修改 |
| 全新用户（无文件无数据） | `base.yml`（自动创建） | 添加后 base.yml 自动创建 |

- 用户每次选择文件后，`Preferences.Set("last_source_file", selectedFile)` 持久化
- 若记忆的文件已被删除，降级到第一个已有文件
- 编辑"未分类"（`SourceFile = null`）的 match 时，选择器显示"未分类"，用户可选择归入某个文件

**与推送逻辑的衔接：**

```
添加 match (SourceFile = "work.yml")
    ↓
dict["newtrigger"] = { SourceFile = "work.yml", ... }
    ↓
SaveDictAsync → 推送
    ↓
WriteToFolderAsync 按 SourceFile 分组 → 增量写入 work.yml

添加 match (SourceFile = null，即"未分类")
    ↓
WriteToFolderAsync → null 走 GetGroupPrefix fallback
    ↓
按首字符分组到 base.yml / emoji.yml / symbols.yml / misc.yml
```

#### 页面定位与用户场景

两个页面操作的是**同一份数据**（`dict`），只是组织方式和交互入口不同：

| 页面 | 定位 | 频率 | 面向用户 | 核心操作 |
|------|------|------|---------|---------|
| 首页（`/`） | 表单式操作 | 高频 | 所有用户 | 添加/搜索/编辑/删除 match，`SourceFile` 下拉选择 |
| 文件管理（`/files`） | 文件组织 + 高级编辑 | 中频 | 所有用户（文件组织）+ 会 YAML 的用户（原始编辑） | 按文件浏览、移动 match、创建/重命名/删除文件 |

**首页**是"按 trigger 扁平看"，**文件管理**是"按文件分组看"。

不会 YAML 的用户：
- 在首页用表单添加/编辑 match，通过 `SourceFile` 选择器管理文件归属
- 在文件管理页面按文件浏览、移动 match、创建/删除文件

会 YAML 的用户：
- 典型工作流：桌面端 espanso 编辑 YAML → 同步到手机 → 手机端浏览/微调
- 阶段 3 的 YAML 原始编辑器定位为**轻量预览 + 小幅修改**（手机屏幕小、输入慢，不适合完整编辑）

#### 导航集成

| 集成点 | 改动 |
|--------|------|
| 路由 | `@page "/files"` 和 `@page "/files/{FileName}"` |
| `TopBar.razor` | 新增 `files` 路由识别，标题显示文件名或"文件" |
| `BottomNav.razor` | 新增 `files` 路由识别，`_showNav = true`；BottomNav 扩展为 4 项（详见 UI/UX 改进） |
| `Settings.razor` | Features 区域新增"文件管理"入口（图标: `Icons.Material.Filled.Folder`） |
| `Main.razor` | 无需改动（`@attribute [Route("/files/{FileName}")]` 在 `Files.razor` 中声明） |

#### 新增本地化 key

| Key | EN | ZH |
|-----|----|----|
| Files | Files | 文件 |
| FileManagement | File Management | 文件管理 |
| NewFile | New File | 新建文件 |
| RenameFile | Rename File | 重命名文件 |
| DeleteFile | Delete File | 删除文件 |
| DeleteFileWarning | All matches in this file will be deleted. Continue? | 该文件下所有匹配项将被删除，是否继续？ |
| MoveTo | Move to | 移动到 |
| Uncategorized | Uncategorized | 未分类 |
| FileNameRequired | File name is required | 文件名为必填项 |
| InvalidFileName | File name must end with .yml or .yaml | 文件名必须以 .yml 或 .yaml 结尾 |
| FileAlreadyExists | A file with this name already exists | 同名文件已存在 |
| EmptyFile | This file has no matches | 此文件没有匹配项 |
| MatchesCount | {0} matches | {0} 个匹配项 |

### 阶段 3：高级增强（可选） — ⏳ 待做

| 任务 | 状态 | 说明 |
|------|------|------|
| 3.1 YAML 原始编辑器 | ⏳ | 高级用户直接编辑文件内容 |
| 3.2 文件模板预设 | ⏳ | 工作、个人、代码片段等分类模板 |
| 3.3 ConflictResolver 内容 diff 对比 | ⏳ | 冲突解决时展示本地 vs 远程的逐行差异 |
| 3.4 Git Termux 推送可靠性 | ⏳ | 替代 fire-and-forget 方案（TermuxResultService 回调或文件标记确认） |

## 阶段 1 已完成的变更

### 新增文件

- `src/Pages/GlobalVars.razor` — 全局变量管理页面
  - 列表展示（名称、类型、参数摘要）
  - 搜索过滤
  - 添加/编辑/删除（支持 echo, date, clipboard, random, choice, shell, script, http, javascript）
  - 保存到 `global.json` + 通知 AC 服务 + 触发同步推送
  - 分页加载（每页 100 条）

### 修改文件

- `src/Pages/Settings.razor` — Features 区域添加"全局变量"入口
- `src/Shared/TopBar.razor` — `/globalvars` 路由标题和返回按钮
- `src/Shared/BottomNav.razor` — `/globalvars` 路由识别
- `src/Resources/AppResources.resx` — 9 个英文 key
- `src/Resources/AppResources.zh.resx` — 9 个中文 key

### 新增的本地化 key

| Key | EN | ZH |
|-----|----|----|
| GlobalVariables | Global Variables | 全局变量 |
| AddGlobalVar | Add Global Variable | 添加全局变量 |
| EditGlobalVar | Edit Global Variable | 编辑全局变量 |
| SearchVariables | Search variables | 搜索变量 |
| NoVariablesFound | No variables found | 未找到变量 |
| NoGlobalVarsYet | No global variables yet... | 暂无全局变量... |
| VariableNameRequired | Variable name is required | 变量名称为必填项 |
| DuplicateVarName | A variable named "{0}" already exists | 名为"{0}"的变量已存在 |
| Update | Update | 更新（复用已有 key） |

## 架构分析

### 当前数据流

```
本地存储 (JSON)          同步推送 (YAML)                    Espanso 桌面
┌─────────────┐     ┌──────────────────────┐           ┌──────────────┐
│ keywords.json│     │ CloudFolder/Syncthing │           │ match/       │
│ (扁平 dict)  │────→│ 先删后写，自动分组     │──────────→│  base.yml    │
│              │     │ base.yml / emoji.yml  │           │  emoji.yml   │
│ global.json  │     │ symbols.yml / misc.yml│           │  work.yml    │ ← 桌面用户
│ (全局变量)    │     │ global_vars.yml       │           │  personal.yml│   自定义文件
└─────────────┘     └──────────────────────┘           └──────────────┘
                    ┌──────────────────────┐
                    │ WebDAV               │           ┌──────────────┐
                    │ 单文件 espansogo.yml  │──────────→│ espansogo.yml│ ← 桌面收到
                    └──────────────────────┘           └──────────────┘   单文件
                    ┌──────────────────────┐
                    │ Git (Termux)         │           ┌──────────────┐
                    │ 单文件 match/        │──────────→│ match/       │ ← 桌面收到
                    │   espansogo.yml      │           │  espansogo.yml│  单文件
                    └──────────────────────┘           └──────────────┘
```

### 存在的问题

1. **同步覆盖桌面文件结构** — CloudFolder 推送时先删除同步目录所有 YAML，替换为自动分组的 4 个文件，桌面用户自定义的 `work.yml`、`personal.yml` 等被删除
2. **三条推送路径不一致** — CloudFolder 写多文件（先删后写），WebDAV/Git 写单文件，桌面端收到的结构完全不同
3. **无全局变量管理 UI** — ✅ 已在阶段 1 解决
4. **无文件归属概念** — 用户不知道 match 来自哪个 YAML 文件，编辑后推送会丢失原始文件归属
5. **分组逻辑过于简单** — 仅按 trigger 首字符分组（`GetGroupPrefix`），无法保留桌面用户的自定义文件结构
6. **Git 推送可靠性问题** — `GitSyncService.RunGitViaTermuxAsync` 是 fire-and-forget + 固定 5 秒等待，返回 `true` 不代表推送成功

### 阶段 2 关键设计

#### 1. `SourceFile` 字段设计

- `Match` 类增加 `[YamlMember(Alias = "_source_file")]` 或使用 `[JsonIgnore]` 标记的 `SourceFile` 属性
- **不应序列化到 YAML 中**（桌面 Espanso 不认识此字段），使用 `[YamlIgnore]` + `[JsonIgnore]`
- 拷贝构造函数 `Match(Match og)` 需增加 `SourceFile = og.SourceFile`
- `MergeGroupIntoDict` 中 `Triggers` 展开时，clone 继承原 match 的 `SourceFile`
- 从旧 `keywords.json` 迁移的 match：`SourceFile = null`（UI 显示为"未分类"）
- 从 YAML 文件读取的 match：`SourceFile = 文件名`（如 `base.yml`）
- 新建 match 默认 `SourceFile = "base.yml"`，用户可在 UI 中选择

#### 2. 本地存储格式迁移

```
旧格式 (v1):                    新格式 (v2):
AppData/                        AppData/
├── keywords.json               ├── keywords/          ← 按文件分组
│   {"key1": {...},             │   ├── base.yml
│    "key2": {...}}             │   ├── emoji.yml
├── global.json                 │   └── misc.yml
└── sync_state.json             ├── global.json         ← 保持不变
                                ├── keywords.json.bak  ← 旧文件备份
                                └── sync_state.json
```

迁移流程：
1. 启动时检查 `AppSettings.DataFormatVersion`
2. 若为 v1：读取 `keywords.json` → 所有 match `SourceFile = null`
3. 备份 `keywords.json` → `keywords.json.bak`
4. 按 `SourceFile` 分组写入 `keywords/` 目录（`null` 归入 `misc.yml`）
5. 写入成功 → 更新 `DataFormatVersion = 2`，保留备份
6. 写入失败 → 恢复 `keywords.json.bak`，回退版本号，应用以旧格式继续运行

#### 3. 增量更新算法（`WriteToFolderAsync` 重构）

```
输入: dict (含 SourceFile), globalVars, targetFolder

1. 按 SourceFile 分组:
   grouped = dict.GroupBy(m => m.SourceFile ?? "misc.yml")

2. 读取目标目录已有文件列表:
   existingFiles = ListYamlFiles(targetFolder)

3. 对每个分组:
   a. 若文件已存在: 读取现有 YAML → 合并/替换 match 条目 → 写回
   b. 若文件不存在: 创建新文件

4. 删除空文件:
   遍历 existingFiles，若文件不在 grouped 的 key 中且内容为空 → 删除

5. 不再删除 grouped 之外的文件
   （保留桌面用户自定义的 work.yml 等文件）
```

边界条件处理：
- **match 被删除**：dict 中不存在该 key → 写入时自然不会包含它
- **match 在文件间移动**：`SourceFile` 从 `emoji.yml` 改为 `custom.yml` → `emoji.yml` 中该条目消失，`custom.yml` 中出现
- **文件变空**：分组后某文件无 match → 若文件原本存在且现在为空，删除空文件
- **SAF 兼容性**：`SafManager` 已支持 `ReadFileAsync`/`WriteFileAsync`/`DeleteFileAsync`/`CreateDocumentUri`，增量写入可行；但需验证"读取已有文件 → 修改 → 写回"的 URI 持久性

#### 4. 三条推送路径统一

| 路径 | 当前行为 | 目标行为 |
|------|---------|---------|
| CloudFolder | 先删后写，自动分 4 文件 | 增量更新，按 SourceFile 分组 |
| WebDAV | 单文件 `espansogo.yml` | 多文件，按 SourceFile 上传 |
| Git | 单文件 `match/espansogo.yml` | 多文件 `match/*.yml`，按 SourceFile 写入 |

WebDAV 改动要点：
- `PushWebDavAsync` 改为遍历 `grouped`，对每个文件调用 `PutFileAsync`
- 需处理 WebDAV 文件删除（match 被移除的文件）
- `PullWebDavAsync` 已支持多文件读取，无需大改

Git 改动要点：
- `GitSyncService.PushAsync` 改为遍历 `grouped`，写入 `match/*.yml`
- `git add -A` 会自动处理删除和新增
- **注意**：Termux 异步可靠性问题仍存在，建议在阶段 2 中增加 `TermuxResultService` 回调或改用文件标记（如 `.espansogo-push-done`）确认完成

#### 5. 分组逻辑改进

- 有了 `SourceFile` 后，`GetGroupPrefix`（按首字符分组）**降级为 fallback**
- 主逻辑：按 `SourceFile` 分组
- Fallback 触发条件：`SourceFile` 为 `null`（旧数据迁移后未分类）
- Fallback 策略保持不变（`base`/`emoji`/`symbols`/`misc`）
- 用户可在 UI 中手动将"未分类"的 match 归入指定文件

## UI/UX 改进（与阶段 2 同步实施）

以下改进应在阶段 2 实施时一并完成，确保整体导航和同步设置体验一致。

### 1. BottomNav 扩展为 4 项

当前 BottomNav 只有 Matches 和 Tools 两项，核心功能不可直达。扩展为 4 项，Settings 放在最右侧：

```
┌──────────────────────────────────────────────────┐
│   Matches      Files       Tools      Settings    │
│    📋          📁          🔧          ⚙️         │
└──────────────────────────────────────────────────┘
```

改动文件：`src/Shared/BottomNav.razor`

- 新增 Files 导航项（`/files`，图标 `Icons.Material.Filled.Folder`）
- 新增 Settings 导航项（`/settings`，图标 `Icons.Material.Filled.Settings`）
- 顺序：Matches → Files → Tools → Settings（最右）
- `UpdateRoute()` 方法新增 `files` 和 `settings` 路由识别

### 2. TopBar 返回逻辑修正

当前所有子页面统一返回 `/`，但多数子页面是从 `/settings` 进入的，返回到 `/` 会丢失上下文。

改动文件：`src/Shared/TopBar.razor`

| 当前页面 | 当前返回到 | 修正后返回到 |
|---------|-----------|------------|
| `/syncsettings` | `/` | `/settings` |
| `/syncsetup` | `/` | `/syncsettings` |
| `/globalvars` | `/` | `/settings` |
| `/packages` | `/` | `/settings` |
| `/files` | `/` | `/` ✅（BottomNav 直达） |
| `/files/{name}` | `/` | `/files` |
| `/conflicts` | `/syncsettings` | `/syncsettings` ✅ |
| `/tools` | `/` | `/` ✅（BottomNav 直达） |

`GoBack()` 方法改为根据当前路由返回对应的来源页面。

### 3. SyncSettings 布局优化

改动文件：`src/Pages/SyncSettings.razor`

**问题：**
- Save 按钮在 Tabs 之外，三个 Tab 共用一个 Save，用户容易忘记保存
- Status Tab 信息密度低，缺少当前配置摘要
- Setup Wizard Step 3 与 Configuration Tab 表单重复

**改进方案：**

```
┌─────────────────────────────────────────────┐
│  TopBar: "Sync Settings" (← /settings)      │
├─────────────────────────────────────────────┤
│                                              │
│  ┌─ 配置摘要卡片 (新增) ─────────────────┐  │
│  │  Method: CloudFolder                  │  │
│  │  Path: /storage/espanso/match         │  │
│  │  Status: ✅ Idle  Last: 2025-06-25    │  │
│  │  [Sync Now]                           │  │
│  └───────────────────────────────────────┘  │
│                                              │
│  ┌─ Tabs ───────────────────────────────┐   │
│  │ [Configuration] [Advanced]           │   │  ← 去掉 Status Tab
│  │                                       │   │     摘要卡片替代
│  │  (配置表单内容)                        │   │
│  │                                       │   │
│  │  [Save]                               │   │  ← Save 移到 Tab 内
│  └───────────────────────────────────────┘  │
│                                              │
│  [Setup Wizard]                              │
│                                              │
└─────────────────────────────────────────────┘
```

具体改动：
- 去掉 Status Tab，在 Tabs 上方新增配置摘要卡片（展示方式名称 + 地址 + 状态 + SyncNow 按钮）
- Save 按钮移到 Configuration Tab 内部，仅在有未保存变更时高亮
- Advanced Tab 的即时操作（Clear Baseline）不需要 Save，保持独立
- 抽取配置表单为共享组件 `Shared/SyncConfigForm.razor`，Setup Wizard 和 SyncSettings 都引用

### 4. SyncStatus 组件移动端可见性

改动文件：`src/Shared/SyncStatus.razor`

- 移除 "TapToManage" 的 `d-none d-sm-flex` class，让提示在移动端也显示
- 或改用 `MudCard` 替代 `MudAlert` 增加视觉权重

### 5. ConflictResolver 增加内容预览

改动文件：`src/Pages/ConflictResolver.razor`

- 冲突展开区域增加本地版本和远程版本的 YAML 内容预览（前 20 行）
- 帮助用户在 Keep Local / Keep Remote 之间做出决策

### 6. Settings 页面入口调整

改动文件：`src/Pages/Settings.razor`

- Features 区域新增"文件管理"入口（图标: `Icons.Material.Filled.Folder`，路由 `/files`）
- 同步区域保持"Sync Settings"入口不变
- 由于 BottomNav 已有 Settings 直达，Settings 页面的定位从"功能枢纽"变为"配置中心"

## 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 迁移失败导致数据丢失 | 高 | 备份旧文件 + 回滚机制 |
| SAF URI 在重启后失效 | 中 | 验证 `CreateDocumentUri` 返回的 URI 是否持久；若不持久，需重新获取授权 |
| WebDAV 服务器不支持 DELETE | 中 | 增量更新时若需删除文件，捕获 405 错误并降级为覆盖写入 |
| Git Termux 异步不可靠 | 中 | 阶段 2 可暂不解决，标记为已知限制；阶段 3 考虑替代方案 |
| `DictWrapper` 与 `MatchGroup` 两套模型 | 低 | 阶段 2 暂不统一，导入路径做适配转换；阶段 3 考虑合并 |
| BottomNav 4 项在窄屏拥挤 | 低 | 图标 + 文字尺寸已优化（0.7rem 文字、24px 图标），4 项在 360px 屏幕可正常显示 |
