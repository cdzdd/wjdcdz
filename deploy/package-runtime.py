"""Create COPY-only Docker build contexts from the current clean Git revision."""
from pathlib import Path
import hashlib
import io
import json
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main():
    if git("status", "--porcelain", "--untracked-files=normal"):
        raise SystemExit("Commit source changes before building a release package.")
    commit = git("rev-parse", "HEAD")
    repository = git("remote", "get-url", "origin")
    if repository.startswith(("https://", "http://")) and "@" in repository.split("/", 3)[2]:
        raise SystemExit("Source repository URL must not contain credentials.")

    files = {
        "server/Dockerfile": ROOT / "deploy/runtime/Dockerfile.server",
        "server/app.jar": ROOT / "server/target/fund-simulation-1.0.0.jar",
        "web/Dockerfile": ROOT / "deploy/runtime/Dockerfile.web",
        "web/nginx.conf": ROOT / "deploy/nginx.conf",
    }
    dist = ROOT / "web/dist"
    if not (dist / "index.html").is_file():
        raise SystemExit("Missing production frontend: run deploy/build-runtime.ps1 first.")
    for path in sorted(dist.rglob("*")):
        if path.is_symlink():
            raise SystemExit("Frontend output must not contain symbolic links.")
        if path.is_file():
            files["web/dist/" + path.relative_to(dist).as_posix()] = path
    for name, path in files.items():
        if not path.is_file() or path.is_symlink():
            raise SystemExit("Missing regular build artifact: " + name)

    manifest = {
        "sourceCommit": commit,
        "sourceRepository": repository,
        "platform": "linux/amd64",
        "images": {
            "server": "ferrari-fund-server:" + commit[:12],
            "web": "ferrari-fund-web:" + commit[:12],
        },
        "baseImages": {
            "server": "eclipse-temurin:21-jre-alpine",
            "web": "nginx:1.28-alpine",
        },
        "files": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in sorted(files.items())},
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output = ROOT / ".runtime/runtime"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / ("fund-" + commit[:12] + ".tar.gz")
    with tarfile.open(archive, "w:gz") as bundle:
        for name, path in sorted(files.items()):
            bundle.add(path, arcname=name, recursive=False)
        entry = tarfile.TarInfo("manifest.json")
        entry.size = len(manifest_bytes)
        entry.mode = 0o644
        bundle.addfile(entry, io.BytesIO(manifest_bytes))
    (output / ("fund-" + commit[:12] + "-manifest.json")).write_bytes(manifest_bytes)
    print(json.dumps({"archive": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                      "sourceCommit": commit, "images": manifest["images"], "files": len(files) + 1}))


if __name__ == "__main__":
    main()
