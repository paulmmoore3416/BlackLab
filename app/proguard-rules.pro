# BlackLab release rules
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.blacklab.app.ui.editor.EditorBridge { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
