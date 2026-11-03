#!/usr/bin/env python3
"""
find_playable_links.py - extract playable links from ONE page you choose.

This is a developer/desktop helper for Stream Buddy. It is NOT shipped inside
the Android APK: the app uses the equivalent native Kotlin scraper in
`app/src/main/java/com/streambuddy/app/browser/PageLinkScraper.kt`, because
embedding a Python runtime would add tens of megabytes and a large attack
surface to a production build.

What it does
------------
* Fetches exactly one http(s) page (no crawling, no search, no index).
* Extracts magnet links, .torrent files, HLS (.m3u8), DASH (.mpd), media files
  and subtitles.
* Resolves relative URLs against the page.
* Deduplicates and caps the result.
* Refuses HTTPS -> HTTP redirects and oversized responses.

It never downloads the media and never executes page JavaScript.

Usage
-----
    python3 tools/find_playable_links.py https://example.test/page
    python3 tools/find_playable_links.py https://example.test/page --json
    python3 tools/find_playable_links.py --html page.html --base https://example.test/
    python3 tools/find_playable_links.py --self-test

Only use this on pages and content you are authorised to access.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

MAX_BYTES = 5 * 1024 * 1024
MAX_LINKS = 200
USER_AGENT = "StreamBuddyLinkFinder/1.0 (developer tool)"

STREAM_EXTENSIONS = {"m3u8"}
DASH_EXTENSIONS = {"mpd"}
SUBTITLE_EXTENSIONS = {"srt", "vtt", "ass", "ssa", "sub", "ttml"}
MEDIA_EXTENSIONS = {
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "m2ts", "mts", "ogv",
    "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma",
}

MAGNET_RE = re.compile(r"""magnet:\?[^\s"'<>\\]+""", re.IGNORECASE)
ABSOLUTE_RE = re.compile(r"""https?://[^\s"'<>\\]+""", re.IGNORECASE)
ATTR_NAMES = {"href", "src", "data-src", "data-href", "data-file", "data-url",
              "data-video", "data-magnet"}


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        for name, value in attrs:
            if value and name.lower() in ATTR_NAMES:
                self.links.append(value)


def kind_for(url: str) -> str | None:
    path = urllib.parse.urlsplit(url).path.lower()
    extension = path.rsplit(".", 1)[1] if "." in path.rsplit("/", 1)[-1] else ""
    if extension == "torrent":
        return "torrent"
    if extension in STREAM_EXTENSIONS:
        return "hls"
    if extension in DASH_EXTENSIONS:
        return "dash"
    if extension in SUBTITLE_EXTENSIONS:
        return "subtitle"
    if extension in MEDIA_EXTENSIONS:
        return "media"
    if "torrent" in path:
        return "torrent"
    return None


def resolve(raw: str, base: str | None) -> str | None:
    value = html.unescape(raw.strip())
    if not value:
        return None
    lowered = value.lower()
    if lowered.startswith(("javascript:", "mailto:", "tel:", "data:", "#")):
        return None
    parts = urllib.parse.urlsplit(value)
    if parts.scheme in ("http", "https"):
        return value
    if base is None:
        return None
    joined = urllib.parse.urljoin(base, value)
    joined_parts = urllib.parse.urlsplit(joined)
    if joined_parts.scheme not in ("http", "https"):
        return None
    return joined


def classify(raw_links: list[str], base: str | None) -> list[dict[str, str]]:
    results: dict[str, dict[str, str]] = {}
    for raw in raw_links:
        if len(results) >= MAX_LINKS:
            break
        value = html.unescape(raw.strip())
        if value.lower().startswith("magnet:"):
            results.setdefault(value, {"url": value, "kind": "magnet", "label": "Magnet link"})
            continue
        resolved = resolve(value, base)
        if resolved is None:
            continue
        kind = kind_for(resolved)
        if kind is None:
            continue
        name = urllib.parse.urlsplit(resolved).path.rsplit("/", 1)[-1] or kind
        results.setdefault(resolved, {"url": resolved, "kind": kind, "label": f"{name} · {kind}"})
    return list(results.values())


def extract_from_html(markup: str, base: str | None) -> list[dict[str, str]]:
    parser = LinkParser()
    try:
        parser.feed(markup)
    except Exception:  # HTMLParser can raise on pathological markup.
        pass
    candidates = list(parser.links)
    candidates.extend(match.group(0) for match in MAGNET_RE.finditer(markup))
    candidates.extend(match.group(0) for match in ABSOLUTE_RE.finditer(markup))
    return classify(candidates, base)


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Refuse to downgrade an HTTPS request to cleartext HTTP."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        if req.full_url.lower().startswith("https://") and not newurl.lower().startswith("https://"):
            raise urllib.error.HTTPError(
                req.full_url, code, "Blocked an HTTPS-to-HTTP redirect", headers, fp,
            )
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url: str, timeout: float = 20.0) -> tuple[str, str]:
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise ValueError("Only http(s) URLs are supported")
    if not parts.hostname:
        raise ValueError("The URL has no host")
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,text/plain;q=0.8",
        },
    )
    opener = urllib.request.build_opener(SafeRedirectHandler())
    with opener.open(request, timeout=timeout) as response:
        content_type = response.headers.get("Content-Type", "").lower()
        if content_type and "html" not in content_type and "text/plain" not in content_type:
            raise ValueError(f"Not an HTML page ({content_type})")
        declared = response.headers.get("Content-Length")
        if declared and declared.isdigit() and int(declared) > MAX_BYTES:
            raise ValueError("The page is too large")
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_BYTES:
                raise ValueError("The page is too large")
            chunks.append(chunk)
        raw = b"".join(chunks)
        charset = response.headers.get_content_charset() or "utf-8"
        try:
            markup = raw.decode(charset, errors="replace")
        except LookupError:
            markup = raw.decode("utf-8", errors="replace")
        return markup, response.geturl()


def print_human(links: list[dict[str, str]], source: str) -> None:
    print(f"Source: {source}")
    if not links:
        print("No magnet, torrent, stream, media or subtitle links found.")
        return
    groups: dict[str, list[dict[str, str]]] = {}
    for link in links:
        groups.setdefault(link["kind"], []).append(link)
    for kind in ("magnet", "torrent", "hls", "dash", "media", "subtitle"):
        entries = groups.get(kind)
        if not entries:
            continue
        print(f"\n{kind.upper()} ({len(entries)})")
        for entry in entries:
            print(f"  {entry['url']}")


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------

def self_test() -> int:
    failures = 0

    def check(name: str, condition: bool, detail: str = "") -> None:
        nonlocal failures
        if condition:
            print(f"PASS  {name}")
        else:
            failures += 1
            print(f"FAIL  {name} {detail}")

    base = "https://example.test/shows/1"
    markup = """
    <html><body>
      <a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Owned">m</a>
      <a href="/files/owned.torrent">t</a>
      <video src="media/owned.mp4"></video>
      <source src="//cdn.example.test/stream/index.m3u8?token=1">
      <a href="data:text/html,<script>alert(1)</script>">x</a>
      <a href="notes.txt">n</a>
      <a href="https://cdn.example.test/sub/owned.vtt">s</a>
      <script>var u = "https://cdn.example.test/deep/manifest.mpd";</script>
    </body></html>
    """
    links = extract_from_html(markup, base)
    by_url = {link["url"]: link["kind"] for link in links}

    check("absolute magnet", any(k == "magnet" for k in by_url.values()))
    check("relative .torrent resolved",
          by_url.get("https://example.test/files/owned.torrent") == "torrent")
    check("relative media resolved",
          by_url.get("https://example.test/shows/media/owned.mp4") == "media")
    check("protocol-relative HLS resolved",
          by_url.get("https://cdn.example.test/stream/index.m3u8?token=1") == "hls")
    check("inline DASH found",
          by_url.get("https://cdn.example.test/deep/manifest.mpd") == "dash")
    check("subtitle found",
          by_url.get("https://cdn.example.test/sub/owned.vtt") == "subtitle")
    check("javascript/data links dropped",
          all(not url.lower().startswith(("javascript:", "data:")) for url in by_url))
    check("ordinary text link dropped", "https://example.test/shows/notes.txt" not in by_url)
    check("deduplicated", len(by_url) == len(links))
    check("kind_for ignores query strings",
          kind_for("https://x.test/a.m3u8?token=abc") == "hls")

    if failures:
        print(f"\n{failures} self-test failure(s)")
        return 1
    print("\nAll self-tests passed.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Extract magnet, torrent, HLS, DASH, media and subtitle links from one page.",
    )
    parser.add_argument("url", nargs="?", help="http(s) page to read")
    parser.add_argument("--html", help="read markup from a local file instead of the network")
    parser.add_argument("--base", help="base URL for resolving relative links with --html")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    parser.add_argument("--self-test", action="store_true", help="run built-in tests and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.html:
        try:
            markup = open(args.html, "r", encoding="utf-8", errors="replace").read()
        except OSError as error:
            print(f"error: cannot read {args.html}: {error}", file=sys.stderr)
            return 2
        source = args.base or args.html
        links = extract_from_html(markup, args.base)
    else:
        if not args.url:
            parser.error("provide a URL, or use --html, or --self-test")
        try:
            markup, source = fetch(args.url)
        except (ValueError, OSError, urllib.error.URLError) as error:
            print(f"error: {error}", file=sys.stderr)
            return 2
        links = extract_from_html(markup, source)

    if args.json:
        print(json.dumps({"source": source, "links": links}, indent=2))
    else:
        print_human(links, source)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
