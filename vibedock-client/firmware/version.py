# PlatformIO pre-script: compiles the firmware version from the Git tag into VIBEDOCK_VERSION, like the server's
# scripts/version.sh ("0.2.0" on the tag 0.2.0 or v0.2.0, "0.2.0-3-g503ce5c" later, "-dirty" with uncommitted changes).
import os, subprocess
Import("env")

def git(*args):
    return subprocess.run(["git", "-c", "safe.directory=*", *args], cwd=env.subst("$PROJECT_DIR"), capture_output=True, text=True)

def describe():
    out = git("describe", "--tags", "--match", "v[0-9]*", "--match", "[0-9]*")
    if out.returncode == 0:
        version = out.stdout.strip()
        version = version[1:] if version.startswith("v") else version
    else:
        commit = git("rev-parse", "--short", "HEAD")
        if commit.returncode != 0:
            return "dev"
        version = "0.0.0-g" + commit.stdout.strip()
    # Only a real difference (exit code 1) marks the build dirty, not a missing work tree (GIT_DIR alone, as in Docker).
    return version + ("-dirty" if git("diff", "--quiet", "HEAD", "--", ".").returncode == 1 else "")

version = "".join(c for c in describe() if c.isalnum() or c in ".-+")[:40] or "dev"
print("VibeDock firmware version: " + version)
env.Append(CPPDEFINES=[("VIBEDOCK_VERSION", env.StringifyMacro(version))])
# Next to firmware.bin, for whoever publishes it (the server image).
os.makedirs(env.subst("$BUILD_DIR"), exist_ok=True)
with open(os.path.join(env.subst("$BUILD_DIR"), "version"), "w") as f:
    f.write(version + "\n")
