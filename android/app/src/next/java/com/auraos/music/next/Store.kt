```diff
*** Update File: android/app/src/next/java/com/auraos/music/next/Store.kt
@@
-    val playlists = MutableStateFlow>>(emptyMap())
-    val favs = MutableStateFlow>(emptyMap())
+    val playlists = MutableStateFlow<Map<String, List<String>>>(emptyMap())
+    val favs = MutableStateFlow<Map<String, Long>>(emptyMap())
*** End Patch
```