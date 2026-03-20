package tempercore

import "fmt"

// AppendImmutable returns a new slice with elem appended, leaving the original unchanged.
func AppendImmutable[T any](slice []T, elem T) []T {
	result := make([]T, len(slice)+1)
	copy(result, slice)
	result[len(slice)] = elem
	return result
}

// GetChecked returns the element at index i, panicking with a descriptive message on out-of-bounds.
func GetChecked[T any](slice []T, i int32) T {
	if i < 0 || int(i) >= len(slice) {
		panic(fmt.Sprintf("index %d out of bounds for slice of length %d", i, len(slice)))
	}
	return slice[i]
}
