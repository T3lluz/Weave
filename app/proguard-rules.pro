# Hilt
-keep class dagger.hilt.** { *; }

# Optional logging binder pulled in transitively; not shipped on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder
