# Keep Xposed entry point
-keep class com.mockloc.hook.XposedInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# Keep JNI native bridge methods and class name
-keepclasseswithmembernames class com.mockloc.data.remote.NativeSecurityBridge {
    native <methods>;
}
-keep class com.mockloc.data.remote.NativeSecurityBridge { *; }

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Parcelable / Serializable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep OSMDroid / Kotlin Coroutines / Gson or org.json reflection safety
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# Aggressive obfuscation & log stripping
-repackageclasses 'o'
-allowaccessmodification
-optimizationpasses 5
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
