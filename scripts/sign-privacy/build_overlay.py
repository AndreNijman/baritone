#!/usr/bin/env python3
"""Build a pinned Fabric release overlay offline, without executing upstream build scripts."""
import argparse
import base64
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM_SHA256 = "49adfc063cfbfd0b6f08e9d814359807baa2d1768c0d39d6c5968268547cbca6"
MINECRAFT_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87"
VERSION = "1.20.0+compat.6"
CLASSES = [
    "baritone/launch/privacy/SignTextPrivacy.class",
    "baritone/launch/privacy/SignTextPrivacy$Api.class",
    "baritone/launch/privacy/SignTextPrivacy$ApiHolder.class",
    "baritone/launch/mixins/MixinSignEditScreen.class",
    "baritone/fp.class",
    "baritone/fj.class",
    "baritone/a.class",
    "baritone/f.class",
    "baritone/f$a.class",
    "baritone/f$b.class",
    "baritone/api/utils/RotationUtils.class",
    "baritone/api/behavior/look/IAimProcessor.class",
    "baritone/utils/GradualLook.class",
    "baritone/utils/GradualLookCommand.class",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--minecraft", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True, help="Existing ASM 9.9 JAR")
    parser.add_argument("--jdk", type=Path, default=Path("/usr/lib/jvm/java-27"))
    parser.add_argument("--libraries", type=Path, default=Path("/var/home/andre/.var/app/com.modrinth.ModrinthApp/data/ModrinthApp/meta/libraries"))
    parser.add_argument("--metadata", type=Path, default=ROOT / ".validation/inputs/26.3.json")
    options = parser.parse_args()
    assert hashlib.sha256(options.upstream.read_bytes()).hexdigest() == UPSTREAM_SHA256, "Wrong upstream release"
    assert hashlib.sha1(options.minecraft.read_bytes()).hexdigest() == MINECRAFT_SHA1, "Wrong Minecraft 26.3 client"
    jdk = options.jdk.resolve()
    if not jdk.is_relative_to("/usr"):
        parser.error("This sandbox expects an existing JDK under /usr")
    metadata = json.loads(options.metadata.read_text())
    assert metadata["downloads"]["client"]["sha1"] == MINECRAFT_SHA1
    dependencies = []
    for lib in metadata["libraries"]:
        artifact = lib.get("downloads", {}).get("artifact")
        if not artifact: continue
        if "-natives-" in artifact["path"]: continue
        relative = Path(artifact["path"])
        assert not relative.is_absolute() and ".." not in relative.parts
        path = options.libraries / relative
        if not path.is_file(): continue
        assert hashlib.sha1(path.read_bytes()).hexdigest() == artifact["sha1"], "Changed game library"
        dependencies.append("/libraries/" + artifact["path"])
    shell = r'''
set -eu
mkdir -p /scratch/home /scratch/tmp /scratch/classes /scratch/tools /scratch/tests
JAVAC_FLAGS='-J-Xmx256m -J-XX:CompressedClassSpaceSize=64m -J-XX:ReservedCodeCacheSize=64m -J-XX:ActiveProcessorCount=2'
JAVA_FLAGS='-Xmx256m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
"$PATCH_JDK/bin/javac" $JAVAC_FLAGS --release 25 -d /scratch/classes /source/src/launch/java/baritone/launch/privacy/SignTextPrivacy.java
"$PATCH_JDK/bin/javac" $JAVAC_FLAGS --release 17 -cp /deps/asm.jar -d /scratch/tools /source/scripts/sign-privacy/GenerateMixin.java /source/scripts/compatibility/PatchMovementInput.java /source/scripts/compatibility/PatchMining.java /source/scripts/compatibility/PatchGradualLook.java
"$PATCH_JDK/bin/java" $JAVA_FLAGS -cp /scratch/tools:/deps/asm.jar GenerateMixin /inputs/minecraft.jar /scratch/classes
"$PATCH_JDK/bin/java" $JAVA_FLAGS -cp /scratch/tools:/deps/asm.jar PatchMovementInput /inputs/upstream.jar /scratch/classes
"$PATCH_JDK/bin/java" $JAVA_FLAGS -cp /scratch/tools:/deps/asm.jar PatchMining /inputs/upstream.jar /scratch/classes
"$PATCH_JDK/bin/javac" $JAVAC_FLAGS -proc:none --release 25 -cp "$LOOK_CP" -d /scratch/classes /source/src/main/java/baritone/utils/GradualLook.java /source/src/main/java/baritone/utils/GradualLookCommand.java
"$PATCH_JDK/bin/java" $JAVA_FLAGS -cp /scratch/tools:/deps/asm.jar PatchGradualLook /inputs/upstream.jar /scratch/classes
"$PATCH_JDK/bin/javac" $JAVAC_FLAGS --release 25 -cp /scratch/classes -d /scratch/tests $(find /source/scripts/sign-privacy/fixture -name '*.java')
"$PATCH_JDK/bin/java" $JAVA_FLAGS -cp /scratch/tests:/scratch/classes baritone.launch.privacy.SignTextPrivacyTest
/usr/bin/python3 - <<'PY'
import base64,json
from pathlib import Path
root=Path('/scratch/classes')
files={str(p.relative_to(root)):base64.b64encode(p.read_bytes()).decode() for p in root.rglob('*.class')}
print('BUILD_CLASSES='+json.dumps(files,sort_keys=True))
PY
'''
    command = [
        "systemd-run", "--user", "--scope", "--quiet", "--collect",
        "-p", "MemoryMax=1073741824", "-p", "TasksMax=64", "-p", "CPUQuota=200%",
        "prlimit", "--as=4294967296", "--cpu=90", "--fsize=33554432", "--nofile=256", "--core=0", "--",
        "bwrap", "--unshare-all", "--new-session", "--die-with-parent", "--clearenv",
        "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
        "--ro-bind", str(ROOT), "/source",
        "--ro-bind", str(options.minecraft.resolve()), "/inputs/minecraft.jar",
        "--ro-bind", str(options.upstream.resolve()), "/inputs/upstream.jar",
        "--ro-bind", str(options.asm.resolve()), "/deps/asm.jar",
        "--ro-bind", str(options.libraries.resolve()), "/libraries",
        "--proc", "/proc", "--dev", "/dev", "--size", "134217728", "--tmpfs", "/scratch",
        "--chdir", "/scratch", "--setenv", "HOME", "/scratch/home", "--setenv", "TMPDIR", "/scratch/tmp",
        "--setenv", "LOOK_CP", ":".join(["/inputs/minecraft.jar", "/inputs/upstream.jar", *dependencies]),
        "--setenv", "PATH", "/usr/bin", "--setenv", "PATCH_JDK", str(jdk), "/usr/bin/bash", "-c", shell,
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if completed.returncode:
        raise SystemExit(completed.stdout + completed.stderr)
    lines = completed.stdout.splitlines()
    payload = next(line.removeprefix("BUILD_CLASSES=") for line in lines if line.startswith("BUILD_CLASSES="))
    classes = json.loads(payload)
    assert sorted(classes) == sorted(CLASSES), "Unexpected compiled files"
    decoded = {name: base64.b64decode(data, validate=True) for name, data in classes.items()}
    assert sum(map(len, decoded.values())) < 65536
    for line in lines:
        if not line.startswith("BUILD_CLASSES="):
            print(line)

    destination = ROOT / "dist" / "baritone-api-fabric-1.20.0-compat.6.jar"
    destination.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(options.upstream) as original, zipfile.ZipFile(destination, "w") as patched:
        assert not any(name.upper().endswith((".SF", ".RSA", ".DSA")) for name in original.namelist()), "Signed input requires separate handling"
        for entry in original.infolist():
            data = original.read(entry.filename)
            if entry.filename in decoded:
                data = decoded[entry.filename]
            if entry.filename == "mixins.baritone.json":
                config = json.loads(data)
                config["client"].append("MixinSignEditScreen")
                data = (json.dumps(config, indent=2) + "\n").encode()
            elif entry.filename == "fabric.mod.json":
                config = json.loads(data)
                config["version"] = VERSION
                config["custom"]["signprivacy"] = {"scope": "baritone.* translations in editable sign text", "upstream_sha256": UPSTREAM_SHA256}
                config["custom"]["movementcompatibility"] = "Minecraft 26.3 keyboard vector normalization and sneak slowdown"
                config["custom"]["gradualgroundaim"] = "12 degree yaw / 8 degree pitch caps, shared fork/live processor and matching-view mining"
                config["custom"]["miningcompatibility"] = "Minecraft 26.3 normal Punch packets and held-item swing animation"
                data = (json.dumps(config, indent=2) + "\n").encode()
            patched.writestr(entry, data)
        for name, data in sorted(decoded.items()):
            if name in original.namelist():
                continue
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            patched.writestr(info, data)

    with zipfile.ZipFile(options.upstream) as original, zipfile.ZipFile(destination) as patched:
        assert patched.testzip() is None
        assert set(patched.namelist()) - set(original.namelist()) == set(CLASSES) - {"baritone/fp.class", "baritone/fj.class", "baritone/a.class", "baritone/f.class", "baritone/f$a.class", "baritone/f$b.class", "baritone/api/utils/RotationUtils.class", "baritone/api/behavior/look/IAimProcessor.class"}
        changed = {name for name in original.namelist() if original.read(name) != patched.read(name)}
        assert changed == {"mixins.baritone.json", "fabric.mod.json", "baritone/fp.class", "baritone/fj.class", "baritone/a.class", "baritone/f.class", "baritone/f$a.class", "baritone/f$b.class", "baritone/api/utils/RotationUtils.class", "baritone/api/behavior/look/IAimProcessor.class"}
        assert not any("/lang/" in name for name in original.namelist())
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".jar.sha256").write_text(f"{checksum}  {destination.name}\n")
    with zipfile.ZipFile(options.minecraft) as client:
        pack_version = json.loads(client.read("version.json"))["pack_version"]
    resource_format = [pack_version["resource_major"], pack_version["resource_minor"]]
    test_pack = destination.parent / "baritone-probe-test-pack-26.3.zip"
    with zipfile.ZipFile(test_pack, "w") as archive:
        resources = {
            "pack.mcmeta": {"pack": {"description": "Baritone sign probe positive control (testing only)", "min_format": resource_format, "max_format": resource_format}},
            "assets/minecraft/lang/en_us.json": {"baritone.setting.allowBreak": "BARITONE_PROBE_TEST_TRANSLATION"},
        }
        for name, data in resources.items():
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, json.dumps(data, indent=2) + "\n")
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().strip("\0").split("\0")
    source_files = set(tracked)
    for directory in ("scripts/sign-privacy", "scripts/compatibility", "src/launch/java/baritone/launch/privacy"):
        source_files.update(str(path.relative_to(ROOT)) for path in (ROOT / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    source_files.add("src/launch/java/baritone/launch/mixins/MixinSignEditScreen.java")
    source_files.update(["src/main/java/baritone/utils/GradualLook.java", "src/main/java/baritone/utils/GradualLookCommand.java"])
    source_archive = destination.parent / "baritone-1.20.0-compat.6-source.zip"
    with zipfile.ZipFile(source_archive, "w") as archive:
        for name in sorted(source_files):
            path = ROOT / name
            if path.is_file():
                entry = zipfile.ZipInfo("baritone-sign-privacy/" + name, (1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = (path.stat().st_mode & 0xffff) << 16
                archive.writestr(entry, path.read_bytes())
    (destination.parent / "README.md").write_text((ROOT / "scripts/sign-privacy/README.md").read_text())
    for artifact in (test_pack, source_archive):
        with zipfile.ZipFile(artifact) as archive:
            assert archive.testzip() is None
    print("Minecraft constructor: exactly one matching Component.getString call")
    print("JAR verified: only eight original classes and two metadata files changed; six new classes; bundled libraries unchanged")
    print(f"Output: {destination}\nSHA-256: {checksum}")
    print(f"Test pack: {test_pack}\nComplete source: {source_archive}")


if __name__ == "__main__":
    main()
