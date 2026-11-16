# R8 / ProGuard rules for release builds.
# Room, Hilt, Compose and AndroidX ship their own consumer rules.
# Rules for Retrofit / kotlinx.serialization are added in Phase 3 together with the dependencies.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
