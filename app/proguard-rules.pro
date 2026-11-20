# Keep the stack traces readable in the in-app crash report.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod

# OkHttp / Conscrypt optional backends.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JSON is read by key, never by reflection; nothing else needs a keep rule.
