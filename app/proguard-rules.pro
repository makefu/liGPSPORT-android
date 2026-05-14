# Protobuf-lite: keep all generated message classes and their fields,
# since R8 doesn't know about reflective access from protobuf's runtime.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class com.google.protobuf.** { *; }

# Nordic BLE library keeps callbacks via reflection
-keep class no.nordicsemi.android.ble.** { *; }

# osmdroid uses reflection for some tile loaders
-keep class org.osmdroid.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Coroutines internal — already covered by upstream rules, but pin them
-dontwarn kotlinx.coroutines.**

# Ktor pulls in slf4j-api; the impl is provided by the consumer (we
# don't ship one), so silence the missing-class report instead of
# bundling a logger.
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**

# Compose Material icons extended pulls in many classes; keep them
-keep class androidx.compose.material.icons.** { *; }

# Ktor selector manager / engines use reflection
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# osmdroid additional warnings
-dontwarn org.osmdroid.**
