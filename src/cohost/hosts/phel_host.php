<?php

// cohost on Phel: the JVM classes the Phel adapter (phel.phel, which loads
// this) builds from PHP: java.util.regex.Pattern over PCRE, and the
// java.lang.Math statics PHP lacks.

declare(strict_types=1);

namespace cohost\hosts\phel;

use Phel\Lang\TypeFactory;

// A Pattern keeps its JVM source and matches it with PCRE in UTF-8 mode; the
// offsets it reports are characters, as Phel's count and subs count them
// (PCRE's are bytes).
final class Pattern implements \Stringable
{
    /** @var array<string, Pattern> one Pattern per source: a literal evaluated again is the same object */
    private static array $cache = [];

    private ?int $groups = null;

    // the last subject and whether it is all ASCII (a byte offset is then a character offset)
    private ?string $subject = null;
    private bool $ascii = true;

    private function __construct(
        public readonly string $src,
        private readonly string $pcre,
        private readonly string $full,
    ) {}

    /** The Pattern of a JVM regex source, or null when PCRE cannot compile it. */
    public static function of(string $src): ?self
    {
        if (isset(self::$cache[$src])) {
            return self::$cache[$src];
        }
        $pcre = "\x01" . $src . "\x01u";
        if (@preg_match($pcre, '') === false) {
            return null;
        }
        return self::$cache[$src] = new self($src, $pcre, "\x01\\A(?:" . $src . ")\\z\x01u");
    }

    /** PCRE's message for a source it cannot compile. */
    public static function error(string $src): string
    {
        error_clear_last();
        @preg_match("\x01" . $src . "\x01u", '');
        $e = error_get_last();
        return $e === null ? preg_last_error_msg() : preg_replace('/^preg_match\(\): /', '', $e['message']);
    }

    public function __toString(): string
    {
        return $this->src;
    }

    public function groupCount(): int
    {
        if ($this->groups === null) {
            // an alternative that matches the empty string reports every group
            preg_match("\x01(?:" . $this->src . ")|\x01u", '', $m, PREG_UNMATCHED_AS_NULL);
            $this->groups = count(array_filter(array_keys($m), 'is_int')) - 1;
        }
        return $this->groups;
    }

    /** [start end groups spans] of the first match at or after character $from, or null. */
    public function find(string $s, int $from): mixed
    {
        $ascii = $this->ascii($s);
        $b = $ascii ? $from : strlen(mb_substr($s, 0, $from));
        if (preg_match($this->pcre, $s, $m, PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL, $b) !== 1) {
            return null;
        }
        return $this->result($s, $m, $ascii);
    }

    /** [0 (count s) groups spans] when the pattern matches all of $s, or null. */
    public function matchFull(string $s): mixed
    {
        if (preg_match($this->full, $s, $m, PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL) !== 1) {
            return null;
        }
        return $this->result($s, $m, $this->ascii($s));
    }

    private function ascii(string $s): bool
    {
        if ($this->subject !== $s) {
            $this->subject = $s;
            $this->ascii = !preg_match('/[\x80-\xff]/', $s);
        }
        return $this->ascii;
    }

    private function result(string $s, array $m, bool $ascii): mixed
    {
        $tf = TypeFactory::getInstance();
        $groups = [];
        $spans = [];
        $bounds = [];
        foreach ($m as $k => $g) {
            if (!is_int($k)) {
                continue;
            }
            [$text, $off] = $g;
            if ($text === null) {
                $groups[] = null;
                $spans[] = null;
                continue;
            }
            $start = $ascii ? $off : mb_strlen(substr($s, 0, $off));
            $end = $ascii ? $off + strlen($text) : $start + mb_strlen($text);
            $groups[] = $text;
            $spans[] = $tf->persistentVectorFromArray([$start, $end]);
            $bounds[] = [$start, $end];
        }
        // a group past the last one that took part is reported unmatched
        for ($i = count($groups), $n = $this->groupCount(); $i <= $n; $i++) {
            $groups[] = null;
            $spans[] = null;
        }
        return $tf->persistentVectorFromArray([
            $bounds[0][0], $bounds[0][1],
            $tf->persistentVectorFromArray($groups),
            $tf->persistentVectorFromArray($spans),
        ]);
    }
}

// A deftype's or defrecord's fields read as properties, as (.-f x) reads them:
// a struct's are protected, so PHP asks __get
interface Fields
{
    public function __get(string $name): mixed;
}

// java.lang.Math as the JVM defines it, over the bits of a double
final class JvmMath
{
    private const SIGN = PHP_INT_MIN;

    public static function bits(float $x): int
    {
        return unpack('q', pack('d', $x))[1];
    }

    public static function fromBits(int $b): float
    {
        return unpack('d', pack('q', $b))[1];
    }

    public static function getExponent(float $x): int
    {
        return ((self::bits($x) >> 52) & 0x7ff) - 1023;
    }

    public static function ulp(float $x): float
    {
        if (is_nan($x)) {
            return $x;
        }
        if (is_infinite($x)) {
            return INF;
        }
        $e = self::getExponent($x);
        if ($e === 1024) {
            return INF;
        }
        if ($e === -1023) {
            return 4.9E-324;
        }
        $e -= 52;
        return $e >= -1022 ? self::fromBits(($e + 1023) << 52) : self::fromBits(1 << ($e + 1074));
    }

    public static function nextUp(float $x): float
    {
        if (is_nan($x) || $x === INF) {
            return $x;
        }
        if ($x == 0.0) {
            return 4.9E-324;
        }
        $b = self::bits($x);
        return self::fromBits($x > 0 ? $b + 1 : $b - 1);
    }

    public static function nextDown(float $x): float
    {
        if (is_nan($x) || $x === -INF) {
            return $x;
        }
        if ($x == 0.0) {
            return -4.9E-324;
        }
        $b = self::bits($x);
        return self::fromBits($x > 0 ? $b - 1 : $b + 1);
    }

    public static function nextAfter(float $a, float $b): float
    {
        if (is_nan($a) || is_nan($b)) {
            return NAN;
        }
        if ($a == $b) {
            return $b;
        }
        return $b > $a ? self::nextUp($a) : self::nextDown($a);
    }

    public static function copySign(float $a, float $b): float
    {
        return self::fromBits((self::bits($a) & ~self::SIGN) | (self::bits($b) & self::SIGN));
    }

    public static function scalb(float $a, int $n): float
    {
        while ($n > 1023) {
            $a *= 8.98846567431158E307;
            $n -= 1023;
        }
        while ($n < -1022) {
            $a *= 2.2250738585072014E-308;
            $n += 1022;
        }
        return $a * self::fromBits(($n + 1023) << 52);
    }

    public static function cbrt(float $x): float
    {
        if (is_nan($x) || is_infinite($x) || $x == 0.0) {
            return $x;
        }
        $r = self::copySign(abs($x) ** (1 / 3), $x);
        // one Newton step lands where the JVM's cbrt does (27.0 is 3.0)
        return $r - ($r * $r * $r - $x) / (3.0 * $r * $r);
    }

    public static function IEEEremainder(float $a, float $b): float
    {
        if (is_nan($a) || is_nan($b) || is_infinite($a) || $b == 0.0) {
            return NAN;
        }
        if (is_infinite($b)) {
            return $a;
        }
        $r = $a - $b * round($a / $b, 0, PHP_ROUND_HALF_EVEN);
        return $r == 0.0 ? self::copySign(0.0, $a) : $r;
    }
}
