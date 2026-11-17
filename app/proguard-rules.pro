# Let R8 optimize and obfuscate app code. AndroidX, Firebase and Play SDKs
# provide their own consumer rules; avoid blanket keep rules that disable shrinking.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-dontwarn org.slf4j.**
# StreamPack uses native/JNI bindings and reflection-backed runtime components.
-keep class io.github.thibaultbee.streampack.** { *; }
-dontwarn io.github.thibaultbee.streampack.**

# Billing SDK and app core models are referenced by SDK callbacks/reflection.
-keep class com.android.billingclient.api.** { *; }
-keep class com.bqsqwad.ai.core.** { *; }
-keepclassmembers class * implements java.io.Serializable { *; }

