# Dev Workspace 0.1 — Dev Tasks 任务运行器设计规格

- 日期：2026-07-21
- 状态：待用户最终审阅
- 目标平台：IntelliJ IDEA 2025.3 及以上
- 首要适配环境：IntelliJ IDEA 2026.2，Build 262
- 实现语言：Kotlin
- 发布范围：个人使用，暂不发布 JetBrains Marketplace

## 1. 背景与目标

Dev Workspace 是一个面向个人开发工作流的 IntelliJ IDEA 集合型插件。未来可逐步加入开发资料库、常用工具箱等功能，但 0.1 版本只实现 **Dev Tasks 任务运行器**。

任务运行器用于集中管理开发脚本和常用命令，并通过 IDEA 原生 Terminal 执行，支持：

- 脚本文件与内联命令；
- 项目共享、项目私有、全局三种作用域；
- TOML 配置与图形界面双向编辑；
- Shell 自动识别；
- Terminal 创建、命名与复用；
- 手动运行、停止与强制关闭；
- IDEA 启动后或项目打开后的自动启动；
- 启动顺序和延迟；
- 项目信任机制；
- 环境变量与 Password Safe；
- 退出状态检测；
- 快速任务选择；
- 九个固定快捷槽位。

## 2. 非目标

0.1 版本不实现：

- 任务依赖图；
- 并行分组；
- 端口、HTTP、日志等服务就绪检测；
- 自动重试或自动重启；
- 跨设备同步；
- 完整终端日志持久化；
- 为任意数量任务动态注册 Action；
- 开发资料库；
- 开发工具箱；
- 图片抠图等图像工具；
- Marketplace 发布适配；
- IntelliJ IDEA 2025.2 及更早版本兼容。

## 3. 总体架构

采用单一 IntelliJ 插件工程，不拆多个 Gradle Module；通过 Kotlin package 和接口隔离职责。

```text
Dev Workspace Plugin
├─ task-domain
│  ├─ 任务定义
│  ├─ 作用域与覆盖规则
│  ├─ 状态机
│  └─ 运行记录
├─ task-storage
│  ├─ TOML 解析与序列化
│  ├─ 三层仓库
│  ├─ 文件监听
│  ├─ 最后有效快照
│  └─ Password Safe
├─ task-runtime
│  ├─ 变量解析
│  ├─ Shell 识别
│  ├─ 命令准备与转义
│  ├─ Terminal 管理
│  ├─ 状态检测
│  └─ 停止流程
├─ task-startup
│  ├─ IDEA 会话触发
│  ├─ 项目打开触发
│  ├─ 启动排序与延迟
│  └─ 项目信任
├─ task-ui
│  ├─ Dev Tasks Tool Window
│  ├─ 任务编辑 Dialog
│  ├─ 快速任务选择弹窗
│  └─ 配置错误提示
└─ task-actions
   ├─ 固定 Action
   └─ Favorite Slot 1–9
```

核心原则：

1. 领域模型不直接依赖 Swing 或 Terminal UI。
2. 配置解析、任务准备、Shell 识别等纯逻辑必须可单元测试。
3. 所有运行入口最终统一进入 `TaskRunner`。
4. 耗时操作、Password Safe、文件 IO、Terminal 等待不得阻塞 EDT。
5. Terminal API 以 Build 262 实际提供的公开 API 为准，实施前必须核对 SDK。

## 4. 核心领域模型

### 4.1 任务定义

```kotlin
data class DevTask(
    val id: String,
    val name: String?,
    val terminalAlias: String?,
    val description: String?,
    val scope: TaskScope,
    val enabled: Boolean,
    val source: TaskSource,
    val workingDirectory: String?,
    val environment: Map<String, EnvironmentValue>,
    val terminalPolicy: TerminalPolicy,
    val exitDetection: Boolean,
    val autoStart: AutoStartConfig?,
    val favoriteSlot: Int?
)
```

### 4.2 任务来源

```kotlin
sealed interface TaskSource {
    data class ScriptFile(
        val path: String,
        val interpreter: String? = null,
        val arguments: List<String> = emptyList()
    ) : TaskSource

    data class InlineCommand(
        val command: String,
        val shell: String? = null
    ) : TaskSource
}
```

### 4.3 作用域

```kotlin
enum class TaskScope {
    GLOBAL,
    PROJECT_SHARED,
    PROJECT_PRIVATE
}
```

覆盖优先级：

```text
项目私有 > 项目共享 > 全局
```

同一 `task.id` 出现在多个作用域时：

- 最高优先级定义整体生效；
- 不做字段级隐式合并；
- 被覆盖定义保留在覆盖链中，供 UI 查看；
- 删除高优先级定义后，下一层定义自动恢复；
- 将来如需要继承，另行增加显式 `extends`，0.1 不实现。

```kotlin
data class ResolvedTask(
    val effective: DevTask,
    val shadowed: List<DevTask>
)
```

### 4.4 自动启动

```kotlin
data class AutoStartConfig(
    val trigger: AutoStartTrigger,
    val order: Int = 100,
    val delaySeconds: Int = 0,
    val globalMode: GlobalAutoStartMode =
        GlobalAutoStartMode.ONCE_PER_IDE_SESSION
)

enum class AutoStartTrigger {
    IDE_START,
    PROJECT_OPEN
}

enum class GlobalAutoStartMode {
    ONCE_PER_IDE_SESSION,
    ONCE_PER_PROJECT
}
```

语义：

- `order` 决定提交顺序，数值越小越先提交；
- `delaySeconds` 是任务轮到提交时的额外等待；
- 不等待前一个长期任务退出；
- 不做自动重试；
- 失败只显示原因，由用户手动处理。

### 4.5 环境变量

```kotlin
sealed interface EnvironmentValue {
    data class Plain(val value: String) : EnvironmentValue
    data class SystemReference(val name: String) : EnvironmentValue
    data class SecretReference(val key: String) : EnvironmentValue
}
```

秘密值：

- 只保存引用键；
- 实值存入 IDEA Password Safe；
- 不写入 TOML；
- 不进入日志、通知、命令预览或历史记录；
- 只在单次执行准备阶段存在于内存。

### 4.6 Terminal 策略

```kotlin
enum class TerminalPolicy {
    REUSE_TASK_TERMINAL,
    ALWAYS_NEW,
    REUSE_SHARED
}
```

- `REUSE_TASK_TERMINAL`：同一项目、同一有效任务 ID 复用标签，默认策略。
- `ALWAYS_NEW`：每次创建新标签，名称追加序号。
- `REUSE_SHARED`：当前项目中的相关任务复用 `Dev Tasks` 标签。

### 4.7 任务状态

```text
IDLE
  ↓
PREPARING
  ↓
WAITING_FOR_TERMINAL
  ↓
RUNNING
  ├─ SUCCEEDED
  ├─ FAILED
  ├─ STOPPING → STOPPED
  └─ UNKNOWN
```

`UNKNOWN` 用于：

- 交互式任务关闭退出检测；
- 用户关闭 Terminal；
- Shell Integration 与标记检测均失效；
- 用户手动更改插件发出的命令；
- IDEA 会话恢复后无法确认上次进程状态。

## 5. 配置存储

### 5.1 文件格式

统一使用 TOML。

原因：

- 支持注释；
- 比 YAML 更少依赖缩进；
- 适合 Git diff；
- 能表达数组、嵌套表与复杂配置；
- 允许用户直接编辑。

### 5.2 存储位置

```text
项目共享：
${PROJECT_DIR}/.dev-workspace/tasks.toml

项目私有：
IDEA 项目系统目录/dev-workspace/tasks.toml

全局：
IDEA 用户配置目录/dev-workspace/tasks.toml

秘密值：
IDEA Password Safe
```

项目私有文件不放在仓库目录和 `.idea` 中，以避免误提交及项目格式差异。

IDEA `PersistentStateComponent` 只保存少量插件状态，例如：

- 项目信任记录；
- UI 偏好；
- 上次选中任务；
- 双击行为；
- 最近运行元数据。

### 5.3 TOML 示例

```toml
version = 1

[[tasks]]
id = "start-backend"
name = "启动后端"
terminalAlias = "Backend"
description = "启动本地 Spring Boot 服务"
enabled = true

sourceType = "script"
script = "${PROJECT_DIR}/scripts/start-backend.ps1"
arguments = ["--profile", "dev"]

workingDirectory = "${PROJECT_DIR}"
terminalPolicy = "reuse-task"
exitDetection = true
favoriteSlot = 1

[tasks.autoStart]
trigger = "project-open"
order = 20
delaySeconds = 3

[tasks.environment]
SPRING_PROFILES_ACTIVE = "dev"
JAVA_HOME = "${ENV:JAVA_HOME}"
DB_PASSWORD = "${SECRET:backend-db-password}"

[[tasks]]
id = "frontend-dev"
name = "启动前端"
enabled = true
sourceType = "inline"
command = "npm run dev"
workingDirectory = "${PROJECT_DIR}/frontend"
terminalPolicy = "reuse-task"
exitDetection = false
```

### 5.4 配置仓库接口

```kotlin
interface TaskRepository {
    val scope: TaskScope

    suspend fun load(): TaskConfigSnapshot

    suspend fun save(tasks: List<DevTask>)

    fun watch(listener: TaskConfigChangeListener)
}
```

实现：

- `GlobalTaskRepository`
- `ProjectSharedTaskRepository`
- `ProjectPrivateTaskRepository`

### 5.5 有效快照

```kotlin
data class TaskConfigSnapshot(
    val scope: TaskScope,
    val tasks: List<DevTask>,
    val sourceFile: Path,
    val contentHash: String,
    val loadedAt: Instant
)
```

每个作用域独立保存最后一次有效快照：

- 某一层解析失败，不影响其他层；
- 失败层继续使用最后有效配置；
- 首次加载且没有有效快照时，该层为空；
- 修复配置后自动恢复。

### 5.6 原子保存

界面保存流程：

```text
生成 TOML
→ 写入同目录临时文件
→ 再次解析临时文件
→ 校验成功
→ 原子替换正式文件
```

保存前比较磁盘内容摘要与编辑窗口打开时摘要：

- 一致：正常保存；
- 不一致：提示重新加载、查看差异或强制覆盖；
- 差异查看使用 IDEA Diff Viewer。

### 5.7 热加载

监听三层 TOML 文件：

```text
文件变化
→ 约 400ms 防抖
→ 后台读取和解析
→ 成功：替换该层快照
→ 失败：保留旧快照并显示错误条
```

插件自身写入时记录 `InternalWriteToken(path, expectedHash)`，避免重复提示。

### 5.8 配置错误

TOML 解析或结构校验失败时：

- 保留最后有效配置；
- 不清空任务列表；
- 面板顶部显示文件、行、列和原因；
- 提供“打开错误位置”；
- 提供“重新加载”；
- 修复并成功解析后自动解除提示。

## 6. 变量系统

支持：

```text
${PROJECT_DIR}
${USER_HOME}
${MODULE_DIR}
${ENV:NAME}
${SECRET:key}
```

规则：

- 解析顺序明确且不可递归无限展开；
- 未识别变量视为准备错误；
- `${MODULE_DIR}` 无法确定时给出明确错误；
- `${ENV:NAME}` 不存在时给出变量名，不输出其他敏感信息；
- `${SECRET:key}` 缺失时阻止执行；
- 编辑界面展示解析后的工作目录、Shell 与命令预览；
- Secret 始终保留为占位符。

## 7. Shell 自动识别

识别顺序：

```text
1. 任务显式配置 interpreter / shell
2. 文件 shebang
3. 文件扩展名
4. 当前操作系统默认 Shell
```

基础映射：

```text
.ps1        → pwsh；不可用时尝试 powershell.exe
.cmd/.bat   → cmd.exe
.sh         → shebang；否则 Unix 下使用 /bin/sh
无扩展名     → shebang
可执行文件   → 直接执行
内联命令     → 当前 Terminal 默认 Shell
```

约束：

- Windows 上 `.sh` 不自动假定存在 Git Bash 或 WSL；
- 找不到解释器时不创建 Terminal；
- 错误信息提示用户在任务设置中指定解释器；
- 路径和参数分别转义；
- 不通过简单字符串拼接处理参数；
- 必须为 PowerShell、CMD、Bash/Zsh 分别测试转义规则。

## 8. 任务准备与执行

### 8.1 统一入口

```kotlin
interface TaskRunner {
    suspend fun run(
        task: ResolvedTask,
        project: Project,
        trigger: RunTrigger
    ): TaskExecution
}
```

所有入口统一调用：

- Tool Window 运行按钮；
- 自动启动；
- `Run Dev Task...`；
- Favorite Slot；
- 固定快捷操作。

### 8.2 准备结果

```kotlin
data class PreparedTask(
    val taskId: String,
    val executionId: String,
    val displayName: String,
    val command: String,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val shellType: ShellType,
    val terminalPolicy: TerminalPolicy,
    val exitDetection: Boolean
)
```

准备阶段依次完成：

1. 检查任务启用状态；
2. 检查项目共享自动任务信任状态；
3. 解析作用域；
4. 解析路径变量；
5. 读取系统环境变量；
6. 异步读取 Password Safe；
7. 校验脚本与工作目录；
8. 识别 Shell；
9. 生成安全命令；
10. 创建随机 `executionId`。

## 9. IDEA Terminal 集成

### 9.1 目标

使用 IDEA 原生 Reworked Terminal，不自行实现控制台。

插件面板只负责：

- 管理任务；
- 展示状态；
- 创建、查找和激活 Terminal；
- 发送命令；
- 停止任务；
- 切换到对应 Terminal。

### 9.2 标签名称

优先级：

```text
terminalAlias
→ 脚本文件名
→ task.name
→ task.id
```

重复的新建标签追加序号。

### 9.3 Shell 就绪

禁止写死固定 `delay(4000)`。

创建 Terminal 后：

```text
等待 Shell Integration，最多约 10 秒
├─ 成功：立即发送
└─ 超时：退化为等待 Terminal 可输入后发送
```

目的：

- 兼容用户 PowerShell + Conda 的 3–4 秒初始化；
- 快速环境不被固定延迟拖慢；
- 复用终端时立即发送；
- Shell Integration 被禁用或失败时仍有降级路径。

Terminal API 在实施阶段必须按 IntelliJ IDEA Build 262 SDK 实际接口核对，不依赖旧版兼容层。

## 10. 状态检测

默认精确检测，单个交互式任务可关闭。

### 10.1 主通道

优先使用 Terminal Shell Integration 或 Terminal 命令模型获得：

- 命令开始；
- 命令结束；
- 运行状态；
- 退出码。

### 10.2 兜底通道

Shell Integration 不可用时，包装命令输出专用标记：

```text
__DEV_TASK_BEGIN__:<executionId>
__DEV_TASK_EXIT__:<executionId>:<exitCode>
```

分别针对 PowerShell、CMD、Bash/Zsh 生成正确包装。

要求：

- 使用随机执行 ID；
- 不使用固定任务 ID 作为唯一标识；
- 不泄露 Secret；
- 历史标记不得影响当前执行；
- 多标签并发互不干扰。

### 10.3 关闭检测

`exitDetection = false` 时：

- 显示“已启动/终端中运行”；
- 不伪造成功状态；
- 用户可切换到 Terminal；
- 停止仍发送 Ctrl+C；
- 最终状态可为 `UNKNOWN` 或 `STOPPED`。

## 11. 停止流程

```text
点击停止
→ 状态改为 STOPPING
→ 向对应 Terminal 发送 Ctrl+C
→ 后台等待命令停止
├─ 已停止：STOPPED
└─ 超时：显示“强制关闭终端”
```

强制关闭：

- 必须二次确认；
- 提示可能丢失复用终端中的上下文；
- 用户确认后关闭标签并终止进程；
- 不自动执行强制关闭。

## 12. 自动启动与信任

### 12.1 自动启动协调器

统一由 `TaskStartupCoordinator` 处理。

```kotlin
data class IdeSessionState(
    val sessionId: String,
    val executedGlobalTaskIds: MutableSet<String>,
    val pendingGlobalTaskIds: MutableSet<String>
)
```

项目打开后：

```text
加载配置
→ 解析覆盖
→ 检查共享任务信任
→ 收集 project-open 任务
→ 收集待执行 IDE-start 全局任务
→ 按 order 排序
→ 依次应用 delaySeconds
→ 提交给 TaskRunner
```

不等待前一任务结束。

### 12.2 全局任务

默认：

- 每次 IDEA 会话执行一次；
- IDEA 启动时无项目，则进入等待状态；
- 第一个项目打开后执行；
- 后续项目不重复。

可选：

- 每个项目执行一次。

### 12.3 项目信任

首次发现项目共享自动任务时显示：

- 自动任务列表；
- 风险说明；
- 查看配置；
- 信任并允许；
- 暂不允许。

信任状态：

- 只保存在本机；
- 不写入仓库；
- 绑定规范化项目路径与执行摘要；
- 执行字段变化后重新确认；
- 仅显示名称、描述、排序等变化不重新确认。

信任摘要包含：

- 任务 ID；
- 是否启用；
- 来源类型与内容；
- 脚本路径和参数；
- 工作目录；
- Shell 覆盖；
- 环境变量名称和引用；
- 自动启动触发方式。

不包含：

- 显示名称；
- 描述；
- UI 排序；
- Favorite Slot；
- Terminal 标签名称。

## 13. Dev Tasks Tool Window

采用独立 Tool Window 与扁平任务列表，不做分组。

### 13.1 顶部工具栏

- 新建；
- 编辑；
- 复制；
- 删除；
- 运行；
- 停止；
- 切换到 Terminal；
- 打开配置；
- 搜索。

### 13.2 任务行

显示：

- 名称；
- 自动/手动；
- 全局/项目共享/项目私有；
- Favorite Slot；
- 当前状态；
- 简短失败原因；
- 覆盖来源提示。

示例：

```text
● 启动后端   [自动] [项目共享] [槽位 1]   运行中
○ 启动前端   [手动] [项目私有]            已停止
× 数据同步   [手动] [全局]                退出码 1
```

### 13.3 交互

- 单击：选中；
- 双击：有 Terminal 时切换到 Terminal；没有时运行；
- 设置项允许改成“双击编辑”；
- 运行中的同一任务再次运行时，默认提示切换到 Terminal；
- 用户明确选择后才允许重复执行。

### 13.4 右键菜单

- 运行；
- 停止；
- 切换到 Terminal；
- 编辑；
- 复制任务；
- 启用/禁用；
- 打开脚本；
- 在文件管理器中定位；
- 复制最终命令；
- 查看覆盖来源；
- 删除。

复制最终命令不得包含 Secret 实值。

### 13.5 空状态

```text
尚未配置开发任务

你可以添加脚本文件或内联命令，
也可以直接编辑 TOML 配置。

[新建任务] [创建示例配置] [打开配置文件]
```

### 13.6 配置错误条

非阻塞显示：

```text
⚠ 项目共享配置存在错误，当前仍使用最后一次有效配置。
  tasks.toml:18:7 — Expected string, found array
  [打开错误位置] [重新加载] [关闭提示]
```

## 14. 任务编辑 Dialog

使用 IDEA 原生 Dialog，按区域组织。

### 14.1 基本信息

- ID；
- 名称；
- Terminal 别名；
- 描述；
- 作用域；
- 启用。

### 14.2 执行内容

- 脚本文件/内联命令；
- 脚本路径或命令；
- 参数；
- Shell/解释器自动或手动；
- 工作目录。

### 14.3 运行配置

- Terminal 策略；
- 退出检测；
- 环境变量；
- Secret 引用。

### 14.4 自动启动

- 不自动启动；
- IDEA 启动；
- 项目打开；
- 顺序；
- 延迟；
- 全局执行方式。

### 14.5 快捷槽位

- 无；
- Slot 1–9。

### 14.6 预览

实时显示：

- 解析后的工作目录；
- 解析后的 Shell；
- 最终执行命令预览；
- 校验警告。

Secret 只显示引用，不显示实值。

## 15. Action 与快捷键

固定 Action：

- 打开 Dev Tasks；
- 运行选中任务；
- 停止选中任务；
- `Run Dev Task...`。

`Run Dev Task...`：

- 弹出可搜索任务列表；
- 支持键盘选择；
- 显示作用域与状态；
- 选择后走统一 `TaskRunner`。

Favorite Slot：

- `Run Favorite Task 1` 至 `Run Favorite Task 9`；
- 在 IDEA Keymap 中绑定快捷键；
- 任务通过 `favoriteSlot = 1..9` 关联；
- 任务改名不影响快捷键；
- 覆盖后的有效任务接管槽位；
- 同一有效列表中槽位冲突必须提示配置错误或明确冲突。

## 16. 错误处理

错误分类：

1. 配置错误；
2. 准备错误；
3. 安全错误；
4. Terminal 错误；
5. 执行错误。

示例：

```text
配置错误：TOML 语法或结构非法
准备错误：脚本、目录或解释器不存在
安全错误：项目未信任或 Secret 缺失
Terminal 错误：标签创建、Shell 等待或发送失败
执行错误：退出码非 0
```

原则：

- 不让 Tool Window 白屏；
- 不清空最后有效任务；
- 不泄露 Secret；
- 不阻塞 EDT；
- 不自动重试失败任务；
- 保留 Terminal 输出；
- 列表显示简短原因；
- 详情显示可操作的完整原因。

失败原因优先级：

1. 配置错误；
2. 变量无法解析；
3. Secret 不存在；
4. 脚本不存在；
5. 工作目录不存在；
6. 找不到解释器；
7. Terminal 创建失败；
8. Shell 初始化或命令发送失败；
9. 退出码非 0；
10. 状态检测丢失。

## 17. 并发与生命周期

### 17.1 重复运行

同一任务存在 `PREPARING`、`WAITING_FOR_TERMINAL` 或 `RUNNING` 实例时：

- 默认不再次启动；
- 提供“切换到终端”；
- 用户明确确认后可重复运行；
- 不同任务可并行。

自动启动时发现同一任务已经手动运行，则跳过自动启动。

### 17.2 配置变化

- 正在运行的任务继续使用启动时快照；
- 下一次运行采用新配置；
- 正在运行的任务被删除时不自动关闭 Terminal；
- 修改任务 ID 视为删除旧任务并创建新任务；
- 被覆盖任务的既有执行实例保留原来源信息。

### 17.3 项目关闭

- 取消尚未触发的自动启动等待；
- 停止文件监听；
- 释放 Service；
- 不额外发送 Ctrl+C；
- 不在退出阶段等待进程。

### 17.4 会话恢复

重新打开项目后：

- 上次 `RUNNING` 或 `STOPPING` 显示为“上次会话已中断”；
- 不假定进程仍存在；
- 不自动重启。

## 18. 运行记录

只保存轻量元数据，不复制完整 Terminal 输出。

```kotlin
data class TaskRunRecord(
    val taskId: String,
    val executionId: String,
    val startTime: Instant,
    val endTime: Instant?,
    val status: TaskStatus,
    val exitCode: Int?,
    val failureCategory: FailureCategory?
)
```

规则：

- 每个任务最多保留最近 20 条；
- Secret 不进入记录；
- 完整输出留在 Terminal；
- IDEA 重启后正在运行状态统一标记为中断。

## 19. 测试策略

### 19.1 单元测试

优先覆盖纯逻辑：

- TOML 解析与序列化；
- 作用域覆盖；
- 删除覆盖后的恢复；
- 变量解析；
- Shell 识别；
- 参数和命令转义；
- 命令包装；
- 信任摘要；
- 任务状态机；
- 自动启动排序；
- Favorite Slot；
- Secret 脱敏；
- TOML 失败保留旧快照；
- 原子保存；
- 配置冲突。

关键测试：

- 项目私有完整覆盖项目共享和全局；
- 删除私有定义后共享定义恢复；
- Windows `.ps1` 识别为 PowerShell；
- Unix 无扩展名脚本通过 shebang 识别；
- Windows `.sh` 未配置解释器时给出明确错误；
- Secret 缺失时命令和日志均不泄漏其他 Secret；
- 自动任务按 `order` 与 `delay` 提交，不等待长期任务退出；
- 信任摘要忽略名称变化，但捕获执行内容变化；
- Favorite Slot 冲突能被发现。

### 19.2 平台集成测试

通过 `runIde` 人工验证：

1. 新建项目共享任务并运行；
2. PowerShell + Conda 初始化完成后自动发送命令；
3. 复用任务 Terminal 时不重新初始化 Shell；
4. Ctrl+C 能停止任务；
5. 超时后显示强制关闭；
6. 修改 TOML 后自动刷新；
7. 错误 TOML 时旧任务仍保留；
8. 首次共享自动任务显示信任提示；
9. 修改执行字段后重新要求信任；
10. 只改名称时不要求重新信任；
11. 私有任务覆盖共享和全局；
12. 全局 IDE-start 任务在第一个项目打开后只运行一次；
13. 每项目全局任务在各项目分别运行；
14. Favorite Slot 可由 Keymap 触发；
15. 重启后配置正确加载；
16. 关闭项目时无 UI 卡顿和退出等待；
17. Secret 不出现在预览、日志和通知中。

## 20. 实施阶段划分

每阶段必须：

- 先写或更新测试；
- 实现最小功能；
- 运行测试；
- 执行 `buildPlugin`；
- 修复编译错误与重要警告；
- 创建独立 Git commit。

阶段：

### 阶段 1：领域模型与配置

- 领域模型；
- TOML 解析与序列化；
- 三层仓库；
- 覆盖优先级；
- 最后有效快照；
- 原子保存；
- 单元测试。

### 阶段 2：任务准备

- 变量解析；
- 环境变量；
- Shell 识别；
- 参数转义；
- 命令包装；
- 准备错误；
- 单元测试。

### 阶段 3：Terminal 集成

- 声明 Terminal 插件依赖；
- 创建 Terminal；
- 标签命名；
- 三种复用策略；
- Shell 就绪等待；
- 命令发送；
- Build 262 API 验证。

### 阶段 4：状态与停止

- 状态机；
- Shell Integration 主通道；
- 输出标记兜底；
- 运行记录；
- Ctrl+C；
- 强制关闭；
- 重复运行保护。

### 阶段 5：主界面

- Dev Tasks Tool Window；
- 扁平列表；
- 工具栏；
- 右键菜单；
- 空状态；
- 任务编辑 Dialog；
- 配置错误条。

### 阶段 6：启动与安全

- 自动启动协调器；
- IDEA 会话任务；
- 项目打开任务；
- 顺序与延迟；
- 项目信任；
- Password Safe；
- Secret 脱敏。

### 阶段 7：快捷操作与收尾

- 固定 Action；
- `Run Dev Task...`；
- Favorite Slot 1–9；
- 文件热加载；
- Diff 冲突处理；
- 生命周期清理；
- 完整人工验证。

## 21. 完成标准

0.1 版本完成必须满足：

```text
打开项目
→ 从三层 TOML 加载任务
→ 正确解析覆盖关系
→ Tool Window 展示任务
→ 点击运行
→ 等待 PowerShell/Conda 初始化
→ 在正确命名的 IDEA Terminal 中执行
→ 正确显示运行、成功、失败或未知状态
→ 可发送 Ctrl+C 停止
→ 超时后可确认强制关闭
→ 修改 TOML 后自动刷新
→ 错误 TOML 时保留最后有效配置
→ 自动任务受信任机制保护
→ Secret 不泄露
→ Favorite Slot 可用
→ 重启 IDEA 后继续正常使用
```

## 22. 后续版本方向

完成 0.1 后，可独立设计：

- 0.2 开发资料库；
- 0.3 本地工具箱；
- 服务就绪检测；
- 显式任务继承；
- 任务模板；
- 导入导出；
- 运行历史查看；
- 更丰富的 Terminal 状态集成；
- 适配更多 IDE 和平台版本。
