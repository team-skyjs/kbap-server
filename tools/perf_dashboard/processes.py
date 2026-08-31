import os
import signal
import subprocess
import threading


class ProcessRegistry:
    def __init__(self, cancel_grace_seconds: float, terminate_grace_seconds: float) -> None:
        self.cancel_grace_seconds = cancel_grace_seconds
        self.terminate_grace_seconds = terminate_grace_seconds
        self._lock = threading.RLock()
        self._processes: dict[str, subprocess.Popen[str]] = {}
        self._done: dict[str, threading.Event] = {}
        self._cancel_started: set[str] = set()

    @property
    def shutdown_budget(self) -> float:
        return self.cancel_grace_seconds + self.terminate_grace_seconds

    def register(self, campaign_id: str, process: subprocess.Popen[str], done: threading.Event) -> None:
        with self._lock:
            self._processes[campaign_id] = process
            self._done[campaign_id] = done

    def complete(self, campaign_id: str) -> None:
        with self._lock:
            self._processes.pop(campaign_id, None)
            self._done.pop(campaign_id, None)

    def cancel(self, campaign_id: str) -> None:
        with self._lock:
            if campaign_id in self._cancel_started:
                return
            self._cancel_started.add(campaign_id)
            process = self._processes.get(campaign_id)
            done = self._done.get(campaign_id)
        if process is None or done is None:
            return
        self._signal(process, signal.SIGINT)
        threading.Thread(target=self._escalate, args=(process, done), daemon=True).start()

    def kill(self, campaign_id: str) -> None:
        with self._lock:
            process = self._processes.get(campaign_id)
        if process is not None:
            self._signal(process, signal.SIGKILL)

    def _escalate(self, process: subprocess.Popen[str], done: threading.Event) -> None:
        if not done.wait(self.cancel_grace_seconds):
            self._signal(process, signal.SIGTERM)
            done.wait(self.terminate_grace_seconds)

    def _signal(self, process: subprocess.Popen[str], selected_signal: signal.Signals) -> None:
        try:
            os.killpg(process.pid, selected_signal)
        except ProcessLookupError:
            return
