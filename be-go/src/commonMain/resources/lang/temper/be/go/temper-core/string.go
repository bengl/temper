package tempercore

import (
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

// IndexOf returns the index of the first occurrence of substr in s, or -1.
func IndexOf(s, substr string) int32 { return int32(strings.Index(s, substr)) }

// StringSlice returns a substring of s from start to end (rune-based).
func StringSlice(s string, start, end int32) string {
	runes := []rune(s)
	si := int(start)
	ei := int(end)
	if si < 0 {
		si = 0
	}
	if ei > len(runes) {
		ei = len(runes)
	}
	return string(runes[si:ei])
}

// RuneCount returns the number of Unicode code points in s.
func RuneCount(s string) int32 { return int32(utf8.RuneCountInString(s)) }

// StringIsEmpty returns true if the string has zero length.
func StringIsEmpty(s string) bool { return len(s) == 0 }

// ParseInt32 parses s as a base-radix int32. Returns (value, failed).
func ParseInt32(s string, radix int32) (int32, bool) {
	n, err := strconv.ParseInt(s, int(radix), 64)
	if err != nil || n < math.MinInt32 || n > math.MaxInt32 {
		return 0, true
	}
	return int32(n), false
}

// ParseInt64 parses s as a base-radix int64. Returns (value, failed).
func ParseInt64(s string, radix int32) (int64, bool) {
	n, err := strconv.ParseInt(s, int(radix), 64)
	if err != nil {
		return 0, true
	}
	return n, false
}

// Int64ToInt32 converts int64 to int32 with bounds checking. Returns (value, failed).
func Int64ToInt32(n int64) (int32, bool) {
	if n < math.MinInt32 || n > math.MaxInt32 {
		return 0, true
	}
	return int32(n), false
}

// RuneAt returns the Unicode code point at position i (rune index).
func RuneAt(s string, i int32) rune {
	idx := int32(0)
	for _, r := range s {
		if idx == i {
			return r
		}
		idx++
	}
	panic("string index out of range")
}
