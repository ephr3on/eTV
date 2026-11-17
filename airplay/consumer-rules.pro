# The native AirPlay engine calls back into these classes by name (JNI), so R8 must keep them as
# they are in eTV's minified release build.
-keep class com.flymop.airplaytv.** { *; }
