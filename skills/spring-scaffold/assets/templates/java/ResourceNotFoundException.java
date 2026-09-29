public class ResourceNotFoundException extends BaseException {
    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorKind.NOT_FOUND, "Resource Not Found", resource + " not found: " + id);
    }
}
