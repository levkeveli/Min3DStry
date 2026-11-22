import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "tools" / "git_head_sha.sh"
SHA = "0123456789abcdef0123456789abcdef01234567"


def run_script(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["sh", str(SCRIPT), str(root)],
        text=True,
        capture_output=True,
        check=False,
    )


class GitHeadShaTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, relative: str, text: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_loose_ref(self) -> None:
        self.write(".git/HEAD", "ref: refs/heads/main\n")
        self.write(".git/refs/heads/main", SHA + "\n")
        result = run_script(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), SHA[:12])

    def test_packed_ref(self) -> None:
        self.write(".git/HEAD", "ref: refs/heads/main\n")
        self.write(
            ".git/packed-refs",
            "# pack-refs with: peeled fully-peeled sorted\n"
            f"{SHA} refs/heads/main\n"
            "1111111111111111111111111111111111111111 refs/heads/other\n",
        )
        result = run_script(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), SHA[:12])

    def test_detached_head(self) -> None:
        self.write(".git/HEAD", SHA + "\n")
        result = run_script(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), SHA[:12])

    def test_gitdir_file_with_commondir(self) -> None:
        worktree_git = self.root / "worktrees" / "agent"
        worktree_git.mkdir(parents=True)
        (worktree_git / "HEAD").write_text("ref: refs/heads/agent/branch\n", encoding="utf-8")
        (worktree_git / "commondir").write_text("../..\n", encoding="utf-8")
        self.write(".git", f"gitdir: {worktree_git.as_posix()}\n")
        # commondir resolves to <root>/worktrees/agent/../.. == <root>
        self.write("refs/heads/agent/branch", SHA + "\n")
        result = run_script(self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), SHA[:12])

    def test_missing_head_fails(self) -> None:
        (self.root / ".git").mkdir()
        result = run_script(self.root)
        self.assertNotEqual(result.returncode, 0)

    def test_no_git_directory_fails(self) -> None:
        result = run_script(self.root)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
