# Hilt
-keep class dagger.hilt.** { *; }

# Optional logging binder pulled in transitively; not shipped on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# The earbuds' helper is not started by the app: the computer runs it over adb, by name
# (computer/weave/roam.py), so its name and its main have to stay as they are.
-keep class com.t3lluz.weave.roam.RoamShell { *; }
-keep class com.t3lluz.weave.roam.RoamShell$* { *; }
