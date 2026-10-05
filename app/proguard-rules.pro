# BQ Sqwad AI release rules.
# Keep Firebase authentication and JSON model reflection safe for release builds.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes *Annotation*

-keep class io.github.thibaultbee.streampack.** { *; }
-keep class com.android.billingclient.** { *; }
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.zxing.** { *; }
-keep class com.bqsqwad.ai.core.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-dontwarn org.slf4j.**
