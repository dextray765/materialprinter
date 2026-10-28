# Proguard rules for MaterialPrinter
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Release build: shrink and optimise, but do not rename anything (readable stack traces)
-dontobfuscate

# Remove verbose/debug/info logging from release builds (it contains the printer's MAC address and serial number).
# Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
