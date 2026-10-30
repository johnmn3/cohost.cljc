"""Boot the stock core.clj in a real browser (Chromium via Playwright) and print
what the page printed. Serves this directory over HTTP first (file:// blocks
the ClojureScript dev loader). Run from the repository root after
`clojure -M:cljs -m cljs.main -co cljs-browser.edn -c`:

    python3 -I dev/browser_boot.py          # the batteries
    python3 -I dev/browser_boot.py bench    # cohost.bench: target/bench-chromium.edn
"""
import http.server, socketserver, threading, sys, os, time
from playwright.sync_api import sync_playwright

class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass

def serve():
    """Serve the library directory on an ephemeral port; returns (server, port)."""
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    httpd = socketserver.TCPServer(("127.0.0.1", 0), Quiet)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, httpd.server_address[1]

def main():
    httpd, PORT = serve()
    lines = []
    with sync_playwright() as p:
        # the pre-installed Chromium (see the session's /opt/pw-browsers), not a download
        exe = os.environ.get("CLOJURE_HOST_CHROMIUM", "/opt/pw-browsers/chromium")
        browser = p.chromium.launch(executable_path=exe if os.path.exists(exe) else None,
                                    args=["--no-sandbox"])
        page = browser.new_page()
        def on_console(m):
            lines.append(m.text)
            if m.text.startswith("  [") is False:   # progress lines are in #out already
                print(m.text, flush=True)
        page.on("console", on_console)
        page.on("pageerror", lambda e: print("PAGE ERROR: " + str(e), flush=True))
        # the boot runs inside the load handler, so do not wait for "load" here
        query = sys.argv[1] if len(sys.argv) > 1 else "verbose"
        page.goto(f"http://127.0.0.1:{PORT}/index.html?{query}", wait_until="domcontentloaded")
        page.wait_for_function("window.clojureHostDone === true", timeout=1_700_000)
        # the page keeps every printed line in #out; the console stream can lag
        out = page.inner_text("#out")
        print("---- page output ----", flush=True)
        print(out, flush=True)
        browser.close()
    # ?bench: the page prints its results on one BENCH-EDN line
    for line in out.splitlines():
        if line.startswith("BENCH-EDN "):
            os.makedirs("target", exist_ok=True)
            with open("target/bench-chromium.edn", "w", encoding="utf-8") as f:
                f.write(line[len("BENCH-EDN "):])
            print("wrote target/bench-chromium.edn", flush=True)
    httpd.shutdown()

if __name__ == "__main__":
    main()
