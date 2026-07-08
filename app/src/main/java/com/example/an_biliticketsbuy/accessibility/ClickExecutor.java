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
 * 2. 手势点击：通过 GestureDescription 在坐标位置模拟手指点击
 */
public class ClickExecutor {

    private static final String TAG = "ClickExecutor";
    private final AccessibilityService service;

    public ClickExecutor(AccessibilityService service) {
        this.service = service;
    }

    /** 方式一：节点点击 */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Node click succeeded (direct)");
            return true;
        }

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

    /** 方式二：手势点击（坐标点击），最低支持 API 24 */
    public boolean clickCoordinate(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture click requires API 24+");
            return false;
        }

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
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

    /** 智能点击：WebView 内直接用坐标手势，原生控件先尝试节点点击再 fallback */
    public boolean smartClick(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if (isInsideWebView(node)) {
            Rect nodeRect = new Rect();
            node.getBoundsInScreen(nodeRect);

            // 如果节点本身可点击，直接用它
            if (node.isClickable()) {
                Log.i(TAG, "WebView node IS clickable: bounds=" + nodeRect.toShortString()
                        + " center=(" + nodeRect.centerX() + ", " + nodeRect.centerY() + ")"
                        + " text=" + node.getText() + " class=" + node.getClassName());
                return clickCoordinate(nodeRect.centerX(), nodeRect.centerY());
            }

            // 节点不可点击，找可点击父节点，对比 bounds
            AccessibilityNodeInfo parent = findClickableParent(node);
            if (parent != null) {
                Rect parentRect = new Rect();
                parent.getBoundsInScreen(parentRect);
                boolean sameBounds = nodeRect.equals(parentRect);

                Log.i(TAG, "WebView text node: bounds=" + nodeRect.toShortString()
                        + " text='" + node.getText() + "' class=" + node.getClassName());
                Log.i(TAG, "WebView clickable parent: bounds=" + parentRect.toShortString()
                        + " sameBounds=" + sameBounds
                        + " class=" + parent.getClassName());

                // 如果父节点 bounds 和文本节点不同（父节点是有具体范围的按钮容器），用父节点
                // 如果相同（父节点只是 TextView 的直接父），用文本节点自身
                Rect targetRect = sameBounds ? nodeRect : parentRect;
                int cx = targetRect.centerX();
                int cy = targetRect.centerY();
                String which = sameBounds ? "text-node(same-bounds)" : "parent(different-bounds)";
                Log.i(TAG, "Using " + which + " gesture click at (" + cx + ", " + cy + ")");
                return clickCoordinate(cx, cy);
            }

            // 没有可点击父节点，直接用文本节点自身
            Log.i(TAG, "WebView node: NO clickable parent, using text node bounds="
                    + nodeRect.toShortString() + " center=(" + nodeRect.centerX() + ", " + nodeRect.centerY() + ")");
            return clickCoordinate(nodeRect.centerX(), nodeRect.centerY());
        }

        // 原生控件：如果节点本身不可点击，先找 clickable 父节点
        if (!node.isClickable()) {
            AccessibilityNodeInfo clickableParent = findClickableParent(node);
            if (clickableParent != null) {
                Log.i(TAG, "Using clickable parent for native click, class=" + clickableParent.getClassName());
                node = clickableParent;
            }
        }

        if (clickNode(node)) {
            return true;
        }

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        Log.i(TAG, "Native click: fallback to gesture at (" + rect.centerX() + ", " + rect.centerY() + ")");
        return clickCoordinate(rect.centerX(), rect.centerY());
    }

    /**
     * 从当前节点向上查找最近的可点击父节点（限制层级防止爬到 WebView 容器）
     */
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int maxLevels = 10;
        while (current != null && maxLevels > 0) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
            maxLevels--;
        }
        return null;
    }

    /**
     * 检测节点是否在 WebView 内部
     * 通过向上遍历父节点检查 className 是否包含 "WebView"
     */
    private boolean isInsideWebView(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            CharSequence className = current.getClassName();
            if (className != null && className.toString().contains("WebView")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** 快速连续点击（用于抢票场景的高频点击） */
    public void rapidClick(AccessibilityNodeInfo node, int times, long interval) {
        new Thread(() -> {
            for (int i = 0; i < times; i++) {
                boolean success = smartClick(node);
                Log.d(TAG, "Rapid click #" + (i + 1) + ": " + success);
                if (!success) break;
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }
}
