# libwebrtc's native code calls into these classes by name (JNI): R8 must neither remove nor rename them.
# The WebRTC AAR ships no rules of its own.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Its JNI glue too: loading the native library looks up org.jni_zero.JniZero by name, and nothing in Java uses it,
# so R8 removed it and every release build aborted on the first internet connection ("java_class == null").
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
