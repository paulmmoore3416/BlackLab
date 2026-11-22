# BlackLab release rules
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.blacklab.app.ui.editor.EditorBridge { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
# PdfBox-Android: optional JPEG 2000 codec isn't bundled
-dontwarn com.gemalto.jp2.**

# Picovoice Porcupine (wake word): JNI binds by class and method name
-keep class ai.picovoice.** { *; }

# JSch (Terminal, 5.18) instantiates its ciphers, MACs, key exchanges and signatures by class name from its config
# ("com.jcraft.jsch.jce.HMACSHA1", …). R8 must keep them all, or SSH fails with "installation of … lead to an error".
-keep class com.jcraft.jsch.** { *; }
# Optional JSch integrations BlackLab doesn't ship (logging bridges, Unix sockets, native agents)
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
