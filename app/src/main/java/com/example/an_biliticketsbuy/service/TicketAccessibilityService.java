package com.example.an_biliticketsbuy.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.an_biliticketsbuy.accessibility.OrderFlowController;

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

    public static TicketAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected");

        instance = this;
        flowController = new OrderFlowController(this);

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
            Log.d(TAG, "A11y event: type=" + event.getEventType()
                    + " pkg=" + event.getPackageName()
                    + " state=" + flowController.getCurrentState());
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

    public void startTicketGrabbing(long targetTimeMillis, long clickInterval, int maxRetry) {
        if (flowController == null) {
            Log.w(TAG, "flowController is null, creating new instance");
            flowController = new OrderFlowController(this);
        }
        Log.i(TAG, "startTicketGrabbing: target=" + targetTimeMillis
                + ", interval=" + clickInterval + ", maxRetry=" + maxRetry);
        flowController.stop();  // 先停止旧任务，重置 isActive 和 handler
        flowController.configure(targetTimeMillis, clickInterval, maxRetry);
        flowController.start();
        Log.i(TAG, "Ticket grabbing started, flowState=" + flowController.getCurrentState());
    }

    public void stopTicketGrabbing() {
        if (flowController != null) {
            flowController.stop();
        }
        Log.i(TAG, "Ticket grabbing stopped");
    }

    public OrderFlowController.FlowState getFlowState() {
        return flowController != null ? flowController.getCurrentState()
                : OrderFlowController.FlowState.IDLE;
    }

    public static boolean isServiceEnabled() {
        return instance != null;
    }
}
