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
