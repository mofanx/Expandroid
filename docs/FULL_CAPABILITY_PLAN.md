# Expandroid 全能力覆盖方案设计文档

> **文档状态**：方案设计阶段（v2，已纳入评审 R4 修正）  
> **创建日期**：2025-06-22  
> **修订日期**：2025-06-22（R4：统一异步线程模型 + 依赖解析 + inject_vars 完善 + match 重写）  
> **关联文档**：`ESPANSO_COMPATIBILITY_PLAN.md`、`MATCH_EDITOR_REDESIGN.md`  
> **目标**：在 Android 端实现 Espanso 桌面版的全能力覆盖，包括 shell、script 变量及插件扩展系统

---

## 1. 背景与动机

### 1.1 当前能力差距

Expandroid 已实现 Espanso 的 6 个内置变量扩展（echo / date / clipboard / random / choice / form），但仍有 **2 个核心变量类型未实现**：

| Espanso 扩展 | 源码位置 | 当前状态 |
|---|---|---|
| `shell` | `espanso-render/src/extension/shell.rs:193` | ❌ 未实现 |
| `script` | `espanso-render/src/extension/script.rs:29` | ❌ 未实现 |
| `match`（递归引用） | `espanso-render/src/renderer/mod.rs:99` | ❌ 未实现 |

此外，Espanso 的 `Extension` trait（`espanso-render/src/lib.rs:120`）采用编译期注册模式，8 个扩展在 `engine/mod.rs:216-225` 中一次性注册。Android 侧需要设计一个**运行时可扩展的注册表**来对齐这一架构。

### 1.2 用户需求

Espanso 桌面端用户迁移到 Android 时，常见配置中包含：

```yaml
# shell 变量 — 执行命令获取输出
- trigger: ":ip"
  replace: "{{ip}}"
  vars:
    - name: ip
      type: shell
      params:
        cmd: "curl -s ifconfig.me"

# script 变量 — 执行外部脚本
- trigger: ":pycalc"
  replace: "{{result}}"
  vars:
    - name: result
      type: script
      params:
        args:
          - python
          - -c
          - "print(2**10)"

# 依赖注入 — shell 变量引用其他变量
- trigger: ":greet"
  replace: "{{greeting}}"
  vars:
    - name: name
      type: echo
      params:
        echo: "World"
    - name: greeting
      type: shell
      params:
        cmd: "echo 'Hello, $ESPANSO_NAME'"
      inject_vars: true
```

这些配置在当前 Expandroid 中会被跳过（`parseItem` 的 `else` 分支，`ExpanderAccessibilityService.kt:578`），用户无感知地丢失功能。

---

## 2. 候选方案评估

### 2.1 方案矩阵

| 方案 | 核心机制 | 需 root | 需额外 App | 执行模式 | shell 能力 | 实现复杂度 |
|------|---------|---------|-----------|---------|-----------|-----------|
| **A. Termux** | `RunCommandService` Intent | 否 | 是 | 异步 | 完整 | 中（~400 行） |
| **B. Shizuku** | `UserService` Binder IPC | 否 | 是 | 同步 | 完整 | 中高（~500 行 + AIDL） |
| **C. Root** | `Runtime.exec("su -c ...")` | 是 | 否 | 同步 | 完整 | 低（~100 行） |
| **D. WebView JS** | `evaluateJavascript()` | 否 | 否 | 异步（主线程） | 无 | 低 |
| **E. QuickJS 嵌入** | JNI JS 引擎 | 否 | 否 | 同步 | 无 | 低（~150 行） |
| **F. HTTP 远程** | OkHttp 请求 | 否 | 否 | 同步（子线程） | 需服务端 | 低（~100 行） |

### 2.2 逐方案深度分析

#### 方案 A：Termux RUN_COMMAND Intent

**机制**：  
Termux 提供 `RunCommandService`（`com.termux.app.RunCommandService`），第三方 App 通过 Intent 发送命令，Termux 在其进程上下文中执行，通过 PendingIntent 异步返回结果。

**关键 API**（源：[Termux Wiki - RUN_COMMAND Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)）：

```kotlin
Intent().apply {
    setClassName("com.termux", "com.termux.app.RunCommandService")
    action = "com.termux.RUN_COMMAND"
    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "print(1+1)"))
    putExtra("com.termux.RUN_COMMAND_STDIN", scriptContent)  // 可直接传脚本内容
    putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)  // 后台执行才能拿到 stdout
    putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
}
```

**结果回调**：  
通过 `IntentService` 接收 `TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE`，包含：
- `EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT`：命令标准输出
- `EXTRA_PLUGIN_RESULT_BUNDLE_STDERR`：错误输出
- `EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE`：退出码

**优势**：
- 免 root，用户基数覆盖中等
- 完整 Linux 环境，支持 Python / Node.js / Ruby 等所有 Termux 可安装的语言
- `EXTRA_STDIN` 可直接传脚本内容，无需创建物理文件
- YAML 配置与 Espanso 桌面端完全兼容，无需修改

**致命问题 1：异步回调与 AccessibilityService 的根本矛盾**

`onAccessibilityEvent` 在**主线程**执行（Android 框架设计，源：[AOSP `AccessibilityService.java`](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java)），且 `AccessibilityEvent` 对象在回调结束后会被系统回收复用（源：[SO 回答](https://stackoverflow.com/questions/50464753)）。

当前 `choice` 变量的异步暂停-恢复模式（`ExpanderAccessibilityService.kt:589-680`）之所以能工作，是因为它**不阻塞主线程** — 弹出 UI 后 `onAccessibilityEvent` 正常返回，用户选择后通过新的 `doExpansion` 调用完成替换。

但 Termux 方案要复杂得多：一个 match 可能包含**多个 shell 变量 + 普通变量混合**，需要串行执行。期间用户继续输入会触发新的 `onAccessibilityEvent`，导致**扩展上下文混乱**。

需要引入全局锁 `isExpansionInProgress`，但这会**让用户感觉键盘无响应**。

**致命问题 2：Termux 后台被杀**

MIUI / EMUI / ColorOS 等国产 ROM 积极杀后台进程。Termux 被杀后 Intent 发送成功但命令不执行，用户看到 `[timeout]`。虽有 `termux-wake-lock`，但需用户额外操作。

**致命问题 3：首次配置成本高**

用户需要：安装 Termux → 打开 Termux → `pkg install python` → 安装 pip 包 → Android Settings → Apps → Expandroid → Permissions → Additional permissions → 勾选 "Run commands in Termux environment"。对普通用户门槛极高。

**结论**：Termux 方案能力覆盖最广，但异步问题是**架构级**的，非工程努力可完美解决。适合作为**最后备选后端**。

---

#### 方案 B：Shizuku UserService

**机制**：  
Shizuku 以 ADB 或 root 权限运行一个服务进程，第三方 App 通过 Binder IPC 调用 `UserService` — 开发者编写的 Java/Kotlin 类在 Shizuku 的 shell 权限进程中实例化并执行。

**关键 API**（源：[Shizuku-API README](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)）：

```kotlin
// AIDL 接口
interface IShellService {
    String execute(in String cmd, in String[] env, long timeoutMs);
}

// 实现类（在 shell 权限进程中运行）
class ShellServiceImpl : IShellService.Stub() {
    override fun execute(cmd: String, env: Array<String>, timeoutMs: Long): String {
        val pb = ProcessBuilder("sh", "-c", cmd)
        env.forEach { e ->
            val (k, v) = e.split("=", limit = 2)
            pb.environment()[k] = v
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        process.destroyForcibly()
        return output
    }
}

// 注册并调用
val userServiceArgs = Shizuku.UserServiceArgs(
    ComponentName(context, ShellServiceImpl::class.java)
).processName("expandroid_shell").debuggable(BuildConfig.DEBUG)

Shizuku.bindUserService(userServiceArgs, object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        shellService = IShellService.Stub.asInterface(binder)
    }
    override fun onServiceDisconnected(name: ComponentName) {
        shellService = null
    }
})

// 同步调用
val result = shellService?.execute(cmd, envArray, 5000)
```

**优势**：
- **同步调用** — Binder IPC 同步返回结果，完美解决 Termux 的异步问题
- 免 root（需 ADB 或无线调试激活）
- `UserService` 比 `newProcess`（已废弃，源：[Shizuku-API README](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)）更强大，可执行任意 Java/Kotlin 代码
- ADB shell 用户（uid 2000）权限足够执行 `Runtime.exec()`、访问 `/sdcard` 等

**问题 1：`newProcess()` 已废弃**  
官方明确推荐 `UserService` 替代，但需要编写 AIDL 接口 + 实现类，复杂度高于 Termux。

**问题 2：Shizuku 激活门槛**  
Android 11+ 支持无线调试（无需电脑），但流程仍不简单：开发者选项 → 无线调试 → 配对 → Shizuku 中输入配对码。对不熟悉 ADB 的用户比安装 Termux 更难。

**问题 3：ADB 权限限制**  
ADB shell 用户权限介于普通 App 和 root 之间。无法访问其他 App 私有数据目录，无法使用 `am` 启动非导出组件。

**问题 4：服务可能断开**  
设备重启后 Shizuku 需重新激活。App 需检测并提示用户。

**结论**：同步调用是巨大优势，复杂度可控。适合作为**次优先后端**。

---

#### 方案 C：Root

**机制**：  
```kotlin
val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
val output = process.inputStream.bufferedReader().readText()
process.waitFor()
```

**优势**：同步、零额外 App、完整 root 权限、实现最简单。

**问题**：2024 年 root 设备占比 < 5%，且持续下降。三星/华为 root 后丢失 Samsung Pay/Knox 等功能。**不适合作为主方案**，但可作为**可选增强**。

---

#### 方案 D：WebView JS

**问题 1**：`evaluateJavascript()` 和回调都在主线程，用 `CountDownLatch` 阻塞会**死锁主线程**（源：[SO 讨论](https://stackoverflow.com/questions/50998907/android-webview-blocking-evaluatejavascript)）。

**问题 2**：WebView 初始化占用 ~30-50MB 内存，对 AccessibilityService 太重。

**问题 3**：JS 无法执行 shell 命令、无法访问文件系统。

**结论**：❌ 不适合。

---

#### 方案 E：QuickJS 嵌入

**机制**：  
[quickjs-java](https://github.com/cashapp/quickjs-java/) 提供 QuickJS JS 引擎的纯 Java 封装，可在子线程**同步执行** JS 代码，内存占用 ~100KB。

```kotlin
val engine = QuickJSEngine()
val result = engine.evaluate("1 + 2 * 3")  // 同步返回 "7"
engine.close()
```

**优势**：零依赖、同步、轻量、始终可用。

**局限**：无法执行 shell 命令、无法访问网络（除非加 JNI 扩展）、无法访问文件系统。**不能替代 shell/script**，但可作为**补充变量类型** `type: "javascript"`。

**结论**：✅ 适合作为 P0 内置变量类型。

---

#### 方案 F：HTTP 远程

**机制**：  
```kotlin
// 在子线程同步调用
val client = OkHttpClient()
val request = Request.Builder().url(url).build()
val response = client.newCall(request).execute()
val body = response.body?.string()
```

**优势**：零依赖（OkHttp 已在项目中）、同步（子线程）、实现简单。

**局限**：需要用户自建服务端或使用第三方 API。不适合通用 shell 替代。

**结论**：✅ 适合作为 P0 内置变量类型 `type: "http"`，覆盖 API 调用场景。

---

### 2.3 方案 PK 矩阵

| 维度 | Termux | Shizuku | Root | QuickJS | HTTP |
|------|--------|---------|------|---------|------|
| **执行模型** | ❌ 原生异步 | ✅ 子线程同步→回调 | ✅ 子线程同步→回调 | ✅ 子线程同步→回调 | ✅（子线程） |
| **无需额外 App** | ❌ | ❌ | ✅ | ✅ | ✅ |
| **无需 root** | ✅ | ✅ | ❌ | ✅ | ✅ |
| **shell 命令** | ✅ | ✅ | ✅ | ❌ | ⚠️（需服务端） |
| **Python 脚本** | ✅ | ✅（需装 Python） | ✅ | ❌ | ⚠️ |
| **JS 脚本** | ✅（node） | ✅（node） | ✅ | ✅ | ⚠️ |
| **首次配置成本** | 高 | 高 | 中 | 低 | 低 |
| **国产 ROM 兼容** | ⚠️ 后台被杀 | ⚠️ 服务断开 | ✅ | ✅ | ✅ |
| **多变量串行** | ✅ R4 统一管线 | ✅ 简单 | ✅ 简单 | ✅ 简单 | ✅ 简单 |
| **实现复杂度** | 中（~400 行） | 中高（~500 行 + AIDL） | 低（~100 行） | 低（~150 行） | 低（~100 行） |
| **用户基数覆盖** | 中 | 中 | 低 | 高 | 高 |

---

## 3. 推荐方案：混合分层架构

### 3.1 设计原则

1. **不赌单一方案** — 按能力分层，自动探测可用后端
2. **零依赖优先** — 内置能力覆盖常见场景，外部后端作为增强
3. **统一异步** — 所有变量执行（含 Root/Shizuku 同步后端）统一走子线程 + 暂停-恢复模式，避免主线程阻塞 ANR（R4 修正）
4. **依赖解析** — 移植 Espanso `resolve.rs` 的拓扑排序算法，正确处理 `depends_on` / `inject_vars` 隐式依赖和乱序变量（R4 修正）
5. **YAML 兼容** — 用户配置与 Espanso 桌面端完全一致，无需修改
6. **优雅降级** — 后端不可用时返回明确提示，不中断扩展流程

### 3.2 分层架构

```
Layer 0: 内置变量（始终可用，零依赖，统一异步执行）
  ├── echo / date / clipboard / random / choice / form  ← 已实现
  ├── javascript  ← 新增（QuickJS 嵌入引擎）
  ├── http        ← 新增（OkHttp 请求）
  └── match       ← 新增（递归引用其他 match，完整渲染流程）

Layer 1: Shell 执行后端（自动探测，按优先级降级，统一异步执行）
  ├── 1a. Root     ← 如果设备已 root（子线程同步 → 异步回调）
  ├── 1b. Shizuku  ← 如果已安装并激活（Binder 同步 → 异步回调）
  └── 1c. Termux   ← 如果已安装并授权（原生异步 PendingIntent）

Layer 2: 插件扩展系统（始终可用，统一异步执行）
  ├── intent   ← 新增（Intent 广播调用第三方 App）
  └── content  ← 新增（ContentProvider 查询）
```

### 3.3 后端探测与选择逻辑

```kotlin
object ShellBackendDetector {
    fun detectBestBackend(context: Context): ShellBackend {
        // 1. 检测 Root
        if (RootChecker.isRootAvailable()) {
            return ShellBackend.ROOT
        }
        // 2. 检测 Shizuku
        if (ShizukuChecker.isShizukuRunning()) {
            return ShellBackend.SHIZUKU
        }
        // 3. 检测 Termux
        if (TermuxChecker.isTermuxAvailable(context) &&
            TermuxChecker.hasRunCommandPermission(context)) {
            return ShellBackend.TERMUX
        }
        // 4. 都不可用
        return ShellBackend.NONE
    }
}

enum class ShellBackend {
    ROOT,       // 子线程同步 → 异步回调，最高优先级
    SHIZUKU,    // Binder 同步 → 异步回调，次优先
    TERMUX,     // 原生异步 PendingIntent，最后备选
    NONE        // 不可用
}
```

### 3.4 可扩展注册表设计

对齐 Espanso 的 `Extension` trait（`espanso-render/src/lib.rs:120`）和 `DefaultRenderer`（`espanso-render/src/renderer/mod.rs:40-52`）：

```kotlin
// Espanso 对应：
// pub trait Extension {
//     fn name(&self) -> &str;
//     fn calculate(&self, context: &Context, scope: &Scope, params: &Params) -> ExtensionResult;
// }
// pub enum ExtensionOutput { Single(String), Multiple(HashMap<String, String>) }
// pub enum ExtensionResult { Success(ExtensionOutput), Aborted, Error(anyhow::Error) }
// — source: espanso-render/src/lib.rs:120-138

// R4 修正：对齐 Espanso 三态结果 + 多值输出
sealed class ExtensionOutput {
    data class Single(val value: String) : ExtensionOutput()
    data class Multiple(val values: Map<String, String>) : ExtensionOutput()
}

sealed class ExtensionResult {
    data class Success(val output: ExtensionOutput) : ExtensionResult()
    object Aborted : ExtensionResult()       // 用户取消（如 form 关闭）
    data class Error(val message: String) : ExtensionResult()
}

interface VariableExtension {
    val typeName: String

    // R4 修正：统一异步接口，所有变量（含同步的 echo/date）都通过此接口调用
    // 在子线程执行，通过 callback 回传结果到主线程
    fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    )
}

// 注册表（对齐 DefaultRenderer.extensions: HashMap<String, &dyn Extension>）
class ExtensionRegistry {
    private val extensions = mutableMapOf<String, VariableExtension>()

    fun register(ext: VariableExtension) {
        extensions[ext.typeName] = ext
    }

    fun get(typeName: String): VariableExtension? = extensions[typeName]
}
```

---

## 4. 详细设计

### 4.0 依赖解析算法（R4 新增）

**Espanso 源码对齐**：
`espanso-render/src/renderer/resolve.rs:38-69` — `resolve_evaluation_order()` 函数通过拓扑排序确定变量求值顺序。

**核心逻辑**（`resolve.rs:71-98`）：
1. 为每个局部变量生成依赖节点
2. `inject_vars == true` 时，扫描 params 中的 `{{var}}` 引用作为隐式依赖（`resolve.rs:79-80`）
3. `depends_on` 列表作为显式依赖（`resolve.rs:82`）
4. 每个局部变量依赖前一个变量，保证执行顺序（`resolve.rs:84-91`）
5. body 依赖所有局部变量 + body 中引用的全局变量（`resolve.rs:103-105`）
6. 递归解析依赖，检测循环引用（`resolve.rs:53`）

**问题示例**（当前代码无法处理）：
```yaml
vars:
  - name: b
    type: echo
    params: { echo: "{{a}}" }
    inject_vars: true
  - name: a
    type: shell
    params: { cmd: "echo hello" }
```
当前代码（`ExpanderAccessibilityService.kt:331-332`）顺序遍历 → b 先执行 → `{{a}}` 未被替换 → 输出 `{{a}}`。
Espanso 会自动重排为 a → b → 输出 `hello`。

**Kotlin 移植实现**：
```kotlin
data class DependencyNode(
    val name: String,
    val variable: Var?,
    val dependencies: Set<String>
)

fun resolveEvaluationOrder(
    body: String,
    localVars: List<Var>,
    globalVars: List<Var>
): Result<List<Var>> {
    val nodeMap = mutableMapOf<String, DependencyNode>()

    // 1. 为局部变量生成节点
    localVars.forEachIndexed { index, v ->
        val deps = mutableSetOf<String>()
        if (v.injectVars) {
            // 扫描 params 中的 {{var}} 引用
            deps.addAll(scanParamVariableNames(v.params))
        }
        v.dependsOn?.let { deps.addAll(it) }
        // 每个局部变量依赖前一个（保证顺序）
        if (index > 0) deps.add(localVars[index - 1].name ?: "")
        nodeMap[v.name ?: ""] = DependencyNode(v.name ?: "", v, deps)
    }

    // 2. 为全局变量生成节点
    globalVars.forEach { v ->
        val deps = mutableSetOf<String>()
        if (v.injectVars) deps.addAll(scanParamVariableNames(v.params))
        v.dependsOn?.let { deps.addAll(it) }
        nodeMap[v.name ?: ""] = DependencyNode(v.name ?: "", v, deps)
    }

    // 3. body 节点依赖所有局部变量 + body 中的变量引用
    val bodyDeps = mutableSetOf<String>()
    bodyDeps.addAll(localVars.mapNotNull { it.name })
    bodyDeps.addAll(scanBodyVariableNames(body))
    nodeMap["__match_body"] = DependencyNode("__match_body", null, bodyDeps)

    // 4. 拓扑排序 + 循环检测
    val evalOrder = mutableListOf<String>()
    val resolved = mutableSetOf<String>()
    val seen = mutableSetOf<String>()  // 用于循环检测

    fun resolveDeps(name: String): Result<Unit> {
        if (name in resolved) return Result.success(Unit)
        if (name in seen) return Result.failure(Exception("Circular dependency detected: $name"))
        seen.add(name)
        val node = nodeMap[name] ?: return Result.failure(Exception("Missing dependency: $name"))
        for (dep in node.dependencies) {
            resolveDeps(dep).onFailure { return it }
        }
        seen.remove(name)
        resolved.add(name)
        if (node.variable != null) evalOrder.add(name)
        return Result.success(Unit)
    }

    resolveDeps("__match_body").onFailure { return it as Result<List<Var>> }
    return Result.success(evalOrder.mapNotNull { nodeMap[it]?.variable })
}

// 扫描 params 中的 {{var}} 引用（对齐 util.rs:37-67 get_params_variable_names）
fun scanParamVariableNames(params: Params): Set<String> {
    val regex = Regex("\\{\\{(\\w+)\\}\\}")
    val names = mutableSetOf<String>()
    params.forEach { (_, value) ->
        when (value) {
            is String -> regex.findAll(value).forEach { names.add(it.groupValues[1]) }
            is List<*> -> value.forEach { if (it is String) regex.findAll(it).forEach { names.add(it.groupValues[1]) } }
            is Map<*, *> -> value.values.forEach { if (it is String) regex.findAll(it).forEach { names.add(it.groupValues[1]) } }
        }
    }
    return names
}

// 扫描 body 中的 {{var}} 引用（对齐 util.rs:28-35 get_body_variable_names）
fun scanBodyVariableNames(body: String): Set<String> {
    return Regex("\\{\\{(\\w+)\\}\\}").findAll(body).map { it.groupValues[1] }.toSet()
}
```

**验收标准**：
- [ ] 乱序变量正确重排：b 依赖 a 但 b 在 a 之前 → 自动重排为 a → b
- [ ] `inject_vars: true` 的变量，其 params 中的 `{{var}}` 被识别为隐式依赖
- [ ] `depends_on: ["x"]` 被识别为显式依赖
- [ ] 循环引用（A → B → A）被检测并报错
- [ ] 全局变量参与依赖解析
- [ ] body 中的 `{{var}}` 引用被正确识别

---

### 4.1 Layer 0：内置变量

#### 4.1.1 `javascript` 变量（QuickJS 嵌入）

**YAML 配置示例**：
```yaml
- trigger: ":calc"
  replace: "{{result}}"
  vars:
    - name: result
      type: javascript
      params:
        code: "Math.pow(2, 10)"
```

**Espanso 对齐**：  
Espanso 桌面端无 `javascript` 变量类型，但这是 Android 端的**增强能力**，不破坏兼容性（Espanso 导入时会跳过未知类型）。

**实现**：
```kotlin
class JavaScriptExtension : VariableExtension {
    override val typeName = "javascript"

    // R4 修正：统一异步接口，在子线程执行 JS，避免阻塞主线程
    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val code = params.string("code") ?: run {
            callback(ExtensionResult.Error("missing 'code' param"))
            return
        }
        // 在子线程执行 QuickJS
        Thread {
            try {
                val engine = QuickJSEngine()
                // 注入 scope 变量
                scope.forEach { (k, v) ->
                    val value = when (v) {
                        is ExtensionOutput.Single -> v.value
                        is ExtensionOutput.Multiple -> v.values.toString()
                    }
                    engine.setGlobal(k, value)
                }
                val result = engine.evaluate(code)
                engine.close()
                callback(ExtensionResult.Success(ExtensionOutput.Single(result?.toString() ?: "")))
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "JS error"))
            }
        }.start()
    }
}
```

**依赖**：`app.cash.quickjs:quickjs-android:0.9.2`（Maven Central，~100KB，纯 Java JNI 封装）

> ⚠️ R4 修正：原方案引用 `dev.rikka.rikka:quickjs-android` 不存在。正确坐标为 `app.cash.quickjs:quickjs-android:0.9.2`（[Maven Central](https://central.sonatype.com/artifact/app.cash.quickjs/quickjs-android)）或 `wang.harlon.quickjs:wrapper-android:3.2.0`（更活跃维护，2025-05-15 最新）。

**验收标准**：
- [ ] `type: javascript` + `params.code: "1+1"` → 替换为 `2`
- [ ] `params.code: "Math.max(3, 7)"` → 替换为 `7`
- [ ] `params.code: "'hello'.toUpperCase()"` → 替换为 `HELLO`
- [ ] scope 变量注入：`code: "name + ' world'"` + scope `{name: "hello"}` → `hello world`
- [ ] JS 执行错误返回 `[js error: ...]`，不崩溃
- [ ] R4：在子线程执行，不阻塞主线程（通过 `Thread {}.start()` 或协程 `Dispatchers.IO`）

---

#### 4.1.2 `http` 变量

**YAML 配置示例**：
```yaml
- trigger: ":weather"
  replace: "{{weather}}"
  vars:
    - name: weather
      type: http
      params:
        url: "https://wttr.in/?format=3"
        method: "GET"
        timeout: 5000
        json_path: "$.current.temp_c"  # 可选，仅 method=GET 且返回 JSON 时
```

**Espanso 对齐**：  
Espanso 桌面端无 `http` 变量类型（用户通常用 `shell` + `curl` 实现），但这是 Android 端的**增强能力**。

**实现**：
```kotlin
class HttpExtension : VariableExtension {
    override val typeName = "http"

    // R4 修正：统一异步接口，在子线程执行 HTTP 请求，避免阻塞主线程
    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val url = params.string("url") ?: run {
            callback(ExtensionResult.Error("missing 'url' param"))
            return
        }
        val method = params.string("method") ?: "GET"
        val timeout = params.long("timeout").takeIf { it > 0 }?.toInt() ?: 5000
        val jsonPath = params.string("json_path")

        // 在子线程执行 HTTP 请求
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder().url(url).method(method, null).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                val result = if (jsonPath != null) extractJsonPath(body, jsonPath) else body.trim()
                callback(ExtensionResult.Success(ExtensionOutput.Single(result)))
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "HTTP error"))
            }
        }.start()
    }
}
```

**依赖**：OkHttp（已在项目中）、可选 JsonPath（`com.jayway.jsonpath:json-path`）

**验收标准**：
- [ ] `url: "https://wttr.in/?format=3"` → 返回天气文本
- [ ] `timeout: 3000` → 超时后返回 `[http error: timeout]`
- [ ] `json_path: "$.temp"` + JSON `{"temp": 25}` → 返回 `25`
- [ ] 网络不可用时返回 `[http error: ...]`，不崩溃
- [ ] R4：在子线程执行，不阻塞主线程

---

#### 4.1.3 `match` 变量（递归引用）

**Espanso 源码对齐**：  
`espanso-render/src/renderer/mod.rs:99-114` — 当 `variable.var_type == "match"` 时，递归查找匹配的 template 并调用 `self.render()`。

**YAML 配置示例**：
```yaml
- trigger: ":greeting"
  replace: "Hello!"
  label: "greeting match"

- trigger: ":formal"
  replace: "{{greeting}}"
  vars:
    - name: greeting
      type: match
      params:
        trigger: ":greeting"  # 通过 trigger 引用
```

**实现**（R4 重写：调用完整渲染流程，对齐 `self.render()`）：

Espanso 的 `match` 变量调用 `self.render(sub_template, context, options)`（`mod.rs:105`），完整执行：依赖解析 → 变量求值 → 模板替换 → 大小写处理。简化遍历 `parseItem` 会丢失递归 match、依赖排序、inject_vars、Aborted/Error 传播。

```kotlin
class MatchExtension(
    private val dict: Map<String, Match>,
    private val globalVars: List<Var>,
    private val renderer: TemplateRenderer  // 完整渲染器引用
) : VariableExtension {
    override val typeName = "match"

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val trigger = params.string("trigger") ?: run {
            callback(ExtensionResult.Error("missing 'trigger' param"))
            return
        }
        val referencedMatch = dict[trigger] ?: run {
            callback(ExtensionResult.Error("match not found: $trigger"))
            return
        }

        // R4 修正：调用完整渲染流程，等价于 Espanso 的 self.render(sub_template)
        // 而非简单遍历 parseItem
        renderer.render(referencedMatch, scope) { result ->
            when (result) {
                is ExtensionResult.Success -> callback(result)
                is ExtensionResult.Aborted -> callback(result)  // 传播 Aborted
                is ExtensionResult.Error -> callback(result)     // 传播 Error
            }
        }
    }
}

// TemplateRenderer 封装完整渲染流程（对齐 DefaultRenderer::render）
interface TemplateRenderer {
    fun render(
        template: Match,
        parentScope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    )
}
```

**验收标准**：
- [ ] `type: match` + `params.trigger: ":greeting"` → 递归渲染 `:greeting` 的 replace
- [ ] 引用的 match 包含变量时，变量也被正确解析（完整渲染流程）
- [ ] R4：引用的 match 包含 shell 变量时，shell 也被执行
- [ ] R4：引用的 match 包含 match 变量时，递归正确工作（match 引用 match 引用 match）
- [ ] R4：引用的 match 的变量乱序时，依赖解析正确重排
- [ ] R4：引用的 match 的 Aborted/Error 状态正确传播
- [ ] 引用不存在的 trigger 时返回 `[match not found: :xxx]`
- [ ] 防止循环引用（A 引用 B，B 引用 A）→ 检测到循环时返回 `[circular reference]`

---

### 4.2 Layer 1：Shell 执行后端

#### 4.2.1 通用 Shell 执行接口

**Espanso 源码对齐**：  
- `shell` 扩展（`espanso-render/src/extension/shell.rs:193-296`）：读取 `params["cmd"]`，通过 `shell.execute_cmd()` 执行，返回 stdout
- `script` 扩展（`espanso-render/src/extension/script.rs:29-164`）：读取 `params["args"]` 数组，`Command::new(args[0])` 执行，返回 stdout
- 环境变量注入（`shell.rs:230` / `script.rs:89`）：`convert_to_env_variables(scope)` 将 scope 变量转为环境变量
- `trim` 参数（`shell.rs:274-284` / `script.rs:142-152`）：默认 `true`，去除输出首尾空白
- `debug` 参数（`shell.rs:247-261` / `script.rs:107-121`）：默认 `false`，输出调试信息
- `ignore_error` 参数（`script.rs:123-139`）：默认 `false`，非零退出码时返回错误

**统一接口**（R4 修正：统一为异步接口，所有后端都通过 callback 返回结果）：
```kotlin
interface ShellExecutor {
    val backendName: String

    // R4 修正：统一异步接口，所有后端（含 Root/Shizuku）都通过 callback 返回
    // Root/Shizuku 在子线程执行同步调用后回调
    // Termux 通过 PendingIntent 异步回调
    fun execute(
        cmd: String,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    )
}

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean
)
```

#### 4.2.2 Root 后端（1a）

**实现**（R4 修正：使用 ProcessBuilder 避免命令注入 + 子线程异步回调）：
```kotlin
class RootShellExecutor : ShellExecutor {
    override val backendName = "root"

    override fun execute(
        cmd: String,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    ) {
        // R4 修正：在子线程执行，避免阻塞主线程 ANR
        Thread {
            try {
                // R4 修正：使用 ProcessBuilder 而非 shell 拼接，避免命令注入
                val pb = ProcessBuilder("su", "-c", cmd)
                // 安全地设置环境变量
                env.forEach { (k, v) -> pb.environment()[k] = v }
                val process = pb.start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!exited) process.destroyForcibly()
                val exitCode = if (exited) process.exitValue() else -1
                callback(ShellResult(stdout, stderr, exitCode, exitCode == 0))
            } catch (e: Exception) {
                callback(ShellResult("", e.message ?: "root error", -1, false))
            }
        }.start()
    }
}
```

> ⚠️ R4 修正：原方案使用 `export $k='$v';` 拼接环境变量，当 `v` 含单引号时会导致命令注入。改用 `ProcessBuilder.environment()` 安全注入。

**Root 检测**：
```kotlin
object RootChecker {
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(3000, TimeUnit.MILLISECONDS)
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }
}
```

**验收标准**：
- [ ] `cmd: "echo hello"` → stdout = `hello`，exitCode = 0
- [ ] `cmd: "whoami"` → stdout = `root`
- [ ] 环境变量注入：`cmd: "echo $NAME"` + env `{NAME: "world"}` → stdout = `world`
- [ ] R4：环境变量含单引号时不发生命令注入（如 `NAME: "it's"`）
- [ ] 超时：`cmd: "sleep 10"` + `timeout: 2000` → 2 秒后返回，exitCode = -1
- [ ] 设备未 root 时 `isRootAvailable()` 返回 false
- [ ] R4：在子线程执行，不阻塞主线程

---

#### 4.2.3 Shizuku 后端（1b）

**AIDL 接口** (`IShellService.aidl`)：
```aidl
// IShellService.aidl
package com.dingleinc.texttoolspro;

interface IShellService {
    String execute(in String cmd, in String[] env, long timeoutMs);
    String executeScript(in String[] args, in String[] env, long timeoutMs);
}
```

**实现类**（在 Shizuku shell 权限进程中运行）：
```kotlin
class ShellServiceImpl : IShellService.Stub() {
    override fun execute(cmd: String, env: Array<String>, timeoutMs: Long): String {
        val pb = ProcessBuilder("sh", "-c", cmd)
        env.forEach { e ->
            val idx = e.indexOf('=')
            if (idx > 0) pb.environment()[e.substring(0, idx)] = e.substring(idx + 1)
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        return output
    }

    override fun executeScript(args: Array<String>, env: Array<String>, timeoutMs: Long): String {
        val pb = ProcessBuilder(*args)
        env.forEach { e ->
            val idx = e.indexOf('=')
            if (idx > 0) pb.environment()[e.substring(0, idx)] = e.substring(idx + 1)
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        return output
    }
}
```

**Shizuku 检测与绑定**：
```kotlin
object ShizukuChecker {
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(context: Context): Boolean {
        return if (Shizuku.isPreV11()) {
            context.checkSelfPermission("moe.shizuku.manager.permission.API_V23") ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }
}

class ShizukuShellExecutor(private val context: Context) : ShellExecutor {
    override val backendName = "shizuku"

    private var shellService: IShellService? = null

    fun bind() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ShellServiceImpl::class.java)
        ).processName("expandroid_shell").debuggable(BuildConfig.DEBUG)

        Shizuku.bindUserService(args, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                shellService = IShellService.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                shellService = null
            }
        })
    }

    // R4 修正：统一异步接口，Binder 同步调用在子线程执行后回调
    override fun execute(
        cmd: String,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    ) {
        val service = shellService
        if (service == null) {
            callback(ShellResult("", "Shizuku service not bound", -1, false))
            return
        }
        val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        // R4 修正：在子线程执行 Binder 同步调用，避免阻塞主线程
        Thread {
            try {
                val output = service.execute(cmd, envArray, timeoutMs)
                callback(ShellResult(output, "", 0, true))
            } catch (e: Exception) {
                callback(ShellResult("", e.message ?: "shizuku error", -1, false))
            }
        }.start()
    }
}
```

**依赖**：`dev.rikka.shizuku:api`、`dev.rikka.shizuku:shared`

**验收标准**：
- [ ] Shizuku 未运行时 `isShizukuRunning()` 返回 false
- [ ] Shizuku 运行但未授权时 `hasPermission()` 返回 false
- [ ] 授权后 `bind()` 成功，`shellService` 非 null
- [ ] `cmd: "echo hello"` → stdout = `hello`
- [ ] `cmd: "whoami"` → stdout = `shell`
- [ ] 设备重启后检测 Shizuku 断开，提示用户重新激活
- [ ] AIDL 接口编译通过，`ShellServiceImpl` 在 shell 权限进程中正确实例化
- [ ] R4：在子线程执行 Binder 调用，不阻塞主线程

---

#### 4.2.4 Termux 后端（1c）

**实现**（R4 修正：移除 isSynchronous 字段，使用 EXTRA_ARGUMENTS 传递 `-c` 而非 EXTRA_STDIN）：
```kotlin
class TermuxShellExecutor(private val context: Context) : ShellExecutor {
    override val backendName = "termux"

    override fun execute(
        cmd: String,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    ) {
        // R4 修正：使用 EXTRA_ARGUMENTS 传递 -c 而非 EXTRA_STDIN
        // EXTRA_STDIN 是 stdin 数据流，用 -c 更明确且可靠
        val envExports = env.entries.joinToString("\n") { (k, v) ->
            // R4 修正：转义单引号防止命令注入
            val escapedV = v.replace("'", "'\\\''")
            "export $k='$escapedV'"
        }
        val fullCmd = "$envExports\n$cmd"

        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            // R4 修正：用 -c 参数传递完整脚本
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", fullCmd))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }

        // 创建 PendingIntent 接收结果
        val resultIntent = Intent(context, TermuxResultService::class.java)
        val executionId = TermuxResultService.getNextExecutionId()
        resultIntent.putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId)

        val pendingIntent = PendingIntent.getService(
            context, executionId, resultIntent,
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)

        // 注册回调
        TermuxResultService.registerCallback(executionId) { result ->
            callback(ShellResult(result.stdout, result.stderr, result.exitCode, result.exitCode == 0))
        }

        // 超时处理
        Handler(Looper.getMainLooper()).postDelayed({
            TermuxResultService.unregisterCallback(executionId)
            callback(ShellResult("", "timeout", -1, false))
        }, timeoutMs)

        context.startService(intent)
    }
}
```

**结果接收 Service**：
```kotlin
class TermuxResultService : IntentService("TermuxResultService") {
    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
        private val callbacks = mutableMapOf<Int, (TermuxResult) -> Unit>()
        private var executionId = 1000

        @Synchronized
        fun getNextExecutionId(): Int = executionId++

        fun registerCallback(id: Int, callback: (TermuxResult) -> Unit) {
            callbacks[id] = callback
        }

        fun unregisterCallback(id: Int) {
            callbacks.remove(id)
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        val bundle = intent?.getBundleExtra("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE")
            ?: return
        val execId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0)
        val stdout = bundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT", "")
        val stderr = bundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR", "")
        val exitCode = bundle.getInt("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1)

        callbacks[execId]?.invoke(TermuxResult(stdout, stderr, exitCode))
        callbacks.remove(execId)
    }
}

data class TermuxResult(val stdout: String, val stderr: String, val exitCode: Int)
```

**Termux 检测**：
```kotlin
object TermuxChecker {
    fun isTermuxAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) { false }
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED
    }
}
```

**权限声明**（`AndroidManifest.xml`）：
```xml
<uses-permission android:name="com.termux.permission.RUN_COMMAND" />
```

**验收标准**：
- [ ] Termux 未安装时 `isTermuxAvailable()` 返回 false
- [ ] Termux 已安装但未授权时 `hasRunCommandPermission()` 返回 false
- [ ] 授权后 `cmd: "echo hello"` → 异步回调 stdout = `hello`
- [ ] R4：使用 `EXTRA_ARGUMENTS` 传递 `-c` 脚本（而非 `EXTRA_STDIN`）
- [ ] 环境变量通过脚本内 `export` 注入
- [ ] R4：环境变量含单引号时正确转义
- [ ] 超时后回调 `[timeout]`，不永久阻塞
- [ ] 多个 shell 变量串行执行（队列管理）
- [ ] Termux 被系统杀后台后超时返回，不崩溃

---

#### 4.2.5 变量渲染管线（R4 重写：统一异步管线）

**当前问题**（`ExpanderAccessibilityService.kt:328-333`）：
- `parseItem` 在主线程同步执行（:550），任何阻塞操作（shell/http）直接 ANR
- 顺序遍历 `match.vars`，无依赖解析，乱序变量无法正确处理
- `inject_vars` 仅注入环境变量，不替换 params 内部的 `{{var}}` 引用

**R4 修正：统一异步渲染管线**：

所有变量（含 echo/date 等原同步变量）统一通过暂停-恢复模式处理：

```kotlin
// R4 修正：统一异步渲染入口，替代原 parseItem 顺序遍历
private var isExpansionInProgress = false
private var pendingRenderContext: RenderContext? = null

data class RenderContext(
    val match: Match,
    val triggerText: String,
    val event: AccessibilityEvent,  // AccessibilityEvent.obtain() 副本
    val expansionStr: String,
    val triggerIndex: Int,
    val storeOriginal: Boolean,
    val original: String,
    val workingReplace: String,      // 逐步替换后的结果
    val evalOrder: List<Var>,        // 依赖解析后的求值顺序
    val currentIndex: Int,           // 当前执行的变量索引
    val scope: MutableMap<String, ExtensionOutput>  // 已计算变量的 scope
)

// 主流程入口：替代原 :328-333 的 globals + vars 遍历
private fun startRender(
    match: Match,
    triggerText: String,
    event: AccessibilityEvent,
    expansionStr: String,
    triggerIndex: Int,
    storeOriginal: Boolean,
    original: String
) {
    var workingReplace = match.replace ?: ""
    // propagateCase 处理...

    // R4 修正：依赖解析，替代顺序遍历
    val localVars = match.vars ?: emptyList()
    val globalVarsList = globals ?: emptyList()
    val evalOrder = resolveEvaluationOrder(workingReplace, localVars, globalVarsList)
        .getOrElse {
            // 依赖解析失败（循环引用等），回退到原顺序
            localVars
        }

    // 检查是否有需要异步处理的变量（shell/script/http/javascript/match/choice/form）
    val hasAsyncVar = evalOrder.any { v ->
        v.type in listOf("shell", "script", "http", "javascript", "match", "choice", "form", "intent")
    }

    if (!hasAsyncVar) {
        // 纯同步变量（echo/date/clipboard/random），直接快速处理
        evalOrder.forEach { v -> workingReplace = parseItemSync(v, workingReplace) }
        val end = expansionStr.substring(triggerIndex).replace(triggerText, workingReplace)
        val newStr = expansionStr.substring(0, triggerIndex) + end
        doExpansion(event, newStr)
        return
    }

    // 有异步变量，进入暂停-恢复模式
    isExpansionInProgress = true
    pendingRenderContext = RenderContext(
        match, triggerText, AccessibilityEvent.obtain(event),
        expansionStr, triggerIndex, storeOriginal, original,
        workingReplace, evalOrder, 0, mutableMapOf()
    )
    executeNextVariable()
}

private fun executeNextVariable() {
    val ctx = pendingRenderContext ?: return
    if (ctx.currentIndex >= ctx.evalOrder.size) {
        // 所有变量完成，执行最终替换
        isExpansionInProgress = false
        val end = ctx.expansionStr.substring(ctx.triggerIndex).replace(ctx.triggerText, ctx.workingReplace)
        val newStr = ctx.expansionStr.substring(0, ctx.triggerIndex) + end
        doExpansion(ctx.event, newStr)
        if (ctx.storeOriginal) {
            previousOriginal = ctx.original
            previousExpansion = newStr
        }
        pendingRenderContext = null
        return
    }

    val variable = ctx.evalOrder[ctx.currentIndex]

    // R4 修正：inject_vars — 先替换 params 中的 {{var}} 引用
    val effectiveParams = if (variable.injectVars) {
        injectVariablesIntoParams(variable.params, ctx.scope)
    } else {
        variable.params
    }

    // choice 和 form 走现有特殊路径
    when (variable.type) {
        "choice" -> {
            // 由 showChoiceForMatch 处理，选择后回调 executeNextVariable
            showChoiceForMatch(variable, ctx) { choiceResult ->
                ctx.scope[variable.name ?: ""] = ExtensionOutput.Single(choiceResult)
                ctx.workingReplace = ctx.workingReplace.replace(wrapName(variable.name ?: ""), choiceResult)
                pendingRenderContext = ctx.copy(currentIndex = ctx.currentIndex + 1)
                executeNextVariable()
            }
            return
        }
        "form" -> {
            // 由 showForm 处理，表单提交后回调 executeNextVariable
            showFormForMatch(variable, ctx) { formResult ->
                ctx.scope[variable.name ?: ""] = formResult  // ExtensionOutput.Multiple
                // 替换 {{form.field}} 语法
                ctx.workingReplace = replaceFormVariables(ctx.workingReplace, variable.name ?: "", formResult)
                pendingRenderContext = ctx.copy(currentIndex = ctx.currentIndex + 1)
                executeNextVariable()
            }
            return
        }
    }

    // 其他变量类型通过 ExtensionRegistry 统一异步执行
    val extension = extensionRegistry.get(variable.type ?: "")
    if (extension == null) {
        // 未知类型，跳过
        pendingRenderContext = ctx.copy(currentIndex = ctx.currentIndex + 1)
        executeNextVariable()
        return
    }

    // R4 修正：统一异步调用，在子线程执行
    extension.calculate(effectiveParams, ctx.scope.toMap()) { result ->
        // 回调在子线程触发，需 post 到主线程
        Handler(Looper.getMainLooper()).post {
            when (result) {
                is ExtensionResult.Success -> {
                    val output = result.output
                    ctx.scope[variable.name ?: ""] = output
                    when (output) {
                        is ExtensionOutput.Single -> {
                            ctx.workingReplace = ctx.workingReplace.replace(
                                wrapName(variable.name ?: ""), output.value
                            )
                        }
                        is ExtensionOutput.Multiple -> {
                            // 替换 {{var.field}} 语法
                            output.values.forEach { (field, value) ->
                                ctx.workingReplace = ctx.workingReplace.replace(
                                    "{{${variable.name}.${field}}}", value
                                )
                            }
                        }
                    }
                }
                is ExtensionResult.Aborted -> {
                    // 用户取消，终止整个渲染
                    isExpansionInProgress = false
                    pendingRenderContext = null
                    return@post
                }
                is ExtensionResult.Error -> {
                    // 错误，替换为错误提示
                    ctx.workingReplace = ctx.workingReplace.replace(
                        wrapName(variable.name ?: ""), "[${result.message}]"
                    )
                }
            }
            pendingRenderContext = ctx.copy(currentIndex = ctx.currentIndex + 1)
            executeNextVariable()
        }
    }
}

// onAccessibilityEvent 中添加
if (isExpansionInProgress) return  // 丢弃期间的输入事件
```

**验收标准**：
- [ ] 单个 shell 变量：`cmd: "echo hello"` → 替换为 `hello`
- [ ] 多个 shell 变量：串行执行，全部替换
- [ ] shell + 普通变量混合：依赖解析后正确排序执行
- [ ] R4：乱序变量（b 依赖 a 但 b 在前）自动重排
- [ ] R4：`inject_vars: true` 时 params 中的 `{{var}}` 被替换为 scope 值
- [ ] R4：`inject_vars: true` 时 scope 变量注入环境变量 `ESPANSO_{VAR_NAME}`
- [ ] R4：ExtensionResult.Aborted 终止整个渲染流程
- [ ] R4：ExtensionResult.Error 替换为 `[error message]`
- [ ] R4：ExtensionOutput.Multiple 支持 `{{form.field}}` 语法
- [ ] `isExpansionInProgress` 期间丢弃新输入事件
- [ ] 所有变量在子线程执行，不阻塞主线程
- [ ] 后端不可用时替换为 `[shell unavailable]`，不中断流程
- [ ] `trim: false` 时保留输出首尾空白

---

#### 4.2.6 Espanso 参数兼容对照

| Espanso 参数 | 源码位置 | Expandroid 实现 |
|---|---|---|
| `cmd` | `shell.rs:217` | ✅ `params.string("cmd")` |
| `shell` | `shell.rs:218` | ✅ R4 修正：Termux 后端尊重 `shell` 参数（bash/sh 差异显著），Root/Shizuku 后端默认 sh 但可配置 |
| `trim` | `shell.rs:274-284` | ✅ `params.data["trim"] as? Boolean ?: true` |
| `debug` | `shell.rs:247-261` | ✅ Log.d 输出（可选） |
| `args` | `script.rs:57` | ✅ `params.stringList("args")` |
| `ignore_error` | `script.rs:123-139` | ✅ 非零退出码时用 stderr 或空值替代 |
| `%HOME%` | `script.rs:68-69` | ✅ R4 修正：后端相关映射 — Root → `/data` 或 root home；Termux → `/data/data/com.termux/files/home`；Shizuku → ADB shell home |
| `%CONFIG%` | `script.rs:71-72` | ✅ 映射到 App 配置目录 |
| `%PACKAGES%` | `script.rs:74-75` | ✅ 映射到 App 包目录 |
| `inject_vars` | `renderer/mod.rs:116-138` | ✅ R4 修正：完整实现 — 1) 替换 params 中的 `{{var}}` 引用 2) 注入环境变量 `ESPANSO_{VAR_NAME}` |
| `depends_on` | `renderer/resolve.rs:38-69` | ✅ R4 修正：移植拓扑排序算法，支持显式依赖 + 隐式依赖 + 循环检测 |

---

### 4.3 Layer 2：插件扩展系统

#### 4.3.1 `intent` 变量

**YAML 配置示例**：
```yaml
- trigger: ":contact"
  replace: "{{name}}"
  vars:
    - name: name
      type: intent
      params:
        action: "com.example.app.GET_NAME"
        extras:
          key: "query"
          value: "default"
        result_key: "name"  # 从返回 Intent 的 extras 中取此 key
        timeout: 5000
```

**机制**：  
发送广播 Intent → 第三方 App 的 BroadcastReceiver 响应 → 通过 PendingIntent 返回结果。

**实现**：
```kotlin
class IntentExtension(private val context: Context) : VariableExtension {
    override val typeName = "intent"

    // R4 修正：统一异步接口
    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val action = params.string("action") ?: run {
            callback(ExtensionResult.Error("missing 'action' param"))
            return
        }
        val resultKey = params.string("result_key") ?: run {
            callback(ExtensionResult.Error("missing 'result_key' param"))
            return
        }
        val timeout = params.long("timeout").takeIf { it > 0 } ?: 5000

        // 发送广播并等待结果
        val pendingResult = PendingIntent.getBroadcast(
            context, System.currentTimeMillis().toInt(),
            Intent(action),
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )

        // 注册临时 BroadcastReceiver 接收结果
        // ...（类似 Termux 的 PendingIntent 模式）

        callback(ExtensionResult.Success(ExtensionOutput.Single("")))  // placeholder
    }
}
```

**验收标准**：
- [ ] 发送指定 action 的广播 Intent
- [ ] 第三方 App 返回结果后正确提取 `result_key` 的值
- [ ] 超时后返回 `[intent timeout]`
- [ ] 无 App 响应时返回 `[intent no response]`

---

#### 4.3.2 `content` 变量

**YAML 配置示例**：
```yaml
- trigger: ":battery"
  replace: "{{level}}"
  vars:
    - name: level
      type: content
      params:
        uri: "content://com.android.battery.level"
        column: "level"
        selection: "where id = 1"
```

**机制**：  
通过 `ContentResolver.query()` 查询其他 App 的 ContentProvider，同步返回结果。

**实现**：
```kotlin
class ContentExtension(private val context: Context) : VariableExtension {
    override val typeName = "content"

    // R4 修正：统一异步接口，ContentResolver 查询在子线程执行
    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val uri = params.string("uri") ?: run {
            callback(ExtensionResult.Error("missing 'uri' param"))
            return
        }
        val column = params.string("column") ?: run {
            callback(ExtensionResult.Error("missing 'column' param"))
            return
        }

        // R4 修正：在子线程执行 ContentResolver 查询
        Thread {
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse(uri), arrayOf(column), null, null, null
                )
                val result = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (result != null) {
                    callback(ExtensionResult.Success(ExtensionOutput.Single(result)))
                } else {
                    callback(ExtensionResult.Error("no result from content provider"))
                }
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "content error"))
            }
        }.start()
    }
}
```

**验收标准**：
- [ ] 查询指定 URI 的 ContentProvider 返回正确值
- [ ] URI 无效或无权限时返回 `[content error: ...]`
- [ ] R4：在子线程执行，不阻塞主线程

---

## 5. 异步架构设计

### 5.1 问题分析

`onAccessibilityEvent` 在主线程执行（AOSP 源码：`AccessibilityService.java` 的 `IEventListenerWrapper.executeMessage` 中 `DO_ON_ACCESSIBILITY_EVENT` case）。`AccessibilityEvent` 对象在回调结束后被系统回收复用。

**不能阻塞主线程** — 否则触发 ANR（Android 5 秒超时）。

### 5.2 统一暂停-恢复模式（R4 修正）

**原设计**：仅 shell 变量（Termux 后端）使用暂停-恢复模式，其他变量同步执行。  
**R4 修正**：所有变量（含 Root/Shizuku 同步后端、http、javascript）统一使用暂停-恢复模式，在子线程执行后回调主线程。

当前 `choice` 变量已使用此模式（`ExpanderAccessibilityService.kt:589-680`），R4 将其泛化为所有变量类型的统一管线（见 §4.2.5 `executeNextVariable`）：

```
触发 :multi_var
  → 依赖解析 resolveEvaluationOrder() → 确定求值顺序
  → isExpansionInProgress = true
  → executeNextVariable()
    → var1 (shell) → 子线程执行 → 回调主线程 → 替换 {{var1}} → scope[var1] = result
    → var2 (http)  → 子线程执行 → 回调主线程 → 替换 {{var2}} → scope[var2] = result
    → var3 (echo)  → inject_vars → scope 替换 params → 计算 → 替换 {{var3}}
  → isExpansionInProgress = false
  → doExpansion(finalResult)
```

### 5.3 全局扩展锁

```kotlin
@Volatile private var isExpansionInProgress = false

override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (isExpansionInProgress) {
        // 丢弃期间的输入事件，防止上下文混乱
        return
    }
    // ... 正常处理 ...
}
```

**风险**：如果 shell 命令执行超过 2 秒，用户会感觉输入无响应。

**缓解措施**：
- 默认超时 5 秒
- 超时后用 `params["fallback"]` 或 `[timeout]` 继续
- 可选：通过 `WindowManager` 显示"正在执行..."悬浮提示

### 5.4 AccessibilityEvent 复制

由于 `AccessibilityEvent` 会被系统回收，异步恢复时需要使用 `AccessibilityEvent.obtain(event)` 创建副本（源：[SO 回答](https://stackoverflow.com/questions/50464753)）。

---

## 6. Scope 变量注入

### 6.1 Espanso 机制

Espanso 的 `inject_vars` 有**两层含义**（`renderer/mod.rs:116-138` + `util.rs:112-146`）：

1. **params 内部替换**：`inject_variables_into_params()` 遍历 params 中所有 Value（String/Array/Object），将 `{{var}}` 替换为 scope 中的值（`util.rs:122-146`）
2. **环境变量注入**：对于 shell/script 变量，`convert_to_env_variables(scope)` 将 scope 转为 `ESPANSO_{VAR_NAME}` 环境变量（`extension/util.rs:23-42`）

对于 shell/script 变量，scope 变量通过环境变量注入（`shell.rs:230` / `script.rs:89`）：
```rust
let mut env_variables = super::util::convert_to_env_variables(scope);
```

### 6.2 Expandroid 实现（R4 修正）

```kotlin
// R4 修正：inject_variables_into_params 等价实现
// 对齐 util.rs:112-146 inject_variables_into_params
fun injectVariablesIntoParams(
    params: Params,
    scope: Map<String, ExtensionOutput>
): Params {
    val result = mutableMapOf<String, Any>()
    params.forEach { (key, value) ->
        result[key] = injectVariablesIntoValue(value, scope)
    }
    return Params(result)
}

fun injectVariablesIntoValue(value: Any, scope: Map<String, ExtensionOutput>): Any {
    return when (value) {
        is String -> {
            // 替换 {{var}} 引用
            var result = value
            scope.forEach { (name, output) ->
                val v = when (output) {
                    is ExtensionOutput.Single -> output.value
                    is ExtensionOutput.Multiple -> output.values.toString()
                }
                result = result.replace("{{\$name}}", v)
            }
            result
        }
        is List<*> -> value.map { injectVariablesIntoValue(it ?: "", scope) }
        is Map<*, *> -> value.mapValues { injectVariablesIntoValue(it.value ?: "", scope) }
        else -> value
    }
}

// 环境变量转换（对齐 extension/util.rs:23-42 convert_to_env_variables）
fun convertToEnvVariables(scope: Map<String, ExtensionOutput>): Map<String, String> {
    val env = mutableMapOf<String, String>()
    scope.forEach { (key, output) ->
        when (output) {
            is ExtensionOutput.Single -> {
                env["ESPANSO_${key.uppercase()}"] = output.value
            }
            is ExtensionOutput.Multiple -> {
                output.values.forEach { (subKey, subValue) ->
                    env["ESPANSO_${key.uppercase()}_${subKey.uppercase()}"] = subValue
                }
            }
        }
    }
    return env
}
```

**环境变量命名**：Espanso 使用 `ESPANSO_{VAR_NAME}` 格式（`extension/util.rs:29`），Multiple 输出使用 `ESPANSO_{VAR_NAME}_{FIELD}` 格式（`extension/util.rs:34`），Expandroid 保持一致。

---

## 7. 用户引导与首次配置

### 7.1 后端不可用时的引导

当检测到配置中包含 `shell`/`script` 变量但无可用后端时，弹出引导对话框：

```
┌─────────────────────────────────────┐
│  Shell 变量需要执行后端              │
│                                     │
│  检测到您的配置包含 shell/script     │
│  变量，需要选择一个执行后端：        │
│                                     │
│  1. Root（推荐，如果设备已 root）    │
│     → 无需额外操作                  │
│                                     │
│  2. Shizuku（推荐，免 root）         │
│     → 安装 Shizuku → 无线调试激活   │
│     → [安装 Shizuku]                │
│                                     │
│  3. Termux（备选，免 root）          │
│     → 安装 Termux → 授权 RUN_COMMAND │
│     → [安装 Termux]                 │
│                                     │
│  [稍后再说]  [查看教程]              │
└─────────────────────────────────────┘
```

### 7.2 后端状态指示

在 App 设置页面显示当前后端状态：

```
Shell 执行后端
  ├─ Root:      ● 不可用（设备未 root）
  ├─ Shizuku:   ● 已连接（shell 权限）
  └─ Termux:    ● 已安装，未授权
```

### 7.3 Termux 一键安装脚本

提供"复制到 Termux 执行"的安装脚本：

```bash
# Expandroid 环境安装脚本
pkg update -y
pkg install python nodejs curl jq -y
pip install requests
echo "Expandroid 环境安装完成！"
```

---

## 8. 实施计划

### 8.1 优先级与依赖

| 优先级 | 模块 | 依赖 | 预估改动 | 验收方式 |
|--------|------|------|---------|---------|
| **P0** | 依赖解析算法（resolve.rs 移植） | 无 | ~150 行 | 单元测试 + 手动验证 |
| **P0** | inject_variables_into_params 实现 | 依赖解析 | ~80 行 | 单元测试 |
| **P0** | ExtensionRegistry + VariableExtension 接口 | 无 | ~80 行 | 编译通过 + 集成测试 |
| **P0** | `javascript` 变量（QuickJS） | ExtensionRegistry | ~150 行 | 单元测试 + 手动验证 |
| **P0** | `http` 变量（OkHttp） | ExtensionRegistry | ~100 行 | 单元测试 + 手动验证 |
| **P0** | `match` 递归引用（完整渲染流程） | ExtensionRegistry + 依赖解析 | ~120 行 | 单元测试 + 手动验证 |
| **P0** | 统一异步渲染管线（executeNextVariable） | 依赖解析 + inject_vars + Registry | ~250 行 | 手动验证 |
| **P1** | Root 后端 | ExtensionRegistry | ~120 行 | 手动验证（root 设备） |
| **P1** | Shizuku 后端 | ExtensionRegistry + AIDL | ~300 行 | 手动验证（Shizuku 设备） |
| **P1** | Shell 变量扩展（shell/script） | Root/Shizuku + 异步管线 | ~150 行 | 手动验证 |
| **P2** | Termux 后端 | ExtensionRegistry | ~250 行 | 手动验证（Termux 设备） |
| **P2** | 后端探测与自动选择 | Root/Shizuku/Termux | ~80 行 | 手动验证 |
| **P2** | 用户引导对话框 | 后端探测 | ~150 行 | 手动验证 |
| **P3** | `intent` 插件变量 | ExtensionRegistry | ~200 行 | 手动验证 |
| **P3** | `content` 插件变量 | ExtensionRegistry | ~100 行 | 手动验证 |
| **P3** | 后端状态指示 UI | 后端探测 | ~100 行 | 手动验证 |

### 8.2 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `DependencyResolver.kt` | 新建 | R4：resolve.rs 移植 — 拓扑排序 + 循环检测 |
| `InjectVariables.kt` | 新建 | R4：inject_variables_into_params + convert_to_env_variables |
| `ExtensionRegistry.kt` | 新建 | 可扩展变量注册表 |
| `VariableExtension.kt` | 新建 | 变量扩展接口 + ExtensionOutput + ExtensionResult |
| `JavaScriptExtension.kt` | 新建 | QuickJS 变量实现 |
| `HttpExtension.kt` | 新建 | HTTP 请求变量实现 |
| `MatchExtension.kt` | 新建 | 递归引用变量实现（完整渲染流程） |
| `TemplateRenderer.kt` | 新建 | R4：完整渲染流程接口 |
| `ShellExecutor.kt` | 新建 | Shell 执行接口 + 数据类 |
| `RootShellExecutor.kt` | 新建 | Root 后端实现 |
| `ShizukuShellExecutor.kt` | 新建 | Shizuku 后端实现 |
| `IShellService.aidl` | 新建 | Shizuku AIDL 接口 |
| `ShellServiceImpl.kt` | 新建 | Shizuku UserService 实现 |
| `TermuxShellExecutor.kt` | 新建 | Termux 后端实现 |
| `TermuxResultService.kt` | 新建 | Termux 结果接收 Service |
| `ShellBackendDetector.kt` | 新建 | 后端探测逻辑 |
| `IntentExtension.kt` | 新建 | Intent 插件变量 |
| `ContentExtension.kt` | 新建 | ContentProvider 插件变量 |
| `ExpanderAccessibilityService.kt` | 修改 | R4：统一异步渲染管线 + 依赖解析 + inject_vars |
| `AndroidManifest.xml` | 修改 | 添加 Termux 权限声明 + Service 注册 |
| `build.gradle` | 修改 | 添加 QuickJS / Shizuku 依赖 |
| `VariableEditorDialog.kt` | 修改 | 编辑器支持新变量类型 |
| `VariableTypePicker.kt` | 修改 | 类型选择器增加新类型 |

---

## 9. 能力覆盖矩阵

### 9.1 最终覆盖情况

| Espanso 桌面能力 | 实现方案 | 覆盖层 | 状态 |
|---|---|---|---|
| echo | 内置同步 | Layer 0 | ✅ 已实现 |
| date | 内置同步 | Layer 0 | ✅ 已实现 |
| clipboard | 内置同步 | Layer 0 | ✅ 已实现 |
| random | 内置同步 | Layer 0 | ✅ 已实现 |
| choice | 内置异步 | Layer 0 | ✅ 已实现 |
| form | 内置异步 | Layer 0 | ✅ 已实现 |
| **shell** | Root/Shizuku/Termux | Layer 1 | 📋 本方案 |
| **script** | Root/Shizuku/Termux | Layer 1 | 📋 本方案 |
| **match**（递归引用） | 内置同步 | Layer 0 | 📋 本方案 |
| global_vars | 内置同步 | Layer 0 | ✅ 已实现 |
| triggers（多触发词） | 内置 | — | ✅ 已实现 |
| left_word / right_word | 内置 | — | ✅ 已实现 |
| propagate_case / uppercase_style | 内置 | — | ✅ 已实现 |
| regex 触发 | 内置 | — | ✅ 已实现 |
| label / search_terms | 内置 | — | ✅ 已实现 |
| YAML 导入/导出 | 内置 | — | ✅ 已实现 |
| **javascript**（增强） | QuickJS 嵌入 | Layer 0 | 📋 本方案 |
| **http**（增强） | OkHttp | Layer 0 | 📋 本方案 |
| **intent**（增强） | Intent 广播 | Layer 2 | 📋 本方案 |
| **content**（增强） | ContentProvider | Layer 2 | 📋 本方案 |
| markdown / html | App 侧渲染 | — | ❌ 不在本方案范围 |
| image_path | 剪贴板方案 | — | ❌ 不在本方案范围 |
| force_clipboard / force_mode | App 侧实现 | — | ❌ 不在本方案范围 |
| filter_title / filter_exec | packageName | — | ❌ 不在本方案范围 |
| shell / script 变量 | Layer 1 | — | 📋 本方案 |

### 9.2 与 Espanso 桌面端兼容性

| 场景 | 兼容性 | 说明 |
|------|--------|------|
| 桌面端 YAML 导入到 Android | ✅ | shell/script 变量自动检测后端，其他变量直接可用 |
| Android YAML 导出到桌面端 | ✅ | javascript/http/intent/content 类型被 Espanso 跳过（未知类型） |
| 双向同步配置 | ✅ | 增强类型不影响 Espanso 桌面端解析 |
| Python 脚本 | ✅ | 通过 Termux `pkg install python` 或 Shizuku 执行 |
| Node.js 脚本 | ✅ | 通过 Termux `pkg install nodejs` 或 Shizuku 执行 |
| 环境变量注入 | ✅ | `ESPANSO_{VAR_NAME}` 格式与 Espanso 一致（`extension/util.rs:29`） |
| inject_vars | ✅ | R4 修正：完整实现 — params 内部 `{{var}}` 替换 + 环境变量注入 |
| depends_on | ✅ | R4 修正：移植拓扑排序算法，支持显式/隐式依赖 + 循环检测 |
| ExtensionOutput::Multiple | ✅ | R4 修正：支持 `{{form.field}}` 语法（对齐 `lib.rs:130`） |
| ExtensionResult::Aborted | ✅ | R4 修正：用户取消时终止渲染（对齐 `lib.rs:136`） |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Termux 后台被杀 | shell 命令不执行 | 超时检测 + 引导用户开启 `termux-wake-lock` |
| Shizuku 服务断开 | shell 命令不执行 | 检测 Binder 存活 + 提示重新激活 |
| 异步执行期间用户输入 | 上下文混乱 | `isExpansionInProgress` 全局锁 |
| AccessibilityEvent 回收 | 异步恢复时崩溃 | `AccessibilityEvent.obtain()` 复制 |
| shell 命令执行超时 | 用户感觉卡顿 | 默认 5 秒超时 + fallback 值 |
| QuickJS JNI 崩溃 | App 崩溃 | try-catch 包裹 + 子线程执行 |
| pip 包安装失败 | Python 脚本不可用 | 提供 `pkg install` 替代方案文档 |
| 国产 ROM 限制 | 后台 Service 被杀 | 前台 Service + 电池优化白名单引导 |
| Android 17 限制 AccessibilityService | 核心功能受影响 | 持续关注 AOSP 变化，准备 IME 方案备选 |

---

## 11. 验收检查清单

### 11.1 P0 验收

- [ ] R4：依赖解析 — 乱序变量自动重排（b 依赖 a 但 b 在前 → a → b）
- [ ] R4：依赖解析 — 循环引用检测报错
- [ ] R4：inject_variables_into_params — params 中的 `{{var}}` 被替换为 scope 值
- [ ] R4：convertToEnvVariables — `ESPANSO_{VAR_NAME}` 环境变量正确生成
- [ ] `type: javascript` + `code: "1+1"` → 替换为 `2`
- [ ] `type: javascript` + scope 注入正确
- [ ] `type: http` + `url` 有效 → 返回响应体
- [ ] `type: http` + `json_path` → 正确提取 JSON 字段
- [ ] `type: http` + 超时 → 返回 `[http error: timeout]`
- [ ] `type: match` + `trigger: ":xxx"` → 递归渲染
- [ ] `type: match` + 循环引用检测
- [ ] R4：`type: match` 引用的 match 包含 shell 变量时也被执行
- [ ] R4：`type: match` 引用的 match 变量乱序时依赖解析正确重排
- [ ] R4：ExtensionResult.Aborted 终止渲染流程
- [ ] R4：ExtensionResult.Error 替换为 `[error message]`
- [ ] R4：ExtensionOutput.Multiple 支持 `{{form.field}}` 语法
- [ ] ExtensionRegistry 编译通过，现有变量类型正常注册
- [ ] R4：所有变量在子线程执行，不阻塞主线程
- [ ] CI 构建通过

### 11.2 P1 验收

- [ ] Root 设备：`type: shell` + `cmd: "echo hello"` → `hello`
- [ ] Root 设备：`type: script` + `args: ["python", "-c", "print(1)"]` → `1`
- [ ] Root 设备：环境变量注入 `ESPANSO_NAME` 正确
- [ ] R4：Root 设备：环境变量含单引号时不发生命令注入
- [ ] Shizuku 设备：`bindUserService` 成功
- [ ] Shizuku 设备：`type: shell` 执行
- [ ] Shizuku 设备：服务断开后检测并提示
- [ ] 非 root 无 Shizuku 设备：返回 `[shell unavailable]`
- [ ] R4：所有 shell 执行在子线程，不阻塞主线程
- [ ] CI 构建通过

### 11.3 P2 验收

- [ ] Termux 设备：`type: shell` 异步执行 → 正确替换
- [ ] Termux 设备：多个 shell 变量串行执行
- [ ] R4：Termux 设备：使用 `EXTRA_ARGUMENTS` 传递 `-c` 脚本
- [ ] R4：Termux 设备：环境变量含单引号时正确转义
- [ ] R4：Termux 设备：`shell` 参数被尊重（bash vs sh）
- [ ] Termux 设备：超时返回 `[timeout]`
- [ ] Termux 未安装：引导对话框显示
- [ ] Termux 已安装未授权：引导授权
- [ ] 后端自动探测：Root > Shizuku > Termux 优先级
- [ ] `isExpansionInProgress` 期间丢弃输入事件
- [ ] CI 构建通过

### 11.4 P3 验收

- [ ] `type: intent` + 广播 → 第三方 App 返回结果
- [ ] `type: content` + ContentProvider 查询 → 返回值
- [ ] 后端状态指示 UI 正确显示
- [ ] CI 构建通过

---

## 12. 溯源引用

### 12.1 Espanso 源码引用

| 引用内容 | 文件路径 | 行号 |
|---------|---------|------|
| `Extension` trait 定义 | `espanso-render/src/lib.rs` | :120 |
| `ExtensionOutput` Single/Multiple | `espanso-render/src/lib.rs` | :128-131 |
| `ExtensionResult` Success/Aborted/Error | `espanso-render/src/lib.rs` | :134-138 |
| `DefaultRenderer` 扩展注册 | `espanso-render/src/renderer/mod.rs` | :40-52 |
| `match` 变量递归调用 | `espanso-render/src/renderer/mod.rs` | :99-114 |
| `inject_vars` 逻辑 | `espanso-render/src/renderer/mod.rs` | :116-138 |
| `inject_variables_into_params` | `espanso-render/src/renderer/util.rs` | :112-146 |
| `get_body_variable_names` | `espanso-render/src/renderer/util.rs` | :28-35 |
| `get_params_variable_names` | `espanso-render/src/renderer/util.rs` | :37-67 |
| `resolve_evaluation_order` | `espanso-render/src/renderer/resolve.rs` | :38-137 |
| `convert_to_env_variables` | `espanso-render/src/extension/util.rs` | :23-42 |
| `ShellExtension` | `espanso-render/src/extension/shell.rs` | :193-296 |
| `ScriptExtension` | `espanso-render/src/extension/script.rs` | :29-164 |
| `DateExtension` | `espanso-render/src/extension/date.rs` | :31-121 |
| `ChoiceExtension` | `espanso-render/src/extension/choice.rs` | :42-120 |
| `FormExtension` | `espanso-render/src/extension/form.rs` | :38-78 |
| `EchoExtension` | `espanso-render/src/extension/echo.rs` | :23-58 |
| `RandomExtension` | `espanso-render/src/extension/random.rs` | :24-60 |
| `ClipboardExtension` | `espanso-render/src/extension/clipboard.rs` | :27-55 |
| 扩展注册入口 | `espanso/src/cli/worker/engine/mod.rs` | :196-225 |
| shell `cmd` 参数 | `espanso-render/src/extension/shell.rs` | :217 |
| shell `shell` 参数 | `espanso-render/src/extension/shell.rs` | :218 |
| shell `trim` 参数 | `espanso-render/src/extension/shell.rs` | :274-284 |
| shell `debug` 参数 | `espanso-render/src/extension/shell.rs` | :247-261 |
| shell 环境变量注入 | `espanso-render/src/extension/shell.rs` | :230 |
| script `args` 参数 | `espanso-render/src/extension/script.rs` | :57 |
| script `%HOME%` 路径映射 | `espanso-render/src/extension/script.rs` | :68-69 |
| script `%CONFIG%` 路径映射 | `espanso-render/src/extension/script.rs` | :71-72 |
| script `%PACKAGES%` 路径映射 | `espanso-render/src/extension/script.rs` | :74-75 |
| script `ignore_error` 参数 | `espanso-render/src/extension/script.rs` | :123-139 |
| script 环境变量注入 | `espanso-render/src/extension/script.rs` | :89 |
| choice `values` 字符串/数组 | `espanso-render/src/extension/choice.rs` | :64-110 |

### 12.2 Expandroid 源码引用

| 引用内容 | 文件路径 | 行号 |
|---------|---------|------|
| `parseItem` 函数 | `ExpanderAccessibilityService.kt` | :550-584 |
| `showChoiceForMatch` 异步模式 | `ExpanderAccessibilityService.kt` | :589-680 |
| `showForm` 异步模式 | `ExpanderAccessibilityService.kt` | :408-494 |
| `doExpansion` 执行替换 | `ExpanderAccessibilityService.kt` | :505-521 |
| `handleTextExpansion` 主流程 | `ExpanderAccessibilityService.kt` | :270-400 |
| `globals` 全局变量处理 | `ExpanderAccessibilityService.kt` | :328-330, :606-608 |
| `dict` / `regexDict` 存储 | `ExpanderAccessibilityService.kt` | :61-62 |
| `formExpansion` 暂停-恢复 | `ExpanderAccessibilityService.kt` | :73, :278-283 |
| `Params` 通用化模型 | `Models.kt` | :6-24 |
| `Var` 模型（含 injectVars/dependsOn） | `Models.kt` | :26-34 |
| `Match` 模型（含 label/searchTerms） | `Models.kt` | :42-73 |

### 12.3 外部参考

| 参考 | URL |
|------|-----|
| Termux RUN_COMMAND Intent Wiki | https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent |
| Termux RunCommandService 源码 | https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/RunCommandService.java |
| Shizuku 官网 | https://shizuku.rikka.app/ |
| Shizuku-API README | https://github.com/RikkaApps/Shizuku-API/blob/master/README.md |
| Shizuku-API 仓库 | https://github.com/RikkaApps/Shizuku-API |
| quickjs-java（QuickJS Android 封装） | https://github.com/cashapp/quickjs-java/ |
| QuickJS Android (Maven Central) | https://central.sonatype.com/artifact/app.cash.quickjs/quickjs-android |
| QuickJS Wrapper (活跃维护) | https://central.sonatype.com/artifact/wang.harlon.quickjs/wrapper-android |
| QuickJS 官网 | https://bellard.org/quickjs/ |
| AccessibilityEvent 回收问题 | https://stackoverflow.com/questions/50464753 |
| WebView evaluateJavascript 死锁 | https://stackoverflow.com/questions/50998907/android-webview-blocking-evaluatejavascript |
| Android 17 Accessibility 限制 | https://www.zerosday.com/post/news/android-17-blocks-non-accessibility-apps-from-accessibility-api-to-prevent-malware-abuse |
| AOSP AccessibilityService 源码 | https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java |

---

## 附录 A：完整 YAML 配置示例

```yaml
# === 内置变量 ===
- trigger: ":hello"
  replace: "Hello, World!"

- trigger: ":date"
  replace: "{{date}}"
  vars:
    - name: date
      type: date
      params:
        format: "%Y-%m-%d"

# === javascript 变量（增强） ===
- trigger: ":calc"
  replace: "{{result}}"
  vars:
    - name: result
      type: javascript
      params:
        code: "Math.pow(2, 10)"

# === http 变量（增强） ===
- trigger: ":ip"
  replace: "My IP: {{ip}}"
  vars:
    - name: ip
      type: http
      params:
        url: "https://api.ipify.org"
        timeout: 3000

# === shell 变量（需后端） ===
- trigger: ":whoami"
  replace: "{{user}}"
  vars:
    - name: user
      type: shell
      params:
        cmd: "whoami"

# === script 变量（需后端） ===
- trigger: ":pytime"
  replace: "{{time}}"
  vars:
    - name: time
      type: script
      params:
        args:
          - python
          - -c
          - "from datetime import datetime; print(datetime.now().strftime('%H:%M:%S'))"

# === match 递归引用 ===
- trigger: ":sig"
  replace: "Best regards,\nJohn Doe"
  label: "signature"

- trigger: ":email"
  replace: "{{sig}}\nSent from my Android device"
  vars:
    - name: sig
      type: match
      params:
        trigger: ":sig"

# === shell + inject_vars ===
- trigger: ":greet"
  replace: "{{greeting}}"
  vars:
    - name: name
      type: echo
      params:
        echo: "World"
    - name: greeting
      type: shell
      params:
        cmd: "echo 'Hello, $ESPANSO_NAME!'"
      inject_vars: true

# === intent 插件（增强） ===
- trigger: ":contact"
  replace: "{{name}}"
  vars:
    - name: name
      type: intent
      params:
        action: "com.example.app.GET_CONTACT"
        result_key: "contact_name"
        timeout: 5000

# === content 插件（增强） ===
- trigger: ":battery"
  replace: "Battery: {{level}}%"
  vars:
    - name: level
      type: content
      params:
        uri: "content://com.android.battery.level"
        column: "level"
```

---

## 附录 B：方案 PK 记录

### B.1 评审时间线

| 轮次 | 日期 | 内容 | 结论 |
|------|------|------|------|
| 第 1 轮 | 2025-06-22 | Termux 方案可行性分析 | 能力覆盖最广，但异步问题是架构级缺陷 |
| 第 2 轮 | 2025-06-22 | 6 方案 PK（Termux/Shizuku/Root/WebView/QuickJS/HTTP） | WebView 淘汰，QuickJS+HTTP 作为 P0 内置 |
| 第 3 轮 | 2025-06-22 | 混合分层方案设计 | Root > Shizuku > Termux 优先级，分 4 层 |
| 第 4 轮 | 2025-06-22 | R4 评审修正（线程模型/依赖解析/inject_vars/match 重写） | 统一异步管线 + 拓扑排序 + 完整 inject_vars + match 调用完整渲染流程 |

### B.2 淘汰方案记录

| 方案 | 淘汰原因 |
|------|---------|
| WebView JS | 主线程死锁 + 内存占用过重 + 无 shell 能力 |
| 纯 Termux 方案 | 异步回调与 AccessibilityService 架构矛盾 + 国产 ROM 杀后台 |
| 纯 Shizuku 方案 | 激活门槛高 + 无 Python 等语言运行时（需额外安装） |
| 纯 Root 方案 | 用户基数 < 5% |
| 纯 HTTP 方案 | 无法执行本地 shell 命令 |

### B.3 最终方案选择理由

1. **分层设计**：不同用户场景匹配不同方案，最大化覆盖
2. **P0 零依赖**：QuickJS + HTTP 覆盖常见场景，无需任何外部 App
3. **R4 统一异步**：所有变量统一走子线程 + 暂停-恢复模式，避免 ANR
4. **R4 依赖解析**：移植 Espanso 拓扑排序算法，正确处理乱序变量和循环引用
5. **R4 完整 inject_vars**：params 内部 `{{var}}` 替换 + 环境变量注入
6. **R4 match 完整渲染**：调用完整渲染流程而非简化 parseItem 遍历
7. **YAML 兼容**：用户配置与 Espanso 桌面端完全一致
8. **Termux 兜底**：原生异步但能力最广，作为最后备选
