import fcntl
import os
import stat
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Final


LOCK_NAME: Final = ".dashboard.lock"


@dataclass(frozen=True, slots=True)
class DashboardAlreadyRunningError(Exception):
    artifact_root: Path

    def __str__(self) -> str:
        return f"dashboard-already-running:{self.artifact_root}"


@dataclass(frozen=True, slots=True)
class ArtifactRootLockError(Exception):
    path: Path

    def __str__(self) -> str:
        return f"artifact-root-lock-error:{self.path}"


class ArtifactRootLock:
    def __init__(self, artifact_root: Path, file_descriptor: int) -> None:
        self.artifact_root = artifact_root
        self._file_descriptor: int | None = file_descriptor
        self._release_lock = threading.Lock()

    @classmethod
    def acquire(cls, artifact_root: Path) -> "ArtifactRootLock":
        artifact_root.mkdir(parents=True, exist_ok=True)
        resolved_root = artifact_root.resolve()
        lock_path = resolved_root / LOCK_NAME
        flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW
        file_descriptor = os.open(lock_path, flags, 0o600)
        try:
            if not stat.S_ISREG(os.fstat(file_descriptor).st_mode):
                raise ArtifactRootLockError(lock_path)
            fcntl.flock(file_descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            os.close(file_descriptor)
            raise DashboardAlreadyRunningError(resolved_root) from error
        except (OSError, ArtifactRootLockError):
            os.close(file_descriptor)
            raise
        return cls(resolved_root, file_descriptor)

    def release(self) -> None:
        with self._release_lock:
            file_descriptor = self._file_descriptor
            if file_descriptor is None:
                return
            self._file_descriptor = None
            try:
                fcntl.flock(file_descriptor, fcntl.LOCK_UN)
            finally:
                os.close(file_descriptor)
