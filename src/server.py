#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Podcast feed, web player and time-shifted live stream of the tagesschau.

The feed and the player list the latest editions as published on tagesschau.de.

The official tagesschau podcasts only carry the 20:00 edition. tagesschau.de
publishes every regular edition (12:00, 14:00, 15:00, 17:00, ..., 20:00) as an
MP4 about 15-20 minutes after it aired. This feed lists them newest first, so
a podcast app always has the most recent one.

Discovery: the "letzte Sendung" pages link the newest editions (ts-N.html), and
every edition page links the previous ones. Each edition's JSON
(api2u/<section>/ts-N.json) carries its air time and MP4 URLs.
"""

import base64
import json
import mimetypes
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

SITE = "https://www.tagesschau.de"
LISTING_PAGES = [SITE + "/multimedia/letzte_sendung", SITE + "/multimedia"]
EDITION_RE = re.compile(r"/(tagesschau|tagesschau_20_uhr)/ts-(\d+)\.html")
STREAMS = {"webs": "h264s", "webm": "h264m", "webxl": "h264xl"}
QUALITIES = ["webxxl", "webxl", "webm", "webs"]  # fallback order
PREFERRED = os.environ.get("TAGESSCHAU_QUALITY", "webxxl")
KEEP = int(os.environ.get("TAGESSCHAU_ITEMS", "12"))
MAX_AGE = timedelta(hours=int(os.environ.get("TAGESSCHAU_MAX_AGE_HOURS", "48")))
CACHE_TTL = int(os.environ.get("TAGESSCHAU_CACHE_SECONDS", "60"))
BIND = os.environ.get("BIND_HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8089"))
BASE = os.environ.get("TAGESSCHAU_BASE_URL", "http://localhost:%d" % PORT).rstrip("/")
# Optional artwork, served at /cover.jpg (feed and player) and /logo.jpg (header).
IMAGES = {
    "/cover.jpg": os.environ.get("TAGESSCHAU_COVER") or None,
    "/logo.jpg": os.environ.get("TAGESSCHAU_LOGO") or None,
}
COVER = escape(BASE + "/cover.jpg") if IMAGES["/cover.jpg"] else None
# The site's own icons, shipped next to this file (favicon, home screen icons).
HERE = os.path.dirname(os.path.abspath(__file__))
ICONS = {
    "/favicon.png": "favicon.png",
    "/favicon.ico": "favicon.png",
    "/icon-192.png": "icon-192.png",
    "/icon-512.png": "icon-512.png",
    "/apple-touch-icon.png": "apple-touch-icon.png",
}
REMEMBER = 100  # editions kept in memory between polls

_lock = threading.Lock()
_known = {}  # ts id -> section, accumulated across polls
_editions = {}  # ts id -> (datetime, url) ; immutable once published
_lengths = {}  # url -> content length
_external = {}  # ts id -> externalId (the ARD Mediathek item is named after it)
_subs = {}  # ts id -> (checked at, WebVTT URL or None)
_feed = {"at": 0.0, "xml": b""}


# --- Scrubbing thumbnails --------------------------------------------------------
# For every edition, ffmpeg turns ARD's lowest-quality MP4 (480x270, a key frame every 2 s) into
# a few JPEG sheets of small frames, decoding key frames only (about 2 s per edition). Players
# show the tile for the scrub position instead of seeking a second video. Sheets of editions
# that left the list are deleted.

THUMBS = os.environ.get("TAGESSCHAU_THUMBNAILS", "1") not in ("0", "false", "no", "")
THUMBS_DIR = os.environ.get("TAGESSCHAU_THUMBNAILS_DIR") or (
    os.path.join(os.environ["CACHE_DIRECTORY"].split(":")[0], "thumbs") if os.environ.get("CACHE_DIRECTORY") else None
)
FFMPEG = os.environ.get("TAGESSCHAU_FFMPEG", "ffmpeg")
THUMB = {"interval": 4, "width": 240, "height": 135, "columns": 5, "rows": 5}
THUMBS_RE = re.compile(r"^/thumbs/(ts-\d+)/(\d+)\.jpg$")
THUMBS_RETRY = 600  # seconds before trying a failed edition again
_thumbs = {}  # ts id -> number of sheets (ready)
_thumbs_failed = {}  # ts id -> time of the last failure
_thumbs_wake = threading.Event()


def thumbs_enabled():
    return THUMBS and THUMBS_DIR is not None and shutil.which(FFMPEG) is not None


def thumbs_info(ts_id):
    """The sheets of an edition for /episodes.json, or None while they are not ready."""
    n = _thumbs.get(ts_id)
    if not n:
        return None
    return dict(THUMB, sheets=["%s/thumbs/%s/%d.jpg" % (BASE, ts_id, i) for i in range(1, n + 1)])


def make_thumbs(ts_id, url):
    low = re.sub(r"\.web[a-z]+\.h264\.mp4$", ".webs.h264.mp4", url)
    final = os.path.join(THUMBS_DIR, ts_id)
    tmp = os.path.join(THUMBS_DIR, ".tmp-" + ts_id)
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    t = THUMB
    vf = "fps=1/%d,scale=%d:%d,tile=%dx%d" % (t["interval"], t["width"], t["height"], t["columns"], t["rows"])
    try:
        subprocess.run(
            [
                FFMPEG,
                "-nostdin",
                "-loglevel",
                "error",
                "-skip_frame",
                "nokey",
                "-i",
                low,
                "-an",
                "-sn",
                "-vf",
                vf,
                "-q:v",
                "7",
                os.path.join(tmp, "%d.jpg"),
            ],
            check=True,
            timeout=300,
            capture_output=True,
        )
        n = len([f for f in os.listdir(tmp) if f.endswith(".jpg")])
        if not n:
            raise RuntimeError("no sheets")
        shutil.rmtree(final, ignore_errors=True)
        os.rename(tmp, final)
        _thumbs[ts_id] = n
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def thumbs_worker():
    os.makedirs(THUMBS_DIR, exist_ok=True)
    for name in os.listdir(THUMBS_DIR):  # sheets made before a restart
        path = os.path.join(THUMBS_DIR, name)
        if name.startswith(".tmp-"):
            shutil.rmtree(path, ignore_errors=True)
        elif os.path.isdir(path):
            n = len([f for f in os.listdir(path) if f.endswith(".jpg")])
            if n:
                _thumbs[name] = n
    while True:
        _thumbs_wake.wait(300)
        _thumbs_wake.clear()
        current = list(_episodes["list"])
        for _, ts_id, url in current:
            if ts_id in _thumbs or time.time() - _thumbs_failed.get(ts_id, 0) < THUMBS_RETRY:
                continue
            try:
                make_thumbs(ts_id, url)
            except Exception:
                _thumbs_failed[ts_id] = time.time()
        if current:
            keep = {ts_id for _, ts_id, _ in current}
            for ts_id in [k for k in _thumbs if k not in keep]:
                _thumbs.pop(ts_id, None)
                shutil.rmtree(os.path.join(THUMBS_DIR, ts_id), ignore_errors=True)


def get(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "tagesschau-feed/2"})
    return urllib.request.urlopen(req, timeout=20)


def links(url):
    html = get(url).read().decode("utf-8", "replace")
    return {"ts-" + n: section for section, n in EDITION_RE.findall(html)}


def discover():
    found = {}
    for page in LISTING_PAGES:
        try:
            found.update(links(page))
        except Exception:
            pass
    if not found and not _known:
        raise RuntimeError("no editions found on tagesschau.de")
    # The newest edition page of each section lists the previous editions.
    for section in set(found.values()):
        newest = max((i for i, s in found.items() if s == section), key=lambda i: int(i[3:]))
        try:
            found.update(links("%s/%s/%s.html" % (SITE, section, newest)))
        except Exception:
            pass
    _known.update(found)
    for old in sorted(_known, key=lambda i: int(i[3:]))[:-REMEMBER]:
        del _known[old]


def edition(ts_id):
    if ts_id in _editions:
        return _editions[ts_id]
    data = json.load(get("%s/api2u/%s/%s.json" % (SITE, _known[ts_id], ts_id)))
    if (data.get("title") or "").strip().lower() != "tagesschau":
        return None
    streams = data.get("streams") or {}
    urls = {q: streams[k] for q, k in STREAMS.items() if streams.get(k)}
    if "webxl" in urls:
        # The 1080p file is not in the JSON but sits next to the 720p one.
        urls["webxxl"] = urls["webxl"].replace(".webxl.h264.mp4", ".webxxl.h264.mp4")
    url = None
    for q in [PREFERRED] + [q for q in QUALITIES if q != PREFERRED]:
        try:
            if q in urls and length(urls[q]):
                url = urls[q]
                break
        except Exception:
            continue
    if not url or not data.get("date"):
        return None  # not published yet; retry on the next poll
    if data.get("externalId"):
        _external[ts_id] = data["externalId"]
    _editions[ts_id] = (datetime.fromisoformat(data["date"]), url)
    return _editions[ts_id]


def editions():
    discover()
    now = datetime.now().astimezone()
    found = []
    for ts_id in _known:
        try:
            e = edition(ts_id)
        except Exception:
            e = None
        if e and now - MAX_AGE <= e[0] <= now:
            found.append((e[0], ts_id, e[1]))
    found.sort(reverse=True)
    return found[:KEEP]


# --- Subtitles -----------------------------------------------------------------
# tagesschau.de's JSON has no subtitles, but the ARD Mediathek item of the same
# video does (WebVTT, timed to the same MP4). Its id is the base64 of a crid
# built from the edition's externalId, with one of two suffixes.

MEDIATHEK_ITEM = "https://api.ardmediathek.de/page-gateway/pages/ard/item/%s?embedded=true&mcV6=true"
SUBS_URL_RE = re.compile(r'"(https://api\.ardmediathek\.de/player-service/subtitle/webvtt/[^"]+\.vtt)"')
SUBS_RETRY = 120  # seconds before looking again for subtitles that were missing (ARD adds them after publishing)
SUBS_RE = re.compile(r"^/subtitles/(ts-\d+)\.vtt$")


def subtitles(ts_id):
    """WebVTT URL of an edition's subtitles, or None."""
    checked = _subs.get(ts_id)
    if checked and (checked[1] or time.time() - checked[0] < SUBS_RETRY):
        return checked[1]
    found = None
    external = _external.get(ts_id)
    for suffix in ("-SENDUNGSVIDEO", "_ganzeSendung") if external else ():
        crid = base64.urlsafe_b64encode(("crid://tagesschau.de/%s%s" % (external, suffix)).encode())
        try:
            page = get(MEDIATHEK_ITEM % crid.decode().rstrip("=")).read().decode("utf-8", "replace")
        except Exception:
            continue
        m = SUBS_URL_RE.search(page)
        if m:
            found = m.group(1)
            break
    _subs[ts_id] = (time.time(), found)
    return found


def length(url):
    if url not in _lengths:
        _lengths[url] = int(get(url, "HEAD").headers.get("Content-Length", "0"))
    return _lengths[url]


def build():
    items = []
    for ts, ts_id, url in editions():
        try:
            size = length(url)
        except Exception:
            continue
        title = "tagesschau %s Uhr, %s" % (ts.strftime("%H:%M"), ts.strftime("%d.%m.%Y"))
        items.append(
            '<item><title>%s</title><guid isPermaLink="false">%s</guid>'
            "<pubDate>%s</pubDate><link>%s/%s/%s.html</link>"
            "<description>%s</description>"
            '<enclosure url="%s" length="%d" type="video/mp4"/></item>'
            % (
                escape(title),
                escape(ts_id),
                format_datetime(ts),
                SITE,
                _known[ts_id],
                escape(ts_id),
                escape(title),
                escape(url),
                size,
            )
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>'
        "<title>tagesschau (alle Ausgaben)</title><link>https://www.tagesschau.de</link>"
        "<language>de</language>"
        + (
            "<image><url>" + COVER + "</url><title>tagesschau (alle Ausgaben)</title>"
            '<link>https://www.tagesschau.de</link></image><itunes:image href="' + COVER + '"/>'
            if COVER
            else ""
        )
        + "<description>Die jeweils neuesten tagesschau-Ausgaben von tagesschau.de.</description>"
        "<itunes:author>ARD</itunes:author><itunes:explicit>false</itunes:explicit>"
        + "".join(items)
        + "</channel></rss>"
    ).encode()


def feed():
    with _lock:
        if time.time() - _feed["at"] > CACHE_TTL or not _feed["xml"]:
            try:
                _feed.update(at=time.time(), xml=build())
            except Exception:
                if not _feed["xml"]:
                    raise
                _feed["at"] = time.time() - CACHE_TTL + 10  # serve stale, retry soon
        return _feed["xml"]


# --- Time-shifted live stream -------------------------------------------------
# Das Erste's live HLS keeps the last 2 hours, every segment stamped with
# EXT-X-PROGRAM-DATE-TIME. /live hands out a session URL for the edition on air
# (or aired within LIVE_MAX); its playlists replay the stream from the edition's
# start, delayed by however late the session began, and end after the edition.
# Any HLS player starts at the live edge, which is then the edition's start.

EPG = "https://programm-api.ard.de/program/api/program?day={}&channelIds=daserste&channelType=ARD"
LIVE_MASTER = os.environ.get(
    "TAGESSCHAU_LIVE_URL", "https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8"
)
LIVE_MAX = timedelta(minutes=int(os.environ.get("TAGESSCHAU_LIVE_MINUTES", "90")))
MIN_EDITION = timedelta(seconds=270)  # skips the 2-minute night summaries
END_MARGIN = timedelta(seconds=60)
CATCH_UP = timedelta(seconds=60)  # sessions begun later than this replay from the start
MIN_DELAY = timedelta(seconds=15)  # segments must exist upstream before we list them
# The web player's service worker: only needed so notifications work (also on Android Chrome).
SERVICE_WORKER = """self.addEventListener("notificationclick", e => {
  e.notification.close();
  e.waitUntil(clients.openWindow((e.notification.data && e.notification.data.url) || "/"));
});
"""
DOWNLOAD_RE = re.compile(r"^/download/(ts-\d+)$")
SESSION_RE = re.compile(r"^/live/(\d+)-(\d+)-(\d+)/(index|video|audio|subs)\.m3u8$")

_epg = {}  # day -> (fetched at, [programme entries])
_upstream = {}  # url -> (fetched at, text)


def programme(day):
    """Das Erste's programme for one (ARD) day: title, subline, start, end, image URL."""
    cached = _epg.get(day)
    if cached and time.time() - cached[0] < 600:
        return cached[1]
    data = json.load(get(EPG.format(day)))
    entries = []
    for ch in data.get("channels", []):
        for slot in ch.get("timeSlots", []):
            for e in slot:
                try:
                    start = datetime.fromisoformat(e["broadcastedOn"])
                    end = datetime.fromisoformat(e["broadcastEnd"])
                except (KeyError, ValueError):
                    continue
                image = ((e.get("images") or {}).get("aspect16x9") or {}).get("src") or ""
                entries.append(
                    {
                        "title": (e.get("title") or "").strip(),
                        "subline": (e.get("subline") or "").strip(),
                        "start": start,
                        "end": end,
                        "image": image.replace("{width}", "480") if image else None,
                    }
                )
    _epg[day] = (time.time(), entries)
    return entries


def schedule(day):
    """The tagesschau editions of one day, as (start, end)."""
    return [
        (e["start"], e["end"])
        for e in programme(day)
        if e["title"].lower() == "tagesschau" and e["end"] - e["start"] >= MIN_EDITION
    ]


def on_air(now):
    """What Das Erste shows now and next, for the players' "Das Erste live" row."""
    entries = []
    for d in (-1, 0, 1):
        try:
            entries += programme((now + timedelta(days=d)).strftime("%Y-%m-%d"))
        except Exception:
            pass
    entries.sort(key=lambda e: e["start"])
    current = next((e for e in entries if e["start"] <= now < e["end"]), None)
    if not current:
        return None
    following = next((e for e in entries if e["start"] >= current["end"]), None)

    def out(e):
        return {
            "title": e["title"],
            "subline": e["subline"],
            "start": e["start"].isoformat(),
            "end": e["end"].isoformat(),
            "image": e["image"],
        }

    return {"now": out(current), "next": out(following) if following else None}


def current_edition(now):
    slots = set()
    for d in (-1, 0, 1):
        try:
            slots.update(schedule((now + timedelta(days=d)).strftime("%Y-%m-%d")))
        except Exception:
            pass
    started = [s for s in slots if s[0] <= now]
    if not started:
        return None
    start, end = max(started)
    return (start, end) if now - start < LIVE_MAX else None


def upstream(url):
    cached = _upstream.get(url)
    if cached and time.time() - cached[0] < 1:
        return cached[1]
    text = get(url).read().decode()
    _upstream[url] = (time.time(), text)
    return text


def renditions():
    """Best video variant, default audio and subtitle renditions of the live master."""
    best, video, audio, subs = -1, None, None, None
    lines = upstream(LIVE_MASTER).splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF"):
            bw = int(re.search(r"[:,]BANDWIDTH=(\d+)", line).group(1))
            if bw > best and i + 1 < len(lines):
                best, video = bw, (line, lines[i + 1])
        elif line.startswith("#EXT-X-MEDIA:TYPE=AUDIO") and "DEFAULT=YES" in line:
            audio = line
        elif line.startswith("#EXT-X-MEDIA:TYPE=SUBTITLES") and subs is None:
            subs = line
    return video, audio, subs


def master_playlist():
    (inf, _), audio, subs = renditions()
    group = re.search(r'GROUP-ID="([^"]+)"', audio).group(1)
    audio = re.sub(r'URI="[^"]*"', 'URI="audio.m3u8"', audio)
    inf = re.sub(r",SUBTITLES=\"[^\"]*\"", "", inf)
    inf = re.sub(r'AUDIO="[^"]*"', 'AUDIO="%s"' % group, inf)
    media = [audio]
    if subs:
        # Offered but not on by default: the players turn it on from their setting.
        media.append(
            '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",'
            'DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,URI="subs.m3u8"'
        )
        inf += ',SUBTITLES="subs"'
    return "#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n%s\n%s\nvideo.m3u8\n" % ("\n".join(media), inf)


def shifted_playlist(kind, start, end, began, now):
    video, audio, subs = renditions()
    if kind == "video":
        url = urllib.parse.urljoin(LIVE_MASTER, video[1])
    else:
        url = urllib.parse.urljoin(LIVE_MASTER, re.search(r'URI="([^"]+)"', subs if kind == "subs" else audio).group(1))
    text = upstream(url)
    target = int(re.search(r"#EXT-X-TARGETDURATION:(\d+)", text).group(1))
    seq = int(re.search(r"#EXT-X-MEDIA-SEQUENCE:(\d+)", text).group(1))
    delay = max(began - start, MIN_DELAY)
    lead = timedelta(seconds=3 * target)  # players start 3 segments before the edge
    cutoff = end + END_MARGIN
    horizon = min(now - delay + lead, cutoff)
    out, first, pdt, dur = [], None, None, None
    for line in text.splitlines():
        if line.startswith("#EXT-X-PROGRAM-DATE-TIME:"):
            pdt = datetime.fromisoformat(line.split(":", 1)[1])
        elif line.startswith("#EXTINF:"):
            dur = timedelta(seconds=float(line[8:].split(",")[0]))
        elif line and not line.startswith("#"):
            if pdt and dur and pdt + dur > start and pdt < horizon:
                if first is None:
                    first = seq
                out.append(
                    "#EXT-X-PROGRAM-DATE-TIME:%s\n#EXTINF:%s,\n%s"
                    % (pdt.isoformat(timespec="milliseconds"), dur.total_seconds(), urllib.parse.urljoin(url, line))
                )
            seq += 1
            pdt = dur = None
    head = [
        "#EXTM3U",
        "#EXT-X-VERSION:4",
        "#EXT-X-TARGETDURATION:%d" % target,
        "#EXT-X-MEDIA-SEQUENCE:%d" % (first or seq),
        "#EXT-X-PLAYLIST-TYPE:EVENT",
    ]
    # A catch-up session starts at the edition's beginning; one without delay (the players' "LIVE")
    # starts at its live edge, the players' default.
    if began - start > CATCH_UP:
        head.append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES")
    tail = ["#EXT-X-ENDLIST"] if now - delay + lead >= cutoff else []
    return "\n".join(head + out + tail) + "\n"


def live_session(now):
    edition = current_edition(now)
    if not edition:
        return None
    start, end = edition
    return "%s/live/%d-%d-%d/" % (BASE, start.timestamp(), end.timestamp(), now.timestamp())


PLAYER = os.environ.get("TAGESSCHAU_PLAYER_HTML", os.path.join(os.path.dirname(__file__), "index.html"))
_episodes = {"at": 0.0, "list": []}


def episodes_json(now):
    """Editions for the web player, plus the edition on air if it is not published yet."""
    with _lock:
        if time.time() - _episodes["at"] > CACHE_TTL or not _episodes["list"]:
            _episodes.update(at=time.time(), list=editions())
            _thumbs_wake.set()
        found = _episodes["list"]
    data = {
        "editions": [
            {"id": ts_id, "date": ts.isoformat(), "url": url}
            | ({"subtitles": "%s/subtitles/%s.vtt" % (BASE, ts_id)} if subtitles(ts_id) else {})
            | ({"thumbs": thumbs_info(ts_id)} if thumbs_info(ts_id) else {})
            for ts, ts_id, url in found
        ],
        "live": None,
    }
    edition = current_edition(now)
    if edition and not any(ts == edition[0] for ts, _, _ in found):
        data["live"] = {
            "date": edition[0].isoformat(),
            "end": edition[1].isoformat(),
            "url": live_session(now) + "index.m3u8",
        }
    try:
        data["channel"] = on_air(now)
    except Exception:
        data["channel"] = None
    return json.dumps(data)


class Handler(BaseHTTPRequestHandler):
    def send_text(self, body, ctype, cache="no-cache"):
        body = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", cache)
        # Cast receivers and other origins load the live playlists and the JSON.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_live(self, path):
        now = datetime.now(UTC)
        try:
            if path in ("/live", "/live/"):
                # Das Erste live, whether a tagesschau is on air or not.
                self.send_text(LIVE_MASTER, "text/plain; charset=utf-8")
                return
            if path in ("/live/edition", "/live/audio"):
                url = live_session(now)
                if not url:
                    self.send_error(404, "no tagesschau edition on air")
                    return
                name = "audio.m3u8" if path == "/live/audio" else "index.m3u8"
                self.send_text(url + name, "text/plain; charset=utf-8")
                return
            m = SESSION_RE.match(path)
            if not m:
                self.send_error(404)
                return
            start, end, began = (datetime.fromtimestamp(int(x), UTC) for x in m.groups()[:3])
            if m.group(4) == "index":
                body = master_playlist()
            else:
                body = shifted_playlist(m.group(4), start, end, began, now)
            self.send_text(body, "application/vnd.apple.mpegurl")
        except Exception as exc:
            self.send_error(502, str(exc)[:100])

    def send_image(self, path):
        try:
            with open(IMAGES.get(path) or os.path.join(HERE, ICONS[path]), "rb") as f:
                body = f.read()
        except OSError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(IMAGES.get(path) or ICONS[path])[0] or "image/png")
        self.send_header("Cache-Control", "public, max-age=86400")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_download(self, ts_id):
        """Passes an edition's MP4 through with a file name, so browsers save it."""
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        quality = (query.get("q") or [PREFERRED])[0]
        if quality not in QUALITIES:
            self.send_error(400, "unknown quality")
            return
        try:
            with _lock:
                if ts_id not in _known:
                    discover()
                found = edition(ts_id) if ts_id in _known else None
        except Exception as exc:
            self.send_error(502, str(exc)[:100])
            return
        if not found:
            self.send_error(404, "unknown edition")
            return
        date, url = found
        url = re.sub(r"\.web[a-z]+\.h264\.mp4$", ".%s.h264.mp4" % quality, url)
        name = date.strftime("tagesschau-%Y-%m-%d-%H%M.mp4")
        try:
            upstream = get(url)
        except Exception as exc:
            self.send_error(502, str(exc)[:100])
            return
        with upstream:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % name)
            length = upstream.headers.get("Content-Length")
            if length:
                self.send_header("Content-Length", length)
            self.end_headers()
            if self.command == "HEAD":
                return
            while chunk := upstream.read(1 << 20):
                self.wfile.write(chunk)

    def do_subtitles(self, ts_id):
        """An edition's WebVTT subtitles from the ARD Mediathek, on our origin."""
        try:
            with _lock:
                if ts_id not in _known:
                    discover()
                if ts_id in _known:
                    edition(ts_id)
            url = subtitles(ts_id)
            body = get(url).read().decode("utf-8", "replace") if url else None
        except Exception as exc:
            self.send_error(502, str(exc)[:100])
            return
        if not body:
            self.send_error(404, "no subtitles")
            return
        self.send_text(body, "text/vtt; charset=utf-8", "public, max-age=3600")

    def do_thumbs(self, ts_id, sheet):
        if ts_id not in _thumbs:
            self.send_error(404)
            return
        try:
            with open(os.path.join(THUMBS_DIR, ts_id, "%d.jpg" % int(sheet)), "rb") as f:
                body = f.read()
        except OSError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Cache-Control", "public, max-age=604800, immutable")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        m = THUMBS_RE.match(path)
        if m:
            self.do_thumbs(*m.groups())
            return
        m = SUBS_RE.match(path)
        if m:
            self.do_subtitles(m.group(1))
            return
        m = DOWNLOAD_RE.match(path)
        if m:
            self.do_download(m.group(1))
            return
        if IMAGES.get(path) or path in ICONS:
            self.send_image(path)
            return
        if path == "/sw.js":
            self.send_text(SERVICE_WORKER, "text/javascript; charset=utf-8")
            return
        # Browsers opening /live get the player, which starts what airs now; apps (Tasker) the URL.
        if path in ("/live", "/live/") and "text/html" in self.headers.get("Accept", ""):
            with open(PLAYER, encoding="utf-8") as f:
                self.send_text(f.read(), "text/html; charset=utf-8")
            return
        if path.startswith("/live"):
            self.do_live(path)
            return
        if path == "/episodes.json":
            try:
                body = episodes_json(datetime.now(UTC))
            except Exception as exc:
                self.send_error(502, str(exc)[:100])
                return
            self.send_text(body, "application/json")
            return
        # Browsers get the player on /; podcast apps and monitoring get the feed.
        if path == "/player" or (path == "/" and "text/html" in self.headers.get("Accept", "")):
            with open(PLAYER, encoding="utf-8") as f:
                self.send_text(f.read(), "text/html; charset=utf-8")
            return
        if path in ("/", "/feed.xml", "/feed"):
            try:
                body = feed()
            except Exception as exc:
                self.send_error(502, str(exc)[:100])
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/rss+xml; charset=utf-8")
            self.send_header("Cache-Control", "public, max-age=30")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    do_HEAD = do_GET

    def log_message(self, *args):
        pass


def main():
    if thumbs_enabled():
        threading.Thread(target=thumbs_worker, daemon=True).start()
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
