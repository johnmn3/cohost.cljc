# jank

## Type hints (H0)

Measured 2026-11-01 ET on jank `0.1-noble` (`jank compiler jank-0.1-alpha`). Raw jank, no cohost code.

```
$ dpkg-query -W jank
jank	0.1-noble
```

Commands, from the repo root. Each timed case is warmed up once, then run five times in that process. `current-time` is nanoseconds (`clojure.core/time` divides it by 1000000.0). `median-ns` is the middle sample. `ratio-hinted-over-plain` is hinted median / plain median. A ratio below 1 is a faster hinted run.

```
jank run -O3 --eager dev/perf/jank_h0_bench.jank
bash dev/perf/jank_h0_spellings.sh
bash dev/perf/jank_h0_codegen.sh
```

### Ratios

`jank run -O3 --eager dev/perf/jank_h0_bench.jank` printed:

```
jank-h0-bench
call-double-hinted runs-ns 23364901 23241968 23091169 23380014 23157052 median-ns 23241968 result 2500000.0
call-double-plain runs-ns 15881338 16734893 15964568 16090133 15727413 median-ns 15964568 result 2500000.0
call-double hinted-median-ns 23241968 plain-median-ns 15964568 ratio-hinted-over-plain 1.4558469731219785
call-long-hinted runs-ns 19810662 19746357 19753583 19814412 19744978 median-ns 19753583 result 6000000
call-i64-hinted runs-ns 20404948 20605439 19960245 20553653 20812376 median-ns 20553653 result 6000000
call-long-plain runs-ns 13107009 13201443 14209193 13910928 13489929 median-ns 13489929 result 6000000
call-long hinted-median-ns 19753583 plain-median-ns 13489929 ratio-hinted-over-plain 1.4643207536525953
call-i64 hinted-median-ns 20553653 plain-median-ns 13489929 ratio-hinted-over-plain 1.5236294423788295
call-lt-double-hinted runs-ns 20998802 20691956 20612979 20650557 20528430 median-ns 20650557 result 2e+06
call-lt-double-plain runs-ns 14619920 14537792 14723090 14580225 14554897 median-ns 14580225 result 2e+06
call-lt-double hinted-median-ns 20650557 plain-median-ns 14580225 ratio-hinted-over-plain 1.416340077056424
call-inc-long-hinted runs-ns 14294771 14132909 14527969 14271089 15244324 median-ns 14294771 result 2000000
call-inc-long-plain runs-ns 11598250 11487993 11427769 11491214 11686082 median-ns 11491214 result 2000000
call-inc-long hinted-median-ns 14294771 plain-median-ns 11491214 ratio-hinted-over-plain 1.2439739613238427
down-double-hinted runs-ns 1030733 1057953 1000102 999997 1006832 median-ns 1006832 result 2.000001e+12
down-double-plain runs-ns 9648831 9687736 9618479 9631431 9461937 median-ns 9631431 result 2.000001e+12
down-double hinted-median-ns 1006832 plain-median-ns 9631431 ratio-hinted-over-plain 0.104536075688026
down-long-hinted runs-ns 762234 937759 778100 766332 767624 median-ns 767624 result -1453759936
down-i64-hinted runs-ns 762045 767815 766139 763497 761915 median-ns 763497 result -1453759936
down-long-plain runs-ns 6563386 6043513 6136461 6006285 6141933 median-ns 6136461 result -1453759936
down-long hinted-median-ns 767624 plain-median-ns 6136461 ratio-hinted-over-plain 0.12509229668370744
down-i64 hinted-median-ns 763497 plain-median-ns 6136461 ratio-hinted-over-plain 0.12441975920648726
locals-hinted runs-ns 12630517 12571512 12613629 12604179 12691892 median-ns 12613629 result -645128448
locals-plain runs-ns 12625139 12618431 12796343 12609223 12625647 median-ns 12625139 result -645128448
locals hinted-median-ns 12613629 plain-median-ns 12625139 ratio-hinted-over-plain 0.9990883268691141
sum-double-hinted runs-ns 10138043 10112546 10105845 10101590 10151114 median-ns 10112546 result 1.9999999e+14
sum-double-plain runs-ns 10133333 10111680 10119095 10143152 10136113 median-ns 10133333 result 1.9999999e+14
sum-double hinted-median-ns 10112546 plain-median-ns 10133333 ratio-hinted-over-plain 0.9979486512483109
jank-h0-bench-done
```

| case | hinted median ns | plain median ns | ratio hinted/plain |
|---|---:|---:|---:|
| `(defn f [^double a ^double b] (+ a b))`, 2e6 calls | 23241968 | 15964568 | 1.4558469731219785 |
| `(defn f [^long a ^long b] (+ a b))`, 2e6 calls | 19753583 | 13489929 | 1.4643207536525953 |
| `(defn f [^jtl.i64 a ^jtl.i64 b] (+ a b))`, 2e6 calls | 20553653 | 13489929 | 1.5236294423788295 |
| `(defn f [^double a ^double b] (< a b))`, 2e6 calls | 20650557 | 14580225 | 1.416340077056424 |
| `(defn f [^long a] (inc a))`, 2e6 calls | 14294771 | 11491214 | 1.2439739613238427 |
| loop carries a `^double` parameter, 2e6 steps | 1006832 | 9631431 | 0.104536075688026 |
| loop carries a `^long` parameter, 2e6 steps | 767624 | 6136461 | 0.12509229668370744 |
| loop carries a `^jtl.i64` parameter, 2e6 steps | 763497 | 6136461 | 0.12441975920648726 |
| `^long` on the loop locals; the parameter is `^long` on both sides; 2e8 steps | 12613629 | 12625139 | 0.9990883268691141 |
| `^double` on the limit only; locals start at literal `0.0`; 2e7 steps | 10112546 | 10133333 | 0.9979486512483109 |

Both sides of each pair returned the same value (`2500000.0`, `6000000`, `2e+06`, `2000000`, `2.000001e+12`, `-1453759936`, `-645128448`, `1.9999999e+14`).

`down-long` and `down-i64` store the accumulator through a volatile (`cpp/h0bench.eat_i`) on every iteration. Without that store, a constant trip count is folded: an earlier run of the same countdown, before the store was added, printed `down-long hinted-median-ns 42 plain-median-ns 3448720 ratio-hinted-over-plain 1.2178431418033357e-05`. The committed probe keeps the loop.

The wrapped results are the int32 sum. `static_cast<int>` is applied to an accumulator that starts at the literal `0`, on the hinted side and the plain side. For `n = 2000000` the mathematical sum is `2000001000000`, and `-1453759936` is that value modulo 2^32. For the locals loop, `n = 200000000`, both sides returned `-645128448`.

### What `+`, `<`, and `inc` compile to

`bash dev/perf/jank_h0_codegen.sh` compiles `dev/perf/h0/codegen.jank` with `jank compile-module h0.codegen --module-path dev/perf -O3 --output-target cpp` and prints the convert lines. The full print is below. The bodies that decide the ratios:

- `f_dbl` (`^double` parameters, body `(+ a b)`): `convert<double>::from_object` on each argument, `jank::runtime::add` on the natives, then `convert<double>::into_object` on the return.
- `f_plain`: `jank::runtime::add` on the two `object_ref` arguments, returned as that object.
- `f_long`: `convert<long>::from_object`, `add`, `convert<long>::into_object`.
- `f_i64` and `f_jank_i64`: `convert<jtl::i64>::from_object`, `add`, `convert<long long>::into_object`. `jtl::i64` is `long long`.
- `f_int`: `convert<int>`.
- `f_cpp_dbl` / `f_cpp_long`: the same `convert<double>` / `convert<long>` as the bare symbols.
- `f_bool`: `convert<bool>::from_object` then `convert<bool>::into_object`.
- `f_float`: `convert<float>`.
- `lt_dbl`: `convert<double>::from_object`, `jank::runtime::lt`, `convert<bool>::into_object`.
- `inc_long`: `convert<long>::from_object`, `jank::runtime::inc`, `convert<long long>::into_object`. `inc_plain` returns the object from `inc` with no convert.
- `ret_only` (`^double` on the fn name, untyped parameters): `add` on `object_ref`, no convert. The return tag is unused.
- `let_hint` and `let_plain`: the same `add` on the object parameter. The `^double` local tag is unused.
- `locals_hinted` and `locals_plain`: the same body. The parameter is `convert<long>::from_object`. The loop counter is `static_cast<int>` of `inc`. The `^long` tags on the locals are unused.
- `down_dbl`: the parameter is unboxed once (`convert<double>::from_object`); `sub` and `add` stay on those natives; `into_object` runs on the final value.
- `down_dbl_plain`: the carried local is `object_ref`, and each step does `convert<double>::from_object` on the `add` result.
- `down_long`: the carried index stays the unboxed `long`. The accumulator is `static_cast<int>`.
- `down_long_plain`: the carried index stays `object_ref`, and each step does `convert<int>::from_object` on the `add` result.

```

== h0_codegen$f_dbl_102_2
auto &&a{ jank::runtime::convert<double>::from_object(a_160) };auto &&b{ jank::runtime::convert<double>::from_object(b_161) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<double>::into_object(v1));

== h0_codegen$f_plain_103_2
auto &&v1(jank::runtime::add(a, b));

== h0_codegen$f_long_104_2
auto &&a{ jank::runtime::convert<long>::from_object(a_162) };auto &&b{ jank::runtime::convert<long>::from_object(b_163) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<long>::into_object(v1));

== h0_codegen$f_i64_105_2
auto &&a{ jank::runtime::convert<jtl::i64>::from_object(a_164) };auto &&b{ jank::runtime::convert<jtl::i64>::from_object(b_165) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<long long>::into_object(v1));

== h0_codegen$f_jank_i64_106_2
auto &&a{ jank::runtime::convert<jtl::i64>::from_object(a_166) };auto &&b{ jank::runtime::convert<jtl::i64>::from_object(b_167) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<long long>::into_object(v1));

== h0_codegen$f_int_107_2
auto &&a{ jank::runtime::convert<int>::from_object(a_168) };auto &&b{ jank::runtime::convert<int>::from_object(b_169) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<int>::into_object(v1));

== h0_codegen$f_cpp_dbl_108_2
auto &&a{ jank::runtime::convert<double>::from_object(a_170) };auto &&b{ jank::runtime::convert<double>::from_object(b_171) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<double>::into_object(v1));

== h0_codegen$f_cpp_long_109_2
auto &&a{ jank::runtime::convert<long>::from_object(a_172) };auto &&b{ jank::runtime::convert<long>::from_object(b_173) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<long>::into_object(v1));

== h0_codegen$f_bool_110_1
auto &&a{ jank::runtime::convert<bool>::from_object(a_174) };
auto const v1(jank::runtime::convert<bool>::into_object(a));

== h0_codegen$f_float_111_2
auto &&a{ jank::runtime::convert<float>::from_object(a_175) };auto &&b{ jank::runtime::convert<float>::from_object(b_176) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<float>::into_object(v1));

== h0_codegen$lt_dbl_112_2
auto &&a{ jank::runtime::convert<double>::from_object(a_177) };auto &&b{ jank::runtime::convert<double>::from_object(b_178) };
auto &&v1(jank::runtime::lt(a, b));
auto const v2(jank::runtime::convert<bool>::into_object(v1));

== h0_codegen$lt_plain_113_2
auto &&v1(jank::runtime::lt(a, b));
auto const v2(jank::runtime::convert<bool>::into_object(v1));

== h0_codegen$inc_long_114_1
auto &&a{ jank::runtime::convert<long>::from_object(a_179) };
auto &&v1(jank::runtime::inc(a));
auto const v2(jank::runtime::convert<long long>::into_object(v1));

== h0_codegen$inc_plain_115_1
auto &&v1(jank::runtime::inc(a));

== h0_codegen$ret_only_116_2
auto &&v1(jank::runtime::add(a, b));

== h0_codegen$ret_and_params_117_2
auto &&a{ jank::runtime::convert<double>::from_object(a_180) };auto &&b{ jank::runtime::convert<double>::from_object(b_181) };
auto &&v1(jank::runtime::add(a, b));
auto const v2(jank::runtime::convert<double>::into_object(v1));

== h0_codegen$let_hint_118_1
auto &&v3(jank::runtime::add(a, v2));

== h0_codegen$let_plain_120_1
auto &&v3(jank::runtime::add(a, v2));

== h0_codegen$locals_hinted_122_1
auto &&n{ jank::runtime::convert<long>::from_object(n_182) };
auto s5(v2);
auto s6(v3);
auto &&v12(jank::runtime::lt(v10, n));
auto &&v18(jank::runtime::inc(v10));
int v19 (static_cast<int>(v18));
auto &&v20(jank::runtime::add(v11, v10));
auto const v24(jank::runtime::convert<int>::into_object(v11));

== h0_codegen$locals_plain_125_1
auto &&n{ jank::runtime::convert<long>::from_object(n_183) };
auto s5(v2);
auto s6(v3);
auto &&v12(jank::runtime::lt(v10, n));
auto &&v18(jank::runtime::inc(v10));
int v19 (static_cast<int>(v18));
auto &&v20(jank::runtime::add(v11, v10));
auto const v24(jank::runtime::convert<int>::into_object(v11));

== h0_codegen$down_dbl_128_1
auto &&n{ jank::runtime::convert<double>::from_object(n_184) };
auto s4(n);
auto s5(v2);
auto &&v12(jank::runtime::lt(v11, v9));
auto &&v19(jank::runtime::sub(v9, v18));
auto &&v20(jank::runtime::add(v10, v9));
auto const v24(jank::runtime::convert<double>::into_object(v10));

== h0_codegen$down_dbl_plain_131_1
jank::runtime::object_ref s4(n);
auto s5(v2);
auto &&v12(jank::runtime::lt(v11, v9));
auto &&v19(jank::runtime::sub(v9, v18));
auto &&v20(jank::runtime::add(v10, v9));
auto const v21(jank::runtime::convert<double>::from_object(v20));
auto const v25(jank::runtime::convert<double>::into_object(v10));

== h0_codegen$down_long_134_1
auto &&n{ jank::runtime::convert<long>::from_object(n_185) };
auto s4(n);
auto s5(v2);
auto &&v12(jank::runtime::lt(v11, v9));
auto &&v19(jank::runtime::sub(v9, v18));
auto &&v20(jank::runtime::add(v10, v9));
int v21 (static_cast<int>(v20));
auto const v25(jank::runtime::convert<int>::into_object(v10));

== h0_codegen$down_long_plain_137_1
jank::runtime::object_ref s4(n);
auto s5(v2);
auto &&v12(jank::runtime::lt(v11, v9));
auto &&v19(jank::runtime::sub(v9, v18));
auto &&v20(jank::runtime::add(v10, v9));
auto const v21(jank::runtime::convert<int>::from_object(v20));
auto const v25(jank::runtime::convert<int>::into_object(v10));
```

### Spellings jank 0.1 accepts

The reader stores a symbol hint as a quoted tag. `^double` reads as `{:tag (quote double)}`. The compiler evals that tag and resolves the symbol as a C++ type (`analyze_fn_arity`, the `tag_kw` block).

`bash dev/perf/jank_h0_spellings.sh` printed:

```
SPELL bare-double            exit=0 1.25 small_real 
SPELL bare-double-int        exit=1 An object of type `real` was expected here, but a `small_integer` was provided instead.
SPELL bare-long              exit=0 7 small_integer 
SPELL bare-long-real         exit=1 An object of type `integer` was expected here, but a `small_real` was provided instead.
SPELL bare-long-big          exit=0 3000000000 integer 
SPELL bare-int               exit=0 7 
SPELL bare-int-real          exit=1 An object of type `integer` was expected here, but a `small_real` was provided instead.
SPELL bare-int-big           exit=0 -1294967296 
SPELL bare-bool              exit=0 true false boolean 
SPELL bare-bool-int          exit=1 An object of type `boolean` was expected here, but a `small_integer` was provided instead.
SPELL bare-float             exit=0 1.25 small_real 
SPELL cpp-double             exit=0 1.25 small_real 
SPELL cpp-long               exit=0 3000000000 
SPELL cpp-int                exit=0 -1294967296 
SPELL cpp-bool               exit=0 true 
SPELL cpp-bool-int           exit=1 An object of type `boolean` was expected here, but a `small_integer` was provided instead.
SPELL string-double          exit=1 Meta hint must be a keyword, symbol, list, or map.
SPELL string-long            exit=1 Meta hint must be a keyword, symbol, list, or map.
SPELL string-bool            exit=1 Meta hint must be a keyword, symbol, list, or map.
SPELL string-int             exit=1 Meta hint must be a keyword, symbol, list, or map.
SPELL jank-colon-i64         exit=1 Unable to find `jank::i64` within the global namespace.
SPELL jank-dot-i64           exit=0 3000000000 
SPELL jtl-i64                exit=0 3000000000 
SPELL jtl-f64                exit=0 1.25 
SPELL jtl-i32                exit=0 -1294967296 
SPELL keyword-double         exit=0 1 1.25 
SPELL map-symbol             exit=1 Parameter type meta data is expected to be a symbol or list which resolves to a C++ type. The `:tag` meta for this parameter is a `jit_function` instead.
SPELL map-string             exit=1 Parameter type meta data is expected to be a symbol or list which resolves to a C++ type. The `:tag` meta for this parameter is a `persistent_string` instead.
SPELL map-quoted             exit=0 1.25 small_real 
SPELL list-cpp               exit=1 Unable to use `double` as a template. If you just want the type, remove the surrounding parens.
SPELL ret-double             exit=0 3000000000 
SPELL let-int-real           exit=0 1.5 
SPELL let-int-big            exit=1 Parsing failed.
SPELL let-plain-big          exit=1 Parsing failed.
SPELL let-double-int         exit=0 1 
SPELL loop-long              exit=0 3 
SPELL unresolved-boolean     exit=1 Unable to find `boolean` within the global namespace.
SPELL unresolved-integer     exit=1 Unable to find `integer` within the global namespace.
SPELL unresolved-i64         exit=1 Unable to find `i64` within the global namespace.
bare-double {:jank/source {:module "user", :file "nil", :start {:offset 17, :line 1, :col 18}, :end {:offset 18, :line 1, :col 19}}, :tag (quote double)}
cpp-double {:jank/source {:module "user", :file "nil", :start {:offset 21, :line 1, :col 22}, :end {:offset 22, :line 1, :col 23}}, :tag (quote cpp/double)}
bare-long {:jank/source {:module "user", :file "nil", :start {:offset 15, :line 1, :col 16}, :end {:offset 16, :line 1, :col 17}}, :tag (quote long)}
bare-bool {:jank/source {:module "user", :file "nil", :start {:offset 15, :line 1, :col 16}, :end {:offset 16, :line 1, :col 17}}, :tag (quote bool)}
bare-int {:jank/source {:module "user", :file "nil", :start {:offset 14, :line 1, :col 15}, :end {:offset 15, :line 1, :col 16}}, :tag (quote int)}
jank-colon {:jank/source {:module "user", :file "nil", :start {:offset 20, :line 1, :col 21}, :end {:offset 21, :line 1, :col 22}}, :tag (quote jank::i64)}
jank-dot {:jank/source {:module "user", :file "nil", :start {:offset 19, :line 1, :col 20}, :end {:offset 20, :line 1, :col 21}}, :tag (quote jank.i64)}
jtl-i64 {:jank/source {:module "user", :file "nil", :start {:offset 18, :line 1, :col 19}, :end {:offset 19, :line 1, :col 20}}, :tag (quote jtl.i64)}
keyword {:double true, :jank/source {:module "user", :file "nil", :start {:offset 18, :line 1, :col 19}, :end {:offset 19, :line 1, :col 20}}}
map-symbol {:jank/source {:module "user", :file "nil", :start {:offset 24, :line 1, :col 25}, :end {:offset 25, :line 1, :col 26}}, :tag double}
map-string {:jank/source {:module "user", :file "nil", :start {:offset 26, :line 1, :col 27}, :end {:offset 27, :line 1, :col 28}}, :tag "double"}
map-quoted {:jank/source {:module "user", :file "nil", :start {:offset 32, :line 1, :col 33}, :end {:offset 33, :line 1, :col 34}}, :tag (quote double)}
list-cpp {:jank/source {:module "user", :file "nil", :start {:offset 23, :line 1, :col 24}, :end {:offset 24, :line 1, :col 25}}, :tag (quote (cpp/double))}
ret-double (quote double)
let-double (quote double)
loop-long (quote long)
```

From that log:

| spelling | result |
|---|---|
| `^long`, `^cpp/long` | accepted. `(f 3000000000)` prints `3000000000`. A real argument throws: expected `integer`, got `small_real` |
| `^jtl.i64`, `^jank.i64` | accepted. `(f 3000000000)` prints `3000000000`. Codegen type is `jtl::i64` |
| `^int`, `^cpp/int`, `^jtl.i32` | accepted. `(f 3000000000)` prints `-1294967296` |
| `^double`, `^cpp/double` | accepted for a real: `(f 1.25)` prints `1.25 small_real`. `(f 1)` throws: expected `real`, got `small_integer` |
| `^float` | accepted for a real: `(f 1.25)` prints `1.25 small_real`. Codegen type is `float` |
| `^bool`, `^cpp/bool` | accepted for a boolean. `(f 1)` throws: expected `boolean`, got `small_integer` |
| `^"long"` `^"double"` `^"bool"` `^"int"` | read error: `Meta hint must be a keyword, symbol, list, or map.` |
| `^jank::i64` | `Unable to find jank::i64 within the global namespace.` The tag symbol's name is the characters `jank::i64` |
| `^boolean`, `^integer`, `^i64` | unresolved in the global namespace |
| `^(cpp/double)` | `Unable to use double as a template.` |
| `^:double` | no `:tag`. The meta is `{:double true}`. `(f 1)` and `(f 1.25)` both return |
| `^{:tag double}` | rejected: the tag evals to `clojure.core/double`, a `jit_function` |
| `^{:tag "double"}` | rejected: the tag is a `persistent_string` |
| `^{:tag (quote double)}` | accepted, same value as `^double` |
| `^double` on the fn name | `(defn ^double f [] 3000000000)` prints `3000000000` |
| `^int` / `^double` on a `let` local, `^long` on a `loop` local | `(let [^int a 1.5] a)` prints `1.5`. `(let [^double a 1] a)` prints `1`. `(loop [^long i 0] ...)` prints `3` |

`(let [a 3000000000] a)` and `(let [^int a 3000000000] a)` both print `Parsing failed.` A separate `jank run` of each form exits 1 with the same clang diagnostic: `no viable overloaded '='` assigning a `long` to `oref<integer>`, then `error: Unable to compile C++ source.` The failure is the binding of an integer that does not fit in a small integer. It happens with the hint and without it.

A `^double` parameter requires a real object. A `^long` parameter requires an integer object. Passing the other type throws. There is no truncation of a real into a `^long`, and no promotion of an integer into a `^double`.

### Verdict

H1 pays for a parameter hint that a loop inside the function carries.

The carried `^double` loop is ratio `0.104536075688026` (hinted median `1006832` ns, plain `9631431` ns). The carried `^long` loop is `0.12509229668370744`. The carried `^jtl.i64` loop is `0.12441975920648726`. In the generated C++ the carried value stays native, `+` / `-` / `<` call the primitive `jank::runtime` overloads, and `into_object` runs on the way out of the function.

H1 loses when the hinted function is the thing the loop calls, and the body returns `(+ a b)`, `(< a b)`, or `(inc a)`.

| body | ratio hinted/plain |
|---|---:|
| `(+ a b)` on `^double` | 1.4558469731219785 |
| `(+ a b)` on `^long` | 1.4643207536525953 |
| `(+ a b)` on `^jtl.i64` | 1.5236294423788295 |
| `(< a b)` on `^double` | 1.416340077056424 |
| `(inc a)` on `^long` | 1.2439739613238427 |

Those bodies do unbox. They then box the result with `into_object` before the caller sees it, and that round trip is slower than the unhinted call.

Return-type hints are unused (`ret_only` has no convert). `let` and `loop` local hints are unused: the locals case ratio is `0.9990883268691141`, and `locals_hinted` and `locals_plain` emit the same C++, including `static_cast<int>`. A `^double` on the loop limit, with locals that start at the literal `0.0`, is ratio `0.9979486512483109`.

H1's map, from the spellings above: `long` -> `long`, `double` -> `double`, `boolean` -> `bool` (the symbol `boolean` does not resolve), `int` -> `int`. `jtl.i64` and `jank.i64` are the `long long` spelling of a 64-bit integer. Apply it to `fn*` parameter vectors. Leave return hints and `let` / `loop` local hints stripped. Strings, keywords, and every other tag stay stripped.

Step 5 does not end at H0: the carried-loop cases gain. The gain is inside the hinted function. Hinting `+`, `<`, or `inc` themselves, so each call boxes on the way out, loses.
