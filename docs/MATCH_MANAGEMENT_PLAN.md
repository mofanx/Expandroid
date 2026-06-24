# Match Management UI — 实施计划与进度

## 概述

为 Expandroid 应用增加结构化的匹配项管理界面，包括全局变量管理、文件管理和同步改进。

## 当前进度

### 阶段 1：全局变量管理（核心功能） — ✅ 已完成

| 任务 | 状态 | 说明 |
|------|------|------|
| 1.1 新增全局变量管理页面 `Pages/GlobalVars.razor` | ✅ | 列表、搜索、添加、编辑、删除 |
| 1.2 导航入口（Settings + TopBar + BottomNav） | ✅ | 设置→功能→全局变量，路由 `/globalvars` |
| 1.3 本地化字符串（en + zh） | ✅ | 10 个新 key |
| 1.4 保存/同步集成 | ✅ | 保存到 `global.json`，通知 AC 服务，触发同步推送 |
| 1.5 同步推送前预览文件列表，警告覆盖 | ⏳ 待做 | 中等优先级 |

### 阶段 2：文件管理（需重构数据模型） — ⏳ 待做

| 任务 | 状态 | 说明 |
|------|------|------|
| 2.1 重构数据模型 — Match 增加 SourceFile 字段 | ⏳ | 本地存储从扁平 dict 改为按文件分组 |
| 2.2 新增文件管理页面 `Pages/Files.razor` | ⏳ | 文件列表、按文件浏览 match |
| 2.3 文件操作功能 | ⏳ | 创建/重命名/删除 YAML 文件，match 在文件间移动 |
| 2.4 改进推送策略 | ⏳ | 停止先删后写，改为按文件增量更新 |

### 阶段 3：高级增强（可选） — ⏳ 待做

| 任务 | 状态 | 说明 |
|------|------|------|
| 3.1 YAML 原始编辑器 | ⏳ | 高级用户直接编辑文件内容 |
| 3.2 文件模板预设 | ⏳ | 工作、个人、代码片段等分类模板 |

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
- `src/Resources/AppResources.resx` — 10 个英文 key
- `src/Resources/AppResources.zh.resx` — 10 个中文 key

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
| Update | Update | 更新 |

## 架构分析

### 当前数据流

```
本地存储 (JSON)          同步推送 (YAML)              Espanso 桌面
┌─────────────┐     ┌──────────────────┐         ┌──────────────┐
│ keywords.json│────→│ 自动分组写入 YAML │────────→│ match/       │
│ (扁平 dict)  │     │ base.yml         │         │  base.yml    │
│              │     │ emoji.yml        │         │  emoji.yml   │
│ global.json  │     │ symbols.yml      │         │  work.yml    │ ← 桌面用户
│ (全局变量)    │     │ misc.yml         │         │  personal.yml│   自定义文件
└─────────────┘     │ global_vars.yml  │         └──────────────┘
                    └──────────────────┘
                         ⚠️ 先删除所有
                           旧文件再写入
```

### 存在的问题

1. **同步覆盖桌面文件结构** — 推送时先删除同步目录所有 YAML，替换为自动分组的 4 个文件
2. **无全局变量管理 UI** — ✅ 已在阶段 1 解决
3. **无文件归属概念** — 用户不知道 match 来自哪个 YAML 文件
4. **分组逻辑过于简单** — 仅按 trigger 首字符分组

### 阶段 2 关键设计点

- `Match` 类增加 `SourceFile` 字段（`src/Models/DictWrapper.cs`）
- 本地存储从 `keywords.json`（扁平 dict）改为按文件分组结构
- `YamlWorkspace.WriteToFolderAsync()` 改为增量更新而非先删后写
- 需要向后兼容旧的 `keywords.json` 格式（迁移逻辑）
