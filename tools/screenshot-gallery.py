#!/usr/bin/env python3
"""
Live gallery for build/debug-screenshots, for driving moba over SSH.

A headless run has nowhere to show a frame, so every look at the game is a
file nobody can see from the machine they are sitting at. This serves the
screenshot directory to the LAN and refreshes itself, so a browser tab left
open on a desktop shows the newest capture seconds after the game writes it.

    tools/screenshot-gallery.py [--port 8001] [--bind 0.0.0.0]

--bind 127.0.0.1 keeps it off the LAN, for use behind `ssh -L 8001:localhost:8001`.
"""

import argparse
import html
import http.server
import json
import os
import socket
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "debug-screenshots")

PAGE = """<!doctype html>
<meta charset="utf-8">
<title>Udea screenshots</title>
<style>
  /* The viewed shot stays put and only the strip scrolls: hunting for a
     thumbnail used to push the image you were comparing against off screen,
     which is exactly when you need to keep looking at it. */
  html, body {{ height:100%; }}
  body {{ margin:0; background:#14121a; color:#e8e4f0;
         font:14px/1.5 system-ui, sans-serif;
         height:100vh; display:flex; flex-direction:column; overflow:hidden; }}
  header {{ padding:10px 16px; background:#1e1b28; display:flex;
            gap:16px; align-items:baseline; flex:none; z-index:2; }}
  h1 {{ font-size:15px; margin:0; font-weight:600; }}
  .meta {{ color:#9a92b0; font-size:12px; }}
  .viewer {{ flex:none; background:#14121a; border-bottom:1px solid #2a2536; }}
  .latest img, .latest video {{ display:block; width:100%; max-width:1280px;
                 margin:16px auto 4px; max-height:58vh; object-fit:contain;
                 border-radius:6px; background:#0d0b12; }}
  .latest [hidden] {{ display:none; }}
  .caption {{ max-width:1280px; margin:0 auto 12px; padding:0 16px;
              display:flex; gap:12px; align-items:baseline;
              color:#9a92b0; font-size:12px; }}
  .caption .name {{ color:#e8e4f0; font-size:13px; }}
  .caption a {{ color:#9a92b0; }}
  .strip {{ display:flex; flex-wrap:wrap; gap:10px; padding:16px 16px 24px;
            flex:1; overflow-y:auto; align-content:flex-start; }}
  .shot {{ width:220px; padding:0; border:0; background:none; cursor:pointer;
           font:inherit; color:#c9c1e0; text-align:left; }}
  .shot img, .shot video {{ width:100%; display:block; border-radius:4px;
               border:1px solid #322c42; background:#0d0b12; }}
  .shot {{ position:relative; }}
  .shot span {{ font-size:11px; word-break:break-all; }}
  .shot .badge {{ position:absolute; top:6px; right:6px; background:#1e1b28cc;
                  color:#e8b84b; border-radius:3px; padding:1px 5px;
                  font-size:10px; letter-spacing:.04em; }}
  .shot[aria-current="true"] img, .shot[aria-current="true"] video {{ border-color:#c8a24a; }}
  .shot[aria-current="true"] > span:not(.badge) {{ color:#e8b84b; }}
  .empty {{ padding:40px 16px; color:#9a92b0; }}
</style>
<header>
  <h1>Udea screenshots</h1>
  <span class="meta">{count} shots &middot; newest {newest} &middot; refreshing every {interval}s</span>
</header>
{body}
<script>
// Clicking a thumbnail shows it up top rather than navigating to the raw PNG:
// leaving the page to look at one screenshot loses the strip, the refresh and
// your place in it, and getting back is the browser's back button every time.
const hero = document.getElementById('hero');
const heroVideo = document.getElementById('hero-video');
const heroName = document.getElementById('hero-name');
const heroLink = document.getElementById('hero-link');
const thumbs = [...document.querySelectorAll('.shot')];

function show(button, remember) {{
  if (!button || !hero) return;
  // Two viewers share the one slot, so each selection has to put the other away:
  // an <img> pointed at an .mp4 is a broken-image icon, and a <video> left with a
  // src goes on buffering - and, once played, goes on making noise - behind the
  // still you have just switched to.
  const isVideo = button.dataset.kind === 'video';
  if (isVideo) {{
    hero.hidden = true;
    hero.removeAttribute('src');
    heroVideo.hidden = false;
    heroVideo.src = button.dataset.src;
  }} else {{
    heroVideo.pause();
    heroVideo.hidden = true;
    heroVideo.removeAttribute('src');
    heroVideo.load();          // drops the buffer; without it the clip keeps downloading
    hero.hidden = false;
    hero.src = button.dataset.src;
  }}
  heroName.textContent = button.dataset.name;
  heroLink.href = button.dataset.src;
  thumbs.forEach(t => t.setAttribute('aria-current', String(t === button)));
  // Kept per tab, not per browser: two tabs open on this gallery are usually
  // two things being compared, and a shared selection would fight itself.
  if (remember) {{ try {{ sessionStorage.setItem('shot', button.dataset.name); }} catch (e) {{}} }}
}}

thumbs.forEach(t => t.addEventListener('click', () => show(t, true)));

// A refresh must not throw away what you were looking at. The selection is
// restored by name after the reload; when that shot is gone, the newest wins.
let chosen = null;
try {{ chosen = sessionStorage.getItem('shot'); }} catch (e) {{}}
const restored = thumbs.find(t => t.dataset.name === chosen);
show(restored || thumbs[0], false);

// The strip scrolls now, so its position is part of "what you were looking at"
// too: without this the refresh below would drag you back to the top every few
// seconds, which is worse than the reload it is trying to be invisible about.
const strip = document.querySelector('.strip');
if (strip) {{
  let top = 0;
  try {{ top = parseInt(sessionStorage.getItem('stripTop') || '0', 10); }} catch (e) {{}}
  if (top > 0) strip.scrollTop = top;
  // A restored selection that is now below the fold is worth scrolling to, but
  // only when the strip was at the top anyway - otherwise it fights the line above.
  else if (restored) restored.scrollIntoView({{block: 'nearest'}});
  let pending = null;
  strip.addEventListener('scroll', () => {{
    if (pending) return;
    pending = setTimeout(() => {{
      pending = null;
      try {{ sessionStorage.setItem('stripTop', String(strip.scrollTop)); }} catch (e) {{}}
    }}, 150);
  }});
}}

// The strip is patched in place; the page never reloads itself.
//
// It used to call location.reload() whenever the stamp moved, which meant every
// screenshot any agent posted - and on a busy run that is one every few seconds -
// tore down the document. A clip you were halfway through went back to nothing,
// and a ten-second clip on a five-second refresh could never be watched to the
// end at all. Nothing about a new file arriving requires the thing you are
// looking at to be disturbed, so now nothing about it is: the viewer is left
// exactly as it stands, and only the strip gains and loses buttons.
function thumbFor(shot) {{
  const b = document.createElement('button');
  b.className = 'shot';
  b.type = 'button';
  b.dataset.name = shot.name;
  b.dataset.src = shot.name + '?v=' + shot.v;
  b.dataset.kind = shot.kind;
  if (shot.kind === 'video') {{
    const v = document.createElement('video');
    v.src = b.dataset.src;
    v.muted = true;
    v.preload = 'metadata';
    b.append(v, Object.assign(document.createElement('span'),
                              {{className: 'badge', textContent: 'clip'}}));
  }} else {{
    const i = document.createElement('img');
    i.src = b.dataset.src;
    i.alt = shot.name;
    b.append(i);
  }}
  b.append(Object.assign(document.createElement('span'), {{textContent: shot.name}}));
  b.addEventListener('click', () => show(b, true));
  return b;
}}

const meta = document.querySelector('.meta');

setInterval(async () => {{
  let j;
  try {{
    const r = await fetch('/index.json', {{cache: 'no-store'}});
    j = await r.json();
  }} catch (e) {{ return; }}          // a blip is not a reason to disturb the page
  if (!j || !j.shots) return;

  const byName = new Map(thumbs.map(t => [t.dataset.name, t]));
  const wanted = new Set(j.shots.map(s => s.name));
  let changed = false;

  // Rebuilt in server order, which is newest first. Existing buttons are moved
  // rather than recreated, so an <img> already decoded is not fetched again and
  // the selected button keeps its identity - and with it the selection, because
  // `show` compares nodes.
  const next = j.shots.map(s => {{
    const held = byName.get(s.name);
    if (held) {{
      // A shot overwritten under the same name - which is what `screenshot`
      // does by default - keeps its button and just gets a fresher src.
      const src = s.name + '?v=' + s.v;
      if (held.dataset.src !== src) {{
        held.dataset.src = src;
        const m = held.querySelector('img, video');
        if (m) m.src = src;
        if (held.getAttribute('aria-current') === 'true') {{
          heroLink.href = src;
          // Only the still is refreshed in place. Re-pointing a <video> would
          // restart it, which is the whole thing this rewrite exists to stop.
          if (s.kind !== 'video') hero.src = src;
        }}
        changed = true;
      }}
      return held;
    }}
    changed = true;
    return thumbFor(s);
  }});

  for (const t of thumbs) {{
    if (!wanted.has(t.dataset.name)) {{ t.remove(); changed = true; }}
  }}
  if (!changed) return;

  // Order matters and append moves rather than copies, so this both inserts the
  // new and re-sorts the old in one pass.
  for (const b of next) strip.append(b);
  thumbs.length = 0;
  thumbs.push(...next);
  if (meta) {{
    meta.textContent = thumbs.length + ' shots \\u00b7 newest '
      + (j.shots[0] ? j.shots[0].name : '-') + ' \\u00b7 live';
  }}

  // If what you were watching has been deleted there is nothing to keep, so the
  // newest wins - the same rule the reload used to apply, now only in the one
  // case that actually calls for it.
  if (!thumbs.some(t => t.getAttribute('aria-current') === 'true')) show(thumbs[0], false);
}}, {interval} * 1000);
</script>
"""


# Clips as well as stills: a particle in flight, a merge effect and a screen
# transition are all things a single frame cannot show, and agents record them
# with ffmpeg straight off the Xvfb display the game is running on.
VIDEO_EXT = (".mp4", ".webm")
STILL_EXT = (".png", ".gif")


def is_video(name):
    return name.lower().endswith(VIDEO_EXT)


def shots():
    if not os.path.isdir(ROOT):
        return []
    files = [f for f in os.listdir(ROOT) if f.lower().endswith(STILL_EXT + VIDEO_EXT)]
    return sorted(files, key=lambda f: os.path.getmtime(os.path.join(ROOT, f)), reverse=True)


def stamp(files):
    # Newest mtime plus the count: catches both a fresh capture and one being
    # overwritten under the same name, which is what `screenshot` does by default.
    if not files:
        return 0
    return int(max(os.path.getmtime(os.path.join(ROOT, f)) for f in files)) * 1000 + len(files)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, *a):
        pass

    def do_GET(self):
        files = shots()
        if self.path.startswith("/index.json"):
            # The list itself, not just a stamp: the page patches the strip in
            # place from this, so a new capture never interrupts what is on
            # screen. A stamp alone can only say "something changed", and the
            # only answer to that is a reload.
            return self._send(json.dumps({
                "stamp": stamp(files),
                "shots": [
                    {"name": f,
                     "v": int(os.path.getmtime(os.path.join(ROOT, f))),
                     "kind": "video" if is_video(f) else "still"}
                    for f in files
                ],
            }).encode(), "application/json")
        if self.path == "/" or self.path.startswith("/?"):
            return self._send(self._page(files).encode(), "text/html; charset=utf-8")
        return super().do_GET()

    def _page(self, files):
        if not files:
            body = '<p class="empty">No screenshots yet. Run the game and call ' \
                   '<code>curl "http://127.0.0.1:7777/command?cmd=screenshot&amp;name=foo"</code>.</p>'
            newest = "-"
        else:
            top = files[0]
            cb = int(os.path.getmtime(os.path.join(ROOT, top)))
            rows = "".join(
                '<button class="shot" type="button" data-name="{n}" data-src="{n}?v={v}" '
                'data-kind="{k}">{thumb}<span>{n}</span></button>'.format(
                    n=html.escape(f), v=int(os.path.getmtime(os.path.join(ROOT, f))),
                    k="video" if is_video(f) else "still",
                    # preload="metadata" gets the poster frame without fetching the
                    # whole clip: a strip of these should stay as cheap as a strip
                    # of stills right up until you click one.
                    thumb=('<video src="{n}?v={v}" muted preload="metadata"></video>'
                           '<span class="badge">clip</span>'
                           if is_video(f) else '<img src="{n}?v={v}" alt="{n}">').format(
                        n=html.escape(f), v=int(os.path.getmtime(os.path.join(ROOT, f)))))
                for f in files
            )
            top_is_video = is_video(top)
            body = (
                '<div class="viewer"><div class="latest">'
                '<img id="hero" {img_src} alt="" {img_hidden}>'
                '<video id="hero-video" controls loop {vid_src} {vid_hidden}></video>'
                '</div>'
                '<div class="caption"><span class="name" id="hero-name">{t}</span>'
                '<a id="hero-link" href="{t}?v={v}" target="_blank" rel="noopener">'
                'open original</a></div>'
                '</div>'
                '<div class="strip">{rows}</div>'
            ).format(t=html.escape(top), v=cb, rows=rows,
                     # No src attribute at all when the newest item is a clip,
                     # rather than an empty one: src="" resolves to the document
                     # URL, so the browser fetches this whole page a second time
                     # and decodes it as an image. The script does the same thing
                     # with removeAttribute when you click a clip in the strip.
                     img_src="" if top_is_video else 'src="{}?v={}"'.format(html.escape(top), cb),
                     img_hidden='hidden' if top_is_video else '',
                     vid_src='src="{}?v={}"'.format(html.escape(top), cb) if top_is_video else '',
                     vid_hidden='' if top_is_video else 'hidden')
            newest = html.escape(top)
        return PAGE.format(count=len(files), newest=newest, interval=ARGS.interval,
                           stamp=stamp(files), body=body)

    def _send(self, payload, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8001)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--interval", type=int, default=2)
    ARGS = p.parse_args()

    os.makedirs(ROOT, exist_ok=True)
    lan = socket.gethostbyname(socket.gethostname())
    srv = http.server.ThreadingHTTPServer((ARGS.bind, ARGS.port), Handler)
    print("gallery on http://%s:%d  (dir: %s)" % (lan, ARGS.port, os.path.realpath(ROOT)))
    srv.serve_forever()
