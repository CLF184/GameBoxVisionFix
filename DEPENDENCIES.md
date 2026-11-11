# Build dependencies

The repository and source archive intentionally exclude large third-party binaries. `build.ps1`
automatically runs `tools/prepare_dependencies.py` to download and extract them. The tested build used:

| File | Source |
| --- | --- |
| `dexkit-2.3.0.aar` | `https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.3.0/dexkit-2.3.0.aar` |
| `kotlin-stdlib-1.5.0.jar` | `https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.5.0/kotlin-stdlib-1.5.0.jar` |
| `flatbuffers-java-23.5.26.jar` | `https://repo.maven.apache.org/maven2/com/google/flatbuffers/flatbuffers-java/23.5.26/flatbuffers-java-23.5.26.jar` |
| `api-102.0.0.aar` | `https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar` |
| `platform-35_r02.zip` | `https://dl.google.com/android/repository/platform-35_r02.zip` |
| `aapt2-windows.jar` | `https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.6.1-11315950/aapt2-8.6.1-11315950-windows.jar` |
| `r8.jar` | `https://storage.googleapis.com/r8-releases/raw/8.6.27/r8.jar` |
| `apksig.jar` | `https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.6.1/apksig-8.6.1.jar` |

After extracting `classes.jar` from `dexkit-2.3.0.aar`, place it at
`tools/dexkit/classes.jar`. Extract `jni/arm64-v8a/libdexkit.so` to the same directory.
