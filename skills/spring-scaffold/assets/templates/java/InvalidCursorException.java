public class InvalidCursorException extends BaseException {
    public InvalidCursorException(String cursor) {
        super(ErrorKind.INVALID_INPUT, "Invalid Cursor", "Invalid pagination cursor: " + cursor);
    }
}
