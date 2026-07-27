# Dev Workspace — MarkMap Preview 合并设计规格

- 日期：2026-07-27
- 状态：已确认
- 来源项目：`D:\idea-workspace\java\idea-markmap-preview\idea-markmap-preview`
- 目标项目：`D:\idea-workspace\java\idea-dev-workspace\Dev Workspace`
- 目标平台：IntelliJ IDEA 2026.2 / Build 262
- 语言：Kotlin
- UI：简体中文

## 1. 目标

将独立的 `idea-markmap-preview` 插件完整合并到 `Dev Workspace`，形成一个插件内的三个功能模块：

```text
Dev Workspace
├─ Dev Tasks
├─ Dev Library
└─ MarkMap Preview
```

合并后不再维护或构建独立 MarkMap 插件。旧项目仅作为只读迁移来源。

## 2. 已确认的迁移边界

迁移：

- MarkMap Tool Window；
- 编辑器和 Project View 右键 Action；
- JCEF 预览面板；
- 文档监听与 300ms 防抖刷新；
- WebView TypeScript/Vite 源码；
- 已构建的单文件 WebView HTML；
- MarkMap 模块图标；
- 可配置文件后缀；
- WebView 开发与重新构建文档。

不迁移：

- 旧插件 ID `com.anmi.markmap`；
- 旧插件名称、vendor、版本和发布配置；
- 旧 Gradle 工程；
- 旧 `plugin.xml` 整体内容；
- 旧设置或持久化数据兼容；
- 旧 `MarkmapPreviewHolder.kt`；
- Gradle 自动执行 npm；
- 普通 `.md`、`.markdown` 文件默认识别；
- 新的 Editor Preview Tab；
- WebDAV、Git 或服务器功能。

## 3. 源项目清单

来源项目当前包含：

```text
src/main/kotlin/
├─ MarkmapPreviewHolder.kt
├─ MarkmapPreviewPanel.kt
├─ MarkmapPreviewService.kt
├─ MarkmapToolWindowFactory.kt
└─ OpenMarkmapPreviewAction.kt

src/main/resources/
├─ META-INF/plugin.xml
├─ META-INF/pluginIcon.svg
└─ markmap/index.html

webview/
├─ index.html
├─ package.json
├─ vite.config.ts
└─ src/main.ts
```

迁移判断：

- `MarkmapPreviewHolder.kt` 未被实际使用，不迁移；
- 其余四个 Kotlin 实现作为迁移基础；
- 不复制旧 `plugin.xml`；
- 不复制旧 `build.gradle.kts` 中的 npm 自动构建任务；
- `src/main/resources/markmap/index.html` 作为已构建运行时资源迁移；
- `webview/` 源码迁到目标工程中继续维护。

## 4. 目标代码结构

```text
src/main/kotlin/com/anmi/devworkspace/markmap/
├─ MarkmapIcons.kt
├─ MarkmapPreviewPanel.kt
├─ MarkmapPreviewService.kt
├─ MarkmapToolWindowFactory.kt
├─ MarkmapSuffixMatcher.kt
├─ OpenMarkmapPreviewAction.kt
└─ settings/
   ├─ MarkmapSettingsState.kt
   └─ MarkmapConfigurable.kt
```

测试：

```text
src/test/kotlin/com/anmi/devworkspace/markmap/
├─ MarkmapSuffixMatcherTest.kt
├─ MarkmapPreviewServiceTest.kt
└─ OpenMarkmapPreviewActionTest.kt
```

设置测试按目标项目现有测试风格放置。

## 5. WebView 结构

```text
webview/
└─ markmap/
   ├─ index.html
   ├─ package.json
   ├─ vite.config.ts
   └─ src/
      └─ main.ts

src/main/resources/
└─ markmap/
   └─ index.html
```

运行时只读取：

```text
/markmap/index.html
```

`vite.config.ts` 调整为从 `webview/markmap` 输出到目标资源目录：

```ts
import { defineConfig } from 'vite'
import { viteSingleFile } from 'vite-plugin-singlefile'

export default defineConfig({
  plugins: [viteSingleFile()],
  build: {
    outDir: '../../src/main/resources/markmap',
    emptyOutDir: true
  }
})
```

## 6. WebView 构建策略

采用“源码保留、构建产物随插件打包、Gradle 不自动调用 npm”。

要求：

- `test`、`runIde`、`buildPlugin` 不依赖 Node/npm；
- 未修改 WebView 时无需安装 Node；
- 不直接编辑生成后的 `src/main/resources/markmap/index.html`；
- 修改 `webview/markmap/` 后手动运行 npm 构建；
- `node_modules/` 不提交；
- 首次 `npm install` 生成的 `package-lock.json` 应提交；
- 构建产物 `src/main/resources/markmap/index.html` 应提交。

开发文档保存为：

```text
docs/markmap-webview-development.md
```

必须包含：

```powershell
cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\webview\markmap"
npm install
npm run build

cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace"
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

## 7. 文件识别规则

只识别配置中的完整文件名后缀。

默认后缀：

```text
mm.md
mm.markdown
```

示例：

```text
architecture.mm.md       匹配
notes.mm.markdown        匹配
TEST.MM.MD               匹配
README.md                 不匹配
notes.markdown            不匹配
example.md.txt            不匹配
xmm.md                    不匹配
```

规范化：

- 每行一个后缀；
- 去除首尾空白；
- 去除所有开头的 `.`；
- 转为小写；
- 删除空值；
- 去重；
- 保留首次出现顺序；
- 忽略大小写匹配。

边界匹配：

```text
文件名等于 suffix
或
文件名以 "." + suffix 结尾
```

因此 `xmm.md` 不会误匹配 `mm.md`。

空后缀集合表示禁用所有 MarkMap 文件识别。

## 8. Settings 配置

位置：

```text
Settings
→ Tools
→ Dev Workspace
→ MarkMap
```

配置项：

```text
识别为 MarkMap 的文件后缀
[多行文本框，每行一个后缀]

默认值：
mm.md
mm.markdown
```

保存方式：

- Application 级 `PersistentStateComponent`；
- 不按项目区分；
- 不兼容旧插件配置；
- 使用 Dev Workspace 现有设置父节点；
- 若目标项目尚无 Dev Workspace 设置父节点，则创建一个稳定父节点；
- Apply 后立即生效，无需重启；
- Reset 恢复已保存状态；
- Restore Defaults 恢复默认后缀。

配置状态建议：

```kotlin
@Service(Service.Level.APP)
@State(
    name = "DevWorkspaceMarkmapSettings",
    storages = [Storage("dev-workspace.xml")],
)
class MarkmapSettingsState : PersistentStateComponent<MarkmapSettingsState.State> {
    data class State(
        var suffixes: MutableList<String> = DEFAULT_SUFFIXES.toMutableList(),
    )
}
```

实际 State Bean 形式应遵循目标项目和 Build 262 的稳定 API。

## 9. Action 行为

Action ID：

```text
com.anmi.devworkspace.markmap.openPreview
```

显示名称：

```text
思维导图
```

入口：

- `EditorPopupMenu`；
- `ProjectViewPopupMenu`；
- Find Action / Keymap 中可搜索；
- 不绑定默认快捷键。

`update()`：

- 读取 `CommonDataKeys.VIRTUAL_FILE`；
- 通过 `MarkmapSuffixMatcher` 判断；
- 不匹配时隐藏并禁用；
- 使用适合 Build 262 的 `ActionUpdateThread`；
- 不在 EDT 读取文件内容；
- 设置修改后下一次 Action 更新立即生效。

`actionPerformed()`：

- 优先使用当前 Editor 的 Document；
- 否则从 VirtualFile 获取 Document；
- 激活 `Markmap` Tool Window；
- 调用项目级 `MarkmapPreviewService.preview(document)`；
- 找不到 Document 或 Tool Window 时记录日志并友好返回。

Action 多语言使用 `DevWorkspaceBundle` 的 Action System 约定键，不使用 `%key` 文字占位。

## 10. Tool Window

Tool Window：

```text
ID：Markmap
Anchor：right
Factory：com.anmi.devworkspace.markmap.MarkmapToolWindowFactory
Icon：目标项目 src/main/resources/icons 中实际存在的 MarkMap SVG
```

要求：

- Codex 先检查实际图标文件名，不猜测；
- 若存在 `markmap-icon.svg`，优先使用；
- 使用 `DevWorkspaceBundle` 提供可见文案；
- 不复制旧插件的主图标注册；
- Tool Window 的交互保持旧项目行为。

## 11. 预览面板与生命周期

`MarkmapPreviewPanel`：

- 使用 `JBCefApp.isSupported()` 判断；
- 不支持时显示本地化提示；
- 支持时创建 `JBCefBrowser`；
- 读取 `/markmap/index.html`；
- Markdown 与 options JSON 使用 Base64 传给 WebView；
- JavaScript 在应用线程切回合适 UI 时机执行；
- `dispose()` 释放 `JBCefBrowser`。

必要生命周期修复：

- Tool Window Content 必须注册 `MarkmapPreviewPanel` 为 disposer；
- 项目关闭或内容释放时 JCEF 资源必须释放；
- 不保留无用的 `MarkmapPreviewHolder`；
- 服务不应持有已释放面板并继续渲染；
- 只做必要修复，不重写 WebView 协议。

## 12. 预览服务

`MarkmapPreviewService` 保持项目级服务：

- 记录当前 Document；
- 绑定 DocumentListener；
- 输入停止 300ms 后刷新；
- 切换预览文档时释放旧监听；
- 项目释放时取消 Alarm、释放监听并清空引用；
- Tool Window 创建后补渲染当前文档；
- 面板释放时安全解除引用或忽略后续渲染。

## 13. 插件注册

禁止整体复制来源 `plugin.xml`。

只将以下内容合并进目标 `plugin.xml`：

- Markmap Tool Window；
- Open Preview Action；
- Editor Popup 注册；
- Project View Popup 注册；
- Application Configurable。

依赖：

- 使用目标插件已有 IntelliJ Platform 依赖；
- 不增加独立插件 ID；
- 不增加旧插件 vendor；
- 不增加 npm Gradle Task；
- 不改变 Dev Tasks 和 Dev Library 注册。

## 14. 多语言

新增资源键至少覆盖：

- Tool Window 标题或说明；
- Action text/description；
- JCEF 不支持提示；
- WebView 资源缺失提示；
- Settings 页面名称；
- 后缀字段标题、说明、默认值提示；
- 无 Document、Tool Window 不存在等用户可见通知（如有）。

日志可使用英文或中文，但不得把文件正文写入日志。

## 15. 测试

### 15.1 Suffix Matcher

自动测试：

- 默认 `architecture.mm.md` 匹配；
- 默认 `notes.mm.markdown` 匹配；
- 大小写忽略；
- 普通 Markdown 不匹配；
- `xmm.md` 不误匹配；
- 输入 `.mm.md` 被规范化；
- 空行、空格和重复项被清理；
- 配置修改后立即影响匹配；
- 空集合不匹配任何文件。

### 15.2 Action

自动测试：

- 匹配文件时 Action 可见且启用；
- 普通 Markdown 时隐藏且禁用；
- 中文 Action 文案不显示 `%`；
- Editor 和 Project View DataContext 可识别；
- Action 更新不读取文件正文。

### 15.3 Service

自动测试：

- 切换 Document 时旧监听释放；
- 300ms 防抖只渲染最后状态；
- Panel 晚于 Document 创建时可补渲染；
- dispose 后不再触发渲染；
- 测试使用可控调度，不依赖实际睡眠。

### 15.4 Settings

自动测试：

- 默认值；
- Apply/Reset；
- 规范化保存；
- Restore Defaults；
- Application 级共享。

### 15.5 构建

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

Gradle 验证不得调用 npm。

## 16. 人工验收

1. `README.md` 不显示“思维导图”；
2. `example.mm.md` 在编辑器右键显示；
3. `example.mm.md` 在 Project View 右键显示；
4. 点击后打开 Markmap Tool Window；
5. 文档修改后约 300ms 刷新；
6. Settings 新增 `mind.md` 后，`example.mind.md` 立即生效；
7. Settings 删除 `mm.md` 后，原 `.mm.md` 不再显示 Action；
8. Reset 和恢复默认值正确；
9. JCEF 不支持时显示友好提示；
10. 关闭项目后无 JCEF 泄漏或异常；
11. 没有 Node 时 `test`、`runIde`、`buildPlugin` 正常；
12. WebView 源码修改并 `npm run build` 后资源正确更新；
13. Dev Tasks 和 Dev Library 不受影响；
14. 三个 Tool Window 图标可清晰区分。

## 17. 完成标准

```text
MarkMap 源码和 WebView 合入 Dev Workspace
→ 旧项目配置和插件身份不迁移
→ 仅配置后缀文件显示入口
→ 默认 mm.md / mm.markdown
→ Settings 可编辑且立即生效
→ 预览交互保持原样
→ WebView 构建不进入 Gradle
→ JCEF 生命周期安全
→ Dev Tasks / Dev Library 无回归
→ test、buildPlugin、边界检查通过
```
