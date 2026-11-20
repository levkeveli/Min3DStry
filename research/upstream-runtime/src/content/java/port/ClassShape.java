package port;

public final class ClassShape {
    private ClassShape() {
    }

    public static boolean isAnonymous(Class<?> type) {
        return !type.isArray() && !type.isPrimitive() && type.getSimpleName().isEmpty();
    }
}
