import 'package:flutter/services.dart';

/// 取件码数据模型（与 Kotlin 侧 PickupCode 对应）
class PickupCode {
  final int id;
  final String code;
  final String courierName;
  final int confidence;
  final String sourcePackage;
  final int createdAt;
  final String? rawSnippet;
  final bool markedPicked;
  final int? pickedAt;
  final int ageMinutes;

  const PickupCode({
    required this.id,
    required this.code,
    required this.courierName,
    required this.confidence,
    required this.sourcePackage,
    required this.createdAt,
    this.rawSnippet,
    this.markedPicked = false,
    this.pickedAt,
    required this.ageMinutes,
  });

  /// 系统脱敏占位码
  bool get isRedacted => code == '__REDACTED__';

  factory PickupCode.fromMap(Map<dynamic, dynamic> m) => PickupCode(
        id: m['id'] as int,
        code: m['code'] as String,
        courierName: m['courierName'] as String? ?? '',
        confidence: m['confidence'] as int? ?? 0,
        sourcePackage: m['sourcePackage'] as String? ?? '',
        createdAt: m['createdAt'] as int,
        rawSnippet: m['rawSnippet'] as String?,
        markedPicked: m['markedPicked'] as bool? ?? false,
        pickedAt: m['pickedAt'] as int?,
        ageMinutes: m['ageMinutes'] as int? ?? 0,
      );

  /// 相对时间的中文描述
  String get ageText {
    if (ageMinutes < 1) return '刚刚';
    if (ageMinutes < 60) return '$ageMinutes分钟前';
    if (ageMinutes < 1440) return '${ageMinutes ~/ 60}小时前';
    final days = ageMinutes ~/ 1440;
    if (days < 30) return '$days天前';
    return '${days ~/ 30}个月前';
  }

  DateTime get createdTime =>
      DateTime.fromMillisecondsSinceEpoch(createdAt);
}

/// 监听来源统计
class SourceStat {
  final String packageName;
  final String displayName;
  final int count;
  final bool enabled;

  const SourceStat({
    required this.packageName,
    required this.displayName,
    required this.count,
    required this.enabled,
  });

  factory SourceStat.fromMap(Map<dynamic, dynamic> m, {bool enabled = true}) =>
      SourceStat(
        packageName: m['package'] as String,
        displayName: m['name'] as String? ?? m['package'] as String,
        count: m['count'] as int? ?? 0,
        enabled: enabled,
      );
}

/// Kotlin 侧桥接客户端。
///
/// 所有数据都存在 Kotlin 侧（SQLite），Flutter 只读不存，
/// 避免双份数据源导致的不一致。
class BeaconBridge {
  static const _channel = MethodChannel('com.codebeacon/bridge');

  // ── 权限 ────────────────────────────────────────────────

  /// 通知使用权是否已授予
  static Future<bool> hasAccess() async =>
      await _channel.invokeMethod<bool>('hasNotificationAccess') ?? false;

  /// 打开通知使用权设置页
  static Future<bool> openAccessSettings() async =>
      await _channel.invokeMethod<bool>('openNotificationAccessSettings') ??
      false;

  // ── 数据读取 ────────────────────────────────────────────

  static Future<List<PickupCode>> getActive() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getActiveCodes');
    return (raw ?? [])
        .map((e) => PickupCode.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<PickupCode>> getArchived() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getArchivedCodes');
    return (raw ?? [])
        .map((e) => PickupCode.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<PickupCode?> getLatest() async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('getLatestCode');
    return raw == null ? null : PickupCode.fromMap(raw);
  }

  static Future<List<SourceStat>> getSourceStats() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getSourceStats');
    return (raw ?? [])
        .map((e) => SourceStat.fromMap(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<SourceStat>> getEnabledSources() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getEnabledSources');
    return (raw ?? [])
        .map((e) => SourceStat.fromMap(e as Map<dynamic, dynamic>,
            enabled: e['enabled'] as bool? ?? true))
        .toList();
  }

  // ── 状态变更 ────────────────────────────────────────────

  static Future<void> markPicked(int id) =>
      _channel.invokeMethod('markPicked', {'id': id});

  static Future<void> unmarkPicked(int id) =>
      _channel.invokeMethod('unmarkPicked', {'id': id});

  static Future<void> deleteCode(int id) =>
      _channel.invokeMethod('deleteCode', {'id': id});

  static Future<void> clearActive() => _channel.invokeMethod('clearActive');

  static Future<void> copyCode(String code) =>
      _channel.invokeMethod('copyCode', {'code': code});

  static Future<void> setSourceEnabled(String pkg, bool enabled) =>
      _channel.invokeMethod('setSourceEnabled',
          {'package': pkg, 'enabled': enabled});

  // ── 记事本导出 ──────────────────────────────────────────

  /// 导出为纯文本（用于预览/分享）
  static Future<String?> exportMemoText() =>
      _channel.invokeMethod<String>('exportMemoText');

  /// 导出为文件并返回 Uri
  static Future<String?> exportMemoFile() =>
      _channel.invokeMethod<String>('exportMemoFile');

  /// 用系统应用打开导出的文件
  static Future<bool> openMemo(String uri) =>
      _channel.invokeMethod<bool>('openMemo', {'uri': uri});

  // ── 调试 ────────────────────────────────────────────────

  static Future<void> refreshWidget() =>
      _channel.invokeMethod('refreshWidget');

  static Future<Map<dynamic, dynamic>> getDebugStats() async =>
      await _channel.invokeMethod<Map<dynamic, dynamic>>('getDebugStats') ??
      {};
}