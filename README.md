# PicFrame

A Raspberry Pi 3 touchscreen photo frame: slideshow, clock and calendar, today's to-dos, weather and an alarm.
It runs unattended for weeks with no desktop, drawing straight to the screen through SDL KMS/DRM.

- What it does and why: `docs/superpowers/specs/2026-10-14-pi-picture-frame-design.md`
- How it was built: `docs/superpowers/plans/2026-10-14-pi-picture-frame.md`
- Screen designs: `docs/design/`

## Install on the Pi

You need Raspberry Pi OS Lite (Bookworm), the 10.1" 1280×800 HDMI touchscreen, a DS3231 RTC on the I2C pins and a speaker.
Before you start, confirm the exact Pi 3 model and touchscreen model, the photo folder path, your city's lat/lon and whether you want a 12 or 24 h clock.

```sh
git clone https://github.com/rencevibe/PicFrame.git ~/PicFrame
cd ~/PicFrame
sudo ./deploy/setup.sh America/Los_Angeles   # your timezone; safe to re-run
nano ~/frame/config.toml                      # set lat/lon, clock_24h, alarm …
sudo reboot
```

`setup.sh` does the following:
- installs the runtime packages from apt (`python3-pygame python3-pil python3-tomli-w`), plus `cec-utils` and `i2c-tools`
- turns on the DS3231 RTC and removes `fake-hwclock`
- turns on network time and sets the timezone
- creates `~/frame/{photos,cache}`, and copies `config.example.toml` to `~/frame/config.toml` unless one exists
- installs and enables `frame.service`

To preview it without changing anything, run `DRY_RUN=1 ./deploy/setup.sh <timezone>`.

| Task | Command |
|---|---|
| Live log | `journalctl -u frame -f` |
| Restart after editing config.toml by hand | `sudo systemctl restart frame` |
| Add photos (JPG/PNG; new ones show up within 5 min) | `rsync -av --progress ~/Pictures/frame/ pi@frame.local:frame/photos/` |

**Today list:** edit `~/frame/todo.toml` over SSH. The frame re-reads it every minute.

```toml
[[item]]
title = "Dentist"
kind = "appointment"   # or "todo"
time = "13:30"         # optional, 24 h HH:MM
date = "2026-10-14"    # optional; no date = every day
```

## Run on a PC

Set `photo_dir` in a config file to a folder of photos, then:

```sh
pip install pygame pillow tomli-w pytest
python3 -m frame.app --windowed --config /path/to/config.toml   # Esc quits
SDL_VIDEODRIVER=dummy python3 -m pytest -q
```

## On-Pi checks (plan Task 11)

These need the real hardware. Tick them off on the Pi; the results are not in this repo yet.

**After setup and a reboot**
- [ ] `i2cdetect -y 1` shows the RTC at `68` (shown as `UU` once the driver has it)
- [ ] `sudo hwclock -r` matches the real time; power off, unplug the network, boot again, and the time is still right
- [ ] `systemctl status frame` shows active (running)
- [ ] The frame is visible within 45 s of power-on. Time it with a stopwatch from plugging in, and note `systemd-analyze` and `systemd-analyze blame | head`
- [ ] `sudo systemctl kill -s KILL frame`: the frame is back within 10 s

**Touch, sound and screen power**
- [ ] Every screen responds to touch: dashboard, photo (tap → bar → Prev/Next/Dashboard), settings (every control), alarm, the night clock
- [ ] Settings → Test alarm plays the chime and shows TEST ALARM; it stops after 10 s
- [ ] For each `screen_power` value in `config.toml`, restart the service and let night mode start:
  - `black` (default) shows a black screen and always works
  - `cec` (`cec-ctl --to 0 --standby`) turns the screen off and a tap turns it back on
  - `dpms` (`vcgencmd display_power 0`) may not work under the full-KMS driver

  Keep the one that works on this screen. If a method fails, the log says "falling back to a black screen".
- [ ] Brightness, if the screen supports it: set `brightness_cmd` (e.g. `ddcutil setvcp 10 {value}`), check that the Settings stepper changes the screen, else leave it unset

**Visual sign-off** (the design counts as final only after this)
- [ ] Photograph every screen and compare with `docs/design/`: the UI text is Atkinson Hyperlegible (slashed zero), no label is clipped, and the colors match
- [ ] Read the clock from 2.5 m and the Today list from 0.6 m
- [ ] Hit every button with a fingertip, including the day chips. Try Snooze and Dismiss half-asleep (eyes half closed, no aiming)
- [ ] In a dark room the night clock is dim enough, and the alarm screen wakes you

## 48 h soak test (plan Task 12)

**Start**
1. Log memory every 10 min, including across reboots:
   - `crontab -e`, then add `@reboot /home/pi/PicFrame/deploy/memlog.sh`
   - start it now with `nohup ~/PicFrame/deploy/memlog.sh >/dev/null 2>&1 &`
2. In Settings set the alarm 5 min ahead, and set a night window starting 1 h ahead.

**During the 48 h** (note the time of each)
- [ ] Unplug the network for 4 h: the weather goes stale ("as of …"), then shows "Weather unavailable"; the frame keeps running
- [ ] Add 20 photos with `rsync`: they appear within 5 min, with no restart
- [ ] Alarms: let one ring unanswered (it stops after 10 min, and "Missed alarm" stays until tapped), snooze one, and dismiss one during night mode (the screen returns to black)
- [ ] Night on/off transitions pass on schedule; a tap shows the dim night clock
- [ ] `sudo reboot` once: the time is right, and the frame is back within 45 s
- [ ] `sudo kill -9 $(systemctl show -p MainPID --value frame)` once: the frame is back within 10 s

**Pass**
- [ ] No crash besides the forced kill: check `journalctl -u frame --since "2 days ago" | grep -iE "traceback|error"`
- [ ] `~/PicFrame/deploy/memlog.sh --report`: RSS growth < 10 % after each run's first hour (it exits 0 on pass)
- [ ] Every Phase 1 acceptance item in the spec is checked

**Results** (fill in after the run, then commit)

| Item | Result |
|---|---|
| Dates of the run | |
| Pi / screen model | |
| `screen_power` method chosen | |
| Boot to display (s) | |
| Restart after `kill -9` (s) | |
| RSS after 1 h / peak / growth | |
| Crashes (besides the forced kill) | |
| Alarm: unanswered / snoozed / dismissed at night | |
| Network-off behavior | |
| Notes | |
