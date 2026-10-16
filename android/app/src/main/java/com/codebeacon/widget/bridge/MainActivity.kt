package com.codebeacon.widget

import android.os.Bundle
import com.codebeacon.widget.bridge.AppBridge
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * 承载 Flutter 界面的 Activity。
 *
 * ## 关键：MethodChannel 注册方式
 *
 * 混编工程里，自定义插件有两条注册路径：
 *  1. `GeneratedPluginRegistrant` —— 只包含 pubspec 里声明的插件，
 *     **不包含**纯 Kotlin 手写的插件
 *  2. 在 `configureFlutterEngine` 里手动 `addPlugin(...)` —— 可靠且显式
 *
 * 这里采用路径 2。若漏掉这一步，Dart 端调用会抛
 * `MissingPluginException(No implementation found for method ...)`，
 * 这是混编工程最常见的坑。
 */
class MainActivity : FlutterFragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 先让 GeneratedPluginRegistrant 注册标准插件
        // （super.configureFlutterEngine 已自动调用）

        // 再手动注册我们的 Kotlin 插件
        flutterEngine.plugins.add(AppBridge())
    }
}