import argparse
import sys
from http.server import ThreadingHTTPServer
from pathlib import Path

from .campaign import configure_controller
from .controller import CampaignController
from .http_handler import DashboardHandler
from .models import Target
from .root_lock import ArtifactRootLock, DashboardAlreadyRunningError
from .store import CampaignStoreError
from .validation import TargetManifestError, load_targets


class DashboardServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        port: int,
        targets: tuple[Target, ...],
        static_root: Path,
        root_lock: ArtifactRootLock,
    ) -> None:
        self.targets = targets
        self.static_root = static_root
        self.root_lock = root_lock
        self.controller: CampaignController
        self._closed = False
        super().__init__(("127.0.0.1", port), DashboardHandler, bind_and_activate=False)

    def bind_socket(self) -> None:
        self.server_bind()
        self.server_activate()

    def attach_controller(self, controller: CampaignController) -> None:
        self.controller = controller

    def abort_startup(self) -> None:
        try:
            super().server_close()
        finally:
            self.root_lock.release()

    def server_close(self) -> None:
        if self._closed:
            return
        self.controller.shutdown_active()
        try:
            super().server_close()
        finally:
            self.root_lock.release()
            self._closed = True


def create_server(
    port: int = 8765,
    targets_path: Path | None = None,
    artifact_root: Path | None = None,
    endpoint_runner: Path | None = None,
    cancel_grace_seconds: float = 10.0,
) -> DashboardServer:
    repo_root = Path(__file__).resolve().parents[2]
    selected_targets = targets_path or repo_root / "k6" / "endpoints" / "targets.json"
    selected_artifacts = artifact_root or repo_root / "artifacts" / "performance"
    selected_runner = endpoint_runner or repo_root / "scripts" / "perf" / "run-endpoint.sh"
    targets = load_targets(selected_targets)
    root_lock = ArtifactRootLock.acquire(selected_artifacts)
    try:
        server = DashboardServer(port, targets, Path(__file__).parent / "static", root_lock)
    except OSError:
        root_lock.release()
        raise
    try:
        server.bind_socket()
    except OSError:
        server.abort_startup()
        raise
    try:
        controller = CampaignController(root_lock.artifact_root, selected_runner, cancel_grace_seconds)
    except (CampaignStoreError, OSError):
        server.abort_startup()
        raise
    server.attach_controller(controller)
    configure_controller(controller)
    return server


def main() -> int:
    parser = argparse.ArgumentParser(prog="perf-dashboard")
    parser.add_argument("--port", type=int, default=8765)
    arguments = parser.parse_args()
    try:
        server = create_server(port=arguments.port)
    except DashboardAlreadyRunningError as error:
        print(str(error), file=sys.stderr)
        return 2
    except TargetManifestError as error:
        print(str(error), file=sys.stderr)
        return 2
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 130
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
