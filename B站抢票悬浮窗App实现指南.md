# B站抢票悬浮窗 App 实现指南

> 基于 Android Studio · Java + Kotlin 混合开发 · 悬浮窗 + AccessibilityService 屏幕点击

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术架构设计](#2-技术架构设计)
3. [项目配置](#3-项目配置)
4. [项目结构规划](#4-项目结构规划)
5. [无障碍服务核心实现](#5-无障碍服务核心实现)
6. [悬浮窗服务](#6-悬浮窗服务)
7. [主界面 UI（Compose）](#7-主界面-uicompose)
8. [权限处理流程](#8-权限处理流程)
9. [NTP 对时与定时调度](#9-ntp-对时与定时调度)
10. [测试与调试](#10-测试与调试)
11. [合规与注意事项](#11-合规与注意事项)

---

## 1. 项目概述

### 1.1 方案选型说明

本方案 **不直接调用 B 站 API**，而是采用 **悬浮窗 + AccessibilityService 屏幕点击** 方案。

| 维度 | API 直接调用（弃用） | 屏幕点击方案（采用） |
|------|---------------------|---------------------|
| 登录态 | 需自行管理 Cookie/Token | B 站 App 已登录，无需处理 |
| 风控对抗 | 需逆向 WBI 签名、设备指纹 | B 站 App 自身处理风控 |
| 验证码 | 需程序化过极验滑块（几乎不可能） | 弹出后用户手动滑即可 |
| 接口维护 | B 站改接口则全部失效 | B 站 App 更新后仅需更新控件 ID |
| 封号风险 | 高（异常请求模式） | 极低（等同人工操作） |
| 实现复杂度 | 高（网络层+签名+加密） | 中（无障碍+悬浮窗） |

**核心思路**：用户手机上已安装并登录 B 站 App → 用户手动打开购票页面 → 我们的 App 通过悬浮窗显示倒计时 → 到点后 AccessibilityService 自动点击"立即购买"等按钮 → B 站 App 正常完成下单流程。

### 1.2 功能目标

| 功能模块 | 说明 |
|---------|------|
| **悬浮窗控制台** | 在 B 站 App 之上显示倒计时面板，一键开始/停止抢票 |
| **NTP 精确对时** | 与 NTP 服务器同步，消除设备本地时间误差 |
| **自动点击引擎** | AccessibilityService 监听 B 站 App 界面，自动识别并点击购买按钮 |
| **下单流程编排** | 自动完成"选票价 → 确认 → 提交订单"全链路点击 |
| **可配置策略** | 支持设置目标时间、点击间隔、重复次数 |
| **权限引导** | 悬浮窗权限 + 无障碍服务权限 + 通知权限的一键引导 |

### 1.3 技术选型

| 层面 | 技术 | 理由 |
|------|------|------|
| UI 框架 | Jetpack Compose | 声明式 UI，开发效率高 |
| 自动点击 | AccessibilityService | Android 系统级无障碍服务，免 Root |
| 悬浮窗 | WindowManager + Foreground Service | 系统级窗口方案，保活能力强 |
| 异步处理 | Kotlin Coroutines | 轻量级协程，适合定时调度 |
| NTP 对时 | Apache Commons Net | 成熟的 NTP 客户端实现 |
| 本地存储 | DataStore (Preferences) | 配置数据持久化 |
| 通信机制 | LocalBroadcastManager | 悬浮窗 Service ↔ 无障碍 Service 双向通信 |

---

## 2. 技术架构设计

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                     B站抢票悬浮窗 App                      │
│                                                          │
│  ┌──────────────┐   ┌──────────────┐  ┌───────────────┐ │
│  │  Compose UI  │   │   悬浮窗      │  │  无障碍服务    │ │
│  │  MainActivity│   │   Service     │  │  (点击引擎)   │ │
│  │              │   │              │  │               │ │
│  │ · 首页配置    │   │ · 倒计时面板  │  │ · 监听窗口变化 │ │
│  │ · 权限引导    │←─→│ · 开始/停止   │←→│ · 查找购买按钮 │ │
│  │ · 设置       │   │ · NTP时间显示 │  │ · 模拟点击    │ │
│  └──────────────┘   └──────────────┘  └───────────────┘ │
│         │                  │                  │          │
│         └──────────┬───────┴──────────────────┘          │
│                    │                                     │
│           ┌────────┴────────┐                            │
│           │  NTP 对时模块    │                            │
│           │  + 定时调度器    │                            │
│           └─────────────────┘                            │
│                                                          │
└─────────────────────────────────────────────────────────┘
          │
          ▼ (悬浮窗覆盖在B站App上方)
┌─────────────────────────────────────────────────────────┐
│                  B站 App (用户已登录)                      │
│                                                          │
│   购票详情页 → 选票 → 确认订单 → 支付                      │
│   (所有风控/登录/验证码由B站App自身处理)                    │
└─────────────────────────────────────────────────────────┘
```

### 2.2 抢票核心流程

```
用户操作                          App内部处理
─────────                        ──────────
打开B站App
进入购票详情页
  ↓
切回我们的App
配置目标开售时间
点击"开始抢票"
  ↓                              ┌─ NTP对时，计算时间偏移
  │                              ├─ 启动悬浮窗Service
  │                              ├─ 启动定时调度器
  │                              └─ 悬浮窗显示倒计时
  ↓
切回B站购票页
  ↓                              ┌─ 倒计时中...
  │                              ├─ 无障碍服务监听窗口
  │                              └─ 等待目标时间到达
  ↓
倒计时归零
  ↓                              ┌─ 无障碍服务触发
  │                              ├─ 查找"立即购买"按钮
  │                              ├─ performAction(ACTION_CLICK)
  │                              ├─ 监听页面跳转
  │                              ├─ 查找"确认"按钮 → 点击
  │                              ├─ 监听页面跳转
  │                              └─ 查找"提交订单" → 点击
  ↓
B站App弹出支付页面
用户手动完成支付
```

### 2.3 Java / Kotlin 分工

| 层面 | 语言 | 理由 |
|------|------|------|
| AccessibilityService | Java | 事件回调风格更适合 Java，与 Android 原生 API 对齐 |
| 悬浮窗 Service | Kotlin | 协程管理倒计时，代码更简洁 |
| NTP 对时 | Java | Apache Commons Net 原生 Java 库 |
| Compose UI | Kotlin | Compose 仅支持 Kotlin |
| 数据模型 / Preferences | Kotlin | data class 简洁 |
| 定时调度器 | Kotlin | 协程 `delay` 精确控制 |

---

## 3. 项目配置

### 3.1 build.gradle.kts (app 模块)

基于你现有的 `app/build.gradle.kts`，只需确认以下依赖：

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.an_biliticketsbuy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.an_biliticketsbuy"
        minSdk = 24          // AccessibilityService 需要最低 API 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // DataStore (配置持久化)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // NTP 对时
    implementation("commons-net:commons-net:3.11.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

> **注意**：不再需要 OkHttp、Retrofit、kotlinx.serialization 等网络库，整个 App 不发任何 HTTP 请求到 B 站。

### 3.2 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- NTP 对时需要网络 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 悬浮窗权限 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <!-- 前台 Service（悬浮窗保活） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- 查询 B站 App 包名（Android 11+ 包可见性） -->
    <queries>
        <package android:name="tv.danmaku.bili" />
    </queries>

    <application
        android:name=".App"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.AnBiliTicketsBuy"
        tools:targetApi="31">

        <!-- 主界面 -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AnBiliTicketsBuy">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 悬浮窗前台 Service -->
        <service
            android:name=".service.FloatingWindowService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Floating control panel for ticket purchasing assistance" />
        </service>

        <!-- 无障碍服务 -->
        <service
            android:name=".service.TicketAccessibilityService"
            android:exported="true"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

### 3.3 无障碍服务配置 XML

新建 `app/src/main/res/xml/accessibility_service_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewClicked"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_desc"
    android:notificationTimeout="50"
    android:packageNames="tv.danmaku.bili" />
```

**关键参数说明**：

| 参数 | 值 | 作用 |
|------|-----|------|
| `accessibilityEventTypes` | 窗口状态变化 + 内容变化 + 点击事件 | 监听页面跳转和按钮出现 |
| `accessibilityFlags` | `flagReportViewIds` | 获取控件 ID（精确定位按钮） |
| `canRetrieveWindowContent` | `true` | 允许读取界面节点树 |
| `canPerformGestures` | `true` | 允许执行手势（坐标点击） |
| `packageNames` | `tv.danmaku.bili` | 只监听 B 站 App，减少不必要的事件回调 |
| `notificationTimeout` | `50ms` | 事件节流间隔，太低会卡，太高会漏 |

---

## 4. 项目结构规划

```
app/src/main/
├── AndroidManifest.xml
├── res/
│   ├── xml/
│   │   └── accessibility_service_config.xml   ← 无障碍配置
│   ├── drawable/
│   │   └── ic_float_panel.xml                 ← 悬浮窗图标
│   └── values/
│       └── strings.xml
├── java/com/example/an_biliticketsbuy/
│   ├── App.kt                                  ← Application 类
│   ├── MainActivity.kt                         ← Compose 入口
│   │
│   ├── service/
│   │   ├── FloatingWindowService.kt            ← 悬浮窗 Service (Kotlin)
│   │   └── TicketAccessibilityService.java     ← 无障碍点击引擎 (Java)
│   │
│   ├── accessibility/
│   │   ├── BiliButtonFinder.java               ← B站按钮查找器 (Java)
│   │   ├── ClickExecutor.java                  ← 点击执行器 (Java)
│   │   ├── OrderFlowController.java            ← 下单流程编排 (Java)
│   │   └── BiliViewIds.java                    ← B站控件ID常量表 (Java)
│   │
│   ├── ntp/
│   │   ├── NtpTimeSync.java                    ← NTP 对时 (Java)
│   │   └── TimeOffsetHolder.kt                 ← 时间偏移持有者 (Kotlin)
│   │
│   ├── schedule/
│   │   └── TicketScheduler.kt                  ← 定时调度器 (Kotlin)
│   │
│   ├── prefs/
│   │   └── AppPreferences.kt                   ← DataStore 配置 (Kotlin)
│   │
│   ├── comm/
│   │   └── ActionBus.kt                        ← Service间通信 (Kotlin)
│   │
│   └── ui/
│       ├── theme/
│       │   ├── Color.kt
│       │   ├── Theme.kt
│       │   └── Type.kt
│       └── screens/
│           ├── HomeScreen.kt                   ← 首页
│           ├── PermissionGuideScreen.kt        ← 权限引导页
│           └── SettingsScreen.kt               ← 设置页
└── ...
```

---

## 5. 无障碍服务核心实现

> 这是整个 App 最核心的模块。负责监听 B 站 App 界面变化，在正确的时间点击正确的按钮。

### 5.1 B站控件 ID 常量表

> ⚠️ B 站 App 更新后控件 ID 可能变化，需用 Layout Inspector 重新抓取。

```java
// accessibility/BiliViewIds.java
package com.example.an_biliticketsbuy.accessibility;

/**
 * B站 App 会员购页面控件 ID 常量表
 *
 * 获取方式：
 * 1. 手机连接电脑，打开 B 站 App 进入购票页
 * 2. Android Studio → Tools → Layout Inspector
 * 3. 选择 B 站进程，在界面树中找到目标按钮的 resource-id
 * 4. resource-id 格式通常为 "tv.danmaku.bili:id/xxx"，取 "/" 后面的部分
 */
public final class BiliViewIds {

    private BiliViewIds() {}

    // ====== 购票详情页 ======
    /** "立即购买" 按钮 */
    public static final String BTN_BUY_NOW = "buy_button";

    /** "选择场次" 区域 */
    public static final String LAYOUT_SESSION_SELECT = "session_select_layout";

    // ====== 选票弹窗 ======
    /** 票档选择项（RecyclerView 中的 item） */
    public static final String ITEM_TICKET_TYPE = "ticket_type_item";

    /** 选票弹窗中的"确认"按钮 */
    public static final String BTN_CONFIRM_TICKET = "confirm_button";

    // ====== 确认订单页 ======
    /** "提交订单" / "立即支付" 按钮 */
    public static final String BTN_SUBMIT_ORDER = "submit_order_button";

    /** 观演人选择区域 */
    public static final String LAYOUT_AUDIENCE_SELECT = "audience_select_layout";

    // ====== 通用 ======
    /** B站会员购主 Activity 的包名前缀 */
    public static final String BILI_PACKAGE = "tv.danmaku.bili";

    /** 会员购页面的 Activity 名（可能含子包路径） */
    public static final String[] TICKET_ACTIVITIES = {
        "tv.danmaku.bili.ui.bangumi.PayActivity",
        "tv.danmaku.bili.ui.ticket.TicketDetailActivity",
        "tv.danmaku.bili.ui.show.TicketActivity"
    };
}
```

### 5.2 B站按钮查找器

```java
// accessibility/BiliButtonFinder.java
package com.example.an_biliticketsbuy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 B 站 App 的界面节点树中查找目标按钮
 */
public class BiliButtonFinder {

    /**
     * 通过 View ID 查找节点
     * 最精确，优先使用
     */
    public static AccessibilityNodeInfo findById(AccessibilityService service, String viewId) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;

        String fullId = BiliViewIds.BILI_PACKAGE + ":id/" + viewId;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.get(0);
        }
        return null;
    }

    /**
     * 通过文本内容查找节点
     * ID 失效时的后备方案
     *
     * @param service 无障碍服务实例
     * @param texts   要匹配的文本列表（如 "立即购买", "立即购票"）
     */
    public static AccessibilityNodeInfo findByText(AccessibilityService service, String... texts) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;

        for (String text : texts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                // 找到文本后，需要向上查找可点击的父节点
                for (AccessibilityNodeInfo node : nodes) {
                    AccessibilityNodeInfo clickable = findClickableParent(node);
                    if (clickable != null) return clickable;
                }
            }
        }
        return null;
    }

    /**
     * 从当前节点向上查找最近的可点击父节点
     */
    private static AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 检查当前活动窗口是否属于 B 站 App
     */
    public static boolean isBiliAppForeground(AccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.isActive() && window.getRoot() != null) {
                CharSequence packageName = window.getRoot().getPackageName();
                if (BiliViewIds.BILI_PACKAGE.equals(String.valueOf(packageName))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取当前页面上所有可点击的节点（用于调试和坐标点击模式）
     */
    public static List<AccessibilityNodeInfo> findAllClickableNodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return result;
        traverseClickable(root, result);
        return result;
    }

    private static void traverseClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            traverseClickable(node.getChild(i), out);
        }
    }
}
```

### 5.3 点击执行器

```java
// accessibility/ClickExecutor.java
package com.example.an_biliticketsbuy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 点击执行器 —— 两种点击方式：
 * 1. 节点点击：通过 AccessibilityNodeInfo.performAction(ACTION_CLICK)
 *    优点：精确，不依赖坐标
 *    缺点：部分自定义 View 不响应 ACTION_CLICK
 *
 * 2. 手势点击：通过 GestureDescription 在坐标位置模拟手指点击
 *    优点：兼容所有控件，等同于真实手指点击
 *    缺点：需要知道坐标，屏幕分辨率变化时需重新定位
 */
public class ClickExecutor {

    private static final String TAG = "ClickExecutor";
    private final AccessibilityService service;

    public ClickExecutor(AccessibilityService service) {
        this.service = service;
    }

    /**
     * 方式一：节点点击
     * @return true 表示点击动作已派发
     */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 先尝试直接点击
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Node click succeeded (direct)");
            return true;
        }

        // 直接点击失败，尝试点击父节点
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Node click succeeded (parent)");
                return true;
            }
            parent = parent.getParent();
        }

        Log.w(TAG, "Node click failed, falling back to gesture");
        return false;
    }

    /**
     * 方式二：手势点击（坐标点击）
     * 使用 GestureDescription API，最低支持 API 24 (Android 7.0)
     */
    public boolean clickCoordinate(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture click requires API 24+");
            return false;
        }

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 50); // 50ms 点击时长
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        return service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Gesture click completed at (" + x + ", " + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Gesture click cancelled");
            }
        }, null);
    }

    /**
     * 智能点击：先尝试节点点击，失败则用节点坐标做手势点击
     */
    public boolean smartClick(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 方式一：节点点击
        if (clickNode(node)) {
            return true;
        }

        // 方式二：获取节点坐标，手势点击
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        float centerX = rect.centerX();
        float centerY = rect.centerY();

        return clickCoordinate(centerX, centerY);
    }

    /**
     * 快速连续点击（用于抢票场景的高频点击）
     * @param node     目标节点
     * @param times    点击次数
     * @param interval 每次点击间隔（毫秒）
     */
    public void rapidClick(AccessibilityNodeInfo node, int times, long interval) {
        new Thread(() -> {
            for (int i = 0; i < times; i++) {
                boolean success = smartClick(node);
                Log.d(TAG, "Rapid click #" + (i + 1) + ": " + success);
                if (!success) break;
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }
}
```

### 5.4 下单流程编排

```java
// accessibility/OrderFlowController.java
package com.example.an_biliticketsbuy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下单流程编排器
 *
 * B站会员购抢票的完整流程：
 * 1. 购票详情页 → 点击"立即购买"
 * 2. 选票弹窗 → 选择票档 → 点击"确认"
 * 3. 确认订单页 → 点击"提交订单" / "立即支付"
 * 4. 支付页面 → 交给用户手动处理
 *
 * 每一步之间需要等待页面加载，通过状态机管理
 */
public class OrderFlowController {

    private static final String TAG = "OrderFlow";

    // 流程状态
    public enum FlowState {
        IDLE,               // 空闲
        WAITING_FOR_START,  // 等待开售时间
        CLICKING_BUY,       // 正在点击"立即购买"
        SELECTING_TICKET,   // 正在选择票档
        CONFIRMING_TICKET,  // 正在确认选票
        SUBMITTING_ORDER,   // 正在提交订单
        WAITING_PAYMENT,    // 等待用户支付
        COMPLETED,          // 完成
        FAILED              // 失败
    }

    private final AccessibilityService service;
    private final ClickExecutor clickExecutor;
    private final Handler handler;
    private final AtomicBoolean isActive = new AtomicBoolean(false);

    private volatile FlowState currentState = FlowState.IDLE;
    private long targetTimeMillis = 0;      // 目标开售时间
    private long clickInterval = 100;       // 点击间隔 ms
    private int maxRetryCount = 50;         // 最大重试次数

    // 按钮文本（B站不同版本可能文本不同，全部列出提高兼容性）
    private static final String[] BUY_BUTTON_TEXTS = {"立即购买", "立即购票", "选座购票"};
    private static final String[] CONFIRM_TEXTS = {"确认", "确定"};
    private static final String[] SUBMIT_TEXTS = {"提交订单", "立即支付", "确认订单"};

    public OrderFlowController(AccessibilityService service) {
        this.service = service;
        this.clickExecutor = new ClickExecutor(service);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 配置抢票参数
     */
    public void configure(long targetTimeMillis, long clickInterval, int maxRetry) {
        this.targetTimeMillis = targetTimeMillis;
        this.clickInterval = clickInterval;
        this.maxRetryCount = maxRetry;
    }

    /**
     * 启动抢票流程
     */
    public void start() {
        if (!isActive.compareAndSet(false, true)) return;
        currentState = FlowState.WAITING_FOR_START;
        Log.i(TAG, "Order flow started. Target time: " + targetTimeMillis);
        waitForTargetTime();
    }

    /**
     * 停止抢票流程
     */
    public void stop() {
        isActive.set(false);
        currentState = FlowState.IDLE;
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Order flow stopped");
    }

    public FlowState getCurrentState() {
        return currentState;
    }

    public boolean isActive() {
        return isActive.get();
    }

    // ====== 内部流程实现 ======

    /**
     * 等待目标时间到达
     * 使用轮询而非 sleep，确保能随时停止
     */
    private void waitForTargetTime() {
        if (!isActive.get()) return;
        currentState = FlowState.WAITING_FOR_START;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isActive.get()) return;

                long now = System.currentTimeMillis();
                if (now >= targetTimeMillis) {
                    Log.i(TAG, "Target time reached, starting click sequence");
                    startClickingBuyButton();
                } else {
                    // 距离目标时间还有 > 2秒时，每 500ms 检查一次
                    // 距离 < 2秒时，每 50ms 检查一次（精度需求）
                    long remaining = targetTimeMillis - now;
                    long delay = remaining > 2000 ? 500 : 50;
                    handler.postDelayed(this, delay);
                }
            }
        }, 500);
    }

    /**
     * 阶段 1：点击"立即购买"
     */
    private void startClickingBuyButton() {
        if (!isActive.get()) return;
        currentState = FlowState.CLICKING_BUY;
        Log.d(TAG, "Phase 1: Clicking BUY button");

        attemptClick(BiliViewIds.BTN_BUY_NOW, BUY_BUTTON_TEXTS, 0);
    }

    /**
     * 阶段 2：选择票档并确认
     */
    private void selectTicket() {
        if (!isActive.get()) return;
        currentState = FlowState.SELECTING_TICKET;
        Log.d(TAG, "Phase 2: Selecting ticket type");

        // 如果有票档选择，先选择第一个可用票档
        AccessibilityNodeInfo ticketItem = BiliButtonFinder.findById(service, BiliViewIds.ITEM_TICKET_TYPE);
        if (ticketItem != null) {
            clickExecutor.smartClick(ticketItem);
        }

        // 然后点击"确认"按钮
        handler.postDelayed(() -> {
            attemptClick(BiliViewIds.BTN_CONFIRM_TICKET, CONFIRM_TEXTS, 0);
        }, 200); // 等 200ms 让选票动画完成
    }

    /**
     * 阶段 3：提交订单
     */
    private void submitOrder() {
        if (!isActive.get()) return;
        currentState = FlowState.SUBMITTING_ORDER;
        Log.d(TAG, "Phase 3: Submitting order");

        attemptClick(BiliViewIds.BTN_SUBMIT_ORDER, SUBMIT_TEXTS, 0);
    }

    /**
     * 核心点击尝试逻辑
     * 先用 View ID 查找，失败则用文本查找，找到就点，没找到就重试
     *
     * @param viewId    控件 ID
     * @param textTexts 后备文本列表
     * @param attempt   当前重试次数
     */
    private void attemptClick(String viewId, String[] textTexts, int attempt) {
        if (!isActive.get() || attempt >= maxRetryCount) {
            if (attempt >= maxRetryCount) {
                Log.w(TAG, "Max retries reached for " + viewId);
                currentState = FlowState.FAILED;
            }
            return;
        }

        // 方式一：通过 View ID 查找
        AccessibilityNodeInfo node = BiliButtonFinder.findById(service, viewId);

        // 方式二：通过文本查找
        if (node == null) {
            node = BiliButtonFinder.findByText(service, textTexts);
        }

        if (node != null) {
            boolean clicked = clickExecutor.smartClick(node);
            if (clicked) {
                Log.d(TAG, "Click succeeded on attempt " + (attempt + 1));
                // 点击成功后，等待页面跳转，进入下一阶段
                handler.postDelayed(this::advanceToNextPhase, 500);
                return;
            }
        }

        // 未找到或点击失败，间隔后重试
        handler.postDelayed(() -> {
            attemptClick(viewId, textTexts, attempt + 1);
        }, clickInterval);
    }

    /**
     * 推进到下一阶段
     * 根据当前状态判断应该进入哪个阶段
     */
    private void advanceToNextPhase() {
        if (!isActive.get()) return;

        switch (currentState) {
            case CLICKING_BUY:
                // "立即购买"点击成功后，进入选票阶段
                selectTicket();
                break;
            case SELECTING_TICKET:
            case CONFIRMING_TICKET:
                // 选票确认后，进入提交订单阶段
                submitOrder();
                break;
            case SUBMITTING_ORDER:
                // 订单提交成功，等待支付
                currentState = FlowState.WAITING_PAYMENT;
                Log.i(TAG, "Order submitted! Waiting for user payment.");
                isActive.set(false);
                break;
            default:
                break;
        }
    }

    /**
     * 由 AccessibilityService 的 onAccessibilityEvent 回调调用
     * 用于检测页面跳转，辅助状态推进
     */
    public void onEvent(AccessibilityEvent event) {
        if (!isActive.get()) return;

        // 检测窗口状态变化（页面跳转）
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            CharSequence packageName = event.getPackageName();
            Log.d(TAG, "Window changed: " + packageName + " / " + className);

            // 可根据 Activity 类名判断当前处于哪个页面
            // 主动触发对应阶段的点击
        }
    }
}
```

### 5.5 无障碍服务主类

```java
// service/TicketAccessibilityService.java
package com.example.an_biliticketsbuy.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.an_biliticketsbuy.accessibility.OrderFlowController;
import com.example.an_biliticketsbuy.comm.ActionBus;

/**
 * B站抢票无障碍服务
 *
 * 职责：
 * 1. 监听 B 站 App 界面事件
 * 2. 接收悬浮窗发出的开始/停止指令
 * 3. 驱动 OrderFlowController 完成抢票流程
 */
public class TicketAccessibilityService extends AccessibilityService {

    private static final String TAG = "TicketA11yService";

    private static TicketAccessibilityService instance;
    private OrderFlowController flowController;

    /**
     * 获取当前服务实例（供外部调用）
     */
    public static TicketAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected");

        instance = this;
        flowController = new OrderFlowController(this);

        // 动态配置服务参数（补充 XML 配置）
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (flowController != null && flowController.isActive()) {
            flowController.onEvent(event);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
        if (flowController != null) {
            flowController.stop();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Accessibility service unbound");
        instance = null;
        return super.onUnbind(intent);
    }

    // ====== 对外接口 ======

    /**
     * 启动抢票
     * @param targetTimeMillis 目标开售时间（时间戳）
     * @param clickInterval    点击间隔（毫秒）
     * @param maxRetry         最大重试次数
     */
    public void startTicketGrabbing(long targetTimeMillis, long clickInterval, int maxRetry) {
        if (flowController == null) {
            flowController = new OrderFlowController(this);
        }
        flowController.configure(targetTimeMillis, clickInterval, maxRetry);
        flowController.start();
        Log.i(TAG, "Ticket grabbing started");
    }

    /**
     * 停止抢票
     */
    public void stopTicketGrabbing() {
        if (flowController != null) {
            flowController.stop();
        }
        Log.i(TAG, "Ticket grabbing stopped");
    }

    /**
     * 获取当前流程状态
     */
    public OrderFlowController.FlowState getFlowState() {
        return flowController != null ? flowController.getCurrentState()
                : OrderFlowController.FlowState.IDLE;
    }

    /**
     * 检查无障碍服务是否已启用
     * 静态方法，供 UI 层调用检查权限
     */
    public static boolean isServiceEnabled() {
        return instance != null;
    }
}
```

---

## 6. 悬浮窗服务

> 悬浮窗是用户在 B 站 App 上方看到的控制面板，显示倒计时和操作按钮。

### 6.1 悬浮窗 Service 完整实现

```kotlin
// service/FloatingWindowService.kt
package com.example.an_biliticketsbuy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.an_biliticketsbuy.R
import com.example.an_biliticketsbuy.accessibility.OrderFlowController
import com.example.an_biliticketsbuy.comm.ActionBus
import com.example.an_biliticketsbuy.ntp.TimeOffsetHolder
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatWindowService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "float_window_channel"

        const val ACTION_START = "com.example.an_biliticketsbuy.START_FLOAT"
        const val ACTION_STOP = "com.example.an_biliticketsbuy.STOP_FLOAT"
        const val EXTRA_TARGET_TIME = "target_time"
        const val EXTRA_CLICK_INTERVAL = "click_interval"
        const val EXTRA_MAX_RETRY = "max_retry"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var tvCountdown: TextView
    private lateinit var tvNtpTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: TextView
    private lateinit var btnStop: TextView

    private var targetTimeMillis: Long = 0
    private var clickInterval: Long = 100
    private var maxRetry: Int = 50

    private var countdownJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("悬浮窗运行中"))
        createFloatingWindow()
        registerReceiver(actionReceiver, IntentFilter().apply {
            addAction(ActionBus.ACTION_START_GRAB)
            addAction(ActionBus.ACTION_STOP_GRAB)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    targetTimeMillis = it.getLongExtra(EXTRA_TARGET_TIME, 0)
                    clickInterval = it.getLongExtra(EXTRA_CLICK_INTERVAL, 100)
                    maxRetry = it.getIntExtra(EXTRA_MAX_RETRY, 50)
                    Log.i(TAG, "Received start: target=$targetTimeMillis, interval=$clickInterval")
                    showFloatingView()
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    // ====== 悬浮窗创建 ======

    private fun createFloatingWindow() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_panel, null)

        // 初始化控件引用
        floatingView?.let { view ->
            tvCountdown = view.findViewById(R.id.tv_countdown)
            tvNtpTime = view.findViewById(R.id.tv_ntp_time)
            tvStatus = view.findViewById(R.id.tv_status)
            btnStart = view.findViewById(R.id.btn_start)
            btnStop = view.findViewById(R.id.btn_stop)

            btnStart.setOnClickListener { startGrabbing() }
            btnStop.setOnClickListener { stopGrabbing() }

            setupDrag(view)
        }
    }

    private fun showFloatingView() {
        if (floatingView?.parent == null && layoutParams != null) {
            windowManager.addView(floatingView, layoutParams)
            startCountdown()
        }
    }

    private fun hideFloatingView() {
        floatingView?.let {
            if (it.parent != null) {
                windowManager.removeView(it)
            }
        }
        countdownJob?.cancel()
    }

    // ====== 拖拽实现 ======

    private fun setupDrag(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx > 5 || dy > 5) isDragging = true
                    layoutParams!!.x = initialX + dx.toInt()
                    layoutParams!!.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 边缘吸附
                    val screenWidth = resources.displayMetrics.widthPixels
                    layoutParams!!.x = if (layoutParams!!.x < screenWidth / 2) 0 else screenWidth - floatingView!!.width
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    !isDragging // 如果没拖动，传播点击事件
                }
                else -> false
            }
        }
    }

    // ====== 倒计时 ======

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
            while (isActive) {
                val ntpNow = System.currentTimeMillis() + TimeOffsetHolder.getOffset()
                val remaining = targetTimeMillis - ntpNow

                if (remaining <= 0) {
                    tvCountdown.text = "00:00:00.000"
                    tvStatus.text = "等待点击..."
                    break
                }

                val hours = remaining / 3600000
                val minutes = (remaining % 3600000) / 60000
                val seconds = (remaining % 60000) / 1000
                val millis = remaining % 1000

                tvCountdown.text = String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
                tvNtpTime.text = "NTP: ${sdf.format(Date(ntpNow))}"

                // 距离目标 > 10秒时每 500ms 刷新，< 10秒时每 50ms 刷新（提高精度）
                delay(if (remaining > 10000) 500 else 50)
            }
        }
    }

    // ====== 抢票控制 ======

    private fun startGrabbing() {
        val a11yService = TicketAccessibilityService.getInstance()
        if (a11yService == null) {
            tvStatus.text = "请先开启无障碍服务"
            return
        }

        // 使用 NTP 校准后的时间
        val ntpTargetTime = targetTimeMillis
        a11yService.startTicketGrabbing(ntpTargetTime, clickInterval, maxRetry)
        tvStatus.text = "抢票中..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        // 广播通知 UI
        sendBroadcast(Intent(ActionBus.ACTION_GRAB_STARTED))
    }

    private fun stopGrabbing() {
        TicketAccessibilityService.getInstance()?.stopTicketGrabbing()
        tvStatus.text = "已停止"
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        sendBroadcast(Intent(ActionBus.ACTION_GRAB_STOPPED))
    }

    // ====== 广播接收 ======

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ActionBus.ACTION_START_GRAB -> startGrabbing()
                ActionBus.ACTION_STOP_GRAB -> stopGrabbing()
            }
        }
    }

    // ====== 前台通知 ======

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持悬浮窗运行"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("B站抢票助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_float_panel)
            .setOngoing(true)
            .build()
    }

    // ====== 生命周期 ======

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingView()
        unregisterReceiver(actionReceiver)
        serviceScope.cancel()
        Log.i(TAG, "Floating window service destroyed")
    }
}
```

### 6.2 悬浮窗布局 XML

新建 `app/src/main/res/layout/layout_floating_panel.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="#E6000000"
    android:padding="12dp"
    android:layout_margin="8dp">

    <!-- 倒计时 -->
    <TextView
        android:id="@+id/tv_countdown"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="00:00:00.000"
        android:textColor="#00FF00"
        android:textSize="22sp"
        android:textStyle="bold"
        android:fontFamily="monospace" />

    <!-- NTP 时间 -->
    <TextView
        android:id="@+id/tv_ntp_time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="NTP: --:--:--.---"
        android:textColor="#AAAAAA"
        android:textSize="11sp"
        android:layout_marginTop="4dp" />

    <!-- 状态 -->
    <TextView
        android:id="@+id/tv_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="待机中"
        android:textColor="#FFFFFF"
        android:textSize="13sp"
        android:layout_marginTop="6dp" />

    <!-- 按钮行 -->
    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="8dp">

        <TextView
            android:id="@+id/btn_start"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="开始"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:background="#FF00A1D6"
            android:paddingStart="20dp"
            android:paddingEnd="20dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp" />

        <TextView
            android:id="@+id/btn_stop"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="停止"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:background="#FFFF4444"
            android:paddingStart="20dp"
            android:paddingEnd="20dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:layout_marginStart="8dp"
            android:enabled="false" />

    </LinearLayout>

</LinearLayout>
```

---

## 7. 主界面 UI（Compose）

### 7.1 通信总线

```kotlin
// comm/ActionBus.kt
package com.example.an_biliticketsbuy.comm

/**
 * Service 间通信的 Action 常量
 * 使用 LocalBroadcastManager 或标准 Broadcast 进行通信
 */
object ActionBus {
    const val ACTION_START_GRAB = "com.example.an_biliticketsbuy.START_GRAB"
    const val ACTION_STOP_GRAB = "com.example.an_biliticketsbuy.STOP_GRAB"
    const val ACTION_GRAB_STARTED = "com.example.an_biliticketsbuy.GRAB_STARTED"
    const val ACTION_GRAB_STOPPED = "com.example.an_biliticketsbuy.GRAB_STOPPED"
    const val ACTION_STATE_UPDATE = "com.example.an_biliticketsbuy.STATE_UPDATE"
    const val EXTRA_STATE = "state"
}
```

### 7.2 首页

```kotlin
// ui/screens/HomeScreen.kt
package com.example.an_biliticketsbuy.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.an_biliticketsbuy.service.FloatingWindowService
import com.example.an_biliticketsbuy.service.TicketAccessibilityService
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current

    var targetDate by remember { mutableStateOf("") }
    var targetTime by remember { mutableStateOf("") }
    var clickInterval by remember { mutableStateOf("100") }
    var maxRetry by remember { mutableStateOf("50") }

    val accessibilityEnabled = remember { mutableStateOf(TicketAccessibilityService.isServiceEnabled()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "B站抢票助手",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
        )

        Text(
            text = "悬浮窗 + 屏幕点击方案",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 权限状态卡片
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                PermissionStatusRow("无障碍服务", accessibilityEnabled.value)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (!accessibilityEnabled.value) "点击下方按钮开启" else "已开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityEnabled.value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }
        }

        // 目标时间设置
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("开售时间", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = targetDate,
                    onValueChange = { targetDate = it },
                    label = { Text("日期 (yyyy-MM-dd)") },
                    placeholder = { Text("2025-07-10") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = targetTime,
                    onValueChange = { targetTime = it },
                    label = { Text("时间 (HH:mm:ss)") },
                    placeholder = { Text("20:00:00") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 抢票参数
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("抢票参数", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = clickInterval,
                        onValueChange = { clickInterval = it },
                        label = { Text("点击间隔(ms)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxRetry,
                        onValueChange = { maxRetry = it },
                        label = { Text("最大重试") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 启动按钮
        Button(
            onClick = {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                val targetTimeMillis = sdf.parse("$targetDate $targetTime")?.time ?: 0L
                val interval = clickInterval.toLongOrNull() ?: 100L
                val retry = maxRetry.toIntOrNull() ?: 50

                val intent = Intent(context, FloatingWindowService::class.java).apply {
                    action = FloatingWindowService.ACTION_START
                    putExtra(FloatingWindowService.EXTRA_TARGET_TIME, targetTimeMillis)
                    putExtra(FloatingWindowService.EXTRA_CLICK_INTERVAL, interval)
                    putExtra(FloatingWindowService.EXTRA_MAX_RETRY, retry)
                }
                context.startForegroundService(intent)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("启动悬浮窗", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 权限引导按钮
        OutlinedButton(
            onClick = onNavigateToPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("权限引导")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onNavigateToSettings) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("设置")
        }
    }
}
```

### 7.3 权限引导页

```kotlin
// ui/screens/PermissionGuideScreen.kt
package com.example.an_biliticketsbuy.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun PermissionGuideScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "权限引导",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 24.dp)
        )

        // 1. 悬浮窗权限
        PermissionCard(
            title = "1. 悬浮窗权限",
            description = "允许 App 在其他应用上方显示悬浮控制面板",
            buttonText = "去开启",
            onButtonClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. 无障碍服务权限
        PermissionCard(
            title = "2. 无障碍服务（核心）",
            description = "开启后 App 才能自动点击 B 站 App 的购买按钮。\n" +
                    "路径：设置 → 无障碍 → 已安装的服务 → B站抢票助手 → 开启",
            buttonText = "去开启",
            onButtonClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                title = "3. 通知权限",
                description = "允许显示前台服务通知，维持悬浮窗保活",
                buttonText = "去开启",
                onButtonClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用步骤", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "1. 开启以上全部权限\n" +
                    "2. 打开 B 站 App，进入购票详情页\n" +
                    "3. 回到本 App，设置开售时间\n" +
                    "4. 点击"启动悬浮窗"\n" +
                    "5. 切回 B 站 App，等待自动点击",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onButtonClick, modifier = Modifier.align(Alignment.End)) {
                Text(buttonText)
            }
        }
    }
}
```

### 7.4 MainActivity

```kotlin
// MainActivity.kt
package com.example.an_biliticketsbuy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.an_biliticketsbuy.ui.screens.HomeScreen
import com.example.an_biliticketsbuy.ui.screens.PermissionGuideScreen
import com.example.an_biliticketsbuy.ui.screens.SettingsScreen
import com.example.an_biliticketsbuy.ui.theme.AnBiliTicketsBuyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnBiliTicketsBuyTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onNavigateToPermissions = { navController.navigate("permissions") },
                    onNavigateToSettings = { navController.navigate("settings") }
                )
            }
            composable("permissions") {
                PermissionGuideScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
```

---

## 8. 权限处理流程

### 8.1 权限检查清单

```
┌─────────────────────────────────────────────────┐
│              App 启动时权限检查                    │
├─────────────────────────────────────────────────┤
│                                                  │
│  ① 悬浮窗权限 (SYSTEM_ALERT_WINDOW)              │
│     Settings.canDrawOverlays(context)            │
│     → false → 引导用户开启                        │
│                                                  │
│  ② 无障碍服务 (BIND_ACCESSIBILITY_SERVICE)       │
│     TicketAccessibilityService.isServiceEnabled()│
│     → false → 引导用户到无障碍设置页               │
│                                                  │
│  ③ 通知权限 (POST_NOTIFICATIONS)  [API 33+]     │
│     ContextCompat.checkSelfPermission()           │
│     → false → 请求通知权限                        │
│                                                  │
│  全部通过 → 允许启动悬浮窗                         │
│                                                  │
└─────────────────────────────────────────────────┘
```

### 8.2 权限检查工具类

```kotlin
// prefs/PermissionChecker.kt
package com.example.an_biliticketsbuy.prefs

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.an_biliticketsbuy.service.TicketAccessibilityService

object PermissionChecker {

    data class PermissionState(
        val overlayGranted: Boolean,
        val accessibilityEnabled: Boolean,
        val notificationGranted: Boolean
    ) {
        val allGranted: Boolean get() = overlayGranted && accessibilityEnabled && notificationGranted
    }

    fun check(context: Context): PermissionState {
        return PermissionState(
            overlayGranted = Settings.canDrawOverlays(context),
            accessibilityEnabled = TicketAccessibilityService.isServiceEnabled(),
            notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
}
```

### 8.3 无障碍服务自启检测

无障碍服务被系统杀死后需要用户手动重新开启。App 每次回到前台时都应检测：

```kotlin
// 在 HomeScreen 中添加
val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            accessibilityEnabled.value = TicketAccessibilityService.isServiceEnabled()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

---

## 9. NTP 对时与定时调度

### 9.1 NTP 对时模块

```java
// ntp/NtpTimeSync.java
package com.example.an_biliticketsbuy.ntp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NTP 时间同步
 *
 * 设备本地时间可能不准（差几秒甚至几十秒），
 * 在抢票场景下这会导致提前或延后点击。
 * 通过 NTP 服务器获取精确时间，计算偏移量。
 */
public class NtpTimeSync {

    private static final String TAG = "NtpTimeSync";

    // 多个 NTP 服务器，依次尝试
    private static final String[] NTP_SERVERS = {
        "ntp.aliyun.com",        // 阿里云 NTP
        "ntp.tencent.com",       // 腾讯云 NTP
        "cn.ntp.org.cn",         // 中国 NTP
        "time.windows.com",      // Windows NTP（备用）
        "pool.ntp.org"           // 国际 NTP（备用）
    };

    private static final int NTP_TIMEOUT = 5000; // 5秒超时
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface SyncCallback {
        void onSuccess(long offsetMillis);
        void onFailure(String error);
    }

    /**
     * 异步同步时间
     * @param callback 回调，在主线程执行
     */
    public void sync(SyncCallback callback) {
        executor.execute(() -> {
            NTPUDPClient client = new NTPUDPClient();
            client.setDefaultTimeout(NTP_TIMEOUT);

            for (String server : NTP_SERVERS) {
                try {
                    client.open();
                    Log.d(TAG, "Trying NTP server: " + server);
                    InetAddress address = InetAddress.getByName(server);
                    TimeInfo timeInfo = client.getTime(address);
                    timeInfo.computeDetails();

                    Long offset = timeInfo.getOffset();
                    if (offset != null) {
                        long offsetMillis = offset;
                        Log.i(TAG, "NTP sync success: " + server +
                              ", offset=" + offsetMillis + "ms");
                        TimeOffsetHolder.setOffset(offsetMillis);
                        mainHandler.post(() -> callback.onSuccess(offsetMillis));
                        client.close();
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "NTP server failed: " + server + " - " + e.getMessage());
                } finally {
                    try { client.close(); } catch (Exception ignored) {}
                }
            }

            mainHandler.post(() -> callback.onFailure("所有 NTP 服务器均不可达"));
        });
    }

    /**
     * 获取校准后的当前时间
     */
    public static long getNtpTime() {
        return System.currentTimeMillis() + TimeOffsetHolder.getOffset();
    }
}
```

### 9.2 时间偏移持有者

```kotlin
// ntp/TimeOffsetHolder.kt
package com.example.an_biliticketsbuy.ntp

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局时间偏移持有者
 * NTP 同步后写入，倒计时和定时调度时读取
 */
object TimeOffsetHolder {
    private val offset = AtomicLong(0L)

    fun setOffset(offsetMillis: Long) {
        offset.set(offsetMillis)
    }

    fun getOffset(): Long = offset.get()

    /**
     * 获取校准后的当前时间戳
     */
    fun currentTimeMillis(): Long = System.currentTimeMillis() + offset.get()
}
```

### 9.3 定时调度器

```kotlin
// schedule/TicketScheduler.kt
package com.example.an_biliticketsbuy.schedule

import android.util.Log
import com.example.an_biliticketsbuy.ntp.TimeOffsetHolder
import kotlinx.coroutines.*

/**
 * 定时调度器
 *
 * 精确到毫秒级的定时触发，使用 NTP 校准时间。
 * 采用"远距离粗轮询 + 近距离细轮询"策略：
 * - 距离目标 > 10秒：每 500ms 检查
 * - 距离目标 ≤ 10秒 且 > 1秒：每 50ms 检查
 * - 距离目标 ≤ 1秒：每 10ms 检查（最后冲刺）
 */
class TicketScheduler {

    companion object {
        private const val TAG = "TicketScheduler"
    }

    private var schedulerJob: Job? = null

    /**
     * 启动定时器
     * @param targetTimeMillis 目标时间（基于 NTP 校准的时间戳）
     * @param onTick           每次检查回调（remaining: 剩余毫秒）
     * @param onTrigger        到达目标时间回调
     */
    fun start(
        targetTimeMillis: Long,
        scope: CoroutineScope,
        onTick: (Long) -> Unit,
        onTrigger: () -> Unit
    ) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Scheduler started, target: $targetTimeMillis")

            while (isActive) {
                val now = TimeOffsetHolder.currentTimeMillis()
                val remaining = targetTimeMillis - now

                if (remaining <= 0) {
                    Log.i(TAG, "Target time reached!")
                    withContext(Dispatchers.Main) { onTrigger() }
                    break
                }

                withContext(Dispatchers.Main) { onTick(remaining) }

                // 自适应轮询间隔
                val delayMs = when {
                    remaining > 10000 -> 500L
                    remaining > 1000 -> 50L
                    else -> 10L
                }
                delay(delayMs)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        Log.i(TAG, "Scheduler stopped")
    }
}
```

---

## 10. 测试与调试

### 10.1 获取 B 站控件 ID

这是开发中最关键的一步，控件 ID 不对整个 App 就废了。

**方法一：Layout Inspector（推荐）**

1. 手机连接电脑，打开 USB 调试
2. 打开 B 站 App，进入购票详情页
3. Android Studio → Tools → Layout Inspector
4. 选择 B 站进程 (`tv.danmaku.bili`)
5. 在界面快照中找到"立即购买"按钮
6. 记录其 `resource-id`（如 `tv.danmaku.bili:id/buy_button`）
7. 把 `/` 后面的部分填入 `BiliViewIds.java`

**方法二：uiautomatorviewer（SDK 自带）**

```bash
# 在 SDK tools 目录下
uiautomatorviewer
# 点击 Device Screenshot 按钮
# 在界面树中查看控件的 resource-id
```

**方法三：无障碍服务日志（App 内自检）**

在 `TicketAccessibilityService` 中添加调试日志：

```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    // 调试模式：打印当前界面所有可点击节点
    if (BuildConfig.DEBUG) {
        Log.d("A11yDebug", "Event: " + event.getEventType());
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            printNodeTree(root, 0);
        }
    }
}

private void printNodeTree(AccessibilityNodeInfo node, int depth) {
    if (node == null) return;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < depth; i++) sb.append("  ");
    sb.append("[").append(node.getClassName()).append("]")
      .append(" id=").append(node.getViewIdResourceName())
      .append(" text=").append(node.getText())
      .append(" clickable=").append(node.isClickable());
    Log.d("A11yDebug", sb.toString());
    for (int i = 0; i < node.getChildCount(); i++) {
        printNodeTree(node.getChild(i), depth + 1);
    }
}
```

### 10.2 调试清单

| 测试项 | 验证方法 |
|--------|---------|
| 悬浮窗显示 | 启动后能在 B 站 App 上方看到倒计时面板 |
| 悬浮窗拖拽 | 按住面板可拖动，松手后边缘吸附 |
| NTP 对时 | Logcat 中看到 `NTP sync success, offset=xxxms` |
| 倒计时准确 | 悬浮窗倒计时与 NTP 时间同步递减 |
| 无障碍监听 | 切到 B 站 App 时，Logcat 中看到窗口变化事件 |
| 按钮查找 | 在 B 站购票页能看到 `Node click succeeded` 日志 |
| 点击效果 | "立即购买"按钮被自动点击，页面跳转 |
| 流程编排 | 确认能依次完成 购买→确认→提交 全流程 |
| 停止功能 | 点击停止按钮后，自动点击立即停止 |

### 10.3 常见问题排查

| 问题 | 原因 | 解决 |
|------|------|------|
| 悬浮窗不显示 | 未开启悬浮窗权限 | 引导用户到设置开启 |
| 无障碍不工作 | 服务未开启 / 被系统杀死 | 重新开启无障碍服务 |
| 找不到按钮 | B 站更新导致控件 ID 变了 | 用 Layout Inspector 重新抓取 ID |
| 点击无反应 | 控件是自定义 View 不响应 ACTION_CLICK | 使用 `ClickExecutor.smartClick()` 自动回退到手势点击 |
| 倒计时不准 | NTP 同步失败 | 检查网络，确认 NTP 服务器可达 |
| 服务被杀 | 省电策略 / 后台清理 | 关闭电池优化，锁定后台 |
| MIUI/EMUI 不保活 | 国产 ROM 严格后台管理 | 加入自启动白名单 + 电池优化白名单 |

### 10.4 国产 ROM 适配建议

```
小米 MIUI:  设置 → 应用设置 → 应用管理 → 本App → 自启动 = 开
            设置 → 电池与性能 → 应用智能省电 → 无限制

华为 EMUI:  设置 → 电池 → 启动管理 → 本App → 手动管理(全部开启)
            设置 → 电池 → 更多电池设置 → 关闭"休眠时始终保持网络连接"

OPPO ColorOS: 设置 → 电池 → 应用耗电管理 → 本App → 允许后台运行

vivo OriginOS: 设置 → 电池 → 后台耗电管理 → 本App → 允许后台高耗电
```

---

## 11. 合规与注意事项

### 11.1 法律风险提示

> ⚠️ **本项目仅供学习交流使用。**

- 使用自动化工具抢票可能违反 B 站用户协议和服务条款
- B 站有权对异常操作账号进行限流、封禁等处理
- 不得用于商业倒卖、批量抢票等行为
- 用户使用本工具产生的一切后果由用户自行承担

### 11.2 技术风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| B 站 App 更新 | 控件 ID 变化，点击失效 | 提供文本后备匹配 + Layout Inspector 工具文档 |
| 无障碍被系统杀 | 抢票中断 | 前台 Service 保活 + 电池优化白名单引导 |
| B 站检测无障碍 | 可能弹验证码 | 控制点击频率，不要太快（建议 100ms+） |
| 不同手机分辨率 | 坐标点击偏移 | 优先使用节点点击，坐标点击仅作后备 |

### 11.3 开发路线图

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| P0 | 悬浮窗 + 无障碍基本功能跑通 | 必须 |
| P0 | 控件 ID 抓取 + 按钮查找 | 必须 |
| P0 | NTP 对时 + 精确倒计时 | 必须 |
| P1 | 下单全流程编排（购买→确认→提交） | 必须 |
| P1 | 国产 ROM 保活适配 | 高 |
| P2 | 票档选择（指定价位） | 中 |
| P2 | 多场次选择 | 中 |
| P3 | 观演人自动选择 | 低 |
| P3 | 优惠券自动选择 | 低 |

---

> **文档版本**：v3.0
> **最后更新**：2025-07-05
> **方案变更**：v1.0 API调用 → v2.0 WebView登录 → v3.0 屏幕点击（当前版本）
