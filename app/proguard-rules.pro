# FreeRDP 引擎的 native 层通过 JNI 按方法名回调 Java，类名/方法名不能混淆
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# 布局 XML 里以类名引用的自定义 View（SessionView / TouchPointerView / ScrollView2D 等）
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 引擎引用了若干未打包的可选依赖，抑制 R8 缺失类告警
-dontwarn com.freerdp.freerdpcore.**
-dontwarn org.slf4j.**
