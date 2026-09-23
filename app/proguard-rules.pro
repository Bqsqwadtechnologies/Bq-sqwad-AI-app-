# BQ Sqwad AI release rules.
# Keep Firebase authentication and JSON model reflection safe for release builds.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes *Annotation*
