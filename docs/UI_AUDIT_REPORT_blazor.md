# Expandroid UI 独立审核报告

**审核日期**：2026-06-24  
**审核人**：第三方独立审核  
**审核范围**：Expandroid MAUI + Blazor + MudBlazor 全部 UI 代码  
**技术栈**：.NET MAUI + Blazor Hybrid + MudBlazor  
**审核方法**：逐文件源码审查，覆盖设计系统、信息架构、交互模式、i18n、可访问性、性能、代码质量

---

## 一、评分总览

| 维度 | 评分 (10) | 等级 | 简评 |
|------|:---:|:---:|------|
| 设计系统与视觉一致性 | 7.5 | B+ | 自定义 MudTheme 配色统一，Light/Dark 完整 |
| 信息架构与导航 | 8.0 | A- | TopBar 导航 + 路由清晰，所有页面可达 |
| 交互反馈与状态管理 | 8.0 | A- | loading 状态完善，Snackbar + Dialog 反馈到位 |
| 国际化 (i18n) | 8.5 | A | LocalizationService 全覆盖，几乎无硬编码 |
| 可访问性 | 6.0 | C+ | MudBlazor 基础支持，但缺少自定义语义 |
| 性能与渲染 | 7.0 | B | 懒加载 + 搜索防抖，但 Index.razor 过大 |
| 代码质量 | 6.5 | B- | 结构清晰但 Index.razor 1021 行需拆分 |
| UX 完整性 | 7.5 | B+ | 空状态/引导/确认齐全，缺 undo 和变量编辑器 |
| 可交互性 | 7.0 | B | loading/disabled 到位，缺触觉反馈和键盘适配 |
| 布局合理性 | 7.5 | B+ | 响应式 Grid + 文本溢出处理，少数间距不统一 |
| **综合** | **7.4** | **B+** | 完成度高，细节有打磨空间 |

---

## 二、UI 架构全景

### 2.1 技术架构

- **MAUI Blazor Hybrid**：.NET MAUI 宿主 + Blazor WebView 渲染
- **MudBlazor**：Material Design 组件库
- **路由导航**：Blazor `@page` 路由 + `NavigationManager`
- **状态管理**：组件内 `@code` 状态 + `SyncManager` 单例 + `WeakReferenceMessenger` 消息
- **i18n**：`ILocalizationService` 注入，`GetString(key)` 模式

### 2.2 页面与组件清单

| 路由 | 文件 | 行数 | 功能 | 可达性 |
|------|------|:---:|------|:---:|
| `/` | `Pages/Index.razor` | 1021 | 主页：Match 管理 + TextTools | ✅ 默认页 |
| `/syncsettings` | `Pages/SyncSettings.razor` | 365 | 同步设置（3 Tab） | ✅ TopBar + SyncStatus |
| `/syncsetup` | `Pages/SyncSetupWizard.razor` | 226 | 同步向导（3 步） | ✅ SyncSettings 按钮 |
| `/packages` | `Pages/PackageStore.razor` | 265 | 包商店（2 Tab） | ✅ TopBar 菜单 |
| `/conflicts` | `Pages/ConflictResolver.razor` | 152 | 冲突解决 | ✅ SyncStatus + Snackbar |
| — | `Pages/TextTools.razor` | 223 | 文本工具（嵌入 Index Tab） | ✅ Index Tab |
| — | `Shared/MainLayout.razor` | 76 | 全局布局 + 主题 | ✅ 所有页面 |
| — | `Shared/TopBar.razor` | 83 | 顶栏：导航 + 主题 + 语言 | ✅ MainLayout |
| — | `Shared/MatchCard.razor` | 43 | Match 卡片 | ✅ Index |
| — | `Shared/SyncStatus.razor` | 101 | 同步状态条 | ✅ Index |
| — | `Shared/LanguageSwitcher.razor` | 32 | 语言切换下拉 | ✅ TopBar |

### 2.3 用户流程图

```
MainLayout
├── TopBar
│    ├── 菜单 → Home / SyncSettings / Packages / Theme / Quit
│    ├── ThemeToggle (Light/Dark/Auto)
│    └── LanguageSwitcher (English/中文)
│
├── Index (/)
│    ├── SyncStatus → 点击跳转 /syncsettings 或 /conflicts
│    ├── Tab: Matches
│    │    ├── 无障碍未开启 → 权限引导卡片
│    │    ├── 编辑器（折叠面板）
│    │    │    ├── Trigger + Replace 输入
│    │    │    ├── Variable 列表 + 计数 Badge
│    │    │    └── 预设模板 + 高级变量
│    │    ├── 工具栏：Sync / Save / Import / Export
│    │    ├── 搜索（防抖 300ms）
│    │    └── MatchCard 列表（懒加载 100）
│    └── Tab: Tools
│         └── TextTools（文本处理 + Discord Markdown）
│
├── SyncSettings (/syncsettings)
│    ├── Tab: Status — 状态 + SyncNow + 冲突入口
│    ├── Tab: Configuration — 方法选择 + 字段配置 + 测试
│    ├── Tab: Advanced — 冲突策略 + 轮询 + WiFi + 清除基线
│    └── 底部：Save / SetupWizard / Back
│
├── SyncSetupWizard (/syncsetup) — 3 步向导
├── PackageStore (/packages) — Browse + Installed
└── ConflictResolver (/conflicts) — Keep Local / Keep Remote
```

---

## 三、问题清单

### P0 — 必须修复

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P0-1 | **变量编辑器功能严重不足** — 仅提供 Name/Type/Format/Echo/Offset 5 个文本框，不支持 shell/script/javascript/http/intent/content/match 等变量类型 | `Index.razor:128-137` | 用户无法在 UI 中配置高级变量类型，与后端能力严重不匹配 |

### P1 — 应当修复

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P1-1 | **Index.razor 1021 行过长** | `Pages/Index.razor` | 维护困难，职责过多（Match 管理 + 导入导出 + 同步 + SAF 观察者） |
| P1-2 | **删除 Match 无 undo** | `Index.razor:719-737` | 误删无法恢复，仅 Snackbar 提示 |
| P1-3 | **PackageStore 搜索无防抖** | `PackageStore.razor:22` | `Immediate="true"` 但无 `DebounceInterval`，每次按键触发过滤 |
| P1-4 | **变量列表无拖拽排序** | `Index.razor:96-102` | 仅按添加顺序显示，无法调整变量执行顺序 |
| P1-5 | **`AddPreBuiltVar` 拼写 "tommorow"** | `Index.razor:276,284` | 变量名拼写错误，影响导出兼容性 |
| P1-6 | **TopBar 应用名 "Expandroid" 硬编码** | `TopBar.razor:7` | 未使用 i18n（虽可接受，但与整体 i18n 策略不一致） |
| P1-7 | **无变量类型选择器** — 高级变量编辑仅用自由文本框输入 Type | `Index.razor:130` | 用户需手动输入类型名，易拼写错误 |
| P1-8 | **编辑器中变量删除无确认** | `Index.razor:100` | `MudIconButton` 直接删除变量，无 Dialog 确认 |

### P2 — 优化建议

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| P2-1 | **MainLayout `pt-24` 硬编码内边距** | `MainLayout.razor:11` | 使用 MudBlazor 响应式间距或 `MudAppBar Fixed` 配套 padding |
| P2-2 | **SyncSettings `_lastResult` 重复显示** — Status Tab 已有结果，Snackbar 也显示 | `SyncSettings.razor:50-62` | 考虑仅用 `_lastResult` 区域，避免 Snackbar 重复 |
| P2-3 | **PackageStore 安装/卸载共用 `_installing` 禁用所有按钮** | `PackageStore.razor:65` | 应按单个包禁用，不影响其他包操作 |
| P2-4 | **ConflictResolver 无批量操作** | `ConflictResolver.razor` | 添加 "Keep All Local" / "Keep All Remote" 按钮 |
| P2-5 | **TextTools 处理结果无复制按钮** | `TextTools.razor:69-88` | 结果卡片添加复制按钮 |
| P2-6 | **无搜索排序** | `Index.razor:159-164` | 添加按触发词/修改时间排序 |
| P2-7 | **无批量操作** | `Index.razor` | 无法批量删除/导出选中 Match |
| P2-8 | **SyncSetupWizard 无步骤指示器** | `SyncSetupWizard.razor` | 添加 Stepper 显示 "Step 1/3" |
| P2-9 | **MatchCard 编辑/删除按钮在小屏可能拥挤** | `MatchCard.razor:12-15` | 考虑小屏使用 `MudMenu` 收纳操作 |
| P2-10 | **无 FAB** — 添加 Match 需先展开编辑器再填写 | `Index.razor:55-62` | 可考虑 FAB 快速添加，但当前折叠面板设计也可接受 |
| P2-11 | **导入逻辑嵌套过深** | `Index.razor:386-511` | 提取为独立方法或服务 |
| P2-12 | **Git 轮询首次延迟非零** — `TimeSpan.FromSeconds(config.ForegroundPollIntervalSec)` 作为 dueTime | `Index.razor:975` | ✅ 正确（已修复之前 dueTime=Zero 的问题） |

---

## 四、分维度详细评审

### 4.1 设计系统与视觉一致性 — 7.5/10

**优点：**
- `MainLayout.razor` 定义了完整的 `MudTheme`，Light/Dark 调色板统一使用 Indigo (#6366f1) + Violet (#8b5cf6) 配色
- 自定义 Typography（Roboto 字族，H4/H6 加粗）
- `MudThemeProvider` 正确注入，支持 Dark Mode 自动切换
- 所有页面使用一致的 `MudTabs` + `MudCard` + `MudStack` 组件模式

**问题：**
- `MainLayout.razor:11` 使用 `Class="pt-24"` 硬编码顶部间距适配 Fixed AppBar，不够优雅
- Dark 调色板 `AppbarBackground = "#1e293b"` 与 Light 的 `#6366f1` 差异较大，视觉一致性略有不足

### 4.2 信息架构与导航 — 8.0/10

**优点：**
- TopBar 菜单提供全局导航（Home / SyncSettings / Packages / Theme / Quit）
- SyncStatus 智能跳转（冲突 → `/conflicts`，其他 → `/syncsettings`）
- SyncSettings 底部有 SetupWizard 入口
- 冲突 Snackbar 带 "Resolve" action 按钮导航到 `/conflicts`
- 所有页面通过 TopBar 可达，无隐藏路由

**问题：**
- TopBar 菜单项在小屏设备上可能拥挤（菜单 + ToggleGroup + LanguageSwitcher）
- `d-none d-sm-flex` 在小屏隐藏了 ThemeToggle 和 LanguageSwitcher，仅通过菜单访问 — 合理的响应式处理

### 4.3 交互反馈与状态管理 — 8.0/10

**优点：**
- **完善的 loading 状态**：`_loading`（初始加载）、`_syncing`（同步）、`_saving`（保存）、`_testing`（连接测试）、`_installing`/`_uninstalling`（包安装）、`processing`（TextTools）
- **按钮 disabled 绑定**：所有异步操作按钮正确绑定 `Disabled="@_xxx"` 状态
- **CircularProgressIndicator**：同步/测试按钮内嵌 spinner
- **Snackbar 反馈**：成功/失败/警告均有 Snackbar 提示
- **Dialog 确认**：删除 Match、导入警告、清除基线均有确认对话框
- **SyncCompleted 事件订阅**：Index.razor 和 SyncStatus 均订阅，自动刷新
- **空状态**：无 Match 时显示引导文案，搜索无结果时显示提示

**问题：**
- 删除无 undo（P1-2）
- 变量删除无确认（P1-8）
- PackageStore 搜索无防抖（P1-3）

### 4.4 国际化 (i18n) — 8.5/10

**优点：**
- `ILocalizationService` 注入式 i18n，几乎所有用户可见文本使用 `localizationService.GetString(key)`
- `LanguageSwitcher` 支持动态切换，通过 `OnLanguageChanged` 事件触发 `StateHasChanged`
- 搜索框、按钮、标签、Snackbar 消息、Dialog 内容全部 i18n
- 支持中文和英文两种语言

**问题：**
- `TopBar.razor:7` "Expandroid" 硬编码（可接受，应用名通常不翻译）
- `Index.razor:276` 变量名 "tommorow" 拼写错误（非 i18n 问题但影响一致性）

### 4.5 可访问性 — 6.0/10

**优点：**
- MudBlazor 组件自带基础 ARIA 支持
- `MudIconButton` 有 `Icon` 属性，MudBlazor 自动生成 `aria-label`
- `MudAlert` 有关闭按钮
- `MudSelect` / `MudTextField` 有关联 Label

**问题：**
- 无自定义 `aria-label` 或 `role` 修饰
- SyncStatus 点击区域用 `MudAlert OnClick`，但无 `role="button"` 或键盘可访问性
- MatchCard 操作按钮依赖鼠标点击，无键盘快捷键
- 无 `LiveRegion` 或 `aria-live` 用于动态状态播报
- 颜色对比度未验证（Indigo on White 在某些情况下可能不满足 WCAG AA）

### 4.6 性能与渲染 — 7.0/10

**优点：**
- `lazyLoadIndex` 懒加载，默认 100 条
- 搜索 `DebounceInterval="300"` 防抖（Index.razor）
- `_filteredDict` 计算属性缓存
- `@key="item.Key"` 正确用于列表项
- `SyncLock` 防止并发同步

**问题：**
- `_filteredDict` 每次访问都执行 LINQ 查询（`dict.Where(...)`），无 `Memoize`
- `PackageStore.ApplyFilter` 每次按键都执行 `ToLowerInvariant()` 全量遍历
- Index.razor `OnInitializedAsync` 中同步执行大量文件 I/O（`File.OpenRead`、`JsonSerializer.Deserialize`），可能阻塞 UI 线程
- `_filteredPackages` 在 PackageStore 中是 `List`，搜索时创建新列表

### 4.7 代码质量 — 6.5/10

**优点：**
- 页面职责清晰（SyncSettings 管同步、PackageStore 管包、ConflictResolver 管冲突）
- `@implements IDisposable` 正确清理事件订阅
- `WeakReferenceMessenger` 消息模式解耦 UI 与服务
- SyncSettings 使用 Tab 分区，结构清晰

**问题：**
- **Index.razor 1021 行**，包含 Match CRUD + 导入导出 + 同步 + SAF 观察者 + 迁移逻辑，应拆分
- `ImportAsync` 方法 125 行，嵌套 4 层 if/try
- `ProcessImport` 递归方法在 Index.razor 中，应移到服务层
- `AddPreBuiltVar` 拼写 "tommorow"
- PackageStore `UninstallPackage` 和 `UninstallInstalledPackage` 几乎完全重复

### 4.8 UX 完整性 — 7.5/10

**优点：**
- 欢迎卡片可关闭并记住状态（`Preferences.Get("welcomed")`）
- 无障碍权限引导清晰（图标 + 文案 + 按钮）
- 空状态完整（无 Match / 搜索无结果 / 无包 / 无冲突 / 无已安装包）
- 预设变量模板（日期/昨天/明天/时间/光标）
- TextTools 处理结果可视化（成功/失败图标）
- 同步状态条显示方法名 + 状态 + 冲突数

**问题：**
- **变量编辑器功能不足**（P0-1）— 仅 5 个通用字段，无法配置 shell/http/js 等高级类型
- 无 undo（P1-2）
- 无新用户引导教程
- 无变量类型选择器（需手动输入类型名）
- SyncSetupWizard 无步骤进度指示

---

## 五、可交互性专项审查

### 5.1 触摸目标

| 位置 | 组件 | 评估 |
|------|------|------|
| `MatchCard.razor:13-14` | 编辑/删除 IconButton (Size.Medium) | ✅ 合格 |
| `Index.razor:100` | 变量删除 IconButton (Size.Small) | ⚠️ Small 在小屏可能偏小 |
| `TopBar.razor:10-18` | 菜单 MenuItem | ✅ 合格 |
| `TopBar.razor:20-24` | ThemeToggle (Size.Small) | ⚠️ Small 但有 `d-none d-sm-flex` 小屏隐藏 |
| `SyncSettings.razor:32` | SyncNow Button | ✅ 合格 |

### 5.2 交互反馈

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| IA-1 | ✅ Save 按钮 disabled + loading | `Index.razor:147` | 良好 |
| IA-2 | ✅ Sync 按钮 disabled + loading | `Index.razor:146` | 良好 |
| IA-3 | ✅ TestConnection disabled + spinner | `SyncSettings.razor:88-91` | 良好 |
| IA-4 | ✅ Install/Uninstall disabled + spinner | `PackageStore.razor:65,100-103` | 良好 |
| IA-5 | ✅ Process 按钮 disabled + spinner | `TextTools.razor:52-62` | 良好 |
| IA-6 | ❌ 删除 Match 无 undo | `Index.razor:719-737` | 误删无法恢复 |
| IA-7 | ❌ 变量删除无确认 | `Index.razor:100` | 误触删除 |
| IA-8 | ⚠️ PackageStore 搜索无防抖 | `PackageStore.razor:22` | 频繁触发过滤 |

### 5.3 键盘/IME 交互

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| IME-1 | ✅ 搜索框有防抖 300ms | `Index.razor:164` | 良好 |
| IME-2 | ✅ MudTextField 自带键盘支持 | 全局 | MudBlazor 组件基础支持 |
| IME-3 | ⚠️ 无 Enter 键提交快捷键 | `Index.razor:70-73` | 编辑器中 Trigger/Replace 字段无 Enter 提交 |
| IME-4 | ⚠️ 无 Escape 键取消编辑 | 全局 | 无键盘取消操作 |

### 5.4 焦点管理

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| F-1 | ⚠️ 编辑器展开时不自动聚焦 Trigger 输入框 | `Index.razor:64-141` | 用户需手动点击 |
| F-2 | ⚠️ Dialog 关闭后不恢复焦点 | 全局 | 辅助功能用户失去上下文 |
| F-3 | ✅ MudBlazor 组件自带 Tab 导航 | 全局 | 基础支持 |

---

## 六、布局合理性专项审查

### 6.1 间距一致性

| 位置 | 用法 | 评估 |
|------|------|------|
| `MainLayout.razor:12` | `pa-4` | ✅ 统一 |
| `Index.razor:66,89` | `pa-4`, `pa-3` | ⚠️ 混用 3 和 4 |
| `SyncSettings.razor:21,68,109` | `pa-4` | ✅ 统一 |
| `MatchCard.razor:6` | `pa-4 mb-3` | ✅ 合理 |
| `PackageStore.razor:94` | `pa-4` | ✅ 合理 |

### 6.2 文本溢出处理

| 位置 | 处理方式 | 评估 |
|------|------|------|
| `MatchCard.razor:9` | `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` | ✅ 优秀 |
| `MatchCard.razor:10` | 同上 | ✅ 优秀 |
| `SyncStatus.razor:13` | 同上 | ✅ 优秀 |
| `PackageStore.razor:45` | `Description.Substring(0, 100) + "..."` | ⚠️ 用 C# 截断而非 CSS，多字节字符可能截断不完整 |

### 6.3 响应式布局

| 位置 | 处理方式 | 评估 |
|------|------|------|
| `Index.razor:68-75` | `MudGrid` + `MudItem xs="6"` | ✅ 两列布局，小屏自适应 |
| `PackageStore.razor:39` | `MudItem xs="12" sm="6" md="4"` | ✅ 三档响应式 |
| `TopBar.razor:20,26` | `d-none d-sm-flex` | ✅ 小屏隐藏非关键元素 |
| `SyncStatus.razor:15,28` | `d-none d-sm-flex` | ✅ 小屏隐藏 Chip |
| `MainLayout.razor:12` | `MaxWidth="MaxWidth.Medium"` | ✅ 限制最大宽度 |

### 6.4 滚动行为

| 位置 | 机制 | 评估 |
|------|------|------|
| `Index.razor:168-179` | 懒加载 100 条 + LoadMore 按钮 | ✅ 良好 |
| `SyncSettings.razor` | Tab 分区，内容不过长 | ✅ 良好 |
| `PackageStore.razor` | MudGrid 网格布局 | ✅ 良好 |

### 6.5 空状态与边界情况

| 位置 | 处理方式 | 评估 |
|------|------|------|
| `Index.razor:188-193` | 无 Match 时显示引导文案 | ✅ 良好 |
| `Index.razor:181-186` | 搜索无结果时显示提示 | ✅ 良好 |
| `PackageStore.razor:29-33` | 无包时显示提示 | ✅ 良好 |
| `PackageStore.razor:84-89` | 无已安装包时显示提示 | ✅ 良好 |
| `ConflictResolver.razor:13-21` | 无冲突 + 全部解决状态 | ✅ 良好 |
| `Index.razor:22-27` | 初始加载 loading spinner | ✅ 良好 |

---

## 七、与 Kotlin/Compose 版本对比

项目中同时存在 Kotlin/Compose UI（`app/src/main/java/.../ui/`）和 Blazor UI（`src/`）。对比关键差异：

| 功能 | Blazor (当前使用) | Kotlin/Compose (遗留?) |
|------|:---:|:---:|
| 变量类型选择器 | ❌ 自由文本输入 | ✅ BottomSheet 选择器 |
| 变量编辑器（按类型） | ❌ 通用 5 字段 | ✅ 13 种类型独立表单 |
| 变量排序 | ❌ | ✅ 上/下按钮 |
| 变量删除确认 | ❌ | ✅ AlertDialog |
| Trigger 多触发词 | ❌ 单 Trigger | ✅ FlowRow + AssistChip |
| Regex 匹配模式 | ❌ | ✅ 支持 |
| Word Boundary 选项 | ❌ 仅 Word 复选框 | ✅ Off/Full/Left/Right |
| Propagate Case | ❌ | ✅ Switch |
| 替换预览 | ❌ | ✅ 实时预览 |
| Match 变量计数 | ✅ Badge | ❌ |
| 文本溢出处理 | ✅ CSS ellipsis | ❌ 无 maxLines |
| loading 状态 | ✅ 完善 | ❌ 缺失 |
| i18n 覆盖 | ✅ ~95% | ❌ ~0% |
| 空状态 | ✅ 完整 | ❌ 缺失 |
| 搜索防抖 | ✅ 300ms | ❌ 无 |

**结论**：Blazor 版本在 i18n、loading、空状态、布局健壮性方面优于 Kotlin 版本，但变量编辑器功能严重不足。

---

## 八、优化建议优先级

### 第一优先：变量编辑器重构 (P0-1, P1-7, P1-8)
- 实现变量类型选择器（MudSelect 或 MudList）
- 按类型渲染对应表单字段（参考 Kotlin 版本的 13 种类型）
- 添加变量删除确认 Dialog
- 添加变量排序功能

### 第二优先：代码拆分 (P1-1, P2-11)
- 将 Index.razor 拆分为：
  - `MatchEditor.razor` — 编辑器组件
  - `MatchList.razor` — 列表 + 搜索
  - `ImportExportService.cs` — 导入导出逻辑
- 将 `ProcessImport` 移到服务层

### 第三优先：交互优化 (P1-2, P1-3, P1-5)
- 删除 Match 后显示带 "Undo" 的 Snackbar
- PackageStore 搜索添加 `DebounceInterval="300"`
- 修正 "tommorow" → "tomorrow"

### 第四优先：UX 增强 (P2-4, P2-5, P2-8)
- ConflictResolver 添加批量操作
- TextTools 结果添加复制按钮
- SyncSetupWizard 添加步骤指示器

### 第五优先：性能优化 (P2-3, 4.6)
- PackageStore 安装/卸载按单个包禁用
- Index.razor `OnInitializedAsync` 中文件 I/O 改为异步
- `_filteredDict` 添加缓存

---

## 九、总结

Expandroid Blazor UI 综合评分 **7.4/10 (B+)**，整体完成度较高。

**三大亮点：**
1. **i18n 覆盖率 ~95%** — 几乎所有用户可见文本通过 `LocalizationService` 国际化
2. **交互反馈完善** — 所有异步操作有 loading + disabled + Snackbar/Dialog 反馈
3. **信息架构清晰** — TopBar 全局导航 + 路由可达 + Tab 分区 + 智能跳转

**三大短板：**
1. **变量编辑器功能不足** — 仅 5 个通用字段，无法配置 shell/http/js 等高级类型，与后端能力严重不匹配
2. **Index.razor 过大** — 1021 行包含 CRUD + 导入导出 + 同步 + SAF 观察者，需拆分
3. **缺少 undo/确认** — 删除 Match 无 undo，删除变量无确认

**高优先级 Quick Wins：**
- PackageStore 搜索添加 `DebounceInterval="300"` — 1 行
- 修正 "tommorow" → "tomorrow" — 2 处
- 变量删除添加确认 Dialog — ~10 行
- PackageStore 按单个包禁用按钮 — ~5 行
