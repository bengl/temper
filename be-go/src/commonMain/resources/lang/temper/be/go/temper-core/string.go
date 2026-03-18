package tempercore

import (
	"strings"
	"unicode/utf8"
)

// IndexOf returns the index of the first occurrence of substr in s, or -1.
func IndexOf(s, substr string) int { return strings.Index(s, substr) }

// StringSlice returns a substring of s from start to end (rune-based).
func StringSlice(s string, start, end int) string {
	runes := []rune(s)
	if start < 0 {
		start = 0
	}
	if end > len(runes) {
		end = len(runes)
	}
	return string(runes[start:end])
}

// RuneCount returns the number of Unicode code points in s.
func RuneCount(s string) int { return utf8.RuneCountInString(s) }

// RuneAt returns the Unicode code point at position i (rune index).
func RuneAt(s string, i int) rune {
	idx := 0
	for _, r := range s {
		if idx == i {
			return r
		}
		idx++
	}
	panic("string index out of range")
}
