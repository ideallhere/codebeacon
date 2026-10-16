package com.codebeacon.widget

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import com.codebeacon.widget.bridge.AppBridge

/**
 * Kotlin 插件注册入口。
 *
 * ⚠️ 关键：Flutter 的 MethodChannel 必须**手动注册**才可用。
 * 仅在 AndroidManifest 声明 `android:name=".bridge.AppBridge"` 是不够的，
 * 还要在这里显式注册（或改用 v2 embedding 的自动注册）。
 *
 * 保留这个显式注册类，是为了在 AppBridge 需要额外初始化逻辑时
 * 有明确的扩展点，同时避免依赖 Flutter 版本的自动注册差异。
 */
class MainActivityPlugin : FlutterPlugin {

    companion object {
        private const val CHANNEL = "com.codebeacon/bridge"
    }

    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        // AppBridge 通过 FlutterApplication 自动注册（android:name 声明），
        // 此处只需保证 channel 名一致，无需重复设置 handler。
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = null
    }
}