# Let R8 optimize and obfuscate app code. AndroidX, Firebase and Play SDKs
# provide their own consumer rules; avoid blanket keep rules that disable shrinking.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-dontwarn org.slf4j.**
