"""Download pinned build dependencies and extract the archives needed by build.ps1."""
from pathlib import Path
import sys
import time
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"

DOWNLOADS = {
    "dexkit-2.3.0.aar": (
        "https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.3.0/dexkit-2.3.0.aar"
    ),
    "kotlin-stdlib-1.5.0.jar": (
        "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/"
        "kotlin-stdlib/1.5.0/kotlin-stdlib-1.5.0.jar"
    ),
    "flatbuffers-java-23.5.26.jar": (
        "https://repo.maven.apache.org/maven2/com/google/flatbuffers/"
        "flatbuffers-java/23.5.26/flatbuffers-java-23.5.26.jar"
    ),
    "api-102.0.0.aar": (
        "https://repo.maven.apache.org/maven2/io/github/libxposed/api/"
        "102.0.0/api-102.0.0.aar"
    ),
    "platform-35_r02.zip": (
        "https://dl.google.com/android/repository/platform-35_r02.zip"
    ),
    "aapt2-windows.jar": (
        "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/"
        "8.6.1-11315950/aapt2-8.6.1-11315950-windows.jar"
    ),
    "r8-8.6.27.jar": (
        "https://storage.googleapis.com/r8-releases/raw/8.6.27/r8.jar"
    ),
    "apksig-8.6.1.jar": (
        "https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/"
        "8.6.1/apksig-8.6.1.jar"
    ),
}


def download(name: str, url: str) -> Path:
    destination = TOOLS / name
    if destination.is_file() and destination.stat().st_size:
        return destination
    print(f"Downloading {name}")
    temporary = destination.with_suffix(destination.suffix + ".part")
    last_error = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            temporary.replace(destination)
            return destination
        except Exception as error:
            last_error = error
            temporary.unlink(missing_ok=True)
            if attempt < 4:
                time.sleep(attempt)
    raise last_error


def extract_member(archive_path: Path, suffix: str, output_path: Path) -> None:
    if output_path.is_file() and output_path.stat().st_size:
        return
    with zipfile.ZipFile(archive_path) as archive:
        name = next(item for item in archive.namelist() if item.endswith(suffix))
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(archive.read(name))


def main() -> None:
    TOOLS.mkdir(parents=True, exist_ok=True)
    paths = {name: download(name, url) for name, url in DOWNLOADS.items()}
    extract_member(paths["dexkit-2.3.0.aar"], "classes.jar",
                   TOOLS / "dexkit/classes.jar")
    extract_member(paths["dexkit-2.3.0.aar"], "libdexkit.so",
                   TOOLS / "dexkit/jni/arm64-v8a/libdexkit.so")
    extract_member(paths["api-102.0.0.aar"], "classes.jar",
                   TOOLS / "libxposed-api-102.jar")
    extract_member(paths["aapt2-windows.jar"], "aapt2.exe",
                   TOOLS / "aapt2.exe")
    extract_member(paths["platform-35_r02.zip"], "/android.jar",
                   TOOLS / "android-35.jar")
    print("Pinned dependencies are ready")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit(f"Dependency preparation failed: {error}")
