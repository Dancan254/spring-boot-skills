public abstract class BaseException extends RuntimeException {

    private final ErrorKind kind;
    private final String title;

    protected BaseException(ErrorKind kind, String title, String message) {
        super(message);
        this.kind = kind;
        this.title = title;
    }

    public ErrorKind getKind() { return kind; }

    public String getTitle() { return title; }
}
