package com.codebeacon.widget

import io.flutter.app.FlutterApplication

/**
 * 应用入口。
 *
 * 用 [FlutterApplication]（v1 embedding 的 Application 基类），
 * 它会自动处理 Flutter 引擎相关的初始化。
 *
 * 注意：自定义插件的注册在
 * [com.codebeacon.widget.bridge.MainActivity.configureFlutterEngine] 中完成，
 * 因为纯 Kotlin 手写插件不在 `GeneratedPluginRegistrant` 里。
 */
class App : FlutterApplication()