# WebView'e JS köprüsü ile açılan sınıfların isimleri korunmalı.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Küçük APK + hızlı ilk açılış
-repackageclasses
-allowaccessmodification
-optimizationpasses 3

# Uyarı gürültüsünü kes
-dontwarn org.jetbrains.annotations.**
