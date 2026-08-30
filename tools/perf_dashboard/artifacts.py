import errno
import json
import os
import re
import shutil
import stat
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Final, Iterator

from .models import Artifact, ArtifactId, JsonValue


ALLOWED_NAMES: Final = frozenset(("report.html", "summary.json", "manifest.json"))
ZIP_TIMESTAMP: Final = (1980, 1, 1, 0, 0, 0)
CHUNK_SIZE: Final = 64 * 1024
JFR_NAME_PATTERN: Final = re.compile(r"^task-[A-Za-z0-9][A-Za-z0-9._-]*\.jfr$")
TARGET_NAME_PATTERN: Final = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


@dataclass(frozen=True, slots=True)
class ArtifactNotFoundError(Exception):
    artifact_id: str

    def __str__(self) -> str:
        return f"artifact-not-found:{self.artifact_id}"


@dataclass(frozen=True, slots=True)
class ArtifactStorageError(Exception):
    artifact_id: str

    def __str__(self) -> str:
        return f"artifact-storage-error:{self.artifact_id}"


@dataclass(frozen=True, slots=True)
class OpenedBundle:
    source: BinaryIO
    size: int


def artifact_os_error(error: OSError, artifact_id: str) -> ArtifactNotFoundError | ArtifactStorageError:
    if error.errno in (errno.ENOENT, errno.ENOTDIR, errno.ELOOP):
        return ArtifactNotFoundError(artifact_id)
    return ArtifactStorageError(artifact_id)


def read_regular_bytes(directory_fd: int, name: str, artifact_id: str) -> bytes:
    try:
        file_descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
    except OSError as error:
        raise artifact_os_error(error, artifact_id) from error
    try:
        try:
            if not stat.S_ISREG(os.fstat(file_descriptor).st_mode):
                raise ArtifactNotFoundError(artifact_id)
            source = os.fdopen(file_descriptor, "rb", closefd=False)
            with source:
                return source.read()
        except OSError as error:
            raise artifact_os_error(error, artifact_id) from error
    finally:
        os.close(file_descriptor)


def is_allowed_artifact(name: str) -> bool:
    return name in ALLOWED_NAMES or JFR_NAME_PATTERN.fullmatch(name) is not None


def is_safe_target_name(name: str) -> bool:
    return TARGET_NAME_PATTERN.fullmatch(name) is not None


def artifact_media_type(name: str) -> str:
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
        regular = stat.S_ISREG(path.stat(follow_symlinks=False).st_mode)
        if is_allowed_artifact(path.name) and regular and resolved.is_relative_to(root):
            artifact_id = ArtifactId(f"{target_key}:{path.name}")
            artifacts.append(Artifact(artifact_id, path.name, str(resolved.relative_to(root)), artifact_media_type(path.name)))
    return tuple(artifacts)


def _mapping(value: JsonValue) -> dict[str, JsonValue]:
    return value if isinstance(value, dict) else {}


def _registered_paths(document: JsonValue) -> tuple[tuple[str, str], ...]:
    root = _mapping(document)
    raw_targets = root.get("targets")
    if not isinstance(raw_targets, list):
        raise ArtifactNotFoundError("campaign.json")
    registrations: list[tuple[str, str]] = []
    identifiers: set[str] = set()
    for raw_target in raw_targets:
        target = _mapping(raw_target)
        key = target.get("key")
        raw_artifacts = target.get("artifacts", [])
        if not isinstance(raw_artifacts, list):
            raise ArtifactNotFoundError("campaign.json")
        if not isinstance(key, str):
            if raw_artifacts:
                raise ArtifactNotFoundError("campaign.json")
            continue
        if not is_safe_target_name(key):
            raise ArtifactNotFoundError("campaign.json")
        for raw_artifact in raw_artifacts:
            artifact = _mapping(raw_artifact)
            artifact_id = artifact.get("id")
            name = artifact.get("name")
            path = artifact.get("path")
            if not isinstance(artifact_id, str) or not isinstance(name, str) or not isinstance(path, str):
                raise ArtifactNotFoundError("campaign.json")
            if not is_allowed_artifact(name) or artifact_id != f"{key}:{name}" or path != f"{key}/{name}":
                raise ArtifactNotFoundError(artifact_id)
            if artifact_id in identifiers:
                raise ArtifactNotFoundError(artifact_id)
            identifiers.add(artifact_id)
            registrations.append((artifact_id, path))
    return tuple(sorted(registrations, key=lambda item: item[1]))


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100600 << 16
    return info


def _read_campaign(campaign_fd: int, campaign_id: str) -> tuple[bytes, JsonValue]:
    data = read_regular_bytes(campaign_fd, "campaign.json", "campaign.json")
    try:
        document: JsonValue = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ArtifactNotFoundError("campaign.json") from error
    if _mapping(document).get("campaignId") != campaign_id:
        raise ArtifactNotFoundError("campaign.json")
    return data, document


def _copy_artifact(archive: zipfile.ZipFile, campaign_fd: int, artifact_id: str, path: str) -> None:
    target_name, file_name = path.split("/", 1)
    try:
        target_fd = os.open(target_name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=campaign_fd)
    except OSError as error:
        raise artifact_os_error(error, artifact_id) from error
    try:
        try:
            file_descriptor = os.open(file_name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=target_fd)
        except OSError as error:
            raise artifact_os_error(error, artifact_id) from error
        try:
            if not stat.S_ISREG(os.fstat(file_descriptor).st_mode):
                raise ArtifactNotFoundError(artifact_id)
            with os.fdopen(file_descriptor, "rb", closefd=False) as source, archive.open(_zip_info(path), "w") as target:
                shutil.copyfileobj(source, target, length=CHUNK_SIZE)
        finally:
            os.close(file_descriptor)
    finally:
        os.close(target_fd)


def _unlink_temporary(campaign_fd: int) -> None:
    try:
        os.unlink(".bundle.tmp", dir_fd=campaign_fd)
    except FileNotFoundError:
        return
    except OSError as error:
        raise ArtifactStorageError("bundle.zip") from error


@contextmanager
def open_bundle(campaign_dir: Path, campaign_id: str) -> Iterator[OpenedBundle]:
    try:
        campaign_fd = os.open(campaign_dir, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    except OSError as error:
        raise artifact_os_error(error, "bundle.zip") from error
    try:
        campaign_data, document = _read_campaign(campaign_fd, campaign_id)
        registrations = _registered_paths(document)
        _unlink_temporary(campaign_fd)
        flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
        try:
            temporary_fd = os.open(".bundle.tmp", flags, 0o600, dir_fd=campaign_fd)
        except OSError as error:
            raise artifact_os_error(error, "bundle.zip") from error
        renamed = False
        try:
            try:
                with os.fdopen(temporary_fd, "w+b", closefd=False) as output, zipfile.ZipFile(output, "w") as archive:
                    archive.writestr(_zip_info("campaign.json"), campaign_data)
                    for artifact_id, path in registrations:
                        _copy_artifact(archive, campaign_fd, artifact_id, path)
                os.replace(".bundle.tmp", "bundle.zip", src_dir_fd=campaign_fd, dst_dir_fd=campaign_fd)
                renamed = True
                os.lseek(temporary_fd, 0, os.SEEK_SET)
                file_stat = os.fstat(temporary_fd)
                if not stat.S_ISREG(file_stat.st_mode):
                    raise ArtifactStorageError("bundle.zip")
            except OSError as error:
                raise artifact_os_error(error, "bundle.zip") from error
            try:
                source = os.fdopen(temporary_fd, "rb", closefd=False)
            except OSError as error:
                raise artifact_os_error(error, "bundle.zip") from error
            with source:
                yield OpenedBundle(source, file_stat.st_size)
        finally:
            os.close(temporary_fd)
            if not renamed:
                _unlink_temporary(campaign_fd)
    finally:
        os.close(campaign_fd)


def build_bundle(campaign_dir: Path) -> Path:
    with open_bundle(campaign_dir, campaign_dir.name):
        return campaign_dir / "bundle.zip"
