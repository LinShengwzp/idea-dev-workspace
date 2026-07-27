# MarkMap WebView 开发

MarkMap 的 TypeScript/Vite 源码位于：

```text
D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\webview\markmap
```

它从只读来源项目
`D:\idea-workspace\java\idea-markmap-preview\idea-markmap-preview\webview`
迁入。运行时只使用已生成并提交的：

```text
D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\src\main\resources\markmap\index.html
```

修改 WebView 源码后，在 PowerShell 中手动执行：

```powershell
cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\webview\markmap"
npm install
npm run build

cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace"
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

约束：

- 不要手工编辑生成后的 `src/main/resources/markmap/index.html`。
- 不提交 `node_modules/`。
- 第一次运行 `npm install` 会生成 `package-lock.json`；依赖发生变化时应同步更新并提交锁文件。
- 应提交重新生成的 `src/main/resources/markmap/index.html`。
- 常规 Gradle `processResources`、`test`、`runIde` 和 `buildPlugin` 不需要 Node，也不会执行 npm。
- Gradle 中不得增加 npm Task 或把 npm 连接到资源处理、测试、IDE 沙箱或插件打包任务。
