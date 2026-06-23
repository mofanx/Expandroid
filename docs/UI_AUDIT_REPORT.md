# Expandroid UI 交互审查与重构方案

**审查日期**：2026-06-23  
**审查范围**：Phase 1-3 全部已实现功能 vs 全部 UI 页面/组件  
**审查方法**：逐文件对照后端公开 API 与前端页面/组件，检查可达性、一致性、i18n、交互反馈  
**审查目标**：统一设计、全局重构、可溯源、可检验

---

## 一、现有 UI 架构全景

### 1.1 页面路由清单

| 路由 | 文件 | 行数 | 功能 | 入口来源 | 可达性 |
|------|------|------|------|---------|--------|
| `/` | `Pages/Index.razor` | 819 | 主页：文本扩展器 + 文本工具 | 应用启动 | ✅ 默认页 |
| `/syncsettings` | `Pages/SyncSettings.razor` | 230 | 同步配置 + 状态 + 测试 | SyncStatus 点击 | ✅ 可达 |
| `/syncsetup` | `Pages/SyncSetupWizard.razor` | 191 | 同步设置向导（3 步） | Index Sync 按钮（仅未配置时） | ⚠️ 条件触发 |
| `/packages` | `Pages/PackageStore.razor` | 185 | Hub 包商店 | SyncSettings 底部按钮 | ⚠️ 入口隐蔽 |
| `/conflicts` | `Pages/ConflictResolver.razor` | 85 | 冲突解决 | **无入口** | ❌ 不可达 |

### 1.2 共享组件清单

| 组件 | 文件 | 用途 | 使用位置 |
|------|------|------|---------|
| `MainLayout` | `Shared/MainLayout.razor` | 全局布局 + 主题 | 所有页面 |
| `TopBar` | `Shared/TopBar.razor` | 顶栏：应用名 + 主题 + 语言 | MainLayout |
| `SyncStatus` | `Shared/SyncStatus.razor` | 同步状态条 | Index.razor |
| `MatchCard` | `Shared/MatchCard.razor` | 单条匹配规则卡片 | Index.razor |
| `LanguageSwitcher` | `Shared/LanguageSwitcher.razor` | 语言切换下拉 | TopBar |
| `TextTools` | `Pages/TextTools.razor` | 文本工具 Tab | Index.razor Tab |

### 1.3 i18n 覆盖状态

| 页面/组件 | 使用 `LocalizationService` | i18n 覆盖率 |
|-----------|--------------------------|------------|
| `Index.razor` | ⚠️ 部分 | ~85%（Sync 按钮文本、导入模式对话框、文件选择器标题、同步结果 Snackbar 等硬编码英文） |
| `TextTools.razor` | ✅ 是 | 100% |
| `MainLayout.razor` | ❌ 无文本 | N/A |
| `TopBar.razor` | ❌ 无文本 | N/A |
| `SyncStatus.razor` | ❌ 硬编码英文 | 0% |
| `SyncSettings.razor` | ❌ 硬编码英文 | 0% |
| `SyncSetupWizard.razor` | ❌ 硬编码英文 | 0% |
| `PackageStore.razor` | ❌ 硬编码英文 | 0% |
| `ConflictResolver.razor` | ❌ 硬编码英文 | 0% |
| `MatchCard.razor` | ❌ 无文本 | N/A |

**AppResources.resx** 现有 key 数量：约 60 个（全部为 Phase 1 功能）  
**AppResources.zh.resx** 现有 key 数量：约 60 个（与英文对齐）  
Phase 2-3 新增页面 + Index.razor 遗漏项需要新增约 70 个 i18n key（详见 4.8 节）。

---

## 二、后端已实现功能 vs UI 暴露 全量对照

### 2.1 SyncManager 公开 API 对照

| 后端 API | 功能 | UI 调用位置 | 暴露状态 |
|---------|------|-----------|---------|
| `GetConfig()` | 获取同步配置 | SyncSettings, SyncStatus, Index | ✅ |
| `UpdateConfig()` | 保存同步配置 | SyncSettings SaveConfig, SetupWizard FinishSetup | ✅ |
| `PushAsync()` | 推送本地到远程 | Index SaveDictAsync | ✅ |
| `PullAsync()` | 拉取远程到本地 | SyncAsync 内部调用 | ✅ 隐含 |
| `SyncAsync()` | 完整 Pull+Merge+Push | Index SyncNowAsync, SyncSettings SyncNow | ✅ |
| `TestConnectionAsync()` | 测试 WebDAV 连接 | SyncSettings TestWebDavConnection, SetupWizard TestWebDavConnection | ✅ |
| `CheckChanges()` | 检测远程变化 | SafObserver, WebDavPollingService 内部 | ✅ 隐含 |
| `ReadSyncedDataAsync()` | 读取同步数据 | SyncAsync 内部调用 | ✅ 隐含 |
| `GetConflictFiles()` | 获取冲突文件列表 | ConflictResolver LoadConflicts | ✅ 但页面不可达 |
| `ResolveConflict()` | 解决单个冲突 | ConflictResolver KeepLocal/KeepRemote | ✅ 但页面不可达 |
| `GitHasRemoteChangesAsync()` | Git 远程变化检测 | Index.razor Timer | ✅ |
| `IsTermuxAvailable()` | Termux 安装检测 | SyncSettings OnInitialized | ✅ |
| `IsWebDav()` / `IsGit()` | 方法判断 | 内部使用 | ✅ 隐含 |
| `IsSafUri()` | SAF URI 判断 | 内部使用 | ✅ 隐含 |
| `CurrentStatus` | 当前同步状态 | SyncStatus, SyncSettings | ✅ |
| `LastSyncTime` | 上次同步时间 | SyncStatus, SyncSettings | ✅ |
| `LastMergeWarnings` | 合并警告列表 | Index SyncNowAsync | ✅ |
| `SyncCompleted` | 同步完成事件 | **未被 UI 订阅** | ❌ 缺少实时通知 |

### 2.2 HubClient 公开 API 对照

| 后端 API | 功能 | UI 调用位置 | 暴露状态 |
|---------|------|-----------|---------|
| `GetPackageIndexAsync()` | 获取包索引 | PackageStore LoadDataAsync | ✅ |
| `InstallPackageAsync()` | 安装包 | PackageStore InstallPackage | ✅ |
| `UninstallPackage()` | 卸载包 | PackageStore UninstallPackage | ✅ |
| `GetInstalledPackages()` | 获取已安装包 | PackageStore OnInitialized | ✅ |

### 2.3 CredentialManager 公开 API 对照

| 后端 API | 功能 | UI 调用位置 | 暴露状态 |
|---------|------|-----------|---------|
| `SavePat()` | 保存 PAT | SyncManager.EnsureGitSyncService 内部 | ✅ 隐含 |
| `GetPat()` | 读取 PAT | SyncManager.EnsureGitSyncService 内部 | ✅ 隐含 |
| `BuildAuthenticatedUrl()` | 构建认证 URL | GitSyncService 内部 | ✅ 隐含 |

### 2.4 SnapshotManager / ThreeWayMergeService

| 后端 API | 功能 | UI 暴露 | 暴露状态 |
|---------|------|---------|---------|
| `CreateSnapshot()` | 创建快照 | SyncAsync 内部 | ✅ 隐含 |
| `HasSnapshot()` | 快照是否存在 | SyncAsync 内部 | ✅ 隐含 |
| `ClearSnapshot()` | 清除快照 | **未被任何代码调用** | ❌ 无 UI 清除快照 |
| `Merge()` | 三方合并 | SyncAsync 内部 | ✅ 隐含 |

---

## 三、问题清单（按严重程度排序）

### P0 — 功能不可用（必须修复）

| # | 问题 | 位置 | 影响 | 溯源 |
|---|------|------|------|------|
| P0-1 | ConflictResolver 页面无入口 | `/conflicts` 路由无任何导航链接 | 冲突解决功能完全不可用 | `SyncStatus.razor:45` 跳转 `/syncsettings` 而非 `/conflicts`；`SyncSettings.razor` 无冲突按钮；`Index.razor:674-676` 仅 Snackbar 无导航 |
| P0-2 | PackageStore 入口过于隐蔽 | 仅 `SyncSettings.razor:91` 有按钮 | 用户无法发现包管理功能 | `Index.razor` 无包商店入口；`TopBar.razor` 无导航 |
| P0-3 | SyncSetupWizard 无主动入口 | 仅 `Index.razor:642-643` 未配置时触发 | 已配置用户无法重新运行向导 | `SyncSettings.razor` 无向导按钮 |

### P1 — 体验缺陷（应当修复）

| # | 问题 | 位置 | 影响 | 溯源 |
|---|------|------|------|------|
| P1-1 | 首页缺少 Sync Settings 入口 | `Index.razor` | 用户无法从首页直达同步设置 | 按钮组（line 44-57）无 Settings 按钮 |
| P1-2 | SyncStatus 信息不足 | `SyncStatus.razor` | 不显示同步方法、冲突数量 | line 30-40 仅状态文字 + 上次同步时间 |
| P1-3 | SyncCompleted 事件未被 UI 订阅 | 全局 | 同步完成时 UI 不会自动刷新 | 无任何组件订阅 `SyncManager.SyncCompleted` |
| P1-4 | Git 轮询首次立即触发 | `Index.razor:782` | 应用启动即执行 git fetch | `Timer` dueTime 为 `TimeSpan.Zero` |
| P1-5 | 包安装后不刷新本地 matches | `PackageStore.razor:154-158` | 需重启才能使用新包 | 无 `WeakReferenceMessenger` 通知 |
| P1-6 | Phase 2-3 页面 + Index.razor 部分硬编码英文 | 6 个 Razor 文件 | 中文用户中英混合 | SyncSettings/SetupWizard/PackageStore/ConflictResolver/SyncStatus 全部硬编码；Index.razor 也有遗漏：`Sync` 按钮文本(line 55)、导入模式对话框(line 300-303)、文件选择器标题(line 345)、同步结果 Snackbar(line 676/680/685/690)、未配置提示(line 642) |
| P1-7 | Sync / Save 按钮无 loading 状态 | `Index.razor:45-56`, `SyncSettings.razor:27-29` | 用户不知道操作进行中 | Index.razor 无 `_syncing`/`_saving` 字段；SyncSettings.razor 的 Sync Now 按钮同样无 loading 状态 |
| P1-8 | ClearSnapshot 无 UI 入口 | `SnapshotManager.cs` | 用户无法手动重置合并基线 | `ClearSnapshot()` 方法存在但从未被调用 |
| P1-9 | `BackgroundPollIntervalMin` 未在 UI 暴露 | `SyncSettings.razor` | 用户无法调整后台同步间隔 | `SyncConfig.BackgroundPollIntervalMin`(SyncManager.cs:81) 被 SetupWizard:178 使用，但 SyncSettings 页面未暴露此设置 |
| P1-10 | SyncSettings `_lastResult` 显示位置不佳 | `SyncSettings.razor:95-107` | 同步结果显示在页面最底部，用户可能看不到 | 应移入 Status Tab（见 4.5 节方案） |

### P2 — 优化建议

| # | 问题 | 位置 | 建议 |
|---|------|------|------|
| P2-1 | TopBar 无导航功能 | `TopBar.razor` | 添加导航图标（首页/同步/包商店） |
| P2-2 | SyncSettings 页面过长 | `SyncSettings.razor` | 用 `MudTabs` 分区：状态 / 配置 / 高级 |
| P2-3 | PackageStore 缺少已安装视图 | `PackageStore.razor` | 添加 "Installed" Tab |
| P2-4 | SetupWizard Git 步骤无 Termux 检测 | `SyncSetupWizard.razor:80-88` | 添加 Termux 状态检测 |
| P2-5 | 缺少同步历史记录 | 无 | SyncSettings 添加最近同步记录 |
| P2-6 | WebDAV 网络降级模式不可见 | `WebDavPollingService` | SyncStatus 显示当前网络模式 |
| P2-7 | Index.razor 819 行过长 | `Index.razor` | 拆分为子组件 |
| P2-8 | MatchCard 不显示 Vars 数量 | `MatchCard.razor` | 显示变量计数图标 |

---

## 四、统一 UI 重构方案

### 4.1 设计原则

1. **全局导航一致性**：所有页面通过 TopBar 导航，不依赖隐藏路由
2. **i18n 全覆盖**：所有用户可见文本使用 `LocalizationService.GetString()`
3. **状态实时性**：UI 订阅 `SyncCompleted` 事件，同步完成后自动刷新
4. **操作反馈统一**：进行中 → loading 状态；成功/失败 → Snackbar；需确认 → Dialog
5. **页面职责单一**：每页聚焦一个功能域，避免单页过长

### 4.2 重构后页面架构

```
TopBar（全局）
├── 应用名
├── 导航图标：[Home] [Sync] [Packages]    ← 新增
├── 主题切换
└── 语言切换

Pages/
├── Index.razor (/)              — 主页：文本扩展器 + 文本工具
│   ├── SyncStatus（增强）        — 显示方法 + 状态 + 冲突数 + 网络模式
│   ├── 按钮组                     — Save / Import / Export / Sync / Packages / Settings  ← 新增
│   ├── MatchCard 列表
│   └── TextTools Tab
│
├── SyncSettings.razor (/syncsettings)
│   ├── Tab: Status              — 状态 + Sync Now + Resolve Conflicts（如有）  ← 新增冲突入口
│   ├── Tab: Configuration       — 方法选择 + 字段配置 + Test Connection
│   ├── Tab: Advanced            — 冲突策略 + 轮询间隔 + WiFi Only + Clear Snapshot  ← 新增
│   └── 底部：Setup Wizard 按钮   ← 新增
│
├── SyncSetupWizard.razor (/syncsetup)
│   ├── Step 1: 是否使用 espanso
│   ├── Step 2: 选择同步方法
│   └── Step 3: 配置（含 Termux 检测）  ← 增强
│
├── PackageStore.razor (/packages)
│   ├── Tab: Browse              — 搜索 + 包列表 + Install/Uninstall
│   └── Tab: Installed           — 已安装包列表 + 详情 + Uninstall  ← 新增
│
└── ConflictResolver.razor (/conflicts)
    └── 冲突列表 + Keep Local / Keep Remote
```

### 4.3 导航重构

**TopBar.razor** — 添加导航图标按钮组：

```razor
<MudAppBar Elevation="2" Color="Color.Primary" Fixed="true">
    <MudText Typo="Typo.h6" Class="flex-grow-1">Expandroid</MudText>

    <MudIconButton Icon="@Icons.Material.Filled.Home" Color="Color.Inherit"
                   OnClick="GoHome" Class="mr-1" />
    <MudIconButton Icon="@Icons.Material.Filled.Sync" Color="Color.Inherit"
                   OnClick="GoSyncSettings" Class="mr-1" />
    <MudIconButton Icon="@Icons.Material.Filled.Extension" Color="Color.Inherit"
                   OnClick="GoPackages" Class="mr-1" />

    <MudToggleGroup ...>  <!-- 主题切换 -->
    <LanguageSwitcher />
</MudAppBar>

@* 需新增注入：@inject NavigationManager NavigationManager *@

@code {
    private void GoHome() => NavigationManager.NavigateTo("/");
    private void GoSyncSettings() => NavigationManager.NavigateTo("/syncsettings");
    private void GoPackages() => NavigationManager.NavigateTo("/packages");
}
```

**Index.razor** — 按钮组新增入口：

```razor
<MudButtonGroup Variant="Variant.Outlined" Color="Color.Primary" Size="Size.Large">
    <MudButton @onclick="SaveDictAsync" StartIcon="@Icons.Material.Filled.Save" Disabled="@_syncing">
        @localizationService.GetString("MakeSureToSave")
    </MudButton>
    <MudButton @onclick="ImportAsync" StartIcon="@Icons.Material.Filled.Upload">
        @localizationService.GetString("Import")
    </MudButton>
    <MudButton @onclick="ExportAsync" StartIcon="@Icons.Material.Filled.Download">
        @localizationService.GetString("Export")
    </MudButton>
    <MudButton @onclick="SyncNowAsync" StartIcon="@Icons.Material.Filled.Sync" Disabled="@_syncing">
        @if (_syncing) { <MudProgressCircular Size="Size.Small" Indeterminate="true" Class="ms-2" /> }
        else { @localizationService.GetString("Sync") }
    </MudButton>
</MudButtonGroup>
<MudStack Row="true" Spacing="2" Class="mt-2">
    <MudButton @onclick="() => NavigationManager.NavigateTo("/syncsettings")"
               Color="Color.Secondary" Variant="Variant.Text" Size="Size.Large"
               StartIcon="@Icons.Material.Filled.Settings">
        @localizationService.GetString("SyncSettings")
    </MudButton>
    <MudButton @onclick="() => NavigationManager.NavigateTo("/packages")"
               Color="Color.Secondary" Variant="Variant.Text" Size="Size.Large"
               StartIcon="@Icons.Material.Filled.Extension">
        @localizationService.GetString("PackageStore")
    </MudButton>
    <MudButton @onclick="ForceQuit" Color="Color.Error" Variant="Variant.Outlined" Size="Size.Large"
               StartIcon="@Icons.Material.Filled.PowerSettingsNew">
        @localizationService.GetString("ForceQuitApp")
    </MudButton>
</MudStack>
```

### 4.3b Index.razor 冲突 Snackbar 带导航

**Index.razor** — 同步冲突时 Snackbar 添加 "Resolve" 操作按钮：

```csharp
if (result.Conflicts > 0)
{
    Snackbar.Add($"{result.Conflicts} conflicts detected", Severity.Warning, config =>
    {
        config.Action = "Resolve";
        config.ActionColor = Color.Warning;
        config.OnClick = snackbar =>
        {
            NavigationManager.NavigateTo("/conflicts");
            return Task.CompletedTask;
        };
    });
}
```

### 4.4 SyncStatus 增强

**SyncStatus.razor** — 丰富信息 + 智能跳转：

```razor
@if (SyncManager.GetConfig().Method != SyncMethod.None)
{
    <MudAlert Severity="@GetSeverity()" Class="mb-3" Style="cursor: pointer;" OnClick="OnClick">
        <MudStack Row="true" Align="Align.Center" Spacing="2">
            <MudIcon Icon="@GetStatusIcon()" />
            <MudText>
                @GetStatusText()
                @if (SyncManager.GetConfig().Method != SyncMethod.None)
                {
                    <MudChip Size="Size.Small" Color="Color.Default" Class="ml-2">
                        @SyncManager.GetConfig().Method
                    </MudChip>
                }
            </MudText>
            @if (SyncManager.CurrentStatus == Services.SyncStatus.Conflict)
            {
                <MudChip Size="Size.Small" Color="Color.Warning">
                    @SyncManager.GetConflictFiles().Count Resolve
                </MudChip>
            }
            else
            {
                <MudChip Size="Size.Small" Color="Color.Primary">Manage</MudChip>
            }
        </MudStack>
    </MudAlert>
}

@code {
    private void OnClick()
    {
        NavigationManager.NavigateTo(
            SyncManager.CurrentStatus == Services.SyncStatus.Conflict
                ? "/conflicts" : "/syncsettings"
        );
    }

    private string GetStatusIcon() => SyncManager.CurrentStatus switch
    {
        Services.SyncStatus.Syncing => Icons.Material.Filled.Sync,
        Services.SyncStatus.Error => Icons.Material.Filled.ErrorOutline,
        Services.SyncStatus.Conflict => Icons.Material.Filled.WarningAmber,
        _ => Icons.Material.Filled.CheckCircle
    };
}
```

### 4.5 SyncSettings 分区重构

**SyncSettings.razor** — 使用 `MudTabs` 分三个 Tab：

```razor
<MudTabs Elevation="2" Rounded="true" ApplyEffectsToContainer="true">
    @* Tab 1: Status *@
    <MudTabPanel Text="@localizationService.GetString("Status")">
        <MudStack Spacing="3" Class="pa-4">
            <MudStack Row="true" Align="Align.Center" Spacing="3">
                <MudChip Color="@GetStatusColor()">@SyncManager.CurrentStatus</MudChip>
                <MudText>Last sync: @(SyncManager.LastSyncTime?.ToLocalTime().ToString("g") ?? "Never")</MudText>
                <MudButton Color="Color.Primary" Variant="Variant.Outlined" OnClick="SyncNow" Disabled="@_syncing">
                    @if (_syncing) { <MudProgressCircular Size="Size.Small" Indeterminate="true" Class="ms-2" /> }
                    @localizationService.GetString("SyncNow")
                </MudButton>
            </MudStack>
            @if (SyncManager.CurrentStatus == Services.SyncStatus.Conflict)
            {
                <MudButton Color="Color.Warning" Variant="Variant.Filled" OnClick="GoToConflicts"
                           StartIcon="@Icons.Material.Filled.WarningAmber">
                    @localizationService.GetString("ResolveConflicts")
                </MudButton>
            }
            @if (SyncManager.LastMergeWarnings?.Count > 0)
            {
                <MudAlert Severity="Severity.Warning">
                    @foreach (var w in SyncManager.LastMergeWarnings) { <div>@w</div> }
                </MudAlert>
            }
        </MudStack>
    </MudTabPanel>

    @* Tab 2: Configuration *@
    <MudTabPanel Text="@localizationService.GetString("Configuration")">
        <MudStack Spacing="3" Class="pa-4">
            <MudSelect T="SyncMethod" @bind-Value="_config.Method" Label="@localizationService.GetString("SyncMethod")" Variant="Variant.Outlined">
                <MudSelectItem Value="@(SyncMethod.None)">None</MudSelectItem>
                <MudSelectItem Value="@(SyncMethod.CloudFolder)">Cloud Folder (SAF)</MudSelectItem>
                <MudSelectItem Value="@(SyncMethod.Syncthing)">Syncthing</MudSelectItem>
                <MudSelectItem Value="@(SyncMethod.WebDAV)">WebDAV</MudSelectItem>
                <MudSelectItem Value="@(SyncMethod.Git)">Git</MudSelectItem>
                <MudSelectItem Value="@(SyncMethod.Manual)">Manual</MudSelectItem>
            </MudSelect>
            @* ... 方法相关字段 ... *@
        </MudStack>
    </MudTabPanel>

    @* Tab 3: Advanced *@
    <MudTabPanel Text="@localizationService.GetString("Advanced")">
        <MudStack Spacing="3" Class="pa-4">
            <MudRadioGroup T="ConflictStrategy" @bind-Value="_config.ConflictStrategy">
                <MudRadio Value="@(ConflictStrategy.LastWriteWins)" Label="@localizationService.GetString("LastWriteWins")" />
                <MudRadio Value="@(ConflictStrategy.KeepBoth)" Label="@localizationService.GetString("KeepBoth")" />
            </MudRadioGroup>
            <MudSlider T="int" @bind-Value="_config.ForegroundPollIntervalSec" Min="30" Max="300" Step="30" Color="Color.Primary" />
            <MudText>@localizationService.GetString("ForegroundPollInterval"): @_config.ForegroundPollIntervalSec s</MudText>
            <MudSlider T="int" @bind-Value="_config.BackgroundPollIntervalMin" Min="15" Max="60" Step="5" Color="Color.Secondary" />
            <MudText>@localizationService.GetString("BackgroundPollInterval"): @_config.BackgroundPollIntervalMin min</MudText>
            @* 注：Android WorkManager 最小周期为 15 分钟，Min 设为 15 *@
            <MudCheckBox @bind-Value="_config.WifiOnly" Label="@localizationService.GetString("WifiOnly")" />
            <MudButton Color="Color.Error" Variant="Variant.Outlined" OnClick="ClearSnapshot"
                       StartIcon="@Icons.Material.Filled.DeleteSweep">
                @localizationService.GetString("ClearMergeBaseline")
            </MudButton>
        </MudStack>
    </MudTabPanel>
</MudTabs>

<MudStack Row="true" Spacing="2" Class="mt-4">
    <MudButton Color="Color.Primary" Variant="Variant.Filled" OnClick="SaveConfig">
        @localizationService.GetString("Save")
    </MudButton>
    <MudButton Color="Color.Info" Variant="Variant.Text" OnClick="GoToSetupWizard">
        @localizationService.GetString("SetupWizard")
    </MudButton>
    <MudButton Color="Color.Secondary" Variant="Variant.Text" OnClick="GoBack">
        @localizationService.GetString("Back")
    </MudButton>
</MudStack>
```

### 4.6 PackageStore 增强

**PackageStore.razor** — 添加 Installed Tab + 安装后通知：

```razor
<MudTabs Elevation="2" Rounded="true">
    <MudTabPanel Text="@localizationService.GetString("Browse")">
        @* 搜索 + 包列表（现有逻辑） *@
    </MudTabPanel>
    <MudTabPanel Text="@localizationService.GetString("Installed")">
        @* 需新增字段：private List<InstalledPackageInfo> _installedPackages = new(); *@
        @* 在 OnInitializedAsync 中通过 HubClient.GetInstalledPackages() 加载 *@
        @if (_installedPackages.Count == 0)
        {
            <MudText Align="Align.Center" class="mt-4">
                @localizationService.GetString("NoInstalledPackages")
            </MudText>
        }
        else
        {
            @foreach (var pkg in _installedPackages)
            {
                <MudCard Class="mb-3 pa-4">
                    <MudStack Row="true" Justify="Justify.SpaceBetween" Align="Align.Center">
                        <div>
                            <MudText Typo="Typo.h6">@pkg.Name</MudText>
                            <MudText Typo="Typo.body2">@pkg.MatchFiles.Count match files</MudText>
                        </div>
                        <MudButton Color="Color.Error" Variant="Variant.Text" OnClick="() => UninstallPackage(pkg)">
                            @localizationService.GetString("Uninstall")
                        </MudButton>
                    </MudStack>
                </MudCard>
            }
        }
    </MudTabPanel>
</MudTabs>
```

安装成功后通知首页刷新：

> **注意**：`AcServiceMessage` 的接收方 `ExpanderAccessibilityService.cs:53-96` 当前处理 `"Add"`、`"Update"`、`"Remove"`、`"Quit"` 命令，但**未处理 `"Reload"` 命令**。需在 `ExpanderAccessibilityService.cs` 的消息处理逻辑中添加 `"Reload"` 分支，重新从磁盘加载 dict 和 regexDict。

```csharp
private async Task InstallPackage(HubPackageInfo pkg)
{
    _installing = true;
    try
    {
        var installed = await HubClient.InstallPackageAsync(pkg);
        if (installed != null)
        {
            _installedNames.Add(pkg.Name);
            Snackbar.Add($"Installed {pkg.Name}: {installed.MatchFiles.Count} files", Severity.Success);
            WeakReferenceMessenger.Default.Send(new AcServiceMessage(("Reload", null)));
        }
    }
    finally { _installing = false; }
}
```

`ExpanderAccessibilityService.cs` 需添加的 `"Reload"` 处理：

```csharp
else if (cmd == "Reload")
{
    // 重新从磁盘加载 dict 和 regexDict
    dict.Clear();
    regexDict.Clear();
    if (File.Exists(AppSettings.DictPath))
    {
        var json = File.ReadAllText(AppSettings.DictPath);
        var loaded = JsonSerializer.Deserialize<Dictionary<string, Match>>(json);
        foreach (var kv in loaded)
        {
            if (!string.IsNullOrEmpty(kv.Value.Regex))
            {
                try { regexDict[new Regex(kv.Value.Regex, RegexOptions.Compiled)] = kv.Value; }
                catch { }
            }
            else dict[kv.Key] = kv.Value;
        }
    }
}
```

### 4.7 SyncCompleted 事件订阅

**Index.razor** — 订阅同步完成事件，自动刷新 UI：

> **与 OnSafRemoteChanged 的关系**：Index.razor:791-801 已有 `OnSafRemoteChanged` 回调，在 SAF/WebDAV/Git 检测到远端变化时调用 `SyncNowAsync()`，后者内部已调用 `StateHasChanged()` 刷新 UI。因此 `SyncCompleted` 订阅的**主要价值在于后台同步**（如 `SyncWorker` 触发的同步）完成后通知前台 UI，而非前台触发的同步（已有刷新机制）。

```csharp
protected override async Task OnInitializedAsync()
{
    // ... 现有代码 ...
    SyncManager.SyncCompleted += OnSyncCompleted;
}

private void OnSyncCompleted(SyncStatus status, SyncResult result)
{
    InvokeAsync(async () =>
    {
        if (result?.HasRemoteChanges == true)
        {
            // 重新加载 dict 和 globalVars
            if (File.Exists(AppSettings.DictPath))
            {
                using var stream = File.OpenRead(AppSettings.DictPath);
                dict = JsonSerializer.Deserialize<Dictionary<string, Match>>(stream);
            }
            StateHasChanged();
        }
    });
}

public void Dispose()
{
    localizationService.OnLanguageChanged -= OnLanguageChanged;
#if ANDROID
    _safObserver?.Stop();
    _safObserver?.Dispose();
    _safObserver = null;
    _webDavPollingService?.Dispose();
    _webDavPollingService = null;
    _gitPollingTimer?.Dispose();
    _gitPollingTimer = null;
#endif
    SyncManager.SyncCompleted -= OnSyncCompleted;
}
```

### 4.8 i18n key 清单

以下为需要新增到 `AppResources.resx` 和 `AppResources.zh.resx` 的 key：

| Key | English | 中文 | 使用位置 |
|-----|---------|------|---------|
| `Sync` | Sync | 同步 | Index |
| `SyncSettings` | Sync Settings | 同步设置 | Index, TopBar |
| `SyncNow` | Sync Now | 立即同步 | SyncSettings |
| `Status` | Status | 状态 | SyncSettings |
| `Configuration` | Configuration | 配置 | SyncSettings |
| `Advanced` | Advanced | 高级 | SyncSettings |
| `SyncMethod` | Sync Method | 同步方式 | SyncSettings |
| `ConflictStrategy` | Conflict Strategy | 冲突策略 | SyncSettings |
| `LastWriteWins` | Last Write Wins (default) | 最后写入优先（默认） | SyncSettings |
| `KeepBoth` | Keep Both Copies | 保留两份 | SyncSettings |
| `PollInterval` | Poll interval | 轮询间隔 | SyncSettings |
| `WifiOnly` | WiFi only | 仅 WiFi | SyncSettings |
| `ClearMergeBaseline` | Clear merge baseline | 清除合并基线 | SyncSettings |
| `SetupWizard` | Setup Wizard | 设置向导 | SyncSettings |
| `ResolveConflicts` | Resolve Conflicts | 解决冲突 | SyncSettings, SyncStatus |
| `ConflictResolution` | Conflict Resolution | 冲突解决 | ConflictResolver |
| `KeepLocal` | Keep Local | 保留本地 | ConflictResolver |
| `KeepRemote` | Keep Remote | 保留远程 | ConflictResolver |
| `NoConflicts` | No conflicts detected. | 未检测到冲突。 | ConflictResolver |
| `Back` | Back | 返回 | SyncSettings, ConflictResolver |
| `Save` | Save | 保存 | SyncSettings |
| `PackageStore` | Package Store | 包商店 | Index, TopBar, SyncSettings |
| `Browse` | Browse | 浏览 | PackageStore |
| `Installed` | Installed | 已安装 | PackageStore |
| `SearchPackages` | Search packages | 搜索包 | PackageStore |
| `Install` | Install | 安装 | PackageStore |
| `Uninstall` | Uninstall | 卸载 | PackageStore |
| `NoInstalledPackages` | No installed packages. | 没有已安装的包。 | PackageStore |
| `RefreshIndex` | Refresh Index | 刷新索引 | PackageStore |
| `NoPackagesFound` | No packages found. Try refreshing. | 未找到包。尝试刷新。 | PackageStore |
| `ConnectionSuccessful` | Connection successful! | 连接成功！ | SyncSettings, SetupWizard |
| `ConnectionFailed` | Connection failed. Check URL and credentials. | 连接失败。请检查 URL 和凭据。 | SyncSettings, SetupWizard |
| `SettingsSaved` | Settings saved | 设置已保存 | SyncSettings |
| `SyncConfigured` | Sync configured successfully! | 同步配置成功！ | SetupWizard |
| `NoLocalData` | No local data to sync | 没有本地数据可同步 | SyncSettings |
| `SyncedFiles` | Synced {0} files | 已同步 {0} 个文件 | SyncSettings, Index |
| `ConflictsDetected` | {0} conflicts detected | 检测到 {0} 个冲突 | SyncSettings, Index |
| `SyncFailed` | Sync failed: {0} | 同步失败：{0} | Index |
| `SyncError` | Sync error: {0} | 同步错误：{0} | Index |
| `SyncNotConfigured` | Sync not configured. Opening setup wizard... | 同步未配置。正在打开设置向导... | Index |
| `SyncOK` | Sync OK (last: {0}) | 同步正常（上次：{0}） | SyncStatus |
| `Syncing` | Syncing... | 同步中... | SyncStatus |
| `SyncErrorTapRetry` | Sync error - tap to retry | 同步错误 - 点击重试 | SyncStatus |
| `SyncConflictDetected` | Sync conflict detected (last: {0}) | 检测到同步冲突（上次：{0}） | SyncStatus |
| `TapToManage` | Tap to manage | 点击管理 | SyncStatus |
| `TermuxDetected` | Detected | 已检测 | SyncSettings |
| `TermuxNotFound` | Not found | 未找到 | SyncSettings |
| `RequiresTermux` | Requires Termux with git installed (pkg install git). | 需要安装 Termux 并安装 git（pkg install git）。 | SyncSettings, SetupWizard |
| `GitRepoUrl` | Git Repository URL | Git 仓库 URL | SyncSettings, SetupWizard |
| `GitUsername` | Git Username | Git 用户名 | SyncSettings, SetupWizard |
| `PersonalAccessToken` | Personal Access Token | 个人访问令牌 | SyncSettings, SetupWizard |
| `WebDavUrl` | WebDAV URL | WebDAV 地址 | SyncSettings, SetupWizard |
| `Username` | Username | 用户名 | SyncSettings, SetupWizard |
| `Password` | Password | 密码 | SyncSettings, SetupWizard |
| `TestConnection` | Test Connection | 测试连接 | SyncSettings, SetupWizard |
| `BrowseFolder` | Browse... | 浏览... | SyncSettings, SetupWizard |
| `SyncFolderPath` | Sync Folder Path | 同步文件夹路径 | SyncSettings, SetupWizard |
| `SetupWizardTitle` | Sync Setup Wizard | 同步设置向导 | SetupWizard |
| `UseDesktopEspanso` | Do you already use desktop espanso? | 您是否已使用桌面版 espanso？ | SetupWizard |
| `ChooseSyncMethod` | Choose a sync method | 选择同步方式 | SetupWizard |
| `CloudFolderRecommended` | Cloud Folder (Recommended) | 云文件夹（推荐） | SetupWizard |
| `CloudFolderDesc` | Use Google Drive, Dropbox, OneDrive, etc. Easiest setup. | 使用 Google Drive、Dropbox、OneDrive 等。最简单的设置。 | SetupWizard |
| `SyncthingDesc` | P2P sync, no cloud needed. Real-time. | P2P 同步，无需云。实时。 | SetupWizard |
| `WebDavDesc` | For self-hosted NAS / Nextcloud / Nutstore. | 适用于自建 NAS / Nextcloud / 坚果云。 | SetupWizard |
| `GitRepoDesc` | For developers. Version history. | 适合开发者。版本历史。 | SetupWizard |
| `ManualDesc` | Import/export files manually. | 手动导入/导出文件。 | SetupWizard |
| `Finish` | Finish | 完成 | SetupWizard |
| `Yes` | Yes | 是 | SetupWizard |
| `NoStartFresh` | No, start fresh | 否，从头开始 | SetupWizard |
| `ConfigureMethod` | Configure {0} | 配置 {0} | SetupWizard |
| `ForegroundPollInterval` | Foreground poll interval | 前台轮询间隔 | SyncSettings |
| `BackgroundPollInterval` | Background poll interval (min) | 后台轮询间隔（分钟） | SyncSettings |
| `ImportMode` | Import Mode | 导入模式 | Index |
| `ImportModeDesc` | Choose import format. Yes = JSON/YML single file, No = YML folder (espanso match directory) | 选择导入格式。是 = JSON/YML 单文件，否 = YML 文件夹（espanso match 目录） | Index |
| `SingleFile` | Single File | 单文件 | Index |
| `YmlFolder` | YML Folder | YML 文件夹 | Index |
| `PickYmlInMatchFolder` | Pick any YAML file in the espanso match folder | 选择 espanso match 文件夹中的任意 YAML 文件 | Index |
| `NoLocalDataToSync` | No local data to sync | 没有本地数据可同步 | SyncSettings |
| `KeptLocalVersion` | Kept local version | 已保留本地版本 | ConflictResolver |
| `KeptRemoteVersion` | Kept remote version | 已保留远程版本 | ConflictResolver |

---

## 五、实施计划

### 阶段 1：导航重构 + 页面可达性（P0）

**目标**：所有功能页面可达

| 任务 | 文件 | 具体改动 | 验收标准 |
|------|------|---------|---------|
| TopBar 添加导航图标 | `TopBar.razor` | 添加 Home / Sync / Packages 三个 `MudIconButton` | 顶栏可见三个导航图标，点击跳转正确 |
| Index 添加 Settings + Packages 按钮 | `Index.razor` | 按钮组后添加两个 `MudButton` | 首页可见两个新按钮 |
| SyncStatus 智能跳转 | `SyncStatus.razor` | 冲突状态跳 `/conflicts`，其他跳 `/syncsettings` | 冲突时点击跳冲突页 |
| SyncSettings 添加冲突入口 | `SyncSettings.razor` | 冲突状态时显示 "Resolve Conflicts" 按钮 | 有冲突时按钮可见可点击 |
| SyncSettings 添加向导入口 | `SyncSettings.razor` | 底部添加 "Setup Wizard" 按钮 | 按钮可点击跳转向导 |
| Index 冲突 Snackbar 带导航 | `Index.razor` | Snackbar 添加 Action 按钮导航到 `/conflicts` | 冲突提示有 "Resolve" 按钮 |

### 阶段 2：交互增强 + 状态实时性（P1）

**目标**：操作有反馈，状态实时更新

| 任务 | 文件 | 具体改动 | 验收标准 |
|------|------|---------|---------|
| Sync 按钮 loading 状态 | `Index.razor` | 添加 `_syncing` 字段，`Disabled` + `MudProgressCircular` | 同步中按钮禁用 + spinner |
| Save 按钮 loading 状态 | `Index.razor` | 添加 `_saving` 字段 | 保存中按钮禁用 |
| SyncSettings Sync Now loading 状态 | `SyncSettings.razor` | 添加 `_syncing` 字段，`Disabled` + `MudProgressCircular` | 同步中按钮禁用 + spinner |
| 订阅 SyncCompleted 事件 | `Index.razor` | `OnSyncCompleted` 回调刷新 dict | 后台同步完成后列表自动更新 |
| Git 轮询首次延迟 | `Index.razor:782` | `dueTime` 改为 `TimeSpan.FromSeconds(interval)` | 启动后不立即 fetch |
| 包安装后通知刷新 | `PackageStore.razor` | 发送 `WeakReferenceMessenger` 消息 | 安装包后首页列表更新 |
| SyncStatus 显示同步方法 | `SyncStatus.razor` | 添加 `MudChip` 显示 `Method` | 状态条显示当前同步方式 |
| SyncStatus 显示状态图标 | `SyncStatus.razor` | 添加 `MudIcon` | 不同状态有不同图标 |
| SyncSettings 添加 ClearSnapshot | `SyncSettings.razor` | 高级 Tab 添加按钮调用 `SnapshotManager.ClearSnapshot()`。**依赖变更**：需注入 `SnapshotManager` 或在 `SyncManager` 上添加 `ClearSnapshot()` 代理方法 | 可清除合并基线 |
| BackgroundPollIntervalMin UI 暴露 | `SyncSettings.razor` | Advanced Tab 添加 `MudSlider` 绑定 `_config.BackgroundPollIntervalMin` | 可调整后台同步间隔 |
| SyncSettings 显示合并警告 | `SyncSettings.razor` | 状态 Tab 显示 `LastMergeWarnings` | 合并警告在设置页可见 |

### 阶段 3：i18n 全覆盖（P1）

**目标**：所有页面中英双语

| 任务 | 文件 | 具体改动 | 验收标准 |
|------|------|---------|---------|
| 新增 i18n key | `AppResources.resx` + `AppResources.zh.resx` | 添加第四节 4.8 中约 70 个 key | resx 文件包含所有新 key |
| 5 个页面注入 ILocalizationService | `SyncSettings.razor`, `SyncSetupWizard.razor`, `PackageStore.razor`, `ConflictResolver.razor`, `SyncStatus.razor` | 添加 `@inject ILocalizationService localizationService`（**前置步骤**） | 5 个文件均可调用 `GetString()` |
| SyncSettings i18n | `SyncSettings.razor` | 所有硬编码文本替换为 `GetString()` | 切换语言后文本变化 |
| SyncSetupWizard i18n | `SyncSetupWizard.razor` | 同上 | 同上 |
| PackageStore i18n | `PackageStore.razor` | 同上 | 同上 |
| ConflictResolver i18n | `ConflictResolver.razor` | 同上 | 同上 |
| SyncStatus i18n | `SyncStatus.razor` | 同上 | 同上 |
| Index.razor 遗漏 i18n | `Index.razor` | Sync 按钮文本(line 55)、导入模式对话框(line 300-303)、文件选择器标题(line 345)、同步结果 Snackbar(line 676/680/685/690)、未配置提示(line 642) 替换为 `GetString()` | 切换语言后上述文本变化 |

### 阶段 4：布局优化（P2）

**目标**：页面结构清晰，不过长

| 任务 | 文件 | 具体改动 | 验收标准 |
|------|------|---------|---------|
| SyncSettings 分 Tab | `SyncSettings.razor` | 拆为 Status / Configuration / Advanced 三个 Tab | 页面不再一长条 |
| PackageStore 添加 Installed Tab | `PackageStore.razor` | 新增已安装包视图 | 可查看已安装包详情 |
| SetupWizard Termux 检测 | `SyncSetupWizard.razor` | Git 步骤显示 Termux 状态 | 向导中可见 Termux 检测结果 |
| MatchCard 显示 Vars 计数 | `MatchCard.razor` | 添加 `MudChip` 显示变量数 | 卡片显示变量数量 |

---

## 六、验收检查清单

实施完成后，按以下清单逐项验证：

### 导航可达性

- [ ] 从首页可以到达 `/syncsettings`
- [ ] 从首页可以到达 `/packages`
- [ ] 从首页可以到达 `/syncsetup`（主动入口，不依赖未配置状态）
- [ ] 从 TopBar 可以到达首页 / 同步设置 / 包商店
- [ ] 同步冲突时 SyncStatus 点击跳转 `/conflicts`
- [ ] SyncSettings 有 "Resolve Conflicts" 按钮（冲突时）
- [ ] SyncSettings 有 "Setup Wizard" 按钮
- [ ] Index 冲突 Snackbar 有 "Resolve" 操作按钮

### 交互反馈

- [ ] Sync 按钮点击后显示 loading 状态
- [ ] Save 按钮点击后显示 loading 状态
- [ ] 后台同步完成后首页列表自动刷新
- [ ] 包安装后首页 matches 列表自动刷新
- [ ] Git 轮询启动后不立即触发首次 fetch
- [ ] SyncStatus 显示当前同步方法
- [ ] SyncStatus 显示状态图标
- [ ] SyncSettings 可清除合并基线（ClearSnapshot）
- [ ] SyncSettings 显示合并警告
- [ ] SyncSettings Sync Now 按钮有 loading 状态
- [ ] SyncSettings 可调整后台同步间隔（BackgroundPollIntervalMin）
- [ ] SyncStatus 显示冲突数量

### i18n

- [ ] SyncSettings 切换中英文后所有文本变化
- [ ] SyncSetupWizard 切换中英文后所有文本变化
- [ ] PackageStore 切换中英文后所有文本变化
- [ ] ConflictResolver 切换中英文后所有文本变化
- [ ] SyncStatus 切换中英文后所有文本变化
- [ ] Index.razor 遗漏项（Sync 按钮、导入对话框、Snackbar）切换中英文后文本变化
- [ ] AppResources.resx 和 AppResources.zh.resx 的 key 数量一致

### 布局

- [ ] SyncSettings 页面使用 Tab 分区，不再一长条
- [ ] PackageStore 有 Browse 和 Installed 两个 Tab
- [ ] SetupWizard Git 步骤显示 Termux 检测状态
- [ ] MatchCard 显示变量计数

---

## 七、溯源矩阵

每个问题 → 后端代码位置 → UI 修复位置 → 验收标准：

| 问题 # | 后端实现位置 | UI 问题位置 | 修复文件 | 验收标准 |
|--------|------------|-----------|---------|---------|
| P0-1 | `SyncManager.cs:496-504` GetConflictFiles/ResolveConflict | `SyncStatus.razor:45` 跳错地址 | `SyncStatus.razor`, `SyncSettings.razor`, `Index.razor` | 冲突时可导航到 `/conflicts` |
| P0-2 | `HubClient.cs` 全部公开 API | `Index.razor` 无入口 | `Index.razor`, `TopBar.razor` | 首页/顶栏可见包商店入口 |
| P0-3 | `SyncSetupWizard.razor` 全部功能 | `SyncSettings.razor` 无向导按钮 | `SyncSettings.razor` | 设置页可见向导入口 |
| P1-1 | `SyncManager.cs:147` GetConfig | `Index.razor:44-57` 无 Settings 按钮 | `Index.razor` | 首页可见 Settings 按钮 |
| P1-2 | `SyncManager.cs:98-101` CurrentStatus/LastSyncTime/LastMergeWarnings | `SyncStatus.razor:30-40` 信息不足 | `SyncStatus.razor` | 状态条显示方法+图标+冲突数 |
| P1-3 | `SyncManager.cs:100` SyncCompleted 事件 | 无订阅者 | `Index.razor` | 后台同步后列表自动刷新 |
| P1-4 | `Index.razor:772-782` Timer | `Index.razor:782` dueTime=Zero | `Index.razor` | 启动后不立即 fetch |
| P1-5 | `HubClient.cs` InstallPackageAsync | `PackageStore.razor:154-158` 无通知 | `PackageStore.razor` | 安装后列表刷新 |
| P1-6 | N/A | 6 个 Razor 文件硬编码英文（含 Index.razor 遗漏） | 6 个文件 + 2 个 resx | 切换语言全部变化 |
| P1-7 | N/A | `Index.razor:45-56`, `SyncSettings.razor:27-29` 无 loading | `Index.razor`, `SyncSettings.razor` | 按钮有 loading 状态 |
| P1-8 | `SnapshotManager.cs` ClearSnapshot | 从未被调用 | `SyncSettings.razor`（需注入 SnapshotManager） | 高级 Tab 有清除按钮 |
| P1-9 | `SyncManager.cs:81` BackgroundPollIntervalMin | `SyncSettings.razor` 未暴露 | `SyncSettings.razor` Advanced Tab | 可调整后台同步间隔 |
| P1-10 | N/A | `SyncSettings.razor:95-107` _lastResult 位置不佳 | `SyncSettings.razor` Status Tab | 同步结果在 Status Tab 可见 |

---

**文档版本**：2.2  
**创建日期**：2026-06-23  
**最后更新**：2026-06-23（v2.2：修正 TopBar 注入说明、Installed Tab 数据模型、Slider Min 值、key 数量一致性）  
**状态**：待审核
