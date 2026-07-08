package com.example.an_biliticketsbuy.accessibility;

/**
 * B站 App 会员购页面控件 ID 常量表
 *
 * 获取方式：
 * 1. 手机连接电脑，打开 B 站 App 进入购票页
 * 2. Android Studio → Tools → Layout Inspector
 * 3. 选择 B 站进程，在界面树中找到目标按钮的 resource-id
 * 4. resource-id 格式通常为 "tv.danmaku.bili:id/xxx"，取 "/" 后面的部分
 *
 * ⚠️ B 站 App 更新后控件 ID 可能变化，需用 Layout Inspector 重新抓取。
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
