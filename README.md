# Dev Workspace

Dev Workspace 是面向 IntelliJ IDEA 的个人开发工作台插件，目前包含三个模块：

- **Dev Tasks**：从 TOML 配置启动开发任务，并将命令发送到 IDEA Terminal。
- **Dev Library**：按全局、项目共享和项目私有作用域管理开发资料。
- **MarkMap Preview**：在右侧 `Markmap` Tool Window 中预览特定后缀的 Markdown 思维导图。

## MarkMap Preview

默认识别以下完整文件名后缀：

```text
mm.md
mm.markdown
```

例如，`architecture.mm.md` 和 `notes.mm.markdown` 会被识别；普通 `.md` 和
`.markdown` 文件默认不会被识别。

可在以下位置修改后缀，保存后无需重启：

```text
Settings → Tools → Dev Workspace → MarkMap
```

在匹配的文件上，可以从编辑器右键菜单或 Project View 右键菜单选择“思维导图”。
该操作会激活右侧 Markmap Tool Window。MarkMap Action 可在 Find Action 和 Keymap
中找到，但不预设快捷键。

MarkMap 预览依赖 IntelliJ Platform 的 JCEF 支持；运行环境不支持 JCEF 时，Tool
Window 会显示本地化提示。

更多信息：

- [MarkMap 集成说明](docs/markmap-integration.md)
- [MarkMap WebView 开发](docs/markmap-webview-development.md)

## 构建验证

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

常规 Gradle 构建不会调用 npm。

## 打包资源进去

```powershell
cls;.\gradlew.bat processResources; .\gradlew.bat runIde

.\gradlew.bat buildPlugin

## \build\distributions\dev-workspace-0.1.0-SNAPSHOT.zip
## D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\build\distributions\dev-workspace-0.1.0-SNAPSHOT.zip
```
