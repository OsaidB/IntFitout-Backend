package life.work.IntFit.backend.exception;

public class NoUserFoundException extends RuntimeException {

    private static final long serialVersionUID = -3284993425429534536L; // Ensures class compatibility during serialization

    public NoUserFoundException(String username) {
        // Calls the parent constructor (RuntimeException) with a custom error message
        super(String.format("No user found with username '%s'.", username));
    }
}

