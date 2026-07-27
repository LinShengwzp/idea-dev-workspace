# MarkMap Preview 人工验收清单

## 准备

- [ ] 确认使用包含 Dev Tasks、Dev Library 和 MarkMap Preview 的 Dev Workspace 插件。
- [ ] 准备 `README.md`、`example.mm.md`、`example.mm.markdown` 和
  `example.mind.md`。
- [ ] 打开 `Settings → Tools → Dev Workspace → MarkMap`。

## 文件识别与 Action

- [ ] 在编辑器中右键普通 `README.md`，确认不显示“思维导图”。
- [ ] 在 Project View 中右键普通 `README.md`，确认不显示“思维导图”。
- [ ] 在编辑器中右键 `example.mm.md`，确认显示并可执行“思维导图”。
- [ ] 在 Project View 中右键 `example.mm.md`，确认显示并可执行“思维导图”。
- [ ] 对 `example.mm.markdown` 重复以上检查，确认同样可用。
- [ ] 在 Find Action 和 Keymap 中能找到“思维导图”，且未预设快捷键。

## Tool Window 与实时预览

- [ ] 执行“思维导图”后，右侧打开 ID 为 `Markmap` 的 Tool Window。
- [ ] Tool Window 使用 `markmap-icon.svg`，且与 Dev Tasks、Dev Library 图标可区分。
- [ ] 预览正确显示当前文档，中文和代码块无编码异常。
- [ ] 连续编辑文档，确认停止输入约 300ms 后只显示最新内容。
- [ ] 从一个 MarkMap 文档切换到另一个文档，确认旧文档后续编辑不再改变当前预览。

## 后缀设置

- [ ] 添加 `mind.md` 并 Apply，确认 `example.mind.md` 无需重启即可显示 Action。
- [ ] 删除 `mm.md` 并 Apply，确认原 `.mm.md` 文件立即隐藏 Action。
- [ ] 使用 Reset，确认恢复最近一次保存的后缀。
- [ ] 使用“恢复默认值”并 Apply，确认后缀恢复为 `mm.md` 和 `mm.markdown`。
- [ ] 清空并 Apply，确认所有文件均不显示 MarkMap Action。

## 异常与生命周期

- [ ] 在不支持 JCEF 的运行环境中，确认 Tool Window 显示本地化提示且无未处理异常。
- [ ] 关闭 Tool Window Content 或项目，确认无 JCEF、已释放面板或文档监听异常。
- [ ] 重开项目并再次预览，确认创建的是有效的新面板。

## 回归与构建

- [ ] Dev Tasks Tool Window、任务发送和 Terminal 行为保持正常。
- [ ] Dev Library Tool Window、搜索、详情和打开行为保持正常。
- [ ] 在未安装或不可用 Node/npm 的环境中运行 `.\gradlew.bat test`，确认成功。
- [ ] 在未安装或不可用 Node/npm 的环境中运行 `.\gradlew.bat buildPlugin`，确认成功。
- [ ] 仅在修改 `webview/markmap` 后手动执行 `npm install` 和 `npm run build`，
  并确认生成的 `/markmap/index.html` 随插件打包。
