# :core:wakeword consumer rules (spec 14 §1.10).
# Vosk calls into libvosk through JNA direct mapping; class and method names must survive.
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
# JNA: reflection over Structure fields, Native.register, callbacks.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; <fields>; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.**
# JNI entry points of libvosk-stderr-shim.so are looked up by name.
-keep class com.assistant.core.wakeword.vosk.VoskStderrShim { native <methods>; }
