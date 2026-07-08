package com.example.an_biliticketsbuy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 B 站 App 的界面节点树中查找目标按钮
 *
 * 使用 getWindows() 遍历所有窗口来定位 B 站的窗口根节点，
 * 而非 getRootInActiveWindow()（悬浮窗或其他窗口可能抢走 active 状态）。
 */
public class BiliButtonFinder {

    private static final String TAG = "BiliFinder";

    /**
     * 获取 B 站 App 的窗口根节点
     * 遍历所有窗口，找到 tv.danmaku.bili 的窗口
     */
    private static AccessibilityNodeInfo getBiliRoot(AccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null || windows.isEmpty()) {
            Log.i(TAG, "getBiliRoot: no windows available");
            return null;
        }

        // 优先找 B 站窗口
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (BiliViewIds.BILI_PACKAGE.equals(String.valueOf(pkg))) {
                    return root;
                }
            }
        }

        // 找不到 B 站窗口时，记录所有可见窗口帮助诊断
        StringBuilder sb = new StringBuilder("getBiliRoot: B站窗口未找到, 当前窗口列表: ");
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                sb.append("[pkg=").append(root.getPackageName())
                  .append(", active=").append(window.isActive())
                  .append(", type=").append(window.getType()).append("] ");
            }
        }
        Log.i(TAG, sb.toString());
        return null;
    }

    /**
     * 通过 View ID 查找节点
     */
    public static AccessibilityNodeInfo findById(AccessibilityService service, String viewId) {
        AccessibilityNodeInfo root = getBiliRoot(service);
        if (root == null) return null;

        String fullId = BiliViewIds.BILI_PACKAGE + ":id/" + viewId;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
        if (nodes != null && !nodes.isEmpty()) {
            Log.i(TAG, "findById: found " + nodes.size() + " node(s) for " + fullId);
            return AccessibilityNodeInfo.obtain(nodes.get(0));
        }
        return null;
    }

    /**
     * 通过文本内容查找节点
     * 先尝试系统 API（对原生控件有效），失败后手动遍历节点树（对 WebView 内容有效）
     */
    public static AccessibilityNodeInfo findByText(AccessibilityService service, String... texts) {
        AccessibilityNodeInfo root = getBiliRoot(service);
        if (root == null) return null;

        // 方式1：系统 API 搜索（对原生 View 有效）
        for (String text : texts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                Log.i(TAG, "findByText(API): found " + nodes.size() + " node(s) for text='" + text + "'");
                return AccessibilityNodeInfo.obtain(nodes.get(0));
            }
        }

        // 方式2：手动遍历节点树（WebView 页面的 fallback）
        Log.i(TAG, "findByText(API): no match, falling back to manual traversal");
        for (String text : texts) {
            AccessibilityNodeInfo found = findByTextTraversal(root, text);
            if (found != null) {
                Log.i(TAG, "findByText(traverse): found node for '" + text + "'"
                        + " clickable=" + found.isClickable()
                        + " class=" + found.getClassName());
                return AccessibilityNodeInfo.obtain(found);
            }
        }

        Log.i(TAG, "findByText: no match in either API or traversal for texts=" + java.util.Arrays.toString(texts));
        return null;
    }

    /**
     * 手动遍历节点树，按 getText() 精确匹配文本
     * 解决 WebView 中 findAccessibilityNodeInfosByText 搜不到的问题
     */
    private static AccessibilityNodeInfo findByTextTraversal(AccessibilityNodeInfo node, String targetText) {
        if (node == null) return null;

        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().equals(targetText)) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByTextTraversal(child, targetText);
                if (found != null) return found;
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
                return AccessibilityNodeInfo.obtain(current);
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 检查当前是否有 B 站 App 的窗口
     */
    public static boolean isBiliAppForeground(AccessibilityService service) {
        return getBiliRoot(service) != null;
    }

    /**
     * 获取当前 B 站页面上所有可点击的节点
     */
    public static List<AccessibilityNodeInfo> findAllClickableNodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = getBiliRoot(service);
        if (root == null) return result;
        traverseClickable(root, result);
        return result;
    }

    /**
     * 检测当前 B 站页面属于哪个阶段
     * 通过检查页面上的关键元素来判断
     */
    public static int detectCurrentPage(AccessibilityService service,
                                        String[] submitTexts,
                                        String[] buyTexts) {
        AccessibilityNodeInfo root = getBiliRoot(service);
        if (root == null) return PAGE_UNKNOWN;

        // 优先检测提交订单页的特征按钮（不要求文本节点本身 clickable，WebView 中 clickable 的是父容器）
        for (String text : submitTexts) {
            AccessibilityNodeInfo found = findByTextTraversal(root, text);
            if (found != null) {
                Log.i(TAG, "detectPage: found submit text '" + text + "' → ORDER_PAGE");
                return PAGE_ORDER;
            }
        }

        // 再检测购买按钮
        for (String text : buyTexts) {
            AccessibilityNodeInfo found = findByTextTraversal(root, text);
            if (found != null) {
                Log.i(TAG, "detectPage: found buy text '" + text + "' → BUY_PAGE");
                return PAGE_BUY;
            }
        }

        Log.i(TAG, "detectPage: no known buttons found → UNKNOWN");
        return PAGE_UNKNOWN;
    }

    /** 页面类型常量 */
    public static final int PAGE_BUY = 1;      // 购票详情页或中间页（有购买按钮）
    public static final int PAGE_ORDER = 2;    // 确认订单页（有提交订单按钮）
    public static final int PAGE_UNKNOWN = 0;  // 未知页面

    /**
     * 打印 B 站窗口上所有可见节点的文本和 ID
     */
    public static void dumpClickableNodes(AccessibilityService service) {
        AccessibilityNodeInfo root = getBiliRoot(service);
        if (root == null) {
            Log.w(TAG, "dumpClickableNodes: B站窗口根节点为 null");
            return;
        }

        Log.i(TAG, "====== B站窗口可见节点 ======");
        int count = dumpNode(root, 0, 80);
        Log.i(TAG, "====== End dump (printed " + count + " nodes) ======");
    }

    private static int dumpNode(AccessibilityNodeInfo node, int depth, int maxCount) {
        if (node == null || maxCount <= 0) return 0;
        int printed = 0;

        if (node.isClickable() || node.getText() != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth && i < 4; i++) sb.append("  ");
            sb.append(node.getClassName());
            if (node.getText() != null) sb.append(" text='").append(node.getText()).append("'");
            if (node.getViewIdResourceName() != null) sb.append(" id=").append(node.getViewIdResourceName());
            sb.append(" clickable=").append(node.isClickable());
            Log.i(TAG, sb.toString());
            printed++;
            maxCount--;
        }

        for (int i = 0; i < node.getChildCount() && maxCount > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                int sub = dumpNode(child, depth + 1, maxCount);
                printed += sub;
                maxCount -= sub;
            }
        }
        return printed;
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
