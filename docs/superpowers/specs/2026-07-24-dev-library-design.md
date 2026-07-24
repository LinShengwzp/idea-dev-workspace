# Dev Workspace 0.2 — 资料库与书签设计规格

- 日期：2026-07-24
- 状态：已确认
- 目标平台：IntelliJ IDEA 2026.2 / Build 262
- 模块名称：Dev Library
- 所属插件：Dev Workspace
- 语言：Kotlin
- UI 文案：简体中文

## 1. 目标

在现有 Dev Workspace 插件中增加一个统一的资料库/书签模块，用于保存：

- Markdown 文本；
- 网页链接；
- 文件路径；
- 图片路径；
- 音视频等多媒体路径。

工具箱不单独开发，作为普通分组存在，例如“工具箱”，其中保存在线工具链接。

## 2. 第一版范围

支持：

- 项目私有、项目共享、全局三种作用域；
- 三个作用域合并展示，不做 ID 覆盖；
- 单层分组；
- 多标签；
- 收藏；
- 搜索与组合筛选；
- 新建、编辑、复制、移动作用域、删除；
- 智能打开；
- Markdown 编辑和预览；
- 图片直接预览；
- 文件存在性检查与重新定位；
- 导入与导出；
- JSON 索引与独立 Markdown 正文；
- 配置热加载；
- 中文 UI；
- 关键接口和复杂逻辑注释。

不支持：

- 多级分组；
- 复制外部文件到资料库；
- 后台持续追踪文件移动；
- 自动搜索失效文件的新位置；
- PDF、音视频内嵌播放器；
- 跨设备同步；
- SQLite；
- 富文本编辑器；
- 条目任意拖拽排序；
- 独立工具箱模块。

## 3. 作用域与存储位置

### 3.1 项目共享

```text
${PROJECT_DIR}/.dev-workspace/library/
├─ library.json
└─ contents/
   └─ <item-id>.md
```

可提交到 Git。

### 3.2 项目私有

```text
${IDE_SYSTEM}/dev-workspace/projects/<project-key>/library/
├─ library.json
└─ contents/
```

不进入项目仓库。

### 3.3 全局

```text
${IDE_CONFIG}/dev-workspace/library/
├─ library.json
└─ contents/
```

### 3.4 合并规则

三个作用域条目全部进入统一视图：

```text
全局 + 项目共享 + 项目私有 → 合并展示
```

- 不按 ID 覆盖；
- 每条记录保留来源作用域；
- 同名分组可在 UI 中视觉聚合；
- 同名条目允许并存；
- 条目可复制或移动到其他作用域。

## 4. 数据模型

### 4.1 类型

```kotlin
enum class LibraryItemType {
    MARKDOWN,
    LINK,
    FILE,
    IMAGE,
    MEDIA
}
```

新建时自动识别，可手动修正：

- `http://`、`https://` → LINK；
- 常见图片扩展名 → IMAGE；
- 常见音视频扩展名 → MEDIA；
- 其他有效路径 → FILE；
- 纯正文 → MARKDOWN。

保存后类型明确，不在每次加载时重新推断。

### 4.2 分组

```kotlin
data class LibraryGroup(
    val id: String,
    val name: String,
    val description: String?,
    val order: Int,
    val scope: LibraryScope,
)
```

规则：

- 单层；
- 分组属于某一作用域；
- 同作用域中名称不可重复；
- 合并视图按名称聚合；
- 删除分组时必须将条目移至“未分组”或其他分组；
- 不级联删除外部文件。

### 4.3 条目

```kotlin
data class LibraryItem(
    val id: String,
    val title: String,
    val type: LibraryItemType,
    val scope: LibraryScope,
    val groupId: String?,
    val tags: Set<String>,
    val note: String?,
    val favorite: Boolean,
    val target: String?,
    val contentFile: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

字段语义：

- `target`：链接或外部文件路径；
- `contentFile`：Markdown 正文相对路径；
- Markdown 正文固定为 `contents/<item-id>.md`；
- ID 使用 UUID；
- 标签去重、去空白；
- 收藏为独立布尔字段，不使用特殊标签。

### 4.4 JSON 索引

```json
{
  "version": 1,
  "groups": [],
  "items": []
}
```

要求：

- UTF-8；
- 稳定字段顺序；
- 分组按 `order`、`id` 输出；
- 条目按 `id` 输出；
- 使用临时文件 + 校验 + 原子替换；
- 单层损坏不影响其他作用域；
- 解析失败保留该层最后一次有效快照。

## 5. 路径规则

只保存原文件路径，不复制文件。

项目目录内：

```text
${PROJECT_DIR}/docs/example.pdf
```

项目外：

```text
D:/documents/example.pdf
```

规则：

- 内部统一使用正斜杠；
- 加载、手动刷新、打开前检查存在性；
- 文件失效时保留条目；
- 显示“文件不存在”警告；
- 提供“重新定位”；
- 不自动同步或猜测新路径；
- 外部文件删除时只影响可用状态，不删除条目。

## 6. 搜索与筛选

搜索覆盖：

- 标题；
- Markdown 正文；
- URL；
- 文件名和路径；
- 标签；
- 备注；
- 分组名称。

筛选条件：

- 作用域；
- 类型；
- 分组；
- 标签；
- 仅收藏；
- 文件状态：全部、正常、路径失效。

组合规则：

- 不同筛选维度之间为 AND；
- 同一维度多选为 OR；
- 搜索忽略大小写；
- 标签精确匹配；
- 搜索和筛选只改变视图，不修改存储。

默认排序：

```text
收藏优先 → 分组顺序 → 最近更新
```

可切换：

- 标题；
- 创建时间；
- 更新时间；
- 类型。

## 7. 智能打开

- LINK：系统浏览器；
- MARKDOWN：资料库内预览，可在 IDEA 编辑器打开正文文件；
- IDEA 可识别的文本、代码、PDF：优先 IDEA 编辑器；
- IMAGE：资料库详情区预览，菜单可用 IDEA 或系统打开；
- MEDIA 和 IDEA 不支持的文件：系统默认程序；
- 路径失效：阻止打开，显示警告并提供重新定位。

## 8. 预览

### 8.1 Markdown

- 编辑显示源文；
- 查看显示渲染结果；
- 支持标题、列表、代码块、链接；
- 不执行嵌入脚本；
- 可切换“预览 / 源文”。

### 8.2 图片

- 异步加载；
- 不阻塞 EDT；
- 按可视区域等比缩放；
- 限制解码尺寸和内存缓存；
- 缓存键包含规范化路径、修改时间和目标尺寸；
- 文件变化后缓存失效；
- 不存在时显示占位提示；
- GIF 第一版按静态图片处理。

## 9. UI

独立 Tool Window：`资料库`

```text
┌ 分组与筛选 ┬ 条目列表 ┬ 详情/预览 ┐
│ 全部       │ 标题      │ 标题      │
│ 收藏       │ 类型      │ 标签      │
│ 工具箱     │ 作用域    │ 备注      │
│ 项目文档   │ 状态      │ 内容预览  │
└───────────┴───────────┴──────────┘
```

要求：

- 三栏；
- 左侧分组、收藏和筛选；
- 中间条目列表；
- 右侧详情与预览；
- 右侧可折叠；
- 窄宽度下不强行保持三栏；
- 顶部搜索；
- 工具栏支持新建、编辑、复制、收藏、打开、重新定位、导入、导出、删除；
- 所有用户文案进入 `DevWorkspaceBundle.properties`；
- 使用 IDEA 原生组件、图标、颜色和滚动容器。

## 10. 导入导出

ZIP 格式：

```text
dev-workspace-library.zip
├─ manifest.json
├─ library.json
└─ contents/
   └─ *.md
```

规则：

- 只导出 JSON 和 Markdown 正文；
- 不复制外部文件、图片或多媒体；
- 路径引用保持原样；
- manifest 包含格式版本、导出时间、来源作用域；
- 导入时选择目标作用域；
- ID 冲突默认生成新 ID；
- 分组名称冲突复用同名分组；
- 缺失 Markdown 正文时跳过该条并报告；
- 导入前完整校验；
- 写入失败不留下半成品。

## 11. 删除和移动

- 删除 Markdown 条目时删除内部 `.md`；
- 删除链接/文件/图片/媒体条目只删除索引；
- 绝不删除外部原文件；
- 移动作用域时复制条目和 Markdown 正文，成功后删除源索引；
- 移动失败保持源数据；
- 修改 ID 视为新建 + 删除旧条目。

## 12. 热加载与容错

- 监听三个 `library.json` 和各自 `contents/`；
- 约 400ms 防抖；
- 插件自身写入避免循环通知；
- 某一作用域解析失败时使用该层最后有效快照；
- 错误显示文件、行列或 JSON path；
- 修复后自动恢复；
- 不清空其他作用域。

## 13. 注释要求

Codex 必须补充有价值的中文或英文注释，重点包括：

- 公共接口和核心数据模型；
- 三层仓库与合并规则；
- JSON 版本迁移边界；
- 原子保存与最后有效快照；
- 路径变量解析；
- 文件状态检测；
- 搜索组合规则；
- 智能打开决策；
- 图片缓存和资源释放；
- 导入导出的事务边界；
- 不直观的 IntelliJ Platform API。

禁止：

- 每行机械注释；
- 重复代码表面含义；
- 在注释中留下待办占位符；
- 注释与实现不一致。

## 14. 完成标准

```text
打开项目
→ 资料库加载三个作用域
→ 分组与条目合并展示
→ 新建 Markdown、链接和文件条目
→ 搜索、标签、收藏和筛选正常
→ 图片可预览
→ 文件失效能提醒并重新定位
→ 智能打开正确
→ 导入导出可用
→ 外部修改自动刷新
→ 单层配置损坏不清空其他资料
→ UI 全中文
→ 测试和 buildPlugin 通过
```
