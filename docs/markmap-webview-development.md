# MarkMap WebView 开发

MarkMap 的 TypeScript/Vite 源码位于 `webview/markmap`，运行时使用已生成并提交的
`src/main/resources/markmap/index.html`。

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
- 第一次运行 `npm install` 会生成 `package-lock.json`，应将其提交。
- 应提交重新生成的 `src/main/resources/markmap/index.html`。
- 常规 Gradle `test`、`runIde` 和 `buildPlugin` 不需要 Node，也不会执行 npm。
