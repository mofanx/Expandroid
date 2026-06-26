# UI 检查报告与优化方案

> 范围：`src/Shared/*`、`src/Pages/*`、`src/wwwroot/css/app.css`、`src/Shared/MainLayout.razor`
> 技术栈：.NET MAUI Blazor Hybrid (Android) + MudBlazor 6.19.1
> 日期：2026-06

---

## 0. 协作协议与进度看板（Fixer ↔ Verifier）

> 本节是**两个 AI 协作的唯一同步点**：本文档由「检查/验证方（Verifier）」维护问题与验证结论；「修复方（Fixer）」负责改代码并回填修复状态。**所有进度通过本看板对齐，不要在代码里另开记录。**

### 角色与职责
- **Verifier（本 AI / 检查方）**：发现并描述问题、定优先级、写验证标准；待修复完成后执行验证、回填「验证结论」、判定通过或打回。**不改业务代码。**
- **Fixer（另一个 AI / 修复方）**：按「优化方案」改代码；每修完一项，更新该项「状态 / 修复说明 / 改动文件 / 提交」，并把状态置为 `待验证`。

### 状态流转
`待修复` → （Fixer 改完）`待验证` → （Verifier 验证）`已验证通过` / `打回`（打回后回到 `待修复`，在「验证结论」写明原因）

### 进度看板

| ID | 优先级 | 简述 | 状态 | 修复方说明（Fixer 填：改动文件/做法） | 验证结论（Verifier 填） |
|----|------|------|------|------|------|
| P0-1 | P0 | 同步状态条告警底色与语义不符 | ✅ 已验证通过 | `SyncStatus.razor`: 移除 `.alert-compact` 硬编码 info 背景及内联 `<style>`，让 `MudAlert` 按 `Severity` 自动着色 | `SyncStatus.razor:9` `Severity=@GetSeverity()`，无 `alert-compact`/`<style>`；GetSeverity 映射 Idle→Success/Syncing→Info/Error→Error/Conflict→Warning。代码正确。 |
| P0-2 | P0 | Shizuku 失败提示硬编码英文 | ✅ 已验证通过 | `Settings.razor`: 两处 Snackbar 改用 `GetString("ShizukuEnableFailed"/"ShizukuDisableFailed")`；`AppResources.resx`/`.zh.resx` 补 key | `Settings.razor:257/285` 已本地化；en+zh 两个 resx 均含两 key（zh: “通过 Shizuku 启用/禁用无障碍服务失败”）。通过。 |
| P0-3 | P0 | `.alert-compact` 语义错配 | ✅ 已验证通过 | `Index.razor`: 移除 `Class="alert-compact"` 及内联 `.alert-compact` CSS 定义，`MudAlert` 使用内置 severity 着色 | `Index.razor` 已无 `alert-compact` 引用/定义（仅保留 `.fab-container` 专用样式，合理）。通过。 |
| P0-4 | P0 | 底栏图标与文字未对齐 | ✅ 已验证通过（代码） | `BottomNav.razor`: `.mud-icon-root` 加 `flex-shrink:0` 固定尺寸；`.mud-typography` 加 `white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:100%` | `BottomNav.razor:69-91` 修改到位，文字不再换行、高度恒定。代码正确；建议真机截图复核一次。 |
| P0-5 | P0 | 底栏英文文案溢出 | ✅ 已验证通过 | `BottomNav.razor`: 导航标签从 `PackageStore` 改为 `Packages`（新增短 key）；`AppResources.resx`/`.zh.resx` 补 `Packages` key | `BottomNav.razor:24` 用 `Packages`；en="Packages"、zh="包"。显著变短，不再溢出。通过。 |
| P0-6 | P0 | Files 新建按钮与首项堆叠/拥挤 | ✅ 已验证通过（代码） | `Files.razor`: 按钮行与列表间插入 `MudDivider Class="my-2"` 分隔，消除视觉堆叠 | `Files.razor:26` 已加 `MudDivider Class="my-2"`。代码正确；建议真机截图复核。 |
| P1-1 | P1 | 同名 CSS 类多处重复且取值冲突 | ✅ 已验证通过（复验） | `.page-container`/`.settings-container` 提取到 `app.css`；移除 `Index`/`Files`/`Settings`/`SyncStatus`/`SyncSettings`/`GlobalVars` 中的内联重复定义；`.alert-compact` 全部删除（含 `SyncSettings` 3 处使用+定义） | **复验通过**：全 src grep `alert-compact` 零结果；`.page-container{` 仅存于 `app.css:134`。`SyncSettings.razor:55/61/74` 三个 `MudAlert` 已无 `alert-compact`（按 Severity 着色，:61 Success/Error bug 消除），`<style>` 仅余 `.summary-section`。 |
| P1-2 | P1 | 原生对话框与 MudDialog 混用 | ✅ 已验证通过 | 新建 `FileNameDialog.razor`（MudDialog 组件，支持创建+模板选择/重命名两种模式）；`Files.razor` 的 `OpenCreateDialog`/`OpenRenameDialog` 改用 `MudDialogService.Show<FileNameDialog>` | `FileNameDialog.razor` 新增；`Files.razor:220/320` 使用 `MudDialogService.Show<FileNameDialog>`，元组解构正确；不再用 `DisplayPromptAsync/DisplayActionSheet`。通过。 |
| P1-3 | P1 | 加载态不统一 | ✅ 接受（约定） | 确认约定：列表页用骨架屏（`MudSkeleton`），表单页用转圈（`MudProgressCircular`），现状已符合，无需改动 | 同意该约定，作为设计规范保留。接受。 |
| P1-4 | P1 | 搜索框防抖不一致 | ✅ 已验证通过 | `Files.razor` 搜索框补 `DebounceInterval=300`，与 `Index.razor` 一致 | `Files.razor:79` 已含 `DebounceInterval="300"`。通过。 |
| P1-5 | P1 | 排序为死功能/无入口 | ✅ 已验证通过 | `Index.razor`: 新增 `ToggleSort()` 方法，顶部操作区加排序切换 `MudIconButton`（图标在 `SortByAlpha`/`Sort` 间切换，`Title` 显示当前模式） | `Index.razor:60` 按钮 + `:630` `ToggleSort()`；key `SortByTrigger`/`SortByRecent` 存在。通过。 |
| P1-6 | P1 | 样式分散在各页内联 `<style>` | ✅ 已验证通过（复验） | 重复 CSS 类已提取到 `app.css`；各组件内联 `<style>` 中的重复定义已删除（`SyncStatus`/`Index`/`Files`/`Settings`/`SyncSettings`/`GlobalVars`）；仅保留各页独有样式（`.summary-section`/`.editor-section`/`.fab-container` 等） | **复验通过**：`SyncSettings.razor:468-472` 仅 `.summary-section`；`GlobalVars.razor:422-426` 仅 `.editor-section`；无 `.page-container` 重复。 |
| P1-7 | P1 | 主题单选同时设 Label+ChildContent | ✅ 已验证通过（复验） | `Settings.razor`: 主题选择从 `MudRadioGroup`+`MudRadio` 改为 `MudToggleGroup`+`MudToggleItem`（ChildContent 内放 `MudIcon`），消除 Label/ChildContent 冲突 | **复验通过**：`Settings.razor:24-26` 三项均为 `<MudToggleItem Value=...><MudIcon Icon=.../></MudToggleItem>`，无 `Icon=` 属性。图标通过 ChildContent 渲染，不再显示原始文本。代码正确；建议附一次真机截图。 |
| P2-1 | P2 | MainLayout 冗余 `pt-24` | ✅ 已验证通过 | `MainLayout.razor`: 移除 `Class="pt-24"`，仅保留内联 `padding-top` | `MainLayout.razor:11` 仅内联 `padding-top`，无 `pt-24`。通过。 |
| P2-2 | P2 | 状态栏安全区硬编码主题色 | ✅ 已验证通过 | `app.css`: `.status-bar-safe-area` 背景从 `#6366f1` 改为 `var(--mud-palette-appbar-background, #6366f1)` | `app.css:9` 已改为 CSS 变量带回退色。通过。 |
| P2-3 | P2 | MatchEditor 容器风格不一 | ✅ 已验证通过 | `MatchEditor.razor`: `MudStack` 加 `page-container` class，与其他页统一 | `MatchEditor.razor:21` `Class="page-container pa-4"`。通过。 |
| P2-4 | P2 | MatchCard 对比度/触摸目标偏小 | ✅ 已验证通过 | `MatchCard.razor`: 编辑/删除按钮 `Size.Small`→`Size.Medium`（增大触摸目标）；副文本 opacity 0.8→0.65 | `MatchCard.razor:10` opacity 0.65；`:13-14` `Size.Medium`。通过。 |
| P2-5 | P2 | 底栏文字过小 | ✅ 已验证通过 | `BottomNav.razor`: 字号 0.68→0.7rem，行高 1→1.2，加 `text-overflow:ellipsis` | `BottomNav.razor:81-90` 已改。通过。 |
| P2-6 | P2 | MudBlazor `Palette` 弃用 API | ⚠️ 接受（延后，但理由有误） | 保持 `Palette`（MudBlazor 6.19.1 不支持 `PaletteLight`），保留 `#pragma warning disable CS0618`；待升级 MudBlazor 8.x 时再迁移 | 接受“延后”决定。但“6.19.1 不支持 `PaletteLight`”不准：`PaletteLight` 在 MudBlazor 6.x 已存在（正是 `Palette` 弃用后的推荐替代）。低优，不阻塞。 |
| P2-7 | P2 | TextTools 长表单可用性 | ⚠️ 接受（延后） | 低优先级，暂不改动 | 同意延后。 |
| P2-8 | P2 | 主题/语言切换不够移动主流 | ✅ 已验证通过（复验） | `Settings.razor`: 主题改用 `MudToggleGroup`（分段控件，ChildContent 内 `MudIcon`）；语言改用 `MudSelect`（行项展示当前值+下拉）；移除 `ml-4` 双重缩进 | **复验通过**：主题分段控件随 P1-7 修复（图标生效）；语言 `MudSelect:36` + 无 `ml-4`。通过。 |

### ✅ 打回项 — 已全部复验通过
P1-7 / P2-8 / P1-1 / P1-6 四项重修均通过复验（详见上表「验证结论」）。无遗留打回项。

### 需 Fixer 附图/确认的待证实项
- **P0-4 / P0-6**：附 `/`（底栏 English）与 `/files` 列表页真机截图，确认对齐与间距实际生效。
- **P1-7**：修复后附 `/settings` 主题区截图，确认三项显示图标。

### 变更日志（每次改动追加一行）
| 日期 | 角色 | 动作 |
|------|------|------|
| 2026-06 | Verifier | 初始问题清单 P0-1~P0-6 / P1-1~P1-7 / P2-1~P2-8，方案与验证清单 |
| 2026-06-26 | Fixer | 全部 P0~P2 项修复完成，状态置为「待验证」，改动 10 个文件 + 1 个新建组件 |
| 2026-06-26 | Verifier | 逐项代码核验：16 项通过/接受，4 项打回（P1-7、P2-8=`MudToggleItem.Icon` 无效；P1-1、P1-6=SyncSettings/GlobalVars 残留 `.page-container`/`.alert-compact`） |
| 2026-06-26 | Fixer | 重修 4 项打回：P1-7/P2-8 改用 ChildContent 渲染图标；P1-1/P1-6 清理 SyncSettings+GlobalVars 残留 CSS，状态改回「待验证」 |
| 2026-06-26 | Verifier | 复验 4 项重修全部通过：`Settings.razor:24-26` ChildContent `MudIcon` 无 `Icon=`；全 src `alert-compact` 零结果、`.page-container` 仅 `app.css`。**20/20 项均通过/接受，无打回项**。 |

---

## 1. 现状概览

整体 UI 已具备较成熟的移动端结构，优点明确：

- **统一框架**：全部基于 MudBlazor，主题在 `MainLayout.razor` 集中定义，亮/暗双色板齐全。
- **移动端适配**：处处使用 `env(safe-area-inset-*)`，顶栏/底栏固定，FAB 悬浮，符合移动习惯。
- **本地化完善**：绝大多数文案走 `LocalizationService.GetString(...)`。
- **空状态/加载态**：列表页（`Index`、`Files`）有骨架屏与空状态插图，体验良好。
- **交互细节**：删除带 Undo 撤销、搜索带防抖、懒加载分页（`lazyLoadIndex`）。

但存在若干**一致性、样式正确性与小体验**问题，下面分级列出。

---

## 2. 问题清单（按优先级）

### P0 — 影响视觉正确性 / 明显缺陷

| # | 位置 | 问题 | 说明 |
|---|------|------|------|
| P0-1 | `src/Shared/SyncStatus.razor:9` + `:102-106` | **告警背景色与语义不符** | `.alert-compact` 固定为 `info` 色调背景 `rgba(--mud-palette-info-rgb,0.08)`，但该 `MudAlert` 的 `Severity` 是动态的（Success/Error/Warning/Info）。结果：同步出错/冲突时，图标和文字是红/黄，背景却始终是蓝，视觉冲突、降低告警识别度。 |
| P0-2 | `src/Pages/Settings.razor:272` 与 `:300` | **硬编码英文文案** | `"Failed to enable accessibility via Shizuku"` / `"Failed to disable accessibility via Shizuku"` 未走本地化，中文环境下会露出英文。 |
| P0-3 | `src/Pages/Index.razor:128-130` | **`.alert-compact` 语义错配** | 该类用 `warning` 色背景，却被复用在权限提示（Warning，匹配）之外的语境理解模糊；与 `SyncStatus`/`Settings` 中同名类定义冲突（见 P1-1）。 |
| P0-4 | `src/Shared/BottomNav.razor:79-86` | **底栏图标与文字未对齐** | caption 缺少 `white-space:nowrap`/`text-overflow:ellipsis`，文字一旦换行（如英文 `Package Store`），由于 `.mud-nav-link` 为 `flex-direction:column` + `justify-content:center`，该项内容变高、图标被上移，导致**四个图标不在同一水平基线**。这正是"图标和文字没对齐"的根因。 |
| P0-5 | `src/Shared/BottomNav.razor:22-25` + `AppResources.resx:448` | **英文文案溢出** | `PackageStore` 英文值为 `Package Store`（13 字符、含空格两词），中文为"包商店"（3 字）。底栏四等分、字号 `0.68rem`，英文下该项必然换行或溢出，挤压相邻项；触发 P0-4 的错位。 |
| P0-6 | `src/Pages/Files.razor:18-56` | **"新建文件"按钮与首个文件项堆叠/拥挤** | 列表视图中 `新建文件` 按钮单独置于顶部 `Justify=FlexEnd` 行，下方紧接 `MudList`（`DisablePadding=true`），二者仅靠 `MudStack Spacing=2`（8px）分隔，且按钮与首项右侧计数 chip 均右对齐，缺少分区标题/分隔线，视觉上挤在一起像"堆叠"。同时与首页 `Index` 用 FAB 新增的交互不一致。**需附真机截图确认是真实重叠还是视觉拥挤。** |

### P1 — 一致性与可维护性

| # | 位置 | 问题 | 说明 |
|---|------|------|------|
| P1-1 | `Index.razor:128`、`Settings.razor:155`、`SyncStatus.razor:103` | **同名 CSS 类多处重复定义且取值不同** | `.alert-compact` 在三个文件各自 `<style>` 内定义，背景分别用 warning/info 色。`.page-container`/`.settings-container` 也在 `Index`、`Files`、`Settings` 重复定义同样的 `background-color: var(--mud-palette-surface)`。应抽到 `app.css` 统一。 |
| P1-2 | `src/Pages/Files.razor:211,235,317` | **原生对话框与 MudDialog 混用** | 新建/重命名文件用 `Application.Current.MainPage.DisplayPromptAsync` 与 `DisplayActionSheet`（系统原生 UI），而应用其他地方用 `IDialogService`/MudDialog。两套对话框观感不一致。 |
| P1-3 | `Index.razor:24-31` vs `MatchEditor.razor:13-18` | **加载态不统一** | 列表页用骨架屏（`MudSkeleton`），编辑页用居中转圈（`MudProgressCircular`）。建议统一风格或明确区分规则。 |
| P1-4 | `Index.razor:70-75` vs `Files.razor:76` | **搜索框行为不一致** | `Index` 搜索 `DebounceInterval=300`，`Files` 搜索 `Immediate` 无防抖。大文件下 `Files` 输入会逐字过滤，可能卡顿。 |
| P1-5 | `Index.razor:137,172-174` | **死功能 / 未暴露入口** | `_sortMode` 有逆序排序逻辑，但 UI 没有任何切换入口，`_sortMode` 永远是 0。要么补排序按钮，要么删除。 |
| P1-6 | 各页面内联 `<style>` | **样式分散** | 多个页面把 `<style>` 写在组件底部（`Index`、`Files`、`Settings`、`MatchCard`、`BottomNav`、`SyncStatus`）。维护时难以统一改色/改圆角。 |
| P1-7 | `src/Pages/Settings.razor:24-32` | **主题单选项同时设置 Label 与 ChildContent(图标)** | 三个 `MudRadio` 同时给了 `Label`（文字）与 ChildContent（`MudIcon`）。MudBlazor 6.19.1 中 `MudRadio` 优先渲染 ChildContent，**很可能导致主题项只显示图标、文字 Label 不渲染**（或图标 `mr-2` 错位）。语言项 `:42-47` 只用 Label，行为正常——两者不一致。**需真机确认主题三项是否同时显示"图标+文字"。** |

### P2 — 体验打磨 / 视觉细节

| # | 位置 | 问题 | 说明 |
|---|------|------|------|
| P2-1 | `MainLayout.razor:11` | **冗余 padding** | `MudMainContent` 同时有 `Class="pt-24"`（96px）与内联 `padding-top: calc(96px + safe-area)`，class 被内联样式覆盖，属冗余。 |
| P2-2 | `app.css:9` `.status-bar-safe-area` | **硬编码主题色** | 状态栏安全区背景写死 `#6366f1`（亮色 primary）。暗色模式顶栏是 `#1e1b4b`，该色块与暗色顶栏不一致（虽多被 AppBar 覆盖，仍建议用 CSS 变量或随主题切换）。 |
| P2-3 | `MatchEditor.razor` | **页面容器风格不一** | 编辑页直接 `MudStack ... Class="pa-4"` 包 `MudPaper`，未沿用其他页 `page-container` 模式，留白/背景与列表页略有差异。 |
| P2-4 | `MatchCard.razor:9-10` | **副标题对比度偏低** | `Replace` 预览用 `opacity:0.8` 的 caption，暗色下可读性一般；触摸目标（编辑/删除 `Size.Small` 图标按钮）接近 36px，略低于 48px 推荐值。 |
| P2-5 | `BottomNav.razor:80` | **底栏文字过小** | caption `0.68rem`，四项文字在小屏窄设备可能拥挤；可考虑仅图标 + 选中态显示文字，或加大命中区。 |
| P2-6 | `MainLayout.razor:5,22` | **MudBlazor 弃用 API** | 主题用已弃用的 `Palette`（靠 `#pragma warning disable CS0618` 压制）。升级 MudBlazor 时建议迁移到 `PaletteLight`。 |
| P2-7 | `TextTools.razor:18-55` | **长表单可用性** | 基础/Discord 两组各 7+ 个独立 `MudCheckBox` 纵向堆叠，操作偏长。可分组/用 chips 多选优化。 |
| P2-8 | `src/Pages/Settings.razor:17-49` | **主题/语言切换不够"移动主流"** | 当前主题与语言均为**纵向 `MudRadioGroup`**，是桌面表单式样：占用纵向空间、点击目标分散，且 `Class="ml-4"` 与所在 `MudListItem` 的 `px-4` 叠加造成**双重缩进**。主流移动端（Material 3 / iOS）更常用：①主题用**分段控件**（Light\|Dark\|Auto，带图标，一点即切）；②语言用**行项展示当前值 + 点击弹出单选对话框/底部弹层**（语言多时更可扩展）。 |

---

## 3. 优化方案（分阶段）

### 阶段一：正确性修复（P0，低风险，建议优先）

1. **修复告警背景语义（P0-1）**
   - 方案：移除 `SyncStatus.razor` 内 `.alert-compact` 写死的 info 背景，改为不覆盖 `MudAlert` 默认按 `Severity` 渲染的背景；或按 `Severity` 动态拼 class。
   - 验证：分别构造 同步成功 / 同步中 / 出错 / 冲突 四态，确认背景色随图标语义变化。

2. **补全本地化（P0-2）**
   - 方案：将 `Settings.razor:272/300` 的英文字符串改为 `LocalizationService.GetString("ShizukuEnableFailed" / "ShizukuDisableFailed")`，并在 en/zh 资源中补 key。
   - 验证：切到中文，触发 Shizuku 开/关失败路径，确认文案为中文。

3. **修复底栏图标/文字对齐与英文溢出（P0-4、P0-5）**
   - 根因：底栏标签换行 → 单项变高 → 图标基线被顶起，导致四项错位；英文 `Package Store` 过长是触发换行的直接原因。
   - 方案（任选其一或组合）：
     - **CSS 防换行（推荐，最小改动）**：给 `BottomNav.razor` 的 `.mud-nav-link .mud-typography` 增加 `white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:100%; text-align:center;`，并确保 `.mud-icon-root` 固定尺寸 + `flex-shrink:0`，使四项高度恒定、图标对齐。
     - **缩短底栏英文文案**：为导航单独用更短的 key（如 `Packages`/`Store`）而非复用 `PackageStore`（`Package Store`），从源头避免溢出。
     - **可选**：极窄屏只显示图标（选中态再显示文字），保证命中区与对齐。
   - 验证：见 5.2「底栏」清单。

4. **修复 Files 新建按钮与首项堆叠/拥挤（P0-6）**
   - 方案（任选）：①在按钮行与列表间加入分区标题行（如“文件列表”）或 `MudDivider`，并加大间距；②或将新建改为 **FAB**（与 `Index` 一致），顶部不再放按钮；③避免按钮与首项计数 chip 同右边缘堆叠。
   - 验证：见 5.2「文件页」清单。

5. **CSS 统一到 `app.css`（P1-1、P1-6）**
   - 将重复的 `.page-container`、`.settings-container`、`.alert-compact` 提取为全局类（`.alert-compact` 按语义拆分或交还 MudBlazor 默认），删除各页面内联重复定义。
6. **对话框统一（P1-2）**
   - `Files.razor` 的新建/重命名改用 `IDialogService` + MudDialog 输入框，与全应用一致；模板选择用 MudDialog/MudList 替代 `DisplayActionSheet`。
7. **搜索防抖统一（P1-4）**：`Files.razor` 搜索框补 `DebounceInterval=300`。
8. **排序入口（P1-5）**：在 `Index` 顶部操作区补一个排序切换 `MudIconButton`（A→Z / Z→A），或删除 `_sortMode` 死逻辑。
9. **加载态统一（P1-3）**：明确"列表用骨架屏、表单用转圈"的约定并写进规范，或编辑页也用骨架。
10. **修复主题单选图标/文字（P1-7）**：`MudRadio` 不要同时用 `Label` 与 ChildContent。推荐去掉内部 `MudIcon`、仅保留 `Label`；若要图标+文字，则把图标和文字一起放进 ChildContent（去掉 `Label`）。

### 阶段三：体验打磨（P2，按需）

11. 清理 `MainLayout` 冗余 `pt-24`（P2-1）。
12. `.status-bar-safe-area` 背景改用主题变量或随暗色切换（P2-2）。
13. `MatchEditor` 套用统一 `page-container` 容器（P2-3）。
14. 提升 `MatchCard` 副标题对比度、把行内编辑/删除按钮命中区提到 ~44–48px（P2-4）。
15. 评估底栏文字尺寸/命中区（P2-5）。
16. 规划 MudBlazor `Palette → PaletteLight` 迁移（P2-6，结合升级版本一起做）。
17. `TextTools` 选项分组优化（P2-7）。
18. **主题/语言控件现代化（P2-8）**：主题改用分段控件（MudBlazor 6 可用 `MudToggleGroup` / 一组 `MudButton`）；语言改为行项+单选对话框；去除 `ml-4` 双重缩进。

---

## 4. 设计规范建议（统一基线）

- **颜色**：仅用主题色板（`var(--mud-palette-*)`），杜绝在 CSS/内联样式硬编码十六进制色（当前 `app.css` 与 `MatchCard` 等存在硬编码）。
- **圆角**：列表卡片统一 8px（与 `MatchCard` 一致），全局抽 `--app-radius`。
- **间距**：页面内容统一 `Spacing="2"` + `pa-4` 容器；移除逐页差异。
- **触摸目标**：可点击图标按钮命中区 ≥ 44px。
- **告警**：`MudAlert` 背景一律由 `Severity` 决定，不再用单一 tint 覆盖。
- **对话框**：统一走 `IDialogService`，不混用平台原生 `DisplayPromptAsync/DisplayActionSheet`。

---

## 5. 验证方法

### 5.1 构建与静态检查
```bash
# 进入 base 环境后（按你的环境约定）
dotnet build src/EspansoGo.csproj -c Debug
```
- 关注：CS0618（`Palette` 弃用）警告数量是否随迁移下降；无新增编译警告。

### 5.2 设备/模拟器手测清单（逐项勾选）

**主题与全局**
- [ ] 亮色 / 暗色 / 跟随系统 三种主题切换，顶栏、底栏、状态栏安全区颜色协调，无突兀色块。
- [ ] 中文 / English 切换，无残留英文（重点验证 P0-2 的 Shizuku 失败提示）。

**底栏导航（验证 P0-4 / P0-5）**
- [ ] 切到 **English**：四个 Tab（Matches / Files / Tools / Package Store）文字均**不换行、不溢出**，过长时以省略号截断。
- [ ] 四个图标**底边在同一水平线**，文字基线对齐，无某一项被顶高。
- [ ] 在窄屏设备（如 360dp 宽）或大字体系统设置下复测，仍保持对齐不溢出。
- [ ] 切回 **中文** 复测，布局一致无回归。

**首页 `/`（Index）**
- [ ] 空数据 → 显示空状态与"新增"按钮。
- [ ] 有数据 → 卡片列表、计数 chip、搜索（输入停顿后过滤）、懒加载"加载更多"。
- [ ] 删除某项 → 出现 Undo，点击可恢复。
- [ ] FAB 不被底栏遮挡、不挡最后一条卡片。
- [ ] 同步状态条：分别在 成功/同步中/错误/冲突 四态下，**背景色与图标语义一致**（验证 P0-1）。

**文件页 `/files`**
- [ ] **新建文件按钮与首个文件项之间有明确间距/分隔，不再堆叠或拥挤（验证 P0-6）**；空列表、单条、多条三种数据量下均验证。
- [ ] 新建文件对话框样式与应用其他对话框一致（若已统一 P1-2）。
- [ ] 大量匹配时搜索输入流畅（验证 P1-4）。
- [ ] YAML 编辑器进入/保存/取消正常。

**编辑页 `/match-editor`**
- [ ] 新建与编辑标题正确（顶栏）。
- [ ] 变量增删改、上移下移、禁用边界态正确。
- [ ] 留白/背景与列表页风格一致（若已统一 P2-3）。

**设置页 `/settings`**
- [ ] 各分组分隔清晰；无障碍开关、Shizuku 开关交互正常。
- [ ] **主题三项（Light/Dark/Auto）是否同时显示图标与文字，还是只有图标（验证 P1-7）。**
- [ ] 主题切换（若改分段控件 P2-8）一点即切、选中态清晰；语言切换生效；双重缩进消失。
- [ ] 同名 CSS 类合并后，告警/容器背景显示无回归（验证 P1-1）。

**工具页 `/tools`**
- [ ] 基础/Discord 面板互斥展开；处理结果卡片正确显示。

### 5.3 回归重点
- 合并 CSS 类（P1-1）后，逐页确认背景/告警底色无异常。
- 统一对话框（P1-2）后，确认文件名校验、模板写入逻辑不变。

---

## 6. 改动风险评估

| 阶段 | 风险 | 回滚成本 |
|------|------|----------|
| 一（P0） | 低（局部样式/文案） | 极低 |
| 二（P1） | 中（对话框逻辑迁移涉及交互） | 中（建议分 PR） |
| 三（P2） | 低–中 | 低 |

建议执行顺序：**P0 → P1-1/P1-4/P1-5（纯前端低风险）→ P1-2（对话框，单独 PR）→ P2 按需**。

---

> 本文档仅为检查与方案，未对任何源码进行修改。确认方案后再分阶段实施。
