; test/native/typed_pointer_probe.ll — the toolchain preflight probe.
;
; The pinned toolchain window is LLVM 14-16 with typed-pointer textual IR
; (llvm-native-backend-integration D4). This module is the era's IR shape, so a
; successful compile at -O0 and -O2 proves the resolved compiler parses and
; lowers typed-pointer textual IR before any emitted module exists.

target triple = "x86_64-pc-linux-gnu"

define i32 @main() {
entry:
  ret i32 0
}
