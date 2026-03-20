package tempercore

import "math"

// IntMin returns the smaller of a and b.
func IntMin(a, b int32) int32 {
	if a < b {
		return a
	}
	return b
}

// IntMax returns the larger of a and b.
func IntMax(a, b int32) int32 {
	if a > b {
		return a
	}
	return b
}

// Int64Min returns the smaller of a and b.
func Int64Min(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

// Int64Max returns the larger of a and b.
func Int64Max(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

// IsNaN reports whether f is NaN.
func IsNaN(f float64) bool { return math.IsNaN(f) }

// IsInf reports whether f is infinite.
func IsInf(f float64) bool { return math.IsInf(f, 0) }
