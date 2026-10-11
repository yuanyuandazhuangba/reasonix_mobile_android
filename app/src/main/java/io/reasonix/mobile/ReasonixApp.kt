package io.reasonix.mobile

import android.app.Application

/**
 * 应用入口。当前无需在此做全局初始化（依赖均为按需创建），
 * 保留该类是为了后续接入崩溃上报、全局配置等启动逻辑时有一个稳定的挂载点。
 */
class ReasonixApp : Application()
