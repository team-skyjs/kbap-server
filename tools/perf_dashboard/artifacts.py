import os
import shutil
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Final

from .models import Artifact, ArtifactId


ALLOWED_NAMES: Final = frozenset(("report.html", "summary.json", "manifest.json"))
ZIP_TIMESTAMP: Final = (1980, 1, 1, 0, 0, 0)
CHUNK_SIZE: Final = 64 * 1024


@dataclass(frozen=True, slots=True)
class ArtifactNotFoundError(Exception):
    artifact_id: str

    def __str__(self) -> str:
        return f"artifact-not-found:{self.artifact_id}"


def _is_allowed(name: str) -> bool:
    return name in ALLOWED_NAMES or (name.startswith("task-") and name.endswith(".jfr"))


def _media_type(name: str) -> str:
    if name == "report.html":
        return "text/html; charset=utf-8"
    if name.endswith(".json"):
        return "application/json"
    return "application/octet-stream"


def discover_artifacts(campaign_dir: Path, target_key: str) -> tuple[Artifact, ...]:
    target_dir = (campaign_dir / target_key).resolve()
    root = campaign_dir.resolve()
    if not target_dir.is_relative_to(root) or not target_dir.is_dir():
        return ()
    artifacts: list[Artifact] = []
    for path in sorted(target_dir.iterdir(), key=lambda item: item.name):
        resolved = path.resolve()
        if _is_allowed(path.name) and resolved.is_relative_to(root) and resolved.is_file():
            artifact_id = ArtifactId(f"{target_key}:{path.name}")
            artifacts.append(Artifact(artifact_id, path.name, str(resolved.relative_to(root)), _media_type(path.name)))
    return tuple(artifacts)


def _bundle_paths(campaign_dir: Path) -> tuple[Path, ...]:
    root = campaign_dir.resolve()
    paths: list[Path] = [campaign_dir / "campaign.json"]
    for path in campaign_dir.rglob("*"):
        resolved = path.resolve()
        if path.parent != campaign_dir and _is_allowed(path.name) and resolved.is_relative_to(root) and resolved.is_file():
            paths.append(path)
    return tuple(sorted(paths, key=lambda item: str(item.relative_to(campaign_dir))))


def build_bundle(campaign_dir: Path) -> Path:
    output = campaign_dir / "bundle.zip"
    temporary = campaign_dir / ".bundle.tmp"
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(temporary, "w") as archive:
            for path in _bundle_paths(campaign_dir):
                name = str(path.relative_to(campaign_dir))
                info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100600 << 16
                with path.open("rb") as source, archive.open(info, "w") as target:
                    shutil.copyfileobj(source, target, length=CHUNK_SIZE)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return output
