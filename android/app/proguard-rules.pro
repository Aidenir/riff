# JNA and the UniFFI bindings are reached through reflection and native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.aidenir.riff.ffi.** { *; }
-keep class com.aidenir.riff.core.NativeLib { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
