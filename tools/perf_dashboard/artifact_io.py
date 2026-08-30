import json
import os
import stat
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterator

from .artifacts import ArtifactNotFoundError, artifact_media_type, artifact_os_error, is_allowed_artifact, is_safe_target_name
from .identifiers import parse_campaign_id
from .models import Artifact, JsonValue
from .store import campaign_from_document


@dataclass(frozen=True, slots=True)
class OpenedArtifact:
    source: BinaryIO
    name: str
    media_type: str
    size: int


def _read_campaign(campaign_fd: int, campaign_id: str) -> tuple[Artifact, ...]:
    try:
        state_fd = os.open("campaign.json", os.O_RDONLY | os.O_NOFOLLOW, dir_fd=campaign_fd)
    except OSError as error:
        raise artifact_os_error(error, "campaign.json") from error
    with os.fdopen(state_fd, "rb") as source:
        try:
            document: JsonValue = json.load(source)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ArtifactNotFoundError("campaign.json") from error
    campaign = campaign_from_document(document)
    if str(campaign.campaign_id) != campaign_id:
        raise ArtifactNotFoundError("campaign.json")
    return tuple(artifact for target in campaign.targets for artifact in target.artifacts)


def _registered_artifact(artifacts: tuple[Artifact, ...], artifact_id: str) -> Artifact:
    matching = tuple(artifact for artifact in artifacts if str(artifact.id) == artifact_id)
    if len(matching) != 1:
        raise ArtifactNotFoundError(artifact_id)
    artifact = matching[0]
    target_name = artifact_id.split(":", 1)[0]
    if not is_safe_target_name(target_name) or str(artifact.id) != f"{target_name}:{artifact.name}" or artifact.path != f"{target_name}/{artifact.name}" or not is_allowed_artifact(artifact.name):
        raise ArtifactNotFoundError(artifact_id)
    return artifact


@contextmanager
def open_artifact(artifact_root: Path, raw_campaign_id: str, artifact_id: str) -> Iterator[OpenedArtifact]:
    campaign_id = str(parse_campaign_id(raw_campaign_id))
    try:
        root_fd = os.open(artifact_root.resolve(), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    except OSError as error:
        raise artifact_os_error(error, artifact_id) from error
    try:
        try:
            campaign_fd = os.open(campaign_id, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
        except OSError as error:
            raise artifact_os_error(error, artifact_id) from error
        try:
            artifact = _registered_artifact(_read_campaign(campaign_fd, campaign_id), artifact_id)
            target_name, file_name = artifact.path.split("/", 1)
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
                    file_stat = os.fstat(file_descriptor)
                    if not stat.S_ISREG(file_stat.st_mode):
                        raise ArtifactNotFoundError(artifact_id)
                    with os.fdopen(file_descriptor, "rb", closefd=False) as source:
                        yield OpenedArtifact(source, artifact.name, artifact_media_type(artifact.name), file_stat.st_size)
                finally:
                    os.close(file_descriptor)
            finally:
                os.close(target_fd)
        finally:
            os.close(campaign_fd)
    finally:
        os.close(root_fd)
