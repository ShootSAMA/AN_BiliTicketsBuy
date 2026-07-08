基于 Android AccessibilityService 的 B 站会员购自动抢票工具。悬浮窗倒计时 + 无障碍自动化，无需 Root。
# B站抢票助手 (BiliBili Ticket Purchase Assistant)

基于 Android AccessibilityService 的 B 站会员购自动抢票工具。悬浮窗倒计时 + 无障碍自动化，无需 Root。

## 功能

- 悬浮窗倒计时（基于 NTP 时间同步）
- 放票瞬间零延迟点击购买按钮
- 自动检测确认订单页并提交订单
- 自动处理拥挤弹窗（"再试一次"）
- 页面检测驱动：不依赖固定流程顺序，自动适配当前页面

## 架构

```
FloatingWindowService         — 悬浮窗 UI + 倒计时
  └─ TicketAccessibilityService — 无障碍服务，转发事件
       └─ OrderFlowController   — 流程编排器（页面检测驱动）
            ├─ BiliButtonFinder — B 站窗口节点查找
            ├─ ClickExecutor    — 智能点击（WebView 手势 / 原生节点）
            └─ NtpTimeSync      — NTP 时间同步
```

### 核心设计

**页面检测驱动**：每次窗口变化触发 `detectAndAct()`，检测当前页面上关键文本的存在判断页面类型（购票页/订单页/未知），自动执行对应操作。

**WebView 自动化**：B 站会员购使用 WebView 渲染，传统 Accessibility API 对 WebView 内容不可靠。采用两步搜索（系统 API → 手动遍历）+ `dispatchGesture` 手势点击代替 `ACTION_CLICK`。

**文本消失验证**：点击提交订单后，通过检查"提交订单"文本是否消失判断操作是否成功，不依赖不可靠的 WebView 坐标。

## 要求

- Android 7.0（API 24）及以上
- 需在系统设置中开启无障碍服务权限
- 需授予悬浮窗权限
- 需安装 B 站 App

## 构建

```bash
# 调试包
./gradlew assembleDebug

# 输出路径：app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装 APK 并开启无障碍服务
2. 授予悬浮窗权限
3. 打开 B 站会员购页面
4. 点击悬浮窗"开始"按钮
5. 等待倒计时归零自动抢票

### 弹窗处理

高峰期 B 站可能弹出"前方拥挤"等提示，程序会自动检测并点击"再试一次"按钮。弹窗处理独立于主流程状态，即使流程已结束也会继续响应。

## 技术要点

| 问题 | 方案 |
|------|------|
| 悬浮窗干扰窗口获取 | `getWindows()` 按包名查找，不用 `getRootInActiveWindow()` |
| WebView 文字搜不到 | 先调系统 API，失败后手动递归遍历节点树 |
| WebView 点击无效 | `dispatchGesture` 坐标手势，不用 `performAction(ACTION_CLICK)` |
| 坐标不准 | 用文本存在/消失验证，不依赖坐标判断结果 |
| 流程状态残留 | `start()` 前先 reset，防止 `isActive` 静默失败 |
| 多机型适配 | 所有点击坐标通过 `getBoundsInScreen()` 动态获取，无硬编码 |

## 依赖

- AGP 9.x / Gradle 9.x
- Kotlin + Jetpack Compose
- AndroidX DataStore（配置持久化）
- Apache Commons Net（NTP 同步）
