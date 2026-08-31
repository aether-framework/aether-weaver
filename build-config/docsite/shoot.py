#!/usr/bin/env python3
"""Photograph a built page, so that a claim about the layout can be checked before it ships.

Two rounds of footer work were reported as done on the strength of reading the builder's
stylesheet and reasoning about where an element would land. Both were wrong, and both times
the person who found out was the reader. The site is a single-page application: what the
builder writes to disk is not what the browser shows, so the only way to know what a page
looks like is to render it.

    python3 build-config/docsite/shoot.py <url> <out-prefix> [--theme dark|light]
                                          [--width 1920] [--clip .footer]

Writes <out-prefix>.png. With --clip, only that element.

Chromium lives in ~/.cache/ms-playwright and its shared libraries in ~/.local/chromium-libs,
both installed without root: the container has neither apt lists nor passwordless sudo, so
the libraries were taken from the Debian archive and unpacked into a prefix of their own.
Run through the wrapper below, which puts that prefix on LD_LIBRARY_PATH:

    build-config/docsite/shoot.sh <url> <out-prefix> ...
"""
import argparse
import sys

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("out")
    parser.add_argument("--theme", default="dark", choices=("dark", "light"))
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--clip", default=None, help="CSS selector to photograph on its own")
    parser.add_argument("--css", default=None, help="a file of CSS to apply before the shot")
    args = parser.parse_args()

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("playwright is not importable. Run through build-config/docsite/shoot.sh, "
              "which uses the virtual environment in ~/.local/venvs/docshot.", file=sys.stderr)
        return 2

    with sync_playwright() as play:
        browser = play.chromium.launch(args=["--no-sandbox", "--disable-dev-shm-usage"])
        page = browser.new_page(viewport={"width": args.width, "height": args.height},
                                color_scheme=args.theme)
        page.goto(args.url, wait_until="networkidle", timeout=60000)
        # The front end paints the header, the footer and the tree after the first frame.
        page.wait_for_timeout(1200)
        if args.css:
            page.add_style_tag(content=open(args.css, encoding="utf-8").read())
            page.wait_for_timeout(400)
        target = f"{args.out}.png"
        if args.clip:
            element = page.locator(args.clip)
            element.scroll_into_view_if_needed()
            page.wait_for_timeout(400)
            element.screenshot(path=target)
        else:
            page.screenshot(path=target)
        browser.close()
    print(target)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
