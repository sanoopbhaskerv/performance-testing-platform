package com.perfplatform.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class StudentNotFoundException extends ResponseStatusException {

    private StudentNotFoundException(long studentId) {
        super(HttpStatus.NOT_FOUND, "Student " + studentId + " was not found");
    }

    public static StudentNotFoundException forId(long studentId) {
        return new StudentNotFoundException(studentId);
    }
}