# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bvb.android.**$$serializer { *; }
-keepclassmembers class com.bvb.android.** { *** Companion; }
-keepclasseswithmembers class com.bvb.android.** { kotlinx.serialization.KSerializer serializer(...); }

# BouncyCastle / PGPainless
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.pgpainless.** { *; }
-dontwarn org.pgpainless.**
# slf4j is loaded reflectively by PGPainless on first use: keep it whole so
# the first crypto operation cannot hit a stripped class at runtime.
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Retrofit
-dontwarn retrofit2.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Compile-only annotations referenced by Tink (security-crypto) and slf4j (PGPainless)
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
