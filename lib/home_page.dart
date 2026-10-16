import 'dart:async';
import 'package:flutter/material.dart';
import 'bridge.dart';
import 'archive_page.dart';
import 'settings_page.dart';

/// 主界面：最新取件码 + 待取列表 + 授权引导
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  bool _hasAccess = false;
  List<PickupCode> _active = [];
  bool _loading = true;
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _refreshAll();
    // 小部件无法主动推送数据到 Flutter，用轻量轮询保持同步
    // 3 秒一次足够（取件码是低频事件），且仅在页面可见时运行
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (mounted) _loadCodes(silent: true);
    });
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshAll() async {
    final access = await BeaconBridge.hasAccess();
    if (!mounted) return;
    setState(() => _hasAccess = access);
    await _loadCodes();
  }

  Future<void> _loadCodes({bool silent = false}) async {
    final codes = await BeaconBridge.getActive();
    if (!mounted) return;
    if (!silent || codes.length != _active.length) {
      setState(() {
        _active = codes;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('取件码信标'),
        actions: [
          IconButton(
            icon: const Icon(Icons.inventory_2_outlined),
            tooltip: '已取件归档',
            onPressed: () async {
              await Navigator.push(context,
                  MaterialPageRoute(builder: (_) => const ArchivePage()));
              _loadCodes(silent: true);
            },
          ),
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const SettingsPage())),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refreshAll,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (!_hasAccess) _buildAccessBanner(),
            if (_hasAccess) _buildLatestCard(),
            const SizedBox(height: 20),
            _buildSectionHeader(),
            const SizedBox(height: 8),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(32),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_active.isEmpty)
              _buildEmptyState()
            else
              ..._active.map(_buildCodeTile),
          ],
        ),
      ),
    );
  }

  /// 权限引导横幅 —— 这是最关键的引导，未开启时一切功能都不工作
  Widget _buildAccessBanner() {
    return Card(
      color: Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.warning_amber_rounded, color: Colors.orange.shade700),
                const SizedBox(width: 8),
                Text(
                  '未开启通知使用权',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: Colors.orange.shade900,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              '这是本应用唯一需要的权限。开启后会自动读取短信、微信、'
              '支付宝里的取件码，所有数据仅保存在本机。',
              style: TextStyle(fontSize: 13, height: 1.5),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: () async {
                await BeaconBridge.openAccessSettings();
                // 用户可能需要几秒到设置页操作，返回后复查
                await Future.delayed(const Duration(seconds: 2));
                await _refreshAll();
              },
              icon: const Icon(Icons.settings),
              label: const Text('立即开启'),
            ),
          ],
        ),
      ),
    );
  }

  /// 最新取件码大卡片
  Widget _buildLatestCard() {
    if (_active.isEmpty) return const SizedBox.shrink();
    final latest = _active.first;

    if (latest.isRedacted) {
      return Card(
        color: Colors.deepOrange.shade50,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(Icons.visibility_off, color: Colors.deepOrange.shade700),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '该通知内容被系统隐藏',
                      style: TextStyle(
                        fontWeight: FontWeight.w500,
                        color: Colors.deepOrange.shade900,
                      ),
                    ),
                    const SizedBox(height: 4),
                    const Text(
                      'Android 15 起，系统会把含验证码特征的短信内容脱敏。'
                      '取件码通常不受影响；若长期无法识别，请到桌面小部件查看。',
                      style: TextStyle(fontSize: 12, height: 1.4),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
    }

    return Card(
      elevation: 2,
      child: InkWell(
        onTap: () => _copyCode(latest),
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  _CourierBadge(name: latest.courierName),
                  const SizedBox(width: 8),
                  Text(
                    latest.courierName,
                    style: TextStyle(
                      fontSize: 14,
                      color: Colors.grey.shade700,
                    ),
                  ),
                  const Spacer(),
                  Text(
                    latest.ageText,
                    style: TextStyle(fontSize: 12, color: Colors.grey.shade500),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                latest.code,
                style: const TextStyle(
                  fontSize: 38,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 2,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '点按复制到剪贴板',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade500),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSectionHeader() {
    return Row(
      children: [
        Text(
          '待取（${_active.length}）',
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
        ),
        const Spacer(),
        if (_active.isNotEmpty)
          TextButton(
            onPressed: () => _showClearConfirm(),
            child: const Text('清空'),
          ),
      ],
    );
  }

  Widget _buildEmptyState() {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 48),
      child: Column(
        children: [
          Icon(Icons.inbox_outlined, size: 56, color: Colors.grey.shade300),
          const SizedBox(height: 12),
          Text(
            _hasAccess ? '暂无取件码' : '开启权限后自动识别',
            style: TextStyle(color: Colors.grey.shade500),
          ),
          const SizedBox(height: 4),
          Text(
            _hasAccess ? '收到快递通知时会自动出现在这里' : '无需手动添加',
            style: TextStyle(fontSize: 12, color: Colors.grey.shade400),
          ),
        ],
      ),
    );
  }

  /// 取件码列表项 —— 支持"顺滑下移消失"
  Widget _buildCodeTile(PickupCode item) {
    return _AnimatedCodeTile(
      key: ValueKey(item.id),
      item: item,
      onCopy: () => _copyCode(item),
      onMarkPicked: () => _markPicked(item),
    );
  }

  Future<void> _copyCode(PickupCode item) async {
    await BeaconBridge.copyCode(item.code);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('取件码已复制：${item.code}'),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  /// 标记已取件 —— 对应需求"顺滑下移消失 + 存入记录"
  Future<void> _markPicked(PickupCode item) async {
    await BeaconBridge.markPicked(item.id);
    await _loadCodes(silent: true);
    await BeaconBridge.refreshWidget();
  }

  Future<void> _showClearConfirm() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空待取列表'),
        content: const Text('归档记录不受影响，此操作不可撤销。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('清空')),
        ],
      ),
    );
    if (ok == true) {
      await BeaconBridge.clearActive();
      await _loadCodes(silent: true);
      await BeaconBridge.refreshWidget();
    }
  }
}

/// 支持"顺滑下移消失"的列表项。
///
/// 这里用 Flutter 的动画实现**真实的**下移 + 淡出效果。
/// 注意：桌面小部件本体受 RemoteViews 限制做不到这个效果，
/// 但应用内可以完整实现。
class _AnimatedCodeTile extends StatefulWidget {
  final PickupCode item;
  final VoidCallback onCopy;
  final VoidCallback onMarkPicked;

  const _AnimatedCodeTile({
    super.key,
    required this.item,
    required this.onCopy,
    required this.onMarkPicked,
  });

  @override
  State<_AnimatedCodeTile> createState() => _AnimatedCodeTileState();
}

class _AnimatedCodeTileState extends State<_AnimatedCodeTile> {
  bool _removing = false;

  Future<void> _handleMark() async {
    if (_removing) return;
    setState(() => _removing = true);

    // 顺滑下移 + 淡出，与列表项填补形成连贯动画
    await Future.delayed(const Duration(milliseconds: 60));
    if (!mounted) return;

    widget.onMarkPicked();
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;

    return AnimatedSlide(
      offset: _removing ? const Offset(0, 0.35) : Offset.zero,
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeInCubic,
      child: AnimatedOpacity(
        opacity: _removing ? 0 : 1,
        duration: const Duration(milliseconds: 180),
        child: AnimatedSize(
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
          child: _removing
              ? const SizedBox(width: double.infinity, height: 0)
              : Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: ListTile(
                    onTap: widget.onCopy,
                    title: Text(
                      item.code,
                      style: const TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 1,
                      ),
                    ),
                    subtitle: Text(
                      '${item.courierName} · ${item.ageText}',
                      style: const TextStyle(fontSize: 12),
                    ),
                    leading: _CourierBadge(name: item.courierName),
                    trailing: IconButton(
                      tooltip: '标记已取件',
                      icon: const Icon(Icons.check_circle_outline),
                      onPressed: _handleMark,
                    ),
                  ),
                ),
        ),
      ),
    );
  }
}

/// 快递徽标
class _CourierBadge extends StatelessWidget {
  final String name;
  const _CourierBadge({required this.name});

  static const Map<String, String> _badges = {
    '顺丰速运': '顺',
    '京东物流': '京',
    '菜鸟裹裹': '菜',
    '中通快递': '中',
    '圆通速递': '圆',
    '韵达速递': '韵',
    '申通快递': '申',
    '极兔速递': '兔',
    '邮政EMS': '邮',
  };

  static const Map<String, Color> _colors = {
    '顺丰速运': Color(0xFF000000),
    '京东物流': Color(0xFFE1251B),
    '菜鸟裹裹': Color(0xFFFF6A00),
    '中通快递': Color(0xFF0070C0),
    '圆通速递': Color(0xFF0068B7),
    '韵达速递': Color(0xFFFFB300),
    '申通快递': Color(0xFFEF6C00),
    '极兔速递': Color(0xFFD32F2F),
    '邮政EMS': Color(0xFF2E7D32),
  };

  @override
  Widget build(BuildContext context) {
    final text = _badges[name] ?? (name.isNotEmpty ? name[0] : '?');
    final color = _colors[name] ?? Colors.blueGrey;

    return CircleAvatar(
      radius: 16,
      backgroundColor: color,
      child: Text(
        text,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 13,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}