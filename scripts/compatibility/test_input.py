#!/usr/bin/env python3
"""Compare production movement input against actual Minecraft 26.3 keyboard bytecode offline."""
import argparse
import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, default=Path("/usr/lib/jvm/java-27"))
    parser.add_argument("--source", type=Path, default=ROOT / "src/main/java/baritone/utils/PlayerMovementInput.java")
    options = parser.parse_args()
    if hashlib.sha1(options.minecraft.read_bytes()).hexdigest() != "e877b6a07acd633fb3bb475002175cec036e7b87":
        parser.error("Expected the pinned Minecraft 26.3 client JAR")
    jdk = options.jdk.resolve()
    if not jdk.is_relative_to("/usr"):
        parser.error("The sandbox requires an existing JDK under /usr")
    script = r'''
set -eu
mkdir -p /scratch/home /scratch/tmp /scratch/classes
/usr/bin/python3 - <<'PY'
import zipfile
from pathlib import Path
with zipfile.ZipFile('/inputs/minecraft.jar') as jar:
    for name in ('net/minecraft/client/player/KeyboardInput.class',):
        target=Path('/scratch/classes')/name
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(jar.read(name))
PY
"$PATCH_JDK/bin/javac" -J-Xmx256m -J-XX:CompressedClassSpaceSize=64m -J-XX:ReservedCodeCacheSize=64m -J-XX:ActiveProcessorCount=2 --release 25 \
  -cp /scratch/classes -d /scratch/classes \
  /source/src/api/java/baritone/api/utils/input/Input.java \
  /inputs/PlayerMovementInput.java \
  $(find /source/scripts/compatibility/fixture -name '*.java')
"$PATCH_JDK/bin/java" -Xmx256m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 \
  -cp /scratch/classes baritone.utils.PlayerMovementInputTest
'''
    command = [
        "systemd-run", "--user", "--scope", "--quiet", "--collect",
        "-p", "MemoryMax=1073741824", "-p", "TasksMax=64", "-p", "CPUQuota=200%",
        "prlimit", "--as=4294967296", "--cpu=90", "--fsize=33554432", "--nofile=128", "--core=0", "--",
        "bwrap", "--unshare-all", "--new-session", "--die-with-parent", "--clearenv",
        "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
        "--ro-bind", str(ROOT), "/source", "--ro-bind", str(options.minecraft.resolve()), "/inputs/minecraft.jar",
        "--ro-bind", str(options.source.resolve()), "/inputs/PlayerMovementInput.java",
        "--proc", "/proc", "--dev", "/dev", "--size", "134217728", "--tmpfs", "/scratch", "--chdir", "/scratch",
        "--setenv", "HOME", "/scratch/home", "--setenv", "TMPDIR", "/scratch/tmp",
        "--setenv", "PATH", "/usr/bin", "--setenv", "PATCH_JDK", str(jdk), "/usr/bin/bash", "-c", script,
    ]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    print(result.stdout, end="")
    print(result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
