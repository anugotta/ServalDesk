import 'dart:ffi';
import 'dart:io';

void main() {
  final stdlib = DynamicLibrary.process();
  final malloc = stdlib.lookupFunction<Pointer<Void> Function(IntPtr), Pointer<Void> Function(int)>('malloc');
  final free = stdlib.lookupFunction<Void Function(Pointer<Void>), void Function(Pointer<Void>)>('free');
  
  final ptr = malloc(1024);
  print('Allocated at: \${ptr.address}');
  free(ptr);
  print('Freed successfully');
}
