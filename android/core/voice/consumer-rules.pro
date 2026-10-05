# :core:voice consumer rules (spec 14 §1.10). WebRTC's JNI looks classes and methods up by name.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# The parity harness and hosts may load the VoiceCore implementation through ServiceLoader.
-keep class com.assistant.core.voice.DefaultVoiceCore { public <init>(); }
