# BoofCV uses reflection in a few places; keep its classes.
-keep class boofcv.** { *; }
-keep class org.ddogleg.** { *; }
-keep class georegression.** { *; }
-dontwarn boofcv.**
-dontwarn org.ddogleg.**
