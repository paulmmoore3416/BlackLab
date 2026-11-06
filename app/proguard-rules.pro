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
