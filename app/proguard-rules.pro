# MapLibre
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
# GraphHopper and its dependencies (reflection + generated accessors)
-keep class com.graphhopper.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-dontwarn com.graphhopper.**
-dontwarn com.fasterxml.jackson.**
-dontwarn com.carrotsearch.hppc.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.apache.**
-dontwarn org.locationtech.jts.**
-keepclassmembers class * { @com.fasterxml.jackson.annotation.** *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
