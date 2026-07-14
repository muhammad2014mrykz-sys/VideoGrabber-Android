# youtubedl-android uses reflection / bundled assets; keep it intact.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
