import 'dart:convert';
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterError.onError = (details) {
    _logFatal('FlutterError: ${details.exception}\n${details.stack ?? ''}');
    runApp(FatalErrorApp(error: details.exception.toString()));
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    _logFatal('PlatformDispatcher: $error\n$stack');
    runApp(FatalErrorApp(error: '$error'));
    return true;
  };
  runApp(const EdgeTtsApp());
}

/// 尽力把致命错误上报到原生日志文件（连点使用说明启用日志模式后生效）。
Future<void> _logFatal(String msg) async {
  try {
    const MethodChannel('com.edge.tts/engine').invokeMethod('logFatal', msg);
  } catch (_) {}
}

/// Flutter 侧致命错误兜底页。
class FatalErrorApp extends StatelessWidget {
  const FatalErrorApp({super.key, required this.error});

  final String error;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.red)),
      home: Scaffold(
        appBar: AppBar(title: const Text('应用发生错误')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  child: SelectableText(
                    error,
                    style: const TextStyle(
                      fontSize: 12,
                      fontFamily: 'monospace',
                      color: Colors.redAccent,
                    ),
                  ),
                ),
              ),
              FilledButton(
                onPressed: () => SystemNavigator.pop(),
                child: const Text('退出'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class EdgeTtsApp extends StatelessWidget {
  const EdgeTtsApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Edge TTS 引擎',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      home: const HomePage(),
    );
  }
}

class VoiceInfo {
  const VoiceInfo({
    required this.shortName,
    required this.locale,
    required this.gender,
    required this.friendlyName,
  });

  factory VoiceInfo.fromJson(Map<String, dynamic> j) => VoiceInfo(
        shortName: j['ShortName'] as String? ?? '',
        locale: j['Locale'] as String? ?? '',
        gender: j['Gender'] as String? ?? '',
        friendlyName: j['FriendlyName'] as String? ?? '',
      );

  final String shortName;
  final String locale;
  final String gender;
  final String friendlyName;
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _channel = MethodChannel('com.edge.tts/engine');

  List<VoiceInfo> _voices = [];
  String _selected = 'zh-CN-XiaoxiaoNeural';
  double _rate = 100;
  double _pitch = 100;
  String _search = '';
  bool _loading = false;
  String _status = '';
  String _langFilter = 'all';
  bool _usageHidden = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
    _loadSettings();
    _loadVoices();
  }

  Future<void> _loadConfig() async {
    try {
      final cfg = await _channel.invokeMethod<Map<dynamic, dynamic>>('getConfig');
      if (cfg == null) return;
      setState(() {
        _selected = (cfg['voice'] as String?) ?? _selected;
        _rate = ((cfg['rate'] as num?) ?? 100).toDouble();
        _pitch = ((cfg['pitch'] as num?) ?? 100).toDouble();
      });
    } catch (e) {
      _setStatus('读取配置失败: $e');
    }
  }

  Future<void> _loadSettings() async {
    try {
      final s = await _channel.invokeMethod<Map<dynamic, dynamic>>('getSettings');
      if (s == null) return;
      setState(() {
        _langFilter = (s['langFilter'] as String?) ?? 'all';
        _usageHidden = (s['usageHidden'] as bool?) ?? false;
      });
    } catch (_) {}
  }

  Future<void> _openSettings() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SettingsPage(
          channel: _channel,
          langFilter: _langFilter,
        ),
      ),
    );
    await _loadSettings();
  }

  Future<void> _loadVoices() async {
    setState(() => _loading = true);
    try {
      final json = await _channel.invokeMethod<String>('getVoices');
      _applyVoices(json ?? '');
    } catch (e) {
      _setStatus('读取音色失败: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _refreshVoices() async {
    setState(() => _loading = true);
    _setStatus('正在从微软拉取音色列表…');
    try {
      final json = await _channel.invokeMethod<String>('refreshVoices');
      _applyVoices(json ?? '');
      _setStatus('已更新 ${_voices.length} 个音色');
    } catch (e) {
      _setStatus('拉取失败，使用内置音色: $e');
      await _loadVoices();
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _applyVoices(String json) {
    try {
      final list = jsonDecode(json) as List<dynamic>;
      final voices = list
          .map((e) => VoiceInfo.fromJson(e as Map<String, dynamic>))
          .where((v) => v.shortName.isNotEmpty)
          .toList();
      if (voices.isEmpty) return;
      setState(() => _voices = voices);
      if (!voices.any((v) => v.shortName == _selected)) {
        setState(() => _selected = voices.first.shortName);
      }
    } catch (e) {
      _setStatus('音色解析失败: $e');
    }
  }

  Future<void> _selectVoice(VoiceInfo v) async {
    setState(() => _selected = v.shortName);
    _setStatus('已选择 ${v.shortName}');
    await _channel.invokeMethod('setConfig', {
      'voice': v.shortName,
      'rate': _rate.round(),
      'pitch': _pitch.round(),
    });
  }

  Future<void> _saveParams() async {
    await _channel.invokeMethod('setConfig', {
      'voice': _selected,
      'rate': _rate.round(),
      'pitch': _pitch.round(),
    });
  }

  Future<void> _test() async {
    setState(() => _loading = true);
    _setStatus('正在朗读…');
    try {
      await _channel.invokeMethod('speak', {
        'text': '你好，这是微软 Edge 语音合成引擎的测试朗读。Hello, this is a test.',
      });
      _setStatus('朗读完成');
    } catch (e) {
      _setStatus('朗读失败: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _setStatus(String s) {
    if (mounted) setState(() => _status = s);
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _voices
        .where((v) => _matchesLangFilter(v))
        .where((v) =>
            v.shortName.toLowerCase().contains(_search.toLowerCase()) ||
            v.locale.toLowerCase().contains(_search.toLowerCase()) ||
            v.friendlyName.toLowerCase().contains(_search.toLowerCase()))
        .toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Edge TTS 引擎'),
        actions: [
          IconButton(
            tooltip: '设置',
            onPressed: _openSettings,
            icon: const Icon(Icons.settings),
          ),
          IconButton(
            tooltip: '刷新音色',
            onPressed: _loading ? null : _refreshVoices,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: [
          if (!_usageHidden) _buildUsageCard(),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: TextField(
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                hintText: '搜索音色 / 语言…',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              onChanged: (s) => setState(() => _search = s),
            ),
          ),
          if (_status.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(_status,
                  style: TextStyle(color: Theme.of(context).colorScheme.primary)),
            ),
          Expanded(
            child: _loading && _voices.isEmpty
                ? const Center(child: CircularProgressIndicator())
                : filtered.isEmpty
                    ? const Center(child: Text('未找到音色，请点击右上角刷新'))
                    : ListView.builder(
                        itemCount: filtered.length,
                        itemBuilder: (context, i) {
                          final v = filtered[i];
                          final selected = v.shortName == _selected;
                          return ListTile(
                            leading: Radio<String>(
                              value: v.shortName,
                              groupValue: _selected,
                              onChanged: (_) => _selectVoice(v),
                            ),
                            title: Text(
                              v.shortName,
                              style: TextStyle(
                                fontWeight:
                                    selected ? FontWeight.bold : FontWeight.normal,
                              ),
                            ),
                            subtitle: Text('${v.locale} · ${v.gender}'),
                            trailing: selected
                                ? const Icon(Icons.check, color: Colors.green)
                                : null,
                            onTap: () => _selectVoice(v),
                          );
                        },
                      ),
          ),
          _buildParamsBar(),
        ],
      ),
    );
  }

  bool _matchesLangFilter(VoiceInfo v) {
    if (_langFilter == 'all') return true;
    if (_langFilter == 'system') {
      return v.locale.toLowerCase().startsWith(
            PlatformDispatcher.instance.locale.languageCode.toLowerCase(),
          );
    }
    return v.locale.toLowerCase().startsWith(_langFilter.toLowerCase());
  }

  Future<void> _hideUsage() async {
    await _channel.invokeMethod('setSettings', {'usageHidden': true});
    if (mounted) setState(() => _usageHidden = true);
  }

  Widget _buildUsageCard() {
    return Card(
      margin: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.info_outline),
                const SizedBox(width: 8),
                const Expanded(
                  child: Text('使用说明',
                      style: TextStyle(fontWeight: FontWeight.bold)),
                ),
                IconButton(
                  tooltip: '关闭使用说明',
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: _hideUsage,
                ),
              ],
            ),
            const SizedBox(height: 6),
            const Text(
              '1. 安装并打开本应用\n'
              '2. 选择音色后点击「打开系统 TTS 设置」\n'
              '3. 在 设置 → 无障碍 → 文字转语音输出 中把默认引擎切换为「Edge TTS 引擎」\n'
              '4. 任意应用（如 Legado 阅读器）即可使用 Edge 在线语音朗读\n'
              '提示：需要联网；音色列表可点右上角刷新拉取全部 400+ 音色。',
              style: TextStyle(fontSize: 13, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildParamsBar() {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                const Text('语速'),
                Expanded(
                  child: Slider(
                    value: _rate,
                    min: 50,
                    max: 200,
                    divisions: 30,
                    label: '${_rate.round()}',
                    onChanged: (v) => setState(() => _rate = v),
                    onChangeEnd: (_) => _saveParams(),
                  ),
                ),
                Text('${_rate.round()}'),
              ],
            ),
            Row(
              children: [
                const Text('语调'),
                Expanded(
                  child: Slider(
                    value: _pitch,
                    min: 50,
                    max: 200,
                    divisions: 30,
                    label: '${_pitch.round()}',
                    onChanged: (v) => setState(() => _pitch = v),
                    onChangeEnd: (_) => _saveParams(),
                  ),
                ),
                Text('${_pitch.round()}'),
              ],
            ),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _loading ? null : _test,
                    icon: const Icon(Icons.volume_up),
                    label: const Text('试听'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () =>
                        _channel.invokeMethod('openTtsSettings'),
                    icon: const Icon(Icons.settings),
                    label: const Text('打开系统 TTS 设置'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 设置页：流式（beta，仅开关与存储）、默认音色语言过滤、调试日志开关。
class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.channel,
    required this.langFilter,
  });

  final MethodChannel channel;
  final String langFilter;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late bool _streaming;
  late bool _debug;
  late String _langFilter;
  bool _dirty = false;

  @override
  void initState() {
    super.initState();
    _langFilter = widget.langFilter;
    _streaming = false;
    _debug = false;
    _load();
  }

  Future<void> _load() async {
    try {
      final s = await widget.channel
          .invokeMethod<Map<dynamic, dynamic>>('getSettings');
      if (!mounted || s == null) return;
      if (_dirty) return;
      setState(() {
        _streaming = (s['streaming'] as bool?) ?? false;
        _debug = (s['debug'] as bool?) ?? false;
        _langFilter = (s['langFilter'] as String?) ?? _langFilter;
      });
    } catch (_) {}
  }

  void _markDirty() {
    _dirty = true;
    _save();
  }

  Future<void> _save() async {
    try {
      await widget.channel.invokeMethod('setSettings', {
        'streaming': _streaming,
        'debug': _debug,
        'langFilter': _langFilter,
      });
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(const SnackBar(
            content: Text('设置已保存'),
            duration: Duration(seconds: 1),
          ));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text('保存失败: $e')));
      }
    }
  }

  List<DropdownMenuItem<String>> _langOptions() {
    const labels = {
      'all': '所有',
      'system': '系统',
      'zh-CN': '简体中文',
      'zh-HK': '粤语(香港)',
      'zh-TW': '繁体中文',
      'en-US': '英语(美国)',
      'en-GB': '英语(英国)',
      'ja-JP': '日语',
      'ko-KR': '韩语',
      'fr-FR': '法语',
      'de-DE': '德语',
      'es-ES': '西班牙语',
      'ru-RU': '俄语',
    };
    final options = ['all', 'system', 'zh-CN', 'zh-HK', 'zh-TW', 'en-US',
      'en-GB', 'ja-JP', 'ko-KR', 'fr-FR', 'de-DE', 'es-ES', 'ru-RU'];
    return options
        .map((code) => DropdownMenuItem(
              value: code,
              child: Text('${labels[code] ?? code} ($code)'),
            ))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('设置'),
      ),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('流式合成'),
            subtitle: const Text('Beta：边收边播，首帧到达即开始播放（需系统 TTS 支持）'),
            value: _streaming,
            onChanged: (v) {
              setState(() => _streaming = v);
              _markDirty();
            },
          ),
          const Divider(),
          ListTile(
            title: const Text('默认音色语言'),
            subtitle: const Text('首页音色列表默认展示的语言范围'),
            trailing: DropdownButton<String>(
              value: _langFilter,
              items: _langOptions(),
              onChanged: (v) {
                if (v == null) return;
                setState(() => _langFilter = v);
                _markDirty();
              },
            ),
          ),
          const Divider(),
          SwitchListTile(
            title: const Text('调试日志'),
            subtitle: const Text('写入 /sdcard/Download/edge-tts-logs/edge-tts.log.txt'),
            value: _debug,
            onChanged: (v) {
              setState(() => _debug = v);
              _markDirty();
            },
          ),
        ],
      ),
    );
  }
}