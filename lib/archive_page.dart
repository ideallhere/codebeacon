import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'bridge.dart';

/// 已取件归档页 —— 对应需求「历史长久记录在记事本」
///
/// ## 关于「写入手机自带备忘录」的技术说明
///
/// 系统**不允许**第三方 App 静默写入手机自带备忘录：
/// - 华为/小米/OPPO/vivo 的备忘录 App 均未开放写入接口
/// - Android 没有标准的"写备忘录" API
///
/// 所以本页面提供两种可行路径：
///  1. **导出为 .txt** 到手机「文档」目录，用户用备忘录打开
///  2. **分享文本** —— 部分备忘录支持作为分享目标接收
class ArchivePage extends StatefulWidget {
  const ArchivePage({super.key});

  @override
  State<ArchivePage> createState() => _ArchivePageState();
}

class _ArchivePageState extends State<ArchivePage> {
  List<PickupCode> _items = [];
  bool _loading = true;
  bool _showAll = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final items = await BeaconBridge.getArchived();
    if (!mounted) return;
    setState(() {
      _items = items;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final shown = _showAll ? _items : _items.take(20).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('已取件归档'),
        actions: [
          IconButton(
            icon: const Icon(Icons.note_alt_outlined),
            tooltip: '导出到记事本',
            onPressed: _showExportSheet,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _items.isEmpty
              ? _buildEmpty()
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      _buildHeader(),
                      const SizedBox(height: 12),
                      ...shown.map(_buildTile),
                      if (_items.length > 20 && !_showAll)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          child: OutlinedButton(
                            onPressed: () => setState(() => _showAll = true),
                            child: Text('显示全部 ${_items.length} 条'),
                          ),
                        ),
                    ],
                  ),
                ),
    );
  }

  Widget _buildHeader() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.inventory_2_outlined, color: Colors.grey.shade600),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '共 ${_items.length} 条记录',
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w500),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '长期保存，可导出到手机备忘录',
                    style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                  ),
                ],
              ),
            ),
            TextButton(
              onPressed: _showExportSheet,
              child: const Text('导出'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTile(PickupCode item) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        onTap: () {
          Clipboard.setData(ClipboardData(text: item.code));
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('已复制：${item.code}')),
          );
        },
        title: Text(
          item.code,
          style: const TextStyle(
            fontSize: 17,
            fontWeight: FontWeight.w600,
            letterSpacing: 1,
          ),
        ),
        subtitle: Text(
          '${item.courierName} · 收到 ${_formatTime(item.createdAt)}'
          '${item.pickedAt != null ? ' · 已取 ${_formatTime(item.pickedAt!)}' : ''}',
          style: const TextStyle(fontSize: 12),
        ),
        trailing: PopupMenuButton<String>(
          onSelected: (v) {
            if (v == 'restore') {
              BeaconBridge.unmarkPicked(item.id).then((_) {
                _load();
                BeaconBridge.refreshWidget();
              });
            } else if (v == 'delete') {
              BeaconBridge.deleteCode(item.id).then((_) => _load());
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(
              value: 'restore',
              child: Row(
                children: [
                  Icon(Icons.undo, size: 18),
                  SizedBox(width: 8),
                  Text('恢复到待取'),
                ],
              ),
            ),
            PopupMenuItem(
              value: 'delete',
              child: Row(
                children: [
                  Icon(Icons.delete_outline, size: 18),
                  SizedBox(width: 8),
                  Text('彻底删除'),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmpty() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.inventory_2_outlined, size: 56, color: Colors.grey.shade300),
          const SizedBox(height: 12),
          Text('还没有归档记录',
              style: TextStyle(color: Colors.grey.shade500)),
          const SizedBox(height: 4),
          Text('点击取件码右侧的勾选即可归档',
              style: TextStyle(fontSize: 12, color: Colors.grey.shade400)),
        ],
      ),
    );
  }

  String _formatTime(int ms) {
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    final now = DateTime.now();
    final sameDay = dt.year == now.year &&
        dt.month == now.month &&
        dt.day == now.day;
    if (sameDay) {
      return '${dt.hour.toString().padLeft(2, '0')}:'
          '${dt.minute.toString().padLeft(2, '0')}';
    }
    return '${dt.month}/${dt.day} '
        '${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }

  // ── 导出到记事本 ────────────────────────────────────────

  void _showExportSheet() {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text(
                '导出到记事本',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w500),
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                '系统不允许直接写入自带备忘录。可导出为文本文件后用备忘录打开，'
                '或直接分享给备忘录应用。',
                style: TextStyle(fontSize: 12, height: 1.4),
              ),
            ),
            const SizedBox(height: 12),
            ListTile(
              leading: const Icon(Icons.picture_as_pdf_outlined),
              title: const Text('预览全部记录'),
              onTap: () {
                Navigator.pop(ctx);
                _previewMemo();
              },
            ),
            ListTile(
              leading: const Icon(Icons.file_download_outlined),
              title: const Text('导出为文本文件'),
              onTap: () {
                Navigator.pop(ctx);
                _exportFile();
              },
            ),
            ListTile(
              leading: const Icon(Icons.share_outlined),
              title: const Text('分享到其他应用'),
              onTap: () {
                Navigator.pop(ctx);
                _shareText();
              },
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Future<void> _previewMemo() async {
    final text = await BeaconBridge.exportMemoText();
    if (!mounted) return;
    if (text == null) {
      _toast('暂无记录');
      return;
    }
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('取件码记录'),
        content: SizedBox(
          width: double.maxFinite,
          height: 400,
          child: SingleChildScrollView(child: Text(text)),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              _shareText();
            },
            child: const Text('分享'),
          ),
        ],
      ),
    );
  }

  Future<void> _exportFile() async {
    _toast('正在导出…');
    final uri = await BeaconBridge.exportMemoFile();
    if (!mounted) return;
    if (uri == null) {
      _toast('暂无记录可导出');
      return;
    }
    _toast('已导出到手机「文档」目录');
  }

  Future<void> _shareText() async {
    final text = await BeaconBridge.exportMemoText();
    if (!mounted) return;
    if (text == null) {
      _toast('暂无记录');
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    _toast('已复制到剪贴板，可在备忘录中粘贴');
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}