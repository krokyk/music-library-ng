package org.kroky.musiclib.provider;

public class ProviderException extends Exception {
    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public static String describe(Throwable throwable) {
        String message = cleanMessage(throwable.getMessage());
        Throwable rootCause = rootCause(throwable);
        String rootMessage = cleanMessage(rootCause.getMessage());
        if (rootCause != throwable) {
            String rootDetail = rootCause.getClass().getSimpleName();
            if (rootMessage != null) {
                rootDetail += ": " + rootMessage;
            }
            if (!rootDetail.equals(message)) {
                return message == null ? rootDetail : message + ": " + rootDetail;
            }
        }
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String cleanMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.trim();
    }
}
