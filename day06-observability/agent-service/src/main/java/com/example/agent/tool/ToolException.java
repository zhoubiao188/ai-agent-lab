package com.example.agent.tool;

public class ToolException extends RuntimeException {

    private final ErrorKind kind;

    public ToolException(ErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ErrorKind kind() {
        return kind;
    }
}
