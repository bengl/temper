package tempercore

import "math"

// AddInt adds two ints and panics on overflow.
func AddInt(a, b int) int {
	result := a + b
	if (b > 0 && result < a) || (b < 0 && result > a) {
		panic("integer overflow")
	}
	return result
}

// SubInt subtracts b from a and panics on overflow.
func SubInt(a, b int) int {
	if (b < 0 && a > math.MaxInt+b) || (b > 0 && a < math.MinInt+b) {
		panic("integer overflow")
	}
	return a - b
}

// MulInt multiplies two ints and panics on overflow.
func MulInt(a, b int) int {
	if a == 0 || b == 0 {
		return 0
	}
	result := a * b
	if result/a != b {
		panic("integer overflow")
	}
	return result
}

// IsNaN reports whether f is NaN.
func IsNaN(f float64) bool { return math.IsNaN(f) }

// IsInf reports whether f is infinite.
func IsInf(f float64) bool { return math.IsInf(f, 0) }
