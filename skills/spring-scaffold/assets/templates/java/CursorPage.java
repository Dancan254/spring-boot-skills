public record CursorPage<T>(List<T> content, String nextCursor, boolean hasNext) {

    public static <E, T> CursorPage<T> of(Window<E> window, Function<E, T> mapper) {
        String nextCursor = window.hasNext() && !window.isEmpty()
            ? PageCursor.encode(window.positionAt(window.size() - 1))
            : null;
        return new CursorPage<>(window.stream().map(mapper).toList(), nextCursor, window.hasNext());
    }
}
