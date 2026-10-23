# Networks are found with ServiceLoader; keep every provider and its no-argument constructor.
-keep class * implements com.fabads.AdProvider { <init>(); }
-keepnames class com.fabads.AdProvider
