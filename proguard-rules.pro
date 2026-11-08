# Rules for the release build (R8). The libraries (Compose, Firebase, Media3) bring their own rules.
# The app itself uses no reflection, so nothing of its own needs to be kept.

# Keep readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
