#!/usr/bin/env python3
"""Execute real Minecraft/release classes using verified, already cached launcher libraries."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FABRIC_CACHE = {
    "net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar": "73eed8c34bbad0320a2a3cba5346351e822f74f82b3f3c060574068474132958",
    "net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar": "9e90efec71d2bad5b96c9089f019d14a8603227d3c5f408d12f53fae89d99d41",
    "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar": "ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb",
    "org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar": "3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b",
    "org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar": "dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc",
    "org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar": "1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c",
    "org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar": "6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--upstream-control", type=Path, help="Pinned official Baritone release for positive-control sign round trips")
    parser.add_argument("--libraries", type=Path, required=True)
    parser.add_argument("--fabric-loader", type=Path, help="Verified supported Fabric 0.19.5 JAR; otherwise use the cached 0.19.3 test override")
    parser.add_argument("--fabric-runtime", action="store_true", help="Also check the Mixin using --fabric-loader or the explicit cached-loader test override")
    parser.add_argument("--jdk", type=Path, default=Path("/usr/lib/jvm/java-27"))
    options = parser.parse_args()
    if not options.jdk.resolve().is_relative_to("/usr"):
        parser.error("The sandbox expects an existing JDK under /usr")
    if options.upstream_control:
        if not options.fabric_runtime:
            parser.error("--upstream-control requires --fabric-runtime")
        if hashlib.sha256(options.upstream_control.read_bytes()).hexdigest() != "49adfc063cfbfd0b6f08e9d814359807baa2d1768c0d39d6c5968268547cbca6":
            parser.error("Wrong upstream control release")
    if hashlib.sha1(options.minecraft.read_bytes()).hexdigest() != "e877b6a07acd633fb3bb475002175cec036e7b87":
        parser.error("Wrong Minecraft client")
    metadata = json.loads(options.metadata.read_text())
    dependencies = []
    for library in metadata["libraries"]:
        artifact = library.get("downloads", {}).get("artifact")
        if not artifact:
            continue
        path = options.libraries / artifact["path"]
        if not path.is_file():
            # All missing cache entries belong to other operating systems.
            rules = library.get("rules", [])
            if any(rule.get("os", {}).get("name") in ("osx", "windows") for rule in rules) or any(word in artifact["path"] for word in ("macos", "windows", "osx")):
                continue
            parser.error("Missing cached dependency: " + artifact["path"])
        if hashlib.sha1(path.read_bytes()).hexdigest() != artifact["sha1"]:
            parser.error("Cached dependency checksum mismatch: " + artifact["path"])
        dependencies.append("/libraries/" + artifact["path"])
    classpath = ":".join(["/inputs/minecraft.jar", "/inputs/candidate.jar", *dependencies])
    fabric_dependencies = []
    loader_mount = []
    loader_version = "0.19.3"
    if options.fabric_loader:
        if not options.fabric_runtime:
            parser.error("--fabric-loader requires --fabric-runtime")
        if hashlib.sha256(options.fabric_loader.read_bytes()).hexdigest() != "93044e4dd46de5d8136701292f05e868da096d2c9fddb4793e4fdbcc63efc695":
            parser.error("Wrong supported Fabric 0.19.5 loader")
        loader_version = "0.19.5"
        loader_mount = ["--ro-bind", str(options.fabric_loader.resolve()), "/deps/fabric-loader.jar"]
        fabric_dependencies.append("/deps/fabric-loader.jar")
    if options.fabric_runtime:
        for name, checksum in FABRIC_CACHE.items():
            if options.fabric_loader and name.startswith("net/fabricmc/fabric-loader/"):
                continue
            path = options.libraries / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
                parser.error("Missing or changed cached Fabric tool: " + name)
            fabric_dependencies.append("/libraries/" + name)
    shell = r'''
set -eu
mkdir -p /scratch/home /scratch/tmp /scratch/classes /scratch/game/mods /scratch/game/config /scratch/etc
echo '127.0.0.1 localhost' > /scratch/etc/hosts
mkdir -p /scratch/sourcecheck
if [ -f /inputs/control.jar ]; then
mkdir -p /scratch/apicheck
"$PATCH_JDK/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -cp "$PATCH_GAME_CP:/inputs/control.jar" -d /scratch/apicheck /source/src/api/java/baritone/api/behavior/look/IAimProcessor.java /source/src/api/java/baritone/api/utils/RotationUtils.java
echo 'Reachability API compiles independently of the new main implementation'
fi
"$PATCH_JDK/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -cp "$PATCH_CP" -d /scratch/sourcecheck /source/src/main/java/baritone/utils/accessor/IPlayerControllerMP.java /source/src/main/java/baritone/utils/BlockBreakHelper.java /source/src/main/java/baritone/utils/GradualLook.java /source/src/main/java/baritone/utils/GradualLookCommand.java /source/src/api/java/baritone/api/utils/RotationUtils.java /source/src/api/java/baritone/api/behavior/look/IAimProcessor.java
"$PATCH_JDK/bin/javac" -J-Xmx512m -J-XX:ActiveProcessorCount=2 --release 25 -cp "$PATCH_CP" -d /scratch/classes /source/scripts/compatibility/ActualRuntimeTest.java /source/scripts/compatibility/GradualLookTest.java
"$PATCH_JDK/bin/java" -Djava.io.tmpdir=/scratch/tmp -Djdk.net.hosts.file=/scratch/etc/hosts -Xmx512m -XX:ActiveProcessorCount=2 -XX:CompressedClassSpaceSize=128m -XX:ReservedCodeCacheSize=128m --sun-misc-unsafe-memory-access=allow --enable-final-field-mutation=ALL-UNNAMED \
  -cp "/scratch/classes:$PATCH_CP" ActualRuntimeTest
"$PATCH_JDK/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 -cp "/scratch/classes:$PATCH_CP" GradualLookTest
'''
    if options.fabric_runtime:
        shell += r'''
cp /inputs/candidate.jar /scratch/game/mods/baritone.jar
if [ "$PATCH_LOADER" = "0.19.3" ]; then
python3 - <<'PY'
import json, zipfile
from pathlib import Path
with zipfile.ZipFile('/inputs/candidate.jar') as jar:
    metadata = json.loads(jar.read('fabric.mod.json'))
dependencies = metadata['depends']
dependencies['fabricloader'] = '>=0.19.3'
Path('/scratch/game/config/fabric_loader_dependencies.json').write_text(json.dumps({'version': 1, 'overrides': {metadata['id']: {'depends': dependencies}}}))
PY
fi
"$PATCH_JDK/bin/javac" -J-Xmx256m --release 25 -cp "$PATCH_CP:$PATCH_FABRIC_CP" -d /scratch/launcher /source/scripts/compatibility/KnotSmokeTest.java
"$PATCH_JDK/bin/javac" -J-Xmx256m --release 25 -cp "$PATCH_CP:$PATCH_FABRIC_CP" -d /scratch/source-check /source/src/launch/java/baritone/launch/mixins/MixinSignEditScreen.java
"$PATCH_JDK/bin/javac" -J-Xmx256m --release 25 -cp "$PATCH_CP" -d /scratch/game-tests /source/scripts/compatibility/SignRoundTripTest.java
"$PATCH_JDK/bin/java" -Djava.io.tmpdir=/scratch/tmp -Djdk.net.hosts.file=/scratch/etc/hosts -Dmixin.debug.export=true -Dmixin.debug.export.decompile=false -Djava.awt.headless=true --sun-misc-unsafe-memory-access=allow --enable-final-field-mutation=ALL-UNNAMED -Xmx512m -XX:ActiveProcessorCount=2 -XX:CompressedClassSpaceSize=128m -XX:ReservedCodeCacheSize=128m \
 -cp "/scratch/launcher:$PATCH_CP:$PATCH_FABRIC_CP" KnotSmokeTest
'''
    if options.upstream_control:
        shell += r'''
cp /inputs/control.jar /scratch/game/mods/baritone.jar
"$PATCH_JDK/bin/java" -Djava.io.tmpdir=/scratch/tmp -Djdk.net.hosts.file=/scratch/etc/hosts -Djava.awt.headless=true --sun-misc-unsafe-memory-access=allow --enable-final-field-mutation=ALL-UNNAMED -Xmx512m -XX:ActiveProcessorCount=2 -XX:CompressedClassSpaceSize=128m -XX:ReservedCodeCacheSize=128m \
 -cp "/scratch/launcher:/inputs/control.jar:$PATCH_GAME_CP:$PATCH_FABRIC_CP" KnotSmokeTest --upstream-control
'''
    control_mount = ["--ro-bind", str(options.upstream_control.resolve()), "/inputs/control.jar"] if options.upstream_control else []
    command = ["systemd-run", "--user", "--scope", "--quiet", "--collect", "-p", "MemoryMax=2147483648", "-p", "TasksMax=96", "-p", "CPUQuota=200%",
        "prlimit", "--as=8589934592", "--cpu=90", "--fsize=33554432", "--nofile=256", "--core=0", "--",
        "bwrap", "--unshare-all", "--hostname", "localhost", "--new-session", "--die-with-parent", "--clearenv",
        "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
        "--ro-bind", "/etc/java", "/etc/java", "--ro-bind", "/etc/crypto-policies", "/etc/crypto-policies",
        "--ro-bind", "/etc/pki/ca-trust/extracted", "/etc/pki/ca-trust/extracted",
        "--ro-bind", str(ROOT), "/source", "--ro-bind", str(options.libraries.resolve()), "/libraries",
        "--ro-bind", str(options.minecraft.resolve()), "/inputs/minecraft.jar", "--ro-bind", str(options.candidate.resolve()), "/inputs/candidate.jar",
        *control_mount, *loader_mount,
        "--proc", "/proc", "--dev", "/dev", "--size", "268435456", "--tmpfs", "/scratch", "--chdir", "/scratch",
        "--setenv", "HOME", "/scratch/home", "--setenv", "TMPDIR", "/scratch/tmp", "--setenv", "PATH", "/usr/bin",
        "--setenv", "PATCH_LOADER", loader_version, "--setenv", "PATCH_CP", classpath, "--setenv", "PATCH_GAME_CP", ":".join(["/inputs/minecraft.jar", *dependencies]),
        "--setenv", "PATCH_FABRIC_CP", ":".join(fabric_dependencies), "--setenv", "PATCH_JDK", str(options.jdk.resolve()), "/usr/bin/bash", "-c", shell]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    print(result.stdout, end=""); print(result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
