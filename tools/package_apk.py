"""Package and verify the manually built LSPosed APK."""
from pathlib import Path
import struct
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
TOOLS = ROOT / "tools"
OUTPUT = ROOT / "outputs" / "gamebox-vision-fix-v1.0.3.apk"


def dex_type_names(dex: bytes) -> list[str]:
    string_count, string_offset = struct.unpack_from("<II", dex, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", dex, string_offset + index * 4)[0]
        while dex[offset] & 0x80:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b"\0", offset)].decode("utf-8", "replace"))
    type_count, type_offset = struct.unpack_from("<II", dex, 64)
    return [
        strings[struct.unpack_from("<I", dex, type_offset + index * 4)[0]]
        for index in range(type_count)
    ]


def package() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(BUILD / "unsigned.apk", "w", zipfile.ZIP_DEFLATED) as target:
        with zipfile.ZipFile(BUILD / "base.apk") as base:
            for entry in base.infolist():
                if entry.filename == "resources.arsc":
                    info = zipfile.ZipInfo(entry.filename)
                    info.compress_type = zipfile.ZIP_STORED
                    offset = target.fp.tell() + 30 + len(info.filename.encode("utf-8"))
                    padding = (-offset) % 4
                    if padding:
                        info.extra = struct.pack("<HH", 0xD935, padding) + bytes(padding)
                    target.writestr(info, base.read(entry))
                else:
                    target.writestr(entry.filename, base.read(entry))
        target.write(BUILD / "dex/classes.dex", "classes.dex")
        target.write(
            TOOLS / "dexkit/jni/arm64-v8a/libdexkit.so",
            "lib/arm64-v8a/libdexkit.so",
            compress_type=zipfile.ZIP_STORED,
        )
        resources = ROOT / "src/main/resources"
        for path in sorted(resources.rglob("*")):
            if path.is_file():
                target.write(path, path.relative_to(resources).as_posix())


def verify() -> None:
    with zipfile.ZipFile(OUTPUT) as archive:
        assert archive.testzip() is None
        table = archive.getinfo("resources.arsc")
        assert table.compress_type == zipfile.ZIP_STORED, "resources.arsc must be uncompressed"
        with OUTPUT.open("rb") as apk:
            apk.seek(table.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", apk.read(4))
        assert (table.header_offset + 30 + name_size + extra_size) % 4 == 0, (
            "resources.arsc must be aligned on a 4-byte boundary"
        )
        assert archive.read("META-INF/xposed/scope.list").strip() == b"com.miui.securitycenter"
        assert archive.read("META-INF/xposed/java_init.list").strip() == (
            b"io.github.codex.gameboxvisionfix.GameBoxVisionFixModule"
        )
        assert archive.read("lib/arm64-v8a/libdexkit.so").startswith(b"\x7fELF")
        dex = archive.read("classes.dex")
        assert b"Lio/github/codex/gameboxvisionfix/GameBoxVisionFixModule;" in dex
        assert b"Lorg/luckypray/dexkit/DexKitBridge;" in dex
        assert b"Lkotlin/jvm/internal/Intrinsics;" in dex
        assert b"Lcom/google/flatbuffers/FlatBufferBuilder;" in dex
        assert b"Initialization completed, curPkg = " in dex
        assert b"release service..." in dex
        assert b"Lcom/miui/gamebooster/utils/GameBoxVisionEnhanceUtils;" not in dex
        assert b"GameBoxVisionEnhanceUtils;->L()V" not in dex

        definitions, class_offset = struct.unpack_from("<II", dex, 96)
        classes = [
            dex_type_names(dex)[
                struct.unpack_from("<I", dex, class_offset + index * 32)[0]
            ]
            for index in range(definitions)
        ]
        allowed = (
            "Lio/github/codex/gameboxvisionfix/",
            "Lorg/luckypray/dexkit/",
            "Lkotlin/",
            "Lcom/google/flatbuffers/",
        )
        unexpected = [name for name in classes if not name.startswith(allowed)]
        assert not unexpected, unexpected[:20]
    print(
        "APK verified: DexKit runtime present, exact scope, native library present, "
        "no hardcoded target descriptor"
    )


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "package":
        package()
    elif command == "verify":
        verify()
    else:
        raise SystemExit("Expected package or verify")
