import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/widgets.dart' hide Image;
import 'package:flutter/gestures.dart';
import 'package:flutter_rfb/src/remote_frame_buffer_isolate_messages.dart';
import 'package:fpdart/fpdart.dart' hide State;

enum InputMode { direct, trackpad }

class RemoteFrameBufferGestureDetector extends StatefulWidget {
  final Image image;
  final Size remoteFrameBufferWidgetSize;
  final Option<SendPort> sendPort;
  final Widget? child;
  final InputMode inputMode;
  final double trackpadSensitivity;

  const RemoteFrameBufferGestureDetector({
    super.key,
    required this.image,
    required this.remoteFrameBufferWidgetSize,
    required this.sendPort,
    required this.inputMode,
    this.trackpadSensitivity = 3.5,
    this.child,
  });

  @override
  State<RemoteFrameBufferGestureDetector> createState() =>
      _RemoteFrameBufferGestureDetectorState();
}

class _RemoteFrameBufferGestureDetectorState
    extends State<RemoteFrameBufferGestureDetector> {
  // Virtual mouse coordinates for trackpad mode
  double _virtualMouseX = 1920 / 2;
  double _virtualMouseY = 1080 / 2;
  
  // Two-finger scroll tracking
  final Set<int> _activePointers = {};
  double _scrollAccumulator = 0.0;

  void _sendPointerEvent({
    required bool button1Down,
    required bool button2Down,
    required bool button3Down,
    bool button4Down = false,
    bool button5Down = false,
    required int x,
    required int y,
  }) {
    widget.sendPort.match(
      () {},
      (final SendPort sendPort) => sendPort.send(
        RemoteFrameBufferIsolateSendMessage.pointerEvent(
          button1Down: button1Down,
          button2Down: button2Down,
          button3Down: button3Down,
          button4Down: button4Down,
          button5Down: button5Down,
          button6Down: false,
          button7Down: false,
          button8Down: false,
          x: x.clamp(0, widget.image.width - 1),
          y: y.clamp(0, widget.image.height - 1),
        ),
      ),
    );
  }

  // Calculate coordinates depending on input mode
  int _getX(Offset localPosition) {
    if (widget.inputMode == InputMode.trackpad) {
      return _virtualMouseX.toInt();
    }
    return (localPosition.dx /
            widget.remoteFrameBufferWidgetSize.width *
            widget.image.width)
        .toInt();
  }

  int _getY(Offset localPosition) {
    if (widget.inputMode == InputMode.trackpad) {
      return _virtualMouseY.toInt();
    }
    return (localPosition.dy /
            widget.remoteFrameBufferWidgetSize.height *
            widget.image.height)
        .toInt();
  }

  int _lastDirectX = 0;
  int _lastDirectY = 0;
  
  int _maxPointersInGesture = 0;
  bool _hasScrolled = false;

  void _handlePointerEvent(PointerEvent event) {
    if (event.kind == PointerDeviceKind.mouse) {
      final int x = (event.localPosition.dx / widget.remoteFrameBufferWidgetSize.width * widget.image.width).toInt();
      final int y = (event.localPosition.dy / widget.remoteFrameBufferWidgetSize.height * widget.image.height).toInt();
      
      _sendPointerEvent(
        button1Down: (event.buttons & kPrimaryMouseButton) != 0,
        button2Down: (event.buttons & kMiddleMouseButton) != 0,
        button3Down: (event.buttons & kSecondaryMouseButton) != 0,
        x: x,
        y: y,
      );
    } else {
      if (event is PointerDownEvent) {
        _activePointers.add(event.pointer);
        if (_activePointers.length > _maxPointersInGesture) {
          _maxPointersInGesture = _activePointers.length;
        }
        _lastDirectX = _getX(event.localPosition);
        _lastDirectY = _getY(event.localPosition);
      }
      
      if (event is PointerUpEvent || event is PointerCancelEvent) {
        _activePointers.remove(event.pointer);
        if (_activePointers.isEmpty) {
          if (_maxPointersInGesture == 2 && !_hasScrolled) {
            final int x = widget.inputMode == InputMode.trackpad ? _virtualMouseX.toInt() : _lastDirectX;
            final int y = widget.inputMode == InputMode.trackpad ? _virtualMouseY.toInt() : _lastDirectY;
            
            // Execute Right Click!
            _sendPointerEvent(button1Down: false, button2Down: false, button3Down: true, x: x, y: y);
            _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: x, y: y);
          }
          // Reset gesture state
          _maxPointersInGesture = 0;
          _hasScrolled = false;
        }
      }
    }
  }

  void _handlePointerSignal(PointerSignalEvent event) {
    if (event is PointerScrollEvent && event.kind == PointerDeviceKind.mouse) {
      final int x = (event.localPosition.dx / widget.remoteFrameBufferWidgetSize.width * widget.image.width).toInt();
      final int y = (event.localPosition.dy / widget.remoteFrameBufferWidgetSize.height * widget.image.height).toInt();
      
      final bool isUp = event.scrollDelta.dy < 0;
      
      _sendPointerEvent(
        button1Down: false, button2Down: false, button3Down: false,
        button4Down: isUp, button5Down: !isUp,
        x: x, y: y,
      );
      _sendPointerEvent(
        button1Down: false, button2Down: false, button3Down: false,
        button4Down: false, button5Down: false,
        x: x, y: y,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerHover: _handlePointerEvent,
      onPointerDown: _handlePointerEvent,
      onPointerMove: _handlePointerEvent,
      onPointerUp: _handlePointerEvent,
      onPointerCancel: _handlePointerEvent,
      onPointerSignal: _handlePointerSignal,
      child: GestureDetector(
      supportedDevices: const {
        PointerDeviceKind.touch,
        PointerDeviceKind.stylus,
        PointerDeviceKind.trackpad, // Apple Magic Trackpad sends this
      },
      // Trackpad clicks
      onTap: widget.inputMode == InputMode.trackpad
          ? () {
              // Press
              _sendPointerEvent(button1Down: true, button2Down: false, button3Down: false, x: _virtualMouseX.toInt(), y: _virtualMouseY.toInt());
              // Release
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _virtualMouseX.toInt(), y: _virtualMouseY.toInt());
            }
          : null,
      onSecondaryTap: widget.inputMode == InputMode.trackpad
          ? () {
              // Press Right Click
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: true, x: _virtualMouseX.toInt(), y: _virtualMouseY.toInt());
              // Release Right Click
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _virtualMouseX.toInt(), y: _virtualMouseY.toInt());
            }
          : null,
      
      // Direct touch taps
      onSecondaryTapDown: widget.inputMode == InputMode.direct
          ? (details) {
              _lastDirectX = _getX(details.localPosition);
              _lastDirectY = _getY(details.localPosition);
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: true, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      onSecondaryTapUp: widget.inputMode == InputMode.direct
          ? (details) {
              _lastDirectX = _getX(details.localPosition);
              _lastDirectY = _getY(details.localPosition);
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      onSecondaryTapCancel: widget.inputMode == InputMode.direct
          ? () {
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
          
      onTapDown: widget.inputMode == InputMode.direct
          ? (details) {
              _lastDirectX = _getX(details.localPosition);
              _lastDirectY = _getY(details.localPosition);
              _sendPointerEvent(button1Down: true, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      onTapUp: widget.inputMode == InputMode.direct
          ? (details) {
              _lastDirectX = _getX(details.localPosition);
              _lastDirectY = _getY(details.localPosition);
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      onTapCancel: widget.inputMode == InputMode.direct
          ? () {
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,

      // Panning / Dragging
      onPanStart: widget.inputMode == InputMode.direct
          ? (details) {
              _lastDirectX = _getX(details.localPosition);
              _lastDirectY = _getY(details.localPosition);
              _sendPointerEvent(button1Down: true, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      onPanUpdate: (details) {
        if (details.kind == PointerDeviceKind.mouse) return; // Handled by Listener

        if (_activePointers.length >= 2) {
          // Two-finger scroll
          _scrollAccumulator += details.delta.dy;
          if (_scrollAccumulator.abs() > 15.0) {
            _hasScrolled = true; // Prevents triggering right-click on release
            
            final bool isUp = _scrollAccumulator > 0;
            final int x = widget.inputMode == InputMode.trackpad ? _virtualMouseX.toInt() : _getX(details.localPosition);
            final int y = widget.inputMode == InputMode.trackpad ? _virtualMouseY.toInt() : _getY(details.localPosition);
            
            // Press scroll wheel
            _sendPointerEvent(
              button1Down: false, button2Down: false, button3Down: false,
              button4Down: isUp, button5Down: !isUp,
              x: x, y: y,
            );
            // Release scroll wheel
            _sendPointerEvent(
              button1Down: false, button2Down: false, button3Down: false,
              button4Down: false, button5Down: false,
              x: x, y: y,
            );
            
            _scrollAccumulator = 0.0;
          }
          return;
        }

        if (widget.inputMode == InputMode.trackpad) {
          // Trackpad: Move cursor without clicking
          setState(() {
            _virtualMouseX += details.delta.dx * widget.trackpadSensitivity;
            _virtualMouseY += details.delta.dy * widget.trackpadSensitivity;
            _virtualMouseX = _virtualMouseX.clamp(0, widget.image.width.toDouble() - 1);
            _virtualMouseY = _virtualMouseY.clamp(0, widget.image.height.toDouble() - 1);
          });
          _sendPointerEvent(
            button1Down: false,
            button2Down: false,
            button3Down: false,
            x: _virtualMouseX.toInt(),
            y: _virtualMouseY.toInt(),
          );
        } else {
          // Direct: Click and drag
          _lastDirectX = _getX(details.localPosition);
          _lastDirectY = _getY(details.localPosition);
          _sendPointerEvent(
            button1Down: true,
            button2Down: false,
            button3Down: false,
            x: _lastDirectX,
            y: _lastDirectY,
          );
        }
      },
      onPanEnd: widget.inputMode == InputMode.direct
          ? (details) {
              _sendPointerEvent(button1Down: false, button2Down: false, button3Down: false, x: _lastDirectX, y: _lastDirectY);
            }
          : null,
      
      child: widget.child,
      ),
    );
  }
}
