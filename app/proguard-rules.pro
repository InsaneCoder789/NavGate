# NavGate release rules. Keep stack traces readable in crash reports.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: generated serializers and companions are looked up by name.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.rohanc.navgate.**$$serializer { *; }

# Room entities, DAOs and the generated database are reached through reflection-like generated code.
-keep class com.rohanc.navgate.data.local.** { *; }

# The pure navigation module is small and its data classes are serialized; keep them whole.
-keep class com.rohanc.navgate.core.** { *; }

# Native/JNI libraries bring their own consumer rules (ARCore, MapLibre, OkHttp); only silence optional references.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
