#?(:jank (ns httpbench.native
           (:include "App.h" "simdjson.h"))
   :default (ns httpbench.native))

;; The C++ half of httpbench: the uWebSockets server, the uSockets bench
;; client, the simdjson response validation and the latency histogram all
;; live in the cpp/raw block below. Clojure reaches it through a handful of
;; cpp/ calls (hb_serve, hb_bench, the hb_stat_* readers); nothing else
;; crosses. On the other hosts this namespace is only the stubs, so the app
;; loads everywhere and -main can say it needs jank.
#?(:jank
   (cpp/raw "
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <string>

// ---------- the fixed ~1 KB JSON body ----------
static std::string hb_body;
static void hb_build_body() {
  std::string items;
  for (int i = 0; i < 48; i++) { if (i) items += ','; items += std::to_string((i * 7) % 100); }
  std::string head = \"{\\\"service\\\":\\\"httpbench\\\",\\\"ok\\\":true,\\\"items\\\":[\" + items + \"],\\\"pad\\\":\\\"\";
  std::string tail = \"\\\"}\";
  hb_body = head + std::string(1024 - head.size() - tail.size(), 'x') + tail;
}

// ---------- the server: uWebSockets, GET / -> the fixed body ----------
int hb_serve(int port) {
  hb_build_body();
  bool ok = false;
  uWS::App app;
  app.get(\"/*\", [](auto *res, auto *req) {
        res->writeHeader(\"Content-Type\", \"application/json\");
        res->end(hb_body);
      })
      .listen(\"127.0.0.1\", port, [&](auto *s) {
        ok = (bool)s;
        if (s) { std::printf(\"httpbench listening on 127.0.0.1:%d\\n\", port); std::fflush(stdout); }
      });
  if (!ok) return 1;
  app.run();
  return 0;
}

// ---------- the latency histogram: 8 sub-buckets per octave of ns ----------
static const int HB_NB = 320;
static uint64_t hb_hist[HB_NB];
static uint64_t hb_done, hb_errs, hb_bytes_total, hb_max_ns, hb_wall_ns;

static int hb_bucket(uint64_t v) {
  if (v < 8) return (int)v;
  int e = 63 - __builtin_clzll(v);          // v in [2^e, 2^(e+1)), e >= 3
  return (e - 3) * 8 + (int)(v >> (e - 3)); // 8..15 shifted: index 8*(e-2).. covers via (v >> (e-3)) in [8,16)
}
static uint64_t hb_bucket_lo(int idx) {
  if (idx < 8) return (uint64_t)idx;
  int e = idx / 8 + 2;                       // inverse of hb_bucket: idx = (e-3)*8 + code, code in [8,16)
  uint64_t code = (uint64_t)(idx % 8) + 8;
  return code << (e - 3);
}
static void hb_record(uint64_t v) {
  hb_hist[hb_bucket(v)]++;
  if (v > hb_max_ns) hb_max_ns = v;
}
long hb_stat_done() { return (long)hb_done; }
long hb_stat_errors() { return (long)hb_errs; }
long hb_stat_bytes() { return (long)hb_bytes_total; }
long hb_stat_max() { return (long)hb_max_ns; }
long hb_stat_wall() { return (long)hb_wall_ns; }
long hb_stat_percentile(double q) {
  if (!hb_done) return 0;
  uint64_t target = (uint64_t)(q * (double)hb_done);
  if ((double)target < q * (double)hb_done) target++;
  if (target == 0) target = 1;
  uint64_t acc = 0;
  for (int i = 0; i < HB_NB; i++) { acc += hb_hist[i]; if (acc >= target) return (long)hb_bucket_lo(i); }
  return (long)hb_max_ns;
}

// ---------- the bench client: uSockets, one request in flight per conn ----------
struct hb_conn { uint64_t start_ns; std::string buf; bool awaiting; };
struct hb_state {
  struct us_socket_context_t *ctx;
  std::string req;
  const char *host; int port;
  long total, issued, live, conc;
  simdjson::ondemand::parser parser;
};
static hb_state *HB;
static uint64_t hb_now() {
  return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
}
static bool hb_validate(const char *data, size_t len) {
  simdjson::padded_string body{std::string_view(data, len)};
  simdjson::ondemand::document doc;
  if (HB->parser.iterate(body).get(doc)) return false;
  simdjson::ondemand::object obj;
  if (doc.get_object().get(obj)) return false;
  bool okv = false; long items = 0;
  for (auto field : obj) {
    std::string_view key;
    if (field.unescaped_key().get(key)) return false;
    if (key == \"ok\") { bool b; if (field.value().get_bool().get(b)) return false; okv = b; }
    else if (key == \"items\") {
      simdjson::ondemand::array arr;
      if (field.value().get_array().get(arr)) return false;
      for (auto el : arr) { int64_t x; if (el.get_int64().get(x)) return false; items++; }
    }
  }
  return okv && items == 48;
}
static void hb_issue(struct us_socket_t *s, hb_conn *c) {
  c->start_ns = hb_now(); c->buf.clear(); c->awaiting = true; HB->issued++;
  us_socket_write(0, s, HB->req.data(), (int)HB->req.size(), 0);
}
static void hb_spawn() {
  HB->live++;
  us_socket_context_connect(0, HB->ctx, HB->host, HB->port, nullptr, 0, (int)sizeof(hb_conn));
}
static struct us_socket_t *hb_on_open(struct us_socket_t *s, int is_client, char *ip, int ip_length) {
  hb_conn *c = new (us_socket_ext(0, s)) hb_conn();
  if (HB->issued < HB->total) hb_issue(s, c);
  return s;
}
static long hb_content_length(const char *h, size_t n) {
  for (size_t i = 0; i + 16 <= n; i++)
    if ((i == 0 || h[i - 1] == '\\n') && strncasecmp(h + i, \"content-length:\", 15) == 0)
      return std::atol(h + i + 15);
  return -1;
}
static struct us_socket_t *hb_on_data(struct us_socket_t *s, char *data, int length) {
  hb_conn *c = (hb_conn *)us_socket_ext(0, s);
  c->buf.append(data, (size_t)length);
  size_t he = c->buf.find(\"\\r\\n\\r\\n\");
  if (he == std::string::npos) return s;
  long cl = hb_content_length(c->buf.data(), he);
  if (cl < 0 || c->buf.size() < he + 4 + (size_t)cl) return s;
  uint64_t lat = hb_now() - c->start_ns;
  c->awaiting = false;
  hb_bytes_total += he + 4 + (size_t)cl;
  if (hb_validate(c->buf.data() + he + 4, (size_t)cl)) { hb_record(lat); hb_done++; }
  else hb_errs++;
  if (HB->issued < HB->total) { hb_issue(s, c); return s; }
  return us_socket_close(0, s, 0, nullptr);
}
static struct us_socket_t *hb_on_close(struct us_socket_t *s, int code, void *reason) {
  hb_conn *c = (hb_conn *)us_socket_ext(0, s);
  if (c->awaiting) hb_errs++;               // its in-flight request is lost
  c->~hb_conn();
  HB->live--;
  while (HB->live < HB->conc && HB->issued < HB->total) hb_spawn();
  return s;
}
static struct us_socket_t *hb_on_connect_error(struct us_socket_t *s, int code) {
  hb_errs++; HB->live--;
  return s;
}
static void hb_loop_noop(struct us_loop_t *loop) { (void)loop; }
long hb_bench(const char *host, int port, const char *path, long total, long conc) {
  for (int i = 0; i < HB_NB; i++) hb_hist[i] = 0;
  hb_done = hb_errs = hb_bytes_total = hb_max_ns = hb_wall_ns = 0;
  hb_state st;
  HB = &st;
  st.host = host; st.port = port; st.total = total; st.issued = 0; st.live = 0; st.conc = conc;
  st.req = std::string(\"GET \") + path + \" HTTP/1.1\\r\\nHost: \" + host + \":\" + std::to_string(port)
         + \"\\r\\nConnection: keep-alive\\r\\n\\r\\n\";
  // uSockets calls the loop's pre/post callbacks unconditionally
  // (loop.c) -- they must be no-ops, never nullptr
  struct us_loop_t *loop = us_create_loop(nullptr, hb_loop_noop, hb_loop_noop, hb_loop_noop, 0);
  struct us_socket_context_options_t options = {};
  st.ctx = us_create_socket_context(0, loop, (int)sizeof(void *), options);
  us_socket_context_on_open(0, st.ctx, hb_on_open);
  us_socket_context_on_data(0, st.ctx, hb_on_data);
  us_socket_context_on_close(0, st.ctx, hb_on_close);
  us_socket_context_on_connect_error(0, st.ctx, hb_on_connect_error);
  long n = conc < total ? conc : total;
  for (long i = 0; i < n; i++) hb_spawn();
  uint64_t t0 = hb_now();
  us_loop_run(loop);
  hb_wall_ns = hb_now() - t0;
  us_socket_context_free(0, st.ctx);
  us_loop_free(loop);
  HB = nullptr;
  return (long)hb_done;
}
")
   :default nil)

(defn serve!
  "Run the HTTP server on 127.0.0.1:port until killed. Jank only."
  [port]
  #?(:jank (cpp/hb_serve port)
     :default (throw (ex-info "httpbench needs jank" {}))))

(defn bench!
  "Run total requests against host:port path with concurrency keep-alive
   connections; -> the number of successful responses. The numbers land in
   the C++ histogram; read them with result. Jank only."
  [host port path total concurrency]
  #?(:jank (cpp/hb_bench host port path total concurrency)
     :default (throw (ex-info "httpbench needs jank" {}))))

(defn result
  "The last bench run's numbers, read back from C++."
  []
  #?(:jank {:count (cpp/hb_stat_done)
            :errors (cpp/hb_stat_errors)
            :bytes (cpp/hb_stat_bytes)
            :wall-ns (cpp/hb_stat_wall)
            :p50 (cpp/hb_stat_percentile 0.5)
            :p90 (cpp/hb_stat_percentile 0.9)
            :p99 (cpp/hb_stat_percentile 0.99)
            :p99.9 (cpp/hb_stat_percentile 0.999)
            :max (cpp/hb_stat_max)}
     :default (throw (ex-info "httpbench needs jank" {}))))
