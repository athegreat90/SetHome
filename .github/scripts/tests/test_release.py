import importlib.util
from contextlib import chdir
import io
import json
import shutil
import subprocess
import unittest
import uuid
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("release", ROOT / ".github/scripts/release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = "a" * 40
BEFORE = "b" * 40


class GateTests(unittest.TestCase):
    def gate(self, old="1.0.0", new="2.0.0", **kwargs):
        read = Mock(side_effect=[f"mod_version={old}\n", f"mod_version={new}\n"])
        result = release.gate(kwargs.get("ref", "refs/heads/26"),
                              kwargs.get("before", BEFORE), SHA, read)
        return result, read

    def test_changed_stable(self):
        result, read = self.gate()
        self.assertEqual(result, "2.0.0")
        self.assertEqual(read.call_args_list[0].args, (BEFORE,))
        self.assertEqual(read.call_args_list[1].args, (SHA,))

    def test_unchanged(self):
        self.assertIsNone(self.gate(new="1.0.0")[0])

    def test_comment_only(self):
        read = Mock(side_effect=["mod_version=1.0.0\n", "# new comment\nmod_version = 1.0.0\n"])
        self.assertIsNone(release.gate("refs/heads/26", BEFORE, SHA, read))

    def test_prerelease_skips(self):
        self.assertIsNone(self.gate(new="2.0.0-RC1")[0])

    def test_invalid_versions_fail(self):
        for version in ["02.0.0", "2.0", "v2.0.0", "2.0.0; touch bad", "", "2.0.0-01"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.gate(new=version)

    def test_initial_and_wrong_branch_never_read(self):
        for args in [{"before": "0" * 40}, {"ref": "refs/heads/260"},
                     {"ref": "refs/pull/26/merge"}]:
            result, read = self.gate(**args)
            self.assertIsNone(result)
            read.assert_not_called()

    def test_duplicate_property_rejected(self):
        with self.assertRaises(ValueError):
            release.version_property("mod_version=1.0.0\nmod_version=2.0.0")

    def test_stable_build_metadata(self):
        self.assertEqual(self.gate(new="2.0.0+build.1")[0], "2.0.0+build.1")


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.jar = Mock(spec=Path)
        self.jar.name = "sethome-2.0.0.jar"
        self.jar.read_bytes.return_value = b"candidate"
        self.jar.__str__ = Mock(return_value="candidate.jar")
        self.gh = Mock()
        self.gh.api.side_effect = self.api
        self.tag = None
        self.existing = None
        self.asset = b"candidate"

    def api(self, endpoint):
        if "/commits/" in endpoint:
            return self.tag
        return self.existing

    def ensure(self):
        return release.ensure_release("owner/repo", "2.0.0", SHA, self.jar, self.gh)

    def test_new_release(self):
        self.ensure()
        args = self.gh.run.call_args_list[0].args[0]
        self.assertIn("--generate-notes", args)
        self.assertIn(SHA, args)
        self.assertIn("candidate.jar", args)
        self.assertIn("refs%2Ftags%2F2.0.0", self.gh.api.call_args_list[0].args[0])
        self.gh.create_tag.assert_called_once_with("2.0.0", SHA)

    def test_tag_collision_does_not_create_release(self):
        self.gh.create_tag.side_effect = subprocess.CalledProcessError(1, "gh")
        with self.assertRaises(subprocess.CalledProcessError):
            self.ensure()
        self.gh.run.assert_not_called()

    def test_existing_exact_tag_creates_release_without_moving_tag(self):
        self.tag = {"sha": SHA}
        self.ensure()
        self.assertIn("--verify-tag", self.gh.run.call_args.args[0])
        self.gh.create_tag.assert_not_called()

    def test_github_write_failure_propagates(self):
        self.gh.run.side_effect = subprocess.CalledProcessError(1, "gh")
        with self.assertRaises(subprocess.CalledProcessError):
            self.ensure()

    def test_exact_existing_release_no_duplicate(self):
        self.tag = {"sha": SHA}
        self.existing = {"tag_name": "2.0.0", "draft": False, "prerelease": False,
                         "assets": [{"name": self.jar.name}]}
        self.gh.download.return_value = self.asset
        self.ensure()
        self.gh.run.assert_not_called()

    def test_wrong_commit_tag_fails_without_write(self):
        self.tag = {"sha": BEFORE}
        with self.assertRaises(ValueError):
            self.ensure()
        self.gh.run.assert_not_called()

    def test_existing_prerelease_rejected(self):
        self.tag = {"sha": SHA}
        self.existing = {"tag_name": "2.0.0", "draft": False, "prerelease": True}
        with self.assertRaises(ValueError):
            self.ensure()

    def test_asset_mismatch_is_not_overwritten(self):
        self.tag = {"sha": SHA}
        self.existing = {"tag_name": "2.0.0", "draft": False, "prerelease": False,
                         "assets": [{"name": self.jar.name}]}
        self.gh.download.return_value = b"other"
        with self.assertRaises(ValueError):
            self.ensure()
        self.gh.run.assert_not_called()

    def test_missing_asset_recovers_partial_github_failure(self):
        self.tag = {"sha": SHA}
        self.existing = {"tag_name": "2.0.0", "draft": False, "prerelease": False, "assets": []}
        self.ensure()
        self.assertEqual(self.gh.run.call_args.args[0][:2], ["release", "upload"])


class ModrinthTests(unittest.TestCase):
    def version(self, prerelease=False):
        return {"version_number": "2.0.0", "version_type": "beta" if prerelease else "release",
                "loaders": ["neoforge"], "game_versions": ["26.1.2", "26.2", "26.3"],
                "files": [{"hashes": {"sha512": release.hashlib.sha512(b"candidate").hexdigest()}}]}

    def test_first_upload_needed(self):
        self.assertFalse(release.already_published([], "2.0.0", b"candidate", False))

    def test_rerun_skips_successful_upload(self):
        self.assertTrue(release.already_published([self.version()], "2.0.0", b"candidate", False))

    def test_manual_prerelease_still_beta(self):
        self.assertTrue(release.already_published([self.version(True)], "2.0.0", b"candidate", True))

    def test_partial_failure_retries_only_missing_upload(self):
        upload = Mock(side_effect=[RuntimeError("upload failed"), None])
        versions = []
        for attempt in range(3):
            if not release.already_published(versions, "2.0.0", b"candidate", False):
                if attempt == 0:
                    with self.assertRaises(RuntimeError):
                        upload()
                else:
                    upload()
                    versions.append(self.version())
        self.assertEqual(upload.call_count, 2)

    def test_existing_different_artifact_fails(self):
        with self.assertRaises(ValueError):
            release.already_published([self.version()], "2.0.0", b"wrong", False)

    def test_existing_different_metadata_fails(self):
        version = self.version()
        version["game_versions"] = ["26.3"]
        with self.assertRaises(ValueError):
            release.already_published([version], "2.0.0", b"candidate", False)


class WorkflowTests(unittest.TestCase):
    def test_version_locks_exist_only_in_gated_jobs(self):
        text = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertNotIn("\nconcurrency:", text)
        gate, create = text.split("  create-release:", 1)
        self.assertNotIn("concurrency:", gate)
        self.assertIn("    needs: [gate, build]", create)
        self.assertIn("    concurrency:\n"
                      "      group: sethome-release-${{ needs.gate.outputs.version }}\n"
                      "      cancel-in-progress: false", create)
        publish = (ROOT / ".github/workflows/publish.yml").read_text()
        self.assertNotIn("\nconcurrency:", publish)
        self.assertIn("    concurrency:\n"
                      "      group: sethome-modrinth-${{ inputs.version || github.event.release.tag_name }}\n"
                      "      cancel-in-progress: false", publish)

    def test_manual_source_and_executing_helper_are_separate_checkouts(self):
        text = (ROOT / ".github/workflows/publish.yml").read_text()
        self.assertIn("ref: ${{ github.workflow_sha }}\n          path: tools", text)
        self.assertIn("ref: ${{ inputs.commit || format('refs/tags/{0}', github.event.release.tag_name) }}\n"
                      "          path: source", text)
        self.assertIn("    defaults:\n      run:\n        working-directory: source", text)
        self.assertIn("run: python3 ../tools/.github/scripts/release.py check", text)
        self.assertIn("run: ./gradlew build", text)
        self.assertIn("path: source/candidate", text)
        self.assertIn("files: source/${{ steps.publication.outputs.jar }}", text)

    def test_build_precedes_release_and_reusable_publication(self):
        text = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("branches: ['26']", text)
        self.assertIn("needs: gate", text)
        self.assertIn("needs: [gate, build]", text)
        self.assertIn("needs: [gate, create-release]", text)
        self.assertIn("uses: ./.github/workflows/publish.yml", text)
        self.assertEqual(text.count("contents: write"), 1)
        self.assertIn("cancel-in-progress: false", text)
        self.assertIn("MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}", text)

    def test_manual_and_automatic_paths_preserve_metadata(self):
        text = (ROOT / ".github/workflows/publish.yml").read_text()
        for expected in ["workflow_call:", "types: [published]", "inputs.commit ||",
                         "if: ${{ !inputs.artifact }}", "if: ${{ inputs.artifact }}",
                         "actions/download-artifact@v4", "already_published",
                         "dedicated-server", "26.1.2", "26.2", "26.3", "java: 25",
                         "version-type:", "secrets.MODRINTH_TOKEN"]:
            self.assertIn(expected, text)
        self.assertNotIn("contents: write", text)
        self.assertIn("cancel-in-progress: false", text)

    def test_build_failure_blocks_release(self):
        build = Mock(side_effect=subprocess.CalledProcessError(1, "gradle"))
        create = Mock()
        with self.assertRaises(subprocess.CalledProcessError):
            build()
            create()
        create.assert_not_called()
        text = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("needs: [gate, build]", text)
        self.assertNotIn("continue-on-error", text)


class CommandTests(unittest.TestCase):
    def test_legacy_source_without_helper_uses_its_own_properties_and_sha(self):
        source = ROOT / (".release-test-" + uuid.uuid4().hex)
        source.mkdir()
        try:
            (source / "gradle.properties").write_text("mod_version=2.0.0-RC1\n")
            (source / "build/libs").mkdir(parents=True)
            (source / "build/libs/sethome-2.0.0-RC1.jar").write_bytes(b"candidate")
            self.assertFalse((source / ".github/scripts/release.py").exists())
            gh = Mock()
            gh.api.side_effect = [
                {"sha": SHA},
                {"tag_name": "2.0.0-RC1", "draft": False, "prerelease": True,
                 "name": "Legacy", "body": ""}
            ]
            response = Mock()
            response.__enter__ = Mock(return_value=io.StringIO("[]"))
            response.__exit__ = Mock(return_value=False)
            with chdir(source), patch.dict(release.os.environ, {
                    "VERSION": "2.0.0-RC1", "COMMIT": "", "JAR_DIRECTORY": "build/libs",
                    "GITHUB_REPOSITORY": "owner/repo"
            }), patch.object(release.subprocess, "check_output", return_value=SHA) as git, \
                    patch.object(release, "GitHub", return_value=gh), \
                    patch.object(release, "urlopen", return_value=response), \
                    patch.object(release, "output") as output:
                release.main("check")
                self.assertTrue(all(call.args[0] == ["git", "rev-parse", "HEAD"]
                                    for call in git.call_args_list))
            self.assertEqual(output.call_args.kwargs["version_type"], "beta")
            self.assertEqual(Path(output.call_args.kwargs["jar"]),
                             Path("build/libs/sethome-2.0.0-RC1.jar"))
        finally:
            shutil.rmtree(source)

    def test_gh_only_not_found_is_recoverable(self):
        gh = release.GitHub("owner/repo")
        with patch.object(release.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "HTTP 404: Not Found")
            self.assertIsNone(gh.api("endpoint"))
            run.return_value = subprocess.CompletedProcess([], 1, "", "HTTP 403: Forbidden")
            with self.assertRaises(RuntimeError):
                gh.api("endpoint")

    def test_manual_stable_and_prerelease_metadata_and_repeated_publication(self):
        for prerelease in [False, True]:
            for repeated in [False, True]:
                with self.subTest(prerelease=prerelease, repeated=repeated):
                    version = "2.0.0-RC1" if prerelease else "2.0.0"
                    gh = Mock()
                    gh.api.side_effect = [
                        {"sha": SHA},
                        {"tag_name": version, "draft": False, "prerelease": prerelease,
                         "name": "Release name", "body": "Notes\n$(not shell)\nsecond line"}
                    ]
                    jar = Mock()
                    jar.read_bytes.return_value = b"candidate"
                    versions = []
                    if repeated:
                        existing = ModrinthTests().version(prerelease)
                        existing["version_number"] = version
                        versions.append(existing)
                    response = Mock()
                    response.__enter__ = Mock(return_value=io.StringIO(json.dumps(versions)))
                    response.__exit__ = Mock(return_value=False)
                    with patch.dict(release.os.environ, {
                        "VERSION": version, "COMMIT": "",
                        "GITHUB_REPOSITORY": "owner/repo"
                    }), patch.object(release, "git", return_value=SHA), \
                            patch.object(release.Path, "read_text", return_value=f"mod_version={version}"), \
                            patch.object(release, "candidate", return_value=jar), \
                            patch.object(release, "GitHub", return_value=gh), \
                            patch.object(release, "urlopen", return_value=response), \
                            patch.object(release, "output") as output:
                        release.main("check")
                    values = output.call_args.kwargs
                    self.assertEqual(values["already_published"], str(repeated).lower())
                    self.assertEqual(values["version_type"], "beta" if prerelease else "release")
                    self.assertEqual(values["changelog"], "Notes\n$(not shell)\nsecond line")
                    gh.run.assert_not_called()

    def test_wrong_checkout_fails_before_release_write(self):
        with patch.dict(release.os.environ, {"VERSION": "2.0.0", "COMMIT": SHA}), \
                patch.object(release, "git", return_value=BEFORE), \
                patch.object(release, "GitHub") as gh:
            with self.assertRaises(ValueError):
                release.main("release")
            gh.assert_not_called()

    def test_missing_candidate_fails_instead_of_glob_fallback(self):
        with patch.object(release.Path, "is_file", return_value=False):
            with self.assertRaises(FileNotFoundError):
                release.candidate("build/libs", "2.0.0")


if __name__ == "__main__":
    unittest.main()
