"""Screen power and brightness via external commands. No pygame imports.

"black" is the guaranteed night behavior: the app draws a black screen and nothing else runs.
"cec" and "dpms" also switch the screen off; any failure is logged once and falls back to black.
With background=True commands run on a worker thread in order, so a slow or hung command
(up to the 5 s timeout) never delays a frame or an alarm."""
from __future__ import annotations

import logging
import queue
import shlex
import subprocess
import threading

log = logging.getLogger(__name__)

TIMEOUT_S = 5
COMMANDS = {
    "cec": {False: ["cec-ctl", "--to", "0", "--standby"], True: ["cec-ctl", "--to", "0", "--image-view-on"]},
    "dpms": {False: ["vcgencmd", "display_power", "0"], True: ["vcgencmd", "display_power", "1"]},
}
FAILURES = (OSError, subprocess.SubprocessError, ValueError)


class _Worker:
    """One daemon thread running submitted jobs in order."""

    def __init__(self, name: str):
        self.q: queue.Queue = queue.Queue()
        threading.Thread(target=self._loop, name=name, daemon=True).start()

    def _loop(self):
        while True:
            job = self.q.get()
            try:
                job()
            except Exception:  # never let one bad job kill the worker
                log.exception("display: background job failed")

    def submit(self, job) -> None:
        self.q.put(job)

    def wait(self, timeout: float) -> bool:
        done = threading.Event()
        self.q.put(done.set)
        return done.wait(timeout)


class _Runner:
    def __init__(self, run, background: bool, name: str):
        self._run = run
        self._worker = _Worker(name) if background else None

    def _do(self, job) -> None:
        if self._worker:
            self._worker.submit(job)
        else:
            job()

    def _exec(self, cmd) -> None:
        self._run(cmd, check=True, timeout=TIMEOUT_S, capture_output=True)

    def wait(self, timeout: float = 10) -> bool:
        """Block until queued commands have run (tests, shutdown)."""
        return self._worker.wait(timeout) if self._worker else True


class DisplayPower(_Runner):
    def __init__(self, method: str = "black", run=subprocess.run, background: bool = False):
        super().__init__(run, background, "display")
        if method != "black" and method not in COMMANDS:
            log.warning("display: unknown screen_power %r; using black", method)
            method = "black"
        self.method, self.on = method, True

    def set(self, on: bool) -> None:
        """Called every frame; runs a command only when the state changes."""
        if on == self.on:
            return
        self.on = on

        def job():
            method = self.method  # re-read: an earlier command may have failed meanwhile
            if method == "black":
                return
            try:
                self._exec(COMMANDS[method][on])
            except FAILURES as e:
                log.warning("display: %s failed (%s); falling back to a black screen", method, e)
                self.method = "black"

        self._do(job)


class Brightness(_Runner):
    """Runs brightness_cmd with {value} = 10–100. Unsupported when unset or after a failure."""

    def __init__(self, cmd: str | None, run=subprocess.run, background: bool = False):
        super().__init__(run, background, "brightness")
        self.cmd, self.value = cmd, None

    @property
    def supported(self) -> bool:
        return self.cmd is not None

    def set(self, value: int) -> None:
        if not self.supported or value == self.value:
            return
        self.value = value

        def job():
            cmd = self.cmd
            if cmd is None:
                return
            try:
                self._exec([part.replace("{value}", str(value)) for part in shlex.split(cmd)])
            except FAILURES as e:
                log.warning("display: brightness command failed (%s); brightness control disabled", e)
                self.cmd = None

        self._do(job)
