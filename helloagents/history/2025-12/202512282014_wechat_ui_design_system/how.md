# 技术设计：微信绿白视觉系统 + 微组件

## 1. 视觉变量（SSOT）

在 `frontend/src/styles/app.css` 中扩展变量，形成稳定的视觉语言：
- 色彩：
  - `--primary`（微信绿）：`#07C160`
  - `--bg`（全局背景）：`#F5F5F5`
  - `--panel/--card`（表面色）：`#FFFFFF`
  - `--text` / `--text-2` / `--text-3`
  - `--divider`：`rgba(0,0,0,.05)`
- 阴影：
  - `--shadow-card`：柔和悬浮感
  - `--shadow-float`：主按钮绿色光晕（仅用于主 CTA）
- 圆角：
  - `--radius-card`：16px
  - `--radius-btn`：8px（与现有 radius 兼容）

兼容策略：
- 保留现有 `--bg-soft/--border/--shadow-soft/--radius-lg` 等变量，并将其映射到新变量，避免现有页面失真。

## 2. 微组件设计

### 2.1 Avatar
职责：
- 图片加载失败时回退到“首字母 + 固定哈希色”占位头像。
- 支持自定义尺寸；默认 44px。

接口（建议）：
- `text?: string`（昵称/用户名）
- `src?: string`
- `seed?: string`（userId 等，用于哈希取色）
- `size?: number`

### 2.2 Badge
职责：
- 提供统一的未读数/状态标签样式。
- 支持 `dot`（小红点）与 `count`（数字）两种模式。

### 2.3 ListItem
职责：
- 通用通栏列表行布局（left / main / right 三段）。
- 支持 hover/active 高亮与可点击区域一致性。

### 2.4 SegmentedControl
职责：
- 好友申请页顶部筛选控件（inbox/outbox/all）。
- 选中项白底 + 阴影；未选中项灰字；整体圆角胶囊。

## 3. 页面重构策略

### 3.1 LoginView
- 采用 Center-Stage 布局，背景使用渐变/噪点（轻量实现）。
- 登录卡片使用轻磨砂（`backdrop-filter`）与柔和阴影。
- 输入框改为“浅灰填充 + 底部强调线”，focus 有绿色扩散环。
- 主按钮全宽 + `--shadow-float`，active 有轻缩放反馈。

### 3.2 ConversationsView
- 列表改为 Edge-to-Edge（取消卡片堆叠间隙，改为分割线）。
- 使用 `Avatar + ListItem + Badge` 组合，统一行高与 hover 背景。
- 时间戳与预览文案走 `--text-2/--text-3`。

### 3.3 FriendRequestsView
- 顶部改为 SegmentedControl。
- 列表行使用 `ListItem + Avatar + Badge`，操作按钮弱化为文字按钮。

## 4. 质量验证
- 前端：`npm -C frontend run build`

