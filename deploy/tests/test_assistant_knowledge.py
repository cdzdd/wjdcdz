"""Delivery-boundary tests; these do not assert editorial wording or model quality."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("assistant_knowledge", Path(__file__).resolve().parents[1] / "package-assistant-knowledge.py")
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class KnowledgePackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fund-knowledge-test-")
        self.repo = Path(self.temporary.name)
        self.git("init", "--quiet")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Local fixture")
        self.git("config", "core.autocrlf", "false")
        self.git("config", "core.symlinks", "false")
        self.git("remote", "add", "origin", packager.SOURCE_REPOSITORY)
        self.write(".gitignore", ".runtime/\n")
        self.write("docs/assistant-knowledge/01-example.md", "# 怎么操作？\n\n这是模拟平台说明。\n")
        self.commit()

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args, data=None):
        return subprocess.run(["git", *args], cwd=self.repo, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout

    def write(self, relative, text):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "fixture")

    def test_exact_committed_identity_flat_members_and_digests(self):
        self.write("docs/assistant-knowledge.md", "This outside maintenance guide must not enter the package.")
        self.commit()
        result = packager.package(self.repo)
        self.assertEqual(result["sourceCommit"], self.git("rev-parse", "HEAD").decode().strip())
        self.assertEqual(result["sourceRepository"], packager.SOURCE_REPOSITORY)
        archive = self.repo / ".runtime/runtime" / result["artifact"]["filename"]
        self.assertEqual(result["artifact"]["sha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        with tarfile.open(archive) as bundle:
            self.assertEqual(set(bundle.getnames()), {"manifest.json", "01-example.md"})
            self.assertTrue(all(member.isfile() and member.mode == 0o644 for member in bundle))
            manifest = json.load(bundle.extractfile("manifest.json"))
            self.assertEqual(set(manifest), {"schemaVersion", "sourceRepository", "sourceCommit", "files"})
            content = self.git("show", "HEAD:docs/assistant-knowledge/01-example.md")
            self.assertEqual(bundle.extractfile("01-example.md").read(), content)
            self.assertEqual(manifest["files"], {"01-example.md": hashlib.sha256(content).hexdigest()})
            self.assertEqual(manifest["files"], result["files"])
        self.assertEqual(json.loads(archive.with_suffix("").with_suffix(".json").read_text()), result)
        with self.assertRaisesRegex(ValueError, "already has"):
            packager.package(self.repo)

    def test_uncommitted_tracked_and_untracked_content_cannot_be_labelled_as_head(self):
        self.write("docs/assistant-knowledge/01-example.md", "# 改动尚未提交？\n不能打包。")
        with self.assertRaisesRegex(ValueError, "Commit all"):
            packager.package(self.repo)
        self.commit()
        self.write("untracked-private.txt", "fake test data")
        with self.assertRaisesRegex(ValueError, "Commit all"):
            packager.package(self.repo)

    def test_origin_normalization_and_wrong_or_credentialed_repository_rejection(self):
        for source in ("git@github.com:cdzdd/wjdcdz.git", "ssh://git@github.com/cdzdd/wjdcdz.git", "https://github.com/cdzdd/wjdcdz"):
            self.assertEqual(packager.source_repository(source), packager.SOURCE_REPOSITORY)
        for source in ("https://token@github.com/cdzdd/wjdcdz.git", "http://github.com/cdzdd/wjdcdz.git", "https://github.com/other/repo.git", "https://github.com/cdzdd/wjdcdz.git?token=fake", "https://github.com:fake-secret/cdzdd/wjdcdz.git"):
            with self.assertRaisesRegex(ValueError, "Source origin must identify"):
                packager.source_repository(source)
        self.git("remote", "set-url", "origin", "https://github.com/other/repo.git")
        with self.assertRaises(ValueError):
            packager.package(self.repo)

    def test_tracked_nested_and_unknown_files_are_rejected_instead_of_silently_omitted(self):
        for path in ("nested/02-example.md", "private.env", "README.md"):
            with self.subTest(path=path):
                self.write("docs/assistant-knowledge/" + path, "# 不应被打包\n测试。")
                self.commit()
                with self.assertRaisesRegex(ValueError, "regular, flat"):
                    packager.package(self.repo)
                self.git("rm", "--", "docs/assistant-knowledge/" + path)
                self.git("commit", "--quiet", "-m", "remove fixture")

    def test_git_symlink_is_rejected_even_on_windows_without_symlink_privilege(self):
        blob = self.git("hash-object", "-w", "--stdin", data=b"../../private.env").decode().strip()
        path = "docs/assistant-knowledge/01-example.md"
        self.git("update-index", "--cacheinfo", "120000," + blob + "," + path)
        self.git("commit", "--quiet", "-m", "symlink fixture")
        (self.repo / path).unlink()
        self.git("checkout", "--", path)
        self.assertFalse(self.git("status", "--porcelain").strip())
        with self.assertRaisesRegex(ValueError, "regular, flat"):
            packager.package(self.repo)

    def test_article_count_single_size_and_total_size_are_bounded(self):
        for number in range(2, 34):
            self.write(f"docs/assistant-knowledge/{number:02}-example.md", "# 测试？\n内容。")
        self.commit()
        with self.assertRaisesRegex(ValueError, "Knowledge limits"):
            packager.package(self.repo)
        for number in range(2, 34):
            self.git("rm", "--", f"docs/assistant-knowledge/{number:02}-example.md")
        self.write("docs/assistant-knowledge/01-example.md", "# Big?\n" + "x" * (64 * 1024))
        self.commit()
        with self.assertRaisesRegex(ValueError, "Knowledge limits"):
            packager.package(self.repo)
        for number in range(1, 6):
            self.write(f"docs/assistant-knowledge/{number:02}-example.md", "# Size?\n" + "x" * 60000)
        self.commit()
        with self.assertRaisesRegex(ValueError, "Knowledge limits"):
            packager.package(self.repo)


if __name__ == "__main__":
    unittest.main()
