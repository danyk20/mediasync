# App-specific R8 rules. Libraries (Compose, kotlinx.serialization, Navigation 3) ship their own.

# WorkManager workers are found by class name (MetroWorkerFactory), so their names must survive R8.
-keepnames class * extends androidx.work.ListenableWorker
