import sys
from pathlib import Path

from tools.perf_dashboard.server import create_server
from tools.perf_dashboard.root_lock import DashboardAlreadyRunningError


def main() -> int:
    artifact_root, targets_path, endpoint_runner, raw_port = sys.argv[1:]
    try:
        server = create_server(
            port=int(raw_port),
            targets_path=Path(targets_path),
            artifact_root=Path(artifact_root),
            endpoint_runner=Path(endpoint_runner),
            cancel_grace_seconds=0.2,
        )
    except OSError:
        return 4
    except DashboardAlreadyRunningError:
        return 3
    print(f"READY {server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 130
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
