package tempercore

import "fmt"

// TemperError is the error type produced by all Temper-generated error paths.
type TemperError struct {
	Message string
	Cause   error
}

func (e *TemperError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

func (e *TemperError) Unwrap() error { return e.Cause }

// NewError creates a TemperError with the given message.
func NewError(msg string) *TemperError { return &TemperError{Message: msg} }

// WrapError wraps an existing error as a TemperError.
func WrapError(msg string, cause error) *TemperError { return &TemperError{Message: msg, Cause: cause} }
