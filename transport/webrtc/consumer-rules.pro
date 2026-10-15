# libwebrtc's native code calls into these classes by name (JNI): R8 must neither remove nor rename them.
# The WebRTC AAR ships no rules of its own.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
