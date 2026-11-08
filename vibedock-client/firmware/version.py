# PlatformIO pre-script: compiles the firmware version from the Git tag into VIBEDOCK_VERSION ("0.6.0" on the tag
# client-v0.6.0, "0.6.0-3-g503ce5c" later, "-dirty" with uncommitted changes). The client has its own tags; as long as
# there is none, the plain version tags of the earlier common releases (0.5.1 or v0.5.1) apply.
import os, subprocess
Import("env")

def git(*args):
    return subprocess.run(["git", "-c", "safe.directory=*", *args], cwd=env.subst("$PROJECT_DIR"), capture_output=True, text=True)

def describe():
    out = git("describe", "--tags", "--match", "client-v[0-9]*")
    if out.returncode != 0:
        out = git("describe", "--tags", "--match", "v[0-9]*", "--match", "[0-9]*")
    if out.returncode == 0:
        version = out.stdout.strip()
        for prefix in ("client-v", "v"):
            if version.startswith(prefix): version = version[len(prefix):]; break
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
