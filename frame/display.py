"""Screen power and brightness via external commands. No pygame imports.

"black" is the guaranteed night behavior: the app draws a black screen and nothing else runs.
"cec" and "dpms" also switch the screen off; any failure is logged once and falls back to black."""
from __future__ import annotations

import logging
import shlex
import subprocess

log = logging.getLogger(__name__)

TIMEOUT_S = 5
COMMANDS = {
    "cec": {False: ["cec-ctl", "--to", "0", "--standby"], True: ["cec-ctl", "--to", "0", "--image-view-on"]},
    "dpms": {False: ["vcgencmd", "display_power", "0"], True: ["vcgencmd", "display_power", "1"]},
}
FAILURES = (OSError, subprocess.SubprocessError)


def _run(cmd, run) -> None:
    run(cmd, check=True, timeout=TIMEOUT_S, capture_output=True)


class DisplayPower:
    def __init__(self, method: str = "black", run=subprocess.run):
        if method != "black" and method not in COMMANDS:
            log.warning("display: unknown screen_power %r; using black", method)
            method = "black"
        self.method, self._run, self.on = method, run, True

    def set(self, on: bool) -> None:
        """Called every frame; runs a command only when the state changes."""
        if on == self.on:
            return
        self.on = on
        if self.method == "black":
            return
        try:
            _run(COMMANDS[self.method][on], self._run)
        except FAILURES as e:
            log.warning("display: %s failed (%s); falling back to a black screen", self.method, e)
            self.method = "black"


class Brightness:
    """Runs brightness_cmd with {value} = 10–100. Unsupported when unset or after a failure."""

    def __init__(self, cmd: str | None, run=subprocess.run):
        self.cmd, self._run, self.value = cmd, run, None

    @property
    def supported(self) -> bool:
        return self.cmd is not None

    def set(self, value: int) -> None:
        if not self.supported or value == self.value:
            return
        self.value = value
        try:
            _run([part.replace("{value}", str(value)) for part in shlex.split(self.cmd)], self._run)
        except (FAILURES + (ValueError,)) as e:
            log.warning("display: brightness command failed (%s); brightness control disabled", e)
            self.cmd = None
