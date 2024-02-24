package pool.exceptions;

import com.jcraft.jsch.JSchException;

public class NoAvailableSessionException extends JSchException {
    private static final long serialVersionUID = -1L;

    public NoAvailableSessionException() {
        super();
    }

    public NoAvailableSessionException(String s) {
        super(s);
    }

    public NoAvailableSessionException(String s, Throwable e) {
        super(s, e);
    }
}
