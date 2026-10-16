import 'package:flutter/material.dart';
import 'bridge.dart';

/// 设置页
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  Map<dynamic, dynamic>? _stats;

  @override
  void initState() {
    super.initState();
    _loadStats();
  }

  Future<void> _loadStats() async {
    final s = await BeaconBridge.getDebugStats();
    if (!mounted) return;
    setState(() => _stats = s);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        children: [
          _buildCategory('管理'),
          ListTile(
            leading: const Icon(Icons.inventory_2_outlined),
            title: const Text('已取件归档'),
            subtitle: Text(
              _stats != null ? '${_stats!['archivedCount']} 条记录' : '加载中',
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.push(context,
                MaterialPageRoute(builder: (_) => const ArchivePage())),
          ),
          ListTile(
            leading: const Icon(Icons.apps_outlined),
            title: const Text('监听应用'),
            subtitle: const Text('选择哪些应用参与识别'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              await Navigator.push(context,
                  MaterialPageRoute(builder: (_) => const SourceSettingsPage()));
              _loadStats();
            },
          ),

          _buildCategory('小部件'),
          ListTile(
            leading: const Icon(Icons.widgets_outlined),
            title: const Text('添加桌面小部件'),
            subtitle: const Text('长按桌面空白处 → 部件 → 找到「取件码」'),
            onTap: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('长按桌面空白处，选择「部件」即可添加'),
                  duration: Duration(seconds: 3),
                ),
              );
            },
          ),
          ListTile(
            leading: const Icon(Icons.open_in_full),
            title: const Text('尺寸与布局'),
            subtitle: const Text('长按小部件可自由拖拽调整大小'),
          ),

          _buildCategory('隐私'),
          const ListTile(
            leading: Icon(Icons.shield_outlined),
            title: Text('完全本地运行'),
            subtitle: Text('V1 不含任何网络权限，取件码仅保存在本机数据库'),
          ),
          const ListTile(
            leading: Icon(Icons.visibility_outlined),
            title: Text('权限说明'),
            subtitle: Text('仅「通知使用权」一项，不申请短信权限'),
          ),

          _buildCategory('关于'),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: const Text('取件码信标'),
            subtitle: Text('版本 1.0.0${_stats != null ? ' · 待取 ${_stats!['activeCount']} 条' : ''}'),
          ),
        ],
      ),
    );
  }

  Widget _buildCategory(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 6),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 13,
          color: Colors.grey.shade600,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}

/// 监听应用设置页
class SourceSettingsPage extends StatefulWidget {
  const SourceSettingsPage({super.key});

  @override
  State<SourceSettingsPage> createState() => _SourceSettingsPageState();
}

class _SourceSettingsPageState extends State<SourceSettingsPage> {
  List<SourceStat> _sources = [];
  List<SourceStat> _stats = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final sources = await BeaconBridge.getEnabledSources();
    final stats = await BeaconBridge.getSourceStats();
    if (!mounted) return;

    // 合并统计信息
    final merged = sources.map((s) {
      final match = stats.where((t) => t.packageName == s.packageName);
      return SourceStat(
        packageName: s.packageName,
        displayName: s.displayName,
        count: match.isNotEmpty ? match.first.count : 0,
        enabled: s.enabled,
      );
    }).toList();

    setState(() {
      _sources = merged;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('监听应用')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              children: [
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    '关闭某个应用后，不再从它的通知中提取取件码。'
                    '注意：若某应用自身关闭了通知，'
                    '本应用也听不到它的消息。',
                    style: TextStyle(fontSize: 13, height: 1.5),
                  ),
                ),
                ..._sources.map(_buildTile),
              ],
            ),
    );
  }

  Widget _buildTile(SourceStat s) {
    return SwitchListTile(
      value: s.enabled,
      onChanged: (v) async {
        await BeaconBridge.setSourceEnabled(s.packageName, v);
        _load();
      },
      title: Text(s.displayName),
      subtitle: Text(
        s.count > 0 ? '已识别 ${s.count} 条' : s.packageName,
        style: TextStyle(
          fontSize: 12,
          color: s.count > 0 ? Colors.green.shade700 : Colors.grey.shade600,
        ),
      ),
    );
  }
}