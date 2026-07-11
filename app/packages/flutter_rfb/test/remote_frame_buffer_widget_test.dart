import 'dart:ui';

import 'package:dart_rfb/dart_rfb.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_rfb/src/remote_frame_buffer_widget.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:freezed_annotation/freezed_annotation.dart';

part 'remote_frame_buffer_widget_test.freezed.dart';

@freezed
class UpdateFrameBufferVariant with _$UpdateFrameBufferVariant {
  const factory UpdateFrameBufferVariant({
    required final ByteData expectedFrameBuffer,
    required final ByteData inputFrameBuffer,
    required final Size inputFrameBufferSize,
    required final RemoteFrameBufferClientUpdateRectangle inputRectangle,
  }) = _UpdateFrameBufferVariant;
}

void main() {
  final ValueVariant<UpdateFrameBufferVariant> updateFrameBufferVariant =
      ValueVariant<UpdateFrameBufferVariant>(<UpdateFrameBufferVariant>{
    UpdateFrameBufferVariant(
      expectedFrameBuffer:
          Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
      inputFrameBuffer:
          Uint8List.fromList(<int>[0, 0, 0, 0]).buffer.asByteData(),
      inputFrameBufferSize: const Size(1, 1),
      inputRectangle: RemoteFrameBufferClientUpdateRectangle(
        byteData: Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
        encodingType: const RemoteFrameBufferEncodingType.raw(),
        height: 1,
        width: 1,
        x: 0,
        y: 0,
      ),
    ),
    UpdateFrameBufferVariant(
      expectedFrameBuffer: Uint8List.fromList(<int>[
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBufferSize: const Size(1, 1),
      inputRectangle: RemoteFrameBufferClientUpdateRectangle(
        byteData: Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
        encodingType: const RemoteFrameBufferEncodingType.raw(),
        height: 1,
        width: 1,
        x: 0,
        y: 0,
      ),
    ),
    UpdateFrameBufferVariant(
      expectedFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBufferSize: const Size(1, 1),
      inputRectangle: RemoteFrameBufferClientUpdateRectangle(
        byteData: Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
        encodingType: const RemoteFrameBufferEncodingType.raw(),
        height: 1,
        width: 1,
        x: 1,
        y: 0,
      ),
    ),
    UpdateFrameBufferVariant(
      expectedFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBufferSize: const Size(1, 1),
      inputRectangle: RemoteFrameBufferClientUpdateRectangle(
        byteData: Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
        encodingType: const RemoteFrameBufferEncodingType.raw(),
        height: 1,
        width: 1,
        x: 0,
        y: 1,
      ),
    ),
    UpdateFrameBufferVariant(
      expectedFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        1,
        1,
        1,
        1,
      ]).buffer.asByteData(),
      inputFrameBuffer: Uint8List.fromList(<int>[
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      ]).buffer.asByteData(),
      inputFrameBufferSize: const Size(1, 1),
      inputRectangle: RemoteFrameBufferClientUpdateRectangle(
        byteData: Uint8List.fromList(<int>[1, 1, 1, 1]).buffer.asByteData(),
        encodingType: const RemoteFrameBufferEncodingType.raw(),
        height: 1,
        width: 1,
        x: 1,
        y: 1,
      ),
    ),
  });

  testWidgets(
    'updateFrameBuffer(...) works',
    variant: updateFrameBufferVariant,
    (final _) async {
      RemoteFrameBufferWidgetState.updateFrameBuffer(
        frameBuffer: updateFrameBufferVariant.currentValue!.inputFrameBuffer,
        frameBufferSize:
            updateFrameBufferVariant.currentValue!.inputFrameBufferSize,
        rectangle: updateFrameBufferVariant.currentValue!.inputRectangle,
      );
      expect(
        listEquals(
          updateFrameBufferVariant.currentValue!.inputFrameBuffer.buffer
              .asUint8List(),
          updateFrameBufferVariant.currentValue!.inputFrameBuffer.buffer
              .asUint8List(),
        ),
        isTrue,
      );
    },
  );
}
