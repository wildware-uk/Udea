#!/usr/bin/env python3
"""Launches every way a developer or player starts moba and hollow, and checks each one came up.

"Came up" means what a person would mean by it, and each launch is held to all of it:

  - the process starts through the same Gradle task a person types, on this operating system's
    wrapper (`gradlew.bat` on Windows, `sh gradlew` elsewhere);
  - a launch that draws writes a real frame: the engine's launch probe (`UDEA_LAUNCH_PNG`, see
    `udea-render`'s `LaunchProbe`) captures one to PNG, and the PNG is decoded here and must hold
    more than a flat colour;
  - a launch with the agent surface answers `/health` with `ok: true`, and closes when asked to
    through `/command?cmd=close` - the tool an agent calls;
  - a client connects to its server, read off the client's own log line;
  - and every process exits with status 0 inside its deadline, by itself - the probe closing the
    window after its frames, a bounded tick count, or the close command - and is never killed to
    make the check pass. A kill is a failure.

It also requires the launch log to name the OpenGL that answered (`[udea-render] GL_RENDERER=...`),
and with `--expect-renderer` that the name contains the given text: on CI that is `llvmpipe`, the
positive control that Mesa's software driver is the one drawing and not whatever the runner had.

Usage:
    python scripts/launch-check.py --out build/reports/udea/launch [--only moba-play ...]
                                   [--expect-renderer llvmpipe] [--list]

Writes `<out>/<launch>.log`, `<out>/<launch>.png` for every launch that draws, and `<out>/summary.txt`.
Exits 1 if any launch failed, and says which and why.

The new-game template is launched only when `--template <dir>` names a copy of it that is already
set up: it builds against a *published* engine, so it needs a `publishToMavenLocal` first, and the
`windows-launch (template)` job does that before it calls this.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

REPO = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"

# Deadlines. Generous, because the Windows runner draws on a software rasteriser with two cores and
# the first launch pays for Gradle's configuration too; a hang still ends in a failure, not a stall.
START_DEADLINE = 900.0
EXIT_DEADLINE = 300.0


def wrapper(root: Path) -> list[str]:
    """The command a person types to run Gradle in [root], on this operating system."""
    if WINDOWS:
        return [str(root / "gradlew.bat")]
    return ["sh", str(root / "gradlew")]


# --- PNG ---------------------------------------------------------------------------------------


def png_colours(path: Path, cap: int = 64) -> int:
    """How many distinct colours the PNG holds, counting no further than [cap].

    A decoder rather than a size check: a black frame compresses to a few hundred bytes but so
    does a small real one, and the failure this exists to catch - a context that came up and drew
    nothing - is exactly one colour. 8-bit RGB or RGBA, non-interlaced: what the engine writes.
    """
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    pos, idat, header = 8, b"", None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    if header is None:
        raise ValueError(f"{path} has no IHDR")
    width, height, depth, colour, _, _, interlace = header
    if depth != 8 or colour not in (2, 6) or interlace != 0:
        raise ValueError(f"{path}: unsupported PNG (depth {depth}, colour type {colour}, interlace {interlace})")
    channels = 4 if colour == 6 else 3
    stride = width * channels
    raw = zlib.decompress(idat)
    previous = bytearray(stride)
    seen: set[bytes] = set()
    for row in range(height):
        start = row * (stride + 1)
        kind = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = previous[i]
            corner = previous[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif kind == 4:
                p = left + up - corner
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
                pred = left if pa <= pb and pa <= pc else (up if pb <= pc else corner)
                line[i] = (line[i] + pred) & 0xFF
        for x in range(0, stride, channels * 7):
            seen.add(bytes(line[x:x + channels]))
            if len(seen) >= cap:
                return len(seen)
        previous = line
    return len(seen)


# --- processes ---------------------------------------------------------------------------------


@dataclass
class Running:
    name: str
    process: subprocess.Popen
    log: Path

    def text(self) -> str:
        return self.log.read_text(encoding="utf-8", errors="replace") if self.log.exists() else ""

    def wait_for(self, needle: str, deadline: float) -> bool:
        """Waits for [needle] in the log; False if the process ended or the deadline passed first."""
        end = time.monotonic() + deadline
        while time.monotonic() < end:
            if needle in self.text():
                return True
            if self.process.poll() is not None:
                return needle in self.text()
            time.sleep(0.5)
        return False

    def wait_exit(self, deadline: float) -> int | None:
        try:
            return self.process.wait(timeout=deadline)
        except subprocess.TimeoutExpired:
            return None

    def kill(self) -> None:
        """The failure path only: a launch that had to be killed has already failed."""
        if self.process.poll() is not None:
            return
        if WINDOWS:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(self.process.pid)], capture_output=True)
        else:
            os.killpg(self.process.pid, signal.SIGKILL)
        self.process.wait(timeout=30)


def launch(name: str, root: Path, args: list[str], out: Path, env: dict[str, str]) -> Running:
    log = out / f"{name}.log"
    command = wrapper(root) + args + ["--console=plain"]
    handle = open(log, "w", encoding="utf-8")
    handle.write(f"$ {' '.join(command)}\n")
    for key in sorted(env):
        handle.write(f"$ env {key}={env[key]}\n")
    handle.flush()
    kwargs: dict = {}
    if WINDOWS:
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(
        command,
        cwd=root,
        stdout=handle,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        env={**os.environ, **env},
        **kwargs,
    )
    return Running(name, process, log)


def health(port: int) -> dict | None:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as reply:
            return json.loads(reply.read().decode("utf-8"))
    except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError, OSError):
        return None


def wait_health(port: int, running: Running, deadline: float) -> dict | None:
    end = time.monotonic() + deadline
    while time.monotonic() < end:
        answer = health(port)
        if answer is not None and answer.get("ok") is True:
            return answer
        if running.process.poll() is not None:
            return None
        time.sleep(1.0)
    return None


def command(port: int, name: str) -> dict:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/command?cmd={name}", timeout=10) as reply:
        return json.loads(reply.read().decode("utf-8"))


def free_port(kind: int = socket.SOCK_DGRAM) -> int:
    """A port nothing on this machine holds now: UDP for a game server, TCP for an agent surface."""
    with socket.socket(socket.AF_INET, kind) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


# --- the checks --------------------------------------------------------------------------------


@dataclass
class Result:
    name: str
    command: str
    problems: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


class Check:
    def __init__(self, out: Path, renderer: str | None, frames: int, start_deadline: float = START_DEADLINE):
        self.out = out
        self.renderer = renderer
        self.frames = frames
        self.start_deadline = start_deadline

    def probe_env(self, name: str, stop: bool) -> dict[str, str]:
        env = {"UDEA_LAUNCH_PNG": str(self.out / f"{name}.png"), "UDEA_LAUNCH_FRAMES": str(self.frames)}
        if stop:
            env["UDEA_LAUNCH_STOP_FILE"] = str(self.out / f"{name}.stop")
        for leftover in (self.out / f"{name}.png", self.out / f"{name}.stop"):
            if leftover.exists():
                leftover.unlink()
        return env

    def drew(self, result: Result, running: Running) -> None:
        """The frame and the OpenGL line: what makes a launch that draws a launch that drew."""
        text = running.text()
        gl = [line for line in text.splitlines() if "[udea-render] GL_RENDERER=" in line]
        if not gl:
            result.problems.append(f"{running.name}: no '[udea-render] GL_RENDERER=' line: no context came up")
        else:
            result.notes.append(running.name + ": " + gl[0].split("[udea-render] ", 1)[1])
            if self.renderer and self.renderer not in gl[0]:
                result.problems.append(f"{running.name}: the context is not {self.renderer}: {gl[0].strip()}")
        png = self.out / f"{running.name}.png"
        if not png.exists():
            result.problems.append(f"{running.name}: no frame was written to {png.name}")
            return
        colours = png_colours(png)
        if colours < 8:
            result.problems.append(f"{running.name}: {png.name} holds {colours} colour(s): a blank frame, not a drawn one")
        else:
            result.notes.append(f"{running.name}: {png.name}: {colours}+ colours")

    def exited(self, result: Result, running: Running, deadline: float = EXIT_DEADLINE) -> None:
        code = running.wait_exit(deadline)
        if code is None:
            result.problems.append(f"{running.name}: did not exit within {deadline:.0f}s; killed")
            running.kill()
        elif code != 0:
            result.problems.append(f"{running.name}: exited with status {code}")
        else:
            result.notes.append(f"{running.name}: exited 0")

    def expect(self, result: Result, running: Running, needle: str, deadline: float | None = None) -> bool:
        if running.wait_for(needle, self.start_deadline if deadline is None else deadline):
            line = next(line for line in running.text().splitlines() if needle in line)
            result.notes.append(f"{running.name}: {line.strip()[:160]}")
            return True
        result.problems.append(f"{running.name}: never printed '{needle}'")
        return False

    # --- one method per shape of launch ---

    def window(self, name: str, root: Path, args: list[str], expect: list[str]) -> Result:
        """A launcher that opens a window and plays: the probe captures, then closes it."""
        running = launch(name, root, args, self.out, self.probe_env(name, stop=False))
        result = Result(name, " ".join(args))
        for needle in expect:
            self.expect(result, running, needle)
        self.exited(result, running, self.start_deadline)
        self.drew(result, running)
        return result

    def agent(self, name: str, root: Path, args: list[str], port: int, draws: bool, mode: str) -> Result:
        """A launcher with the agent surface: `/health`, a frame, then `close` over HTTP."""
        env = self.probe_env(name, stop=True) if draws else {}
        running = launch(name, root, args + [f"-PdebugPort={port}"], self.out, env)
        result = Result(name, " ".join(args + [f"-PdebugPort={port}"]))
        answer = wait_health(port, running, self.start_deadline)
        if answer is None:
            result.problems.append(f"/health on {port} never answered ok")
            running.kill()
            return result
        result.notes.append(f"/health: renderMode={answer.get('renderMode')} frame={answer.get('frame')}")
        if answer.get("renderMode") != mode:
            result.problems.append(f"/health says renderMode {answer.get('renderMode')}, expected {mode}")
        if draws:
            png = self.out / f"{name}.png"
            end = time.monotonic() + self.start_deadline
            while not png.exists() and running.process.poll() is None and time.monotonic() < end:
                time.sleep(0.5)
        closed = command(port, "close")
        if closed.get("accepted") is not True:
            result.problems.append(f"close was refused: {closed}")
        else:
            result.notes.append("close accepted")
        self.exited(result, running)
        if draws:
            self.drew(result, running)
        return result

    def headless(self, name: str, root: Path, args: list[str], expect: list[str]) -> Result:
        """A launcher with no window: it runs a bounded number of ticks and exits."""
        running = launch(name, root, args, self.out, {})
        result = Result(name, " ".join(args))
        for needle in expect:
            self.expect(result, running, needle)
        self.exited(result, running, self.start_deadline)
        return result

    def pair(
        self,
        name: str,
        root: Path,
        server_args: list[str],
        server_ready: str,
        server_draws: bool,
        client_args: list[str],
        connected: str,
        server_done: str | None,
    ) -> Result:
        """A server and a client that joins it over a real UDP socket, each its own process.

        A server that draws holds its window open on the probe's stop file until the client has
        connected, drawn and closed, so the order is the check's rather than a race of frame counts.
        """
        result = Result(name, f"{' '.join(server_args)} + {' '.join(client_args)}")
        server_env = self.probe_env(f"{name}-server", stop=True) if server_draws else {}
        server = launch(f"{name}-server", root, server_args, self.out, server_env)
        if not self.expect(result, server, server_ready):
            server.kill()
            return result
        client = launch(f"{name}-client", root, client_args, self.out, self.probe_env(f"{name}-client", stop=False))
        self.expect(result, client, connected)
        self.exited(result, client, self.start_deadline)
        self.drew(result, client)
        if server_draws:
            (self.out / f"{name}-server.stop").write_text("the client is done\n")
        if server_done is not None:
            self.expect(result, server, server_done, EXIT_DEADLINE)
        self.exited(result, server)
        if server_draws:
            self.drew(result, server)
        return result


def launches(check: Check, template: Path | None) -> list[tuple[str, Callable[[], Result]]]:
    """Every launch, in the order they run. Names are what `--only` takes and what files are called."""
    repo = REPO
    moba_port, hollow_port = free_port(), free_port()
    run_port, editor_port = free_port(socket.SOCK_STREAM), free_port(socket.SOCK_STREAM)
    template_port = free_port(socket.SOCK_STREAM)
    entries: list[tuple[str, Callable[[], Result]]] = [
        ("moba-play", lambda: check.window(
            "moba-play", repo, ["playMoba"], ["[moba.client]"])),
        ("moba-run", lambda: check.agent(
            "moba-run", repo, [":moba:desktop:run"], port=run_port, draws=True, mode="Offscreen")),
        ("moba-editor", lambda: check.agent(
            "moba-editor", repo, [":moba:desktop:runEditor"], port=editor_port, draws=True, mode="Windowed")),
        ("moba-client-local", lambda: check.window(
            "moba-client-local", repo, [":moba:desktop:runClient", "--args=local"], ["[moba.client]"])),
        ("moba-client-listen", lambda: check.window(
            "moba-client-listen", repo, [":moba:desktop:runClient", "--args=listen"], ["server up:"])),
        ("moba-host-join", lambda: check.pair(
            "moba-host-join", repo,
            [":moba:desktop:runClient", f"--args=host {moba_port}"], "serving on", True,
            [":moba:desktop:runClient", f"--args=join 127.0.0.1:{moba_port}"], "connected as", None)),
        ("moba-server", lambda: check.headless(
            "moba-server", repo, [":moba:desktop:runServer", "-Pudea.net.ticks=600"],
            ["authoritative", "stopped after 600 tick(s)"])),
        ("hollow-play", lambda: check.window(
            "hollow-play", repo, ["playHollow"], ["connected as"])),
        ("hollow-run", lambda: check.window(
            "hollow-run", repo, [":hollow:desktop:run"], ["connected as"])),
        ("hollow-server-client", lambda: check.pair(
            "hollow-server-client", repo,
            [":hollow:desktop:runServer", f"--args={hollow_port}", "-Phollow.server.ticks=7200"], "listening on", False,
            [":hollow:desktop:runClient", f"--args=join 127.0.0.1:{hollow_port}"], "connected as",
            "stopped after 7200 tick(s)")),
        ("hollow-host-join", lambda: check.pair(
            "hollow-host-join", repo,
            [":hollow:desktop:runClient", f"--args=host {hollow_port}"], "hosting on", True,
            [":hollow:desktop:runClient", f"--args=join 127.0.0.1:{hollow_port}"], "connected as", None)),
    ]
    if template is not None:
        entries += [
            ("template-run", lambda: check.headless(
                "template-run", template, ["run"], ["new-game ran 600 ticks"])),
            ("template-agent", lambda: check.agent(
                "template-agent", template, ["run"], port=template_port, draws=False, mode="Headless")),
        ]
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=REPO / "build" / "reports" / "udea" / "launch")
    parser.add_argument("--only", nargs="*", default=None, help="launch names to run; all when absent")
    parser.add_argument("--expect-renderer", default=None, help="text GL_RENDERER must contain, e.g. llvmpipe")
    parser.add_argument("--frames", type=int, default=120, help="frames each window draws before its capture")
    parser.add_argument("--template", type=Path, default=None, help="a set-up copy of templates/new-game")
    parser.add_argument("--start-deadline", type=float, default=START_DEADLINE,
                        help="seconds a launch may take to come up, and a window to close")
    parser.add_argument("--list", action="store_true", help="print the launch names and stop")
    options = parser.parse_args()

    out = options.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    check = Check(out, options.expect_renderer, options.frames, options.start_deadline)
    entries = launches(check, options.template.resolve() if options.template else None)
    if options.list:
        print("\n".join(name for name, _ in entries))
        return 0
    if options.only:
        unknown = sorted(set(options.only) - {name for name, _ in entries})
        if unknown:
            print(f"unknown launch(es): {', '.join(unknown)}", file=sys.stderr)
            return 2
        entries = [(name, run) for name, run in entries if name in options.only]

    results: list[Result] = []
    for name, run in entries:
        print(f"=== {name}", flush=True)
        started = time.monotonic()
        result = run()
        results.append(result)
        verdict = "ok  " if result.ok else "FAIL"
        print(f"{verdict} {name} ({time.monotonic() - started:.0f}s)", flush=True)
        for line in result.problems:
            print(f"     problem: {line}", flush=True)
        for line in result.notes:
            print(f"     {line}", flush=True)

    lines = [f"{'ok  ' if r.ok else 'FAIL'} {r.name}: {r.command}" for r in results]
    for r in results:
        lines += [f"  problem: {p}" for p in r.problems]
        lines += [f"  {n}" for n in r.notes]
    (out / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    failed = [r.name for r in results if not r.ok]
    print(f"\n{len(results) - len(failed)} of {len(results)} launches came up" + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
