package com.codebeacon.widget

import android.app.Application

/**
 * 应用入口。
 *
 * Flutter 3.27 已移除 v1 embedding 的 FlutterApplication，
 * 标准 Application 即可（引擎初始化由 MainActivity 的
 * configureFlutterEngine 完成，纯 Kotlin 手写插件不走
 * GeneratedPluginRegistrant）。
 */
class App : Application()
