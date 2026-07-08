package com.example.an_biliticketsbuy.comm

/** Service 间通信的 Action 常量 */
object ActionBus {
    const val ACTION_START_GRAB = "com.example.an_biliticketsbuy.START_GRAB"
    const val ACTION_STOP_GRAB = "com.example.an_biliticketsbuy.STOP_GRAB"
    const val ACTION_GRAB_STARTED = "com.example.an_biliticketsbuy.GRAB_STARTED"
    const val ACTION_GRAB_STOPPED = "com.example.an_biliticketsbuy.GRAB_STOPPED"
    const val ACTION_STATE_UPDATE = "com.example.an_biliticketsbuy.STATE_UPDATE"
    const val EXTRA_STATE = "state"
}
