# The host loads the factory by the name in the manifest, and a future Mihon tracker would bind to the
# source class by its qualified name -- neither may be renamed or removed.
-keep class eu.kanade.tachiyomi.extension.all.uchiyomi.UchiyomiFactory { <init>(); }
-keepnames class eu.kanade.tachiyomi.extension.all.uchiyomi.Uchiyomi

# Injekt resolves generic type tokens reflectively, so the Signature attribute must survive.
-keepattributes Signature
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends uy.kohesive.injekt.api.FullTypeReference

# kotlinx.serialization: the generated $serializer companions are looked up at runtime.
# https://github.com/Kotlin/kotlinx.serialization/tree/dev/rules
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation class <1>

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
