package com.perfplatform.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class StudentConflictException extends ResponseStatusException {

    private StudentConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public static StudentConflictException emailAlreadyExists(String email) {
        return new StudentConflictException("A student with email '" + email + "' already exists");
    }
}