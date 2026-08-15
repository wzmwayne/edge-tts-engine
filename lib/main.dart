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
  int _usageTaps = 0;

  @override
  void initState() {
    super.initState();
    _loadConfig();
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
            tooltip: '刷新音色',
            onPressed: _loading ? null : _refreshVoices,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: [
          _buildUsageCard(),
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

  Future<void> _enableLogging() async {
    try {
      final path = await _channel.invokeMethod<String>('enableLogging');
      _setStatus('日志模式已启用，日志文件：$path');
    } catch (e) {
      _setStatus('启用日志失败: $e');
    }
  }

  void _onUsageTap() {
    _usageTaps++;
    if (_usageTaps >= 7) {
      _usageTaps = 0;
      _enableLogging();
    }
  }

  Widget _buildUsageCard() {
    return GestureDetector(
      onTap: _onUsageTap,
      child: Card(
        margin: const EdgeInsets.all(12),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: const [
                  Icon(Icons.info_outline),
                  SizedBox(width: 8),
                  Text('使用说明', style: TextStyle(fontWeight: FontWeight.bold)),
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