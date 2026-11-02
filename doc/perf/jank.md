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

## AOT stage 1

Measured 2026-11-01 ET on jank `0.1-noble` (`jank compiler jank-0.1-alpha`). Load time is nanoseconds from process start (`time.time_ns`, the same CLOCK_REALTIME clock as `cpp/jank.runtime.current_time`) until the `LOAD-BEFORE-BOOT-NS` line, which is the first form after `(require 'cohost.run)` and immediately before `cohost.run/-main`. `-main` calls `boot!` after it parses its arguments. `--load-only` stops at the stamp.

### Compile

```
jank --module-path src compile-module cohost.run -O3 --target-dir target/jank-aot
```

The shell recorded `EXIT:0`. Clang still rejected `cohost.hosts.jank`. The log begins:

```
/workspace/src/cohost/hosts/jank.jank:231:1: error: no member named 'runtime' in namespace 'cohost::hosts::jank'; did you mean '::jank::runtime'?
  231 | jank::runtime::obj::keyword_ref const_2830{ _jank_null{ } };
      | ^~~~~~~~~~~~~
      | ::jank::runtime
```

and ends `fatal error: too many errors emitted, stopping now [-ferror-limit=]` / `20 errors generated.` No `cohost/hosts/jank.o` was written. The other objects were:

`target/jank-aot/_cache/x86_64-unknown-linux-gnu-d41ef4a07a9d4dd9963044192227fbfde2da40c9f817907bab326588f8e7ef94/cohost/{adapter,battery,bench,boot,lib_battery,policy,rewrite,run,scan,shim}.o` and `cohost/hosts/auto.o`.

The same failure on a namespace whose name ends in `jank`, with no cohost code:

```
jank --module-path /tmp/repro compile-module repro.jank -O0 --target-dir /tmp/repro-out
```

exited 0 and Clang reported `no member named 'runtime' in namespace 'repro::jank'; did you mean '::jank::runtime'?`. Inside the generated namespace `…::jank`, the qualifier `jank::` binds to that namespace, so `jank::runtime` does not name the runtime. `cohost.hosts.jank` therefore stays source. The boot below puts the object directory ahead of `src`; jank still requires the source on the path. With only the object directory:

```
error: The registered module `cohost.run` has no source files.
```

(`runtime/module-binary-without-source`).

### Load time

`bash dev/perf/jank_aot_time.sh` printed:

```
aot-dir target/jank-aot/_cache/x86_64-unknown-linux-gnu-d41ef4a07a9d4dd9963044192227fbfde2da40c9f817907bab326588f8e7ef94
warmup jit
warmup aot
jit run 1 load-ns 5150953051
jit run 2 load-ns 5093467970
jit run 3 load-ns 5169138294
jit run 4 load-ns 5027232320
jit run 5 load-ns 5196487980
median-ns 5150953051
aot run 1 load-ns 1504751759
aot run 2 load-ns 1487988698
aot run 3 load-ns 1468460498
aot run 4 load-ns 1499645531
aot run 5 load-ns 1511217965
median-ns 1499645531
```

| module path | median of 5 `load-ns` |
|---|---:|
| `src` (source) | 5150953051 |
| `<aot-dir>:src` (objects ahead of source) | 1499645531 |

### Boot

`bb boot:jank` printed:

```
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 124/139 stock behaviors pass
libraries: 30/61 cases pass across 12 clojure.* libraries
```

`bb boot:jank-aot` printed:

```
LOAD-BEFORE-BOOT-NS 1793543844087418012
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 123/139 stock behaviors pass
libraries: 30/61 cases pass across 12 clojure.* libraries
```

The boot line and the library line match. The battery does not. The AOT run has one failure the source run does not:

```
   FAIL radix parsing and printing
        got      "ERR For input string: \"-zz\" under radix 36"
        expected [255 -1295 "ff" "ffffffff" "101"]
```

That row is `(Long/parseLong "-zz" 36)` through `cohost.shim/parse-radix`, and `shim.o` is one of the objects loaded ahead of `shim.cljc`. The source boot does not print this FAIL. No `src/` file was edited.

### Remeasure after `cohost.hosts.jank-host`

Measured 2026-11-01 ET on the same jank, base `dcf9afc` (the adapter namespace is `cohost.hosts.jank-host`). `jank --module-path src compile-module cohost.run -O3 --target-dir target/jank-aot` printed `COMPILE:0`. `cohost/hosts/jank_host.o` is `5698472` bytes. Clang did not report the `jank::runtime` error. The other objects in that cache directory are `adapter.o`, `boot.o`, `policy.o`, `rewrite.o`, `run.o`, `scan.o`, `battery.o`, `bench.o`, `lib_battery.o`, and `hosts/auto.o`.

`(Long/parseLong "-zz" 36)` is still wrong in an AOT `shim.o`, and it is not only `-O3`. Each level was compiled with `jank --module-path src compile-module cohost.shim -<level> --target-dir target/jank-aot`, which writes a different `_cache` directory than the `-O3` `cohost.run` build. The parse was run with that directory ahead of `src`:

| shim object | `(Long/parseLong "-zz" 36)` |
|---|---|
| source (`shim.o` absent) | `-1295` |
| `-O3` | `NumberFormatException`, `For input string: "-zz" under radix 36` |
| `-O2` | same exception |
| `-O1` | same exception |
| `-O0` | same exception |

No opt level keeps the source result. `bb boot:jank-aot` deletes `shim.o` after the `-O3` compile so `cohost.shim` loads from source. It does not compile `cohost.shim` on its own (that would add another `_cache` directory).

`bash dev/perf/jank_aot_time.sh` with `jank_host.o` present and `shim.o` absent:

```
jit run 1 load-ns 6497792680
jit run 2 load-ns 7128903616
jit run 3 load-ns 7290241483
jit run 4 load-ns 6214716137
jit run 5 load-ns 6240240959
median-ns 6497792680
aot run 1 load-ns 3981557519
aot run 2 load-ns 3952562118
aot run 3 load-ns 4098704375
aot run 4 load-ns 3904978481
aot run 5 load-ns 3804885655
median-ns 3952562118
```

| module path | median of 5 `load-ns` |
|---|---:|
| `src` | 6497792680 |
| `<aot-dir>:src` (`jank_host.o` ahead of source, no `shim.o`) | 3952562118 |

`bb boot:jank` and `bb boot:jank-aot` printed the same boot line, the same battery, and the same libraries:

```
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

Both fail the same two rows, `subs count nth first last butlast` (`Unterminated vector.`) and `Character outside ASCII` (`Unexpected end of file.`). The AOT boot does not fail `radix parsing and printing`.

### Default

Measured 2026-11-01 ET. `bb boot:jank` and `bb bench:jank` load the compiled modules. `bb boot:jank-jit` stays `jank --module-path src run-main cohost.run`.

`dev/perf/jank_aot_ensure.clj` hashes every regular file under `src/cohost`: the sorted relative paths, one `sha256sum` per file, then `sha256sum` of that listing. The stamp beside the objects (`<cache>/stamp`) is that hash and the line from `jank print-binary-version`. A missing `cohost/run.o`, `cohost/hosts/jank_host.o`, or `cohost/shim.o`, a missing stamp, or a mismatch deletes `target/jank-aot/_cache` and runs `jank --module-path src compile-module cohost.run -O3 --target-dir target/jank-aot` before anything loads the objects. A match prints `AOT-FRESH`.

`jank print-binary-version` printed `x86_64-unknown-linux-gnu-cf627abb22b776c8ebbb6caee09f4f22fe25a16e33827b2ccc2eb155085e5e68`. The first `bb boot:jank` printed `AOT-RECOMPILE` and this stamp:

```
d4500fc9360363e4e5616a4a97be295ca95154f5a95985031b943e171dc7945a
x86_64-unknown-linux-gnu-cf627abb22b776c8ebbb6caee09f4f22fe25a16e33827b2ccc2eb155085e5e68
```

`cohost/shim.o` from that compile was 10659624 bytes. The boot printed:

```
AOT-RECOMPILE
AOT-DIR target/jank-aot/_cache/x86_64-unknown-linux-gnu-d41ef4a07a9d4dd9963044192227fbfde2da40c9f817907bab326588f8e7ef94
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The same two rows fail (`Unterminated vector.`, `Unexpected end of file.`). `radix parsing and printing` passed with `shim.o` loaded. The object used to throw that row because the literal `-9223372036854775808` becomes a garbage double at every `-O` level; `long-min-value` is `(- -9223372036854775807 1)` instead. The minimal repro is `doc/upstream/jank-10-aot-long-min-literal.md`.

Appending `;; g12-stamp-touch` to `src/cohost/run.cljc` made the next `bb boot:jank` print `AOT-RECOMPILE`, then the same boot, battery, and library lines. The stamp hash became `acc112880e2ac668348809b22275876f26ef84d5352271514c476999d1d7de0b`. `git checkout -- src/cohost/run.cljc` removed the line. The next `bb bench:jank --quick` printed `AOT-RECOMPILE` and wrote the `d4500fc9…` stamp again. The two benches after that printed `AOT-FRESH`.

`bb bench:jank --quick` sets `warm-ms` 0, `budget-ms` 0, `min-runs` 1, `settle-ms` 0. Before is the source module path (`jank --module-path src run-main cohost.run -- --bench --quick`). After is `bb bench:jank --quick` (the object directory ahead of `src`, `shim.o` included). `boot-ms` and `libs-ms` are the values in `target/bench-jank.edn`. The median is the middle of the three sorted runs.

| | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| JIT `boot-ms` | 28614.529163 | 30967.885298 | 29644.042124 | 29644.042124 |
| JIT `libs-ms` | 23322.846218999475 | 29486.88468999788 | 23494.7270280011 | 23494.7270280011 |
| AOT `boot-ms` | 8612.379721 | 8714.469301 | 8568.13362 | 8612.379721 |
| AOT `libs-ms` | 5696.899114001542 | 6101.376775000244 | 5855.873693000525 | 5855.873693000525 |

The AOT `shim.o` on that recompile was 10659648 bytes. `boot-ms` is the time inside the jank process, after `ensure!` returns, so the recompile on the first AOT bench is not part of `boot-ms`.

The same objects with `shim.o` moved aside (so `cohost.shim` loaded from source; `ensure!` was not used, because a missing `shim.o` is stale) and then three `jank --module-path <aot-dir>:src run-main cohost.run -- --bench --quick` runs:

| | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| AOT, shim from source, `boot-ms` | 10807.08871 | 11021.98627 | 11640.262625 | 11021.98627 |
| AOT, shim from source, `libs-ms` | 8103.36599900201 | 9084.837037999183 | 9241.82250900194 | 9084.837037999183 |

`shim.o` was moved back after those runs (10659648 bytes). Loading it is the faster of the two AOT medians: `boot-ms` 8612.379721 against 11021.98627, `libs-ms` 5855.873693000525 against 9084.837037999183.

## AOT stage 2 probe

Measured 2026-11-01 ET on jank `0.1-noble`. The boot was not changed. A temporary local hook (not committed) appended each rewritten form `boot!` and `load-lib!` actually evaluated. `cohost.core` produced 550 forms (`boot!` printed `BOOT 524 171 78 0`; the file has 791 top-level forms counted by the boot report, and fewer than 2,000 are evaluated, so the module is all 550, not a 2,000-form prefix). `clojure.walk` produced 10 forms in `cohost.walk`.

### Compile

`cohost.walk`, host core referred, `-O3`, three runs. No object was written.

```
jank --module-path /tmp/jank-snap-src:src compile-module cohost.walk -O3 --target-dir /tmp/jank-snap-aot
```

`COMPILE_NS` `378779228`, `437787731`, `430674118`. Median `430674118`. Each exited 1:

```
error: The symbol `cohost.shim/is$clojure_lang_IMapEntry` could not be resolved.
```

The same command at `-O1` exited 1 with that same error. One run printed `Building pre-compiled header` and `COMPILE_NS` `5410469166`. The next, header already built, was `384029619`.

`cohost.core`, bare ns, `-O3`, three runs. No object was written.

```
jank --module-path /tmp/jank-snap-src:src compile-module cohost.core -O3 --target-dir /tmp/jank-snap-aot-core
```

`COMPILE_NS` `385537106`, `427512951`, `388416781`. Median `388416781`. Each exited 1:

```
error: The symbol `cohost.shim/PersistentList-creator` could not be resolved.
```

A bare `cohost.walk` (`:refer-clojure :only []`) failed earlier, in `661410276` ns, with `The symbol list? could not be resolved.`

Load time was not measured. `find` over the target dirs printed no `.o` files.

### Eval of the same forms

`jank --module-path src run dev/perf/jank_snapshot_check.jank`, three processes. After `boot!` and `load-lib!` of `clojure.walk`, the dumped files were read and each form except the `ns` form was `eval`'d again. `EVAL-NS` is nanoseconds.

walk, 10 forms: `5821463`, `5843343`, `23841514`. Median `5843343`.

core, 550 forms: `1561941496`, `1559581158`, `1521598600`. Median `1559581158`.

### Checks

The compiled module never loaded, so every check of "after the compiled module" is no. The same checks after a normal boot, from the three runs (the lines matched):

```
CHECK walk-var true
CHECK walk-doc "Traverses form, an arbitrary data structure.  inner and outer are\n  functions.  Applies inner to each element of form, building up a\n  data structure of the same type, then applies outer to the result.\n  Recognizes all Clojure data structures. Consumes seqs as with doall."
CHECK walk-arglists ([inner outer form])
CHECK mx-macro nil
CHECK keywordize {:a 1}
CHECK when-macro true
CHECK print-method true
CHECK print-method-keys [:jank/source :cohost/wrapped :cohost/host]
CHECK type-atom "atom"
CHECK cpp-cohost false
CHECK record ERR
```

| check | normal boot | compiled module |
|---|---|---|
| `cohost.walk/walk` is a var | yes, `true` | no, no object loaded |
| `:doc` | yes, the walk docstring | no |
| `:arglists` | yes, `([inner outer form])` | no |
| `:macro` on `macroexpand-all` | yes, the value is `nil` (it is a function) | no |
| `:macro` on `cohost.core/when` | yes, `true` | no |
| `keywordize-keys` of `{"a" 1}` | yes, `{:a 1}` | no |
| `print-method` var | yes, meta keys `[:jank/source :cohost/wrapped :cohost/host]` | no |
| `type` of an atom | yes, `"atom"` | no |
| `cpp/cohost_obj.is_cohost` of an atom | yes, the call returned `false` | no |
| `defrecord` | no, the check printed `ERR` | no |

### Verdict

The Step 6.2 design does not hold on jank 0.1: `compile-module` of the rewritten forms does not produce a module, at `-O3` or `-O1`. Analyze stops before codegen.

What has to change:

- Vars the codemod calls are interned at boot, not defined in source. `cohost.shim/is$clojure_lang_IMapEntry` comes from `install-is!`. `cohost.shim/PersistentList-creator` is the same kind of runtime var. The snapshot has to emit real defs for those, or compile against a shim the compiler can already resolve.
- Unqualified names in the booted namespace are `cohost.core`'s, and `cohost.core` is not a source module. A bare ns cannot resolve `list?`. Referring the host core would bind jank's `list?`, not `cohost.core/list?`.
- `scan-macros!`, refers, and `:cohost/host` / `:cohost/wrapped` are not part of the evaluated forms. `print-method`'s meta after a normal boot is that host marking. A module of only the eval'd forms does not redo it.
- There are 550 evaluated `cohost.core` forms, not 2,000. `clojure.walk` is 10.

## AOT stage 2

Measured 2026-11-02 ET on jank `0.1-noble` (lane D, D-1), branch `d/jank-snapshot`. A boot from the snapshot loads compiled objects of the booted core and the twelve libraries instead of booting `core.clj`.

```
bb boot:jank-snapshot                    # batteries; builds the snapshot first when stale
bb boot:jank-snapshot --bench --quick    # the bench, its boot-ms and libs-ms from the snapshot
bash dev/perf/jank_snapshot_time.sh      # the medians below
```

### How it works

The probe's verdict named three gaps: shim vars interned only at boot, a namespace that must see `cohost.core` rather than jank's core, and var surgery that is not in the evaluated forms. The build closes them with replay, not by re-emitting shim source:

- **Record** (`boot!` with `{:record? true}`, off by default on every host; `dev/perf/jank_snapshot_record.jank`). Every effect the boot has, in order: each rewritten form after it evaluated, `take-from-host!`, the companion names from the adapter's core extras, each `scan-macros!` that marked or wrapped a macro, the shim's instance tests (`install-is!`) and missing-method dispatchers, the codemod's `:inline` captures, the loader's directives (`ns`, `require`, `alias` ...), each library's begin and end, a failed form's leftover var, and the boot's state at the end (the report, the codemod ctx's imports, renames, user types, referred host names, loader namespaces). jank's printer ignores `*print-meta*`, so a form's metadata is recorded as data and the emitter turns it back into reader metadata.
- **Emit** (`dev/perf/jank_snapshot_emit.clj`): one module per booted namespace under `target/jank-snapshot/src/`: `cohost.core`, `cohost.walk`, ... `cohost.spec.gen.alpha`. Each is the recorded forms with calls of `cohost.boot/snap-*` between them; those calls redo what the boot did through the adapter (the same functions the boot calls), so the module's object redoes it when it loads. Each module is a bare namespace (`clojure.core/ns`, `(:refer-clojure :only [])`), as the boot's namespaces are; a library's replayed `ns` directive refers `cohost.core` into it.
- **Compile**: `jank compile-module cohost.snapshot -O3`, an entry module requiring the core and the top-level libraries, compiles all of them (and cohost's own namespaces) into `target/jank-snapshot/aot/`.
- **Load**: `dev/perf/jank_snapshot_run.jank` requires `cohost.core` (the snapshot's), then does what `cohost.run/-main` does after `boot!`. `load-lib!` requires a library's snapshot module when the snapshot registered one, which replays that library's load; the registry is nil unless a snapshot is loaded.
- **Key**: sha256 over the stock sources (`resources/cohost/clojure-1.12.0`), `src/cohost`, `dev/perf/jank_snapshot_*`, and `jank print-binary-version`, in `target/jank-snapshot/stamp` (`dev/perf/jank_snapshot_ensure.clj`). No stamp, another key, or a missing object is stale: the snapshot is deleted and built again before anything loads it.

jank facts found on the way (0.1-noble):
- `compile-module` evaluates each top-level form before it analyzes the next, so a var a call interns resolves in the forms after it; its codegen pass analyzes the module's `ns` form inside the new namespace, so a bare namespace's header must be written `clojure.core/ns`.
- A required file's `ns` form is analyzed in the requiring namespace: the core module requires `cohost.run` before its own bare `ns` form.
- An object is not loaded without its source on the module path (`runtime/module-binary-without-source`): the emitted sources stay beside the objects.

### Checks (cohost.walk end to end)

`jank --module-path <snapshot> run dev/perf/jank_snapshot_check.jank -- --snapshot`, the probe's check script after a snapshot boot and `load-lib! 'clojure.walk`:

```
CHECK-BOOT snapshot
CHECK snapshot-report true
CHECK walk-var true
CHECK walk-doc "Traverses form, an arbitrary data structure.  inner and outer are\n  functions.  Applies inner to each element of form, building up a\n  data structure of the same type, then applies outer to the result.\n  Recognizes all Clojure data structures. Consumes seqs as with doall."
CHECK walk-arglists ([inner outer form])
CHECK mx-macro nil
CHECK keywordize {:a 1}
CHECK when-macro true
CHECK print-method true
CHECK print-method-keys [:cohost/host :cohost/wrapped]
CHECK type-atom "atom"
CHECK cpp-cohost ERR
CHECK record ERR
```

Every row matches the normal boot's (probe section) except two. `print-method`'s meta has no `:jank/source`, which is reader position data. `cpp-cohost` (a fresh `eval` of `cpp/cohost_obj.is_cohost`) is ERR where the probe's JIT boot printed `false`; it is ERR too after a normal boot on the stage 1 objects (`jank --module-path target/jank-aot/_cache/<triple>:src run dev/perf/jank_snapshot_check.jank`, which printed every other row as the probe did, `print-method-keys [:jank/source :cohost/host :cohost/wrapped]`), so it comes from `cohost.hosts.jank-host` loaded as an object (its `cpp/raw` declarations are not seen by a later `eval`), not from the snapshot. The batteries do not depend on it. `record` is ERR on both.

### Batteries from the snapshot

`bb boot:jank-snapshot` (stale: no stamp; rebuilt, then booted from it):

```
SNAPSHOT-STALE no stamp
SNAPSHOT-BUILT a4fe66750c3bbf7f46c090fb3627bfd01e9b8215b176620194fefa076aec9714 in 552634 ms
SNAPSHOT-BOOT-MS 743.59331
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
   FAIL Character/isWhitespace is Java's
   FAIL Character outside ASCII
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The same as `bb boot:jank` on this box (542/171/78/0, 143/145, 61/61), the two failures the documented Character host facts, and the same eight library load errors listed under the libraries (restored from the recorded report). The build: record 1452 events (930 forms, 13 library namespaces, none failed), emit, compile -O3; 552,634 ms in all, about 9.2 minutes, paid once per input change.

### boot-ms and libs-ms

`bash dev/perf/jank_snapshot_time.sh`: three runs each, alternating, `--quick`, the box otherwise idle. `bench:jank` is the stage 1 default (cohost's namespaces AOT, `core.clj` booted).

```
RUN aot 1 boot-ms libs-ms sum: 9502.696091 8248.29765299987 17750.99374399987
RUN snapshot 1 boot-ms libs-ms sum: 795.273757 752.1922149998136 1547.4659719998135
RUN aot 2 boot-ms libs-ms sum: 10609.258105 9980.032635000069 20589.29074000007
RUN snapshot 2 boot-ms libs-ms sum: 530.175672 578.077767000068 1108.2534390000678
RUN aot 3 boot-ms libs-ms sum: 9590.933535 9225.588032000232 18816.521567000233
RUN snapshot 3 boot-ms libs-ms sum: 597.172844 696.111727999989 1293.284571999989
MEDIAN aot boot-ms 9590.933535 libs-ms 9225.588032000232 sum 18816.521567000233
MEDIAN snap boot-ms 597.172844 libs-ms 696.111727999989 sum 1293.284571999989
```

| median of 3 | boot-ms | libs-ms | sum |
|---|---:|---:|---:|
| `bb bench:jank --quick` (no snapshot) | 9,590.9 | 9,225.6 | 18,816.5 |
| `bb boot:jank-snapshot --bench --quick` | 597.2 | 696.1 | 1,293.3 |

The sum is 14.5 times smaller. For scale (jank-FAST): JVM Clojure 1.3 s, jolt 2.5 s boot. All 30 bench cases ran on both sides of the snapshot run with the same values.

### A stale snapshot

After a change to one input (a comment in `dev/perf/jank_snapshot_record.jank`), `bb boot:jank-snapshot` twice:

```
== run 1
SNAPSHOT-STALE key c39cdd2962cea5d0f7f12773c98761b7611b04a354fcb1323d343f044ef21d31 is not 9aaafc662f7efcf1a060f60bb41f46630ae62d5242d966f27f8f8b0769ac8c60
AOT-FRESH
SNAPSHOT-EVENTS 1452 bytes 748576
SNAPSHOT-BUILT 9aaafc662f7efcf1a060f60bb41f46630ae62d5242d966f27f8f8b0769ac8c60 in 364831 ms
SNAPSHOT-BOOT-MS 582.00829
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
== run 2
SNAPSHOT-FRESH 9aaafc662f7efcf1a060f60bb41f46630ae62d5242d966f27f8f8b0769ac8c60
SNAPSHOT-BOOT-MS 558.419392
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The stale key was detected before anything loaded, the snapshot was rebuilt (364,831 ms with the stage 1 objects already fresh), and the next run loaded it.

### The default (D-2)

`bb boot:jank` and `bb bench:jank` boot from the snapshot when it is fresh (`fresh-path` in `dev/perf/jank_snapshot_key.clj`: the same key) and otherwise print why and run the AOT boot of `core.clj` as before. Neither builds nor loads a stale snapshot; `bb boot:jank-snapshot` builds it. Measured 2026-11-02 ET, `d/jank-snapshot-default`, one chain on an idle box:

```
== boot:jank (stale)
SNAPSHOT-STALE key 9aaafc662f7efcf1a060f60bb41f46630ae62d5242d966f27f8f8b0769ac8c60 is not 525ad8536f6cf76d237374a707d96d812345e5fbd84276f47767f6431ce8e6c7 (bb boot:jank-snapshot builds it)
AOT-FRESH
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
== boot:jank-snapshot (build)
SNAPSHOT-BUILT 525ad8536f6cf76d237374a707d96d812345e5fbd84276f47767f6431ce8e6c7 in 371088 ms
== boot:jank (fresh)
SNAPSHOT-FRESH 525ad8536f6cf76d237374a707d96d812345e5fbd84276f47767f6431ce8e6c7
SNAPSHOT-BOOT-MS 580.373918
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

`bash dev/perf/jank_snapshot_time.sh` (its no-snapshot side runs the AOT boot directly, `run-main cohost.run -- --bench --quick`, what `bench:jank` ran before; the other side is `bb bench:jank --quick`, checked to print `SNAPSHOT-FRESH`). Each column's median is taken on its own:

```
RUN aot 1 boot-ms libs-ms sum: 10364.375912 9378.710642000195 19743.086554000194
RUN snapshot 1 boot-ms libs-ms sum: 501.703839 530.5053889998235 1032.2092279998235
RUN aot 2 boot-ms libs-ms sum: 9716.03025 8700.08130600024 18416.11155600024
RUN snapshot 2 boot-ms libs-ms sum: 733.175061 710.0212499997579 1443.1963109997578
RUN aot 3 boot-ms libs-ms sum: 11083.572973 9098.975416000001 20182.548389000003
RUN snapshot 3 boot-ms libs-ms sum: 580.445917 609.4266209998168 1189.8725379998168
MEDIAN aot boot-ms 10364.375912 libs-ms 9098.975416000001 sum 19743.086554000194
MEDIAN snap boot-ms 580.445917 libs-ms 609.4266209998168 sum 1189.8725379998168
```

| `bb bench:jank --quick`, median of 3 | boot-ms | libs-ms | sum |
|---|---:|---:|---:|
| before (AOT boot of core.clj) | 10,364.4 | 9,099.0 | 19,743.1 |
| after (fresh snapshot, the default) | 580.4 | 609.4 | 1,189.9 |

The cost moved to the build: about 6 minutes (371,088 ms here) after any change to an input, `src/cohost` included, paid by `bb boot:jank-snapshot`; until then `bb boot:jank` boots as before.

## -O3

Measured 2026-11-02 ET (lane D, D-3), `d/jank-o3` on `9627981`, research plan item 1 (S3-1). Every jank task that evaluates cohost now runs jank's JIT at `-O3`: `boot:jank` (both paths), `boot:jank-jit`, `bench:jank` (both paths), `boot:jank-aot-profile`, `boot:jank-snapshot`, through one helper in `bb.edn` (`jank!`). `COHOST_JANK_O=none` gives the command line without `-O`, as before; any other value (`-O0`, `-O2`) replaces the level. The snapshot's `compile-module` was `-O3` already, so on the default path (a fresh snapshot) the flag reaches only what the JIT compiles while the process runs: the bench's cases, the battery's forms, user code. The cohost CLI has no jank target (`bin/cohost`: `:cljrs :jank not wired yet`). The box is a fresh container since 10:20 AM ET: compare the pairs below with each other, not with earlier sections.

```
bash dev/perf/jank_snapshot_o3.sh            # bench --quick, {snapshot, AOT boot} x {none, -O3}, 3 each, interleaved
FULL=1 bash dev/perf/jank_snapshot_o3.sh     # bb bench:jank (the full bench) from the snapshot, {none, -O3}, 3 each
dev/perf/jank_snapshot_o3_probe.jank         # format and sort-by, 20 timed calls after 3 warm ones, per process
```

### Batteries

`bb boot:jank` (fresh snapshot, `-O3`): `booted as stock: 542   from host: 171   skipped: 78   errors: 0`, `battery: 143/145`, `libraries: 61/61`, the same eight library load errors. Every bench case returned the same value on all four sides of the matrix.

### boot-ms and libs-ms (median of 3)

| | boot-ms | libs-ms | sum |
|---|---:|---:|---:|
| snapshot, no -O (`--quick`) | 586.5 | 668.5 | 1,288.5 |
| snapshot, -O3 (`--quick`) | 652.3 | 710.9 | 1,363.2 |
| snapshot, no -O (full bench) | 650.8 | 675.6 | 1,338.9 |
| snapshot, -O3 (full bench) | 584.8 | 625.5 | 1,210.6 |
| AOT boot of core.clj, no -O (`--quick`) | 18,728.9 | 11,856.7 | 30,535.3 |
| AOT boot of core.clj, -O3 (`--quick`) | 33,658.0 | 16,884.3 | 51,695.6 |

From the snapshot the flag does not move startup beyond the run-to-run spread (two sets of three runs disagree in direction). Without a snapshot (the fallback when it is stale) the boot JIT-compiles `core.clj` and the libraries at `-O3`: 21.2 s more, as R's P6b found for the whole process.

### Bench cases (bb bench:jank, the full bench, from the snapshot, stock side, median of 3, ms)

| case | no -O | -O3 | ratio |
|---|---:|---:|---:|
| fn-call | 3.4 | 0.9 | 0.26 |
| loop-arith | 2.1 | 0.0 | 0.00 |
| hinted-loop | 1.7 | 0.0 | 0.01 |
| lazy-iterate | 8.9 | 6.0 | 0.67 |
| destructure | 33.0 | 19.1 | 0.58 |
| keyword-lookup | 19.6 | 13.4 | 0.69 |
| protocol | 80.4 | 62.7 | 0.78 |
| str-build | 77.0 | 63.6 | 0.83 |
| partition | 254.2 | 218.2 | 0.86 |
| atom-swap | 23.2 | 20.5 | 0.88 |
| lazy-pipeline | 221.3 | 196.9 | 0.89 |
| distinct | 478.2 | 426.6 | 0.89 |
| multimethod | 40.0 | 36.9 | 0.92 |
| conj-vector | 36.8 | 35.0 | 0.95 |
| set-build | 45.1 | 43.8 | 0.97 |
| regex | 344.7 | 342.2 | 0.99 |
| equality, pr-str, frequencies, sort, map-lookup, string-join | | | 1.00-1.02 |
| reduce-range | 19.2 | 20.5 | 1.07 |
| assoc-map | 56.5 | 60.3 | 1.07 |
| group-by | 71.8 | 77.8 | 1.08 |
| format | 159.0 | 176.6 | 1.11 |
| sort-by | 73.6 | 81.9 | 1.11 |
| transient-vector | 30.7 | 34.8 | 1.13 |
| into-vector | 30.5 | 34.8 | 1.14 |
| transduce | 17.7 | 22.1 | 1.25 |

The cases above 1.05 were checked against the spread of their three runs. `transduce`, `into-vector`, `transient-vector`: each run's value moves by up to 2x on both sides (stock `transduce` 17.7 / 26.7 / 13.9 without, 16.1 / 26.2 / 22.1 with; its host side 8.2 / 15.6 / 6.6 and 7.8 / 15.9 / 15.2): noise. `assoc-map`, `group-by`, `reduce-range`: the ranges overlap. `sort-by` and `format` went to the probe (median of 20 calls per process, three processes per level, ns):

```
format   none 120489657 122686171 132509020   -O1 140402957 211427235 126905263   -O2 138735614 133599220 140019454   -O3 161952661 151708867 144162371
sort-by  none  73077888  78821362  88413199   -O1  84080874  79800194  80563051   -O2  84737917  77948967  84492845   -O3  75592014  80963927  80923220
```

`sort-by` is the same at every level. **`format` is slower at every level, most at `-O3`**: medians 120-133 ms without, 144-162 ms with (+15 to +30%), the fastest calls 102-108 ms against 124-139. The case's code (cohost.shim's `jformat`, the adapter's `format`) is in AOT objects compiled `-O3` either way; what the flag changes for it is the JIT-compiled case fn and whatever the JIT compiles while it runs. The cause is not found.

### What the flag buys

Every call-heavy case: `fn-call` 3.8x, `loop-arith` and `hinted-loop` fold to nothing (the loop is a constant at `-O3`, as R's P6 found), `destructure` 1.7x, `keyword-lookup` 1.5x, `protocol` 1.3x: the user code that the JIT compiles. The library-heavy cases do not move: their code is the snapshot's, `-O3` already. One case, `format`, is slower.

## Type hints (H1)

Measured 2026-11-01 ET on jank `0.1-noble`. The commands ran on `67b4cbf2a03fe878586530f2c3f29b0bb8008f44`. The branch was then rebased onto `b61bf0e5856c78d06605e6c32682534a61edf32c`; those commits touch `doc/upstream/` and `doc/hosts/jank-FAST.md` only, so the boots were not run again. H0's ratios stand: a parameter hint pays when a loop inside the fn carries it (0.104536075688026 to 0.12509229668370744) and loses when the hinted fn is called from a loop (1.2439739613238427 to 1.5236294423788295). Return hints and let/loop local hints stay stripped.

JVM Clojure 1.12.0, `clojure -M -e` of the four shapes the battery now runs:

```
:long-double [1 -1]
:double-long [2.0 1.5]
:cce java.lang.ClassCastException
:loop [6 10 0]
:npe java.lang.NullPointerException
```

`(fn [^long x] x)` of 1.9 is 1 and of -1.9 is -1. `(fn [^double x] x)` of 2 is 2.0 and of 1.5 is 1.5. `(fn [^long x] x)` of `"a"` is ClassCastException, of nil is NullPointerException. `(fn [^long n] (loop [i n a 0] (if (pos? i) (recur (dec i) (+ a i)) a)))` of 3.9 is 6, of 4 is 10, of -1.2 is 0.

The codemod coerces a primitive `fn*` parameter on every host (`long`, `double`, `int`, `boolean`), so those rows match the JVM. A non-number does not reach jank's `long` or `double`: jank's conversion throws a native error that `catch` of an object does not see, and a real into `^long` or an integer into `^double` throws where the JVM truncates or widens. nil becomes a NullPointerException; anything else that is not a number becomes a ClassCastException. `:native-hints` defaults to nil. jank's map is `{long long, double double, boolean bool, int int}`. The native tag is passed through only when that map names the hint and the arity's body has `loop`, `loop*`, or `recur` outside a nested fn. The outer parameter stays untyped. One `let*` coerces, then one call enters `(fn* [^{:tag (quote long)} n] ...)`. A leaf stays coerced and untagged: H0's called-leaf ratios are above 1.

`bb boot:jvm` before:

```
cohost.boot on clj: 791 top-level forms
  booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0
battery: 138/139 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The one failure is the pre-existing row "reduce over an eduction; a :tag that is data". After the four rows:

```
cohost.boot on clj: 791 top-level forms
  booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0
battery: 142/143 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

Same failure, and no other. 138 + 4 = 142 and 139 + 4 = 143.

`bb boot:jank` before and after printed the same boot line. Battery went from 124/139 to 128/143. The fifteen failures are the same rows. Libraries stayed 30/61.

```
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 124/139 stock behaviors pass
libraries: 30/61 cases pass across 12 clojure.* libraries
```

```
cohost.boot on jank: 791 top-level forms
  booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 128/143 stock behaviors pass
libraries: 30/61 cases pass across 12 clojure.* libraries
```

`bb bench:jank` medians, host then stock. `loop-arith` and `reduce-range` are loops over unhinted `+`, `<`, `inc`, and `range`. H1 does not retag those. Both sides moved between the two runs.

before:

```
  loop-arith  host 1.45  stock 1.57  x1.08
  reduce-range  host 7.54  stock 11.14  x1.47
```

after:

```
  loop-arith  host 1.36  stock 1.38  x1.01
  reduce-range  host 5.39  stock 8.34  x1.54
```

`bb boot:cljrs` (cljrs 0.2.32), this session, after the change. No before line was recorded. The four new rows are not in the failure list. Boot errors stayed 0 and libraries stayed 0/61. cljrs's `fn` is a special form, so the codemod coerces a primitive parameter on the `fn` form itself; `:native-hints` is nil, so the form gets no native tag.

```
cohost.boot on cljrs: 791 top-level forms
  booted as stock: 561   from host: 95   skipped: 135   errors: 0
battery: 99/143 stock behaviors pass
libraries: 0/61 cases pass across 12 clojure.* libraries
```

Shared files: `src/cohost/adapter.cljc` (capability `:native-hints`, default nil), `src/cohost/rewrite.cljc` (coerce every primitive `fn*` parameter; native tag only when the arity loops), `src/cohost/battery.cljc` (four rows), `src/cohost/hosts/jank.jank` (one key). `doc/perf/jank.md` section "Type hints (H1)" only.

### Finish

The `fn` to `fn*` clause is gated. It runs only when `policy/host-overrides` `:special` contains `fn` (cljrs), and only when every parameter of every arity is a plain symbol and no arity opens with a condition map (a map followed by more body, the stock `fn` macro's test). A `:pre` / `:post` map or a destructured parameter stays as written, and every other host leaves `fn` for the stock macro. `fn*` coercion is unchanged.

Two more battery rows. `clojure -M -e` on Clojure 1.12.0 printed `:pre` and `6`.

`bb boot:jvm` after the gate and the two rows:

```
cohost.boot on clj: 791 top-level forms
  booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0
battery: 144/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The only failure is still "reduce over an eduction; a :tag that is data" (`class clojure.core.Eduction cannot be cast to class clojure.lang.IReduce`). The two new rows are not in the failure list. On this machine:

```
$ clojure -Sdescribe
{:version "1.12.6.1673"
 :config-files ["/usr/local/lib/clojure/deps.edn" "/home/ubuntu/.clojure/deps.edn" "deps.edn" ]
 :install-dir "/usr/local/lib/clojure"
 :config-dir "/home/ubuntu/.clojure"
 :cache-dir ".cpcache"
 :force false
 :repro false
 :main-aliases ""
 :repl-aliases ""}
$ clojure -M -e '(clojure-version)'
1.12.0
$ bb --version
babashka v1.13.229
```

`bb boot:jank` printed the same boot line (`542` stock, `171` host, `78` skipped, `0` errors). Battery `130/145`. Libraries `30/61`. The two new rows are not in the failure list.

`bb boot:cljrs` printed `561` stock, `95` host, `135` skipped, `0` errors. Battery `101/145`. Libraries `0/61`. The two new rows are not in the failure list.

`hinted-loop` is `(defn f [^long n] (loop [i 0 a 0] (if (< i n) (recur (inc i) (+ a i)) a)))` then `(f 200000)`, timed on both sides by `bb bench:jank`. Five runs with `:native-hints` as shipped (`{long long, double double, boolean bool, int int}`), then five runs with that key set to `nil` for the process and restored afterward (the committed adapter map is the shipped one). Each line is that run's median ms. The median of the five stock medians is the middle value.

shipped:

```
  hinted-loop  host 1.26  stock 1.26  x0.99
  hinted-loop  host 1.38  stock 1.44  x1.03
  hinted-loop  host 1.26  stock 1.27  x1.0
  hinted-loop  host 1.24  stock 1.25  x1.0
  hinted-loop  host 1.21  stock 1.24  x1.02
```

median of the five: host `1.26`, stock `1.26`.

`:native-hints` nil:

```
  hinted-loop  host 1.26  stock 2.36  x1.87
  hinted-loop  host 1.27  stock 2.25  x1.77
  hinted-loop  host 1.26  stock 2.35  x1.86
  hinted-loop  host 1.27  stock 2.45  x1.93
  hinted-loop  host 1.26  stock 2.46  x1.95
```

median of the five: host `1.26`, stock `2.36`. The host side is raw jank either way. The stock side is the rewritten `^long` parameter: with the native tag its median matches the host (`1.26`); without it the median is `2.36`.

## Boot profile

Measured 2026-11-01 ET on jank `0.1-noble`. Timers use the adapter's `:nano-time` and run only when `boot!` is called with `{:profile? true}`. `bb boot:jank` and `bb boot:jvm` do not pass that flag.

Three runs:

```
bash dev/perf/jank_boot_profile.sh
```

That script runs `bb boot:jank-profile` three times. Each run is `jank --module-path src run dev/perf/jank_boot_profile.jank`: `boot!` of `clojure/core.clj`, then `load-lib!` of the twelve `clojure.*` libraries. The total is the boot wall plus the library wall. Each run's eight phases sum to that run's total (`PROFILE-SUM`).

| phase | run 1 ns | run 2 ns | run 3 ns | median ns | share of median total |
|---|---:|---:|---:|---:|---:|
| reading | 2114299405 | 2341475412 | 2286907050 | 2286907050 | 4.2760% |
| codemod | 20834060807 | 21958852298 | 21993146691 | 21958852298 | 41.0585% |
| eval | 2314885223 | 2435137201 | 2515380825 | 2435137201 | 4.5532% |
| scan-macros | 228692736 | 268769340 | 271545356 | 268769340 | 0.5025% |
| setup | 1138629455 | 1178320078 | 1166652684 | 1166652684 | 2.1814% |
| companion | 146891167 | 150927407 | 148592623 | 148592623 | 0.2778% |
| library | 23139633836 | 22747687050 | 22989756563 | 22989756563 | 42.9861% |
| other | 2290665881 | 2400709659 | 2639081126 | 2400709659 | 4.4888% |
| total | 52207758510 | 53481878445 | 54011062918 | 53481878445 | |

`reading` is slurping and the reader, core only. `codemod` is `prepare` plus `rewrite`. `eval` is `eval-form` of each core form, including `after-eval` and inline capture. `scan-macros` is `scan-macros!` during the core boot. `setup` is `make-ctx`. `companion` and `library` are wall times of those sections (their own read, rewrite, eval, and scan stay inside the wall, not in the core rows). `other` is the boot wall plus the library wall minus the seven rows above. The sum of the eight median phase values is `53655377418`, which is `1.003244` times the median total `53481878445` (0.324% apart). Each run's own phases sum to its own total.

`other` on the three runs is 4.3876%, 4.4888%, and 4.8862% of that run's total.

Top 20 forms by eval time, from run 2 (its total is the median total). Columns are bucket, namespace, head, name, eval ns.

```
1  :library cohost.spec.alpha clojure.spec.alpha/def clojure.spec.alpha/def 3561444079
2  :library cohost.spec.gen.alpha lazy-prims lazy-prims 1846014381
3  :library cohost.spec.gen.alpha lazy-combinators lazy-combinators 1481867537
4  :library cohost.data extend-protocol extend-protocol 420050541
5  :library cohost.data defprotocol EqualityPartition 337970847
6  :library cohost.spec.alpha defonce registry-ref 278875865
7  :library cohost.spec.alpha defonce *compile-asserts* 263512475
8  :library cohost.spec.alpha defn map-spec-impl 258784110
9  :library cohost.test defmulti report 161065468
10 :core cohost.core definline booleans 151585604
11 :library cohost.spec.gen.alpha defonce dynalock 144785116
12 :library cohost.data extend extend 142631609
13 :library cohost.test defmethod report 125786955
14 :core cohost.core defonce *loaded-libs* 115259903
15 :core cohost.core defn float 110303732
16 :core cohost.core defn alength 109016083
17 :core cohost.core defn aclone 103644136
18 :core cohost.core def system-newline 97299679
19 :library cohost.test defmulti use-fixtures 96201746
20 :core cohost.core def not-every? 96044168
```

jank's profiler, one run of the same script. `perf` was not installed.

```
jank --profile --profile-output target/jank-boot.profile --module-path src run dev/perf/jank_boot_profile.jank
```

The file is enter/exit events. Exclusive time (a region's time not inside a nested region) sums to the span `62201025119` ns. Share is exclusive time over that span. Top costs:

| region | exclusive ns | share |
|---|---:|---:|
| jit eval_string | 44181125287 | 71.030% |
| eval ast node call | 12537306824 | 20.156% |
| rt eval_string | 1527764492 | 2.456% |
| eval ast node let | 887342863 | 1.427% |
| rt intern_keyword | 736827844 | 1.185% |
| rt read_string | 529038219 | 0.851% |
| rt macroexpand1 | 324251957 | 0.521% |
| eval ast node def | 203251919 | 0.327% |
| main | 143594071 | 0.231% |
| jit ctor | 129005823 | 0.207% |

That profiled run's own phase total was `54037433152` ns. The profiler span is larger because it includes process startup around `boot!`.

Step 6 stage 2 replaces booting the namespaces with loading a compiled snapshot. On run 2, reading + codemod + eval + scan-macros + companion + library is `49902848708` ns, `93.3080%` of that run's `53481878445` ns. `setup` is `1178320078` ns (`2.2032%`) and `other` is `2400709659` ns (`4.4888%`). The snapshot's load time was not measured.

Step 3's syntax-quote metadata pass is not its own profiler region. Exclusive time of the jit functions whose names contain meta, syntax, or quote is under `0.001%` each (`strip-meta` `492806` ns, `with-meta` `67690` ns, `carry-meta` `238658` ns, of the span `62201025119`). `rt macroexpand1` is `0.521%` exclusive and is every macroexpansion, not only the `:jank/source` maps. Those maps are built inside macroexpansion and then evaluated inside `jit eval_string` and `eval ast node call`, which the profile does not split further. No share is assigned to that pass beyond those measured regions.

## Runtime profile

Measured 2026-11-01 ET by lane M (jank-FAST Step 2, order M-3), on the same full
`bb bench:jank` run doc/PERF.md records as the jank baseline (muse/jank-step2 @ 573ee4d5).
The five worst cases of that table, by the stock core's ratio against jank's own core:
regex (x320.10), format (x49.57), into-vector (x30.32), sort-by (x27.45),
transduce (x21.68).

Method. A driver boots cohost, evaluates the case's setup once, evaluates the case's
body wrapped as `(fn [] body)` once -- the bench's own `side` shape, so the timed
calls are calls of a compiled fn -- and calls it R times at the bench's full size:

```
jank --profile --profile-output prof-<case>.profile --module-path src run-main probe.prof
```

The profile is jank's enter/exit scope log. Self time per scope name was aggregated
with a stack walk, and a boot-only run of the same driver (R = 0) was subtracted
scope by scope, so what remains is the case loop's share. Loop times under the
profiler run about 1.6x the bench medians; the shares are of the case-attributed
self time. One limit shapes every row: the profiler instruments jank's compiler and
runtime scopes, not the bodies of JIT-compiled functions. A compiled stock fn's
execution is a single unattributed block, reported here as the root scope
(`eval user code`) self time. What the profile can and cannot split is itself the
finding (lane G's boot profile above hit the same wall from the other side).

| case | reps | loop under profiler | execution inside the compiled stock fns | first-use JIT compilation (`jit eval_string`) | everything else |
|---|---:|---:|---:|---:|---:|
| regex | 12 | 26,674 ms | 81.8% | 17.3% (30 strings) | 0.9% |
| format | 60 | 16,962 ms | 71.4% | 27.3% (13 strings) | 1.3% |
| into-vector | 60 | 13,272 ms | 63.2% | 34.2% (19 strings) | 2.6% |
| sort-by | 30 | 13,739 ms | 82.8% | 12.5% (31 strings) | 4.7% |
| transduce | 80 | 15,529 ms | 79.8% | 17.8% (16 strings) | 2.4% |

Reading:

- The interpreter machinery is not the cost. Reader, macroexpansion
  (`rt macroexpand1`), var lookup (`rt find_var`) and the AST node scopes are
  under 1% in every case once the boot is subtracted. Nothing in Step 3 or 4
  should be aimed at eval itself.
- 63-83% of each case is native execution inside the compiled stock and shim
  fns: the shim's dispatch and the adapter's capability calls, which is what
  Step 3's items (type tests by tag, the instance guard as one C++ call, hot
  capability lookups read once) and Step 4's natives address. This profiler
  cannot split that block further; the case ratios in doc/PERF.md are the
  before/after measure for those steps.
- The rest, 12-34%, is JIT compilation of fns the boot alone never calls:
  13-31 `jit eval_string` compilations per case, triggered on first use inside
  the loop (for format, 13 compilations costing 27% -- the format call graph
  is compiled lazily and the fns are large). The bench's warm-up absorbs this
  in steady state, but a fresh process pays it on first use of each call
  graph; it is the runtime-profile counterpart of the boot profile's
  compile-heavy split, and it is what AOT (Step 6) removes by compiling
  ahead of time.

## Codemod

Measured 2026-11-01 ET on jank `0.1-noble`, branch `grok/jank-codemod`, after rebase onto `972ecc0`. Timers sum with `prof+` (`#?(:lpy + :default +')`). `bb boot:jank` does not pass `:profile?`. The phase commands are `bb boot:jank-profile` (JIT) and `bb boot:jank-aot-profile` (compile-module `-O3` of `cohost.run` when `cohost/run.o` is missing, then the same script with the AOT cache ahead of `src`). `rewrite.o` is produced. `cohost.hosts.jank` produces no object: its C++ namespace is `jank`, so `jank::runtime` resolves inward. The load path still has `src`.

### AOT vs JIT

Median of three runs. Share is the median phase over the median total. JIT totals were `52932129886`, `55342174973`, `64859700633`. AOT totals were `20507734657`, `20842052679`, `20866967972`.

| phase | JIT median ns | JIT share | AOT median ns | AOT share |
|---|---:|---:|---:|---:|
| reading | 2591613764 | 4.6829% | 528120085 | 2.5339% |
| codemod | 21578079728 | 38.9903% | 9051148855 | 43.4273% |
| eval | 2501915405 | 4.5208% | 1643867968 | 7.8873% |
| scan-macros | 254410073 | 0.4597% | 125291138 | 0.6011% |
| setup | 1295181762 | 2.3403% | 32870035 | 0.1577% |
| companion | 172866733 | 0.3124% | 92603210 | 0.4443% |
| library | 24899918816 | 44.9927% | 8908766388 | 42.7442% |
| other | 2219298611 | 4.0101% | 225563883 | 1.0823% |
| total | 55342174973 | | 20842052679 | |

The sum of the eight JIT median phases is `55513284892` (`1.003092` times the JIT median total). The sum of the eight AOT median phases is `20608231562` (`0.988781` times the AOT median total).

Compiling `rewrite.cljc` at `-O3` (as part of the `cohost.run` module graph) shrinks the codemod phase from `21578079728` ns to `9051148855` ns. The share does not shrink: the rest of the boot shrinks too, because `boot.o` and `shim.o` are on the same path. The codemod is still about 43% of the AOT boot.

### Per form

Top 20 after the plain let/loop shortcut, the JIT run whose total is the median of those three (`55653558913` ns, `bb boot:jank-profile`). Each line is the namespace, the form, codemod ns, then eval ns.

Core, top 20 by codemod:

```
1 cohost.core def *print-namespace-maps* 1714840303 35773585
2 cohost.core defn to-array 1400767107 268051
3 cohost.core defmacro fn 1237740082 3395154
4 cohost.core definline booleans 1069178055 162600206
5 cohost.core declare cat 899966317 137721
6 cohost.core defn- maybe-min-hash 897345680 1778955
7 cohost.core defn with-redefs-fn 855561342 1358233
8 cohost.core defn map 740133245 2729446
9 cohost.core def cons 607657256 293568
10 cohost.core def let 603366984 279577
11 cohost.core defn take 523396551 1078310
12 cohost.core def seq? 513635876 317677
13 cohost.core defn nil? 497176582 269009
14 cohost.core def first 486895047 290611
15 cohost.core defn distinct? 455449589 789717
16 cohost.core def list 448274465 140813
17 cohost.core def assoc 389664356 576069
18 cohost.core defmacro when 371115255 311739
19 cohost.core defmacro if-let 344709795 2581254
20 cohost.core defmacro doseq 334590581 10474808
```

Core, top 20 by eval:

```
1 cohost.core defn float 335985 303400353
2 cohost.core definline booleans 1069178055 162600206
3 cohost.core defonce *loaded-libs* 94533482 113476324
4 cohost.core def not-every? 143799 106719277
5 cohost.core defn unsigned-bit-shift-right 346908 103337204
6 cohost.core . . 91010 87445926
7 cohost.core defn bit-and-not 477913 83510638
8 cohost.core defn abs 421116 81929019
9 cohost.core defn bit-flip 333936 80054097
10 cohost.core defn bit-set 334213 79343998
11 cohost.core defn bit-test 340177 79266189
12 cohost.core defn bit-clear 285425 78447198
13 cohost.core add-doc-and-meta add-doc-and-meta 73758973 76303156
14 cohost.core def max-switch-table-size 60425986 38817487
15 cohost.core def global-hierarchy 123735 35784415
16 cohost.core def *print-namespace-maps* 1714840303 35773585
17 cohost.core defn alength 282619 31066507
18 cohost.core defn aclone 286557 29315559
19 cohost.core def system-newline 136754 27801363
20 cohost.core defmacro for 36954470 20903113
```

Library, top 20 by codemod:

```
1 cohost.spec.alpha clojure.spec.alpha/def 4120450907 4281638724
2 cohost.zip defn up 671250580 959085
3 cohost.test defmulti report 610331631 181654523
4 cohost.data defprotocol EqualityPartition 489187819 315969884
5 cohost.spec.alpha defn spec? 431245748 335572
6 cohost.spec.alpha defn- ns-qualify 431214313 975648
7 cohost.string defn replace 353495557 1646645
8 cohost.test defmethod report 348046648 143843289
9 cohost.spec.gen.alpha lazy-prims 285213403 2132092791
10 cohost.spec.gen.alpha lazy-combinators 255110400 1558821972
11 cohost.spec.alpha defn multi-spec-impl 227712433 6984835
12 cohost.spec.alpha defmacro every 199542204 6566720
13 cohost.data extend 181166067 142056749
14 cohost.spec.alpha defn map-spec-impl 167888596 281152367
15 cohost.data extend-protocol 148252629 389339154
16 cohost.test defn testing-vars-str 126977954 627147
17 cohost.test defn inc-report-counter 114818916 373433
18 cohost.spec.gen.alpha def quick-check-ref 95545791 62755532
19 cohost.string defn trimr 74646339 500019
20 cohost.string defn index-of 74617122 676907
```

Library, top 20 by eval:

```
1 cohost.spec.alpha clojure.spec.alpha/def 4120450907 4281638724
2 cohost.spec.gen.alpha lazy-prims 285213403 2132092791
3 cohost.spec.gen.alpha lazy-combinators 255110400 1558821972
4 cohost.data extend-protocol 148252629 389339154
5 cohost.spec.alpha defonce registry-ref 495782 332776382
6 cohost.spec.alpha defonce *compile-asserts* 621477 318273958
7 cohost.data defprotocol EqualityPartition 489187819 315969884
8 cohost.spec.alpha defn map-spec-impl 167888596 281152367
9 cohost.test defmulti report 610331631 181654523
10 cohost.spec.gen.alpha defonce dynalock 483356 173787221
11 cohost.test defmethod report 348046648 143843289
12 cohost.data extend 181166067 142056749
13 cohost.test defmulti use-fixtures 1677551 114525885
14 cohost.test defmulti assert-expr 2581549 94489387
15 cohost.spec.gen.alpha let 1400135 88717315
16 cohost.test defonce *load-tests* 365771 71803816
17 cohost.spec.gen.alpha def quick-check-ref 95545791 62755532
18 cohost.spec.alpha defn- re-explain 2911320 17661624
19 cohost.spec.alpha defn every-impl 40117861 15313499
20 cohost.spec.alpha defn tuple-impl 7435364 9508252
```

The one `clojure.spec.alpha/def` is the top-level form at `clojure/spec/alpha.clj` line 1792, `(clojure.spec.alpha/def ::kvs->map (conformer ...))`. On this run its codemod time is `4120450907` ns and its eval time is `4281638724` ns. The other two let/loop JIT runs were `3639273070` / `4183293804` and `2951190326` / `3277039076`. It is both sides: the macro expand (one AOT run timed that expand alone at `931466155` ns) and the eval of the expansion.

The early core defs (`*print-namespace-maps*`, `to-array`) are small forms. Their codemod time drops once `rewrite.o` is loaded: on an AOT run the top core codemod forms are `maybe-min-hash` and `with-redefs-fn`, not the first defs. That early JIT cost is compilation of the codemod, which is the AOT shrink above.

### Hot functions

`jank --profile` on the AOT boot (`--profile-output target/jank-aot-boot.profile`, module path `<aot-cache>:src`) does not name `cohost.rewrite` functions. Exclusive time sums to the span `24744627159` ns (mismatch 0). Top exclusive: `jit eval_string` `13605896260` (54.985%), `eval ast node call` `7316051899` (29.566%), `eval ast node let` `602219661` (2.434%), `rt read_string` `553738592` (2.238%), `rt intern_keyword` `506402492` (2.047%). That run's own phase total was `21868868078` ns. The AOT rewrite time sits inside `jit eval_string`, which is the evaluator calling into the compiled codemod.

Dispatch-branch timers in `rewrite.cljc` (`timed`, off unless `profile-branches` is set) give exclusive shares of one AOT profiled boot. Branch sum `11875764379` ns:

| branch | exclusive ns | share |
|---|---:|---:|
| macro | 8553172114 | 72.022% |
| call | 2211312726 | 18.620% |
| seq | 382407816 | 3.220% |
| symbol | 192632306 | 1.622% |
| head-var | 131909481 | 1.111% |

`macro` is the expand call, not the walk of the expansion (that walk is in the nested branches). One AOT run's expand times, same boot as the shape split: `let/plain` `767743194` ns / 334 calls, `let/vector` `337165901` / 31, `loop/plain` `108051164` / 44, `loop/vector` `365858076` / 9, `for` `852945285` / 2, `defn` `786553947` / 585, `clojure.spec.alpha/def` `931466155` / 1, `definline` `671322169` / 8.

JIT of this instrumented `rw-list` exited 139. The branch shares and the shortcut medians below are AOT runs.

### Plain let and loop

`rw-list` turns `(let [sym init ...] body)` and `(loop [sym init ...] body)` into `let*` / `loop*` without calling the macro, only when `codemod-expands?` is true (the codemod would have applied the macro; a `clojure.core` macro is still left for the host). Destructuring bindings stay on the macro. Functions added: `plain-bindings?`, `codemod-expands?`. The `let` and `loop` clauses of `rw-list` are the only dispatch changes.

Before, three AOT profile runs with the branch timers and no shortcut. Codemod ns `8515313920`, `9170745944`, `9187529144` (median `9170745944`). Totals `18576322213`, `20325561303`, `20562352550` (median `20325561303`).

After, three AOT profile runs. Codemod ns `8147647056`, `8304989494`, `8736504646` (median `8304989494`). Totals `17966559842`, `18305395228`, `18719317550` (median `18305395228`).

JIT, `bb boot:jank-profile`, before the shortcut. Codemod ns `21053071642`, `23456974265`, `21578079728` (median `21578079728`). Totals `52932129886`, `64859700633`, `55342174973` (median `55342174973`).

JIT after the shortcut. Codemod ns `21480690231`, `18429822657`, `22047010669` (median `21480690231`). Totals `55936739309`, `48299516673`, `55653558913` (median `55653558913`). The codemod median did not rise. The total median moved by less than the spread of the before runs. Kept.

`let/plain` and `loop/plain` are absent from the after expand list. `let/vector` remains (31 calls). `dev/perf/jank_let_eq.jank` compared rewrite to macroexpand after boot and printed DIFF with the rewrite still headed by `let`: that call resolved a `let` the codemod does not expand, so it did not take the shortcut. The boot profiles are the evidence the shortcut ran.

The shortcut is off on basilisp (`#?(:lpy false ...)`). Emitting `let*` there fails `clojure.spec.alpha` "registry and keys". The `timed` wrappers are gone: they expanded to their body, and a macro defined in `rewrite.cljc` does not expand under self-hosted ClojureScript. The jank medians above were measured with the shortcut on and the wrappers expanding to `do`.

### Dropped: plain defn

`defn-expansion` matched the booted macro for 407 core defns (87 left on the macro: pre/post or `:inline`). The name's `:tag` is dropped by the booted `defn`; `:rettag` is not on the fn form. JIT codemod median rose, so the clause was removed. First three `bb boot:jank-profile` runs: codemod `22618071864`, `25457352534`, `25004987907` (median `25004987907`). Second three, still on disk: codemod `22631906979`, `24654046293`, `22906084294` (median `22906084294`), totals `58832418566`, `64446882505`, `60141846272` (median `60141846272`). Both medians are above the let/loop codemod median `21480690231`. Building the expansion in the codemod is slower on jank's JIT than calling the macro.

### Dropped: keyword spec def

One form, `clojure.spec.alpha/def` of `::kvs->map`. A local resolve of that expansion deep-equaled the macro's resolved form on the JVM, including metadata. The JIT form did not get faster: the time is resolve plus the rewrite of the expansion, not `clojure.walk/postwalk`. After, three `bb boot:jank-profile` runs. Codemod ns `24097257229`, `23846725192`, `21810810846` (median `23846725192`). Totals `64709167082`, `61164084610`, `59790248185` (median `61164084610`). That form's rewrite ns was `4635287212`, `4009112964`, `4144651309` (median `4144651309`), against `4120450907` on the let/loop median-total run. The codemod median rose from `21480690231`. Removed. Functions removed: `spec-def-var?`, `spec-keep`, `spec-replace`, `spec-unfn`, `spec-res-sym`, `spec-walk`, `spec-res`, `spec-def-expansion`, and the `rw-list` clause.

### Not changed: for

`for` is two calls, `852945285` ns on the AOT expand split. The time is inside `clojure.core/for` in `resources/`. A replacement expansion would change the gensyms (`iter__`, `s__`, `i__`). `resources/` was not edited.

### Dropped: call-list rebuild

The call branch's exclusive share is the else of `rw-list` after nested walks: applying `:inline`, which is a syntax-quote the host has to compile. Replacing inlines would change output. `rw-call-list` built the same list, left to right, as `apply list`. Three `bb boot:jank-profile` runs: codemod `21860368937`, `26290761804`, `29611995727` (median `26290761804`), totals `56618900564`, `70082325538`, `74774599850` (median `70082325538`). The codemod median rose from `21480690231`. Restored `(apply list (rw ctx h) (doall (rw-all ctx args)))`.

The syntax-quote pass was not touched. The only rewrite shortcut kept is plain `let` / `loop`.

The `timed` macro now expands to its body. jank's JIT of the instrumented `rw-list` exited 139 on `run-main cohost.run` with the timers off. The shares above were captured before that expansion was removed.

Timers off, after that change. `bb boot:jvm`:

```
booted as stock: 465   from host: 202   natives: 66   skipped: 58   errors: 0
battery: 144/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The one JVM failure is `reduce over an eduction; a :tag that is data` (`Eduction` cannot be cast to `IReduce`). `jank --module-path src run-main cohost.run`:

```
booted as stock: 542   from host: 171   skipped: 78   errors: 0
battery: 143/145 stock behaviors pass
libraries: 61/61 cases pass across 12 clojure.* libraries
```

The two jank failures are `subs count nth first last butlast` (`Unterminated vector.`) and `Character outside ASCII` (`Unexpected end of file.`). `bb boot:jank` on the tip, after the defn, spec, and call-list shortcuts were removed, printed the same three lines: `542` / `171` / `78` / `0`, battery `143/145`, libraries `61/61`.

Step 8's program suite (fib, tak, nbody, and the rest) is `bb bench:programs`. The table is "Programs against JVM Clojure" in `doc/PERF.md` (JVM, jank-jit, jank-aot, cohost on jank, and clojurust).

## Step 5 H1: the coercion's fast path, and the :arglists strip (2026-11-02)

The H1 coercion in front of a hinted fn* arity now passes a value already of
the hinted kind on one host type test -- the shim's `long-kind?`/`double-kind?`,
bound once to the host core's own `integer?`/`double?` -- with the stock-core
conversion only otherwise. `bb bench:programs jank` warm medians (box load ~2,
ratios are the signal; G-13's baselines in parens): fib 258.5, fib-hinted 552.6
(x2.14; was x3.5); tak 17.95, tak-hinted 38.21 (x2.13; was x3.1). A shape
isolation through the same AOT boot prices the parts: no check 280 ms, the
kind test 554, the old (number? + stock long) shape 837, the real ^long fn 561.
The remaining jank gap is the one cross-module fn call itself (~100 ns per
entry); parity needs the test inlined as a native tag check in the emitted
form, which the codemod does not do today.

On the JVM the same work found why every hinted program was ABSENT: a defn's
var kept `{:tag long}` inside its `:arglists` (in the `(quote ...)` form the
defn macro emits), so the JVM compiler read the var as a primitive fn and
emitted checkcasts to `IFn$LO`/`IFn$LLLO` the coerced fn does not implement.
`strip-arglists` existed for exactly this, but `carry-meta`'s "unchanged"
test used `=`, which is metadata-blind: a strip whose only change is
metadata inside a meta value compares equal and was dropped. `carry-meta`
now tests `identical?`. After it: `bb bench:programs jvm-cohost` has no
ABSENT rows (six hinted programs were absent), tak-hinted's warm median
0.60 ms beats tak's 2.39, and fib-hinted runs (86.5 median, unsettled spread,
vs fib 10.95). `bb boot:jvm` battery 144/145 (the eduction row above fails
on the base too), libraries 61/61; `bb boot:jank` 143/145, 61/61.

## Programs on cohost-on-jank

C-2 (jank-FAST Step 8). nbody, mandelbrot, fannkuch and nsieve fail on cohost on jank, and they fail the same way on
jank's own core (`jank -O3 run`, and `compile-module -O3`): this is not a cohost bug but jank 0.1's, and cohost can
work around it in its codemod. Measured 2026-11-02 on jank 0.1-noble, cohost at `c499930`; programs from
`c/bench-programs` (`bb bench:programs jank`, doc/PERF.md "Programs against JVM Clojure").

**The cause (all four).** jank 0.1's C++ codegen fails on a `loop*` that binds a name an enclosing `loop*` binds:
`error: use of undeclared identifier 'sNN'`. The init does not matter (`(loop [a a] ...)`, `(loop [a (inc a)] ...)`,
`(loop [b 0 a a] ...)` all fail); `let` shadowing, and a `loop` rebinding a fn parameter, compile. Each program has
one such inner loop. The same form rebound through a fresh name, `(loop [x__1 x] (let [x x__1] ...))`, compiles and
gives the JVM's answer on raw jank and on cohost (one form per process: after a failed C++ compile, later forms in the
same cohost process failed to link, `JIT session error: Symbols not found: [ ...try_object... ]`, then `Failed to
find symbol: 'cohost_user$let_73_0'`).

**The owning file.** cohost's codemod: `src/cohost/rewrite.cljc`, `rw-let*` (every `loop*` cohost evaluates passes
through it, the user's `loop` after the stock macro expands it). The fix needs a capability, so it touches the shared
layer: `src/cohost/adapter.cljc` (`:loop-rebind-shadow?`, doc, default `false`, accessor), `src/cohost/boot.cljc`
(passes it to the rewrite options), and one key in `src/cohost/hosts/jank_host.jank` (lane M's file):
`:loop-rebind-shadow? true`. With it set, `rw-let*` rewrites `(loop* [a init ...] body)`, `a` bound by an enclosing
`loop*`, to `(loop* [a__N init ...] (let* [a a__N] body))` (recur is positional, so the loop is the same; the hint
meta moves to `a__N`). Every other host's default leaves its forms byte-identical. The patch is the commit
"C-2 proposed fix" on `c/jank-programs`, for the coordinator to order where it lands; with it:
`bb boot:jank` -> stock 542, host 171, skipped 78, errors 0; battery 143/145; libraries 61/61 (unchanged), and the
four programs run on cohost on jank with the JVM's results (below, small sizes: `bb bench:programs jank
"nbody:200,mandelbrot:40,fannkuch:6,nsieve:12"` -> -169027, 967, -77, 564, the same as `bb bench:programs jvm` at
those sizes).

### nbody

- Error (cohost on jank, `bb bench:programs jank nbody`): `Unable to compile C++ source. -- nil:7:1: error: use of
  undeclared identifier 's34'` (nbody-hinted the same). jank's own core first fails on `Math/sqrt` (`The symbol
  'Math/sqrt' could not be resolved`), so the G-13 note "runs on jank's own core" did not hold.
- The loops: `energy`'s `(loop [j (inc i) e e] ...)` inside `(loop [i 0 e 0.0] ...)`, and `advance`'s
  `(loop [j (inc i) bs bs b b] ...)` inside `(loop [i 0 bs bs] ...)`.
- Repro: `(loop [i 0 e 0.0] (if (= i 2) e (recur (inc i) (loop [j 0 e e] (if (= j 2) e (recur (inc j) (+ e 1.0)))))))`
  -> JVM `4.0`; raw jank `error: use of undeclared identifier 's31'`; cohost on jank the same.
- With the fix: -169027 at size 200, the JVM's.

### mandelbrot

- Error: `Unable to compile C++ source. -- nil:7:1: error: use of undeclared identifier 's36'` (hinted `'s38'`);
  jank's own core `use of undeclared identifier 's35'`.
- The loop: `(loop [x 0 acc acc] ...)` inside `(loop [y 0 acc 0] ...)`.
- Repro: `(loop [y 0 acc 0] (if (= y 2) acc (recur (inc y) (loop [x 0 acc acc] (if (= x 2) acc (recur (inc x) (inc acc)))))))`
  -> JVM `4`; raw jank `'s31'`; cohost on jank the same. Rebound: `4` on both.
- With the fix: 967 at size 40, the JVM's.

### fannkuch

- Error: `Unable to compile C++ source. -- nil:6:1: error: use of undeclared identifier 's28'` (hinted `'s30'`);
  jank's own core `'s28'`.
- The loop: `flips`' `(loop [p p i 0 j k] ...)` inside `(loop [p (vec p) f 0] ...)`.
- Repro: `(loop [p [1 0] f 0] (if (zero? (p 0)) f (recur (loop [p p i 0] (if (< i 1) (recur (assoc p 0 (p 1) 1 (p 0)) (inc i)) p)) (inc f))))`
  -> JVM `1`; raw jank `'s27'`; cohost on jank the same.
- With the fix: -77 at size 6, the JVM's.

### nsieve

- Error: `Unable to compile C++ source. -- nil:10:1: error: use of undeclared identifier 's61'` (hinted `'s63'`);
  jank's own core `'s56'`.
- The loop: `(loop [j (+ i i) flags flags] ...)` inside `(loop [i 2 cnt 0 flags flags] ...)`.
- Repro: `(loop [i 0 flags [true true]] (if (= i 2) flags (recur (inc i) (loop [j i flags flags] (if (>= j 2) flags (recur (+ j 2) (assoc flags j false)))))))`
  -> JVM `[false false]`; raw jank `'s30'`; cohost on jank the same.
- With the fix: 564 at size 12, the JVM's.

No bench program changes: the programs are portable Clojure; the failure is the host's, and the codemod is where
cohost absorbs it. Upstream: no draft in doc/upstream/ names this codegen bug yet (Step 7).

## Step 5 H1, the inline test (M-6b, 2026-11-02)

The kind test is emitted inline on jank through the `:prim-kind-test`
capability (default nil; every other host keeps the shim call):
`jank.runtime.is_integer` for a long hint, `jank.runtime.is_real` for a
double hint -- jank's own runtime predicates, visible from every module,
with exactly the kind semantics (a host long, never a bigint; a host
double, never a decimal). `bb bench:programs jank`, the one quiet run:
fib 262.26, fib-hinted 326.88 (x1.25; was x2.14 with the shim call);
tak 19.44, tak-hinted 21.68 (x1.12; was x2.13). The residual is the
`is_integer` call itself, ~24 ns per entry: it is a runtime-library
call, not an inlined tag read. The true inline read exists --
`(cpp/.get_type x)` returns the `object_type` -- but comparing it
through Clojure measured 4x slower than the call (the tag boxes on the
way into a Clojure value), and the enum-cast spellings resisted six
attempts. An emitted cpp call cannot name the adapter's cpp/raw block
either: a cpp namespace is visible only in its own module.

## Emission gaps (M-9, 2026-11-02)

A def whose value is an unnamed `fn*` carries the def's name onto the
`fn*` on jank (the `:defn-fn-name?` capability; jank's own `defn` emits
the same shape), so self-recursion is a direct call instead of a var
deref and a dynamic call. R's P6 (`JANK_O=3`, medians of 5): fib-stock
73,445,800 ns -> 19,823,645; fib-stock / fib-fn-def x3.57 -> x1.00.
Dotted def names are skipped (deftype's `Box.` constructor is not a fn
name; jank's codegen rejects it on a `fn*`). The semantic price is
pinned by the battery row "redefining a fn during its own recursion":
the JVM answers `:redefined` (the recursion goes through the var),
jank answers `:original` -- raw jank's own `defn` answer -- and the row
stands as a documented host fact. One codemod lesson from the same
work: a raw `(.method ...)` form inside rewrite.cljc breaks the boot
(the file is rewritten by itself); plain fn calls only there.

Of the shim statics, `Numbers-minus` is now the host's `-` on jank:
the wrapper checked nothing (the host's `-` throws on nil and on a
non-number, as the JVM's static does), so the static is the twin and
`host-aliases` fires. The divide family keeps its wrappers -- jank's
own `quot`/`rem`/`/` die with SIGFPE on a zero divisor where the JVM
throws `ArithmeticException` -- and max/min keep theirs (jank's
`(min 1 ##NaN)` answers 1.0; the JVM's static answers NaN).

`bb bench:jank` after all of it (two runs, host ms constant, stock ms
as printed): transient-vector x5.07 / x5.37 (was x14.07 in Step 4's
bench), transduce x11.28 / x11.14, into-vector x5.51 / x5.88,
set-build x4.22 / x4.34, group-by x1.97 / x2.60, frequencies x2.39 /
x2.25. Read with care: the bench's stock side still carries first-use
JIT compilation inside its timed loops (Step 3's profile put it at
12-34% of a case), and which case pays it moves when the emitted shape
of every stock fn changes -- into-vector's stock ms went 10.26 ->
~38 while its host ms never moved. The remaining gap in the
collection cases is the per-element call path through stock
`reduce`/transducer step fns, which neither the name nor the twins
reach; a steady-state per-case method (Step 8) is the instrument that
can price what is left.
