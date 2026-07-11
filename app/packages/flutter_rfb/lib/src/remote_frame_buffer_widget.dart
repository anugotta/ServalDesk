import 'dart:async';
import 'dart:isolate';
import 'dart:typed_data';
import 'dart:ui';

import 'package:dart_rfb/dart_rfb.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' hide Image;
import 'package:flutter/services.dart';
import 'package:flutter_rfb/src/child_size_notifier_widget.dart';
import 'package:flutter_rfb/src/extensions/logical_keyboard_key_extensions.dart';
import 'package:flutter_rfb/src/remote_frame_buffer_client_isolate.dart';
import 'package:flutter_rfb/src/remote_frame_buffer_gesture_detector.dart';
import 'package:flutter_rfb/src/remote_frame_buffer_isolate_messages.dart';
import 'package:fpdart/fpdart.dart' hide State;
import 'package:logging/logging.dart';

final Logger _logger = Logger('RemoteFrameBufferWidget');

/// This widget displays the framebuffer associated with the RFB session.
/// On creation, it tries to establish a connection with the remote server
/// in an isolate. On success, it runs the read loop in that isolate.
class RemoteFrameBufferWidget extends StatefulWidget {
  final Option<Widget> _connectingWidget;
  final String _hostName;
  final Option<void Function(Object error)> _onError;
  final Option<String> _password;
  final int _port;
  final InputMode inputMode;
  final double trackpadSensitivity;

  /// Immediately tries to establish a connection to a remote server at
  /// [hostName]:[port], optionally using [password].
  RemoteFrameBufferWidget({
    super.key,
    final Widget? connectingWidget,
    required final String hostName,
    final void Function(Object error)? onError,
    final String? password,
    final int port = 5900,
    this.inputMode = InputMode.trackpad,
    this.trackpadSensitivity = 3.5,
  })  : _connectingWidget = optionOf(connectingWidget),
        _hostName = hostName,
        _onError = optionOf(onError),
        _password = optionOf(password),
        _port = port;

  @override
  State<RemoteFrameBufferWidget> createState() =>
      RemoteFrameBufferWidgetState();
}

@visibleForTesting
class RemoteFrameBufferWidgetState extends State<RemoteFrameBufferWidget> {
  late Timer _clipBoardMonitorTimer;
  Option<ByteData> _frameBuffer = none();
  Option<Image> _image = none();
  Option<Isolate> _isolate = none();
  Option<SendPort> _isolateSendPort = none();
  final ValueNotifier<Size> _sizeValueNotifier = ValueNotifier<Size>(Size.zero);
  Option<StreamSubscription<Object?>> _streamSubscription = none();
  
  // 60 FPS Render Throttler
  Timer? _renderTimer;
  bool _needsRender = false;
  bool _isRendering = false;
  int _fbWidth = 0;
  int _fbHeight = 0;

  @override
  Widget build(final BuildContext context) => _frameBuffer
      .flatMap(
        (final ByteData frameBuffer) => frameBuffer.buffer
                .asUint8List(
                  frameBuffer.offsetInBytes,
                  frameBuffer.lengthInBytes,
                )
                .where((final int byte) => byte != 0)
                .isNotEmpty
            ? _image
            : none<Image>(),
      )
      .match(
        _buildConnecting,
        (final Image image) => _buildImage(image: image),
      );

  @override
  void dispose() {
    _clipBoardMonitorTimer.cancel();
    _streamSubscription.match(
      () {},
      (final StreamSubscription<Object?> subscription) =>
          unawaited(subscription.cancel()),
    );
    _renderTimer?.cancel();
    _image.match(
      () {},
      (final Image image) => image.dispose(),
    );
    _isolate.match(
      () {},
      (final Isolate isolate) => isolate.kill(),
    );
    RawKeyboard.instance.removeListener(_rawKeyEventListener);
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _monitorClipBoard();
    RawKeyboard.instance.addListener(_rawKeyEventListener);
    unawaited(_initAsync());
  }

  Widget _buildConnecting() => widget._connectingWidget.getOrElse(
        () => const Center(
          child: CircularProgressIndicator(),
        ),
      );

  SizeTrackingWidget _buildImage({required final Image image}) =>
      SizeTrackingWidget(
        sizeValueNotifier: _sizeValueNotifier,
        child: RemoteFrameBufferGestureDetector(
          image: image,
          remoteFrameBufferWidgetSize: _sizeValueNotifier.value,
          sendPort: _isolateSendPort,
          inputMode: widget.inputMode,
          trackpadSensitivity: widget.trackpadSensitivity,
          child: RawImage(image: image),
        ),
      );

  void _decodeAndUpdateImage({
    required final ByteData frameBuffer,
  }) {
      if (!mounted) return;
      _isRendering = true;
      decodeImageFromPixels(
        frameBuffer.buffer.asUint8List(),
        _fbWidth,
        _fbHeight,
        PixelFormat.bgra8888,
        (final Image result) {
          if (mounted) {
            setState(
              () {
                _image.match(
                  () {},
                  (final Image image) => image.dispose(),
                );
                _image = some(result);
                _isRendering = false;
              },
            );
            _isolateSendPort.match(
              () {},
              (final SendPort sendPort) => sendPort.send(
                const RemoteFrameBufferIsolateSendMessage
                    .frameBufferUpdateRequest(),
              ),
            );
          }
        },
      );
  }

  Future<void> _handleFrameBufferUpdateMessage({
    required final RemoteFrameBufferIsolateReceiveMessageFrameBufferUpdate update,
  }) async {
        _isolateSendPort = some(update.sendPort);
        if (_frameBuffer.isNone()) {
          _frameBuffer = some(
            ByteData(
              update.frameBufferHeight * update.frameBufferWidth * 4,
            ),
          );
        }
        
        final ByteData? fb = _frameBuffer.toNullable();
        if (fb != null) {
            final Stopwatch stopwatch = Stopwatch()..start();
            
            for (final RemoteFrameBufferClientUpdateRectangle rectangle
                in update.update.rectangles) {
              
              if (stopwatch.elapsedMilliseconds > 8) {
                await Future.delayed(Duration.zero);
                stopwatch.reset();
              }
              
              rectangle.encodingType.when(
                copyRect: () {
                  final int sourceX = rectangle.byteData.getUint16(0);
                  final int sourceY = rectangle.byteData.getUint16(2);
                  
                  final int rowBytes = rectangle.width * 4;
                  final int fbWidthBytes = update.frameBufferWidth * 4;
                  
                  final Uint8List fbBytes = fb.buffer.asUint8List(fb.offsetInBytes, fb.lengthInBytes);
                  final Uint8List copiedBytes = Uint8List(rectangle.height * rowBytes);
                  
                  for (int row = 0; row < rectangle.height; row++) {
                    final int srcOffset = ((sourceY + row) * fbWidthBytes) + (sourceX * 4);
                    final int destOffset = row * rowBytes;
                    copiedBytes.setRange(destOffset, destOffset + rowBytes, fbBytes, srcOffset);
                  }

                  updateFrameBuffer(
                    frameBuffer: fb,
                    frameBufferSize: Size(
                      update.frameBufferWidth.toDouble(),
                      update.frameBufferHeight.toDouble(),
                    ),
                    rectangle: rectangle.copyWith(
                      encodingType: const RemoteFrameBufferEncodingType.raw(),
                      byteData: ByteData.sublistView(copiedBytes),
                    ),
                  );
                },
                raw: () => updateFrameBuffer(
                  frameBuffer: fb,
                  frameBufferSize: Size(
                    update.frameBufferWidth.toDouble(),
                    update.frameBufferHeight.toDouble(),
                  ),
                  rectangle: rectangle,
                ),
                unsupported: (final ByteData bytes) {},
              );
            }
            
            _fbWidth = update.frameBufferWidth;
            _fbHeight = update.frameBufferHeight;
            _needsRender = true;
        }
  }

  /// Initializes logic that requires to be run asynchronous.
  Future<void> _initAsync() async {
    final ReceivePort receivePort = ReceivePort();
    _streamSubscription = some(
      receivePort.listen(
        (final Object? message) {
          // Error, first is error, second is stacktrace or null
          if (message is List) {
            widget._onError.match(
              () {},
              (final void Function(Object error) onError) =>
                  onError(message.first),
            );
          } else if (message is RemoteFrameBufferIsolateReceiveMessage) {
            message.map(
              clipBoardUpdate: (
                final RemoteFrameBufferIsolateReceiveMessageClipBoardUpdate
                    update,
              ) =>
                  Clipboard.setData(ClipboardData(text: update.text)),
              frameBufferUpdate: (
                final RemoteFrameBufferIsolateReceiveMessageFrameBufferUpdate
                    update,
              ) {
                _handleFrameBufferUpdateMessage(update: update);
              },
            );
          }
        },
      ),
    );
    _logger.info('Spawning new isolate for RFB client');
    _isolate = some(
      await Isolate.spawn(
        startRemoteFrameBufferClient,
        RemoteFrameBufferIsolateInitMessage(
          hostName: widget._hostName,
          password: widget._password,
          port: widget._port,
          sendPort: receivePort.sendPort,
        ),
        onError: receivePort.sendPort,
      ),
    );

    // Start 60 FPS Render Loop (1000ms / 60 = ~16ms)
    _renderTimer = Timer.periodic(const Duration(milliseconds: 16), (_) {
      if (_needsRender && !_isRendering) {
        _needsRender = false;
        _frameBuffer.match(
          () {},
          (fb) => _decodeAndUpdateImage(frameBuffer: fb),
        );
      }
    });
  }

  void _monitorClipBoard() {
    Option<String> lastClipBoardContent = none();
    _clipBoardMonitorTimer = Timer.periodic(
      const Duration(seconds: 1),
      (final _) async {
        optionOf(await Clipboard.getData(Clipboard.kTextPlain))
            .flatMap((final ClipboardData data) => optionOf(data.text))
            .filter(
              (final String text) => lastClipBoardContent.match(
                () => true,
                (final String lastClipBoardContent) =>
                    lastClipBoardContent != text,
              ),
            )
            .match(
              () {},
              (final String text) => _isolateSendPort.match(
                () {},
                (final SendPort sendPort) {
                  lastClipBoardContent = some(text);
                  sendPort.send(
                    RemoteFrameBufferIsolateSendMessage.clipBoardUpdate(
                      text: text,
                    ),
                  );
                },
              ),
            );
      },
    );
  }

  void _rawKeyEventListener(final RawKeyEvent rawKeyEvent) {
      _isolateSendPort.match(
        () {},
        (final SendPort sendPort) {
          final bool down = rawKeyEvent is RawKeyDownEvent;
          int keysym = rawKeyEvent.logicalKey.asXWindowSystemKey();
          
          // Accurately resolve Shifted and Uppercase characters
          if (rawKeyEvent.character != null && rawKeyEvent.character!.isNotEmpty) {
            final int charCode = rawKeyEvent.character!.codeUnitAt(0);
            if (charCode >= 32 && charCode <= 126) {
              keysym = charCode;
            }
          }

          sendPort.send(
            RemoteFrameBufferIsolateSendMessage.keyEvent(
              down: down,
              key: keysym,
            ),
          );
        }
      );
  }

  /// Updates [frameBuffer] with the given [rectangle]s.
  @visibleForTesting
  static void updateFrameBuffer({
    required final ByteData frameBuffer,
    required final Size frameBufferSize,
    required final RemoteFrameBufferClientUpdateRectangle rectangle,
  }) {
          final Uint8List dest = frameBuffer.buffer.asUint8List(frameBuffer.offsetInBytes, frameBuffer.lengthInBytes);
          final Uint8List src = rectangle.byteData.buffer.asUint8List(rectangle.byteData.offsetInBytes, rectangle.byteData.lengthInBytes);
          
          final int rowBytes = rectangle.width * 4;
          final int fbWidthBytes = frameBufferSize.width.toInt() * 4;
          
          if (rectangle.width == frameBufferSize.width && rectangle.x == 0) {
              // Fast path: contiguous block copy (full screen update)
              final int destOffset = (rectangle.y * fbWidthBytes).toInt();
              dest.setRange(destOffset, destOffset + src.length, src);
          } else {
              // Fast path: Row-by-row block copy (memmove)
              for (int y = 0; y < rectangle.height; y++) {
                  final int srcOffset = y * rowBytes;
                  final int destOffset = ((rectangle.y + y) * fbWidthBytes) + (rectangle.x * 4);
                  dest.setRange(destOffset, destOffset + rowBytes, src, srcOffset);
              }
          }
  }
}
