package at.bestsolution.releng.distrobuilder;

public class DistroBuildException extends Exception {
    private static final long serialVersionUID = 1L;

    public DistroBuildException() {
    }

    public DistroBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    public DistroBuildException(String message) {
        super(message);
    }

    public DistroBuildException(Throwable cause) {
        super(cause);
    }

}
