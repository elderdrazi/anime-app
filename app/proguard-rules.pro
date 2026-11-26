# AniList response models are read by kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.kumo.anime.data.**$$serializer { *; }
-keepclassmembers class com.kumo.anime.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.kumo.anime.data.** { *; }
