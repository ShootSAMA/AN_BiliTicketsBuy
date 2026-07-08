package com.example.an_biliticketsbuy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 下单流程编排器（页面检测驱动）
 *
 * 不依赖严格的 1→2→3 顺序，而是通过检测当前页面内容自动执行对应操作：
 * - 检测到购买按钮 → 点击购买
 * - 检测到提交订单按钮 → 提交订单
 * - 检测到支付页面 → 停止自动化，交给用户
 *
 * 每次窗口变化事件都会触发页面检测，确保流程在任何阶段都能正确响应。
 */
public class OrderFlowController {

    private static final String TAG = "OrderFlow";

    public enum FlowState {
        IDLE,
        WAITING_FOR_START,
        CLICKING_BUY,        // 正在点击购买按钮（详情页或中间页）
        SUBMITTING_ORDER,    // 确认订单页：提交订单
        WAITING_PAYMENT,     // 等待用户手动支付
        COMPLETED,
        FAILED
    }

    private final AccessibilityService service;
    private final ClickExecutor clickExecutor;
    private final Handler handler;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isActing = new AtomicBoolean(false); // 防止重复触发

    private volatile FlowState currentState = FlowState.IDLE;
    private volatile AccessibilityNodeInfo cachedBuyNode = null; // 预缓存的购买按钮节点
    private long targetTimeMillis;
    private long clickInterval = 100;
    private int maxRetryCount = 50;

    private static final String[] BUY_BUTTON_TEXTS = {"立即购买", "立即购票", "选座购票", "立即预定"};
    private static final String[] SUBMIT_TEXTS = {"提交订单", "立即支付", "确认订单", "去支付", "确认支付"};
    private static final String[] RETRY_TEXTS = {"再试一次"};

    public OrderFlowController(AccessibilityService service) {
        this.service = service;
        this.clickExecutor = new ClickExecutor(service);
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void configure(long targetTimeMillis, long clickInterval, int maxRetry) {
        this.targetTimeMillis = targetTimeMillis;
        this.clickInterval = clickInterval;
        this.maxRetryCount = maxRetry;
    }

    public void start() {
        handler.removeCallbacksAndMessages(null);
        isActive.set(false);
        isActing.set(false);
        currentState = FlowState.IDLE;

        if (!isActive.compareAndSet(false, true)) {
            Log.w(TAG, "start() failed: isActive still true after reset");
            return;
        }
        currentState = FlowState.WAITING_FOR_START;
        Log.i(TAG, "Order flow started. Target time: " + targetTimeMillis);

        // 立即检测当前页面状态并上报
        handler.post(() -> {
            if (!isActive.get()) return;
            long now = System.currentTimeMillis();
            int page = BiliButtonFinder.detectCurrentPage(service, SUBMIT_TEXTS, BUY_BUTTON_TEXTS);
            String pageName = page == BiliButtonFinder.PAGE_BUY ? "BUY"
                    : page == BiliButtonFinder.PAGE_ORDER ? "ORDER" : "UNKNOWN";
            Log.i(TAG, "Start detect: page=" + pageName + " now=" + now
                    + " remaining=" + (targetTimeMillis - now) + "ms");

            if (now >= targetTimeMillis) {
                // 已过目标时间，根据当前页面直接执行
                Log.i(TAG, "Already past target time, acting based on current page");
                detectAndAct();
            }
        });

        waitForTargetTime();
    }

    public void stop() {
        isActive.set(false);
        isActing.set(false);
        cachedBuyNode = null;
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

    private void waitForTargetTime() {
        if (!isActive.get()) return;
        currentState = FlowState.WAITING_FOR_START;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isActive.get()) return;

                long now = System.currentTimeMillis();
                if (now >= targetTimeMillis) {
                    Log.i(TAG, "Target time reached! Attempting instant click with cached node");
                    // 倒计时归零，立即用缓存节点点击
                    if (tryCachedClick()) {
                        return; // 缓存点击成功，后续由 onEvent 驱动 detectAndAct
                    }
                    // 缓存未命中，走正常检测流程
                    Log.i(TAG, "Cache miss, falling back to detectAndAct");
                    detectAndAct();
                } else {
                    // 倒计时进行中，持续预缓存购买按钮
                    preCacheBuyButton();

                    long remaining = targetTimeMillis - now;
                    long delay = remaining > 2000 ? 500 : 50;
                    handler.postDelayed(this, delay);
                }
            }
        });
    }

    /**
     * 预缓存购买按钮节点
     * 在倒计时期间持续刷新缓存，确保归零时节点是最新的
     */
    private void preCacheBuyButton() {
        AccessibilityNodeInfo node = BiliButtonFinder.findByText(service, BUY_BUTTON_TEXTS);
        if (node != null) {
            cachedBuyNode = node;
            Log.i(TAG, "Buy button cached: text='" + node.getText() + "'");
        } else {
            cachedBuyNode = null;
        }
    }

    /**
     * 使用缓存的购买按钮节点进行即时点击
     * @return true 如果缓存命中并成功点击
     */
    private boolean tryCachedClick() {
        AccessibilityNodeInfo node = cachedBuyNode;
        if (node == null) return false;

        // 验证缓存节点仍然有效（页面没刷新掉）
        try {
            CharSequence text = node.getText();
            if (text == null) return false;

            // 确认文本仍然匹配购买按钮
            String nodeText = text.toString();
            boolean isBuyButton = false;
            for (String buyText : BUY_BUTTON_TEXTS) {
                if (nodeText.contains(buyText)) {
                    isBuyButton = true;
                    break;
                }
            }
            if (!isBuyButton) {
                Log.i(TAG, "Cached node text changed to '" + nodeText + "', not a buy button anymore");
                return false;
            }

            Log.i(TAG, "Instant click on cached buy button: '" + nodeText + "'");
            currentState = FlowState.CLICKING_BUY;
            isActing.set(true);
            boolean clicked = clickExecutor.smartClick(node);
            if (clicked) {
                Log.i(TAG, "Instant click SUCCEEDED!");
                // 点击成功后进入检测循环
                handler.postDelayed(() -> {
                    isActing.set(false);
                    if (isActive.get()) detectAndAct();
                }, 600);
                return true;
            } else {
                Log.w(TAG, "Instant click returned false (gesture dispatch failed)");
                isActing.set(false);
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Cached node is stale: " + e.getMessage());
            return false;
        }
    }

    /**
     * 核心方法：检测当前页面并执行对应操作
     * 每次窗口变化或点击成功后都会调用
     */
    private void detectAndAct() {
        if (!isActive.get()) return;

        // 防止在已有操作进行中时重复触发
        if (!isActing.compareAndSet(false, true)) {
            Log.i(TAG, "detectAndAct: already acting, skip");
            return;
        }

        // 先处理拥挤弹窗
        dismissRetryPopup();

        int page = BiliButtonFinder.detectCurrentPage(service, SUBMIT_TEXTS, BUY_BUTTON_TEXTS);

        switch (page) {
            case BiliButtonFinder.PAGE_BUY:
                currentState = FlowState.CLICKING_BUY;
                Log.i(TAG, "Detected BUY page, clicking buy button");
                attemptClick(BiliViewIds.BTN_BUY_NOW, BUY_BUTTON_TEXTS, 0);
                break;

            case BiliButtonFinder.PAGE_ORDER:
                currentState = FlowState.SUBMITTING_ORDER;
                Log.i(TAG, "Detected ORDER page, submitting order");
                submitOrder();
                break;

            case BiliButtonFinder.PAGE_UNKNOWN:
            default:
                isActing.set(false);
                Log.i(TAG, "Unknown page, waiting for next event...");
                // 未知页面时延迟后重新检测
                handler.postDelayed(() -> {
                    isActing.set(false);
                    if (isActive.get()) detectAndAct();
                }, 500);
                break;
        }
    }

    /**
     * 确认订单页：直接点击提交订单，用文本消失判断是否成功
     */
    private void submitOrder() {
        if (!isActive.get()) return;

        dismissRetryPopup();

        // 点击提交订单，然后用文本检测验证
        handler.postDelayed(() -> clickSubmitAndVerify(0), 300);
    }

    /**
     * 点击提交订单按钮并通过文本消失验证是否成功
     * 不依赖 attemptClick 的通用重试机制，只用文本检测判断
     */
    private void clickSubmitAndVerify(int attempt) {
        if (!isActive.get() || attempt >= maxRetryCount) {
            if (attempt >= maxRetryCount) {
                Log.w(TAG, "Max retries for submit order");
                currentState = FlowState.FAILED;
                isActing.set(false);
            }
            return;
        }

        dismissRetryPopup();

        // 找到提交按钮节点
        AccessibilityNodeInfo submitNode = BiliButtonFinder.findByText(service, SUBMIT_TEXTS);
        if (submitNode == null) {
            // 找不到提交按钮文本 → 页面已跳转，提交成功
            Log.i(TAG, "Submit text gone → order submitted successfully!");
            currentState = FlowState.WAITING_PAYMENT;
            isActing.set(false);
            isActive.set(false);
            Log.i(TAG, "Waiting for user to complete payment.");
            return;
        }

        android.graphics.Rect r = new android.graphics.Rect();
        submitNode.getBoundsInScreen(r);
        Log.i(TAG, "Submit node found: text='" + submitNode.getText()
                + "' bounds=" + r.toShortString()
                + " attempt=" + (attempt + 1));
        clickExecutor.smartClick(submitNode);

        // 延迟后检查文本是否消失
        handler.postDelayed(() -> clickSubmitAndVerify(attempt + 1), clickInterval + 200);
    }

    private void attemptClick(String viewId, String[] textTexts, int attempt) {
        if (!isActive.get() || attempt >= maxRetryCount) {
            if (attempt >= maxRetryCount) {
                Log.w(TAG, "Max retries reached for " + viewId);
                currentState = FlowState.FAILED;
                isActing.set(false);
            }
            return;
        }

        dismissRetryPopup();

        if (attempt < 3) {
            Log.i(TAG, "attemptClick [" + viewId + "] attempt=" + (attempt + 1) + "/" + maxRetryCount);
        }

        AccessibilityNodeInfo node = BiliButtonFinder.findById(service, viewId);
        if (node == null) {
            node = BiliButtonFinder.findByText(service, textTexts);
        }

        if (node != null) {
            Log.i(TAG, "Found node for [" + viewId + "] on attempt " + (attempt + 1)
                    + ", text=" + node.getText() + ", class=" + node.getClassName());
            boolean clicked = clickExecutor.smartClick(node);
            if (clicked) {
                Log.i(TAG, "Click succeeded on attempt " + (attempt + 1));
                // 点击成功后，延迟再检测当前页面状态
                handler.postDelayed(() -> {
                    isActing.set(false);
                    if (isActive.get()) {
                        Log.i(TAG, "Re-detecting page after click...");
                        detectAndAct();
                    }
                }, 600);
                return;
            } else {
                Log.w(TAG, "smartClick returned false on attempt " + (attempt + 1));
            }
        } else {
            if (attempt == 0 || attempt == 9) {
                Log.i(TAG, "Node NOT found for [" + viewId + "] texts=" + java.util.Arrays.toString(textTexts)
                        + ", attempt=" + (attempt + 1) + ", dumping visible nodes:");
                BiliButtonFinder.dumpClickableNodes(service);
            }
        }

        handler.postDelayed(() -> {
            attemptClick(viewId, textTexts, attempt + 1);
        }, clickInterval);
    }

    /**
     * 检测并持续关闭拥挤弹窗（"再试一次"）。
     * 不做次数限制，以屏幕实际内容为准——只要有弹窗就一直点，
     * 直到弹窗确认消失才返回。isActive 作为安全阀防止死循环。
     */
    private void dismissRetryPopup() {
        while (isActive.get()) {
            AccessibilityNodeInfo retryButton = BiliButtonFinder.findByText(service, RETRY_TEXTS);
            if (retryButton == null) {
                return; // 弹窗已消失
            }
            Log.i(TAG, "检测到拥挤弹窗，自动点击'再试一次'");
            clickExecutor.smartClick(retryButton);

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 无障碍事件回调：窗口变化时触发页面检测
     */
    public void onEvent(AccessibilityEvent event) {
        // 弹窗处理独立于流程状态，即使流程已结束也要处理弹窗
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.postDelayed(() -> dismissRetryPopup(), 200);
        }

        if (!isActive.get()) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(TAG, "Window changed: " + event.getPackageName() + " / " + event.getClassName());
            // 窗口变化后延迟检测，等新页面渲染完成
            handler.postDelayed(() -> {
                if (isActive.get() && !isActing.get()) {
                    detectAndAct();
                }
            }, 300);
        }
    }
}
