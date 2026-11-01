# Catalog requests

`gradle/libs.versions.toml` and `settings.gradle.kts` belong to A-01 (spec 14 §7). Other work
packages do **not** edit them. To add or bump a library, append a row here; A-01's owner or the
coordinator merges requests between waves, after checking the version exists (E-3) and, for any
library a minSdk-21 module uses, that its AAR manifest still declares minSdk ≤ 21.

| Date | WP | Alias | Coordinate | Tier (legacy / modern / pinned / shared / test) | Module(s) | Why | Status |
|---|---|---|---|---|---|---|---|
| 2026-10-04 | B-07 | `leakcanary-android-instrumentation` | `com.squareup.leakcanary:leakcanary-android-instrumentation:2.14` | test | `:feature:visuals` (androidTest) | Spec 14 §7 B-07 DoD names LeakCanary for the WebViewPool leak test; until it lands, `WebViewPoolLeakTest` checks reachability with WeakReference + GC | requested |
| 2026-10-16 | T0 (spec 15) | `androidx-tv-material` | `androidx.tv:tv-material:1.1.0` | modern | `:app-tv` | TV-1: tv-material NavigationDrawer, Surface/Button focus styling for the 10-foot app. Verified on Google Maven (latest stable 1.1.0); AAR manifest minSdk 23 (modern tier only) | merged (T0) |
