# MarkMap Preview 集成说明

## 用户入口

MarkMap Preview 已作为 Dev Workspace 的一个模块提供，不再作为独立插件运行。

默认识别以下完整文件名后缀：

```text
mm.md
mm.markdown
```

匹配忽略大小写，并要求完整后缀边界。普通 `.md` 和 `.markdown` 文件默认不会显示
“思维导图”操作。

后缀设置位于：

```text
Settings → Tools → Dev Workspace → MarkMap
```

Apply 后，编辑器和 Project View 右键菜单会在下一次更新时立即使用新设置，无需重启。
可使用 Reset 恢复已保存设置，或使用“恢复默认值”重新填入默认后缀。

在匹配文件的编辑器或 Project View 右键菜单中选择“思维导图”，会打开右侧
`Markmap` Tool Window。Action 同时可从 Find Action 和 Keymap 搜索，但没有默认快捷键。

预览使用 JCEF。JCEF 不可用时，Tool Window 会显示提示，不会尝试创建替代的编辑器
预览页。

## 集成边界

迁移来源为只读项目：

```text
D:\idea-workspace\java\idea-markmap-preview\idea-markmap-preview
```

Kotlin 代码迁入：

```text
src/main/kotlin/com/anmi/devworkspace/markmap
```

WebView 源码与运行时资源分别位于：

```text
webview/markmap
src/main/resources/markmap/index.html
```

集成保留了 Base64 WebView 协议、300ms 文档变更防抖、右侧 Tool Window 交互和
JCEF 生命周期管理。

以下旧组件明确没有迁移：

- `MarkmapPreviewHolder.kt`
- 旧插件身份和旧 `plugin.xml`
- 旧 Gradle npm 自动构建钩子
- 旧发布配置与元数据

独立项目的插件 ID、vendor、版本、设置和发布配置均不属于 Dev Workspace 的兼容边界。
Dev Tasks 与 Dev Library 的行为不因本次集成而改变。

## 构建边界

Gradle 的 `processResources`、`test`、`runIde` 和 `buildPlugin` 均不会执行 npm。
修改 WebView 后，应按照 [MarkMap WebView 开发](markmap-webview-development.md)
手动生成并提交单文件 HTML。
