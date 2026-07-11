import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rfb/flutter_rfb.dart';
import 'package:droiddesk/theme/droid_theme.dart';

/// Full-screen VNC desktop viewer with:
/// - Auto-rotation (landscape support)
/// - Full-screen direct touch (whole screen = trackpad, like Termux:X11)
/// - Keyboard toggle button
/// - Floating overlay control bar
class VncDesktopScreen extends StatefulWidget {
  const VncDesktopScreen({super.key});

  @override
  State<VncDesktopScreen> createState() => _VncDesktopScreenState();
}

class _VncDesktopScreenState extends State<VncDesktopScreen> {
  bool _showControls = true;
  final bool _connected = true;
  int _retryKey = 0;
  bool _keyboardVisible = false;
  InputMode _inputMode = InputMode.trackpad;
  double _trackpadSensitivity = 3.5;
  final FocusNode _focusNode = FocusNode();
  final Set<int> _activePointers = {};

  @override
  void initState() {
    super.initState();
    // Allow all orientations for the desktop viewer
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    // Go full-screen immersive
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  @override
  void dispose() {
    // Restore portrait-only and system UI when leaving
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    _focusNode.dispose();
    super.dispose();
  }

  void _toggleKeyboard() {
    setState(() {
      _keyboardVisible = !_keyboardVisible;
    });
    if (_keyboardVisible) {
      _focusNode.requestFocus();
      SystemChannels.textInput.invokeMethod('TextInput.show');
    } else {
      _focusNode.unfocus();
      SystemChannels.textInput.invokeMethod('TextInput.hide');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      resizeToAvoidBottomInset: true,
      body: Stack(
        children: [
          // ── The VNC Viewer (full screen, pinch-to-zoom) ──
          Positioned.fill(
            child: Listener(
              onPointerDown: (event) {
                _activePointers.add(event.pointer);
                if (_activePointers.length == 3) {
                  setState(() {
                    _showControls = !_showControls;
                  });
                }
              },
              onPointerUp: (event) => _activePointers.remove(event.pointer),
              onPointerCancel: (event) => _activePointers.remove(event.pointer),
              child: Builder(
                builder: (context) {
                  if (!_connected) {
                    return const Center(
                      child: Text(
                        'Disconnected',
                        style: TextStyle(color: Colors.white, fontSize: 18),
                      ),
                    );
                  }

                  return RemoteFrameBufferWidget(
                    key: ValueKey(_retryKey),
                    hostName: '127.0.0.1',
                    port: 5900,
                    password: 'password',
                    inputMode: _inputMode,
                    trackpadSensitivity: _trackpadSensitivity,
                    connectingWidget: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const CircularProgressIndicator(
                          color: DroidTheme.primary,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Starting Desktop...',
                          style: DroidTheme.bodyLg.copyWith(
                            color: Colors.white70,
                          ),
                        ),
                      ],
                    ),
                    onError: (error) {
                      debugPrint('VNC Connection Error: $error');
                      Future.delayed(const Duration(seconds: 2), () {
                        if (mounted && _connected) {
                          setState(() {
                            _retryKey++;
                          });
                        }
                      });
                    },
                  );
                },
              ),
            ),
          ),

          // ── Floating Control Bar (top) ──
          if (_showControls)
            Positioned(
              top: MediaQuery.of(context).padding.top + 8,
              left: 12,
              right: 12,
              child: _buildControlBar(),
            ),

          // ── Tap zone to bring controls back (invisible, top-right corner) ──
          if (!_showControls)
            Positioned(
              top: 0,
              right: 0,
              child: GestureDetector(
                onTap: () {
                  setState(() {
                    _showControls = true;
                  });
                },
                child: Container(
                  width: 60,
                  height: 60,
                  color: Colors.transparent,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildControlBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: DroidTheme.surfaceBorder.withValues(alpha: 0.5),
        ),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // Back button
          _controlButton(
            icon: Icons.close_rounded,
            tooltip: 'Exit Desktop',
            onTap: () => Navigator.pop(context),
          ),

          // Keyboard toggle
          _controlButton(
            icon: _keyboardVisible
                ? Icons.keyboard_hide_rounded
                : Icons.keyboard_rounded,
            tooltip: _keyboardVisible ? 'Hide Keyboard' : 'Show Keyboard',
            onTap: _toggleKeyboard,
            highlighted: _keyboardVisible,
          ),

          // Input Mode toggle
          _controlButton(
            icon: _inputMode == InputMode.trackpad
                ? Icons.mouse_rounded
                : Icons.touch_app_rounded,
            tooltip: _inputMode == InputMode.trackpad
                ? 'Switch to Direct Touch'
                : 'Switch to Trackpad Mode',
            onTap: () {
              setState(() {
                _inputMode = _inputMode == InputMode.trackpad
                    ? InputMode.direct
                    : InputMode.trackpad;
              });
            },
            highlighted: _inputMode == InputMode.trackpad,
          ),

          // Trackpad Settings (only show in trackpad mode)
          if (_inputMode == InputMode.trackpad)
            _controlButton(
              icon: Icons.tune_rounded,
              tooltip: 'Trackpad Sensitivity',
              onTap: () {
                showDialog(
                  context: context,
                  builder: (context) {
                    return StatefulBuilder(
                      builder: (context, setDialogState) {
                        return AlertDialog(
                          backgroundColor: DroidTheme.surface,
                          title: const Text(
                            'Pointer Speed',
                            style: TextStyle(color: Colors.white),
                          ),
                          content: SizedBox(
                            width: 300,
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Slider(
                                  value: _trackpadSensitivity,
                                  min: 0.5,
                                  max: 10.0,
                                  activeColor: DroidTheme.primary,
                                  onChanged: (val) {
                                    setDialogState(() {
                                      _trackpadSensitivity = val;
                                    });
                                    setState(() {
                                      _trackpadSensitivity = val;
                                    });
                                  },
                                ),
                                Text(
                                  '${_trackpadSensitivity.toStringAsFixed(1)}x',
                                  style: const TextStyle(color: Colors.white70),
                                ),
                              ],
                            ),
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context),
                              child: const Text(
                                'Done',
                                style: TextStyle(color: DroidTheme.primary),
                              ),
                            ),
                          ],
                        );
                      },
                    );
                  },
                );
              },
            ),

          // Hide controls
          _controlButton(
            icon: Icons.visibility_off_rounded,
            tooltip: 'Hide Controls (double-tap to show)',
            onTap: () {
              setState(() {
                _showControls = false;
              });
            },
          ),
        ],
      ),
    );
  }

  Widget _controlButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback onTap,
    bool highlighted = false,
  }) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: highlighted
            ? DroidTheme.primary.withValues(alpha: 0.3)
            : Colors.transparent,
        borderRadius: BorderRadius.circular(10),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Icon(
              icon,
              color: highlighted ? DroidTheme.primary : Colors.white70,
              size: 22,
            ),
          ),
        ),
      ),
    );
  }
}
