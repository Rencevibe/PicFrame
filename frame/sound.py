"""Alarm sound via pygame.mixer. No audio device → logged once; the alarm stays visual only."""
from __future__ import annotations

import logging
from pathlib import Path

import pygame

log = logging.getLogger(__name__)

ALARM_WAV = Path(__file__).resolve().parents[1] / "assets" / "alarm.wav"
_sound: pygame.mixer.Sound | None = None
_disabled = False


def _load() -> pygame.mixer.Sound | None:
    global _sound, _disabled
    if _sound is None and not _disabled:
        try:
            if not pygame.mixer.get_init():
                pygame.mixer.init()
            _sound = pygame.mixer.Sound(str(ALARM_WAV))
        except (pygame.error, OSError) as e:
            _disabled = True
            log.warning("sound: no audio (%s); alarm will be visual only", e)
    return _sound


def play_alarm(loop: bool = True) -> bool:
    """Start the alarm sound (looping by default). Returns False if there is no audio."""
    s = _load()
    if s is None:
        return False
    s.stop()
    s.play(loops=-1 if loop else 0)
    return True


def stop() -> None:
    if _sound is not None:
        _sound.stop()


def playing() -> bool:
    return _sound is not None and _sound.get_num_channels() > 0


def _reset() -> None:
    """Forget the loaded sound and any earlier failure (tests)."""
    global _sound, _disabled
    stop()
    _sound, _disabled = None, False
