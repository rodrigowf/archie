# Catalog requests

`gradle/libs.versions.toml` and `settings.gradle.kts` belong to A-01 (spec 14 §7). Other work
packages do **not** edit them. To add or bump a library, append a row here; A-01's owner or the
coordinator merges requests between waves, after checking the version exists (E-3) and, for any
library a minSdk-21 module uses, that its AAR manifest still declares minSdk ≤ 21.

| Date | WP | Alias | Coordinate | Tier (legacy / modern / pinned / shared / test) | Module(s) | Why | Status |
|---|---|---|---|---|---|---|---|
| 2026-10-04 | B-07 | `leakcanary-android-instrumentation` | `com.squareup.leakcanary:leakcanary-android-instrumentation:2.14` | test | `:feature:visuals` (androidTest) | Spec 14 §7 B-07 DoD names LeakCanary for the WebViewPool leak test; until it lands, `WebViewPoolLeakTest` checks reachability with WeakReference + GC | requested |
