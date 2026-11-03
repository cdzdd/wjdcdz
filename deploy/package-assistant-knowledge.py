"""Package only committed fund-assistant FAQ text; never include workspace secrets."""
from pathlib import Path
import gzip
import hashlib
import io
import json
import re
import subprocess
import tarfile
from urllib.parse import urlsplit

SOURCE_REPOSITORY = "https://github.com/cdzdd/wjdcdz.git"
PREFIX = "docs/assistant-knowledge/"


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo, stderr=subprocess.PIPE)


def source_repository(value):
    if value.startswith("git@github.com:"):
        value = "https://github.com/" + value[len("git@github.com:"):]
    try:
        url = urlsplit(value)
        port = url.port
    except ValueError:
        raise ValueError("Source origin must identify the credential-free fund source repository") from None
    valid_scheme = url.scheme == "https" and url.username is None and url.password is None
    valid_ssh = url.scheme == "ssh" and url.username == "git" and url.password is None
    if (not (valid_scheme or valid_ssh) or url.hostname != "github.com" or port is not None
            or url.query or url.fragment or url.path.rstrip("/") not in ("/cdzdd/wjdcdz", "/cdzdd/wjdcdz.git")):
        raise ValueError("Source origin must identify the credential-free fund source repository")
    return SOURCE_REPOSITORY


def package(repo):
    repo = Path(repo).resolve()
    if git(repo, "status", "--porcelain", "--untracked-files=all").strip():
        raise ValueError("Commit all source changes before packaging assistant knowledge")
    commit = git(repo, "rev-parse", "HEAD").decode("ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A full committed SHA-1 source revision is required")
    repository = source_repository(git(repo, "remote", "get-url", "origin").decode("utf-8").strip())
    entries = git(repo, "ls-tree", "-r", "-z", "--full-tree", "HEAD", "--", PREFIX).split(b"\0")
    files = {}
    total = 0
    for entry in filter(None, entries):
        identity, raw_path = entry.split(b"\t", 1)
        mode, kind, blob = identity.split()
        path = raw_path.decode("utf-8")
        name = path.removeprefix(PREFIX)
        if (not path.startswith(PREFIX) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}\.md", name)
                or mode != b"100644" or kind != b"blob" or (repo / path).is_symlink()):
            raise ValueError("Only regular, flat, lowercase FAQ Markdown filenames are allowed")
        size = int(git(repo, "cat-file", "-s", blob.decode("ascii")))
        total += size
        if len(files) >= 32 or not 0 < size <= 64 * 1024 or total > 256 * 1024:
            raise ValueError("Knowledge limits are 32 articles, 64KiB per article and 256KiB total")
        content = git(repo, "cat-file", "blob", blob.decode("ascii"))
        text = content.decode("utf-8")
        if not text.startswith("# ") or "\x00" in text or not text.splitlines()[0][2:].strip():
            raise ValueError("Each UTF-8 FAQ must begin with a question heading and contain no NUL")
        files[name] = content
    if not files:
        raise ValueError("No committed assistant FAQ articles found")
    checksums = {name: hashlib.sha256(content).hexdigest() for name, content in sorted(files.items())}
    manifest = {"schemaVersion": 1, "sourceRepository": repository, "sourceCommit": commit, "files": checksums}
    members = {"manifest.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"), **files}
    data = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=data, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, content in sorted(members.items()):
                item = tarfile.TarInfo(name)
                item.size = len(content)
                item.mode = 0o644
                archive.addfile(item, io.BytesIO(content))
    archive_bytes = data.getvalue()
    basename = "fund-assistant-knowledge-" + commit[:12]
    metadata = {"schemaVersion": 1, "sourceRepository": repository, "sourceCommit": commit,
                "artifact": {"filename": basename + ".tar.gz", "sha256": hashlib.sha256(archive_bytes).hexdigest()}, "files": checksums}
    output = repo / ".runtime/runtime"
    if (not output.resolve().is_relative_to(repo) or (repo / ".runtime").is_symlink() or output.is_symlink()):
        raise ValueError("Runtime output must stay in the source workspace")
    archive_path, metadata_path = output / metadata["artifact"]["filename"], output / (basename + ".json")
    if archive_path.exists() or metadata_path.exists():
        raise ValueError("This revision already has knowledge artifacts; preserve them rather than overwrite")
    output.mkdir(parents=True, exist_ok=True)
    with archive_path.open("xb") as target:
        target.write(archive_bytes)
    with metadata_path.open("xb") as target:
        target.write((json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return metadata


if __name__ == "__main__":
    try:
        print(json.dumps(package(Path(__file__).resolve().parents[1]), ensure_ascii=False))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit("Knowledge package was not created: " + str(error)) from None
