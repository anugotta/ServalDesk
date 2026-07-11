import 'package:flutter/material.dart';
import 'package:droiddesk/services/platform_bridge.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  String _installType = 'minimal'; // 'minimal' or 'full'
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=download, 4=install, 5=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  final bool _isInstallingDE = false;
  String? _statusMessage;
  
  // Terminal history
  final List<String> _terminalOutput = ['DroidDesk Linux Terminal\nType commands below.\n'];
  List<String> get terminalOutput => _terminalOutput;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Getters ──
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  String get installType => _installType;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;

  bool get isSetupComplete => _isBootstrapped && _installedDistro.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return 'Unknown GPU';
  }

  // ── Initialization ──

  Future<void> initialize() async {
    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isDownloading) {
          _isDownloading = false;
          runExtraction();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isExtracting) {
          _isExtracting = false;
          refreshStatus(); // Updates _isBootstrapped and completes setup
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = status;
      _statusMessage = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isExtracting) {
          _isExtracting = false;
          refreshStatus();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');
      
      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      final lines = cleanedText.split('\n');
      
      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      // Notify listeners to update terminal UI if open
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSelectedDE(String de) {
    _selectedDE = de;
    notifyListeners();
  }

  void setInstallType(String type) {
    _installType = type;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> runSetup() async {
    try {
      _errorMessage = null;

      // Step 1: Bootstrap (extract proot binary)
      _setupStep = 3;
      notifyListeners();
      await DroidDeskPlatform.setupBootstrap();

      // Step 2: Download rootfs
      _isDownloading = true;
      _downloadProgress = 0.0;
      notifyListeners();
      await DroidDeskPlatform.downloadRootfs(_selectedDistro);

      // Download runs in background — callbacks update progress
      // Wait for download completion is handled by the callback

    } catch (e) {
      _errorMessage = 'Setup failed: $e';
      _isDownloading = false;
      notifyListeners();
    }
  }

  Future<void> runExtraction() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _errorMessage = null;
      notifyListeners();
      await DroidDeskPlatform.extractRootfs();
    } catch (e) {
      _errorMessage = 'Extraction failed: $e';
      _isExtracting = false;
      notifyListeners();
    }
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true; // Re-use the extracting state for UI purposes
      _extractProgress = 0.0;
      _statusMessage = 'Installing Desktop Environment...';
      _errorMessage = null;
      notifyListeners();
      await DroidDeskPlatform.installDesktopEnvironment(_selectedDE, type: _installType);
    } catch (e) {
      _errorMessage = 'Installation failed: $e';
      _isExtracting = false;
      notifyListeners();
    }
  }

  // ── Session Control ──

  Future<void> startLinux({String mode = 'vnc', int width = 1920, int height = 1080}) async {
    try {
      _errorMessage = null;
      await DroidDeskPlatform.startLinux(de: _selectedDE, mode: mode, width: width, height: height);
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = 'Failed to launch desktop activity: $e';
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    }
  }

  Future<String> executeCommand(String command) async {
    try {
      _terminalOutput.add('\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
    }
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add('DroidDesk Linux Terminal\nType commands below.\n');
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}
