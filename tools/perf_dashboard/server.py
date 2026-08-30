import argparse
from http.server import ThreadingHTTPServer
from pathlib import Path

from .campaign import configure_controller
from .controller import CampaignController
from .http_handler import DashboardHandler
from .models import Target
from .validation import load_targets


class DashboardServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        port: int,
        targets: tuple[Target, ...],
        controller: CampaignController,
        static_root: Path,
    ) -> None:
        self.targets = targets
        self.controller = controller
        self.static_root = static_root
        super().__init__(("127.0.0.1", port), DashboardHandler)


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
    controller = CampaignController(selected_artifacts, selected_runner, cancel_grace_seconds)
    configure_controller(controller)
    return DashboardServer(port, load_targets(selected_targets), controller, Path(__file__).parent / "static")


def main() -> int:
    parser = argparse.ArgumentParser(prog="perf-dashboard")
    parser.add_argument("--port", type=int, default=8765)
    arguments = parser.parse_args()
    server = create_server(port=arguments.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 130
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
