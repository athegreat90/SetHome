"""Offline-testable release checks; all mutations use the runner's gh CLI."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote
from urllib.request import Request, urlopen
import uuid


SEMVER = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?", re.ASCII
)


def version_property(text):
    values = re.findall(r"^\s*mod_version\s*[=:]\s*(.*?)\s*$", text, re.MULTILINE)
    if len(values) != 1 or not SEMVER.fullmatch(values[0]):
        raise ValueError("Expected exactly one literal SemVer mod_version property")
    return values[0]


def commit(value):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Expected a full commit SHA")
    return value


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def gate(ref, before, sha, read):
    if ref != "refs/heads/26" or before == "0" * 40:
        return None
    old = version_property(read(commit(before)))
    new = version_property(read(commit(sha)))
    if old == new or SEMVER.fullmatch(new).group(4) is not None:
        return None
    return new


def output(**values):
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            delimiter = uuid.uuid4().hex
            stream.write(f"{key}<<{delimiter}\n{value}\n{delimiter}\n")


class GitHub:
    def __init__(self, repository):
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository, re.ASCII):
            raise ValueError("Invalid repository")
        self.repository = repository

    def run(self, args):
        subprocess.run(["gh", *args, "--repo", self.repository], check=True)

    def api(self, endpoint):
        result = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True)
        if result.returncode:
            if "HTTP 404" in result.stderr:
                return None
            raise RuntimeError(f"GitHub API failed for {endpoint}: {result.stderr}")
        return json.loads(result.stdout)

    def download(self, version, name):
        return subprocess.check_output(
            ["gh", "release", "download", version, "--pattern", name,
             "--output", "-", "--repo", self.repository]
        )

    def create_tag(self, version, sha):
        subprocess.run(
            ["gh", "api", f"repos/{self.repository}/git/refs", "--method", "POST",
             "--raw-field", f"ref=refs/tags/{version}", "--raw-field", f"sha={sha}"],
            check=True
        )


def release_identity(repository, version, sha, gh, stable=False):
    tag = gh.api(f"repos/{repository}/commits/{quote('refs/tags/' + version, safe='')}")
    existing = gh.api(f"repos/{repository}/releases/tags/{quote(version, safe='')}")
    if tag is not None and tag["sha"] != sha:
        raise ValueError("Existing tag points at a different commit")
    if existing is not None:
        if (tag is None or existing["tag_name"] != version or existing["draft"]
                or (stable and existing["prerelease"])):
            raise ValueError("Existing release identity does not match")
    return tag, existing


def ensure_release(repository, version, sha, jar, gh):
    if not SEMVER.fullmatch(version) or SEMVER.fullmatch(version).group(4):
        raise ValueError("Automatic releases require stable SemVer")
    tag, existing = release_identity(repository, version, sha, gh, stable=True)
    if existing is None:
        if tag is None:
            gh.create_tag(version, sha)
        args = ["release", "create", version, str(jar), "--target", sha,
                "--title", version, "--generate-notes", "--verify-tag"]
        gh.run(args)
        return
    jars = [asset["name"] for asset in existing["assets"] if asset["name"].endswith(".jar")]
    if jars and jars != [jar.name]:
        raise ValueError("Existing release contains a different JAR asset")
    if jar.name in jars:
        if gh.download(version, jar.name) != jar.read_bytes():
            raise ValueError("Existing GitHub asset differs from the candidate")
    else:
        gh.run(["release", "upload", version, str(jar)])


def already_published(versions, version, candidate, prerelease):
    matches = [item for item in versions if item["version_number"] == version]
    if not matches:
        return False
    if len(matches) != 1:
        raise ValueError("Multiple Modrinth versions have the same version number")
    existing = matches[0]
    hashes = [file["hashes"]["sha512"] for file in existing["files"]]
    if (hashes != [hashlib.sha512(candidate).hexdigest()]
            or existing["version_type"] != ("beta" if prerelease else "release")
            or set(existing["loaders"]) != {"neoforge"}
            or set(existing["game_versions"]) != {"26.1.2", "26.2", "26.3"}):
        raise ValueError("Existing Modrinth version differs from this publication")
    return True


def candidate(directory, version):
    jar = Path(directory) / f"sethome-{version}.jar"
    if not jar.is_file():
        raise FileNotFoundError(f"Expected packaged mod JAR: {jar}")
    return jar


def main(command):
    if command == "gate":
        version = gate(os.environ["GITHUB_REF"], os.environ["BEFORE"],
                       os.environ["GITHUB_SHA"],
                       lambda sha: git("show", f"{sha}:gradle.properties"))
        output(version=version or "", publish=str(version is not None).lower())
        return
    version = os.environ.get("VERSION") or version_property(Path("gradle.properties").read_text())
    sha = commit(os.environ.get("COMMIT") or git("rev-parse", "HEAD"))
    if git("rev-parse", "HEAD") != sha:
        raise ValueError("Checkout is not the requested commit")
    if version_property(Path("gradle.properties").read_text()) != version:
        raise ValueError("Release tag/version differs from the checked-out mod_version")
    jar = candidate(os.environ.get("JAR_DIRECTORY", "build/libs"), version)
    if command == "candidate":
        output(jar=str(jar))
        return
    repository = os.environ["GITHUB_REPOSITORY"]
    gh = GitHub(repository)
    if command == "release":
        ensure_release(repository, version, sha, jar, gh)
        return
    if command != "check":
        raise ValueError("Unknown release command")
    _, existing = release_identity(repository, version, sha, gh)
    if existing is None:
        raise ValueError("GitHub release must exist before Modrinth publication")
    request = Request("https://api.modrinth.com/v2/project/HhUmEUqn/version",
                      headers={"User-Agent": f"{repository}/release-automation"})
    with urlopen(request, timeout=30) as response:
        versions = json.load(response)
    published = already_published(versions, version, jar.read_bytes(), existing["prerelease"])
    output(already_published=str(published).lower(), version=version, jar=str(jar),
           name=existing["name"] or version, changelog=existing["body"] or "",
           version_type="beta" if existing["prerelease"] else "release")


if __name__ == "__main__":
    main(sys.argv[1])
