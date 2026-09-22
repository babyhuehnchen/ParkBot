"""Validate the app version and release notes before signing or publishing."""
import os
from pathlib import Path
import re


def release_metadata(root, ref_type, ref_name):
    text = (root / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
    versions = re.findall(r'^\s*versionName\s*=\s*"(\d+\.\d+\.\d+)"\s*$', text, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)
    if len(versions) != 1 or len(codes) != 1 or int(codes[0]) < 1:
        raise ValueError("Expected one versionName (X.Y.Z) and positive versionCode.")
    version = versions[0]
    tag = f"v{version}"
    if ref_type == "tag" and ref_name != tag:
        raise ValueError(f"Tag {ref_name!r} does not match app version {tag}.")
    if not (root / "releases" / f"{tag}.md").is_file():
        raise ValueError(f"Add release notes at releases/{tag}.md before publishing.")
    return {"version": version, "tag": tag, "version_code": codes[0]}


if __name__ == "__main__":
    try:
        result = release_metadata(Path("."), os.getenv("GITHUB_REF_TYPE", ""), os.getenv("GITHUB_REF_NAME", ""))
    except ValueError as error:
        raise SystemExit(str(error)) from error
    for name, value in result.items():
        print(f"{name}={value}")