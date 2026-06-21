# Android ↔ 桌面 Espanso 无缝同步方案

## 设计目标

**一次配置，无感同步。** 用户在桌面 espanso 和 Android Expandroid 之间自由切换，配置自动保持一致，无需手动导入/导出。

### 目标用户体验

```
首次使用：
  安装 Expandroid → 设置向导 → 选择同步方式 → 自动拉取配置 → 完成

日常使用：
  桌面修改 matches → 保存 → 手机自动更新（无需打开 App）
  手机修改 matches → 保存 → 桌面自动更新（espanso auto_restart）

多设备：
  笔记本 + 台式机 + 手机，通过同一同步源保持一致
```

---

## 现状分析

### Espanso 桌面版

**配置目录结构**：
```
espanso/
├── config/
│   ├── default.yml              # 主配置
│   └── app-specific.yml         # App 特定配置
├── match/                       # ← 同步的核心目标
│   ├── base.yml                 # 基础 matches
│   ├── personal.yml             # 用户自定义 matches
│   └── *.yml                    # 其他 match 文件
└── packages/                    # 从 hub 安装的包
```

**关键特性**：
- `auto_restart: true`（默认开启）— 配置文件变化时自动重载
- 支持 `imports` 跨文件引用
- IPC 仅用于进程控制（Unix Socket / Named Pipe），无 HTTP API
- 包管理：从 espanso hub（GitHub Releases）下载 zip 并安装

**配置路径**：
- Linux：`~/.config/espanso/` 或 `~/.espanso/`
- macOS：`~/Library/Application Support/espanso/`
- Windows：`%APPDATA%\espanso\`

### Expandroid 当前状态

**内部存储**：
- `keywords.json` — 所有 matches 序列化为 JSON
- `global.json` — 全局变量
- 导入/导出为单文件 YAML（手动触发）

**关键差距**：
1. 内部用 JSON 存储，与 espanso 的多文件 YAML 结构不对应
2. 导入是单文件、手动、一次性的
3. 无后台同步机制
4. 无文件监控能力
5. 不支持 `imports` 递归解析

---

## 整体架构设计

### 分层架构

```
┌─────────────────────────────────────────────────────┐
│                   用户界面层                          │
│  设置向导 · 同步状态指示 · 包商店 · 手动操作            │
├─────────────────────────────────────────────────────┤
│                   同步管理层                          │
│  SyncManager · 冲突检测 · 变更追踪 · 通知              │
├──────────────────────┬──────────────────────────────┤
│   传输层 (可插拔)      │      格式层                    │
│  CloudFolder         │  YamlWorkspace               │
│  GitRepo             │  (多文件 YAML 读写)            │
│  LocalNetwork        │                               │
├──────────────────────┴──────────────────────────────┤
│                   存储层                              │
│  AppData (内部) · SyncFolder (外部) · Cache           │
└─────────────────────────────────────────────────────┘
```

### 核心设计决策

#### 决策 1：Expandroid 采用 espanso 原生 YAML 格式作为同步标准

**现状问题**：Expandroid 内部用 `keywords.json`（单文件 JSON），espanso 用 `match/*.yml`（多文件 YAML）。每次导入/导出都需要格式转换，且无法保持文件结构。

**方案**：引入 `YamlWorkspace` 概念 — Expandroid 能直接读写 espanso 的多文件 YAML 结构。

**实现**：
- 内部仍用 `Dictionary<string, Match>` 作为运行时模型（AccessibilityService 依赖）
- 新增 `YamlWorkspace` 服务，负责在 `Dictionary` 和多文件 YAML 之间双向转换
- 同步时以 YAML 文件为单位，保持与 espanso 文件结构一致
- 每个 YAML 文件对应一个 `DictWrapper`（matches + global_vars）

**好处**：
- 同步粒度从"整个配置"变为"单个文件"，冲突更少
- 桌面端 espanso 无需任何改动，`auto_restart` 自动处理文件变化
- 保留 espanso 的 `imports` 结构

#### 决策 2：传输层可插拔，自动选择最优方式

用户在设置向导中选择同步方式后，SyncManager 自动处理后续所有传输逻辑。

| 传输方式 | 触发机制 | 方向 | 实时性 | 适用场景 |
|---------|---------|------|--------|---------|
| **WebDAV** | 短轮询 + 即时推送 | 双向 | 秒级 | 自建 NAS / Nextcloud / 坚果云 |
| **CloudFolder** | WorkManager 定时 + 文件监控 | 双向 | 分钟级 | 普通用户，已有云存储 |
| **GitRepo** | WorkManager 定时 + 手动 | 双向 | 分钟级 | 开发者，需要版本历史 |
| **LocalNetwork** | 按需（同局域网时） | 双向 | 秒级 | 高频同步，无云依赖 |
| **ManualFile** | 用户手动触发 | 单次 | N/A | 偶尔同步，无云服务 |

#### 决策 3：即时推送 + 智能轮询

**Android 本地修改 → 即时推送**：
- 用户在 Expandroid 中保存 match 时，立即触发 `Push()` 写入同步源
- 无需等待轮询周期，延迟 < 1 秒

**远端修改 → Android 感知**（按传输方式自动选择）：
- **WebDAV**：短轮询 `PROPFIND`（默认 30s，WiFi 下可配置更短），一次请求仅几 KB
- **CloudFolder (SAF)**：WorkManager 周期检查（15 分钟），SAF 无主动通知能力
- **GitRepo**：WorkManager 周期 `pull`（15 分钟）
- **LocalNetwork**：WebSocket 长连接，服务端推送（秒级）

**通知链路**：
- 文件变化时通过 `WeakReferenceMessenger` 通知 AccessibilityService 更新 `dict`
- 同步状态显示在 UI 顶部（上次同步时间、状态图标、同步方式）

**桌面端**：
- 无需额外开发 — espanso 的 `auto_restart` 已内置文件监控
- 用户只需将 espanso 的 `match/` 目录挂载为 WebDAV 或放入同步文件夹

---

## 传输方案详细设计

### 方案 A：WebDAV（推荐默认）— 自建云 / NAS 最优解

**原理**：Expandroid 内置 WebDAV 客户端，直接与 WebDAV 服务器通信。桌面端通过 WebDAV 客户端（如 rclone、davfs2）将 espanso match 目录挂载到 WebDAV。

**桌面端设置**（一次性）：
```bash
# 方法 1：rclone 挂载（推荐，跨平台）
rclone mount webdav:espanso-match ~/.config/espanso/match --vfs-cache-mode writes

# 方法 2：davfs2（Linux 原生）
mount -t davfs https://nas.local/dav/espanso-match ~/.config/espanso/match

# 方法 3：Nextcloud / 坚果云桌面客户端同步 espanso match 目录
```

**Android 端设置**（一次性）：
1. 设置向导 → 选择"WebDAV 同步"
2. 输入 WebDAV 服务器地址、用户名、密码
3. 指定远程路径（如 `/espanso-match/`）
4. Expandroid 测试连接 → 保存配置
5. 后台短轮询自动检查变化

**同步流程**：
```
桌面修改 → 保存到 match/*.yml → WebDAV 客户端自动上传
                                    ↓
Android 短轮询 PROPFIND（30s）→ 检测到 ETag/时间戳变化
                                    ↓
                        GET 变化的文件 → 解析 YAML → 更新 dict
                                    ↓
                            通知 AccessibilityService

Android 修改 → 保存时即时 PUT → WebDAV 服务器（< 1s）
                                    ↓
桌面 WebDAV 客户端同步到本地 → espanso auto_restart 检测变化 → 自动重载
```

**技术要点**：
- WebDAV 协议操作（基于 `HttpClient`）：
  - `PROPFIND`（Depth: 1）— 列目录，返回文件列表 + ETag + Last-Modified
  - `GET` — 下载文件内容
  - `PUT` — 上传文件（即时推送）
  - `MKCOL` — 创建目录
  - `DELETE` — 删除文件
- 变化检测：优先使用 `ETag`，回退到 `Last-Modified` 时间戳
- 短轮询策略：
  - WiFi 下默认 30s，可配置 10s~5min
  - 移动网络下降级为 5 分钟
  - App 在前台时轮询，后台时切换为 WorkManager 15 分钟
- 认证：HTTP Basic Auth，凭据存储在 `AndroidKeyStore`
- 兼容性测试：Nextcloud、坚果云、Synology NAS、rclone serve webdav

**优点**：
- 即时推送（Android 修改 → PUT < 1s）
- 快速感知（桌面修改 → Android 30s 内检测到）
- 支持自建 NAS / Nextcloud / 坚果云，隐私可控
- 不需要额外 Android App（WebDAV 客户端内置）
- 不增加 APK 体积（`HttpClient` 是 .NET 内置）
- 桌面端选择丰富（rclone / davfs2 / Nextcloud 客户端等）

**缺点**：
- 需要用户有 WebDAV 服务器（NAS / Nextcloud / 坚果云等）
- 短轮询有少量流量消耗（每次 PROPFIND ~1-2KB）
- 需处理 WebDAV 服务器兼容性差异（部分实现不完全符合 RFC 4918）

**改动量**：中等 — WebDAV 客户端 + SyncManager + YamlWorkspace + 设置向导 UI

---

### 方案 B：CloudFolder（SAF 云文件夹同步）— 商业云存储

**原理**：通过 Android Storage Access Framework（SAF）选择云存储客户端（Google Drive、Dropbox、OneDrive 等）同步的文件夹，Expandroid 读写该文件夹。

**桌面端设置**（一次性）：
```bash
# 将 espanso match 目录 symlink 到云同步文件夹
ln -s ~/Google\ Drive/espanso-match/ ~/.config/espanso/match
# 或在云客户端中添加 ~/.config/espanso/match/ 为同步文件夹
```

**Android 端设置**（一次性）：
1. 设置向导 → 选择"云文件夹同步"
2. 使用 SAF 选择云存储中的 espanso-match 文件夹
3. Expandroid 保存文件夹 URI（持久化权限）
4. 后台 WorkManager 定时检查文件夹变化

**同步流程**：
```
桌面修改 → 保存到 match/*.yml → 云客户端自动上传
                                    ↓
Android WorkManager 检测到文件变化（≤15分钟）→ 解析 YAML → 更新 dict
                                    ↓
                            通知 AccessibilityService

Android 修改 → 序列化为 YAML → 写入云文件夹 → 云客户端自动上传
                                    ↓
桌面云客户端同步到本地 → espanso auto_restart 检测变化 → 自动重载
```

**技术要点**：
- SAF 持久化 URI：通过 `ContentResolver.TakePersistableUriPermission` 保存访问权限
- 文件变化检测：记录每个文件的 `last_modified` 时间戳，对比变化
- WorkManager 约束：仅在 WiFi 连接时同步（可选），电池优化
- 多文件处理：遍历文件夹中所有 `.yml` 文件，逐个解析合并
- 即时推送：Android 修改保存时立即写入 SAF 文件夹（云客户端自动上传）

**优点**：
- 用户只需选择一次文件夹，之后完全自动
- 桌面端零改动（利用 espanso 现有 `auto_restart`）
- 支持所有主流云服务（Google Drive、Dropbox、OneDrive、Nextcloud 等）
- 不增加 APK 体积（SAF 是 Android 原生）

**缺点**：
- 远端变化感知有延迟（WorkManager 最短 15 分钟，SAF 无主动通知）
- 依赖云客户端的同步速度
- 需要桌面端安装对应云客户端

**改动量**：小 — SAF API 已内置，主要工作是 UI 和文件选择逻辑

---

### 方案 C：GitRepo（Git 仓库同步）— 开发者首选

**原理**：将 espanso match 配置存放在 Git 仓库中，两端通过 Git 操作同步。

**桌面端设置**：
```bash
cd ~/.config/espanso/match
git init
git remote add origin git@github.com:user/espanso-config.git
# 可选：设置自动 commit + push 的 cron job 或 git hooks
```

**Android 端设置**：
1. 设置向导 → 选择"Git 同步"
2. 输入仓库 URL + PAT（Personal Access Token）
3. Expandroid 执行 `clone` 到本地缓存目录
4. WorkManager 定时 `pull` / 修改后 `commit + push`

**同步流程**：
```
桌面修改 → git add + commit + push（手动或自动）
                    ↓
Android WorkManager → git pull → 解析 YAML → 更新 dict

Android 修改 → 序列化 YAML → git add + commit + push
                    ↓
桌面 → git pull（手动或 cron）→ espanso auto_restart
```

**技术要点**：
- Git 库选择：`libgit2sharp`（~5MB）或调用系统 `git` 命令（需 Termux）
- 认证：PAT token 存储在 `AndroidKeyStore` 中
- 冲突处理：`rebase` 策略，失败时保留两份让用户手动选择
- 自动化：桌面端可提供 `espanso-sync` 脚本（git auto-commit + push）

**优点**：
- 完整版本历史，可回滚
- 多设备天然支持
- 不依赖额外云客户端（Git 本身就是同步工具）
- 与 espanso hub 的 GitHub 生态契合

**缺点**：
- APK 增加 ~5MB（libgit2sharp）
- 需要 Git 知识
- 认证配置对普通用户不友好

**改动量**：较大 — libgit2sharp 集成 + 认证管理 + 冲突处理 UI

---

### 方案 D：LocalNetwork（局域网同步）— 零云依赖

**原理**：桌面端运行一个轻量 HTTP 服务，Expandroid 在同一局域网内直接通信。

**桌面端**：开发 `espanso-sync` companion 工具（小型 Rust/Go 二进制）：
- 监听 `0.0.0.0:8765`
- `GET /api/files` — 返回 match 目录文件列表 + 内容
- `POST /api/files` — 接收 Expandroid 推送的文件
- mDNS 广播 `_espanso-sync._tcp` 供自动发现
- 二维码显示连接信息（IP + 端口 + 配对码）

**Android 端**：
- 设置向导 → 选择"局域网同步" → 扫描桌面二维码
- WorkManager 在 WiFi 连接时自动检测桌面服务
- 检测到变化时拉取/推送

**优点**：
- 无云依赖，隐私友好
- 延迟低（局域网直连）
- 可扩展为远程触发扩展

**缺点**：
- 需要开发桌面端 companion 工具
- 仅限同一局域网
- 需处理安全性（配对码认证）

**改动量**：大 — 桌面端工具 + Android 端 HTTP 客户端 + mDNS + 配对 UI

---

### 方案 E：Espanso Hub 客户端 — 社区包获取

**原理**：复用 espanso hub 的包索引和下载机制，Expandroid 直接从 hub 安装社区包。

**实现**：
- 包索引：`GET https://github.com/espanso/hub/releases/latest/download/package_index.json`
- 下载包 zip → SHA256 校验 → 解压 → 解析 `package.yml` → 导入 matches
- 本地缓存包索引（1 小时有效期，与 espanso 一致）

**与同步方案的关系**：
- Hub 客户端不是同步方案，而是配置获取的补充
- 安装的包可以纳入 CloudFolder/GitRepo 的同步范围
- 桌面端 `espanso package install` 和 Android 端安装的包通过同步保持一致

**优点**：
- 直接复用 espanso 生态，数百个现成包
- 用户体验好：浏览 → 一键安装
- 与桌面端使用相同包源

**缺点**：
- GitHub Releases 国内访问可能受限
- 只能下载，不能上传（非双向同步）

**改动量**：中等 — HTTP 客户端 + zip 解压 + SHA256 + 包浏览 UI

---

## 推荐方案组合

### 主方案：WebDAV + CloudFolder + Hub 客户端

| 需求 | 方案 | 理由 |
|------|------|------|
| 个人 matches 双向同步（自建云） | WebDAV | 即时推送 + 秒级感知，隐私可控 |
| 个人 matches 双向同步（商业云） | CloudFolder | 利用现有云存储，零额外服务端 |
| 社区包获取 | Hub 客户端 | 复用 espanso 生态，一键安装 |
| 开发者高级同步 | GitRepo（可选） | 版本历史，多设备 |

**方案选择逻辑**：
- 用户有 NAS / Nextcloud / 坚果云 → 推荐 WebDAV（实时性最好）
- 用户有 Google Drive / Dropbox / OneDrive → 推荐 CloudFolder
- 用户两者都没有 → 手动导入 或 GitRepo

### 完整用户旅程

```
┌──────────────────────────────────────────────────────────────┐
│  首次设置                                                     │
│                                                              │
│  1. 安装 Expandroid                                          │
│  2. 设置向导启动                                              │
│     ├─ "你已经在使用桌面 espanso 吗？"                        │
│     │   ├─ 是 → 选择同步方式                                  │
│     │   │   ├─ WebDAV（推荐）→ 输入服务器信息 → 自动拉取      │
│     │   │   ├─ 云文件夹 → SAF 选择文件夹 → 自动拉取           │
│     │   │   ├─ Git 仓库 → 输入 URL + PAT → clone → 解析     │
│     │   │   └─ 手动导入 → 文件选择器 → 导入                  │
│     │   └─ 否 → 空白配置开始 / 浏览包商店                    │
│     └─ 同步配置完成，显示导入摘要                             │
│                                                              │
│  3. 开始使用                                                  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  日常使用 — 桌面修改                                          │
│                                                              │
│  1. 用户在桌面编辑 personal.yml                               │
│  2. espanso auto_restart 自动重载                            │
│  3. WebDAV 客户端 / 云客户端自动上传                          │
│  4. Android 感知变化：                                        │
│     ├─ WebDAV：短轮询 30s 内检测到 → GET 拉取                │
│     └─ CloudFolder：WorkManager ≤15分钟检测到                │
│  5. 解析 YAML → 更新 dict → 通知 AccessibilityService        │
│  6. 下次用户打字时，新 matches 已生效                         │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  日常使用 — Android 修改                                      │
│                                                              │
│  1. 用户在 Expandroid 中添加/编辑 match                       │
│  2. 保存时即时推送：序列化 YAML → 写入同步源（< 1s）          │
│  3. 桌面端同步到本地                                          │
│     ├─ WebDAV：rclone/davfs2 实时同步                        │
│     └─ CloudFolder：云客户端自动同步                          │
│  4. espanso auto_restart 检测变化 → 自动重载                  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  社区包安装                                                  │
│                                                              │
│  1. 用户打开"包商店" Tab                                      │
│  2. 浏览/搜索 espanso hub 包列表                              │
│  3. 点击安装 → 下载 zip → 校验 → 解压 → 导入 matches         │
│  4. 安装的包自动纳入同步范围                                   │
│  5. 桌面端通过同步获得相同的包                                │
└──────────────────────────────────────────────────────────────┘
```

---

## 技术实现细节

### 1. YamlWorkspace 服务

负责在 Expandroid 内部 `Dictionary<string, Match>` 和 espanso 多文件 YAML 之间转换。

```
YamlWorkspace
├── ReadFromFolder(uri)     → 遍历文件夹中所有 .yml → 合并为 Dictionary
├── WriteToFolder(uri, dict) → 拆分为多个 .yml 文件写入
├── ReadFile(uri)           → 解析单个 YAML 文件
├── WriteFile(uri, wrapper)  → 序列化单个 YAML 文件
└── GetFileList(uri)        → 返回文件列表 + 时间戳（用于变化检测）
```

**文件拆分策略**：
- 导入时：保留原始文件结构（每个 .yml 文件独立解析）
- 导出时：按来源文件分组写入；新建的 matches 写入 `expandroid.yml`
- `global_vars` 写入单独文件 `global_vars.yml`

### 2. SyncManager 服务

```
SyncManager
├── 配置
│   ├── SyncMethod (WebDAV / Cloud / Git / Local / Manual)
│   ├── SyncUri (WebDAV URL / SAF URI / Git URL / HTTP URL)
│   ├── Credentials (用户名密码 / PAT / 配对码 — 存储于 AndroidKeyStore)
│   ├── SyncInterval (前台轮询: 30s~5min / 后台: 15min / Manual only)
│   ├── LastSyncTime
│   └── FileETags (文件 → ETag/时间戳 映射，用于变化检测)
├── Push()     → 即时推送：YamlWorkspace.WriteToFolder → 写入同步源
├── Pull()     → 从同步源拉取 → YamlWorkspace.ReadFromFolder → 更新 dict
├── CheckChanges() → 对比 ETag/时间戳，检测是否有变化
└── ResolveConflicts() → 冲突处理策略
```

**冲突处理策略**：
- 默认：Last-Write-Wins（按文件修改时间戳）
- 高级：保留两份（`local_*.yml` 和 `remote_*.yml`），通知用户手动选择
- Git 模式：使用 Git 的 merge/rebase 机制

### 3. 同步调度策略

```
前台短轮询（WebDAV 专用）
├── 触发：App 在前台时启动，后台时停止
├── 间隔：WiFi 30s（可配置 10s~5min），移动网络 5min
├── 逻辑：
│   1. PROPFIND 远程目录 → 对比 ETag/时间戳
│   2. 如有变化 → GET 变化的文件 → 解析 YAML → 更新 dict
│   3. 更新 FileETags 缓存
└── 耗电：单次 PROPFIND ~1-2KB 流量，30s 间隔可忽略

后台 WorkManager（所有传输方式）
├── 约束：WiFi 连接（可选）、电池不低
├── 周期：15 分钟（最短允许值）
├── 逻辑：
│   1. CheckChanges() → 检测远程文件变化
│   2. 如有变化 → Pull() → 更新 dict
│   3. 检测本地 dict 是否有未推送的修改
│   4. 如有 → Push()
│   5. 更新 LastSyncTime → 发送通知
└── 失败重试：指数退避

即时推送（所有传输方式，Android 修改时）
├── 触发：用户在 Expandroid 中保存 match
├── 逻辑：序列化 YAML → Push() → 写入同步源
└── 延迟：< 1 秒（WebDAV PUT / SAF 写入）
```

### 4. 设置向导 UI

```
WizardStep 1: "你已经在使用桌面 espanso 吗？"
  └─ 是 → Step 2 | 否 → 空白开始 / 包商店

WizardStep 2: "选择同步方式"
  ├─ 🌐 WebDAV 同步（推荐）→ Step 3a
  ├─ ☁ 云文件夹同步       → Step 3b
  ├─ 📦 Git 仓库同步       → Step 3c
  ├─ 📡 局域网同步          → Step 3d
  └─ 📄 手动导入            → Step 3e

WizardStep 3a: "输入 WebDAV 服务器信息"
  └─ URL + 用户名 + 密码 + 远程路径 → 测试连接 → Step 4

WizardStep 3b: "选择云存储中的 espanso match 文件夹"
  └─ SAF 文件夹选择器 → 保存 URI → Step 4

WizardStep 3c: "输入 Git 仓库信息"
  └─ URL + PAT → clone → Step 4

WizardStep 3d: "扫描桌面二维码"
  └─ 相机扫码 → 连接 → Step 4

WizardStep 3e: "选择 YAML 配置文件"
  └─ 文件选择器 → 导入 → 完成

WizardStep 4: "同步中..." → "导入完成：N 条 matches，M 条 global_vars"
  └─ 完成 → 进入主界面
```

### 5. 桌面端配置指南

无需开发任何桌面端工具，用户只需选择一种方式：

#### WebDAV 方式（推荐，实时性最好）

**Linux / macOS**：

方式 1 — rclone 挂载（推荐）：
```bash
rclone mount webdav:espanso-match ~/.config/espanso/match --vfs-cache-mode writes --daemon
```

方式 2 — davfs2（Linux 原生，仅 Linux）：
```bash
mount -t davfs https://nas.local/dav/espanso-match ~/.config/espanso/match
```

**Windows**：

方式 1 — rclone 挂载（需 WinFsp，适合有管理员权限的机器）：
```powershell
# 安装 rclone 和 WinFsp
choco install rclone winfsp

# 挂载为盘符
rclone mount webdav:espanso-match E: --vfs-cache-mode writes
```

方式 2 — rclone sync + 计划任务（非挂载，适合受限环境）：
```powershell
# 安装 rclone（不需要 WinFsp）
choco install rclone

# 配置 WebDAV 远程
rclone config  # 选择 webdav，输入 OpenList URL + 凭据

# 创建同步脚本 espanso-sync.ps1
$remote = "webdav:espanso-match"
$local = "$env:APPDATA\espanso\match"

# 拉取远程变更
rclone sync $remote $local --exclude "*.tmp"
# 推送本地变更
rclone sync $local $remote --exclude "*.tmp"

# 注册为 Windows 计划任务（每 30 秒运行）
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
  -Argument "-WindowStyle Hidden -File C:\Scripts\espanso-sync.ps1"
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
  -RepetitionInterval (New-TimeSpan -Seconds 30)
Register-ScheduledTask -TaskName "EspansoWebDAVSync" `
  -Action $action -Trigger $trigger
```

方式 3 — 纯 PowerShell 脚本（零安装，适合完全受限环境）：
```powershell
# espanso-webdav-sync.ps1 — 无需安装任何工具
$baseUrl = "https://nas.local:5244/dav/espanso-match"
$user = "youruser"
$pass = "yourpass"
$local = "$env:APPDATA\espanso\match"
$headers = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$user`:$pass")) }

# 拉取：PROPFIND 获取文件列表，下载变化的文件
$resp = Invoke-WebRequest -Uri $baseUrl -Method PROPFIND -Headers $headers -SkipCertificateCheck
# 解析 XML 获取文件名和 ETag，对比本地缓存，下载变化的文件...
# （完整脚本约 50 行，此处省略 XML 解析部分）

# 推送：上传本地修改的文件
Get-ChildItem "$local\*.yml" | ForEach-Object {
    Invoke-WebRequest -Uri "$baseUrl/$($_.Name)" -Method PUT -Headers $headers `
      -InFile $_.FullName -SkipCertificateCheck
}
```

**受限环境特别说明**：
- 优先使用方式 2（rclone sync）或方式 3（纯脚本），无需 WinFsp / 管理员权限
- 计划任务可能需要用户首次登录后手动创建，或由 IT 管理员统一下发
- 确认桌面环境到 WebDAV 服务器的网络连通性（若 WebDAV 在本地 NAS，需通过公网 IP 或 VPN 访问）
- espanso 的 `auto_restart` 会检测 `$env:APPDATA\espanso\match\` 下的文件变化，rclone sync / 脚本写入后自动重载

**桌面端方案对比**：

| 方式 | 平台 | 需要安装 | 需要管理员 | 实时性 | 适合场景 |
|------|------|---------|-----------|--------|---------|
| rclone mount | 全平台 | rclone + WinFsp | 是（Windows） | 实时 | 个人电脑，有完整权限 |
| rclone sync + 计划任务 | 全平台 | 仅 rclone | 否 | 30s | 受限环境，无管理员权限 |
| 纯 PowerShell 脚本 | Windows | 无 | 否 | 30s | 完全受限环境 |
| davfs2 | Linux | davfs2 | 是 | 实时 | Linux 服务器/桌面 |

**云文件夹方式**：
```bash
# 将 espanso match 目录 symlink 到云同步文件夹
ln -s ~/Google\ Drive/espanso/ ~/.config/espanso/match
# 或反过来：在云客户端中添加 ~/.config/espanso/match/ 为同步文件夹
```

**Git 方式**：
```bash
cd ~/.config/espanso/match
git init && git remote add origin <repo-url>
# 可选自动同步脚本：
echo '* * * * * cd ~/.config/espanso/match && git add -A && git commit -m "auto" && git push' | crontab
```

---

## 实施路线图

### 第一期：基础设施 + WebDAV + CloudFolder 同步（核心）

| 任务 | 改动文件 | 优先级 |
|------|---------|--------|
| `YamlWorkspace` 服务 | 新建 `Services/YamlWorkspace.cs` | 高 |
| `SyncManager` 服务（含即时推送 + 智能轮询） | 新建 `Services/SyncManager.cs` | 高 |
| WebDAV 客户端 | 新建 `Services/WebDavClient.cs` | 高 |
| SAF 持久化 URI 管理 | `AppSettings.cs` + 新建 `Services/SafManager.cs` | 中 |
| WorkManager 后台同步 | 新建 `Platforms/Android/Workers/SyncWorker.cs` | 高 |
| 前台短轮询服务（WebDAV 30s 轮询） | 新建 `Platforms/Android/Services/PollingService.cs` | 中 |
| 设置向导 UI | 新建 `Pages/SyncSetupWizard.razor` | 高 |
| 同步状态指示器 | `Shared/SyncStatus.razor` | 中 |
| 批量 YAML 导入（替代当前单文件） | 重构 `Index.razor` ImportAsync | 高 |
| `imports` 递归解析 | `YamlWorkspace.cs` | 中 |
| WebDAV 兼容性测试 | 新建 `Tests/WebDavCompatTest.cs` | 低 |

### 第二期：Espanso Hub 客户端 + CloudFolder SAF

| 任务 | 改动文件 | 优先级 |
|------|---------|--------|
| Hub API 客户端 | 新建 `Services/HubClient.cs` | 中 |
| 包索引缓存 | `HubClient.cs` | 中 |
| 包浏览 UI | 新建 `Pages/PackageStore.razor` | 中 |
| 包安装（下载 + 校验 + 解压） | `HubClient.cs` | 中 |
| 已安装包管理 | 新建 `Models/InstalledPackage.cs` | 低 |

### 第三期：Git 同步（可选）

| 任务 | 改动文件 | 优先级 |
|------|---------|--------|
| libgit2sharp 集成 | `Expandroid.csproj` + `Services/GitSyncService.cs` | 低 |
| PAT 认证管理 | `Services/CredentialManager.cs` | 低 |
| Git 同步 UI | 扩展设置向导 | 低 |
| 冲突处理 UI | 新建 `Pages/ConflictResolver.razor` | 低 |

### 第四期：局域网同步（可选）

| 任务 | 改动文件 | 优先级 |
|------|---------|--------|
| 桌面端 companion 工具 | 独立项目 `espanso-sync` | 低 |
| mDNS 发现 | `Services/MdnsDiscovery.cs` | 低 |
| 二维码配对 | `Pages/PairingPage.razor` | 低 |
| HTTP 同步客户端 | `Services/LocalSyncService.cs` | 低 |

---

## 与兼容性改进计划的协同

本同步方案与 `ESPANSO_COMPATIBILITY_PLAN.md` 中的改进相互依赖：

| 兼容性改进 | 同步方案依赖 | 说明 |
|-----------|-------------|------|
| `triggers` 多触发词 | YamlWorkspace | 同步时需正确处理多触发词 |
| `imports` 跨文件引用 | YamlWorkspace | 同步时需递归解析 |
| 日期格式转换 | YamlWorkspace | YAML 中存 chrono 格式，内存中转 .NET 格式 |
| `left_word`/`right_word` | YamlWorkspace | YAML 字段需正确序列化 |
| `choice` 变量类型 | Hub 客户端 | hub 包中可能包含 choice 变量 |
| `propagate_case` | YamlWorkspace | YAML 字段需正确序列化 |

**建议实施顺序**：先完成兼容性改进的阶段一（模型扩展），再开始同步方案的第一期。这样 YamlWorkspace 在序列化/反序列化时就能直接支持新字段。

---

## 不在计划范围内

| 特性 | 原因 |
|------|------|
| `shell` / `script` 变量同步 | Android 无桌面 shell，同步后也无法执行 |
| `image_path` 同步 | AccessibilityService 不支持图片注入 |
| `config/default.yml` 同步 | 桌面端配置（backend, toggle_key 等）与 Android 无关 |
| `filter_title` / `filter_exec` 同步 | Android 用 packageName 机制，与桌面不同 |
| 实时同步（< 10 秒） | WebDAV 短轮询最短 10s，LocalNetwork 用 WebSocket 可达秒级；更短需前台常驻服务（耗电） |
| 远程触发扩展 | 属于 LocalNetwork 方案的扩展功能，第四期考虑 |
